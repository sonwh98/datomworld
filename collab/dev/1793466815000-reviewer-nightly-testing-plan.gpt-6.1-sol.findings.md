I’ll use the code-review-pr skill to compare the plan with the scripts and repository rules, then give an explicit verdict for each question. I’ll keep the review read-only and return findings here.

===

The Dart runner can create up to eight inner shards per subsystem, so four concurrent Flutter commands may launch up to 32 test workers. I’m checking the fixture hazards and coverage checks next. The host-only scripts aren’t in this checkout, and sandboxed SSH couldn’t resolve shiva; I’ll try a read-only SSH inspection.

===

The deployed scripts have two coverage blockers: Dart computes the `uncovered` list but never emits or runs it, and the nightly JVM command still excludes `^:slow` tests. Both new shard scripts also finish with exit 0 after recording red shards. I’ll check the pin freshness and log retention before finishing the review.

===

**Overall: REVISE before wiring either shard script into the nightly.** The approach is reasonable, but the deployed implementation can omit tests, accept yesterday’s pin, report success after failures, and overwrite diagnostic evidence.

I inspected the scripts on both hosts, local checkout `2bd42461`, shadow-cljs 3.3.5’s runner source, and shiva’s installed Flutter test compiler. No files were written and no test suites were launched.

1. **R1 — FAIL: the coverage diff proves planned membership, not execution.**

   Two concrete omissions already exist:

   - **Dart’s residual never runs.** `shiva:~/ci/dart-shard.sh:36–43` computes `ns-uncovered.txt` and includes it in compilation, but the emission and execution loops at lines 49 and 70 iterate only `$SUBS`. Compilation does not execute those tests.
   - **The nightly JVM lane excludes slow tests.** `jupiter:~/ci/shard-jvm.sh:30` invokes `clojure -M:test -e :slow`. `nightly-jvm.sh:55` calls this script and never calls `slow-tier.sh`. Consequently, the deployed nightly is not the stated full JVM suite.

   Further gaps:

   - Node’s runtime selector silently ignores unknown namespaces: shadow-cljs’s `shadow/test/node.cljs`, `find-matching-test-vars` and `execute-cli`, filter registered vars without validating every requested namespace. A partial or empty selection can therefore exit successfully.
   - Cross-host peer prerequisites are incomplete. Node’s shard script builds the Node REPL but not `build:yin-repl-peer`; neither does the JVM shard script. [bb.edn:248](/Users/sto/workspace/datomworld/bb.edn:248) documents that missing peers cause tests to skip. An existing peer can also belong to an older commit.
   - Disk enumeration covers conventional `test/**/*_test.<lane-extension>` files. It does not inherently cover generated namespaces, dynamically registered namespaces, additional discovery roots, or future naming conventions.

   Reader-conditional namespaces with no tests on a particular lane are legitimate exclusions. Parameterized tests generated inside a selected namespace should execute normally; separate generated namespaces require explicit discovery. I found no namespace/path mismatch among the current 335 conventional test source files, so path conversion is not a demonstrated current omission.

   **Required:** run Dart’s residual; include JVM slow tests; rebuild required peers at the selected SHA; reconcile planned namespaces against fresh runtime/generated test inventories, recording explicit lane exclusions. Fail on unexplained missing execution.

2. **R2 — CONCERN: concurrent Node execution is plausible, with remaining conditions.**

   One completed compile followed by six Node processes avoids concurrent shadow compiler writes, provided the clone remains exclusive until all workers finish. Runtime selection is supported by the inspected shadow runner and avoids relying on the warm-cache compile filter.

   Fixture samples are encouraging:

   - `test/dao/stream/ws/node_test.cljs:379` and `test/yin/repl/host/node_test.cljs:43` request ephemeral ports.
   - [serve_test.cljc:5](/Users/sto/workspace/datomworld/test/yin/repl/serve_test.cljc:5) explicitly uses an in-process loopback fixture; its apparent port 8080 does not bind a socket.
   - [main_test.cljc:34](/Users/sto/workspace/datomworld/test/yin/repl/main_test.cljc:34) and `dht_head_test.cljc:53` use UUID scratch paths.

   Remaining hazards are other real socket fixtures, shared filesystem paths, leaked child processes, and module initialization: `--test` selects test vars after loading the program; it does not isolate namespace-loading side effects.

   There is also no clone lock preventing another compile, watcher, checkout, or `npm ci` during execution. An existing shadow server/watch process must not mutate the bundle or cache while workers use it.

   **Before authoritative use:** finish the complete parallel validation, compare per-namespace execution with a serial baseline, and rerun concurrency-only failures serially while retaining both results. Enforce exclusive ownership of the clone during the run.

