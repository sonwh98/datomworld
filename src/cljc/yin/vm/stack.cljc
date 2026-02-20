(ns yin.vm.stack
  (:require
    [yin.module :as module]
    [yin.scheduler :as scheduler]
    [yin.stream :as stream]
    [yin.vm :as vm]))


;; =============================================================================
;; Stack VM
;; =============================================================================
;;
;; The AST as :yin/ datoms (from vm/ast->datoms) is the canonical
;; representation. This namespace provides:
;;   1. Stack assembly: project :yin/ datoms to symbolic stack instructions
;;   2. Assembly to numeric bytecode encoding
;;   3. Numeric bytecode VM (step, run via protocols)
;; =============================================================================


;; =============================================================================
;; VM Records
;; =============================================================================

(defrecord StackVM
  [pc         ; program counter
   bytecode   ; bytecode vector
   stack      ; operand stack
   env        ; lexical environment
   call-stack ; call frames for function returns
   pool       ; constant pool
   halted     ; true if execution completed
   value      ; final result value
   store      ; heap memory
   parked     ; parked continuations
   id-counter ; unique ID counter
   primitives ; primitive operations
   blocked    ; true if blocked
   run-queue  ; vector of runnable continuations
   wait-set   ; vector of parked continuations waiting on
   ;; streams
   ])


;; =============================================================================
;; Numeric Opcodes
;; =============================================================================
(def OP_LITERAL 1)       ; [OP_LITERAL] [val-idx]
(def OP_LOAD_VAR 2)      ; [OP_LOAD_VAR] [sym-idx]
(def OP_LAMBDA 3)        ; [OP_LAMBDA] [arity] [body-len-hi] [body-len-lo] ...body...
(def OP_APPLY 4)         ; [OP_APPLY] [argc]
(def OP_JUMP_IF_FALSE 5) ; [OP_JUMP_IF_FALSE] [offset-hi] [offset-lo]
(def OP_RETURN 6)        ; [OP_RETURN]
(def OP_JUMP 7)          ; [OP_JUMP] [offset-hi] [offset-lo]
(def OP_GENSYM 8)        ; [OP_GENSYM] [prefix-idx]
(def OP_STORE_GET 9)     ; [OP_STORE_GET] [key-idx]
(def OP_STORE_PUT 10)    ; [OP_STORE_PUT] [key-idx]
(def OP_STREAM_MAKE 11)  ; [OP_STREAM_MAKE] [buf-idx]
(def OP_STREAM_PUT 12)   ; [OP_STREAM_PUT]
(def OP_STREAM_CURSOR 13); [OP_STREAM_CURSOR]
(def OP_STREAM_NEXT 14)  ; [OP_STREAM_NEXT]
(def OP_STREAM_CLOSE 15) ; [OP_STREAM_CLOSE]
(def OP_PARK 16)         ; [OP_PARK]
(def OP_RESUME 17)       ; [OP_RESUME]
(def OP_CURRENT_CONT 18) ; [OP_CURRENT_CONT]


;; =============================================================================
;; Datoms -> Stack Assembly
;; =============================================================================

(defn ast-datoms->asm
  "Project :yin/ datoms to symbolic stack assembly.

   Instructions:
     [:push v]           - push literal value v
     [:load name]        - push value of variable name
     [:call argc]        - pop function and argc args, call, push result
     [:lambda params label] - push closure (captures env, body follows)
     [:jump-false label] - pop condition, if false jump to label
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
  (let [datoms (vec ast-as-datoms)
        by-entity (group-by first datoms)
        get-attr (fn [e attr]
                   (some (fn [[_ a v]] (when (= a attr) v)) (get by-entity e)))
        root-id (apply max (keys by-entity))
        instructions (atom [])
        emit! (fn [instr] (swap! instructions conj instr))
        label-counter (atom 0)
        gen-label! (fn [prefix]
                     (keyword (str prefix "-" (swap! label-counter inc))))]
    (letfn
      [(compile-node
         [e]
         (case (get-attr e :yin/type)
           :literal (emit! [:push (get-attr e :yin/value)])
           :variable (emit! [:load (get-attr e :yin/name)])
           :lambda (let [params (get-attr e :yin/params)
                         body-node (get-attr e :yin/body)
                         skip-label (gen-label! "after-lambda")]
                     (emit! [:lambda params skip-label])
                     (compile-node body-node)
                     (emit! [:return])
                     (emit! [:label skip-label]))
           :application (let [op-node (get-attr e :yin/operator)
                              operand-nodes (get-attr e :yin/operands)]
                          (compile-node op-node)
                          (doseq [arg-node operand-nodes]
                            (compile-node arg-node))
                          (emit! [:call (count operand-nodes)]))
           :if (let [test-node (get-attr e :yin/test)
                     cons-node (get-attr e :yin/consequent)
                     alt-node (get-attr e :yin/alternate)
                     else-label (gen-label! "else")
                     end-label (gen-label! "end")]
                 (compile-node test-node)
                 (emit! [:jump-false else-label])
                 (compile-node cons-node)
                 (emit! [:jump end-label])
                 (emit! [:label else-label])
                 (compile-node alt-node)
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
                           {:type (get-attr e :yin/type), :entity e}))))]
      (compile-node root-id) @instructions)))


