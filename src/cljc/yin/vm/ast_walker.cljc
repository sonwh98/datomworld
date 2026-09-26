(ns yin.vm.ast-walker
  "Direct AST interpreter for the Yin Abstract Machine on DaoStream v2.

   A CESK machine over raw AST maps with a linked continuation, a ready queue
   and a wait set. `step` and `run` execute already-loaded work only: program
   input is observed above the VM by `dao.stream.observer`, which hands
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

   No macro branch: evaluators know nothing about macros (decision 1 of
   `yin.vm.macro.md`). The `:lambda` arm ignores `:macro?`; there is no macro
   flag on closures and no expansion ledger. Expansion is a process on the
   syntax side of a medium boundary, so programs arrive here already expanded."
  (:require [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.telemetry :as telemetry]))


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
   rows           ; {row-id row}: every row loaded or attached, grow-only
   row-index      ; {[body-node params] lambda-row-id} over `rows`
   row-nodes      ; {row-id node}: each held row decoded once
   resources      ; private engine resources: the link pair
   origin         ; this task's origin tag for link ids
   origins        ; counter for the origin tags of install children
   ancestry       ; the modules installing on this task's install chain
   installs       ; {module-name install}: the install children
   module-stores  ; {manifest-address store}: linked modules' stores
   link-retired   ; {link-id :restored | :abandoned}
   link-diagnostics ; skipped link responses not yet taken
   capability-secret ; this task's secret; every reference is sealed by it
   secret-source  ; the composition's minting of child task secrets
   attach-stream  ; the composition's attacher for lowered streams
   ])


(defn- cesk-return
  "Create a new ASTWalkerVM with updated CESK fields in a single allocation.
   Preserves blocked, store, and scheduler fields from vm.
   Derives :halted? from the new CESK state."
  [^ASTWalkerVM vm control env k val]
  (let [blocked (:blocked? vm)
        ;; a halt leaves no module store context (yin.vm.linker.md 7.3)
        env (if (and (nil? control) (nil? k))
              (engine/without-store-of env)
              env)]
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
                   (:primitive-profiles vm)
                   (:primitive-canonical-names vm)
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
                   (:telemetry-eid vm)
                   (:vm-model vm)
                   (:vm-id vm)
                   (:rows vm)
                   (:row-index vm)
                   (:row-nodes vm)
                   (:resources vm)
                   (:origin vm)
                   (:origins vm)
                   (:ancestry vm)
                   (:installs vm)
                   (:module-stores vm)
                   (:link-retired vm)
                   (:link-diagnostics vm)
                   (:capability-secret vm)
                   (:secret-source vm)
                   (:attach-stream vm))))


(defn- closure-of
  "The closure a `:lambda` `node` instantiates. A node the row decoder
   annotated with its source row id (`vm-load-rows`, `attach-image`)
   passes the id on as `:lambda` (yin.vm.linker.md section 7.3, the
   walker rule); a node from any other loader carries none."
  [node params body env]
  (let [closure {:type :closure, :params params, :body body, :env env}]
    (if-let [id (::lambda-row (meta node))]
      (assoc closure :lambda id)
      closure)))


(defn- check-params!
  "Rule R at transition time: the definition operator is never a binder."
  [params]
  (when-let [p (some #(when (vm/reserved-name? %) %) params)]
    (vm/refuse-reserved! :binder p)))


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
                                              :stream-id (:stream-id r)}),
                              :module/require (fn [_s _e _r]
                                                {:k k, :env env})}})]
      (if blocked?
        (assoc state
               :control nil
               :k nil
               :halted? false)
        (cesk-return state nil env k value)))
    (cesk-return state nil env k result)))


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
  (let [{:keys [call-in]} (ffi/require-call-pair! (:resources state) op)
        response-cont {:type :dao.stream.apply/eval-call, :next k, :env env}
        parked (engine/park-continuation state {:k response-cont, :env env})
        parked-id (get-in parked [:value :id])
        request (apply2/request parked-id op (vec args))
        result (apply2/put-request! call-in request)]
    (case (:dao.stream/outcome result)
      :dao.stream/ok
      (-> parked
          (update :wait-set
                  (fnil conj [])
                  (ffi/call-response-wait-entry parked-id k env))
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
                  {:k {:type :dao.stream.apply/request-sent,
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
                                    (:dao.stream.apply/outcome result))})))))


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
              extended-env (merge closure-env
                                  (engine/bind-params params evaluated-operands))]
          (cesk-return state body extended-env k (:value state)))
        :else (throw (ex-info "Cannot apply non-function" {:fn fn-value}))))


