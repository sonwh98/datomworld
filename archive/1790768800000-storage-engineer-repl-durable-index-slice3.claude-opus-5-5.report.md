Completed-GMT: 2026-09-30 12:37:15 GMT
Completed-Local: 2026-09-30 19:37:15 +07
Coding-Agent: claude
Session-ID: adc2ad2b-7804-4513-bdbe-b35cd38d01bf

# Durable index store, slice 3: indexer rehydration and `(reset)` continuity

Worktree `/Users/sto/workspace/datomworld-durable-index`, branch `repl-durable-index`, on top of slice 2 (fbc30186). Nothing is staged or committed. Every lane below ran in the foreground to completion and is green; none was cut off.

## Carried over from slice 2 fix round 3

The report for slice 2 fix round 3 (`…slice2.claude-opus-5-5.report-r4.md`) was **never written**. Its code changes landed in fbc30186:
- claims naming this process's own pid count as live;
- a two-worker-thread test;
- the pid-reuse contract.

Its kondo, JVM, Node, and peer-build checks were green. Its `bb test:cljd` run ended before finishing: the session ended with the lane moved to the background on a heavily loaded machine, and no result was ever seen. This slice's Dart lane ran to completion on top of fbc30186 and passed (below). That covers the round-3 code on Dart too; round 3 touched only ClojureScript-only code, cljs-only tests, and docstrings.

## What changed

**`src/cljc/yin/repl/index.cljc`**
- `rehydrate`: builds a fresh complete-retention memory log from the recovered datoms.
  - One `{:dao.space/transaction {:t t :datoms [...]}}` record per original `t`, in ascending `t` order; each datom is kept exactly as recovered, `t` included.
  - Entity allocation (`next-entity-id`) resumes one past the greatest restored `e` or integer `m`, never below `datom/first-user-id`.
  - `:transactions` and `:published` are set to the number of restored transactions, and `:manifest-address` to the recovered manifest.
  - A recovery with no manifest leaves the indexer untouched.
  - The transactor needs no change: it already derives the next `t` from the restored log.
- `carry-over`: a rebuilt session's fresh indexer takes over the previous indexer's `:local`, `:next-e`, counts, and manifest. The observer stays the new session's.

**`src/cljc/yin/repl.cljc`**
- `create-state` rehydrates the new session's indexer from `(:recovery index-store)` before returning, so it happens before any evaluation is admitted.
- `rebuild-session` (`(reset)` and `(vm …)`) applies `carry-over` only when `store/durable?`; the memory store is unchanged.
- Shell tokens are already minted per `create-state`, so a new token per process start needed no change. Restored datoms keep their original `:yin.repl/session`, `:yin.repl/root`, and `:yin.repl/round` facts.

**`src/cljc/yin/repl/store.cljc`**: adds `durable?`.

**Docs:** the interim-hazard text is removed from `docs/design/yin.repl.dao.space-index.md` and from the `yin.repl.store` docstring. Both, plus the `create-state`, `rebuild-session`, and `yin.repl.index` namespace docstrings, now describe the slice-3 behaviour. No `dao.space.*` or `dao.jing.*` file changed.

## Tests first

The new tests are in `test/yin/repl/store_test.cljc` and run on the JVM, Node, and Dart.
- `a-restarted-durable-shell-answers-q-with-the-previous-runs-facts`: the §5 restart sequence as real REPL input lines, plus `(reset)` and `(vm :stack)` continuity.
- `mem-mode-reset-still-starts-an-empty-index`.

**Queries use constants no query line contains.** Every program is indexed, the calling `q` line included, so a name passed as a `q` input would match the query's own literal. The tests therefore use unique numeric constants (`(def alpha 1001)`, `2002`, `3003`) written inside the quoted query; only the defs carry them.

**Before the fix**, `clj -M:test -n yin.repl.store-test` gave **8 failures, 0 errors**, all in the restart test:
- `q` saw no old facts (`#{}`).
- There was no increasing `t`.
- Restored and new entity ids collided (`#{16 17 18 19 20}`).
- HEAD after the restart publication lacked the old facts.
- `(reset)` lost `beta` (`#{}`).
- `gamma`'s `t` restarted at 0.
- Entity allocation collided after the rebuild.

The mem-mode test passed before the fix, as expected: its behaviour is meant to stay unchanged. Both tests pass after the fix.

## Acceptance → evidence

The restart sequence, in the restart test:

| Step | Evidence |
|---|---|
| 1. Evaluate code, then stop | `(def alpha 1001)` on `{:type :file :dir d}`, then `store/close!`, which releases the lock |
| 2. Restart against the same dir and require `dao.space.query` | a new `create-state` on the same spec; the `require` line answers `'dao.space.query` |
| 3. `q` sees the old facts | a history-view provenance query for 1001 answers with first-run's token only. Before any evaluation, `repl-state` shows `:transactions` = the restored count and `:published? true` |
| 4. Evaluate more code | `(def beta 2002)` |
| 5. Old and new facts, increasing `t`, distinct session provenance | 1001 answers `[first-token alpha-t]` and 2002 answers `[second-token beta-t]`, with `alpha-t < beta-t` and `first-token ≠ second-token` |