(defn asm->bytecode
  "Convert symbolic stack assembly to numeric bytecode.
   Returns {:bc bytes :pool pool :source-map {byte-offset instr-index}}."
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
                       :jump-false 3
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
                    (emit-byte! OP_LITERAL)
                    (emit-byte! idx)
                    (swap! emit-offset + 2))
            :load (let [idx (add-constant arg1)]
                    (emit-byte! OP_LOAD_VAR)
                    (emit-byte! idx)
                    (swap! emit-offset + 2))
            :lambda (let [params-idx (add-constant arg1)
                          target-label arg2
                          target-offset (get @label-offsets target-label)
                          ;; Body length = target-offset - (current-offset
                          ;; + 4)
                          body-len (- target-offset (+ @emit-offset 4))]
                      (emit-byte! OP_LAMBDA)
                      (emit-byte! params-idx)
                      (emit-short! body-len)
                      (swap! emit-offset + 4))
            :call (do (emit-byte! OP_APPLY)
                      (emit-byte! arg1)
                      (swap! emit-offset + 2))
            :jump-false (let [target-label arg1
                              target-offset (get @label-offsets target-label)
                              ;; Relative offset = target-offset -
                              ;; (current-offset + 3)
                              rel-offset (- target-offset (+ @emit-offset 3))]
                          (emit-byte! OP_JUMP_IF_FALSE)
                          (emit-short! rel-offset)
                          (swap! emit-offset + 3))
            :jump (let [target-label arg1
                        target-offset (get @label-offsets target-label)
                        rel-offset (- target-offset (+ @emit-offset 3))]
                    (emit-byte! OP_JUMP)
                    (emit-short! rel-offset)
                    (swap! emit-offset + 3))
            :return (do (emit-byte! OP_RETURN) (swap! emit-offset + 1))
            :label nil ; No code emitted
            :gensym (let [idx (add-constant arg1)]
                      (emit-byte! OP_GENSYM)
                      (emit-byte! idx)
                      (swap! emit-offset + 2))
            :store-get (let [idx (add-constant arg1)]
                         (emit-byte! OP_STORE_GET)
                         (emit-byte! idx)
                         (swap! emit-offset + 2))
            :store-put (let [idx (add-constant arg1)]
                         (emit-byte! OP_STORE_PUT)
                         (emit-byte! idx)
                         (swap! emit-offset + 2))
            :stream-make (let [idx (add-constant arg1)]
                           (emit-byte! OP_STREAM_MAKE)
                           (emit-byte! idx)
                           (swap! emit-offset + 2))
            :stream-put (do (emit-byte! OP_STREAM_PUT) (swap! emit-offset + 1))
            :stream-cursor (do (emit-byte! OP_STREAM_CURSOR)
                               (swap! emit-offset + 1))
            :stream-next (do (emit-byte! OP_STREAM_NEXT)
                             (swap! emit-offset + 1))
            :stream-close (do (emit-byte! OP_STREAM_CLOSE)
                              (swap! emit-offset + 1))
            :park (do (emit-byte! OP_PARK) (swap! emit-offset + 1))
            :resume (do (emit-byte! OP_RESUME) (swap! emit-offset + 1))
            :current-cont (do (emit-byte! OP_CURRENT_CONT)
                              (swap! emit-offset + 1)))))
      {:bc @bytes, :pool @pool, :source-map @source-map})))


