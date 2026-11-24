# Test discovery filter and count reconciliation

Code head: `89f09c0c` (findings committed separately on top). Base: `2fac7a2739fc8c3f2de9397945ab2fd7dea9705f`.

- `affected/index-entry` recognizes only test-path namespaces ending `-test`. `slow-inventory` applies the same rule and can inventory a selected namespace list; it fails if selection is missing from discovery.
- Quick sharding inventories selected quick test vars after prerequisites, fails on missing summaries or a total below the metadata count, and records reconciliation in `summary.edn`.
- Nightly slow children are reconciled against the selected slow manifest, including var-sharded schedules; missing summaries or a deficit fail the lane and print the deficit.
- No test files or production source changed. No full tier was run.

## Verification

- `bb test:quick-sharded --list`: selected=367, covered=367, uncovered=0, unexpected=0, duplicates=0. Compared baseline 369 and new plan files: only `d2-wedge-min-repro` and `d2-wedge-probe2` removed from selected, covered, and `ns-unmapped.txt`; all other shard files byte-identical.
- Synthetic quick lane (`with-redefs` inventory=10; shard results 7 and missing): printed `FAIL: test count reconciliation deficit= 3 expected= 10 executed= 7 missing summaries= ["missing"]` and returned lane exit 1. Quick and slow checker probes also rejected a short summary (9/10, deficit 1) and missing summary (7/10, deficit 3).
- Selected JVM inventory smoke: `dao.data-test` produced 12 quick vars and an empty slow map.
- `bb -cp src/dev -m quick-sharded-test`: 9 tests, 141 assertions, zero failures/errors.
- `clojure -M:kondo --lint` on all four touched driver files: 0 errors, 0 warnings; `git diff --check` clean.
