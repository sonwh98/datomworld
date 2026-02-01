(ns yin.assembly
  (:refer-clojure :exclude [compile type]))

;; =============================================================================
;; Semantic Bytecode: RISC for Semantics
;; =============================================================================
;;
;; Traditional bytecode uses numeric opcodes that lose semantic information:
;;   [1 0]  ; What does this mean? OP_LITERAL with index 0? Context-dependent.
;;
;; Semantic bytecode uses datom-like triples that preserve meaning:
;;   [node-1 :op/type :literal]
;;   [node-1 :op/value 42]
;;
;; This is "low-level form, high-level semantics" - flat and explicit like
;; assembly, but preserving semantic relationships for queryability.
;;
;; Key differences from numeric bytecode:
;; - Semantic: :op/type :lambda vs opcode 3
;; - Queryable: find all applications, all mutations, all closures
;; - Explicit: every relationship is a separate fact
;; - Composable: Datalog queries over semantic primitives
;;
;; The AST as datoms is canonical. Syntax (Clojure, Python, etc.) is a rendering.
;; =============================================================================

;; --- Node ID Generation ---
(def ^:private node-counter (atom 0))

(defn- gen-node-id
  "Generate a unique node ID for bytecode instructions."
  []
  (keyword "node" (str (swap! node-counter inc))))

(defn reset-node-counter!
  "Reset node counter (for testing)."
  []
  (reset! node-counter 0))

;; --- Semantic Opcodes as Keywords ---
;; Instead of numeric opcodes, we use semantic keywords that preserve meaning.
;; These are the "RISC primitives" for program semantics.

(def semantic-ops
  "The minimal set of semantic primitives (like RISC instruction set)."
  #{:literal      ; Self-evaluating value
    :load-var     ; Variable reference (lexical lookup)
    :lambda       ; Function creation (closure)
    :apply        ; Function application
    :jump-if      ; Conditional branch
    :jump         ; Unconditional branch
    :return})     ; Return from function

;; --- Datom-Based Instruction Representation ---
;; Each instruction is a set of datoms: [entity attribute value]
;; This enables Datalog queries over the bytecode.

(defn- emit-datoms
  "Emit datoms for a semantic instruction.
   Returns {:node node-id :datoms [...datoms...]}."
  [op-type & kvs]
  (let [node-id (gen-node-id)
        base-datoms [[node-id :op/type op-type]]
        extra-datoms (mapv (fn [[k v]] [node-id k v]) (partition 2 kvs))]
    {:node node-id
     :datoms (into base-datoms extra-datoms)}))

;; --- Constant Pool ---
;; Values are stored in a constant pool and referenced by ID.
;; Unlike numeric indices, we use semantic references.

(defn- add-constant [pool val]
  (let [const-id (keyword "const" (str (count pool)))]
    [(assoc pool const-id val) const-id]))

;; --- Compiler: AST → Semantic Datoms ---
;; Forward declaration
(declare compile-ast)

(defn compile-seq
  "Compile a sequence of AST nodes, threading the constant pool."
  [ast-seq pool]
  (reduce (fn [{:keys [datoms pool seq-nodes]} ast]
            (let [result (compile-ast ast pool)]
              {:datoms (into datoms (:datoms result))
               :pool (:pool result)
               :seq-nodes (conj seq-nodes (:node result))}))
          {:datoms [] :pool pool :seq-nodes []}
          ast-seq))

