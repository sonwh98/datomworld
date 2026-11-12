Completed-GMT: 2026-11-11 16:58:54 GMT
Completed-Local: 2026-11-11 23:58:54 ICT

F1: OBJECTION: **P1 — result rendering bypasses both authoritative wrappers.**

The two wrappers correctly overwrite all three retention keys, including empty snapshots. However, recovery still consumes exception-carried evidence; the report’s claim that every relevant exception crosses a wrapper is false.

A supported frontend `:render` hook executes at [repl.cljc:1326](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1326), **after** `run-evaluator-stage` returns. If it throws the same forged payload used by the new tests:

- `run-evaluation` catches it directly, merges fabricated handles at [2080](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:2080), and prefers fabricated failures over `@latest` through [334](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:334).
- Pending completion likewise renders inside `recheck-pending*` at [2526](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:2526); its catch directly adopts exception-carried retention fields at [2560](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:2560).

Thus fabricated evidence can still enter session state. On immediate completion it can also produce a false Warning.

**Required fix:** make recovery consume only trusted shell snapshots across the entire protected operation, including finalization/rendering. Add immediate and pending-completion renderer regressions with conflicting exception data; preserve genuine diagnostics and handles exactly once.

F2: CONFIRMED-FIXED **for the tested immediate VM-throw and query-resume paths**. Both healthy and genuinely failing retention variants assert rejection of fabricated diagnostics/handles, Warning behavior, and survival counts. See [tests:2287](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2287), [2327](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2327), and [2355](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2355).

The reported **17 failures/46 assertions → 0/46** is consistent with those assertions and the previous implementation. The available author record reports the reverted-hunk run; I did not independently execute or verify that historical run. [Report:33](/Users/sto/workspace/datomworld/collab/yang-clojure/1794407164000-engineer-cc3-fix-r9.muse-wonder-1.0.findings.md:33).

F3: CONFIRMED-FIXED — genuine retention failures remain stage-tagged and observable; successful retention and the additive Warning path remain intact. See [repl.cljc:218](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:218), [1406](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1406), and [1636](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1636).

F4: CONFIRMED-FIXED — dropping the merge inside `link-raise` does not lose legitimate retention on its current call paths. It wraps resumed `vm/run` failures or the shell’s query-limit error, using the session that already holds completed retention. See [repl.cljc:1692](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1692), [1784](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1784), and [1816](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1816).

Static review only; no suites run or files edited. Cross-node acceptance remains conditional on Shiva.

CONFIRMATION VERDICT: REQUEST CHANGES