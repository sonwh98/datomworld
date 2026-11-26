# Quick rebalance r2 — semantic gate passed and PHP quick-var split

## Completed r3 decision

Tested base `1dc17314241274e8ef9006f2e7762bdc1b3b9f77`, branch `fix/quick-rebalance-r2`, host jupiter. The r3 decision replaces the raw-byte gate described below with semantic disposition equality. Both arms completed: aggregate alone rc=0, 1 test / 3 assertions; PHPT alone rc=0, 1 test / 6 assertions; paired aggregate followed by PHPT in one JVM rc=0, 2 tests / 9 assertions. All have zero failures and errors. Separate instrumented split-model walls were 1701.43 s for aggregate (previous r2 run) and 755.35 s for PHPT (r3 run); both under concurrent load, not an end-to-end quick-tier forecast. Paired timings are not used as split-model measurements.

The aggregate assertions compared all 518 programs against the pinned oracle in both arms. The PHPT manifest validated the exact gap IDs and refusal count in both; an additional full-result capture compared each of the 271 entries in order by source hash and its actual pass/unsupported/gap classification. Both arms produced 119/48/104 and zero row differences. The two `:threw` rows matched source hash, exception class, reason and message: `-1338664852`, `clojure.lang.ExceptionInfo`, `:wrong-type`, `data primitive refused`; `-1289510589`, `clojure.lang.ExceptionInfo`, `:wrong-type`, `integer primitive refused`. Comparison extracts these semantic fields from the rendered exception, so unrelated session-map order, identity and UUID tokens do not affect it. The comparator self-check on identical input passed. Raw capture SHA-256, recorded only as provenance: separate aggregate `09190a6a6492de729e8d45e0041ae85f4f9e39e47cfdfd950f31065af1662415`; separate PHPT `62d7fea38f67df47102d6a088aa2a7eb45be5f436a474a32e25d3c3154a931a0`; paired `32830c3681d78b08b1fa38cf66c4022e30055e6976f998fc27adfdb8166d4236`. Captures, comparison script, logs and timings are in `target/r2-ab/` on this worktree. A second paired run using the earlier wrapper also completed rc=0, 2 tests / 9 assertions, 119/48/104.

The authorized split is now implemented in `src/dev/quick_sharded.clj` only: aggregate 1 var, PHPT 1 var, remainder 21 vars; the other 28 PHP namespaces stay together. List mode rc=0 with the split disabled and enabled: identical 369-namespace sets, 369 covered, no omissions, extras or duplicates; all 23 selected PHP vars assigned exactly once; all 16 non-PHP shard commands byte-identical. The planner tests rc=0, 9 tests / 141 assertions, zero failures/errors; clj-kondo rc=0, zero errors/warnings; `git diff --check` rc=0. No test file or other namespace was edited. Full quick-tier wall was not measured and no merge was performed.

## Historical r2 stopping point


Host: jupiter. Tested base HEAD: `12924b2b5f58e291d6ee8145a90a4204cc9a26b2` (it follows origin/master `dc0c803c7d3da23cca2e1c15996237a5d8233f83`). Branch: `fix/quick-rebalance-r2`.

**No scheduler change is included.** I drafted the authorized PHP var split and ran the scoped checks, but stopped the combined A/B run after observing non-identical raw thrown payloads. The difference is not by itself proof of cross-var state: the payload contains process-specific object identities and UUIDs, and unordered maps. It nevertheless fails the specified requirement that thrown payloads match. No conclusion about independence can be drawn from the incomplete paired run. The draft scheduler and test changes were reverted; this is a findings-only handoff.

The original namespace-only task stopped because the 518-program aggregate and PHPT selection are both vars in `yang.php.antlr.e2e-test`. The follow-up decision authorized splitting this one namespace after the specified independence check. At this head the namespace has no `use-fixtures` or `defonce`; its `volatile!` is local to a function and the e2e harness constructs its service atoms inside each run. This inspection does not establish independence of all transitive code.

## Draft balance and list checks

The following describes the **reverted draft**, not an active plan. It discovered quick vars in a fresh test-classpath JVM with `:test` and `not :slow` metadata, checked exact duplicate-free var coverage, and retained `-e :slow` in each command. An environment setting disabled it or selected the only authorized namespace; other namespaces remained whole.

