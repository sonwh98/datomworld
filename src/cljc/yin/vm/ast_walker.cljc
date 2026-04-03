(ns yin.vm.ast-walker
  (:require
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.host-ffi :as host-ffi]))


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
  [blocked      ; boolean, true if blocked
   bridge       ; explicit host-side FFI bridge state
   halted       ; boolean, true when active continuation has completed
   continuation ; reified continuation or nil
   control      ; current AST node or nil
   environment  ; persistent lexical scope map
   id-counter   ; integer counter for unique IDs
   parked       ; parked continuations map
   primitives   ; primitive operations map
   run-queue    ; vector of runnable continuations
   store        ; heap memory map
   value        ; last computed value
   wait-set     ; vector of parked continuations
   ;; waiting on streams
   ])


(defn- cesk-return
  "Create a new ASTWalkerVM with updated CESK fields in a single allocation.
   Preserves blocked, store, and scheduler fields from vm.
   Derives :halted from the new CESK state."
  [^ASTWalkerVM vm control env cont val]
  (let [blocked (:blocked vm)]
    (->ASTWalkerVM
      blocked
      (:bridge vm)
      (and (not blocked) (nil? control) (nil? cont))
      cont
      control
      env
      (:id-counter vm)
      (:parked vm)
      (:primitives vm)
      (:run-queue vm)
      (:store vm)
      val
      (:wait-set vm))))


(defn- handle-primitive-result
  "Shared logic for handling the result of a primitive function application.
   Handles effect dispatch and blocking via engine/handle-effect."
  [state result continuation env]
  (if (module/effect? result)
    (let [{:keys [state value blocked?]}
          (engine/handle-effect
            state
            result
            {:park-entry-fns
             {:stream/put (fn [_s _e r]
                            {:continuation continuation,
                             :environment env,
                             :reason :put,
                             :stream-id (:stream-id r),
                             :datom (:val result)}),
              :stream/next (fn [_s _e r]
                             {:continuation continuation,
                              :environment env,
                              :reason :next,
                              :cursor-ref (:cursor-ref r),
                              :stream-id (:stream-id r)})}})]
      (if blocked?
        (assoc state :control nil :continuation nil :halted false)
        (cesk-return state nil env continuation value)))
    (cesk-return state nil env continuation result)))


(defn- park-and-call
  "Park continuation and register as reader-waiter on call-out response stream."
  [state op args continuation env]
  (let [;; 1. Park the continuation with a response-processing frame
        response-cont {:type :dao.stream.apply/eval-call
                       :parent continuation
                       :environment env}
        parked (engine/park-continuation state
                                         {:continuation response-cont,
                                          :environment env})
        parked-id (get-in parked [:value :id])

        ;; 2. Get response stream from store
        call-out (get-in parked [:store vm/call-out-stream-key])
        cursor-data (get-in parked [:store vm/call-out-cursor-key])
        cursor-pos (:position cursor-data)

        ;; 3. Register as reader-waiter on response stream
        waiter-entry {:continuation response-cont
                      :environment env
                      :cursor-ref {:type :cursor-ref, :id vm/call-out-cursor-key}
                      :reason :next
                      :stream-id vm/call-out-stream-key}
        _ (when (satisfies? ds/IDaoStreamWaitable call-out)
            (ds/register-reader-waiter! call-out cursor-pos waiter-entry))

        ;; 4. Get request stream from store
        call-in (get-in parked [:store vm/call-in-stream-key])

        ;; 5. Build and emit request
        request (dao.stream.apply/request parked-id op args)
        _ (ds/put! call-in request)]

    ;; 6. Return blocked state
    (assoc parked
           :control nil
           :continuation nil
           :value :yin/blocked
           :blocked true
           :halted false)))


(defn- apply-function
  "Shared logic for applying a function (primitive or closure) to arguments.
   env is the active environment at the call site."
  [state fn-value evaluated-operands continuation env]
  (cond
    ;; Primitive function
    (fn? fn-value)
    (handle-primitive-result state (apply fn-value evaluated-operands) continuation env)

    ;; User-defined closure
    (= :closure (:type fn-value))
    (let [{:keys [params body], closure-env :environment} fn-value
          extended-env (merge closure-env
                              (zipmap params evaluated-operands))]
      (cesk-return state body extended-env continuation (:value state)))
    :else (throw (ex-info "Cannot apply non-function" {:fn fn-value}))))


