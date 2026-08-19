(ns yin.vm.stack
  "Stack-based bytecode VM for the Yin language.

   A stack machine implementation where:
   - Operands are pushed/popped from an explicit stack
   - Call frames are managed via first-class continuations (:k)
   - The AST as :yin/ datoms (from vm/ast->datoms) is the canonical source.

   Mapping to CESK:
   - Control (C):     :control (integer program counter into :bytecode)
   - Environment (E): :env (lexical bindings)
   - Store (S):       :store (shared heap memory)
   - Kontinuation (K): :k (linked list of frames, which save control/env/operand-stack)

   This namespace provides:
   1. Stack assembly: project :yin/ datoms to symbolic stack instructions
   2. Assembly to numeric bytecode encoding (assemble)
   3. Numeric bytecode VM (step, run via protocols)"
  (:require [dao.stream :as ds]
            [dao.stream.apply :as dao.stream.apply]
            [yin.module :as module]
            #?(:clj [yin.vm :as vm :refer [opcase]]
               :cljs [yin.vm :as vm]
               :cljd [yin.vm :as vm])
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.macro :as macro]
            [yin.vm.semantic :as semantic]
            [yin.vm.telemetry :as telemetry])
  #?(:cljs (:require-macros [yin.vm :refer [opcase]])))


(declare restore-frame)


;; =============================================================================
;; Stack VM
;; =============================================================================


;; =============================================================================
;; VM Records
;; =============================================================================

(defrecord StackVM
  [blocked?  ; true if blocked
   in-stream ; ingress DaoStream carrying canonical datom programs
   in-cursor ; ingress cursor position
   bytecode  ; bytecode vector
   k         ; continuation (call frame stack)
   compiled-by-version ; {program-version -> compiled artifact}
   compiled-cache-limit ; max retained compiled artifacts
   active-compiled-version ; compiled program version for new control
   ;; transfers
   compile-dirty? ; canonical datom stream changed since last compile
   env        ; lexical environment (E in CESK)
   halted?    ; true if execution completed
   id-counter ; unique ID counter
   macro-registry ; {macro-lambda-eid -> (fn [ctx] {:datoms [...] :root-eid
   ;; eid})}
   parked  ; parked continuations
   control ; program counter (C in CESK)
   pool    ; constant pool
   datoms  ; canonical bounded datom stream snapshot
   datom-index ; derived datom index for compilation
   program-root-eid ; root entity id of canonical program
   program-version ; monotonic program version for append/recompile
   ;; lifecycle
   primitives ; primitive operations
   ready-queue ; vector of runnable continuations
   stack    ; operand stack
   store    ; heap memory (S in CESK)
   value    ; final result value
   wait-set ; vector of parked continuations waiting on streams
   telemetry ; optional telemetry config
   telemetry-step ; telemetry snapshot counter
   telemetry-t ; telemetry transaction counter
   vm-model    ; telemetry model keyword
   ])


;; =============================================================================
;; Datoms -> Stack Assembly
;; =============================================================================

