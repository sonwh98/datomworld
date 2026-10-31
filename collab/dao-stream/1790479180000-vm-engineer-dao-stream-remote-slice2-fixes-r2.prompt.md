Created-GMT: 2026-09-27 10:50:00 GMT
Created-Local: 2026-09-27 17:50:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 2 fixes r2)

# Task: Slice 2 — Fix the confirmation gate's residual P1 (probe retry)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 2 +
fix round 1 are uncommitted). Read:
collab/1790479176072-architect-dao-stream-remote-slice2-confirm.gpt-6-sol.findings.md

P1 (remote.cljc:751): a full-refused attach descriptor probe is left
unregistered (correct per fix round 1), but drain! (:446) retries only
OUTSTANDING probes — so once the writer recovers, no later operation
re-sends the probe: remote confirmation, the learned surface, and the
confirmation event can remain absent indefinitely. The round-1 test
checks cursor recovery but not probe recovery.

Fix: retain the pending unsent probe on the link and retry it on a
later drain (once per drain, consistent with the resend cadence),
until it is accepted; on acceptance it becomes the outstanding probe
and the normal filing path confirms it. Add the gate's test: refuse
the probe with a full-refusing writer, then confirm the answer arrives
after writer recovery (probe answered, learned surface, confirmation
event if composed).

Constraints: touch only remote.cljc and remote_test.cljc. Preserve
round-1 behavior (cursor/next/full refusals leave nothing outstanding;
registered-sends-only). ASCII, <= 80 cols, cljstyle/kondo clean, no
commit/stage/checkout/reset/stash, no diagnostics. Verify all three
lanes sequentially/solo, exact counts (current: JVM 2,254/183,208/0;
Node 2,166/49,814/0; Dart 2,128).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
