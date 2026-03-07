# WASM VM — Design and Compilation Pipeline

## Overview

The WASM backend compiles Yin/Clojure programs to WebAssembly binary modules
and executes them synchronously via Node.js built-in `WebAssembly`. Zero npm
dependencies. Platform: CLJS only (Node.js / browser).

```
yang source
  -> yang/compile-program        (Clojure text -> Universal AST map)
  -> wasm/extract-program        (split into named fns + main expression)
  -> wasm/program->asm           (AST maps -> symbolic WAT-like IR per fn)
  -> wasm/assemble / assemble-multi  (IR -> WASM binary bytes)
  -> WebAssembly.Module / .Instance
  -> call exported "main"
  -> WasmVM{:halted true :value result}
```

## Supported Subset

### V1 — arithmetic + if (implemented)

Literals (numeric -> f64, boolean -> i32), arithmetic (`+` `-` `*` `/`),
comparisons (`=` `<` `>` `<=` `>=`), logical `not`, `if`/else.

### V2 — named recursive functions (to implement)

Top-level `(def name (fn [params] body))` patterns compile each lambda as a
WASM function with f64-typed parameters. Recursion via direct `call`. All user
function params and return values are f64.

### Unsupported (by design)

- `and`/`or`: short-circuit, value-returning — cannot be uniformly typed in
  WASM's stack machine.
- Closures capturing non-parameter free variables.
- Higher-order functions passed as values (no `call_indirect`).

---

## V2 Implementation Plan

### 1. Concrete yang AST for `(def fib ...) (fib 7)`

`yang/compile-program` wraps each `def` form as `((fn [_] <rest>) (yin/def name lambda))`.
The full AST for the Fibonacci example is:

```clojure
{:type :application, :tail? true
 :operator {:type :lambda
             :params ['_]
             :body {:type :application, :tail? true      ; (fib 7)
                    :operator {:type :variable, :name 'fib}
                    :operands [{:type :literal, :value 7}]}}
 :operands [{:type :application                          ; (yin/def fib <lambda>)
              :operator {:type :variable, :name 'yin/def}
              :operands [{:type :literal, :value 'fib}
                         {:type :lambda                  ; (fn [n] (if ...))
                          :params ['n]
                          :body {:type :if
                                 :test {:type :application
                                        :operator {:type :variable, :name '<}
                                        :operands [{:type :variable, :name 'n}
                                                   {:type :literal, :value 2}]}
                                 :consequent {:type :variable, :name 'n}
                                 :alternate  {:type :application
                                              :operator {:type :variable, :name '+}
                                              :operands
                                              [{:type :application
                                                :operator {:type :variable, :name 'fib}
                                                :operands [{:type :application
                                                            :operator {:type :variable, :name '-}
                                                            :operands [{:type :variable, :name 'n}
                                                                       {:type :literal, :value 1}]}]}
                                               {:type :application
                                                :operator {:type :variable, :name 'fib}
                                                :operands [{:type :application
                                                            :operator {:type :variable, :name '-}
                                                            :operands [{:type :variable, :name 'n}
                                                                       {:type :literal, :value 2}]}]}]}}}]}]}
```

For multiple `def` forms the wrapping nests: each def adds one
`((fn [_] <rest>) (yin/def ...))` layer around the rest of the program.

---

### 2. `extract-program` — pattern recognition

Detects the `compile-program` wrapping by matching:
- `:type :application`
- `:operator` is `{:type :lambda, :params ['_], :body <rest>}`
- single operand is `{:type :application, :operator {:type :variable, :name 'yin/def}, :operands [name-literal lambda]}`

Returns `{:fns [{:name sym, :params [sym...], :body ast}...], :main ast}`.

```clojure
(defn extract-program [ast]
  (loop [node ast, fns []]
    (let [op       (:operator node)
          operands (:operands node)
          def-expr (first operands)]
      (if (and (= :application (:type node))
               (= :lambda      (:type op))
               (= ['_]         (:params op))
               (= 1            (count operands))
               (= :application (:type def-expr))
               (= 'yin/def     (-> def-expr :operator :name))
               (= :lambda      (:type (second (:operands def-expr)))))
        (let [name-lit (:value (first (:operands def-expr)))
              lambda   (second (:operands def-expr))]
          (recur (:body op)
                 (conj fns {:name   name-lit
                             :params (:params lambda)
                             :body   (:body lambda)})))
        {:fns fns, :main node}))))
```

