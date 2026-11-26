# Test runtime attribution — empirical consult

Host jupiter; detached `/tmp/perf-attribution` at `origin/master` 48a2f9e298b545c24d626fb99e1c37f154d0e15a. Nightly evidence is older `e4f2cb89` and is not a measurement of this checkout. The owner-provided nightly was red: uncovered rc=1 (3 failures in dao.host.gate), Python rc=1. Tests use `-e :slow`; slow numbers below use `-i :slow` and are a distinct workload. The current nightly source uses `bb test:quick-sharded`, not the old shell script used for this run. Do not apply the old 12,419 s prediction directly to today's plan.

## Evidence and ranked opportunities

The original artifacts are `/home/sto/ci/nightly-results/20261123-0018-jvm-shards-dir/{summary.txt,ns-uncovered.txt,ns-yang.python.txt,ns-yin.repl.txt,uncovered.log}` and `/home/sto/ci/nightly-results/20261123-0018-slow-runs.txt`. They give lane wall times, **not namespace or test timestamps**. The 74-namespace residual is not a single test, and the logs do not prove which portion of 12,419 s belongs to any particular test. Do not add per-namespace measurements from a different revision to recreate that total.

| Rank / lane or namespace | Seconds, evidence | Attributed cause / fix candidate | Expected saving |
|---|---:|---|---|
| 1. old uncovered | 12,419 wall; 74 assigned namespaces, 489 tests, 14,087 assertions; rc=1 | `/home/sto/ci/shard-jvm.sh` enumerated only 13 named subsystems, then `comm -23` put everything else into one serial JVM. PHP, TypeScript, host, star and call all ended up together. `src/dev/quick_sharded.clj` now assigns via registry including PHP, TS and host; residual is `unmapped`. Preserve coverage/duplicate checks and schedule work by observed timings, splitting only independent work. | Old-run upper bound 12,419 s minus longest new critical path and overhead; no defensible numerical forecast without per-ns timing. Re-sharding changes wall, not CPU or test semantics. Current improvement not yet measured. |
| 2. slow `yang.php.antlr.include-test` / `yang.typescript.antlr.corpus-test` | 9,130 / 8,959 wall, rc=0 each, original slow runs | Both exceed all but old residual; their original slow-per-namespace logs are available. Profile the rows before tuning; schedule both early on separate workers. | Unknown CPU savings; with sufficient independent workers their overlap can avoid up to 8,959 s of serial makespan, subject to other critical paths and contention. Do not add this to quick savings. |
| 3. `yang.php.antlr.e2e-test` (old uncovered) | >905.95 s; deliberately stopped, rc=143, no test summary | Two main-thread snapshots in canonical CBOR / bytecode-to-AST, first within `stage0-enabled-fixtures-on-the-walker-test`; `run-raw` derives/project/reconstructs for each source (`test/yang/php/antlr/e2e_harness.clj:280`). PHPT selection has 1,088 entries; exactly 271 survive its `:needs`/`:slow` filter on this checkout; preserve corpus and oracle coverage. Measure per-row compilation/derivation and share only immutable artifacts where valid. | Not quantified; 271 is the selected row count, not a measured time or completed-row count. |
| 4. `yang.latte-oracle-test` (old uncovered) | 888.28 s, rc=0, 37 tests/2,608 assertions current checkout; historical prior run 2,222 s at different revision/host and failed one assertion (`orchestrator-logs/1793385006-zcode-glm-5.3-flash-yang-clojure-track.md:387`) | Sampled current JVM main thread twice RUNNABLE in CBOR content-hash / AST to semantic bytecode; first sample in `test-oracle-slice8-definition-kinds-and-delta`, not a sleep or process startup. `kernel-gate-evidence` runs each fixture through A, B, walker, semantic and compiles it (latte_oracle_test.clj:3062,3393); preserve independent admission/identity and outcomes. Profile fixture timings and reuse immutable compilation only where identity contracts permit. | Not quantified. Two thread dumps are snapshots, not a percentage or proof of the 12,419 s breakdown. |
| 5. `yin.repl.main-test` quick | 116.94 wall, 189.40 user, 10.03 sys, 43 tests/353 assertions pass; one Dart peer case skipped | Single snapshot at 52 s in `the-interactive-composition-holds-every-standard-language`, `transcript` -> semantic bytecode -> CBOR content hash. Namespace also uses an `:once` fixture for process A and contains real child-peer processes and polling (`main_test.cljc:834,880`). This snapshot measures neither process startup nor fixture share. Measure fixture and individual runs separately before reuse. | Unknown; preserve cross-process behavior and avoid silently omitting Dart peer. |
| 6. `yang.python` quick | nightly entire shard 1,468 wall, 287 tests, 3,079 assertions, rc=1; current `yang.python.antlr.e2e-test` alone 15.28 wall, 2 tests/61 assertions pass | This namespace's many other vars are `^:slow`; its 15 s does not explain shard. Prior audit measured repeated compilation/admission of whole prelude, rather than re-lowering prelude, at older revision; target remaining quick namespaces with timing first. | Unknown current shard saving. Historic estimates below are per-run and cannot be scaled to today's shard without counts. |
| 7. `yin.repl.python-antlr-test` quick | 92.61 wall; 11 tests/80 assertions, 2 errors due Python profile revision-pin mismatch | Current master has different profile state from nightly, and early errors change work performed. Fix artifact pin separately; rerun profiling only after pin is valid. | Unknown. |
| 8. JVM fixed costs | minimal `dao.host.bytes-test` quick 3.45 wall with 6 tests/98 assertions, not a pure startup measurement; fresh `bb gen:antlr` 11.21 wall once | JVM startup, dependency loading, namespace load/compile, and test body are not individually separated; minimal test gives an observed combined floor on this warm cache, not 15–30 s proof. Slow runs do launch a JVM per namespace (`src/dev/nightly.clj:231–235`); pool carefully, retain isolation where tests depend on process state. | At most the actually eliminated duplicate startup/load cost; 3.45 s is not a universal per-lane bound. |

