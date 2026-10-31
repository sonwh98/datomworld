Created-GMT: 2026-09-20 07:12:00 GMT
Created-Local: 2026-09-20 14:12:00 +07 (Indochina Time)
Session-ID: 4b1292c3-fe79-43fa-b169-4e6b860cd9b6 (resumed — your W3+W4 session)
# Task: W3+W4 reconciliation — the Dart driver tests fail on first execution

The orchestrator ran the CLJD lane on your tree (after fixing two of your
test libspecs to the house string form — `["dart:core" :as core]`, not
`[dart:core :as core]`, which the cljd compiler cannot locate). The lane
now compiles and runs, and your two Dart driver tests FAIL:

1. `an-armed-timer-fires-the-tick-and-only-the-tick` — Expected
   `(= 3 (deref ticks))`, actual 0: the armed timer never invoked the
   composition root's tick; a rerun shows `TimeoutException after 30s`.
2. `a-parked-wait-set-keeps-being-polled-with-no-nudges` — Expected
   `(= [:v] (deref received))`, actual `[]`: the parked waitset was never
   polled/woken.

Full failure text (stack traces, async gaps, line numbers) is in the
orchestrator's lane log at
`/Users/sto/workspace/datomworld/collab/1789849500000-…` — no; read the
fresh run yourself: run `clojure -M:cljd test` (or the peers+lane) in this
worktree and read `/tmp`-free output via your own capture. The voxel
fixtures are now MERGED into this branch, so the lane baseline is green
except your two tests: the failing list is exactly your two driver tests
plus nothing else.

Diagnose: the defect is in `src/cljd/dao/stream/waitset/driver.cljd`'s
arming/microtask code, in the test's async handling, or both — Dart runs
one isolate and every wait is a turn on the event loop; `await` over a
poll must actually yield, and `Timer.` construction must fire the
composition's own tick. Compare with how `yin/repl/flutter.cljd` and the
tree's other cljd files await and arm timers.

Scope: `src/cljd/dao/stream/waitset/driver.cljd`,
`test/dao/stream/waitset/driver_test.cljd`, and (only if the fix
requires) `src/cljd/yin/repl` adoption lines. Verification: `clojure
-M:cljd test` must be ALL GREEN (voxel included now), and the JVM + CLJS
lanes must stay green (1557/168773 and 1474/38636 to hold). One single
simple command per step; no chaining. No staging, no commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: the root cause, the fix, exact lane outcomes (all four
checks), and anything unresolved.
