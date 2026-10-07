Completed-GMT: 2026-09-28 14:41:58 GMT
Completed-Local: 2026-09-28 21:41:58 +07
Coding-Agent: claude
Session-ID: 7fcb4501-80b2-43a3-8511-32424dd6f0d4

# Report r3: bounded intake retention (gate r2 P2, owner decision "Fresh intake per round")

Worktree: /Users/sto/workspace/datomworld-repl-index. Nothing staged or committed. The changed files this round are `src/cljc/yin/repl/index.cljc` and `test/yin/repl/index_test.cljc`. `src/cljc/yin/repl.cljc` is unchanged since r2 (still modified relative to HEAD from r1/r2). There are no dao.space.* edits.

## How publish! is pointed at a fresh intake without a dao.space change
`transactor/create!` takes the intake pool in its spec and derives the next `t` from the local stream's retained history. So the indexer no longer holds a long-lived transactor. It holds its complete-retention local transaction log (`:local`, memory-log). A round that has code to commit opens a `:publication`:
- `transactor/create! {:local-stream (:local ix) :intake-pool [fresh-memory-log]}`;
- a `dao.jing` pool observer over that intake.

The round commits through that transactor (`transact!`), publishes through it (`transactor/publish!`, not `publish-index!`), drains the intake into the store, and reads the manifest back. At the end of `step` it `dissoc`s `:publication`, which drops the transactor, the intake and the pool. There is only ever one writer on `:local`. Transaction time continues across rounds because each create! rescans the log; the test asserts t = 0, 1, 2, 3 over four rounds.

Between rounds the indexer holds exactly `:observer :local :content-store :session-token :publish-opts :next-e :transactions :published :published-payloads :manifest-address :lost? :failure`. `:published-payloads` is a count, not payloads; it replaces the tests' former read of the intake. `:local` holds only transaction records. The P1 guarantee still holds because each round's intake is complete-retention, and the r2 status/warning behaviour is unchanged.

The namespace docstring now documents the remaining cost as accepted under the owner's every-round cadence. Each publication rebuilds the covered indexes over the whole history, and opening the round's transactor rescans that history. Per-round time therefore grows linearly and session time quadratically, while memory is bounded to one publication during its round.

## Tests
- **New:** `no-earlier-publication-is-retained-between-rounds` runs four shell rounds. After each round it asserts:
  - the indexer's key set is exactly the set above (no transactor, intake or pool);
  - `:local` holds only `:dao.space/transaction` records, one per round;
  - the status is published and `:published-payloads` is positive.
  
  It also asserts that transaction t continues 0..3 across the per-round transactors.
- **Updated:** `a-publication-beyond-the-old-intake-bound-completes` now asserts `(< 4096 (:published-payloads ix))`, plus manifest count and read-back as before. The failure, gap and projection tests read `:local` instead of `[:transactor :local-stream]` and are otherwise unchanged.

## Verification (exact)
- `clj -M:kondo --lint src/cljc/yin/repl.cljc src/cljc/yin/repl/index.cljc test/yin/repl/index_test.cljc` → `errors: 0, warnings: 0`. The first lint caught an edit leftover (unmatched brackets); it was fixed and re-linted clean.
- `cljstyle check /Users/sto/workspace/datomworld-repl-index/src/cljc/yin/repl/index.cljc` was **blocked by the permission gate** ("requires approval"). cljstyle has not run on any file.
- `clj -M:test -n yin.repl.index-test -n dao.space.transactor-test -n yang.clojure.stream-eval-test -n yin.repl-test` → **Ran 74 tests containing 695 assertions. 0 failures, 0 errors.**
- `clj -M:test -n yin.repl.index-test` → Ran 10 tests containing 98 assertions. 0 failures, 0 errors.
- `bb test:cljs` → **Ran 2204 tests containing 50062 assertions. 0 failures, 0 errors.** "Testing yin.repl.index-test" is present.
- `bb test:cljd`: not run, as instructed. The other yin.repl.* namespaces (main, serve, require, …) were not re-run this round; `repl.cljc` did not change.

## Unresolved concerns
- **Publish time** still grows with history (documented above). An incremental publish in dao.space.index would remove it.
- **CLJD compile** is unverified; that lane is the orchestrator's.
- **cljstyle** is unverified (permission gate).
