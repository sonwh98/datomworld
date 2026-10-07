All three gate findings fixed. Only the two slice-1 files were touched; nothing staged/committed, no diagnostics left.

**P1 — gate state per wrapped handle, minted at wrap** (`/Users/sto/workspace/datomworld/src/cljc/dao/stream/middleware.cljc`)
- The gate map is now a definition: `gate` returns `:dao.stream.middleware/state` (a zero-arg constructor, middleware.cljc:473) plus state-taking `in`/`out` (middleware.cljc:481-491); the constructor mints the volatile and calls `cursor :oldest` on the decision medium exactly once (middleware.cljc:474-479).
- `wrap` instantiates each chain entry via `instantiated` (middleware.cljc:146-163), which calls the state constructor once at wrap and specializes the transforms; `wrap` maps it over the chain (middleware.cljc:180). One definition wrapped twice now holds two independent decision cursors.
- Test change (`/Users/sto/workspace/datomworld/test/dao/stream/middleware_test.cljc:517-552`): the old "mint happens once, at gate construction" block (wrong lifecycle) was replaced — the definition alone mints nothing, each of two wraps over the same definition mints its own `:c0`/`:c1`, and each wrapped handle reads the shared medium through its own cursor (`[:next :c0]` vs `[:next :c1]`). The follow-on block's "construction mint" wording became "the wrap mint" (middleware_test.cljc:564). All other gate lifecycle tests pass unchanged, confirming the observable per-operation call sequences were preserved.

**P1 — the position rule is structural: wrap validates every out result** (middleware.cljc)
- `position-keys` (middleware.cljc:70-80): outcome kind, cursor, anchor, identity, `:dao.stream.remote/op`, `:dao.stream.remote/id` may never change (adding one where none was counts as a change).
- `position-preserving?` (middleware.cljc:83-101): cheap and total, structural on the two outcome maps only, no deep element walk — no dropped keys, protected keys equal, and `:dao.stream/value` replaceable only on an `ok` from `next` (the encryption exemplar's and the tombstone test's replacement); open keys may be added.
- `run-outs` (middleware.cljc:104-125) validates after every `out` (short-circuit outcomes included) and raises `ex-info` "out broke the position rule" with the middleware index, op, received and returned maps — a host-assembly defect per dao.stream.md Result Convention.
- Adversarial test added (middleware_test.cljc:403-447): an out turning an ok-from-next into `:dao.stream/blocked` raises (and the stream still holds `[:drop]`); an out changing the outcome kind raises; an out rewriting a cursor raises. Confirmed paths (cipher, tombstone, metering) still pass.

**P2 — recovery outcomes clear state** (middleware.cljc:427-451)
- The inner `case` in `absorb` now handles `:dao.stream/gap` explicitly (adopt the recovery cursor, stay without a value; middleware.cljc:442-446) and its catch-all clears cursor and value (re-mint next operation) for every other recovery outcome (middleware.cljc:447-450), matching dao.stream.middleware.md's gate rules.

**Verification** (sequential, solo, JVM tooling under mise; cljstyle 0.17.642 clean after one fix, clj-kondo 2026.08.04: 0 errors / 0 warnings, pure ASCII, all lines <= 80 columns):
- JVM (`mise exec -- clojure -M:test`): Ran 2,233 tests containing 183,017 assertions. 0 failures, 0 errors. (Baseline 2,232/183,003/0.)
- Node (`mise exec -- bb test:cljs`): Ran 2,145 tests containing 49,666 assertions. 0 failures, 0 errors. (Baseline 2,144/49,655/0.)
- Dart (`mise exec -- bb test:cljd`): All tests passed! — 2,107 passed. (Baseline 2,106; the new tests verified present in `test/cljd-out/dao/stream/middleware-test_test.dart`.)

Status: COMPLETE