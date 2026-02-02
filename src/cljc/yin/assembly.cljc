(ns yin.assembly)


;; =============================================================================
;; Assembly: Datom Projections
;; =============================================================================
;;
;; The AST as :yin/ datoms (from vm/ast->datoms) is the canonical
;; representation.
;; This namespace provides:
;;   1. Query helpers for :yin/ datoms
;;   2. run-semantic: execute :yin/ datoms by graph traversal
;;   3. Stack assembly: project :yin/ datoms to stack machine bytecode
;;   4. Numeric bytecode VM (run-bytes)
;;
;; All functions consume :yin/ datoms — either 5-tuples [e a v t m] or
;; 3-tuples [e a v]. Vector destructuring [_ a v] handles both.
;; =============================================================================


;; =============================================================================
;; Querying Semantic Bytecode
;; =============================================================================
;; Because bytecode is now datoms, we can query it with Datalog-like patterns.
;; This is the key insight from the blog: low-level form enables high-level
;; queries.

(defn find-by-type
  "Find all nodes of a given type.
   Works with both 3-tuples [e a v] and 5-tuples [e a v t m].

   Example: (find-by-type datoms :lambda) => all lambda nodes"
  [datoms node-type]
  (->> datoms
       (filter (fn [[_ a v]] (and (= a :yin/type) (= v node-type))))
       (map first)
       set))


(defn find-by-attr
  "Find all datoms with a given attribute.
   Works with both 3-tuples and 5-tuples."
  [datoms attr]
  (filter (fn [[_ a _]] (= a attr)) datoms))


(defn get-node-attrs
  "Get all attributes for a node as a map.
   Works with both 3-tuples [e a v] and 5-tuples [e a v t m]."
  [datoms node-id]
  (->> datoms
       (filter (fn [[e _ _]] (= e node-id)))
       (reduce (fn [m [_ a v]] (assoc m a v)) {})))


(defn find-applications
  "Find all function applications in the datoms."
  [datoms]
  (find-by-type datoms :application))


(defn find-lambdas
  "Find all lambda definitions in the datoms."
  [datoms]
  (find-by-type datoms :lambda))


(defn find-variables
  "Find all variable references in the datoms."
  [datoms]
  (find-by-type datoms :variable))


;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================
;; The VM interprets :yin/ datoms by traversing node references (graph walk).
;; No program counter, no constant pool — values are inline in the datoms.

(defn run-semantic
  "Execute :yin/ datoms starting from a root node.

   Traverses the datom graph by following entity references.
   Takes {:node root-entity-id :datoms [[e a v ...] ...]} and an env map."
  ([compiled] (run-semantic compiled {}))
  ([{:keys [node datoms]} env]
   (let [node-map (get-node-attrs datoms node)
         node-type (:yin/type node-map)]
     (case node-type
       :literal (:yin/value node-map)
       :variable (get env (:yin/name node-map))
       :lambda {:type :closure,
                :params (:yin/params node-map),
                :body-node (:yin/body node-map),
                :datoms datoms,
                :env env}
       :application
         (let [fn-val (run-semantic {:node (:yin/operator node-map),
                                     :datoms datoms}
                                    env)
               args (mapv #(run-semantic {:node %, :datoms datoms} env)
                      (:yin/operands node-map))]
           (cond (fn? fn-val) (apply fn-val args)
                 (= :closure (:type fn-val))
                   (let [{:keys [params body-node datoms env]} fn-val
                         new-env (merge env (zipmap params args))]
                     (run-semantic {:node body-node, :datoms datoms} new-env))
                 :else (throw (ex-info "Cannot apply non-function"
                                       {:fn fn-val}))))
       :if (let [test-val (run-semantic {:node (:yin/test node-map),
                                         :datoms datoms}
                                        env)]
             (if test-val
               (run-semantic {:node (:yin/consequent node-map), :datoms datoms}
                             env)
               (run-semantic {:node (:yin/alternate node-map), :datoms datoms}
                             env)))
       (throw (ex-info "Unknown node type"
                       {:node-map node-map, :type node-type}))))))


;; =============================================================================
;; Legacy Numeric Bytecode (for comparison/compatibility)
;; =============================================================================
;; The following preserves the original numeric bytecode implementation.
;; This shows the contrast: numeric bytecode loses semantic information.

;; --- Numeric Opcodes ---
(def OP_LITERAL 1)       ; [OP_LITERAL] [val-idx]
(def OP_LOAD_VAR 2)      ; [OP_LOAD_VAR] [sym-idx]
(def OP_LAMBDA 3)        ; [OP_LAMBDA] [arity] [body-len-hi] [body-len-lo] ...body...
(def OP_APPLY 4)         ; [OP_APPLY] [argc]
(def OP_JUMP_IF_FALSE 5) ; [OP_JUMP_IF_FALSE] [offset-hi] [offset-lo]
(def OP_RETURN 6)        ; [OP_RETURN]
(def OP_JUMP 7)          ; [OP_JUMP] [offset-hi] [offset-lo]

(defn- add-constant-legacy
  [pool val]
  (let [idx (count pool)] [(conj pool val) idx]))


;; =============================================================================
;; Datoms -> Stack Assembly
;; =============================================================================

