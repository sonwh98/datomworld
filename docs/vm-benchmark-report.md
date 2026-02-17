# VM Benchmark Report

## Scope

This report compares VM performance before and after applying the stashed VM refactor.

Benchmark runner: `src/dev/yin/vm/impl_bench.clj`
Mode: `full`
Config: `{:ast-iters 1500, :vm-iters 400, :semantic-vm-iters 150, :leaf-count 1024}`

Data files:
- Pre-pop baseline: `target/benchmarks/current-pre-pop.edn`
- Post-pop refactor: `target/benchmarks/current-post-pop.edn`

## Results (ns/op, lower is better)

| Benchmark | Pre-pop ns/op | Post-pop ns/op | Speedup | Change |
|---|---:|---:|---:|---:|
| `:ast->datoms` | 4,689,545.22 | 1,501,530.53 | 3.12x | -67.98% |
| `:register/eval` | 9,976,694.79 | 6,958,868.33 | 1.43x | -30.25% |
| `:register/run-loaded` | 1,119,483.54 | 1,209,486.25 | 0.93x | +8.04% |
| `:stack/eval` | 9,299,685.63 | 4,955,307.71 | 1.88x | -46.72% |
| `:stack/run-loaded` | 1,748,226.98 | 742,191.46 | 2.36x | -57.55% |
| `:semantic/eval` | 651,577,874.17 | 4,626,186.11 | 140.85x | -99.29% |
| `:semantic/run-loaded` | 494,917,671.94 | 3,001,347.50 | 164.90x | -99.39% |
| `:ast-walker/eval` | 7,621,123.33 | 8,021,845.10 | 0.95x | +5.26% |

## Summary

- Biggest gains are in semantic VM execution, consistent with replacing full datom scans with entity indexing.
- Significant gains are also visible in stack VM paths, consistent with O(1) `subvec` arg slicing.
- Register VM `eval` improved, while `register/run-loaded` regressed slightly in this benchmark shape.
- AST walker was not a primary optimization target and changed slightly in the negative direction in this run.