(defn- cesk-transition
  "Steps the CESK machine to evaluate an AST node.
   Each return path produces exactly one ASTWalkerVM allocation via
   cesk-return."
  [state ast]
  (let [{:keys [control env k primitives modules]} state
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
          :dao.stream.apply/eval-operand
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
          :dao.stream.apply/request-sent
          ;; A request retained on `full` has now been appended by the polling
          ;; wait set. The call starts waiting for its correlated response,
          ;; exactly as an immediately-sent one does.
          (-> state
              (update :wait-set
                      (fnil conj [])
                      (ffi/call-response-wait-entry (:parked-id k)
                                                    (:next k)
                                                    (:env k)))
              (telemetry/emit-snapshot :bridge {:bridge-op (:op k)})
              (assoc :control nil
                     :k nil
                     :value :yin/blocked
                     :blocked? true
                     :halted? false))
          :dao.stream.apply/eval-call
          ;; The response consumed here completes the call that
          ;; `park-and-call` recorded, so its continuation leaves `:parked`
          ;; rather than accumulating for the lifetime of the VM.
          (let [state (update state :parked dissoc (:call-id k))]
            (cesk-return state
                         nil
                         env
                         (:next k)
                         (ffi/call-result (:value state) (:call-id k))))
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
          :eval-stream-close-source
          (let [stream-ref (:value state)
                effect {:effect :stream/close, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect
                                        state
                                        effect
                                        {:restore-fn ast-walker-restore})]
            (cesk-return state nil env (:next k) value))
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
          :eval-define
          ;; The definition transition's second half: the value operand has
          ;; been evaluated, and its literal key is written. The operator
          ;; was never resolved (Rule R).
          (let [v (:value state)
                env (or (:env k) env)
                state (engine/put-active state (engine/env-store-of env)
                                         (:name k) v)]
            (cesk-return state nil env (:next k) v))
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
        (let [value (engine/resolve-var env
                                        (engine/active-store
                                          state (engine/env-store-of env))
                                        primitives modules (:name node))]
          (cesk-return state nil env k value))
        :lambda (let [{:keys [params body]} node]
                  (check-params! params)
                  (cesk-return
                    state
                    nil
                    env
                    k
                    (closure-of node params body env)))
        :application
        (if (vm/definition? node)
          ;; Rule R: a definition never resolves its operator. The key is
          ;; the literal first operand; only the value operand runs.
          (cesk-return state
                       (second (:operands node))
                       env
                       {:type :eval-define,
                        :name (vm/definition-name node),
                        :next k,
                        :env env}
                       (:value state))
          (cesk-return state
                       (:operator node)
                       env
                       {:frame node, :next k, :env env, :type :eval-operator}
                       (:value state)))
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
                          :type :dao.stream.apply/eval-operand}
                         (:value state))))
        :vm/gensym (let [prefix (or (:prefix node) "id")
                         [id s'] (engine/gensym state prefix)]
                     (cesk-return s' nil env k id))
        ;; A module closure's store nodes route to its module store, with
        ;; no ambient fallback (yin.vm.linker.md section 7.3, r7).
        :vm/store-get (do (engine/check-store-key! (:key node))
                          (cesk-return state nil env k
                                       (get (engine/active-store
                                              state
                                              (engine/env-store-of env))
                                            (:key node))))
        :vm/store-put (let [key (:key node)
                            value (:val node)]
                        (cesk-return (engine/put-active
                                       state (engine/env-store-of env)
                                       key value)
                                     nil env k value))
        :vm/store-update (let [key (:key node)
                               f (:fn node)
                               args (:args node)
                               store-of (engine/env-store-of env)
                               _ (engine/check-store-key! key)
                               current (get (engine/active-store state store-of)
                                            key)
                               new-value (apply f current args)]
                           (cesk-return (engine/put-active state store-of key
                                                           new-value)
                                        nil env k new-value))
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
        :stream/close
        (cesk-return
          state
          (:source node)
          env
          {:frame node, :next k, :env env, :type :eval-stream-close-source}
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
                            extended-env (merge closure-env
                                                (engine/bind-params params []))]
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
                                                  (engine/bind-params params
                                                                      evaluated))]
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
                                                     (engine/active-store
                                                       vm
                                                       (engine/env-store-of
                                                         env))
                                                     (:primitives vm)
                                                     (:modules vm)
                                                     (:name node))]
                           (recur nil env k v vm))
               :lambda (do (check-params! (:params node))
                           (recur nil
                                  env
                                  k
                                  (closure-of node
                                              (:params node)
                                              (:body node)
                                              env)
                                  vm))
               :application
               (if (vm/definition? node)
                 (let [next (cesk-transition (cesk-return vm node env k val)
                                             nil)]
                   (recur (:control next) (:env next) (:k next) (:value next)
                          next))
                 (recur (:operator node)
                        env
                        {:frame node, :next k, :env env, :type :eval-operator}
                        val
                        vm))
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


