(ns yin.vm.stack
  (:require [datascript.core :as d]
            [yin.vm :as vm]
            [yin.vm.protocols :as proto]))


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

(defrecord StackVM [pc         ; program counter
                    bytecode   ; bytecode vector
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


(defn assembly->bytecode
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


(defn- fetch-short
  [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))


(defn make-state
  "Create initial stack VM state for stepping execution."
  ([bytes constant-pool] (make-state bytes constant-pool {}))
  ([bytes constant-pool env]
   (merge (vm/empty-state)
          {:pc 0,
           :bytecode (vec bytes),
           :stack [],
           :env env,
           :call-stack [],
           :pool constant-pool,
           :halted false,
           :value nil})))


(defn- stack-step
  "Execute one stack VM instruction. Returns updated state.
   When execution completes, :halted is true and :value contains the result."
  [state]
  (let [{:keys [pc bytecode stack env call-stack pool]} state]
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
        (condp = op
          OP_LITERAL (let [val-idx (nth bytecode (inc pc))
                           val (nth pool val-idx)]
                       (assoc state
                         :pc (+ pc 2)
                         :stack (conj stack val)))
          OP_LOAD_VAR (let [sym-idx (nth bytecode (inc pc))
                            sym (nth pool sym-idx)
                            val (get env sym)]
                        (assoc state
                          :pc (+ pc 2)
                          :stack (conj stack val)))
          OP_LAMBDA (let [params-idx (nth bytecode (inc pc))
                          params (nth pool params-idx)
                          body-len (fetch-short bytecode (+ pc 2))
                          body-start (+ pc 4)
                          body-bytes
                            (subvec bytecode body-start (+ body-start body-len))
                          closure {:type :closure,
                                   :params params,
                                   :body-bytes body-bytes,
                                   :env env}]
                      (assoc state
                        :pc (+ pc 4 body-len)
                        :stack (conj stack closure)))
          OP_APPLY (let [argc (nth bytecode (inc pc))
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
                                          :bytecode bytecode,
                                          :stack stack-rest,
                                          :env env}]
                               (assoc state
                                 :pc 0
                                 :bytecode clo-body
                                 :stack []
                                 :env new-env
                                 :call-stack (conj call-stack frame)))
                           :else (throw (ex-info "Cannot apply non-function"
                                                 {:fn fn-val}))))
          OP_JUMP_IF_FALSE (let [offset (fetch-short bytecode (inc pc))
                                 condition (peek stack)
                                 new-stack (pop stack)]
                             (assoc state
                               :pc (if condition (+ pc 3) (+ pc 3 offset))
                               :stack new-stack))
          OP_JUMP (let [offset (fetch-short bytecode (inc pc))]
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
                            :bytecode (:bytecode frame)
                            :stack (conj (:stack frame) result)
                            :env (:env frame)
                            :call-stack rest-frames))))
          (throw (ex-info "Unknown Opcode" {:op op, :pc pc})))))))


;; =============================================================================
;; StackVM Protocol Implementation
;; =============================================================================

(defn- stack-vm->state
  "Convert StackVM to legacy state map."
  [^StackVM vm]
  {:pc (:pc vm),
   :bytecode (:bytecode vm),
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
             (:bytecode state)
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


(defn- stack-vm-step
  "Execute one step of StackVM. Returns updated VM."
  [^StackVM vm]
  (let [state (stack-vm->state vm)
        new-state (stack-step state)]
    (state->stack-vm vm new-state)))


(defn- stack-vm-halted?
  "Returns true if VM has halted."
  [^StackVM vm]
  (boolean (:halted vm)))


(defn- stack-vm-blocked?
  "Returns true if VM is blocked."
  [^StackVM vm]
  (= :yin/blocked (:value vm)))


(defn- stack-vm-value "Returns the current value." [^StackVM vm] (:value vm))


(defn- stack-vm-run
  "Run StackVM until halted or blocked."
  [^StackVM vm]
  (loop [v vm]
    (if (or (stack-vm-halted? v) (stack-vm-blocked? v))
      v
      (recur (stack-vm-step v)))))


(defn- stack-vm-load-program
  "Load bytecode into the VM.
   Expects {:bc [...] :pool [...]}."
  [^StackVM vm {:keys [bc pool]}]
  (map->StackVM (merge (stack-vm->state vm)
                       {:pc 0,
                        :bytecode (vec bc),
                        :stack [],
                        :call-stack [],
                        :pool pool,
                        :halted false,
                        :value nil})))


(defn- stack-vm-transact!
  "Transact datoms into the VM's DataScript db."
  [^StackVM vm datoms]
  (let [tx-data (vm/datoms->tx-data datoms)
        conn (d/conn-from-db (:db vm))
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:vm (assoc vm :db @conn), :tempids tempids}))


(defn- stack-vm-q
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


(defn create-vm
  "Create a new StackVM with optional environment."
  ([] (create-vm {}))
  ([env]
   (map->StackVM (merge (vm/empty-state)
                        {:pc 0,
                         :bytecode [],
                         :stack [],
                         :env env,
                         :call-stack [],
                         :pool [],
                         :halted false,
                         :value nil}))))
