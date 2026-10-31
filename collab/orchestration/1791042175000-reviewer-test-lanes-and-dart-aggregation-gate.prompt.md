Created-GMT: 2026-10-03 15:42:55 GMT
Created-Local: 2026-10-03 22:42:55 +07 (+0700)
Coding-Agent: glm
Session-ID: 881afb54-9cdb-4f0a-b7d3-14b8a6e72be0

# Task: Test-lanes restructure and Dart aggregation runner, static gate

Role: Adversarial Code Reviewer and Security Auditor (static gate: no test suites)

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-10-03 22:43 +07 | Status: active | Rationale: routing-status 2026-10-03 (glm available via CLI); different family from the Claude-family author

Perform a read-only review of the UNCOMMITTED test-infrastructure change in /Users/sto/workspace/datomworld
(master 8d338571 plus working-tree changes). Read the owner's requirement first, verbatim:

> "buy us time is good. bb test should run all lanes quickly. bb test:all should run all tests, bb test:slow
> should run only the slow tests"

Inspect, with `git diff` and by reading the new files:
- bb.edn (the new test, test:all, test:slow tasks and their :clj/:cljs/:cljd subtasks)
- src/dev/cljd_agg.clj (new): compiles the generated Dart tests through the cljd CLI, groups the generated
  test/cljd-out/**/*_test.dart files into shards under build/cljd-agg/, runs `flutter test` on the shards,
  deletes the shards. Each generated file has a `main()` that registers its deftests with package:test; a shard
  imports N files `as mK` and calls `mK.main()` for each, so one load replaces N.
- test/dao/test_slow.cljc (new) and its use: 11 cross-host .cljc tests wrap their body in
  `(dao.test-slow/guard "name" (fn [] ...))`, in test/dao/space/dht_test.cljc, test/yang/clojure/stream_eval_test.cljc,
  test/yang/python/antlr/safepoint_test.cljc, test/yin/repl/{ast_query_e2e,dht,query}_test.cljc
- docs/agents/build-n-test.md changes
- test/yin/vm/debruijn_register_benchmark_test.cljc (the JFR test and helpers were deliberately removed:
  the Node/Dart branch was `(is true)`, the JVM branch tested the Java profiler, not our code)

Check, with file:line evidence:
1. Do the tasks match the owner's three sentences? `bb test` = fast default on all 3 lanes; `bb test:all` = every
   test incl. slow on all 3 lanes; `bb test:slow` = ONLY slow tests (JVM by ^:slow tag; Node and Dart by
   namespace, with the documented limit that the other tests in those 6 namespaces also run). Any task that runs
   something it should not, or skips something it should run? Are :depends correct (the Node REPL build, the Dart
   peer build, python-antlr generation)?
2. cljd_agg.clj correctness and robustness: sharding determinism; stale shard cleanup (shards must never sit under
   test/, where plain `cljd test` would run them twice); exit-code propagation (a failing test must make the
   task fail; a shard that fails to load must too); handling of a namespace with two source files (a bug of this
   kind was found and fixed: dao.stream.cbor-test and dao.stream.ws-codec-test have both a .cljc and a
   .cljd, which doubled their tests; confirm the fix is complete in every code path, including --slow-only
   and --slow-regex); regex or path assumptions that break on a namespace with `_` or unusual forms; shell
   quoting; the `--config-merge` EDN string the test:slow:cljs task builds from the regex (is the escaping right?);
   concurrency choices.
3. Isolation: running several test namespaces in one Dart isolate. Any global/shared state hazard you can point to
   in the code (not hypothetical)? Process-exit calls, top-level mutable state, fixed ports or temp paths shared
   between namespaces (the DHT/loopback tests are the likely ones)?
4. dao.test-slow: the `:cljd` branch must come FIRST in every reader conditional (ClojureDart's host pass matches
   :clj too). Check the Platform.environment read, the `exists? js/process` guard, and that the 11 wrapped tests
   keep their original assertions byte for byte inside the thunk (a diff of only indentation plus the wrapper).
5. Anything in the docs that is now false or misleading.

Already verified by the orchestrator (untrusted by you, but do not re-run): `bb test` end to end exit 0 in
857.8 s (JVM 2899 tests/226556 assertions/0 failures; Node 2717/91631/0; Dart all passed); after the dedup fix
Dart runs 166 test files in 8 shards, +2672 tests, 0 failures, 255 s; cljstyle and kondo clean. Do NOT run suites.

Do not edit. Treat prior reports as untrusted. Complete in one turn.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate. End with an explicit ready-to-commit verdict.
