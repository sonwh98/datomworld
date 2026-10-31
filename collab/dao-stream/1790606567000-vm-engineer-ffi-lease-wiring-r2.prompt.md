Created-GMT: 2026-09-28 15:27:41 GMT
Created-Local: 2026-09-28 22:27:41 +07 (+0700)
Coding-Agent: claude
Session-ID: 7d111381-2e90-40d2-9519-0a15d8ccca19 (resumed)
# Task: Slice 3b fix round 1 — gate P1 (grantor collision) and P2 (dao.lease public wire/unwire, OWNER-AUTHORIZED)
Role: Yin.VM Runtime Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-28 22:27:41 +07 (+0700) | Status: active | Rationale: same session

Gate (collab/1790608971000-reviewer-ffi-lease-wiring-gate.gpt-6-sol.findings.md) returned REQUEST CHANGES. The
orchestrator verified and accepts both findings. Do not stage or commit. Master moved to fd3f0edd (an unrelated
yin.repl indexing commit); your uncommitted remote_serve changes are intact on top of it.

P1 | remote_serve.cljc ~238 | The resolver treats each fact medium's :source as its author, while open! accepts a
::lease-media source equal to grantor, so facts on that medium can acquire grantor authority. Fix: refuse such a
source in open! (refusal names it, before anything is published) and test the refusal.

P2 | remote_serve.cljc ~539, ~652 | Renewal media are wired with lease/wire-facts WITHOUT a :medium declaration, and
finished media are removed by editing the judge's :facts vector directly; make-judge validates declarations and keeps
them on wired entries, so both paths bypass the dao.lease composition contract.
OWNER AUTHORIZATION (verbatim selected option): "Authorize (Recommended) — Implementer adds public declared-wire/unwire
to src/cljc/dao/lease.cljc (+ tests in dao.lease tests), validated like make-judge's declarations, and remote-serve
uses them. Fixed in the same round as P1; gated together."
Fix: add two public functions to src/cljc/dao/lease.cljc — one wiring a fact medium WITH its declaration, validated
exactly as make-judge validates declarations at assembly (reuse that validation; don't fork it), and one unwiring a
medium by its identity — then use them in remote_serve instead of wire-facts-without-declaration and the direct
:facts edit. No remote_serve code may touch the judge's :facts vector afterwards (grep to confirm). Test both new
dao.lease functions in the dao.lease tests (valid declaration wires and is retained; invalid declaration refused;
unwire removes exactly that medium and is idempotent; an unwired medium's facts no longer reach judge-step), in the
existing dao.lease test namespace(s) that fit.

Allowed files this round: src/cljc/yin/vm/ffi/remote_serve.cljc, test/yin/vm/ffi/remote_serve_test.cljc,
src/cljc/dao/lease.cljc, and the dao.lease test file(s) you extend. Nothing else. Existing dao.lease behaviour and
tests must not change: additive only.

Also note in your report (no code change): the gate accepted Q3 (open renewal) as the documented S6 seam and Q6
(tolerance must cover the mirror's read budget) as the composition owner's sizing duty — make sure the open!/step
docstrings state the Q6 sizing duty.

Verify and report exactly: kondo on changed files; cljstyle check (say if blocked); focused JVM
(clj -M:test -n yin.vm.ffi.remote-serve-test -n dao.lease-test -n dao.lease-composition-test -n dao.stream.remote-test
-n yin.vm.ucf.remote-test); bb test:cljs. Not bb test:cljd. Write the report to
collab/1790606567000-vm-engineer-ffi-lease-wiring.claude-opus-5-5.report-r2.md and give it as your final response,
beginning with the Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines.
