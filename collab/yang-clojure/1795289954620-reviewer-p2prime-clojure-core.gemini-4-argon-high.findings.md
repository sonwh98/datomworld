Created-GMT: 2026-11-21T19:43:00Z
Created-Local: 2026-11-22T02:43:00+07:00
Coding-Agent: agy
Session-ID: e4fbf30d-58bb-4263-9981-af6c3994b3fb

# Task: Step-7 review — yang.preludes P2′ (unified clojure.core + yang.clojure migration)
Role: Reviewer (independent, read-only)
Implementers:

## Review Summary & Contract Audit

Independent read-only audit of the P2′ deliverable on `feat/p2prime-clojure-core` against [yang.preludes.md](file:///Users/sto/workspace/dw-p2prime/docs/design/yang.preludes.md) (r8, §§6.2, 7.4, 7.5, 8.1, 9 P2′) and the engineer task breakdown (`1795286006293-engineer-p2prime-clojure-core-migration.prompt.md`).

### 1. §7.5 `clojure.core.impl` Contract ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L1-L449))
- **Pure, stateless module**:
  - `module-uast` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L431-L435)) declares `runtime-keys` as `[]`; `module-spec` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L441-L449)) declares `:requires ['yang.call 'clj 'yrt.int]`, zero state cells, zero `:ambient-projections`, and zero direct calls to `cell/dsg__new-provenance` or `cell/dsg__provenance`.
- **Zero bare-primitive store collisions**:
  - All internal definitions are prefixed `impl-op-*` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L397-L399)) and all exported entry definitions are prefixed `impl-<name>` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L390-L391), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L400-L401)).
  - In linked mode, internal calls to VM primitives (`get`, `assoc`, `conj`, `first`, `rest`, `vec`, `nil?`, `empty?`, `not`) resolve directly to VM primitive operations without self-shadowing (`impl-get`, `impl-assoc`, `impl-conj`, etc. are distinct symbols). In bundled mode, `bundled-definitions` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L422-L429)) runs `core/protect-primitives` ([core/prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/core/prelude.cljc#L142-L152)) to rewrite unshadowed primitive operator symbols to `dsg__primitive/*` aliases (`vm/compiler-primitive-aliases` in [vm.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yin/vm.cljc#L473-L479), honored by `engine/resolve-var` in [engine.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yin/vm/engine.cljc#L127-L128)), so user-defined top-level `get`/`assoc`/`+`/`<=` in the shared bundled store cannot hijack standard-library internals.
- **Cell-safe collection predicates**:
  - `impl-cell-ref?` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L16-L19)) checks `(data/map? x)`, `(= (get x :type) :cell-ref)`, and `(cell/authentic? x)`.
  - `impl-map?` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L21-L23)), `impl-coll?` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L294-L297)), `impl-seq?` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L24-L25)), `impl-sequential?` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L293)), and `impl-reduced?` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L169-L170)) explicitly exclude authenticated cell references. An authentic callable cell returns `false` for all nine required predicates (`coll?`, `map?`, `set?`, `vector?`, `sequential?`, `seq?`, `list?`, `reduced?`) and `true` for `fn?` (`(clj/admitted-callable? x)` at [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L252)), covered in [effect_test.cljc](file:///Users/sto/workspace/dw-p2prime/test/yang/rt/effect_test.cljc#L230-L235).
- **Host-backed operations & cold-error policy**:
  - `inc`, `dec`, `rem`, `int?` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L237-L249)): delegate to `integer/add`, `integer/sub`, `integer/quot-rem`, `integer/integer?` with `impl-int-result` (`(yrt.int/check-limit (clj/rt-ctx) value)` at [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L13-L14)) and `float/add`/`float/sub` with `(integer/to-float-boxed 1)` on `(data/float64? x)`. `rem` performs `:math/div-zero` on `b == 0` and `:math/rem-type` on non-integer arguments via `clj/perform-policy`.
  - `not=` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L250)): delegates to `(not (data/equiv a b))`.
  - `str` / `impl-render` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L114-L168), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L360)): pure Yang deterministic renderer delegating integers to `integer/format`, floats to `float/format`, keywords/symbols to `kind/namespace` + `kind/name`, maps/sets to `data/canonical-entries` (via `impl-vec`), and authenticated callable cells (`impl-cell-ref?`) to `"#<fn>"`.
  - Collection traversal (`impl-vec`, `impl-first`, `impl-rest`, `impl-seq`, `impl-keys`, `impl-vals`, `impl-reduce-at`, `impl-map-one`/`two`, `impl-filter-at`, `impl-into`, `impl-merge-two`, `union`, `difference`): all normalize maps and sets through `impl-vec` -> `data/canonical-entries` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L27-L32)).
  - Map/set mutators and lookups (`impl-get-default`, `impl-assoc-one`, `impl-contains?`, `impl-conj-one`, `impl-disj`, `dissoc`, `impl-into`, `impl-merge-two`, `union`, `difference`): all validate non-vector keys/elements through `clj/collection-key` ([prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L225-L229)), which checks `(data/admit-key? k)` and routes unadmitted keys through `(clj/perform-policy :coll/unsupported-key {:key k})`.
  - Policy dispatch: `clj/run-source-unit` ([prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L286-L292)) installs `:on-unhandled-policy clj/dsg__source-policy` on `(clj/rt-ctx)`. Both `clj/perform-policy` ([prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L215-L222)) and `yrt.int/_perform` ([int.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/rt/int.cljc#L23-L39)) check for an active `yrt.effect/handle` frame first and otherwise invoke `:on-unhandled-policy` (raising `vm/raise` with the P2′ cold-error messages for `:int/overflow`, `:math/div-zero`, `:math/rem-type`, and `:coll/unsupported-key`), while still falling back to `yrt.effect/perform` when no `:on-unhandled-policy` is installed.

### 2. §7.5 `clojure.core` Facade ([core_module.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_module.cljc#L1-L90))
- **Linked module contract**:
  - `module-spec` ([core_module.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_module.cljc#L82-L90)) requires `'clj` and `'clojure.core.impl`, allocates one private runtime cell `exports` initialized to `:clojure.core/uninitialized`, and has zero `:ambient-projections`.
  - Exports only cell-free pure functions at install time:
    1. Idempotent 0-arg `init!` ([core_module.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_module.cljc#L44-L48)): checks `(data/content= (cell/get clojure.core.rt/exports) :clojure.core/uninitialized)`, populates `{sym cell}` using `clj/prim-assoc` and `clj/make-source-callable` (which mints callable cells through `clj/make-callable` at [prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L188-L194)), and stores the map into `clojure.core.rt/exports`.
    2. 1-arg `export` ([core_module.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_module.cljc#L49)): calls `(clojure.core/init!)` and looks up `(clj/prim-get (cell/get clojure.core.rt/exports) name)`.
    3. 0-arg cell-accessor exports for each public operation (`(fn [] (clojure.core/export (quote <name>)))` at [core_module.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_module.cljc#L50-L52)).
- **Callable admission & dispatch**:
  - Dynamic closures in `comp` and `partial` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L216-L231), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L368-L374)) construct callable cells via `clj/make-callable`.
  - All higher-order callbacks in `reduce`, `map`, `filter`, `comp`, `partial`, and `update` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L174), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L183), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L196), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L222), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L230), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L257-L260), [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L312)) invoke through `clj/invoke-callable`.
