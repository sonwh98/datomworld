(ns yin.vm.ast-walker
  (:refer-clojure :exclude [eval])
  (:require [datascript.core :as d]
            [yin.module :as module]
            [yin.stream :as stream]
            [yin.vm :as vm]))


;; Counter for generating unique IDs (legacy, for backward compatibility)
(def ^:private id-counter (atom 0))


(defn gensym-id
  "Generate a unique keyword ID with optional prefix.
   Legacy function for backward compatibility.
   In the pure VM, use the :id-counter field in the state."
  ([] (gensym-id "id"))
  ([prefix] (keyword (str prefix "-" (swap! id-counter inc)))))


;; =============================================================================
;; ASTWalkerVM Record
;; =============================================================================

(defrecord ASTWalkerVM [control      ; current AST node or nil
                        environment  ; persistent lexical scope map
                        continuation ; reified continuation or nil
                        value        ; last computed value
                        store        ; heap memory map
                        db           ; DataScript db value
                        parked       ; parked continuations map
                        id-counter   ; integer counter for unique IDs
                        primitives   ; primitive operations map
                       ])


(defn make-state
  "Create an initial CESK machine state with given environment.
   Legacy helper: returns a map, not a VM record.
   Enhanced to include new state fields."
  [env]
  (merge (vm/empty-state)
         {:control nil, :environment env, :continuation nil, :value nil}))