(defn- refuse-reserved-ast!
  "Whole-tree Rule R validation of a reconstructed AST at load time."
  [ast]
  (when-let [defect (vm/ast-reserved-defect ast)]
    (throw (ex-info "Program violates Rule R" defect))))


(defn vm-load-program
  "Load one datom batch into the VM: the existing datom-to-AST conversion
   plus the execution-field updates. This is the loader host composition
   hands to `dao.stream.observer/run-on-stream` alongside
   `engine/ready-for-ingress?` and the VM's runner.

   `contract` is required: the batch's stamp, compared with
   `vm/ast-contract` before anything else (`:contract-missing`,
   `:contract-mismatch`). The reconstructed tree is then validated whole
   against Rule R. A fresh-code producer supplies the current constant."
  [^ASTWalkerVM vm datoms contract]
  (vm/check-contract! vm/ast-contract contract)
  (let [ast (vm/datoms->ast datoms)]
    (refuse-reserved-ast! ast)
    (assoc vm
           :program ast
           :control ast
           :halted? false
           :blocked? false
           :value nil)))


(defn- decode-rows
  "Decode every row of validated `rows` into a node, extending `nodes`
   (`{row-id node}`, the nodes already held): each row is rebuilt once
   and shared as `vm/semantic-bytecode->ast` does, a row already held is
   reused as it stands, and every `:lambda` node is annotated with its
   source row id in metadata (yin.vm.linker.md section 7.3, the walker
   rule). Metadata leaves node equality alone, so a tree equals the one
   `semantic-bytecode->ast` builds."
  [rows nodes]
  (let [built (atom (or nodes {}))]
    (letfn [(build
              [id]
              (or (get @built id)
                  (let [row (get rows id)
                        tag (nth row 1)
                        node (reduce
                               (fn [m [[field kind] v]]
                                 (assoc m
                                        field
                                        (case kind
                                          :node (build v)
                                          :nodes (mapv build v)
                                          v)))
                               {:type tag}
                               (map vector
                                    (get vm/semantic-bytecode-grammar tag)
                                    (drop 2 row)))
                        node (if (= :lambda tag)
                               (vary-meta node assoc ::lambda-row id)
                               node)]
                    (swap! built assoc id node)
                    node)))]
      (doseq [id (keys rows)] (build id))
      @built)))


