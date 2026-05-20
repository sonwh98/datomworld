# WASM VM — Design and Compilation Pipeline

## Architecture

WASM is one compilation target among many. The Universal AST datoms are the
canonical representation; WASM is a projection of those datoms into WebAssembly
binary format. The CESK continuation machine is the *compilation host*, not the
runtime target — its complexity stays in compiler passes, not in generated code.

Full pipeline (self-hosted, eventual):

```
Yin source
  -> yang/compile-program          (Yin frontend: text -> Universal AST datoms)
  -> cps/transform                 (CPS pass: AST datoms -> CPS datoms)
  -> closure/convert               (closure pass: CPS datoms -> explicit closure datoms)
  -> wasm/codegen                  (WASM pass: closure datoms -> WASM binary bytes)
  -> wasm/runtime-lib              (minimal runtime: value tagging, heap, FFI)
  -> WebAssembly.Module/.Instance
  -> result
```

Each arrow is a pure stream processor: datoms in, datoms (or bytes) out. Same
Universal AST, multiple projections. The WASM codegen does not implement a CESK
machine; the CESK machine compiled the program.

---

## Versioned Scope

### V1 — arithmetic + if (implemented)

Literals (numeric -> f64, boolean -> i32), arithmetic (`+` `-` `*` `/`),
comparisons (`=` `<` `>` `<=` `>=`), logical `not`, `if`/else.

Single-function WASM module. No variables, no user functions.

### V2 — named recursive functions (next step)

Top-level `(def name (fn [params] body))` patterns. Each lambda compiles to a
WASM function with f64-typed parameters. Recursion via direct `call`. No CPS
pass needed because there are no closures over free variables and recursion is
simple (fib, factorial).

Handles: `(def fib (fn [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))))`.

### V3 — closures (requires closure conversion pass)

Lambdas that capture free variables. Each closure is `(func-table-idx,
env-record-ptr)` in WASM linear memory. Requires `call_indirect`, a WASM
function table, a heap allocator, and value tagging.

### V4 — first-class continuations (requires CPS pass)

Full CESK continuation model. CPS transform at compile time eliminates runtime
continuation frames. All calls become tail calls (`return_call`). Continuations
are function values (closures whose `k` parameter is a `funcref`). No CESK
machine in the WASM output; the CESK machine ran during compilation.

### V5 — self-hosted bootstrap

The CPS transform, closure conversion, and WASM codegen passes are themselves
written in Yin and compiled to WASM. The Yin compiler runs natively in WASM,
can compile Yin to WASM, and can compile itself.

---

## V2 Implementation Plan (immediate)

### The fundamental insight

WASM is a stack machine; the CESK machine is the compiler, not the runtime.
For V2 (pure numeric recursive functions with no free-variable closures), CPS
is not needed — parameters map directly to WASM locals, recursion maps directly
to WASM `call`.

### yang AST shape for `(def fib ...) (fib 7)`

`yang/compile-program` wraps each `def` as `((fn [_] <rest>) (yin/def name lambda))`.

