Completed-GMT: 2026-11-11 08:43:23 GMT
Completed-Local: 2026-11-11 15:43:23 ICT

Static review only; no suites run and no files edited.

**F1: CONFIRMED-FIXED.**

Scalar grammar fields are compared at translated positions ([latte_oracle_test.clj:3573](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3573)); the recognizer requires `gate-site!` ([line 2047](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2047)); duplicate site IDs refuse before either map is constructed ([line 3690](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3690)). Scalar-mutation, wrong-helper, and duplicate-ID refusal tests are present at [line 3904](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3904).

**F2: OBJECTION: P1 — default admissions still collide, and nonzero ordinals break binding.**

The global counter is gone, and the explicit cross-scope/replay test exists. Two defects remain:

- The one-argument form assigns **every admission** `[:source :program :oracle-run 0]` ([line 2471](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2471)). Existing callers—including the principal binding test at [line 3727](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3727)—still use it. Repeated admissions therefore collide even within one process.
- The scoped arity places the ordinal in origin slot 3 ([line 2476](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2476)), but binding reconstructs a single-member envelope whose member index is always zero ([line 3631](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3631)). For ordinal 1, projected markers have origin ending in `0`, while binding searches for keys ending in `1`, causing refusal.

**Required fix:** propagate explicit coordinates through the actual callers. For separately admitted single-member batches, a token such as `[run-scope admission-ordinal]` with member index zero preserves the distinction. Add same-scope/different-ordinal coverage, including a nonzero ordinal.

**F4: OBJECTION: P1 — immediate execution errors are fixed, but errors after evaluator-stage return still lose handles.**

The new catch carries completed handles, recovery merges them, and the direct throw-after-load/reopen test is present ([repl.cljc:1507](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1507), [clojure_callsite_test.cljc:2055](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2055)). The keyed merge does not double-register handles.

However, a claimed program can return from `run-evaluator-stage` waiting on a query/link, then throw when `drive-links` resumes it ([repl.cljc:1885](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1885)). Its handles exist in `evaluated`, but `link-raise` does not carry them ([line 1559](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1559)), and recovery falls back to the earlier `expanded` state ([line 1914](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1914)). The persisted artifacts again lose their lookup handles.

**Required fix:** preserve completed retention through the entire evaluation/recovery path, including resumed query/link failures. Add that failure-and-reopen regression.

**F6: OBJECTION: P2 — numerical evidence is missing, and the measurement script does not measure the claimed quantities.**

Both report copies still contain `F6-NUMBERS-TBD` ([worktree report:144](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794376197000-engineer-cc3-fix-r3.muse-wonder-1.0.findings.md:144)).

The referenced script also has two substantive problems:

- The structures are already retained by global Vars before the “before-retain” sample. Adding references to a holder measures the holder’s incremental allocation, not those structures’ retained memory ([script:19](/tmp/cc3-f6-measure.clj:19), [line 33](/tmp/cc3-f6-measure.clj:33)).
- The sampled pipeline performs projection/derivation and tagged execution, but no persistence operation. Serialization itself occurs after the sampler stops ([line 27](/tmp/cc3-f6-measure.clj:27)).

**Required fix:** measure structure allocation/lifetime without pre-existing retained references, include actual serialization and durable persistence within peak sampling, and record numerical results against the declared ceilings. Preserve the incomplete status of the existing evidence.

F3/F5 remain confirmed within their previously stated scope.

CONFIRMATION VERDICT: REQUEST CHANGES