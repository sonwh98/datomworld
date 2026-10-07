Created-GMT: 2026-09-28 12:18:38 GMT
Created-Local: 2026-09-28 19:18:38 +07 (+0700)
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2 (resumed)
# Task: Slice 3a fix round 3 — gate P3 test pin (writer-only export must skip the cursor check)
Role: Yin.VM Runtime Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-28 19:18:38 +07 (+0700) | Status: active | Rationale: same session

Gate round 2 (collab/1790596235000-reviewer-ffi-export-binding-gate-r2.gpt-6-sol.findings.md) accepted all three fixes
and left one finding, which the orchestrator accepts:

P3 | test/yin/vm/ffi/remote_serve_test.cljc:371 | The writer-only test uses a ring whose cursors are portable; it would
still pass if serve! wrongly applied the cursor codec check to writer-only exports. Fix: serve a handle with a writer
surface and UNPORTABLE reader cursors, served under a writer-only surface policy, and assert admission (the existing
host-cursor fixture could be extended with a writer surface).

Test file only (remote_serve_test.cljc). If the new test fails, the source has a real bug: stop and report it instead
of changing the source. Confirm the pin bites: temporarily make serve! check cursors for writer-only exports, see the
test fail, revert, grep to confirm the revert. Do not stage or commit.
Run kondo on the test file and the focused JVM command from before. Write the report to
collab/1790595472000-vm-engineer-ffi-export-binding.claude-opus-5-5.report-r4.md and give it as your final response,
beginning with the Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines.
