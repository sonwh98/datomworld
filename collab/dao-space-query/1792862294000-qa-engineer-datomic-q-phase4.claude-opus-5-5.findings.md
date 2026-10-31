Completed-GMT: 2026-10-24 17:22:07 GMT
Completed-Local: 2026-10-25 00:22:07 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

**Phase 4 is only partly done, and I stopped on the core threading and the checked-read seam.** I delivered the generic execution machinery and its tests; I did not wire it into `query.cljc` or the adapter. The acceptance criteria are not met: nothing yet bounds a real query, and the adapter still refuses `:timeout`. I'm sending this up as a stop-and-report rather than widening scope.

## Delivered (two new files)
- `src/cljc/dao/space/query/execution.cljc` is the generic machinery, with no Client names and no atoms, volatiles, dynamic vars or global clock:
  - **State:** an immutable state of steps taken, remaining allowance, absolute deadline, phase and terminal outcome.
  - **Operations:** `start`, `unbounded` (the explicit never-expiring policy legacy `q` would use), and `charge`, which refuses before consuming the unit and reads no clock. `checkpoint` reads the driver only when a deadline exists and the state is live. `read-step` and `run-reads` give the one-in-flight guarantee. `finish` does the final observation.
  - **Unit table:** published as data in `step-units`: candidate, scan-advance, rule-entry, aggregate-input, pull-advance, parse-step and output-row, one unit each.
  - **Drivers and exhaustion:** the driver is a small protocol, `IDriver` with `fn-driver`. Exhaustion is terminal data, `{:kind :timeout|:step-budget-exhausted :phase :steps}`, and a terminal state never touches the driver again.
  - **Expiry rule I chose:** expired means observation >= deadline. The contract doesn't fix the exact-boundary rule, so the architect should confirm it.
- `test/dao/space/query/execution_test.cljc` has 13 tests and 60 assertions:
  - A fake driver that expires after N observations.
  - Before, at and after the work-allowance boundary, and the deadline boundary.
  - Work exhaustion independent of time.
  - No clock reads when unbounded or after a terminal state.
  - State threaded rather than forked.
  - Overshoot demonstrated over an explicit read sequence: with expiry during read 2, exactly reads 0, 1 and 2 run, no later read starts, and no value is delivered from the overshooting read.
  - Inputs unchanged.
  - One JVM-only real-clock smoke with 5s margins.

## Why I stopped
1. **The evaluator cannot thread immutable state as it stands.** Every `eval-*` function returns a lazy seq of bindings, with no place to return the next state. `eval-where` is a `reduce` over `mapcat`; `eval-rule`, `eval-not` and `eval-or` build lazy `content-distinct` and `mapcat` chains; `relation-result` and aggregates are lazy too.
   - The `::active-rules` precedent is not enough, because it threads a value downward and never returns progress.
   - The contract bans atoms and volatiles. Satisfying it means making the whole `eval-clause` family state-passing and strict (return `[bindings state]`) and re-forcing pull, grouping and projection that way. That is a rewrite of roughly 600 lines under 72 passing tests, not the "bounded seams" the brief allows. Without it, checks only fall around `q`, `collect` or a clause, which the contract forbids as the only checks.
2. **The checked-read seam hits the STOP rule.** `select-by-index` calls `bt/slice`, an opaque lazy sequence. Its node fetches go through the storage baked into the borrowed tree when it was restored, so there is no per-fetch step to checkpoint.
   - Getting one needs either a stepped traversal in `dao.data.btree` or an invocation-scoped restore with a wrapping `IStorage`. That is a traversal refactor outside my authority and needs architecture review.
   - Until then the timed path over lazy published indexes is unqualified, which the contract permits ("explicitly leave the timed path unqualified"). I kept the unchecked state out of the adapter.

## Decision for the architect
Pick the route for threading: strict state-passing in the core evaluator, or a separate bounded evaluator.
- **Strict state-passing (route A):** rewrite the evaluator and keep one evaluator. Per-read granularity still needs the `dao.data.btree` stepping design.
- **Separate bounded evaluator (route B):** keep the lazy legacy evaluator and add a strict bounded one for adapter calls with a cap, at the cost of two evaluators to keep in agreement.

## Not done
- **`query.cljc` seams:** none made.
- **Adapter wiring:** `:timeout` and `:dao.space.query.datomic/max-steps` are not wired, and the adapter still refuses `:timeout`.
- **Adapter tests:** no timeout or step-budget tests.
- **Examples:** `src/dev/dao/space/query/datomic_examples.cljc` is not written, since it depends on the timeout and max-steps options.
- **Corpus:** unchanged, with no re-digest.
- **Required cases:** the doc's required cases (joins, negation, disjunction, nested rules, recursion, pull, aggregates, parse/output boundaries) are not covered because they need core integration.

## Verification
| Command | Outcome |
|---|---|
| `clj -M:test -n dao.space.query.execution-test` | 13 tests, 60 assertions, 0 failures |
| `clj -M:test -n dao.space.query.datomic-test` | 29 tests, 226 assertions, 0 failures |
| `clj -M:test -n dao.space.query-test` | 72 tests, 282 assertions, 0 failures (untouched) |
| `clj -M:test -n dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions, 0 failures |
| `clj -M:test -n dao.space.index-test` | 56 tests, 531 assertions, 0 failures |
| `clj -M:test -n dao.space.transactor-test` | 16 tests, 100 assertions, 0 failures |
| `clj -M:kondo --lint` on both new files | 0 errors, 0 warnings |
| `mise exec -- bb check:node-discovery` | `Node discovery OK: 214 source files outside test/` |
| `git status --porcelain` (scoped) | my 2 new files; `docs/orchestrator-log.md` is also modified, which is not mine; I did not touch it |

The new namespace and its test have not run on CLJS or CLJD. I used `volatile!` in the test scaffolding, which should be portable, but I haven't confirmed it. I touched no ble, Flutter, `deps.edn`, docs, `query.cljc`, adapter or corpus file.
