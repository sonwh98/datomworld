---
description: Configurable and composable preludes for Yang language frontends — a three-tier hybrid architecture (pure host value core, unified guest %capture algebraic effects, minimal VM stream/cell effects) and portable semantics descriptors for native and Yin/Clojure frontend profiles.
---

# yang.preludes: configurable and composable preludes for Yang frontends

**Status:** Proposed design, 2026-11-21 (r8 — r7 reconciliation: selector, entry ABI, effect unwind, role evidence, slice repair). No implementation or completed acceptance gate is claimed.

**Revision history:**
- **2026-11-20 (r1 → r2 reconciliation):** Addressed all 10 findings (6 HIGH, 4 MEDIUM) from Lead System Architect review [`collab/yang-base/1795169993265-architect-yang-preludes-design.gpt-6-astra.findings.md`](../../collab/yang-base/1795169993265-architect-yang-preludes-design.gpt-6-astra.findings.md).
- **2026-11-21 (r2 → r3 reconciliation):** Addressed all 8 remaining findings (`F1`–`F8`: 5 HIGH, 2 MEDIUM, 1 LOW) from Lead System Architect r2 review [`collab/yang-base/1795186644731-architect-yang-preludes-design-r2.gpt-6-astra.findings.md`](../../collab/yang-base/1795186644731-architect-yang-preludes-design-r2.gpt-6-astra.findings.md).
- **2026-11-21 (r3 → r4 reconciliation):** Addressed all 13 remaining findings (9 HIGH, 3 MEDIUM, 1 LOW) from Lead System Architect r3 review [`collab/yang-base/1795196337796-architect-yang-preludes-design-r3.gpt-6.2-astra.findings.md`](../../collab/yang-base/1795196337796-architect-yang-preludes-design-r3.gpt-6.2-astra.findings.md).
- **2026-11-21 (r4 → r5 reconciliation & clean-slate simplification):** Addressed all 9 remaining findings (7 HIGH, 1 MEDIUM, 1 LOW) from Jupiter Pool-2 r4 review [`collab/yang-base/1795198164100-architect-yang-preludes-design-r4-jupiter.gpt-6.2-astra.findings.md`](../../collab/yang-base/1795198164100-architect-yang-preludes-design-r4-jupiter.gpt-6.2-astra.findings.md) under the **Owner Clean-Slate Directive** (standardized prelude emitters uniformly and unified `clj` + `clojure.core` without a separate bridge or dual legacy Clojure stdlib).
- **2026-11-21 (r5 → r6 hybrid architecture & focused profile scope):** Eliminated guest-UAST host-leak workarounds and collapsed fragmented control subsystems into a **three-tier hybrid model** (aligned with Unison, Koka, and OCaml 5): (1) promoted representation-sensitive pure ops (`data/float64?`, `data/equiv`, `data/canonical-compare`, `data/canonical-entries`, `data/admit-key?`, `kind/namespace`, and boxed IEEE-754 `yin.vm.float`) into `:pure` host primitives backed by `dao.jing.cbor` / `dao.host.*`, eliminating raw-double stack-frame hazards and guest-side $O(n \log n)$ sorting; (2) unified `yrt.escape`, `yrt.gen`, safepoint interrupt dispatch, and cold error/limit policy traps into one composable guest algebraic-effect layer (`yrt.effect`) over `%capture` with zero Jolt kernel expansion; (3) focused swappable preludes on **Native profiles** (`py`, `js`, `php`, `clj` for existing code in those languages) plus **N → 1 Yin/Clojure profiles** (`yang.python/clojure-core-v1`, future `yang.typescript/clojure-core-v1`), dropping unnecessary off-diagonal N×M symmetry (such as `:yang.clojure/py-v1`).
- **2026-11-21 (r7 → r8 reconciliation):** Addressed all findings from the r7 architect review (thread `01a1faa3-bb7e-7c30-b3bf-cedbad008bfc`; 8 HIGH, 1 MEDIUM, plus default-profile regressions): (H1) explicit default-selector mechanism — the composition's immutable configuration carries the preferred `[id revision]`, applied at the normal request entry, with two-revision / legacy-pin / in-flight-snapshot gates; (H2) per-frontend **entry-lowering contracts** emitting the landed native entry shapes (`py` 1-arg `run-main`, `js` 1-operand, `php` 2-or-4 with request stream) alongside the Clojure resolver-bearing 2-arg shape; (H3) ONE derivation record (frontend/lowerer revision, descriptor, resolved runtime closure, input, output), pins reconciled by module symbol before lowering, dependency-first installation in linked composition, resolver derivation in provenance / live cell in the task; (H4) complete effect-unwind traversal per frame kind with exactly-once cleanup, a P2′ cold-error policy for plain `yang.clojure`, `clojure.core.impl` owning zero cells while declaring its runtime effects; (H5) address-bound export-**role** evidence (same-arity wrong-role fails closed); (H6) the §5.1 example labeled a Track B recipe recording bundled shape, linked trailing forms, pins, and C1 behavior independently; (H7) slice repair — P1′ clj-only synthetic fixtures, P2′ native collection invocation + bundled shadowing, P3′ the real cross-syntax unwind; (H8) boxed conversion/true-division at the host boundary, zero-raw-double scoped to boxed-path profiles; (M9) `yang.core.defquoted` naming consistency, r6 gates attached verbatim to Track B, oracle assignment (legacy pins ← native oracles, default ← capability refusals); (R) default-profile regressions — unqualified `:k` admission, cross-carrier integer identity, `:clj/unbound` distinguishability, canonical order settled to Jing encoded-byte order.
- **2026-11-21 (r6 → r7 Clojure-First Default & Legacy Track, plus landed-DRY reconciliation):** Three owner directives. **(D10) Default-profile inversion:** for `yang.python` (and later `yang.typescript`/`yang.php`), the **N → 1 Yin/Clojure profile is the DEFAULT** runtime profile; the native preludes (`py`/`js`/`php` semantics) become the **Legacy compatibility track** — kept frozen as landed (they already exist, oracle-parity-proven, at their current content addresses), with their *re-architecture* onto `yrt.*`/boxed float deferred until legacy demand justifies it. **(Clojure-first roadmap):** the implementation order re-sequenced so the Clojure prelude family (`yrt.effect`/`yrt.int` with Clojure-only consumers, unified trusted `clj`, `clojure.core.impl` + `clojure.core` facade, `clj.py`, the python lowerer's descriptor parameterization) is built FIRST — the other-language native preludes are untouched by those slices (zero golden churn; defquoted consolidation and emitter standardization for py/ts/php defer to the legacy track). **(DRY-alignment reconciliation):** §5.2 admission checks home in `yang.core.prelude-admission` (3→5 publics, at bound) keeping `yang.core.prelude` pure emission; §8.2 names both the guest linker modules (`yrt.*`) and their host emitter namespaces (`src/cljc/yang/rt/*.cljc`) and acknowledges the analyzer's 3 self-loop warnings (macro self-reference, no multi-node cycles); defquoted consolidation accounted against the public-surface ledger; §2 gains the tao-architecture second-axiom alignment (the semantics descriptor is stream-resident interpreter configuration; the provenance tuple names the interpreter).

**Authority:** Subordinate to [`datom.world.md`](./datom.world.md) (axioms, invariants, streams, host boundaries), [`adr/0004-external-effects-are-interpreted-stream-deposits.md`](./adr/0004-external-effects-are-interpreted-stream-deposits.md) (external effects as stream deposits), [`yang.antlr.md`](./yang.antlr.md) (§3 frontend SPI, §8.1–§8.2 semantic placement, §8.5.5 float addresses, §8.5.6 linked modules, §8.10 shared calling convention, §8.11 cells, §9 four-layer stdlib model), [`yang.typescript.md`](./yang.typescript.md) (§2.2 shared rulings, §17 lift enumeration, §19 owner decision A12.5), [`yang.php.md`](./yang.php.md) (§9 prelude inventory), [`yin.vm.linker.md`](./yin.vm.linker.md), [`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md), [`dao.jing.cbor.md`](./dao.jing.cbor.md), and [`src/cljc/yin/vm/docs/state.md`](../../src/cljc/yin/vm/docs/state.md).

**Scope, Owner Clean-Slate Directive, and amendments:** This document settles owner decision A12.5 of `yang.typescript.md` §19 (the deferred `yrt` shared prelude lift) and extends `yang.antlr.md` §3.1/§3.4/§9.3 to treat preludes as **composable runtime modules** and **swappable frontend libraries** supporting both **native language profiles** (for existing Python, TypeScript, PHP, and Clojure programs) and **N → 1 Yin/Clojure profiles** (allowing familiar syntaxes such as `yang.python` or `yang.typescript` to compile directly onto `clojure.core` and persistent Yin data). Per the Owner Clean-Slate Directive (single-user system, zero legacy deployments), clean rewrites, uniform emitter normalization, direct `yang.clojure` migration onto `clojure.core`, and clean golden regeneration are explicitly preferred over maintaining dual legacy runtimes or frozen-quirk compatibility shims. It adds no UAST node type, no VM opcode, no manifest schema version, and no closed language registry.

---

## 1. Problem statement and repository baseline

### 1.1 Current repository baseline

Two sharing mechanisms already exist in `src/cljc/yang/`:

1. **Host-side emitter and admission machinery (`yang.core.*`):**
   - `yang.core.prelude` (`src/cljc/yang/core/prelude.cljc`): pure UAST builders and symbol strippers (`define`, `strip`, `module-definitions`, `wide-app`, `module-tree`, `exports`, `free-names`, `defined-keys`, `free-name-vec`, `definitions->uast`, `balanced-definitions->uast`).
   - `yang.core.prelude-admission` (`src/cljc/yang/core/prelude_admission.cljc`): `host-declaration`, `module-spec`, and `admit` over `yin.vm` and `yin.vm.module`.
   - `yang.core.module` and `yang.core.publication`: shared linker module naming, free-primitive declaration, SCC bundle/alias specs, and dependency-first publication.
   - *(Residual host helper duplication:)* the ClojureDart AOT `defquoted` macro is still duplicated across `yang.python.antlr.prelude`, `yang.typescript.antlr.prelude`, and `yang.php.antlr.quoted`.
2. **One shared guest runtime module (`yang.core.call`):**
   - `src/cljc/yang/core/call.cljc` emits the language-neutral calling convention (`yang.call`) using `yang.core.prelude` and `yang.core.prelude-admission`, with both a bundled tree (`uast`) and a linked module (`module-uast`, `module-spec`). All four frontends (`py`, `js`, `php`, `clj`) compose against it.

Despite that shared plumbing, all remaining guest runtime code lives in four separate language preludes and three safepoint hook preludes:

| Prelude namespace | Linker module | Size / definitions | Lowering coupling |
|---|---|---|---|
| `yang.python.antlr.prelude` | `py` | 4,188 LOC / 343 defs | `yang.python.antlr.lower` hardcodes 103 `py/*` names + `{:py/str}`, `{:py/float}`, `:py/None`, `:py/unbound` |
| `yang.typescript.antlr.prelude` | `js` | 4,447 LOC / 475 defs | `yang.typescript.antlr.lower` hardcodes 93 `js/*` names + `:js/undefined`, `:js/null`, `:js/tdz`, `{:js/str}` |
| `yang.php.antlr.prelude` (+ `_module`, `_p9`, `builtins`) | `php` | 8,150 LOC / 936 defs | `yang.php.antlr.lower` hardcodes 188 `php/*` names + `:php/null`, `{:php/type :array}`, etc. |
| `yang.clojure.prelude` (+ inline family in `yang.clojure`) | `clj` | 250 LOC / 17 defs (+ `library`) | `yang.clojure` emits `clj/*` continuations and `yang.call/*` directly |
| `yang.*.antlr.safepoint` | `pysp`, `jssp`, `php.sp` | 478 / 235 / 70 LOC | Hardcoded hook profiles |

### 1.2 Three problems to solve together

1. **Host representation leaks in guest UAST:** Bare VM primitives (`+`, `-`, `=`, `get`, `assoc`, `vec`, `kind/str`) delegate directly to host values, leaking JVM vs. Node/CLJS vs. Dart/CLJD vs. Jolt differences (`Float64` boxing, `BigInt` mixing, `NaN`/`-0.0` equality, CLJS joined-keyword collapse `(keyword nil "a/b") == (keyword "a" "b")`, HAMT map/set iteration order, float formatting). Patching around those leaks in guest UAST produces hundreds of lines of slow, fragile code (`admit-key!`, $O(n \log n)$ `canonical-compare`, raw-double leaf-frame rules).
2. **Duplicated, fragmented guest control mechanics:** ~1,200–2,000 lines of guest UAST across `py`, `js`, `php`, and `clj` duplicate `%capture` handler stacks, coroutine stepping (`gen-switch`), safepoint signal handling, and numeric error-status plumbing as four separate mechanisms instead of one composable guest algebraic-effect layer.
3. **Hardcoded syntax-to-prelude coupling:** A frontend lowering currently assumes one fixed runtime profile. Even though `yang.frontend` (`src/cljc/yang/frontend.cljc`) already tracks `:yang.frontend/grammar`, `:yang.frontend/lowering-profile`, `:yang.frontend/runtime-profile`, and `:yang.frontend/support-profile` independently, frontends cannot yet offer both their **native semantics profile** (for existing code in that language) and a **Yin/Clojure profile** (compiling Python or TypeScript syntax directly onto `clojure.core` and persistent Yin values).

---

## 2. Derivation from foundations and non-negotiable invariants

This design is constrained directly by `docs/design/datom.world.md`, `docs/design/adr/0004-external-effects-are-interpreted-stream-deposits.md`, and `src/cljc/yin/vm/docs/state.md`.

### 2.1 Foundational axioms & three-tier hybrid rationale

1. **Everything is a Stream:** Prelude selection, composition, and lowering are pure functions of explicit data configurations and CST streams. External I/O and host capabilities remain declared VM/stream effects (`stream/*`, `cell/*`, `:module/require`) per ADR 0004; prelude composition adds no ambient I/O.
2. **Interpretation Creates Semantics:** A CST packet carries syntax only; its runtime meaning emerges from `(cst-packet, lowering-profile, semantics-descriptor, runtime-profile)`. Interpreting one Python CST under `:yang.python/py-v1` (CPython semantics) versus `:yang.python/clojure-core-v1` (Clojure persistent-data semantics) is a direct realization of "one truth, many perspectives."
3. **Code and State are Datoms (and Unitarity / Derive-Don't-Persist):**
   - Lowering preserves source CST identity; compiled UAST roots under different preludes have distinct content addresses (or identical addresses when two descriptors emit identical UAST) linked by explicit provenance datoms carrying the §6.5 compilation derivation record (r8: frontend/lowerer revision, descriptor address, resolved runtime closure, input, output — reconciled by module symbol on every compilation, including a single lowerer revision paired with a conflicting runtime pin).
   - Zero new AST tags, zero VM opcodes, and zero manifest schema changes are introduced. Linker manifests retain schema 1, and C1 `:yang/call-site` tables continue to publish beside manifests rather than inside them.
   - Composition facts (which `yrt.*` modules a prelude requires) live in the explicit module `:requires` DAG, never as a redundant tag in UAST rows.
4. **Everything is a Continuation (Hybrid Effect Boundary):**
   Real-world algebraic-effect systems converge on one clean boundary: **Unison** keeps pure built-in value types (`Nat`/`Int`/`Float`/`Text`/`Bytes`) in the runtime, models control/errors/streams as pure user-handled abilities, and reserves only `IO` for the runtime; **Koka** keeps total math/data primitives effect-free (`total`) and draws the effect line at partiality (`exn`), divergence (`div`), state (`st`), and I/O; **OCaml 5** keeps hot synchronous ops native, reserves effect handlers for resumable control flow, and forbids effects from crossing raw native/C frames. Yin/Yang adopts the exact same three-tier split:
   - **Pure host value core (`:pure` primitives):** Total, representation-sensitive math, equality, canonical ordering, key admission, and text ops stay native and fast across all 4 hosts.
   - **Guest algebraic effects (`yrt.effect` over `%capture`):** Control flow (exceptions, `finally`, PHP `abandon-to`, resumable `yield`) and cold policy traps (`:math/div-zero`, `:math/overflow`, `:int/bit-limit`, `:int/digit-limit`, `:coll/unsupported-key`, `:runtime/interrupt`) live in pure guest UAST — zero Jolt kernel expansion.
   - **VM / stream effects (`make-effect` → `:effect-handlers`):** Strictly `cell/*`, `stream/*`, `:module/require`, and bound external surfaces per ADR 0004.

### 2.1a Alignment with the tao architecture's second axiom (`yang.tao.md` §2.2)

The semantics descriptor and profile pinning are a direct application of the governing axiom **Everything is a Stream** and its invariant *Do not introduce callbacks; every callback is an event on a stream*:

- **Interpreter configuration is stream-resident data.** `yang.tao.md` §2.2 requires that "the grammar, name environment, and elaborator configuration participate in interpretation" and that "an interpretation result must name both its input and the interpreter that produced it." The hardcoded `py/*`-style coupling in the current lowerers buried that configuration in host code; the `:yang.semantics/descriptor` and `:yang.prelude/profile` lift it into portable, content-addressed artifacts, and the compilation **derivation record** names the interpreter exactly: `(frontend/lowerer revision, descriptor address, resolved runtime closure, input address, output address)` — one record, consistent with `yang.antlr.md`'s compilation derivation contract; the resolver's *derivation* belongs in provenance while its *live cell* belongs to the task.
- **"One truth, many perspectives" is a stream operation.** The same Python CST packet is one truth; `py-v1` and `clojure-core-v1` are two perspectives. Selecting between them — the wolf-in-sheep's-clothing inversion — is a *re-pin of stream-resident artifacts* (the descriptor and profile addresses), never a callback into the lowerer and never ambient prelude state.
- **The "explicit local functions" clause is where the ambient resolver lives:** the `:kind :cell-accessor` projections resolve through the unit-bound `ambient-resolver` passed as data (`[body-fn ambient-resolver]`) — a task-local cell of cells, explicit, named in the provenance, and validated against verified UAST before use.

Consequence: switching a frontend's default semantics is a *data re-pin* (revision selection in `yang.frontend`), carrying zero code change and zero content-address churn for unselected profiles.

### 2.2 Non-negotiable invariants

1. **No hidden global state — stateless `yrt.*` modules:**
   - `yang.core.*` holds no mutable global registry of preludes or languages (`yang.frontend` immutable catalog rule).
   - **Shared `yrt.*` modules own zero `*.rt/*` state cells.** Every stateful `yrt.*` function takes the caller's task-local state cell (e.g., `ctx-cell`) as an explicit parameter. When a single Yin task links independent foreign language runtimes (`py`, `js`, `php`, `clj`) together via `yang.call`, each foreign runtime maintains its own isolated handler stack and depth/base accounting inside its own `*.rt/ctx` cell rather than aliasing or corrupting another language's stack.
   - **Intentional intra-Clojure domain sharing:** By explicit architectural design, `clj`, `clj.py`, `clojure.core.impl`, and `clojure.core` execute *inside* the Clojure semantics and exception domain and therefore share `(clj/rt-ctx)` intentionally so Clojure `(throw ...)` / `(try ... (finally ...))` and Python-syntax `try` / `except` / `finally` unwind across `clj → clj.py → clj` nesting on one coherent handler stack while saving and restoring `(clj/rt-ctx)` at every `yrt.effect/try-value` and `finally` boundary.
2. **No implicit control flow:** All escapes and resumptions remain explicit continuation invocations over an explicit handler chain.
3. **No raw callbacks & no raw host doubles in guest frames:** Policy handlers and closures passed into `yrt.*` are ordinary guest Yin closures, never host-boundary callbacks. By boxing IEEE-754 float operations at the `yin.vm.float` host boundary, unwrapped host doubles never appear on guest stacks **on boxed-path profiles (the Clojure family)**, making `%capture` and safepoints safe there — the frozen native preludes retain their landed, separately-validated numerics until Track B (r8 scope note).
4. **No shared mutable state:** Each stateful profile allocates its own task-local cells once per task in its own idempotent `init!` under its own private namespace (`py.rt/*`, `js.rt/*`, `php.rt/*`, `clj.rt/*`, `clj.py.rt/*`, `clojure.core.rt/*`); `clojure.core.impl` is pure and stateless.
5. **No layer collapsing:** Parsing (host ANTLR boundary) → lowering (syntax + frontend lowering contract + semantics descriptor → UAST) → prelude composition & admission (pure spec/artifact verification; never publishes or executes) → publication/linking → VM execution stay separated across explicit stream/module boundaries.
6. **No assumed graphs:** Every composed prelude declares an explicit, acyclic `:requires` DAG over an **immutable, verified artifact closure**. Missing exports, missing transitive artifacts, or cycles fail closed at validation/admission time before publication or execution.
7. **No closed language registry (`yang.antlr.md` owner directive):** Neither `yrt.*` nor `yang.core.*` contains a `case`/dispatch over `:python`, `:typescript`, `:php`, or `:clojure`, inspects `:py/type`/`:js/type`/`:php/type`/`:clj/type` tags, or encodes language-specific lowering ABI tables.
8. **Security boundary — VM provenance doors stay private:** Under `src/cljc/yin/vm/docs/state.md` and `src/cljc/yin/vm/module.cljc`, `cell/dsg__new-provenance` and `cell/dsg__provenance` are reserved compiler/runtime-module doors whose names guest code must not forge or re-export.
   - **`yrt.*` modules, `clj.py`, and Layer-2c standard libraries (`clojure.core.impl`, `clojure.core`) MUST NEVER declare (`:yin.module/primitives`) or export (`:yin.module/exports`) provenance-door primitives directly.** (Transitive `:yin.module/footprint` unions are governed by `:yang.prelude/max-effects`.)
   - **No descriptor hook or source-facing ambient projection** may expose `cell/dsg__*`, `dsg__*`, `yin/def`, `*.rt/*`, `*.b/*`, `yang.safepoint/*`, or non-projectable internal implementation modules such as `*.impl/*` (`clojure.core.impl/*`) (`§6.3`).
   - Retained protocol-condition provenance (`yang.antlr.md` §8.10.7) resides strictly inside trusted Layer-2b runtime modules (`py`, `js`, `php`, **`clj`**) in **both** bundled (`uast`) and linked (`module-uast`) emission, never exported and never exposed across `yrt.*`, Layer-2c libraries, or source scope.

---

## 3. Three-tier hybrid architecture & prelude layering

`yang.antlr.md` §9.1 Layer 2 ("Language intrinsics and preludes") sits directly on top of a small, portable `:pure` host value core and minimal VM effect boundary:

```text
+-----------------------------------------------------------------------------+
| Layer 2c: Ambient & Standard Libraries (clojure.core, clojure.core.impl, …) |
|   Ordinary compiled linker modules; explicit projections enter source scope |
+-----------------------------------------------------------------------------+
                                      ^
                                      | binds / lowers through
+-----------------------------------------------------------------------------+
| Layer 2b: Semantics-Protocol Libraries (py, js, php, clj, clj.py)           |
|   Language value encodings, coercions, effect handlers, state cells, init!  |
+-----------------------------------------------------------------------------+
                                      ^
                                      | composes explicitly via :requires DAG
+-----------------------------------------------------------------------------+
| Layer 2a: Composable Guest Building Blocks (yang.call, yrt.effect, yrt.int, |
|   yrt.safepoint) — stateless guest UAST over %capture + :pure host core     |
+-----------------------------------------------------------------------------+
                                      ^
              +-----------------------+-----------------------+
              |                                               |
+-------------+-------------------------+     +---------------+---------------+
| Tier 1: Native Host :pure Core        |     | Tier 3: Minimal VM Effects    |
|   data | integer | float | kind       |     |   cell | stream | :module/req |
|   (universal truth, zero suspension)  |     |   (state & ADR-0004 I/O ABI)  |
+---------------------------------------+     +-------------------------------+
```

### 3.1 Litmus test: Native Host `:pure` vs. Guest `%capture` Effects vs. VM Effects

| Tier | Mechanism | Inclusion criteria | Examples |
|---|---|---|---|
| **Tier 1: Native Host `:pure` Core** | Host module exports (`:pure`, effects `#{}`) in `data`, `integer`, `float`, `kind` | **All four hold:** (1) one universal mathematical/representational truth across all languages and all 4 hosts; (2) host-representation-sensitive (avoids `Float64`/`BigInt`/keyword/HAMT leaks); (3) total or status-returning without suspending control flow or calling guest closures; (4) hot-path value operation. | Exact big-int math, boxed IEEE-754 float math & formatting, `data/float64?`, `data/equiv`, `data/canonical-compare`, `data/canonical-entries`, `data/admit-key?`, `kind/namespace`, code-point strings. |
| **Tier 2: Guest Algebraic Effects** | `yrt.effect` over `%capture` + task-local handler stack (100% guest UAST) | **Any holds:** (1) language-specific or swappable policy (`OverflowError` vs. `Infinity` vs. PHP int64 demotion, test replay); (2) non-local control flow (exceptions, `finally`, PHP `abandon-to`, resumable `yield`); (3) cold/error path; (4) must invoke guest closures. **Zero Jolt kernel cost.** | `raise`/`try`/`finally`, `call-ec`, `abandon-to`, resumable `yield` generators, cold traps (`:math/div-zero`, `:math/overflow`, `:int/bit-limit`, `:int/digit-limit`, `:coll/unsupported-key`), `:runtime/interrupt`. |
| **Tier 3: VM / Stream Effects** | `make-effect` → VM `:effect-handlers` | State, module loading, and external world capabilities only (ADR 0004). Never language-specific semantics. | `cell/*`, `stream/*` (`stream/poll!` safepoints), `:module/require`, bound external I/O surfaces. |

### 3.2 Explicit exclusions from Layer 2a (`yrt.*`)

The following remain in Layer 2b (per-language preludes) because unifying them either breaks semantic fidelity or forces a closed type-tag dispatch:

| Mechanism | Why it stays in Layer 2b |
|---|---|
| **Ordered maps / dicts / arrays** | Python dicts normalize `1 == 1.0 == True` via exact rationals and tuple-key recursion; JS own-key maps sort uint32 indices ahead of strings and symbols; PHP arrays coerce int64-looking strings, track `:next-index`, and implement copy-on-write refcounted handles. |
| **Module / import loaders** | Python `sys.modules` + package hierarchy, JS ESM link/eval stacks + live bindings, and PHP dynamic `include`/`require` scope-cell adoption have incompatible lifecycle rules. |
| **Object / class / prototype dispatch** | MRO/descriptors (`py`), prototype chains/accessors (`js`), and traits/refcounted destructors (`php`) belong to `yang.antlr.md` §9.4 Layer 3. |
| **Provenance doors** | `cell/dsg__new-provenance` and `cell/dsg__provenance` are security-sensitive VM doors (`§2.2` invariant 8). |
| **PHP `php.sp` budget/ticks hooks** | `php.sp` uses a decrementing execution-budget cell (`php.sp/remaining`) and `:line :after` statement ticks rather than `stream/poll!` signal cursors. |

---

## 4. Host `:pure` core extensions & Layer 2a (`yrt.*`) specifications

### 4.1 Native host `:pure` core extensions (`data`, `float`, `kind`)

Existing `yin.vm.integer` (`src/cljc/yin/vm/integer.cljc`, v4) and `yin.vm.data` (`src/cljc/yin/vm/data.cljc`) already establish the `:pure` host-module pattern: total or keyword-status-returning kernels over `dao.host.*` and `dao.jing.cbor` with zero guest callbacks and identical semantics across JVM, CLJS/Node, CLJD/Dart, and Jolt/Scheme.

To eliminate host representation leaks and raw-double stack-frame hazards across all frontends, we add a small, cohesive set of `:pure` exports (zero changes to VM opcodes or `yin.vm/primitives`):

1. **Additions to `yin.vm.data` (`src/cljc/yin/vm/data.cljc`):**
   - **`data/float64? [x]`:** Backed directly by existing `dao.jing.cbor/float64?` (`src/cljc/dao/jing/cbor.cljc:212`). Distinguishes float64 carriers from exact integers, booleans, and maps on all 4 hosts.
   - **`data/equiv [a b]`:** Portable IEEE-aware, kind-strict value equality (distinct from content-addressing `data/content=`):
     - Exact int × exact int: `integer/compare == 0`; float64 × float64: `false` if either is `NaN`, else IEEE numeric equality (`+0.0 == -0.0` is `true`, `NaN == NaN` is `false`); exact int × float64: **`false`** (`1 != 1.0`).
     - Keywords/symbols: strict kind + nullable namespace + name equality, so `(keyword nil "a/b") == (keyword "a" "b")` is **`false`** on CLJS/Node as well as JVM/Dart/Jolt.
     - Persistent vectors/lists, maps (excluding `:cell-ref` handles), and sets: recursive pairwise/entry equality using `data/equiv`.
     - Other scalars (`nil`, booleans, strings, authenticated `:cell-ref` handles): host `=`.
   - **`data/admit-key? [k]`:** Recursive portable key/element predicate preventing host map/set collapse (`yin/vm/data.cljc:34–36`, `dao.jing.cbor.md:173, 266`). Returns `true` iff `k` (recursively across vectors, maps, and sets) contains only `nil`, booleans, exact integers, strings, and keywords/symbols whose `name` is free of `"/"` and not starting with `":"` — **an absent namespace is admitted (r8): the namespace-non-empty rule applies to two-part keywords only, so an unqualified `:k` admits**. Returns `false` for any `float64?` (at any nesting depth), ambiguous identifiers, seqs/lists, closures/continuations, or `:cell-ref` handles. **Integer-carrier normalization (r8):** distinct exact-integer carriers (`int64` vs boxed) normalize recursively on insertion AND lookup so equal integers hit the same entry.
   - **`data/canonical-compare [a b]`:** Total, deterministic comparator over admitted keys/elements. **Normative order (r8):** `dao.jing.cbor.md:173`'s unsigned encoded-byte order is the SINGLE ordering rule — including for compound keys, where elements compare by their encoded bytes rather than a separate recursive clause (the r7 recursive-lexicographic wording is withdrawn as it could disagree with encoded bytes for different-length vectors); identical results on all 4 hosts.
   - **`data/canonical-entries [coll]`:** For a map, returns a vector of `[k v]` pairs sorted ascending by `data/canonical-compare` on `k`; for a set, returns a vector of elements sorted ascending by `data/canonical-compare`. Eliminates guest-UAST $O(n \log n)$ sorting for `keys`, `vals`, `seq`, `reduce`, and `str`.
2. **Boxed IEEE-754 host module `yin.vm.float` (`src/cljc/yin/vm/float.cljc`, module name `'float`):** **Host-boundary conversion rule (r8):** boxed conversion and true-division results are exposed AT the host boundary (`integer/to-float` results and `true-div` quotients enter guest frames only as boxed `data/float64`); the zero-raw-double guarantee is scoped to profiles on the boxed path (the Clojure-family profiles). The frozen native ABI of the legacy preludes is unchanged.
   - Operates exclusively on **boxed `data/float64`** inputs and outputs (plus exact integers where specified), returning either a boxed `data/float64`, a boolean/integer/string, or a pure status keyword (`:yin.vm.float/div-zero`, `:yin.vm.float/underflow`, `:yin.vm.float/out-of-range`, `:yin.vm.float/fractional-exp`, `:yin.vm.float/infinite-exp`).
   - **Zero raw host doubles in guest UAST:** Because guest code never unwraps a host double across stack frames, `%capture` effects and safepoints can never observe an unwrapped host double — retiring the fragile raw-double leaf-frame rules of r1–r5 completely.
   - **Exports (all `:pure`, effects `#{}`):**
     - Constants/predicates: `inf`, `neg-inf`, `nan`, `zero`, `neg-zero`, `nan?`, `finite?`, `zero?`, `neg-zero?`, `zero-like`.
     - Core IEEE arithmetic: `add`, `sub`, `mul`, `div` (returns boxed IEEE result, or `:yin.vm.float/div-zero` on `div-checked` when `y == 0.0`), `neg` (preserves `-0.0 ↔ +0.0`), `abs`, `compare` (`-1`/`0`/`1` or `:yin.vm.float/nan`).
     - Division/floor/power: `floor` (total IEEE double floor), `floor-int` (exact integer for finite `|x| < 2^53`, else `:yin.vm.float/out-of-range`), `fmod-pos` (Sterbenz remainder for finite `x >= 0, y > 0`), `floordiv-mod` (returns `[q r]` boxed pair or `:yin.vm.float/div-zero`, preserving `-0.0` quotient sign and `2.1 // 0.7 == 3.0` rounding correction), `fpow-half` (half-power squaring for `py`/`js`/`clj.py`), `fpow-base` (base squaring for `php`, preserving `1.1 ** 6` bit-exact oracle parity — see D7), `neg-pow` (`[x n pow-order]`, returns boxed `float64` or `:yin.vm.float/underflow`), `parts` (`[mantissa exp2]` exact integer pair).
     - Formatting: `format` (deterministic 4-host decimal/scientific formatting over `dao.host.float/float-digits`, rendering `NaN`, `Infinity`, `-Infinity`, `0.0`, `-0.0`, `1.0 -> "1.0"` identically on JVM, Node, Dart, and Jolt).
3. **Addition to `yang.clojure/kind-module`:**
   - **`kind/namespace [x]`:** One `:pure` unary export backed by `namespace`, enabling deterministic symbol/keyword rendering in `clojure.core.impl`.

### 4.2 `yang.call` *(existing Layer-2a leaf)*

Remains unchanged at `src/cljc/yang/core/call.cljc` (`yang.antlr.md` §8.10): structural callable discovery (`entry`, `eligible?`), invocation records (`invocation`, `invocation-with`, `valid-invocation?`), and condition transfer (`condition`, `thrown`, `transfer`, `invoke`, `invoke-with`). Requires no `yrt.*` module so `yang.call` stays a leaf dependency.

### 4.3 `yrt.effect` — Unified guest algebraic effects over `%capture`

Used by: `py`, `js`, `php`, `clj`, `clj.py`, `yrt.int`, `yrt.safepoint`. Host dependencies: `cell/get`, `cell/set!`, `cell/new`, `data/nth`.

Every Layer-2a module follows `yang.core.call`'s single-source dual-emitter pattern: one ordered definition vector emitting both a bundled `uast` (tail-marked) and a linked `module-uast` + `module-spec` (wide-app over stripped definitions, C1-finalized call sites, and `prelude-admission/module-spec`).

`yrt.effect` replaces separate `yrt.escape`, `yrt.gen`, and ad-hoc error-callback plumbing with **one composable guest algebraic-effect handler model** over `%capture` and an explicit task-local `ctx-cell`:

- **Unified context record:** `{:handlers hs :depth d :base b :frame f}`.
  - **4-to-5-slot migration for `js` and `clj`:** In P1, `js/init!`, `clj/init!`, and bundled `yang.clojure` migrate from 4-slot frames to `(yrt.effect/ctx nil 0 0 nil)` and 5-slot frames `[kind payload rest n depth]`:
    - `kind`: `:finally` (cleanup frame), `:handler` (abortive exception handler), `:effect` (tagged resumable/abortive effect handler), or any language-installed boundary tag (such as `:generator` during P1→P4 migration).
    - `payload`: cleanup closure `fin` (for `:finally`), abortive continuation `r` (for `:handler`), or `[tag handler-fn outer-k]` (for `:effect`).
    - `rest`: enclosing frame (or `nil`).
    - **`n` (slot 3):** monotonic handler-stack frame count `(+ 1 (frame-depth rest))` for $O(1)$ structural depth comparison (`<= (data/nth hs 3) n`).
    - **`depth` (slot 4):** activation-relative call depth restored onto `:depth` by `pop-frame` (`py`/`php` recursion accounting; `0` for `js`/`clj`).
- **Capture-safe vector frame reads (`data/nth`):** Every read of the 5-slot frame vector uses **`data/nth`**, reserving `get` strictly for context-map fields (`:handlers`, `:depth`, `:base`). Source-defined `(defn get ...)` can never corrupt frame dispatch.
- **Continuation-portability envelope (r8, VM-engineer review):** `%capture` continuations are **task-local, non-portable, resumable only within their task** — `state.md`'s lift encoder refuses closures/continuations as `:yin.k/non-portable`, so generator suspension cannot serialize a paused `k` across tasks or streams. The `restore!` `:base` rule is sufficient within a task's suspension/resumption, not across tasks; Track-B generator checkpointing to streams is out of scope for this design.
- **Unwind traversal semantics (r8):** `raise` delivers to the first non-`:finally` boundary; on the way down every `:finally` frame runs its cleanup closure **exactly once**, and every `:effect` frame is popped with its context restored (an `:effect` frame carries no cleanup closure — a guest throw crossing an unrelated effect handler is a plain unwind past it, never an error). `perform` walks `ctx-cell` to the nearest matching `:effect` frame **without** running `:finally` frames; only when the handler aborts without resuming does the abort path run the traversed `:finally` cleanups during that unwind. In all cases the context is restored to the surviving frame's state and each cleanup runs at most once per activation.
- **`restore!` invariant:** `(fn [ctx-cell saved] (cell/set! ctx-cell (assoc saved :base (get (cell/get ctx-cell) :base))))` — preserves live `:base` across generator/coroutine crossings.

**Public exports:**

| Category | Export | Signature | Behavior |
|---|---|---|---|
| **Context & frames** | `ctx`, `handlers`, `set-handlers!`, `restore!`, `frame-depth`, `frame`, `pop-frame` | `[...]` | Stateless context-cell accessors and 5-slot frame constructors (`restore!` preserves live `:base`). |
| **Abortive control (exceptions & escapes)** | `raise` | `[ctx-cell e on-empty]` | Unwind `:finally` frames passing `e`; `:effect` frames are popped with context restored (no cleanup closure — a throw crossing an unrelated effect frame is a plain unwind past it); deliver `e` to the first enclosing `:handler` or task-boundary frame — `:effect` frames are NEVER delivery targets, they are popped-and-passed (r8 shorthand clarified: "non-`:finally` boundary" means `:handler`/boundary only, never an `:effect` frame); call `(on-empty e)` if empty. **One rule per frame kind (r8):** `:finally` → run cleanup exactly once, continue unwinding; `:effect` → pop, restore, continue unwinding; `:handler`/boundary → deliver and stop. Gate: a throw past an unrelated effect frame with exactly-once cleanup. |
| | `try-value` / `try-orelse` | `[ctx-cell body handler]` / `[ctx-cell body handler orelse]` | Install `:handler`; on normal exit restore and return `(body)` (or tail-call `(orelse)` for `py`/`php`/`clj.py`); on escape restore and call `(handler e)`. |
| | `try-finally` | `[ctx-cell body fin normal-arg]` | Install `:finally`; on normal exit restore, run `(fin normal-arg)`, return `v`. |
| | `unwind-to` / `call-ec` | `[ctx-cell n normal-arg]` / `[ctx-cell f normal-arg]` | Pop frames down to depth `n` running `:finally` thunks with `normal-arg` (`:py/None`, `:js/undefined`, `:php/null`, `nil`), then restore and jump. |
| | `abandon-to` / `call-terminal` | `[ctx-cell n]` / `[ctx-cell f]` | Pop frames down to depth `n` **without** running `:finally` thunks (PHP fatal exit). |
| **Resumable & tagged algebraic effects** | `handle` | `[ctx-cell tag handler-fn body]` | Install an `:effect` frame for `tag` around `(body)`. When `(perform ctx-cell tag payload)` fires inside `body`, `yrt.effect` captures the delimited continuation `k`, restores the outer context, and invokes `(handler-fn payload k)` — where `handler-fn` may either abort (returning a value or calling `raise`) or resume `(k v)` one-shot with a computed value. |
| | `perform` | `[ctx-cell tag payload on-unhandled]` | Captures the current continuation via `%capture`, walks `ctx-cell` to the nearest matching `:effect` frame for `tag` (without running `:finally` frames unless the handler aborts without resuming), and invokes `(handler-fn payload k)`. If no matching handler exists, calls `(on-unhandled tag payload)`. |

- **One mechanism for four subsystems:**
  1. **Exceptions & escapes:** `try-value`, `try-orelse`, `try-finally`, `call-ec`, `call-terminal` (abortive fast path).
  2. **Cold numeric/resource policy traps:** Pure host ops (`yin.vm.integer`, `yin.vm.float`, `data/admit-key?`) return values on the hot path and status keywords on the cold path. Thin guest wrappers call `(yrt.effect/perform ctx :math/overflow ...)` / `:math/div-zero` / `:int/bit-limit` / `:int/digit-limit` / `:coll/unsupported-key` only on the cold branch — letting each language install its own policy handler (e.g., Python `OverflowError`/`MemoryError`/`ZeroDivisionError`, JS `Infinity`/`NaN`, PHP int64-to-float demotion, or deterministic test replay).
  3. **Generators / coroutines (Track B — deferred):** `yield` is simply `(yrt.effect/perform ctx :gen/yield value)` handled by a coroutine driver that stores the captured continuation `k` and resumes `(k sent-val)` on `.next()`/`.send()`, with explicit `on-start-ctx` / `on-resume-ctx` hooks preserving Python `:base` rebasing vs. PHP direct context restoration and refcount cleanup. **The `get` → `data/nth` frame-read conversion is a BEHAVIORAL migration (r8, VM-engineer review):** the landed py/php frames read slots via bare `get`; migrating them to `data/nth` adds capture-safety against guest `(defn get ...)` shadowing — the Track-B bundled==linked parity gate must prove the shadowed-`get` case for the migrated preludes, not only for `clj`.
  4. **Safepoint interrupts (Track B — deferred):** `yrt.safepoint` polls `stream/poll!` (VM effect) and performs `:runtime/interrupt` via `yrt.effect` when a signal arrives (Python maps to `KeyboardInterrupt` / registered signal handler; JS maps to uncatchable root unwind).

**Capture-safety for bundled `yang.clojure`:**
- When `yrt.effect/uast` is bundled inline into non-linked `yang.clojure`, `[get = assoc]` bind to portable guest closures `[dsg__get dsg__equal dsg__assoc]` (with `dsg__get` supporting both maps and vectors via `data/nth`), `conj` relies on binder-check refusal, and `+`/`<=` use portable guest closures `dsg__add`/`dsg__lte`. In linked mode, module lexical isolation makes `dsg__*` unnecessary.

### 4.4 `yrt.int` — Exact-integer adapters and radix scanners

Used by: `py`, `js`, `php`, `clj.py` (omitted by `clj`). Host dependencies: `data/count`, `data/nth`, `integer/*`. Requires: `yrt.effect`.

- **`yin.vm.integer` v4 refusal algebra (`src/cljc/yin/vm/integer.cljc:81–100`):** Returns keyword reasons for `:yin.vm.integer/bit-limit`, `:yin.vm.integer/digit-limit`, and `:yin.vm.integer/float-overflow`; every other refusal (`:arity`, `:wrong-type`, `:out-of-range`, `:syntax`, `:zero-division`, `:negative-count`, `:negative-exponent`) throws a host operational failure and **MUST NOT** be caught or relabeled into guest exceptions.
- **Public exports:**
  - `check-limit [ctx-cell res]` (and direct callback variant `check-limit-with [res on-bit on-digit on-float]`): returns `res` untouched on the hot path; on any of the three limit keywords, performs `:int/bit-limit`, `:int/digit-limit`, or `:math/overflow` via `yrt.effect/perform` (or invokes the supplied policy closure).
  - Fixed-width wrapping: `wrap-unsigned [x width-pow2 mask]`, `wrap-signed [x half-range range]`, `in-range? [x min-v max-v]` (backing `js/wrap32` and `php/wrap64` / `php/int-result`).
  - Generic radix digit scanners: `digit-value [cp]`, `all-digits? [cs i n radix]`, `scan-digits [cs i n radix acc]`, `skip-while [cs i n pred?]`, `skip-zeros [cs i n]`.
  - Per-language integer floors (`min-integer-bits = 53` for `py`/`js`/`clj.py`; `min-integer-bits = 4096, min-integer-digits = 1000` for `php`) remain enforced by each profile's admission check.

### 4.5 `yrt.safepoint` *(Track B — deferred)*

Extracts `stream/cursor`, `stream/poll!` gap/signal detection, and `attach!` setup into one shared module (`yrt.safepoint`, requiring `yrt.effect` and host `stream`). When `stream/poll!` returns a signal, `yrt.safepoint` performs `:runtime/interrupt` via `yrt.effect/perform`, keeping signal delivery policy (`KeyboardInterrupt` in `pysp`; uncatchable root `Interrupt` unwind in `jssp`) and recursion depth counting (`pysp`) in each hook module.

---

## 5. Declarative prelude composition, recipes, and admission

To eliminate ad-hoc `module-spec` and `admit` boilerplate while keeping every runtime dependency completely reproducible and content-pinned, **build-time emission recipes** and **immutable deployment profiles** are separated cleanly.

### 5.1 Emission recipe vs. resolved runtime profile

1. **`:yang.prelude/recipe` (host-side compiler recipe that *emits* a runtime module) — the example below is a TRACK B recipe** (a legacy re-architecture recipe for a native prelude; the P0′ recipes for the Clojure-family modules use the standardized fields):
   ```clojure
   {:yang.prelude/schema 1
    :yang.prelude/id :yang.python/py-prelude
    :yang.prelude/revision "2026-11-21.1"
    :yang.prelude/module 'py
    :yang.prelude/components
    [[yang.call "<blake3-address>"]
     [yrt.effect "<blake3-address>"]
     [yrt.int "<blake3-address>"]]
    :yang.prelude/host-modules
    {:cell {:required true}
     :data {:required true, :min-max-items 1048576}
     :integer {:required true, :min-bits 53}
     :float {:required true}}
    :yang.prelude/emitter
    {:mode :linear                 ;; :linear (definitions->uast) | :balanced (balanced-definitions->uast)
     :uninit :py/uninit
     :rt-namespaces #{"py.rt" "py.b"}
     :unshadow-locals? false
     :trailing-requires []         ;; `[]` for base preludes (`py`/`js`/`php`/`clj`/`yang.call`), `['py]`/`['js]` only for safepoint overlays
     :finalize-c1? true            ;; annotate `:yang/call-site 1` uniformly across all emitted preludes
     :capture-safe-primitives nil} ;; optional for bundled clj mode
    :yang.prelude/max-effects nil  ;; optional effect-ceiling set; nil = derive without extra ceiling
    :yang.prelude/refusal-ns "yang.python.antlr"}
   ```
2. **`:yang.prelude/profile` (immutable, content-addressed runtime profile after publication):**
   Replaces local build knobs with `:yang.prelude/root-address` (the published content address of the owning runtime module) alongside the pinned transitive closure:
   ```clojure
   {:yang.prelude/schema 1
    :yang.prelude/id :yang.python/py-prelude
    :yang.prelude/revision "2026-11-21.1"
    :yang.prelude/module 'py
    :yang.prelude/root-address "<blake3-address-of-published-py-module>"
    :yang.prelude/components
    [[yang.call "<blake3-address>"]
     [yrt.effect "<blake3-address>"]
     [yrt.int "<blake3-address>"]]
    :yang.prelude/host-modules
    {:cell {:required true}
     :data {:required true, :min-max-items 1048576}
     :integer {:required true, :min-bits 53}
     :float {:required true}}
    :yang.prelude/max-effects nil}
   ```
   A `:yang.prelude/profile` contains zero host functions or closures and passes `yang.frontend/non-portable-path`.

### 5.2 Composition rules and verified-closure admission

`yang.core.prelude` and `yang.core.prelude-admission` enforce six fail-closed checks. **Home rule (DRY-alignment, r7):** the composition checks (`validate-profile`, `admit-profile`, and their six sub-checks) are implemented in **`yang.core.prelude-admission`** (3 → 5 publics, exactly at the bounded-complexity target), keeping `yang.core.prelude` pure UAST/s-expression emission — the emission/admission boundary is the landed batch-1 seam (`1604aabe`), and the C2 lesson (module duplicating prelude's `host-declaration` was the duplication removed) requires one admission authority, not two namespaces sharing the checks:

1. **Verified artifact closure input (`validate-profile`):** Takes `(profile artifact-store registry)`, where `artifact-store` supplies an immutable content-addressed map of published manifests (`manifest-addr -> verified-manifest`) AND their referenced canonical module trees (`tree-addr -> verified-uast`). Verifies that direct root pins match `:yang.prelude/components`, walks the full transitive `:requires` DAG from `:yang.prelude/root-address`, confirms finiteness and acyclicity, and refuses duplicate bindings for the same linker module symbol.
2. **Export collision rule (qualified vs. projected):** Distinct Layer-2a/2b/2c modules may share bare local export names across qualified module borders; collisions are refused on duplicate linker module symbols, duplicate stripped local `module-definitions` keys, or duplicate source-facing `:ambient-projections` aliases (`§6.3`).
3. **Provenance-door security exclusion (direct declarations, not transitive footprint):** Checks each non-trusted module's (`yrt.*`, `clj.py`, `clojure.core.impl`, `clojure.core`) **direct** `:yin.module/primitives` (refusing `cell/dsg__new-provenance` and `cell/dsg__provenance`) and **direct** `:yin.module/exports` (refusing any `dsg__*` export on **every** module including trusted Layer-2b runtimes), while governing transitive `:yin.module/footprint` through `:yang.prelude/max-effects`.
4. **Effect-ceiling verification (`:yang.prelude/max-effects`):** Optional ceiling checked via `(clojure.set/subset? derived-transitive-effects max-effects)` over the verified `:yin.module/footprint :effects` union.
5. **Host-module and limit admission (`admit-profile`):** Takes `(profile artifact-store registry host-limits)`, verifies every required host export (`prelude-admission/admit`), and enforces declared numeric floors (`:min-bits`, `:min-digits`, `:min-max-items`) against `host-limits` (failing closed if absent or below floor).
6. **Dual-emitter standardization & Parity Law:** Standardized linked emission applies to the **P0′ Clojure-family modules** (`:trailing-requires []`, `:finalize-c1? true`) — it does NOT retroactively standardize the frozen native preludes (r8: landed `py` ends its module tree with `require yang.call` and has no C1 finalizer there; php likewise; the bundled clojure continuation family is a left fold — the `:mode` field alone cannot freeze these, so each frozen runtime records its bundled shape, linked trailing forms, dependency pins, and C1 side-table identity-or-absence **independently**). Any program compiled under a composed prelude MUST produce identical outcomes in bundled and linked modes across the semantic, stack, and register VMs (and the AST walker once L-b lands).

---

## 6. Preludes as swappable libraries: the semantics descriptor contract

### 6.1 Focused profile scope: Yin/Clojure DEFAULT + Native legacy track

Swappable preludes serve two concrete, complementary goals. **Per owner directive D10 (r7), the Yin/Clojure profiles are the DEFAULT runtime profiles** for the parameterized frontends; the native profiles become the Legacy compatibility track, kept frozen as landed and re-architected later only on demand:

1. **Yin/Clojure profiles (`N → 1` — the DEFAULT, e.g. `:yang.python/clojure-core-v1`):** New `datom.world` code, scripts, and agents are written in familiar Python or TypeScript syntax compiled directly onto `clojure.core`, persistent Yin collections, and `dao.stream` effects — with zero mutable-object baggage or marshalling overhead. The wolf in sheep's clothing: the syntax is the familiar grammar; the semantics are Clojure's persistent, immutable, functional core. Per the truth-in-advertising invariant (§7.1) the descriptor *declares* this — capability refusals fail closed, and equality/truthiness differences are contract, not accident.
2. **Native language profiles (`py`, `js`, `php` — the Legacy compatibility track, built later):** Preserve full, faithful CPython/Node/PHP semantics for existing libraries and code that assumes them. The landed preludes already provide this at their current content addresses and remain the regression net (their oracle suites keep passing untouched). What defers is their *re-architecture* onto `yrt.*`/boxed float — a separate legacy-modernization track, undertaken only when legacy demand justifies it. Until then they run exactly as landed: frozen mechanisms, zero golden churn, selectable by pinning their revision.

**Explicit non-goal (avoiding unnecessary N×M complexity):** We do **not** require arbitrary off-diagonal swaps (such as compiling `yang.clojure` syntax onto Python mutable dicts or PHP syntax onto JS prototypes). `yang.clojure` compiles directly onto unified `clj` + `clojure.core` without being forced through a generic hook ABI. Non-Clojure frontends (`yang.python`, `yang.typescript`, `yang.php`) parameterize their lowering through `:yang.semantics/descriptor` to select between their native profile and a Yin/Clojure profile (or custom ambient library projections).

### 6.2 Core vs. Frontend ownership boundary

To preserve `yang.antlr.md` §3 ("Yang core contains no enumeration of supported languages"):
- **`yang.core.semantics` (language-agnostic)** owns ONLY:
  1. Generic schema validation for `:yang.semantics/descriptor`.
  2. Pure, closed literal-strategy emission (`:raw`, `:float64-raw`, `:tagged-map`, `:raw-or-call`).
  3. Generic capability lookup, hook-hygiene validation (every hook must be namespace-qualified), UAST-derived structural export/arity/role verification against the descriptor's **selected, address-verified closure** across **every** executable descriptor field (hooks, executable literal strategies such as `:literals.*.overflow-op`, and ambient projections), and reserved-door projection checks.
- **Each parameterized frontend package (e.g. `yang.python.antlr.contract`)** owns:
  1. Its **fixed Hook ABI table** (`{hook-key {:arity n :required-by [...]}}`) specifying exact parameter conventions and structural lowering shapes for that frontend's grammar.
  2. Structural AST lowering rules marshaling CST operands to those hooks.

### 6.3 Portable `:yang.semantics/descriptor` schema

A semantics descriptor contains **only portable data** (satisfies `yang.frontend/non-portable-path`):

```clojure
{:yang.semantics/schema 1
 :yang.semantics/id :yang.python/clojure-core-v1
 :yang.semantics/syntax :python
 :yang.semantics/prelude-module 'clj.py
 :yang.semantics/ambient-modules
 [{:module 'yang.call,          :address "<blake3-address>"}
  {:module 'yrt.effect,         :address "<blake3-address>"}
  {:module 'yrt.int,            :address "<blake3-address>"}
  {:module 'clj,                :address "<blake3-address>"}
  {:module 'clojure.core.impl,  :address "<blake3-address>"}
  {:module 'clojure.core,       :address "<blake3-address>"}
  {:module 'clj.py,             :address "<blake3-address>"}]

 :yang.semantics/capabilities
 {:mutation/locals      :cell          ;; :cell | :single-assignment | :refused
  :mutation/subscript   :refused       ;; :supported | :refused
  :mutation/attribute   :refused
  :mutation/delete      :refused
  :scope/global         :supported     ;; per-module globals cell only
  :scope/nonlocal       :supported     ;; lexical cells when :mutation/locals is :cell
  :calls/positional     :supported
  :calls/varargs        :supported     ;; *args
  :calls/defaults       :refused
  :calls/kwonly         :refused
  :calls/kwstar         :refused
  :calls/kwargs         :refused
  :destructuring/unpack :refused       ;; explicit choice; or :supported via clj.py/unpack
  :expr/comprehensions  :supported     ;; eager list/set/dict only; genexpr gated by :control/generators
  :control/exceptions   :supported     ;; bare `except:` + `finally` + `raise`
  :control/except-typed :refused       ;; class-matched / typed `except ExcType [as e]:` refused when :oo/classes :refused
  :control/generators   :refused       ;; gates BOTH `yield`/`yield from` AND generator expressions
  :control/with         :refused
  :oo/classes           :refused
  :modules/import       :ambient-only} ;; :supported | :ambient-only | :refused

 :yang.semantics/literals
 {:nil     {:kind :raw, :value nil}
  :bool    {:kind :raw}
  :str     {:kind :raw}
  :float   {:kind :float64-raw}      ;; vs {:kind :tagged-map, :tag :py/float}
  :int     {:kind :raw-or-call, :safe-max-bits 53, :overflow-op 'clj.py/int-lit}
  :unbound {:kind :raw, :value :clj/unbound}}

 :yang.semantics/hooks
 {;; Arithmetic & comparisons — ALL namespace-qualified (hygienic, immune to guest shadowing)
  :op/add           'clj.py/add
  :op/sub           'clj.py/sub
  :op/mul           'clj.py/mul
  :op/truediv       'clj.py/truediv
  :op/floordiv      'clj.py/floordiv
  :op/mod           'clj.py/mod
  :op/pow           'clj.py/pow
  :op/neg           'clj.py/neg
  :op/pos           'clj.py/pos
  :op/eq            'clj.py/eq
  :op/ne            'clj.py/ne
  :op/lt            'clj.py/lt
  :op/le            'clj.py/le
  :op/gt            'clj.py/gt
  :op/ge            'clj.py/ge
  :op/lnot          'clj.py/lnot
  :op/truthy        'clj.py/truthy
  ;; Collections, indexing, and comprehension accumulators
  :coll/vconj       'clj.py/vconj
  :coll/list        'clj.py/list
  :coll/tuple       'clj.py/tuple
  :coll/set         'clj.py/set-from
  :coll/dict        'clj.py/map-from
  :coll/getitem     'clj.py/get-item
  :comp/list-new    'clj.py/comp-list-new    ;; [init-vec] -> accumulator; py-v1: 'py/list
  :comp/list-append 'clj.py/comp-list-append ;; [acc val] -> step result; py-v1: 'py/list-append
  :comp/set-new     'clj.py/comp-set-new     ;; [] -> accumulator; py-v1: 'py/set-new
  :comp/set-add     'clj.py/comp-set-add     ;; [acc val] -> step result; py-v1: 'py/set-add
  :comp/dict-new    'clj.py/comp-dict-new    ;; [] -> accumulator; py-v1: 'py/dict-new
  :comp/dict-set    'clj.py/comp-dict-set    ;; [acc k v] -> step result; py-v1: 'py/dict-set
  :comp/finish      'clj.py/comp-finish      ;; optional [acc] -> final collection; nil in py-v1
  ;; Scopes & bindings
  :env/local-get    'clj.py/local-get
  :env/global-get   'clj.py/global-get
  :env/global-set   'clj.py/global-set
  ;; Functions & calls
  :fn/arg           'clj.py/arg
  :fn/make          'clj.py/make-fn
  :fn/call          'clj.py/call
  ;; Control flow & unit entry (unit/run-main always takes `[body-fn ambient-resolver]`)
  :ctrl/for-each    'clj.py/for-each
  :ctrl/try         'clj.py/try
  :ctrl/try-finally 'clj.py/try-finally
  :ctrl/raise       'clj.py/raise
  :ctrl/call-ec     'clj.py/call-ec
  :unit/run-main    'clj.py/run-main}

 ;; Each entry defaults to :kind :cell-accessor (emitted per unit into `ambient-resolver` as
 ;; `(m/e)`, returning the module's stable task-local authenticated callable cell); optional
 ;; :kind :value references a non-lambda immutable export directly.
 :yang.semantics/ambient-projections
 {"identity"  {:module 'clojure.core, :export 'identity,  :kind :cell-accessor}
  "inc"       {:module 'clojure.core, :export 'inc,       :kind :cell-accessor}
  "dec"       {:module 'clojure.core, :export 'dec,       :kind :cell-accessor}
  "str"       {:module 'clojure.core, :export 'str,       :kind :cell-accessor}
  "count"     {:module 'clojure.core, :export 'count,     :kind :cell-accessor}
  "first"     {:module 'clojure.core, :export 'first,     :kind :cell-accessor}
  "rest"      {:module 'clojure.core, :export 'rest,      :kind :cell-accessor}
  "second"    {:module 'clojure.core, :export 'second,    :kind :cell-accessor}
  "cons"      {:module 'clojure.core, :export 'cons,      :kind :cell-accessor}
  "conj"      {:module 'clojure.core, :export 'conj,      :kind :cell-accessor}
  "assoc"     {:module 'clojure.core, :export 'assoc,     :kind :cell-accessor}
  "assoc_in"  {:module 'clojure.core, :export 'assoc-in,  :kind :cell-accessor}
  "get"       {:module 'clojure.core, :export 'get,       :kind :cell-accessor}
  "get_in"    {:module 'clojure.core, :export 'get-in,    :kind :cell-accessor}
  "update"    {:module 'clojure.core, :export 'update,    :kind :cell-accessor}
  "update_in" {:module 'clojure.core, :export 'update-in, :kind :cell-accessor}
  "map"       {:module 'clojure.core, :export 'map,       :kind :cell-accessor}
  "filter"    {:module 'clojure.core, :export 'filter,    :kind :cell-accessor}
  "reduce"    {:module 'clojure.core, :export 'reduce,    :kind :cell-accessor}
  "into"      {:module 'clojure.core, :export 'into,      :kind :cell-accessor}
  "keys"      {:module 'clojure.core, :export 'keys,      :kind :cell-accessor}
  "vals"      {:module 'clojure.core, :export 'vals,      :kind :cell-accessor}
  "merge"     {:module 'clojure.core, :export 'merge,     :kind :cell-accessor}
  "is_empty"  {:module 'clojure.core, :export 'empty?,    :kind :cell-accessor}
  "is_nil"    {:module 'clojure.core, :export 'nil?,      :kind :cell-accessor}
  "is_map"    {:module 'clojure.core, :export 'map?,      :kind :cell-accessor}
  "is_vector" {:module 'clojure.core, :export 'vector?,   :kind :cell-accessor}
  "is_set"    {:module 'clojure.core, :export 'set?,      :kind :cell-accessor}
  "ex_info"   {:module 'clj.py,       :export 'ex-info,   :kind :cell-accessor}}}
```

### 6.4 Validation, lexical hygiene, authority checks, and parameterization

1. **Hygienic generated references (no bare hook symbols):** Every non-nil symbol in `:yang.semantics/hooks` and every executable literal-strategy field (such as `:literals.*.overflow-op`) MUST be namespace-qualified (`:yang.semantics/unqualified-hook` otherwise), so user locals named `conj` or `not` can never capture compiler-emitted calls.
2. **Scope-aware precedence for `:yang.semantics/ambient-projections`:** Free source identifiers resolve in strict order: (1) lexical scope (`:local`, `:cell`, `:nonlocal`), (2) per-module globals (`:vars` in `globals-cell`), (3) unit-bound ambient resolver (`:ambient` in `globals-cell`, passed via `:unit/run-main`), failing with a guest `NameError` on miss.
3. **UAST-derived structural export & reserved-door validation (`validate-descriptor`):**
   - Takes `(descriptor frontend-contract artifact-store registry)`, computes the descriptor's **selected, address-verified closure**, and inspects every active hook, executable `:literals.*` target, and `:ambient-projections` entry directly against the target manifest's verified `:yin.module/tree` UAST (`wide-app` top-level `(yin/def (quote sym) expr)` definitions):
     - **Hooks & executable `:literals.*` targets:** must be namespace-qualified, exported by a module in the selected closure (or required host module checked against `:yin.k/arities`), and defined as a `:lambda`. **Signature vs. call shapes (r8):** admission verifies the export's verified signature against the frontend contract's **recorded entry contract**, which states the export's parameter list AND the permitted emitted call shapes (e.g. the frozen PHP `php/run-main` is a 4-parameter lambda legitimately called with 2 or 4 operands; the `clj.py` profile always takes 2 args) — a call shape outside the contract's permitted set fails. Both PHP modes are gated, including observable stream output.
     - **Ambient projections (`:kind :cell-accessor`):** unique alias names, target `e` is a 0-arg `:lambda` in `m`, and `m` exports 0-arg `init!` and 1-arg `export`.
     - **Ambient projections (`:kind :value`):** target `e` is a non-lambda immutable value.
     - **Reserved-door ban:** refuses any reference to `cell/dsg__*`, `dsg__*`, `yin/def`, `*.rt/*`, `*.b/*`, `yang.safepoint/*`, or `*.impl/*` (`clojure.core.impl/*`).
   - Any violation fails closed before reading CST packets with `:yang.semantics/invalid-descriptor`.
4. **Address-bound export-role evidence (r8):** qualification, closure membership, and arity alone do not establish a hook's *role* — a binary `clj/prim-get` satisfies an arity check for `:op/add`. The frontend contract or the admitted profile therefore supplies per-hook **role records** (arity + role: `:cell-accessor`, `:value`, operator, projection) resolved by recorded address, and host primitive profiles are resolved by their recorded profile addresses. A same-arity wrong-role target fails closed. **Authorization key (r8):** `(module address, export symbol, exact hook or projection role)` — the broad role `operator` alone cannot authorize (two binary operators in one published module share address, arity, and role); the same key applies to executable literal targets. Gate: substituting subtraction for addition within the same module must fail.
5. **Fail-closed capability enforcement during lowering:** Before lowering any guarded CST construct (`classdef`, `yield_expr`, `genexpr`, `with_stmt`, `del_stmt`, typed `except E:`, kwargs, defaults, subscript mutation), lowering checks the descriptor's capability map (with `genexpr` explicitly gated by `:control/generators`) and emits `{:status :refused :reason :yang.semantics/unsupported-capability :capability <cap> :semantics-id <id> :construct <c> :node <id>}` when disabled.
6. **Frontend parameterization:** `yang.python.antlr.lower`, `yang.typescript.antlr.lower`, and `yang.php.antlr.lower` read literals, hooks (including 2-arg `:unit/run-main [body-fn ambient-resolver]` and optional `:comp/finish`), capabilities, and projections from `:yang.semantics/descriptor`, preserving full CPython/Node/PHP oracle and 4-host parity under the legacy pins. **Entry-lowering contracts (r8):** each frontend's contract specifies BOTH entry shapes — the landed native entry shape (`py/run-main` 1-arg, `js` single-operand, `php` 2-or-4 with the request stream in slot two) for the legacy track, and the Clojure profile's resolver-bearing 2-arg `[body-fn ambient-resolver]` shape — so the native mapping is 1:1 against the FROZEN exports and never re-arches them to satisfy the Clojure profile. **Oracle assignment (r8):** native-oracle parity suites gate the LEGACY pins; capability-refusal suites gate the default profile.

### 6.5 Frontend SPI and provenance integration (`yang.frontend`)

No `yang.frontend` schema change is required (`src/cljc/yang/frontend.cljc`):
- `:yang.frontend/id` names the frontend syntax + lowering implementation (e.g., `:yang.python/antlr`), and each profile gets an immutable `:yang.frontend/revision` (`"py-v1"` vs. `"clojure-core-v1"`).
- `:yang.frontend/lowering-profile` pins the content address of the `:yang.semantics/descriptor` EDN artifact; `:yang.frontend/runtime-profile` pins the resolved `:yang.prelude/profile` manifest. **Descriptor/runtime reconciliation (r8):** where two lowerer revisions consume one descriptor, the pins are reconciled **by module symbol before lowering** — a conflicting pin fails closed. Dependency-first module installation belongs to linked composition (a verified dependency pin does not install its module in a cold task; first execution on a cold task is gated). The resolver's derivation is recorded in provenance; its live cell is task-owned.
- Compilation provenance retains the exact derivation record (r8: frontend/lowerer revision, descriptor, resolved runtime closure, input, output).
- **Default selector (r8):** the composition's immutable configuration carries the preferred `[frontend-id revision]` pair, applied at the normal request entry; `yang.frontend/select`'s ID-only ambiguity resolves through that configuration. Gates: both revisions installed → ordinary request selects Clojure semantics; explicit legacy pin → native behavior preserved; in-flight request → original snapshot retained.

---

## 7. Case study: `yang.python` with a `clojure.core` prelude (`:yang.python/clojure-core-v1`)

### 7.1 Value representation & semantic mapping

| Python syntax | Native `py` prelude (`:yang.python/py-v1`) | `clojure.core` prelude (`:yang.python/clojure-core-v1`) |
|---|---|---|
| `None` | `:py/None` | `nil` |
| `True` / `False` | `true` / `false` (numeric as `1`/`0`) | `true` / `false` (**non-numeric**: arithmetic raises guest error) |
| `"abc"` | `{:py/str "abc"}` | `"abc"` (raw Yin/UTF-8 string) |
| `42` | exact integer (safe raw, else `py/int-lit`) | exact integer (safe raw, else `clj.py/int-lit`) |
| `1.5` | `{:py/float <float64>}` | `<float64>` (untagged Jing `data/float64` carrier) |
| `[1, 2]` | cell `{:py/type :list :items [1 2]}` | persistent vector `[1 2]` |
| `(1, 2)` | `{:py/type :tuple :items [1 2]}` | persistent vector `[1 2]` |
| `{"a": 1}` | cell `{:py/type :dict ...}` | persistent map `{"a" 1}` |
| `{1, 2}` | cell `{:py/type :set ...}` | persistent set `#{1 2}` |
| `def f(x): ...` | cell `{:py/type :function :code ... :yang.call/entry ...}` | authenticated cell `{:clj.py/type :function :name s :spec spec :yang.call/entry ...}` |
| Truthiness | `0`, `""`, `[]`, `{}`, `None`, `False` falsy | Clojure truthiness (`clj.py/truthy`): only `nil` and `false` falsy (`0`, `""`, `[]` are truthy) |
| Equality `==` / `!=` | `1 == 1.0 == True`, structural list/dict | `data/equiv`: `1 == 1` `true`, `1 == 1.0` `false`, `1 == True` `false`, `NaN == NaN` `false`, `0.0 == -0.0` `true`, recursive over persistent collections |

*Truth-in-advertising invariant:* `:yang.python/clojure-core-v1` explicitly declares Clojure truthiness, persistent immutable collections, non-numeric booleans, and kind-strict equality as its support profile — never CPython parity.

### 7.2 Portable numeric & collection contract (`clj.py` over `:pure` host core + `yrt.effect`)

Because the Tier-1 `:pure` host core (`yin.vm.data`, `yin.vm.integer`, `yin.vm.float`) handles all host-representation details natively, `clj.py` becomes a thin, readable adapter:

1. **Portable kind classification (`clj.py/num-kind`):**
   ```clojure
   (if (integer/integer? x)
     :int
     (if (data/float64? x) :float :other))
   ```
   Non-numeric operands raise `{:clj.py/error :type-error ...}` via `clj/raise` (in `v1`, string `+` concatenates via `data/str-concat`; `*` is strictly numeric).
2. **Int × Int:** Delegates directly to `yin.vm.integer` (`add`, `sub`, `neg`, `mul`, `floor-div-mod`, `pow`, `true-div`, `compare`), checking `b == 0` beforehand (performing `:math/div-zero`) and passing every result through `yrt.int/check-limit` before indexing `[q r]` pairs or chaining into further ops. **All host-boundary float carriers are boxed (r8):** `integer/to-float-boxed` for promotion, `integer/true-div-boxed` for division quotients, and boxed `float/neg-pow` for negative exponents — no raw host double enters a guest frame on any Clojure-family numeric path. The frozen native ABI (`integer/to-float`, `true-div`) is unchanged for the legacy track. **Boxed comparison (r8, VM-engineer review):** `integer/compare-float-boxed [x-boxed y-boxed]` (and `integer/from-float-boxed`) are added to the integer host-module exports (listed in §7.2's boxed-path usage) — the landed `compare-float`/`from-float` take raw host doubles and are legacy-only; mixed int×float comparisons on the boxed path never unwrap.
3. **Float × Float and Mixed Int × Float:**
   - Exact ints promote via `(data/float64 (yrt.int/check-limit (clj/rt-ctx) (integer/to-float-boxed i)))` — `integer/to-float-boxed` (r8) returns the boxed carrier at the host boundary so no raw host double crosses a guest frame.
   - `+`, `-`, `*`, unary `-`, unary `+`, `/`, `//`, `%`, `**`, and `<`/`<=`/`>`/`>=` delegate directly to boxed `yin.vm.float` exports (`float/add`, `float/sub`, `float/mul`, `float/neg`, `float/div-checked`, `float/floordiv-mod`, `float/fpow-half`, `float/neg-pow`, `float/compare`) and `integer/compare-float` (via boxed float helper).
   - Cold status keywords (`:yin.vm.float/div-zero`, `:yin.vm.float/underflow`, `:yin.vm.float/fractional-exp`, `:yin.vm.float/infinite-exp`) are translated by `clj.py`'s effect handler into guest error maps (`{:clj.py/error :zero-division ...}`, `:overflow`, `:pow-fractional`, `:pow-infinite`) via `yrt.effect/perform` → `clj/raise`.
4. **Uniform key admission & equality:**
   - **Key/element admission:** Map/set construction and lookup in both `clj.py` and `clojure.core.impl` call `(data/admit-key? k)` once; on `false`, perform `:coll/unsupported-key` (raising `{:clj.py/error :unsupported-key :key k}` or `ex-info`).
   - **Equality:** `clj.py/eq` delegates directly to **`data/equiv`**; `clj.py/ne` delegates to `(not (data/equiv a b))`.
   - **Deterministic traversal:** Map/set iteration delegates directly to **`data/canonical-entries`**.

### 7.3 Python lowering Hook ABI & `clj.py` adapters

| Hook key | Frontend ABI signature | `clj.py` implementation |
|---|---|---|
| `:env/local-get` | `[cell name]` | Reads `(cell/get cell)`; **presence is carried independently of the payload (r8)** — the cell's map distinguishes absent from a stored `:clj/unbound` value, so a local equal to `:clj/unbound` reads back as that value; only true absence raises the guest unbound error. |
| `:env/global-get` | `[globals-cell name]` | Checks `(data/contains? (get g :vars) name)` first; on miss invokes unit-owned `((get g :ambient) name)`; presence-vs-payload applies here too (r8) — a projected value equal to `:clj/unbound` returns as-is; only true absence raises the guest `NameError` map built via evaluated `assoc`. |
| `:env/global-set` | `[globals-cell name value]` | Updates `:vars` in `globals-cell` (preserving `:ambient`) and returns `value`. |
| `:fn/make` | `[fname spec defaults kwdefaults code]` | Accepts the 5 arguments from `yang.python.antlr.lower/function-value:1870`; delegates to trusted Layer-2b **`clj/make-callable`** (`§7.4`). |
| `:fn/call` | `[callee args]` | Delegates to trusted Layer-2b **`clj/invoke-callable`** (`§7.4`). Zero provenance doors inside `clj.py`. |
| `:ctrl/try` | `[body handler orelse]` | Calls `yrt.effect/try-orelse` on `(clj/rt-ctx)`. |
| `:ctrl/try-finally` | `[body fin]` | Calls `yrt.effect/try-finally` on `(clj/rt-ctx)` with `normal-arg = nil`. |
| `:ctrl/raise` | `[e]` | Calls `clj/raise` (delegating to `yrt.effect/raise` on `(clj/rt-ctx)`). |
| `:ctrl/call-ec` | `[f]` | Calls `yrt.effect/call-ec` on `(clj/rt-ctx)` with `normal-arg = nil`. |
| `:ctrl/for-each` | `[iterable step-fn]` | Invokes `clojure.core.impl/impl-seq`, `impl-first`, `impl-rest` (backed by `data/canonical-entries` for maps/sets), raises guest error on non-collection iterables, and invokes `step-fn` per element. |
| `:comp/list-new`, `:comp/set-new`, `:comp/dict-new` | `[init-vec]`, `[]`, `[]` | Allocates temporary local accumulator cell holding persistent `[]`, `#{}`, or `{}`. |
| `:comp/list-append`, `:comp/set-add`, `:comp/dict-set` | `[acc val]`, `[acc val]`, `[acc k v]` | Validates set elements / dict keys via `data/admit-key?`, updates persistent value inside `acc`, returns `nil`. |
| `:comp/finish` | `[acc]` *(optional)* | Returns `(cell/get acc)` (omitted/`nil` in `:yang.python/py-v1`). |
| `:unit/run-main` | `[body-fn ambient-resolver]` | Calls idempotent `(clj/init!)`, `(clojure.core/init!)`, `(clj.py/init!)`; installs `clj.py`'s cold-effect policy handlers (`:math/*`, `:int/*`, `:coll/unsupported-key`) on `(clj/rt-ctx)`; allocates a fresh per-unit `globals` cell; invokes `(body-fn globals globals-fn)` and returns the value verbatim without stripping `[:yin.k/ok ...]`. |

### 7.4 Unified trusted `clj` callable bridge, cross-unit admission, and §8.10.7 validation

Under the Owner Clean-Slate Directive, all Clojure-family §8.10.7 callable/provenance bridging is unified directly into **trusted Layer-2b `clj`** (`src/cljc/yang/clojure/prelude.cljc`, requiring `yang.call` and `yrt.effect`) — eliminating both any separate `clj.bridge` module and `yang.clojure`'s legacy `dsg__call_token` / inline provenance code in P3:

- `clj` owns the private provenance doors (`cell/dsg__new-provenance`, `cell/dsg__provenance`) inside **both** its linked (`module-uast`) and bundled (`uast`) emitters, never exporting them and keeping them strictly out of `yrt.*`, `clj.py`, `clojure.core.impl`, and `clojure.core`.
- `clj` exports collision-free primitive helpers **`clj/prim-get`** and **`clj/prim-assoc`**. **Bundled-mode door enforcement (r8, VM-engineer review):** in bundled mode the door effects are honored ONLY for the emitted `clj` definition addresses — the VM admits a provenance-door call by the calling definition's recorded address, never by name resolution from guest scope, so a forged co-bundled call cannot mint provenance (linked mode already gates by content address/admission).
- **Source-level callable admission (`clj/admitted-callable?`):** Nested `if` verifying `(data/map? x)`, `(= (get x :type) :cell-ref)`, `(cell/authentic? x)`, `(data/callable? (yang.call/entry x))`, and `(yang.call/eligible? x)`. Plain maps, unauthenticated cell-ref maps, and raw host primitives fail cleanly.
- **Two-step cell-backed callable constructor (`clj/make-callable`):** Allocates `owner = (cell/new meta)` first, installs `entry` closing over `owner` via `cell/set!`, and returns `owner`.
- **Full 4-step §8.10.7 entry boundary:**
  - *Step A:* Verify `(data/map? invocation)` and `(data/callable? (get invocation :yang.call/raise))` (fail operationally otherwise).
  - *Step B:* Outside `try-value`, verify `(yang.call/valid-invocation? invocation)` and `(= (get invocation :yang.call/callee) owner)`; build condition evidence via evaluated `assoc` calls and transfer `:yang.call/invalid-invocation` on failure.
  - *Step C:* Outside `try-value`, reject unsupported `:yang.call/binding-profile` via evaluated `assoc` evidence and transfer `:yang.call/unsupported-binding`.
  - *Step D:* Run argument binding + body inside **`yrt.effect/try-value`** on `(clj/rt-ctx)`. Normal exit restores `(clj/rt-ctx)` and returns the value verbatim; escape inspects private `(cell/dsg__provenance e)` to forward retained protocol conditions without double-wrapping or wrap user escapes via `(yang.call/thrown e)`.
- **Call-site boundary (`clj/invoke-callable`):** Checks `clj/admitted-callable?` (minting retained `:yang.call/not-callable` on failure), else calls `yang.call/invoke` with a `raise-exit` that unwraps `:yang.call/thrown` and retains all other protocol conditions via private `cell/dsg__new-provenance`.

### 7.5 Two-module portable `clojure.core` contract (`clojure.core.impl` + `clojure.core`)

In Slice P3, the portable Clojure standard library splits into two Layer-2c linker modules — eliminating store-key collisions against VM primitives while publishing cell-free 0-arg export accessors:

1. **`clojure.core.impl` (pure, stateless implementation module — banned from `:ambient-projections`):**
   - Requires `yang.call`, `clj`, `yrt.int` (zero direct provenance doors; **zero state cells**). Its guest **policy tags** (`:coll/unsupported-key`, `:math/*`, `:int/*`) live in the handler contract installed by the owning entry (`clj.py/run-main` at P3′, plain `yang.clojure`'s own cold-error policy at P2′) — they are NOT part of the linker-derived VM effect footprint; only the underlying **VM effects** its definitions perform are declared through the existing linker footprint mechanism (`yin/vm/linker/publish.cljc`). Host dependencies: `kind` (with `kind/namespace`), `data` (with `float64?`, `equiv`, `admit-key?`, `canonical-compare`, `canonical-entries`), `integer` (`:min-bits 53`), `float`, and `cell` (for cell checks/updates only).
   - **Zero bare primitive store collisions:** Every definition is prefixed `impl-*` (`impl-identity`, `impl-get`, `impl-assoc`, `impl-conj`, etc.), so internal calls to `(get m k)`, `(assoc m k v)`, `(conj xs x)`, `(first xs)`, `(rest xs)`, `(vec xs)`, `(nil? x)`, `(empty? xs)`, `(not b)` resolve directly to VM primitives.
   - **Cell-safe collection predicates:** `impl-cell-ref?` ensures authenticated callable cells answer **`false`** to all of `coll?`, `map?`, `set?`, `vector?`, `sequential?`, `seq?`, `list?`, and `reduced?`; `impl-fn?` matches `clj/admitted-callable?` 1-to-1.
   - **Host-backed portable operations (zero guest-UAST leak workarounds):**
     - `impl-inc`, `impl-dec`, `impl-rem`, `impl-int?`: delegate to `integer/*` + `yrt.int/check-limit` and `float/add`/`float/sub`.
     - `impl-not=`: delegates to `(not (data/equiv a b))`.
     - `impl-str`: deterministic renderer delegating ints to `integer/format`, floats to `float/format`, keywords/symbols to `kind/namespace` + `kind/name`, maps/sets to `data/canonical-entries`, and authenticated cells to `"#<fn>"`.
     - `impl-seq`, `impl-first`, `impl-rest`, `impl-vec`, `impl-keys`, `impl-vals`, `impl-reduce`, `impl-map`, `impl-filter`, `impl-remove`, `impl-into`, `impl-merge`, `impl-union`, `impl-difference`: normalize maps/sets via `data/canonical-entries`.
     - Map/set mutators and lookups (`impl-assoc`, `impl-get`, `impl-contains?`, `impl-conj`, `impl-disj`, `impl-merge`, `impl-into`, etc.): enforce `data/admit-key?`.
     - Explicitly excluded: `var?`, `resolve`, `invoke-value`.
2. **Public cell-free facade `clojure.core` (`src/cljc/yang/clojure/core_module.cljc`):**
   - Requires `clj` and `clojure.core.impl`. Exports only cell-free pure functions at install time: idempotent 0-arg `init!` (building `{sym cell}` with `clj/prim-assoc` and `clj/make-callable`), 1-arg `export` (using `clj/prim-get`), and 0-arg cell-accessor exports `(fn [] (clojure.core/export (quote <name>)))`.
   - Dynamic closures (`impl-comp`, `impl-partial`) construct cells via `clj/make-callable`; all dynamic callbacks (`map`, `filter`, `reduce`, `update`, etc.) invoke via `clj/invoke-callable`.
   - **Direct `yang.clojure` migration in P3:** Retires `yang.clojure`'s inline `library-for` duplication and unit-local `dsg__call_token` path so there is one unified Clojure runtime.

### 7.6 Concrete lowering example

```python
def sum_even_squares(xs):
    if is_empty(xs):
        return 0
    evens = filter(lambda n: n % 2 == 0, xs)
    squares = map(lambda n: n * n, evens)
    return reduce(lambda a, b: a + b, 0, squares)
```

- `is_empty`, `filter`, `map`, `reduce` resolve via `:yang.semantics/ambient-projections` to `clojure.core`'s task-instantiated cell-backed callables.
- `%`, `*`, `+`, `==` lower hygienically to `clj.py/mod`, `clj.py/mul`, `clj.py/add`, `clj.py/eq` (backed by `:pure` `yin.vm.integer` / `yin.vm.float` / `data/equiv`).
- `lambda n: ...` lowers to `clj.py/make-fn`, producing an authenticated cell-backed `yang.call` callable invoked directly by `clojure.core/filter` and `clojure.core/map`.
- `sum_even_squares([1, 2, 3, 4])` evaluates `[1 2 3 4]` → `20` (exact persistent integer), returned verbatim.

---

## 8. Portability, content addressing, and malleability

### 8.1 Multi-host portability (JVM, CLJS/Node, CLJD/Dart, Jolt/Scheme)

- **ClojureDart AOT literal limits:** Consolidate `defquoted` into **`yang.core.defquoted`** (r8 M9 — the dedicated host macro namespace; NOT `yang.core.prelude`, keeping the emission namespace free of macro definitions).
- **Zero bare integral float literals & zero raw host doubles in guest UAST:** All float operations pass boxed `data/float64` through `yin.vm.float`, avoiding raw host doubles across JVM, Node, Dart, and Jolt.
- **Canonical ordering (`b86bc0e8` invariant):** Recipes, profiles, descriptor maps, and emitted UAST operand sequences sort keys deterministically, guaranteeing byte-identical `dao.jing.cbor` hashes across all 4 hosts.
- **No JVM-only reflection:** `yang.core.semantics` and `yang.core.prelude` use pure portable Clojure data transforms.

### 8.2 Dependency graph and malleability (`docs/agents/malleability.md`)

Verified with `clj ./bin/analyze-deps.clj` (r7: the analyzer's high-degree predicate now counts combined in+out degree ≥ 6, matching this document's rule; `56d17563`). No multi-node cycles and no multi-node SCCs exist. The analyzer prints three SELF-loop warnings (`yang.php.antlr.quoted`, `yang.python.antlr.prelude`, `yang.typescript.antlr.prelude` — macro self-reference in defquoted-family namespaces); these are analyzer-reporting artifacts, not runtime dependency cycles, and are expected to disappear once the defquoted consolidation (P0) lands:

```text
Layer A (AST & Data Schemas):    yang.core.uast
Layer B (Pure Composition):      yang.core.prelude, yang.core.semantics, yang.core.defquoted (P0 host macro ns)
Layer C (Registry Admission):    yang.core.prelude-admission, yang.core.module, yang.core.publication
Layer D (Guest linker modules,  yrt.effect, yrt.int, yrt.safepoint, yang.call — emitted UAST
  emitted from host code):        artifacts consumed at runtime on every backend
Layer D' (Host emitter ns for   src/cljc/yang/rt/{effect,int,safepoint}.cljc — clojure namespaces
  the D-layer modules):           requiring yang.core.prelude/prelude-admission/core.call
Layer E (Language Frontends):    yang.python.antlr.*, yang.typescript.antlr.*, yang.php.antlr.*,
                                 yang.clojure.{prelude, core-impl, core-module}
```

Layer D and D' are deliberately separated in the graph: D is *guest linker modules* (runtime artifacts, host-agnostic by construction); D' is their *host emitter namespaces* (the Clojure namespaces that appear in `bin/analyze-deps.clj`'s graph, requiring Layer B/C seams). Conflating them — as the r5/r6 text did with `yang.rt.*` — mixes runtime identity with emitter identity; D2's settled `yrt.*` naming applies to D, while D' uses the `src/cljc/yang/rt/` path layout.

### 8.3 Content-addressing and golden discipline (Clean-Slate)

Per the Owner Clean-Slate Directive, regenerate affected golden snapshots cleanly per slice (`P0`–`P4`) and validate correctness via behavioral, 4-host, and CPython/Node/PHP language-oracle parity suites.

---

## 9. Phased implementation roadmap and acceptance gates

**r7 reordering (owner directive D10 — Clojure-first):** the Clojure prelude family is built FIRST; the native py/js/php preludes are untouched by these slices (frozen as landed, zero golden churn), with their `yrt.*`/boxed-float re-architecture deferred to the legacy track (Track B). The original r6 P1–P4 order is superseded; its non-Clojure legs reappear in Track B.

| Slice | Scope & deliverables | Golden impact | Concrete acceptance gate |
|---|---|---|---|
| **P0′ — Composition plumbing & `:pure` host core extensions** | Implement `:yang.prelude/recipe` / `:yang.prelude/profile`, `validate-profile` + `admit-profile` **in `yang.core.prelude-admission`** (3→5 publics, per the §5.2 home rule); add `:pure` host exports (`data/float64?`, `data/equiv`, `data/admit-key?`, `data/canonical-compare`, `data/canonical-entries`, `kind/namespace`, and boxed `yin.vm.float` across all 4 hosts); consolidate `defquoted` into `yang.core.defquoted` (dedicated host macro namespace, keeping `yang.core.prelude` at 11 publics — naming per r8 M9, consistent across §8.1/§8.2); base-prelude emitter recipes record the *current* per-prelude modes as migration truth (`:linear` for `py`, `:balanced` for `php`/`js`; the bundled `yang.clojure` continuation family is a LEFT FOLD, not balanced — recorded as its own mode so the freeze is exact) without changing shapes. | No golden churn for py/js/php (no shape change); new modules get fresh addresses | Closure, missing-manifest, `host-limits`, direct-provenance vs. transitive-footprint tests; 4-host test suite for `data/equiv` (`[0.0] == [-0.0]`, `[NaN] != [NaN]`, `(keyword nil "a/b") != (keyword "a" "b")`), `data/admit-key?`, `data/canonical-compare`/`canonical-entries`, and `yin.vm.float` (`2.1 // 0.7 == 3.0`, `-0.0 // 2.0 == -0.0`, `0.5 ** -2000 -> :underflow`, `format` `1.0 -> "1.0"`); existing py/php oracle suites unchanged-green. |
| **P1′ — `yrt.effect` + `yrt.int` with Clojure-only consumers; unified trusted `clj`** | Implement stateless `yrt.effect` (5-slot `data/nth` frames, abortive `raise`/`try-value`/`try-orelse`/`try-finally`/`call-ec`, plus resumable `handle`/`perform` — the frame design anticipates py/php `:depth` accounting but only clj/clj.py exercise it) and `yrt.int`; upgrade `clj` to the unified trusted form (two-step `make-callable`, evaluated `assoc` conditions, `invoke-callable`, `prim-get`/`prim-assoc`, retiring `yang.clojure`'s `dsg__call_token` / inline provenance ops); add `dsg__add`/`dsg__lte` for bundled `clj`. **py/js/php preludes untouched** — they keep their own mechanisms. | Refresh `clj`/`yang.clojure` goldens cleanly; py/js/php goldens untouched | `yrt.effect` unit tests (abortive, `finally`, resumable one-shot `handle`/`perform`, cold policy traps) across semantic/stack/register VMs; bundled/linked `yang.clojure` parity under shadowed `(defn get ...)`; clj-only synthetic nonzero depth/base fixtures exercising the `:depth` slot; independent concurrent `clj` tasks sharing `yrt.*`; bundled == linked + 4-host; `clj ./bin/analyze-deps.clj` clean. (H7 repair: native collection invocation — including the landed fixture where a locally bound map invoked with `:k` returns 9, which requires the native data-call dispatch to admit locally bound maps — and bundled primitive shadowing through the new standard library gate in the P2′ row below; the REAL cross-syntax `clj → clj.py → clj` unwind with cleanup and context restoration gates SOLELY in the P3′ row.) |
| **P2′ — Unified `clojure.core` + `yang.clojure` migration** | Publish collision-free `clojure.core.impl` (pure, `impl-*` prefix, `admit-key?`-guarded) + cell-free `clojure.core` facade; migrate `yang.clojure` directly onto unified `clj` + `clojure.core`. | Refresh `yang.clojure` goldens cleanly | Native collection invocation through the new standard library (the locally-bound-map `:k` → 9 fixture) and bundled primitive shadowing of bare `get`/`assoc` (which otherwise resolve through the source store before VM primitives); unified `yang.clojure` suite green. The real `clj → clj.py → clj` cross-syntax unwind (cleanup + context restoration) gates ONLY at P3′, after `clj.py` exists. |
| **P3′ — Descriptor parameterization + `clj.py` + `:yang.python/clojure-core-v1` (the DEFAULT flip)** | Implement generic `yang.core.semantics` with UAST-derived arity & role validation; define the python frontend contract; refactor `yang.python.antlr.lower` to read literals/hooks/capabilities from `:yang.semantics/descriptor` (the legacy `py-v1` descriptor pins today's `'py/*` hooks 1:1 — behavior-preserving, gated by the existing CPython oracle suites); publish `clj.py`; flip `yang.python`'s default `runtime-profile` to `:yang.python/clojure-core-v1` (a revision re-pin in `yang.frontend`). | py goldens regenerate under the descriptor-driven path (legacy descriptor byte-identical outcomes); clojure-core-v1 gets fresh goldens | Legacy-descriptor behavior preservation: full CPython corpus + 4-host parity under `py-v1` pin; end-to-end 4-host `:yang.python/clojure-core-v1` suite (numeric/float ops, `:max-bits 53` limit traps, `data/admit-key?` refusals, `data/equiv`, `data/canonical-entries` ordering, deterministic `str`/`gensym`); the cross-syntax `clj → clj.py → clj` unwind gate — cleanup and context restoration, exactly once per activation — listed directly here; capability refusal tests (typed `except E:`, `genexpr` under `:control/generators :refused`); default-profile regressions (r8): an unqualified `:k` key admits successfully (the namespace-non-empty rule applies to two-part keywords only); equal exact integers in different host carriers hit the same map entry on insert AND lookup; a local/projected value equal to `:clj/unbound` remains distinguishable from an absent binding; canonical traversal order matches Jing encoded-byte order (`0` before `nil`); UAST-derived validation rejecting wrong arity, non-lambda hooks, unqualified hooks, reserved doors, out-of-closure exports. |
| **P4′ — Additional N → 1 profiles** | `:yang.typescript/clojure-core-v1` next (descriptor + ts frontend contract); `:yang.php/clojure-core-v1` after. | Fresh goldens per profile | End-to-end parity suites per profile. |
| **Track B — Legacy prelude re-architecture (deferred, on demand)** | Migrate the native `py`/`js`/`php` preludes onto `yrt.effect`/`yrt.int`/boxed float (the r6 P1/P4 legs: 4→5-slot contexts, generators via `:gen/yield`, `yrt.safepoint`, `php.sp` integration per its own hooks); undertaken only when legacy demand justifies it. | Refresh py/js/php goldens per migration slice | The r6 P1/P4 gates apply verbatim at that time, attached here by value: generator + safepoint parity suites across VMs/hosts preserving PHP lifecycle/cleanup and Python `:base` rebasing; migration of `py`/`js`/`php` onto `yrt.effect`, `yrt.int`, and boxed `yin.vm.float` (4→5-slot contexts; `dsg__add`/`dsg__lte` for bundled `clj`); existing Python & PHP generator tests green; independent concurrent `py`/`js`/`php`/`clj` tasks sharing `yrt.*`; bundled == linked + 4-host + CPython/Node/PHP oracle parity; `clj ./bin/analyze-deps.clj` clean. |

---

## 10. Owner decisions and disposition ledger

1. **D10 — Default-profile policy & legacy-track deferral (`Settled — r7 Owner Directive`):** For the parameterized frontends, the **Yin/Clojure profile is the DEFAULT** (`:yang.python/clojure-core-v1` first; ts/php to follow); the native preludes (`py`/`js`/`php` semantics) become the **Legacy compatibility track** — kept frozen as landed (they already exist at their current content addresses with passing oracle suites), with their `yrt.*`/boxed-float re-architecture deferred to Track B until legacy demand justifies it. The clojure prelude family is built first. Consequence named in §6.1: capability refusals (classes, generators, typed `except`, `with`, kwargs) are the default experience for source written against native semantics; pinning the legacy revision is the escape hatch.
2. **D1 — Local mutation model for `:yang.python/clojure-core-v1`:**
   - *Option A (Recommended — Architect concurred):* `:mutation/locals :cell` (task-heap cells holding pure persistent Clojure values, matching Python parameter binding, unbound reads, reassignment, loops, and `nonlocal` with per-module `globals` cells). Keep `:single-assignment` as a separate restricted profile.
   - *Option B:* `:mutation/locals :single-assignment` only (refuses local reassignment).
2. **D2 — Namespace prefix for Layer-2a shared modules:**
   - *Option A (Recommended — Architect concurred):* `yrt.*` (`yrt.effect`, `yrt.int`, `yrt.safepoint`) matching `yang.typescript.md` §17 and `yang.php.md` §9, implemented in Clojure under `src/cljc/yang/rt/*.cljc`.
   - *Option B:* `yang.rt.*` for both Clojure namespaces and linker module names.
3. **D3 — Python float tagging (r7: moot until Track B — py keeps its tagged `{:py/float}` representation as-frozen; boxed `yin.vm.float` ships for the Clojure path in P0′):**
   - *Option A (Recommended — Architect concurred):* Retain `{:py/float x}` behind a 2-line `py` boundary adapter around boxed `yin.vm.float` through P1 so Python's `1 == 1.0` oracle semantics stay cleanly isolated.
   - *Option B:* Migrate Python to untagged `data/float64` carriers in P1.
4. **D4 — Golden migration policy (`Settled — Owner Clean-Slate Directive`):**
   - Settled in favor of clean golden regeneration per slice (`P0`–`P4`), preserving correctness via behavioral, 4-host, and language-oracle suites rather than frozen-quirk backwards compatibility.
5. **D5 — Cross-syntax identifier mapping for Clojure names (`?`, `!`, `-`):**
   - *Option A (Recommended — Architect concurred):* Curated `:yang.semantics/ambient-projections` table validated against UAST-derived arity/roles, unit-bound `ambient-resolver`, lexical precedence, and reserved-door bans (`*.impl/*`, `dsg__*`).
   - *Option B:* Algorithmic mangling rule (`_q` / `_bang`).
6. **D6 — Publishing `clojure.core` and migrating `yang.clojure` (`Settled — Owner Clean-Slate Directive`):**
   - Settled in favor of publishing collision-free `clojure.core.impl` + cell-free `clojure.core` AND migrating `yang.clojure` directly onto unified `clj` + `clojure.core` in P3 (retiring `yang.clojure`'s legacy `library-for` duplication and unit-local `dsg__call_token` path).
7. **D7 — Float exponentiation rounding (r7: `fpow-half` ships in P0′ for the Clojure path; `fpow-base` defers to Track B with the php re-architecture):**
   - *Option A (Recommended for P0/P1 — Architect concurred):* Export both `float/fpow-half` and `float/fpow-base` in `yin.vm.float` so `1.1 ** 6` preserves bit-exact parity (`0x1.c585058dde7acp+0` for `py`/`js` vs `0x1.c585058dde7abp+0` for `php`) across existing CPython/Node and PHP language oracles.
   - *Option B:* Explicitly approve unifying PHP onto `fpow-half` (or `py`/`js` onto `fpow-base`) and update the affected language's oracle departure ledger.
8. **D8 — Three-tier hybrid architecture (`Settled — r6 Owner Directive`):**
   - Settled on: (1) `:pure` host value core (`yin.vm.data`, `yin.vm.integer`, boxed `yin.vm.float`, `kind/namespace`) for total, representation-sensitive ops; (2) unified guest algebraic effects (`yrt.effect` over `%capture`) for control flow, generators, and cold error/limit policy traps; (3) minimal VM/stream effects (`cell/*`, `stream/*`, `:module/require`) per ADR 0004.
9. **D9 — Focused swappable-prelude scope (`Settled — r6 Owner Directive`):**
   - Settled on supporting **Native profiles** (`py`, `js`, `php`, `clj`) for existing language code plus **N → 1 Yin/Clojure profiles** (`:yang.python/clojure-core-v1`, future `:yang.typescript/clojure-core-v1`), omitting unnecessary off-diagonal N×M profiles (such as `:yang.clojure/py-v1`) and keeping `yang.clojure` directly on unified `clj` + `clojure.core`.