(defn compile-ast
  "Compile an AST node to semantic datoms.

   Returns {:node root-node-id
            :datoms [[e a v]...]
            :pool constant-pool}"
  [ast pool]
  (let [{:keys [type value name params body operator operands test consequent alternate]} ast]
    (case type
      ;; Literal: self-evaluating value
      ;; Traditional: [OP_LITERAL idx]
      ;; Semantic: [node-1 :op/type :literal] [node-1 :op/value 42]
      :literal
      (let [[new-pool const-id] (add-constant pool value)
            {:keys [node datoms]} (emit-datoms :literal
                                               :op/const-ref const-id
                                               :op/value-type (cond
                                                                (number? value) :number
                                                                (string? value) :string
                                                                (boolean? value) :boolean
                                                                (nil? value) :nil
                                                                :else :unknown))]
        {:node node :datoms datoms :pool new-pool})

      ;; Variable: lexical lookup
      ;; Traditional: [OP_LOAD_VAR idx]
      ;; Semantic: [node-1 :op/type :load-var] [node-1 :op/var-name 'x]
      :variable
      (let [[new-pool const-id] (add-constant pool name)
            {:keys [node datoms]} (emit-datoms :load-var
                                               :op/const-ref const-id
                                               :op/var-name name)]
        {:node node :datoms datoms :pool new-pool})

      ;; Lambda: closure creation
      ;; Traditional: [OP_LAMBDA params-idx len-hi len-lo ...body...]
      ;; Semantic: preserves params, body reference, captures environment
      :lambda
      (let [;; Compile body first
            body-result (compile-ast body pool)
            [params-pool params-id] (add-constant (:pool body-result) params)
            {:keys [node datoms]} (emit-datoms :lambda
                                               :op/params-ref params-id
                                               :op/params params
                                               :op/arity (count params)
                                               :op/body-node (:node body-result)
                                               :op/captures-env? true)]
        {:node node
         :datoms (into datoms (:datoms body-result))
         :pool params-pool})

      ;; Application: function call
      ;; Traditional: [OP_APPLY argc]
      ;; Semantic: preserves operator, operands, arity explicitly
      :application
      (let [;; Compile operator
            op-result (compile-ast operator pool)
            ;; Compile operands
            args-result (compile-seq operands (:pool op-result))
            {:keys [node datoms]} (emit-datoms :apply
                                               :op/operator-node (:node op-result)
                                               :op/operand-nodes (:seq-nodes args-result)
                                               :op/arity (count operands)
                                               :op/call-type :unknown)] ; Could be :primitive, :closure, etc.
        {:node node
         :datoms (-> datoms
                     (into (:datoms op-result))
                     (into (:datoms args-result)))
         :pool (:pool args-result)})

      ;; Conditional: if-then-else
      ;; Traditional: [test...] [JUMP_IF_FALSE offset] [cons...] [JUMP offset] [alt...]
      ;; Semantic: preserves structure without offset calculation
      :if
      (let [test-result (compile-ast test pool)
            cons-result (compile-ast consequent (:pool test-result))
            alt-result (compile-ast alternate (:pool cons-result))
            {:keys [node datoms]} (emit-datoms :jump-if
                                               :op/test-node (:node test-result)
                                               :op/consequent-node (:node cons-result)
                                               :op/alternate-node (:node alt-result)
                                               :op/branch-type :if-then-else)]
        {:node node
         :datoms (-> datoms
                     (into (:datoms test-result))
                     (into (:datoms cons-result))
                     (into (:datoms alt-result)))
         :pool (:pool alt-result)})

      ;; Default: unknown AST type
      (throw (ex-info "Unknown AST type" {:ast ast})))))

(defn compile
  "Compile an AST to semantic bytecode.

   Returns {:node root-node
            :datoms [[e a v]...]
            :pool {const-id value...}}"
  [ast]
  (reset-node-counter!)
  (compile-ast ast {}))

;; =============================================================================
;; Querying Semantic Bytecode
;; =============================================================================
;; Because bytecode is now datoms, we can query it with Datalog-like patterns.
;; This is the key insight from the blog: low-level form enables high-level queries.

(defn find-by-type
  "Find all nodes of a given semantic type.

   Example: (find-by-type datoms :lambda) => all lambda nodes"
  [datoms op-type]
  (->> datoms
       (filter (fn [[_ a v]] (and (= a :op/type) (= v op-type))))
       (map first)
       set))

(defn find-by-attr
  "Find all datoms with a given attribute.

   Example: (find-by-attr datoms :op/arity) => all nodes with arity info"
  [datoms attr]
  (filter (fn [[_ a _]] (= a attr)) datoms))

(defn get-node-attrs
  "Get all attributes for a node as a map.

   Example: (get-node-attrs datoms :node/1) => {:op/type :lambda, :op/arity 2, ...}"
  [datoms node-id]
  (->> datoms
       (filter (fn [[e _ _]] (= e node-id)))
       (reduce (fn [m [_ a v]] (assoc m a v)) {})))

(defn find-applications
  "Find all function applications in the bytecode."
  [datoms]
  (find-by-type datoms :apply))

(defn find-lambdas
  "Find all lambda definitions in the bytecode."
  [datoms]
  (find-by-type datoms :lambda))

(defn find-variables
  "Find all variable references in the bytecode."
  [datoms]
  (find-by-type datoms :load-var))

;; =============================================================================
;; VM: Execute Semantic Bytecode
;; =============================================================================
;; The VM interprets semantic datoms. Unlike numeric bytecode which requires
;; sequential execution, semantic bytecode can be traversed by node reference.

(defn- get-const [pool const-ref]
  (get pool const-ref))

(defn- node->map
  "Convert datoms for a node into a map for efficient lookup."
  [datoms node-id]
  (get-node-attrs datoms node-id))

(defn run-semantic
  "Execute semantic bytecode starting from a node.

   Unlike traditional bytecode VMs that use a program counter,
   this follows node references (like graph traversal)."
  ([compiled] (run-semantic compiled {}))
  ([{:keys [node datoms pool]} env]
   (let [node-map (node->map datoms node)]
     (case (:op/type node-map)
       :literal
       (get-const pool (:op/const-ref node-map))

       :load-var
       (let [var-name (get-const pool (:op/const-ref node-map))]
         (get env var-name))

       :lambda
       (let [params (get-const pool (:op/params-ref node-map))
             body-node (:op/body-node node-map)]
         {:type :closure
          :params params
          :body-node body-node
          :datoms datoms
          :pool pool
          :env env})

       :apply
       (let [op-node (:op/operator-node node-map)
             operand-nodes (:op/operand-nodes node-map)
             ;; Evaluate operator
             fn-val (run-semantic {:node op-node :datoms datoms :pool pool} env)
             ;; Evaluate operands
             args (mapv #(run-semantic {:node % :datoms datoms :pool pool} env)
                        operand-nodes)]
         (cond
           ;; Host function (primitive)
           (fn? fn-val)
           (apply fn-val args)

           ;; Closure
           (= :closure (:type fn-val))
           (let [{:keys [params body-node datoms pool env]} fn-val
                 new-env (merge env (zipmap params args))]
             (run-semantic {:node body-node :datoms datoms :pool pool} new-env))

           :else
           (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))

       :jump-if
       (let [test-node (:op/test-node node-map)
             cons-node (:op/consequent-node node-map)
             alt-node (:op/alternate-node node-map)
             test-val (run-semantic {:node test-node :datoms datoms :pool pool} env)]
         (if test-val
           (run-semantic {:node cons-node :datoms datoms :pool pool} env)
           (run-semantic {:node alt-node :datoms datoms :pool pool} env)))

       (throw (ex-info "Unknown op type" {:node-map node-map}))))))

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

(defn- add-constant-legacy [pool val]
  (let [idx (count pool)]
    [(conj pool val) idx]))

;; =============================================================================
;; Datoms -> Stack Assembly
;; =============================================================================

(defn ast-datoms->stack-assembly
  "Takes the AST as datoms and transforms it to symbolic stack assembly.

   Assembly is a vector of symbolic instructions (keyword mnemonics).
   Unlike register assembly, this targets a stack machine.

   Instructions:
     [:push v]           - push literal value v
     [:load name]        - push value of variable name
     [:call argc]        - pop function and argc args, call, push result
     [:lambda params body-len] - push closure (captures env, body follows)
     [:jump-false label] - pop condition, if false jump to label
     [:jump label]       - unconditional jump to label
     [:return]           - return top of stack
     [:label name]       - pseudo-instruction for jump targets"
  [ast-as-datoms]
  (let [;; Materialize and index datoms by entity
        datoms (vec ast-as-datoms)
        by-entity (group-by first datoms)

        ;; Attribute mapping from op (assembly) to yin (vm) namespaces
        attr-mapping {:op/type :yin/type
                      :op/value :yin/value
                      :op/var-name :yin/name
                      :op/params :yin/params
                      :op/body-node :yin/body
                      :op/operator-node :yin/operator
                      :op/operand-nodes :yin/operands
                      :op/test-node :yin/test
                      :op/consequent-node :yin/consequent
                      :op/alternate-node :yin/alternate}

        ;; Get attribute value for entity (checks both :op/... and :yin/... attributes)
        get-attr (fn [e attr]
                   (let [yin-attr (get attr-mapping attr)]
                     (some (fn [[_ a v _ _]]
                             (cond
                               (= a attr) v
                               (= a yin-attr) v
                               :else nil))
                           (get by-entity e))))

        ;; Find root entity (max of negative tempids = -1)
        root-id (apply max (keys by-entity))

        ;; Assembly accumulator
        instructions (atom [])
        emit! (fn [instr] (swap! instructions conj instr))

        ;; Label generator
        label-counter (atom 0)
        gen-label! (fn [prefix]
                     (keyword (str prefix "-" (swap! label-counter inc))))]

    (letfn [(compile-node [e]
              (let [node-type (get-attr e :op/type)]
                (case node-type
                  :literal
                  (emit! [:push (get-attr e :op/value)])

                  :variable
                  (emit! [:load (get-attr e :op/var-name)])

                  :load-var ;; Handle :load-var explicitly if node-type matches but attr names differ?
                  (emit! [:load (get-attr e :op/var-name)])

                  :lambda
                  (let [params (get-attr e :op/params)
                        body-node (get-attr e :op/body-node)
                        skip-label (gen-label! "after-lambda")]
                    ;; Emit lambda header
                    (emit! [:lambda params skip-label])
                    ;; Compile body
                    (compile-node body-node)
                    (emit! [:return])
                    ;; Emit skip label
                    (emit! [:label skip-label]))

                  :apply
                  (let [op-node (get-attr e :op/operator-node)
                        operand-nodes (get-attr e :op/operand-nodes)]
                    ;; Push operator
                    (compile-node op-node)
                    ;; Push operands
                    (doseq [arg-node operand-nodes]
                      (compile-node arg-node))
                    ;; Call
                    (emit! [:call (count operand-nodes)]))

                  :application ;; Handle :application alias for :apply
                  (let [op-node (get-attr e :op/operator-node)
                        operand-nodes (get-attr e :op/operand-nodes)]
                    ;; Push operator
                    (compile-node op-node)
                    ;; Push operands
                    (doseq [arg-node operand-nodes]
                      (compile-node arg-node))
                    ;; Call
                    (emit! [:call (count operand-nodes)]))

                  :jump-if
                  (let [test-node (get-attr e :op/test-node)
                        cons-node (get-attr e :op/consequent-node)
                        alt-node (get-attr e :op/alternate-node)
                        else-label (gen-label! "else")
                        end-label (gen-label! "end")]
                    ;; Test
                    (compile-node test-node)
                    (emit! [:jump-false else-label])
                    ;; Consequent
                    (compile-node cons-node)
                    (emit! [:jump end-label])
                    ;; Alternate
                    (emit! [:label else-label])
                    (compile-node alt-node)
                    ;; End
                    (emit! [:label end-label]))

                  :if ;; Handle :if alias for :jump-if
                  (let [test-node (get-attr e :op/test-node)
                        cons-node (get-attr e :op/consequent-node)
                        alt-node (get-attr e :op/alternate-node)
                        else-label (gen-label! "else")
                        end-label (gen-label! "end")]
                    ;; Test
                    (compile-node test-node)
                    (emit! [:jump-false else-label])
                    ;; Consequent
                    (compile-node cons-node)
                    (emit! [:jump end-label])
                    ;; Alternate
                    (emit! [:label else-label])
                    (compile-node alt-node)
                    ;; End
                    (emit! [:label end-label]))

                  ;; Handle unknown types gracefully (e.g. implicitly return nil or throw)
                  (throw (ex-info "Unknown node type in stack assembly compilation"
                                  {:type node-type :entity e})))))]

      (compile-node root-id)
      @instructions)))

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
                       :lambda 4 ;; op + params_idx + 2 byte len
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
            :push
            (let [idx (add-constant arg1)]
              (emit-byte! OP_LITERAL)
              (emit-byte! idx)
              (swap! emit-offset + 2))

            :load
            (let [idx (add-constant arg1)]
              (emit-byte! OP_LOAD_VAR)
              (emit-byte! idx)
              (swap! emit-offset + 2))

            :lambda
            (let [params-idx (add-constant arg1)
                  target-label arg2
                  target-offset (get @label-offsets target-label)
                  ;; Body length = target-offset - (current-offset + 4)
                  body-len (- target-offset (+ @emit-offset 4))]
              (emit-byte! OP_LAMBDA)
              (emit-byte! params-idx)
              (emit-short! body-len)
              (swap! emit-offset + 4))

            :call
            (do
              (emit-byte! OP_APPLY)
              (emit-byte! arg1)
              (swap! emit-offset + 2))

            :jump-false
            (let [target-label arg1
                  target-offset (get @label-offsets target-label)
                  ;; Relative offset = target-offset - (current-offset + 3)
                  rel-offset (- target-offset (+ @emit-offset 3))]
              (emit-byte! OP_JUMP_IF_FALSE)
              (emit-short! rel-offset)
              (swap! emit-offset + 3))

            :jump
            (let [target-label arg1
                  target-offset (get @label-offsets target-label)
                  rel-offset (- target-offset (+ @emit-offset 3))]
              (emit-byte! OP_JUMP)
              (emit-short! rel-offset)
              (swap! emit-offset + 3))

            :return
            (do
              (emit-byte! OP_RETURN)
              (swap! emit-offset + 1))

            :label
            nil ;; No code emitted
            )))

      [@bytes @pool])))

