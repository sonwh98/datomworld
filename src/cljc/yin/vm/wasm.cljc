(ns yin.vm.wasm
  "WASM execution backend for the Yin language.

   Compiles yin AST datoms to a WASM binary module and delegates execution
   to Node.js built-in WebAssembly. Zero new npm dependencies.

   Pipeline:
     yin AST map
       -> vm/ast->datoms          (existing)
       -> vm/index-datoms          (existing)
       -> ast-datoms->asm         (symbolic WAT-like IR, platform-agnostic)
       -> assemble                (symbolic IR -> WASM binary bytes, platform-agnostic)
       -> WebAssembly.Module      (Node.js built-in, CLJS-only)
       -> WebAssembly.Instance
       -> call main export
       -> WasmVM{:halted true :value result}

   V1 supported subset: numeric/boolean literals, arithmetic (+,-,*,/),
   comparisons (=,<,>,<=,>=), logical negation (not), if/else.

   Backend contract (differs from dynamic VMs):
   - Comparison and logical ops return Clojure boolean (true/false), not raw i32.
   - All numeric literals are f64; boolean literals are Clojure true/false.
  - Mixed-type if branches (one f64, one i32) are promoted to f64 via
     f64.convert_i32_s. The selected branch value is correct; its type may
     differ from what a dynamic VM would return (e.g., true becomes 1.0).
   - and/or are NOT supported: Clojure and/or return operand values, which
     cannot be encoded in WASM's typed stack machine without branching that
     reintroduces the mixed-type promotion problem."
  (:require
    [yin.vm :as vm]
    [yin.vm.telemetry :as telemetry]))


;; =============================================================================
;; WasmVM Record
;; =============================================================================

(defrecord WasmVM
  [control          ; current control state (nil for wasm vm)
   env              ; lexical environment
   halted           ; true if execution completed
   id-counter       ; unique ID counter
   k                ; continuation
   parked           ; parked continuations
   primitives       ; primitive operations
   run-queue        ; vector of runnable continuations
   store            ; heap memory
   value            ; final result value
   wait-set         ; vector of parked continuations waiting on streams
   ])


;; =============================================================================
;; Binary Encoding Utilities (platform-agnostic where possible)
;; =============================================================================

(defn- leb128
  "Unsigned LEB128 encoding. Returns a vector of bytes (ints 0-255)."
  [n]
  (loop [n n result []]
    (if (< n 128)
      (conj result n)
      (recur (bit-shift-right n 7)
             (conj result (bit-or (bit-and n 0x7F) 0x80))))))


(defn- encode-f64
  "Encode a double as 8 bytes in IEEE 754 little-endian format."
  [v]
  #?(:cljs
     (let [buf  (js/ArrayBuffer. 8)
           view (js/DataView. buf)]
       (.setFloat64 view 0 v true)
       (mapv #(.getUint8 view %) (range 8)))
     :clj
     (let [buf (java.nio.ByteBuffer/allocate 8)]
       (.order buf java.nio.ByteOrder/LITTLE_ENDIAN)
       (.putDouble buf (double v))
       (mapv #(bit-and 0xFF %) (vec (.array buf))))))


(defn- vec-section
  "Build a WASM section: [section-id, LEB128(payload-length), ...payload...]."
  [id payload-bytes]
  (into [id] (into (leb128 (count payload-bytes)) payload-bytes)))


;; =============================================================================
;; Symbolic WAT-like IR: ast-datoms->asm
;; =============================================================================

(defn- compile-primitive-op
  "Compile a primitive operator application to WAT-like IR.
   Returns {:type :f64|:i32, :instrs [...]}."
  [op-name operand-refs compile-fn coerce-to-bool-fn]
  (case op-name
    ;; Arithmetic: f64 operands, f64 result
    + (let [a (compile-fn (nth operand-refs 0))
            b (compile-fn (nth operand-refs 1))]
        {:type :f64, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.add]])})
    - (let [a (compile-fn (nth operand-refs 0))
            b (compile-fn (nth operand-refs 1))]
        {:type :f64, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.sub]])})
    * (let [a (compile-fn (nth operand-refs 0))
            b (compile-fn (nth operand-refs 1))]
        {:type :f64, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.mul]])})
    / (let [a (compile-fn (nth operand-refs 0))
            b (compile-fn (nth operand-refs 1))]
        {:type :f64, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.div]])})
    ;; Comparisons: f64 operands, i32 result
    = (let [a (compile-fn (nth operand-refs 0))
            b (compile-fn (nth operand-refs 1))]
        {:type :i32, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.eq]])})
    < (let [a (compile-fn (nth operand-refs 0))
            b (compile-fn (nth operand-refs 1))]
        {:type :i32, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.lt]])})
    > (let [a (compile-fn (nth operand-refs 0))
            b (compile-fn (nth operand-refs 1))]
        {:type :i32, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.gt]])})
    <= (let [a (compile-fn (nth operand-refs 0))
             b (compile-fn (nth operand-refs 1))]
         {:type :i32, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.le]])})
    >= (let [a (compile-fn (nth operand-refs 0))
             b (compile-fn (nth operand-refs 1))]
         {:type :i32, :instrs (into (into (:instrs a) (:instrs b)) [[:f64.ge]])})
    ;; Logical negation: coerce operand to i32 first (handles f64 truthiness)
    not (let [a (coerce-to-bool-fn (compile-fn (nth operand-refs 0)))]
          {:type :i32, :instrs (conj (:instrs a) [:i32.eqz])})
    ;; and/or are NOT supported: Clojure and/or return operand values (value-passing,
    ;; short-circuit), which cannot be encoded in WASM's typed stack machine without
    ;; branching that reintroduces the mixed-type problem. Use (if ...) instead.
    (throw (ex-info "wasm: unsupported primitive operator" {:op op-name}))))


