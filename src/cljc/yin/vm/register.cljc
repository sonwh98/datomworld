(ns yin.vm.register
  "Register-based assembly VM for the Yin language.

   A register machine implementation of the CESK model where:
   - Virtual registers (r0, r1, r2...) hold intermediate values
   - Registers are a vector that grows as needed (no fixed limit)
   - Each call frame has its own register space (saved/restored via :k)
   - Continuation :k is always first-class and explicit
   - No implicit call stack - continuation IS the stack

   Assembly is symbolic (keyword mnemonics). Bytecode is numeric opcodes.

   Assembly instructions:
     [:loadk rd v]           - rd := literal v
     [:loadv rd name]        - rd := lookup name in env/store
     [:move rd rs]           - rd := rs
     [:closure rd params addr] - rd := closure capturing env
     [:call rd rf args]      - call fn in rf with args, result to rd
     [:return rs]            - return value in rs
     [:branch rt then else]  - conditional jump
     [:jump addr]            - unconditional jump"
  (:require [datascript.core :as d]
            [yin.module :as module]
            [yin.vm :as vm]
            [yin.vm.protocols :as proto]))


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


(def reverse-opcode-table (into {} (map (fn [[k v]] [v k]) opcode-table)))


;; =============================================================================
;; RegisterVM Record
;; =============================================================================