On this checkout, user/sys CPU can exceed wall because JVM compilation and GC use threads; it cannot be read as a direct split between fixture and assertions. Two point-in-time thread dumps only identify code active at those instants. Sleeps/polling and subprocesses are present in REPL sources; no elapsed attribution to them is established. The old nightly ran 12 JVM shards concurrently and current samples ran at most four, so wall values are not apples-to-apples. Fresh tree generated ANTLR (11.21 s) but no Dart peer; errors, skips, changed revision and different contention all limit comparisons. No full suite and no `yang.call.exception-contract-test` were run.

## Prior audit and applied work

`collab/yang-python/1791201600000-engineer-python-test-cost-investigation.findings.md` measured 2026-10-05 at baa3796a: warm bundled Python print(1) about 1.437 s / four JVMs, mostly whole-image resolve, compile and admission; register accounted for about 60%; in a hooked case whole-tree projection and derivation were also substantial. Prototype one shared resolve plus safepoint derived AST caching showed roughly 5–8% fast-form improvement, with meaningful differences across runs. Review: `collab/general/1791209400000-reviewer-test-cost-fixes-gate.gpt-6.1-sol.findings.md`.

Applied and verified by source inspection: per-body liveness arrays (`src/cljc/yin/vm/debruijn_register_code.cljc:339`); Dart crypto SHA (`src/cljc/dao/host/hash.cljc:47`); byte-table hex (`src/cljc/yin/vm/debruijn_code.cljc:158`); memoized derived AST in `test/yang/python/antlr/safepoint_test.cljc:454`. The old `resolved-of` symbol no longer exists; the current e2e harness has since been restructured and we did not establish whether equivalent shared resolution survives. Linked prelude is available as an explicit mode in `test/yang/python/antlr/e2e_test.clj:139`, but its existence is not evidence that all tests use it or that caching admitted images is safe.

