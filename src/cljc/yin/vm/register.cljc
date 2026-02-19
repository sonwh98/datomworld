(ns yin.vm.register
  "Register-based bytecode VM for the Yin language.

   A register machine implementation of the CESK model where:
   - Virtual registers (r0, r1, r2...) hold intermediate values
   - Registers are a vector that grows as needed (no fixed limit)
   - Each call frame has its own register space (saved/restored via :k)
   - Continuation :k is always first-class and explicit
   - No implicit call stack - continuation IS the stack

   Executes numeric bytecode produced by asm->bytecode.
   The compilation pipeline is: AST datoms -> ast-datoms->asm (symbolic IR) -> asm->bytecode (numeric).
   See ast-datoms->asm docstring for the full instruction set."
  (:require
    [yin.module :as module]
    [yin.vm :as vm])
  #?(:cljs
     (:require-macros
       [yin.vm.register :refer [opcase]])))


(def opcode-table
  {:loadk 0,
   :loadv 1,
   :move 2,
   :closure 3,
   :call 4,
   :return 5,
   :branch 6,
   :jump 7,
   :gensym 8,
   :sget 9,
   :sput 10,
   :stream-make 11,
   :stream-put 12,
   :stream-take 13})


#?(:clj (defmacro opcase
          [op-expr & clauses]
          (let [default (when (odd? (count clauses)) (last clauses))
                paired (if default (butlast clauses) clauses)
                pairs (partition 2 paired)
                resolved (mapcat (fn [[kw body]] [(get opcode-table kw) body])
                                 pairs)]
            `(case (int ~op-expr) ~@resolved ~@(when default [default])))))


;; =============================================================================
;; RegisterVM Record
;; =============================================================================

(defrecord RegisterVM
  [regs       ; virtual registers vector
   k          ; continuation (call frame stack)
   env        ; lexical environment
   ip         ; instruction pointer
   bytecode   ; numeric bytecode vector
   pool       ; constant pool
   halted     ; true if execution completed
   value      ; final result value
   store      ; heap memory
   parked     ; parked continuations
   id-counter ; unique ID counter
   primitives ; primitive operations
   blocked    ; true if blocked
   ])


(defn ast-datoms->asm
  "Takes the AST as datoms and transforms it to register-based assembly.

   Assembly is a vector of symbolic instructions (keyword mnemonics, not numeric
   opcodes). A separate asm->bytecode pass would encode these as numbers.

   Instructions:
     [:loadk rd v]                    - rd := literal value v
     [:loadv rd name]                 - rd := lookup name in env/store
     [:move rd rs]                    - rd := rs
     [:closure rd params addr nregs]  - rd := closure, body at addr, needs nregs
     [:call rd rf args]               - call fn in rf with args (reg vector), result to rd
     [:return rs]                     - return value in rs
     [:branch rt then else]           - if rt then goto 'then' else goto 'else'
     [:jump addr]                     - unconditional jump
     [:gensym rd prefix]              - rd := generate unique ID
     [:sget rd key]                   - rd := store[key]
     [:sput rs key]                   - store[key] := rs
     [:stream-make rd buf]            - rd := new stream
     [:stream-put rs rt]              - put rs into stream rt
     [:stream-take rd rs]             - rd := take from stream rs

   Uses simple linear register allocation."
  [ast-as-datoms]
  (let [;; Materialize and index datoms by entity
        datoms (vec ast-as-datoms)
        by-entity (group-by first datoms)
        ;; Get attribute value for entity
        get-attr (fn [e attr]
                   (some (fn [[_ a v _ _]] (when (= a attr) v))
                         (get by-entity e)))
        ;; Find root entity (max of negative tempids = -1025)
        root-id (apply max (keys by-entity))
        ;; Assembly accumulator
        bytecode (atom [])
        emit! (fn [instr] (swap! bytecode conj instr))
        current-addr #(count @bytecode)
        ;; Register allocator (simple linear allocation, unbounded)
        reg-counter (atom 0)
        alloc-reg! (fn []
                     (let [r @reg-counter]
                       (swap! reg-counter inc)
                       r))
        reset-regs! (fn [] (reset! reg-counter 0))]
    ;; Compile entity to assembly, returns the register holding the result
    (letfn
      [(compile-node
         [e]
         (let [node-type (get-attr e :yin/type)]
           (case node-type
             :literal (let [rd (alloc-reg!)]
                        (emit! [:loadk rd (get-attr e :yin/value)])
                        rd)
             :variable (let [rd (alloc-reg!)]
                         (emit! [:loadv rd (get-attr e :yin/name)])
                         rd)
             :lambda
             (let [params (get-attr e :yin/params)
                   body-ref (get-attr e :yin/body)
                   rd (alloc-reg!)
                   closure-idx (current-addr)]
               ;; Emit closure with placeholder for address and reg-count
               (emit! [:closure rd params :placeholder :placeholder])
               ;; Jump over body
               (let [jump-idx (current-addr)]
                 (emit! [:jump :placeholder])
                 ;; Body starts here - fresh register scope
                 (let [body-addr (current-addr)
                       saved-reg-counter @reg-counter]
                   (reset-regs!)
                   (let [result-reg (compile-node body-ref)
                         max-regs @reg-counter]
                     (emit! [:return result-reg])
                     ;; Restore register counter
                     (reset! reg-counter saved-reg-counter)
                     ;; Patch addresses and register count
                     (let [after-body (current-addr)]
                       (swap! bytecode assoc-in [closure-idx 3] body-addr)
                       (swap! bytecode assoc-in [closure-idx 4] max-regs)
                       (swap! bytecode assoc-in [jump-idx 1] after-body)))))
               rd)
             :application (let [op-ref (get-attr e :yin/operator)
                                operand-refs (get-attr e :yin/operands)
                                ;; Compile operands first
                                arg-regs (mapv compile-node operand-refs)
                                ;; Compile operator
                                fn-reg (compile-node op-ref)
                                ;; Result register
                                rd (alloc-reg!)]
                            (emit! [:call rd fn-reg arg-regs])
                            rd)
             :if (let [test-ref (get-attr e :yin/test)
                       cons-ref (get-attr e :yin/consequent)
                       alt-ref (get-attr e :yin/alternate)
                       ;; Compile test
                       test-reg (compile-node test-ref)
                       ;; Result register (shared by both branches)
                       rd (alloc-reg!)
                       branch-idx (current-addr)]
                   ;; Emit branch with placeholders
                   (emit! [:branch test-reg :then :else])
                   ;; Consequent
                   (let [then-addr (current-addr)
                         cons-reg (compile-node cons-ref)]
                     (emit! [:move rd cons-reg])
                     (let [jump-idx (current-addr)]
                       (emit! [:jump :end])
                       ;; Alternate
                       (let [else-addr (current-addr)
                             alt-reg (compile-node alt-ref)]
                         (emit! [:move rd alt-reg])
                         ;; Patch addresses
                         (let [end-addr (current-addr)]
                           (swap! bytecode assoc-in [branch-idx 2] then-addr)
                           (swap! bytecode assoc-in [branch-idx 3] else-addr)
                           (swap! bytecode assoc-in [jump-idx 1] end-addr)))))
                   rd)
             ;; VM primitives
             :vm/gensym (let [rd (alloc-reg!)]
                          (emit! [:gensym rd (get-attr e :yin/prefix)])
                          rd)
             :vm/store-get (let [rd (alloc-reg!)]
                             (emit! [:sget rd (get-attr e :yin/key)])
                             rd)
             :vm/store-put (let [rd (alloc-reg!)]
                             (emit! [:loadk rd (get-attr e :yin/val)])
                             (emit! [:sput rd (get-attr e :yin/key)])
                             rd)
             ;; Stream operations
             :stream/make (let [rd (alloc-reg!)]
                            (emit! [:stream-make rd (get-attr e :yin/buffer)])
                            rd)
             :stream/put (let [target-ref (get-attr e :yin/target)
                               val-ref (get-attr e :yin/val)
                               val-reg (compile-node val-ref)
                               target-reg (compile-node target-ref)]
                           (emit! [:stream-put val-reg target-reg])
                           val-reg)
             :stream/take (let [source-ref (get-attr e :yin/source)
                                source-reg (compile-node source-ref)
                                rd (alloc-reg!)]
                            (emit! [:stream-take rd source-reg])
                            rd)
             ;; Unknown type
             (throw (ex-info
                      "Unknown node type in register assembly compilation"
                      {:type node-type, :entity e})))))]
      (let [result-reg (compile-node root-id)
            max-regs @reg-counter]
        (emit! [:return result-reg])
        {:asm @bytecode, :reg-count max-regs}))))


(defn asm->bytecode
  "Convert register assembly (keyword mnemonics) to numeric bytecode.

   Returns {:bytecode [int...] :pool [value...] :source-map {byte-offset instr-index}}

   The bytecode is a flat vector of integers. The pool holds all
   non-register operands (literals, symbols, param vectors).

   Address fixup: assembly addresses index into the instruction vector
   (instruction 0, 1, 2...). Bytecode addresses index into the flat
   int vector (byte offset 0, 3, 6...). All jump targets are rewritten."
  [asm-instructions]
  (let [;; Build pool while emitting bytecode
        pool (atom [])
        pool-index (atom {})
        intern! (fn [v]
                  (if-let [idx (get @pool-index v)]
                    idx
                    (let [idx (count @pool)]
                      (swap! pool conj v)
                      (swap! pool-index assoc v idx)
                      idx)))
        bytecode (atom [])
        ;; instruction index -> byte offset
        instr-offsets (atom {})
        ;; [byte-position assembly-address] pairs needing fixup
        fixups (atom [])
        emit! (fn [& ints] (swap! bytecode into ints))
        current-offset #(count @bytecode)
        emit-fixup! (fn [asm-addr]
                      (swap! fixups conj [(current-offset) asm-addr])
                      (swap! bytecode conj asm-addr))]
    ;; Emit bytecode
    (doseq [[idx instr] (map-indexed vector asm-instructions)]
      (swap! instr-offsets assoc idx (current-offset))
      (let [[op & args] instr]
        (case op
          :loadk (let [[rd v] args]
                   (emit! (opcode-table :loadk) rd (intern! v)))
          :loadv (let [[rd name] args]
                   (emit! (opcode-table :loadv) rd (intern! name)))
          :move (let [[rd rs] args] (emit! (opcode-table :move) rd rs))
          :closure
          (let [[rd params addr reg-count] args]
            (emit! (opcode-table :closure) rd (intern! params) reg-count)
            (emit-fixup! addr))
          :call (let [[rd rf arg-regs] args]
                  (emit! (opcode-table :call) rd rf (count arg-regs))
                  (doseq [ar arg-regs] (emit! ar)))
          :return (let [[rs] args] (emit! (opcode-table :return) rs))
          :branch (let [[rt then-addr else-addr] args]
                    (emit! (opcode-table :branch) rt)
                    (emit-fixup! then-addr)
                    (emit-fixup! else-addr))
          :jump (let [[addr] args]
                  (emit! (opcode-table :jump))
                  (emit-fixup! addr))
          :gensym (let [[rd prefix] args]
                    (emit! (opcode-table :gensym) rd (intern! prefix)))
          :sget (let [[rd key] args]
                  (emit! (opcode-table :sget) rd (intern! key)))
          :sput (let [[rs key] args]
                  (emit! (opcode-table :sput) rs (intern! key)))
          :stream-make (let [[rd buf] args]
                         (emit! (opcode-table :stream-make) rd buf))
          :stream-put (let [[rs rt] args]
                        (emit! (opcode-table :stream-put) rs rt))
          :stream-take (let [[rd rs] args]
                         (emit! (opcode-table :stream-take) rd rs)))))
    ;; Fix addresses
    (let [offsets @instr-offsets
          fixed (reduce (fn [bc [pos asm-addr]]
                          (assoc bc pos (get offsets asm-addr asm-addr)))
                        @bytecode
                        @fixups)
          source-map (into {} (map (fn [[k v]] [v k]) offsets))]
      {:bytecode fixed, :pool @pool, :source-map source-map})))


(defn- get-reg
  "Get value from register."
  [state r]
  (nth (:regs state) r))


(defn- set-reg
  "Set register to value."
  [state r v]
  (assoc state :regs (assoc (:regs state) r v)))


;; =============================================================================
;; Register Bytecode VM (numeric opcodes, flat int vector, constant pool)
;; =============================================================================
;;
;; Bytecode is a flat vector of integers produced by
;; asm->bytecode.
;; Opcodes are integers (see opcode-table). Non-integer operands (literals,
;; symbols, param vectors) live in a constant pool referenced by index.
;;
;; State uses :bytecode and :pool instead of :assembly.
;; IP indexes into the flat int vector, not an instruction vector.
;; =============================================================================

(defn- reg-vm-step
  "Execute one bytecode instruction. Returns updated state."
  [state]
  (let [{:keys [bytecode pool ip regs env store k primitives]} state
        op (get bytecode ip)]
    (if (nil? op)
      (throw (ex-info "Bytecode ended without return instruction" {:ip ip}))
      (opcase
        op
        :loadk
        (let [rd (get bytecode (+ ip 1))
              v (get pool (get bytecode (+ ip 2)))]
          (assoc state
                 :regs (assoc regs rd v)
                 :ip (+ ip 3)))
        :loadv
        (let [rd (get bytecode (+ ip 1))
              name (get pool (get bytecode (+ ip 2)))
              v (if-let [pair (find env name)]
                  (val pair)
                  (if-let [pair (find store name)]
                    (val pair)
                    (or (get primitives name) (module/resolve-symbol name))))]
          (assoc state
                 :regs (assoc regs rd v)
                 :ip (+ ip 3)))
        :move
        (let [rd (get bytecode (+ ip 1))
              rs (get bytecode (+ ip 2))]
          (assoc state
                 :regs (assoc regs rd (get-reg state rs))
                 :ip (+ ip 3)))
        :closure
        (let [rd (get bytecode (+ ip 1))
              params (get pool (get bytecode (+ ip 2)))
              reg-count (get bytecode (+ ip 3))
              body-addr (get bytecode (+ ip 4))
              closure {:type :closure,
                       :params params,
                       :body-addr body-addr,
                       :reg-count reg-count,
                       :env env,
                       :bytecode bytecode,
                       :pool pool}]
          (assoc state
                 :regs (assoc regs rd closure)
                 :ip (+ ip 5)))
        :call
        (let [rd (get bytecode (+ ip 1))
              rf (get bytecode (+ ip 2))
              argc (get bytecode (+ ip 3))
              fn-args (loop [i 0
                             args (transient [])]
                        (if (< i argc)
                          (recur (inc i)
                                 (conj! args
                                        (get-reg state
                                                 (get bytecode (+ ip 4 i)))))
                          (persistent! args)))
              fn-val (get-reg state rf)
              next-ip (+ ip 4 argc)]
          (cond (fn? fn-val)
                (let [result (apply fn-val fn-args)]
                  (if (module/effect? result)
                    (case (:effect result)
                      :vm/store-put (assoc state
                                           :store (assoc store
                                                         (:key result) (:val result))
                                           :regs (assoc regs rd (:val result))
                                           :ip next-ip)
                      (throw (ex-info "Unhandled effect in reg-vm-step"
                                      {:effect result})))
                    (assoc state
                           :regs (assoc regs rd result)
                           :ip next-ip)))
                (= :closure (:type fn-val))
                (let [{:keys [params body-addr env bytecode pool reg-count]}
                      fn-val
                      new-frame {:type :call-frame,
                                 :return-reg rd,
                                 :return-ip next-ip,
                                 :saved-regs regs,
                                 :saved-env (:env state),
                                 :saved-bytecode (:bytecode state),
                                 :saved-pool (:pool state),
                                 :parent k}
                      new-env (merge env (zipmap params fn-args))]
                  (assoc state
                         :regs (vec (repeat reg-count nil))
                         :k new-frame
                         :env new-env
                         :bytecode bytecode
                         :pool pool
                         :ip body-addr))
                :else (throw (ex-info "Cannot call non-function"
                                      {:fn fn-val}))))
        :return
        (let [rs (get bytecode (+ ip 1))
              result (get-reg state rs)]
          (if (nil? k)
            (assoc state
                   :halted true
                   :value result)
            (let [{:keys [return-reg return-ip saved-regs saved-env
                          saved-bytecode saved-pool parent]}
                  k]
              (assoc state
                     :regs (assoc saved-regs return-reg result)
                     :k parent
                     :env saved-env
                     :bytecode (or saved-bytecode (:bytecode state))
                     :pool (or saved-pool (:pool state))
                     :ip return-ip))))
        :branch
        (let [rt (get bytecode (+ ip 1))
              then-addr (get bytecode (+ ip 2))
              else-addr (get bytecode (+ ip 3))
              test-val (get-reg state rt)]
          (assoc state :ip (if test-val then-addr else-addr)))
        :jump
        (let [addr (get bytecode (+ ip 1))] (assoc state :ip addr))
        (throw (ex-info "Unknown bytecode opcode" {:op op, :ip ip}))))))


;; =============================================================================
;; RegisterVM Protocol Implementation
;; =============================================================================

(defn- reg-vm-halted?
  "Returns true if VM has halted."
  [^RegisterVM vm]
  (boolean (:halted vm)))


(defn- reg-vm-blocked?
  "Returns true if VM is blocked."
  [^RegisterVM vm]
  (boolean (:blocked vm)))


(defn- reg-vm-value
  "Returns the current value."
  [^RegisterVM vm]
  (:value vm))


(defn- reg-vm-reset
  "Reset RegisterVM execution state to initial baseline, preserving loaded program."
  [^RegisterVM vm]
  (assoc vm
         :regs (vec (repeat (count (:regs vm)) nil))
         :k nil
         :ip 0
         :halted false
         :value nil
         :blocked false))


(defn- reg-vm-load-program
  "Load bytecode into the VM.
   Accepts {:bytecode [...] :pool [...]}."
  [^RegisterVM vm program]
  (assoc vm
         :regs (vec (repeat (:reg-count program 0) nil))
         :k nil
         :ip 0
         :bytecode (:bytecode program)
         :pool (:pool program)
         :halted false
         :value nil
         :blocked false))


(defn- reg-vm-eval
  "Evaluate an AST. Owns the step loop and compilation pipeline.
   When ast is non-nil, compiles through datoms -> asm -> bytecode and loads.
   When nil, resumes from current state."
  [^RegisterVM vm ast]
  (let [v (if ast
            (let [datoms (vm/ast->datoms ast)
                  {:keys [asm reg-count]} (ast-datoms->asm datoms)
                  compiled (assoc (asm->bytecode asm) :reg-count reg-count)]
              (reg-vm-load-program vm compiled))
            vm)]
    (loop [v v] (if (or (:halted v) (:blocked v)) v (recur (reg-vm-step v))))))


(extend-type RegisterVM
  vm/IVMStep
  (step [vm] (reg-vm-step vm))
  (halted? [vm] (reg-vm-halted? vm))
  (blocked? [vm] (reg-vm-blocked? vm))
  (value [vm] (reg-vm-value vm))
  vm/IVMRun
  (run [vm] (vm/eval vm nil))
  vm/IVMReset
  (reset [vm] (reg-vm-reset vm))
  vm/IVMLoad
  (load-program [vm program] (reg-vm-load-program vm program))
  vm/IVMEval
  (eval [vm ast] (reg-vm-eval vm ast))
  vm/IVMState
  (control [vm] {:ip (:ip vm), :bytecode (:bytecode vm), :regs (:regs vm)})
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm] (:k vm)))


(defn create-vm
  "Create a new RegisterVM with optional opts map.
   Accepts {:env map, :primitives map}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})]
     (map->RegisterVM (merge (vm/empty-state (select-keys opts [:primitives]))
                             {:regs [],
                              :k nil,
                              :env env,
                              :ip 0,
                              :bytecode nil,
                              :pool nil,
                              :halted false,
                              :value nil,
                              :blocked false})))))