(defrecord RegisterVM
  [regs       ; virtual registers vector
   k          ; continuation (call frame stack)
   env        ; lexical environment
   ip         ; instruction pointer
   bytecode   ; symbolic instruction vector (for
   ;; assembly VM)
   bc         ; numeric bytecode vector (for bytecode
   ;; VM)
   pool       ; constant pool (for bytecode VM)
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
    ;; Compile entity to bytecode, returns the register holding the result
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


(comment
  ;; ast-datoms->asm exploration. Pipeline: AST map -> datoms -> register
  ;; assembly. Simple literal
  (ast-datoms->asm (ast->datoms {:type :literal, :value 42}))
  ;; => [[:loadk 0 42]]
  ;; Variable reference
  (ast-datoms->asm (ast->datoms {:type :variable, :name 'x}))
  ;; => [[:loadv 0 x]]
  ;; Application (+ 1 2)
  (ast-datoms->asm (ast->datoms {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :literal, :value 1}
                                            {:type :literal, :value 2}]}))
  ;; => [[:loadk 0 1]        ; r0 = 1
  ;;     [:loadk 1 2]        ; r1 = 2
  ;;     [:loadv 2 +]        ; r2 = +
  ;;     [:call 3 2 [0 1]]]  ; r3 = r2(r0, r1)
  ;; Lambda (fn [x] x)
  (ast-datoms->asm (ast->datoms {:type :lambda,
                                 :params ['x],
                                 :body {:type :variable, :name 'x}}))
  ;; => [[:closure 0 [x] 2]  ; r0 = closure, body at addr 2
  ;;     [:jump 4]           ; skip body
  ;;     [:loadv 0 x]        ; body: r0 = x
  ;;     [:return 0]]        ; return r0
  ;; Conditional (if true 1 2)
  (ast-datoms->asm (ast->datoms {:type :if,
                                 :test {:type :literal, :value true},
                                 :consequent {:type :literal, :value 1},
                                 :alternate {:type :literal, :value 2}}))
  ;; => [[:loadk 0 true]     ; r0 = test
  ;;     [:branch 0 2 5]     ; if r0 goto 2 else 5
  ;;     [:loadk 2 1]        ; r2 = 1 (consequent)
  ;;     [:move 1 2]         ; r1 = r2 (result)
  ;;     [:jump 7]           ; skip alternate
  ;;     [:loadk 3 2]        ; r3 = 2 (alternate)
  ;;     [:move 1 3]]        ; r1 = r3 (result)
  ;; Full pipeline: AST -> datoms -> register assembly -> execute
  (let [ast {:type :application,
             :operator {:type :variable, :name '+},
             :operands [{:type :literal, :value 1} {:type :literal, :value 2}]}
        bytecode (ast-datoms->asm (ast->datoms ast))
        state (make-state bytecode {'+ +})
        result (run state)]
    (:value result))
  ;; => 3
)


(defn register-assembly->bytecode
  "Convert register assembly (keyword mnemonics) to numeric bytecode.

   Returns {:bc [int...] :pool [value...]}

   The bytecode is a flat vector of integers. The pool holds all
   non-register operands (literals, symbols, param vectors).

   Address fixup: assembly addresses index into the instruction vector
   (instruction 0, 1, 2...). Bytecode addresses index into the flat
   int vector (byte offset 0, 3, 6...). All jump targets are rewritten."
  [asm-instructions]
  (let [;; Phase 1 + 2 combined: build pool while emitting bytecode
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
    ;; Phase 2: Emit bytecode
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
    ;; Phase 3: Fix addresses
    (let [offsets @instr-offsets
          fixed (reduce (fn [bc [pos asm-addr]]
                          (assoc bc pos (get offsets asm-addr asm-addr)))
                  @bytecode
                  @fixups)
          source-map (into {} (map (fn [[k v]] [v k]) offsets))]
      {:bc fixed, :pool @pool, :source-map source-map})))


(defn make-state
  "Create initial register-based assembly VM state."
  ([bytecode] (make-state bytecode {}))
  ([bytecode env]
   (merge (vm/empty-state)
          {:regs [],          ; virtual registers (grows as needed)
           :k nil,            ; continuation (always explicit)
           :env env,          ; lexical environment
           :ip 0,             ; instruction pointer
           :bytecode bytecode ; instruction vector
          })))


(defn get-reg
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


(defn step
  "Execute one assembly instruction. Returns updated state."
  [state]
  (let [{:keys [bytecode ip regs env store k]} state
        instr (get bytecode ip)]
    (if (nil? instr)
      ;; End of assembly without :return - should not happen with compiled
      ;; assembly
      (throw (ex-info "Assembly ended without :return instruction" {:ip ip}))
      (let [[op & args] instr]
        (case op
          ;; Load constant into register
          :loadk (let [[rd v] args]
                   (-> state
                       (set-reg rd v)
                       (update :ip inc)))
          ;; Load variable from environment/store
          :loadv (let [[rd name] args
                       v (or (get env name)
                             (get store name)
                             (get (:primitives state) name)
                             (module/resolve-symbol name))]
                   (-> state
                       (set-reg rd v)
                       (update :ip inc)))
          ;; Move register to register
          :move (let [[rd rs] args]
                  (-> state
                      (set-reg rd (get-reg state rs))
                      (update :ip inc)))
          ;; Create closure
          :closure (let [[rd params body-addr] args
                         closure {:type :rbc-closure,
                                  :params params,
                                  :body-addr body-addr,
                                  :env env,
                                  :bytecode bytecode}]
                     (-> state
                         (set-reg rd closure)
                         (update :ip inc)))
          ;; Function call
          :call (let [[rd rf arg-regs] args
                      fn-val (get-reg state rf)
                      fn-args (mapv #(get-reg state %) arg-regs)]
                  (cond
                    ;; Primitive function
                    (fn? fn-val)
                      (let [result (apply fn-val fn-args)]
                        (if (module/effect? result)
                          ;; Handle effect
                          (case (:effect result)
                            :vm/store-put (-> state
                                              (assoc-in [:store (:key result)]
                                                        (:val result))
                                              (set-reg rd (:val result))
                                              (update :ip inc))
                            ;; Other effects...
                            (throw (ex-info "Unhandled effect in step"
                                            {:effect result})))
                          ;; Regular value
                          (-> state
                              (set-reg rd result)
                              (update :ip inc))))
                    ;; User-defined closure
                    (= :rbc-closure (:type fn-val))
                      (let [{:keys [params body-addr env bytecode]} fn-val
                            new-frame {:type :call-frame,
                                       :return-reg rd,
                                       :return-ip (inc ip),
                                       :saved-regs regs,
                                       :saved-env (:env state),
                                       :parent k}
                            new-env (merge env (zipmap params fn-args))]
                        (-> state
                            (assoc :regs []) ; fresh register frame (grows
                            ;; as needed)
                            (assoc :k new-frame)
                            (assoc :env new-env)
                            (assoc :ip body-addr)))
                    :else (throw (ex-info "Cannot call non-function"
                                          {:fn fn-val}))))
          ;; Return from function
          :return (let [[rs] args
                        result (get-reg state rs)]
                    (if (nil? k)
                      ;; Top level - halt with result
                      (assoc state
                        :halted true
                        :value result)
                      ;; Restore caller context
                      (let [{:keys [return-reg return-ip saved-regs saved-env
                                    parent]}
                              k]
                        (-> state
                            (assoc :regs (assoc saved-regs return-reg result))
                            (assoc :k parent)
                            (assoc :env saved-env)
                            (assoc :ip return-ip)))))
          ;; Conditional branch
          :branch (let [[rt then-addr else-addr] args
                        test-val (get-reg state rt)]
                    (assoc state :ip (if test-val then-addr else-addr)))
          ;; Unconditional jump
          :jump (let [[addr] args] (assoc state :ip addr))
          ;; Unknown instruction
          (throw (ex-info "Unknown assembly instruction"
                          {:op op, :instr instr})))))))


;; =============================================================================
;; Register Bytecode VM (numeric opcodes, flat int vector, constant pool)
;; =============================================================================
;;
;; Bytecode is a flat vector of integers produced by
;; register-assembly->bytecode.
;; Opcodes are integers (see opcode-table). Non-integer operands (literals,
;; symbols, param vectors) live in a constant pool referenced by index.
;;
;; State uses :bc and :pool instead of :bytecode.
;; IP indexes into the flat int vector, not an instruction vector.
;; =============================================================================

(defn make-bc-state
  "Create initial bytecode VM state from {:bc [...] :pool [...]}."
  ([compiled] (make-bc-state compiled {}))
  ([{:keys [bc pool]} env]
   (merge (vm/empty-state)
          {:regs [], :k nil, :env env, :ip 0, :bc bc, :pool pool})))


(defn step-bc
  "Execute one bytecode instruction. Returns updated state."
  [state]
  (let [{:keys [bc pool ip regs env store k]} state
        op (get bc ip)]
    (if (nil? op)
      (throw (ex-info "Bytecode ended without return instruction" {:ip ip}))
      (case (int op)
        ;; loadk: rd = pool[const-idx]
        0 (let [rd (get bc (+ ip 1))
                v (get pool (get bc (+ ip 2)))]
            (-> state
                (set-reg rd v)
                (assoc :ip (+ ip 3))))
        ;; loadv: rd = env/store lookup of pool[const-idx]
        1 (let [rd (get bc (+ ip 1))
                name (get pool (get bc (+ ip 2)))
                v (or (get env name)
                      (get store name)
                      (get (:primitives state) name)
                      (module/resolve-symbol name))]
            (-> state
                (set-reg rd v)
                (assoc :ip (+ ip 3))))
        ;; move: rd = rs
        2 (let [rd (get bc (+ ip 1))
                rs (get bc (+ ip 2))]
            (-> state
                (set-reg rd (get-reg state rs))
                (assoc :ip (+ ip 3))))
        ;; closure: rd = closure{params=pool[idx], body-addr, env}
        3 (let [rd (get bc (+ ip 1))
                params (get pool (get bc (+ ip 2)))
                body-addr (get bc (+ ip 3))
                closure {:type :rbc-closure,
                         :params params,
                         :body-addr body-addr,
                         :env env,
                         :bc bc,
                         :pool pool}]
            (-> state
                (set-reg rd closure)
                (assoc :ip (+ ip 4))))
        ;; call: rd = fn(args...)
        4 (let [rd (get bc (+ ip 1))
                rf (get bc (+ ip 2))
                argc (get bc (+ ip 3))
                fn-args (loop [i 0
                               args (transient [])]
                          (if (< i argc)
                            (recur (inc i)
                                   (conj! args
                                          (get-reg state (get bc (+ ip 4 i)))))
                            (persistent! args)))
                fn-val (get-reg state rf)
                next-ip (+ ip 4 argc)]
            (cond (fn? fn-val)
                    (let [result (apply fn-val fn-args)]
                      (if (module/effect? result)
                        (case (:effect result)
                          :vm/store-put (-> state
                                            (assoc-in [:store (:key result)]
                                                      (:val result))
                                            (set-reg rd (:val result))
                                            (assoc :ip next-ip))
                          (throw (ex-info "Unhandled effect in step-bc"
                                          {:effect result})))
                        (-> state
                            (set-reg rd result)
                            (assoc :ip next-ip))))
                  (= :rbc-closure (:type fn-val))
                    (let [{:keys [params body-addr env bc pool]} fn-val
                          new-frame {:type :call-frame,
                                     :return-reg rd,
                                     :return-ip next-ip,
                                     :saved-regs regs,
                                     :saved-env (:env state),
                                     :saved-bc (:bc state),
                                     :saved-pool (:pool state),
                                     :parent k}
                          new-env (merge env (zipmap params fn-args))]
                      (-> state
                          (assoc :regs [])
                          (assoc :k new-frame)
                          (assoc :env new-env)
                          (assoc :bc bc)
                          (assoc :pool pool)
                          (assoc :ip body-addr)))
                  :else (throw (ex-info "Cannot call non-function"
                                        {:fn fn-val}))))
        ;; return
        5 (let [rs (get bc (+ ip 1))
                result (get-reg state rs)]
            (if (nil? k)
              (assoc state
                :halted true
                :value result)
              (let [{:keys [return-reg return-ip saved-regs saved-env saved-bc
                            saved-pool parent]}
                      k]
                (-> state
                    (assoc :regs (assoc saved-regs return-reg result))
                    (assoc :k parent)
                    (assoc :env saved-env)
                    (assoc :bc (or saved-bc (:bc state)))
                    (assoc :pool (or saved-pool (:pool state)))
                    (assoc :ip return-ip)))))
        ;; branch
        6 (let [rt (get bc (+ ip 1))
                then-addr (get bc (+ ip 2))
                else-addr (get bc (+ ip 3))
                test-val (get-reg state rt)]
            (assoc state :ip (if test-val then-addr else-addr)))
        ;; jump
        7 (let [addr (get bc (+ ip 1))] (assoc state :ip addr))
        ;; Unknown opcode
        (throw (ex-info "Unknown bytecode opcode" {:op op, :ip ip}))))))


(defn run-bc
  "Run bytecode VM to completion or until blocked."
  [state]
  (loop [s state]
    (if (or (:halted s) (= :yin/blocked (:value s))) s (recur (step-bc s)))))


(comment
  ;; Bytecode VM exploration. Assembly -> bytecode conversion
  (register-assembly->bytecode [[:loadk 0 42] [:return 0]])
  ;; => {:bc [0 0 0, 5 0], :pool [42]}
  ;; Full pipeline: AST -> datoms -> assembly -> bytecode -> execute
  (let [ast {:type :application,
             :operator {:type :variable, :name '+},
             :operands [{:type :literal, :value 1} {:type :literal, :value 2}]}
        asm (ast-datoms->asm (ast->datoms ast))
        compiled (register-assembly->bytecode asm)
        state (make-bc-state compiled {'+ +})
        result (run-bc state)]
    (:value result))
  ;; => 3
)


;; =============================================================================
;; RegisterVM Protocol Implementation
;; =============================================================================

(defn- vm->state
  "Convert RegisterVM to legacy state map."
  [^RegisterVM vm]
  (let [base {:regs (:regs vm),
              :k (:k vm),
              :env (:env vm),
              :ip (:ip vm),
              :halted (:halted vm),
              :value (:value vm),
              :store (:store vm),
              :db (:db vm),
              :parked (:parked vm),
              :id-counter (:id-counter vm),
              :primitives (:primitives vm)}]
    (if (:bc vm)
      (assoc base
        :bc (:bc vm)
        :pool (:pool vm))
      (assoc base :bytecode (:bytecode vm)))))


(defn- state->vm
  "Convert legacy state map back to RegisterVM."
  [^RegisterVM vm state]
  (->RegisterVM (:regs state)
                (:k state)
                (:env state)
                (:ip state)
                (:bytecode state)
                (:bc state)
                (:pool state)
                (:halted state)
                (:value state)
                (:store state)
                (:db state)
                (:parked state)
                (:id-counter state)
                (:primitives state)))


(defn reg-vm-step
  "Execute one step of RegisterVM. Returns updated VM."
  [^RegisterVM vm]
  (let [state (vm->state vm)
        new-state (if (:bc vm) (step-bc state) (step state))]
    (state->vm vm new-state)))


(defn reg-vm-halted?
  "Returns true if VM has halted."
  [^RegisterVM vm]
  (boolean (:halted vm)))


(defn reg-vm-blocked?
  "Returns true if VM is blocked."
  [^RegisterVM vm]
  (= :yin/blocked (:value vm)))


(defn reg-vm-value "Returns the current value." [^RegisterVM vm] (:value vm))


(defn reg-vm-run
  "Run RegisterVM until halted or blocked."
  [^RegisterVM vm]
  (loop [v vm]
    (if (or (reg-vm-halted? v) (reg-vm-blocked? v)) v (recur (reg-vm-step v)))))


(defn reg-vm-reset
  "Reset RegisterVM execution state to initial baseline, preserving loaded program."
  [^RegisterVM vm]
  (assoc vm
    :regs []
    :k nil
    :ip 0
    :halted false
    :value nil))


(defn reg-vm-load-program
  "Load bytecode into the VM.
   Accepts either symbolic bytecode vector or {:bc [...] :pool [...]}."
  [^RegisterVM vm program]
  (if (map? program)
    ;; Numeric bytecode with pool
    (map->RegisterVM (merge (vm->state vm)
                            {:regs [],
                             :k nil,
                             :ip 0,
                             :bytecode nil,
                             :bc (:bc program),
                             :pool (:pool program),
                             :halted false,
                             :value nil}))
    ;; Symbolic bytecode
    (map->RegisterVM (merge (vm->state vm)
                            {:regs [],
                             :k nil,
                             :ip 0,
                             :bytecode program,
                             :bc nil,
                             :pool nil,
                             :halted false,
                             :value nil}))))


(defn reg-vm-transact!
  "Transact datoms into the VM's DataScript db."
  [^RegisterVM vm datoms]
  (let [tx-data (vm/datoms->tx-data datoms)
        conn (d/conn-from-db (:db vm))
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:vm (assoc vm :db @conn), :tempids tempids}))


(defn reg-vm-q
  "Run a Datalog query against the VM's db."
  [^RegisterVM vm args]
  (apply d/q (first args) (:db vm) (rest args)))


(extend-type RegisterVM
  proto/IVMStep
    (step [vm] (reg-vm-step vm))
    (halted? [vm] (reg-vm-halted? vm))
    (blocked? [vm] (reg-vm-blocked? vm))
    (value [vm] (reg-vm-value vm))
  proto/IVMRun
    (run [vm] (reg-vm-run vm))
  proto/IVMReset
    (reset [vm] (reg-vm-reset vm))
  proto/IVMLoad
    (load-program [vm program] (reg-vm-load-program vm program))
  proto/IVMDataScript
    (transact! [vm datoms] (reg-vm-transact! vm datoms))
    (q [vm args] (reg-vm-q vm args)))


(defn create
  "Create a new RegisterVM with optional environment."
  ([] (create {}))
  ([env]
   (map->RegisterVM (merge (vm/empty-state)
                           {:regs [],
                            :k nil,
                            :env env,
                            :ip 0,
                            :bytecode nil,
                            :bc nil,
                            :pool nil,
                            :halted false,
                            :value nil}))))
