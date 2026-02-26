(ns yin.vm.stack
  "Stack-based bytecode VM for the Yin language.

   A stack machine implementation where:
   - Operands are pushed/popped from an explicit stack
   - Call frames are managed via first-class continuations (:k)
   - The AST as :yin/ datoms (from vm/ast->datoms) is the canonical source.

   This namespace provides:
   1. Stack assembly: project :yin/ datoms to symbolic stack instructions
   2. Assembly to numeric bytecode encoding (assemble)
   3. Numeric bytecode VM (step, run via protocols)"
  (:require
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.engine :as engine])
  #?(:cljs
     (:require-macros
       [yin.vm :refer [opcase]])))


;; =============================================================================
;; Stack VM
;; =============================================================================


;; =============================================================================
;; VM Records
;; =============================================================================

(defrecord StackVM
  [blocked    ; true if blocked
   bytecode   ; bytecode vector
   call-stack ; call frames for function returns
   env        ; lexical environment
   halted     ; true if execution completed
   id-counter ; unique ID counter
   parked     ; parked continuations
   pc         ; program counter
   pool       ; constant pool
   primitives ; primitive operations
   run-queue  ; vector of runnable continuations
   stack      ; operand stack
   store      ; heap memory
   value      ; final result value
   wait-set   ; vector of parked continuations waiting on
   ;; streams
   ])


;; =============================================================================
;; Datoms -> Stack Assembly
;; =============================================================================

(defn ast-datoms->asm
  "Project :yin/ datoms to symbolic stack assembly.

   Instructions:
     [:push v]           - push literal value v
     [:load name]        - push value of variable name
     [:call argc]        - pop function and argc args, call, push result
     [:tailcall argc]    - pop function and argc args, tail-call closure
     [:lambda params label] - push closure (captures env, body follows)
     [:branch label]     - pop condition, if true jump to label
     [:jump label]       - unconditional jump to label
     [:return]           - return top of stack
     [:label name]       - pseudo-instruction for jump targets
     [:gensym prefix]    - push generated unique ID
     [:store-get key]    - push value from store
     [:store-put key]    - pop value, write to store, push value back
     [:stream-make buf]  - push new stream ref
     [:stream-put]       - pop val and stream-ref, put val, push val
     [:stream-cursor]    - pop stream-ref, push cursor-ref
     [:stream-next]      - pop cursor-ref, push next value (may block)
     [:stream-close]     - pop stream-ref, close it, push nil
     [:park]             - park current continuation
     [:resume]           - pop val and parked-id, resume parked cont
     [:current-cont]     - push reified continuation"
  [ast-as-datoms]
  (let [{:keys [get-attr root-id]} (engine/index-datoms ast-as-datoms)
        instructions (atom [])
        emit! (fn [instr] (swap! instructions conj instr))
        label-counter (atom 0)
        gen-label! (fn [prefix]
                     (keyword (str prefix "-" (swap! label-counter inc))))]
    (letfn
      [(compile-node
         ([e] (compile-node e false))
         ([e tail?]
          (case (get-attr e :yin/type)
            :literal (emit! [:push (get-attr e :yin/value)])
            :variable (emit! [:load (get-attr e :yin/name)])
            :lambda (let [params (get-attr e :yin/params)
                          body-node (get-attr e :yin/body)
                          skip-label (gen-label! "after-lambda")]
                      (emit! [:lambda params skip-label])
                      (compile-node body-node true)
                      (emit! [:return])
                      (emit! [:label skip-label]))
            :application
            (let [op-node (get-attr e :yin/operator)
                  operand-nodes (get-attr e :yin/operands)]
              (compile-node op-node)
              (doseq [arg-node operand-nodes] (compile-node arg-node))
              (emit! [(if tail? :tailcall :call) (count operand-nodes)]))
            :if (let [test-node (get-attr e :yin/test)
                      cons-node (get-attr e :yin/consequent)
                      alt-node (get-attr e :yin/alternate)
                      cons-label (gen-label! "then")
                      end-label (gen-label! "end")]
                  (compile-node test-node)
                  (emit! [:branch cons-label])
                  (compile-node alt-node tail?)
                  (emit! [:jump end-label])
                  (emit! [:label cons-label])
                  (compile-node cons-node tail?)
                  (emit! [:label end-label]))
            ;; VM primitives
            :vm/gensym (emit! [:gensym (or (get-attr e :yin/prefix) "id")])
            :vm/store-get (emit! [:store-get (get-attr e :yin/key)])
            :vm/store-put (let [val-node (get-attr e :yin/val)]
                            (emit! [:push val-node])
                            (emit! [:store-put (get-attr e :yin/key)]))
            ;; Stream operations
            :stream/make (emit! [:stream-make
                                 (or (get-attr e :yin/buffer) 1024)])
            :stream/put (let [target-node (get-attr e :yin/target)
                              val-node (get-attr e :yin/val)]
                          (compile-node val-node)
                          (compile-node target-node)
                          (emit! [:stream-put]))
            :stream/cursor (let [source-node (get-attr e :yin/source)]
                             (compile-node source-node)
                             (emit! [:stream-cursor]))
            :stream/next (let [source-node (get-attr e :yin/source)]
                           (compile-node source-node)
                           (emit! [:stream-next]))
            :stream/close (let [source-node (get-attr e :yin/source)]
                            (compile-node source-node)
                            (emit! [:stream-close]))
            ;; Continuation primitives
            :vm/park (emit! [:park])
            :vm/resume (do (emit! [:push (get-attr e :yin/parked-id)])
                           (emit! [:push (get-attr e :yin/val)])
                           (emit! [:resume]))
            :vm/current-continuation (emit! [:current-cont])
            (throw (ex-info "Unknown node type in stack assembly compilation"
                            {:type (get-attr e :yin/type), :entity e})))))]
      (compile-node root-id true) @instructions)))


