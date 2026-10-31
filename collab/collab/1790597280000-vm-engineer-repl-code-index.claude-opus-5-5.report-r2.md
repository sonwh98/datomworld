Completed-GMT: 2026-09-28 12:50:42 GMT
Completed-Local: 2026-09-28 19:50:42 +07
Coding-Agent: claude
Session-ID: 7fcb4501-80b2-43a3-8511-32424dd6f0d4

# Report r2: REPL code indexing fix round 1 (gate P1, P2, owner ruling on index gaps)

Worktree: /Users/sto/workspace/datomworld-repl-index. Nothing staged or committed. Files touched are the same allowed set as r1: `src/cljc/yin/repl.cljc` (modified), `src/cljc/yin/repl/index.cljc` (new) and `test/yin/repl/index_test.cljc` (new). There are no `dao.space.*`, `yin.vm` or `dao.jing` edits.

## P1: publication overflow (fixed without dao.space edits)
- **Intake:** the indexer's intake is now a `dao.stream.memory-log`, a complete-retention log, instead of the 4096-payload ring buffer. `intake-capacity` and the ringbuffer require are removed. `publish-index!` can append any number of node blobs before the manifest, and the round's drain afterwards reads them all.
- **Read-back:** after draining, `publish` reads the manifest back from the store with `dao.space.index/read-manifest`, which throws on a missing or invalid manifest. Only then does it report `:manifest-address` and record `:published` (the transaction count that publication covers). A drain gap or defect records a `:materialize` failure; a throw anywhere records a `:publish` failure.
- **Option:** `make-indexer` accepts `:publish-opts`, passed straight through to `transactor/publish!`. The shell doesn't set it; the test uses `{:branching-factor 4}`.
- **Test:** `a-publication-beyond-the-old-intake-bound-completes` commits 4 programs of 300 operands each and publishes them in one step. It asserts that this single publication put more than 4096 payloads on the intake, that the status is published, that `read-manifest :count` equals the committed datom count, and that `read-datoms` read back from the store equals the committed datoms.

## P2: failures surfaced in the round result and repl-state
- **`repl-state :index`** is `yin.repl.index/status`: `{:transactions n :published? b :lost? b :gaps n :failure f}`. `:published?` means a readable manifest exists and it covers every committed transaction. The shell-specific manifest address and session token are left out, so the value doesn't depend on which VM or shell ran the programs. `yang.clojure.stream-eval-test` passes unchanged.
- **Round result:** `eval-program` compares the indexer before and after the index step (`index/notice`). When the round's code was not committed, not published, or not indexed because the indexer is lost, it appends one `Warning: …` line after the evaluation's own text. Healthy rounds are byte-identical to before.
- **Test:** `a-publication-failure-is-reported-in-the-round-and-repl-state` uses a store whose `put-bytes-fn` throws. Both rounds answer `3` / `5` followed by a `Warning:` line containing `publish failed: store refused the write`. `repl-state :index` shows 2 transactions, `:published? false`, `:failure :stage :publish` and `:lost? false`.

## Owner ruling on Q1: report, keep evaluating
- **Evaluation continues:** the shell's `ingress-gaps` counts only the evaluation path's own readers again (program-in and the evaluator's program-out reader). An index-reader gap therefore no longer sets `:ingress-loss?` or refuses evaluation. Evaluator-reader gaps keep their existing behaviour; `yin.repl-test`'s gap test is unchanged and passes.
- **Lost indexer:** a gap marks the indexer `:lost? true` (both in `step` and in `skip`, the failed-round path). While lost, the indexer still advances past packets but indexes none of them, including retained packets after the gap in the same round, until reset or VM selection rebuilds it.
- **Reporting:** every forwarded round while lost carries the notice "Warning: the code index lost a program batch; this round's code was not indexed, and indexing is suspended until (reset)". `repl-state :index` shows `:lost? true`.
- **Tests:**
  - `a-gap-on-the-index-reader-loses-the-indexer-until-rebuilt` (unit level): a gap marks the indexer lost, the retained packets after the gap are not committed, and a later round commits nothing and still produces a notice.
  - `an-index-gap-is-reported-evaluation-continues-and-reset-recovers` (shell level): the result is `3\nWarning: …(reset)…`; `:ingress-loss? false`; evaluator gaps stay 0; the index status is lost with 1 gap. The next round still evaluates (`5\nWarning: …`). `(reset)` produces a fresh, healthy status and keeps the same store, and the next round answers exactly `3`, is committed, and is published.

## Verification (commands and exact outcomes)
- `clj -M:kondo --lint src/cljc/yin/repl.cljc src/cljc/yin/repl/index.cljc test/yin/repl/index_test.cljc` → `errors: 0, warnings: 0`.
- **cljstyle: not run.** Both `cljstyle check <the three files>` (relative paths) and the same with absolute paths were refused by the permission gate ("requires approval"). Formatting is unverified by cljstyle.
- `clj -M:test -n yin.repl.index-test` → Ran 9 tests containing 77 assertions. 0 failures, 0 errors.
- `clj -M:test -n yin.repl.index-test -n dao.space.transactor-test -n yang.clojure.stream-eval-test -n yin.repl-test -n yin.vm.store-write-audit-test -n yin.repl.adapter-test -n yin.repl.connect-test -n yin.repl.driver-test -n yin.repl.embed-test -n yin.repl.main-test -n yin.repl.require-test -n yin.repl.serve-test -n yin.repl.serve-connect-wire-test -n yin.repl.build-test` → **Ran 165 tests containing 1308 assertions. 0 failures, 0 errors.** The known main-test intermittent did not occur on this run.
- `bb test:cljs` → **Ran 2203 tests containing 50041 assertions. 0 failures, 0 errors.** "Testing yin.repl.index-test" is present.
- `bb test:cljd`: not run, as instructed.
- The full JVM `clj -M:test` suite was not run this round.

## Design choices
- **Sticky failure:** `:failure` is the last failure and is not cleared by a later successful publication, so a program that failed to commit is not forgotten. `:published?` says whether the current manifest covers every committed transaction.
- **Gap mid-round:** transactions committed before a gap in the same round are still published; nothing after the gap is.
- **Notice placement:** the notice is appended after all round text, including the evaluator-loss error branch, and only for rounds that forwarded a program.

## Unresolved concerns
- **Intake memory:** the complete-retention intake keeps every publication for the session's life. Each round republishes the whole history, so intake memory grows roughly quadratically with the number of programs, on top of the known quadratic publish time. A bounded alternative within yin.repl would be a fresh memory-log intake per publication, calling `dao.space.index/publish-index!` directly over the indexer's own local stream. I did not do that because it bypasses `transactor/publish!` and leaves the transactor's intake pool unused. An incremental publish in dao.space would fix both the time and the memory.
- **CLJD compile** of the changed namespaces is unverified; that lane is the orchestrator's.
- **cljstyle** is unverified (permission gate).
