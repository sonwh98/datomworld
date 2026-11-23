Created-GMT: 2026-11-22 05:39:39 GMT
Created-Local: 2026-11-22 12:39:39 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: yang.preludes P3′ — descriptor parameterization + clj.py + the DEFAULT flip

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-22 12:39 +07 | Status: active | Rationale: proven model with P1′/P2′ lineage; POOL 1 confirmed available by the owner

## Working tree

Work EXCLUSIVELY in `/Users/sto/workspace/dw-p3prime` (branch `feat/p3prime-descriptor-cljpy`, base master `b1eb3e40` — contains landed P1′ `3749514d` and P2′ `a9977e58`). Your cwd IS your writable sandbox root. `git add -N` every NEW file (including tests). Do NOT commit.

## Prior slices (landed — build on them)

- P1′: `yang.rt.effect` (`yrt.effect`), `yang.rt.int` (`yrt.int`), unified trusted `clj` (make-callable/invoke-callable/prim-get/prim-assoc, private doors, bundled content-address enforcement via `yin.vm.provenance_scope`), 5-slot frames, guest try/catch.
- P2′: `src/cljc/yang/clojure/core_impl.cljc` (`clojure.core.impl`) + `core_module.cljc` (`clojure.core` facade); `yang.clojure` migrated; locally-bound-map `:k`→9 native dispatch; bundled shadowing.
- Standing lessons: RUN FULL NAMESPACES (`-v` selections let a 38-failure breakage reach the gate in P1′); the gate goes wide (full `bb test`) when yin.vm/core is touched — expect it and don't fear it; pre-existing master reds are NOT yours (listed at the bottom).

## Governing design

`docs/design/yang.preludes.md` r8 — this slice is the largest; read ALL of: §6.1 (DEFAULT vs legacy track), §6.2 (core/frontend ownership), §6.3 (the complete `:yang.semantics/descriptor` schema — your data contract, quoted in full), §6.4 (validation/hygiene/authority/parameterization — items 1–6 are binding), §6.5 (frontend SPI, revision pins, default selector), §7.1–§7.6 (the python case study: value representation, `clj.py` contract, entry-lowering contracts), §9 row "P3′" (your acceptance gates, quoted in your brief below). r8 reviewer conditions are folded into the doc — do not re-derive: entry-lowering contracts carry BOTH shapes; oracle assignment (native-oracle suites gate LEGACY pins, capability-refusal suites gate the default); address-bound export-role authorization key `(module address, export symbol, exact role)`.

## Scope (per §9 P3′ row)

