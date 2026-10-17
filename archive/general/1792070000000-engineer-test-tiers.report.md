Completed-GMT: 2026-10-11 19:38:54 GMT
Completed-Local: 2026-10-12 02:38:54 Asia/Bangkok

# Test tiers: slow tags on the >= 5 s deftests

Worktree `/Users/sto/workspace/datomworld-srvm-tiers`, branch `tiers-slow-tags`,
uncommitted, nothing staged. (The timestamps above are this machine's clock,
which runs earlier than the brief's Created header.)

## What changed

All 63 rows of `collab/slow-deftests-5s.tsv` were found and tagged `^:slow`.
In `.cljc` files, the body is wrapped in
`(slow/guard\n "name"\n (fn [] ...))`, the layout `int_conv_test.cljc` already
uses. There is one exception: a deftest inside the `:clj` branch of a reader
conditional only gets `^:slow`, because it never exists on Node or Dart. That
matches the existing practice in `main_test.cljc` (e.g.
`stopping-the-endpoint-...`). It applies to `main-test`'s
`killing-the-connection-...` and `reattaching-resumes-...`, and to 4 of the
10 `python-antlr-test` tests (the `probe-*` ones, which are JVM-only).
- 37 tests are guarded and 26 have `^:slow` only (20 in `.clj` files,
  6 in `:clj` branches).
- `[dao.test-slow :as slow]` was added after the `clojure.test` require in
  the 9 `.cljc` files that lacked it: main, python-antlr, dht-head,
  dht-end-to-end, compose-rows, holder.driver, safepoint-harness,
  stage-e-rows and int-literal.
- No test logic, assertions or order changed. Inside the guards, every line
  shifts +4 columns. The continuation lines of multi-line string literals were
  left alone, so string contents are unchanged.
- The edits were made with rewrite-clj (`collab/tag_slow.clj`, which starts
  from `git show HEAD:<file>`, and `collab/add_slow_require.clj`).

## Per namespace (JVM, this laptop: 8 cores, one JVM at a time)

The wall times include JVM startup and namespace load, about 5 to 10 s each.
The slow part also runs tests that were already `^:slow` before this change.

| namespace | tagged | quick wall s | quick tests/assertions | slow wall s | slow tests/assertions | quick dominated by shared setup? |
|---|---|---|---|---|---|---|
| dao.space.dht-test | 1 | 9.3 | 44/35906 | 14.0 | 2/858 | no |
| yang.clojure.stream-eval-test | 1 | 14.0 | 10/172 | 26.5 | 3/137 | no |
| yang.python.antlr.e2e-c1-test | 2 | 4.9 | 0/0 | 401.6 | 19/323 | n/a (all tests now slow) |
| yang.python.antlr.e2e-c2-test | 3 | **69.4** | 34/174 | 374.8 | 7/123 | no: 34 tests just under 5 s each (the Python e2e ~2-3 s per-test floor); no fixture, defonce or delay |
| yang.python.antlr.e2e-test | 9 | 5.1 | 2/49 | 1270.6 | 40/696 | no |
| yang.python.antlr.int-conv-test | 2 | 17.8 | 8/29 | 373.1 | 5/240 | no |
| yang.python.antlr.int-literal-test | 1 | 10.9 | 13/356 | 8.4 | 1/8 | no |
| yang.python.antlr.int-ops-parser-test | 5 | 4.8 | 0/0 | 320.6 | 8/260 | n/a (all tests now slow) |
| yang.python.antlr.int-ops-test | 1 | **24.9** | 13/49 | 226.2 | 4/256 | no: several tests under 5 s; no shared fixture |
| yang.python.antlr.prelude-parity-test | 1 | **40.0** | 26/105 | 41.2 | 9/116 | no: 26 tests under 5 s; no shared fixture |
| yang.python.antlr.safepoint-test | 1 | 13.7 | 9/419 | 226.6 | 13/108 | no |
| yin.repl.dht-head-test | 4 | 9.4 | 18/169 | 34.2 | 5/54 | no |
| yin.repl.dht-test | 1 | 18.3 | 43/418 | 56.6 | 3/37 | no |
| yin.repl.main-test | 4 | 10.4 | 37/298 | 52.7 | 6/56 | no |
| yin.repl.python-antlr-test | 10 | 6.5 | 7/49 | 1581.5 | 10/97 | no |
| yin.repl.query-test | 2 | 15.8 | 18/246 | 114.9 | 6/105 | no |
| yin.vm.linker.dht-end-to-end-test | 2 | 15.1 | 4/33 | 14.9 | 2/44 | no |
| yin.vm.ucf.compose-rows-test | 1 | **30.9** | 39/760 | 7.8 | 1/46 | no: 39 tests under 5 s; no shared fixture |
| yin.vm.ucf.holder.driver-test | 1 | **34.5** | 117/1523 | 22.4 | 1/389 | no: 117 small tests; no shared fixture |
| yin.vm.ucf.safepoint-harness-test | 2 | 4.9 | 6/130 | 30.9 | 2/1851 | no |
| yin.vm.ucf.stage-e-process-test | 1 | 4.5 | 0/0 | 499.4 | 13/2622 | n/a (all tests now slow) |
| yin.vm.ucf.stage-e-rows-test | 8 | 13.6 | 5/71 | 49.2 | 8/430 | no |

Every run exited 0 with 0 failures and 0 errors. The raw lines are in
`collab/tiers-runs/summary.tsv` and the full output in
`collab/tiers-runs/<ns>.<quick|slow>.log`.

**Quick parts still above ~20 s:** e2e-c2 (69 s), prelude-parity (40 s),
driver (34.5 s), compose-rows (31 s) and int-ops (25 s). Shared setup is not
the cause:
- None of these files has `use-fixtures`, `defonce` or `delay`.
- In each one, the time is spread over many tests that are each below the
  5 s cut.

The first-test parser-load concern did not show up either: after
`zero-division-test` was tagged, e2e-test's quick part is 5.1 s. Per-test times
inside these quick runs were not measured. Lowering the cut for Python e2e
(build-n-test.md already names 3.0 s for them) would shrink e2e-c2. That is
not done here; it is a decision for the owner or Architect.

Requested item (c): `clojure -M:test -e :slow -n yin.repl.python-antlr-test`
took 6.5 s wall and ran 7 tests / 49 assertions, 0 failures. Its slow part
(10 tests) ran once, last: 1581.5 s, 10 tests / 97 assertions, 0 failures.

## Node

```
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge \
  '{:ns-regexp "^(<the 17 changed cljc namespaces>)$"}'
node target/node-tests.js
```

- Result: Ran 596 tests containing 41926 assertions, 0 failures, 0 errors.
- The run also covered a few required test namespaces: lower-portable,
  require, yin.vm-test, debruijn-vm-contract, linker closure and publish,
  parity, and compose.
- All 37 newly guarded bodies printed `SKIP slow test <name> (set
  DATOM_SLOW_TESTS=1 to run)`, along with the guards that already existed.
- The log is `collab/tiers-runs/node.log`.
- The guarded bodies were not run on Node with `DATOM_SLOW_TESTS=1`.

## Lint and format

- clj-kondo: `clj -M:kondo --lint <22 files>` gave errors 0, warnings 0.
- **cljstyle: NOT RUN.** `cljstyle fix` and `cljstyle check` both needed a
  permission approval that this non-interactive session could not get.
  - Instead, the edit script writes the indentation itself, in the existing
    `slow/guard` layout from `int_conv_test.cljc`.
  - Please run `cljstyle check` on the 22 files before landing.

## Changed files

Python track (yang.python.antlr.* and yin.repl.python-antlr-test; check these
for conflicts):
- test/yang/python/antlr/e2e_c1_test.clj
- test/yang/python/antlr/e2e_c2_test.clj
- test/yang/python/antlr/e2e_test.clj
- test/yang/python/antlr/int_conv_test.cljc
- test/yang/python/antlr/int_literal_test.cljc (+ require)
- test/yang/python/antlr/int_ops_parser_test.clj
- test/yang/python/antlr/int_ops_test.cljc
- test/yang/python/antlr/prelude_parity_test.cljc
- test/yang/python/antlr/safepoint_test.cljc
- test/yin/repl/python_antlr_test.cljc (+ require)

Others:
- test/dao/space/dht_test.cljc
- test/yang/clojure/stream_eval_test.cljc
- test/yin/repl/dht_head_test.cljc (+ require)
- test/yin/repl/dht_test.cljc
- test/yin/repl/main_test.cljc (+ require)
- test/yin/repl/query_test.cljc
- test/yin/vm/linker/dht_end_to_end_test.cljc (+ require)
- test/yin/vm/ucf/compose_rows_test.cljc (+ require)
- test/yin/vm/ucf/holder/driver_test.cljc (+ require)
- test/yin/vm/ucf/safepoint_harness_test.cljc (+ require)
- test/yin/vm/ucf/stage_e_process_test.clj
- test/yin/vm/ucf/stage_e_rows_test.cljc (+ require)

Helper scripts (not for landing): collab/tag_slow.clj,
collab/add_slow_require.clj, collab/tiers_run.clj.

Setup run in this worktree: `bb gen:python-antlr` (without it, the ANTLR lexer
class was missing) and `bb build:yin-repl-node`.

## Not done

- cljstyle fix/check (permission; see above).
- Dart lane (the orchestrator runs it).
- Node with `DATOM_SLOW_TESTS=1`.
- Per-test timing inside the quick parts that are still above 20 s.
- Changes to build-n-test.md's slow-set notes: none made. Its "Python slow set
  (2026-10-06)" counts are now stale.
