(ns yin.vm.register
  "Register-based bytecode VM for the Yin language.

   A register machine implementation of the CESK model where:
   - Virtual registers (r0, r1, r2...) hold intermediate values
   - Registers are a vector that grows as needed (no fixed limit)
   - Each call frame has its own register space (saved/restored via :k)
   - Continuation :k is always first-class and explicit
   - No implicit call stack - continuation IS the stack

   Executes numeric bytecode produced by assemble.
   The compilation pipeline is: AST datoms -> ast-datoms->asm (symbolic IR) -> assemble (numeric).
   See ast-datoms->asm docstring for the full instruction set."
  (:require
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.engine :as engine])
  #?(:cljs
     (:require-macros
       [yin.vm :refer [opcase]])))


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
   opcodes). A separate assemble pass would encode these as numbers.

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


(defn assemble
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
            (emit! (vm/opcode-table :lambda) rd (intern! (vec params)) reg-count)
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


(defn- reg-array?
  [regs]
  #?(:clj (and regs (.isArray (class regs)))
     :cljs (array? regs)
     :cljd (and (some? regs) (not (vector? regs)))))


(defn- reg-array-get
  [regs r]
  #?(:clj (aget ^objects regs (int r))
     :cljs (aget regs r)
     :cljd (aget regs (int r))))


(defn- reg-array-set!
  [regs r v]
  #?(:clj (aset ^objects regs (int r) v)
     :cljs (aset regs r v)
     :cljd (aset regs (int r) v)))


(defn- reg-array-clone
  [regs]
  #?(:clj (aclone ^objects regs)
     :cljs (.slice regs)
     :cljd (aclone regs)))


(defn- make-empty-regs-array
  [n]
  #?(:clj (object-array (int n))
     :cljs (let [arr (js/Array. n)]
             (loop [i 0]
               (if (< i n)
                 (do (aset arr i nil) (recur (inc i)))
                 arr)))
     :cljd (object-array (int n))))


(defn- regs->array
  [regs]
  (if (reg-array? regs)
    regs
    #?(:clj (object-array regs)
       :cljd (object-array regs)
       :cljs (let [n (count regs)
                   arr (js/Array. n)]
               (loop [i 0]
                 (if (< i n)
                   (do (aset arr i (nth regs i)) (recur (inc i)))
                   arr))))))


(defn- regs->vector
  [regs]
  (cond
    (reg-array? regs) (vec regs)
    (vector? regs) regs
    :else (vec regs)))


(defn- get-reg
  "Get value from register."
  [regs r]
  (if (reg-array? regs)
    (reg-array-get regs r)
    (nth regs r)))


(defn- collect-call-args
  "Collect function arguments from register indices encoded in bytecode."
  [regs bytecode arg-base argc]
  (loop [i 0
         args (transient [])]
    (if (< i argc)
      (let [arg-reg (nth bytecode (+ arg-base i))]
        (recur (inc i) (conj! args (get-reg regs arg-reg))))
      (persistent! args))))


(defn- invoke-native
  "Invoke a native function directly for small arities to avoid apply/seq churn."
  [fn-val regs bytecode arg-base argc]
  (case argc
    0 (fn-val)
    1 (fn-val (get-reg regs (nth bytecode arg-base)))
    2 (fn-val (get-reg regs (nth bytecode arg-base))
              (get-reg regs (nth bytecode (inc arg-base))))
    3 (fn-val (get-reg regs (nth bytecode arg-base))
              (get-reg regs (nth bytecode (inc arg-base)))
              (get-reg regs (nth bytecode (+ arg-base 2))))
    4 (fn-val (get-reg regs (nth bytecode arg-base))
              (get-reg regs (nth bytecode (inc arg-base)))
              (get-reg regs (nth bytecode (+ arg-base 2)))
              (get-reg regs (nth bytecode (+ arg-base 3))))
    (apply fn-val (collect-call-args regs bytecode arg-base argc))))


