(ns yin.vm.space
  "A CESK machine over the Universal AST whose configuration is reified as datoms.

  Executes canonical `:yin/*` AST datoms through `vm/IVM`: literals,
  closures, application with TCO, `if`, streams,
  park/resume/current-continuation, `dao.stream.apply` calls, and runtime
  macro expansion.

  What distinguishes it is not what it executes but what it leaves behind.
  Every component of the CESK configuration is *also* deposited as datoms,
  so one datalog query spans code and live machine state:

    - Control (C) — the program is genesis, loaded into the space at t=0 as
      the `:yin/*` datoms it executes. `:cfg/ctrl` names the node each step
      entered, so the control trace is queryable data (dead code is a set
      difference, not an instrumentation pass).
    - Environment (E) — lexical extension emits a `:frame/parent` chain
      with one `:bind/frame` + `:bind/name` + `:bind/addr` entity per
      binding, so \"which frames currently bind `n`?\" is a join.
    - Store (S) — each binding's value lands in a cell entity
      (`:cell/value` for scalars, `:cell/ref` for closures), attributed to
      the config that wrote it via `:cell/set-by`.
    - Kontinuation (K) — continuation frames are entities with `:k/tag` and
      `:k/next`, so the pending computation is inspectable without walking
      host data structures.
    - Time — each step appends under a fresh `t` and stamps `:cfg/step`, so
      the transaction axis *is* the step counter and `:as-of` recovers any
      past configuration.
    - Ownership — every datom the machine writes carries its reified owner
      entity in the `m` slot (see `dao.datom`: ids >= 1025 are metadata
      entity refs), so a space shared by several machines partitions by
      writer.

  The heap that holds host objects (streams, cursors, FFI plumbing) stays a
  Clojure map in `:store` — a `RingBufferStream` is not a tuple. The datom
  space mirrors the *lexical* machine state, which is what the query catalog
  in `yin.vm.space-test` pins down.

  Emitting the trace costs allocations, so it is opt-in per VM via
  `:trace?` (default true) the way telemetry is; semantics never depend on
  the trace.

  **What the deposit is, and is not.** `:space` is a plain in-memory vector
  of `[e a v t m]` tuples held in this record. It is not backed by
  `dao.jing`, nothing is persisted, and no covered index is published. The
  query helpers reach it through `dao.space.query`'s *source polymorphism*
  (`q`/`match`/`pull` accept a raw datom vector), which `dao.space.md` is
  explicit is \"an ergonomic property of the query function, not a second
  medium\" — so by that document's own criterion this machine's facts sit
  *outside* the tuple space. Two consequences worth knowing before relying
  on it:

    - Every query folds the whole vector into fresh indexes, so read cost is
      O(|space|) — seconds, once a long run has deposited ~10^6 datoms. The
      execution hot path never reads the space, so this cost lands on the
      querying side only. Publishing covered indexes via `dao.space.index`
      is the fix and is not wired up.
    - Passing another machine's `:space` at construction copies its datoms
      by value; it does not join a shared medium. `m` still partitions the
      result by writer, but neither machine sees the other's later writes.
      Real coordination belongs at the `dao.jing` layer, not here."
  (:require [dao.space.query :as query]
            [dao.stream :as ds]
            [dao.stream.apply :as dao.stream.apply]
            [yin.module :as module]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.macro :as macro]
            [yin.vm.semantic :as semantic]
            [yin.vm.telemetry :as telemetry]))


(declare space-vm-restore)
(declare space-expand-macro-call)


;; =============================================================================
;; SpaceVM Record
;; =============================================================================

(defrecord SpaceVM
  [blocked?   ; true if blocked
   bridge     ; explicit host-side FFI bridge state
   in-stream  ; ingress DaoStream carrying canonical datom programs
   in-cursor  ; ingress cursor position
   control    ; current control state {:type :node/:value, ...}
   datoms     ; canonical AST datoms
   env        ; lexical environment (host map, E)
   env-frame  ; eid of the frame entity mirroring `env` in the space
   halted?    ; true if execution completed
   id-counter ; unique ID counter
   index      ; {eid {attr value}} node index over `datoms`
   node-id-counter ; unique negative ID counter for AST nodes
   parked     ; parked continuations
   primitives ; primitive operations
   ready-queue ; vector of runnable continuations
   k        ; linked list of continuation frames (host, K)
   store    ; heap memory (host objects: streams, cursors, defs)
   value    ; final result value
   wait-set ; parked continuations waiting on streams
   macro-registry ; {macro-lambda-eid -> (fn [ctx] {:datoms [...]
   ;; :root-eid})}
   space      ; vector of [e a v t m] — the machine as datoms
   space-eid  ; next entity id for machine-state entities
   space-t    ; transaction axis / step counter
   cfg        ; eid of the config entity for the current step
   owner      ; reified owner entity, stamped as `m` on every datom written
   owner-name ; symbol naming the owner entity
   trace?     ; whether to deposit the machine-state trace
   telemetry  ; optional telemetry config
   telemetry-step ; telemetry snapshot counter
   telemetry-t ; telemetry transaction counter
   vm-model    ; telemetry model keyword
   ])