(defn ast-datoms->asm
  "Project :yin/ datoms to symbolic stack assembly.

   Instructions:
     [:push v]           - push literal value v
     [:load name]        - push value of variable name
     [:call argc]        - pop function and argc args, call, push result
     [:tailcall argc]    - pop function and argc args, tail-call closure
     [:lambda params label] - push closure (captures env, body follows)
     [:branch label]     - pop condition, if true jump to label
     [:jump label]       - unconditional jump to label
     [:return]           - return top of stack
     [:label name]       - pseudo-instruction for jump targets
     [:gensym prefix]    - push generated unique ID
     [:store-get key]    - push value from store
     [:store-put key]    - pop value, write to store, push value back
     [:stream-make buf]  - push new stream ref
     [:stream-put]       - pop val and stream-ref, put val, push val
     [:stream-cursor]    - pop stream-ref, push cursor-ref
     [:stream-next]      - pop cursor-ref, push next value (may block)
     [:stream-close]     - pop stream-ref, close it, push nil
     [:dao.stream.apply/call op argc] - park, enqueue dao.stream.apply request, resume with result
     [:park]             - park current continuation
     [:resume]           - pop val and parked-id, resume parked cont
     [:current-cont]     - push reified continuation

   Optional opts:
   - :root-id explicit root entity id
   - :by-entity precomputed entity index {eid [datom ...]}."
  ([ast-as-datoms] (ast-datoms->asm ast-as-datoms {}))
  ([ast-as-datoms {:keys [root-id by-entity]}]
   (let [{:keys [get-attr root-id]} (vm/index-datoms ast-as-datoms
                                                     {:root-id root-id,
                                                      :by-entity by-entity})]
     (letfn
       [(compile-node
          ([state e] (compile-node state e false))
          ([state e tail?]
           (case (get-attr e :yin/type)
             :literal
             (update state :instructions conj [:push (get-attr e :yin/value)])
             :variable
             (update state :instructions conj [:load (get-attr e :yin/name)])
             :lambda (let [params (get-attr e :yin/params)
                           body-node (get-attr e :yin/body)
                           skip-label (keyword (str "after-lambda-"
                                                    (inc (:label-counter
                                                           state))))
                           state1 (-> state
                                      (update :label-counter inc)
                                      (update :instructions
                                              conj
                                              [:lambda params skip-label]))
                           state2 (compile-node state1 body-node true)
                           state3 (update state2 :instructions conj [:return])]
                       (update state3 :instructions conj [:label skip-label]))
             :application
             (let [op-node (get-attr e :yin/operator)
                   operand-nodes (get-attr e :yin/operands)
                   state1 (compile-node state op-node)
                   state2 (reduce compile-node state1 operand-nodes)]
               (update state2
                       :instructions
                       conj
                       [(if tail? :tailcall :call) (count operand-nodes)]))
             :dao.stream.apply/call
             (let [operand-nodes (or (get-attr e :yin/operands) [])
                   op (get-attr e :yin/op)
                   state1 (reduce compile-node state operand-nodes)]
               (update state1
                       :instructions
                       conj
                       [:dao.stream.apply/call op (count operand-nodes)]))
             :if (let [test-node (get-attr e :yin/test)
                       cons-node (get-attr e :yin/consequent)
                       alt-node (get-attr e :yin/alternate)
                       cons-label (keyword (str "then-"
                                                (inc (:label-counter state))))
                       end-label (keyword (str "end-"
                                               (+ 2 (:label-counter state))))
                       state1 (-> state
                                  (update :label-counter + 2)
                                  (compile-node test-node))
                       state2 (->
                                state1
                                (update :instructions conj [:branch cons-label])
                                (compile-node alt-node tail?)
                                (update :instructions conj [:jump end-label])
                                (update :instructions conj [:label cons-label])
                                (compile-node cons-node tail?))]
                   (update state2 :instructions conj [:label end-label]))
             ;; VM primitives
             :vm/gensym (update state
                                :instructions
                                conj
                                [:gensym (or (get-attr e :yin/prefix) "id")])
             :vm/store-get (update state
                                   :instructions
                                   conj
                                   [:store-get (get-attr e :yin/key)])
             :vm/store-put (let [val (get-attr e :yin/value)]
                             (-> state
                                 (update :instructions conj [:push val])
                                 (update :instructions
                                         conj
                                         [:store-put (get-attr e :yin/key)])))
             ;; Stream operations
             :stream/make (update state
                                  :instructions
                                  conj
                                  [:stream-make
                                   (or (get-attr e :yin/buffer) 1024)])
             :stream/put (let [target-node (get-attr e :yin/target)
                               val-node (get-attr e :yin/val-node)
                               state1 (compile-node state val-node)
                               state2 (compile-node state1 target-node)]
                           (update state2 :instructions conj [:stream-put]))
             :stream/cursor
             (let [source-node (get-attr e :yin/source)
                   state1 (compile-node state source-node)]
               (update state1 :instructions conj [:stream-cursor]))
             :stream/next (let [source-node (get-attr e :yin/source)
                                state1 (compile-node state source-node)]
                            (update state1 :instructions conj [:stream-next]))
             :stream/close (let [source-node (get-attr e :yin/source)
                                 state1 (compile-node state source-node)]
                             (update state1 :instructions conj [:stream-close]))
             ;; Continuation primitives
             :vm/park (update state :instructions conj [:park])
             :vm/resume
             (let [state1 (update state
                                  :instructions
                                  conj
                                  [:push (get-attr e :yin/parked-id)])
                   state2 (compile-node state1 (get-attr e :yin/val-node))]
               (update state2 :instructions conj [:resume]))
             :vm/current-continuation
             (update state :instructions conj [:current-cont])
             (throw (ex-info "Unknown node type in stack assembly compilation"
                             {:type (get-attr e :yin/type), :entity e})))))]
       (:instructions
         (compile-node {:instructions [], :label-counter 0} root-id true))))))