(defn- bind-closure-env
  "Bind closure params from argument registers while preserving zipmap semantics:
   extra args are ignored and missing args bind as nil."
  [base-env params regs bytecode arg-base argc]
  (let [pcount (count params)]
    (case pcount
      0 base-env
      1 (assoc base-env
               (nth params 0)
               (if (< 0 argc)
                 (get-reg regs (nth bytecode arg-base))
                 nil))
      2 (let [v0 (if (< 0 argc)
                   (get-reg regs (nth bytecode arg-base))
                   nil)
              v1 (if (< 1 argc)
                   (get-reg regs (nth bytecode (inc arg-base)))
                   nil)]
          (assoc (assoc base-env (nth params 0) v0)
                 (nth params 1)
                 v1))
      3 (let [v0 (if (< 0 argc)
                   (get-reg regs (nth bytecode arg-base))
                   nil)
              v1 (if (< 1 argc)
                   (get-reg regs (nth bytecode (inc arg-base)))
                   nil)
              v2 (if (< 2 argc)
                   (get-reg regs (nth bytecode (+ arg-base 2)))
                   nil)]
          (assoc (assoc (assoc base-env (nth params 0) v0)
                        (nth params 1)
                        v1)
                 (nth params 2)
                 v2))
      (loop [i 0
             tenv (transient base-env)]
        (if (< i pcount)
          (recur (inc i)
                 (assoc! tenv
                         (nth params i)
                         (if (< i argc)
                           (get-reg regs (nth bytecode (+ arg-base i)))
                           nil)))
          (persistent! tenv))))))


(defn- snap-frame
  "Snapshot current VM frame for parking or reification."
  [{:keys [regs k env bytecode pool]} rd next-pc]
  {:pc next-pc,
   :regs regs,
   :k k,
   :env env,
   :bytecode bytecode,
   :pool pool,
   :result-reg rd})


(defn- native-park-entry
  "Build wait-set entry for native stream effects."
  [state effect stream-result rd next-pc]
  (case (:effect effect)
    :stream/put (merge (snap-frame state rd next-pc)
                       {:reason :put,
                        :stream-id (:stream-id stream-result),
                        :datom (:val effect)})
    :stream/next (merge (snap-frame state rd next-pc)
                        {:reason :next,
                         :cursor-ref (:cursor-ref stream-result),
                         :stream-id (:stream-id stream-result)})
    nil))


(defn- restore-frame
  "Restore VM state from a snapshot, optionally pushing a result into rd."
  ([state frame]
   (assoc state
          :pc (:pc frame)
          :regs (:regs frame)
          :k (:k frame)
          :env (:env frame)
          :bytecode (or (:bytecode frame) (:bytecode state))
          :pool (or (:pool frame) (:pool state))))
  ([state frame value]
   (let [rd (:result-reg frame)]
     (assoc state
            :pc (:pc frame)
            :regs (if rd (assoc (:regs frame) rd value) (:regs frame))
            :k (:k frame)
            :env (:env frame)
            :bytecode (or (:bytecode frame) (:bytecode state))
            :pool (or (:pool frame) (:pool state))))))


(defn- handle-native-result
  "Handle result of calling a native fn. Routes effects through handle-effect."
  [{:keys [regs], :as state} result rd next-pc]
  (if (module/effect? result)
    (let [effect-opts (case (:effect result)
                        :stream/put {:park-entry-fns
                                     {:stream/put
                                      (fn [s e r]
                                        (native-park-entry s e r rd next-pc))}}
                        :stream/next {:park-entry-fns
                                      {:stream/next
                                       (fn [s e r]
                                         (native-park-entry s e r rd next-pc))}}
                        {})
          {:keys [state value blocked?]}
          (engine/handle-effect state result effect-opts)]
      (if blocked?
        state
        (assoc state
               :regs (assoc regs rd value)
               :pc next-pc)))
    (assoc state
           :regs (assoc regs rd result)
           :pc next-pc)))