3. **R3 — FAIL: distinct aggregator directories do not isolate Flutter’s writable state.**

   **Concurrent reads of stable `lib/cljd-out` are safe** if every compiler and generator has finished and no other job can rewrite it. The problem lies beyond that directory.

   On shiva’s installed Flutter 3.47.1, `packages/flutter_tools/lib/src/test/test_compiler.dart:112–128` selects a project-wide `build/test_cache/...` kernel cache; lines 281–291 copy compiler output into that shared file. Four Flutter commands in one project can therefore write the same cache despite different aggregator directories. Flutter also has project asset and generated registrant outputs.

   The deployed resource cap is **four outer commands × `--concurrency 4` = up to sixteen test workers**, plus compiler processes (`dart-shard.sh:63,71`). Distinct directories can each contain eight aggregator files, but only four execute concurrently per command.

   Emission failures are also unsafe: lines 52–55 merely record their exit codes; execution later checks directory existence. Old aggregator directories survive outside `target-ci`, so a failed emission can leave a stale directory eligible to run.

   **Required:** use isolated project copies for concurrent Flutter commands, or one Flutter invocation over all emitted aggregator files with a single concurrency budget. Treat emission failures as fatal and use fresh, run-specific output directories.

4. **R4 — FAIL: the pin protocol does not establish a common nightly run.**

   `shiva:~/ci/nightly-full.sh:33` writes a persistent SHA-only file. `jupiter:~/ci/nightly-jvm.sh:35–45` accepts the first readable value whose object exists locally. There is no run date, generation identifier, or freshness check.

   **Concrete race:** jupiter starts before shiva updates the file, reads yesterday’s valid SHA, and immediately labels its run “pinned.” Retries never occur. The stale pin remains readable between nights.

   Other failure modes:

   - Publication uses truncation rather than atomic replacement.
   - Both scripts ignore fetch and checkout failures; a summary can claim a pin while tests execute the previous checkout.
   - Jupiter fetches before reading the pin. A newly published commit might not exist in that earlier fetch, causing fallback instead of fetching/retrying that commit.
   - The claimed three-minute bound excludes SSH connection time and does not bound total remote-command duration.

   **Yes: an unpinned run should be advisory for the combined matrix.** Its individual lane remains useful evidence for its actual SHA, but it cannot complete another SHA’s matrix.

   **Required:** atomically publish `{run-id, full-SHA, timestamp}`; require the expected Vietnam nightly date; validate the commit; check every Git command and actual `HEAD`; join results only by matching run-id and SHA. Record fallback reasons explicitly.

5. **R5 — CONCERN: retain the owner-approved split until comparable measurements exist.**

   The current measurements support jupiter for JVM and justify investigating its Node anomaly. They do not establish the best placement after sharding. The JVM comparison is quick-tier timing, and the deployed JVM nightly currently omits slow tests.

   Measure, at one pinned commit:

   - Complete full-tier walls, including prerequisites and compilation.
   - Per-subsystem duration and the longest shard.
   - CPU utilization, peak aggregate RSS, swap, I/O, and guest activity.
   - Worker-count scaling on both hosts, with repeated comparable warm/cold runs.
   - Failure and timeout differences under concurrency.

   **The first alternative worth measuring is Node on shiva, JVM plus Dart on jupiter**, with separate clones and a shared host resource budget. If Node remains dominated by one heavy subsystem, distributing selected shards across hosts may help more than moving an entire lane. Do not assume more workers shorten the longest shard.

6. **R6 — FAIL: failures and diagnostic evidence can be lost or misattributed.**

   - **Failures return success:** `node-shard.sh:67–70` and `dart-shard.sh:74–77` calculate red counts, then finish with a successful `echo`. Worker functions likewise finish by writing summaries. `xargs` failures are unchecked. Both nightly wrappers explicitly end with `exit 0`.
   - **Missing workers can look green:** red counts inspect only rows that exist; expected-versus-completed shard sets are never compared.
   - **Prerequisite failures can be hidden:** both shard scripts continue after unsuccessful prerequisite commands. Dart emission failures are also ignored.
   - **Logs are overwritten:** all shard scripts delete their fixed `target-ci/*shards` directory at startup. Jupiter’s timestamped nightly log captures summary output, while individual test stdout remains in the directory deleted next run (`nightly-jvm.sh:55`, `shard-jvm.sh:9,30`).
   - **Attribution is incomplete:** the summaries lack a consistent full-SHA/run-id/host/toolchain manifest. Pulling `nightly-jvm-latest-summary.txt` can attach a previous run’s result (`nightly-full.sh:48`).
   - Node and Dart workers call `ts` without exporting it, unlike the JVM runner, so their timestamps are missing.
   - Summary synchronization has no connection timeout; “best effort” can still hang after testing finishes.

   **Required:** preserve immutable per-run plans, prerequisite logs, emission logs, shard stdout/stderr, and result records. Aggregate against the expected shard set; return nonzero for failed, missing, or incomplete work; distinguish incomplete infrastructure from test failures. Sync matching manifests with bounded timeouts, keeping local evidence authoritative.

These findings concern evidence integrity and completeness. They remain blockers even though the repository defines the nightly as a post-hoc master integrity check rather than a landing gate.