(ns yin.vm.ast-walker
  (:require
    [yin.module :as module]
    [yin.scheduler :as scheduler]
    [yin.stream :as stream]
    [yin.vm :as vm]))


;; =============================================================================
;; AST Walker VM
;; =============================================================================
;;
;; Interprets raw AST maps directly (e.g., {:type :literal, :value 42}).
;; Traverses the in-memory tree via direct map field access
;; (:operator, :operands, :body).
;;
;; Continuations are a linked list with :parent pointers:
;;   {:type :eval-operand, :frame ..., :parent cont}
;; Control is the AST node itself, or nil when awaiting continuation.
;;
;; Scheduler: run-queue + wait-set for cooperative multitasking.
;;   run-queue:  [{:continuation k, :environment env, :value v}]
;;   wait-set:   [{:continuation k, :environment env, :reason :next/:put,
;;                  :cursor-ref ref, :stream-id id}]
;; =============================================================================


;; =============================================================================
;; ASTWalkerVM Record
;; =============================================================================

(defrecord ASTWalkerVM
  [control      ; current AST node or nil
   environment  ; persistent lexical scope map
   continuation ; reified continuation or nil
   value        ; last computed value
   store        ; heap memory map
   parked       ; parked continuations map
   id-counter   ; integer counter for unique IDs
   primitives   ; primitive operations map
   blocked      ; boolean, true if blocked
   run-queue    ; vector of runnable continuations
   wait-set     ; vector of parked continuations
   ;; waiting on streams
   ])


(defn- cesk-transition
  "Steps the CESK machine to evaluate an AST node.

  State is a map containing:
    :control, :environment, :store, :continuation, :value
    :db, :parked, :id-counter, :primitives

  Returns updated state after one step of evaluation."
  [state ast]
  (let [{:keys [control environment continuation store primitives]} state
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
          ;; Stream continuation: evaluate target for put
          :eval-stream-put-target (let [frame (:frame continuation)
                                        stream-ref (:value state)
                                        val-node (:val frame)]
                                    (assoc state
                                           :control val-node
                                           :continuation (assoc continuation
                                                                :type :eval-stream-put-val
                                                                :stream-ref stream-ref)))
          ;; Stream continuation: evaluate value for put, then do the put
          :eval-stream-put-val
          (let [val (:value state)
                stream-ref (:stream-ref continuation)
                effect {:effect :stream/put, :stream stream-ref, :val val}
                result (stream/handle-put state effect)]
            (if (:park result)
              ;; At capacity: park the putting continuation
              (let [parked-entry {:continuation (:parent continuation),
                                  :environment environment,
                                  :reason :put,
                                  :stream-id (:stream-id result),
                                  :datom val}]
                (assoc (:state result)
                       :wait-set (conj (or (:wait-set state) []) parked-entry)
                       :value :yin/blocked
                       :blocked true
                       :control nil
                       :continuation nil))
              ;; Success
              (assoc (:state result)
                     :value (:value result)
                     :control nil
                     :continuation (:parent continuation))))
          ;; Stream continuation: evaluate source for cursor creation
          :eval-stream-cursor-source
          (let [stream-ref (:value state)
                gen-id-fn (fn [prefix]
                            (keyword (str prefix "-" (:id-counter state))))
                [cursor-ref new-state] (stream/handle-cursor
                                         state
                                         {:effect :stream/cursor,
                                          :stream stream-ref}
                                         gen-id-fn)]
            (assoc new-state
                   :value cursor-ref
                   :control nil
                   :continuation (:parent continuation)
                   :id-counter (inc (:id-counter state))))
          ;; Stream continuation: evaluate cursor-ref for next!
          :eval-stream-next-cursor
          (let [cursor-ref (:value state)
                effect {:effect :stream/next, :cursor cursor-ref}
                result (stream/handle-next state effect)]
            (if (:park result)
              ;; Blocked: park the continuation
              (let [parked-entry {:continuation (:parent continuation),
                                  :environment environment,
                                  :reason :next,
                                  :cursor-ref (:cursor-ref result),
                                  :stream-id (:stream-id result)}]
                (assoc (:state result)
                       :wait-set (conj (or (:wait-set state) []) parked-entry)
                       :value :yin/blocked
                       :blocked true
                       :control nil
                       :continuation nil))
              ;; Got value (or nil for :end)
              (assoc (:state result)
                     :value (:value result)
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
                        value (if-let [pair (find environment name)]
                                (val pair)
                                (if-let [pair (find store name)]
                                  (val pair)
                                  (or (get primitives name)
                                      (module/resolve-symbol name))))]
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
                    :stream/put
                    (let [effect-result (stream/handle-put state
                                                           result)]
                      (if (:park effect-result)
                        ;; At capacity: park
                        (let [parked-entry
                              {:continuation continuation,
                               :environment environment,
                               :reason :put,
                               :stream-id (:stream-id effect-result),
                               :datom (:val (:val result))}]
                          (assoc (:state effect-result)
                                 :wait-set (conj (or (:wait-set state) [])
                                                 parked-entry)
                                 :value :yin/blocked
                                 :blocked true
                                 :control nil
                                 :continuation nil))
                        (assoc (:state effect-result)
                               :value (:value effect-result)
                               :control nil)))
                    :stream/cursor
                    (let [gen-id-fn
                          (fn [prefix]
                            (keyword
                              (str prefix "-" (:id-counter state))))
                          [cursor-ref new-state] (stream/handle-cursor
                                                   state
                                                   result
                                                   gen-id-fn)]
                      (assoc new-state
                             :value cursor-ref
                             :control nil
                             :id-counter (inc (:id-counter state))))
                    :stream/next
                    (let [effect-result (stream/handle-next state
                                                            result)]
                      (if (:park effect-result)
                        ;; Blocked: park
                        (let [parked-entry
                              {:continuation continuation,
                               :environment environment,
                               :reason :next,
                               :cursor-ref (:cursor-ref
                                             effect-result),
                               :stream-id (:stream-id effect-result)}]
                          (assoc (:state effect-result)
                                 :wait-set (conj (or (:wait-set state) [])
                                                 parked-entry)
                                 :value :yin/blocked
                                 :blocked true
                                 :control nil
                                 :continuation nil))
                        ;; Got value
                        (assoc (:state effect-result)
                               :value (:value effect-result)
                               :control nil)))
                    :stream/close
                    (let [close-result (stream/handle-close state
                                                            result)
                          new-state (:state close-result)
                          to-resume (:resume-parked close-result)
                          ;; Move resumed entries to run-queue with
                          ;; nil value
                          run-queue (or (:run-queue new-state) [])
                          new-run-queue (into run-queue
                                              (map (fn [entry]
                                                     (assoc entry
                                                            :value nil))
                                                   to-resume))]
                      (assoc new-state
                             :run-queue new-run-queue
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
                       new-parked (assoc (:parked state) park-id parked-cont)]
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
        ;; Stream Operations (AST node forms)
        ;; ============================================================
        :stream/make (let [capacity (:buffer node)
                           gen-id-fn (fn [prefix]
                                       (keyword
                                         (str prefix "-" (:id-counter state))))
                           effect {:effect :stream/make, :capacity capacity}
                           [stream-ref new-state]
                           (stream/handle-make state effect gen-id-fn)]
                       (assoc new-state
                              :value stream-ref
                              :control nil
                              :id-counter (inc (:id-counter state))))
        :stream/put (assoc state
                           :control (:target node)
                           :continuation {:frame node,
                                          :parent continuation,
                                          :environment environment,
                                          :type :eval-stream-put-target})
        :stream/cursor (assoc state
                              :control (:source node)
                              :continuation {:frame node,
                                             :parent continuation,
                                             :environment environment,
                                             :type :eval-stream-cursor-source})
        :stream/next (assoc state
                            :control (:source node)
                            :continuation {:frame node,
                                           :parent continuation,
                                           :environment environment,
                                           :type :eval-stream-next-cursor})
        ;; Unknown node type
        (throw (ex-info "Unknown AST node type" {:type type, :node node}))))))


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
            ;; Apply any store updates from the wake-up check
            new-store (or (:store-updates entry) (:store state))]
        (assoc state
               :run-queue rest-queue
               :store new-store
               :control nil
               :continuation (:continuation entry)
               :environment (:environment entry)
               :value (:value entry)
               :blocked false)))))


