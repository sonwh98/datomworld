(ns yin.vm.stack
  (:require [datascript.core :as d]
            [yin.vm :as vm]
            [yin.vm.protocols :as proto]))


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
;; VM Records
;; =============================================================================

(defrecord StackVM [pc         ; program counter
                    bytes      ; bytecode vector
                    stack      ; operand stack
                    env        ; lexical environment
                    call-stack ; call frames for function returns
                    pool       ; constant pool
                    halted     ; true if execution completed
                    value      ; final result value
                    store      ; heap memory
                    db         ; DataScript db value
                    parked     ; parked continuations
                    id-counter ; unique ID counter
                    primitives ; primitive operations
                   ])


(defrecord SemanticVM [control    ; current control state {:type
                       ;; :node/:value, ...}
                       env        ; lexical environment
                       stack      ; continuation stack
                       datoms     ; AST datoms
                       halted     ; true if execution completed
                       value      ; final result value
                       store      ; heap memory
                       db         ; DataScript db value
                       parked     ; parked continuations
                       id-counter ; unique ID counter
                       primitives ; primitive operations
                      ])


;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================
;; The VM interprets :yin/ datoms by traversing node references (graph walk).
;; No program counter, no constant pool — values are inline in the datoms.

;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================
;; The VM interprets :yin/ datoms by traversing node references (graph walk).
;; No program counter, no constant pool — values are inline in the datoms.

(defn make-semantic-state
  "Create initial state for stepping the semantic (datom) VM."
  [{:keys [node datoms]} env]
  (merge (vm/empty-state)
         {:control {:type :node, :id node},
          :env env,
          :stack [],
          :datoms datoms,
          :halted false,
          :value nil}))


(defn semantic-step
  "Execute one step of the semantic VM."
  [state]
  (let [{:keys [control env stack datoms]} state]
    (if (= :value (:type control))
      ;; Handle return value from previous step
      (let [val (:val control)]
        (if (empty? stack)
          (assoc state
            :halted true
            :value val)
          (let [frame (peek stack)
                new-stack (pop stack)]
            (case (:type frame)
              :if (let [{:keys [cons alt env]} frame]
                    (assoc state
                      :control {:type :node, :id (if val cons alt)}
                      :env env
                      :stack new-stack))
              :app-op (let [{:keys [operands env]} frame
                            fn-val val]
                        (if (empty? operands)
                          ;; 0-arity call
                          (if (fn? fn-val)
                            (assoc state
                              :control {:type :value, :val (fn-val)}
                              :env env
                              :stack new-stack)
                            (if (= :closure (:type fn-val))
                              (let [{:keys [body-node env]} fn-val]
                                (assoc state
                                  :control {:type :node, :id body-node}
                                  :env env ; Switch to closure env
                                  :stack (conj new-stack
                                               {:type :restore-env,
                                                :env (:env frame)})))
                              (throw (ex-info "Cannot apply non-function"
                                              {:fn fn-val}))))
                          ;; Prepare to eval args
                          (let [first-arg (first operands)
                                rest-args (vec (rest operands))]
                            (assoc state
                              :control {:type :node, :id first-arg}
                              :env env
                              :stack (conj new-stack
                                           {:type :app-args,
                                            :fn fn-val,
                                            :evaluated [],
                                            :pending rest-args,
                                            :env env})))))
              :app-args
                (let [{:keys [fn evaluated pending env]} frame
                      new-evaluated (conj evaluated val)]
                  (if (empty? pending)
                    ;; All args evaluated, apply
                    (if (fn? fn)
                      (assoc state
                        :control {:type :value, :val (apply fn new-evaluated)}
                        :env env
                        :stack new-stack)
                      (if (= :closure (:type fn))
                        (let [{:keys [params body-node env]} fn
                              new-env (merge env (zipmap params new-evaluated))]
                          (assoc state
                            :control {:type :node, :id body-node}
                            :env new-env ; Closure env + args
                            :stack (conj new-stack
                                         {:type :restore-env,
                                          :env (:env frame)})))
                        (throw (ex-info "Cannot apply non-function" {:fn fn}))))
                    ;; More args to eval
                    (let [next-arg (first pending)
                          rest-pending (vec (rest pending))]
                      (assoc state
                        :control {:type :node, :id next-arg}
                        :env env
                        :stack (conj new-stack
                                     (assoc frame
                                       :evaluated new-evaluated
                                       :pending rest-pending))))))
              :restore-env (assoc state
                             :control control ; Pass value up
                             :env (:env frame) ; Restore caller env
                             :stack new-stack)))))
      ;; Handle node evaluation
      (let [node-id (:id control)
            node-map (get-node-attrs datoms node-id)
            node-type (:yin/type node-map)]
        (case node-type
          :literal (assoc state
                     :control {:type :value, :val (:yin/value node-map)})
          :variable (assoc state
                      :control {:type :value,
                                :val (get env (:yin/name node-map))})
          :lambda (assoc state
                    :control {:type :value,
                              :val {:type :closure,
                                    :params (:yin/params node-map),
                                    :body-node (:yin/body node-map),
                                    :datoms datoms,
                                    :env env}})
          :if (assoc state
                :control {:type :node, :id (:yin/test node-map)}
                :stack (conj stack
                             {:type :if,
                              :cons (:yin/consequent node-map),
                              :alt (:yin/alternate node-map),
                              :env env}))
          :application (assoc state
                         :control {:type :node, :id (:yin/operator node-map)}
                         :stack (conj stack
                                      {:type :app-op,
                                       :operands (:yin/operands node-map),
                                       :env env}))
          (throw (ex-info "Unknown node type" {:node-map node-map})))))))


