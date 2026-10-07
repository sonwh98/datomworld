Created-GMT: 2026-09-19 18:29:00 GMT
Created-Local: 2026-09-20 01:29:00 +07 (Indochina Time)
Session-ID: 7544db48-801f-4328-a095-e2174a746b28 (resumed — your W0 census session)
# Task: W0 census — fix the findings from its architect sign-off (r2)

Your census was audited by the architect (gpt-5.6-sol): spot-checks passed,
but the census is incomplete and two of its no-loop claims are contradicted
by source. The orchestrator verified every citation below against the
source — they are accurate. Fix the census in
`docs/design/dao.stream.waitset.implementation-plan.md` (the W0 section
only; still touch nothing else):

1. **Missing production multiplexed waiters — add rows:**
   - `dao.stream.ws`'s acknowledgement-slot sweep: `endpoint-step`
     (`src/cljc/dao/stream/ws.cljc:565-591`), invoked by `serving/step!` at
     `serving.cljc:339-342` inside the same tick — multiplexed; a W4
     candidate like the serving row.
   - `yin.repl.serve/step`'s per-session multiplexing:
     `advance-sessions`/`session-step` (`src/cljc/yin/repl/serve.cljc:586-619`)
     — request reads and retained-response retries for every adopted
     session; production multi-waiter; a W4 candidate.
2. **Correct the "no loop of their own" claims:**
   - `dao.stream.forward/forward-step` DOES contain a bounded polling loop
     (`forward.cljc:101-140`: cursor/budget/resumes loop). Reclassify it —
     bounded budget step, cadence owned by its callers.
   - `yin.repl.connect/observe` DOES contain a budgeted loop
     (`connect.cljc:537-556`). Same treatment.
3. **Add the omissions:**
   - `yin.vm.ffi`'s single-medium bridge loop (`src/cljc/yin/vm/ffi.cljc:237-244`).
   - `dao.space.index`'s `snapshot-datoms` drain (`src/cljc/dao/space/index.cljc:445-452`).
4. Re-check your other no-loop claims (`observe/step`, `dao.jing.remote.step`,
   `dao.await`) against the source and correct any that are likewise wrong.

Scope: only `docs/design/dao.stream.waitset.implementation-plan.md`. No
staging, no commit, one single simple command per step if any.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: the rows added or corrected, and any citation you could not
verify.