(defn- cesk-transition
  "Steps the CESK machine to evaluate an AST node.

  State is a map containing:
    :control, :environment, :store, :continuation, :value
    :db, :parked, :id-counter, :primitives

  Returns updated state after one step of evaluation.
  Each return path produces exactly one ASTWalkerVM allocation via cesk-return."
  [state ast]
  (let [{:keys [control environment continuation store primitives]} state
        {:keys [type], :as node} (or ast control)]
    ;; If control is nil but we have a continuation, handle it
    (if (and (nil? node) continuation)
      (let [cont-type (:type continuation)]
        (case cont-type
          :eval-operator
          (let [frame (:frame continuation)
                fn-value (:value state)
                operands (:operands frame)
                saved-env (or (:environment continuation) environment)]
            (if (empty? operands)
              ;; Arity-0 call: apply immediately
              (apply-function state fn-value [] (:parent continuation) saved-env)
              ;; Has operands: evaluate first one
              (let [updated-frame (assoc frame
                                         :operator-evaluated? true
                                         :fn fn-value)]
                (cesk-return state
                             (first operands)
                             saved-env
                             (assoc continuation
                                    :type :eval-operand
                                    :frame updated-frame)
                             nil))))
          :eval-operand
          (let [frame (:frame continuation)
                operand-value (:value state)
                evaluated (conj (or (:evaluated frame) []) operand-value)
                operands (:operands frame)
                saved-env (or (:environment continuation) environment)]
            (if (= (count evaluated) (count operands))
              ;; All evaluated: apply immediately
              (apply-function state (:fn frame) evaluated
                              (:parent continuation) saved-env)
              ;; More operands: evaluate next
              (let [next-idx (count evaluated)
                    next-node (nth operands next-idx)
                    updated-frame (assoc frame :evaluated evaluated)]
                (cesk-return state
                             next-node
                             saved-env
                             (assoc continuation :frame updated-frame)
                             nil))))
          :eval-test
          (let [frame (:frame continuation)
                test-value (:value state)
                saved-env (or (:environment continuation) environment)
                branch (if test-value (:consequent frame) (:alternate frame))]
            (cesk-return state branch saved-env (:parent continuation) test-value))
          :dao.stream.apply/eval-operand
          (let [frame (:frame continuation)
                operand-value (:value state)
                evaluated (conj (or (:evaluated frame) []) operand-value)
                operands (:operands frame)
                saved-env (or (:environment continuation) environment)]
            (if (= (count evaluated) (count operands))
              (park-and-call state
                             (:op frame)
                             evaluated
                             (:parent continuation)
                             saved-env)
              (let [next-idx (count evaluated)
                    next-node (nth operands next-idx)
                    updated-frame (assoc frame :evaluated evaluated)]
                (cesk-return state
                             next-node
                             saved-env
                             (assoc continuation :frame updated-frame)
                             nil))))
          :dao.stream.apply/eval-call
          (let [result-value (:dao.stream.apply/value (:value state))]
            (cesk-return state nil environment (:parent continuation) result-value))
          ;; Stream continuation: evaluate target for put
          :eval-stream-put-target
          (let [frame (:frame continuation)
                stream-ref (:value state)
                val-node (:val frame)]
            (cesk-return state
                         val-node
                         environment
                         (assoc continuation
                                :type :eval-stream-put-val
                                :stream-ref stream-ref)
                         stream-ref))
          ;; Stream continuation: evaluate value for put, then do the put
          :eval-stream-put-val
          (let [val (:value state)
                stream-ref (:stream-ref continuation)
                effect {:effect :stream/put, :stream stream-ref, :val val}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns
                   {:stream/put (fn [_s _e r]
                                  {:continuation (:parent continuation),
                                   :environment environment,
                                   :reason :put,
                                   :stream-id (:stream-id r),
                                   :datom val})}})]
            (if blocked?
              (assoc state :control nil :continuation nil :halted false)
              (cesk-return state nil environment (:parent continuation) value)))
          ;; Stream continuation: evaluate source for cursor creation
          :eval-stream-cursor-source
          (let [stream-ref (:value state)
                effect {:effect :stream/cursor, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (cesk-return state nil environment (:parent continuation) value))
          ;; Stream continuation: evaluate cursor-ref for next!
          :eval-stream-next-cursor
          (let [cursor-ref (:value state)
                effect {:effect :stream/next, :cursor cursor-ref}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns
                   {:stream/next (fn [_s _e r]
                                   {:continuation (:parent continuation),
                                    :environment environment,
                                    :reason :next,
                                    :cursor-ref (:cursor-ref r),
                                    :stream-id (:stream-id r)})}})]
            (if blocked?
              (assoc state :control nil :continuation nil :halted false)
              (cesk-return state nil environment (:parent continuation) value)))
          ;; Resume: val has been evaluated, now do the resume
          :eval-resume-val
          (let [resume-val (:value state)
                parked-id (:parked-id continuation)]
            (engine/resume-continuation
              state
              parked-id
              resume-val
              (fn [new-state parked rv]
                (cesk-return new-state
                             nil
                             (:environment parked)
                             (:continuation parked)
                             rv))))
          ;; Default for unknown continuation
          (throw (ex-info "Unknown continuation type"
                          {:continuation-type cont-type,
                           :continuation continuation}))))
      ;; Otherwise handle the node type
      (case type
        ;; Literals evaluate to themselves
        :literal (cesk-return state nil environment continuation (:value node))
        ;; Variable lookup
        :variable
        (let [value (engine/resolve-var environment store primitives (:name node))]
          (cesk-return state nil environment continuation value))
        ;; Lambda creates a closure
        :lambda (let [{:keys [params body]} node]
                  (cesk-return state nil environment continuation
                               {:type :closure,
                                :params params,
                                :body body,
                                :environment environment}))
        ;; Function application
        :application
        (cesk-return state
                     (:operator node)
                     environment
                     {:frame node,
                      :parent continuation,
                      :environment environment,
                      :type :eval-operator}
                     (:value state))
        ;; Conditional
        :if
        (cesk-return state
                     (:test node)
                     environment
                     {:frame node,
                      :parent continuation,
                      :environment environment,
                      :type :eval-test}
                     (:value state))
        :dao.stream.apply/call
        (let [operands (or (:operands node) [])
              op (:op node)]
          (if (empty? operands)
            (park-and-call state op [] continuation environment)
            (cesk-return state
                         (first operands)
                         environment
                         {:frame {:op op,
                                  :operands operands,
                                  :evaluated []},
                          :parent continuation,
                          :environment environment,
                          :type :dao.stream.apply/eval-operand}
                         (:value state))))
        ;; ============================================================
        ;; VM Primitives for Store Operations
        ;; ============================================================
        ;; Generate unique ID
        :vm/gensym (let [prefix (or (:prefix node) "id")
                         [id s'] (engine/gensym state prefix)]
                     (assoc s'
                            :value id
                            :control nil
                            :halted (nil? continuation)))
        ;; Read from store
        :vm/store-get
        (cesk-return state nil environment continuation (get store (:key node)))
        ;; Write to store
        :vm/store-put (let [key (:key node)
                            value (:val node)
                            new-store (assoc store key value)]
                        (assoc state
                               :store new-store
                               :value value
                               :control nil
                               :halted (and (not (:blocked state))
                                            (nil? continuation))))
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
                                  :control nil
                                  :halted (and (not (:blocked state))
                                               (nil? continuation))))
        ;; ============================================================
        ;; VM Primitives for Continuation Control
        ;; ============================================================
        ;; Get current continuation as a value
        :vm/current-continuation
        (cesk-return state nil environment continuation
                     {:type :reified-continuation,
                      :continuation continuation,
                      :environment environment})
        ;; Park (suspend) - saves current continuation and halts
        :vm/park (-> (engine/park-continuation state
                                               {:continuation continuation,
                                                :environment environment})
                     (assoc :control nil
                            :continuation nil))
        ;; Resume a parked continuation with a value
        :vm/resume
        (cesk-return state
                     (:val node)
                     environment
                     {:type :eval-resume-val,
                      :parked-id (:parked-id node),
                      :parent continuation,
                      :environment environment}
                     (:value state))
        ;; ============================================================
        ;; Stream Operations (AST node forms)
        ;; ============================================================
        :stream/make (let [capacity (or (:buffer node) 1024)
                           effect {:effect :stream/make, :capacity capacity}
                           {:keys [state value]}
                           (engine/handle-effect state effect {})]
                       (cesk-return state nil environment continuation value))
        :stream/put
        (cesk-return state
                     (:target node)
                     environment
                     {:frame node,
                      :parent continuation,
                      :environment environment,
                      :type :eval-stream-put-target}
                     (:value state))
        :stream/cursor
        (cesk-return state
                     (:source node)
                     environment
                     {:frame node,
                      :parent continuation,
                      :environment environment,
                      :type :eval-stream-cursor-source}
                     (:value state))
        :stream/next
        (cesk-return state
                     (:source node)
                     environment
                     {:frame node,
                      :parent continuation,
                      :environment environment,
                      :type :eval-stream-next-cursor}
                     (:value state))
        ;; Unknown node type
        (throw (ex-info "Unknown AST node type" {:type type, :node node}))))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- resume-from-run-queue
  "Pop first entry from run-queue and resume it as the active computation.
   Returns updated state or nil if queue is empty."
  [state]
  (engine/resume-from-run-queue state
                                (fn [base entry]
                                  (cesk-return base
                                               nil
                                               (:environment entry)
                                               (:continuation entry)
                                               (:value entry)))))


