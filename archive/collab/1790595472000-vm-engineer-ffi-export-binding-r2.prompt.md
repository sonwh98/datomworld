Created-GMT: 2026-09-28 11:52:23 GMT
Created-Local: 2026-09-28 18:52:23 +07 (+0700)
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2 (resumed)
# Task: Slice 3a fix round 1 — gate findings P1 (bounded step) and P2 (cursor portability)
Role: Yin.VM Runtime Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-28 18:52:23 +07 (+0700) | Status: active | Rationale: same session as the implementation

The gpt-6-sol gate returned REQUEST CHANGES: collab/1790596235000-reviewer-ffi-export-binding-gate.gpt-6-sol.findings.md.
The orchestrator verified both findings below against the code and spec. Fix them now. Same allowed files as your
original brief (remote_serve.cljc and remote_serve_test.cljc ONLY; do not edit dao.stream.remote or ucf.remote — if a
fix truly needs them, stop and report). Do not stage or commit.

The orchestrator also applied cljstyle fix to the test file (formatting only) after your run; keep it.

P1 | remote_serve.cljc step | step calls remote/mirror-step, which loops until blocked/end (remote.cljc ~198-209), so a
continuously supplied channel keeps one step call running: the design requires "one bounded drive pass".
Fix: a per-step request budget (a required/validated option or explicit step argument) after which step returns and
retains the successor cursor; the next step resumes from it. Achieve this within your allowed files (for example a
budget-limited reader view over the channel reader that answers blocked once the budget is spent — only if it
preserves the mirror's cursor semantics exactly; otherwise stop and report). Test: more requests on the channel than
the budget -> exactly budget answered in one step, the rest in later steps, nothing skipped or answered twice.

P2 | remote_serve.cljc servable-surface / serve! | serve! never checks that a reader's cursors survive the channel
codec. Spec dao.stream.remote.md:206-208: "A handle whose cursors hold a host object cannot be served, and a
composition refuses to enter it into a table."
Fix: validate cursor portability with the binding's configured codec before entering the handle (reuse the same
round-trip rule as ucf.remote's portable-cursor — call a public fn if one exists, otherwise implement the equivalent
locally; don't use #'private access, it fails on CLJD). serve! returns nil and enters nothing. Test with a handle
whose cursor holds a host object.

NOT in this round: the close! / channel-ownership finding — it awaits an owner decision; leave close! as is.

Re-run and report exactly: kondo on both files, cljstyle check on both files, focused JVM
(clj -M:test -n yin.vm.ffi.remote-serve-test -n yin.vm.ucf.remote-test -n dao.stream.remote-test).
Append your report to collab/1790595472000-vm-engineer-ffi-export-binding.claude-opus-5-5.report-r2.md and give it
as your final response, beginning with the Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines.
