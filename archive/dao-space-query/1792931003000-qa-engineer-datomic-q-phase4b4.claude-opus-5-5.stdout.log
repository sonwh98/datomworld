Completed-GMT: 2026-10-25 14:48:33 GMT
Completed-Local: 2026-10-25 21:48:33 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

4b-4 is done: the three P3 fixes, the Dart lane fix, the contract amendments and a re-digested corpus are all in, and the bare three-host gates pass. One shared file, `src/dev/cljd_agg.clj`, changed (item B), so please point the reviewer at it. Nothing is staged or committed.

## A. P3 fixes
1. **Rootless descriptor.** I wrapped `cursor/start` in the same `guarded` helper as every other cursor call. I chose that over tightening `valid-read-descriptor?` because the check stays structural and the failure is classified uniformly. A structurally valid but rootless descriptor now yields `:source-failed` with the cursor's `:invalid-descriptor` as `ex-cause`, with zero reads. There is a test for it.
2. **`:read` phase.** `:read` is now in the published `phases` vector with an explanation, and the phases test is updated. A new test confirms a deadline during a fetch reports `:phase :read`.
3. **Stale docstring.** `published_read_test.cljc`'s namespace docstring now states the admitted profile.

## B. Dart lane
- **Verified, not a reporter artifact.** `dao.data.sorted-user-type-test` has every `deftest` behind `#?(:cljd nil ...)`, so the cljd tool writes no `test/cljd-out/.../sorted-user-type-test_test.dart`. It does write `lib/cljd-out/dao/data/sorted-user-type-test.dart` (the ns compiles as an empty library). `--only` treated the missing test file as fatal.
- **Fix, in shared lane code (`src/dev/cljd_agg.clj`).** Under `--only` (used by `test:sub` and `test:changed`), a requested namespace that produced a lib file but no test file is reported as JVM-only and skipped. A namespace that produced neither file is still fatal, and `--slow-only` is unchanged. A new `jvm-only?` helper holds the rule, and the docstring is updated.
- **Result.** `bb test:sub:cljd dao.data` now passes bare: 10 namespaces, 107 tests, 0 failures.

## C. Contract (`docs/design/dao.space.query.datomic-compat.md`)
- **Status header.** Now says 4a is landed, 4b-1 through 4b-3 are implemented, and 4b-4 records the evidence. It names the admitted profile and keeps Client oracle qualification in Phase 5.
- **Calibration paragraph.** Added to the step-unit section.
- **New subsection "Phase 4b: checked published-index traversal."** It covers:
  - the cursor/reader/descriptor/producer mechanism;
  - the range plan;
  - the sentinel table and stopping rule;
  - the memoization clarification;
  - the unchanged one-in-flight guarantee;
  - the adapter-profile boundary note, stating that refusing fallback-needing clauses before any read is an adapter-profile property;
  - the `:read` footnote;
  - the recorded evidence.
- **Admission table and phase table.** The admission table is replaced with the section-9 table, and the phase-table 4b row carries its status.
- **Pre-existing edits.** `git diff` for this doc shows more than my hunks, because the file already had orchestrator edits before this slice.

## E. Corpus
`bounds.published-source-refused` was stale after the admission flip, so I updated its note and errata E12 to describe the admitted published profile. The case stays `pending` with no expectation change.
- **New digest.** `a1c99136b1d87d1c93e336d9e8cb44142eea02d07b945a08703eeffe9771fa7b`, pinned once in the test literal and once in `provenance.edn`. Provenance records the superseded `a4983d69`.

## D. Qualification gates
Three-host, `mise exec -- bb test:sub dao.data dao.space`, run in one invocation:
```
lane  namespaces  tests failures seconds  status
clj           27    560        0     392  pass
cljs          26    550        0     972  pass
cljd          26    550        0     714  pass
```
The Dart lane ran bare through the subsystem path, with no `--only` workaround.

JVM, each suite individually:

| Suite | Result |
|---|---|
| `dao.space.query-test` | 72 tests, 282 assertions |
| `dao.space.query.datomic-test` | 29 tests, 222 assertions |
| `dao.space.query.execution-test` | 42 tests, 261 assertions |
| `dao.space.query.datomic-execution-test` | 22 tests, 190 assertions |
| `dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions |
| `dao.data.btree.cursor-test` | 17 tests, 1227 assertions |
| `dao.data.btree.storage-read-test` | 5 tests, 17 assertions |
| `dao.space.published-read-test` | 5 tests, 37 assertions |
| `dao.space.query.published-bounded-test` | 24 tests, 312 assertions |
| query-numeric / positional-query | 18/922 and 11/27 |
| index / index-numeric | 56/531 and 5/345 |
| transactor | 16/100 |
| dht | 46/36,764 |
| transact | 8/26 |
| schema | 72/210 |

All of these have 0 failures. kondo on every file changed across 4b (including `cljd_agg.clj`): 0 errors, 0 warnings. `bb check:node-discovery`: `Node discovery OK: 229 source files outside test/`.

`git status` for my work: modified are `storage.cljc`, `query.cljc`, `datomic.cljc`, `execution.cljc`, `cljd_agg.clj`, the conformance test, `execution_test.cljc`, the compat doc, and the three corpus files (errata, json, provenance). New are the cursor, the cursor test, the storage-read test, `published_read_test.cljc` and `published_bounded_test.cljc`. `docs/orchestrator-log.md` is the orchestrator's.

## Concerns
- **Lane timing.** The combined run took 392s on the JVM, 972s on Node and 714s on Dart because the lanes ran in parallel with other work. The new bounded matrix is the main cost, and trimming its cap sweeps would be easy if the gate time matters.
- **Cursor tests on Dart.** `--only`-based runs also reported `dao.data.sorted-user-type-test` as skipped. That is the intended new behavior, but the reviewer should check the message wording.
- **`:read` phase.** Adding `:read` to the vector means `ex/phases` iterators (the unbounded-no-observation test) now also exercise it. I confirmed that test still passes.