;; =============================================================================
;; C — the node index over canonical AST datoms
;; =============================================================================

(def ^:private cardinality-many-attrs
  "Attributes materialized as repeated datoms in the canonical stream."
  #{:yin/operands})


(defn- index-attr
  [idx e a v]
  (if (contains? cardinality-many-attrs a)
    (update-in
      idx
      [e a]
      (fn [existing]
        (if (vector? v) (into (or existing []) v) (conj (or existing []) v))))
    (assoc-in idx [e a] v)))


(defn- index-datoms
  "Build {eid {attr value}} over AST datoms, in log order."
  [datoms]
  (reduce (fn [idx [e a v _t _m]] (index-attr idx e a v)) {} datoms))


(defn- node-attr
  [index eid attr]
  (get-in index [eid attr]))


;; =============================================================================
;; The space — every write goes through `space-add`
;; =============================================================================

(defn- space-eid!
  "Allocate a machine-state entity id. Returns [eid vm]."
  [vm]
  [(:space-eid vm) (update vm :space-eid inc)])


(defn- space-add
  "Append assertion datoms (each an [e a v] triple) at the current `space-t`,
  stamped with this machine's owner entity as `m`. Nil values are skipped:
  a fact with no value is not a fact."
  [vm & triples]
  (let [t (:space-t vm)
        owner (:owner vm)]
    (update vm
            :space
            into
            (keep (fn [[e a v]] (when (some? v) [e a v t owner])) triples))))


(defn- trace-add
  "`space-add`, but only when this machine is tracing."
  [vm & triples]
  (if (:trace? vm) (apply space-add vm triples) vm))


;; =============================================================================
;; Values: closures carry their space identity so cells can ref them
;; =============================================================================

(defn- closure?
  [v]
  (and (map? v) (= :closure (:type v))))


(defn- value-triple
  "Choose the ref-valued or scalar-valued attribute for `v`, so datalog
  unifies through closure values without eid/number collisions."
  [e ref-attr val-attr v]
  (if (closure? v)
    [e ref-attr (:space-eid v)]
    ;; Host objects (streams, cursors, reified continuations) are not
    ;; datom values; only ground scalars enter the space.
    (when (or (number? v) (string? v) (boolean? v) (keyword? v) (symbol? v))
      [e val-attr v])))


;; =============================================================================
;; E and S — lexical extension emits a frame, one bind and one cell per name
;; =============================================================================

(defn- reify-frame
  "Mirror a lexical extension into the space: a frame entity chained to
  `parent`, plus a bind entity and a value cell per name. Returns
  [frame-eid vm]. When tracing is off this is a no-op returning the parent."
  [vm parent names values]
  (if-not (:trace? vm)
    [parent vm]
    (let [[frame vm] (space-eid! vm)
          vm (cond-> (space-add vm [frame :frame/parent parent])
               (:cfg vm) (space-add [frame :frame/created-by (:cfg vm)]))]
      (loop [vm vm
             names (seq names)
             values (seq values)]
        (if-not names
          [frame vm]
          (let [name (first names)
                v (first values)
                [cell vm] (space-eid! vm)
                [bind vm] (space-eid! vm)
                vm (apply space-add
                          vm
                          (keep identity
                                [(value-triple cell :cell/ref :cell/value v)
                                 (when (:cfg vm) [cell :cell/set-by (:cfg vm)])
                                 [bind :bind/frame frame] [bind :bind/name name]
                                 [bind :bind/addr cell]]))]
            (recur vm (next names) (next values))))))))


;; =============================================================================
;; K — continuation frames as entities
;; =============================================================================

(defn- reify-kont
  "Deposit a continuation frame entity for `tag`, linked to the entity of
  the frame it will return to. Returns [k-eid vm]."
  [vm tag next-eid]
  (if-not (:trace? vm)
    [nil vm]
    (let [[k vm] (space-eid! vm)]
      [k (space-add vm [k :k/tag tag] [k :k/next next-eid])])))


(defn- kont-eid
  "The space entity of a host continuation frame, if it has one."
  [k]
  (:space-eid k))


(defn- push-k
  "Build a host continuation frame and its space entity together, so `:k/next`
  mirrors `:next`."
  [vm frame]
  (let [[keid vm] (reify-kont vm (:type frame) (kont-eid (:next frame)))]
    [(assoc frame :space-eid keid) vm]))


;; =============================================================================
;; Config — one entity per step, so the trace itself is datoms
;; =============================================================================

(defn- reify-config
  "Deposit the configuration entity for the step about to run and make it
  the machine's current `:cfg`, so writes during the step attribute to it."
  [vm]
  (if-not (:trace? vm)
    vm
    (let [{:keys [control k env-frame]} vm
          [c vm] (space-eid! vm)
          eval? (= :node (:type control))
          vm (apply space-add
                    vm
                    (keep identity
                          [[c :cfg/mode (if eval? :eval :apply)]
                           [c :cfg/step (:space-t vm)]
                           (when eval? [c :cfg/ctrl (:id control)])
                           (when-not eval?
                             (value-triple c :cfg/val-ref :cfg/val (:val control)))
                           (when env-frame [c :cfg/env env-frame])
                           (when-let [ke (kont-eid k)] [c :cfg/kont ke])
                           (when (:cfg vm) [c :cfg/prev (:cfg vm)])]))]
      (assoc vm :cfg c))))


;; =============================================================================
;; The reduction relation
;; =============================================================================
;; A standard CESK reduction over the node vocabulary, with the space
;; deposits above threaded through it.

(defn- park-and-call
  "Park the current continuation stack and emit a dao.stream.apply request."
  [vm op args k env]
  (let [response-k {:type :dao.stream.apply/call, :next k}
        parked (engine/park-continuation vm {:k response-k, :env env})
        parked-id (get-in parked [:value :id])
        call-out (get-in parked [:store vm/call-out-stream-key])
        cursor-data (get-in parked [:store vm/call-out-cursor-key])
        cursor-pos (:position cursor-data)
        waiter-entry {:cursor-ref {:type :cursor-ref,
                                   :id vm/call-out-cursor-key},
                      :reason :next,
                      :stream-id vm/call-out-stream-key,
                      :k response-k,
                      :env env}
        _ (when (satisfies? ds/IDaoStreamWaitable call-out)
            (ds/register-reader-waiter! call-out cursor-pos waiter-entry))
        call-in (get-in parked [:store vm/call-in-stream-key])
        request (dao.stream.apply/request parked-id op args)
        _ (ds/append! call-in request)]
    (assoc (telemetry/emit-snapshot parked :bridge {:bridge-op op})
           :control nil
           :value :yin/blocked
           :blocked? true
           :halted? false)))


(defn- apply-closure
  "Enter a closure body: bind params, mirror the binding frame into the
  space, and push a :restore-env frame unless this is a tail call."
  [vm fn-val arg-vals env-call k tail?]
  (let [{params :params, body-node :body-node, env-clo :env, frame-clo :frame}
        fn-val
        new-env (merge env-clo (zipmap params arg-vals))
        [frame vm] (reify-frame vm frame-clo params arg-vals)]
    (if tail?
      (assoc vm
             :control {:type :node, :id body-node}
             :env new-env
             :env-frame frame
             :k k)
      (let [[restore vm] (push-k vm
                                 {:type :restore-env,
                                  :env env-call,
                                  :env-frame (:env-frame vm),
                                  :next k})]
        (assoc vm
               :control {:type :node, :id body-node}
               :env new-env
               :env-frame frame
               :k restore)))))


(defn- handle-node-eval
  [vm]
  (let [{:keys [control env k index store primitives]} vm
        node-id (:id control)
        attrs (get index node-id)]
    (if (nil? attrs)
      (throw (ex-info "Unknown node id in space VM" {:node-id node-id}))
      (let [node-type (:yin/type attrs)
            attr #(get attrs %)]
        (case node-type
          :literal (assoc vm :control {:type :value, :val (attr :yin/value)})
          :variable (let [name (attr :yin/name)
                          val (engine/resolve-var env store primitives name)]
                      (assoc vm :control {:type :value, :val val}))
          :lambda (let [[clo-eid vm] (if (:trace? vm) (space-eid! vm) [nil vm])
                        closure {:type :closure,
                                 :params (attr :yin/params),
                                 :body-node (attr :yin/body),
                                 :env env,
                                 :frame (:env-frame vm),
                                 :space-eid clo-eid}
                        vm (trace-add vm
                                      [clo-eid :clo/param
                                       (first (attr :yin/params))]
                                      [clo-eid :clo/params (attr :yin/params)]
                                      [clo-eid :clo/body (attr :yin/body)]
                                      [clo-eid :clo/env (:env-frame vm)])]
                    (assoc vm :control {:type :value, :val closure}))
          :if (let [[frame vm] (push-k vm
                                       {:type :if,
                                        :consequent (attr :yin/consequent),
                                        :alternate (attr :yin/alternate),
                                        :env env,
                                        :next k})]
                (assoc vm
                       :control {:type :node, :id (attr :yin/test)}
                       :k frame))
          :application (let [[frame vm] (push-k vm
                                                {:type :app-op,
                                                 :operands (attr :yin/operands),
                                                 :env env,
                                                 :tail? (attr :yin/tail?),
                                                 :next k})]
                         (assoc vm
                                :control {:type :node, :id (attr :yin/operator)}
                                :k frame))
          :dao.stream.apply/call
          (let [operands (or (attr :yin/operands) [])
                op (attr :yin/op)]
            (if (empty? operands)
              (park-and-call vm op [] k env)
              (let [[frame vm] (push-k vm
                                       {:type :dao.stream.apply/args-eval,
                                        :op op,
                                        :evaluated [],
                                        :operands operands,
                                        :next-idx 1,
                                        :env env,
                                        :next k})]
                (assoc vm
                       :control {:type :node, :id (first operands)}
                       :k frame))))
          ;; VM primitives
          :vm/gensym (let [prefix (or (attr :yin/prefix) "id")
                           [id vm'] (engine/gensym vm prefix)]
                       (assoc vm' :control {:type :value, :val id}))
          :vm/store-get
          (assoc vm :control {:type :value, :val (get store (attr :yin/key))})
          :vm/store-put (let [key (attr :yin/key)
                              val (attr :yin/value)]
                          (assoc vm
                                 :control {:type :value, :val val}
                                 :store (assoc store key val)))
          ;; Stream operations
          :stream/make
          (let [effect {:effect :stream/make, :capacity (attr :yin/buffer)}
                {:keys [state value]} (engine/handle-effect
                                        vm
                                        effect
                                        {:restore-fn space-vm-restore})]
            (assoc state :control {:type :value, :val value}))
          :stream/put (let [[frame vm] (push-k vm
                                               {:type :stream-put-target,
                                                :val-node (attr :yin/val-node),
                                                :env env,
                                                :next k})]
                        (assoc vm
                               :control {:type :node, :id (attr :yin/target)}
                               :k frame))
          :stream/cursor (let [[frame vm] (push-k vm
                                                  {:type :stream-cursor-source,
                                                   :env env,
                                                   :next k})]
                           (assoc vm
                                  :control {:type :node, :id (attr :yin/source)}
                                  :k frame))
          :stream/next
          (let [[frame vm]
                (push-k vm {:type :stream-next-cursor, :env env, :next k})]
            (assoc vm
                   :control {:type :node, :id (attr :yin/source)}
                   :k frame))
          :stream/close
          (let [[frame vm]
                (push-k vm {:type :stream-close-source, :env env, :next k})]
            (assoc vm
                   :control {:type :node, :id (attr :yin/source)}
                   :k frame))
          ;; Continuation primitives
          :vm/park (-> (engine/park-continuation vm {:k k, :env env})
                       (assoc :control nil))
          :vm/resume (let [[frame vm] (push-k vm
                                              {:type :resume-val,
                                               :parked-id (attr :yin/parked-id),
                                               :env env,
                                               :next k})]
                       (assoc vm
                              :control {:type :node, :id (attr :yin/val-node)}
                              :k frame))
          :vm/current-continuation
          (assoc vm
                 :control {:type :value,
                           :val {:type :reified-continuation, :k k, :env env}})
          ;; Runtime macro expansion
          :yin/macro-expand (space-expand-macro-call vm node-id attrs env k)
          (throw (ex-info "Unknown node type" {:node-type node-type})))))))


(defn- handle-return-value
  [vm]
  (let [{:keys [control k]} vm
        val (:val control)]
    (if (nil? k)
      (assoc vm
             :halted? true
             :value val)
      (let [frame k
            new-k (:next k)]
        (case (:type frame)
          :if (assoc vm
                     :control {:type :node,
                               :id (if val (:consequent frame) (:alternate frame))}
                     :env (:env frame)
                     :k new-k)
          :app-op (let [{operands :operands, env-call :env, tail? :tail?} frame
                        fn-val val]
                    (if (empty? operands)
                      (if (fn? fn-val)
                        (let [result (fn-val)]
                          (if (module/effect? result)
                            (let [{:keys [state value]} (engine/handle-effect
                                                          vm
                                                          result
                                                          {:restore-fn
                                                           space-vm-restore})]
                              (assoc state
                                     :control {:type :value, :val value}
                                     :env env-call
                                     :k new-k))
                            (assoc vm
                                   :control {:type :value, :val result}
                                   :env env-call
                                   :k new-k)))
                        (if (closure? fn-val)
                          (apply-closure vm fn-val [] env-call new-k tail?)
                          (throw (ex-info "Cannot apply non-function"
                                          {:fn fn-val}))))
                      (assoc vm
                             :control {:type :node, :id (first operands)}
                             :env env-call
                             :k {:type :app-args,
                                 :fn fn-val,
                                 :evaluated [],
                                 :operands operands,
                                 :next-idx 1,
                                 :env env-call,
                                 :tail? tail?,
                                 :space-eid (kont-eid frame),
                                 :next new-k})))
          :app-args
          (let [{fn-val :fn,
                 evaluated :evaluated,
                 operands :operands,
                 next-idx :next-idx,
                 env-call :env,
                 tail? :tail?}
                frame
                new-evaluated (conj evaluated val)]
            (if (= next-idx (count operands))
              (if (fn? fn-val)
                (let [result (apply fn-val new-evaluated)]
                  (if (module/effect? result)
                    (let [{:keys [state value blocked?]}
                          (engine/handle-effect
                            vm
                            result
                            {:restore-fn space-vm-restore,
                             :park-entry-fns
                             {:stream/put (fn [_s e r]
                                            {:k new-k,
                                             :env env-call,
                                             :reason :put,
                                             :stream-id (:stream-id r),
                                             :datom (:val e)}),
                              :stream/next (fn [_s _e r]
                                             {:k new-k,
                                              :env env-call,
                                              :reason :next,
                                              :cursor-ref (:cursor-ref r),
                                              :stream-id (:stream-id
                                                           r)})}})]
                      (if blocked?
                        (assoc state :control nil)
                        (assoc state
                               :control {:type :value, :val value}
                               :env env-call
                               :k new-k)))
                    (assoc vm
                           :control {:type :value, :val result}
                           :env env-call
                           :k new-k)))
                (if (closure? fn-val)
                  (apply-closure vm fn-val new-evaluated env-call new-k tail?)
                  (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
              (assoc vm
                     :control {:type :node, :id (nth operands next-idx)}
                     :env env-call
                     :k (assoc frame
                               :evaluated new-evaluated
                               :next-idx (inc next-idx)))))
          :dao.stream.apply/args-eval
          (let [{op :op,
                 evaluated :evaluated,
                 operands :operands,
                 next-idx :next-idx,
                 env-call :env}
                frame
                new-evaluated (conj evaluated val)]
            (if (= next-idx (count operands))
              (park-and-call vm op new-evaluated new-k env-call)
              (assoc vm
                     :control {:type :node, :id (nth operands next-idx)}
                     :env env-call
                     :k (assoc frame
                               :evaluated new-evaluated
                               :next-idx (inc next-idx)))))
          :dao.stream.apply/call (assoc vm
                                        :control {:type :value,
                                                  :val (:dao.stream.apply/value val)}
                                        :k new-k)
          :restore-env (assoc vm
                              :control control
                              :env (:env frame)
                              :env-frame (:env-frame frame)
                              :k new-k)
          ;; Stream continuation frames
          :stream-put-target (assoc vm
                                    :control {:type :node, :id (:val-node frame)}
                                    :k {:type :stream-put-val,
                                        :stream-ref val,
                                        :env (:env frame),
                                        :space-eid (kont-eid frame),
                                        :next new-k})
          :stream-put-val
          (let [effect
                {:effect :stream/put, :stream (:stream-ref frame), :val val}
                {:keys [state value blocked?]}
                (engine/handle-effect vm
                                      effect
                                      {:park-entry-fns
                                       {:stream/put (fn [_s _e r]
                                                      {:k new-k,
                                                       :env (:env frame),
                                                       :reason :put,
                                                       :stream-id
                                                       (:stream-id r),
                                                       :datom val})}})]
            (if blocked?
              (assoc state :control nil)
              (assoc state
                     :control {:type :value, :val value}
                     :env (:env frame)
                     :k new-k)))
          :stream-cursor-source (let [{:keys [state value]}
                                      (engine/handle-effect
                                        vm
                                        {:effect :stream/cursor, :stream val}
                                        {:restore-fn space-vm-restore})]
                                  (assoc state
                                         :control {:type :value, :val value}
                                         :env (:env frame)
                                         :k new-k))
          :stream-next-cursor (let [{:keys [state value blocked?]}
                                    (engine/handle-effect
                                      vm
                                      {:effect :stream/next, :cursor val}
                                      {:park-entry-fns
                                       {:stream/next
                                        (fn [_s _e r]
                                          {:k new-k,
                                           :env (:env frame),
                                           :reason :next,
                                           :cursor-ref (:cursor-ref r),
                                           :stream-id (:stream-id r)})}})]
                                (if blocked?
                                  (assoc state :control nil)
                                  (assoc state
                                         :control {:type :value, :val value}
                                         :env (:env frame)
                                         :k new-k)))
          :stream-close-source (let [{:keys [state value]}
                                     (engine/handle-effect
                                       vm
                                       {:effect :stream/close, :stream val}
                                       {:restore-fn space-vm-restore})]
                                 (assoc state
                                        :control {:type :value, :val value}
                                        :env (:env frame)
                                        :k new-k))
          :resume-val (engine/resume-continuation vm
                                                  (:parked-id frame)
                                                  val
                                                  (fn [new-state parked rv]
                                                    (assoc new-state
                                                           :k (:k parked)
                                                           :env (:env parked)
                                                           :control {:type :value,
                                                                     :val rv})))
          (throw (ex-info "Unknown frame type" {:frame frame})))))))


(defn- space-step
  "Execute one step: deposit the configuration, then transition."
  [vm]
  (let [vm (reify-config vm)
        vm' (if (= :value (:type (:control vm)))
              (handle-return-value vm)
              (handle-node-eval vm))]
    (update vm' :space-t inc)))


;; =============================================================================
;; Program loading
;; =============================================================================

(defn- adopt-datoms
  "Deposit canonical AST datoms into the machine's own space (genesis, t=0,
  stamped with this machine's owner) and refresh the node index."
  [vm new-datoms]
  (let [merged (into (vec (:datoms vm)) new-datoms)
        owner (:owner vm)]
    (assoc vm
           :datoms merged
           :index (reduce (fn [idx [e a v _t _m]] (index-attr idx e a v))
                          (or (:index vm) {})
                          new-datoms)
           :space (into (:space vm)
                        (keep (fn [[e a v _t _m]] (when (some? v) [e a v 0 owner]))
                              new-datoms)))))


(defn space-append-datoms
  "Append datoms to the VM's program without resetting execution state.
   Used by the macro expander to inject expansion output at runtime."
  [vm new-datoms]
  (adopt-datoms vm new-datoms))


(defn- space-expand-macro-call
  "Expand a :yin/macro-expand node at runtime. Returns the VM with the
   expansion appended and control pointing at the expansion root."
  [vm node-id attrs env k]
  (let [macro-registry (:macro-registry vm)
        index (:index vm)
        op-eid (:yin/operator attrs)
        arg-eids (or (:yin/operands attrs) [])
        get-attr (fn [eid a] (node-attr index eid a))
        by-entity (group-by first (:datoms vm))
        macro-lambda-eid
        (if (= :variable (get-attr op-eid :yin/type))
          (let [vname (get-attr op-eid :yin/name)]
            (or (get macro/default-name-registry vname)
                (macro/find-macro-lambda-by-name vname (:datoms vm) get-attr)
                op-eid))
          op-eid)
        macro-fn (get macro-registry macro-lambda-eid)
        eid-counter (macro/make-eid-counter! (:datoms vm))
        event-eid (swap! eid-counter dec)
        fresh-eid-fn (fn [] (swap! eid-counter dec))
        ctx {:get-attr get-attr,
             :by-entity by-entity,
             :arg-eids arg-eids,
             :fresh-eid fresh-eid-fn,
             :phase :runtime}
        result
        (if macro-fn
          (macro-fn ctx)
          (if (get-attr macro-lambda-eid :yin/macro?)
            (semantic/invoke-macro-lambda macro-lambda-eid ctx (:datoms vm))
            (throw (ex-info "No macro registered and lambda not found"
                            {:macro-lambda-eid macro-lambda-eid,
                             :call-eid node-id,
                             :registered-keys (keys macro-registry)}))))
        exp-datoms (:datoms result)
        exp-root (:root-eid result)
        evt-datoms (macro/expansion-event-datoms event-eid
                                                 node-id
                                                 macro-lambda-eid
                                                 exp-root
                                                 :runtime)
        marked-exp (macro/mark-with-provenance exp-datoms event-eid)
        updated-vm (adopt-datoms vm (vec (concat evt-datoms marked-exp)))]
    (assoc updated-vm
           :control {:type :node, :id exp-root}
           :env env
           :k k)))


(defn- space-vm-load-program
  "Load one datom transaction into the VM.

   Macro call sites are left standing: `:yin/macro-expand` nodes expand at
   runtime, when control reaches them, rather than up front at load. An
   interpreter can afford that laziness, and it is the coherent choice here
   — a macro sitting in a branch that never runs stays unexpanded and shows
   up as dead code in the same set-difference query as any other unvisited
   node."
  [vm datoms]
  (let [d (vec datoms)
        root-eid (:root-id (vm/index-datoms d))
        vm (adopt-datoms vm d)]
    (assoc vm
           :control {:type :node, :id root-eid}
           :k nil
           :env-frame nil
           :cfg nil
           :halted? false
           :value nil
           :blocked? false)))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- space-vm-restore
  ([base entry] (space-vm-restore base entry (:value entry)))
  ([base entry val]
   (assoc base
          :k (:k entry)
          :env (:env entry)
          :control {:type :value, :val val})))


(defn- resume-from-run-queue
  [state]
  (engine/resume-from-run-queue state space-vm-restore))


(defn- space-vm-run-on-stream
  [vm]
  (engine/run-on-stream vm
                        (:in-stream vm)
                        space-vm-load-program
                        (if (telemetry/enabled? vm)
                          (fn [state]
                            (telemetry/emit-snapshot (space-step state) :step))
                          space-step)
                        resume-from-run-queue
                        space-vm-restore))


(defn- space-vm-eval
  "Evaluate an AST. When ast is non-nil, converts to datoms and loads.
   When nil, resumes."
  [vm ast]
  (let [initial-env (:env vm)
        res (if ast
              (let [datoms (vm/ast->datoms ast
                                           {:id-start (:node-id-counter vm)})
                    min-id (apply min (map first datoms))]
                (-> (assoc vm :node-id-counter (dec min-id))
                    (space-vm-load-program datoms)
                    (vm/run)))
              (vm/run vm))]
    (engine/restore-initial-env initial-env res)))


(defn- space-vm-reset
  "Reset execution state to initial baseline, preserving the loaded program."
  [vm]
  (let [root-id (when (seq (:datoms vm))
                  (:root-id (vm/index-datoms (:datoms vm))))]
    (assoc vm
           :control (when root-id {:type :node, :id root-id})
           :k nil
           :env-frame nil
           :cfg nil
           :halted? (nil? root-id)
           :value nil
           :blocked? false)))


;; =============================================================================
;; SpaceVM Protocol Implementation
;; =============================================================================

(extend-type SpaceVM
  vm/IVM
  (step [vm]
    (telemetry/emit-snapshot (engine/step-on-stream vm
                                                    (:in-stream vm)
                                                    space-vm-load-program
                                                    space-step)
                             :step))
  (run [vm] (ffi/maybe-run vm space-vm-run-on-stream))
  (eval [vm ast] (space-vm-eval vm ast))
  (reset [vm] (space-vm-reset vm))
  (halted? [vm] (engine/halted-with-empty-queue? vm))
  (blocked? [vm] (engine/vm-blocked? vm))
  (value [vm] (engine/vm-value vm))
  vm/IVMState
  (control [vm] (:control vm))
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm]
    (when-let [k-head (:k vm)]
      (loop [k k-head
             acc []]
        (if (nil? k) acc (recur (:next k) (conj acc k)))))))


(defn create-vm
  "Create a new SpaceVM.
   Accepts {:env map, :primitives map, :macro-registry map, :bridge handlers,
            :telemetry config, :space vec, :eid-base int, :owner-name sym,
            :trace? bool}.

   :space     seed datoms to start from (default []), copied by value. This
              does not join a shared medium: the new machine sees a snapshot
              of those datoms and nothing either machine writes afterward.
   :eid-base  start of this machine's machine-state entity range (default
              2048; keep ranges disjoint when seeding one machine from
              another so `m` stays an unambiguous writer tag, and >= 1025 so
              `m` refs a metadata entity — see dao.datom).
   :trace?    deposit the machine-state trace (default true)."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})
         base (vm/empty-state {:primitives (:primitives opts),
                               :telemetry (:telemetry opts),
                               :vm-model :space})
         bridge-state (ffi/bridge-from-opts opts)
         in-stream (:in-stream opts)
         eid-base (or (:eid-base opts) 2048)
         owner-name (or (:owner-name opts) 'machine)
         trace? (if (contains? opts :trace?) (:trace? opts) true)
         owner eid-base
         vm (map->SpaceVM
              (merge base
                     {:bridge bridge-state,
                      :in-stream in-stream,
                      :in-cursor {:position 0},
                      :control nil,
                      :env env,
                      :env-frame nil,
                      :k nil,
                      :datoms [],
                      :index {},
                      :space (vec (:space opts)),
                      :space-eid (inc eid-base),
                      :space-t 0,
                      :cfg nil,
                      :owner owner,
                      :owner-name owner-name,
                      :trace? trace?,
                      :halted? true,
                      :value nil,
                      :blocked? false,
                      :node-id-counter -1024,
                      :macro-registry (or (:macro-registry opts) {})}))]
     (-> (space-add vm [owner :agent/name owner-name])
         (telemetry/install :space)
         (telemetry/emit-snapshot :init)))))


;; =============================================================================
;; Query utilities — the space is the point
;; =============================================================================

(defn machine-space
  "The machine's datoms: program (C) plus the reified configuration trace.

   A plain vector, suitable as a `dao.space.query` source. Folding it is
   O(count), so hoist it out of loops rather than re-querying per result."
  [vm]
  (:space vm))


(defn find-by-type
  "Find all AST entity IDs with the given :yin/type value."
  [vm t]
  (map first
       (query/q '[:find ?e :in $ ?t :where [?e :yin/type ?t]] (:space vm) t)))


(comment
  ;; The whole machine — C, E, S, K, trace — as one queryable space.
  (require '[yang.clojure :as yang])
  (def r
    (-> (create-vm)
        (vm/eval (yang/compile '((fn [f n] (if (< n 2) 1 (* n (f f (- n 1)))))
                                 (fn [f n] (if (< n 2) 1 (* n (f f (- n 1)))))
                                 5)))))
  (vm/value r) ; => 120
  (def space (machine-space r))
  ;; [1] C is datoms: the program answers AST-shaped queries, and is
  ;;     genesis — visible before the first step ran.
  (query/q '[:find ?e :where [?e :yin/type :lambda]] space)
  (query/q '[:find ?e :where [?e :yin/type :lambda]] space {:as-of 0})
  ;; [2] One q spans code AND state: variable nodes joined to live
  ;; bindings.
  (query/q '[:find ?v :where [?var :yin/type :variable] [?var :yin/name ?nm]
             [?b :bind/name ?nm] [?b :bind/addr ?a] [?a :cell/value ?v]]
           space)
  ;; [3] Provenance: why does a cell hold 5? The write names its config.
  (query/q '[:find ?s :where [?a :cell/value 5] [?a :cell/set-by ?cfg]
             [?cfg :cfg/step ?s]]
           space)
  ;; [4] Dead code = control-trace set difference, no instrumentation.
  (let [entered (set (map first
                       (query/q '[:find ?n :where [_ :cfg/ctrl ?n]] space)))]
    (remove entered
      (map first (query/q '[:find ?e :where [?e :yin/type _]] space))))
  ;; [5] Ownership: every datom's m is the machine's reified owner entity.
  (set (map peek space))
  (query/pull space 2048 [:agent/name]))
