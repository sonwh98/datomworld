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
            :vm/store-put (let [val (get-attr e :yin/value)]
                            (emit! [:push val])
                            (emit! [:store-put (get-attr e :yin/key)]))
            ;; Stream operations
            :stream/make (emit! [:stream-make
                                 (or (get-attr e :yin/buffer) 1024)])
            :stream/put (let [target-node (get-attr e :yin/target)
                              val-node (get-attr e :yin/val-node)]
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
                           (compile-node (get-attr e :yin/val-node))
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


(def ^:private initial-stack-capacity 32)


(defn- make-stack-array
  [n]
  #?(:clj (object-array (int n))
     :cljs (let [arr (js/Array. n)]
             (loop [i 0]
               (if (< i n)
                 (do (aset arr i nil) (recur (inc i)))
                 arr)))
     :cljd (object-array (int n))))


(defn- stack-array-length
  [arr]
  #?(:clj (alength ^objects arr)
     :cljs (.-length arr)
     :cljd (alength arr)))


(defn- stack-array-get
  [arr idx]
  #?(:clj (aget ^objects arr (int idx))
     :cljs (aget arr idx)
     :cljd (aget arr (int idx))))


(defn- stack-array-set!
  [arr idx val]
  #?(:clj (aset ^objects arr (int idx) val)
     :cljs (aset arr idx val)
     :cljd (aset arr (int idx) val)))


(defn- stack-array-grow
  [arr min-capacity]
  (let [old-len (stack-array-length arr)
        new-len (loop [len (max 1 old-len)]
                  (if (< len min-capacity)
                    (recur (* 2 len))
                    len))
        new-arr (make-stack-array new-len)]
    #?(:clj (System/arraycopy arr 0 new-arr 0 old-len)
       :cljs (loop [i 0]
               (when (< i old-len)
                 (aset new-arr i (aget arr i))
                 (recur (inc i))))
       :cljd (dotimes [i old-len]
               (aset new-arr i (aget arr i))))
    new-arr))


(defn- stack-array->vector
  [arr sp]
  (if (neg? sp)
    []
    (loop [i 0
           out (transient [])]
      (if (<= i sp)
        (recur (inc i) (conj! out (stack-array-get arr i)))
        (persistent! out)))))


(defn- stack-vector->array+sp
  [stack]
  (let [n (count stack)
        cap (max initial-stack-capacity n)
        arr (make-stack-array cap)]
    (loop [i 0]
      (when (< i n)
        (stack-array-set! arr i (nth stack i))
        (recur (inc i))))
    [arr (dec n)]))


(defn- stack-push
  [arr sp v]
  (let [next-sp (inc sp)
        arr' (if (< next-sp (stack-array-length arr))
               arr
               (stack-array-grow arr (inc next-sp)))]
    (stack-array-set! arr' next-sp v)
    [arr' next-sp]))


(defn- stack-args->vector
  [stack-arr fn-pos argc]
  (loop [i 0
         out (transient [])]
    (if (< i argc)
      (recur (inc i)
             (conj! out (stack-array-get stack-arr (+ fn-pos 1 i))))
      (persistent! out))))


(defn- invoke-native-fast
  [fn-val stack-arr fn-pos argc]
  (case argc
    0 (fn-val)
    1 (fn-val (stack-array-get stack-arr (inc fn-pos)))
    2 (fn-val (stack-array-get stack-arr (inc fn-pos))
              (stack-array-get stack-arr (+ fn-pos 2)))
    3 (fn-val (stack-array-get stack-arr (inc fn-pos))
              (stack-array-get stack-arr (+ fn-pos 2))
              (stack-array-get stack-arr (+ fn-pos 3)))
    4 (fn-val (stack-array-get stack-arr (inc fn-pos))
              (stack-array-get stack-arr (+ fn-pos 2))
              (stack-array-get stack-arr (+ fn-pos 3))
              (stack-array-get stack-arr (+ fn-pos 4)))
    (apply fn-val (stack-args->vector stack-arr fn-pos argc))))


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


(defn- restore-frame
  "Restore VM state from a snapshot, optionally pushing a return value."
  ([state frame]
   (assoc state
          :pc (:pc frame)
          :bytecode (:bytecode frame)
          :stack (:stack frame)
          :env (:env frame)
          :call-stack (:call-stack frame)
          :pool (or (:pool frame) (:pool state))))
  ([state frame value]
   (assoc state
          :pc (:pc frame)
          :bytecode (:bytecode frame)
          :stack (conj (:stack frame) value)
          :env (:env frame)
          :call-stack (:call-stack frame)
          :pool (or (:pool frame) (:pool state)))))


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
                 clo-bytecode :bytecode,
                 clo-body :body-bytes,
                 clo-body-start :body-start,
                 clo-env :env,
                 clo-pool :pool}
                fn-val
                target-bytecode (or clo-bytecode clo-body)
                target-pc (or clo-body-start 0)
                new-env (merge clo-env (zipmap clo-params args))
                frame (snap-frame state stack-rest next-pc)]
            (if tail?
              ;; TCO: reuse current frame by
              ;; jumping directly to callee body.
              (assoc state
                     :pc target-pc
                     :bytecode target-bytecode
                     :stack []
                     :env new-env
                     :pool (or clo-pool pool)
                     :call-stack call-stack)
              (assoc state
                     :pc target-pc
                     :bytecode target-bytecode
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
          (restore-frame state (peek call-stack) result)))
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
                body-end (+ body-start body-len)
                closure {:type :closure,
                         :params params,
                         :body-start body-start,
                         :body-end body-end,
                         :bytecode bytecode,
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
              (restore-frame state (peek call-stack) result)))
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
                                          (let [resumed (restore-frame new-state parked rv)]
                                            (assoc resumed :pc (inc (:pc parked)))))))
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
                                  (restore-frame base entry (:value entry)))))


