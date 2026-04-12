(ns yin.vm.ast-walker
  (:require
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.host-ffi :as host-ffi]
    [yin.vm.telemetry :as telemetry]))


;; =============================================================================
;; AST Walker VM
;; =============================================================================
;;
;; Interprets raw AST maps directly (e.g., {:type :literal, :value 42}).
;; Traverses the in-memory tree via direct map field access
;; (:operator, :operands, :body).
;;
;; Continuations are a linked list with :next pointers:
;;   {:type :eval-operand, :frame ..., :next k}
;; Control is the AST node itself, or nil when awaiting continuation.
;;
;; Scheduler: run-queue + wait-set for cooperative multitasking.
;;   run-queue:  [{:k k, :env env, :value v}]
;;   wait-set:   [{:k k, :env env, :reason :next/:put,
;;                  :cursor-ref ref, :stream-id id}]
;; =============================================================================


;; =============================================================================
;; ASTWalkerVM Record
;; =============================================================================

(defrecord ASTWalkerVM
  [blocked      ; boolean, true if blocked
   bridge       ; explicit host-side FFI bridge state
   in-stream    ; ingress DaoStream carrying AST programs
   in-cursor    ; ingress cursor position
   halted       ; boolean, true when active continuation has completed
   k            ; reified continuation or nil
   program      ; last ingested AST program
   control      ; current AST node or nil
   env          ; persistent lexical scope map
   id-counter   ; integer counter for unique IDs
   parked       ; parked continuations map
   primitives   ; primitive operations map
   run-queue    ; vector of runnable continuations
   store        ; heap memory map
   value        ; last computed value
   wait-set     ; vector of parked continuations
   telemetry    ; optional telemetry config
   telemetry-step ; telemetry snapshot counter
   telemetry-t  ; telemetry transaction counter
   vm-model     ; telemetry model keyword
   ;; waiting on streams
   ])


(defn- cesk-return
  "Create a new ASTWalkerVM with updated CESK fields in a single allocation.
   Preserves blocked, store, and scheduler fields from vm.
   Derives :halted from the new CESK state."
  [^ASTWalkerVM vm control env k val]
  (let [blocked (:blocked vm)]
    (->ASTWalkerVM
      blocked
      (:bridge vm)
      (:in-stream vm)
      (:in-cursor vm)
      (and (not blocked) (nil? control) (nil? k))
      k
      (:program vm)
      control
      env
      (:id-counter vm)
      (:parked vm)
      (:primitives vm)
      (:run-queue vm)
      (:store vm)
      val
      (:wait-set vm)
      (:telemetry vm)
      (:telemetry-step vm)
      (:telemetry-t vm)
      (:vm-model vm))))


(defn- handle-primitive-result
  "Shared logic for handling the result of a primitive function application.
   Handles effect dispatch and blocking via engine/handle-effect."
  [state result k env]
  (if (module/effect? result)
    (let [{:keys [state value blocked?]}
          (engine/handle-effect
            state
            result
            {:park-entry-fns
             {:stream/put (fn [_s _e r]
                            {:k k,
                             :env env,
                             :reason :put,
                             :stream-id (:stream-id r),
                             :datom (:val result)}),
              :stream/next (fn [_s _e r]
                             {:k k,
                              :env env,
                              :reason :next,
                              :cursor-ref (:cursor-ref r),
                              :stream-id (:stream-id r)})}})]
      (if blocked?
        (assoc state :control nil :k nil :halted false)
        (cesk-return state nil env k value)))
    (cesk-return state nil env k result)))