Single-expression programs (no `def`) return `{:fns [], :main ast}` unchanged.

---

### 3. New IR instructions

Add to `encode-instr` in `wasm.cljc`:

| Symbolic IR          | Bytes          | Description              |
|----------------------|----------------|--------------------------|
| `[:local.get idx]`   | `0x20 idx`     | Read function parameter  |
| `[:call func-idx]`   | `0x10 func-idx`| Direct function call     |

```clojure
:local.get [0x20 (second instr)]
:call      [0x10 (second instr)]
```

---

### 4. `compile-body` — compile yang AST map to WAT IR

A new private function (not the existing `letfn` inside `ast-datoms->asm`).
Works directly on yang AST maps; does **not** call `vm/ast->datoms`.

Reuses `compile-primitive-op`, `coerce-to-bool`, `promote-to-f64` unchanged.

```clojure
(defn- compile-body
  "Compile a yang AST map to WAT-like IR.
   param-map: {symbol -> local-idx}   (function parameters as WASM locals)
   func-map:  {symbol -> func-idx}    (named user functions)"
  [ast param-map func-map]
  (letfn
    [(coerce-to-bool [code]   ; identical to ast-datoms->asm version
       (if (= (:type code) :i32)
         code
         {:type :i32, :instrs (into (:instrs code) [[:drop] [:i32.const 1]])}))

     (promote-to-f64 [code]   ; identical to ast-datoms->asm version
       (if (= (:type code) :f64)
         code
         {:type :f64, :instrs (conj (:instrs code) [:f64.convert_i32_s])}))

     (compile-node [node]
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
           (if (and (= :variable (:type op))
                    (contains? func-map (:name op)))
             ;; User function call: compile args left-to-right, emit call
             (let [func-idx  (get func-map (:name op))
                   arg-codes (mapv compile-node args)]
               {:type   :f64
                :instrs (into (reduce into [] (map :instrs arg-codes))
                              [[:call func-idx]])})
             ;; Primitive operator: delegate to existing compile-primitive-op
             (compile-primitive-op (:name op) args compile-node coerce-to-bool)))

         :if
         (let [test-code  (coerce-to-bool (compile-node (:test node)))
               cons-code  (compile-node (:consequent node))
               alt-code   (compile-node (:alternate node))
               result-type (if (or (= :f64 (:type cons-code))
                                   (= :f64 (:type alt-code))) :f64 :i32)
               cons-final (if (= result-type :f64) (promote-to-f64 cons-code) cons-code)
               alt-final  (if (= result-type :f64) (promote-to-f64 alt-code) alt-code)]
           {:type   result-type
            :instrs (-> (:instrs test-code)
                        (into [[:if result-type]])
                        (into (:instrs cons-final))
                        (conj [:else])
                        (into (:instrs alt-final))
                        (conj [:end]))})

         (throw (ex-info "wasm: unsupported AST node type" {:node-type (:type node)}))))]

    (compile-node ast)))
```

---

### 5. `program->asm` — multi-function IR

Returns `{:fns [{:params [:f64...], :result-type :f64, :instrs [...]}...], :main {:params [], :result-type :f64|:i32, :instrs [...]}}`.

```clojure
(defn program->asm [{:keys [fns main]}]
  (if (empty? fns)
    ;; V1 path: single expression, use existing datoms pipeline
    {:fns  []
     :main (ast-datoms->asm (vm/ast->datoms main))}
    ;; V2 path: named functions
    (let [func-map  (into {} (map-indexed (fn [i {:keys [name]}] [name i]) fns))
          wasm-fns  (mapv (fn [{:keys [params body]}]
                            (let [param-map (into {} (map-indexed (fn [i p] [p i]) params))]
                              {:params      (vec (repeat (count params) :f64))
                               :result-type :f64
                               :instrs      (:instrs (compile-body body param-map func-map))}))
                          fns)
          main-ir   (compile-body main {} func-map)]
      {:fns  wasm-fns
       :main (assoc main-ir :params [])})))
```