(defn eval
  "Steps the CESK machine to evaluate an AST node.

  State is a map containing:
    :control, :environment, :store, :continuation, :value
    :db, :parked, :id-counter, :primitives

  Returns updated state after one step of evaluation."
  [state ast]
  (let [{:keys [control environment continuation store]} state
        {:keys [type], :as node} (or ast control)]
    ;; If control is nil but we have a continuation, handle it
    (if (and (nil? node) continuation)
      (let [cont-type (:type continuation)]
        (case cont-type
          :eval-operator (let [frame (:frame continuation)
                               fn-value (:value state)
                               updated-frame (assoc frame
                                               :operator-evaluated? true
                                               :fn-value fn-value)
                               saved-env (:environment continuation)]
                           (assoc state
                             :control updated-frame
                             :environment (or saved-env environment)
                             :continuation (:parent continuation)
                             :value nil))
          :eval-operand
            (let [frame (:frame continuation)
                  operand-value (:value state)
                  evaluated-operands (conj (or (:evaluated-operands frame) [])
                                           operand-value)
                  updated-frame (assoc frame
                                  :evaluated-operands evaluated-operands)
                  saved-env (:environment continuation)]
              (assoc state
                :control updated-frame
                :environment (or saved-env environment)
                :continuation (:parent continuation)
                :value nil))
          :eval-test (let [frame (:frame continuation)
                           test-value (:value state)
                           saved-env (:environment continuation)]
                       (assoc state
                         :control (assoc frame :evaluated-test? true)
                         :value test-value
                         :environment (or saved-env environment)
                         :continuation (:parent continuation)))
          ;; Stream continuation: evaluate source for take
          :eval-stream-source
            (let [stream-ref (:value state)
                  result (stream/vm-stream-take state stream-ref continuation)]
              (if (:park result)
                ;; Need to park - add continuation to takers
                (let [gen-id-fn (fn [prefix]
                                  (keyword
                                    (str prefix "-" (:id-counter state))))
                      stream-id (:stream-id result)
                      parked-cont {:type :parked-continuation,
                                   :id (gen-id-fn "taker"),
                                   :continuation (:parent continuation),
                                   :environment environment}
                      store (:store state)
                      new-store
                        (update-in store [stream-id :takers] conj parked-cont)]
                  (assoc state
                    :store new-store
                    :value :yin/blocked
                    :control nil
                    :continuation nil
                    :id-counter (inc (:id-counter state))))
                ;; Got value
                (assoc (:state result)
                  :control nil
                  :continuation (:parent continuation))))
          ;; Stream continuation: evaluate target for put
          :eval-stream-put-target (let [frame (:frame continuation)
                                        stream-ref (:value state)
                                        val-node (:val frame)]
                                    (assoc state
                                      :control val-node
                                      :continuation (assoc continuation
                                                      :type :eval-stream-put-val
                                                      :stream-ref stream-ref)))
          ;; Stream continuation: evaluate value for put
          :eval-stream-put-val
            (let [val (:value state)
                  stream-ref (:stream-ref continuation)
                  result (stream/vm-stream-put state stream-ref val)]
              (if-let [taker (:resume-taker result)]
                ;; Taker was waiting - need to resume it. For now, just
                ;; complete the put and let scheduler handle resume
                (assoc (:state result)
                  :control nil
                  :continuation (:parent continuation))
                ;; No taker - value buffered
                (assoc (:state result)
                  :control nil
                  :continuation (:parent continuation))))
          ;; Default for unknown continuation
          (throw (ex-info "Unknown continuation type"
                          {:continuation-type cont-type,
                           :continuation continuation}))))
      ;; Otherwise handle the node type
      (case type
        ;; Literals evaluate to themselves
        :literal (let [{:keys [value]} node]
                   (assoc state
                     :value value
                     :control nil))
        ;; Variable lookup
        :variable (let [{:keys [name]} node
                        ;; Check local environment, then store (global),
                        ;; then module system (via primitives)
                        value (or (get environment name)
                                  (get store name)
                                  (get (:primitives state) name)
                                  (module/resolve-symbol name))]
                    (assoc state
                      :value value
                      :control nil))
        ;; Lambda creates a closure
        :lambda (let [{:keys [params body]} node
                      closure {:type :closure,
                               :params params,
                               :body body,
                               :environment environment}]
                  (assoc state
                    :value closure
                    :control nil))
        ;; Function application
        :application
          (let [{:keys [operator operands evaluated-operands fn-value
                        operator-evaluated?]}
                  node
                evaluated-operands (or evaluated-operands [])]
            (cond
              ;; All evaluated - apply function
              (and operator-evaluated?
                   (= (count evaluated-operands) (count operands)))
                (cond
                  ;; Primitive function
                  (fn? fn-value)
                    (let [result (apply fn-value evaluated-operands)]
                      ;; Check if result is an effect descriptor
                      (if (module/effect? result)
                        ;; Execute effect
                        (case (:effect result)
                          :vm/store-put (let [key (:key result)
                                              value (:val result)
                                              new-store (assoc store key value)]
                                          (assoc state
                                            :store new-store
                                            :value value
                                            :control nil))
                          :stream/make
                            (let [gen-id-fn
                                    (fn [prefix]
                                      (keyword
                                        (str prefix "-" (:id-counter state))))
                                  [stream-ref new-state]
                                    (stream/handle-make state result gen-id-fn)]
                              (assoc new-state
                                :value stream-ref
                                :control nil
                                :id-counter (inc (:id-counter state))))
                          :stream/put (let [effect-result
                                              (stream/handle-put state result)]
                                        (assoc (:state effect-result)
                                          :value (:value effect-result)
                                          :control nil))
                          :stream/take
                            (let [effect-result (stream/handle-take state
                                                                    result)]
                              (if (:park effect-result)
                                ;; Need to park
                                (let [gen-id-fn (fn [prefix]
                                                  (keyword (str prefix
                                                                "-"
                                                                (:id-counter
                                                                  state))))
                                      stream-id (:stream-id effect-result)
                                      parked-cont {:type :parked-continuation,
                                                   :id (gen-id-fn "taker"),
                                                   :continuation continuation,
                                                   :environment environment}
                                      new-store (update-in store
                                                           [stream-id :takers]
                                                           conj
                                                           parked-cont)]
                                  (assoc state
                                    :store new-store
                                    :value :yin/blocked
                                    :control nil
                                    :continuation nil
                                    :id-counter (inc (:id-counter state))))
                                ;; Got value
                                (assoc (:state effect-result)
                                  :value (:value effect-result)
                                  :control nil)))
                          :stream/close
                            (let [new-state (stream/handle-close state result)]
                              (assoc new-state
                                :value nil
                                :control nil))
                          ;; Unknown effect
                          (throw (ex-info "Unknown effect type"
                                          {:effect result})))
                        ;; Regular return value
                        (assoc state
                          :value result
                          :control nil)))
                  ;; User-defined closure
                  (= :closure (:type fn-value))
                    (let [{:keys [params body environment]} fn-value
                          extended-env (merge environment
                                              (zipmap params
                                                      evaluated-operands))]
                      (assoc state
                        :control body
                        :environment extended-env
                        :continuation continuation))
                  :else (throw (ex-info "Cannot apply non-function"
                                        {:fn-value fn-value})))
              ;; Evaluate operands one by one
              operator-evaluated?
                (let [next-operand (nth operands (count evaluated-operands))]
                  (assoc state
                    :control next-operand
                    :continuation {:frame node,
                                   :parent continuation,
                                   :environment environment,
                                   :type :eval-operand}))
              ;; Evaluate operator first
              :else (assoc state
                      :control operator
                      :continuation {:frame node,
                                     :parent continuation,
                                     :environment environment,
                                     :type :eval-operator})))
        ;; Conditional
        :if (let [{:keys [test consequent alternate]} node]
              (if (:evaluated-test? node)
                ;; Test evaluated, choose branch
                (let [test-value (:value state)
                      branch (if test-value consequent alternate)]
                  (assoc state :control branch))
                ;; Evaluate test first
                (assoc state
                  :control test
                  :continuation {:frame node,
                                 :parent continuation,
                                 :environment environment,
                                 :type :eval-test})))
        ;; ============================================================
        ;; VM Primitives for Store Operations
        ;; ============================================================
        ;; Generate unique ID
        :vm/gensym (let [prefix (or (:prefix node) "id")
                         id (keyword (str prefix "-" (:id-counter state)))]
                     (assoc state
                       :value id
                       :control nil
                       :id-counter (inc (:id-counter state))))
        ;; Read from store
        :vm/store-get (let [key (:key node)
                            value (get store key)]
                        (assoc state
                          :value value
                          :control nil))
        ;; Write to store
        :vm/store-put (let [key (:key node)
                            value (:val node)
                            new-store (assoc store key value)]
                        (assoc state
                          :store new-store
                          :value value
                          :control nil))
        ;; Update store (apply function to current value)
        :vm/store-update (let [key (:key node)
                               f (:fn node)
                               args (:args node)
                               current (get store key)
                               new-value (apply f current args)
                               new-store (assoc store key new-value)]
                           (assoc state
                             :store new-store
                             :value new-value
                             :control nil))
        ;; ============================================================
        ;; VM Primitives for Continuation Control
        ;; ============================================================
        ;; Get current continuation as a value
        :vm/current-continuation (assoc state
                                   :value {:type :reified-continuation,
                                           :continuation continuation,
                                           :environment environment}
                                   :control nil)
        ;; Park (suspend) - saves current continuation and halts
        :vm/park (let [park-id (keyword (str "parked-" (:id-counter state)))
                       parked-cont {:type :parked-continuation,
                                    :id park-id,
                                    :continuation continuation,
                                    :environment environment}
                       new-parked (assoc (or (:parked state) {})
                                    park-id parked-cont)]
                   (assoc state
                     :parked new-parked
                     :value parked-cont
                     :control nil
                     :continuation nil ; Halt execution
                     :id-counter (inc (:id-counter state))))
        ;; Resume a parked continuation with a value
        :vm/resume (let [parked-id (:parked-id node)
                         resume-value (:val node)
                         parked-cont (get-in state [:parked parked-id])]
                     (if parked-cont
                       (let [new-parked (dissoc (:parked state) parked-id)]
                         (assoc state
                           :parked new-parked
                           :value resume-value
                           :control nil
                           :continuation (:continuation parked-cont)
                           :environment (:environment parked-cont)))
                       (throw (ex-info
                                "Cannot resume: parked continuation not found"
                                {:parked-id parked-id}))))
        ;; ============================================================
        ;; Stream Operations (library-based)
        ;; ============================================================
        :stream/make (let [capacity (or (:buffer node) 1024)
                           [stream-ref new-state]
                             (stream/vm-stream-make state capacity)]
                       (assoc new-state
                         :value stream-ref
                         :control nil))
        :stream/put (assoc state
                      :control (:target node)
                      :continuation {:frame node,
                                     :parent continuation,
                                     :environment environment,
                                     :type :eval-stream-put-target})
        :stream/take (assoc state
                       :control (:source node)
                       :continuation {:frame node,
                                      :parent continuation,
                                      :environment environment,
                                      :type :eval-stream-source})
        ;; Unknown node type
        (throw (ex-info "Unknown AST node type" {:type type, :node node}))))))


