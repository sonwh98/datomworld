Created-GMT: 2026-11-22 03:19:14 GMT
Created-Local: 2026-11-22 10:19:14 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: not-applicable (agy assigns its own conversation id; capture it)

# Task: Step-7 review — yang.preludes P2′ (unified clojure.core + yang.clojure migration)

Role: Reviewer (independent, read-only)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-22 10:19 +07 | Status: active | Rationale: independent family from the codex/gpt-6.2-sol implementer; same reviewer lineage as P1′ (conv e4fbf30d-58bb-4263-9981-af6c3994b3fb) for design continuity

## What to review

The UNCOMMITTED working-tree diff in `/Users/sto/workspace/dw-p2prime` (branch `feat/p2prime-clojure-core`, base master `f51c3a0d` which contains landed P1′). Review the real diff (`git diff`; two NEW files `src/cljc/yang/clojure/core_impl.cljc` and `src/cljc/yang/clojure/core_module.cljc` — read them whole), not any summary. Do NOT modify anything.

- Governing design: `/Users/sto/workspace/dw-p2prime/docs/design/yang.preludes.md` r8 — review against §7.5 (two-module `clojure.core` contract), §9 row "P2′" (acceptance gates), §6.2, §8.1.
- Engineer brief: `/Users/sto/workspace/datomworld/collab/yang-clojure/1795286006293-engineer-p2prime-clojure-core-migration.prompt.md`.
- Context: the engineer's session was cut by a quota limit AFTER completing implementation and its verification matrix (captured logs green) — the final report is missing; your review is the check on the deliverable itself.

## Already verified by the orchestrator (do not re-run suites)

JVM: `clj -M:test -n yang.clojure-test -n yang.clojure-callsite-test -n yang.rt.effect-test` = 160 tests / 1677 assertions / 0 failures; `d2-wedge-min-repro` 1/7/0; `yang.latte-oracle-test` 37/2608/0; `analyze-deps` clean; py/js/php diff empty. Delegate-captured logs also show an adjacent lane 16 tests / 91501 assertions green. Spot-check code, not suites.

## Review focus (prioritized)

1. **§7.5 `clojure.core.impl` contract:** pure/stateless (ZERO state cells, no `:ambient-projections`, zero direct provenance doors); every definition `impl-*` prefixed with zero bare primitive store collisions (check internal calls resolve to VM primitives: `get`, `assoc`, `conj`, `first`, `rest`, `vec`, `nil?`, `empty?`, `not`); `impl-cell-ref?` authenticated cells answer false to ALL nine collection predicates; `impl-fn?` matches `clj/admitted-callable?` 1-to-1; policy tags in the handler contract while only underlying VM effects declared through the linker footprint; the P2′ cold-error policy installed by `yang.clojure` itself (NOT `clj.py/run-main` — that's P3′).
2. **§7.5 facade:** cell-free pure exports; idempotent 0-arg `init!` building `{sym cell}` via `clj/prim-assoc` + `clj/make-callable`; 1-arg `export` via `clj/prim-get`; 0-arg cell accessors; dynamic closures construct cells via `clj/make-callable`, callbacks invoke via `clj/invoke-callable`; excluded ops (`var?`, `resolve`, `invoke-value`) absent.
3. **`yang.clojure` migration:** inline `library-for` duplication retired — ONE unified runtime; no legacy residue (`dsg__call_token`, `dsg__intra_call`, unit-local provenance paths stay retired); P1′'s bundled door enforcement (`yin.vm.provenance-scope`, content-address checks) NOT weakened — the P2′ diff touches `engine.cljc` and `prelude.cljc`, scrutinize those hunks specifically.
4. **The §9 H7 fixtures:** the locally-bound-map `:k` → 9 native data-call dispatch; bundled primitive shadowing of bare `get`/`assoc` through the new standard library. Verify the FIXTURES actually encode these properties (and weren't weakened vs. the brief's intent).
5. **Scope fences + portability:** py/js/php untouched; no JVM-only reflection; canonical ordering intact (CBOR byte-identity); zero raw host doubles; `admit-key?` enforced in the map/set mutators; `impl-str` deterministic renderer matches §7.5.
6. **`data/nth` discipline** in new frame/payload vector reads; `get` only on maps.

## Deliverable

Write findings to `/Users/sto/workspace/dw-p2prime/collab/yang-clojure/1795289954620-reviewer-p2prime-clojure-core.gemini-4-argon-high.findings.md` with the artifact header. Verdict per finding CRITICAL/HIGH/MEDIUM/LOW/NOTE with file:line evidence, then ONE overall verdict: `VERDICT: APPROVED` / `APPROVED-WITH-FIXES` / `CHANGES-REQUIRED`. End your agent message with the same verdict.
