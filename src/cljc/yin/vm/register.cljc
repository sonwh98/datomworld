(ns yin.vm.register
  "Register-based bytecode VM for the Yin language.

   A register machine implementation of the CESK model where:
   - Virtual registers (r0, r1, r2...) hold intermediate values
   - Registers are a vector that grows as needed (no fixed limit)
   - Each call frame has its own register space (saved/restored via :k)
   - Continuation :k is always first-class and explicit
   - No implicit call stack - continuation IS the stack

   Executes numeric bytecode produced by assembly->bytecode.
   The compilation pipeline is: AST datoms -> ast-datoms->asm (symbolic IR) -> assembly->bytecode (numeric).
   See ast-datoms->asm docstring for the full instruction set."
  (:require [datascript.core :as d]
            [yin.module :as module]
            [yin.vm :as vm])
  #?(:cljs (:require-macros [yin.vm.register :refer [opcase]])))


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

(defrecord RegisterVM [regs       ; virtual registers vector
                       k          ; continuation (call frame stack)
                       env        ; lexical environment
                       ip         ; instruction pointer
                       bytecode   ; numeric bytecode vector
                       pool       ; constant pool
                       halted     ; true if execution completed
                       value      ; final result value
                       store      ; heap memory
                       db         ; DataScript db value
                       parked     ; parked continuations
                       id-counter ; unique ID counter
                       primitives ; primitive operations
                      ])


(defn ast-datoms->asm
  "Takes the AST as datoms and transforms it to register-based assembly.

   Assembly is a vector of symbolic instructions (keyword mnemonics, not numeric
   opcodes). A separate assembly->bytecode pass would encode these as numbers.

   Instructions:
     [:loadk rd v]              - rd := literal value v
     [:loadv rd name]           - rd := lookup name in env/store
     [:move rd rs]              - rd := rs
     [:closure rd params addr]  - rd := closure, body at addr
     [:call rd rf args]         - call fn in rf with args (reg vector), result to rd
     [:return rs]               - return value in rs
     [:branch rt then else]     - if rt then goto 'then' else goto 'else'
     [:jump addr]               - unconditional jump
     [:gensym rd prefix]        - rd := generate unique ID
     [:sget rd key]             - rd := store[key]
     [:sput rs key]             - store[key] := rs
     [:stream-make rd buf]      - rd := new stream
     [:stream-put rs rt]        - put rs into stream rt
     [:stream-take rd rs]       - rd := take from stream rs

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
      [(compile-node [e]
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
                 ;; Emit closure with placeholder
                 (emit! [:closure rd params :placeholder])
                 ;; Jump over body
                 (let [jump-idx (current-addr)]
                   (emit! [:jump :placeholder])
                   ;; Body starts here - fresh register scope
                   (let [body-addr (current-addr)
                         saved-reg-counter @reg-counter]
                     (reset-regs!)
                     (let [result-reg (compile-node body-ref)]
                       (emit! [:return result-reg])
                       ;; Restore register counter
                       (reset! reg-counter saved-reg-counter)
                       ;; Patch addresses
                       (let [after-body (current-addr)]
                         (swap! bytecode assoc-in [closure-idx 3] body-addr)
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
      (let [result-reg (compile-node root-id)]
        (emit! [:return result-reg])
        @bytecode))))


(defn assembly->bytecode
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
          :closure (let [[rd params addr] args]
                     (emit! (opcode-table :closure) rd (intern! params))
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
  "Get value from register. Returns nil if register not yet allocated."
  [state r]
  (get (:regs state) r))


(defn- set-reg
  "Set register to value. Grows register vector if needed."
  [state r v]
  (let [regs (:regs state)
        regs (if (> r (dec (count regs)))
               ;; Grow vector to accommodate register
               (into regs (repeat (- (inc r) (count regs)) nil))
               regs)]
    (assoc state :regs (assoc regs r v))))


;; =============================================================================
;; Register Bytecode VM (numeric opcodes, flat int vector, constant pool)
;; =============================================================================
;;
;; Bytecode is a flat vector of integers produced by
;; assembly->bytecode.
;; Opcodes are integers (see opcode-table). Non-integer operands (literals,
;; symbols, param vectors) live in a constant pool referenced by index.
;;
;; State uses :bytecode and :pool instead of :assembly.
;; IP indexes into the flat int vector, not an instruction vector.
;; =============================================================================

(defn- step-bc
  "Execute one bytecode instruction. Returns updated state."
  [state]
  (let [{:keys [bytecode pool ip regs env store k]} state
        op (get bytecode ip)]
    (if (nil? op)
      (throw (ex-info "Bytecode ended without return instruction" {:ip ip}))
      (opcase
        op
        :loadk
        (let [rd (get bytecode (+ ip 1))
              v (get pool (get bytecode (+ ip 2)))]
          (-> state
              (set-reg rd v)
              (assoc :ip (+ ip 3))))
        :loadv
        (let [rd (get bytecode (+ ip 1))
              name (get pool (get bytecode (+ ip 2)))
              v (or (get env name)
                    (get store name)
                    (get (:primitives state) name)
                    (module/resolve-symbol name))]
          (-> state
              (set-reg rd v)
              (assoc :ip (+ ip 3))))
        :move
        (let [rd (get bytecode (+ ip 1))
              rs (get bytecode (+ ip 2))]
          (-> state
              (set-reg rd (get-reg state rs))
              (assoc :ip (+ ip 3))))
        :closure
        (let [rd (get bytecode (+ ip 1))
              params (get pool (get bytecode (+ ip 2)))
              body-addr (get bytecode (+ ip 3))
              closure {:type :closure,
                       :params params,
                       :body-addr body-addr,
                       :env env,
                       :bytecode bytecode,
                       :pool pool}]
          (-> state
              (set-reg rd closure)
              (assoc :ip (+ ip 4))))
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
          (cond (fn? fn-val) (let [result (apply fn-val fn-args)]
                               (if (module/effect? result)
                                 (case (:effect result)
                                   :vm/store-put (-> state
                                                     (assoc-in [:store
                                                                (:key result)]
                                                               (:val result))
                                                     (set-reg rd (:val result))
                                                     (assoc :ip next-ip))
                                   (throw (ex-info "Unhandled effect in step-bc"
                                                   {:effect result})))
                                 (-> state
                                     (set-reg rd result)
                                     (assoc :ip next-ip))))
                (= :closure (:type fn-val))
                  (let [{:keys [params body-addr env bytecode pool]} fn-val
                        new-frame {:type :call-frame,
                                   :return-reg rd,
                                   :return-ip next-ip,
                                   :saved-regs regs,
                                   :saved-env (:env state),
                                   :saved-bytecode (:bytecode state),
                                   :saved-pool (:pool state),
                                   :parent k}
                        new-env (merge env (zipmap params fn-args))]
                    (-> state
                        (assoc :regs [])
                        (assoc :k new-frame)
                        (assoc :env new-env)
                        (assoc :bytecode bytecode)
                        (assoc :pool pool)
                        (assoc :ip body-addr)))
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
              (-> state
                  (assoc :regs (assoc saved-regs return-reg result))
                  (assoc :k parent)
                  (assoc :env saved-env)
                  (assoc :bytecode (or saved-bytecode (:bytecode state)))
                  (assoc :pool (or saved-pool (:pool state)))
                  (assoc :ip return-ip)))))
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

(defn- vm->state
  "Convert RegisterVM to state map for step-bc."
  [^RegisterVM vm]
  {:regs (:regs vm),
   :k (:k vm),
   :env (:env vm),
   :ip (:ip vm),
   :bytecode (:bytecode vm),
   :pool (:pool vm),
   :halted (:halted vm),
   :value (:value vm),
   :store (:store vm),
   :db (:db vm),
   :parked (:parked vm),
   :id-counter (:id-counter vm),
   :primitives (:primitives vm)})


(defn- state->vm
  "Convert state map back to RegisterVM."
  [^RegisterVM vm state]
  (->RegisterVM (:regs state)
                (:k state)
                (:env state)
                (:ip state)
                (:bytecode state)
                (:pool state)
                (:halted state)
                (:value state)
                (:store state)
                (:db state)
                (:parked state)
                (:id-counter state)
                (:primitives state)))


(defn- reg-vm-step
  "Execute one step of RegisterVM. Returns updated VM."
  [^RegisterVM vm]
  (let [state (vm->state vm)
        new-state (step-bc state)]
    (state->vm vm new-state)))


(defn- reg-vm-halted?
  "Returns true if VM has halted."
  [^RegisterVM vm]
  (boolean (:halted vm)))


(defn- reg-vm-blocked?
  "Returns true if VM is blocked."
  [^RegisterVM vm]
  (= :yin/blocked (:value vm)))


(defn- reg-vm-value "Returns the current value." [^RegisterVM vm] (:value vm))


(defn- reg-vm-run
  "Run RegisterVM until halted or blocked."
  [^RegisterVM vm]
  (loop [v vm]
    (if (or (reg-vm-halted? v) (reg-vm-blocked? v)) v (recur (reg-vm-step v)))))


(defn- reg-vm-reset
  "Reset RegisterVM execution state to initial baseline, preserving loaded program."
  [^RegisterVM vm]
  (assoc vm
    :regs []
    :k nil
    :ip 0
    :halted false
    :value nil))


(defn- reg-vm-load-program
  "Load bytecode into the VM.
   Accepts {:bytecode [...] :pool [...]}."
  [^RegisterVM vm program]
  (map->RegisterVM (merge (vm->state vm)
                          {:regs [],
                           :k nil,
                           :ip 0,
                           :bytecode (:bytecode program),
                           :pool (:pool program),
                           :halted false,
                           :value nil})))


(defn- reg-vm-transact!
  "Transact datoms into the VM's DataScript db."
  [^RegisterVM vm datoms]
  (let [tx-data (vm/datoms->tx-data datoms)
        conn (d/conn-from-db (:db vm))
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:vm (assoc vm :db @conn), :tempids tempids}))


(defn- reg-vm-q
  "Run a Datalog query against the VM's db."
  [^RegisterVM vm args]
  (apply d/q (first args) (:db vm) (rest args)))


(extend-type RegisterVM
  vm/IVMStep
    (step [vm] (reg-vm-step vm))
    (halted? [vm] (reg-vm-halted? vm))
    (blocked? [vm] (reg-vm-blocked? vm))
    (value [vm] (reg-vm-value vm))
  vm/IVMRun
    (run [vm] (reg-vm-run vm))
  vm/IVMReset
    (reset [vm] (reg-vm-reset vm))
  vm/IVMLoad
    (load-program [vm program] (reg-vm-load-program vm program))
  vm/IVMState
    (control [vm] {:ip (:ip vm), :bytecode (:bytecode vm), :regs (:regs vm)})
    (environment [vm] (:env vm))
    (store [vm] (:store vm))
    (continuation [vm] (:k vm))
  vm/IVMDataScript
    (transact! [vm datoms] (reg-vm-transact! vm datoms))
    (q [vm args] (reg-vm-q vm args)))


(defn create-vm
  "Create a new RegisterVM with optional environment."
  ([] (create-vm {}))
  ([env]
   (map->RegisterVM (merge (vm/empty-state)
                           {:regs [],
                            :k nil,
                            :env env,
                            :ip 0,
                            :bytecode nil,
                            :pool nil,
                            :halted false,
                            :value nil}))))