---

### 6. `assemble-multi` — WASM binary encoding

WASM type section encoding for a function type:
```
0x60                       ; func type marker
leb128(n-params)           ; number of parameters
[0x7C per param]           ; each f64 param = 0x7C
0x01                       ; 1 result
0x7C | 0x7F                ; result type: f64=0x7C, i32=0x7F
```

Examples:
```
() -> f64   : 0x60 0x00 0x01 0x7C
(f64) -> f64: 0x60 0x01 0x7C 0x01 0x7C
```

Full assembler for multi-function modules:

```clojure
(defn assemble-multi [{:keys [fns main]}]
  (if (empty? fns)
    (assemble main)   ; V1 fallback: existing single-function assembler
    (let [all-fns  (conj fns main)          ; user fns first, main last
          main-idx (count fns)

          type-byte   (fn [t] (if (= t :f64) 0x7C 0x7F))
          encode-type (fn [{:keys [params result-type]}]
                        (into [0x60]
                              (into (into (leb128 (count params))
                                          (mapv type-byte params))
                                    [0x01 (type-byte result-type)])))

          ;; Type section (id=1)
          type-entries (mapv encode-type all-fns)
          type-payload (into (leb128 (count type-entries))
                             (reduce into [] type-entries))
          type-section (vec-section 0x01 type-payload)

          ;; Function section (id=3): func i uses type i (1:1)
          func-payload (into (leb128 (count all-fns))
                             (mapcat leb128 (range (count all-fns))))
          func-section (vec-section 0x03 func-payload)

          ;; Export section (id=7): export "main" = func main-idx
          export-payload (into [0x01                        ; 1 export
                                0x04 109 97 105 110         ; name len=4, "main"
                                0x00]                       ; kind = function
                               (leb128 main-idx))
          export-section (vec-section 0x07 export-payload)

          ;; Code section (id=10): one body per function
          encode-body (fn [{:keys [instrs]}]
                        (let [instr-bytes (reduce #(into %1 (encode-instr %2)) [] instrs)
                              body        (into [0x00] (conj instr-bytes 0x0B))]
                          (into (leb128 (count body)) body)))
          code-payload (into (leb128 (count all-fns))
                             (reduce into [] (mapv encode-body all-fns)))
          code-section (vec-section 0x0A code-payload)]

      (into [0x00 0x61 0x73 0x6D   ; magic "\0asm"
             0x01 0x00 0x00 0x00]  ; version 1
            (into type-section
                  (into func-section
                        (into export-section code-section)))))))
```

---

### 7. Updated `wasm-eval`

```clojure
(defn- wasm-eval [vm ast]
  (if (nil? ast)
    vm
    #?(:cljs
       (let [program (extract-program ast)
             ir      (program->asm program)
             bytes   (assemble-multi ir)
             raw     (run-wasm-bytes bytes)
             result  (if (= :i32 (:result-type (:main ir)))
                       (not (zero? raw))
                       raw)]
         (assoc vm :halted true :value result))
       :clj (throw (ex-info "wasm: JVM execution not supported" {})))))
```

`assemble-multi` falls back to `assemble` when `(:fns ir)` is empty, so V1
and V2 paths both flow through `assemble-multi`.

---

### 8. Target WASM module for Fibonacci (annotated bytes)

