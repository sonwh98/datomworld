Completed-GMT: 2026-10-07 21:56:17 GMT
Completed-Local: 2026-10-08 04:56:17 ICT

# Track A Phase C4 Slice P1 Architectural Sign-Off Review

Role: Lead System Architect — Codex.
Branch: `yang-python-c4-p1`; HEAD: `802d9ee2dfb32ff1c3552427d8e2963225ae4edc`.
Scope: nine tracked working-tree changes, governing architecture, P1 specification, engineer report, adversarial review, and available P1 lane logs. Read-only review; only this requested report was written. No test suites were rerun, dependencies repaired, implementation changed, or commit made. Timestamps use the actual system clock and Asia/Ho_Chi_Minh (UTC+07).

## Architectural Evaluation

The implementation is architecturally consistent with the P1 specification. Overall completion sign-off is withheld because the required verification gate is not established.

| Decision / invariant | Evaluation |
|---|---|
| Explicit state; no hidden globals or shared host mutable state | PASS. Runtime state is represented by task-owned store bindings and explicit cell operations. Host-level tables are immutable code-generation data, not ambient Python process state. The change introduces no host callbacks, new host exports, or implicit initialization. Existing stream/VM boundaries remain intact. |
| A1 — single definitions list and explicit allocation | PASS. `prelude.cljc:3006` assembles `definitions` from core lambda forms, literal `py.rt/state = :py/uninit`, and the `py/init!` lambda. `functions-uast` emits that list without executing the initializer. `init-form` creates the three runtime cells, classes in base-first order, builtin functions, method objects, and the builtins dict; seeds classes then functions; writes `:py/ready` last. Bundled `uast` explicitly invokes `py/init!` before the module body. No runtime cells are allocated merely by defining the base prelude. |
| A2 — idempotence and uninit guard | PASS. Allocation is exclusively under equality with `:py/uninit`; other states return `:py/None` without allocation or reseeding. Readiness is the final write, so a failed initialization cannot advertise success. Initialization executes no guest bodies, making this sufficient for the specified single-task execution model. It is not a concurrent initialization lock. Identity tests cover representative class/function objects, builtins and all three runtime cells; the adversarial review additionally reports unchanged heap allocation keys. |
| A3 — builtin functions versus methods | PASS. The 25 Python-visible functions occupy `builtin-functions`; method implementations occupy a separate table and are allocated but omitted from namespace seeding. The exact ordered-key test checks class/function visibility and excludes methods. Canonical interpreter exception references remain `py.b/*` store references, independent of dictionary mutation. |
| A4 — dynamic global lookup | PASS. `py/global-get` checks module dictionary key presence, then current builtins dictionary key presence, then raises canonical `py.b/NameError`. Presence checks preserve false/None values. Assignment and cleanup affect only the module dictionary, restoring builtin fallback after deletion. Class-body fallback follows this path. The specified `globals` exception retains the per-module `%globals-fn` via `py/global-or`. |
| A5 — walker, semantic, stack, register parity | IMPLEMENTED; ACCEPTANCE EVIDENCE INCOMPLETE. Tests enumerate all four VMs for definition/redefinition, definition-only load, initialization identity, seeding, shadow/delete and mutation. The independent reviewer reports passing JVM execution and additional probes on all four. This establishes the bundled JVM mechanism; it does not establish the required current full-corpus result on every host. See gaps below. |
| A6 — static builtin-name map removal | PASS. The base prelude's `builtin-names` map and compiler references are removed. Ordinary lowering emits `py/global-get` and no `py.b/*` variable. Production seeding derives from the class/function tables. The test-only list of expected function names is an independent visibility assertion, not a runtime lookup map. |

Other specification invariants: Rule R literal definition keys are retained; guest lowering introduces no `yin/def`; `host-names` and admission remain unchanged. The safepoint prelude is untouched. Its separate load-time state is explicitly P3 scope, not a violation of P1's base-prelude lifecycle. P1 remains bundled: cross-unit identity, module-store ownership under linking, empty-heap linked installation, publication and persistent REPL state remain P2/P3/F3 obligations and are not certified here. Calling `py/init!` repeatedly is idempotent; reinstalling the complete bundled definitions would reset the literal flag and is not the promised linked lifecycle.