(defn- hold-rows
  "Add `rows` to the rows `vm` holds, decode each once into the held
   `:row-nodes`, and index each `:lambda` row among them by
   `[body-node params]`. The decode is held on the VM as data, so a
   lift's `row-node` reads it rather than rebuilding a subtree."
  [vm rows]
  (let [nodes (decode-rows rows (:row-nodes vm))]
    (assoc vm
           :rows (merge (:rows vm) rows)
           :row-nodes nodes
           :row-index (reduce-kv (fn [index id row]
                                   (if (= :lambda (nth row 1))
                                     (assoc index
                                            [(get nodes (nth row 3))
                                             (nth row 2)]
                                            id)
                                     index))
                                 (or (:row-index vm) {})
                                 rows))))


(defn vm-load-rows
  "Load one semantic-bytecode row set `{:root id, :rows {id row}}` into the
   VM: `vm/semantic-bytecode->ast` runs the §7.4 validator first and throws
   `ex-info` carrying the defect's `:rule` and `:path` (or `:id`), then
   reconstructs the map AST, and this applies the same execution-field
   updates as `vm-load-program`. This is §9.1's loader, the successor of
   the datom decode; the datom loader stays alongside it — both are legal
   per-composition choices, and a composition wires whichever loader(s) it
   wants.

   `contract` is required and compared with `vm/ast-contract` first, as
   for `vm-load-program`; S7.4 validation includes Rule R. The rows join
   the held rows and the `[node params]` index, as `attach-image` adds
   them, and every `:lambda` node of the tree carries its row id."
  [^ASTWalkerVM vm bc contract]
  (vm/check-contract! vm/ast-contract contract)
  (vm/semantic-bytecode->ast bc)
  (let [held (hold-rows vm (:rows bc))
        ast (get (:row-nodes held) (:root bc))]
    (assoc held
           :program ast
           :control ast
           :halted? false
           :blocked? false
           :value nil)))


(defn attach-image
  "Extend the code space of `vm` with the row set `bc`, non-destructively
   (yin.vm.linker.md section 7.3, act 3): `bc` is admitted as
   `vm-load-rows` admits it and its rows are added to the held rows by
   id, the `[node params]` index extended; the current node, environment,
   continuation, and store are unchanged. Rows are content-addressed, so
   a row already held is the same row."
  [^ASTWalkerVM vm bc contract]
  (vm/check-contract! vm/ast-contract contract)
  (vm/semantic-bytecode->ast bc)
  (hold-rows vm (:rows bc)))


(defn row-node
  "The node the walker's row decoder built for held row `id`, or nil when
   no such row is held. A lower reconstructs a closure's body this way.
   The decode ran once, when the row was loaded or attached."
  [vm id]
  (get (:row-nodes vm) id))