(defn semantic-run
  "Run semantic VM to completion."
  [state]
  (loop [s state] (if (:halted s) (:value s) (recur (semantic-step s)))))


(defn run-semantic
  "Execute :yin/ datoms starting from a root node.

   Traverses the datom graph by following entity references.
   Takes {:node root-entity-id :datoms [[e a v ...] ...]} and an env map."
  ([compiled] (run-semantic compiled {}))
  ([compiled env] (semantic-run (make-semantic-state compiled env))))


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
          )))
      {:bc @bytes, :pool @pool, :source-map @source-map})))


;; --- Numeric Bytecode VM ---
;; This shows what information is LOST with numeric bytecode.
;; You cannot query "find all lambdas" without parsing the byte stream.

(defn- fetch-short
  [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))


(defn make-stack-state
  "Create initial stack VM state for stepping execution."
  ([bytes constant-pool] (make-stack-state bytes constant-pool {}))
  ([bytes constant-pool env]
   (merge (vm/empty-state)
          {:pc 0,
           :bytes (vec bytes),
           :stack [],
           :env env,
           :call-stack [],
           :pool constant-pool,
           :halted false,
           :value nil})))


(defn stack-step
  "Execute one stack VM instruction. Returns updated state.
   When execution completes, :halted is true and :value contains the result."
  [state]
  (let [{:keys [pc bytes stack env call-stack pool]} state]
    (if (>= pc (count bytes))
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
              :bytes (:bytes frame)
              :stack (conj (:stack frame) result)
              :env (:env frame)
              :call-stack rest-frames))))
      (let [op (nth bytes pc)]
        (condp = op
          OP_LITERAL (let [val-idx (nth bytes (inc pc))
                           val (nth pool val-idx)]
                       (assoc state
                         :pc (+ pc 2)
                         :stack (conj stack val)))
          OP_LOAD_VAR (let [sym-idx (nth bytes (inc pc))
                            sym (nth pool sym-idx)
                            val (get env sym)]
                        (assoc state
                          :pc (+ pc 2)
                          :stack (conj stack val)))
          OP_LAMBDA (let [params-idx (nth bytes (inc pc))
                          params (nth pool params-idx)
                          body-len (fetch-short bytes (+ pc 2))
                          body-start (+ pc 4)
                          body-bytes
                            (subvec bytes body-start (+ body-start body-len))
                          closure {:type :closure,
                                   :params params,
                                   :body-bytes body-bytes,
                                   :env env}]
                      (assoc state
                        :pc (+ pc 4 body-len)
                        :stack (conj stack closure)))
          OP_APPLY (let [argc (nth bytes (inc pc))
                         args (vec (take-last argc stack))
                         stack-minus-args (vec (drop-last argc stack))
                         fn-val (peek stack-minus-args)
                         stack-rest (pop stack-minus-args)]
                     (cond (fn? fn-val) (let [res (apply fn-val args)]
                                          (assoc state
                                            :pc (+ pc 2)
                                            :stack (conj stack-rest res)))
                           (= :closure (:type fn-val))
                             (let [{clo-params :params,
                                    clo-body :body-bytes,
                                    clo-env :env}
                                     fn-val
                                   new-env (merge clo-env
                                                  (zipmap clo-params args))
                                   frame {:pc (+ pc 2),
                                          :bytes bytes,
                                          :stack stack-rest,
                                          :env env}]
                               (assoc state
                                 :pc 0
                                 :bytes clo-body
                                 :stack []
                                 :env new-env
                                 :call-stack (conj call-stack frame)))
                           :else (throw (ex-info "Cannot apply non-function"
                                                 {:fn fn-val}))))
          OP_JUMP_IF_FALSE (let [offset (fetch-short bytes (inc pc))
                                 condition (peek stack)
                                 new-stack (pop stack)]
                             (assoc state
                               :pc (if condition (+ pc 3) (+ pc 3 offset))
                               :stack new-stack))
          OP_JUMP (let [offset (fetch-short bytes (inc pc))]
                    (assoc state :pc (+ pc 3 offset)))
          OP_RETURN (let [result (peek stack)]
                      (if (empty? call-stack)
                        (assoc state
                          :halted true
                          :value result)
                        (let [frame (peek call-stack)
                              rest-frames (pop call-stack)]
                          (assoc state
                            :pc (:pc frame)
                            :bytes (:bytes frame)
                            :stack (conj (:stack frame) result)
                            :env (:env frame)
                            :call-stack rest-frames))))
          (throw (ex-info "Unknown Opcode" {:op op, :pc pc})))))))