(defn- park-and-call
  "Park continuation and register as reader-waiter on call-out response stream."
  [state op args k env]
  (let [;; 1. Park the continuation with a response-processing frame
        response-cont {:type :dao.stream.apply/eval-call
                       :next k
                       :env env}
        parked (engine/park-continuation state
                                         {:k response-cont,
                                          :env env})
        parked-id (get-in parked [:value :id])

        ;; 2. Get response stream from store
        call-out (get-in parked [:store vm/call-out-stream-key])
        cursor-data (get-in parked [:store vm/call-out-cursor-key])
        cursor-pos (:position cursor-data)

        ;; 3. Register as reader-waiter on response stream
        waiter-entry {:k response-cont
                      :env env
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
    (assoc (telemetry/emit-snapshot parked :bridge {:bridge-op op})
           :control nil
           :k nil
           :value :yin/blocked
           :blocked true
           :halted false)))


(defn- apply-function
  "Shared logic for applying a function (primitive or closure) to arguments.
   env is the active environment at the call site."
  [state fn-value evaluated-operands k env]
  (cond
    ;; Primitive function
    (fn? fn-value)
    (handle-primitive-result state (apply fn-value evaluated-operands) k env)

    ;; User-defined closure
    (= :closure (:type fn-value))
    (let [{:keys [params body], closure-env :env} fn-value
          extended-env (merge closure-env
                              (zipmap params evaluated-operands))]
      (cesk-return state body extended-env k (:value state)))
    :else (throw (ex-info "Cannot apply non-function" {:fn fn-value}))))


(defn- cesk-transition
  "Steps the CESK machine to evaluate an AST node.

  State is a map containing:
    :control, :env, :store, :k, :value
    :db, :parked, :id-counter, :primitives

  Returns updated state after one step of evaluation.
  Each return path produces exactly one ASTWalkerVM allocation via cesk-return."
  [state ast]
  (let [{:keys [control env k store primitives]} state
        {:keys [type], :as node} (or ast control)]
    ;; If control is nil but we have a continuation, handle it
    (if (and (nil? node) k)
      (let [cont-type (:type k)]
        (case cont-type
          :eval-operator
          (let [frame (:frame k)
                fn-value (:value state)
                operands (:operands frame)
                saved-env (or (:env k) env)]
            (if (empty? operands)
              ;; Arity-0 call: apply immediately
              (apply-function state fn-value [] (:next k) saved-env)
              ;; Has operands: evaluate first one
              (let [updated-frame (assoc frame
                                         :operator-evaluated? true
                                         :fn fn-value)]
                (cesk-return state
                             (first operands)
                             saved-env
                             (assoc k
                                    :type :eval-operand
                                    :frame updated-frame)
                             nil))))
          :eval-operand
          (let [frame (:frame k)
                operand-value (:value state)
                evaluated (conj (or (:evaluated frame) []) operand-value)
                operands (:operands frame)
                saved-env (or (:env k) env)]
            (if (= (count evaluated) (count operands))
              ;; All evaluated: apply immediately
              (apply-function state (:fn frame) evaluated
                              (:next k) saved-env)
              ;; More operands: evaluate next
              (let [next-idx (count evaluated)
                    next-node (nth operands next-idx)
                    updated-frame (assoc frame :evaluated evaluated)]
                (cesk-return state
                             next-node
                             saved-env
                             (assoc k :frame updated-frame)
                             nil))))
          :eval-test
          (let [frame (:frame k)
                test-value (:value state)
                saved-env (or (:env k) env)
                branch (if test-value (:consequent frame) (:alternate frame))]
            (cesk-return state branch saved-env (:next k) test-value))
          :dao.stream.apply/eval-operand
          (let [frame (:frame k)
                operand-value (:value state)
                evaluated (conj (or (:evaluated frame) []) operand-value)
                operands (:operands frame)
                saved-env (or (:env k) env)]
            (if (= (count evaluated) (count operands))
              (park-and-call state
                             (:op frame)
                             evaluated
                             (:next k)
                             saved-env)
              (let [next-idx (count evaluated)
                    next-node (nth operands next-idx)
                    updated-frame (assoc frame :evaluated evaluated)]
                (cesk-return state
                             next-node
                             saved-env
                             (assoc k :frame updated-frame)
                             nil))))
          :dao.stream.apply/eval-call
          (let [result-value (:dao.stream.apply/value (:value state))]
            (cesk-return state nil env (:next k) result-value))
          ;; Stream continuation: evaluate target for put
          :eval-stream-put-target
          (let [frame (:frame k)
                stream-ref (:value state)
                val-node (:val frame)]
            (cesk-return state
                         val-node
                         env
                         (assoc k
                                :type :eval-stream-put-val
                                :stream-ref stream-ref)
                         stream-ref))
          ;; Stream continuation: evaluate value for put, then do the put
          :eval-stream-put-val
          (let [val (:value state)
                stream-ref (:stream-ref k)
                effect {:effect :stream/put, :stream stream-ref, :val val}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns
                   {:stream/put (fn [_s _e r]
                                  {:k (:next k),
                                   :env env,
                                   :reason :put,
                                   :stream-id (:stream-id r),
                                   :datom val})}})]
            (if blocked?
              (assoc state :control nil :k nil :halted false)
              (cesk-return state nil env (:next k) value)))
          ;; Stream continuation: evaluate source for cursor creation
          :eval-stream-cursor-source
          (let [stream-ref (:value state)
                effect {:effect :stream/cursor, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (cesk-return state nil env (:next k) value))
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
                                   {:k (:next k),
                                    :env env,
                                    :reason :next,
                                    :cursor-ref (:cursor-ref r),
                                    :stream-id (:stream-id r)})}})]
            (if blocked?
              (assoc state :control nil :k nil :halted false)
              (cesk-return state nil env (:next k) value)))
          ;; Resume: val has been evaluated, now do the resume
          :eval-resume-val
          (let [resume-val (:value state)
                parked-id (:parked-id k)]
            (engine/resume-continuation
              state
              parked-id
              resume-val
              (fn [new-state parked rv]
                (cesk-return new-state
                             nil
                             (:env parked)
                             (:k parked)
                             rv))))
          ;; Default for unknown continuation
          (throw (ex-info "Unknown continuation type"
                          {:continuation-type cont-type,
                           :continuation k}))))
      ;; Otherwise handle the node type
      (case type
        ;; Literals evaluate to themselves
        :literal (cesk-return state nil env k (:value node))
        ;; Variable lookup
        :variable
        (let [value (engine/resolve-var env store primitives (:name node))]
          (cesk-return state nil env k value))
        ;; Lambda creates a closure
        :lambda (let [{:keys [params body]} node]
                  (cesk-return state nil env k
                               {:type :closure,
                                :params params,
                                :body body,
                                :env env}))
        ;; Function application
        :application
        (cesk-return state
                     (:operator node)
                     env
                     {:frame node,
                      :next k,
                      :env env,
                      :type :eval-operator}
                     (:value state))
        ;; Conditional
        :if
        (cesk-return state
                     (:test node)
                     env
                     {:frame node,
                      :next k,
                      :env env,
                      :type :eval-test}
                     (:value state))
        :dao.stream.apply/call
        (let [operands (or (:operands node) [])
              op (:op node)]
          (if (empty? operands)
            (park-and-call state op [] k env)
            (cesk-return state
                         (first operands)
                         env
                         {:frame {:op op,
                                  :operands operands,
                                  :evaluated []},
                          :next k,
                          :env env,
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
                            :halted (nil? k)))
        ;; Read from store
        :vm/store-get
        (cesk-return state nil env k (get store (:key node)))
        ;; Write to store
        :vm/store-put (let [key (:key node)
                            value (:val node)
                            new-store (assoc store key value)]
                        (assoc state
                               :store new-store
                               :value value
                               :control nil
                               :halted (and (not (:blocked state))
                                            (nil? k))))
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
                                               (nil? k))))
        ;; ============================================================
        ;; VM Primitives for Continuation Control
        ;; ============================================================
        ;; Get current continuation as a value
        :vm/current-continuation
        (cesk-return state nil env k
                     {:type :reified-continuation,
                      :k k,
                      :env env})
        ;; Park (suspend) - saves current continuation and halts
        :vm/park (-> (engine/park-continuation state
                                               {:k k,
                                                :env env})
                     (assoc :control nil
                            :k nil))
        ;; Resume a parked continuation with a value
        :vm/resume
        (cesk-return state
                     (:val node)
                     env
                     {:type :eval-resume-val,
                      :parked-id (:parked-id node),
                      :next k,
                      :env env}
                     (:value state))
        ;; ============================================================
        ;; Stream Operations (AST node forms)
        ;; ============================================================
        :stream/make (let [capacity (or (:buffer node) 1024)
                           effect {:effect :stream/make, :capacity capacity}
                           {:keys [state value]}
                           (engine/handle-effect state effect {})]
                       (cesk-return state nil env k value))
        :stream/put
        (cesk-return state
                     (:target node)
                     env
                     {:frame node,
                      :next k,
                      :env env,
                      :type :eval-stream-put-target}
                     (:value state))
        :stream/cursor
        (cesk-return state
                     (:source node)
                     env
                     {:frame node,
                      :next k,
                      :env env,
                      :type :eval-stream-cursor-source}
                     (:value state))
        :stream/next
        (cesk-return state
                     (:source node)
                     env
                     {:frame node,
                      :next k,
                      :env env,
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
                                               (:env entry)
                                               (:k entry)
                                               (:value entry)))))