(defn ast-datoms->asm
  "Compile AST datoms to a symbolic WAT-like IR.

   Returns {:type :f64|:i32, :instrs [...]} where :instrs is a flat vector of
   tagged instruction tuples:
     [:f64.const 42.0]   [:i32.const 1]
     [:f64.add]          [:f64.sub]   [:f64.mul]   [:f64.div]
     [:f64.eq]           [:f64.lt]    [:f64.gt]    [:f64.le]   [:f64.ge]
     [:i32.eqz]          [:i32.and]   [:i32.or]
     [:if :f64|:i32]     [:else]      [:end]
     [:f64.convert_i32_s]

   Each recursive call returns {:type :f64|:i32, :instrs [...]}, allowing
   :if to pick the correct WASM result type."
  [datoms]
  (let [{:keys [by-entity root-id]} (vm/index-datoms datoms)
        ;; vm/index-datoms uses (some ...) for get-attr, which treats false
        ;; as falsy and returns nil. We need reduce+reduced to handle false values.
        get-attr (fn [e attr]
                   (reduce (fn [_ [_ a v]]
                             (when (= a attr) (reduced v)))
                           nil
                           (get by-entity e)))]
    (letfn
      [(coerce-to-bool
         [code]
         (if (= (:type code) :i32)
           code
           ;; In Yin/Clojure, all numbers are truthy.
           ;; We must drop the f64 value to keep the stack clean for the if instruction.
           {:type :i32, :instrs (into (:instrs code) [[:drop] [:i32.const 1]])}))

       (promote-to-f64
         [code]
         (if (= (:type code) :f64)
           code
           {:type :f64, :instrs (conj (:instrs code) [:f64.convert_i32_s])}))

       (compile-node
         [e]
         (let [node-type (get-attr e :yin/type)]
           (case node-type
             :literal
             (let [v (get-attr e :yin/value)]
               (if (boolean? v)
                 {:type :i32, :instrs [[:i32.const (if v 1 0)]]}
                 {:type :f64, :instrs [[:f64.const (double v)]]}))

             :application
             (let [op-e        (get-attr e :yin/operator)
                   op-name     (get-attr op-e :yin/name)
                   operand-refs (get-attr e :yin/operands)]
               (compile-primitive-op op-name operand-refs compile-node coerce-to-bool))

             :if
             (let [test-e    (get-attr e :yin/test)
                   cons-e    (get-attr e :yin/consequent)
                   alt-e     (get-attr e :yin/alternate)
                   test-code (coerce-to-bool (compile-node test-e))
                   cons-code (compile-node cons-e)
                   alt-code  (compile-node alt-e)
                   ;; Reconcile result type: if either is f64, promote both to f64.
                   result-type (if (or (= (:type cons-code) :f64)
                                       (= (:type alt-code) :f64))
                                 :f64 :i32)
                   cons-final (if (= result-type :f64) (promote-to-f64 cons-code) cons-code)
                   alt-final  (if (= result-type :f64) (promote-to-f64 alt-code) alt-code)]
               {:type result-type
                :instrs (-> (:instrs test-code)
                            (into [[:if result-type]])
                            (into (:instrs cons-final))
                            (conj [:else])
                            (into (:instrs alt-final))
                            (conj [:end]))})

             (throw (ex-info "wasm: unsupported AST node type"
                             {:node-type node-type})))))]
      (compile-node root-id))))