(defn closure-row
  "The `:lambda` row id a walker `closure` lifts from (yin.vm.linker.md
   section 7.3, the walker rule). A recorded `:lambda` id must name a held
   `:lambda` row whose params are the closure's and whose body decodes to
   the closure's node; a closure with none resolves through the
   `[node params]` index. Anything else is the `:yin.k/non-portable`
   refusal of kind `:unrooted-body`."
  [vm closure]
  (let [{:keys [params body lambda]} closure
        refusal {:yin.k/status :yin.k/non-portable,
                 :yin.k/kind :unrooted-body,
                 :yin.k/params params}]
    (if (some? lambda)
      (let [row (get (:rows vm) lambda)]
        (if (and row
                 (= :lambda (nth row 1))
                 (= params (nth row 2))
                 (= body (row-node vm (nth row 3))))
          lambda
          refusal))
      (get (:row-index vm) [body params] refusal))))


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
                   resume-from-run-queue))


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
                  (vm-load-program (vm/ast->datoms ast) vm/ast-contract)
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
     :primitive-profiles published primitive profile registry
     :primitive-canonical-names name -> canonical name for intentional aliases
     :modules       module registry value (see `yin.vm.module`)
     :make-stream   (fn [capacity] -> create outcome); no default
     :call-in       explicit inbound request handle
     :call-out      explicit outbound response handle
     :call-capacity capacity for a constructed FFI pair
     :bridge        host FFI handlers

   There is no `:in-stream`: program observation belongs to
   `dao.stream.observer`, and an obsolete `:in-stream` option is
   rejected here, before any FFI resource is allocated.

   Construction is all-or-nothing: creating the FFI pair and minting the
   call-out cursor are stream operations, and any non-`ok` outcome fails here
   rather than leaving a half-built VM. The bridge cursor is minted by
   `ffi/attach` below; the program cursor belongs to observer attachment."
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
                                     :call-capacity :link-request
                                     :link-response :origin :ancestry
                                     :capability-secret :secret-source
                                     :attach-stream])
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


;; =============================================================================
;; Module kernel (yin.vm.linker.md section 7.3)
;; =============================================================================
;; A walker closure lifts from its `:lambda` row by the walker rule
;; (`closure-row`) and lowers to the body the receiving decoder built for
;; that row's body slot, present after act 3 attached the rows. The
;; module store context rides in the environment, as for the semantic VM.

(defn- binding-mismatch!
  [marker]
  (throw (ex-info "Closure marker of another binding discipline or format"
                  {:reason :binding-mismatch,
                   :binding (:yin.k/binding marker),
                   :format (:yin.k/format marker),
                   :expected-format :yin.ast/code})))


(extend-type ASTWalkerVM
  module/IModuleKernel
  (link-format [_] {:format :yin.ast/code, :contract vm/ast-contract})
  (spawn-module
    [vm image {:keys [modules origin ancestry capability-secret]}]
    (vm-load-rows (create-vm
                    {:primitives (:primitives vm),
                     :primitive-profiles (:primitive-profiles vm),
                     :primitive-canonical-names
                     (:primitive-canonical-names vm),
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
                     :attach-stream (:attach-stream vm)})
                  image
                  vm/ast-contract))
  (image-identity [_ image] (:root image))
  (image-holds? [_ image segment] (contains? (:rows image) segment))
  (attach-module [vm image] (attach-image vm image vm/ast-contract))
  (lift-closure [vm closure encode]
    (let [id (closure-row vm closure)
          env (:env closure)
          store-of (engine/env-store-of env)]
      (when (map? id)
        (throw (ex-info "Walker closure has no source row" id)))
      (cond-> {:yin.k/tag :yin.k/closure,
               :yin.k/binding :named,
               :yin.k/format :yin.ast/code,
               :yin.k/segment id,
               :yin.k/entry nil,
               :yin.k/params (:params closure),
               :yin.k/env (into {}
                                (map (fn [[k x]] [k (encode x)]))
                                (engine/without-store-of env))}
        store-of (assoc :yin.k/store-of store-of))))
  (lower-closure [vm marker decode]
    (when-not (and (= :named (:yin.k/binding marker))
                   (= :yin.ast/code (:yin.k/format marker)))
      (binding-mismatch! marker))
    (let [id (:yin.k/segment marker)
          row (get (:rows vm) id)
          store-of (:yin.k/store-of marker)]
      (when-not (and row (= :lambda (nth row 1)))
        (throw (ex-info "Closure origin image is not attached"
                        {:reason :origin-not-attached, :segment id})))
      {:type :closure,
       :params (:yin.k/params marker),
       :body (row-node vm (nth row 3)),
       :env (cond-> (into {}
                          (map (fn [[k x]] [k (decode x)]))
                          (:yin.k/env marker))
              store-of (assoc engine/store-of-key store-of)),
       :lambda id})))