```clojure
{:type :application, :tail? true
 :operator {:type :lambda
             :params ['_]
             :body {:type :application, :tail? true        ; (fib 7)
                    :operator {:type :variable, :name 'fib}
                    :operands [{:type :literal, :value 7}]}}
 :operands [{:type :application                            ; (yin/def fib <lambda>)
              :operator {:type :variable, :name 'yin/def}
              :operands [{:type :literal, :value 'fib}
                         {:type :lambda                    ; (fn [n] (if ...))
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

Multiple `def` forms nest: each adds one `((fn [_] <rest>) (yin/def ...))` layer.

### `extract-program`

Recognizes the `compile-program` wrapping and returns
`{:fns [{:name sym, :params [sym...], :body ast}...], :main ast}`.

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

Single-expression programs (no `def`) fall through unchanged: `{:fns [], :main ast}`.

### New IR instructions

Add to `encode-instr`:

| Symbolic IR          | Bytes           | Description              |
|----------------------|-----------------|--------------------------|
| `[:local.get idx]`   | `0x20 idx`      | Read function parameter  |
| `[:call func-idx]`   | `0x10 func-idx` | Direct function call     |

### `compile-body`

Works directly on yang AST maps (no `vm/ast->datoms` call). Reuses
`compile-primitive-op`, `coerce-to-bool`, `promote-to-f64` unchanged.

```clojure
(defn- compile-body [ast param-map func-map]
  (letfn
    [(coerce-to-bool [code]
       (if (= (:type code) :i32)
         code
         {:type :i32, :instrs (into (:instrs code) [[:drop] [:i32.const 1]])}))

     (promote-to-f64 [code]
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
             (throw (ex-info "wasm: unresolved variable" {:name name}))))

         :application
         (let [op   (:operator node)
               args (:operands node)]
           (if (and (= :variable (:type op))
                    (contains? func-map (:name op)))
             (let [func-idx  (get func-map (:name op))
                   arg-codes (mapv compile-node args)]
               {:type   :f64
                :instrs (into (reduce into [] (map :instrs arg-codes))
                              [[:call func-idx]])})
             (compile-primitive-op (:name op) args compile-node coerce-to-bool)))

         :if
         (let [test-code   (coerce-to-bool (compile-node (:test node)))
               cons-code   (compile-node (:consequent node))
               alt-code    (compile-node (:alternate node))
               result-type (if (or (= :f64 (:type cons-code))
                                   (= :f64 (:type alt-code))) :f64 :i32)
               cons-final  (if (= result-type :f64) (promote-to-f64 cons-code) cons-code)
               alt-final   (if (= result-type :f64) (promote-to-f64 alt-code) alt-code)]
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

### `program->asm`

Returns `{:fns [{:params [:f64...], :result-type :f64, :instrs [...]}...], :main {...}}`.
`func-map` is built before compiling any body so self-recursive calls resolve.

```clojure
(defn program->asm [{:keys [fns main]}]
  (if (empty? fns)
    {:fns [], :main (ast-datoms->asm (vm/ast->datoms main))}   ; V1 path
    (let [func-map (into {} (map-indexed (fn [i {:keys [name]}] [name i]) fns))
          wasm-fns (mapv (fn [{:keys [params body]}]
                           (let [param-map (into {} (map-indexed (fn [i p] [p i]) params))]
                             {:params      (vec (repeat (count params) :f64))
                              :result-type :f64
                              :instrs      (:instrs (compile-body body param-map func-map))}))
                         fns)
          main-ir  (compile-body main {} func-map)]
      {:fns wasm-fns, :main (assoc main-ir :params [])})))
```

### `assemble-multi`

WASM type section encoding for a function type:
```
0x60                    ; func type marker
leb128(n-params)        ; parameter count
[0x7C per param]        ; each f64 param = 0x7C, i32 = 0x7F
0x01                    ; 1 result
0x7C | 0x7F             ; result type
```

Examples:
```
() -> f64    : 0x60 0x00 0x01 0x7C
(f64) -> f64 : 0x60 0x01 0x7C 0x01 0x7C
(f64 f64) -> f64 : 0x60 0x02 0x7C 0x7C 0x01 0x7C
```

```clojure
(defn assemble-multi [{:keys [fns main]}]
  (if (empty? fns)
    (assemble main)    ; V1 fallback
    (let [all-fns  (conj fns main)
          main-idx (count fns)

          type-byte   (fn [t] (if (= t :f64) 0x7C 0x7F))
          encode-type (fn [{:keys [params result-type]}]
                        (into [0x60]
                              (into (into (leb128 (count params))
                                          (mapv type-byte params))
                                    [0x01 (type-byte result-type)])))

          type-entries (mapv encode-type all-fns)
          type-payload (into (leb128 (count type-entries))
                             (reduce into [] type-entries))
          type-section (vec-section 0x01 type-payload)

          func-payload (into (leb128 (count all-fns))
                             (mapcat leb128 (range (count all-fns))))
          func-section (vec-section 0x03 func-payload)

          export-payload (into [0x01 0x04 109 97 105 110 0x00]
                               (leb128 main-idx))
          export-section (vec-section 0x07 export-payload)

          encode-body (fn [{:keys [instrs]}]
                        (let [instr-bytes (reduce #(into %1 (encode-instr %2)) [] instrs)
                              body        (into [0x00] (conj instr-bytes 0x0B))]
                          (into (leb128 (count body)) body)))
          code-payload (into (leb128 (count all-fns))
                             (reduce into [] (mapv encode-body all-fns)))
          code-section (vec-section 0x0A code-payload)]

      (into [0x00 0x61 0x73 0x6D 0x01 0x00 0x00 0x00]
            (into type-section
                  (into func-section
                        (into export-section code-section)))))))
```

### Updated `wasm-eval`

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

### Target WASM for Fibonacci (annotated)

```
Type section:
  type 0: (f64) -> f64    [0x60 0x01 0x7C 0x01 0x7C]
  type 1: ()   -> f64    [0x60 0x00 0x01 0x7C]
Function section: func 0 = type 0, func 1 = type 1
Export: "main" = func 1
Code:
  func 0 (fib):
    0x20 0x00          ; local.get 0  (n)
    0x44 <2.0 bytes>   ; f64.const 2.0
    0x63               ; f64.lt
    0x04 0x7C          ; if f64
      0x20 0x00        ;   local.get 0  (return n)
    0x05               ; else
      0x20 0x00        ;   local.get 0
      0x44 <1.0 bytes> ;   f64.const 1.0
      0xA1             ;   f64.sub      (n-1)
      0x10 0x00        ;   call 0       (fib(n-1))
      0x20 0x00        ;   local.get 0
      0x44 <2.0 bytes> ;   f64.const 2.0
      0xA1             ;   f64.sub      (n-2)
      0x10 0x00        ;   call 0       (fib(n-2))
      0xA0             ;   f64.add
    0x0B               ; end
  func 1 (main):
    0x44 <7.0 bytes>   ; f64.const 7.0
    0x10 0x00          ; call 0         (fib(7) -> 13.0)
```

---

## V3: Closures

Requires three additions beyond V2:

**1. WASM function table** — a `table` section holding `funcref` entries. Each
lambda body becomes a function in the table. `call_indirect` invokes by index
with a type check.

**2. Linear memory heap + allocator** — env records are structs in WASM linear
memory: `{parent-ptr: i32, n-slots: i32, [(sym-id: i32, value: i64)...]}`. A
bump-pointer allocator suffices for short-lived computations; a mark-sweep GC is
needed for long-lived programs.

**3. Value tagging (NaN-boxing)** — uniform 64-bit slots everywhere:
- Actual f64: stored directly (non-NaN bit patterns)
- i32 integer: NaN with tag `0x01` in mantissa
- Boolean: NaN with tag `0x02`
- Heap pointer (env record, cons cell): NaN with tag `0x03` + 32-bit address
- `funcref` index (closure function): NaN with tag `0x04` + table index
- nil: NaN with tag `0x00`

A closure value is two words: `(tagged-funcref, tagged-env-ptr)`.

**Closure conversion pass** (new stream processor on datoms):
- Identify free variables in each lambda
- Replace lambda body with `(fn [captured-env param1 ... paramN k] body)`
- Replace closure creation sites with `(make-closure func-idx env-record)`
- Replace closure calls with `(call_indirect func-idx env arg1 ... argN k)`

This pass runs on Universal AST datoms, before WASM codegen. Output is new
datoms with `:wasm/closure-idx`, `:wasm/free-vars`, `:wasm/env-layout` attributes.

---

## V4: First-Class Continuations (CPS pass)

CPS transform eliminates runtime continuation frames. Every function gets an
extra `k` parameter (a closure: the continuation). All return becomes `(k value)`.
All calls in non-tail position become CPS chains.

Before CPS (`fib` body):
```clojure
(if (< n 2)
  n
  (+ (fib (- n 1)) (fib (- n 2))))
```

After CPS:
```clojure
(fn [n k]
  (<-cps n 2
    (fn [test]
      (if test
        (k n)
        (-cps n 1
          (fn [n1]
            (fib-cps n1
              (fn [r1]
                (-cps n 2
                  (fn [n2]
                    (fib-cps n2
                      (fn [r2]
                        (+cps r1 r2 k)))))))))))))
```

After CPS every call is in tail position. With WASM tail calls (`return_call`,
standardized 2023, supported in V8/SpiderMonkey/Wasmtime), the WASM call stack
stays bounded regardless of recursion depth. Continuations are first-class
because `k` is just a closure value: store it, call it later, pass it to another
function.

**CPS pass** (new stream processor on datoms):
- Walk AST datoms
- For each non-tail call site, introduce a fresh `k` lambda wrapping the
  continuation of the current expression
- Emit new datoms with `:wasm/cps? true` marking transformed nodes
- Output is a valid Universal AST (still datoms, still interpretable by the
  existing CESK machine for debugging)

The CPS pass runs before closure conversion. Order: `cps/transform ->
closure/convert -> wasm/codegen`.

**Serializable continuations** — park/migrate still works because `k` is a
closure (function index + env record). The env record is in WASM linear memory.
To serialize: walk the env record chain, replace WASM pointers with content
hashes (AST nodes) or serialized values. This is the same mechanism as the
existing `park` in the CESK machine, now operating on WASM linear memory
instead of Clojure maps.

---

## V5: Bootstrap

The CPS pass, closure conversion pass, and WASM codegen are themselves written
in Yin. They run on the Stage 0 CESK machine (ClojureScript) to produce a WASM
binary. That WASM binary contains the Yin compiler. The Yin compiler running in
WASM can compile Yin to WASM. The loop closes.

Bootstrap sequence:
```
Stage 0  ClojureScript CESK machine
         Runs yang (CLJS), wasm.cljc (CLJS)
         Produces: working V2 WASM backend

Stage 1  Write compiler passes in Yin, run on Stage 0
         yang frontend in Yin
         CPS transform in Yin (datoms -> CPS datoms)
         Closure conversion in Yin (CPS datoms -> closure datoms)
         WASM codegen in Yin (closure datoms -> bytes)
         Produces: WASM binary of Yin compiler

Stage 2  WASM binary runs natively (no CLJS dependency)
         Compiles Yin programs to WASM
         Produces: WASM binaries of arbitrary Yin programs

Stage 3  WASM binary compiles itself
         Full self-hosting: Yin compiles Yin to WASM
```

Each stage is a pure stream processor projection. The Universal AST datoms
travel across all stages unchanged; only the execution substrate changes.

---

## Files to Modify (V2)

| File | Changes |
|------|---------|
| `src/cljc/yin/vm/wasm.cljc` | Add `extract-program`, `compile-body`, `program->asm`, `assemble-multi`; extend `encode-instr`; update `wasm-eval` |
| `test/yin/vm/wasm_test.cljc` | Add `extract-program-test`, `program->asm-fib-test`, `eval-fibonacci-test`, `eval-factorial-test` |

No changes to `vm/ast->datoms`, `engine.cljc`, `yang/clojure.cljc`.

---

## Tests to Add (V2)

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
      (is (= 'fib (:name (first fns))))
      (is (= ['n] (:params (first fns))))
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

### CLJS only

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

Note: `yang/compile-program` takes a **sequence** of forms, not a single quoted
form.

---

## Invariants

- All user function params/returns are f64 in V2. Comparison i32 results coerced
  via `promote-to-f64` / `coerce-to-bool` at boundaries.
- `func-map` is built before compiling any body: self-recursive calls resolve.
- V1 single-expression path (`assemble`, `ast-datoms->asm`) is untouched.
- `compile-body` works on yang AST maps directly; does not call `vm/ast->datoms`.
- No pass mutates its input datoms. All intermediate representations coexist.
- The Universal AST is the invariant. WASM binary is a local artifact, not the
  canonical form. Datoms travel; bytes are re-derived at the destination.