;; =============================================================================
;; Program Extraction and AST-Map Compilation (V2)
;; =============================================================================

(defn extract-program
  "Extract top-level named functions from compile-program output.
   Returns {:fns [{:name sym :params [...] :body ast} ...] :main ast}."
  [ast]
  (loop [node ast
         fns  []]
    (let [op       (:operator node)
          operands (:operands node)
          def-expr (first operands)]
      (if (and (= :application (:type node))
               (= :lambda (:type op))
               (= ['_] (:params op))
               (= 1 (count operands))
               (= :application (:type def-expr))
               (= :variable (-> def-expr :operator :type))
               (= 'yin/def (-> def-expr :operator :name))
               (= 2 (count (:operands def-expr)))
               (= :literal (:type (first (:operands def-expr))))
               (= :lambda (:type (second (:operands def-expr)))))
        (let [name-lit (:value (first (:operands def-expr)))
              lambda   (second (:operands def-expr))]
          (when-not (symbol? name-lit)
            (throw (ex-info "wasm: def name must be symbol"
                            {:name name-lit})))
          (recur (:body op)
                 (conj fns {:name   name-lit
                            :params (:params lambda)
                            :body   (:body lambda)})))
        {:fns fns
         :main node}))))


(defn- compile-body
  "Compile a Yang AST map to symbolic WAT-like IR.
   param-map: {symbol -> local-idx}
   func-map:  {symbol -> func-idx}"
  [ast param-map func-map]
  (letfn [(coerce-to-bool
            [code]
            (if (= (:type code) :i32)
              code
              ;; Yin/Clojure truthiness: all numbers are truthy.
              {:type :i32, :instrs (into (:instrs code) [[:drop] [:i32.const 1]])}))

          (promote-to-f64
            [code]
            (if (= (:type code) :f64)
              code
              {:type :f64, :instrs (conj (:instrs code) [:f64.convert_i32_s])}))

          (compile-node
            [node]
            (case (:type node)
              :literal
              (let [v (:value node)]
                (if (boolean? v)
                  {:type :i32, :instrs [[:i32.const (if v 1 0)]]}
                  {:type :f64, :instrs [[:f64.const (double v)]]}))

              :variable
              (let [name (:name node)]
                (if-let [idx (get param-map name)]
                  {:type :f64, :instrs [[:local.get idx]]}
                  (throw (ex-info "wasm: unresolved variable (only params allowed in fn body)"
                                  {:name name}))))

              :application
              (let [op   (:operator node)
                    args (:operands node)]
                (when-not (= :variable (:type op))
                  (throw (ex-info "wasm: unsupported application operator (must be symbol)"
                                  {:operator (:type op)})))
                (let [op-name (:name op)]
                  (if-let [func-idx (get func-map op-name)]
                    ;; User function call: all args must match f64 parameter type.
                    (let [arg-codes (mapv #(promote-to-f64 (compile-node %)) args)]
                      {:type :f64
                       :instrs (into (reduce into [] (map :instrs arg-codes))
                                     [[:call func-idx]])})
                    ;; Primitive op
                    (compile-primitive-op op-name args compile-node coerce-to-bool))))

              :if
              (let [test-code   (coerce-to-bool (compile-node (:test node)))
                    cons-code   (compile-node (:consequent node))
                    alt-code    (compile-node (:alternate node))
                    result-type (if (or (= :f64 (:type cons-code))
                                        (= :f64 (:type alt-code)))
                                  :f64
                                  :i32)
                    cons-final  (if (= result-type :f64) (promote-to-f64 cons-code) cons-code)
                    alt-final   (if (= result-type :f64) (promote-to-f64 alt-code) alt-code)]
                {:type result-type
                 :instrs (-> (:instrs test-code)
                             (into [[:if result-type]])
                             (into (:instrs cons-final))
                             (conj [:else])
                             (into (:instrs alt-final))
                             (conj [:end]))})

              (throw (ex-info "wasm: unsupported AST node type" {:node-type (:type node)}))))]
    (compile-node ast)))


(defn program->asm
  "Compile extracted program {:fns [...] :main ast} to wasm IR.
   V1: returns {:fns [] :main {:type ... :instrs ...}}
   V2: returns {:fns [{:params [...] :result-type ... :instrs [...]} ...]
                :main {:params [] :result-type ... :instrs [...]}}"
  [{:keys [fns main]}]
  (if (empty? fns)
    {:fns []
     :main (ast-datoms->asm (vm/ast->datoms main))}
    (let [func-map     (into {} (map-indexed (fn [i {:keys [name]}] [name i]) fns))
          ensure-f64   (fn [code]
                         (if (= :f64 (:type code))
                           code
                           {:type :f64
                            :instrs (conj (:instrs code) [:f64.convert_i32_s])}))
          compiled-fns (mapv (fn [{:keys [params body]}]
                               (let [param-map (into {} (map-indexed (fn [i p] [p i]) params))
                                     code      (ensure-f64 (compile-body body param-map func-map))]
                                 {:params      (vec (repeat (count params) :f64))
                                  :result-type :f64
                                  :instrs      (:instrs code)}))
                             fns)
          main-code    (compile-body main {} func-map)]
      {:fns compiled-fns
       :main {:params      []
              :result-type (:type main-code)
              :type        (:type main-code)
              :instrs      (:instrs main-code)}})))


;; =============================================================================
;; Assembler: symbolic IR -> WASM binary bytes
;; =============================================================================

(defn- encode-instr
  "Encode a single symbolic instruction to a vector of bytes."
  [instr]
  (case (first instr)
    :f64.const (into [0x44] (encode-f64 (double (second instr))))
    :i32.const (into [0x41] (leb128 (second instr)))
    :f64.add   [0xA0]
    :f64.sub   [0xA1]
    :f64.mul   [0xA2]
    :f64.div   [0xA3]
    :f64.eq    [0x61]
    :f64.lt    [0x63]
    :f64.gt    [0x64]
    :f64.le    [0x65]
    :f64.ge    [0x66]
    :i32.eqz   [0x45]
    :i32.and   [0x71]
    :i32.or    [0x72]
    :local.get (into [0x20] (leb128 (second instr)))
    :call      (into [0x10] (leb128 (second instr)))
    :if        [0x04 (if (= (second instr) :f64) 0x7C 0x7F)]
    :else      [0x05]
    :end       [0x0B]
    :f64.convert_i32_s [0xB7]
    :drop      [0x1A]))


(defn assemble
  "Encode symbolic WAT-like IR to a WASM binary module.

   Takes the output of ast-datoms->asm {:type :f64|:i32, :instrs [...]}.
   Returns a flat vector of bytes (ints 0-255) representing the full WASM module.

   Module structure:
     magic + version
     type section    (one func type: () -> f64 or () -> i32)
     function section (one function, type index 0)
     export section   (export 'main' as func 0)
     code section     (one function body)"
  [{:keys [type instrs]}]
  (let [result-type-byte (if (= type :f64) 0x7C 0x7F)

        ;; Encode instructions to bytes
        instr-bytes (reduce #(into %1 (encode-instr %2)) [] instrs)

        ;; Type section (id=1): one func type () -> result-type
        type-payload    [0x01 0x60 0x00 0x01 result-type-byte]
        type-section    (vec-section 0x01 type-payload)

        ;; Function section (id=3): one function, type index 0
        func-section    (vec-section 0x03 [0x01 0x00])

        ;; Export section (id=7): export "main" (109 97 105 110) as func 0
        export-payload  [0x01 0x04 109 97 105 110 0x00 0x00]
        export-section  (vec-section 0x07 export-payload)

        ;; Code section (id=10): one function body
        ;; func-body = [0x00 (no locals), ...instrs..., 0x0B (end)]
        func-body       (into [0x00] (conj instr-bytes 0x0B))
        code-payload    (into (leb128 1)
                              (into (leb128 (count func-body)) func-body))
        code-section    (vec-section 0x0A code-payload)]

    (into [0x00 0x61 0x73 0x6D   ; magic "\0asm"
           0x01 0x00 0x00 0x00]  ; version 1
          (into type-section
                (into func-section
                      (into export-section code-section))))))


(defn assemble-multi
  "Encode multi-function wasm IR to bytes.
   Falls back to single-function assemble when :fns is empty."
  [{:keys [fns main]}]
  (if (empty? fns)
    (assemble main)
    (let [all-fns       (conj (vec fns) main)
          main-idx      (count fns)
          type-byte     (fn [t]
                          (case t
                            :f64 0x7C
                            :i32 0x7F
                            (throw (ex-info "wasm: unsupported wasm type" {:type t}))))
          encode-type   (fn [{:keys [params result-type type]}]
                          (let [result (or result-type type)]
                            (into [0x60]
                                  (into (into (leb128 (count params))
                                              (mapv type-byte params))
                                        [0x01 (type-byte result)]))))
          type-entries  (mapv encode-type all-fns)
          type-payload  (into (leb128 (count type-entries))
                              (reduce into [] type-entries))
          type-section  (vec-section 0x01 type-payload)
          func-payload  (into (leb128 (count all-fns))
                              (mapcat leb128 (range (count all-fns))))
          func-section  (vec-section 0x03 func-payload)
          export-payload (into [0x01                     ; one export
                                0x04 109 97 105 110      ; "main"
                                0x00]                    ; kind=function
                               (leb128 main-idx))
          export-section (vec-section 0x07 export-payload)
          encode-body    (fn [{:keys [instrs]}]
                           (let [instr-bytes (reduce #(into %1 (encode-instr %2)) [] instrs)
                                 body        (into [0x00] (conj instr-bytes 0x0B))]
                             (into (leb128 (count body)) body)))
          code-payload   (into (leb128 (count all-fns))
                               (reduce into [] (mapv encode-body all-fns)))
          code-section   (vec-section 0x0A code-payload)]
      (into [0x00 0x61 0x73 0x6D
             0x01 0x00 0x00 0x00]
            (into type-section
                  (into func-section
                        (into export-section code-section)))))))


;; =============================================================================
;; WASM Runtime (CLJS-only)
;; =============================================================================

#?(:cljs
   (defn- run-wasm-bytes
     "Instantiate and run a WASM binary module synchronously.
      Calls the exported 'main' function and returns its numeric result."
     [bytes]
     (let [arr  (js/Uint8Array. (clj->js bytes))
           mod  (js/WebAssembly.Module. (.-buffer arr))
           inst (js/WebAssembly.Instance. mod #js {})
           main (.-main (.-exports inst))]
       (main))))


;; =============================================================================
;; Protocol Implementations
;; =============================================================================

(defn- wasm-eval
  "Compile yin AST to WASM binary and execute synchronously.
   When ast is nil, returns vm unchanged (eval-only, no resume)."
  [vm ast]
  (if (nil? ast)
    vm
    #?(:cljs
       (let [program   (extract-program ast)
             ir        (program->asm program)
             raw       (-> ir assemble-multi run-wasm-bytes)
             main-type (or (:result-type (:main ir))
                           (:type (:main ir)))
             result    (if (= main-type :i32)
                         (not (zero? raw))
                         raw)]
         (-> vm
             (assoc :halted false :value nil)
             (telemetry/emit-snapshot :step)
             (assoc :halted true :value result)
             (telemetry/emit-snapshot :halt)))
       :clj
       (throw (ex-info "wasm: JVM execution not supported" {})))))


(extend-type WasmVM
  vm/IVMStep
  (step     [vm]  (telemetry/emit-snapshot vm :step))
  (halted?  [vm]  (boolean (:halted vm)))
  (blocked? [_vm] false)
  (value    [vm]  (:value vm))

  vm/IVMRun
  (run [vm] (vm/eval vm nil))

  vm/IVMEval
  (eval [vm ast] (wasm-eval vm ast))

  vm/IVMState
  (control      [vm] (:control vm))
  (environment  [vm] (:env vm))
  (store        [vm] (:store vm))
  (continuation [vm] (:k vm)))


;; =============================================================================
;; Factory
;; =============================================================================

(defn create-vm
  "Create a new WasmVM with optional opts map.
   Accepts {:env map, :primitives map, :telemetry config}."
  ([] (create-vm {}))
  ([opts]
   (-> (map->WasmVM (merge (vm/empty-state {:primitives (:primitives opts)
                                            :telemetry (:telemetry opts)
                                            :vm-model :wasm})
                           {:halted  false
                            :value   nil
                            :control nil
                            :env     (or (:env opts) {})
                            :k       nil}))
       (telemetry/install :wasm)
       (telemetry/emit-snapshot :init))))
