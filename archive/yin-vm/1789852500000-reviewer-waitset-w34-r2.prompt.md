Created-GMT: 2026-09-20 09:57:00 GMT
Created-Local: 2026-09-20 16:57:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your review session)
# Task: confirm the W3+W4 r2 fixes

Your two P2 liveness defects and five P3s were fixed. Orchestrator
verification: JVM 1557 tests / 168780 assertions / 0 failures 0 errors;
CLJS 1476 / 38647 / 0; CLJD +1439 All tests passed (the two new liveness
tests run on Dart).

- P2 throwing tick: the `run-node!`/`run-dart!` ticks wrap the body in
  try/finally, arming `tick-millis` when no timer is armed and the owner
  has not finished. The liveness tests simulate the post-throw state
  (a real timer-callback throw is process-fatal on Node / zone-swallowed
  under cljd.test) — your alternative-fix framing is the shape shipped.
- P2 nudge disarm order: `(disarm! wake)` moved inside the
  `when-not @(:pending? wake)` branch, both hosts; the in-tick
  nudge-after-arm test added.
- P3s: cadence init validates numeric `:factor` ≥ 1 and
  `:ceiling-ms` ≥ `:poll-ms`; the nil-cursor probe spin is closed
  (`some? (:cursor session)` in `observing?` and the resolver guard);
  the latency consequence is recorded in the plan's W4 section (the
  implementer's proposed text); `advance-sessions`' docstring corrected;
  the probe-isolation assertion added.

Re-read only the touched lines and the new tests. Challenge. Do not
edit. Do not rerun suites.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Return: finding | final disposition | evidence | remaining action.
Explicitly state whether W3+W4 is ready for architect sign-off.
