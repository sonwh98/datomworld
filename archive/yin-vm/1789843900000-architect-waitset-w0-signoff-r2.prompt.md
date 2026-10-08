Created-GMT: 2026-09-19 18:54:00 GMT
Created-Local: 2026-09-20 01:54:00 +07 (Indochina Time)
Session-ID: 01a0bad9-1f47-7003-9f20-4d8743772a39 (resumed — your W0 sign-off thread)
# Task: re-submit — the W0 census after your findings

All findings were applied to the W0 section of
`docs/design/dao.stream.waitset.implementation-plan.md` (now +50/−1):

1. The missing production multiplexed waiters are rows: `ws/endpoint-step`'s
   ack-slot sweep (`ws.cljc:565-591`, invoked from `serving/step!`
   `:339-342`) and `yin.repl.serve`'s per-session step
   (`advance-sessions`/`session-step`, `serve.cljc:586-619`).
2. The source-contradicted no-loop claims are corrected: `forward-step`
   (loop at `forward.cljc:101-140`) reclassified as a bounded budget step
   inside the serving row; `yin.repl.connect/observe` (loop at
   `connect.cljc:537-564`) given its own row.
3. The omissions are rows: `yin.vm.ffi`'s bridge loop (`ffi.cljc:237-244`)
   and `dao.space.index/snapshot-datoms` (`index.cljc:445-452`, folded into
   the one-shot drains row).
4. The census's own re-check found one more wrong no-loop claim the audit
   had not named: `dao.jing.remote.step`'s `issue-pending-verifies` loops
   (`step.cljc:396-457`) — given its own row as a bounded budget step.

The orchestrator spot-checked the new citations against source; they hold.
Re-read the W0 section. If the census is now a safe basis for the W4
adoption list, grant the sign-off. (The W3 driver design's blocking
findings remain a separate, pending reconciliation — still not this task.)

Do not edit anything. End with exactly one line:
`SIGN-OFF: <granted | denied> — <one sentence>`