;; =============================================================================
;; Register Bytecode VM (numeric opcodes, flat int vector, constant pool)
;; =============================================================================

(defn- reg-vm-step
  "Execute one bytecode instruction. Returns updated state."
  [state]
  (let [{:keys [bytecode pool pc regs env store k primitives]} state
        op (nth bytecode pc nil)]
    (if (nil? op)
      (throw (ex-info "Bytecode ended without return instruction" {:pc pc}))
      (vm/opcase
        op
        :literal
        (let [rd (nth bytecode (inc pc))
              v (nth pool (nth bytecode (+ pc 2)))]
          (assoc state
                 :regs (assoc regs rd v)
                 :pc (+ pc 3)))
        :load-var
        (let [rd (nth bytecode (inc pc))
              name (nth pool (nth bytecode (+ pc 2)))
              v (engine/resolve-var env store primitives name)]
          (assoc state
                 :regs (assoc regs rd v)
                 :pc (+ pc 3)))
        :move
        (let [rd (nth bytecode (inc pc))
              rs (nth bytecode (+ pc 2))]
          (assoc state
                 :regs (assoc regs rd (get-reg regs rs))
                 :pc (+ pc 3)))
        :lambda
        (let [rd (nth bytecode (inc pc))
              params (nth pool (nth bytecode (+ pc 2)))
              reg-count (nth bytecode (+ pc 3))
              body-addr (nth bytecode (+ pc 4))
              closure {:type :closure,
                       :params params,
                       :body-addr body-addr,
                       :reg-count reg-count,
                       :empty-regs (vec (repeat reg-count nil)),
                       :empty-regs-arr (make-empty-regs-array reg-count),
                       :env env,
                       :bytecode bytecode,
                       :pool pool}]
          (assoc state
                 :regs (assoc regs rd closure)
                 :pc (+ pc 5)))
        :call
        (let [rd (nth bytecode (inc pc))
              rf (nth bytecode (+ pc 2))
              argc (nth bytecode (+ pc 3))
              arg-base (+ pc 4)
              fn-val (get-reg regs rf)
              next-pc (+ arg-base argc)]
          (cond (fn? fn-val)
                (let [result (invoke-native fn-val regs bytecode arg-base argc)]
                  (handle-native-result state result rd next-pc))
                (= :closure (:type fn-val))
                (let [{clo-params :params,
                       body-addr :body-addr,
                       clo-env :env,
                       clo-bytecode :bytecode,
                       clo-pool :pool,
                       empty-regs :empty-regs} fn-val
                      next-regs (or empty-regs
                                    (vec (repeat (:reg-count fn-val) nil)))
                      new-frame {:type :call-frame,
                                 :result-reg rd,
                                 :pc next-pc,
                                 :regs regs,
                                 :env env,
                                 :bytecode bytecode,
                                 :pool pool,
                                 :k k}
                      new-env (bind-closure-env
                                clo-env
                                clo-params
                                regs
                                bytecode
                                arg-base
                                argc)]
                  (assoc state
                         :regs next-regs
                         :k new-frame
                         :env new-env
                         :bytecode clo-bytecode
                         :pool clo-pool
                         :pc body-addr))
                :else (throw (ex-info "Cannot call non-function"
                                      {:fn fn-val}))))
        :tailcall
        (let [rd (nth bytecode (inc pc))
              rf (nth bytecode (+ pc 2))
              argc (nth bytecode (+ pc 3))
              arg-base (+ pc 4)
              fn-val (get-reg regs rf)
              next-pc (+ arg-base argc)]
          (cond (fn? fn-val)
                (let [result (invoke-native fn-val regs bytecode arg-base argc)]
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
                       empty-regs :empty-regs}
                      fn-val
                      next-regs (or empty-regs
                                    (vec (repeat (:reg-count fn-val) nil)))
                      new-env (bind-closure-env
                                clo-env
                                clo-params
                                regs
                                bytecode
                                arg-base
                                argc)]
                  ;; TCO: reuse the current frame (k stays the same)
                  (assoc state
                         :regs next-regs
                         :env new-env
                         :bytecode clo-bytecode
                         :pool clo-pool
                         :pc body-addr))
                :else (throw (ex-info "Cannot call non-function"
                                      {:fn fn-val}))))
        :return
        (let [rs (nth bytecode (inc pc))
              result (get-reg regs rs)]
          (if (nil? k)
            (assoc state
                   :halted true
                   :value result)
            (restore-frame state k result)))
        :branch
        (let [rt (nth bytecode (inc pc))
              then-addr (nth bytecode (+ pc 2))
              else-addr (nth bytecode (+ pc 3))
              test-val (get-reg regs rt)]
          (assoc state :pc (if test-val then-addr else-addr)))
        :jump
        (let [addr (nth bytecode (inc pc))] (assoc state :pc addr))
        :gensym
        (let [rd (nth bytecode (inc pc))
              prefix (nth pool (nth bytecode (+ pc 2)))
              [id s'] (engine/gensym state prefix)]
          (assoc s'
                 :regs (assoc regs rd id)
                 :pc (+ pc 3)))
        :store-get
        (let [rd (nth bytecode (inc pc))
              key (nth pool (nth bytecode (+ pc 2)))
              val (get store key)]
          (assoc state
                 :regs (assoc regs rd val)
                 :pc (+ pc 3)))
        :store-put
        (let [rs (nth bytecode (inc pc))
              key (nth pool (nth bytecode (+ pc 2)))
              val (get-reg regs rs)]
          (assoc state
                 :store (assoc store key val)
                 :pc (+ pc 3)))
        :stream-make
        (let [rd (nth bytecode (inc pc))
              buf (nth pool (nth bytecode (+ pc 2)))
              effect {:effect :stream/make, :capacity buf}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
                 :regs (assoc regs rd value)
                 :pc (+ pc 3)))
        :stream-put
        (let [rs (nth bytecode (inc pc))
              rt (nth bytecode (+ pc 2))
              val (get-reg regs rs)
              stream-ref (get-reg regs rt)
              effect {:effect :stream/put, :stream stream-ref, :val val}
              {:keys [state blocked?]}
              (engine/handle-effect
                state
                effect
                {:park-entry-fns {:stream/put
                                  (fn [_s _e r]
                                    (merge (snap-frame state rs (+ pc 3))
                                           {:reason :put,
                                            :stream-id (:stream-id r),
                                            :datom val}))}})]
          (if blocked? state (assoc state :pc (+ pc 3))))
        :stream-cursor
        (let [rd (nth bytecode (inc pc))
              rs (nth bytecode (+ pc 2))
              stream-ref (get-reg regs rs)
              effect {:effect :stream/cursor, :stream stream-ref}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
                 :regs (assoc regs rd value)
                 :pc (+ pc 3)))
        :stream-next
        (let [rd (nth bytecode (inc pc))
              rs (nth bytecode (+ pc 2))
              cursor-ref (get-reg regs rs)
              effect {:effect :stream/next, :cursor cursor-ref}
              {:keys [state value blocked?]}
              (engine/handle-effect
                state
                effect
                {:park-entry-fns {:stream/next
                                  (fn [_s _e r]
                                    (merge (snap-frame state rd (+ pc 3))
                                           {:reason :next,
                                            :cursor-ref (:cursor-ref r),
                                            :stream-id (:stream-id r)}))}})]
          (if blocked?
            state
            (assoc state
                   :regs (assoc regs rd value)
                   :pc (+ pc 3))))
        :stream-close
        (let [rd (nth bytecode (inc pc))
              rs (nth bytecode (+ pc 2))
              stream-ref (get-reg regs rs)
              effect {:effect :stream/close, :stream stream-ref}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
                 :regs (assoc regs rd value)
                 :pc (+ pc 3)))
        :park
        (let [rd (nth bytecode (inc pc))]
          (engine/park-continuation state (snap-frame state rd (+ pc 2))))
        :resume
        (let [rs (nth bytecode (inc pc))
              rt (nth bytecode (+ pc 2))
              parked-id (get-reg regs rs)
              resume-val (get-reg regs rt)]
          (engine/resume-continuation state
                                      parked-id
                                      resume-val
                                      (fn [new-state parked rv]
                                        (restore-frame new-state parked rv))))
        :current-cont
        (let [rd (nth bytecode (inc pc))
              cont (assoc (snap-frame state rd (+ pc 2))
                          :type :reified-continuation)]
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
                                  (restore-frame base entry (:value entry)))))


(defn- reg-vm-run-slow
  "Generic scheduler-backed run loop."
  [vm-state]
  (engine/run-loop vm-state
                   (fn [v] (and (not (:blocked v)) (not (:halted v))))
                   reg-vm-step
                   resume-from-run-queue))


(declare fast-frame->map)


(defn- materialize-fast-state
  "Materialize loop locals back into a RegisterVM value."
  [vm pc regs env store k bytecode pool id-counter]
  (assoc vm
         :pc pc
         :regs (regs->vector regs)
         :env env
         :store store
         :k (fast-frame->map k)
         :bytecode bytecode
         :pool pool
         :id-counter id-counter
         :halted false
         :blocked false))


(defn- fast-call-frame
  "Compact call-frame representation for the fast loop."
  [result-reg next-pc regs env bytecode pool parent-k]
  [:fast-call result-reg next-pc regs env bytecode pool parent-k])


(defn- fast-call-frame?
  [frame]
  (and (vector? frame) (= :fast-call (nth frame 0 nil))))


(defn- fast-frame->map
  "Convert compact fast-loop call frames to map call frames for slow-path fallback."
  [frame]
  (if (fast-call-frame? frame)
    {:type :call-frame,
     :result-reg (nth frame 1),
     :pc (nth frame 2),
     :regs (regs->vector (nth frame 3)),
     :env (nth frame 4),
     :bytecode (nth frame 5),
     :pool (nth frame 6),
     :k (fast-frame->map (nth frame 7))}
    frame))


(defn- reg-vm-run-fast
  "Fast register VM run loop that keeps hot execution state in locals.
   Falls back to the generic scheduler loop when stream/control effects appear."
  [vm]
  (loop [pc (:pc vm)
         regs (regs->array (:regs vm))
         env (:env vm)
         store (:store vm)
         k (:k vm)
         bytecode (:bytecode vm)
         pool (:pool vm)
         id-counter (:id-counter vm)]
    (let [op (nth bytecode pc nil)
          fallback #(reg-vm-run-slow
                      (materialize-fast-state
                        vm
                        pc
                        regs
                        env
                        store
                        k
                        bytecode
                        pool
                        id-counter))]
      (if (nil? op)
        (throw (ex-info "Bytecode ended without return instruction" {:pc pc}))
        (vm/opcase
          op
          :literal
          (let [rd (nth bytecode (inc pc))
                v (nth pool (nth bytecode (+ pc 2)))]
            (reg-array-set! regs rd v)
            (recur (+ pc 3) regs env store k bytecode pool id-counter))
          :load-var
          (let [rd (nth bytecode (inc pc))
                name (nth pool (nth bytecode (+ pc 2)))
                v (engine/resolve-var env store (:primitives vm) name)]
            (reg-array-set! regs rd v)
            (recur (+ pc 3) regs env store k bytecode pool id-counter))
          :move
          (let [rd (nth bytecode (inc pc))
                rs (nth bytecode (+ pc 2))]
            (reg-array-set! regs rd (get-reg regs rs))
            (recur (+ pc 3) regs env store k bytecode pool id-counter))
          :lambda
          (let [rd (nth bytecode (inc pc))
                params (nth pool (nth bytecode (+ pc 2)))
                reg-count (nth bytecode (+ pc 3))
                body-addr (nth bytecode (+ pc 4))
                closure {:type :closure,
                         :params params,
                         :body-addr body-addr,
                         :reg-count reg-count,
                         :empty-regs (vec (repeat reg-count nil)),
                         :empty-regs-arr (make-empty-regs-array reg-count),
                         :env env,
                         :bytecode bytecode,
                         :pool pool}]
            (reg-array-set! regs rd closure)
            (recur (+ pc 5) regs env store k bytecode pool id-counter))
          :call
          (let [rd (nth bytecode (inc pc))
                rf (nth bytecode (+ pc 2))
                argc (nth bytecode (+ pc 3))
                arg-base (+ pc 4)
                fn-val (get-reg regs rf)
                next-pc (+ arg-base argc)]
            (cond (fn? fn-val)
                  (let [result (invoke-native fn-val regs bytecode arg-base argc)]
                    (if (module/effect? result)
                      (-> (materialize-fast-state
                            vm
                            pc
                            regs
                            env
                            store
                            k
                            bytecode
                            pool
                            id-counter)
                          (handle-native-result result rd next-pc)
                          reg-vm-run-slow)
                      (do
                        (reg-array-set! regs rd result)
                        (recur next-pc
                               regs
                               env
                               store
                               k
                               bytecode
                               pool
                               id-counter))))
                  (= :closure (:type fn-val))
                  (let [{clo-params :params,
                         body-addr :body-addr,
                         clo-env :env,
                         clo-bytecode :bytecode,
                         clo-pool :pool,
                         empty-regs :empty-regs,
                         empty-regs-arr :empty-regs-arr}
                        fn-val
                        next-regs (reg-array-clone
                                    (or empty-regs-arr
                                        (some-> empty-regs regs->array)
                                        (make-empty-regs-array
                                          (:reg-count fn-val))))
                        new-frame (fast-call-frame
                                    rd
                                    next-pc
                                    regs
                                    env
                                    bytecode
                                    pool
                                    k)
                        new-env (bind-closure-env
                                  clo-env
                                  clo-params
                                  regs
                                  bytecode
                                  arg-base
                                  argc)]
                    (recur body-addr
                           next-regs
                           new-env
                           store
                           new-frame
                           clo-bytecode
                           clo-pool
                           id-counter))
                  :else (throw (ex-info "Cannot call non-function"
                                        {:fn fn-val}))))
          :tailcall
          (let [rd (nth bytecode (inc pc))
                rf (nth bytecode (+ pc 2))
                argc (nth bytecode (+ pc 3))
                arg-base (+ pc 4)
                fn-val (get-reg regs rf)
                next-pc (+ arg-base argc)]
            (cond (fn? fn-val)
                  (let [result (invoke-native fn-val regs bytecode arg-base argc)]
                    (if (module/effect? result)
                      (case (:effect result)
                        :vm/store-put
                        (do
                          (reg-array-set! regs rd (:val result))
                          (recur next-pc
                                 regs
                                 env
                                 (assoc store (:key result) (:val result))
                                 k
                                 bytecode
                                 pool
                                 id-counter))
                        (throw (ex-info "Unhandled effect in tailcall"
                                        {:effect result})))
                      (do
                        (reg-array-set! regs rd result)
                        (recur next-pc
                               regs
                               env
                               store
                               k
                               bytecode
                               pool
                               id-counter))))
                  (= :closure (:type fn-val))
                  (let [{clo-params :params,
                         body-addr :body-addr,
                         clo-env :env,
                         clo-bytecode :bytecode,
                         clo-pool :pool,
                         empty-regs :empty-regs,
                         empty-regs-arr :empty-regs-arr}
                        fn-val
                        next-regs (reg-array-clone
                                    (or empty-regs-arr
                                        (some-> empty-regs regs->array)
                                        (make-empty-regs-array
                                          (:reg-count fn-val))))
                        new-env (bind-closure-env
                                  clo-env
                                  clo-params
                                  regs
                                  bytecode
                                  arg-base
                                  argc)]
                    (recur body-addr
                           next-regs
                           new-env
                           store
                           k
                           clo-bytecode
                           clo-pool
                           id-counter))
                  :else (throw (ex-info "Cannot call non-function"
                                        {:fn fn-val}))))
          :return
          (let [rs (nth bytecode (inc pc))
                result (get-reg regs rs)]
            (cond
              (nil? k)
              (assoc vm
                     :pc pc
                     :regs (regs->vector regs)
                     :env env
                     :store store
                     :k k
                     :bytecode bytecode
                     :pool pool
                     :id-counter id-counter
                     :halted true
                     :value result
                     :blocked false)
              (fast-call-frame? k)
              (let [rd (nth k 1)
                    frame-pc (nth k 2)
                    frame-regs (nth k 3)
                    frame-env (nth k 4)
                    frame-bytecode (nth k 5)
                    frame-pool (nth k 6)
                    parent-k (nth k 7)]
                (when (some? rd)
                  (reg-array-set! frame-regs rd result))
                (recur frame-pc
                       frame-regs
                       frame-env
                       store
                       parent-k
                       frame-bytecode
                       frame-pool
                       id-counter))
              :else
              (let [rd (:result-reg k)
                    frame-regs (regs->array (:regs k))
                    bytecode' (or (:bytecode k) bytecode)
                    pool' (or (:pool k) pool)]
                (when (some? rd)
                  (reg-array-set! frame-regs rd result))
                (recur (:pc k)
                       frame-regs
                       (:env k)
                       store
                       (:k k)
                       bytecode'
                       pool'
                       id-counter))))
          :branch
          (let [rt (nth bytecode (inc pc))
                then-addr (nth bytecode (+ pc 2))
                else-addr (nth bytecode (+ pc 3))
                test-val (get-reg regs rt)]
            (recur (if test-val then-addr else-addr)
                   regs
                   env
                   store
                   k
                   bytecode
                   pool
                   id-counter))
          :jump
          (let [addr (nth bytecode (inc pc))]
            (recur addr regs env store k bytecode pool id-counter))
          :gensym
          (let [rd (nth bytecode (inc pc))
                prefix (nth pool (nth bytecode (+ pc 2)))
                id (keyword (str prefix "-" id-counter))]
            (reg-array-set! regs rd id)
            (recur (+ pc 3) regs env store k bytecode pool (inc id-counter)))
          :store-get
          (let [rd (nth bytecode (inc pc))
                key (nth pool (nth bytecode (+ pc 2)))
                val (get store key)]
            (reg-array-set! regs rd val)
            (recur (+ pc 3) regs env store k bytecode pool id-counter))
          :store-put
          (let [rs (nth bytecode (inc pc))
                key (nth pool (nth bytecode (+ pc 2)))
                val (get-reg regs rs)]
            (recur (+ pc 3)
                   regs
                   env
                   (assoc store key val)
                   k
                   bytecode
                   pool
                   id-counter))
          ;; Stream/control opcodes use scheduler/effect machinery. Defer to
          ;; the generic run-loop from the current local state.
          (fallback))))))


(defn- reg-vm-run
  [vm]
  (if (or (:blocked vm)
          (:halted vm)
          (seq (:run-queue vm))
          (seq (:wait-set vm))
          (nil? (:bytecode vm)))
    (reg-vm-run-slow vm)
    (reg-vm-run-fast vm)))


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
                  compiled (assoc (assemble asm) :reg-count reg-count)]
              (reg-vm-load-program vm compiled))
            vm)]
    (reg-vm-run v)))


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
