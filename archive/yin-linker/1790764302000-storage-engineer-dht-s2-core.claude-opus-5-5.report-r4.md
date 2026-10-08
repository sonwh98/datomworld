Completed-GMT: 2026-09-30 12:51:21 GMT
Completed-Local: 2026-09-30 19:51:21 +07
Coding-Agent: claude
Session-ID: eb3d451a-74ba-462d-8788-df45ee5c25f8
# Report r4: DHT epic S2, fix round 3 (r3 sign-off findings, gpt-6-sol)

Both findings are fixed in the worktree `/Users/sto/workspace/datomworld-dht-s2`. Every lane finished and passed; each
result below is one I saw. Nothing is staged or committed.

One existing test had to change because of fix 1: it now advances time in small steps (see "Changed existing test").

## 1. HIGH: a reply arriving after its query's deadline was accepted

- **Fix (`on-reply`, `src/cljc/dao/jing/dht.cljc`).** A matched reply (full or need-cookie) is now dropped when
  `now - sent >= query-ticks`. The query stays pending, so the owner's own budgeted advance expires it and records
  the failed try. A late reply never proves, freshens or stores a cookie, and never feeds an acknowledgement. So the
  verdict no longer depends on which operations a budgeted step reached. The docstring says this.
- **Test (written first): `a-reply-after-its-query-deadline-is-dropped`.**
  - Setup: two pending writes each ping peers 2 and 3 at tick 0. A budget-1 step at tick 600 advances one write and
    skips the other, whose two queries stay pending past their deadline.
  - Full `:ok` replies to those two queries are then injected. The final step uses budget 2, because the traffic
    stage is budget-bounded too: at budget 1 only one of the two replies is read. With budget 2 both replies are read
    and the skipped write is advanced first.
  - Assertions: no `/sent` fact; no routing entry; `:seen` is empty; both writes recorded failed tries.
  - Red on the round-2 code: all 4 assertions failed (dht_test.cljc:805-808): the late replies made both peers fresh
    and the write was acknowledged.

## 2. MEDIUM: each operation filtered the whole global query map

- **Fix.** New state key `:query-index {owner #{qkey}}`, kept only through two helpers:
  - `add-query`, used by `issue`.
  - `remove-query`, used by `drop-owner`, `on-reply` (full reply, refused re-send, owner gone) and `expire-queries`.
    An owner left with no queries leaves the index.
  - `owner-queries`, `expire-queries` and `drop-owner` now visit only the owner's index entry, never the whole
    `:queries` map. The need-cookie re-send rewrites its query in place, keeping the same key and owner.
- **Test (written first): `queries-are-indexed-by-owner`.**
  - A 3-node mesh runs a write and a get to completion over 12 single rounds. On every node after every round, the
    index must equal `:queries` grouped by owner, and it must be `{}` at the end.
  - A second case: an operation dropped while it still has a query pending (a deadline give-up with
    `query-ticks > ack-ticks`) must leave `:queries` and `:query-index` both `{}`.
  - Red on the round-2 code: 40 failures (`:query-index` was nil). The second case was added after mutation K2 below
    survived the first version; it fails on the round-2 code too, where `:query-index` does not exist.

## Mutation proofs (each applied, `dao.jing.dht-test` run, then reverted; grep finds 0 leftovers)

| Mutation | Result |
|---|---|
| K1: the late-reply branch never fires | caught: a-reply-after… :805-808 (4 failures) |
| K2: `drop-owner` removes queries but leaves their index entries | survived the first version of the test (every operation there finished with no query pending); after adding the pending-drop case, caught: queries-are-indexed-by-owner :849 |
| K3: `add-query` does not index | caught: 13 failures across the index, late-reply, advance-bound, expiry-bound, need-cookie and timeout tests |

## Changed existing test

`a-query-that-times-out-is-retried-then-its-peer-is-dead` used to jump the clock in 500-tick drives of 3 rounds. A
reply sent in a drive's last round was read in the next drive, 500 ticks later and past its deadline. Under fix 1 that
reply is correctly dropped, so live node 2 was wrongly ruled dead. The test now advances 100 ticks per single round,
from 60000 to 61500. Its assertions are unchanged: two expired tries leave node 3 in the table; the third removes it;
node 2 stays; at 65000 the write is `too-few-peers` with `/peers 1`.

## Checks (all started in the foreground in the worktree, one lane at a time)

- `clj -M:kondo --lint src/cljc/dao/jing/dht.cljc test/dao/jing/dht_test.cljc`: 0 errors, 0 warnings.
- Focused `clojure -M:test` on dao.jing.dht-test, dao.jing.dht.facade-test, yin.vm.linker-test and
  yin.vm.linker-step-test: 101 tests, 838 assertions, 0 failures, 0 errors.
- Full `clojure -M:test`: 2415 tests, 184963 assertions, 0 failures, 0 errors (finished in the foreground).
- `bb test:cljs`: 2317 tests, 51318 assertions, 0 failures, 0 errors (finished in the foreground; "Testing
  dao.jing.dht-test" appears in the output).
- `bb test:cljd`: `+2279: All tests passed!`, and both new tests appear in the run. It hit the tool's 10-minute
  foreground cap, was moved to the background by the harness, and ran to completion there (exit code 0). I read that
  final output before writing this report.

## Files changed this round

`src/cljc/dao/jing/dht.cljc` (late-reply drop in `on-reply`; `:query-index` with `add-query`, `remove-query`,
`owner-queries`, `drop-owner` and `expire-queries`) and `test/dao/jing/dht_test.cljc` (+2 tests, the timeout test's
clock).
