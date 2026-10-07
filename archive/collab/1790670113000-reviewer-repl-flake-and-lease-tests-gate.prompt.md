Created-GMT: 2026-09-29 08:21:53 GMT
Created-Local: 2026-09-29 15:21:53 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ec41-a5c7-7a40-bbcf-6f99a869d582 (captured)
# Task: Gate — yin.repl driver race fix (main-test flake) + lease source-authority regression test

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-29 15:21:53 +07 (+0700) | Status: active | Rationale: standing gate route; both changes Claude-authored (claude-opus-5-5)

Read-only review in /Users/sto/workspace/datomworld (master 3cf3c6de, uncommitted). Do not edit. Treat reports as
untrusted; cite evidence. Change under review: git diff -- src/cljc/yin/repl/driver.cljc test/yin/repl/driver_test.cljc
test/dao/lease_composition_test.cljc. (docs/orchestrator-log.md is orchestrator bookkeeping, out of scope; a separate
worktree datomworld-q-require is unrelated.)

A. Driver race (fixes the yin.repl.main-test cross-process flake).
Brief: collab/1790665619000-qa-engineer-repl-main-test-flake.prompt.md (incl. Round 3 addendum). Reports (untrusted):
collab/1790665619000-qa-engineer-repl-main-test-flake.claude-opus-5-5.report.md and report-r3.md. Claimed root cause:
after attach the driver could send an eval request while its rpc response cursor was still the unresolved :newest
anchor; the cursor later resolved one past the answer, so the answer was never read. Instrumented evidence in the report
(tick 10 send while cursor :newest; tick 19 cursor resolves to 5; answer at 4). Fix: response-cursor-unminted? guard in
handle-line / release-queue / pending-write?; rpc/rebind keeps a resolved cursor. Before 4/20 failed, after 0/36.
Regression test in driver_test.cljc (guard off -> 2 failures). The implementer notes the same hazard exists in
dao.stream.rpc/request! (suggests a :dao.stream.rpc/cursor-pending outcome) — out of this change's scope, queued for
the Architect.
B. Lease authority regression (Architect post-epic ruling item 2, collab/1790665619000-architect-post-epic-queue.gpt-6-sol.findings.md):
one new test a-judge-wires-and-reads-its-own-grantor-authored-medium-test (make-judge and wire-declared-facts both
wire and read the judge's own grantor-authored ledger); the other two invariants were already covered (cited in
collab/1790665745000-qa-engineer-lease-authority-regressions.claude-opus-5-5.report.md). Tests only; src unchanged.

Orchestrator-verified on this exact tree (do not rerun): kondo 0 errors (1 pre-existing warning,
lease_composition_test.cljc:627); cljstyle clean; build/yin-repl-peer rebuilt from the fixed driver before CLJD; full
JVM 2344 / 184072 / 0 (main-test passed); Node 2250 / 50596 / 0; CLJD +2212: All tests passed!.

Rule: (1) Is the root cause demonstrated and is the fix at the cause (not a masking delay)? Any request path that can
still send before the cursor is minted (reattach/rebind, queued lines, retry-unsent, other callers of the same rpc
client in yin.repl)? Could the guard deadlock a session whose cursor never resolves (e.g. dead channel) — is that
surfaced? (2) Does the regression test pin the invariant? (3) Is the lease test correct and meaningful?
(4) Should the dao.stream.rpc cursor-pending change be required now, or is it a separate Architect item?
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer 1-4.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