(defn ast-datoms->stack-assembly
  "Project :yin/ datoms to symbolic stack assembly.

   Instructions:
     [:push v]           - push literal value v
     [:load name]        - push value of variable name
     [:call argc]        - pop function and argc args, call, push result
     [:lambda params label] - push closure (captures env, body follows)
     [:jump-false label] - pop condition, if false jump to label
     [:jump label]       - unconditional jump to label
     [:return]           - return top of stack
     [:label name]       - pseudo-instruction for jump targets"
  [ast-as-datoms]
  (let [datoms (vec ast-as-datoms)
        by-entity (group-by first datoms)
        get-attr (fn [e attr]
                   (some (fn [[_ a v]] (when (= a attr) v)) (get by-entity e)))
        root-id (ffirst datoms)
        instructions (atom [])
        emit! (fn [instr] (swap! instructions conj instr))
        label-counter (atom 0)
        gen-label! (fn [prefix]
                     (keyword (str prefix "-" (swap! label-counter inc))))]
    (letfn
      [(compile-node [e]
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
           (throw (ex-info "Unknown node type in stack assembly compilation"
                           {:type (get-attr e :yin/type), :entity e}))))]
      (compile-node root-id) @instructions)))


(defn stack-assembly->bytecode
  "Convert symbolic stack assembly to numeric bytecode.
   Returns [bytes pool]."
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
                       0))]
    ;; Pass 1: Calculate label offsets
    (doseq [instr instructions]
      (if (= :label (first instr))
        (swap! label-offsets assoc (second instr) @current-offset)
        (swap! current-offset + (instr-size instr))))
    ;; Pass 2: Emit bytes
    (let [bytes (atom [])
          emit-byte! (fn [b] (swap! bytes conj b))
          emit-short! (fn [s]
                        (emit-byte! (bit-shift-right (bit-and s 0xFF00) 8))
                        (emit-byte! (bit-and s 0xFF)))
          ;; Re-calculate current offset during emission for relative jumps
          emit-offset (atom 0)]
      (doseq [instr instructions]
        (let [[op arg1 arg2] instr]
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
          )))
      [@bytes @pool])))


;; --- Numeric Bytecode VM ---
;; This shows what information is LOST with numeric bytecode.
;; You cannot query "find all lambdas" without parsing the byte stream.

(defn- fetch-short
  [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))


(defn run-bytes
  "Execute legacy numeric bytecode.
   Note: This loses semantic information. You can't query the bytecode,
   only execute it sequentially.
   Uses an explicit call stack to avoid host recursion on closure calls."
  ([bytes constant-pool] (run-bytes bytes constant-pool {}))
  ([bytes constant-pool env]
   (loop [pc 0
          bytes (vec bytes)
          stack []
          env env
          call-stack []]
     (if (>= pc (count bytes))
       ;; End of bytes: return top of stack or pop frame
       (let [result (peek stack)]
         (if (empty? call-stack)
           result
           (let [frame (peek call-stack)
                 rest-frames (pop call-stack)]
             (recur (:pc frame)
                    (:bytes frame)
                    (conj (:stack frame) result)
                    (:env frame)
                    rest-frames))))
       (let [op (nth bytes pc)]
         (condp = op
           OP_LITERAL (let [val-idx (nth bytes (inc pc))
                            val (nth constant-pool val-idx)]
                        (recur (+ pc 2) bytes (conj stack val) env call-stack))
           OP_LOAD_VAR (let [sym-idx (nth bytes (inc pc))
                             sym (nth constant-pool sym-idx)
                             val (get env sym)]
                         (recur (+ pc 2) bytes (conj stack val) env call-stack))
           OP_LAMBDA (let [params-idx (nth bytes (inc pc))
                           params (nth constant-pool params-idx)
                           body-len (fetch-short bytes (+ pc 2))
                           body-start (+ pc 4)
                           body-bytes
                             (subvec bytes body-start (+ body-start body-len))
                           closure {:type :closure,
                                    :params params,
                                    :body-bytes body-bytes,
                                    :env env}]
                       (recur (+ pc 4 body-len)
                              bytes
                              (conj stack closure)
                              env
                              call-stack))
           OP_APPLY
             (let [argc (nth bytes (inc pc))
                   args (vec (take-last argc stack))
                   stack-minus-args (vec (drop-last argc stack))
                   fn-val (peek stack-minus-args)
                   stack-rest (pop stack-minus-args)]
               (cond (fn? fn-val) (let [res (apply fn-val args)]
                                    (recur (+ pc 2)
                                           bytes
                                           (conj stack-rest res)
                                           env
                                           call-stack))
                     (= :closure (:type fn-val))
                       (let [{clo-params :params,
                              clo-body :body-bytes,
                              clo-env :env}
                               fn-val
                             new-env (merge clo-env (zipmap clo-params args))
                             frame {:pc (+ pc 2),
                                    :bytes bytes,
                                    :stack stack-rest,
                                    :env env}]
                         (recur 0 clo-body [] new-env (conj call-stack frame)))
                     :else (throw (ex-info "Cannot apply non-function"
                                           {:fn fn-val}))))
           OP_JUMP_IF_FALSE
             (let [offset (fetch-short bytes (inc pc))
                   condition (peek stack)
                   new-stack (pop stack)]
               (if condition
                 (recur (+ pc 3) bytes new-stack env call-stack)
                 (recur (+ pc 3 offset) bytes new-stack env call-stack)))
           OP_JUMP (let [offset (fetch-short bytes (inc pc))]
                     (recur (+ pc 3 offset) bytes stack env call-stack))
           OP_RETURN (let [result (peek stack)]
                       (if (empty? call-stack)
                         result
                         (let [frame (peek call-stack)
                               rest-frames (pop call-stack)]
                           (recur (:pc frame)
                                  (:bytes frame)
                                  (conj (:stack frame) result)
                                  (:env frame)
                                  rest-frames))))
           (throw (ex-info "Unknown Opcode" {:op op, :pc pc}))))))))
