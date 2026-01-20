(ns yin.bytecode
  (:refer-clojure :exclude [compile type]))

;; --- Opcodes ---
(def OP_LITERAL 1)       ; [OP_LITERAL] [val-idx]
(def OP_LOAD_VAR 2)      ; [OP_LOAD_VAR] [sym-idx]
(def OP_LAMBDA 3)        ; [OP_LAMBDA] [arity] [body-len-hi] [body-len-lo] ...body...
(def OP_APPLY 4)         ; [OP_APPLY] [argc]
(def OP_JUMP_IF_FALSE 5) ; [OP_JUMP_IF_FALSE] [offset-hi] [offset-lo]
(def OP_RETURN 6)        ; [OP_RETURN]
(def OP_JUMP 7)          ; [OP_JUMP] [offset-hi] [offset-lo]

;; --- Compiler State ---
;; We need to pool literals and symbols to refer to them by index in the bytecode.
;; For a real generic bytecode, we might put these in a "constant pool".
;; For this prototype, we'll embed them or assume a context.
;; To keep it simple and "byte-oriented", let's assume valid datoms for literals/symbols
;; are stored in a side vector (constant pool) and we refer to them by index.

(defn- emit [bytes op]
  (conj bytes op))

(defn- emit-short [bytes n]
  (conj bytes (bit-shift-right (bit-and n 0xFF00) 8) (bit-and n 0xFF)))

;; Forward declaration
(declare compile-ast)

(defn compile-seq [ast-seq constant-pool]
  (reduce (fn [[bytes pool] ast]
            (let [[new-bytes new-pool] (compile-ast ast pool)]
              [(into bytes new-bytes) new-pool]))
          [[] constant-pool]
          ast-seq))

(defn- add-constant [pool val]
  (let [idx (count pool)]
    [(conj pool val) idx]))

(defn compile-ast [ast constant-pool]
  (let [{:keys [type value name params body operator operands test consequent alternate]} ast]
    (case type
      :literal
      (let [[new-pool idx] (add-constant constant-pool value)]
        [[OP_LITERAL idx] new-pool])

      :variable
      (let [[new-pool idx] (add-constant constant-pool name)]
        [[OP_LOAD_VAR idx] new-pool])

      :lambda
      ;; We need to compile the body first to know its length
      (let [[body-bytes body-pool] (compile-ast body constant-pool)
            body-len (count body-bytes)
            [params-pool params-idx] (add-constant body-pool params)]
        ;; Structure: OP_LAMBDA [params-idx] [body-len-high] [body-len-low] [BODY...]
        [(-> [OP_LAMBDA params-idx]
             (into [(bit-shift-right (bit-and body-len 0xFF00) 8) (bit-and body-len 0xFF)])
             (into body-bytes))
         params-pool])

      :application
      ;; Compile operator, then operands, then emit APPLY
      (let [[op-bytes pool-1] (compile-ast operator constant-pool)
            [args-bytes pool-2] (compile-seq operands pool-1)]
        [(-> []
             (into op-bytes)
             (into args-bytes)
             (into [OP_APPLY (count operands)]))  ;; NOTE: Limit 255 args
         pool-2])

      :if
      (let [[test-bytes pool-1] (compile-ast test constant-pool)
            [cons-bytes pool-2] (compile-ast consequent pool-1)
            [alt-bytes pool-3] (compile-ast alternate pool-2)

            ;; Jumps
            ;; [TEST] [JUMP_IF_FALSE to ALT] [CONSEQUENT] [JUMP to END] [ALTERNATE]
            alt-len (count alt-bytes)
            cons-len (count cons-bytes)
            jump-cons-len (+ cons-len 3) ;; +3 for OP_JUMP + 2 bytes offset
            ]
        [(-> []
             (into test-bytes)
             (into [OP_JUMP_IF_FALSE (bit-shift-right (bit-and jump-cons-len 0xFF00) 8) (bit-and jump-cons-len 0xFF)])
             (into cons-bytes)
             (into [OP_JUMP (bit-shift-right (bit-and alt-len 0xFF00) 8) (bit-and alt-len 0xFF)])
             (into alt-bytes))
         pool-3])

      ;; Default
      (throw (ex-info "Unknown AST type" {:ast ast})))))

(defn compile [ast]
  (compile-ast ast []))

;; --- VM ---

(defn- fetch-short [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))

(defn run-bytes
  ([bytes constant-pool] (run-bytes bytes constant-pool {}))
  ([bytes constant-pool env]
   (loop [pc 0
          stack []
          env env]
     (if (>= pc (count bytes))
       (peek stack) ;; Return top of stack
       (let [op (nth bytes pc)]
         (condp = op
           OP_LITERAL
           (let [val-idx (nth bytes (inc pc))
                 val (nth constant-pool val-idx)]
             (recur (+ pc 2) (conj stack val) env))

           OP_LOAD_VAR
           (let [sym-idx (nth bytes (inc pc))
                 sym (nth constant-pool sym-idx)
                 val (get env sym)]
             (recur (+ pc 2) (conj stack val) env))

           OP_LAMBDA
           (let [params-idx (nth bytes (inc pc))
                 params (nth constant-pool params-idx)
                 body-len (fetch-short bytes (+ pc 2))
                 body-start (+ pc 4)
                 ;; Capture code segment as the function body
                 ;; In a real byte VM, we'd probably jump over it, but here we construct a closure
                 ;; that "knows" its code range or sub-slice.
                 ;; For simplicity of this prototype: we'll create a closure that holds the sub-vector of bytes.
                 body-bytes (vec (subvec (vec bytes) body-start (+ body-start body-len)))

                 closure {:type :closure
                          :params params
                          :body-bytes body-bytes
                          :env env}]
             ;; Skip over body
             (recur (+ pc 4 body-len) (conj stack closure) env))

           OP_APPLY
           (let [argc (nth bytes (inc pc))
                 ;; Stack: [Fn, Arg1, Arg2, ... ArgN] <- Top
                 ;; We need to pop N args, then the Fn
                 args (subvec stack (- (count stack) argc))
                 stack-minus-args (subvec stack 0 (- (count stack) argc))
                 fn-val (peek stack-minus-args)
                 stack-rest (pop stack-minus-args)]

             (cond
               (fn? fn-val) ;; Primitive
               (let [res (apply fn-val args)]
                 (recur (+ pc 2) (conj stack-rest res) env))

               (= :closure (:type fn-val))
               (let [{:keys [params body-bytes env] :as closure} fn-val
                     closure-env env
                     new-env (merge closure-env (zipmap params args))]
                 ;; RECURSIVE CALL for the closure execution
                 ;; In a real iterative VM, we'd push a frame. Here using host recursion for simplicity of prototype.
                 (let [res (run-bytes body-bytes constant-pool new-env)]
                   (recur (+ pc 2) (conj stack-rest res) env)))

               :else
               (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))

           OP_JUMP_IF_FALSE
           (let [offset (fetch-short bytes (inc pc))
                 condition (peek stack)
                 new-stack (pop stack)]
             (if condition
               (recur (+ pc 3) new-stack env)
               (recur (+ pc 3 offset) new-stack env)))

           OP_JUMP
           (let [offset (fetch-short bytes (inc pc))]
             (recur (+ pc 3 offset) stack env))

           OP_RETURN
           (peek stack)

           (throw (ex-info "Unknown Opcode" {:op op :pc pc}))))))))