```
Type section:
  type 0: (f64) -> f64    [0x60, 0x01, 0x7C, 0x01, 0x7C]
  type 1: ()   -> f64    [0x60, 0x00, 0x01, 0x7C]
Function section: func 0 = type 0, func 1 = type 1
Export: "main" = func 1
Code:
  func 0 (fib):
    0x20 0x00          ; local.get 0  (n)
    0x44 <2.0 bytes>   ; f64.const 2.0
    0x63               ; f64.lt
    0x04 0x7C          ; if f64
      0x20 0x00        ; local.get 0  (return n)
    0x05               ; else
      0x20 0x00        ; local.get 0
      0x44 <1.0 bytes> ; f64.const 1.0
      0xA1             ; f64.sub      (n-1)
      0x10 0x00        ; call 0       (fib(n-1))
      0x20 0x00        ; local.get 0
      0x44 <2.0 bytes> ; f64.const 2.0
      0xA1             ; f64.sub      (n-2)
      0x10 0x00        ; call 0       (fib(n-2))
      0xA0             ; f64.add
    0x0B               ; end
  func 1 (main):
    0x44 <7.0 bytes>   ; f64.const 7.0
    0x10 0x00          ; call 0       (fib(7))
```

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/cljc/yin/vm/wasm.cljc` | Add `extract-program`, `compile-body`, `program->asm`, `assemble-multi`; extend `encode-instr`; update `wasm-eval` |
| `test/yin/vm/wasm_test.cljc` | Add `extract-program-test`, `program->asm-fib-test`, `eval-fibonacci-test`, `eval-factorial-test` |

No changes to `vm/ast->datoms`, `engine.cljc`, `yang/clojure.cljc`, or any
other file.

---

## Tests to Add

### Platform-agnostic

```clojure
(deftest extract-program-test
  (testing "single literal returns empty fns"
    (let [{:keys [fns main]} (wasm/extract-program {:type :literal, :value 42})]
      (is (empty? fns))
      (is (= {:type :literal, :value 42} main))))

  (testing "extracts one def and main"
    (let [lambda {:type :lambda, :params ['n], :body {:type :literal, :value 0}}
          ast    {:type :application
                  :operator {:type :lambda, :params ['_]
                              :body {:type :application
                                     :operator {:type :variable, :name 'fib}
                                     :operands [{:type :literal, :value 7}]}}
                  :operands [{:type :application
                               :operator {:type :variable, :name 'yin/def}
                               :operands [{:type :literal, :value 'fib} lambda]}]}
          {:keys [fns main]} (wasm/extract-program ast)]
      (is (= 1 (count fns)))
      (is (= 'fib  (:name (first fns))))
      (is (= ['n]  (:params (first fns))))
      (is (= {:type :application
              :operator {:type :variable, :name 'fib}
              :operands [{:type :literal, :value 7}]} main)))))

(deftest program->asm-fib-test
  (testing "fib IR contains local.get and call"
    (let [fib-ast (yang/compile-program
                    '[(def fib (fn [n]
                        (if (< n 2) n
                          (+ (fib (- n 1)) (fib (- n 2))))))
                      (fib 7)])
          {:keys [fns main]} (wasm/program->asm (wasm/extract-program fib-ast))]
      (is (= 1 (count fns)))
      (is (some #(= [:local.get 0] %) (:instrs (first fns))))
      (is (some #(= [:call 0] %)      (:instrs (first fns))))
      (is (some #(= [:call 0] %)      (:instrs main))))))
```

### CLJS only (require WebAssembly runtime)

```clojure
#?(:cljs
   (do
     (deftest eval-fibonacci-test
       (testing "(fib 7) -> 13.0"
         (let [ast    (yang/compile-program
                        '[(def fib (fn [n]
                            (if (< n 2) n
                              (+ (fib (- n 1)) (fib (- n 2))))))
                          (fib 7)])
               result (vm/eval (wasm/create-vm) ast)]
           (is (vm/halted? result))
           (is (= 13.0 (vm/value result))))))

     (deftest eval-factorial-test
       (testing "(fact 5) -> 120.0"
         (let [ast    (yang/compile-program
                        '[(def fact (fn [n]
                            (if (= n 0) 1 (* n (fact (- n 1))))))
                          (fact 5)])
               result (vm/eval (wasm/create-vm) ast)]
           (is (= 120.0 (vm/value result))))))))
```

Note: `yang/compile-program` takes a **sequence** of forms (a vector or list),
not a single quoted form.

---

## Invariants

- All user function params/returns are f64. Comparison i32 results are coerced
  via existing `promote-to-f64` / `coerce-to-bool` at boundaries.
- `func-map` is built before compiling any function body, so self-recursive
  calls (`fib` calling `fib`) resolve correctly.
- The V1 single-expression path (`assemble`, `ast-datoms->asm`) is untouched.
- `compile-body` works on yang AST maps directly; does not call `vm/ast->datoms`.
