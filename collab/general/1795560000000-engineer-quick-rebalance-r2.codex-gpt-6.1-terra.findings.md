# Quick rebalance r2 — blocked on namespace boundary

Host: jupiter. Base HEAD: `dc0c803c7d3da23cca2e1c15996237a5d8233f83` (origin/master at fetch). No scheduler change has been made.

The requested separate groups for the 518-program aggregate and 271 PHPT entries conflict with the namespace-preservation requirement. Both `permutation-corpus-quick-test` (518 = 2 × (169 + 40 + 25 + 25)) and `phpt-selection-runs-test` are defined in `test/yang/php/antlr/e2e_test.clj`, namespace `yang.php.antlr.e2e-test`. They cannot run in separate fresh-JVM namespace groups without changing the existing namespace boundary or scheduling individual vars. Both options change the required same-process lifetime for that namespace. A decision is needed before implementing that split.

## Baseline

`bb test:quick-sharded --list` rc=0: selected=369, covered=369, uncovered=0, unexpected=0, duplicates=0. Artifacts: `target/profile/quick-sharded-1795531695653-f04a4105/`. Before counts: dao.base 4, dao.data 10, dao.gui 14, dao.host 6, dao.jing 15, dao.postgraphics 5, dao.space 19, dao.stream 41, demo 9, unmapped 21, yang.base 11, yang.php 29, yang.python 31, yang.typescript 20, yin.repl 28, yin.vm 78, yin.vm.ucf 28. No after plan exists yet.

Nightly 20261123-2124 at `0d378482933fa8cf73a2d12f144a04052176ccb8`: PHP 29 namespaces, 8008.770 s, rc=0, 188 tests, 5066 assertions, 0 failures, 0 errors; entire JVM wall 8012.943 s. Python 1410.874 s; yin.repl 1052.257 s; yang.base 754.029 s; yin.vm 644.061 s. `unmapped` failed at 279.530 s with no test summary, and its log ends in `d2-wedge-probe2`, before LaTTe; this is not a completed residual measurement. Prior independent LaTTe run on a different revision measured 888.28 s, rc=0, 37 tests, 2608 assertions, 0 failures/errors (see `1795470000000-engineer-test-runtime-attribution.codex-gpt-6.1-terra.findings.md`). These runs have different revisions and contention; do not add them as a measured wall forecast.

`bb gen:antlr` rc=0. Two separate PHP namespace runs, after-plan equality, planner tests, and lint remain unexecuted; no separate namespace exists for the two identified workloads. No merge performed.
