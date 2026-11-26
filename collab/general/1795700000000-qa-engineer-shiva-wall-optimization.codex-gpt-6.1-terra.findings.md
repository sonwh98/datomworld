# Shiva wall optimization — partial findings

Base HEAD: `83951d27` (branch `fix/shiva-wall-optimization`). Evidence run: `0d378482933fa8cf73a2d12f144a04052176ccb8`. Receipts: `/home/sto/ci/datomworld-nightly-{node,dart}/target-ci/{node,dart}-shards-20261123-2121/` and `/home/sto/ci/nightly-results/20261123-2121/summary.txt`. No rerun was used for the decomposition. This is an incomplete implementation; countersign must not treat it as the requested completed optimization.

## Wall and critical path

The two lanes overlap: node 45,428s, rc=143; dart 35,756s, rc=1. Node job scheduling began approximately 21:28:50 local on Nov 23; initial setup is about 460s, including peer build ~358s and compile 36s. The last Node job, `uncovered`, was terminated while running; there is no completion row or duration, so 45,428s is a censored lane wall, not its natural completion time. Its log already contained six `yang.rt.effect-test` failures. Node `yin.vm` completed in 31,901s (rc=0), `yang.python` 25,244s (rc=1), and `yin.repl` 11,303s (rc=1). These are concurrent, not additive. After `yin.vm` finished, `uncovered` was the sole recorded remaining job, until termination; individual slot idle histories before that cannot be established exactly from these records.

Dart compile 499s, Flutter 34,873s (rc=1). The `uncovered/shard_3` corpus test started at Flutter 5,547s and its last slow var ended at 34,613s; the other tests' last reported activity was 8,077s. Thus the late corpus dominates the Dart tail. The five largest observed concurrent contributors are: Node `uncovered` > approximately 44,900s (censored, not a completion); Node `yin.vm` 31,901s; Dart corpus 29,066s; Node `yang.python` 25,244s; Node `yin.repl` 11,303s. The first estimate subtracts about 460s setup from the lane wall and should not be used as a measured completed job duration.

Dart corpus transition intervals (seconds): t3-bundled 2,995 (includes initial load), t3-linked 909, t1-bundled 7,691, t2-linked 2,501, t2-bundled 6,788, t1-linked 8,182; small tests are interspersed. These are sequential transition intervals, not independent isolated benchmarks.

## Gantt evidence

Node offsets below are seconds from first observed job-log creation. File creation time is a proxy for process start, `runs.txt` is authoritative for durations, and offset + duration approximates completion to within timestamp/rounding overhead. Interrupted `uncovered` has no completion row. Parallelism was 12; overlaps and simultaneous completions prevent a precise per-worker assignment without invented data.

| job | start offset | end offset | duration | rc |
|---|---:|---:|---:|---:|
| dao.base | 0 | 32 | 32 | 0 |
| dao.data | 0 | 45 | 45 | 0 |
| dao.gui | 0 | 36 | 36 | 0 |
| dao.jing | 0 | 50 | 50 | 0 |
| dao.postgraphics | 0 | 31 | 31 | 0 |
| dao.space | 0 | 1840 | 1840 | 0 |
| dao.stream | 0 | 138 | 138 | 0 |
| uncovered | 0 | censored | unknown | interrupted |
| yang.base | 0 | 5927 | 5927 | 0 |
| yang.python | 0 | 25244 | 25244 | 1 |
| yin.vm | 0 | 31901 | 31901 | 0 |
| yin.vm.ucf | 0 | 1504 | 1504 | 0 |
| demo | 32 | 69 | 37 | 0 |
| yin.repl | 33 | 11336 | 11303 | 1 |

Dart table: first and last reporter mentions, in seconds from Flutter's displayed clock. These are **not** definitive isolate starts or ends: Flutter reports repeated progress for active tests, may omit a file entirely, and records no worker identity. The last mention can include failure details. Therefore a complete per-job Gantt and exact idle-slot graph cannot be reconstructed from this log alone. There are 108 emitted shard files; only the following files have timestamped path-bearing progress lines under this parser.