- **Excluded operations**:
  - `var?`, `resolve`, and `invoke-value` are completely absent from `core_impl/public-names` ([core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc#L379-L388)) and `core_module/public-names` ([core_module.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_module.cljc#L8)).

### 3. `yang.clojure` Migration & §7.4 Bundled Door Enforcement ([clojure.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure.cljc), [prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc))
- **Single standard-library source**:
  - Inline `library` and `library-for` duplication in `yang.clojure` and `test/yang/latte_oracle_test.clj` is completely retired. `with-runtime` ([clojure.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure.cljc#L1972-L1990)) wraps bundled programs with `clj-prelude/with-bundled-runtime`, `core-impl/bundled-definitions`, and `core-module/bundled-definitions`, and wraps linked units with `(clojure.core/init!)` inside `clj/run-source-unit`.
  - Retired `dsg__call_token`, `dsg__intra_call`, and unit-local provenance tokens remain absent.
- **§7.4 bundled provenance door enforcement**:
  - [provenance_scope.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yin/vm/provenance_scope.cljc#L1-L151) is untouched (`allowed-bundled-provenance-owners` hashes unchanged across `:row`, `:semantic`, `:stack`, `:register`).
  - In [prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L21-L171) and [prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L320-L328), `definitions` and `uast` (the closed `:clj/bundled-runtime` lambda that owns the sole bundled `cell/dsg__new-provenance` and `cell/dsg__provenance` sites) are unchanged from P1′.
  - The new `source-definitions` ([prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L174-L292)) contain zero direct provenance doors and are emitted outside `uast` inside `with-bundled-runtime` ([prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L335-L343)).

### 4. §9 H7 Fixtures ([effect_test.cljc](file:///Users/sto/workspace/dw-p2prime/test/yang/rt/effect_test.cljc#L190-L214), [clojure_test.clj](file:///Users/sto/workspace/dw-p2prime/test/yang/clojure_test.clj#L3139-L3145))
- **Locally-bound-map `:k` -> `9` native data-call dispatch**:
  - `clj/invoke-source` ([prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L240-L268)) routes any map `f` without `:yang.call/entry` that is not an authentic `:cell-ref` to `clj/dsg__lookup-call` (`(clj/prim-get-default coll (clj/collection-key x) default)`), alongside vectors, keywords, and sets in operator position.
  - Verified in [effect_test.cljc](file:///Users/sto/workspace/dw-p2prime/test/yang/rt/effect_test.cljc#L201) (`(let [value 9 m {:k value}] (m :k))` -> `9` in both bundled and linked modes across all four VM backends) and [clojure_test.clj](file:///Users/sto/workspace/dw-p2prime/test/yang/clojure_test.clj#L3139-L3145) (`(let [f {:k 9}] (f :k))` -> `[:yin.k/ok 9]`).
- **Bundled primitive shadowing of bare `get`/`assoc` through the unified standard library**:
  - [effect_test.cljc](file:///Users/sto/workspace/dw-p2prime/test/yang/rt/effect_test.cljc#L190-L214) shadows `get`, `+`, `<=`, and `assoc` at top level (`(defn get [m k] :shadowed)` and `(defn assoc [m k v] :shadowed-assoc)`) and verifies within the same unit that:
    1. Bare `(get nil :missing)` and `(assoc {} :k 1)` resolve through the source store to `:shadowed` and `:shadowed-assoc`;
    2. Qualified `(clojure.core/get (clojure.core/assoc {} :k 12) :k)` evaluates to `12` and `(map inc [1 2])` evaluates to `'(2 3)` without hijacking by the shadowed bare names;
    3. Dynamic map literal construction `{:k value}` routes through `clj/construct-map` when `assoc` is shadowed ([clojure.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure.cljc#L369-L375)) and `(m :k)` evaluates to `9`.

### 5. Scope Fences & §8.1 Portability
- `yang.python`, `yang.javascript`, and `yang.php` preludes and goldens are untouched.
- Zero JVM-only interop or raw host double literals in `core_impl.cljc`, `core_module.cljc`, or `prelude.cljc` (uses `(integer/to-float-boxed 1)`, `data/float64?`, `float/add`, `float/sub`, `float/format`, `integer/format`).
- Canonical collection traversal ordering is preserved via `data/canonical-entries` and verified by `replay-expectations` in [clojure_callsite_test.cljc](file:///Users/sto/workspace/dw-p2prime/test/yang/clojure_callsite_test.cljc#L604-L607).
- Every vector element access in [core_impl.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/core_impl.cljc) and [prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc) uses `data/nth`; `get` is used only on maps (or in `prim-get-default`'s non-vector branch).

---

## Findings

### F1 [NOTE] — `clj/invoke-known` uses bare `get` on `clj.rt/dsg__source-entries` map instead of `clj/prim-get`
- **Location**: [prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L197)
- **Evidence**:
  ```clojure
  (def clj/invoke-known
    (fn [name fallback args]
      (let [registered (get (cell/get clj.rt/dsg__source-entries) name)]
        (if (nil? registered)
          (clj/invoke-source fallback args)
          (clj/invoke-callable registered args)))))
  ```
- **Analysis**:
  - `clj/make-source-callable` at [prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L192) writes to `clj.rt/dsg__source-entries` via `clj/prim-assoc`, whereas `clj/invoke-known` at line 197 reads from that map via bare `get`.
  - This is functionally safe in both execution modes: in bundled mode `with-bundled-runtime` ([prelude.cljc](file:///Users/sto/workspace/dw-p2prime/src/cljc/yang/clojure/prelude.cljc#L336)) passes `source-definitions` through `core/protect-primitives`, rewriting `get` to `dsg__primitive/get`; in linked mode `clj` executes in its own module scope where `get` is never shadowed. Using `clj/prim-get` at line 197 would simply be symmetrical with lines 192 and 245. No action required for P2′ approval.

VERDICT: APPROVED