(defn assemble
  "Convert symbolic stack assembly to numeric bytecode.

   Returns {:bytecode [int...] :pool [value...] :source-map {byte-offset instr-index}}"
  [instructions]
  (let [pool (atom [])
        pool-index (atom {})
        add-constant (fn [val]
                       (if-let [idx (get @pool-index val)]
                         idx
                         (let [idx (count @pool)]
                           (swap! pool conj val)
                           (swap! pool-index assoc val idx)
                           idx)))
        ;; Pass 1: Measure sizes and record label positions.
        label-offsets (atom {})
        current-offset (atom 0)
        ;; Helper to size instruction
        instr-size (fn [instr]
                     (case (first instr)
                       :push 2
                       :load 2
                       :lambda 4 ; op + params_idx + 2 byte len
                       :call 2
                       :tailcall 2
                       :branch 3
                       :jump 3
                       :return 1
                       :label 0
                       :gensym 2
                       :store-get 2
                       :store-put 2
                       :stream-make 2
                       :stream-put 1
                       :stream-cursor 1
                       :stream-next 1
                       :stream-close 1
                       :park 1
                       :resume 1
                       :current-cont 1
                       0))]
    ;; Pass 1: Calculate label offsets
    (doseq [instr instructions]
      (if (= :label (first instr))
        (swap! label-offsets assoc (second instr) @current-offset)
        (swap! current-offset + (instr-size instr))))
    ;; Pass 2: Emit bytes
    (let [bytes (atom [])
          source-map (atom {})
          emit-byte! (fn [b] (swap! bytes conj b))
          emit-short! (fn [s]
                        (emit-byte! (bit-shift-right (bit-and s 0xFF00) 8))
                        (emit-byte! (bit-and s 0xFF)))
          ;; Re-calculate current offset during emission for relative jumps
          emit-offset (atom 0)]
      (doseq [[idx instr] (map-indexed vector instructions)]
        (let [[op arg1 arg2] instr
              start-offset @emit-offset]
          ;; Record source map
          (when (not= :label op) (swap! source-map assoc start-offset idx))
          (case op
            :push (let [idx (add-constant arg1)]
                    (emit-byte! (vm/opcode-table :literal))
                    (emit-byte! idx)
                    (swap! emit-offset + 2))
            :load (let [idx (add-constant arg1)]
                    (emit-byte! (vm/opcode-table :load-var))
                    (emit-byte! idx)
                    (swap! emit-offset + 2))
            :lambda (let [params-idx (add-constant arg1)
                          target-label arg2
                          target-offset (get @label-offsets target-label)
                          ;; Body length = target-offset - (current-offset
                          ;; + 4)
                          body-len (- target-offset (+ @emit-offset 4))]
                      (emit-byte! (vm/opcode-table :lambda))
                      (emit-byte! params-idx)
                      (emit-short! body-len)
                      (swap! emit-offset + 4))
            :call (do (emit-byte! (vm/opcode-table :call))
                      (emit-byte! arg1)
                      (swap! emit-offset + 2))
            :tailcall (do (emit-byte! (vm/opcode-table :tailcall))
                          (emit-byte! arg1)
                          (swap! emit-offset + 2))
            :branch (let [target-label arg1
                          target-offset (get @label-offsets target-label)
                          ;; Relative offset = target-offset -
                          ;; (current-offset + 3)
                          rel-offset (- target-offset (+ @emit-offset 3))]
                      (emit-byte! (vm/opcode-table :branch))
                      (emit-short! rel-offset)
                      (swap! emit-offset + 3))
            :jump (let [target-label arg1
                        target-offset (get @label-offsets target-label)
                        rel-offset (- target-offset (+ @emit-offset 3))]
                    (emit-byte! (vm/opcode-table :jump))
                    (emit-short! rel-offset)
                    (swap! emit-offset + 3))
            :return (do (emit-byte! (vm/opcode-table :return))
                        (swap! emit-offset + 1))
            :label nil ; No code emitted
            :gensym (let [idx (add-constant arg1)]
                      (emit-byte! (vm/opcode-table :gensym))
                      (emit-byte! idx)
                      (swap! emit-offset + 2))
            :store-get (let [idx (add-constant arg1)]
                         (emit-byte! (vm/opcode-table :store-get))
                         (emit-byte! idx)
                         (swap! emit-offset + 2))
            :store-put (let [idx (add-constant arg1)]
                         (emit-byte! (vm/opcode-table :store-put))
                         (emit-byte! idx)
                         (swap! emit-offset + 2))
            :stream-make (let [idx (add-constant arg1)]
                           (emit-byte! (vm/opcode-table :stream-make))
                           (emit-byte! idx)
                           (swap! emit-offset + 2))
            :stream-put (do (emit-byte! (vm/opcode-table :stream-put))
                            (swap! emit-offset + 1))
            :stream-cursor (do (emit-byte! (vm/opcode-table :stream-cursor))
                               (swap! emit-offset + 1))
            :stream-next (do (emit-byte! (vm/opcode-table :stream-next))
                             (swap! emit-offset + 1))
            :stream-close (do (emit-byte! (vm/opcode-table :stream-close))
                              (swap! emit-offset + 1))
            :park (do (emit-byte! (vm/opcode-table :park))
                      (swap! emit-offset + 1))
            :resume (do (emit-byte! (vm/opcode-table :resume))
                        (swap! emit-offset + 1))
            :current-cont (do (emit-byte! (vm/opcode-table :current-cont))
                              (swap! emit-offset + 1)))))
      {:bytecode @bytes, :pool @pool, :source-map @source-map})))


