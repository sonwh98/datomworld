# Quick shard queue and worker count

Implementation head: `25567a096b1fbce1689aec5a287e0594cbcd4400` (this findings commit follows it). Fetched `origin/master` was `afad60813a59bce6a93cb725da4f9b71b6cf6dbe`. The requested `752a35e9` object was absent locally and `git fetch origin 752a35e9` returned rc=128 (remote ref not found); consequently this branch cannot claim to include that requested base. Orchestrator must reconcile this before landing. No merge performed.

The queue follows `nightly/run-pool`: at most N futures claim one ordered item at a time. Default 16: at 2.5 GB peak per JVM that reserves ~40 GB of 62 GB, leaving ~22 GB for OS/GC headroom; at 1.3 cores per compiling JVM it uses ~20.8 of 48 cores. `QUICK_WORKERS` overrides it, `--workers` overrides that. Nightly's full quick invocation now defers to this default/override; its slow pool and subset pool retain `par`. Coverage, commands, var split, reconciliation and return-code rules are unchanged. Weights are full-shard historical wall times, so subset ordering is approximate and unfamiliar shards sort last.

Confirmation receipt: `~/ci/nightly-results/20261125-2114/jvm-quick.log`, 20 completed shard rows. The prompt's illustrative numbers do not match this file. Its `JVM=4630.404s` is elapsed lane wall, not total worker-seconds; summing the 20 shard wall durations gives 16,910.508 worker-seconds. Total including setup was 5,046.933s. Modeled non-preemptive LPT, assigning each next logged weight to the least-loaded worker, gives **4,626.262s at 12 and 4,626.262s at 16**: the largest shard dominates both, with fixed durations and no contention modeled. This is not a measured prediction of the next run.

Before list order: dao.base, dao.data, dao.gui, dao.host, dao.jing, dao.postgraphics, dao.space, dao.stream, demo, unmapped, yang.base, yang.php, yang.php-e2e-aggregate, yang.php-e2e-phpt, yang.php-e2e-rest, yang.python, yang.typescript, yin.repl, yin.vm, yin.vm.ucf.

Before execution order (static chains drawn from this order): yang.php-e2e-aggregate, yang.php-e2e-phpt, yang.php-e2e-rest, yin.vm, dao.stream, yang.python, yang.php, yin.repl, yin.vm.ucf, yang.typescript, dao.space, unmapped, dao.jing, dao.gui, yang.base, dao.data, demo, dao.host, dao.postgraphics, dao.base.

After list and pool order, with receipt seconds: yang.php 4626.262; unmapped 3748.143; yang.php-e2e-aggregate 1892.194; yang.python 1543.029; yang.php-e2e-rest 1054.150; yin.repl 1035.740; yang.php-e2e-phpt 843.901; yang.base 738.468; yin.vm 614.653; yang.typescript 469.322; yin.vm.ucf 122.386; dao.space 114.139; dao.host 29.773; dao.stream 27.862; dao.jing 12.110; dao.data 10.273; demo 8.942; dao.base 8.319; dao.gui 6.713; dao.postgraphics 4.129.

## Scoped verification and return codes

- `git fetch origin master`: rc=0. `git fetch origin 752a35e9`: rc=128, remote ref not found.
- First baseline and changed full `--list`: each rc=1, PHP inventory could not find generated `PhpLexer` in fresh checkout. `bb gen:antlr`: rc=0. Repeated baseline and changed `--list`: each rc=0, 20 commands each; exact command multiset equality true, zero missing or added. Coverage 367/367, no uncovered, unexpected or duplicates.
- `bb -cp src/dev -m quick-sharded-test`: rc=0, 9 tests, 141 assertions, 0 failures, 0 errors; bounded-worker test included.
- `QUICK_WORKERS=18` parsed as 18, explicit `--workers 4` parsed as 4, rc=0; invalid `QUICK_WORKERS=0` rejected rc=1 as intended.
- Real subset `--workers 4 --changed test/dao/await_test.cljc test/dao/data_test.cljc test/dao/gui/compiler_test.clj test/dao/host/bytes_test.cljc test/dao/jing_test.cljc test/dao/postgraphics/math_test.cljc`: rc=0, seven selected namespaces in six shards. At 10:43:35 UTC process sampling showed four simultaneous direct Java children of planner PID 3926200: 3928260, 3928261, 3928264, 3928272. Per-shard exits all 0: dao.host 6 tests/98 assertions, dao.data 12/74, dao.jing 53/279, dao.base 17/38, dao.gui 34/113, dao.postgraphics 42/110. Total 164 tests/712 assertions, 0 failures/errors; reconciliation expected=164 actual=164 deficit=0 missing=[] pass=true. JVM wall 10.353s, total 22.429s. Output `target/profile/quick-sharded-1795689801732-19cd73fb/summary.edn`.
- `clj-kondo` binary directly: rc=127 (not on PATH); `clojure -M:kondo --lint src/dev/quick_sharded.clj src/dev/nightly.clj bb.edn`: rc=0, 0 errors, 0 warnings.
- `git diff --check`: rc=0.
