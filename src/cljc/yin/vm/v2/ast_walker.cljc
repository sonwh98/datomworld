(ns yin.vm.v2.ast-walker
  "Direct AST interpreter for the Yin Abstract Machine on DaoStream v2.

   A CESK machine over raw AST maps with a linked continuation, a ready queue
   and a wait set. `step` and `run` execute already-loaded work only: program
   input is observed above the VM by `yin.vm.v2.stream-observer`, which hands
   each batch to `vm-load-program` through `run-on-stream`. `eval` converts
   its supplied AST, loads it, and runs it without draining any independently
   queued program input. v1's evaluator was driven through its own
   `:in-stream`; removing that coupling is what V7 changed, so this evaluator
   is v1's kernel, not v1 unchanged.

   The port's other changes below the evaluator survive: opaque cursors,
   outcome maps, a supplied stream constructor, a registry value, and no
   waiters — the one deletion inside this namespace is v1's transport-local
   waiter registration in `park-and-call`, whose continuation now parks in the
   polling wait set like every other blocked read until the response arrives
   on the call-out stream.

   There is no `macro-expand` branch here, exactly as in v1: this evaluator
   runs macro-free programs."
  (:require [dao.stream.v2.apply :as apply2]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.engine :as engine]
            [yin.vm.v2.ffi :as ffi]
            [yin.vm.v2.module :as module]
            [yin.vm.v2.telemetry :as telemetry]))


(declare ast-walker-restore)


;; =============================================================================
;; ASTWalkerVM Record
;; =============================================================================

(defrecord ASTWalkerVM
  [blocked?       ; boolean, true if blocked
   bridge         ; explicit host-side FFI bridge state
   halted?        ; boolean, true when active continuation has completed
   k              ; reified continuation or nil
   program        ; last loaded AST program
   control        ; current AST node or nil
   env            ; persistent lexical scope map
   id-counter     ; integer counter for unique IDs
   parked         ; parked continuations map
   primitives     ; primitive operations map
   modules        ; module registry value
   make-stream    ; host-supplied stream constructor, or nil
   call-capacity  ; declared capacity of the FFI pair
   ready-queue    ; vector of runnable continuations
   store          ; heap memory map
   value          ; last computed value
   wait-set       ; vector of continuations waiting on a transport
   telemetry      ; optional telemetry config (always nil in this slice)
   telemetry-step ; telemetry snapshot counter
   telemetry-t    ; telemetry transaction counter
   vm-model       ; telemetry model keyword
   ])


