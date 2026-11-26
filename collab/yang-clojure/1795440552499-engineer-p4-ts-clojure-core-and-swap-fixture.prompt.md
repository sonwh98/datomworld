Created-GMT: 2026-11-22 18:35:52 GMT
Created-Local: 2026-11-23 01:35:52 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: yang.preludes P4′-a — :yang.typescript/clojure-core-v1 descriptor + ts frontend contract + the composition-swap fixture

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-23 01:35 +07 | Status: active | Rationale: proven model across P1′–P3′ lineage; POOL 1 per owner availability

## Working tree

Work EXCLUSIVELY in `/Users/sto/workspace/dw-p4ts` (branch `feat/p4-ts-clojure-core`, base master `0c36043d` — contains landed P1′/P2′/P3′ and the dao.stream ws fix). cwd = writable sandbox root. `git add -N` every NEW file. Do NOT commit (fix rounds may commit only if told).

## Prior slices (landed — the patterns to follow)

P3′ (`5b655b59`) is YOUR TEMPLATE: `src/cljc/yang/core/semantics.cljc` (generic descriptor validation incl. address-bound role keys), `src/cljc/yang/python/antlr/contract.cljc` (frontend contract pattern), `profiles.cljc` (descriptor definition + verify-builtin pattern), `clj_runtime.cljc` (the clj.py adapter pattern), and `test/yang/python/antlr/descriptor_profile_test.cljc` (the gate-fixture pattern). Read these before writing anything ts.

## Governing design

`docs/design/yang.preludes.md` r8: §6.3 (descriptor schema verbatim), §6.4 (validation/hygiene/authority — items 1–6 binding; item 6 names your slice: ts lowerer reads literals/hooks/capabilities from the descriptor, preserving full Node parity under the legacy pin; the ts native entry shape is the **1-operand** `js` form per the entry-lowering contracts), §6.5 (SPI, revisions, default selector), §9 row "P4′". Oracle assignment (r8): the existing ts/js oracle suites gate the LEGACY pin; capability-refusal suites gate the new profile.

## Scope

1. **The `:yang.typescript/clojure-core-v1` descriptor** (in a `yang.typescript.antlr` profiles namespace, mirroring `yang.python.antlr.profiles`): capabilities declared honestly per the ts grammar's actual lowering surface (mutations/refusals, exceptions, ambient-only imports — derive from the landed ts lowerer's hooks; do NOT invent capabilities the lowering can't honor). Ambient projections per §6.3's table (python names → ts-idiomatic names where the grammar demands, e.g. camelCase; keep the projection aliases legal identifiers for the ts frontend). `:unit/run-main` → the ts adapter's resolver-bearing entry.
2. **The ts frontend contract** (`yang.typescript.antlr.contract` or repo convention): fixed Hook ABI table with arities + per-hook role records; the entry-lowering contract carrying BOTH shapes — the FROZEN native ts/js 1-operand entry and the Clojure 2-arg `[body-fn ambient-resolver]` shape.
3. **Refactor `yang.typescript.antlr.lower` to read literals/hooks/capabilities from the descriptor** — parameterization like P3′'s python change: a legacy `ts-v1` descriptor pins today's `'ts/*`/js hooks 1:1, behavior-preserving, gated by the EXISTING ts oracle suites untouched. Fail-closed capability enforcement per §6.4 item 5.
4. **The ts adapter module** (the clj.ts analogue of `clj_runtime.cljc`): zero provenance doors inside it; `:fn/make` → `clj/make-callable`, `:fn/call` → `clj/invoke-callable`; run-main installs the cold-error policy handler.
5. **The DEFAULT flip for ts**: define both revisions, wire the default selector to `clojure-core-v1`, keep `ts-v1` explicitly pinnable, in-flight snapshots retained — exactly the P3′ python mechanism (reuse, don't reinvent).
6. **THE COMPOSITION-SWAP FIXTURE (owner directive, binding)**: a fixture proving the descriptor is just data — take the landed python `clojure-core-v1` path (NOT ts), swap ONE ambient module for another valid admitted module in a modified descriptor (e.g. project an export from a second admitted module with role-valid entries), and prove end-to-end with ZERO frontend changes: the swapped descriptor validates, lowers, executes (JVM+Node at minimum), and the derivation record in provenance names the swapped module's address. Also include the negative: a swap to a module whose export lacks the required role fails closed. This fixture goes in the python descriptor-profile test file (the demonstrated frontend is python; ts is your main deliverable but the fixture proves composition on the stable path).

## Acceptance gates

- Legacy ts behavior preservation: existing ts oracle suites pass UNTOUCHED under the `ts-v1` pin (full namespaces, not `-v` selections).
- End-to-end `:yang.typescript/clojure-core-v1` suite (fresh goldens): numeric ops, admit-key? refusals, equiv, canonical-entries ordering, deterministic str, capability refusals failing closed before emission.
- The swap fixture per item 6 (positive + negative paths).
- UAST-derived validation rejecting wrong arity / non-lambda / unqualified hooks / reserved doors / out-of-closure exports / same-arity wrong-role (reuse P3′'s validation — do not fork it).
- 4-host where lanes allow (JVM + Node minimum; ts is Node-adjacent — Node is your second host).
- `clj ./bin/analyze-deps.clj` clean.

## Out of scope

- The php profile (a parallel engineer's slice — do NOT touch `yang/php/`); Track B; changes to P1′–P3′ semantics (reuse only).
- Known pre-existing master reds (qa-owned, exclude): `dao.host.gate-test` 29; `yang.python.antlr.float-address-test` 5; linked-prelude pin red; cljd `-map-lit` compile on jupiter; `d2-wedge-probe2`.

## Tooling + discipline

`export MISE_TRUSTED_CONFIG_PATHS="$PWD"; eval "$(mise env)"`. FULL namespaces. Canonical ordering (§8.1), no JVM-only reflection, zero raw host doubles. Match surrounding style.

## Deliverable

Implementation on `feat/p4-ts-clojure-core`, UNCOMMITTED. Final report: files per scope item, each gate with exact command + counts, legacy-parity evidence, swap-fixture description, ambiguities resolved with §/quotes.