The other required checks:

| Check | Evidence |
|---|---|
| `(reset)` keeps the facts in durable mode | after `(reset)` plus `require`, both provenance answers equal the pre-reset ones |
| `(vm :stack)` keeps them too | after `(vm :stack)`, `(def gamma 3003)` gets `beta-t < gamma-t` |
| Mem-mode reset unchanged | `mem-mode-reset-still-starts-an-empty-index`: after `(reset)` the query for 1001 answers `#{}`. The existing mem tests (`index-test/vm-selection-rebuilds-the-indexer`, `query-test` reset tests) stay green |
| Restored ids never collide with new ones | the entity and metadata-entity ids (≥ `first-user-id`) of the restored datoms and the new datoms are disjoint, both after the restart and after the rebuilds |
| HEAD after the first post-restart publication covers old and new | HEAD equals `{:version 1 :manifest <the indexer's manifest>}`, and `read-datoms` of that manifest contains every first-run datom and every committed datom |

**Live smoke** (JVM, real processes, `clj -M:clj-yin-repl --index-store file:target/smoke-s3`):
- Process 1 ran `(def alpha 1001)`.
- Process 2 required `dao.space.query`, and `q` for 1001 answered `#{["a8449476-…" 0]}`, process 1's token. It then ran `(def beta 2002)`.
- Process 3 got 1001 → `#{["a8449476-…" 0]}` and 2002 → `#{["3fbadffe-…" 3]}` (process 2's token, higher `t`). After `(reset)` plus `require`, 2002 still answered `#{["3fbadffe-…" 3]}`.

(In process 2 I also tried a query using a `contains?` predicate. The query surface refused it: "Unknown query fn — pass it via :fns". That's a mistake in my smoke query, not a defect.)

## Mutations (one or more per core property)

Each was applied, run with `clj -M:test -n yin.repl.store-test`, and reverted by the script. `git diff --stat` was identical before and after.

| Mutation | Property | Result |
|---|---|---|
| `rehydrate` returns the indexer unchanged | rehydrate before evaluation | 7 failures |
| no restored `:local` log | `t` continues from the restored log; HEAD covers old facts | 4 failures |
| counts not seeded | `q` answers before any new evaluation | 1 failure (see note) |
| entity allocation not resumed | restored and new ids never collide | 3 failures |
| no `carry-over` in durable mode | `(reset)` / `(vm …)` continuity | 4 failures |
| `carry-over` applied in mem mode too | mem reset unchanged | 1 failure (mem test) |
| constant shell token | a new token per process start | 1 failure |

**Note on the counts mutation:** it first survived. The `require` line is itself an evaluation that publishes over the whole restored log, which reseeds the counts before any query runs. I added an assertion on the restarted shell's `repl-state` before any line is evaluated, which is exactly the property "q answers before any new evaluation" needs. Rerun, it fails.

## Verification (all in the foreground)

| Check | Result |
|---|---|
| `clj -M:kondo --lint` on the changed files | 0 errors, 0 warnings |
| Focused JVM: store, main, repl, index, query | 114 tests, 846 assertions, 0 failures, 0 errors |
| Full `clj -M:test` | 2413 tests, 184,702 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2322 tests, 51,138 assertions, 0 failures, 0 errors; "Testing yin.repl.store-test" appears |
| `bb build:yin-repl-peer` | built `build/yin-repl-peer` |
| `bb test:cljd` | "All tests passed!" (+2280, in 7:10). Running the compiled store test alone with `--reporter expanded` lists `a-restarted-durable-shell-answers-q-with-the-previous-runs-facts` and `mem-mode-reset-still-starts-an-empty-index`: all passed |
| cljstyle | not run (blocked by the approval gate in earlier slices). Mechanical checks are clean: no trailing whitespace, no tabs, no run of more than 2 blank lines, each file ends in a single newline |

**Portability:** `:cljd`-first ordering is kept (no new mixed reader conditionals in source). No `array-map`, no cross-namespace `#'private`. The tests read answers by value, so no refusal helper was needed.

## Notes for the orchestrator

- **Rehydration cost:** it is linear in the recovered datoms at startup. The existing every-round cost (republishing over the whole retained log, now including restored history) grows as the `yin.repl.index` docstring already describes. No new cost class.
- **Within-transaction order:** restored datoms come out in EAVT order within each transaction rather than their original emission order. Each datom and its `t` are preserved exactly; nothing reads the order within a transaction.
- The `:index-recovery` state key is kept, as the handoff seam. Reports and backups live only in the ignored `target/` directory.
