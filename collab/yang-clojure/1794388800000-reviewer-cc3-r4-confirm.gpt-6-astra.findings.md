Completed-GMT: 2026-11-11 09:51:32 GMT
Completed-Local: 2026-11-11 16:51:32 ICT

Static review only; no suites run and no files edited.

**F2: OBJECTION: P1 — token construction is fixed, but actual callers still reuse admission coordinates.**

The constructor now correctly validates and retains `[:source :program [run-scope ordinal] 0]`; nonzero ordinals bind successfully by construction ([latte_oracle_test.clj:2480](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2480)). The distinct-scope, distinct-ordinal, and replay tests are present at [line 3990](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3990). No admission global was reintroduced.

However, moving constants into callers preserves the collision:

- `kernel-row` always supplies `:kernel-row 0` ([line 2894](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2894)), while `kernel-gate-evidence` calls it separately for every fixture ([line 3207](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3207)).
- The proof-supply loop likewise supplies the same coordinate on every iteration ([line 2552](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2552)).
- The principal binding test uses a fixed `:cc3-binding-run 0`, with no enclosing run identity ([line 3731](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3731)).

**Required fix:** allocate and retain scope at the run composition, thread it through `kernel-row`, and assign distinct admission ordinals within that run. Fresh runs need distinct supplied scopes; replay must explicitly reuse the retained record. Pin this through the actual caller path, beyond direct constructor tests.

**F4: CONFIRMED-FIXED.**

`link-raise` carries completed handles ([repl.cljc:1572](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1572)). Recovery merges both carried handles and the latest completed evaluation state ([line 1885](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1885), [line 1940](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1940)); pending-run recovery also preserves them ([line 2409](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:2409)).

The query-resume failure and reopen regression is present at [clojure_callsite_test.cljc:2104](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2104). These are keyed-map merges, so repeated carriage does not double-register handles.

**F6: CONFIRMED-FIXED for the measurement-method defects; bounded acceptance remains failed.**

The in-repo script constructs retained structures locally, samples while the holder remains live, and releases it on return ([measurement script:20](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794386683000-cc3-f6-measure.clj:20)). Peak sampling spans serialization, file-backed materialization, and both tagged lanes ([line 90](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794386683000-cc3-f6-measure.clj:90)). Numerical results and GC/sampling comparability limits are recorded ([r4 report:123](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794386683000-engineer-cc3-fix-r4.muse-wonder-1.0.findings.md:123)). I inspected the method; I did not reproduce the measurements.

One acceptance-accounting defect remains: the script judges retained memory using the **median** ([script:141](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794386683000-cc3-f6-measure.clj:141)). Even the smaller holder has an **8,044,024 B** sample above the **8,000,000 B** ceiling, despite being labelled “under” ([report:127](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794386683000-engineer-cc3-fix-r4.muse-wonder-1.0.findings.md:127)). A median cannot establish compliance with a maximum ceiling.

**EXCEEDANCE RULING: (c) — preserve this run as failed; permit a new, explicitly versioned bounded run.**

I do not retroactively declare the old ceiling mis-scoped. Both the compiled-inclusive measurements and one smaller-holder sample exceed it. §6.3 permits revised bounds through a new declaration while preserving the failed run ([design:329](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md:329)); shrinking the fixture is therefore not mandatory.

Before fresh tagged evidence, the new declaration must state:

- The pinned corpus/revision, host/JVM settings, retained object set—including compiled input and reconstruction evidence—and maximum simultaneously retained fixtures.
- Separate finite storage, retained-memory, peak-heap, and time ceilings, justified by the pilot measurements with explicit headroom.
- The exact reproducible measurement procedure, including the compiled-inclusive variant, repetitions, sampling limitations, and maximum-based pass/fail rule.
- The superseded run’s failed status and exceedances, without relabelling it successful.

F1/F3/F5 remain confirmed within their previously stated scope.

CONFIRMATION VERDICT: REQUEST CHANGES