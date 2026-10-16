(ns yin.vm.semantic
  "The register-shaped canonical evaluator: effects, continuations, FFI,
   module installation, bounded execution and custody-gate guards."
  (:require [dao.jing :as jing]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.telemetry :as telemetry]
            [yin.vm.values :as values]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.semantic.analysis :as analysis]))


(defrecord SemanticVM
  [blocked?       ; boolean, true if blocked
   bridge         ; explicit host-side FFI bridge state
   halted?        ; boolean, true when active continuation has completed
   k              ; continuation, a vector of frames (innermost last) or nil
   program        ; segment id of the last loaded batch
   control        ; {:segment id :pc n} or nil
   env            ; persistent lexical scope map
   free-env       ; checked initial environment, fixed for this instance
   window         ; sparse body-local register map
   id-counter     ; integer counter for unique IDs
   parked         ; parked continuations map
   primitives     ; primitive operations map
   primitive-profiles ; portable primitive profile projection
   primitive-canonical-names ; declared aliases for identity reverse lookup
   modules        ; module registry value
   make-stream    ; host-supplied stream constructor, or nil
   call-capacity  ; declared capacity of the FFI pair
   ready-queue    ; vector of runnable continuations
   store          ; heap memory map
   value          ; last computed value
   wait-set       ; vector of continuations waiting on a transport
   telemetry      ; telemetry config map ({:stream … :vm-id …}) or nil
   telemetry-step ; telemetry snapshot counter
   telemetry-t    ; telemetry transaction counter
   telemetry-eid  ; telemetry entity-id seed, floored at datom/first-user-id
   vm-model       ; telemetry model keyword
   vm-id          ; telemetry instance id, minted by telemetry/install
   code           ; {segment-id image}; built once per segment, never written
   code-aliases   ; {address segment-id}; additive, one address one id (UCF §7.3.4)
   gc])           ; {:since :base :threshold :pinned}: heap reclamation



(defn put-registers
  "Materialize control, window, environment and continuation; value is exit-only."
  [machine seg pc W E K]
  (assoc machine :control (when seg {:segment seg :pc pc})
         :window W :env E :k (when (seq K) K)
         :halted? (and (not (:blocked? machine)) (nil? seg))))


(defn- return-to
  "The one popped-frame delivery: retain exactly the saved window and write rd."
  [frame value]
  (assoc frame :window (assoc (:window frame) (:rd frame) value)))


(defn- act-payload
  [seg pc E W K rd facts]
  {:segment seg :pc (inc pc) :env E
   :window (select-keys W (analysis/saved facts (inc pc) rd))
   :k K :deliver {:deliver :rd :rd rd}})


(defn- entry-builders
  [payload]
  {:stream/put (fn [_state _effect result]
                 (assoc payload :reason :put :stream-id (:stream-id result)))
   :stream/next (fn [_state _effect result]
                  (assoc payload :reason :next :cursor-ref (:cursor-ref result)
                         :stream-id (:stream-id result)))
   :module/require (fn [_state _effect _result] payload)})


(defn- act-builders
  [seg pc E W K rd facts]
  (entry-builders (act-payload seg pc E W K rd facts)))


(defn- tail-builders
  [seg pc K]
  (entry-builders {:segment seg :site pc :k K :deliver {:deliver :return}}))


(defn- run-effect
  [machine seg pc W E K effect builders]
  (let [{:keys [state value blocked?]}
        (engine/handle-effect (put-registers machine seg pc W E K) effect
                              (when builders {:park-entry-fns builders}))]
    (if blocked?
      {:stop (assoc state :control nil :k nil)}
      {:continue [state value]})))


