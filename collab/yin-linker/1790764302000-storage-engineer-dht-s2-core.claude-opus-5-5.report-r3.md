Completed-GMT: 2026-09-30 12:17:55 GMT
Completed-Local: 2026-09-30 19:17:55 +07
Coding-Agent: claude
Session-ID: eb3d451a-74ba-462d-8788-df45ee5c25f8
# Report r3: DHT epic S2, fix round 2 (r2 sign-off findings, gpt-6-sol)

Both findings are fixed in the worktree `/Users/sto/workspace/datomworld-dht-s2`. Every lane finished and passed; each
result below is one I saw. Nothing is staged or committed.

## 1. HIGH: query expiry was not bounded by the budget

- **Fix (`src/cljc/dao/jing/dht.cljc`).** `expire-queries` now takes an `owner` and expires only that operation's
  queries. `advance` no longer expires every query up front. For each operation it takes under the budget
  (round robin after `:advance-cursor`), it first expires that operation's queries, then advances it. So one step
  expires and advances at most `budget` pending operations.
- **Test (written first): `query-expiry-is-bounded-by-the-budget`** (dht_test).
  - Five pending writes each hold one query. Tick 500 expires all of them.
  - Three budget-2 steps must leave `[2 4 5]` writes with a failed try recorded.
  - Red on the round-1 code: `actual (5 5 5)` (dht_test.cljc:774).
- **Mutation proof.** I restored "expire every live query" by changing the filter to "any query whose owner exists".
  It is caught by `query-expiry-is-bounded-by-the-budget` :774 and by `the-advance-stage-is-bounded-by-the-budget`
  :739. Reverted; grep finds 0 leftovers.
- **Two consequences** (neither changes a contract rule; a tick gap already makes expiry late, not wrong):
  - A query whose operation is not taken in a step expires later: when the round robin reaches it, never early.
  - A query sent before the first tick starts its clock when its operation is next taken, not in the first ticked
    step.
  - Queries no longer need an ownerless sweep: every path that removes an operation goes through `drop-owner`, which
    removes its queries too.

## 2. LOW: §8 text on the need-cookie reply

`docs/design/dao.jing.dht.md` §8, "Recovery", now says: on a need-cookie reply the requester re-sends the query once
carrying the new cookie, and that re-send consumes no try. The need-cookie reply's cookie is untrusted: it rides that
one re-send, and the requester stores a peer's cookie only when a full reply carries it. A re-send the socket refuses
is a failed send. The timeout sentence is unchanged.

## Checks (all started in the foreground in the worktree, run one lane at a time)

- `clj -M:kondo --lint src/cljc/dao/jing/dht.cljc test/dao/jing/dht_test.cljc`: 0 errors, 0 warnings.
- Focused `clojure -M:test` on dao.jing.dht-test, dao.jing.dht.facade-test, yin.vm.linker-test and
  yin.vm.linker-step-test: 99 tests, 787 assertions, 0 failures, 0 errors.
- Full `clojure -M:test`: 2413 tests, 184910 assertions, 0 failures, 0 errors (finished in the foreground).
- `bb test:cljs`: 2315 tests, 51267 assertions, 0 failures, 0 errors (finished in the foreground; "Testing
  dao.jing.dht-test" appears in the output).
- `bb test:cljd`: `+2277: All tests passed!`, and the new `query-expiry-is-bounded-by-the-budget` is in the run. It
  hit the tool's 10-minute foreground cap, was moved to the background by the harness, and ran to completion there
  (exit code 0). I read that final output before writing this report.

## Files changed this round

`src/cljc/dao/jing/dht.cljc` (`expire-queries` per operation, and `advance`), `test/dao/jing/dht_test.cljc` (+1 test),
`docs/design/dao.jing.dht.md` (§8 Recovery).