(defn assemble
  "Convert symbolic stack assembly to numeric bytecode.

   Returns {:bytecode [int...] :pool [value...] :source-map {byte-offset instr-index}}"
  [instructions]
  (let [instr-size (fn [instr]
                     (case (first instr)
                       :push 2
                       :load 2
                       :lambda 4 ; op + params_idx + 2 byte len
                       :call 2
                       :tailcall 2
                       :branch 3
                       :jump 3
                       :return 1
                       :label 0
                       :gensym 2
                       :store-get 2
                       :store-put 2
                       :stream-make 2
                       :stream-put 1
                       :stream-cursor 1
                       :stream-next 1
                       :stream-close 1
                       :dao.stream.apply/call 3
                       :park 1
                       :resume 1
                       :current-cont 1
                       0))
        pass1 (reduce (fn [state instr]
                        (if (= :label (first instr))
                          (assoc-in state
                                    [:label-offsets (second instr)]
                                    (:current-offset state))
                          (update state :current-offset + (instr-size instr))))
                      {:label-offsets {}, :current-offset 0}
                      instructions)
        label-offsets (:label-offsets pass1)]
    (letfn [(add-constant
              [state val]
              (if-let [idx (get (:pool-index state) val)]
                [state idx]
                (let [idx (count (:pool state))]
                  [(-> state
                       (update :pool conj val)
                       (update :pool-index assoc val idx)) idx])))
            (emit-byte
              [state b]
              (-> state
                  (update :bytes conj b)
                  (update :emit-offset inc)))
            (emit-short
              [state s]
              (-> state
                  (update :bytes conj (bit-shift-right (bit-and s 0xFF00) 8))
                  (update :bytes conj (bit-and s 0xFF))
                  (update :emit-offset + 2)))]
      (let [final-state
            (reduce
              (fn [state [idx instr]]
                (let [op (first instr)
                      arg1 (second instr)
                      arg2 (nth instr 2 nil)
                      start-offset (:emit-offset state)
                      state (if (not= :label op)
                              (assoc-in state [:source-map start-offset] idx)
                              state)]
                  (case op
                    :push (let [[state' cidx] (add-constant state arg1)]
                            (-> state'
                                (emit-byte (vm/opcode-table :literal))
                                (emit-byte cidx)))
                    :load (let [[state' cidx] (add-constant state arg1)]
                            (-> state'
                                (emit-byte (vm/opcode-table :load-var))
                                (emit-byte cidx)))
                    :lambda (let [[state' pidx] (add-constant state arg1)
                                  target-label arg2
                                  target-offset (get label-offsets
                                                     target-label)
                                  body-len (- target-offset
                                              (+ (:emit-offset state') 4))]
                              (-> state'
                                  (emit-byte (vm/opcode-table :lambda))
                                  (emit-byte pidx)
                                  (emit-short body-len)))
                    :call (-> state
                              (emit-byte (vm/opcode-table :call))
                              (emit-byte arg1))
                    :tailcall (-> state
                                  (emit-byte (vm/opcode-table :tailcall))
                                  (emit-byte arg1))
                    :branch (let [target-label arg1
                                  target-offset (get label-offsets
                                                     target-label)
                                  rel-offset (- target-offset
                                                (+ (:emit-offset state) 3))]
                              (-> state
                                  (emit-byte (vm/opcode-table :branch))
                                  (emit-short rel-offset)))
                    :jump (let [target-label arg1
                                target-offset (get label-offsets target-label)
                                rel-offset (- target-offset
                                              (+ (:emit-offset state) 3))]
                            (-> state
                                (emit-byte (vm/opcode-table :jump))
                                (emit-short rel-offset)))
                    :return (emit-byte state (vm/opcode-table :return))
                    :label state ; No code emitted
                    :gensym (let [[state' cidx] (add-constant state arg1)]
                              (-> state'
                                  (emit-byte (vm/opcode-table :gensym))
                                  (emit-byte cidx)))
                    :store-get (let [[state' cidx] (add-constant state arg1)]
                                 (-> state'
                                     (emit-byte (vm/opcode-table :store-get))
                                     (emit-byte cidx)))
                    :store-put (let [[state' cidx] (add-constant state arg1)]
                                 (-> state'
                                     (emit-byte (vm/opcode-table :store-put))
                                     (emit-byte cidx)))
                    :stream-make
                    (let [[state' cidx] (add-constant state arg1)]
                      (-> state'
                          (emit-byte (vm/opcode-table :stream-make))
                          (emit-byte cidx)))
                    :stream-put (emit-byte state
                                           (vm/opcode-table :stream-put))
                    :stream-cursor
                    (emit-byte state (vm/opcode-table :stream-cursor))
                    :stream-next (emit-byte state
                                            (vm/opcode-table :stream-next))
                    :stream-close (emit-byte state
                                             (vm/opcode-table :stream-close))
                    :dao.stream.apply/call
                    (let [[state' cidx] (add-constant state arg1)]
                      (-> state'
                          (emit-byte (vm/opcode-table
                                       :dao.stream.apply/call))
                          (emit-byte cidx)
                          (emit-byte arg2)))
                    :park (emit-byte state (vm/opcode-table :park))
                    :resume (emit-byte state (vm/opcode-table :resume))
                    :current-cont
                    (emit-byte state (vm/opcode-table :current-cont)))))
              {:pool [],
               :pool-index {},
               :bytes [],
               :source-map {},
               :emit-offset 0}
              (map-indexed vector instructions))]
        {:bytecode (:bytes final-state),
         :pool (:pool final-state),
         :source-map (:source-map final-state)}))))


(defn- compile-stack-artifact
  [program-root-eid program-datoms program-index]
  (assemble (ast-datoms->asm program-datoms
                             {:root-id program-root-eid,
                              :by-entity program-index})))


(defn maybe-recompile-at-boundary
  "Compile the current canonical program version if dirty.
   Called at explicit dispatch boundaries only."
  [vm]
  (let [[vm' _artifact]
        (engine/maybe-recompile-at-boundary vm compile-stack-artifact)]
    vm'))


(def append-program-datoms engine/append-program-datoms)


(defn- activate-compiled-artifact
  [vm artifact]
  (assoc vm
         :control 0
         :bytecode (:bytecode artifact)
         :pool (:pool artifact)
         :stack []
         :k nil
         :halted? false
         :value nil
         :blocked? false))


(defn- fetch-short-unsigned
  [bytes pc]
  (let [hi (nth bytes pc)
        lo (nth bytes (inc pc))]
    (bit-or (bit-shift-left hi 8) lo)))


(defn- fetch-short-signed
  [bytes pc]
  (let [u (fetch-short-unsigned bytes pc)] (if (>= u 32768) (- u 65536) u)))


(def ^:private initial-stack-capacity 32)


(defn- make-stack-array
  [n]
  #?(:clj (object-array (int n))
     :cljs (let [arr (js/Array. n)]
             (loop [i 0]
               (if (< i n) (do (aset arr i nil) (recur (inc i))) arr)))
     :cljd (object-array (int n))))


(defn- stack-array-length
  [arr]
  #?(:clj (alength ^objects arr)
     :cljs (.-length arr)
     :cljd (alength arr)))


(defn- stack-array-get
  [arr idx]
  #?(:clj (aget ^objects arr (int idx))
     :cljs (aget arr idx)
     :cljd (aget arr (int idx))))


(defn- stack-array-set!
  [arr idx val]
  #?(:clj (aset ^objects arr (int idx) val)
     :cljs (aset arr idx val)
     :cljd (aset arr (int idx) val)))


(defn- stack-array-grow
  [arr min-capacity]
  (let [old-len (stack-array-length arr)
        new-len (loop [len (max 1 old-len)]
                  (if (< len min-capacity) (recur (* 2 len)) len))
        new-arr (make-stack-array new-len)]
    #?(:clj (System/arraycopy arr 0 new-arr 0 old-len)
       :cljs
       (loop [i 0]
         (when (< i old-len) (aset new-arr i (aget arr i)) (recur (inc i))))
       :cljd (dotimes [i old-len] (aset new-arr i (aget arr i))))
    new-arr))


(defn- stack-array->vector
  [arr sp]
  (if (neg? sp)
    []
    (loop [i 0
           out (transient [])]
      (if (<= i sp)
        (recur (inc i) (conj! out (stack-array-get arr i)))
        (persistent! out)))))


(defn- stack-vector->array+sp
  [stack]
  (let [n (count stack)
        cap (max initial-stack-capacity n)
        arr (make-stack-array cap)]
    (loop [i 0]
      (when (< i n) (stack-array-set! arr i (nth stack i)) (recur (inc i))))
    [arr (dec n)]))


(defn- stack-push
  [arr sp v]
  (let [next-sp (inc sp)
        arr' (if (< next-sp (stack-array-length arr))
               arr
               (stack-array-grow arr (inc next-sp)))]
    (stack-array-set! arr' next-sp v)
    [arr' next-sp]))


(defn- stack-args->vector
  [stack-arr fn-pos argc]
  (loop [i 0
         out (transient [])]
    (if (< i argc)
      (recur (inc i) (conj! out (stack-array-get stack-arr (+ fn-pos 1 i))))
      (persistent! out))))


(defn- invoke-native-fast
  [fn-val stack-arr fn-pos argc]
  (case argc
    0 (fn-val)
    1 (fn-val (stack-array-get stack-arr (inc fn-pos)))
    2 (fn-val (stack-array-get stack-arr (inc fn-pos))
              (stack-array-get stack-arr (+ fn-pos 2)))
    3 (fn-val (stack-array-get stack-arr (inc fn-pos))
              (stack-array-get stack-arr (+ fn-pos 2))
              (stack-array-get stack-arr (+ fn-pos 3)))
    4 (fn-val (stack-array-get stack-arr (inc fn-pos))
              (stack-array-get stack-arr (+ fn-pos 2))
              (stack-array-get stack-arr (+ fn-pos 3))
              (stack-array-get stack-arr (+ fn-pos 4)))
    (apply fn-val (stack-args->vector stack-arr fn-pos argc))))


(defn- snap-frame
  "Snapshot current VM frame for parking or reification."
  [{:keys [bytecode env k pool active-compiled-version]} stack control]
  {:control control,
   :bytecode bytecode,
   :stack stack,
   :env env,
   :next k,
   :pool pool,
   :compiled-version active-compiled-version})


(defn- handle-native-result
  "Handle result of calling a native fn. Routes effects through handle-effect."
  [state result stack-rest next-control park-entry-fns]
  (if (module/effect? result)
    (let [{:keys [state value blocked?]}
          (engine/handle-effect state
                                result
                                {:park-entry-fns park-entry-fns,
                                 :restore-fn
                                 (fn [base entry val]
                                   (restore-frame base entry val))})]
      (if blocked?
        state
        (assoc state
               :stack (conj stack-rest value)
               :control next-control)))
    (assoc state
           :stack (conj stack-rest result)
           :control next-control)))


(defn- restore-frame
  "Restore VM state from a snapshot, optionally pushing a return value."
  ([state frame]
   (assoc state
          :control (:control frame)
          :bytecode (:bytecode frame)
          :stack (:stack frame)
          :env (:env frame)
          :k (:next frame)
          :pool (or (:pool frame) (:pool state))
          :active-compiled-version (or (:compiled-version frame)
                                       (:active-compiled-version state))))
  ([state frame value]
   (assoc state
          :control (:control frame)
          :bytecode (:bytecode frame)
          :stack (conj (:stack frame) value)
          :env (:env frame)
          :k (:next frame)
          :pool (or (:pool frame) (:pool state))
          :active-compiled-version (or (:compiled-version frame)
                                       (:active-compiled-version state)))))


(defn- apply-op
  [state argc tail?]
  (let [{:keys [control _bytecode stack _env k pool _store _id-counter]} state
        n (count stack)
        args (subvec stack (- n argc) n)
        fn-val (nth stack (- n argc 1))
        stack-rest (subvec stack 0 (- n argc 1))
        next-control (+ control 2)]
    (cond (fn? fn-val)
          (let [res (apply fn-val args)]
            (handle-native-result
              state
              res
              stack-rest
              next-control
              {:stream/put (fn [_s _e r]
                             (merge (snap-frame state stack-rest next-control)
                                    {:reason :put,
                                     :stream-id (:stream-id r),
                                     :datom (:val res)})),
               :stream/next (fn [_s _e r]
                              (merge
                                (snap-frame state stack-rest next-control)
                                {:reason :next,
                                 :cursor-ref (:cursor-ref r),
                                 :stream-id (:stream-id r)}))}))
          (= :closure (:type fn-val))
          (let [{clo-params :params,
                 clo-bytecode :bytecode,
                 clo-body :body-bytes,
                 clo-body-start :body-start,
                 clo-env :env,
                 clo-pool :pool,
                 clo-version :compiled-version}
                fn-val
                target-bytecode (or clo-bytecode clo-body)
                target-control (or clo-body-start 0)
                new-env (merge clo-env (zipmap clo-params args))
                frame (snap-frame state stack-rest next-control)]
            (if tail?
              ;; TCO: reuse current frame by
              ;; jumping directly to callee body.
              (assoc state
                     :control target-control
                     :bytecode target-bytecode
                     :stack []
                     :env new-env
                     :pool (or clo-pool pool)
                     :active-compiled-version (or clo-version
                                                  (:active-compiled-version state))
                     :k k)
              (assoc state
                     :control target-control
                     :bytecode target-bytecode
                     :stack []
                     :env new-env
                     :pool (or clo-pool pool)
                     :active-compiled-version (or clo-version
                                                  (:active-compiled-version state))
                     :k frame)))
          :else (throw (ex-info "Cannot apply non-function" {:fn fn-val})))))


(defn- stack-step
  "Execute one stack VM instruction. Returns updated state.
   When execution completes, :halted? is true and :value contains the result."
  [state]
  (let [op0 (nth (:bytecode state) (:control state) nil)
        state (if (and (:compile-dirty? state)
                       (or (= op0 (vm/opcode-table :call))
                           (= op0 (vm/opcode-table :tailcall))))
                (maybe-recompile-at-boundary state)
                state)
        {:keys [control bytecode stack env k pool store primitives _id-counter]}
        state]
    (if (>= control (count bytecode))
      ;; End of bytes: return top of stack or pop frame
      (let [result (peek stack)]
        (if (nil? k)
          (assoc state
                 :halted? true
                 :value result)
          (restore-frame state k result)))
      (let [op (nth bytecode control)]
        (vm/opcase
          op
          :literal
          (let [val-idx (nth bytecode (inc control))
                val (nth pool val-idx)]
            (assoc state
                   :control (+ control 2)
                   :stack (conj stack val)))
          :load-var
          (let [sym-idx (nth bytecode (inc control))
                sym (nth pool sym-idx)
                val (engine/resolve-var env store primitives sym)]
            (assoc state
                   :control (+ control 2)
                   :stack (conj stack val)))
          :lambda
          (let [params-idx (nth bytecode (inc control))
                params (nth pool params-idx)
                body-len (fetch-short-unsigned bytecode (+ control 2))
                body-start (+ control 4)
                body-end (+ body-start body-len)
                closure {:type :closure,
                         :params params,
                         :body-start body-start,
                         :body-end body-end,
                         :bytecode bytecode,
                         :env env,
                         :pool pool,
                         :compiled-version (:active-compiled-version state)}]
            (assoc state
                   :control (+ control 4 body-len)
                   :stack (conj stack closure)))
          :call
          (let [argc (nth bytecode (inc control))] (apply-op state argc false))
          :tailcall
          (let [argc (nth bytecode (inc control))] (apply-op state argc true))
          :branch
          (let [offset (fetch-short-signed bytecode (inc control))
                condition (peek stack)
                new-stack (pop stack)]
            (assoc state
                   :control (if condition (+ control 3 offset) (+ control 3))
                   :stack new-stack))
          :jump
          (let [offset (fetch-short-signed bytecode (inc control))]
            (assoc state :control (+ control 3 offset)))
          :return
          (let [result (peek stack)]
            (if (nil? k)
              (assoc state
                     :halted? true
                     :value result)
              (restore-frame state k result)))
          :gensym
          (let [prefix-idx (nth bytecode (inc control))
                prefix (nth pool prefix-idx)
                [id s'] (engine/gensym state prefix)]
            (assoc s'
                   :control (+ control 2)
                   :stack (conj stack id)))
          :store-get
          (let [key-idx (nth bytecode (inc control))
                key (nth pool key-idx)
                val (get store key)]
            (assoc state
                   :control (+ control 2)
                   :stack (conj stack val)))
          :store-put
          (let [key-idx (nth bytecode (inc control))
                key (nth pool key-idx)
                val (peek stack)
                new-store (assoc store key val)]
            (assoc state
                   :control (+ control 2)
                   :store new-store))
          :stream-make
          (let [buf-idx (nth bytecode (inc control))
                buf (nth pool buf-idx)
                effect {:effect :stream/make, :capacity buf}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (assoc state
                   :control (+ control 2)
                   :stack (conj stack value)))
          :stream-put ; OP_STREAM_PUT - pop stream-ref, pop val
          (let [stream-ref (peek stack)
                stack1 (pop stack)
                val (peek stack1)
                stack-rest (pop stack1)
                effect {:effect :stream/put, :stream stream-ref, :val val}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns
                   {:stream/put (fn [_s _e r]
                                  (merge (snap-frame state
                                                     (conj stack-rest val)
                                                     (+ control 1))
                                         {:reason :put,
                                          :stream-id (:stream-id r),
                                          :datom val}))}})]
            (if blocked?
              state
              (assoc state
                     :control (+ control 1)
                     :stack (conj stack-rest value))))
          :stream-cursor ; OP_STREAM_CURSOR - pop stream-ref
          (let [stream-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/cursor, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (assoc state
                   :control (+ control 1)
                   :stack (conj stack-rest value)))
          :stream-next ; OP_STREAM_NEXT - pop cursor-ref
          (let [cursor-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/next, :cursor cursor-ref}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  state
                  effect
                  {:park-entry-fns
                   {:stream/next
                    (fn [_s _e r]
                      (merge (snap-frame state stack-rest (+ control 1))
                             {:reason :next,
                              :cursor-ref (:cursor-ref r),
                              :stream-id (:stream-id r)}))}})]
            (if blocked?
              state
              (assoc state
                     :control (+ control 1)
                     :stack (conj stack-rest value))))
          :stream-close ; OP_STREAM_CLOSE - pop stream-ref
          (let [stream-ref (peek stack)
                stack-rest (pop stack)
                effect {:effect :stream/close, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect state effect {})]
            (assoc state
                   :control (+ control 1)
                   :stack (conj stack-rest value)))
          :dao.stream.apply/call
          (let [op-idx (nth bytecode (inc control))
                argc (nth bytecode (+ control 2))
                op-name (nth pool op-idx)
                n (count stack)
                args (subvec stack (- n argc) n)
                stack-rest (subvec stack 0 (- n argc))
                next-control (+ control 3)
                park-snapshot (snap-frame state stack-rest next-control)
                parked (engine/park-continuation state park-snapshot)
                parked-id (get-in parked [:value :id])
                ;; Register reader-waiter on call-out response stream
                call-out (get-in parked [:store vm/call-out-stream-key])
                cursor-data (get-in parked [:store vm/call-out-cursor-key])
                cursor-pos (:position cursor-data)
                waiter-entry (assoc park-snapshot
                                    :type :dao.stream.apply/resumer
                                    :cursor-ref {:type :cursor-ref,
                                                 :id vm/call-out-cursor-key}
                                    :reason :next
                                    :stream-id vm/call-out-stream-key)
                _ (when (satisfies? ds/IDaoStreamWaitable call-out)
                    (ds/register-reader-waiter! call-out
                                                cursor-pos
                                                waiter-entry))
                ;; Emit request to call-in stream
                call-in (get-in parked [:store vm/call-in-stream-key])
                request (dao.stream.apply/request parked-id op-name args)
                _ (ds/append! call-in request)]
            (assoc (telemetry/emit-snapshot parked :bridge {:bridge-op op-name})
                   :value :yin/blocked
                   :blocked? true
                   :halted? false))
          :park
          (engine/park-continuation state (snap-frame state stack control))
          :resume ; OP_RESUME - pop val, pop parked-id
          (let [resume-val (peek stack)
                stack1 (pop stack)
                parked-id (peek stack1)]
            (engine/resume-continuation
              state
              parked-id
              resume-val
              (fn [new-state parked rv]
                (let [resumed (restore-frame new-state parked rv)]
                  (assoc resumed :control (inc (:control parked)))))))
          :current-cont
          (let [cont (assoc (snap-frame state stack control)
                            :type :reified-continuation)]
            (assoc state
                   :control (+ control 1)
                   :stack (conj stack cont)))
          (throw (ex-info "Unknown Opcode" {:op op, :control control})))))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- stack-vm-restore
  ([base entry] (stack-vm-restore base entry (:value entry)))
  ([base entry val]
   (let [val' (if (= :dao.stream.apply/resumer (:type entry))
                (:dao.stream.apply/value val)
                val)]
     (-> (restore-frame base entry val')
         maybe-recompile-at-boundary))))


(defn- resume-from-run-queue
  "Pop first entry from run-queue and resume it as the active computation.
   Returns updated state or nil if queue is empty."
  [state]
  (engine/resume-from-run-queue state stack-vm-restore))


(defn- stack-vm-run-scheduler
  "Generic scheduler-backed run loop."
  [vm-state]
  (engine/run-loop vm-state
                   engine/active-continuation?
                   (if (telemetry/enabled? vm-state)
                     (fn [state]
                       (telemetry/emit-snapshot (stack-step state) :step))
                     stack-step)
                   resume-from-run-queue
                   stack-vm-restore))


(defn- materialize-fast-state
  "Materialize fast-loop locals back into a StackVM value."
  [vm control bytecode pool stack-arr sp env k store id-counter]
  (assoc vm
         :control control
         :bytecode bytecode
         :pool pool
         :stack (stack-array->vector stack-arr sp)
         :env env
         :k k
         :store store
         :id-counter id-counter
         :halted? false
         :blocked? false))


(defn- halt-fast-state
  [vm control bytecode pool stack-arr sp env k store id-counter result]
  (assoc vm
         :control control
         :bytecode bytecode
         :pool pool
         :stack (stack-array->vector stack-arr sp)
         :env env
         :k k
         :store store
         :id-counter id-counter
         :halted? true
         :blocked? false
         :value result))


(defn- stack-vm-run-active-continuation
  "Fast stack VM run loop with mutable stack storage.
   Falls back to the generic scheduler loop for stream/control opcodes."
  [vm]
  (let [[stack-arr0 sp0] (stack-vector->array+sp (:stack vm))
        primitives (:primitives vm)]
    (loop [control (:control vm)
           bytecode (:bytecode vm)
           pool (:pool vm)
           stack-arr stack-arr0
           sp sp0
           env (:env vm)
           k (:k vm)
           store (:store vm)
           id-counter (:id-counter vm)]
      (let [fallback #(stack-vm-run-scheduler (materialize-fast-state
                                                vm
                                                control
                                                bytecode
                                                pool
                                                stack-arr
                                                sp
                                                env
                                                k
                                                store
                                                id-counter))]
        (if (>= control (count bytecode))
          (let [result (when-not (neg? sp) (stack-array-get stack-arr sp))]
            (if (nil? k)
              (halt-fast-state vm
                               control
                               bytecode
                               pool
                               stack-arr
                               sp
                               env
                               k
                               store
                               id-counter
                               result)
              (let [frame k
                    [restored-arr restored-sp0] (stack-vector->array+sp
                                                  (:stack frame))
                    [restored-arr restored-sp]
                    (stack-push restored-arr restored-sp0 result)]
                (recur (:control frame)
                       (:bytecode frame)
                       (or (:pool frame) pool)
                       restored-arr
                       restored-sp
                       (:env frame)
                       (:next frame)
                       store
                       id-counter))))
          (let [op (nth bytecode control)]
            (vm/opcase
              op
              :literal
              (let [val-idx (nth bytecode (inc control))
                    val (nth pool val-idx)
                    [next-arr next-sp] (stack-push stack-arr sp val)]
                (recur (+ control 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       k
                       store
                       id-counter))
              :load-var
              (let [sym-idx (nth bytecode (inc control))
                    sym (nth pool sym-idx)
                    val (engine/resolve-var env store primitives sym)
                    [next-arr next-sp] (stack-push stack-arr sp val)]
                (recur (+ control 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       k
                       store
                       id-counter))
              :lambda
              (let [params-idx (nth bytecode (inc control))
                    params (nth pool params-idx)
                    body-len (fetch-short-unsigned bytecode (+ control 2))
                    body-start (+ control 4)
                    body-end (+ body-start body-len)
                    closure {:type :closure,
                             :params params,
                             :body-start body-start,
                             :body-end body-end,
                             :bytecode bytecode,
                             :env env,
                             :pool pool}
                    [next-arr next-sp] (stack-push stack-arr sp closure)]
                (recur (+ control 4 body-len)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       k
                       store
                       id-counter))
              :call
              (let [argc (nth bytecode (inc control))
                    fn-pos (- sp argc)
                    fn-val (stack-array-get stack-arr fn-pos)
                    stack-rest-sp (dec fn-pos)
                    next-control (+ control 2)]
                (cond
                  (fn? fn-val)
                  (let [result
                        (invoke-native-fast fn-val stack-arr fn-pos argc)]
                    (if (module/effect? result)
                      (let [state' (materialize-fast-state
                                     vm
                                     control
                                     bytecode
                                     pool
                                     stack-arr
                                     sp
                                     env
                                     k
                                     store
                                     id-counter)
                            stack-rest (stack-array->vector stack-arr
                                                            stack-rest-sp)]
                        (->
                          (handle-native-result
                            state'
                            result
                            stack-rest
                            next-control
                            {:stream/put (fn [_s _e r]
                                           (merge (snap-frame state'
                                                              stack-rest
                                                              next-control)
                                                  {:reason :put,
                                                   :stream-id (:stream-id r),
                                                   :datom (:val result)})),
                             :stream/next
                             (fn [_s _e r]
                               (merge
                                 (snap-frame state' stack-rest next-control)
                                 {:reason :next,
                                  :cursor-ref (:cursor-ref r),
                                  :stream-id (:stream-id r)}))})
                          stack-vm-run-scheduler))
                      (let [arr1 stack-arr
                            sp1 stack-rest-sp
                            [next-arr next-sp] (stack-push arr1 sp1 result)]
                        (recur next-control
                               bytecode
                               pool
                               next-arr
                               next-sp
                               env
                               k
                               store
                               id-counter))))
                  (= :closure (:type fn-val))
                  (let [{clo-params :params,
                         clo-bytecode :bytecode,
                         clo-body :body-bytes,
                         clo-body-start :body-start,
                         clo-env :env,
                         clo-pool :pool}
                        fn-val
                        target-bytecode (or clo-bytecode clo-body)
                        target-control (or clo-body-start 0)
                        new-env (merge clo-env
                                       (zipmap clo-params
                                               (stack-args->vector stack-arr
                                                                   fn-pos
                                                                   argc)))
                        frame
                        (snap-frame
                          {:bytecode bytecode, :env env, :k k, :pool pool}
                          (stack-array->vector stack-arr stack-rest-sp)
                          next-control)]
                    (recur target-control
                           target-bytecode
                           (or clo-pool pool)
                           stack-arr
                           -1
                           new-env
                           frame
                           store
                           id-counter))
                  :else (throw (ex-info "Cannot apply non-function"
                                        {:fn fn-val}))))
              :tailcall
              (let [argc (nth bytecode (inc control))
                    fn-pos (- sp argc)
                    fn-val (stack-array-get stack-arr fn-pos)
                    stack-rest-sp (dec fn-pos)
                    next-control (+ control 2)]
                (cond
                  (fn? fn-val)
                  (let [result
                        (invoke-native-fast fn-val stack-arr fn-pos argc)]
                    (if (module/effect? result)
                      (let [state' (materialize-fast-state
                                     vm
                                     control
                                     bytecode
                                     pool
                                     stack-arr
                                     sp
                                     env
                                     k
                                     store
                                     id-counter)
                            stack-rest (stack-array->vector stack-arr
                                                            stack-rest-sp)]
                        (->
                          (handle-native-result
                            state'
                            result
                            stack-rest
                            next-control
                            {:stream/put (fn [_s _e r]
                                           (merge (snap-frame state'
                                                              stack-rest
                                                              next-control)
                                                  {:reason :put,
                                                   :stream-id (:stream-id r),
                                                   :datom (:val result)})),
                             :stream/next
                             (fn [_s _e r]
                               (merge
                                 (snap-frame state' stack-rest next-control)
                                 {:reason :next,
                                  :cursor-ref (:cursor-ref r),
                                  :stream-id (:stream-id r)}))})
                          stack-vm-run-scheduler))
                      (let [arr1 stack-arr
                            sp1 stack-rest-sp
                            [next-arr next-sp] (stack-push arr1 sp1 result)]
                        (recur next-control
                               bytecode
                               pool
                               next-arr
                               next-sp
                               env
                               k
                               store
                               id-counter))))
                  (= :closure (:type fn-val))
                  (let [{clo-params :params,
                         clo-bytecode :bytecode,
                         clo-body :body-bytes,
                         clo-body-start :body-start,
                         clo-env :env,
                         clo-pool :pool}
                        fn-val
                        target-bytecode (or clo-bytecode clo-body)
                        target-control (or clo-body-start 0)
                        new-env (merge clo-env
                                       (zipmap clo-params
                                               (stack-args->vector stack-arr
                                                                   fn-pos
                                                                   argc)))]
                    (recur target-control
                           target-bytecode
                           (or clo-pool pool)
                           stack-arr
                           -1
                           new-env
                           k
                           store
                           id-counter))
                  :else (throw (ex-info "Cannot apply non-function"
                                        {:fn fn-val}))))
              :branch
              (let [offset (fetch-short-signed bytecode (inc control))
                    condition (stack-array-get stack-arr sp)]
                (recur (if condition (+ control 3 offset) (+ control 3))
                       bytecode
                       pool
                       stack-arr
                       (dec sp)
                       env
                       k
                       store
                       id-counter))
              :jump
              (let [offset (fetch-short-signed bytecode (inc control))]
                (recur (+ control 3 offset)
                       bytecode
                       pool
                       stack-arr
                       sp
                       env
                       k
                       store
                       id-counter))
              :return
              (let [result (when-not (neg? sp) (stack-array-get stack-arr sp))]
                (if (nil? k)
                  (halt-fast-state vm
                                   control
                                   bytecode
                                   pool
                                   stack-arr
                                   sp
                                   env
                                   k
                                   store
                                   id-counter
                                   result)
                  (let [frame k
                        [restored-arr restored-sp0] (stack-vector->array+sp
                                                      (:stack frame))
                        [restored-arr restored-sp]
                        (stack-push restored-arr restored-sp0 result)]
                    (recur (:control frame)
                           (:bytecode frame)
                           (or (:pool frame) pool)
                           restored-arr
                           restored-sp
                           (:env frame)
                           (:next frame)
                           store
                           id-counter))))
              :gensym
              (let [prefix-idx (nth bytecode (inc control))
                    prefix (nth pool prefix-idx)
                    id (keyword (str prefix "-" id-counter))
                    [next-arr next-sp] (stack-push stack-arr sp id)]
                (recur (+ control 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       k
                       store
                       (inc id-counter)))
              :store-get
              (let [key-idx (nth bytecode (inc control))
                    key (nth pool key-idx)
                    val (get store key)
                    [next-arr next-sp] (stack-push stack-arr sp val)]
                (recur (+ control 2)
                       bytecode
                       pool
                       next-arr
                       next-sp
                       env
                       k
                       store
                       id-counter))
              :store-put
              (let [key-idx (nth bytecode (inc control))
                    key (nth pool key-idx)
                    val (when-not (neg? sp) (stack-array-get stack-arr sp))]
                (recur (+ control 2)
                       bytecode
                       pool
                       stack-arr
                       sp
                       env
                       k
                       (assoc store key val)
                       id-counter))
              (fallback))))))))


;; =============================================================================
;; StackVM Protocol Implementation
;; =============================================================================

(defn- stack-vm-halted?
  "Returns true if VM has halted."
  [^StackVM vm]
  (engine/halted-with-empty-queue? vm))


(defn- stack-vm-blocked?
  "Returns true if VM is blocked."
  [^StackVM vm]
  (engine/vm-blocked? vm))


(defn- stack-vm-value
  "Returns the current value."
  [^StackVM vm]
  (engine/vm-value vm))


(defn- stack-vm-reset
  "Reset StackVM execution state to initial baseline, preserving loaded program."
  [^StackVM vm]
  (if-let [artifact
           (get-in vm [:compiled-by-version (:active-compiled-version vm)])]
    (activate-compiled-artifact vm artifact)
    (assoc vm
           :stack []
           :k nil
           :control (when (seq (:bytecode vm)) 0)
           :halted? (empty? (:bytecode vm))
           :value nil
           :blocked? false)))


(defn- stack-vm-load-canonical-program
  "Load canonical datom-form program into the VM.
   Runs macro expansion before bytecode compilation if macro-registry is set."
  [^StackVM vm {:keys [node datoms]}]
  (let [registry (or (:macro-registry vm) {})
        d (vec datoms)
        {:keys [datoms root-eid]}
        (macro/expand-all
          d
          node
          registry
          {:invoke-lambda (fn [lambda-eid ctx]
                            (semantic/invoke-macro-lambda lambda-eid ctx d))})
        program-version (inc (or (:program-version vm) 0))
        vm' (assoc vm
                   :program-root-eid root-eid
                   :datoms datoms
                   :program-version program-version
                   :datom-index nil
                   :compile-dirty? true)
        [compiled-vm artifact] (engine/ensure-compiled-version
                                 vm'
                                 program-version
                                 compile-stack-artifact)]
    (activate-compiled-artifact compiled-vm artifact)))


(defn- stack-vm-load-program
  "Load one datom transaction into the Stack VM."
  [^StackVM vm datoms]
  (let [d (vec datoms)
        root-id (:root-id (vm/index-datoms d))]
    (stack-vm-load-canonical-program vm {:node root-id, :datoms d})))


(defn- stack-vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, normalizes through vm/ast->datoms and loads via the
   canonical {:node :datoms} path.
   When nil, resumes from current state."
  [^StackVM vm ast]
  (let [initial-env (:env vm)
        res (if ast
              (let [datoms (vec (vm/ast->datoms ast))]
                (-> vm
                    (stack-vm-load-program datoms)
                    (vm/run)))
              (vm/run vm))]
    (engine/restore-initial-env initial-env res)))


(defn- stack-vm-run-on-stream
  [vm]
  (engine/run-on-stream vm
                        (:in-stream vm)
                        stack-vm-load-program
                        (if (telemetry/enabled? vm)
                          (fn [state]
                            (telemetry/emit-snapshot (stack-step state) :step))
                          stack-step)
                        resume-from-run-queue
                        stack-vm-restore))


(extend-type StackVM
  vm/IVM
  (step [vm]
    (telemetry/emit-snapshot (engine/step-on-stream vm
                                                    (:in-stream vm)
                                                    stack-vm-load-program
                                                    stack-step)
                             :step))
  (run [vm] (ffi/maybe-run vm stack-vm-run-on-stream))
  (eval [vm ast] (stack-vm-eval vm ast))
  (reset [vm] (stack-vm-reset vm))
  (halted? [vm] (stack-vm-halted? vm))
  (blocked? [vm] (stack-vm-blocked? vm))
  (value [vm] (stack-vm-value vm))
  vm/IVMState
  (control [vm] {:control (:control vm), :bytecode (:bytecode vm)})
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm]
    (when-let [k-head (:k vm)]
      (loop [k k-head
             acc []]
        (if (nil? k) acc (recur (:next k) (conj acc k)))))))


(defn create-vm
  "Create a new StackVM with optional opts map.
   Accepts {:env map, :primitives map, :macro-registry map, :bridge handlers, :telemetry config}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})
         bridge-state (ffi/bridge-from-opts opts)
         in-stream (:in-stream opts)
         base (vm/empty-state {:primitives (:primitives opts),
                               :telemetry (:telemetry opts),
                               :vm-model :stack})]
     (-> (map->StackVM
           (merge base
                  {:in-stream in-stream,
                   :in-cursor {:position 0},
                   :control nil,
                   :bytecode [],
                   :stack [],
                   :env env,
                   :k nil,
                   :pool [],
                   :compiled-by-version {},
                   :compiled-cache-limit (or
                                           (:compiled-cache-limit opts)
                                           engine/default-compiled-cache-limit),
                   :active-compiled-version nil,
                   :compile-dirty? false,
                   :macro-registry (or (:macro-registry opts) {}),
                   :program-root-eid nil,
                   :datoms [],
                   :program-version 0,
                   :datom-index nil,
                   :halted? true,
                   :value nil,
                   :blocked? false}
                  (when bridge-state {:bridge bridge-state})))
         (telemetry/install :stack)
         (telemetry/emit-snapshot :init)))))
