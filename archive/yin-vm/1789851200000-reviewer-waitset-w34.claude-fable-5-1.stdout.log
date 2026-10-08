Completed-GMT: 2026-09-20 09:35:31 GMT
Completed-Local: 2026-09-20 16:35:31 +07

# Adversarial review: dao.stream.waitset Phases W3+W4

**W3+W4 is not yet ready for architect sign-off.** Two P2 liveness defects in the cljs and cljd wake sources should be fixed first. Both fixes are a few lines. I found no P0 or P1, and everything else conforms to the plan. I edited nothing and did not rerun suites.

## What holds

- **Pure layer.** `cadence.cljc` requires nothing, reads no clock and calls no `check`. A wake resets the curve to `:poll-ms`, and an idle owner climbs to the ceiling and stays there.
- **Wake sources.**
  - Host state is a queue of contentless tokens on clj, and a timer plus a pending flag on cljs and cljd.
  - The host files contain no waitset, store or results.
  - They have no `make-driver` and no budget.
  - The tick receives no arguments.
- **Nudge on the JVM.** `sleep!` drains surplus tokens, so repeated nudges coalesce. `nudge!` never blocks and runs no owner code inline.
- **Deletions.** A grep for `setInterval`, `Timer.periodic` and `Thread/sleep` across `yin/repl.cljc` and `yin/repl/` returns nothing. The `advance-sessions` call that read every session unconditionally is gone: `session-step` reads only when `selected?` is true.
- **Substrate.** No file under `src/cljc/dao/stream*` is modified. The only additions are `cadence.cljc` and the three `driver` files.
- **Serve assignment, row by row.**
  - **One waiter per session.** The resolver refuses a terminal or pending session. `maintain-probes` retires probes and parks new ones after each step.
  - **Pending responses.** `deliver-response` runs first in `session-step`, and it is not gated on the probe.
  - **Probe advance.** The probe's `:advance` is identity. `session-step` re-reads the medium and stays the only commit authority.
  - **Non-`ok` wakes.** A probe that wakes with a status other than `ok` still selects its session, so the session's own step applies its own gap and close policy.
  - **Departed sessions.** Their probes are retired in `sync-sessions`, not by polling.
- **Unchanged rows.** `serving/step!` and the ws acknowledgement sweep are untouched. `remote.cljc` swaps `Thread/sleep` for `sleep!` inside the `:cljd []`-first splice and gains nothing else.
- **Judgment call 1 (`serve/moved?` counts a round where a probe woke).** It is sound. A wake means a request was read. The round that delivers the response is not counted, so the next sleep is still the base interval.
- **Judgment call 2 (shutdown drain at the flat base interval).** It is sound. The drain is a bounded budget of `stop-ticks`, and a backoff curve there would only lengthen shutdown.

## Findings

**P2 | `src/cljs/dao/stream/waitset/driver.cljs:36-49` and `.cljd` `arm!` | A tick that throws kills the owner for good.**
- The timer callback clears `:timer`, runs the tick, and relies on the tick calling `arm!` as its last act.
- If `step-all` throws, nothing re-arms. The old `setInterval` and `Timer.periodic` ticks fired again at the next interval.
- The shell then hangs silently on Node and Dart. JVM behaviour is unchanged, because the poller thread already died on a throw.
- Fix: in the `run-node!` and `run-dart!` ticks, wrap the body in `try`/`finally`. If no timer is armed and the owner has not finished, the `finally` arms `tick-millis`. Alternatively, arm a fallback at the start of the tick and let the final `arm!` replace it. Add a test with a tick that throws once and is still called again.

**P2 | `driver.cljs:63-72` and `driver.cljd` `nudge!` | `nudge!` disarms the timer before it checks the pending flag.**
- A nudge issued inside a nudge-scheduled tick, after that tick's `arm!`, cancels the timer the tick just armed. It then schedules nothing because `pending?` is true.
- The result is no timer and no microtask, so the owner is dead until an outside nudge arrives.
- The docstring says a swallowed in-tick nudge "costs nothing", which is true only when the nudge comes before the `arm!`.
- The current shell ticks arm last and never nudge themselves, so the hazard is latent. A composition that wraps its deposit with a nudge and deposits from inside its own tick would hit it.
- `a-nudge-from-inside-a-tick-does-not-re-enter-it` uses a tick that never arms, so it cannot see this.
- Fix: move `(disarm! wake)` inside the `when-not @(:pending? wake)` branch. Add a test where the tick calls `arm!` then `nudge!`, and a later timer tick still fires.

**P3 | `cadence.cljc` `init` | Validation checks that `:factor` and `:ceiling-ms` exist, not what they are.**
- A `:factor` below 1 shrinks the interval toward zero. On the JVM `(long 0.x)` is 0, so `sleep!` polls with a zero timeout and spins. A `:factor` of exactly 0 zeroes it in one step. That contradicts "zero busy loops".
- A ceiling below `:poll-ms` silently sleeps less than the base interval.
- The docstring promises that defects are "named here rather than silently rounded".
- Fix: require a numeric `:factor` of at least 1 and a `:ceiling-ms` of at least `:poll-ms`. Extend `parameters-are-validated-at-init` to cover both.

**P3 | `serve.cljc` `probe-resolver` and `maintain-probes` | A session adopted with a nil cursor spins its probe.**
- `adopt` stores `(mint …)`, which is nil when the cursor mint fails. `session-step` skips such a session.
- Its probe resolves with a nil cursor and wakes terminally. The session is selected and skipped, then `maintain-probes` parks a fresh probe.
- This repeats every round. `:step-moved?` is true every round, so the endpoint's cadence is pinned at 25 ms indefinitely.
- The old code skipped such a session quietly.
- Fix: add `(some? (:cursor session))` to `observing?` and to the resolver's guard.

**P3 | `repl.cljc` `default-cadence` | The idle endpoint now answers the first request up to 200 ms late, where it used to be 25 ms.**
- Only the line producers and `request-stop!` call `nudge!`. The serving inbound path does not.
- The plan lists exactly those nudge callers and accepts "a lost nudge costs the armed interval", so this conforms.
- The latency change should be recorded. Alternatively the ceiling could be lowered for a served composition.

**P3 | `serve.cljc` `advance-sessions` docstring | It says sessions step "in wait-set order".**
- The reduce runs over `(keys (:sessions endpoint))`, the same order as before. Correct the sentence.

**P3 | Test gap against the W4 deliverable.**
- The deliverable asks for a test that "no probe shares cursor state with an advancing entry".
- `a-probe-carries-no-cursor-state-and-wakes-on-the-current-position` shows that the probe entry carries only `:reason` and `:attachment`. That is the structural half.
- In serve, probe rule 3 (cursor-state isolation) holds trivially because the wait set contains only probes. One assertion that every entry in `:probes` has an identity `:advance`, or a comment saying so, would close the item.