## Original partition membership

The shell script collects each named subsystem via `bb test:sub:list`, constructs the set of every `*_test.clj`/`*_test.cljc` under `test/`, and runs sorted set difference in one JVM. The namespace membership is the original run's recorded list, not an inference from current registry. `-e :slow` excludes slow vars; a namespace can be present in the file while having no runnable quick vars, as for `yang.call.exception-contract-test`. Full original other shard lists follow the residual list for reproducibility.

### uncovered (74 namespaces)

```text
dao.host.bytes-test
dao.host.conformance-test
dao.host.float-test
dao.host.gate-test
dao.host.hash-test
dao.host.text-test
dao.star.driver-test
dao.star.facts-test
dao.star.loopback-test
dao.star.peer-close-test
dao.star.r2-test
dao.star.r3-test
dao.star.r4-test
dao.star.r5-test
dao.star.router-test
dao.star.signals-test
yang.call.conformance-test
yang.call.depth-probe-test
yang.call.exception-contract-test
yang.call.exception-source-test
yang.call.php-exception-test
yang.call.python-exception-test
yang.cross-lang-test
yang.latte-oracle-test
yang.php.antlr.builtin-surfaces-test
yang.php.antlr.class-model-test
yang.php.antlr.cst-test
yang.php.antlr.e2e-test
yang.php.antlr.eval-admission-test
yang.php.antlr.eval-bridge-test
yang.php.antlr.eval-coordinator-test
yang.php.antlr.eval-emitter-test
yang.php.antlr.eval-publication-test
yang.php.antlr.eval-scope-cleanup-test
yang.php.antlr.eval-service-test
yang.php.antlr.eval-transport-test
yang.php.antlr.exceptions-test
yang.php.antlr.finalization-test
yang.php.antlr.generators-test
yang.php.antlr.include-test
yang.php.antlr.lower-portable-test
yang.php.antlr.objects-test
yang.php.antlr.p5b2-lifecycle-test
yang.php.antlr.p8-corpus-test
yang.php.antlr.prelude-test
yang.php.antlr.request-context-test
yang.php.antlr.safepoint-test
yang.php.antlr.scope-test
yang.php.antlr.siteset-test
yang.php.antlr.ticks-test
yang.php.antlr.zz-probe2-test
yang.php.antlr.zz-probe3-test
yang.php.antlr.zz-probe-test
yang.rt.effect-test
yang.typescript.antlr.class-prelude-test
yang.typescript.antlr.corpus-test
yang.typescript.antlr.cst-test
yang.typescript.antlr.e2e-test
yang.typescript.antlr.float-address-test
yang.typescript.antlr.global-write-test
yang.typescript.antlr.linked-prelude-test
yang.typescript.antlr.lower-portable-test
yang.typescript.antlr.lower-test
yang.typescript.antlr.object-e2e-test
yang.typescript.antlr.object-parity-test
yang.typescript.antlr.prelude-module-test
yang.typescript.antlr.prelude-parity-test
yang.typescript.antlr.prelude-test
yang.typescript.antlr.safepoint-test
yang.typescript.antlr.scope-test
yang.typescript.antlr.source-call-parser-test
yang.typescript.antlr.source-call-test
yang.typescript.antlr.string-forms-test
yang.typescript-callsite-test

```

### yang.python (31 namespaces)

