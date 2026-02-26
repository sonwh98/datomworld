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
  (:require [yin.module :as module]
            [yin.vm :as vm]
            [yin.vm.engine :as engine])
  #?(:cljs (:require-macros [yin.vm :refer [opcase]])))


;; =============================================================================
;; RegisterVM Record
;; =============================================================================

(defrecord RegisterVM
  [blocked    ; true if blocked
   bytecode   ; numeric bytecode vector
   env        ; lexical environment
   halted     ; true if execution completed
   id-counter ; unique ID counter
   k          ; continuation (call frame stack)
   parked     ; parked continuations
   pc         ; program counter
   pool       ; constant pool
   primitives ; primitive operations
   regs       ; virtual registers vector
   run-queue  ; vector of runnable continuations
   store      ; heap memory
   value      ; final result value
   wait-set   ; vector of parked continuations waiting on streams
  ])


(defn ast-datoms->asm
  "Takes the AST as datoms and transforms it to register-based assembly.

   Assembly is a vector of symbolic instructions (keyword mnemonics, not numeric
   opcodes). A separate asm->bytecode pass would encode these as numbers.

   Instructions:
     [:literal rd v]                  - rd := literal value v
     [:load-var rd name]              - rd := lookup name in env/store
     [:move rd rs]                    - rd := rs
     [:lambda rd params addr nregs]   - rd := closure, body at addr, needs nregs
     [:call rd rf args]               - call fn in rf with args (reg vector), result to rd
     [:return rs]                     - return value in rs
     [:branch rt then else]           - if rt then goto 'then' else goto 'else'
     [:jump addr]                     - unconditional jump
     [:gensym rd prefix]              - rd := generate unique ID
     [:store-get rd key]              - rd := store[key]
     [:store-put rs key]              - store[key] := rs
     [:stream-make rd buf]            - rd := new stream
     [:stream-put rs rt]              - put rs into stream rt
     [:stream-cursor rd rs]           - rd := cursor for stream rs
     [:stream-next rd rs]             - rd := next value from cursor rs
     [:stream-close rd rs]            - close stream rs, rd := nil

   Uses simple linear register allocation."
  [ast-as-datoms]
  (let [{:keys [get-attr root-id]} (engine/index-datoms ast-as-datoms)
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
         ([e] (compile-node e false))
         ([e tail?]
          (let [node-type (get-attr e :yin/type)]
            (case node-type
              :literal (let [rd (alloc-reg!)]
                         (emit! [:literal rd (get-attr e :yin/value)])
                         rd)
              :variable (let [rd (alloc-reg!)]
                          (emit! [:load-var rd (get-attr e :yin/name)])
                          rd)
              :lambda
                (let [params (get-attr e :yin/params)
                      body-ref (get-attr e :yin/body)
                      rd (alloc-reg!)
                      closure-idx (current-addr)]
                  ;; Emit closure with placeholder for address and
                  ;; reg-count
                  (emit! [:lambda rd params :placeholder :placeholder])
                  ;; Jump over body
                  (let [jump-idx (current-addr)]
                    (emit! [:jump :placeholder])
                    ;; Body starts here - fresh register scope
                    (let [body-addr (current-addr)
                          saved-reg-counter @reg-counter]
                      (reset-regs!)
                      (let [result-reg (compile-node body-ref true)
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
              :application
                (let [op-ref (get-attr e :yin/operator)
                      operand-refs (get-attr e :yin/operands)
                      ;; Compile operands first (never in tail position)
                      arg-regs (mapv #(compile-node % false) operand-refs)
                      ;; Compile operator (never in tail position)
                      fn-reg (compile-node op-ref false)
                      ;; Result register
                      rd (alloc-reg!)]
                  (emit! [(if tail? :tailcall :call) rd fn-reg arg-regs])
                  rd)
              :if (let [test-ref (get-attr e :yin/test)
                        cons-ref (get-attr e :yin/consequent)
                        alt-ref (get-attr e :yin/alternate)
                        ;; Compile test (never in tail position)
                        test-reg (compile-node test-ref false)
                        ;; Result register (shared by both branches)
                        rd (alloc-reg!)
                        branch-idx (current-addr)]
                    ;; Emit branch with placeholders
                    (emit! [:branch test-reg :then :else])
                    ;; Consequent (propagate tail?)
                    (let [then-addr (current-addr)
                          cons-reg (compile-node cons-ref tail?)]
                      (emit! [:move rd cons-reg])
                      (let [jump-idx (current-addr)]
                        (emit! [:jump :end])
                        ;; Alternate (propagate tail?)
                        (let [else-addr (current-addr)
                              alt-reg (compile-node alt-ref tail?)]
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
                              (emit! [:store-get rd (get-attr e :yin/key)])
                              rd)
              :vm/store-put (let [rd (alloc-reg!)]
                              (emit! [:literal rd (get-attr e :yin/val)])
                              (emit! [:store-put rd (get-attr e :yin/key)])
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
              :stream/cursor (let [source-ref (get-attr e :yin/source)
                                   source-reg (compile-node source-ref)
                                   rd (alloc-reg!)]
                               (emit! [:stream-cursor rd source-reg])
                               rd)
              :stream/next (let [source-ref (get-attr e :yin/source)
                                 source-reg (compile-node source-ref)
                                 rd (alloc-reg!)]
                             (emit! [:stream-next rd source-reg])
                             rd)
              :stream/close (let [source-ref (get-attr e :yin/source)
                                  source-reg (compile-node source-ref)
                                  rd (alloc-reg!)]
                              (emit! [:stream-close rd source-reg])
                              rd)
              ;; Continuation primitives
              :vm/park (let [rd (alloc-reg!)]
                         (emit! [:park rd])
                         rd)
              :vm/resume (let [parked-id (get-attr e :yin/parked-id)
                               val (get-attr e :yin/val)
                               rd (alloc-reg!)]
                           (emit! [:literal rd parked-id])
                           (let [rv (alloc-reg!)]
                             (emit! [:literal rv val])
                             (emit! [:resume rd rv])
                             rv))
              :vm/current-continuation (let [rd (alloc-reg!)]
                                         (emit! [:current-cont rd])
                                         rd)
              ;; Unknown type
              (throw (ex-info
                       "Unknown node type in register assembly compilation"
                       {:type node-type, :entity e}))))))]
      (let [result-reg (compile-node root-id true)
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
          :literal (let [[rd v] args]
                     (emit! (vm/opcode-table :literal) rd (intern! v)))
          :load-var (let [[rd name] args]
                      (emit! (vm/opcode-table :load-var) rd (intern! name)))
          :move (let [[rd rs] args] (emit! (vm/opcode-table :move) rd rs))
          :lambda
            (let [[rd params addr reg-count] args]
              (emit! (vm/opcode-table :lambda) rd (intern! params) reg-count)
              (emit-fixup! addr))
          :call (let [[rd rf arg-regs] args]
                  (emit! (vm/opcode-table :call) rd rf (count arg-regs))
                  (doseq [ar arg-regs] (emit! ar)))
          :tailcall (let [[rd rf arg-regs] args]
                      (emit! (vm/opcode-table :tailcall) rd rf (count arg-regs))
                      (doseq [ar arg-regs] (emit! ar)))
          :return (let [[rs] args] (emit! (vm/opcode-table :return) rs))
          :branch (let [[rt then-addr else-addr] args]
                    (emit! (vm/opcode-table :branch) rt)
                    (emit-fixup! then-addr)
                    (emit-fixup! else-addr))
          :jump (let [[addr] args]
                  (emit! (vm/opcode-table :jump))
                  (emit-fixup! addr))
          :gensym (let [[rd prefix] args]
                    (emit! (vm/opcode-table :gensym) rd (intern! prefix)))
          :store-get (let [[rd key] args]
                       (emit! (vm/opcode-table :store-get) rd (intern! key)))
          :store-put (let [[rs key] args]
                       (emit! (vm/opcode-table :store-put) rs (intern! key)))
          :stream-make
            (let [[rd buf] args]
              (emit! (vm/opcode-table :stream-make) rd (intern! buf)))
          :stream-put (let [[rs rt] args]
                        (emit! (vm/opcode-table :stream-put) rs rt))
          :stream-cursor (let [[rd rs] args]
                           (emit! (vm/opcode-table :stream-cursor) rd rs))
          :stream-next (let [[rd rs] args]
                         (emit! (vm/opcode-table :stream-next) rd rs))
          :stream-close (let [[rd rs] args]
                          (emit! (vm/opcode-table :stream-close) rd rs))
          :park (let [[rd] args] (emit! (vm/opcode-table :park) rd))
          :resume (let [[rd rs] args] (emit! (vm/opcode-table :resume) rd rs))
          :current-cont (let [[rd] args]
                          (emit! (vm/opcode-table :current-cont) rd)))))
    ;; Fix addresses
    (let [offsets @instr-offsets
          fixed (reduce (fn [bc [pos asm-addr]]
                          (assoc bc pos (get offsets asm-addr asm-addr)))
                  @bytecode
                  @fixups)
          source-map (into {} (map (fn [[k v]] [v k]) offsets))]
      {:bytecode fixed, :pool @pool, :source-map source-map})))


(defn- get-reg "Get value from register." [state r] (nth (:regs state) r))


;; =============================================================================
;; Register Bytecode VM (numeric opcodes, flat int vector, constant pool)
;; =============================================================================

(defn- reg-vm-step
  "Execute one bytecode instruction. Returns updated state."
  [state]
  (let [{:keys [bytecode pool pc regs env store k primitives id-counter]} state
        op (get bytecode pc)]
    (if (nil? op)
      (throw (ex-info "Bytecode ended without return instruction" {:pc pc}))
      (vm/opcase
        op
        :literal
        (let [rd (get bytecode (+ pc 1))
              v (get pool (get bytecode (+ pc 2)))]
          (assoc state
            :regs (assoc regs rd v)
            :pc (+ pc 3)))
        :load-var
        (let [rd (get bytecode (+ pc 1))
              name (get pool (get bytecode (+ pc 2)))
              v (engine/resolve-var env store primitives name)]
          (assoc state
            :regs (assoc regs rd v)
            :pc (+ pc 3)))
        :move
        (let [rd (get bytecode (+ pc 1))
              rs (get bytecode (+ pc 2))]
          (assoc state
            :regs (assoc regs rd (get-reg state rs))
            :pc (+ pc 3)))
        :lambda
        (let [rd (get bytecode (+ pc 1))
              params (get pool (get bytecode (+ pc 2)))
              reg-count (get bytecode (+ pc 3))
              body-addr (get bytecode (+ pc 4))
              closure {:type :closure,
                       :params params,
                       :body-addr body-addr,
                       :reg-count reg-count,
                       :env env,
                       :bytecode bytecode,
                       :pool pool}]
          (assoc state
            :regs (assoc regs rd closure)
            :pc (+ pc 5)))
        :call
        (let [rd (get bytecode (+ pc 1))
              rf (get bytecode (+ pc 2))
              argc (get bytecode (+ pc 3))
              fn-args (loop [i 0
                             args (transient [])]
                        (if (< i argc)
                          (recur (inc i)
                                 (conj! args
                                        (get-reg state
                                                 (get bytecode (+ pc 4 i)))))
                          (persistent! args)))
              fn-val (get-reg state rf)
              next-pc (+ pc 4 argc)]
          (cond (fn? fn-val)
                  (let [result (apply fn-val fn-args)]
                    (if (module/effect? result)
                      (let [{:keys [state value blocked?]}
                              (engine/handle-effect
                                state
                                result
                                {:park-entry-fns
                                   {:stream/put (fn [_s _e r]
                                                  {:pc next-pc,
                                                   :regs regs,
                                                   :k k,
                                                   :env env,
                                                   :bytecode bytecode,
                                                   :pool pool,
                                                   :result-reg rd,
                                                   :reason :put,
                                                   :stream-id (:stream-id r),
                                                   :datom (:val result)}),
                                    :stream/next (fn [_s _e r]
                                                   {:pc next-pc,
                                                    :regs regs,
                                                    :k k,
                                                    :env env,
                                                    :bytecode bytecode,
                                                    :pool pool,
                                                    :result-reg rd,
                                                    :reason :next,
                                                    :cursor-ref (:cursor-ref r),
                                                    :stream-id (:stream-id
                                                                 r)})}})]
                        (if blocked?
                          state
                          (assoc state
                            :regs (assoc regs rd value)
                            :pc next-pc)))
                      (assoc state
                        :regs (assoc regs rd result)
                        :pc next-pc)))
                (= :closure (:type fn-val))
                  (let [{:keys [params body-addr env bytecode pool reg-count]}
                          fn-val
                        new-frame {:type :call-frame,
                                   :return-reg rd,
                                   :return-pc next-pc,
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
                      :pc body-addr))
                :else (throw (ex-info "Cannot call non-function"
                                      {:fn fn-val}))))
        :tailcall
        (let [rd (get bytecode (+ pc 1))
              rf (get bytecode (+ pc 2))
              argc (get bytecode (+ pc 3))
              fn-args (loop [i 0
                             args (transient [])]
                        (if (< i argc)
                          (recur (inc i)
                                 (conj! args
                                        (get-reg state
                                                 (get bytecode (+ pc 4 i)))))
                          (persistent! args)))
              fn-val (get-reg state rf)
              next-pc (+ pc 4 argc)]
          (cond (fn? fn-val)
                  (let [result (apply fn-val fn-args)]
                    (if (module/effect? result)
                      (case (:effect result)
                        :vm/store-put (assoc state
                                        :store (assoc store
                                                 (:key result) (:val result))
                                        :regs (assoc regs rd (:val result))
                                        :pc next-pc)
                        (throw (ex-info "Unhandled effect in tailcall"
                                        {:effect result})))
                      (assoc state
                        :regs (assoc regs rd result)
                        :pc next-pc)))
                (= :closure (:type fn-val))
                  (let [{clo-params :params,
                         body-addr :body-addr,
                         clo-env :env,
                         clo-bytecode :bytecode,
                         clo-pool :pool,
                         reg-count :reg-count}
                          fn-val
                        new-env (merge clo-env (zipmap clo-params fn-args))]
                    ;; TCO: reuse the current frame (k stays the same)
                    (assoc state
                      :regs (vec (repeat reg-count nil))
                      :env new-env
                      :bytecode clo-bytecode
                      :pool clo-pool
                      :pc body-addr))
                :else (throw (ex-info "Cannot call non-function"
                                      {:fn fn-val}))))
        :return
        (let [rs (get bytecode (+ pc 1))
              result (get-reg state rs)]
          (if (nil? k)
            (assoc state
              :halted true
              :value result)
            (let [{:keys [return-reg return-pc saved-regs saved-env
                          saved-bytecode saved-pool parent]}
                    k]
              (assoc state
                :regs (assoc saved-regs return-reg result)
                :k parent
                :env saved-env
                :bytecode (or saved-bytecode (:bytecode state))
                :pool (or saved-pool (:pool state))
                :pc return-pc))))
        :branch
        (let [rt (get bytecode (+ pc 1))
              then-addr (get bytecode (+ pc 2))
              else-addr (get bytecode (+ pc 3))
              test-val (get-reg state rt)]
          (assoc state :pc (if test-val then-addr else-addr)))
        :jump
        (let [addr (get bytecode (+ pc 1))] (assoc state :pc addr))
        :gensym
        (let [rd (get bytecode (+ pc 1))
              prefix (get pool (get bytecode (+ pc 2)))
              [id s'] (engine/gensym state prefix)]
          (assoc s'
            :regs (assoc regs rd id)
            :pc (+ pc 3)))
        :store-get
        (let [rd (get bytecode (+ pc 1))
              key (get pool (get bytecode (+ pc 2)))
              val (get store key)]
          (assoc state
            :regs (assoc regs rd val)
            :pc (+ pc 3)))
        :store-put
        (let [rs (get bytecode (+ pc 1))
              key (get pool (get bytecode (+ pc 2)))
              val (get-reg state rs)]
          (assoc state
            :store (assoc store key val)
            :pc (+ pc 3)))
        :stream-make
        (let [rd (get bytecode (+ pc 1))
              buf (get pool (get bytecode (+ pc 2)))
              effect {:effect :stream/make, :capacity buf}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
            :regs (assoc regs rd value)
            :pc (+ pc 3)))
        :stream-put
        (let [rs (get bytecode (+ pc 1))
              rt (get bytecode (+ pc 2))
              val (get-reg state rs)
              stream-ref (get-reg state rt)
              effect {:effect :stream/put, :stream stream-ref, :val val}
              {:keys [state blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns {:stream/put (fn [_s _e r]
                                                  {:pc (+ pc 3),
                                                   :regs regs,
                                                   :k k,
                                                   :env env,
                                                   :bytecode bytecode,
                                                   :pool pool,
                                                   :result-reg rs,
                                                   :reason :put,
                                                   :stream-id (:stream-id r),
                                                   :datom val})}})]
          (if blocked? state (assoc state :pc (+ pc 3))))
        :stream-cursor
        (let [rd (get bytecode (+ pc 1))
              rs (get bytecode (+ pc 2))
              stream-ref (get-reg state rs)
              effect {:effect :stream/cursor, :stream stream-ref}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
            :regs (assoc regs rd value)
            :pc (+ pc 3)))
        :stream-next
        (let [rd (get bytecode (+ pc 1))
              rs (get bytecode (+ pc 2))
              cursor-ref (get-reg state rs)
              effect {:effect :stream/next, :cursor cursor-ref}
              {:keys [state value blocked?]}
                (engine/handle-effect state
                                      effect
                                      {:park-entry-fns
                                         {:stream/next
                                            (fn [_s _e r]
                                              {:pc (+ pc 3),
                                               :regs regs,
                                               :k k,
                                               :env env,
                                               :bytecode bytecode,
                                               :pool pool,
                                               :result-reg rd,
                                               :reason :next,
                                               :cursor-ref (:cursor-ref r),
                                               :stream-id (:stream-id r)})}})]
          (if blocked?
            state
            (assoc state
              :regs (assoc regs rd value)
              :pc (+ pc 3))))
        :stream-close
        (let [rd (get bytecode (+ pc 1))
              rs (get bytecode (+ pc 2))
              stream-ref (get-reg state rs)
              effect {:effect :stream/close, :stream stream-ref}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
            :regs (assoc regs rd value)
            :pc (+ pc 3)))
        :park
        (let [rd (get bytecode (+ pc 1))]
          (engine/park-continuation state
                                    {:pc (+ pc 2),
                                     :regs regs,
                                     :k k,
                                     :env env,
                                     :bytecode bytecode,
                                     :pool pool,
                                     :result-reg rd}))
        :resume
        (let [rs (get bytecode (+ pc 1))
              rt (get bytecode (+ pc 2))
              parked-id (get-reg state rs)
              resume-val (get-reg state rt)]
          (engine/resume-continuation state
                                      parked-id
                                      resume-val
                                      (fn [new-state parked rv]
                                        (let [rd (:result-reg parked)]
                                          (assoc new-state
                                            :pc (:pc parked)
                                            :regs (assoc (:regs parked) rd rv)
                                            :k (:k parked)
                                            :env (:env parked)
                                            :bytecode (:bytecode parked)
                                            :pool (:pool parked))))))
        :current-cont
        (let [rd (get bytecode (+ pc 1))
              cont {:type :reified-continuation,
                    :pc (+ pc 2),
                    :regs regs,
                    :k k,
                    :env env,
                    :bytecode bytecode,
                    :pool pool,
                    :result-reg rd}]
          (assoc state
            :regs (assoc regs rd cont)
            :pc (+ pc 2)))
        (throw (ex-info "Unknown bytecode opcode" {:op op, :pc pc}))))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- resume-from-run-queue
  "Pop first entry from run-queue and resume it as the active computation.
   Returns updated state or nil if queue is empty."
  [state]
  (engine/resume-from-run-queue state
                                (fn [base entry]
                                  (assoc base
                                    :pc (:pc entry)
                                    :regs (if-let [rd (:result-reg entry)]
                                            (assoc (:regs entry)
                                              rd (:value entry))
                                            (:regs entry))
                                    :k (:k entry)
                                    :env (:env entry)
                                    :bytecode (:bytecode entry)
                                    :pool (:pool entry)))))


;; =============================================================================
;; RegisterVM Protocol Implementation
;; =============================================================================

(defn- reg-vm-halted?
  "Returns true if VM has halted."
  [^RegisterVM vm]
  (engine/halted-with-empty-queue? vm))


(defn- reg-vm-blocked?
  "Returns true if VM is blocked."
  [^RegisterVM vm]
  (engine/vm-blocked? vm))


(defn- reg-vm-value
  "Returns the current value."
  [^RegisterVM vm]
  (engine/vm-value vm))


(defn- reg-vm-reset
  "Reset RegisterVM execution state to initial baseline, preserving loaded program."
  [^RegisterVM vm]
  (assoc vm
    :regs (vec (repeat (count (:regs vm)) nil))
    :k nil
    :pc 0
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
    :pc 0
    :bytecode (:bytecode program)
    :pool (:pool program)
    :halted false
    :value nil
    :blocked false))


(defn- reg-vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, compiles through datoms -> asm -> bytecode and loads.
   When nil, resumes from current state."
  [^RegisterVM vm ast]
  (let [v (if ast
            (let [datoms (vm/ast->datoms ast)
                  {:keys [asm reg-count]} (ast-datoms->asm datoms)
                  compiled (assoc (asm->bytecode asm) :reg-count reg-count)]
              (reg-vm-load-program vm compiled))
            vm)]
    (engine/run-loop v
                     (fn [v] (and (not (:blocked v)) (not (:halted v))))
                     reg-vm-step
                     resume-from-run-queue)))


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
    (control [vm] {:pc (:pc vm), :bytecode (:bytecode vm), :regs (:regs vm)})
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
                              :pc 0,
                              :bytecode nil,
                              :pool nil,
                              :halted false,
                              :value nil,
                              :blocked false})))))
