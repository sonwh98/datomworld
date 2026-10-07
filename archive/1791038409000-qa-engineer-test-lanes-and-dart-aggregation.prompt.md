Created-GMT: 2026-10-03 14:40:09 GMT
Created-Local: 2026-10-03 21:40:09 +07 (+0700)
Coding-Agent: claude
Session-ID: a5deb099-bc8e-412a-a2f4-cc844085f3b3

# Task: bb test / test:all / test:slow lanes, and a Dart aggregation runner

Role: QA, TDD & Verification Engineer

Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-03 21:40 +07 | Status: active | Rationale: bounded build-tooling work in bb.edn, a dev script and docs; everyday coding tier; different review family (glm / gpt) will gate it

Implement in /Users/sto/workspace/datomworld (master 8d338571 plus UNCOMMITTED work that you must
keep and build on: see "Current state"). Read first: docs/agents/build-n-test.md, bb.edn, the cljd tool's
`test-cli` (in ~/.gitlibs/libs/sonwh98/clojuredart/23576fbe1b35fa2eb1a70f52c70627c447353bf6/clj/src/cljd/build.clj,
~line 695) and test/dao/test_slow.cljc.

## Owner requirement (verbatim quote; the paraphrase below is mine)

> "buy us time is good. bb test should run all lanes quickly. bb test:all should run all tests, bb test:slow
> should run only the slow tests"

My reading: `bb test` runs the three lanes (JVM, Node, Dart) in their FAST default form; `bb test:all` runs every
test on every lane, slow ones included; `bb test:slow` runs ONLY the slow tests, on every lane where that is
possible. If any part of this reading looks wrong against the repo, stop and report instead of guessing.

## Current state (all of this exists in the working tree; do not revert it)

- JVM: 22 tests are tagged ^:slow. `bb test:clj` = `clojure -M:test -e :slow`; `bb test:slow` = `-i :slow`.
- Node/Dart have no tag filter. 11 cross-host .cljc slow tests wrap their body in
  `(dao.test-slow/guard "name" (fn [] ...))` (test/dao/test_slow.cljc): the body runs on Node/Dart only when
  DATOM_SLOW_TESTS=1, else it prints SKIP. Those 11 live in 6 namespaces: yang.clojure.stream-eval-test,
  yang.python.antlr.safepoint-test, yin.repl.query-test, yin.repl.dht-test, yin.repl.ast-query-e2e-test,
  dao.space.dht-test. (Derive this list from the source, `grep slow/guard`, never hard-code it.)
- bb.edn already has test:clj, test:cljs, test:cljd, test:slow (JVM only), test:slow:cljs, test:slow:cljd
  (the last two run the WHOLE lane with the env var on). docs/agents/build-n-test.md documents these.
- Measured lane times today: JVM default 5 min 38 s; Node default 3 min 40 s; Dart default 8 min 8 s
  (setup 108 s = peer build + cljd compile; `flutter test` 379 s).

## Required behavior

1. `bb test` = test:clj + test:cljs + test:cljd, default (fast) form. No ^:slow / guard-skipped test runs.
2. `bb test:all` = JVM `clojure -M:test` with NO filter (everything, incl. ^:slow), Node with
   DATOM_SLOW_TESTS=1, Dart with DATOM_SLOW_TESTS=1. Every test runs once on every lane.
3. `bb test:slow` = ONLY slow tests:
   - JVM: `clojure -M:test -i :slow`.
   - Node: the namespaces that contain a slow/guard test, with DATOM_SLOW_TESTS=1 (namespace-level selection;
     the non-slow tests inside those 6 namespaces running too is acceptable and must be documented). Use
     shadow-cljs `--config-merge '{:ns-regexp "..."}'` or whatever shadow supports; verify it.
   - Dart: only the generated test files of those namespaces, with DATOM_SLOW_TESTS=1.