(defn- fetch-short-unsigned
  [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))


(defn- fetch-short-signed
  [bytes pc]
  (let [u (fetch-short-unsigned bytes pc)] (if (>= u 32768) (- u 65536) u)))


(defn- snap-frame
  "Snapshot current VM frame for parking or reification."
  [{:keys [bytecode env call-stack pool]} stack pc]
  {:pc pc,
   :bytecode bytecode,
   :stack stack,
   :env env,
   :call-stack call-stack,
   :pool pool})


(defn- handle-native-result
  "Handle result of calling a native fn. Routes effects through handle-effect."
  [state result stack-rest next-pc park-entry-fns]
  (if (module/effect? result)
    (let [{:keys [state value blocked?]} (engine/handle-effect
                                           state
                                           result
                                           {:park-entry-fns park-entry-fns})]
      (if blocked?
        state
        (assoc state
               :stack (conj stack-rest value)
               :pc next-pc)))
    (assoc state
           :stack (conj stack-rest result)
           :pc next-pc)))


(defn- apply-op
  [state argc tail?]
  (let [{:keys [pc bytecode stack env call-stack pool _store id-counter]} state
        n (count stack)
        args (subvec stack (- n argc) n)
        fn-val (nth stack (- n argc 1))
        stack-rest (subvec stack 0 (- n argc 1))
        next-pc (+ pc 2)]
    (cond (fn? fn-val)
          (let [res (apply fn-val args)]
            (handle-native-result
              state
              res
              stack-rest
              next-pc
              {:stream/put (fn [_s _e r]
                             (merge (snap-frame state stack-rest next-pc)
                                    {:reason :put,
                                     :stream-id (:stream-id r),
                                     :datom (:val res)})),
               :stream/next (fn [_s _e r]
                              (merge (snap-frame state stack-rest next-pc)
                                     {:reason :next,
                                      :cursor-ref (:cursor-ref r),
                                      :stream-id (:stream-id r)}))}))
          (= :closure (:type fn-val))
          (let [{clo-params :params,
                 clo-body :body-bytes,
                 clo-env :env,
                 clo-pool :pool}
                fn-val
                new-env (merge clo-env (zipmap clo-params args))
                frame (snap-frame state stack-rest next-pc)]
            (if tail?
              ;; TCO: reuse current frame by
              ;; jumping directly to callee body.
              (assoc state
                     :pc 0
                     :bytecode clo-body
                     :stack []
                     :env new-env
                     :pool (or clo-pool pool)
                     :call-stack call-stack)
              (assoc state
                     :pc 0
                     :bytecode clo-body
                     :stack []
                     :env new-env
                     :pool (or clo-pool pool)
                     :call-stack (conj call-stack frame))))
          :else (throw (ex-info "Cannot apply non-function" {:fn fn-val})))))