;; =============================================================================
;; ASTWalkerVM Protocol Implementation
;; =============================================================================

(defn- vm-step
  "Execute one step of ASTWalkerVM. Returns updated VM.
   eval operates directly on the record (assoc preserves record type)."
  [^ASTWalkerVM vm]
  (cesk-transition vm nil))


(defn- vm-halted?
  "Returns true if VM has halted."
  [^ASTWalkerVM vm]
  (and (nil? (:control vm))
       (nil? (:continuation vm))
       (empty? (or (:run-queue vm) []))))


(defn- vm-blocked?
  "Returns true if VM is blocked."
  [^ASTWalkerVM vm]
  (boolean (:blocked vm)))


(defn- vm-value
  "Returns the current value."
  [^ASTWalkerVM vm]
  (:value vm))


(defn- vm-load-program
  "Load an AST into the VM."
  [^ASTWalkerVM vm ast]
  (assoc vm :control ast))


(defn- vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, loads it first. When nil, resumes from current state."
  [^ASTWalkerVM vm ast]
  (let [v (if ast (vm-load-program vm ast) vm)]
    (loop [v v]
      (cond
        ;; Active computation: step it
        (and (not (:blocked v)) (or (:control v) (:continuation v)))
        (recur (vm-step v))
        ;; Blocked or halted: check if scheduler can wake something
        (:blocked v) (let [v' (scheduler/check-wait-set v)]
                       (if-let [resumed (resume-from-run-queue v')]
                         (recur resumed)
                         v'))
        ;; No active computation but run-queue has entries
        (seq (or (:run-queue v) [])) (if-let [resumed (resume-from-run-queue v)]
                                       (recur resumed)
                                       v)
        ;; Truly halted
        :else v))))


(extend-type ASTWalkerVM
  vm/IVMStep
  (step [vm] (vm-step vm))
  (halted? [vm] (vm-halted? vm))
  (blocked? [vm] (vm-blocked? vm))
  (value [vm] (vm-value vm))
  vm/IVMRun
  (run [vm] (vm/eval vm nil))
  vm/IVMLoad
  (load-program [vm program] (vm-load-program vm program))
  vm/IVMEval
  (eval [vm ast] (vm-eval vm ast))
  vm/IVMState
  (control [vm] (:control vm))
  (environment [vm] (:environment vm))
  (store [vm] (:store vm))
  (continuation [vm] (:continuation vm)))


(defn create-vm
  "Create a new ASTWalkerVM with optional opts map.
   Accepts {:env map, :primitives map}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})
         base (vm/empty-state (select-keys opts [:primitives]))]
     (map->ASTWalkerVM (merge base
                              {:control nil,
                               :environment env,
                               :continuation nil,
                               :value nil,
                               :blocked false})))))
