Created-GMT: 2026-09-29 04:43:20 GMT
Created-Local: 2026-09-29 11:43:20 +07 (+0700)
Coding-Agent: claude
Session-ID: 08d36f7d-b33d-404e-adcb-dc04d5b907ef (resumed)
# Task: Slice 3d fix round 1 — gate P2: caller readiness leaks reflections
Role: Yin.VM Runtime Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-29 11:43:20 +07 (+0700) | Status: active | Rationale: same session

Gate (collab/1790656866000-reviewer-ffi-caller-correlation-gate.gpt-6-sol.findings.md) accepted Q1-Q4 and Q6 and
acceptance a-g, and withheld sign-off on one finding the orchestrator verified and accepts:

P2 | src/cljc/yin/vm/ffi/remote_serve/caller.cljc ~83, ~113 | open retains the call-in reflection when the call-out
attach fails; after both attach, step can return ::exhausted or ::refused while retaining both reflections, with no
cleanup path. Remote reflection close! is local and releases pending/outstanding work.
Fix: close every acquired reflection on each failure outcome (attach failure, ::exhausted, ::refused), and provide an
explicit public teardown for a caller whose readiness attempt ends without a VM (idempotent). Successful readiness
hands the reflections to the VM composition unchanged. Tests: each failure path leaves no open reflection (assert via
the reflections' own closed/closable state or outstanding-work release), teardown is idempotent, the success path is
unchanged. Prove by a temporary mutation, revert, grep.

Files: caller.cljc and its tests (responder_test.cljc or a new caller test ns) only. Do not stage or commit. Run
kondo on changed files and focused JVM (yin.vm.ffi.remote-serve.responder-test, any caller test ns,
yin.vm.ffi.remote-serve-test, yin.vm.ffi-test). Write the report to
collab/1790655248000-vm-engineer-ffi-caller-correlation.claude-opus-5-5.report-r2.md and give it as your final response,
beginning with the Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines.