| shard | first mention | last mention |
|---|---:|---:|
| yang.python/shard_1_test.dart | 0 | 1607 |
| yang.python/shard_4_test.dart | 180 | 186 |
| yin.repl/shard_2_test.dart | 222 | 275 |
| yang.python/shard_2_test.dart | 665 | 7173 |
| yin.repl/shard_3_test.dart | 711 | 711 |
| yin.repl/shard_7_test.dart | 741 | 7779 |
| yin.vm/shard_1_test.dart | 1007 | 2816 |
| yang.python/shard_6_test.dart | 1501 | 1501 |
| yang.python/shard_7_test.dart | 1628 | 5646 |
| yin.vm/shard_5_test.dart | 2618 | 5494 |
| yang.python/shard_5_test.dart | 2959 | 5566 |
| yin.vm.ucf/shard_2_test.dart | 3517 | 3900 |
| dao.jing/shard_3_test.dart | 3951 | 3951 |
| dao.jing/shard_7_test.dart | 3973 | 3976 |
| dao.gui/shard_1_test.dart | 4067 | 4067 |
| uncovered/shard_3_test.dart | 5547 | 34662 |
| yang.base/shard_0_test.dart | 6103 | 6264 |
| yang.base/shard_1_test.dart | 6266 | 8077 |

## Changes and remaining work

- **Dart ordering implemented, not runtime-verified:** `dart_shard.clj` enumerates emitted files, places the file importing corpus first, then prioritizes `yin.vm`, `yang.python`, and `yin.repl`, and passes files to one Flutter invocation. It writes `schedule.txt`. It uses coarse historical subsystem priorities, not per-file measured weights. The projected idealized Dart lane with unchanged corpus duration and immediate start is approximately 30,200s (35,756 minus 5,547); Flutter scheduling, startup contention and compile cost could alter this. This is a projection, not a measurement.
- **Per-var Dart wrappers not implemented:** generated corpus Dart exposes test registration functions, but splitting requires proving generated registrations and fixtures are exactly preserved and validating isolated execution. No wrapper was compiled or run. Cross-review's 9,000–10,000s projection remains a hypothesis, especially under changed CPU contention.
- **Node registry and group splits not implemented:** canonical registry adds `dao.host`, `yin.shell`, `yang.typescript`, `yang.php` etc., moving namespaces out of the old `uncovered` membership, which conflicts with the stated stop condition. Per-namespace Node durations for the old monoliths are not recorded, so measured Node sub-group weights cannot be honestly supplied. A split also needs the requested green scoped run and inventory proof before landing. Node remains the overall critical lane; Dart ordering alone cannot make the combined wall 12.6h shorter.
- **Residual weights not refreshed:** this run predates per-namespace residual jobs and offers no per-job measurements. Refresh from a subsequent `runs.txt`, retaining explicit unknown estimates for missing/censored jobs.

## Verification

- `bb` loaded the changed driver: rc=0.
- A disposable two-file Flutter project, `flutter test --reporter expanded --concurrency 1 test/z_test.dart test/a_test.dart`, returned rc=0 and ran `z-first` before `a-second`. This checks explicit argument order at concurrency 1; it does not measure or guarantee startup order under nightly contention.
- Replay of old emitted directory inventory through `ordered-shard-files`: rc=0; 108 before / 108 after, set equal, no duplicates, corpus file first. Since emitted contents are unmodified, this proves file ordering preserves those files, not a fresh namespace/var inventory comparison.
- `clojure -M:kondo --lint src/dev/dart_shard.clj`: rc=0, 0 errors, 0 warnings. An initial lint returned rc=2 due to two pre-existing warnings; both were corrected.
- `git diff --check`: rc=0.
- Node schedule equality, a new Node split green run, Dart per-var inventory/compilation and a wrapper run: **not performed**. No newly measured wall, no claim of a green lane.

For full reconstruction in future, emit per-job start/end timestamps, rc and worker identity as structured records; Flutter's expanded reporter is insufficient for full isolate occupancy. Verify startup order under nightly concurrency before relying on the projection.
