# DHT S3 fix round 1 — engineering report

Worktree: `/Users/sto/workspace/datomworld-dht-s3`. No staging or commit.

## Changes

- Added `chunk-partials-are-evicted-by-tick-age` in `test/dao/jing/dht_test.cljc`. It sends one valid request chunk, confirms the partial remains at tick 9, advances to the `query-ticks` deadline at tick 10, then asserts both `:partial` and `:partial-order` are empty. Time moves only by appended ticks.
- Changed `on-tick` in `src/cljc/dao/jing/dht.cljc` to update the partial maps only when `expired` is nonempty. The expiration rule itself is unchanged.

## Red, mutation, green

The new test passed against the existing age-eviction implementation (5 assertions). I then temporarily added `false` to the age-expiration predicate. The same test **failed** on both empty-table assertions (2 failures), proving it detects disabled eviction. The original source was restored; after the no-expiry optimization the test passed again (5 assertions, 0 failures).

## Checks

- `clj -M:kondo --lint src/cljc/dao/jing/dht.cljc test/dao/jing/dht_test.cljc`: 0 errors, 0 warnings.
- `clj -M:test`: 2465 tests, 185577 assertions, 0 failures, 0 errors.
- `bb test:cljs`: 2376 tests, 51936 assertions, 0 failures, 0 errors.
- `bb test:cljd`: 2335 tests, all passed; `test/cljd-out` removed first.
- `git diff --check`: clean.

The orchestrator's S3 rulings defer live mixed-host sockets to S5 and accept the earlier S3 test-first and mesh-test limitations. This round changes only the required tick-age test and the no-expiry update path.