(defn- ast-walker-run-active-continuation
  "Hot loop that keeps CESK state in JVM locals instead of an immutable record.
   Inlines common transitions to reduce allocation overhead."
  [^ASTWalkerVM vm-init control-init env-init cont-init val-init]
  (loop [control control-init
         env env-init
         cont cont-init
         val val-init
         vm vm-init]
    (let [node control
          type (:type node)]
      (cond
        ;; --- 1. Handle Continuation (node is nil) ---
        (and (nil? node) cont)
        (let [cont-type (:type cont)]
          (case cont-type
            :eval-operator
            (let [frame (:frame cont)
                  fn-value val
                  operands (:operands frame)
                  saved-env (or (:environment cont) env)]
              (if (empty? operands)
                ;; Arity-0 call
                (cond
                  (= :closure (:type fn-value))
                  (let [{:keys [params body], closure-env :environment} fn-value
                        extended-env (merge closure-env (zipmap params []))]
                    (recur body extended-env (:parent cont) val vm))

                  (fn? fn-value)
                  (let [result (apply fn-value [])]
                    (if (module/effect? result)
                      (let [state (cesk-return vm control env cont val)
                            res (handle-primitive-result state
                                                         result
                                                         (:parent cont)
                                                         saved-env)]
                        (if (or (:blocked res) (and (nil? (:control res)) (nil? (:continuation res))))
                          res
                          (recur (:control res) (:environment res) (:continuation res) (:value res) res)))
                      (recur nil saved-env (:parent cont) result vm)))

                  :else (throw (ex-info "Cannot apply non-function" {:fn fn-value})))
                ;; Has operands
                (let [updated-frame (assoc frame :operator-evaluated? true :fn fn-value)]
                  (recur (first operands) saved-env (assoc cont :type :eval-operand :frame updated-frame) nil vm))))

            :eval-operand
            (let [frame (:frame cont)
                  operand-value val
                  evaluated (conj (or (:evaluated frame) []) operand-value)
                  operands (:operands frame)
                  saved-env (or (:environment cont) env)]
              (if (= (count evaluated) (count operands))
                ;; All evaluated
                (let [fn-value (:fn frame)]
                  (cond
                    (= :closure (:type fn-value))
                    (let [{:keys [params body], closure-env :environment} fn-value
                          extended-env (merge closure-env (zipmap params evaluated))]
                      (recur body extended-env (:parent cont) val vm))

                    (fn? fn-value)
                    (let [result (apply fn-value evaluated)]
                      (if (module/effect? result)
                        (let [state (cesk-return vm control env cont val)
                              res (handle-primitive-result state
                                                           result
                                                           (:parent cont)
                                                           saved-env)]
                          (if (or (:blocked res) (and (nil? (:control res)) (nil? (:continuation res))))
                            res
                            (recur (:control res) (:environment res) (:continuation res) (:value res) res)))
                        (recur nil saved-env (:parent cont) result vm)))

                    :else (throw (ex-info "Cannot apply non-function" {:fn fn-value}))))
                ;; More operands
                (let [next-idx (count evaluated)
                      next-node (nth operands next-idx)
                      updated-frame (assoc frame :evaluated evaluated)]
                  (recur next-node saved-env (assoc cont :frame updated-frame) nil vm))))

            :eval-test
            (let [frame (:frame cont)
                  test-value val
                  saved-env (or (:environment cont) env)
                  branch (if test-value (:consequent frame) (:alternate frame))]
              (recur branch saved-env (:parent cont) test-value vm))

            ;; Fallback for complex/uncommon continuations
            (let [state (cesk-return vm control env cont val)
                  next (cesk-transition state nil)]
              (if (or (:blocked next) (and (nil? (:control next)) (nil? (:continuation next))))
                next
                (recur (:control next) (:environment next) (:continuation next) (:value next) next)))))

        ;; --- 2. Handle Node Type ---
        node
        (case type
          :literal (recur nil env cont (:value node) vm)
          :variable
          (let [v (engine/resolve-var env (:store vm) (:primitives vm) (:name node))]
            (recur nil env cont v vm))
          :lambda
          (recur nil env cont
                 {:type :closure, :params (:params node), :body (:body node), :environment env}
                 vm)
          :application
          (recur (:operator node) env
                 {:frame node, :parent cont, :environment env, :type :eval-operator}
                 val vm)
          :if
          (recur (:test node) env
                 {:frame node, :parent cont, :environment env, :type :eval-test}
                 val vm)

          ;; Fallback for complex/uncommon nodes
          (let [state (cesk-return vm node env cont val)
                next (cesk-transition state nil)]
            (if (or (:blocked next) (and (nil? (:control next)) (nil? (:continuation next))))
              next
              (recur (:control next) (:environment next) (:continuation next) (:value next) next))))

        ;; --- 3. Exit: Halted, Blocked, or Scheduler ---
        :else
        (let [result (cesk-return vm control env cont val)]
          (cond
            (:blocked result)
            (let [v' (engine/check-wait-set result)]
              (if-let [resumed (resume-from-run-queue v')]
                (recur (:control resumed) (:environment resumed)
                       (:continuation resumed) (:value resumed) resumed)
                v'))

            (seq (or (:run-queue result) []))
            (if-let [resumed (resume-from-run-queue result)]
              (recur (:control resumed) (:environment resumed)
                     (:continuation resumed) (:value resumed) resumed)
              result)

            :else result))))))


;; =============================================================================
;; ASTWalkerVM Protocol Implementation
;; =============================================================================

(defn- vm-step
  "Execute one step of ASTWalkerVM. Returns updated VM.
   cesk-transition derives :halted via cesk-return, so no extra assoc needed."
  [^ASTWalkerVM vm]
  (cesk-transition vm nil))


(defn- vm-halted?
  "Returns true if VM has halted."
  [^ASTWalkerVM vm]
  (engine/halted-with-empty-queue? vm))


(defn- vm-blocked?
  "Returns true if VM is blocked."
  [^ASTWalkerVM vm]
  (engine/vm-blocked? vm))


(defn- vm-value
  "Returns the current value."
  [^ASTWalkerVM vm]
  (engine/vm-value vm))


(defn- vm-load-program
  "Load an AST into the VM."
  [^ASTWalkerVM vm ast]
  (assoc vm
         :control ast
         :halted false
         :blocked false))


(defn- ast-walker-run-scheduler
  "Thin wrapper over engine/run-loop with vm-step (slow path)."
  [vm]
  (engine/run-loop
    vm
    engine/active-continuation?
    vm-step
    resume-from-run-queue))


(defn- ast-walker-run
  "Dispatcher: chooses fast path (hot loop) or slow path (scheduler)."
  [vm]
  (if (or (:blocked vm)
          (:halted vm)
          (seq (:run-queue vm))
          (seq (:wait-set vm)))
    (ast-walker-run-scheduler vm)
    (ast-walker-run-active-continuation
      vm (:control vm) (:environment vm) (:continuation vm) (:value vm))))


(defn- vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, loads it first. When nil, resumes from current state."
  [^ASTWalkerVM vm ast]
  (let [v (if ast (vm-load-program vm ast) vm)
        run-loaded
        (fn [state]
          (let [result (ast-walker-run state)]
            ;; Fast path may yield a blocked state immediately after parking.
            ;; Re-enter scheduler once so idle handlers (wait-set/FFI) can run.
            (if (:blocked result)
              (ast-walker-run-scheduler result)
              result)))]
    (host-ffi/maybe-run v run-loaded)))


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
   Accepts {:env map, :primitives map, :bridge handlers}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})
         base (vm/empty-state (select-keys opts [:primitives]))
         bridge-state (host-ffi/bridge-from-opts opts)]
     (map->ASTWalkerVM (merge base
                              {:bridge bridge-state,
                               :control nil,
                               :environment env,
                               :continuation nil,
                               :value nil,
                               :halted true,
                               :blocked false})))))