(defn stack-run
  "Run stack VM to completion. Returns final value."
  [state]
  (loop [s state] (if (:halted s) (:value s) (recur (stack-step s)))))


(defn run-bytes
  "Execute numeric bytecode to completion.
   Note: This loses semantic information. You can't query the bytecode,
   only execute it sequentially.
   Delegates to make-stack-state and stack-run for backward compatibility."
  ([bytes constant-pool] (run-bytes bytes constant-pool {}))
  ([bytes constant-pool env]
   (stack-run (make-stack-state bytes constant-pool env))))


;; =============================================================================
;; StackVM Protocol Implementation
;; =============================================================================

(defn- stack-vm->state
  "Convert StackVM to legacy state map."
  [^StackVM vm]
  {:pc (:pc vm),
   :bytes (:bytes vm),
   :stack (:stack vm),
   :env (:env vm),
   :call-stack (:call-stack vm),
   :pool (:pool vm),
   :halted (:halted vm),
   :value (:value vm),
   :store (:store vm),
   :db (:db vm),
   :parked (:parked vm),
   :id-counter (:id-counter vm),
   :primitives (:primitives vm)})


(defn- state->stack-vm
  "Convert legacy state map back to StackVM."
  [^StackVM vm state]
  (->StackVM (:pc state)
             (:bytes state)
             (:stack state)
             (:env state)
             (:call-stack state)
             (:pool state)
             (:halted state)
             (:value state)
             (:store state)
             (:db state)
             (:parked state)
             (:id-counter state)
             (:primitives state)))


(defn stack-vm-step
  "Execute one step of StackVM. Returns updated VM."
  [^StackVM vm]
  (let [state (stack-vm->state vm)
        new-state (stack-step state)]
    (state->stack-vm vm new-state)))


(defn stack-vm-halted?
  "Returns true if VM has halted."
  [^StackVM vm]
  (boolean (:halted vm)))


(defn stack-vm-blocked?
  "Returns true if VM is blocked."
  [^StackVM vm]
  (= :yin/blocked (:value vm)))


(defn stack-vm-value "Returns the current value." [^StackVM vm] (:value vm))


(defn stack-vm-run
  "Run StackVM until halted or blocked."
  [^StackVM vm]
  (loop [v vm]
    (if (or (stack-vm-halted? v) (stack-vm-blocked? v))
      v
      (recur (stack-vm-step v)))))


(defn stack-vm-load-program
  "Load bytecode into the VM.
   Expects {:bc [...] :pool [...]}."
  [^StackVM vm {:keys [bc pool]}]
  (map->StackVM (merge (stack-vm->state vm)
                       {:pc 0,
                        :bytes (vec bc),
                        :stack [],
                        :call-stack [],
                        :pool pool,
                        :halted false,
                        :value nil})))


(defn stack-vm-transact!
  "Transact datoms into the VM's DataScript db."
  [^StackVM vm datoms]
  (let [tx-data (vm/datoms->tx-data datoms)
        conn (d/conn-from-db (:db vm))
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:vm (assoc vm :db @conn), :tempids tempids}))


(defn stack-vm-q
  "Run a Datalog query against the VM's db."
  [^StackVM vm args]
  (apply d/q (first args) (:db vm) (rest args)))


