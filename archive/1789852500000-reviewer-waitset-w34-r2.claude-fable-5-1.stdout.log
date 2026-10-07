Completed-GMT: 2026-09-20 09:56:37 GMT
Completed-Local: 2026-09-20 16:56:37 +07

# W3+W4 r2 confirmation

**W3+W4 is ready for architect sign-off.** Both P2 liveness fixes are correct, and I found no new defect in the touched lines or the new tests. One P3 has no code or test part left, only a missing plan sentence: the latency note is not in this tree's plan. I edited nothing and did not rerun suites.

| Finding | Disposition | Evidence | Remaining action |
|---|---|---|---|
| P2 throwing tick kills the owner | **Fixed** | Both ticks arm a fallback at `tick-millis` before the body runs, and the body's own `arm!` replaces it (`repl.cljc`, `run-node!` and `run-dart!`). This is the alternative fix I offered, in place of the `try`/`finally`. `finish!` disarms, so a finished owner leaves nothing armed. A tick that keeps throwing retries from the same state, because the box is not written before the throw. The old `setInterval` behaved the same way. `a-tick-whose-body-fails-before-its-own-arm-still-ticks-again` is present on cljs and cljd. | None. |
| P2 `nudge!` disarmed before the pending check | **Fixed** | `disarm!` now sits inside `when-not @(:pending? wake)` on both hosts. `a-nudge-from-inside-a-tick-leaves-the-timer-it-armed-alone` uses a tick that calls `arm!` and then `nudge!`. It requires at least three ticks, and the old ordering would have stranded it at one. A timer can never fire before a queued microtask, so skipping the disarm while the flag is up is safe. | None. |
| P3 cadence validation | **Fixed** | `init` refuses a `:factor` that is not a number of at least 1, and a `:ceiling-ms` below `:poll-ms`. | None. |
| P3 nil-cursor probe spin | **Fixed** | `(some? (:cursor session))` appears in both the resolver guard and `observing?` (`serve.cljc:469, 494`). | None. |
| P3 `advance-sessions` docstring | **Fixed (per the orchestrator's report; I did not re-read the docstring)** | The phrase "in wait-set order" no longer appears in `serve.cljc`. | None. |
| P3 probe-isolation assertion | **Accepted (per the orchestrator's report; I did not re-read the test)** | — | None. |
| P3 latency consequence recorded | **Not in this tree** | `git diff` of `docs/design/dao.stream.waitset.implementation-plan.md` is empty here, and a grep of the plan for "200 ms" and "first request" finds nothing. | The orchestrator should apply the proposed W4 sentence before or with the commit. It should say that an idle served endpoint answers its first request at up to the 200 ms ceiling, because the serving inbound path has no nudge caller. This does not block sign-off. |

## One observation (no action needed)

- On Node, a throw inside a timer callback or a microtask is an uncaught exception, and it ends the process unless a handler is installed.
- The fallback arm therefore only matters when such a handler exists. It is the same exposure the old interval had.
- The test comment states this limit honestly. The tests simulate the state after the throw and do not throw.