| Scheduling unit | Before | Draft after | Observed wall |
|---|---:|---:|---|
| `yang.php` whole subsystem | 29 namespaces | 28 namespaces | Before: 8008.770 s on nightly 20261123-2124; draft after: not measured |
| PHP e2e aggregate | included above | 1 namespace, 1 var, 518 programs | 1701.43 s, instrumented fresh JVM under concurrent load |
| PHP e2e PHPT selection | included above | 1 namespace, 1 var, 271 entries | 747.81 s, instrumented fresh JVM under concurrent load |
| PHP e2e remaining | included above | 1 namespace, 21 vars | not measured |

Original and draft-after list mode rc=0: exact namespace-set equality, 369 namespaces; selected=369, covered=369, uncovered=0, unexpected=0, duplicates=0. The draft assigned all 23 PHP quick vars exactly once. Against its split-disabled list, all 16 non-PHP shard commands were byte-identical; the 28 remaining PHP namespaces retained their original command order. Artifacts: `target/r2-before-list.log`, `target/r2-disabled-list.log`, `target/r2-after-final.log` and their generated coverage directories. Draft list-mode discovery required generated ANTLR artifacts.

Nightly 20261123-2124 at `0d378482933fa8cf73a2d12f144a04052176ccb8`: whole quick JVM wall 8012.943 s; PHP rc=0, Ran 188 tests containing 5066 assertions, 0 failures, 0 errors; Python 1410.874 s; yin.repl 1052.257 s. `unmapped` failed at 279.530 s before reaching LaTTe, without a final summary. A prior independent LaTTe run on a different revision was 888.28 s (rc=0, Ran 37 tests containing 2608 assertions, 0 failures, 0 errors). Neither is a completed comparable `unmapped` wall; there is no measured post-split critical path.

## A/B evidence and stopping point

The first three uninstrumented runs were interrupted, rc=130, before a Ran line, to capture caught engine exception payloads. Their walls were not retained and they are not A/B evidence.

The instrumented A/B used the same Cognitect runner and `-e :slow`, `-n`, and `-v` selectors. A temporary wrapper delegated `engine-result` unchanged and recorded source hashes with returned `:threw` and `:uncaught` payloads. The three JVMs ran concurrently; walls are contention-affected. The paired JVM executed the aggregate first and then PHPT.

- Aggregate alone: rc=0, wall=1701.43 s; Ran 1 tests containing 3 assertions; 0 failures, 0 errors.
- PHPT alone: rc=0, wall=747.81 s; Ran 1 tests containing 6 assertions; 0 failures, 0 errors. Its 271 entries reported 119 passes, 48 unsupported and 104 recorded gaps, checked against the pinned manifest.
- Both together: interrupted rc=130 after 2068.09 s during PHPT; no Ran line. It cannot establish paired dispositions.
- For source hash `-1338664852`, both captured `:threw` strings begin with `clojure.lang.ExceptionInfo: data primitive refused` and report `float-value`, `:wrong-type`, argument 0. The separate and paired raw records differ: 3,761,792 versus 3,761,776 bytes, SHA-256 respectively `f105e4881cf6b99088bd7e56743016877bb4a634ec0e5fe38aa128d35d040e29` and `9266766f4c8c262c59f5ee31138bfa6c7685212fb4c0bbf794049e0f15918660`. Those digests are of the UTF-8 record lines without their trailing newlines. The payload includes an internal session snapshot with object identities, UUIDs and maps whose printed order varies. Replacing obvious identity/UUID tokens still leaves map-order differences; no canonicalized or semantic equivalence is claimed.

Logs, timing files, wrapper and payload records are under `target/r2-ab/` on this worktree. A decision on an appropriate comparable payload representation and a completed A/B run are needed before the stated gate can pass; the observed mismatch does not establish a shared mutable variable or cross-var state.

Draft verification before revert: `bb -cp src/dev -m quick-sharded-test`, rc=0, Ran 10 tests containing 150 assertions, 0 failures, 0 errors; `clojure -M:kondo --lint src/dev/quick_sharded.clj src/dev/quick_sharded_test.clj`, rc=0, 0 errors, 0 warnings; `git diff --check`, rc=0. No full quick gate or master merge was run.