(defn- fetch-short
  [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))


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
        (case (int op)
          1 ; OP_LITERAL
          (let [val-idx (nth bytecode (inc pc))
                val (nth pool val-idx)]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack val)))
          2 ; OP_LOAD_VAR
          (let [sym-idx (nth bytecode (inc pc))
                sym (nth pool sym-idx)
                val (if-let [pair (find env sym)]
                      (val pair)
                      (if-let [pair (find store sym)]
                        (val pair)
                        (or (get primitives sym)
                            (module/resolve-symbol sym))))]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack val)))
          3 ; OP_LAMBDA
          (let [params-idx (nth bytecode (inc pc))
                params (nth pool params-idx)
                body-len (fetch-short bytecode (+ pc 2))
                body-start (+ pc 4)
                body-bytes
                (subvec bytecode body-start (+ body-start body-len))
                closure {:type :closure,
                         :params params,
                         :body-bytes body-bytes,
                         :env env,
                         :pool pool}]
            (assoc state
                   :pc (+ pc 4 body-len)
                   :stack (conj stack closure)))
          4 ; OP_APPLY
          (let [argc (nth bytecode (inc pc))
                n (count stack)
                args (subvec stack (- n argc) n)
                fn-val (nth stack (- n argc 1))
                stack-rest (subvec stack 0 (- n argc 1))]
            (cond
              (fn? fn-val)
              (let [res (apply fn-val args)]
                (if (module/effect? res)
                  (case (:effect res)
                    :vm/store-put (let [key (:key res)
                                        value (:val res)
                                        new-store (assoc store key value)]
                                    (assoc state
                                           :store new-store
                                           :pc (+ pc 2)
                                           :stack (conj stack-rest value)))
                    :stream/make
                    (let [gen-id-fn (fn [prefix]
                                      (keyword
                                        (str prefix "-" id-counter)))
                          [stream-ref new-state]
                          (stream/handle-make state res gen-id-fn)]
                      (assoc new-state
                             :pc (+ pc 2)
                             :stack (conj stack-rest stream-ref)
                             :id-counter (inc id-counter)))
                    :stream/put
                    (let [result (stream/handle-put state res)]
                      (if (:park result)
                        (let [parked-entry {:pc (+ pc 2),
                                            :bytecode bytecode,
                                            :stack stack-rest,
                                            :env env,
                                            :call-stack call-stack,
                                            :pool pool,
                                            :reason :put,
                                            :stream-id (:stream-id
                                                         result),
                                            :datom (:val res)}]
                          (assoc (:state result)
                                 :wait-set (conj (or (:wait-set state) [])
                                                 parked-entry)
                                 :value :yin/blocked
                                 :blocked true
                                 :halted false))
                        (assoc (:state result)
                               :pc (+ pc 2)
                               :stack (conj stack-rest (:value result)))))
                    :stream/cursor
                    (let [gen-id-fn (fn [prefix]
                                      (keyword
                                        (str prefix "-" id-counter)))
                          [cursor-ref new-state]
                          (stream/handle-cursor state res gen-id-fn)]
                      (assoc new-state
                             :pc (+ pc 2)
                             :stack (conj stack-rest cursor-ref)
                             :id-counter (inc id-counter)))
                    :stream/next
                    (let [result (stream/handle-next state res)]
                      (if (:park result)
                        (let [parked-entry
                              {:pc (+ pc 2),
                               :bytecode bytecode,
                               :stack stack-rest,
                               :env env,
                               :call-stack call-stack,
                               :pool pool,
                               :reason :next,
                               :cursor-ref (:cursor-ref result),
                               :stream-id (:stream-id result)}]
                          (assoc (:state result)
                                 :wait-set (conj (or (:wait-set state) [])
                                                 parked-entry)
                                 :value :yin/blocked
                                 :blocked true
                                 :halted false))
                        (assoc (:state result)
                               :pc (+ pc 2)
                               :stack (conj stack-rest (:value result)))))
                    :stream/close
                    (let [close-result (stream/handle-close state res)
                          new-state (:state close-result)
                          to-resume (:resume-parked close-result)
                          run-queue (or (:run-queue new-state) [])
                          new-run-queue (into run-queue
                                              (map (fn [entry]
                                                     (assoc entry
                                                            :value nil))
                                                   to-resume))]
                      (assoc new-state
                             :run-queue new-run-queue
                             :pc (+ pc 2)
                             :stack (conj stack-rest nil)))
                    (throw (ex-info "Unhandled effect in stack-step"
                                    {:effect res})))
                  (assoc state
                         :pc (+ pc 2)
                         :stack (conj stack-rest res))))
              (= :closure (:type fn-val))
              (let [{clo-params :params,
                     clo-body :body-bytes,
                     clo-env :env,
                     clo-pool :pool}
                    fn-val
                    new-env (merge clo-env (zipmap clo-params args))
                    frame {:pc (+ pc 2),
                           :bytecode bytecode,
                           :stack stack-rest,
                           :env env,
                           :pool pool}]
                (assoc state
                       :pc 0
                       :bytecode clo-body
                       :stack []
                       :env new-env
                       :pool (or clo-pool pool)
                       :call-stack (conj call-stack frame)))
              :else (throw (ex-info "Cannot apply non-function"
                                    {:fn fn-val}))))
          5 ; OP_JUMP_IF_FALSE
          (let [offset (fetch-short bytecode (inc pc))
                condition (peek stack)
                new-stack (pop stack)]
            (assoc state
                   :pc (if condition (+ pc 3) (+ pc 3 offset))
                   :stack new-stack))
          7 ; OP_JUMP
          (let [offset (fetch-short bytecode (inc pc))]
            (assoc state :pc (+ pc 3 offset)))
          6 ; OP_RETURN
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
          8 ; OP_GENSYM
          (let [prefix-idx (nth bytecode (inc pc))
                prefix (nth pool prefix-idx)
                id (keyword (str prefix "-" id-counter))]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack id)
                   :id-counter (inc id-counter)))
          9 ; OP_STORE_GET
          (let [key-idx (nth bytecode (inc pc))
                key (nth pool key-idx)
                val (get store key)]
            (assoc state
                   :pc (+ pc 2)
                   :stack (conj stack val)))
          10 ; OP_STORE_PUT
          (let [key-idx (nth bytecode (inc pc))
                key (nth pool key-idx)
                val (peek stack)
                new-store (assoc store key val)]
            (assoc state
                   :pc (+ pc 2)
                   :store new-store))
          11 ; OP_STREAM_MAKE
          (let [buf-idx (nth bytecode (inc pc))
                buf (nth pool buf-idx)
                gen-id-fn (fn [prefix] (keyword (str prefix "-" id-counter)))
                effect {:effect :stream/make, :capacity buf}
                [stream-ref new-state]
                (stream/handle-make state effect gen-id-fn)]
            (assoc new-state
                   :pc (+ pc 2)
                   :stack (conj stack stream-ref)
                   :id-counter (inc id-counter)))
          12 ; OP_STREAM_PUT - pop stream-ref, pop val
          (let [stream-ref (peek stack)
                stack1 (pop stack)
                val (peek stack1)
                stack-rest (pop stack1)
                effect {:effect :stream/put, :stream stream-ref, :val val}
                result (stream/handle-put state effect)]
            (if (:park result)
              (let [parked-entry {:pc (+ pc 1),
                                  :bytecode bytecode,
                                  :stack (conj stack-rest val),
                                  :env env,
                                  :call-stack call-stack,
                                  :pool pool,
                                  :reason :put,
                                  :stream-id (:stream-id result),
                                  :datom val}]
                (assoc (:state result)
                       :wait-set (conj (or (:wait-set state) []) parked-entry)
                       :value :yin/blocked
                       :blocked true
                       :halted false))
              (assoc (:state result)
                     :pc (+ pc 1)
                     :stack (conj stack-rest (:value result)))))
          13 ; OP_STREAM_CURSOR - pop stream-ref
          (let [stream-ref (peek stack)
                stack-rest (pop stack)
                gen-id-fn (fn [prefix] (keyword (str prefix "-" id-counter)))
                effect {:effect :stream/cursor, :stream stream-ref}
                [cursor-ref new-state]
                (stream/handle-cursor state effect gen-id-fn)]
            (assoc new-state
                   :pc (+ pc 1)
                   :stack (conj stack-rest cursor-ref)
                   :id-counter (inc id-counter)))
          14 ; OP_STREAM_NEXT - pop cursor-ref
          (let [cursor-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/next, :cursor cursor-ref}
                result (stream/handle-next state effect)]
            (if (:park result)
              (let [parked-entry {:pc (+ pc 1),
                                  :bytecode bytecode,
                                  :stack stack-rest,
                                  :env env,
                                  :call-stack call-stack,
                                  :pool pool,
                                  :reason :next,
                                  :cursor-ref (:cursor-ref result),
                                  :stream-id (:stream-id result)}]
                (assoc (:state result)
                       :wait-set (conj (or (:wait-set state) []) parked-entry)
                       :value :yin/blocked
                       :blocked true
                       :halted false))
              (assoc (:state result)
                     :pc (+ pc 1)
                     :stack (conj stack-rest (:value result)))))
          15 ; OP_STREAM_CLOSE - pop stream-ref
          (let [stream-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/close, :stream stream-ref}
                close-result (stream/handle-close state effect)
                new-state (:state close-result)
                to-resume (:resume-parked close-result)
                run-queue (or (:run-queue new-state) [])
                new-run-queue (into run-queue
                                    (map (fn [entry] (assoc entry :value nil))
                                         to-resume))]
            (assoc new-state
                   :run-queue new-run-queue
                   :pc (+ pc 1)
                   :stack (conj stack-rest nil)))
          16 ; OP_PARK
          (let [park-id (keyword (str "parked-" id-counter))
                parked-cont {:type :parked-continuation,
                             :id park-id,
                             :pc pc,
                             :bytecode bytecode,
                             :stack stack,
                             :env env,
                             :call-stack call-stack,
                             :pool pool}
                new-parked (assoc (:parked state) park-id parked-cont)]
            (assoc state
                   :parked new-parked
                   :value parked-cont
                   :halted true
                   :id-counter (inc id-counter)))
          17 ; OP_RESUME - pop val, pop parked-id
          (let [resume-val (peek stack)
                stack1 (pop stack)
                parked-id (peek stack1)
                parked-cont (get-in state [:parked parked-id])]
            (if parked-cont
              (let [new-parked (dissoc (:parked state) parked-id)]
                (assoc state
                       :parked new-parked
                       :pc (:pc parked-cont)
                       :bytecode (:bytecode parked-cont)
                       :stack (conj (:stack parked-cont) resume-val)
                       :env (:env parked-cont)
                       :call-stack (:call-stack parked-cont)
                       :pool (:pool parked-cont)))
              (throw (ex-info "Cannot resume: parked continuation not found"
                              {:parked-id parked-id}))))
          18 ; OP_CURRENT_CONT
          (let [cont {:type :reified-continuation,
                      :pc pc,
                      :bytecode bytecode,
                      :stack stack,
                      :env env,
                      :call-stack call-stack,
                      :pool pool}]
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
  (let [run-queue (or (:run-queue state) [])]
    (when (seq run-queue)
      (let [entry (first run-queue)
            rest-queue (subvec run-queue 1)
            new-store (or (:store-updates entry) (:store state))]
        (assoc state
               :run-queue rest-queue
               :store new-store
               :pc (:pc entry)
               :bytecode (:bytecode entry)
               :stack (conj (:stack entry) (:value entry))
               :env (:env entry)
               :call-stack (:call-stack entry)
               :pool (:pool entry)
               :blocked false
               :halted false)))))


;; =============================================================================
;; StackVM Protocol Implementation
;; =============================================================================

(defn- stack-vm-halted?
  "Returns true if VM has halted."
  [^StackVM vm]
  (and (boolean (:halted vm)) (empty? (or (:run-queue vm) []))))


(defn- stack-vm-blocked?
  "Returns true if VM is blocked."
  [^StackVM vm]
  (boolean (:blocked vm)))


(defn- stack-vm-value
  "Returns the current value."
  [^StackVM vm]
  (:value vm))


(defn- stack-vm-load-program
  "Load bytecode into the VM.
   Expects {:bc [...] :pool [...]}."
  [^StackVM vm {:keys [bc pool]}]
  (assoc vm
         :pc 0
         :bytecode (vec bc)
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
                  compiled (asm->bytecode asm)]
              (stack-vm-load-program vm compiled))
            vm)]
    (loop [v v]
      (cond
        ;; Active computation: step it
        (and (not (:blocked v)) (not (:halted v))) (recur (stack-step v))
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
