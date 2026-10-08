Completed-GMT: 2026-10-07 22:01:31 GMT
Completed-Local: 2026-10-08 05:01:31 ICT

# Track A Slice P1 Architectural Re-Evaluation

Role: Lead System Architect — Codex.
Branch: `yang-python-c4-p1`; HEAD: `802d9ee2dfb32ff1c3552427d8e2963225ae4edc`.

## Final verdict: ACCEPTED

Architectural sign-off is granted for the bundled Track A C4 P1 slice. This supersedes the temporary rejection in `collab/1791401500000-architect-c4-p1-signoff.codex.findings.md`. The supplied fresh Dart execution evidence closes the outstanding P1 host-verification gap; the baseline failure is now explicitly accounted for; the definition-table consumer change and golden re-pins are documented. No architectural implementation defect remains identified.

This is an evidence re-evaluation, not a new test execution. I read the revised engineer report, prior architectural findings, governing architecture and specification, available lane logs, and relevant working-tree changes and tests. The fresh Dart counts below are supplied execution evidence corroborated by the revised report; I did not independently rerun them or locate separate fresh Dart logs in `target/`. `git diff --check` passed in this review. Only this report was written.

## Closure of the prior findings

### 1. Dart P1 acceptance and VM parity — CLOSED

| Fresh execution supplied for re-evaluation | Result |
|---|---|
| `bb src/dev/cljd_agg.clj --only yang.python.antlr.prelude-parity-test,yang.python.antlr.float-address-test,yang.python.antlr.int-conv-test` | 57 tests passed, 0 failures; “All tests passed!” |
| `DATOM_SLOW_TESTS=1 bb src/dev/cljd_agg.clj --only yang.python.antlr.prelude-parity-test` | 32 tests passed, 0 failures; “All tests passed!” |

The fast selection covers definition-inside-lambda, definition-only loading without runtime modules, initialization idempotence, exact builtin seeding, shadow/delete fallback, mutation, and portable address/integer-conversion tests. Inspection confirms the P1 lifecycle tests enumerate VM runners. The slow selection executes the guarded prelude parity bodies, including semantics, float identity, delegation/unwinding, generator throw/close, and integer host-defect behavior across walker, semantic, stack and register VMs. These are tests across four VMs, not a claim that the suite contains exactly four slow tests. Dart assertion totals were not supplied and are not inferred.

Together with the previously reviewed JVM and Node evidence, this is sufficient for P1 architectural acceptance. The recorded JVM focused result remains 135 tests / 1,414 assertions, the JVM Python slow result 76 / 2,645, and Node slow result 354 / 43,202, all with zero failures/errors. The fresh focused Dart runs supplement the earlier full fast runs; they are not a new full-repository Dart slow run.

### 2. Missing UCF fixture — CLOSED FOR P1 BY EXPLICIT BASELINE EXCEPTION

I accept the missing `test/resources/yin/vm/ucf/handoff-v2.txt` as a pre-existing, out-of-scope baseline defect for this P1 architectural sign-off. Firsthand repository inspection confirms that HEAD already contains the census test referencing that path, while `git ls-tree HEAD test/resources/yin/vm/ucf/` contains no such fixture. The nine tracked P1 changes touch Python/ANTLR and its design document, not UCF or this fixture.

The actual repository-wide outcomes remain failed lanes:

| Existing full fast evidence | Recorded outcome |
|---|---|
| `target/p1-test-clj.log` | 3,754 tests / 239,163 assertions; 0 failures, 1 error |
| `target/p1-test-cljs.log` | 3,610 tests / 103,554 assertions; 0 failures, 1 error |
| `target/p1-test-cljd.log` (previously reviewed) | +3561 -1; “Some tests failed.” |

Each identifies the same missing-fixture census test. This exception applies only to that known failure and does not certify repository-wide green status or waive future unrelated failures. Fixture restoration remains repository maintenance outside P1. The revised report's phrase “All 3,754+ other tests pass” is imprecise: 3,754 is the JVM total, not a common passing count across hosts; use the lane-specific counts above.

### 3. Consumer rename and golden reporting — CLOSED

The revised report records the `prelude/function-definitions` to `prelude/definitions` consumer update and the address/tree re-pins. Inspection confirms the string-literal lint now traverses builtin and method bodies nested within `py/init!`, expanding coverage. Precisely, it checks that `:py/str` literal payloads are strings; it does not check integer-conversion magnitude boundaries as the report's wording suggests. This wording correction is non-blocking.

For an explicit record, the changed semantic address goldens in `float_address_test.cljc` are:

| Golden | New `segment/blake3-` suffix |
|---|---|
| Bundled prelude | `09131b20d81c468468c952051974373c8963dd82f17c4154c8bc974158a06976` |
| A | `8b09d94fd093759cf7a6f58c69c01a9bf6a47ed89f42174a5367b1b2461cf30e` |
| A′ | `26b026cf477c69abebb975397ddae0eb4d7eb2c5f417ded8218c25208600aabc` |
| Record | `f0de9df6e0f5bd4e8d1de106d361823183b290674b18d252ac7ab49fe891cf32` |
| Shared prelude subtree | `91b76c3c80254abf5407f850a8311cb6d5f613ecfb94f5c6e525121dfed1b7ec` |

The lowering tree goldens replace static builtin references with ordinary `py/global-get` reads. These changes follow from the new bundled prelude and lookup structure; they do not change the encoding contract. The safepoint prelude address remains unchanged.

## Architectural decisions and scope

| Decision | Final evaluation |
|---|---|
| A1 — one definitions table, explicit allocation and initialization | PASS |
| A2 — uninitialized guard, idempotence, readiness written last | PASS |
| A3 — visible builtin functions separated from methods | PASS |
| A4 — module dictionary then current builtins dictionary lookup | PASS |
| A5 — four-VM behavior across JVM, Node and Dart | PASS for P1, with the explicit baseline exception above |
| A6 — removal of the static builtin-name lookup map | PASS |

The prior invariant assessment stands: explicit task-owned state, no added hidden global/shared host mutable state, no new callbacks or host exports, preserved stream boundaries, literal definition keys, and no guest-generated `yin/def`. Canonical exception references and the specified per-module `globals` fallback remain intact.

Acceptance covers P1's bundled lifecycle only. Cross-unit identity, linked module-store ownership, empty-heap linked installation, publication, persistent REPL state and safepoint lifecycle migration remain P2/P3/F3 obligations. Repeated `py/init!` is idempotent; reinstalling all bundled definitions is not the linked lifecycle being certified. No redesign or further P1 implementation revision is required by this review. No commit was made.