(defn- stack-vm-run-scheduler
  "Generic scheduler-backed run loop."
  [vm-state]
  (engine/run-loop vm-state
                   engine/active-continuation?
                   stack-step
                   resume-from-run-queue))


(defn- materialize-fast-state
  "Materialize fast-loop locals back into a StackVM value."
  [vm pc bytecode pool stack-arr sp env call-stack store id-counter]
  (assoc vm
         :pc pc
         :bytecode bytecode
         :pool pool
         :stack (stack-array->vector stack-arr sp)
         :env env
         :call-stack call-stack
         :store store
         :id-counter id-counter
         :halted false
         :blocked false))


(defn- halt-fast-state
  [vm pc bytecode pool stack-arr sp env call-stack store id-counter result]
  (assoc vm
         :pc pc
         :bytecode bytecode
         :pool pool
         :stack (stack-array->vector stack-arr sp)
         :env env
         :call-stack call-stack
         :store store
         :id-counter id-counter
         :halted true
         :blocked false
         :value result))


(defn- stack-vm-run-active-continuation
  "Fast stack VM run loop with mutable stack storage.
   Falls back to the generic scheduler loop for stream/control opcodes."
  [vm]
  (let [[stack-arr0 sp0] (stack-vector->array+sp (:stack vm))
        primitives (:primitives vm)]
    (loop [pc (:pc vm)
           bytecode (:bytecode vm)
           pool (:pool vm)
           stack-arr stack-arr0
           sp sp0
           env (:env vm)
           call-stack (:call-stack vm)
           store (:store vm)
           id-counter (:id-counter vm)]
      (let [fallback #(stack-vm-run-scheduler
                        (materialize-fast-state
                          vm
                          pc
                          bytecode
                          pool
                          stack-arr
                          sp
                          env
                          call-stack
                          store
                          id-counter))]
        (if (>= pc (count bytecode))
          (let [result (when-not (neg? sp) (stack-array-get stack-arr sp))]
            (if (empty? call-stack)
              (halt-fast-state vm
                               pc
                               bytecode
                               pool
                               stack-arr
                               sp
                               env
                               call-stack
                               store
                               id-counter
                               result)
              (let [frame (peek call-stack)
                    [restored-arr restored-sp0] (stack-vector->array+sp
                                                  (:stack frame))
                    [restored-arr restored-sp] (stack-push restored-arr
                                                           restored-sp0
                                                           result)]
                (recur (:pc frame)
                       (:bytecode frame)
                       (or (:pool frame) pool)
                       restored-arr
                       restored-sp
                       (:env frame)
                       (:call-stack frame)
                       store
                       id-counter))))
          (let [op (nth bytecode pc)]
            (vm/opcase
              op
              :literal
              (let [val-idx (nth bytecode (inc pc))
                    val (nth pool val-idx)
                    [next-arr next-sp] (stack-push stack-arr sp val)]
                (recur (+ pc 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       call-stack
                       store
                       id-counter))
              :load-var
              (let [sym-idx (nth bytecode (inc pc))
                    sym (nth pool sym-idx)
                    val (engine/resolve-var env store primitives sym)
                    [next-arr next-sp] (stack-push stack-arr sp val)]
                (recur (+ pc 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       call-stack
                       store
                       id-counter))
              :lambda
              (let [params-idx (nth bytecode (inc pc))
                    params (nth pool params-idx)
                    body-len (fetch-short-unsigned bytecode (+ pc 2))
                    body-start (+ pc 4)
                    body-end (+ body-start body-len)
                    closure {:type :closure,
                             :params params,
                             :body-start body-start,
                             :body-end body-end,
                             :bytecode bytecode,
                             :env env,
                             :pool pool}
                    [next-arr next-sp] (stack-push stack-arr sp closure)]
                (recur (+ pc 4 body-len)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       call-stack
                       store
                       id-counter))
              :call
              (let [argc (nth bytecode (inc pc))
                    fn-pos (- sp argc)
                    fn-val (stack-array-get stack-arr fn-pos)
                    stack-rest-sp (dec fn-pos)
                    next-pc (+ pc 2)]
                (cond
                  (fn? fn-val)
                  (let [result (invoke-native-fast fn-val stack-arr fn-pos argc)]
                    (if (module/effect? result)
                      (let [state' (materialize-fast-state
                                     vm
                                     pc
                                     bytecode
                                     pool
                                     stack-arr
                                     sp
                                     env
                                     call-stack
                                     store
                                     id-counter)
                            stack-rest (stack-array->vector stack-arr
                                                            stack-rest-sp)]
                        (-> (handle-native-result
                              state'
                              result
                              stack-rest
                              next-pc
                              {:stream/put
                               (fn [_s _e r]
                                 (merge
                                   (snap-frame state' stack-rest next-pc)
                                   {:reason :put,
                                    :stream-id (:stream-id r),
                                    :datom (:val result)})),
                               :stream/next
                               (fn [_s _e r]
                                 (merge
                                   (snap-frame state' stack-rest next-pc)
                                   {:reason :next,
                                    :cursor-ref (:cursor-ref r),
                                    :stream-id (:stream-id r)}))})
                            stack-vm-run-scheduler))
                      (let [arr1 stack-arr
                            sp1 stack-rest-sp
                            [next-arr next-sp] (stack-push arr1 sp1 result)]
                        (recur next-pc
                               bytecode
                               pool
                               next-arr
                               next-sp
                               env
                               call-stack
                               store
                               id-counter))))
                  (= :closure (:type fn-val))
                  (let [{clo-params :params,
                         clo-bytecode :bytecode,
                         clo-body :body-bytes,
                         clo-body-start :body-start,
                         clo-env :env,
                         clo-pool :pool}
                        fn-val
                        target-bytecode (or clo-bytecode clo-body)
                        target-pc (or clo-body-start 0)
                        new-env (merge clo-env
                                       (zipmap clo-params
                                               (stack-args->vector
                                                 stack-arr
                                                 fn-pos
                                                 argc)))
                        frame (snap-frame {:bytecode bytecode,
                                           :env env,
                                           :call-stack call-stack,
                                           :pool pool}
                                          (stack-array->vector stack-arr
                                                               stack-rest-sp)
                                          next-pc)
                        next-call-stack (conj call-stack frame)]
                    (recur target-pc
                           target-bytecode
                           (or clo-pool pool)
                           stack-arr
                           -1
                           new-env
                           next-call-stack
                           store
                           id-counter))
                  :else
                  (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
              :tailcall
              (let [argc (nth bytecode (inc pc))
                    fn-pos (- sp argc)
                    fn-val (stack-array-get stack-arr fn-pos)
                    stack-rest-sp (dec fn-pos)
                    next-pc (+ pc 2)]
                (cond
                  (fn? fn-val)
                  (let [result (invoke-native-fast fn-val stack-arr fn-pos argc)]
                    (if (module/effect? result)
                      (let [state' (materialize-fast-state
                                     vm
                                     pc
                                     bytecode
                                     pool
                                     stack-arr
                                     sp
                                     env
                                     call-stack
                                     store
                                     id-counter)
                            stack-rest (stack-array->vector stack-arr
                                                            stack-rest-sp)]
                        (-> (handle-native-result
                              state'
                              result
                              stack-rest
                              next-pc
                              {:stream/put
                               (fn [_s _e r]
                                 (merge
                                   (snap-frame state' stack-rest next-pc)
                                   {:reason :put,
                                    :stream-id (:stream-id r),
                                    :datom (:val result)})),
                               :stream/next
                               (fn [_s _e r]
                                 (merge
                                   (snap-frame state' stack-rest next-pc)
                                   {:reason :next,
                                    :cursor-ref (:cursor-ref r),
                                    :stream-id (:stream-id r)}))})
                            stack-vm-run-scheduler))
                      (let [arr1 stack-arr
                            sp1 stack-rest-sp
                            [next-arr next-sp] (stack-push arr1 sp1 result)]
                        (recur next-pc
                               bytecode
                               pool
                               next-arr
                               next-sp
                               env
                               call-stack
                               store
                               id-counter))))
                  (= :closure (:type fn-val))
                  (let [{clo-params :params,
                         clo-bytecode :bytecode,
                         clo-body :body-bytes,
                         clo-body-start :body-start,
                         clo-env :env,
                         clo-pool :pool}
                        fn-val
                        target-bytecode (or clo-bytecode clo-body)
                        target-pc (or clo-body-start 0)
                        new-env (merge clo-env
                                       (zipmap clo-params
                                               (stack-args->vector
                                                 stack-arr
                                                 fn-pos
                                                 argc)))]
                    (recur target-pc
                           target-bytecode
                           (or clo-pool pool)
                           stack-arr
                           -1
                           new-env
                           call-stack
                           store
                           id-counter))
                  :else
                  (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
              :branch
              (let [offset (fetch-short-signed bytecode (inc pc))
                    condition (stack-array-get stack-arr sp)]
                (recur (if condition (+ pc 3 offset) (+ pc 3))
                       bytecode
                       pool
                       stack-arr
                       (dec sp)
                       env
                       call-stack
                       store
                       id-counter))
              :jump
              (let [offset (fetch-short-signed bytecode (inc pc))]
                (recur (+ pc 3 offset)
                       bytecode
                       pool
                       stack-arr
                       sp
                       env
                       call-stack
                       store
                       id-counter))
              :return
              (let [result (when-not (neg? sp) (stack-array-get stack-arr sp))]
                (if (empty? call-stack)
                  (halt-fast-state vm
                                   pc
                                   bytecode
                                   pool
                                   stack-arr
                                   sp
                                   env
                                   call-stack
                                   store
                                   id-counter
                                   result)
                  (let [frame (peek call-stack)
                        [restored-arr restored-sp0]
                        (stack-vector->array+sp (:stack frame))
                        [restored-arr restored-sp]
                        (stack-push restored-arr restored-sp0 result)]
                    (recur (:pc frame)
                           (:bytecode frame)
                           (or (:pool frame) pool)
                           restored-arr
                           restored-sp
                           (:env frame)
                           (:call-stack frame)
                           store
                           id-counter))))
              :gensym
              (let [prefix-idx (nth bytecode (inc pc))
                    prefix (nth pool prefix-idx)
                    id (keyword (str prefix "-" id-counter))
                    [next-arr next-sp] (stack-push stack-arr sp id)]
                (recur (+ pc 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       call-stack
                       store
                       (inc id-counter)))
              :store-get
              (let [key-idx (nth bytecode (inc pc))
                    key (nth pool key-idx)
                    val (get store key)
                    [next-arr next-sp] (stack-push stack-arr sp val)]
                (recur (+ pc 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       call-stack
                       store
                       id-counter))
              :store-put
              (let [key-idx (nth bytecode (inc pc))
                    key (nth pool key-idx)
                    val (when-not (neg? sp) (stack-array-get stack-arr sp))]
                (recur (+ pc 2)
                       bytecode
                       pool
                       stack-arr
                       sp
                       env
                       call-stack
                       (assoc store key val)
                       id-counter))
              (fallback))))))))


(defn- stack-vm-run
  [vm]
  ;; Fast path is valid only for a single active compute continuation.
  ;; Any of these conditions means we must re-enter scheduler semantics:
  ;; - :blocked / :wait-set: wait-set polling and wakeup checks.
  ;; - :run-queue: dequeue + resume parked continuations.
  ;; - :halted: current continuation finished, but scheduler may still have work.
  ;; - nil :bytecode: no active instruction stream to execute.
  (if (or (:blocked vm)
          (:halted vm)
          (seq (:run-queue vm))
          (seq (:wait-set vm))
          (nil? (:bytecode vm)))
    (stack-vm-run-scheduler vm)
    (stack-vm-run-active-continuation vm)))


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
    (stack-vm-run v)))


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