(defn register-restore
  "Re-park a retained FFI request, decode its response, then deliver exactly once."
  ([base entry] (register-restore base entry (:value entry)))
  ([base entry value]
   (if (:request-sent entry)
     (let [wait (cond-> (ffi/response-wait-entry entry (:call-id entry))
                  (and (:response-cursor entry) (:response-stream entry))
                  (assoc :cursor-ref {:type :cursor-ref :id (:response-cursor entry)}
                         :stream-id (:response-stream entry)))]
       (assoc base :wait-set (conj (vec (:wait-set base)) wait)
              :control nil :k nil :value :yin/blocked :blocked? true :halted? false))
     (let [call-id (:call-id entry)
           base (if call-id (update base :parked dissoc call-id) base)
           value (if call-id (ffi/call-result value call-id) value)]
       (case (get-in entry [:deliver :deliver])
         :rd (put-registers base (:segment entry) (:pc entry)
                            (assoc (:window entry) (get-in entry [:deliver :rd]) value)
                            (:env entry) (:k entry))
         :return (if-let [frame (peek (:k entry))]
                   (put-registers base (:segment frame) (:pc frame)
                                  (:window (return-to frame value))
                                  (:env frame) (pop (:k entry)))
                   (assoc (put-registers base nil nil {}
                                         (engine/without-store-of (:env base)) nil)
                          :value value))
         (throw (ex-info "Entry carries no delivery record" {:rule :deliver :entry entry})))))))


(defn- scheduler-round
  [machine]
  (engine/scheduler-round machine register-restore))