```text
yang.antlr.packet-test
yang.python.antlr.c3-gate-parser-test
yang.python.antlr.c3-gate-test
yang.python.antlr.call-entry-test
yang.python.antlr.cst-export-test
yang.python.antlr.descriptor-profile-test
yang.python.antlr.dht-import-test
yang.python.antlr.e2e-c1-test
yang.python.antlr.e2e-c2-test
yang.python.antlr.e2e-test
yang.python.antlr.exception-call-test
yang.python.antlr.float-address-test
yang.python.antlr.float-text-test
yang.python.antlr.import-test
yang.python.antlr.int-contract-test
yang.python.antlr.int-conv-test
yang.python.antlr.int-heap-test
yang.python.antlr.int-literal-test
yang.python.antlr.int-ops-parser-test
yang.python.antlr.int-ops-test
yang.python.antlr.linked-prelude-test
yang.python.antlr.linked-safepoint-test
yang.python.antlr.lower-portable-test
yang.python.antlr.lower-test
yang.python.antlr.oracle-parity-test
yang.python.antlr.prelude-parity-test
yang.python.antlr.publication-test
yang.python.antlr.safepoint-test
yang.python.antlr.scope-test
yang.python.antlr.siteset-test
yang.python.antlr.source-call-test

```

### yin.repl (28 namespaces)

```text
yin.repl-test
yin.repl.adapter-test
yin.repl.ast-index-test
yin.repl.ast-query-e2e-test
yin.repl.build-test
yin.repl.connect-test
yin.repl.dht-args-test
yin.repl.dht-head-test
yin.repl.dht-process-test
yin.repl.dht-step-test
yin.repl.dht-test
yin.repl.driver-test
yin.repl.embed-test
yin.repl.frontend-catalog-test
yin.repl.host.jvm-test
yin.repl.index-test
yin.repl.main-test
yin.repl.owner-test
yin.repl.php-parser-test
yin.repl.python-antlr-test
yin.repl.query-test
yin.repl.require-test
yin.repl.serve-connect-wire-test
yin.repl.serve-test
yin.repl.shell-session-test
yin.repl.state-test
yin.repl.store-test
yin.repl.trust-test

```

## One-minute sampling of long quick namespaces

At 16:59:26 UTC, while both runs were active, `jcmd <pid> JFR.start name=perf settings=profile duration=60s filename=...` captured JVM execution samples. In the 60 s window, LaTTe had 4,104 main-thread samples (2,695 stack traces truncated), PHP had 4,358 (3,245 truncated). Inclusive counts can overlap and undercount callers due to truncation: LaTTe CBOR 207, walker 569; PHP CBOR 577, walker 575. Top sampled frames also included Clojure `RT`, lazy seq and concat machinery. Samples are neither elapsed-time percentages nor a full CPU profile; they identify additional candidates alongside the point samples, and do not establish that CBOR alone dominates either namespace. Recordings and exported JSON are under `target/perf-attribution/` in this worktree.

A tail observer (100 ms polling, started after the gate had already begun) timestamps printed `KERNEL-FIXTURE` completions without another test invocation. The gate corpus has 155 rows. Consecutive completion intervals in the running current-checkout test: `:h3-2-elab-discharge-states` 22.83 s after preceding row, `:h3-2-have-duplicate` 12.51 s, `:h3-2-declare-invalid` 9.61 s, `:h3-2-have-mismatched-type` 10.91 s, `:h3-2-discharge-incorrect` 10.21 s. These are inter-output wall intervals, not isolated CPU or a representative average of all rows; the first observed row has no preceding timestamp. See `target/perf-attribution/latte-row-times.log` for the full observed tail.

## Commands and measurements

Six scoped invocations total, each `/usr/bin/time` with a 1,800 s timeout; no test ran more than once. Times include JVM startup, class loading and body; `user`/`sys` are aggregate process CPU and can exceed wall. Launches were concurrent as shown; all times UTC.