;; Helper function to step the VM until completion
(defn run
  "Runs the CESK machine until the computation completes.
  Returns the final state with the result in :value."
  [initial-state ast]
  (loop [state (assoc initial-state :control ast)]
    (if (or (:control state) (:continuation state))
      (recur (eval state nil))
      state)))


;; Helper to check if VM is parked (blocked)
(defn parked?
  "Returns true if the VM has parked continuations waiting."
  [state]
  (boolean (seq (:parked state))))


;; Helper to get parked continuation IDs
(defn parked-ids
  "Returns the IDs of all parked continuations."
  [state]
  (keys (:parked state)))


;; =============================================================================
;; ASTWalkerVM Protocol Implementation
;; =============================================================================

(defn- vm->state
  "Convert ASTWalkerVM to legacy state map for eval."
  [^ASTWalkerVM vm]
  {:control (:control vm),
   :environment (:environment vm),
   :store (:store vm),
   :continuation (:continuation vm),
   :value (:value vm),
   :db (:db vm),
   :parked (:parked vm),
   :id-counter (:id-counter vm),
   :primitives (:primitives vm)})


(defn- state->vm
  "Convert legacy state map back to ASTWalkerVM."
  [^ASTWalkerVM vm state]
  (->ASTWalkerVM (:control state)
                 (:environment state)
                 (:continuation state)
                 (:value state)
                 (:store state)
                 (:db state)
                 (:parked state)
                 (:id-counter state)
                 (:primitives state)))


