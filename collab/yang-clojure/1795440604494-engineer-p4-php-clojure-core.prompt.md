Created-GMT: 2026-11-22 18:36:44 GMT
Created-Local: 2026-11-23 01:36:44 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: not-applicable (agy assigns its own conversation id; capture it)

# Task: yang.preludes P4′-b — :yang.php/clojure-core-v1 descriptor + php frontend contract + clj.php adapter

Role: Engineer

Implementers:
- Status-Event: 2026-11-23 21:30 +07 | Model: gemini-4-argon-high | Status: timed-out | Rationale: agy argon pool exhausted (429, resets ~2026-11-28) before any work landed; conversation f44b412c-24b7-4523-aeec-966124f3a987 errored with zero output
- Model: gpt-6.2-sol | Assigned: 2026-11-23 21:35 +07 | Status: pending (dispatches after the P4′-a codex run completes — one-pool sequencing per team.md) | Rationale: owner-directed codex/agy delegation; agy unavailable

## Working tree

Work EXCLUSIVELY in `/Users/sto/workspace/dw-p4php` (branch `feat/p4-php-clojure-core`, base master `0c36043d` — contains landed P1′/P2′/P3′). cwd = writable sandbox root. `git add -N` every NEW file. Do NOT commit.

## Template to follow

P3′ (`5b655b59`, in master) is your template: `src/cljc/yang/python/antlr/contract.cljc` (frontend contract pattern), `profiles.cljc` (descriptor + verify-builtin), `clj_runtime.cljc` (the adapter-module pattern — yours is clj.php), and `test/yang/python/antlr/descriptor_profile_test.cljc` (gate fixtures). Also study the landed php surfaces: `src/cljc/yang/php/antlr/lower.cljc` and the php prelude.

## Governing design

`docs/design/yang.preludes.md` r8: §6.3 (descriptor schema), §6.4 items 1–6 — item 6 is your slice, and it names the php-specific hard parts: the **entry-lowering contract carries the landed php native entry shape (`php/run-main`, a 4-parameter lambda legitimately called with 2 or 4 operands, request stream in slot two — FROZEN, never re-arched)** alongside the Clojure 2-arg `[body-fn ambient-resolver]` shape; signature-vs-call-shape validation must accept BOTH shapes for the frozen export; "Both PHP modes are gated, including observable stream output". §9 row "P4′": "`:yang.php/clojure-core-v1` after [ts]". Oracle assignment: existing php oracle suites gate the LEGACY pin; capability-refusal suites gate the new profile.

## Scope

1. **The `:yang.php/clojure-core-v1` descriptor** (php profiles namespace mirroring the python one): capabilities declared honestly per the landed php lowering surface; ambient projections per §6.3 (php-idiomatic aliases where grammar-appropriate); `:unit/run-main` → the clj.php resolver-bearing entry.
2. **The php frontend contract**: fixed Hook ABI table (arities + role records); the entry-lowering contract with BOTH shapes (frozen native 2-or-4-operand php/run-main + Clojure 2-arg) — the signature-vs-call-shape validation in `yang.core.semantics` must accept the frozen export's dual shape via the contract's recorded permitted set (this is the §6.4 r8 pattern; if `yang.core.semantics` needs a small generalization to express the 2-or-4 permission, make it in the contract-recorded shape, NOT in the frozen php prelude).
3. **Refactor `yang.php.antlr.lower` to read literals/hooks/capabilities from the descriptor** — legacy `php-v1` descriptor pins today's `'php/*` hooks 1:1, behavior-preserving, gated by the EXISTING php oracle suites untouched. Fail-closed capability enforcement per §6.4 item 5.
4. **The clj.php adapter module** (analogue of python's `clj_runtime.cljc`): zero provenance doors inside it; `:fn/make` → `clj/make-callable`, `:fn/call` → `clj/invoke-callable`; run-main installs the cold-error policy handler and preserves the php observable-stream-output gating (a clojure-core-v1 php program's stream output is gated like the native mode's).
5. **The DEFAULT flip for php**: both revisions, default selector → `clojure-core-v1`, `php-v1` explicitly pinnable, in-flight snapshots retained — the P3′ python mechanism, reused.
6. **End-to-end suite + capability refusals** (fresh goldens for clojure-core-v1): numeric ops, admit-key? refusals, equiv, canonical ordering, deterministic str, refusals failing closed before emission, and the stream-output gate demonstrated under the new profile.

## Acceptance gates

- Legacy php behavior preservation: existing php oracle suites pass UNTOUCHED under the `php-v1` pin (full namespaces).
- The clojure-core-v1 php suite green (fresh goldens) with stream-output gating proven.
- UAST-derived validation rejections (reuse P3′ validation; the dual-shape entry permission exercised: a 3-operand call to php/run-main must fail, 2 and 4 must pass under the legacy contract).
- 4-host where lanes allow (JVM + Node minimum; note any host you couldn't run).
- `clj ./bin/analyze-deps.clj` clean.

## Out of scope

- The ts profile + composition-swap fixture (a parallel engineer's slice — do NOT touch `yang/typescript/` or the python descriptor tests); Track B; changes to P1′–P3′ semantics (reuse only). **php prelude modules stay frozen** — only the lowerer reads descriptors.
- Known pre-existing master reds (qa-owned, exclude): `dao.host.gate-test` 29; float-address 5; linked-prelude pin red; cljd `-map-lit` compile on jupiter; `d2-wedge-probe2`.

## Tooling + discipline

`export MISE_TRUSTED_CONFIG_PATHS="$PWD"; eval "$(mise env)"`. FULL namespaces (never `-v` selections). Canonical ordering (§8.1), no JVM-only reflection, zero raw host doubles. Match surrounding style.

## Deliverable

Implementation on `feat/p4-php-clojure-core`, UNCOMMITTED. Final report: files per scope item, each gate with exact command + counts, legacy-parity evidence, the dual-shape validation demonstration, ambiguities resolved with §/quotes.