| Command (`clojure -M:test -e :slow -n …`) | Start | End | Wall s | Result |
|---|---|---|---:|---|
| `yang.python.antlr.e2e-test` | 16:55:54 | 16:56:09 | 15.28 | rc 0, 2/61/0 |
| `yin.repl.main-test` | 16:55:54 | 16:57:51 | 116.94 | rc 0, 43/353/0; Dart peer skipped |
| `yin.repl.python-antlr-test` | 16:56:33 | 16:58:05 | 92.61 | rc 1, 11/80/2 errors (revision pin) |
| `dao.host.bytes-test` | 16:58:05 | 16:58:09 | 3.45 | rc 0, 6/98/0 |
| `yang.latte-oracle-test` | 16:55:54 | 17:10:42 | 888.28 | rc 0, 37/2608/0 |
| `yang.php.antlr.e2e-test` | 16:55:54 | 17:11:00 | 905.95 | rc 143, deliberately stopped after 15 min; incomplete, no summary |

`bb gen:antlr` fresh worktree: 11.21 s wall, 61.47 user, 4.80 sys, rc 0. The one-minute JFR captures used `jcmd 2983301 JFR.start name=perf settings=profile duration=60s filename=...` (0.79 s) and the analogous command for 2983304 (0.57 s). `jfr print --json --events jdk.ExecutionSample` took 1.03 and 1.12 s; Python sample aggregation took 2.00 s. `bb` EDN selection count took 0.11 s; corpus count took 0.03 s. The read-only `jcmd ... Thread.print` batches took 0.8, 0.4 and 0.13 s, respectively. `git worktree add --detach /tmp/perf-attribution origin/master` took 3.7 s. The one-time hostname/artifact search took 2.62 s. All other inspection calls (cat/rg/sed/ls/ps, git status, artifact checks, and writing this file) were each reported by the command tool as at most 0.6 s; explicit 25/30/45 s sleeps were monitoring waits, not test runtimes. The row observer ran concurrently from 17:01:48.556 to 17:10:43.001 UTC (534.45 s). After 15 minutes without a namespace summary, `kill -TERM 2983300` stopped our own PHP timeout process at 17:11:00 (command wall 0.00 s); the test elapsed 905.95 s, and child exit 143 means censored, not failed or passed. A 10 s monitoring sleep preceded this signal. Tool startup dates for the preliminary uninstrumented reads were not recorded, so only their reported elapsed times can be stated.

At 17:06:14 UTC the last of 155 gate rows printed; the namespace continued beyond the gate. The observer saw 102 consecutive rows: the 101 intervals between first and last total 257.43 s. Longest observed interval was `:h3-2-elab-discharge-states` 22.83 s, followed by `:opacity-install-use` 13.92 s and `:h3-2-have-duplicate` 12.51 s. These are completion-to-completion intervals, and the interval includes anything between print statements; they cannot be extrapolated to the other 53 rows. The observer started mid-gate.

At 17:02:07, 17:05:58, and 17:06:50 UTC, PHP main-thread stacks were RUNNABLE in the loop of `fixtures-on-the-walker-test` (e2e_test.clj:47–49), often in the walker scheduler. There is no per-row output, so this is not proof that a particular source input hung or that no row advanced. Its wall share is bounded only by the sample times, not a per-deftest timer. Next audit should instrument fixture IDs/completions and step counts in this loop, using bounded steps without reducing test coverage; compare cold/warm derivation and inspect the slowest program for nontermination. Snapshot commands each took about 0.13–0.14 s.

At 17:07:22 UTC the LaTTE main thread was RUNNABLE inside `test-kernel-proof-fresh-supply` (`latte_oracle_test.clj:2701`), stamping sites during another kernel compilation after the 155-row gate had finished. This is one stack snapshot, not a duration measurement. `jcmd ... Thread.print` wall 0.14 s.

At 17:09:48 UTC a fourth PHP snapshot still showed main in the same `fixtures-on-the-walker-test` invocation, so the test spans at least 461 s from the 17:02:07 snapshot (assuming the runner's ordinary one invocation of that var). This is a lower bound on that test, not its total, and does not identify a fixture. The concurrent LaTTE snapshot had moved to `test-oracle-slice4-loop-anchors` at line 464, demonstrating progress beyond the gate and proof-freshness test. Each `jcmd` took 0.13–0.14 s.