(defn- cesk-return
  "Create a new ASTWalkerVM with updated CESK fields in a single allocation.
   Preserves blocked, store, and scheduler fields from vm.
   Derives :halted? from the new CESK state."
  [^ASTWalkerVM vm control env k val]
  (let [blocked (:blocked? vm)]
    (->ASTWalkerVM blocked
                   (:bridge vm)
                   (and (not blocked) (nil? control) (nil? k))
                   k
                   (:program vm)
                   control
                   env
                   (:id-counter vm)
                   (:parked vm)
                   (:primitives vm)
                   (:modules vm)
                   (:make-stream vm)
                   (:call-capacity vm)
                   (:ready-queue vm)
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
            {:restore-fn ast-walker-restore,
             :park-entry-fns {:stream/put (fn [_s _e r]
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
        (assoc state
               :control nil
               :k nil
               :halted? false)
        (cesk-return state nil env k value)))
    (cesk-return state nil env k result)))


(defn- call-response-wait-entry
  "The polling wait entry for a sent call awaiting its correlated response."
  [call-id next-k env]
  {:k {:type :dao.stream.v2.apply/eval-call,
       :next next-k,
       :env env,
       :call-id call-id},
   :env env,
   :cursor-ref {:type :cursor-ref, :id vm/call-out-cursor-key},
   :reason :next,
   :stream-id vm/call-out-stream-key})


(defn- park-and-call
  "Park the continuation, emit the request, and wait for its response.

   The pair is checked before `park-continuation` deliberately: the park runs
   before the first stream touch, so an error raised later would strand a
   continuation in `:parked` and consume an id counter.

   The request is retained, never dropped. A `full` call-in leaves the
   identical encoded request in the polling wait set, which retries it, and
   the call starts waiting for its response only once the append has
   succeeded. `closed`, `invalid-value` and `transport-error` fail the call
   here and name their outcome."
  [state op args k env]
  (let [{:keys [call-in]} (ffi/require-call-pair! (:store state) op)
        response-cont {:type :dao.stream.v2.apply/eval-call, :next k, :env env}
        parked (engine/park-continuation state {:k response-cont, :env env})
        parked-id (get-in parked [:value :id])
        request (apply2/request parked-id op (vec args))
        result (apply2/put-request! call-in request)]
    (case (:dao.stream/outcome result)
      :dao.stream/ok
      (-> parked
          (update :wait-set
                  (fnil conj [])
                  (call-response-wait-entry parked-id k env))
          (telemetry/emit-snapshot :bridge {:bridge-op op})
          (assoc :control nil
                 :k nil
                 :value :yin/blocked
                 :blocked? true
                 :halted? false))
      :dao.stream/full
      (-> parked
          (update :wait-set
                  (fnil conj [])
                  {:k {:type :dao.stream.v2.apply/request-sent,
                       :parked-id parked-id,
                       :next k,
                       :env env,
                       :op op},
                   :env env,
                   :reason :put,
                   :stream-id vm/call-in-stream-key,
                   :datom request})
          (telemetry/emit-snapshot :bridge {:bridge-op op})
          (assoc :control nil
                 :k nil
                 :value :yin/blocked
                 :blocked? true
                 :halted? false))
      (throw (ex-info "FFI request could not be appended"
                      {:op op,
                       :outcome (or (:dao.stream/outcome result)
                                    (:dao.stream.v2.apply/outcome result))})))))


(defn- call-result
  "Unwrap a response envelope for the continuation that made the call.

   v1 read `:dao.stream.apply/value` off a response that could only succeed.
   A v2 response carries exactly one of `ok` or `error`, and correlation is
   checked here rather than assumed from stream order."
  [response call-id]
  (when-not (apply2/response? response)
    (throw (ex-info "FFI response envelope is malformed" {:response response})))
  (when (and call-id (not= call-id (apply2/response-id response)))
    (throw (ex-info "FFI response does not correlate with this parked call"
                    {:call-id call-id,
                     :response-id (apply2/response-id response)})))
  (if-let [err (apply2/response-error response)]
    (throw (ex-info (str "FFI call failed: "
                         (:dao.stream.v2.apply/message err))
                    {:call-id call-id, :error err}))
    (apply2/response-ok response)))


(defn- apply-function
  "Shared logic for applying a function (primitive or closure) to arguments.
   env is the active environment at the call site."
  [state fn-value evaluated-operands k env]
  (cond (fn? fn-value)
        (handle-primitive-result state
                                 (apply fn-value evaluated-operands)
                                 k
                                 env)
        (= :closure (:type fn-value))
        (let [{:keys [params body], closure-env :env} fn-value
              extended-env (merge closure-env (zipmap params evaluated-operands))]
          (cesk-return state body extended-env k (:value state)))
        :else (throw (ex-info "Cannot apply non-function" {:fn fn-value}))))


(defn- cesk-transition
  "Steps the CESK machine to evaluate an AST node.
   Each return path produces exactly one ASTWalkerVM allocation via
   cesk-return."
  [state ast]
  (let [{:keys [control env k store primitives modules]} state
        {:keys [type], :as node} (or ast control)]
    (if (and (nil? node) k)
      (let [cont-type (:type k)]
        (case cont-type
          :eval-operator
          (let [frame (:frame k)
                fn-value (:value state)
                operands (:operands frame)
                saved-env (or (:env k) env)]
            (if (empty? operands)
              (apply-function state fn-value [] (:next k) saved-env)
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
              (apply-function state (:fn frame) evaluated (:next k) saved-env)
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
          :dao.stream.v2.apply/eval-operand
          (let [frame (:frame k)
                operand-value (:value state)
                evaluated (conj (or (:evaluated frame) []) operand-value)
                operands (:operands frame)
                saved-env (or (:env k) env)]
            (if (= (count evaluated) (count operands))
              (park-and-call state (:op frame) evaluated (:next k) saved-env)
              (let [next-idx (count evaluated)
                    next-node (nth operands next-idx)
                    updated-frame (assoc frame :evaluated evaluated)]
                (cesk-return state
                             next-node
                             saved-env
                             (assoc k :frame updated-frame)
                             nil))))
          :dao.stream.v2.apply/request-sent
          ;; A request retained on `full` has now been appended by the polling
          ;; wait set. The call starts waiting for its correlated response,
          ;; exactly as an immediately-sent one does.
          (-> state
              (update :wait-set
                      (fnil conj [])
                      (call-response-wait-entry (:parked-id k)
                                                (:next k)
                                                (:env k)))
              (telemetry/emit-snapshot :bridge {:bridge-op (:op k)})
              (assoc :control nil
                     :k nil
                     :value :yin/blocked
                     :blocked? true
                     :halted? false))
          :dao.stream.v2.apply/eval-call
          ;; The response consumed here completes the call that
          ;; `park-and-call` recorded, so its continuation leaves `:parked`
          ;; rather than accumulating for the lifetime of the VM.
          (let [state (update state :parked dissoc (:call-id k))]
            (cesk-return state
                         nil
                         env
                         (:next k)
                         (call-result (:value state) (:call-id k))))
          :eval-stream-put-target (let [frame (:frame k)
                                        stream-ref (:value state)
                                        val-node (:val frame)]
                                    (cesk-return state
                                                 val-node
                                                 env
                                                 (assoc k
                                                        :type :eval-stream-put-val
                                                        :stream-ref stream-ref)
                                                 stream-ref))
          :eval-stream-put-val
          (let [val (:value state)
                stream-ref (:stream-ref k)
                effect {:effect :stream/put, :stream stream-ref, :val val}
                {:keys [state value blocked?]}
                (engine/handle-effect state
                                      effect
                                      {:restore-fn ast-walker-restore,
                                       :park-entry-fns
                                       {:stream/put (fn [_s _e r]
                                                      {:k (:next k),
                                                       :env env,
                                                       :reason :put,
                                                       :stream-id
                                                       (:stream-id r),
                                                       :datom val})}})]
            (if blocked?
              (assoc state
                     :control nil
                     :k nil
                     :halted? false)
              (cesk-return state nil env (:next k) value)))
          :eval-stream-cursor-source
          (let [stream-ref (:value state)
                effect {:effect :stream/cursor, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect
                                        state
                                        effect
                                        {:restore-fn ast-walker-restore})]
            (cesk-return state nil env (:next k) value))
          :eval-stream-next-cursor
          (let [cursor-ref (:value state)
                effect {:effect :stream/next, :cursor cursor-ref}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:restore-fn ast-walker-restore,
                   :park-entry-fns {:stream/next
                                    (fn [_s _e r]
                                      {:k (:next k),
                                       :env env,
                                       :reason :next,
                                       :cursor-ref (:cursor-ref r),
                                       :stream-id (:stream-id r)})}})]
            (if blocked?
              (assoc state
                     :control nil
                     :k nil
                     :halted? false)
              (cesk-return state nil env (:next k) value)))
          :eval-resume-val
          (let [resume-val (:value state)
                parked-id (:parked-id k)]
            (engine/resume-continuation
              state
              parked-id
              resume-val
              (fn [new-state parked rv]
                (cesk-return new-state nil (:env parked) (:k parked) rv))))
          (throw (ex-info "Unknown continuation type"
                          {:continuation-type cont-type, :continuation k}))))
      (case type
        :literal (cesk-return state nil env k (:value node))
        :variable
        (let [value (engine/resolve-var env store primitives modules (:name node))]
          (cesk-return state nil env k value))
        :lambda (let [{:keys [params body]} node]
                  (cesk-return
                    state
                    nil
                    env
                    k
                    {:type :closure, :params params, :body body, :env env}))
        :application (cesk-return
                       state
                       (:operator node)
                       env
                       {:frame node, :next k, :env env, :type :eval-operator}
                       (:value state))
        :if (cesk-return state
                         (:test node)
                         env
                         {:frame node, :next k, :env env, :type :eval-test}
                         (:value state))
        :dao.stream.apply/call
        (let [operands (or (:operands node) [])
              op (:op node)]
          (if (empty? operands)
            (park-and-call state op [] k env)
            (cesk-return state
                         (first operands)
                         env
                         {:frame {:op op, :operands operands, :evaluated []},
                          :next k,
                          :env env,
                          :type :dao.stream.v2.apply/eval-operand}
                         (:value state))))
        :vm/gensym (let [prefix (or (:prefix node) "id")
                         [id s'] (engine/gensym state prefix)]
                     (assoc s'
                            :value id
                            :control nil
                            :halted? (nil? k)))
        :vm/store-get (cesk-return state nil env k (get store (:key node)))
        :vm/store-put (let [key (:key node)
                            value (:val node)
                            new-store (assoc store key value)]
                        (assoc state
                               :store new-store
                               :value value
                               :control nil
                               :halted? (and (not (:blocked? state)) (nil? k))))
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
                                  :halted? (and (not (:blocked? state))
                                                (nil? k))))
        :vm/current-continuation
        (cesk-return state
                     nil
                     env
                     k
                     {:type :reified-continuation, :k k, :env env})
        :vm/park (-> (engine/park-continuation state {:k k, :env env})
                     (assoc :control nil
                            :k nil))
        :vm/resume (cesk-return state
                                (:val node)
                                env
                                {:type :eval-resume-val,
                                 :parked-id (:parked-id node),
                                 :next k,
                                 :env env}
                                (:value state))
        :stream/make (let [capacity (or (:buffer node)
                                        vm/default-stream-capacity)
                           effect {:effect :stream/make, :capacity capacity}
                           {:keys [state value]} (engine/handle-effect
                                                   state
                                                   effect
                                                   {:restore-fn
                                                    ast-walker-restore})]
                       (cesk-return state nil env k value))
        :stream/put
        (cesk-return
          state
          (:target node)
          env
          {:frame node, :next k, :env env, :type :eval-stream-put-target}
          (:value state))
        :stream/cursor
        (cesk-return
          state
          (:source node)
          env
          {:frame node, :next k, :env env, :type :eval-stream-cursor-source}
          (:value state))
        :stream/next
        (cesk-return
          state
          (:source node)
          env
          {:frame node, :next k, :env env, :type :eval-stream-next-cursor}
          (:value state))
        (throw (ex-info "Unknown AST node type" {:type type, :node node}))))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- ast-walker-restore
  ([base entry] (ast-walker-restore base entry (:value entry)))
  ([base entry val] (cesk-return base nil (:env entry) (:k entry) val)))


(defn- resume-from-run-queue
  "Pop first entry from the ready-queue and resume it as the active
   computation. Returns updated state or nil if the queue is empty."
  [state]
  (engine/resume-from-run-queue state ast-walker-restore))


(defn- ast-walker-run-active-continuation
  "Hot loop that keeps CESK state in host locals instead of an immutable
   record. Inlines common transitions to reduce allocation overhead."
  [^ASTWalkerVM vm-init control-init env-init k-init val-init]
  (loop [control control-init
         env env-init
         k k-init
         val val-init
         vm vm-init]
    (let [node control
          type (:type node)]
      (cond
        (and (nil? node) k)
        (let [cont-type (:type k)]
          (case cont-type
            :eval-operator
            (let [frame (:frame k)
                  fn-value val
                  operands (:operands frame)
                  saved-env (or (:env k) env)]
              (if (empty? operands)
                (cond (= :closure (:type fn-value))
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
                            (if (or (:blocked? res)
                                    (and (nil? (:control res)) (nil? (:k res))))
                              res
                              (recur (:control res)
                                     (:env res)
                                     (:k res)
                                     (:value res)
                                     res)))
                          (recur nil saved-env (:next k) result vm)))
                      :else (throw (ex-info "Cannot apply non-function"
                                            {:fn fn-value})))
                (let [updated-frame (assoc frame
                                           :operator-evaluated? true
                                           :fn fn-value)]
                  (recur (first operands)
                         saved-env
                         (assoc k
                                :type :eval-operand
                                :frame updated-frame)
                         nil
                         vm))))
            :eval-operand
            (let [frame (:frame k)
                  operand-value val
                  evaluated (conj (or (:evaluated frame) []) operand-value)
                  operands (:operands frame)
                  saved-env (or (:env k) env)]
              (if (= (count evaluated) (count operands))
                (let [fn-value (:fn frame)]
                  (cond (= :closure (:type fn-value))
                        (let [{:keys [params body], closure-env :env} fn-value
                              extended-env (merge closure-env
                                                  (zipmap params evaluated))]
                          (recur body extended-env (:next k) val vm))
                        (fn? fn-value)
                        (let [result (apply fn-value evaluated)]
                          (if (module/effect? result)
                            (let [state (cesk-return vm control env k val)
                                  res (handle-primitive-result state
                                                               result
                                                               (:next k)
                                                               saved-env)]
                              (if (or (:blocked? res)
                                      (and (nil? (:control res))
                                           (nil? (:k res))))
                                res
                                (recur (:control res)
                                       (:env res)
                                       (:k res)
                                       (:value res)
                                       res)))
                            (recur nil saved-env (:next k) result vm)))
                        :else (throw (ex-info "Cannot apply non-function"
                                              {:fn fn-value}))))
                (let [next-idx (count evaluated)
                      next-node (nth operands next-idx)
                      updated-frame (assoc frame :evaluated evaluated)]
                  (recur next-node
                         saved-env
                         (assoc k :frame updated-frame)
                         nil
                         vm))))
            :eval-test (let [frame (:frame k)
                             test-value val
                             saved-env (or (:env k) env)
                             branch (if test-value
                                      (:consequent frame)
                                      (:alternate frame))]
                         (recur branch saved-env (:next k) test-value vm))
            (let [state (cesk-return vm control env k val)
                  next (cesk-transition state nil)]
              (if (or (:blocked? next)
                      (and (nil? (:control next)) (nil? (:k next))))
                next
                (recur (:control next)
                       (:env next)
                       (:k next)
                       (:value next)
                       next)))))
        node (case type
               :literal (recur nil env k (:value node) vm)
               :variable (let [v (engine/resolve-var env
                                                     (:store vm)
                                                     (:primitives vm)
                                                     (:modules vm)
                                                     (:name node))]
                           (recur nil env k v vm))
               :lambda (recur nil
                              env
                              k
                              {:type :closure,
                               :params (:params node),
                               :body (:body node),
                               :env env}
                              vm)
               :application
               (recur (:operator node)
                      env
                      {:frame node, :next k, :env env, :type :eval-operator}
                      val
                      vm)
               :if (recur (:test node)
                          env
                          {:frame node, :next k, :env env, :type :eval-test}
                          val
                          vm)
               (let [state (cesk-return vm node env k val)
                     next (cesk-transition state nil)]
                 (if (or (:blocked? next)
                         (and (nil? (:control next)) (nil? (:k next))))
                   next
                   (recur (:control next)
                          (:env next)
                          (:k next)
                          (:value next)
                          next))))
        :else (let [result (cesk-return vm control env k val)]
                (cond (:blocked? result)
                      (let [v' (engine/check-wait-set result)]
                        (if-let [resumed (resume-from-run-queue v')]
                          (recur (:control resumed)
                                 (:env resumed)
                                 (:k resumed)
                                 (:value resumed)
                                 resumed)
                          v'))
                      (seq (or (:ready-queue result) []))
                      (if-let [resumed (resume-from-run-queue result)]
                        (recur (:control resumed)
                               (:env resumed)
                               (:k resumed)
                               (:value resumed)
                               resumed)
                        result)
                      :else result))))))


;; =============================================================================
;; ASTWalkerVM Protocol Implementation
;; =============================================================================

(defn- vm-step
  "Execute one step of ASTWalkerVM. Returns updated VM."
  [^ASTWalkerVM vm]
  (cesk-transition vm nil))


(defn- vm-halted?
  [^ASTWalkerVM vm]
  (engine/halted-with-empty-queue? vm))


(defn- vm-blocked?
  [^ASTWalkerVM vm]
  (engine/vm-blocked? vm))


(defn- vm-value
  [^ASTWalkerVM vm]
  (engine/vm-value vm))


(defn vm-load-program
  "Load one datom batch into the VM: the existing datom-to-AST conversion
   plus the execution-field updates. This is the loader host composition
   hands to `yin.vm.v2.stream-observer/run-on-stream` alongside
   `engine/ready-for-ingress?` and the VM's runner."
  [^ASTWalkerVM vm datoms]
  (let [ast (vm/datoms->ast datoms)]
    (assoc vm
           :program ast
           :control ast
           :halted? false
           :blocked? false
           :value nil)))


(defn- vm-reset
  "Reset execution state, preserving the loaded AST program."
  [^ASTWalkerVM vm]
  (assoc vm
         :control (:program vm)
         :k nil
         :halted? (nil? (:program vm))
         :value nil
         :blocked? false))


(defn- ast-walker-run-scheduler
  "The raw runner: already-loaded work only, through the shared scheduler
   loop. `ffi/maybe-run` wraps this for bridge dispatch, and `vm/run` stops
   here — no program stream is polled."
  [vm]
  (engine/run-loop vm
                   engine/active-continuation?
                   vm-step
                   resume-from-run-queue
                   ast-walker-restore))


(defn- vm-eval
  "Evaluate an AST: convert it to datoms, load it, and run. When ast is
   non-nil it is loaded first; when nil, the current state resumes. Either
   way this is direct evaluation of supplied work: it does not observe or
   drain any independently queued program input, which is the observer
   composition's job."
  [^ASTWalkerVM vm ast]
  (let [initial-env (:env vm)
        res (if ast
              (-> vm
                  (vm-load-program (vm/ast->datoms ast))
                  (vm/run))
              (vm/run vm))]
    (engine/restore-initial-env initial-env res)))


(extend-type ASTWalkerVM
  vm/IVM
  (step [vm]
    (telemetry/emit-snapshot
      (if (engine/ready-for-ingress? vm) vm (vm-step vm))
      :step))
  (run [vm] (ffi/maybe-run vm ast-walker-run-scheduler))
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
        (if (nil? k) acc (recur (:next k) (conj acc k)))))))


(defn create-vm
  "Create a new ASTWalkerVM.

   Options:
     :env           initial lexical environment
     :primitives    primitive operations map
     :modules       module registry value (see `yin.vm.v2.module`)
     :make-stream   (fn [capacity] -> create outcome); no default
     :call-in       explicit inbound request handle
     :call-out      explicit outbound response handle
     :call-capacity capacity for a constructed FFI pair
     :bridge        host FFI handlers

   There is no `:in-stream`: program observation belongs to
   `yin.vm.v2.stream-observer`, and an obsolete `:in-stream` option is
   rejected here, before any FFI resource is allocated.

   Construction is all-or-nothing: creating the FFI pair and minting the
   call-out cursor are stream operations, and any non-`ok` outcome fails here
   rather than leaving a half-built VM. The bridge cursor is minted by
   `ffi/attach` below; the program cursor belongs to observer attachment."
  ([] (create-vm {}))
  ([opts]
   (when (contains? opts :in-stream)
     (throw (ex-info
              "Program observation moved to yin.vm.v2.stream-observer: a VM no longer accepts :in-stream"
              {:in-stream (:in-stream opts)})))
   (let [env (or (:env opts) {})
         base (vm/empty-state
                (assoc (select-keys opts
                                    [:primitives :modules :make-stream :call-in
                                     :call-out :call-capacity])
                       :telemetry (:telemetry opts)
                       :vm-model :ast-walker))]
     (-> (map->ASTWalkerVM (merge base
                                  {:bridge nil,
                                   :program nil,
                                   :control nil,
                                   :env env,
                                   :k nil,
                                   :value nil,
                                   :halted? true,
                                   :blocked? false}))
         (telemetry/install :ast-walker)
         (ffi/attach (:bridge opts))
         (telemetry/emit-snapshot :init)))))
