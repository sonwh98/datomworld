Completed-GMT: 2026-09-30 13:16:52 GMT
Completed-Local: 2026-09-30 20:16:53 +07
Coding-Agent: claude
Session-ID: adc2ad2b-7804-4513-bdbe-b35cd38d01bf

# Durable index store, slice 3: fix round 1 (gpt-6-sol sign-off findings)

Worktree `/Users/sto/workspace/datomworld-durable-index`, branch `repl-durable-index`. Nothing is staged or committed. All three findings are addressed. Every lane ran one at a time and finished with a seen result; one ran past the 600-second tool limit (see the verification section).

The strengthened tests exposed one real defect, finding 3's message. Findings 1 and 2 were test-strength gaps: the rehydration code was already correct, and each new test now fails under a matching mutation.

## New fixture (`test/yin/repl/store_test.cljc`)

`publish-fixture!` commits three transactions through a real `dao.space.transactor` over a memory log. It publishes the covered indexes into a fresh durable directory, drains the intake into `content.jing`, moves HEAD with the store's own `:head-fn`, and closes the store. The transactor assigns them `t` 0, 1 and 2:
- **t 0:** `[16 :fixture/name "a" · 1]` and `[17 :fixture/name "b" · 1]`.
- **t 1:** `[18 :fixture/name "c" · 40]`. Here 40 is a metadata-entity id: the greatest id in the fixture, and it appears only in `m`.
- **t 2:** `[16 :fixture/name "a" · 0]`, a retraction.

A shell is then started on that directory.

## Findings → change → evidence

**1. MEDIUM: one transaction, no retraction.**
- **New test:** `a-restart-restores-every-transaction-group-history-row-and-retraction`. It asserts:
  - the fixture spans `t` 0, 1 and 2;
  - the restored transaction groups (`t` → the set of its datoms), read before any evaluation, **equal** the committed ones;
  - the history rows `[e v t m]` from `q … {:view :history}` equal every committed datom, the retraction included;
  - the current view is exactly `#{[17 "b"] [18 "c"]}`, so the retraction is applied.
- The history and current checks run **before and after** a new publication (`(def delta 4004)`). After it, the restored groups are unchanged and every new transaction's `t` is greater than 2.
- **Mutations** (each applied, run, and reverted by a script; `git diff --stat` identical before and after):
  - all datoms regrouped into one transaction at the last `t` → 3 failures, 4 errors;
  - an older `t` rewritten → 3 failures, 4 errors;
  - retractions dropped → 3 failures, 4 errors.
  
  The errors are the query answering an `Error: …` refusal instead of a relation, which `read-edn` then can't parse. I inspected this under the dropped-retractions mutation.
- **A test bug on the way, not a defect:** my first draft read the restored groups after the later rounds had run. The indexer's log is a live stream those rounds append to, so it showed the new transactions too. The groups are now captured before any evaluation, and the restored groups 0, 1 and 2 were exact all along.

**2. MEDIUM: the metadata-id rule was not isolated.**
- The same test asserts that every entity or metadata-entity id allocated after the restart is greater than 40, the greatest id, which appears only in `m`.
- **Mutation:** `next-entity-id` ignores `m` → 1 failure (allocation resumes at 19).
- On the unmutated code the new ids run from 41 up.

**3. LOW: `(vm …)` said "store cleared" in durable mode** (`src/cljc/yin/repl.cljc`, `handle-command`).
- **Fix:** the message now depends on the store mode.
  - Durable: `Switched to <VM> (VM store cleared; durable code index kept)`.
  - Memory: unchanged, `Switched to <VM> (store cleared)`, so the existing `repl_test` and `main_test` assertions stay valid.
- **Test:** `vm-selection-says-what-it-keeps` asserts the exact durable and memory messages. Before the fix it failed (got `… (store cleared)`); after, it passes.
- **Mutation:** use the memory message in durable mode → 1 failure.

## Verification (one lane at a time, in the foreground)

| Check | Result |
|---|---|
| `clj -M:kondo --lint` on the changed files | 0 errors, 0 warnings |
| Focused JVM: store, main, repl, index, query | 116 tests, 861 assertions, 0 failures, 0 errors |
| Full `clj -M:test` | 2415 tests, 184,717 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2324 tests, 51,153 assertions, 0 failures, 0 errors; "Testing yin.repl.store-test" appears |
| `bb build:yin-repl-peer` | built `build/yin-repl-peer` |
| `bb test:cljd` | "All tests passed!" (+2282, 11:01 elapsed). The run went past the tool's 600-second foreground limit and the harness moved it to the background. I waited on its log in the foreground until the verdict line appeared, and the task completed with exit 0. The compiled store test run alone with `--reporter expanded` lists both new tests, all passed. |
| cljstyle | not run (blocked by the approval gate in earlier slices). Formatting checked mechanically. |

Files changed this round: `src/cljc/yin/repl.cljc` (the message) and `test/yin/repl/store_test.cljc` (the fixture and two tests). No `dao.*` file changed.