(defn vm-step
  "Execute one step of ASTWalkerVM. Returns updated VM."
  [^ASTWalkerVM vm]
  (let [state (vm->state vm)
        new-state (eval state nil)]
    (state->vm vm new-state)))


(defn vm-halted?
  "Returns true if VM has halted."
  [^ASTWalkerVM vm]
  (and (nil? (:control vm)) (nil? (:continuation vm))))


(defn vm-blocked?
  "Returns true if VM is blocked."
  [^ASTWalkerVM vm]
  (= :yin/blocked (:value vm)))


(defn vm-value "Returns the current value." [^ASTWalkerVM vm] (:value vm))


(defn vm-run
  "Run ASTWalkerVM until halted or blocked."
  [^ASTWalkerVM vm]
  (loop [v vm] (if (or (vm-halted? v) (vm-blocked? v)) v (recur (vm-step v)))))


(defn vm-load-program
  "Load an AST into the VM."
  [^ASTWalkerVM vm ast]
  (assoc vm :control ast))


(defn vm-transact!
  "Transact datoms into the VM's DataScript db."
  [^ASTWalkerVM vm datoms]
  (let [tx-data (vm/datoms->tx-data datoms)
        conn (d/conn-from-db (:db vm))
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:vm (assoc vm :db @conn), :tempids tempids}))


(defn vm-q
  "Run a Datalog query against the VM's db."
  [^ASTWalkerVM vm args]
  (apply d/q (first args) (:db vm) (rest args)))


(extend-type ASTWalkerVM
  vm/IVMStep
    (step [vm] (vm-step vm))
    (halted? [vm] (vm-halted? vm))
    (blocked? [vm] (vm-blocked? vm))
    (value [vm] (vm-value vm))
  vm/IVMRun
    (run [vm] (vm-run vm))
  vm/IVMLoad
    (load-program [vm program] (vm-load-program vm program))
  vm/IVMDataScript
    (transact! [vm datoms] (vm-transact! vm datoms))
    (q [vm args] (vm-q vm args)))


(defn create-vm
  "Create a new ASTWalkerVM with optional environment."
  ([] (create-vm {}))
  ([env]
   (let [base (vm/empty-state)]
     (map->ASTWalkerVM
       (merge
         base
         {:control nil, :environment env, :continuation nil, :value nil})))))