(defn- ast-walker-run-active-continuation
  "Hot loop that keeps CESK state in JVM locals instead of an immutable record.
   Inlines common transitions to reduce allocation overhead."
  [^ASTWalkerVM vm-init control-init env-init k-init val-init]
  (loop [control control-init
         env env-init
         k k-init
         val val-init
         vm vm-init]
    (let [node control
          type (:type node)]
      (cond
        ;; --- 1. Handle Continuation (node is nil) ---
        (and (nil? node) k)
        (let [cont-type (:type k)]
          (case cont-type
            :eval-operator
            (let [frame (:frame k)
                  fn-value val
                  operands (:operands frame)
                  saved-env (or (:env k) env)]
              (if (empty? operands)
                ;; Arity-0 call
                (cond
                  (= :closure (:type fn-value))
                  (let [{:keys [params body], closure-env :env} fn-value
                        extended-env (merge closure-env (zipmap params []))]
                    (recur body extended-env (:next k) val vm))

                  (fn? fn-value)
                  (let [result (apply fn-value [])]
                    (if (module/effect? result)
                      (let [state (cesk-return vm control env k val)
                            res (handle-primitive-result state
                                                         result
                                                         (:next k)
                                                         saved-env)]
                        (if (or (:blocked res) (and (nil? (:control res)) (nil? (:k res))))
                          res
                          (recur (:control res) (:env res) (:k res) (:value res) res)))
                      (recur nil saved-env (:next k) result vm)))

                  :else (throw (ex-info "Cannot apply non-function" {:fn fn-value})))
                ;; Has operands
                (let [updated-frame (assoc frame :operator-evaluated? true :fn fn-value)]
                  (recur (first operands) saved-env (assoc k :type :eval-operand :frame updated-frame) nil vm))))

            :eval-operand
            (let [frame (:frame k)
                  operand-value val
                  evaluated (conj (or (:evaluated frame) []) operand-value)
                  operands (:operands frame)
                  saved-env (or (:env k) env)]
              (if (= (count evaluated) (count operands))
                ;; All evaluated
                (let [fn-value (:fn frame)]
                  (cond
                    (= :closure (:type fn-value))
                    (let [{:keys [params body], closure-env :env} fn-value
                          extended-env (merge closure-env (zipmap params evaluated))]
                      (recur body extended-env (:next k) val vm))

                    (fn? fn-value)
                    (let [result (apply fn-value evaluated)]
                      (if (module/effect? result)
                        (let [state (cesk-return vm control env k val)
                              res (handle-primitive-result state
                                                           result
                                                           (:next k)
                                                           saved-env)]
                          (if (or (:blocked res) (and (nil? (:control res)) (nil? (:k res))))
                            res
                            (recur (:control res) (:env res) (:k res) (:value res) res)))
                        (recur nil saved-env (:next k) result vm)))

                    :else (throw (ex-info "Cannot apply non-function" {:fn fn-value}))))
                ;; More operands
                (let [next-idx (count evaluated)
                      next-node (nth operands next-idx)
                      updated-frame (assoc frame :evaluated evaluated)]
                  (recur next-node saved-env (assoc k :frame updated-frame) nil vm))))

            :eval-test
            (let [frame (:frame k)
                  test-value val
                  saved-env (or (:env k) env)
                  branch (if test-value (:consequent frame) (:alternate frame))]
              (recur branch saved-env (:next k) test-value vm))

            ;; Fallback for complex/uncommon continuations
            (let [state (cesk-return vm control env k val)
                  next (cesk-transition state nil)]
              (if (or (:blocked next) (and (nil? (:control next)) (nil? (:k next))))
                next
                (recur (:control next) (:env next) (:k next) (:value next) next)))))

        ;; --- 2. Handle Node Type ---
        node
        (case type
          :literal (recur nil env k (:value node) vm)
          :variable
          (let [v (engine/resolve-var env (:store vm) (:primitives vm) (:name node))]
            (recur nil env k v vm))
          :lambda
          (recur nil env k
                 {:type :closure, :params (:params node), :body (:body node), :env env}
                 vm)
          :application
          (recur (:operator node) env
                 {:frame node, :next k, :env env, :type :eval-operator}
                 val vm)
          :if
          (recur (:test node) env
                 {:frame node, :next k, :env env, :type :eval-test}
                 val vm)

          ;; Fallback for complex/uncommon nodes
          (let [state (cesk-return vm node env k val)
                next (cesk-transition state nil)]
            (if (or (:blocked next) (and (nil? (:control next)) (nil? (:k next))))
              next
              (recur (:control next) (:env next) (:k next) (:value next) next))))

        ;; --- 3. Exit: Halted, Blocked, or Scheduler ---
        :else
        (let [result (cesk-return vm control env k val)]
          (cond
            (:blocked result)
            (let [v' (engine/check-wait-set result)]
              (if-let [resumed (resume-from-run-queue v')]
                (recur (:control resumed) (:env resumed)
                       (:k resumed) (:value resumed) resumed)
                v'))

            (seq (or (:run-queue result) []))
            (if-let [resumed (resume-from-run-queue result)]
              (recur (:control resumed) (:env resumed)
                     (:k resumed) (:value resumed) resumed)
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
  "Load one datom transaction into the VM."
  [^ASTWalkerVM vm datoms]
  (let [ast (vm/datoms->ast datoms)]
    (assoc vm
           :program ast
           :control ast
           :halted false
           :blocked false
           :value nil)))


(defn- vm-reset
  "Reset execution state, preserving the loaded AST program."
  [^ASTWalkerVM vm]
  (assoc vm
         :control (:program vm)
         :k nil
         :halted (nil? (:program vm))
         :value nil
         :blocked false))


(defn- ast-walker-run-scheduler
  "Thin wrapper over engine/run-loop with vm-step (slow path)."
  [vm]
  (engine/run-loop
    vm
    engine/active-continuation?
    (if (telemetry/enabled? vm)
      (fn [state]
        (telemetry/emit-snapshot (vm-step state) :step))
      vm-step)
    resume-from-run-queue))


(defn- vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, loads it first. When nil, resumes from current state."
  [^ASTWalkerVM vm ast]
  (if ast
    (-> vm
        (vm-load-program (vm/ast->datoms ast))
        (vm/run))
    (vm/run vm)))


(defn- ast-walker-run-on-stream
  [vm]
  (engine/run-on-stream vm
                        (:in-stream vm)
                        vm-load-program
                        (if (telemetry/enabled? vm)
                          (fn [state]
                            (telemetry/emit-snapshot (vm-step state) :step))
                          vm-step)
                        resume-from-run-queue))


(extend-type ASTWalkerVM
  vm/IVM
  (step [vm]
    (telemetry/emit-snapshot
      (engine/step-on-stream vm (:in-stream vm) vm-load-program vm-step)
      :step))
  (run [vm] (host-ffi/maybe-run vm ast-walker-run-on-stream))
  (eval [vm ast] (vm-eval vm ast))
  (reset [vm] (vm-reset vm))
  (halted? [vm] (vm-halted? vm))
  (blocked? [vm] (vm-blocked? vm))
  (value [vm] (vm-value vm))
  vm/IVMState
  (control [vm] (:control vm))
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm]
    (when-let [k-head (:k vm)]
      (loop [k k-head
             acc []]
        (if (nil? k)
          acc
          (recur (:next k) (conj acc k)))))))


(defn create-vm
  "Create a new ASTWalkerVM with optional opts map.
   Accepts {:env map, :primitives map, :bridge handlers, :telemetry config}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})
         base (vm/empty-state {:primitives (:primitives opts)
                               :telemetry (:telemetry opts)
                               :vm-model :ast-walker})
         bridge-state (host-ffi/bridge-from-opts opts)
         in-stream (:in-stream opts)]
     (-> (map->ASTWalkerVM (merge base
                                  {:bridge bridge-state,
                                   :in-stream in-stream,
                                   :in-cursor {:position 0},
                                   :program nil,
                                   :control nil,
                                   :env env,
                                   :k nil,
                                   :value nil,
                                   :halted true,
                                   :blocked false}))
         (telemetry/install :ast-walker)
         (telemetry/emit-snapshot :init)))))