1. **Generic `yang.core.semantics`** (`src/cljc/yang/core/semantics.cljc` — language-agnostic, extending the landed file): schema validation for `:yang.semantics/descriptor`; closed literal-strategy emission (`:raw`, `:float64-raw`, `:tagged-map`, `:raw-or-call`); generic capability lookup; hook-hygiene validation (every hook namespace-qualified, `:yang.semantics/unqualified-hook` otherwise); `validate-descriptor` with UAST-derived structural export/arity/ROLE verification against the selected address-verified closure's `:yin.module/tree` (wide-app `(yin/def ...)` definitions) across hooks, executable literal-strategy fields, and ambient projections — including: signature-vs-call-shape checks against the frontend contract's recorded entry contract; cell-accessor projection checks (unique aliases, 0-arg lambda target, module exports 0-arg `init!` + 1-arg `export`); the reserved-door ban (`cell/dsg__*`, `dsg__*`, `yin/def`, `*.rt/*`, `*.b/*`, `yang.safepoint/*`, `*.impl/*`); and the address-bound role key — a same-arity wrong-role target fails closed (gate: substituting subtraction for addition in the same module must fail).
2. **The python frontend contract** (`yang.python.antlr.contract` or per repo convention): the fixed Hook ABI table (`{hook-key {:arity n :required-by [...]}}`), per-hook role records, and the entry-lowering contract carrying BOTH shapes — legacy `py/run-main` 1-arg (FROZEN exports, never re-arched) and the Clojure profile's 2-arg `[body-fn ambient-resolver]`.
3. **Refactor `yang.python.antlr.lower` to read literals/hooks/capabilities from `:yang.semantics/descriptor`** — parameterization, NOT re-architecture. The legacy `py-v1` descriptor pins today's `'py/*` hooks 1:1; behavior-preserving. Fail-closed capability enforcement during lowering per §6.4 item 5 (classdef, yield_expr, genexpr (gated by `:control/generators`), with_stmt, del_stmt, typed except, kwargs, defaults, subscript mutation → `:yang.semantics/unsupported-capability` with capability/semantics-id/construct/node).
4. **Publish `clj.py`** per §7.3 (the `:fn/make` → `clj/make-callable` 5-arg delegation, `:fn/call` → `clj/invoke-callable`, zero provenance doors inside `clj.py`) and §6.3's hook table (`clj.py/add` … `clj.py/run-main`, comprehension accumulators, `:literals.int` overflow-op `clj.py/int-lit`, `:unbound` `:clj/unbound` raw). clj.py requires `yrt.effect` and the landed modules; its `run-main` installs the cold-error policy handler (the P3′ owner per §7.5).
5. **The DEFAULT flip**: define the `:yang.python/clojure-core-v1` descriptor (per §6.3 verbatim as the data contract), pin it as a `:yang.frontend/revision` with `:yang.frontend/lowering-profile` + `:runtime-profile` addresses, and flip the default via the §6.5 default-selector mechanism (composition's immutable configuration carrying preferred `[frontend-id revision]`, applied at normal request entry) — the LEGACY `py-v1` revision stays installed and selectable by explicit pin.

## Acceptance gates (§9 P3′ row — all must hold)

- **Legacy-descriptor behavior preservation**: full CPython corpus + 4-host parity under the `py-v1` pin — the existing CPython oracle suites pass UNTOUCHED; py goldens regenerate under the descriptor-driven path with legacy descriptor byte-identical outcomes.
- End-to-end `:yang.python/clojure-core-v1` suite (fresh goldens): numeric/float ops, `:max-bits 53` limit traps, `data/admit-key?` refusals, `data/equiv`, `data/canonical-entries` ordering, deterministic `str`.
- **The cross-syntax `clj → clj.py → clj` unwind gate** — cleanup and context restoration, exactly once per activation (THIS slice, after clj.py exists).
- Capability refusal tests: typed `except E:`, `genexpr` under `:control/generators :refused`.
- Default-profile regressions (r8, exact): unqualified `:k` key admits (namespace-non-empty rule = two-part keywords only); equal exact integers in different host carriers hit the same map entry on insert AND lookup; a local/projected value equal to `:clj/unbound` stays distinguishable from an absent binding; canonical traversal order = Jing encoded-byte order (`0` before `nil`).
- UAST-derived validation rejecting: wrong arity, non-lambda hooks, unqualified hooks, reserved doors, out-of-closure exports, same-arity wrong-role.
- 4-host where lanes allow (JVM + Node at minimum; note any host you couldn't run).
- `clj ./bin/analyze-deps.clj` clean.

## Out of scope

- js/php descriptor contracts and profiles (P4′); Track B legacy re-architecture; NO changes to the py/js/php PRELUDE modules or their mechanisms (only the lowerer reads descriptors); php/ `yang/php/` surfaces untouched.
- Known pre-existing master reds (exclude, do NOT fix — a qa engineer owns them per the owner): `yang.python.antlr.float-address-test` 5 failures; `yang.python.antlr.linked-prelude-test` 71-vs-72; `dao.host.gate-test` 29 failures; cljd `-map-lit` compile failure in `yin.vm.data-test` on jupiter; `d2-wedge-probe2` budget exhaustion.

## Tooling + discipline

`export MISE_TRUSTED_CONFIG_PATHS="$PWD"; eval "$(mise env)"` for bb/clj. Full namespaces for clojure/yang.python suites. Deterministic canonical ordering (§8.1), no JVM-only reflection, zero raw host doubles in guest UAST (boxed `yin.vm.float` only). Match surrounding code style.

## Deliverable

Implementation on `feat/p3prime-descriptor-cljpy`, UNCOMMITTED (no commit authority). Final report: files added/changed per scope item; each gate with exact command + counts; py-v1 legacy corpus parity evidence; fresh goldens list; the cross-syntax unwind fixture description; ambiguities resolved with §/quotes; any gate not run and why.
