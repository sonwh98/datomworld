(ns yin.vm.register
  "Register-based bytecode VM for the Yin language.

   A register machine implementation of the CESK model where:
   - Virtual registers (r0, r1, r2...) hold intermediate values
   - Registers are a vector that grows as needed (no fixed limit)
   - Each call frame has its own register space (saved/restored via :k)
   - Continuation :k is always first-class and explicit
   - No implicit call stack - continuation IS the stack

   Mapping to CESK:
   - Control (C):     :control (integer program counter into :bytecode)
   - Environment (E): :env (lexical bindings)
   - Store (S):       :store (shared heap memory)
   - Kontinuation (K): :k (linked list of frames, which save regs/control/env)

   Executes numeric bytecode produced by assemble.
   The compilation pipeline is: AST datoms -> ast-datoms->asm (symbolic IR) -> assemble (numeric).
   See ast-datoms->asm docstring for the full instruction set."
  (:require
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.host-ffi :as host-ffi]
    [yin.vm.macro :as macro]
    [yin.vm.semantic :as semantic]
    [yin.vm.telemetry :as telemetry])
  #?(:cljs
     (:require-macros
       [yin.vm :refer [opcase]])))


;; =============================================================================
;; RegisterVM Record
;; =============================================================================

(defrecord RegisterVM
  [blocked    ; true if blocked
   bridge     ; explicit host-side FFI bridge state
   in-stream  ; ingress DaoStream carrying canonical datom programs
   in-cursor  ; ingress cursor position
   bytecode   ; numeric bytecode vector
   compiled-by-version ; {program-version -> compiled artifact}
   compiled-cache-limit ; max retained compiled artifacts
   active-compiled-version ; compiled program version for new control transfers
   compile-dirty? ; canonical datom stream changed since last compile
   env        ; lexical environment (E in CESK)
   halted     ; true if execution completed
   id-counter ; unique ID counter
   k          ; continuation (K in CESK)
   macro-registry ; {macro-lambda-eid -> (fn [ctx] {:datoms [...] :root-eid eid})}
   parked     ; parked continuations
   control    ; program counter (C in CESK)
   pool       ; constant pool
   program-datoms ; canonical bounded datom stream snapshot
   program-index ; derived datom index for compilation
   program-root-eid ; root entity id of canonical program
   program-version ; monotonic program version for append/recompile lifecycle
   primitives ; primitive operations
   regs       ; virtual registers vector (part of K in CESK)
   run-queue  ; vector of runnable continuations
   store      ; heap memory (S in CESK)
   value      ; final result value
   wait-set   ; vector of parked continuations waiting on streams
   telemetry    ; optional telemetry config
   telemetry-step ; telemetry snapshot counter
   telemetry-t  ; telemetry transaction counter
   vm-model     ; telemetry model keyword
   ])