4. Keep per-lane subtasks with clear names (suggested: test:clj / test:cljs / test:cljd = default;
   test:all:clj|cljs|cljd; test:slow:clj|cljs|cljd) so a lane can be run alone. Existing names
   test:slow:cljs and test:slow:cljd change meaning to "slow only"; update docs accordingly.

## Dart aggregation runner (the main engineering item)

Measured finding (collab: this brief's author, 2026-10-03): `flutter test` loads each of the 166 generated
test/cljd-out/**/*_test.dart separately at about 7.5 s each (964 s of aggregate loading across parallel workers),
against 513 s of test execution. Four aggregator files that `import` N generated files `as mK` and call
`mK.main()` for each ran all 2,673 tests in 138 s wall (vs 379 s), all passing, same default concurrency. Each
generated file's `main()` just calls its deftest functions, which register with package:test.

Build a runner so the Dart lanes use it:
- The cljd tool's `cljd test` = `compile-cli` then `flutter test` with NO path, which runs every *_test.dart under
  test/. So generated shards must never sit under test/ (they would run twice, and plain
  `clojure -M:clojuredart:cljd test` must keep working unchanged). Put them under an ignored dir (build/ is
  git-ignored) or clean them up reliably; stale shards must never double-run.
- Script (suggested src/dev/cljd_agg.clj, plus bb tasks): (a) compile through the cljd tool's own
  functions (cljd.build/compile-cli, needing the :clojuredart classpath and the tool's *deps* binding; if that
  proves too brittle, say so and propose the least invasive alternative), (b) discover the *_test.dart files
  (optionally restricted to given namespaces), (c) shard them deterministically and balanced (greedy by file
  size; N = min(8, cores) unless you measure better; keep concurrency settings explicit), (d) generate the
  aggregator files with relative imports and calls to each `main()`, (e) run `flutter test` on the shards
  and exit non-zero if anything fails, (f) remove generated shards.
- Failures must stay attributable: package:test names are ns-qualified so a failure shows its test; confirm
  by running with one deliberately failing test (then remove it) and showing the output.
- Isolation: tests of different namespaces now share an isolate per shard. Report any test that behaves
  differently aggregated than alone.
- Dart default, all and slow lanes all go through this runner (slow = only the slow namespaces' files).

## Constraints

- Allowed files: bb.edn, a new script under src/dev/, docs/agents/build-n-test.md, .gitignore if needed.
  Do NOT edit test files, src/cljc/**, deps.edn or shadow-cljs.edn without asking (state why if you must).
  Do not commit, stage, or touch collab/. Preserve the uncommitted work already in the tree.
- ASCII, lines <= 80 columns where practical in the script; kondo 0 errors; cljstyle clean on changed files.
- Only one CLJD runner at a time repo-wide; the orchestrator will not run Dart while you do.
- Run EVERY lane in the FOREGROUND and finish the round in this one turn; no background processes. If a command
  may exceed the 10-minute cap, chunk it and run chunks sequentially.

## Verification you must run and report (exact commands, times, counts)

1. `bb test:cljd` (new default path): all 2,673 tests pass, with the 11 SKIP notices; wall time vs 488 s.
2. `bb test:slow:cljd` and `bb test:slow:cljs`: only the slow namespaces run (report which and their counts);
   the guarded bodies actually execute (assertion counts > 0, no SKIP lines).
3. `bb test:all:cljs` assertion count equals the previous full Node lane (92053) and `bb test:cljs` = 91632.
4. JVM: `bb test:clj` count 2900 minus the removed JFR test (report the new number), `bb test:all:clj` includes
   the ^:slow tests (report count; this lane takes ~20+ min, so run `-i :slow` on ONE small slow namespace
   instead and show it selects the tagged test; say plainly that the full unfiltered JVM lane was not run).
5. `bb tasks` lists the new tasks with accurate docs.
The orchestrator re-runs the lanes independently; your numbers are untrusted until then.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact Session-ID>

Then report changed files, exact outcomes with counts and times, deviations from this brief, any behavior
that differs aggregated vs alone, and anything unfinished. Do not claim edits or runs that did not occur.
