Created-GMT: 2026-09-20 09:47:00 GMT
Created-Local: 2026-09-20 16:47:00 +07 (Indochina Time)
Session-ID: 4b1292c3-fe79-43fa-b169-4e6b860cd9b6 (resumed — your W3+W4 session)
# Task: W3+W4 reconciliation — the review's 2 P2 liveness defects + P3s

The review confirmed the pure layer, the adoption rows, and both flagged
judgment calls, but blocked on two liveness defects in the cljs/cljd wake
sources. Read
`collab/1789851200000-reviewer-waitset-w34.claude-fable-5-1.findings.md`
(this tree's copy). All accepted. Scope: the same files as your W3+W4
build.

Dispositions (all ACCEPT):

- **P2 throwing tick kills the owner:** in the `run-node!`/`run-dart!`
  ticks, wrap the body in try/finally — if no timer is armed and the owner
  has not finished, the finally arms `tick-millis`. Add the test: a tick
  that throws once is still called again.
- **P2 nudge disarms before pending check:** move `(disarm! wake)` inside
  the `when-not @(:pending? wake)` branch in both cljs and cljd `nudge!`.
  Add the test: a tick calls `arm!` then `nudge!`, and a later timer tick
  still fires. Correct the docstring's "costs nothing" clause.
- **P3 cadence init:** require numeric `:factor` ≥ 1 and
  `:ceiling-ms` ≥ `:poll-ms`; extend
  `parameters-are-validated-at-init`.
- **P3 nil-cursor probe spin:** add `(some? (:cursor session))` to
  `observing?` and the resolver's guard.
- **P3 latency record:** add the sentence to the plan's W3 section? NO —
  plan edits are the orchestrator's. State the text you want in your
  report ("the served composition's first-request latency can reach the
  ceiling; lower the ceiling or add a serving-path nudge if it
  matters").
- **P3 docstring:** `advance-sessions` "in wait-set order" → correct
  sentence (keys order, same as before).
- **P3 probe isolation assertion:** add the comment/assertion closing the
  cursor-isolation item.

After fixing: the cljd lane must be ALL GREEN, and the JVM + CLJS lanes
must hold. Single simple commands. No staging, no commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-finding disposition, tests added, exact counts, and the
latency text for the plan.