(def ^:private default-compiled-cache-limit 8)
(def ^:private derived-metadata-eid 1)


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
     [:dao.stream.apply/call rd op args]           - park, enqueue dao.stream.apply request, resume into rd

   Uses simple linear register allocation.

   Optional opts:
   - :root-id explicit root entity id
   - :by-entity precomputed entity index {eid [datom ...]}."
  ([ast-as-datoms] (ast-datoms->asm ast-as-datoms {}))
  ([ast-as-datoms {:keys [root-id by-entity]}]
   (let [{:keys [get-attr root-id]} (vm/index-datoms ast-as-datoms
                                                     {:root-id root-id,
                                                      :by-entity by-entity})
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
               :dao.stream.apply/call
               (let [operand-refs (or (get-attr e :yin/operands) [])
                     arg-regs (mapv #(compile-node % false) operand-refs)
                     op (get-attr e :yin/op)
                     rd (alloc-reg!)]
                 (emit! [:dao.stream.apply/call rd op arg-regs])
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
                               (emit! [:literal rd (get-attr e :yin/value)])
                               (emit! [:store-put rd (get-attr e :yin/key)])
                               rd)
               ;; Stream operations
               :stream/make (let [rd (alloc-reg!)]
                              (emit! [:stream-make rd (get-attr e :yin/buffer)])
                              rd)
               :stream/put (let [target-ref (get-attr e :yin/target)
                                 val-ref (get-attr e :yin/val-node)
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
                                val-ref (get-attr e :yin/val-node)
                                rd (alloc-reg!)]
                            (emit! [:literal rd parked-id])
                            (let [rv (compile-node val-ref)]
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
         {:asm @bytecode, :reg-count max-regs})))))


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
          :dao.stream.apply/call (let [[rd op arg-regs] args]
                                   (emit! (vm/opcode-table :dao.stream.apply/call)
                                          rd
                                          (intern! op)
                                          (count arg-regs))
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


(defn- canonical-program?
  [program]
  (and (map? program)
       (contains? program :node)
       (contains? program :datoms)))


(defn- build-program-index
  [datoms]
  (group-by first (vec datoms)))


(defn- executable-program-datom?
  [[_e a _v _t m]]
  (and (keyword? a)
       (= "yin" (namespace a))
       (not= m derived-metadata-eid)))


(defn- frame-versions
  [frame]
  (loop [f frame
         acc #{}]
    (if (map? f)
      (let [acc (cond-> acc
                  (some? (:compiled-version f))
                  (conj (:compiled-version f)))]
        (if-let [parent (:next f)]
          (recur parent acc)
          acc))
      acc)))


(defn- collect-frame-versions
  [frames]
  (reduce (fn [acc frame] (into acc (frame-versions frame)))
          #{}
          frames))


(defn- pinned-compiled-versions
  [vm]
  (let [active-k (:k vm)
        parked-frames (vals (or (:parked vm) {}))
        run-entries (or (:run-queue vm) [])
        wait-entries (or (:wait-set vm) [])]
    (-> #{}
        (cond-> (some? (:active-compiled-version vm))
          (conj (:active-compiled-version vm)))
        (into (frame-versions active-k))
        (into (collect-frame-versions parked-frames))
        (into (collect-frame-versions run-entries))
        (into (collect-frame-versions wait-entries))
        (disj nil))))


(defn- trim-compiled-cache
  [vm]
  (let [compiled (or (:compiled-by-version vm) {})
        limit (max 1 (or (:compiled-cache-limit vm) default-compiled-cache-limit))
        versions (sort (keys compiled))
        keep-newest (set (take-last limit versions))
        keep (into keep-newest (pinned-compiled-versions vm))]
    (assoc vm
           :compiled-by-version
           (reduce-kv (fn [m version artifact]
                        (if (contains? keep version)
                          (assoc m version artifact)
                          m))
                      {}
                      compiled))))


(defn- compile-register-artifact
  [program-root-eid program-datoms program-index]
  (let [{:keys [asm reg-count]}
        (ast-datoms->asm program-datoms
                         {:root-id program-root-eid,
                          :by-entity program-index})]
    (assoc (assemble asm) :reg-count reg-count)))


(defn- cache-compiled-artifact
  [vm version artifact program-index]
  (-> vm
      (assoc :program-index program-index
             :compile-dirty? false
             :active-compiled-version version)
      (update :compiled-by-version (fnil assoc {}) version artifact)
      trim-compiled-cache))


(defn- ensure-compiled-version
  [vm version]
  (if-let [artifact (get-in vm [:compiled-by-version version])]
    [vm artifact]
    (let [program-datoms (vec (or (:program-datoms vm) []))
          program-root-eid (:program-root-eid vm)]
      (when (or (empty? program-datoms) (nil? program-root-eid))
        (throw (ex-info "Canonical program is not loaded"
                        {:program-version version})))
      (let [program-index (or (:program-index vm)
                              (build-program-index program-datoms))
            artifact (compile-register-artifact program-root-eid
                                                program-datoms
                                                program-index)
            vm' (cache-compiled-artifact vm version artifact program-index)]
        [vm' artifact]))))


(defn- maybe-recompile-at-boundary
  "Compile the current canonical program version if dirty.
   Called at explicit dispatch boundaries only."
  [vm]
  (if (and (:compile-dirty? vm)
           (seq (:program-datoms vm))
           (some? (:program-root-eid vm)))
    (let [version (:program-version vm)
          [vm' _artifact] (ensure-compiled-version vm version)]
      vm')
    vm))


(defn append-program-datoms
  "Append datoms to the canonical program stream.
   Optionally update the root eid for the new program version."
  ([vm datoms] (append-program-datoms vm datoms nil))
  ([vm datoms new-root-eid]
   (when-not (some? (:program-root-eid vm))
     (throw (ex-info "append-program-datoms requires a canonical program"
                     {})))
   (let [appended (vec datoms)]
     (if (and (empty? appended) (nil? new-root-eid))
       vm
       (let [version (inc (or (:program-version vm) 0))
             executable? (or (some executable-program-datom? appended)
                             (some? new-root-eid))]
         (-> vm
             (update :program-datoms into appended)
             (assoc :program-version version
                    :program-root-eid (or new-root-eid
                                          (:program-root-eid vm))
                    :compile-dirty? (or (:compile-dirty? vm) executable?)
                    :program-index (if executable?
                                     nil
                                     (:program-index vm)))))))))


(defn- activate-compiled-artifact
  [vm artifact]
  (assoc vm
         :regs (vec (repeat (:reg-count artifact 0) nil))
         :k nil
         :control 0
         :bytecode (:bytecode artifact)
         :pool (:pool artifact)
         :halted false
         :value nil
         :blocked false))


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
  [{:keys [regs k env bytecode pool active-compiled-version]} rd next-control]
  {:control next-control,
   :regs regs,
   :next k,
   :env env,
   :bytecode bytecode,
   :pool pool,
   :compiled-version active-compiled-version,
   :result-reg rd})


(defn- native-park-entry
  "Build wait-set entry for native stream effects."
  [state effect stream-result rd next-control]
  (case (:effect effect)
    :stream/put (merge (snap-frame state rd next-control)
                       {:reason :put,
                        :stream-id (:stream-id stream-result),
                        :datom (:val effect)})
    :stream/next (merge (snap-frame state rd next-control)
                        {:reason :next,
                         :cursor-ref (:cursor-ref stream-result),
                         :stream-id (:stream-id stream-result)})
    nil))


(defn- restore-frame
  "Restore VM state from a snapshot, optionally pushing a result into rd."
  ([state frame]
   (assoc state
          :control (:control frame)
          :regs (:regs frame)
          :k (:next frame)
          :env (:env frame)
          :bytecode (or (:bytecode frame) (:bytecode state))
          :pool (or (:pool frame) (:pool state))
          :active-compiled-version (or (:compiled-version frame)
                                       (:active-compiled-version state))))
  ([state frame value]
   (let [rd (:result-reg frame)]
     (assoc state
            :control (:control frame)
            :regs (if rd (assoc (:regs frame) rd value) (:regs frame))
            :k (:next frame)
            :env (:env frame)
            :bytecode (or (:bytecode frame) (:bytecode state))
            :pool (or (:pool frame) (:pool state))
            :active-compiled-version (or (:compiled-version frame)
                                         (:active-compiled-version state))))))


(defn- handle-native-result
  "Handle result of calling a native fn. Routes effects through handle-effect."
  [{:keys [regs], :as state} result rd next-control]
  (if (module/effect? result)
    (let [effect-opts (case (:effect result)
                        :stream/put {:park-entry-fns
                                     {:stream/put
                                      (fn [s e r]
                                        (native-park-entry s e r rd next-control))}}
                        :stream/next {:park-entry-fns
                                      {:stream/next
                                       (fn [s e r]
                                         (native-park-entry s e r rd next-control))}}
                        {})
          {:keys [state value blocked?]}
          (engine/handle-effect state result effect-opts)]
      (if blocked?
        state
        (assoc state
               :regs (assoc regs rd value)
               :control next-control)))
    (assoc state
           :regs (assoc regs rd result)
           :control next-control)))


;; =============================================================================
;; Register Bytecode VM (numeric opcodes, flat int vector, constant pool)
;; =============================================================================

(declare maybe-recompile-at-boundary)


(defn- reg-vm-step
  "Execute one bytecode instruction. Returns updated state."
  [state]
  (let [op0 (nth (:bytecode state) (:control state) nil)
        state (if (and (:compile-dirty? state)
                       (or (= op0 (vm/opcode-table :call))
                           (= op0 (vm/opcode-table :tailcall))))
                (maybe-recompile-at-boundary state)
                state)
        {:keys [bytecode pool control regs env store k primitives]} state
        op (nth bytecode control nil)]
    (if (nil? op)
      (throw (ex-info "Bytecode ended without return instruction" {:control control}))
      (vm/opcase
        op
        :literal
        (let [rd (nth bytecode (inc control))
              v (nth pool (nth bytecode (+ control 2)))]
          (assoc state
                 :regs (assoc regs rd v)
                 :control (+ control 3)))
        :load-var
        (let [rd (nth bytecode (inc control))
              name (nth pool (nth bytecode (+ control 2)))
              v (engine/resolve-var env store primitives name)]
          (assoc state
                 :regs (assoc regs rd v)
                 :control (+ control 3)))
        :move
        (let [rd (nth bytecode (inc control))
              rs (nth bytecode (+ control 2))]
          (assoc state
                 :regs (assoc regs rd (get-reg regs rs))
                 :control (+ control 3)))
        :lambda
        (let [rd (nth bytecode (inc control))
              params (nth pool (nth bytecode (+ control 2)))
              reg-count (nth bytecode (+ control 3))
              body-addr (nth bytecode (+ control 4))
              closure {:type :closure,
                       :params params,
                       :body-addr body-addr,
                       :reg-count reg-count,
                       :empty-regs (vec (repeat reg-count nil)),
                       :empty-regs-arr (make-empty-regs-array reg-count),
                       :env env,
                       :bytecode bytecode,
                       :pool pool,
                       :compiled-version (:active-compiled-version state)}]
          (assoc state
                 :regs (assoc regs rd closure)
                 :control (+ control 5)))
        :call
        (let [rd (nth bytecode (inc control))
              rf (nth bytecode (+ control 2))
              argc (nth bytecode (+ control 3))
              arg-base (+ control 4)
              fn-val (get-reg regs rf)
              next-control (+ arg-base argc)]
          (cond (fn? fn-val)
                (let [result (invoke-native fn-val regs bytecode arg-base argc)]
                  (handle-native-result state result rd next-control))
                (= :closure (:type fn-val))
                (let [{clo-params :params,
                       body-addr :body-addr,
                       clo-env :env,
                       clo-bytecode :bytecode,
                       clo-pool :pool,
                       clo-version :compiled-version,
                       empty-regs :empty-regs} fn-val
                      next-regs (or empty-regs
                                    (vec (repeat (:reg-count fn-val) nil)))
                      new-frame {:type :call-frame,
                                 :result-reg rd,
                                 :control next-control,
                                 :regs regs,
                                 :env env,
                                 :bytecode bytecode,
                                 :pool pool,
                                 :compiled-version (:active-compiled-version state),
                                 :next k}
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
                         :active-compiled-version (or clo-version
                                                      (:active-compiled-version state))
                         :control body-addr))
                :else (throw (ex-info "Cannot call non-function"
                                      {:fn fn-val}))))
        :tailcall
        (let [rd (nth bytecode (inc control))
              rf (nth bytecode (+ control 2))
              argc (nth bytecode (+ control 3))
              arg-base (+ control 4)
              fn-val (get-reg regs rf)
              next-control (+ arg-base argc)]
          (cond (fn? fn-val)
                (let [result (invoke-native fn-val regs bytecode arg-base argc)]
                  (if (module/effect? result)
                    (case (:effect result)
                      :vm/store-put (assoc state
                                           :store (assoc store
                                                         (:key result) (:val result))
                                           :regs (assoc regs rd (:val result))
                                           :control next-control)
                      (throw (ex-info "Unhandled effect in tailcall"
                                      {:effect result})))
                    (assoc state
                           :regs (assoc regs rd result)
                           :control next-control)))
                (= :closure (:type fn-val))
                (let [{clo-params :params,
                       body-addr :body-addr,
                       clo-env :env,
                       clo-bytecode :bytecode,
                       clo-pool :pool,
                       clo-version :compiled-version,
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
                         :active-compiled-version (or clo-version
                                                      (:active-compiled-version state))
                         :control body-addr))
                :else (throw (ex-info "Cannot call non-function"
                                      {:fn fn-val}))))
        :dao.stream.apply/call
        (let [rd (nth bytecode (inc control))
              op (nth pool (nth bytecode (+ control 2)))
              argc (nth bytecode (+ control 3))
              arg-base (+ control 4)
              args (collect-call-args regs bytecode arg-base argc)
              next-control (+ arg-base argc)
              park-snapshot (snap-frame state rd next-control)
              parked (engine/park-continuation state park-snapshot)
              parked-id (get-in parked [:value :id])

              ;; Register reader-waiter on call-out response stream
              call-out (get-in parked [:store vm/call-out-stream-key])
              cursor-data (get-in parked [:store vm/call-out-cursor-key])
              cursor-pos (:position cursor-data)
              waiter-entry (assoc park-snapshot
                                  :type :dao.stream.apply/resumer
                                  :cursor-ref {:type :cursor-ref
                                               :id vm/call-out-cursor-key}
                                  :reason :next
                                  :stream-id vm/call-out-stream-key)
              _ (when (satisfies? ds/IDaoStreamWaitable call-out)
                  (ds/register-reader-waiter! call-out cursor-pos waiter-entry))

              ;; Emit request to call-in stream
              call-in (get-in parked [:store vm/call-in-stream-key])
              request (dao.stream.apply/request parked-id op args)
              _ (ds/put! call-in request)]
          (assoc (telemetry/emit-snapshot parked :bridge {:bridge-op op})
                 :value :yin/blocked
                 :blocked true
                 :halted false))
        :return
        (let [rs (nth bytecode (inc control))
              result (get-reg regs rs)]
          (if (nil? k)
            (assoc state
                   :halted true
                   :value result)
            (restore-frame state k result)))
        :branch
        (let [rt (nth bytecode (inc control))
              then-addr (nth bytecode (+ control 2))
              else-addr (nth bytecode (+ control 3))
              test-val (get-reg regs rt)]
          (assoc state :control (if test-val then-addr else-addr)))
        :jump
        (let [addr (nth bytecode (inc control))] (assoc state :control addr))
        :gensym
        (let [rd (nth bytecode (inc control))
              prefix (nth pool (nth bytecode (+ control 2)))
              [id s'] (engine/gensym state prefix)]
          (assoc s'
                 :regs (assoc regs rd id)
                 :control (+ control 3)))
        :store-get
        (let [rd (nth bytecode (inc control))
              key (nth pool (nth bytecode (+ control 2)))
              val (get store key)]
          (assoc state
                 :regs (assoc regs rd val)
                 :control (+ control 3)))
        :store-put
        (let [rs (nth bytecode (inc control))
              key (nth pool (nth bytecode (+ control 2)))
              val (get-reg regs rs)]
          (assoc state
                 :store (assoc store key val)
                 :control (+ control 3)))
        :stream-make
        (let [rd (nth bytecode (inc control))
              buf (nth pool (nth bytecode (+ control 2)))
              effect {:effect :stream/make, :capacity buf}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
                 :regs (assoc regs rd value)
                 :control (+ control 3)))
        :stream-put
        (let [rs (nth bytecode (inc control))
              rt (nth bytecode (+ control 2))
              val (get-reg regs rs)
              stream-ref (get-reg regs rt)
              effect {:effect :stream/put, :stream stream-ref, :val val}
              {:keys [state blocked?]}
              (engine/handle-effect
                state
                effect
                {:park-entry-fns {:stream/put
                                  (fn [s e r]
                                    (native-park-entry s e r rs (+ control 3)))}})]
          (if blocked? state (assoc state :control (+ control 3))))
        :stream-cursor
        (let [rd (nth bytecode (inc control))
              rs (nth bytecode (+ control 2))
              stream-ref (get-reg regs rs)
              effect {:effect :stream/cursor, :stream stream-ref}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
                 :regs (assoc regs rd value)
                 :control (+ control 3)))
        :stream-next
        (let [rd (nth bytecode (inc control))
              rs (nth bytecode (+ control 2))
              cursor-ref (get-reg regs rs)
              effect {:effect :stream/next, :cursor cursor-ref}
              {:keys [state value blocked?]}
              (engine/handle-effect
                state
                effect
                {:park-entry-fns {:stream/next
                                  (fn [s e r]
                                    (native-park-entry s e r rd (+ control 3)))}})]
          (if blocked?
            state
            (assoc state
                   :regs (assoc regs rd value)
                   :control (+ control 3))))
        :stream-close
        (let [rd (nth bytecode (inc control))
              rs (nth bytecode (+ control 2))
              stream-ref (get-reg regs rs)
              effect {:effect :stream/close, :stream stream-ref}
              {:keys [state value]} (engine/handle-effect state effect {})]
          (assoc state
                 :regs (assoc regs rd value)
                 :control (+ control 3)))
        :park
        (let [rd (nth bytecode (inc control))]
          (engine/park-continuation state (snap-frame state rd (+ control 2))))
        :resume
        (let [rs (nth bytecode (inc control))
              rt (nth bytecode (+ control 2))
              parked-id (get-reg regs rs)
              resume-val (get-reg regs rt)]
          (engine/resume-continuation state
                                      parked-id
                                      resume-val
                                      (fn [new-state parked rv]
                                        (restore-frame new-state parked rv))))
        :current-cont
        (let [rd (nth bytecode (inc control))]
          (let [cont (assoc (snap-frame state rd (+ control 2))
                            :type :reified-continuation)]
            (assoc state
                   :regs (assoc regs rd cont)
                   :control (+ control 2))))
        (throw (ex-info "Unknown bytecode opcode" {:op op, :control control}))))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- resume-from-run-queue
  "Pop first entry from run-queue and resume it as the active computation.
   Returns updated state or nil if queue is empty."
  [state]
  (engine/resume-from-run-queue state
                                (fn [base entry]
                                  (let [val (:value entry)
                                        val' (if (= :dao.stream.apply/resumer (:type entry))
                                               (:dao.stream.apply/value val)
                                               val)]
                                    (-> (restore-frame base entry val')
                                        maybe-recompile-at-boundary)))))


(defn- reg-vm-run-scheduler
  "Generic scheduler-backed run loop."
  [vm-state]
  (engine/run-loop vm-state
                   engine/active-continuation?
                   (if (telemetry/enabled? vm-state)
                     (fn [state]
                       (telemetry/emit-snapshot (reg-vm-step state) :step))
                     reg-vm-step)
                   resume-from-run-queue))


(declare fast-frame->map)


(defn- materialize-fast-state
  "Materialize loop locals back into a RegisterVM value."
  [vm control regs env store k bytecode pool id-counter]
  (assoc vm
         :control control
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
  [result-reg next-control regs env bytecode pool parent-k]
  [:fast-call result-reg next-control regs env bytecode pool parent-k])


(defn- fast-call-frame?
  [frame]
  (and (vector? frame) (= :fast-call (nth frame 0 nil))))


(defn- fast-frame->map
  "Convert compact fast-loop call frames to map call frames for slow-path fallback."
  [frame]
  (if (fast-call-frame? frame)
    {:type :call-frame,
     :result-reg (nth frame 1),
     :control (nth frame 2),
     :regs (regs->vector (nth frame 3)),
     :env (nth frame 4),
     :bytecode (nth frame 5),
     :pool (nth frame 6),
     :next (fast-frame->map (nth frame 7))}
    frame))


(defn- reg-vm-run-active-continuation
  "Fast register VM run loop that keeps hot execution state in locals.
   Falls back to the generic scheduler loop when stream/control effects appear."
  [vm]
  (loop [control (:control vm)
         regs (regs->array (:regs vm))
         env (:env vm)
         store (:store vm)
         k (:k vm)
         bytecode (:bytecode vm)
         pool (:pool vm)
         id-counter (:id-counter vm)]
    (let [op (nth bytecode control nil)
          fallback #(reg-vm-run-scheduler
                      (materialize-fast-state
                        vm
                        control
                        regs
                        env
                        store
                        k
                        bytecode
                        pool
                        id-counter))]
      (if (nil? op)
        (throw (ex-info "Bytecode ended without return instruction" {:control control}))
        (vm/opcase
          op
          :literal
          (let [rd (nth bytecode (inc control))
                v (nth pool (nth bytecode (+ control 2)))]
            (reg-array-set! regs rd v)
            (recur (+ control 3) regs env store k bytecode pool id-counter))
          :load-var
          (let [rd (nth bytecode (inc control))
                name (nth pool (nth bytecode (+ control 2)))
                v (engine/resolve-var env store (:primitives vm) name)]
            (reg-array-set! regs rd v)
            (recur (+ control 3) regs env store k bytecode pool id-counter))
          :move
          (let [rd (nth bytecode (inc control))
                rs (nth bytecode (+ control 2))]
            (reg-array-set! regs rd (get-reg regs rs))
            (recur (+ control 3) regs env store k bytecode pool id-counter))
          :lambda
          (let [rd (nth bytecode (inc control))
                params (nth pool (nth bytecode (+ control 2)))
                reg-count (nth bytecode (+ control 3))
                body-addr (nth bytecode (+ control 4))
                closure {:type :closure,
                         :params params,
                         :body-addr body-addr,
                         :reg-count reg-count,
                         :empty-regs (vec (repeat reg-count nil)),
                         :empty-regs-arr (make-empty-regs-array reg-count),
                         :env env,
                         :bytecode bytecode,
                         :pool pool,
                         :compiled-version (:active-compiled-version vm)}]
            (reg-array-set! regs rd closure)
            (recur (+ control 5) regs env store k bytecode pool id-counter))
          :call
          (let [rd (nth bytecode (inc control))
                rf (nth bytecode (+ control 2))
                argc (nth bytecode (+ control 3))
                arg-base (+ control 4)
                fn-val (get-reg regs rf)
                next-control (+ arg-base argc)]
            (cond (fn? fn-val)
                  (let [result (invoke-native fn-val regs bytecode arg-base argc)]
                    (if (module/effect? result)
                      (-> (materialize-fast-state
                            vm
                            control
                            regs
                            env
                            store
                            k
                            bytecode
                            pool
                            id-counter)
                          (handle-native-result result rd next-control)
                          reg-vm-run-scheduler)
                      (do
                        (reg-array-set! regs rd result)
                        (recur next-control
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
                                    next-control
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
          (let [rd (nth bytecode (inc control))
                rf (nth bytecode (+ control 2))
                argc (nth bytecode (+ control 3))
                arg-base (+ control 4)
                fn-val (get-reg regs rf)
                next-control (+ arg-base argc)]
            (cond (fn? fn-val)
                  (let [result (invoke-native fn-val regs bytecode arg-base argc)]
                    (if (module/effect? result)
                      (case (:effect result)
                        :vm/store-put
                        (do
                          (reg-array-set! regs rd (:val result))
                          (recur next-control
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
                        (recur next-control
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
                  :else (throw (ex-info "Cannot apply non-function"
                                        {:fn fn-val}))))
          :return
          (let [rs (nth bytecode (inc control))
                result (get-reg regs rs)]
            (cond
              (nil? k)
              (assoc vm
                     :control control
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
                    frame-control (nth k 2)
                    frame-regs (nth k 3)
                    frame-env (nth k 4)
                    frame-bytecode (nth k 5)
                    frame-pool (nth k 6)
                    parent-k (nth k 7)]
                (when (some? rd)
                  (reg-array-set! frame-regs rd result))
                (recur frame-control
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
                (recur (:control k)
                       frame-regs
                       (:env k)
                       store
                       (:next k)
                       bytecode'
                       pool'
                       id-counter))))
          :branch
          (let [rt (nth bytecode (inc control))
                then-addr (nth bytecode (+ control 2))
                else-addr (nth bytecode (+ control 3))
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
          (let [addr (nth bytecode (inc control))]
            (recur addr regs env store k bytecode pool id-counter))
          :gensym
          (let [rd (nth bytecode (inc control))
                prefix (nth pool (nth bytecode (+ control 2)))
                id (keyword (str prefix "-" id-counter))]
            (reg-array-set! regs rd id)
            (recur (+ control 3) regs env store k bytecode pool (inc id-counter)))
          :store-get
          (let [rd (nth bytecode (inc control))
                key (nth pool (nth bytecode (+ control 2)))
                val (get store key)]
            (reg-array-set! regs rd val)
            (recur (+ control 3) regs env store k bytecode pool id-counter))
          :store-put
          (let [rs (nth bytecode (inc control))
                key (nth pool (nth bytecode (+ control 2)))
                val (get-reg regs rs)]
            (recur (+ control 3)
                   regs
                   env
                   (assoc store key val)
                   k
                   bytecode
                   pool
                   id-counter))
          ;; Stream/continuation opcodes must use scheduler for effects
          (fallback))))))


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
  (if-let [artifact (get-in vm [:compiled-by-version
                                (:active-compiled-version vm)])]
    (activate-compiled-artifact vm artifact)
    (assoc vm
           :regs (vec (repeat (count (:regs vm)) nil))
           :k nil
           :control 0
           :halted false
           :value nil
           :blocked false)))


(defn- reg-vm-load-canonical-program
  "Load canonical datom-form program into the VM.
   Runs macro expansion before bytecode compilation if macro-registry is set."
  [^RegisterVM vm {:keys [node datoms]}]
  (let [registry (or (:macro-registry vm) {})
        d (vec datoms)
        {:keys [datoms root-eid]}
        (macro/expand-all d node registry
                          {:invoke-lambda
                           (fn [lambda-eid ctx]
                             (semantic/invoke-macro-lambda lambda-eid ctx d))})
        program-version (inc (or (:program-version vm) 0))
        vm' (assoc vm
                   :program-root-eid root-eid
                   :program-datoms datoms
                   :program-version program-version
                   :program-index nil
                   :compile-dirty? true)
        [compiled-vm artifact] (ensure-compiled-version vm' program-version)]
    (activate-compiled-artifact compiled-vm artifact)))


(defn- reg-vm-load-program
  "Load a program into the Register VM.
   Expects canonical form: {:node root-id :datoms [...]}."
  [^RegisterVM vm program]
  (if (canonical-program? program)
    (reg-vm-load-canonical-program vm program)
    (throw (ex-info "Unsupported register program form"
                    {:expected {:node :datoms},
                     :program program}))))


(defn- reg-vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, normalizes through vm/ast->datoms and loads via the
   canonical {:node :datoms} path.
   When nil, resumes from current state."
  [^RegisterVM vm ast]
  (if ast
    (let [datoms (vec (vm/ast->datoms ast))
          root-id (apply max (map first datoms))]
      (-> vm
          (vm/load-program {:node root-id, :datoms datoms})
          (vm/run)))
    (vm/run vm)))


(defn- reg-vm-run-on-stream
  [vm]
  (engine/run-on-stream vm
                        (:in-stream vm)
                        vm/ingest-program
                        (if (telemetry/enabled? vm)
                          (fn [state]
                            (telemetry/emit-snapshot (reg-vm-step state) :step))
                          reg-vm-step)
                        resume-from-run-queue))


(defmethod vm/ingest-program :register
  [vm program]
  (reg-vm-load-program vm program))


(extend-type RegisterVM
  vm/IVMStep
  (step [vm]
    (telemetry/emit-snapshot
      (engine/step-on-stream vm (:in-stream vm) vm/ingest-program reg-vm-step)
      :step))
  (halted? [vm] (reg-vm-halted? vm))
  (blocked? [vm] (reg-vm-blocked? vm))
  (value [vm] (reg-vm-value vm))
  vm/IVMRun
  (run [vm] (host-ffi/maybe-run vm reg-vm-run-on-stream))
  vm/IVMReset
  (reset [vm] (reg-vm-reset vm))
  vm/IVMEval
  (eval [vm ast] (reg-vm-eval vm ast))
  vm/IVMState
  (control [vm] {:control (:control vm), :bytecode (:bytecode vm), :regs (:regs vm)})
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm]
    (when-let [k-head (:k vm)]
      (loop [k k-head
             acc []]
        (if (nil? k)
          acc
          (recur (:next k) (conj acc k)))))))


(defn create-vm
  "Create a new RegisterVM with optional opts map.
   Accepts {:env map, :primitives map, :macro-registry map, :bridge handlers, :telemetry config}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})
         bridge-state (host-ffi/bridge-from-opts opts)
         in-stream (or (:in-stream opts) (vm/make-ingress-stream))
         base (vm/empty-state {:primitives (:primitives opts)
                               :telemetry (:telemetry opts)
                               :vm-model :register})]
     (-> (map->RegisterVM (merge base
                                 {:in-stream in-stream,
                                  :in-cursor {:position 0},
                                  :regs [],
                                  :k nil,
                                  :env env,
                                  :control 0,
                                  :bytecode nil,
                                  :pool nil,
                                  :compiled-by-version {},
                                  :compiled-cache-limit (or (:compiled-cache-limit opts)
                                                            default-compiled-cache-limit),
                                  :active-compiled-version nil,
                                  :compile-dirty? false,
                                  :macro-registry (or (:macro-registry opts) {}),
                                  :program-root-eid nil,
                                  :program-datoms [],
                                  :program-version 0,
                                  :program-index nil,
                                  :halted false,
                                  :value nil,
                                  :blocked false}
                                 (when bridge-state
                                   {:bridge bridge-state})))
         (telemetry/install :register)
         (telemetry/emit-snapshot :init)))))
