(ns yin.vm.semantic
  (:require [dao.db :as db]
            [yin.module :as module]
            [yin.scheduler :as scheduler]
            [yin.stream :as stream]
            [yin.vm :as vm]))


;; =============================================================================
;; Semantic VM
;; =============================================================================
;;
;; Interprets datom 5-tuples [e a v t m] (from vm/ast->datoms) by graph
;; traversal. Looks up node attributes via entity ID scanning the datom set.
;;
;; Continuations are an explicit stack (vector of frames):
;;   [{:type :app-op}, {:type :app-args}, {:type :if}, {:type :restore-env}]
;; Control is {:type :node, :id eid} or {:type :value, :val v} (two-phase
;; dispatch: handle return values vs evaluate nodes).
;;
;; Proves the same computation can be recovered purely from the datom stream
;; without reconstructing the original AST maps.
;; =============================================================================


(def ^:private cardinality-many-attrs
  "Attributes materialized as repeated datoms in DataScript."
  #{:yin/operands})


(defn find-by-type
  "Find all nodes of a given type. db must satisfy IDaoDb."
  [db node-type]
  (db/find-eids-by-av db :yin/type node-type))


(defn get-node-attrs
  "Get all attributes for a node as a map. db must satisfy IDaoDb."
  [db node-id]
  (db/entity-attrs db node-id))


(defn- datom-node-attrs
  "Get node attributes directly from indexed datoms."
  [index node-id]
  (reduce (fn [m [e a v _t _m]]
            (if (contains? cardinality-many-attrs a)
              (if (vector? v)
                (update m a (fnil into []) v)
                (update m a (fnil conj []) v))
              (assoc m a v)))
    {}
    (get index node-id)))


(defn find-applications
  "Find all function applications in the db."
  [db]
  (find-by-type db :application))


(defn find-lambdas
  "Find all lambda definitions in the db."
  [db]
  (find-by-type db :lambda))


(defn find-variables
  "Find all variable references in the db."
  [db]
  (find-by-type db :variable))


;; =============================================================================
;; VM Records
;; =============================================================================

(defrecord SemanticVM
  [control    ; current control state {:type
   ;; :node/:value, ...}
   env        ; lexical environment
   stack      ; continuation stack
   datoms     ; AST datoms
   index      ; Entity index {eid [datom...]}
   halted     ; true if execution completed
   value      ; final result value
   store      ; heap memory
   parked     ; parked continuations
   id-counter ; unique ID counter
   primitives ; primitive operations
   blocked    ; true if blocked
   run-queue  ; vector of runnable continuations
   wait-set   ; vector of parked continuations waiting
   ;; on streams
   node-id-counter ; unique negative ID counter for AST nodes
  ])


;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================


(defn- gen-id-fn
  [id-counter]
  (fn [prefix] (keyword (str prefix "-" id-counter))))


(defn- resume-entries-with-nil [entries] (mapv #(assoc % :value nil) entries))


(defn- handle-return-value
  [vm]
  (let [{:keys [control env stack datoms index store id-counter]} vm
        val (:val control)]
    (if (empty? stack)
      (assoc vm
        :halted true
        :value val)
      (let [frame (peek stack)
            new-stack (pop stack)]
        (case (:type frame)
          :if (let [{:keys [cons alt env]} frame]
                (assoc vm
                  :control {:type :node, :id (if val cons alt)}
                  :env env
                  :stack new-stack))
          :app-op
            (let [{:keys [operands env]} frame
                  fn-val val]
              (if (empty? operands)
                ;; 0-arity call
                (if (fn? fn-val)
                  (let [result (fn-val)]
                    (if (module/effect? result)
                      (case (:effect result)
                        :vm/store-put
                          (assoc vm
                            :control {:type :value, :val (:val result)}
                            :store (assoc store (:key result) (:val result))
                            :env env
                            :stack new-stack)
                        :stream/make
                          (let [[stream-ref new-state]
                                  (stream/handle-make vm
                                                      result
                                                      (gen-id-fn id-counter))]
                            (assoc new-state
                              :control {:type :value, :val stream-ref}
                              :env env
                              :stack new-stack
                              :id-counter (inc id-counter)))
                        (throw (ex-info "Unhandled 0-arity effect"
                                        {:effect result})))
                      (assoc vm
                        :control {:type :value, :val result}
                        :env env
                        :stack new-stack)))
                  (if (= :closure (:type fn-val))
                    (let [{:keys [body-node env]} fn-val]
                      (assoc vm
                        :control {:type :node, :id body-node}
                        :env env ; Switch to closure env
                        :stack (conj new-stack
                                     {:type :restore-env, :env (:env frame)})))
                    (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
                ;; Prepare to eval args
                (let [first-arg (first operands)
                      rest-args (subvec operands 1)]
                  (assoc vm
                    :control {:type :node, :id first-arg}
                    :env env
                    :stack (conj new-stack
                                 {:type :app-args,
                                  :fn fn-val,
                                  :evaluated [],
                                  :pending rest-args,
                                  :env env})))))
          :app-args
            (let [{:keys [fn evaluated pending env]} frame
                  new-evaluated (conj evaluated val)]
              (if (empty? pending)
                ;; All args evaluated, apply
                (if (fn? fn)
                  (let [result (apply fn new-evaluated)]
                    (if (module/effect? result)
                      (case (:effect result)
                        :vm/store-put
                          (assoc vm
                            :control {:type :value, :val (:val result)}
                            :store (assoc store (:key result) (:val result))
                            :env env
                            :stack new-stack)
                        :stream/make
                          (let [[stream-ref new-state]
                                  (stream/handle-make vm
                                                      result
                                                      (gen-id-fn id-counter))]
                            (assoc new-state
                              :control {:type :value, :val stream-ref}
                              :env env
                              :stack new-stack
                              :id-counter (inc id-counter)))
                        :stream/put
                          (let [put-result (stream/handle-put vm result)]
                            (if (:park put-result)
                              (let [parked-entry {:stack new-stack,
                                                  :env env,
                                                  :reason :put,
                                                  :stream-id (:stream-id
                                                               put-result),
                                                  :datom (:val result)}]
                                (assoc (:state put-result)
                                  :wait-set (conj (or (:wait-set vm) [])
                                                  parked-entry)
                                  :value :yin/blocked
                                  :blocked true
                                  :control nil))
                              (assoc (:state put-result)
                                :control {:type :value,
                                          :val (:value put-result)}
                                :env env
                                :stack new-stack)))
                        :stream/cursor
                          (let [[cursor-ref new-state]
                                  (stream/handle-cursor vm
                                                        result
                                                        (gen-id-fn id-counter))]
                            (assoc new-state
                              :control {:type :value, :val cursor-ref}
                              :env env
                              :stack new-stack
                              :id-counter (inc id-counter)))
                        :stream/next
                          (let [next-result (stream/handle-next vm result)]
                            (if (:park next-result)
                              (let [parked-entry
                                      {:stack new-stack,
                                       :env env,
                                       :reason :next,
                                       :cursor-ref (:cursor-ref next-result),
                                       :stream-id (:stream-id next-result)}]
                                (assoc (:state next-result)
                                  :wait-set (conj (or (:wait-set vm) [])
                                                  parked-entry)
                                  :value :yin/blocked
                                  :blocked true
                                  :control nil))
                              (assoc (:state next-result)
                                :control {:type :value,
                                          :val (:value next-result)}
                                :env env
                                :stack new-stack)))
                        :stream/close
                          (let [close-result (stream/handle-close vm result)
                                new-state (:state close-result)
                                to-resume (:resume-parked close-result)
                                run-queue (or (:run-queue new-state) [])
                                new-run-queue (into run-queue
                                                    (resume-entries-with-nil
                                                      to-resume))]
                            (assoc new-state
                              :run-queue new-run-queue
                              :control {:type :value, :val nil}
                              :env env
                              :stack new-stack))
                        (throw (ex-info "Unhandled effect in semantic VM"
                                        {:effect result})))
                      (assoc vm
                        :control {:type :value, :val result}
                        :env env
                        :stack new-stack)))
                  (if (= :closure (:type fn))
                    (let [{:keys [params body-node env]} fn
                          new-env (merge env (zipmap params new-evaluated))]
                      (assoc vm
                        :control {:type :node, :id body-node}
                        :env new-env ; Closure env + args
                        :stack (conj new-stack
                                     {:type :restore-env, :env (:env frame)})))
                    (throw (ex-info "Cannot apply non-function" {:fn fn}))))
                ;; More args to eval
                (let [next-arg (first pending)
                      rest-pending (subvec pending 1)]
                  (assoc vm
                    :control {:type :node, :id next-arg}
                    :env env
                    :stack (conj new-stack
                                 (assoc frame
                                   :evaluated new-evaluated
                                   :pending rest-pending))))))
          :restore-env (assoc vm
                         :control control ; Pass value up
                         :env (:env frame) ; Restore caller env
                         :stack new-stack)
          ;; Stream continuation frames
          :stream-put-target (let [stream-ref val
                                   val-node (:val-node frame)]
                               (assoc vm
                                 :control {:type :node, :id val-node}
                                 :stack (conj new-stack
                                              {:type :stream-put-val,
                                               :stream-ref stream-ref,
                                               :env (:env frame)})))
          :stream-put-val
            (let [put-val val
                  stream-ref (:stream-ref frame)
                  effect {:effect :stream/put, :stream stream-ref, :val put-val}
                  result (stream/handle-put vm effect)]
              (if (:park result)
                (let [parked-entry {:stack new-stack,
                                    :env (:env frame),
                                    :reason :put,
                                    :stream-id (:stream-id result),
                                    :datom put-val}]
                  (assoc (:state result)
                    :wait-set (conj (or (:wait-set vm) []) parked-entry)
                    :value :yin/blocked
                    :blocked true
                    :control nil))
                (assoc (:state result)
                  :control {:type :value, :val (:value result)}
                  :env (:env frame)
                  :stack new-stack)))
          :stream-cursor-source
            (let [stream-ref val
                  effect {:effect :stream/cursor, :stream stream-ref}
                  [cursor-ref new-state]
                    (stream/handle-cursor vm effect (gen-id-fn id-counter))]
              (assoc new-state
                :control {:type :value, :val cursor-ref}
                :env (:env frame)
                :stack new-stack
                :id-counter (inc id-counter)))
          :stream-next-cursor
            (let [cursor-ref val
                  effect {:effect :stream/next, :cursor cursor-ref}
                  result (stream/handle-next vm effect)]
              (if (:park result)
                (let [parked-entry {:stack new-stack,
                                    :env (:env frame),
                                    :reason :next,
                                    :cursor-ref (:cursor-ref result),
                                    :stream-id (:stream-id result)}]
                  (assoc (:state result)
                    :wait-set (conj (or (:wait-set vm) []) parked-entry)
                    :value :yin/blocked
                    :blocked true
                    :control nil))
                (assoc (:state result)
                  :control {:type :value, :val (:value result)}
                  :env (:env frame)
                  :stack new-stack)))
          :stream-close-source
            (let [stream-ref val
                  effect {:effect :stream/close, :stream stream-ref}
                  close-result (stream/handle-close vm effect)
                  new-state (:state close-result)
                  to-resume (:resume-parked close-result)
                  run-queue (or (:run-queue new-state) [])
                  new-run-queue (into run-queue
                                      (resume-entries-with-nil to-resume))]
              (assoc new-state
                :run-queue new-run-queue
                :control {:type :value, :val nil}
                :env (:env frame)
                :stack new-stack)))))))


(defn handle-node-eval
  [vm]
  (let [{:keys [control env stack datoms index store primitives id-counter]} vm
        node-id (:id control)
        node-map (datom-node-attrs index node-id)
        node-type (:yin/type node-map)]
    (case node-type
      :literal (assoc vm :control {:type :value, :val (:yin/value node-map)})
      :variable (let [name (:yin/name node-map)
                      val (if-let [pair (find env name)]
                            (val pair)
                            (if-let [pair (find store name)]
                              (val pair)
                              (or (get primitives name)
                                  (module/resolve-symbol name))))]
                  (assoc vm :control {:type :value, :val val}))
      :lambda (assoc vm
                :control {:type :value,
                          :val {:type :closure,
                                :params (:yin/params node-map),
                                :body-node (:yin/body node-map),
                                :datoms datoms,
                                :env env}})
      :if (assoc vm
            :control {:type :node, :id (:yin/test node-map)}
            :stack (conj stack
                         {:type :if,
                          :cons (:yin/consequent node-map),
                          :alt (:yin/alternate node-map),
                          :env env}))
      :application (assoc vm
                     :control {:type :node, :id (:yin/operator node-map)}
                     :stack (conj stack
                                  {:type :app-op,
                                   :operands (:yin/operands node-map),
                                   :env env}))
      ;; VM primitives
      :vm/gensym (let [prefix (or (:yin/prefix node-map) "id")
                       id (keyword (str prefix "-" id-counter))]
                   (assoc vm
                     :control {:type :value, :val id}
                     :id-counter (inc id-counter)))
      :vm/store-get (let [key (:yin/key node-map)
                          val (get store key)]
                      (assoc vm :control {:type :value, :val val}))
      :vm/store-put (let [key (:yin/key node-map)
                          val (:yin/val node-map)]
                      (assoc vm
                        :control {:type :value, :val val}
                        :store (assoc store key val)))
      ;; Stream operations
      :stream/make
        (let [capacity (:yin/buffer node-map)
              effect {:effect :stream/make, :capacity capacity}
              [stream-ref new-state]
                (stream/handle-make vm effect (gen-id-fn id-counter))]
          (assoc new-state
            :control {:type :value, :val stream-ref}
            :id-counter (inc id-counter)))
      :stream/put (let [target-node (:yin/target node-map)]
                    (assoc vm
                      :control {:type :node, :id target-node}
                      :stack (conj stack
                                   {:type :stream-put-target,
                                    :val-node (:yin/val node-map),
                                    :env env})))
      :stream/cursor (let [source-node (:yin/source node-map)]
                       (assoc vm
                         :control {:type :node, :id source-node}
                         :stack (conj stack
                                      {:type :stream-cursor-source, :env env})))
      :stream/next (let [source-node (:yin/source node-map)]
                     (assoc vm
                       :control {:type :node, :id source-node}
                       :stack (conj stack
                                    {:type :stream-next-cursor, :env env})))
      :stream/close (let [source-node (:yin/source node-map)]
                      (assoc vm
                        :control {:type :node, :id source-node}
                        :stack (conj stack
                                     {:type :stream-close-source, :env env})))
      ;; Continuation primitives
      :vm/park (let [park-id (keyword (str "parked-" id-counter))
                     parked-cont {:type :parked-continuation,
                                  :id park-id,
                                  :stack stack,
                                  :env env}
                     new-parked (assoc (:parked vm) park-id parked-cont)]
                 (assoc vm
                   :parked new-parked
                   :value parked-cont
                   :halted true
                   :control nil
                   :id-counter (inc id-counter)))
      :vm/resume (let [parked-id (:yin/parked-id node-map)
                       resume-val (:yin/val node-map)
                       parked-cont (get-in vm [:parked parked-id])]
                   (if parked-cont
                     (let [new-parked (dissoc (:parked vm) parked-id)]
                       (assoc vm
                         :parked new-parked
                         :stack (:stack parked-cont)
                         :env (:env parked-cont)
                         :control {:type :value, :val resume-val}))
                     (throw (ex-info
                              "Cannot resume: parked continuation not found"
                              {:parked-id parked-id}))))
      :vm/current-continuation
        (assoc vm
          :control {:type :value,
                    :val {:type :reified-continuation, :stack stack, :env env}})
      (throw (ex-info "Unknown node type" {:node-map node-map})))))


(defn- semantic-step
  "Execute one step of the semantic VM.
   Operates directly on SemanticVM record (assoc preserves record type)."
  [^SemanticVM vm]
  (let [{:keys [control env stack datoms]} vm]
    (if (= :value (:type control))
      ;; Handle return value from previous step
      (handle-return-value vm)
      ;; Handle node evaluation
      (handle-node-eval vm))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- resume-from-run-queue
  "Pop first entry from run-queue and resume it as the active computation.
   Returns updated state or nil if queue is empty."
  [state]
  (let [run-queue (or (:run-queue state) [])]
    (when (seq run-queue)
      (let [entry (first run-queue)
            rest-queue (subvec run-queue 1)
            new-store (or (:store-updates entry) (:store state))]
        (assoc state
          :run-queue rest-queue
          :store new-store
          :stack (:stack entry)
          :env (:env entry)
          :control {:type :value, :val (:value entry)}
          :blocked false
          :halted false)))))


;; =============================================================================
;; SemanticVM Protocol Implementation
;; =============================================================================

(defn- semantic-vm-step
  "Execute one step of SemanticVM. Returns updated VM."
  [^SemanticVM vm]
  (semantic-step vm))


(defn- semantic-vm-halted?
  "Returns true if VM has halted."
  [^SemanticVM vm]
  (and (boolean (:halted vm)) (empty? (or (:run-queue vm) []))))


(defn- semantic-vm-blocked?
  "Returns true if VM is blocked."
  [^SemanticVM vm]
  (boolean (:blocked vm)))


(defn- semantic-vm-value
  "Returns the current value."
  [^SemanticVM vm]
  (:value vm))


(defn- semantic-vm-load-program
  "Load datoms into the VM.
   Expects {:node root-id :datoms [...]}."
  [^SemanticVM vm {:keys [node datoms]}]
  (let [new-index (group-by first datoms)]
    (assoc vm
      :control {:type :node, :id node}
      :stack []
      :datoms (into (:datoms vm) datoms)
      :index (merge (:index vm) new-index)
      :halted false
      :value nil
      :blocked false)))


(defn- semantic-vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, converts to datoms and loads. When nil, resumes."
  [^SemanticVM vm ast]
  (let [v (if ast
            (let [datoms (vm/ast->datoms ast {:id-start (:node-id-counter vm)})
                  root-id (apply max (map first datoms))
                  min-id (apply min (map first datoms))
                  next-node-id (dec min-id)]
              (semantic-vm-load-program (assoc vm :node-id-counter next-node-id)
                                        {:node root-id, :datoms datoms}))
            vm)]
    (loop [v v]
      (cond
        ;; Active computation: step it
        (and (not (:blocked v)) (not (:halted v))) (recur (semantic-vm-step v))
        ;; Blocked: check if scheduler can wake something
        (:blocked v) (let [v' (scheduler/check-wait-set v)]
                       (if-let [resumed (resume-from-run-queue v')]
                         (recur resumed)
                         v'))
        ;; Halted but run-queue has entries
        (seq (or (:run-queue v) [])) (if-let [resumed (resume-from-run-queue v)]
                                       (recur resumed)
                                       v)
        ;; Truly halted
        :else v))))


(extend-type SemanticVM
  vm/IVMStep
    (step [vm] (semantic-vm-step vm))
    (halted? [vm] (semantic-vm-halted? vm))
    (blocked? [vm] (semantic-vm-blocked? vm))
    (value [vm] (semantic-vm-value vm))
  vm/IVMRun
    (run [vm] (vm/eval vm nil))
  vm/IVMLoad
    (load-program [vm program] (semantic-vm-load-program vm program))
  vm/IVMEval
    (eval [vm ast] (semantic-vm-eval vm ast))
  vm/IVMState
    (control [vm] (:control vm))
    (environment [vm] (:env vm))
    (store [vm] (:store vm))
    (continuation [vm] (:stack vm)))


(defn create-vm
  "Create a new SemanticVM with optional opts map.
   Accepts {:env map, :primitives map}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})]
     (map->SemanticVM (merge (vm/empty-state (select-keys opts [:primitives]))
                             {:control nil,
                              :env env,
                              :stack [],
                              :datoms [],
                              :index {},
                              :halted false,
                              :value nil,
                              :blocked false,
                              :node-id-counter -1024})))))