(extend-type StackVM
  proto/IVMStep
    (step [vm] (stack-vm-step vm))
    (halted? [vm] (stack-vm-halted? vm))
    (blocked? [vm] (stack-vm-blocked? vm))
    (value [vm] (stack-vm-value vm))
  proto/IVMRun
    (run [vm] (stack-vm-run vm))
  proto/IVMLoad
    (load-program [vm program] (stack-vm-load-program vm program))
  proto/IVMDataScript
    (transact! [vm datoms] (stack-vm-transact! vm datoms))
    (q [vm args] (stack-vm-q vm args)))


(defn create-stack-vm
  "Create a new StackVM with optional environment."
  ([] (create-stack-vm {}))
  ([env]
   (map->StackVM (merge (vm/empty-state)
                        {:pc 0,
                         :bytes [],
                         :stack [],
                         :env env,
                         :call-stack [],
                         :pool [],
                         :halted false,
                         :value nil}))))


;; =============================================================================
;; SemanticVM Protocol Implementation
;; =============================================================================

(defn- semantic-vm->state
  "Convert SemanticVM to legacy state map."
  [^SemanticVM vm]
  {:control (:control vm),
   :env (:env vm),
   :stack (:stack vm),
   :datoms (:datoms vm),
   :halted (:halted vm),
   :value (:value vm),
   :store (:store vm),
   :db (:db vm),
   :parked (:parked vm),
   :id-counter (:id-counter vm),
   :primitives (:primitives vm)})


(defn- state->semantic-vm
  "Convert legacy state map back to SemanticVM."
  [^SemanticVM vm state]
  (->SemanticVM (:control state)
                (:env state)
                (:stack state)
                (:datoms state)
                (:halted state)
                (:value state)
                (:store state)
                (:db state)
                (:parked state)
                (:id-counter state)
                (:primitives state)))


(defn semantic-vm-step
  "Execute one step of SemanticVM. Returns updated VM."
  [^SemanticVM vm]
  (let [state (semantic-vm->state vm)
        new-state (semantic-step state)]
    (state->semantic-vm vm new-state)))


(defn semantic-vm-halted?
  "Returns true if VM has halted."
  [^SemanticVM vm]
  (boolean (:halted vm)))


(defn semantic-vm-blocked?
  "Returns true if VM is blocked."
  [^SemanticVM vm]
  (= :yin/blocked (:value vm)))


(defn semantic-vm-value
  "Returns the current value."
  [^SemanticVM vm]
  (:value vm))


(defn semantic-vm-run
  "Run SemanticVM until halted or blocked."
  [^SemanticVM vm]
  (loop [v vm]
    (if (or (semantic-vm-halted? v) (semantic-vm-blocked? v))
      v
      (recur (semantic-vm-step v)))))


(defn semantic-vm-load-program
  "Load datoms into the VM.
   Expects {:node root-id :datoms [...]}."
  [^SemanticVM vm {:keys [node datoms]}]
  (map->SemanticVM (merge (semantic-vm->state vm)
                          {:control {:type :node, :id node},
                           :stack [],
                           :datoms datoms,
                           :halted false,
                           :value nil})))


(defn semantic-vm-transact!
  "Transact datoms into the VM's DataScript db."
  [^SemanticVM vm datoms]
  (let [tx-data (vm/datoms->tx-data datoms)
        conn (d/conn-from-db (:db vm))
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:vm (assoc vm :db @conn), :tempids tempids}))


(defn semantic-vm-q
  "Run a Datalog query against the VM's db."
  [^SemanticVM vm args]
  (apply d/q (first args) (:db vm) (rest args)))


(extend-type SemanticVM
  proto/IVMStep
    (step [vm] (semantic-vm-step vm))
    (halted? [vm] (semantic-vm-halted? vm))
    (blocked? [vm] (semantic-vm-blocked? vm))
    (value [vm] (semantic-vm-value vm))
  proto/IVMRun
    (run [vm] (semantic-vm-run vm))
  proto/IVMLoad
    (load-program [vm program] (semantic-vm-load-program vm program))
  proto/IVMDataScript
    (transact! [vm datoms] (semantic-vm-transact! vm datoms))
    (q [vm args] (semantic-vm-q vm args)))


(defn create-semantic-vm
  "Create a new SemanticVM with optional environment."
  ([] (create-semantic-vm {}))
  ([env]
   (map->SemanticVM (merge (vm/empty-state)
                           {:control nil,
                            :env env,
                            :stack [],
                            :datoms [],
                            :halted false,
                            :value nil}))))