(defn compile-legacy
  "Compile AST to traditional numeric bytecode (for comparison).
   Now reimplemented using the stack assembly pipeline."
  [ast]
  ;; Use the new pipeline: AST -> Datoms -> Stack Asm -> Bytecode
  (reset-node-counter!)
  (let [datoms (:datoms (compile ast))
        stack-asm (ast-datoms->stack-assembly datoms)]
    (stack-assembly->bytecode stack-asm)))

;; --- Legacy Numeric VM ---
;; This shows what information is LOST with numeric bytecode.
;; You cannot query "find all lambdas" without parsing the byte stream.

(defn- fetch-short [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))

(defn run-bytes
  "Execute legacy numeric bytecode.
   Note: This loses semantic information. You can't query the bytecode,
   only execute it sequentially."
  ([bytes constant-pool] (run-bytes bytes constant-pool {}))
  ([bytes constant-pool env]
   (loop [pc 0
          stack []
          env env]
     (if (>= pc (count bytes))
       (peek stack)
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
                 body-bytes (vec (subvec (vec bytes) body-start (+ body-start body-len)))
                 closure {:type :closure
                          :params params
                          :body-bytes body-bytes
                          :env env}]
             (recur (+ pc 4 body-len) (conj stack closure) env))

           OP_APPLY
           (let [argc (nth bytes (inc pc))
                 args (vec (take-last argc stack))
                 stack-minus-args (vec (drop-last argc stack))
                 fn-val (peek stack-minus-args)
                 stack-rest (pop stack-minus-args)]
             (cond
               (fn? fn-val)
               (let [res (apply fn-val args)]
                 (recur (+ pc 2) (conj stack-rest res) env))

               (= :closure (:type fn-val))
               (let [{:keys [params body-bytes env]} fn-val
                     new-env (merge env (zipmap params args))]
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

;; =============================================================================
;; Comparison: The Key Insight
;; =============================================================================
;;
;; NUMERIC BYTECODE (loses information):
;;   [1 0 1 1 4 2]
;;   - Cannot query "what operations use variable x?"
;;   - Cannot find "all function applications"
;;   - Must execute sequentially, opcode by opcode
;;
;; SEMANTIC BYTECODE (preserves information):
;;   [[:node/1 :op/type :literal]
;;    [:node/1 :op/value 42]
;;    [:node/2 :op/type :apply]
;;    [:node/2 :op/operator-node :node/3]
;;    [:node/2 :op/arity 2]]
;;   - Query: (find-by-type datoms :apply) => all applications
;;   - Query: (find-by-attr datoms :op/arity) => all nodes with arity
;;   - Traverse by reference, not by offset
;;
;; Both are "low-level" (flat, explicit), but semantic bytecode
;; preserves meaning while numeric bytecode destroys it.
;;
;; This is the blog's key point: the Universal AST looks like assembly
;; (low-level form) but operates at a fundamentally higher abstraction
;; (semantic preservation, queryability).
;; =============================================================================