## Verification Evidence

These are prior recorded runs, not new executions by this review. Unversioned target logs do not prove that each run used the exact final working-tree contents.

| Evidence | Recorded result |
|---|---|
| Engineer focused JVM suite | 135 tests, 1,414 assertions; 0 failures, 0 errors (report only). |
| `target/p1-test-clj.log:654` | 3,754 tests, 239,163 assertions; 0 failures, **1 error**. |
| `target/p1-test-cljs.log:590` | 3,610 tests, 103,554 assertions; 0 failures, **1 error**. |
| `target/p1-test-cljd.log` | Final runner count **+3561 -1**, “Some tests failed.” Assertion count not supplied. |
| `target/p1-slow-clj.log` | 76 tests, 2,645 assertions; 0 failures, 0 errors. |
| `target/p1-slow-cljs.log:99` | 354 tests, 43,202 assertions; 0 failures, 0 errors. |
| Dart slow lane | No result located in the supplied reports or available P1 logs. |
| Independent adversarial JVM fast / slow | 92 tests / 820 assertions and 72 tests / 2,551 assertions respectively; both 0 failures, 0 errors. |
| Independent adversarial static checks | Kondo 0 errors/0 warnings; cljstyle exit 0; diff check exit 0. This review also ran `git diff --check`: exit 0. |

All three full fast-lane logs identify the same failing test, `yin.vm.ucf.handoff-v2-census-test/a-version-2-body-is-the-same-bytes-on-every-host`, due to missing `test/resources/yin/vm/ucf/handoff-v2.txt`. This is outside the changed Python files; the logs do not implicate the P1 architecture. They nevertheless are failed lane results, not clean passes.

The Dart fast log contains builtin mutation coverage but does not name the new definition-inside-lambda, definition-only-load, init-idempotence, seeded-builtins or shadow/delete tests. It therefore cannot certify those final P1 additions. Its slow tests explicitly skip. The reviewer also records that its later Node attempt failed before execution because of the local dependency symlink/missing `@noble/hashes/blake3.js`; that failed attempt does not invalidate the earlier successful Node slow log, but cannot supplement it.

## Defect / Gap Findings

1. **Blocking — acceptance verification incomplete.** `docs/design/yang.antlr.md:3090–3104` requires P1 acceptance on all three hosts and unchanged full-corpus output on every VM/host. Specification sections 4.7 and 5 require the lane runs, including Dart slow. No current complete Dart evidence is supplied; the available full fast runs fail on the missing fixture. Before sign-off, provide passing required lanes against the final reviewed tree, including all four VMs' new P1 acceptance tests and Dart slow coverage. Resolve the fixture/setup issue or obtain an explicit authorized baseline exception with evidence; this review grants no implicit waiver. This is a completion-evidence gap, not an observed Python implementation defect.

2. **Reporting gap — engineer completion overstates conformance.** The report says “None” under deviations and that all steps were implemented verbatim, but omits the three failed full fast-lane results and Dart slow status. It also fails to explain the extra `int_conv_test.cljc` consumer rename and additional address-golden changes required by the specification's reporting rule. Those edits are reasonable on inspection: the renamed definitions list expands the string-literal lint's coverage, and bundled prelude changes alter dependent addresses. Record these facts and the actual lane outcomes. The design document's “P1 have landed” statement is premature as a completion claim while sign-off evidence is unresolved.

No revision-worthy architectural implementation defect was found. The adversarial ACCEPT remains credible for its stated code-review scope; it expressly disclaims an independent three-host release gate and cannot substitute for the architectural acceptance criteria.

## Sign-Off Verdict: REJECTED

Rejected for incomplete mandatory verification and inaccurate completion reporting. A1–A4, A6 and the foundational design are satisfactory; A5's full host acceptance remains unproven. Re-review can focus on corrected evidence/reporting unless implementation changes in the meantime. No architectural redesign is requested.
