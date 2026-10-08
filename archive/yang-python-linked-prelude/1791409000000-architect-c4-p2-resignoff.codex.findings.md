# Final architectural sign-off: Track A Phase C4 Slice P2

Date: 2026-10-08.
Architect: Codex, Lead System Architect.
Branch: `yang-python-c4-p2`; baseline: `f0ade63e`; current HEAD: `6df4c12b`, with the P2 working-tree changes and linked acceptance files covered by the prior review.

**Architectural verdict: ACCEPTED for merge. Finding P2-R2 is resolved and closed. Finding P2-R1 remains closed. No P2 merge-blocking architectural findings remain.**

## Basis and evidence provenance

This ruling closes the execution-evidence gap in the [prior architectural ruling](1791408500000-architect-c4-p2-signoff.codex.findings.md). Its implementation assessment and A1-A12 analysis remain applicable. The controlling [L-f section 4 amendment](../archive/linker-l-f/1791407000000-architect-l-f-signoff.codex.findings.md#4-f3--serving-policy-deferred-liveness-and-p2-evidence-amendment) remains binding.

The completed verification record supplied with the request, dated 2026-10-08 14:18 GMT, is accepted as the reported execution evidence for the submitted P2 working tree. The fresh JVM and Node runs and recorded Dart results below were not independently rerun in this sign-off. Branch and HEAD were checked locally, the prior ruling and controlling amendment were read, and `git diff --check f0ade63e` was independently repeated successfully. HEAD alone does not identify the uncommitted acceptance files; this acceptance covers the reviewed working tree as submitted, not arbitrary later changes.

## Completed verification record

| Host and lane | Tests | Assertions | Result |
| --- | ---: | ---: | --- |
| Node fast, `bb test:cljs` | 3,698 | 106,373 | 0 failures, 1 known baseline error |
| Node slow, `bb test:slow:cljs` | 433 | 43,930 | 0 failures, 0 errors |
| JVM fast, `bb test:clj` | 3,833 | 242,065 | 0 failures, 1 known baseline error |
| JVM slow, `clj -M:test -i :slow -n yang.python.antlr.c3-gate-test` | 2 | 67 | 0 failures, 0 errors |
| JVM slow, `clj -M:test -i :slow -n yang.python.antlr.linked-prelude-test` | 7 | 70 | 0 failures, 0 errors |
| JVM, `clj -M:test -n yang.python.antlr.e2e-c1-test` | 19 | 323 | 0 failures, 0 errors |
| JVM, `clj -M:test -n yang.python.antlr.e2e-c2-test` | 32 | 234 | 0 failures, 0 errors |
| Dart fast, recorded working-tree evidence | 3,649 passed | Not supplied | 1 known baseline failure |
| Dart slow, recorded working-tree evidence | 336 passed | Not supplied | Passed, including linked-prelude and C3 parity |

The fast-lane exceptions are the previously documented UCF census text fixture, `test/resources/yin/vm/ucf/handoff-v2.txt`. They remain explicit baseline exceptions; this ruling does not describe the full fast suites as entirely green or claim that the fixture was repaired.

The completed Node slow run includes `c3-gate-test`, `linked-prelude-test`, `prelude-parity-test`, `lower-portable-test`, float address/text, integer contract/conversion/heap/operations, safepoint, `yin.repl.*`, and `yin.vm.linker*` coverage. The JVM fast record includes `yang.python.antlr.e2e-test`, linked-prelude, prelude parity, and the compiler pipeline. The targeted completed JVM runs supply the outstanding Python slow evidence; they are not represented as a completed aggregate JVM slow suite.

Reported static analysis, `clj -M:kondo --lint src/cljc/yang/python/antlr test/yang/python/antlr src/cljc/yin/vm test/yin/vm`, completed with zero errors and zero warnings.

## Finding dispositions

**P2-R2: CLOSED, resolved.** The missing Node fast and guarded slow completion records and outstanding JVM Python execution results are now supplied. Together with the retained Dart evidence, these close the multi-host execution gap for A1's three local verified publication outcomes, A3's fixed program-root golden, and A2's linked corpus parity. The prior incomplete or interrupted runs are superseded as acceptance evidence by the completed records above.

This file records the final lane counts as the closing acceptance addendum. A11's existing cost report remains accepted: publication approximately 37-45 seconds, linked execution 4.4-5.3 seconds, and bundled execution 0.4-1.0 seconds. No new per-backend measurements were supplied or reproduced. The prior request to consolidate those measurements in the engineer report is treated as nonblocking documentation follow-up, not an outstanding P2-R2 gate. The execution target overrun remains an acknowledged nonblocking performance finding; it is not declared fixed and does not authorize linker or CBOR tuning within P2.

**P2-R1: CLOSED, superseded.** The L-f amendment removes `a-verifying-serve-links-py` and verified-versus-trusted output parity from P2's acceptance gate. Local verified derivation and trusted serving remain distinct evidence. The deferred integration test has not passed. Large default-verifying serving liveness remains assigned to independent linker/REPL remediation, with finite-work bounds preserved.

Walker support remains assigned to L-b, Python-source module semantics remain assigned to I1, and the remaining L-a scope retains its prior disposition. None is reopened as P2 work.

## Final disposition

**Track A Phase C4 Slice P2, “Module emitter, py manifest, linked profile”: ACCEPTED for merge.** The prior architectural approval stands, and the execution-evidence blocker is closed with the named baseline exceptions and existing deferred work preserved. This ruling supersedes the prior REVISE verdict.

Only this findings file is authored by this sign-off. No implementation changes, staging, commit, or merge are performed.