(defn- stack-step
  "Execute one stack VM instruction. Returns updated state.
   When execution completes, :halted is true and :value contains the result."
  [state]
  (let [{:keys [pc bytecode stack env call-stack pool store primitives
                id-counter]}
        state]
    (if (>= pc (count bytecode))
      ;; End of bytes: return top of stack or pop frame
      (let [result (peek stack)]
        (if (empty? call-stack)
          (assoc state
                 :halted true
                 :value result)
          (let [frame (peek call-stack)
                rest-frames (pop call-stack)]
            (assoc state
                   :pc (:pc frame)
                   :bytecode (:bytecode frame)
                   :stack (conj (:stack frame) result)
                   :env (:env frame)
                   :call-stack rest-frames))))
      (let [op (nth bytecode pc)]
        (vm/opcase
          op
          :literal
          (let [val-idx (nth bytecode (inc pc))
                val (nth pool val-idx)]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack val)))
          :load-var
          (let [sym-idx (nth bytecode (inc pc))
                sym (nth pool sym-idx)
                val (engine/resolve-var env store primitives sym)]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack val)))
          :lambda
          (let [params-idx (nth bytecode (inc pc))
                params (nth pool params-idx)
                body-len (fetch-short-unsigned bytecode (+ pc 2))
                body-start (+ pc 4)
                body-bytes (subvec bytecode body-start (+ body-start body-len))
                closure {:type :closure,
                         :params params,
                         :body-bytes body-bytes,
                         :env env,
                         :pool pool}]
            (assoc state
                   :pc (+ pc 4 body-len)
                   :stack (conj stack closure)))
          :call
          (let [argc (nth bytecode (inc pc))] (apply-op state argc false))
          :tailcall
          (let [argc (nth bytecode (inc pc))] (apply-op state argc true))
          :branch
          (let [offset (fetch-short-signed bytecode (inc pc))
                condition (peek stack)
                new-stack (pop stack)]
            (assoc state
                   :pc (if condition (+ pc 3 offset) (+ pc 3))
                   :stack new-stack))
          :jump
          (let [offset (fetch-short-signed bytecode (inc pc))]
            (assoc state :pc (+ pc 3 offset)))
          :return
          (let [result (peek stack)]
            (if (empty? call-stack)
              (assoc state
                     :halted true
                     :value result)
              (let [frame (peek call-stack)
                    rest-frames (pop call-stack)]
                (assoc state
                       :pc (:pc frame)
                       :bytecode (:bytecode frame)
                       :stack (conj (:stack frame) result)
                       :env (:env frame)
                       :pool (or (:pool frame) pool)
                       :call-stack rest-frames))))
          :gensym
          (let [prefix-idx (nth bytecode (inc pc))
                prefix (nth pool prefix-idx)
                [id s'] (engine/gensym state prefix)]
            (assoc s'
                   :pc (+ pc 2)
                   :stack (conj stack id)))
          :store-get
          (let [key-idx (nth bytecode (inc pc))
                key (nth pool key-idx)
                val (get store key)]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack val)))
          :store-put
          (let [key-idx (nth bytecode (inc pc))
                key (nth pool key-idx)
                val (peek stack)
                new-store (assoc store key val)]
            (assoc state
                   :pc (+ pc 2)
                   :store new-store))
          :stream-make
          (let [buf-idx (nth bytecode (inc pc))
                buf (nth pool buf-idx)
                effect {:effect :stream/make, :capacity buf}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack value)))
          :stream-put ; OP_STREAM_PUT - pop stream-ref, pop val
          (let [stream-ref (peek stack)
                stack1 (pop stack)
                val (peek stack1)
                stack-rest (pop stack1)
                effect {:effect :stream/put, :stream stream-ref, :val val}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns
                   {:stream/put
                    (fn [_s _e r]
                      (merge (snap-frame state (conj stack-rest val) (+ pc 1))
                             {:reason :put,
                              :stream-id (:stream-id r),
                              :datom val}))}})]
            (if blocked?
              state
              (assoc state
                     :pc (+ pc 1)
                     :stack (conj stack-rest value))))
          :stream-cursor ; OP_STREAM_CURSOR - pop stream-ref
          (let [stream-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/cursor, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (assoc state
                   :pc (+ pc 1)
                   :stack (conj stack-rest value)))
          :stream-next ; OP_STREAM_NEXT - pop cursor-ref
          (let [cursor-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/next, :cursor cursor-ref}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns
                   {:stream/next
                    (fn [_s _e r]
                      (merge (snap-frame state stack-rest (+ pc 1))
                             {:reason :next,
                              :cursor-ref (:cursor-ref r),
                              :stream-id (:stream-id r)}))}})]
            (if blocked?
              state
              (assoc state
                     :pc (+ pc 1)
                     :stack (conj stack-rest value))))
          :stream-close ; OP_STREAM_CLOSE - pop stream-ref
          (let [stream-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/close, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (assoc state
                   :pc (+ pc 1)
                   :stack (conj stack-rest value)))
          :park
          (engine/park-continuation state (snap-frame state stack pc))
          :resume ; OP_RESUME - pop val, pop parked-id
          (let [resume-val (peek stack)
                stack1 (pop stack)
                parked-id (peek stack1)]
            (engine/resume-continuation state
                                        parked-id
                                        resume-val
                                        (fn [new-state parked rv]
                                          (assoc new-state
                                                 :pc (+ 1 (:pc parked))
                                                 :bytecode (:bytecode parked)
                                                 :stack (conj (:stack parked) rv)
                                                 :env (:env parked)
                                                 :call-stack (:call-stack parked)
                                                 :pool (:pool parked)))))
          :current-cont
          (let [cont (assoc (snap-frame state stack pc)
                            :type :reified-continuation)]
            (assoc state
                   :pc (+ pc 1)
                   :stack (conj stack cont)))
          (throw (ex-info "Unknown Opcode" {:op op, :pc pc})))))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- resume-from-run-queue
  "Pop first entry from run-queue and resume it as the active computation.
   Returns updated state or nil if queue is empty."
  [state]
  (engine/resume-from-run-queue state
                                (fn [base entry]
                                  (assoc base
                                         :pc (:pc entry)
                                         :bytecode (:bytecode entry)
                                         :stack (conj (:stack entry) (:value entry))
                                         :env (:env entry)
                                         :call-stack (:call-stack entry)
                                         :pool (:pool entry)))))


