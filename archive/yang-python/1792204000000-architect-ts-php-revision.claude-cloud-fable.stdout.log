Completed-GMT: 2026-10-17 07:28:23 GMT
Completed-Local: 2026-10-17 14:28:23 +0700

# Revised designs: yang.typescript and yang.php (round 2)

Role: Lead System Architect. Findings only; no file edited. This revision answers the codex review (`collab/1792200000000-reviewer-ts-php-designs.gpt-6.1-sol.findings.md`): all eight P1 findings (the brief names five; the review has eight), the five P2s, and the P3. Each ruling is woven into the design section it changes; unchanged sections of the round-1 design are referenced, not repeated. I verified every code citation the reviewer made (`cbor.cljc` `check-text`, `vm.cljc` `semantic-bytecode-grammar`, `yang.safepoint` site-not-lambda, `linked_harness` walker exclusion, `publication.cljc` `unpublished-import`, `compilation_pipeline.cljs` synchronous legacy compile, `yin.repl` `(lang)`), and re-read the upstream `TypeScriptParser.g4` and `PhpLexerBase.java` for findings 7 and 11. All confirmed.

Verdict on the review: every P1 is accepted. P2-9, P2-10, P2-11, P2-12 and P2-13 accepted with rulings. P3-14 accepted with one correction of the reviewer's own count.

---

## A. yang.typescript, revised

### A1. Superset posture (revised for P1-3, P1-7, P2-10)

The frontend is one id with a dialect option, as before, but the compilation profile is now pinned as data, because "TypeScript semantics" without emit flags is not a semantics:

```clojure
{:yang.ts/dialect  :ts | :js
 :yang.ts/goal     :module | :script
 :yang.ts/types    :erase | :claims
 :yang.ts/compiler {:typescript "5.6.3"            ; pinned reference, owner decision A12.2
                    :target :es2022 :module :esnext
                    :isolatedModules true
                    :verbatimModuleSyntax true
                    :preserveConstEnums true
                    :useDefineForClassFields true
                    :experimentalDecorators false}
 :yang.ts/checker  <address> | nil
 :yang.ts/strict   {...}}                           ; checker parameters only
```

Rulings that follow from the pinned flags:

- **Type-only imports and exports must be marked** (`import type`, `export type`, or inline `type` specifiers), which is what `verbatimModuleSyntax` demands of the reference compiler. An unmarked `import {X}` is a runtime import edge with a runtime binding; if the exporter has no runtime export `X`, that is the link-time `SyntaxError` of A8, not an elision. Import elision therefore does not exist in this frontend, and the erasure law is not conditional on a whole-program type analysis.
- **`const enum`** (P1-3): a local `const enum` lowers exactly as `enum` (an object; `preserveConstEnums` plus `isolatedModules` is the reference compiler's own behaviour under these flags). Member references are property reads, never inlined literals. An **ambient** `const enum` (from `declare const enum` or any declaration unit) referenced in runtime position is **refused** `:yang.ts/ambient-const-enum`, which is also what `isolatedModules` makes an error. The `.d.ts` story stays optional: no lowering ever needs a declaration's values. Round 1's "inlined as literals" rule is withdrawn.
- **`a<b>(c)`** (P2-10 and P1-7): in `:ts` the parse is the reference compiler's (type arguments when `<...>` is followed by `(`); in `:js` it is relational `(a < b) > (c)`. The grammar cannot resolve this by alternative order, so the generated parser takes a **dialect flag on the worker** read by a semantic predicate in the pinned parser base class (worker configuration, not a callback; §4.3). Dialect-disambiguation fixtures are a T0 acceptance line. The round-1 claim that the `:js` dialect's superset safety comes from refusing TS syntax alone is withdrawn; it comes from refusal plus this disambiguation.

### A5. Grammar route (revised for P1-7)

Verified against the upstream parser grammar at `master`: it has **no** conditional-type production, **no** mapped types, **no** template-literal types, **no** `import type`/`export type`, **no** `satisfies`, and indexed access `T[K]` only by accident of `ArrayPrimType`. Round 1 advertised those under "erased" as though parsed. Ruling:

- Pin an exact upstream revision at T0 (digest-pinned like Python's) and ship a **grammar patch set `ts-g4-1`** in the package manifest, through the existing exact-text `:patches` mechanism extended to `.g4` files: `import type` / `export type` and inline `type` specifiers; `satisfies`; conditional types with `infer`; mapped types with `as` clauses and `+/-readonly`, `+/-?`; template-literal types; `typeof` type queries; indexed access as its own production; `unique symbol` as already present; the dialect predicate for generic calls. ASCII, versioned, re-tested on regeneration (§4.2).
- **Acceptance by fixture, not by inventory**: T0 pins a parse fixture per advertised construct (`type X<T> = T extends string ? 1 : 2`, `import type {X} from "x"`, `x satisfies Y`, `{ [K in keyof T]?: T[K] }`, `` `prefix-${string}` ``, `typeof v`, `a<b>(c)` in both dialects). A construct whose fixture the patched grammar does not parse at T0 exit is moved from A3's "erased" column to **refused** `:yang.ts/unsupported-type-syntax`, recorded in the support profile. The A3 table is the target; T0's fixture set is the claim.
- The round-1 statement that the upstream helpers share Python's "unresolved notice" situation stands for the TypeScript helpers (no header in the Java bases); the review's correction on this point concerns PHP (B3 below).

### A6 and A7. Lowering and prelude (revised for P1-1, P1-2, P2-10)

**Strings** (P1-1). Jing refuses unpaired UTF-16 surrogates (`cbor.cljc` `check-text`), so a host string cannot carry every JS string value. Ruling: **one value, one representation, by normalization**:

- A JS string whose code-unit sequence is well-formed UTF-16 is `{:js/str s}` with `s` a host string.
- A JS string with any unpaired surrogate is `{:js/units [u0 u1 ...]}`, a vector of code units (integers 0 to 65535).
- Normalization is an invariant enforced by the prelude's string constructors: a result that is well-formed is always the `:js/str` form; one that is not is always the `:js/units` form. Concatenating `"😀"[0]` and `"😀"[1]` therefore yields `{:js/str "😀"}` again. Every operator that produces a string goes through `js/mk-str`, which checks well-formedness (a `data` export, below) and picks the form.
- Property keys use the normalized value, so `o["\uD800"]` is a `:js/units` key: plain data, legal in Jing, ordered by the ordered-map's string partition (code-unit comparison for the integer-index test is unaffected).
- Both forms are plain persistent data, so literals, publication, snapshots and continuations carry them unchanged. Addresses are a function of the normalized form only.
- `data` module additions (one version bump, Python unaffected): `str-unit-length`, `str-unit-at`, `str-units` (string to code-unit vector), `units->str` (vector to host string, or a refusal datum when ill-formed), `well-formed-utf16?`. Code-unit slicing is `(data/str-units s)` then `subvec` then `js/mk-str`.
- **Tests (T1 and T2)**: literal `"\uD800"` lowers, projects, round-trips through rows on three hosts and reads back as a `:js/units` value; `"😀"[0]`, `.slice(0,1)`, `.charCodeAt`, `.length` (code units), concatenation re-forming a pair; a `:js/units` property key set and read and enumerated; `JSON.stringify` of a lone surrogate (`"\ud800"` escape, the well-formed-stringify rule); a module exporting a lone-surrogate constant publishes and imports parserless; a continuation captured while holding a `:js/units` value in an operand frame lifts and lowers byte for byte.

**Generated names** (P1-2). `%<prefix><cst-id>` is a canonical row slot (`:lambda [:params :syms]`), so inserting an erased annotation shifts preorder ids and moves rows. Ruling: **canonical renaming before projection**, as a shared pass:

- `yang.core.names/canonicalize` walks the finished UAST in a deterministic preorder over the row grammar's slots and renames every symbol whose name begins with `%` to `%<n>` by first-occurrence order, capture-safe because `%` names are never guest names and are unique per lowering already. The pass is a pure function of the lowered tree; the tree is a function of the runtime constructs alone once type-only subtrees are consumed. Hence rows are a function of the runtime tree. `%_` (discard) and the reserved `yang.safepoint/_` are excluded.
- The TS lowering keeps using CST ids internally (simple, unique) and applies the pass in `lower-packet`, `module-packet` and `module-spec` before `mark-tails` and projection. Site marks (metadata) survive the rename.
- Python keeps its goldens: the pass is **not** applied to Python in L2. Python's names are already deterministic; adopting the pass there is an owner-scheduled golden-moving slice with no correctness need.
- **Erasure law, strengthened (T8)**: for every T1 to T7 corpus program `P`, a generator produces `annotate(P)` by inserting annotations and erased declarations **before** every loop, function, class, generated-temporary site (compound assignment, destructuring, optional chain, template literal) and between statements, plus `as`/`satisfies`/`!` wrappers on operands; `rows(annotate(P)) = rows(P)` and the roots are equal, on three hosts. A second law pins the pass itself: renaming is idempotent and host-independent.

**`for-in`** (P2-10): the spec's `EnumerateObjectProperties` informative algorithm, pinned: walk the object then its prototype chain; for each object, snapshot its own string keys in own-key order; yield a key if it is enumerable, not already visited (shadowing), and **still present** at the time of visiting (deleted-before-visit skipped); keys added during enumeration are not visited (V8's behaviour within the spec's latitude; a recorded choice). Round 1's "own enumerable only" is withdrawn.

**Script-goal globals** (P2-10, D6 corrected): `var` and function declarations are properties of the global object; top-level `let`/`const`/`class` live in a separate global declarative environment (cells with TDZ) that shadows the global object and is not reachable by property access. The REPL's persistent session keeps both.

### A8. ES modules (revised for P1-5)

Delivery was described; instantiation was missing. Ruling: **two prelude operations and a module record with explicit states**, matching ES2022 `InitializeEnvironment` and `ExecuteModule`:

Compiled module shape (replacing round 1's two-export shape):

```clojure
(do (require 'js)
    (yin/def spec {:specifier "./a/b" :goal :module
                   :bindings {"x" :let, "f" :function, "v" :var, "C" :class}
                   :imports  [{:from "./c" :import "y" :as "y"} {:from "./d" :namespace "D"}]
                   :exports  {"x" "x", "default" "%default", "y" {:re-export "./c" "y"}}
                   :star-exports ["./e"]
                   :binders  {"x" <path>, "f" <path> ...}})      ; see A10, claims
    (yin/def init (fn [%env] <assign hoisted function declarations into %env cells>))
    (yin/def body (fn [%env] <lowered module body>)))
;; :yin.module/exports #{spec init body}
```

Module record: a cell `{:js/type :module :spec :status :env {name cell} :namespace ref|nil :error}` with `:status` in `:unlinked`, `:linking`, `:linked`, `:evaluating`, `:evaluated`, `:errored`. The registry is `js.rt/modules` (specifier to record), one per task.

- **`js/link-module`** (instantiation), over the static import graph in the spec: (1) deliver every module of the graph through the I4-a thunks and create records in `:linking` (a delivery refusal is a `SyntaxError`-class link error before any body runs); (2) allocate every module's `%env`: a cell `:js/tdz` per `let`/`const`/`class`, `:js/undefined` per `var`, `:js/tdz` per function (filled next); (3) run every `init`, filling hoisted function cells (functions close over their own `%env`, so cyclic calls resolve); (4) resolve imports: an import binding's env entry **is the exporter's cell** (live binding by shared cell), namespace imports get the exporter's namespace object (an exotic object whose property reads go through the env cells, frozen); `export * from` resolved transitively with ambiguity detection; a **missing export is a link error** raised here, before any evaluation (fixture: no side effect of any body is observed). Cycles need no special case: envs exist before any resolution. Record `:linked`.
- **`js/evaluate-module`**: post-order DFS over the same graph with `:evaluating` as the cycle guard (a module reached while `:evaluating` is skipped, which is the spec's rule); runs `body`; an exception marks `:errored`, stores the error, and every later import of that module rethrows the stored error (the spec's evaluation-error caching); success marks `:evaluated`. TDZ reads during a cycle raise `ReferenceError`.
- **Dynamic `import()`**: link then evaluate the sub-graph rooted at the literal specifier, returning a promise; the registry makes it idempotent.
- **Publication**: unchanged in mechanism (`jsm.<mangled>`, I5 SCC bundles). The SCC unit exports one `spec`/`init`/`body` triple per member; alias modules re-export them; the link graph the prelude walks is the spec graph, so bundling is invisible to instantiation. Python's lazy bodies are not reused for TS instantiation; only the publication mechanics are.
- **Fixtures (T7)**: `a` imports `b`, `b` calls `a`'s exported function during `b`'s body before `a`'s body ran (works, returns the function's value); the same with an exported `let` (ReferenceError); `import {missing} from "./m"` where `m` has a side-effecting body: link error, no side effect, `m` not in the registry as evaluated; a module whose body throws, imported twice: the same error object both times, body run once; a star re-export chain with a shadowing named export; live binding update visible after `export let x; x++`; dynamic import of an already-evaluated module resolves without re-running.

### A10. Claims and declaration units (revised for P1-4; was A4 and T10)

The claim entity is `[canonical-root path]` (typecheck.md §2.2). Round 1 pointed claims at manifest addresses and at erased declarations that have no occurrence. Ruling, as a contract:

1. **Target root**: a claim's root is the module's **code root** `A` (the `module-packet` tree). A manifest pins `A`; the frontend resolves manifest to `A` when minting claims about a consumed module.
2. **Export-to-occurrence**: `spec` carries `:binders {name path}`, the structural path (row-grammar slot positions, the same path vocabulary `yang.safepoint` uses) from `A` to the **binder occurrence** of each top-level binding: the parameter position in the locals lambda of `body` (or `init` for functions). Paths into `body` do not depend on `spec`'s content, so no circularity. For the `js` prelude the binder occurrence of an export is its `(yin/def 'k v)` application node in the module tree.
3. **Type-only declarations** (interfaces, aliases, ambient declarations, generics' parameters) have no runtime occurrence. They become the module's **type environment**, one claim on the module's `spec` binder occurrence: `[[A path-of-spec] :yin.sig/type-env {"Foo" <type-value> ...} t m]`. Named-type references inside other claims resolve against that environment lexically (by module, then by its imported type-only names, recorded in the environment as `{:import "./x" "T"}`), which is the checker's job (typecheck.md decision 8: the type language is the checker's). This document fixes only that the environment is a claim on an occurrence that exists.
4. **Annotation claims**: `[[A path-of-binder] :yin.sig/type <type-value> t m]` for annotated bindings, parameters, return types (claim on the function's lambda occurrence), and class members (claim on the class's constructor-function binder with a member map).
5. **The claims artifact of a declaration unit** (`.d.ts`, `js-lib.d.ts`, an effect binding's stubs): `{:yang.ts.claims/target <manifest address>, :root A, :claims [[path attribute value] ...]}`, published as datoms with the target pinned; minting requires the target manifest as an explicit pinned input of the compilation request (so a `.d.ts` is compiled **against** a module, never floating). A `.d.ts` compiles to zero program rows and this artifact only.
6. **`claims-t`**: the frontend appends a unit's claims to the `claims` medium before appending its program envelope, and the envelope carries `:yang.claims/t`, the claims cursor after the last claim, as typecheck.md §2.2 item 1 allows via the batch envelope. Under `:erase` nothing is appended and the key is absent.
7. **Identity**: none of the above touches rows. `:binders` lives in `spec`, so adding or removing an *exported runtime binding* moves `A` (that is module identity, correct); adding an annotation or interface moves nothing.

**T10 acceptance, now testable**: for every corpus module, each `:binders` path resolves to a `:lambda` parameter occurrence of the named binding (a test walks `A`); `js-lib.d.ts` claims resolve to the prelude's `yin/def` occurrences; annotating a program appends claims whose roots and paths equal the unannotated program's; a `report`-topology composition with the T1 claims-consistency checker refuses `f("x")` given a declared `(n: number) => void` on `f`'s binder and the evaluator output is unchanged; `claims-t` barrier: the observer does not evaluate a packet before its claims arrive (a delayed-append fixture).

### A9. Safepoints and matrix (revised for P1-8)

Linked execution runs on the semantic, stack and register VMs; **the AST walker under the linked profile waits on L-b**, exactly as the Python harness states. Round 1's "all four VMs" for T1 onward is qualified: bundled profile on four evaluators; linked profile on three until L-b lands, after which the matrix widens with no slice change. This is an explicit prerequisite row, not a nondependency.

### A11. Slices (changes only)

| Slice | Change |
|---|---|
| L2 | adds `yang.core.names/canonicalize` (not applied to Python) and the `data` string exports (`str-unit-length`, `str-unit-at`, `str-units`, `units->str`, `well-formed-utf16?`) |
| L4 | acceptance now includes (P2-12): a second toy frontend with its own `:primitives` installed; `(lang)` switch installs the selected binding's primitives and drops `:frontend-session`; `(reset)` isolation between frontends; Python output unchanged |
| T0 | adds the `ts-g4-1` patch set, the per-construct parse fixtures, the dialect predicate and its `a<b>(c)` fixtures, the pinned upstream revision, the refusal list produced at exit |
| T1 | adds the string representation and its tests; `for-in` is in T2 with the pinned enumeration fixture |
| T7 | replaces "bodies in post-order" with link then evaluate; the module record states; the seven fixtures above |
| T8 | the strengthened erasure law with insertion-before-constructs; `import type` required; `verbatimModuleSyntax` fixtures (unmarked type-only import is a link error) |
| T9 | `const enum` as object; ambient `const enum` refusal fixture; parity against the reference compiler's emit under the pinned flags |
| T10 | the claims contract above |

### A12. Owner decisions (revised)

1. One id with a dialect option, unchanged.
2. Reference runtime: Node LTS version, **and** the TypeScript compiler version and flag set above (new: the flags are profile data).
3. `:erase` default, no checker in Phase 1, unchanged.
4. Decorators deferred, unchanged.
5. `yrt` shared prelude timing, unchanged.
6. `data` module bump: now five code-unit exports (was two).
7. **New**: the two-form string representation by normalization (recommended) versus code-unit vectors for every string (uniform, costly) versus refusing lone surrogates (a semantic departure the reviewer rightly rejects).
8. **New**: canonical `%` renaming applied to TS from T1 and to Python in a later golden-moving slice (recommended), versus never for Python.
9. **New**: ambient `const enum` refused (recommended) versus mandatory declaration inputs.
10. **New**: grammar patch set with fixture-gated narrowing at T0 (recommended) versus upstream verbatim with a narrower surface.

---

## B. yang.php, revised

### B1. Paradigm (revised for P1-6, P2-9)

**References: ownership and rebinding** (P1-6). Round 1's "always move into a fresh shared cell" nests markers and keeps unshared references alive through array copies. Ruling, with the invariant **markers never nest** and a deterministic holder count standing in for the engine's refcount:

- A shared cell holds `{:value v :holders n}`. A marker `{:php/ref c}` may sit in a variable cell, an array slot or an object property.
- **Rebinding** `$x =& $y`: if `$y`'s location already holds a marker, `$x`'s location receives the same marker and `holders` increments. Otherwise `$y`'s content moves into a fresh shared cell with `holders 2` and both locations receive the marker. `$x`'s previous marker, if any, is released (decrement). `$a =& $a[0]`-style self references follow the same two steps on the two locations.
- **Release**: `unset($x)` of a marker-holding location, rebinding away, and reassignment of an array slot that held a marker decrement `holders`. **Function return does not release** the locals' markers (there are no finalizers; this is the departure stated under owner decision B8.8).
- **Demotion at write**: a write to a location whose marker's `holders` is 1 replaces the marker by the written value (the reference is unshared, so PHP would have unwrapped it at the next separation). A read through any marker dereferences one level. This reproduces the reviewer's fixture: after `unset($r)` the slot's `holders` is 1; `$b = $a` copies the value; `$b[0] = 2` demotes `$b`'s slot; `$a[0]` still reads 1.
- **Array copy with live references**: an array value carries `:refs`, the set of slot paths holding markers (usually empty). Copying an array whose `:refs` is non-empty increments `holders` for each (O(k), k = number of reference slots; ref-free arrays stay O(1)). This reproduces `$r =& $a[0]; $b = $a; unset($r); $b[0] = 2` leaving `$a[0]` as 2, since the copy raised `holders` to 3 and `unset` to 2.
- **Nested lvalues**: `php/path-set` walks the path; at each level a marker is dereferenced into the shared cell (writing through when `holders > 1`, demoting when 1); a marker at the final slot follows the same rule; `$a['x'][0] =& $b` installs the marker at the path and records it in `:refs`.
- **Renderer**: `var_dump` and `debug_zval_refcount`-free output show a `&`-less value for `holders 1`, as PHP does.
- **Departure, documented**: a reference whose last extra holder left scope without `unset` keeps `holders > 1` and so keeps sharing through later array copies where PHP would have unwrapped it. Fixture pinned as a known departure. Everything else in the reviewer's list is reproduced.

**Semantic corrections** (P2-9), each becoming an oracle fixture rather than a summary:

- `static $x = <expr>;` initializers are arbitrary expressions evaluated **when execution first reaches the declaration** (PHP 8.3); the cell is owned by the function object, allocated at function creation with an "uninitialized" sentinel and filled once.
- `PHP_INT_MIN % -1` is `0`; `intdiv(PHP_INT_MIN, -1)` throws `ArithmeticError`; `% 0` and `intdiv(x, 0)` throw `DivisionByZeroError`; `fmod` never throws.
- Loose equality is **the PHP 8 comparison table, pinned as a fixture matrix** (every pair of `null`, `true`, `false`, `0`, `1`, `-1`, `"0"`, `""`, `"1"`, `"01"`, `"1e1"`, `"10"`, `"abc"`, `" 1"`, `"1 "`, `[]`, `[0]`, `0.0`, `NAN`, `INF`, an object, a `Stringable`): round 1's shorthand grouping `0 == "" == []` was wrong (`0 == ""` and `0 == []` and `"" == []` are all false in PHP 8; `0 == "0"`, `"" == null`, `[] == false`, `"1" == "01"`, `"10" == "1e1"`, `100 == "1e2"` are true).
- `@` lowers the expression's diagnostics with `error_reporting()` temporarily reduced to the PHP 8 mask (`E_ERROR | E_PARSE | E_CORE_ERROR | E_COMPILE_ERROR | E_USER_ERROR | E_RECOVERABLE_ERROR`); a custom handler installed by `set_error_handler` is **still invoked** and observes the reduced value; only the default output is silenced. Fixture: a handler counting calls under `@`.

### B3. Grammar route (revised for P2-11)

- `PhpLexerBase.java` carries an explicit MIT notice (Kochurkin and Marianne); it is **vendored** beside the `.g4` files like Python's grammars, with its notice; round 1's "same unresolved-notice situation" was wrong for PHP and is withdrawn.
- The helper rewrites tokens: a `?>` close tag becomes a `SemiColon`-typed token on the skip channel or with its type changed (text `?>` kept, so the exporter sees real text and a non-synthetic span, type `SemiColon`); heredoc end tokens get constructed or rewritten text (`identifier + ";\n"` or `"\n;"`), which the exporter classifies as synthetic with a zero-width span. Ruling: no helper patch for heredoc ends; the lowering derives heredoc and nowdoc content from the **spans of the body tokens**, never from an end token's text; the close-tag `SemiColon` tokens are handled by type. **P0 fixtures**: a heredoc with indentation removal (7.3 rule) and interpolation, a nowdoc, `?>` followed by a newline and by text, `<?=`, a close tag ending a statement without `;`, each asserting the exported spans cover exactly the source and that synthetic tokens appear only where expected. If a fixture shows the lowering needs a rewritten token's source, the Python-style positioned-token patch is added to the manifest at P0, not later.

### B4. Lowering (no change) and B6 (revised for P1-8, P2-13)

**Safepoints** (P1-8): `yang.safepoint` refuses a selected site that is not a `:lambda` (`site-not-lambda`), and `:line` insertion is Python's pending safepoint slice 3. Ruling: statement-level ticks (`declare(ticks)`, `register_tick_function`) **depend on the shared `:line` extension of `yang.safepoint`** (slice 3 of the safepoint design: a prefix insertion before a marked non-lambda node, which is also what Python tracing needs). P7 splits: **P7-a** signals via `:loop`/`:call` (no dependency); **P7-b** ticks, behind safepoint slice 3, with the tick-count fixture. Neither L1 to L4 nor this frontend extends `yang.safepoint`; the extension is the safepoint seat's.

**Linked matrix** (P1-8): as for TS, linked execution on three VMs; the walker under the linked profile waits on L-b; bundled on four. P1 onward is so qualified.

**Missing includes** (P2-13): two stages, two meanings. (1) **Publication**: every static `include`/`require` edge with a literal path must be pinned to a concrete address; a target absent from the unit set and from `published` is refused `:unpublished-import` by the lifted publisher (the governing ruling at `yang.antlr.md` 8.5.6 "publication still requires a concrete address"). There is **no** publication-time representation of an optional absent include; that is the ledger's open item ("publication-time-unavailable optional imports cannot be represented without concrete segment pins"), not solved here. (2) **Run time**: "missing" means the pinned content is unavailable or delivery is refused (`:absent` with `:address`, mismatch, untrusted principal): `include` emits the PHP `Warning: include(...): Failed to open stream` record and yields `false`; `require` throws `Error` with PHP's fatal text. Both stages are P6 fixtures: an unpublished target refused at publication by name; a pinned-but-unserved target warning versus `Error`.

### B7. Slices (changes only)

| Slice | Change |
|---|---|
| P0 | heredoc/close-tag span fixtures; vendored MIT helper |
| P1 | static initializer timing, `%`/`intdiv` edge fixtures, the comparison matrix fixture, `@` with handler fixture |
| P3 | the reference ownership rules; the reviewer's two fixtures plus: double rebinding `$b =& $a; $c =& $a;` (one shared cell, `holders 3`, no nesting); unset of one of three; array-copy with a live reference; nested path reference; the documented scope-exit departure fixture |
| P6 | the two-stage missing-include fixtures |
| P7 | split into P7-a (signals) and P7-b (ticks, behind safepoint slice 3) |
| P8 | the browser demo (`compilation_pipeline.cljs`) compiles PHP synchronously in CLJS with the legacy frontend; the ANTLR parser is JVM-only. Ruling: the demo moves to **checked-in CST packets** for its sample programs (generated by a JVM script, the `dht-import-v1.edn` pattern), lowering and running parserless on the browser host; editable PHP source in the demo is dropped or routed to a configured parser service when one exists. Removal of the legacy file waits for that retarget; `src/dev/subsystems.edn` is a one-line edit |

### B8. Owner decisions (revised)

1 to 7 unchanged (reference 8.3.x, byte strings, dynamic-scope profile, warnings as records, safepoint budget, replace-and-delete, autoload class map).
8. **New**: the holder-count reference model with the scope-exit departure (recommended) versus O(n) slot scans at every array copy (exact, kills O(1) copy) versus ignoring unshared-reference unwrapping (the reviewer's fixture fails).
9. **New**: the demo goes fixture-based (recommended) versus removing PHP from the demo.
10. **New**: optional absent includes stay refused at publication until the ledger's open item is ruled (recommended).

---

## C. Corrections of record (P3-14) and matrix

- `free-names` and `defined-keys` are already shared from `safepoint.cljc` through `prelude/*`; D7 is narrowed to the emitter, placeholder, strip, module-spec and `admit` duplication.
- The legacy PHP test file's `deftest` count is immaterial to P8 (the reviewer counted nine; a grep counts ten lines); the ruling is "every program in that file re-expressed in the corpus".
- ADR 0004 is untracked and Proposed on the inspected tree. Both designs apply it because the task mandates its doctrine; where a ruling rests on it (request records, bound surfaces, refusal of ambient resolution) that dependency is named so the owner can see what a rejection of the ADR would reopen. Nothing else in the designs depends on it.

Acceptance matrices: unchanged in shape from round 1, with the two qualifications above (linked execution on three VMs until L-b; PHP ticks behind safepoint slice 3) written into the "Host" and "Support claim" rows.

---

## D. Revisions I expect the reviewer to probe

1. **The two-form string normalization**: whether every string-producing path (template literals, `String.fromCharCode`, `JSON.parse` of `"\ud800"`, `split("")`, `replace`) goes through `js/mk-str`; whether `:js/units` keys sort correctly against `:js/str` keys in own-key order; and whether the cost of the well-formedness check on every concatenation is acceptable (I expect a request to make it incremental).
2. **Canonical `%` renaming**: whether first-occurrence preorder is stable under the safepoint stage's wrapping (it is: renaming runs before projection and the stage reconstructs from rows with already-canonical names) and whether the erasure law's generator inserts in enough positions; also why Python does not adopt it now.
3. **The claims contract**: whether paths in `:binders` are stable across the SCC bundling (the bundle's `A` differs from the member's; the alias module's spec must carry paths into the bundle root) and whether a `type-env` claim on the `spec` occurrence is acceptable to the typecheck design's authors as "an occurrence of a binder".
4. **Module instantiation**: the `init`-before-import-resolution order for cyclic function hoisting; namespace object semantics; error caching across dynamic imports; and whether `init` as a third export disturbs the linker's `spec`/`body` conventions (it should not: exports are a set).
5. **PHP reference holder counts**: the scope-exit departure, and whether `:refs` tracking survives nested array copies and `array_*` functions that rebuild arrays (they must copy `:refs` or drop markers deliberately; I expect a request for an explicit rule per builtin family).
6. **Grammar patch set**: whether patching `.g4` text through the manifest mechanism is acceptable versus forking the grammar into the repository, and the size of the conditional/mapped-type patch.
7. **The `const enum` and `verbatimModuleSyntax` pins**: whether the reference compiler at the pinned version truly emits a local `const enum` as an object under `isolatedModules` alone (it does under `preserveConstEnums`, which is why both flags are pinned).
