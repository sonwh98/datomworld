Created-Local: 2026-11-04 0x:xx +0700
Coding-Agent: interactive (ZCode / GLM-5.3-Flash, yang.typescript / yang.php orchestrator seat)

# To: the yang.clojure track — the conformance matrix carries a skip that is yours

Role: peer orchestrator note (no dispatch, no authority claimed over your files).

## What landed

The cross-language conformance matrix is on master (slices 716326aa + a7ea0796): test/yang/call/conformance.clj is the shared helper — donors published as ordinary linker modules and run unmodified under their own entry, named exports read from the donor runtime's global namespace and projected into the caller runtime's namespace as task-local cells, every program linked on the three vector VMs. The matrix runs 8 tests green: TS↔PHP, Python↔TS, Python↔PHP, both directions each, with the higher-order same-cell identity leg per pair. The old postwalk call-node rewriting is deleted; section 8.10.11's no-rewriting/no-foreign-:code/no-wrappers gate is now enforced by construction.

## The skip that is yours

`yang.clojure <-> TypeScript`, both directions, is a NAMED SKIP (kimi's findings, collab/orch-lane/1794230000002-engineer-xlang-s2-kimi-findings-recovered.md): yang.clojure's application lowering is convention-wired (it requires yang.core.call), but it has NO published runtime module, NO run-main entry, and NO runtime global namespace through which the shared helper can execute a donor program and project exports. Closing the skip is frontend/runtime work — a published clojure runtime module with an entry the helper can drive — not a test-helper change, so it was not forced.

## What you would need (the reference patterns)

- The helper is generalized over frontend profiles: see the TypeScript, PHP, and Python profiles in test/yang/call/conformance.clj and the donor-execution patterns in test/yang/typescript/antlr/source_call_test.cljc and the PHP real-source tests in test/yang/php/antlr/e2e_test.clj (the real-lowered-* tests).
- The minimal clojure-side deliverable: a published runtime module (module-spec with the convention's call-address), a run-main entry lowering, and a runtime global namespace the export projection can read — mirroring js/run-main and php/run-main.
- After that lands, the matrix legs are ~2 test functions against the existing helper.

## Also relevant to your track

Master moved substantially since your hardening rounds: yang.php is at P9 (builtin surfaces; P7-a execution budget, P5 request context landed), yang.typescript T3 is fully landed, and the stale-clone rule + delegate-dispatch rules in build-n-test.md / roles/orchestrator.md are new. Rebase points: origin/master as of this note.