;; =============================================================================
;; StackVM Protocol Implementation
;; =============================================================================

(defn- stack-vm-halted?
  "Returns true if VM has halted."
  [^StackVM vm]
  (engine/halted-with-empty-queue? vm))


(defn- stack-vm-blocked?
  "Returns true if VM is blocked."
  [^StackVM vm]
  (engine/vm-blocked? vm))


(defn- stack-vm-value
  "Returns the current value."
  [^StackVM vm]
  (engine/vm-value vm))


(defn- stack-vm-load-program
  "Load bytecode into the VM.
   Expects {:bytecode [...] :pool [...]}."
  [^StackVM vm {:keys [bytecode pool]}]
  (assoc vm
         :pc 0
         :bytecode (vec bytecode)
         :stack []
         :call-stack []
         :pool pool
         :halted false
         :value nil
         :blocked false))


(defn- stack-vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, compiles through datoms -> asm -> bytecode and loads.
   When nil, resumes from current state."
  [^StackVM vm ast]
  (let [v (if ast
            (let [datoms (vm/ast->datoms ast)
                  asm (ast-datoms->asm datoms)
                  compiled (assemble asm)]
              (stack-vm-load-program vm compiled))
            vm)]
    (engine/run-loop v
                     (fn [v] (and (not (:blocked v)) (not (:halted v))))
                     stack-step
                     resume-from-run-queue)))


(extend-type StackVM
  vm/IVMStep
  (step [vm] (stack-step vm))
  (halted? [vm] (stack-vm-halted? vm))
  (blocked? [vm] (stack-vm-blocked? vm))
  (value [vm] (stack-vm-value vm))
  vm/IVMRun
  (run [vm] (vm/eval vm nil))
  vm/IVMLoad
  (load-program [vm program] (stack-vm-load-program vm program))
  vm/IVMEval
  (eval [vm ast] (stack-vm-eval vm ast))
  vm/IVMState
  (control [vm] {:pc (:pc vm), :bytecode (:bytecode vm)})
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm] (:call-stack vm)))


(defn create-vm
  "Create a new StackVM with optional opts map.
   Accepts {:env map, :primitives map}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})]
     (map->StackVM (merge (vm/empty-state (select-keys opts [:primitives]))
                          {:pc 0,
                           :bytecode [],
                           :stack [],
                           :env env,
                           :call-stack [],
                           :pool [],
                           :halted false,
                           :value nil,
                           :blocked false})))))