(defn- check-gate!
  [machine]
  (let [mode (vm/gate-mode machine)]
    (when (contains? #{:exporting :ended} mode)
      (throw (ex-info "Gate is closed" {:rule :gate-closed :yin.k/gate mode})))))


(defn- vm-hot*
  "Execute fuel instructions with the activation in locals; nil fuel runs to exit."
  [machine fuel]
  (if (and (nil? (:control machine)) (nil? (:k machine)))
    (scheduler-round machine)
    (let [{:keys [segment pc]} (:control machine)]
      (loop [machine machine seg segment pc pc W (:window machine)
             E (:env machine) K (or (:k machine) []) fuel fuel]
        (if (and fuel (zero? fuel))
          (put-registers machine seg pc W E K)
          (let [image (get (:code machine) seg)
                inst (nth (:vector image) pc)
                op (nth inst 0)
                remaining (when fuel (dec fuel))]
            (case op
              :const (recur machine seg (inc pc) (assoc W (nth inst 1) (nth inst 2)) E K remaining)
              :var (recur machine seg (inc pc)
                          (assoc W (nth inst 1)
                                 (engine/resolve-var E
                                                     (engine/active-store machine (engine/env-store-of E))
                                                     (:primitives machine) (:modules machine) (nth inst 2)))
                          E K remaining)
              :closure (recur machine seg (inc pc)
                              (assoc W (nth inst 1)
                                     (values/closure (:owner machine)
                                                     {:type :closure :params (vm/check-params! (nth inst 2))
                                                      :entry (nth inst 3) :segment seg :env E}))
                              E K remaining)
              :jump (recur machine seg (nth inst 1) W E K remaining)
              :branch-false (recur machine seg (if (get W (nth inst 1)) (inc pc) (nth inst 2)) W E K remaining)
              :define (let [value (get W (nth inst 3))
                            next-state (engine/put-active machine
                                                          (engine/env-store-of E) (nth inst 2) value)]
                        (recur next-state seg (inc pc) (assoc W (nth inst 1) value) E K remaining))
              :gensym (let [[id next-state] (engine/gensym machine (nth inst 2))]
                        (recur next-state seg (inc pc) (assoc W (nth inst 1) id) E K remaining))
              :store-get (recur machine seg (inc pc)
                                (assoc W (nth inst 1) (get (engine/active-store machine (engine/env-store-of E)) (nth inst 2)))
                                E K remaining)
              :store-put (let [next-state (engine/put-active machine
                                                             (engine/env-store-of E) (nth inst 2) (nth inst 3))]
                           (recur next-state seg (inc pc) (assoc W (nth inst 1) (nth inst 3)) E K remaining))
              :halt (assoc (put-registers machine nil nil {} (engine/without-store-of E) nil) :value (get W (nth inst 1)))
              :return (let [value (get W (nth inst 1))]
                        (if-let [frame (peek K)]
                          (recur machine (:segment frame) (:pc frame)
                                 (:window (return-to frame value)) (:env frame) (pop K) remaining)
                          (assoc (put-registers machine nil nil {} (engine/without-store-of E) nil) :value value)))
              :call (let [rd (nth inst 1) f (get W (nth inst 2))
                          args (mapv #(get W %) (nth inst 3)) tail? (nth inst 4)]
                      (case (engine/operator-kind machine f)
                        :closure (let [c (values/payload f)
                                       frame (when-not tail?
                                               {:type :return :segment seg :pc (inc pc) :env E
                                                :window (select-keys W (analysis/saved (:analysis image) (inc pc) rd)) :rd rd})]
                                   (recur machine (:segment c) (:entry c) {}
                                          (merge (:env c) (engine/bind-params (:params c) args))
                                          (if tail? K (conj K frame)) remaining))
                        :host-fn
                        (let [result (apply f args)
                              effect? (module/effect? result)
                              r (if effect?
                                  (run-effect (engine/check-callee-effect!
                                                (put-registers machine seg pc W E K) f result)
                                              seg pc W E K result
                                              (if tail? (tail-builders seg pc K)
                                                  (act-builders seg pc E W K rd (:analysis image))))
                                  {:continue [machine result]})]
                          (if-let [[next-state value] (:continue r)]
                            (let [E' (if effect? (:env next-state) E)
                                  K' (if effect? (or (:k next-state) []) K)
                                  W' (if effect? (:window next-state) W)]
                              (if tail?
                                (if-let [frame (peek K')]
                                  (recur next-state (:segment frame) (:pc frame)
                                         (:window (return-to frame value)) (:env frame) (pop K') remaining)
                                  (assoc (put-registers next-state nil nil {} (engine/without-store-of E') nil) :value value))
                                (recur next-state seg (inc pc) (assoc W' rd value) E' K' remaining)))
                            (:stop r)))
                        :continuation
                        (let [value (engine/continuation-argument args)
                              restored (register-restore machine (values/payload f) value)
                              control (:control restored)]
                          (recur restored (:segment control) (:pc control) (:window restored)
                                 (:env restored) (or (:k restored) []) remaining))))
              (:stream-make :stream-cursor :stream-close :stream-put :stream-next)
              (let [rd (nth inst 1)
                    effect (case op
                             :stream-make (module/make-effect :stream/make {:capacity (nth inst 2)})
                             :stream-cursor (module/make-effect :stream/cursor {:stream (get W (nth inst 2))})
                             :stream-close (module/make-effect :stream/close {:stream (get W (nth inst 2))})
                             :stream-put (module/make-effect :stream/put {:stream (get W (nth inst 2)) :val (get W (nth inst 3))})
                             :stream-next (module/make-effect :stream/next {:cursor (get W (nth inst 2))}))
                    r (run-effect machine seg pc W E K effect
                                  (when (contains? #{:stream-put :stream-next} op)
                                    (act-builders seg pc E W K rd (:analysis image))))]
                (if-let [[next-state value] (:continue r)]
                  (recur next-state seg (inc pc) (assoc (:window next-state) rd value)
                         (:env next-state) (or (:k next-state) []) remaining)
                  (:stop r)))
              :ffi-call
              (let [rd (nth inst 1) ffi-op (nth inst 2)
                    args (mapv #(get W %) (nth inst 3))
                    {:keys [call-in]} (ffi/require-call-pair! (:resources machine) ffi-op)
                    state (put-registers (engine/pin-refs machine args) seg pc W E K)
                    call-id (ffi/call-id state (engine/park-id state))
                    payload (act-payload seg pc E W K rd (:analysis image))
                    parked (engine/park-continuation state payload call-id)
                    request (apply2/request call-id ffi-op args)
                    result (ffi/put-request state call-in request)
                    entry (case (:dao.stream/outcome result)
                            :dao.stream/ok (ffi/response-wait-entry payload call-id)
                            :dao.stream/full (assoc payload :request-sent true :call-id call-id
                                                    :op ffi-op :reason :put
                                                    :stream-id vm/call-in-stream-key :datom request)
                            (throw (ex-info "FFI request could not be appended"
                                            {:op ffi-op :outcome (or (:dao.stream/outcome result)
                                                                     (:dao.stream.apply/outcome result))})))]
                (-> parked
                    (update :wait-set (fnil conj []) entry)
                    (assoc :control nil :k nil :value :yin/blocked :blocked? true :halted? false)))
              :current-continuation
              (let [rd (nth inst 1)
                    payload (assoc (act-payload seg pc E W K rd (:analysis image)) :type :reified-continuation)]
                (recur machine seg (inc pc) (assoc W rd (values/continuation (:owner machine) payload)) E K remaining))
              :park (-> (put-registers machine seg pc W E K)
                        (engine/park-continuation (act-payload seg pc E W K (nth inst 1) (:analysis image)))
                        (assoc :control nil :k nil))
              :resume (let [restored (engine/resume-continuation (put-registers machine seg pc W E K)
                                                                 (nth inst 1) (get W (nth inst 2)) register-restore)
                            control (:control restored)]
                        (if (:halted? restored) restored
                            (recur restored (:segment control) (:pc control) (:window restored)
                                   (:env restored) (or (:k restored) []) remaining)))
              (throw (ex-info "Unknown register instruction" {:op op})))))))))


(defn vm-hot
  "Execute fuel instructions, refusing a closed gate once at entry."
  [machine fuel]
  (check-gate! machine)
  (vm-hot* machine fuel))


(defn run-fuel
  "One budgeted action, followed by at most one bridge step. Fuel counts
   completed guest instructions per visited task, without a wall-clock bound."
  [machine n]
  (when-not (and (code/reg? n) (pos? n))
    (throw (ex-info "Fuel must be a positive exact integer" {:rule :fuel :fuel n})))
  (check-gate! machine)
  (let [v (if (some? (:control machine))
            (vm-hot* machine n)
            (engine/scheduler-round-fuel machine register-restore #(run-fuel % n)))]
    (if (and (:blocked? v) (ffi/installed? v))
      (:vm (ffi/bridge-step v))
      v)))


(defn- register-run-scheduler
  [machine]
  (engine/run-loop machine engine/active-continuation? #(vm-hot* % nil)
                   #(engine/resume-from-run-queue % register-restore)))


(defn- store-image
  [images image]
  (let [seg (:segment image)]
    (if-let [existing (find images seg)]
      (if (= (:address (val existing)) (:address image)) images
          (throw (ex-info "Cannot load segment: the id already holds different code" {:segment seg})))
      (assoc images seg image))))


(defn- store-alias
  [aliases image]
  (let [address (:address image) seg (:segment image)]
    (if-let [prior (find aliases address)]
      (if (= (val prior) seg) aliases
          (throw (ex-info "Cannot load segment: the address is already aliased to another id" {:address address :aliased-to (val prior)})))
      (assoc aliases address seg))))


(defn- admit-image
  [machine v stamp opts]
  (let [image (code/load-vector v stamp)
        seg (or (get (:code-aliases machine) (:address image)) (:id opts)
                (dec (vm/loaded-code-floor (:code machine))))]
    (assoc image :segment seg :length (count v) :analysis (analysis/analyze v))))


(defn load-vector
  "Validate and load a v4 vector; an existing address retains its local id."
  ([machine v stamp] (load-vector machine v stamp {}))
  ([machine v stamp opts]
   (let [image (admit-image machine v stamp opts) seg (:segment image)]
     (assoc machine :code (store-image (:code machine) image)
            :code-aliases (store-alias (:code-aliases machine) image)
            :program seg :control {:segment seg :pc 0} :window {} :k nil
            :halted? false :blocked? false :value nil))))


(defn attach-image
  "Attach a validated vector without changing execution fields."
  [machine v stamp]
  (let [image (admit-image machine v stamp {})]
    (assoc machine :code (store-image (:code machine) image)
           :code-aliases (store-alias (:code-aliases machine) image))))


(defn load-ast
  "Project AST datoms through the phase-3 loader."
  [machine datoms stamp]
  (vm/check-contract! vm/ast-contract stamp)
  (load-vector machine (:vector (linearize/project-datoms datoms)) code/contract))


(extend-type SemanticVM
  vm/IVM
  (step [machine] (check-gate! machine)
    (telemetry/emit-snapshot
      (if (engine/ready-for-ingress? machine) machine (vm-hot* machine 1)) :step))
  (run [machine] (check-gate! machine)
    (ffi/maybe-run machine register-run-scheduler))
  (eval [machine ast]
    (when ast (throw (ex-info "The semantic VM executes loaded vectors; use load-ast" {:ast ast})))
    (engine/restore-initial-env (:env machine) (vm/run machine)))
  (reset [machine]
    (assoc machine :control (when-let [seg (:program machine)] {:segment seg :pc 0})
           :window {} :k nil :value nil :halted? (nil? (:program machine)) :blocked? false))
  (halted? [machine] (engine/halted-with-empty-queue? machine))
  (blocked? [machine] (engine/vm-blocked? machine))
  (value [machine] (engine/vm-value machine))
  vm/IVMState
  (control [machine] (:control machine))
  (environment [machine] (:env machine))
  (store [machine] (:store machine))
  (continuation [machine] (:k machine)))


(defn create-vm
  "Create a new SemanticVM.

   Options:
     :env           initial lexical environment
     :primitives    primitive operations map
     :primitive-profiles published primitive profile registry
     :primitive-canonical-names name -> canonical name for intentional aliases
     :modules       module registry value (see `yin.vm.module`)
     :make-stream   (fn [capacity] -> create outcome); no default
     :call-in       explicit inbound request handle
     :call-out      explicit outbound response handle
     :call-out-cursor, :ffi-caller-id  `yin.vm/empty-state`'s
     :call-capacity capacity for a constructed FFI pair
     :bridge        host FFI handlers

   As for the walker: there is no `:in-stream` (program observation belongs
   to `dao.stream.observer`), and construction is all-or-nothing — the FFI
   pair and its call-out cursor are stream operations whose failure fails
   here."
  ([] (create-vm {}))
  ([opts]
   (when (contains? opts :in-stream)
     (throw (ex-info
              "Program observation moved to dao.stream.observer: a VM no longer accepts :in-stream"
              {:in-stream (:in-stream opts)})))
   (let [env (vm/check-bindings! :env (or (:env opts) {}))
         base (vm/empty-state
                (assoc (select-keys opts
                                    [:primitives :primitive-profiles
                                     :primitive-canonical-names :modules
                                     :make-stream :call-in :call-out
                                     :call-out-cursor :ffi-caller-id
                                     :call-capacity :link-request
                                     :link-response :origin :ancestry
                                     :capability-secret :secret-source
                                     :attach-stream :gc-threshold])
                       :telemetry (:telemetry opts)
                       :vm-model :semantic))]
     (-> (map->SemanticVM (merge base
                                 {:bridge nil,
                                  :program nil,
                                  :control nil,
                                  :env env,
                                  :free-env env,
                                  :window {},
                                  :k nil,
                                  :value nil,
                                  :code {},
                                  :code-aliases {},
                                  :halted? true,
                                  :blocked? false}))
         (telemetry/install :semantic)
         (ffi/attach (:bridge opts))
         (telemetry/emit-snapshot :init)))))


(extend-type SemanticVM
  module/IModuleKernel
  (link-format [_]
    {:format :yin.semantic/code, :contract code/contract})
  (spawn-module
    [vm image {:keys [modules origin ancestry capability-secret]}]
    (load-vector (create-vm
                   {:primitives (:primitives vm),
                    :primitive-profiles (:primitive-profiles vm),
                    :primitive-canonical-names (:primitive-canonical-names vm),
                    :modules modules,
                    :make-stream (:make-stream vm),
                    :link-request (get (:resources vm)
                                       module/link-request-resource),
                    :link-response (get (:resources vm)
                                        module/link-response-resource),
                    :origin origin,
                    :ancestry ancestry,
                    :capability-secret capability-secret,
                    :secret-source (:secret-source vm),
                    :attach-stream (:attach-stream vm),
                    :gc-threshold (:base (:gc vm))})
                 image
                 code/contract))
  (image-identity [_ image] (jing/segment-key image))
  (image-holds? [_ image segment] (jing/segment-matches? segment image))
  (attach-module [vm image] (attach-image vm image code/contract))
  (lift-closure [vm closure encode]
    (let [local (:segment closure)
          address (some (fn [[a l]] (when (= l local) a)) (:code-aliases vm))
          env (:env closure)
          store-of (engine/env-store-of env)]
      (when (nil? address)
        (throw (ex-info "Closure segment has no address"
                        {:yin.k/status :yin.k/non-portable,
                         :yin.k/kind :unrooted-body,
                         :segment local})))
      (cond-> {:yin.k/tag :yin.k/closure,
               :yin.k/binding :named,
               :yin.k/format :yin.semantic/code,
               :yin.k/segment address,
               :yin.k/entry (:entry closure),
               :yin.k/params (:params closure),
               :yin.k/env (into {}
                                (map (fn [[k x]] [k (encode x)]))
                                (engine/without-store-of env))}
        store-of (assoc :yin.k/store-of store-of))))
  (lower-closure [vm marker decode]
    (when-not (and (= :named (:yin.k/binding marker))
                   (= :yin.semantic/code (:yin.k/format marker)))
      (throw (ex-info "Closure binding or format does not match this kernel" {:reason :binding-mismatch :marker marker})))
    (let [local (get (:code-aliases vm) (:yin.k/segment marker))
          entry (:yin.k/entry marker)
          store-of (:yin.k/store-of marker)]
      ;; An entry outside the attached image names no code of it either.
      (when-not (and (some? local)
                     (nat-int? entry)
                     (< entry (count (get-in vm [:code local :vector]))))
        (throw (ex-info "Closure origin image is not attached"
                        {:reason :origin-not-attached,
                         :segment (:yin.k/segment marker)})))
      ;; the binders are those of a `:closure` tuple of the attached image
      ;; whose entry this is, never the wire's
      (vm/check-params! (:yin.k/params marker))
      (when-not (some (fn [inst]
                        (and (= :closure (nth inst 0))
                             (= entry (nth inst 3))
                             (= (:yin.k/params marker) (nth inst 2))))
                      (get-in vm [:code local :vector]))
        (engine/marker-mismatch! marker))
      (values/closure
        (:owner vm)
        {:type :closure,
         :params (:yin.k/params marker),
         :entry entry,
         :segment local,
         :env (cond-> (into {}
                            (map (fn [[k x]] [k (decode x)]))
                            (:yin.k/env marker))
                store-of (assoc engine/store-of-key store-of))})))
  (gc-roots [machine]
    {:kernel [(:control machine) (:k machine)]
     :values [(:free-env machine) (:env machine) (:window machine) (:value machine)]})
  (gc-children [_ _x] nil))
