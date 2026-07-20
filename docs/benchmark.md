### Performance Results Comparison (n = 50 and n = 5000)

| Metric | Register VM | CESK-space (before) | CESK-space (after) |
|---|---|---|---|
| **Mean time per run (n = 50)** | 544.7 us (Criterium) | 20,126.5 ms | **33.3 ms** (~604x speedup) |
| **Mean time per run (n = 5000)** | 28.98 ms (Criterium) | DNF (est. 10+ hours) | **747.2 ms** |
| **Scaling** | Linear O(N) | Superlinear O(N²) | **Linear O(N)** |

After optimization, CESK-space is ~26x slower than the Register VM at $n=5000$ (747 ms vs 29 ms). This residual is execution/interpretation overhead plus persistent-map churn; only projection (step 6 of the cesk-space-optimization plan) removes it.

### Summary of Changes

1. **Incremental Read Cache:** Created a derived map cache `{eid {attr value}}` in the machine cursor `st`. Core read queries now use map-based lookup for $O(1)$ amortized lookups instead of invoking full index folds via `q/pull`.
2. **Environment Chain Walk Optimization:** Walk parent frames via `:env-cache` (populated dynamically from binding events in `sync-st-cache`), transforming environment lookup from a heavy datalog query per frame into simple map hops.
3. **Optional Step Telemetry:** Added a `:trace?` option (defaulting to `true` for test compatibility) to `inject` and `run` to disable telemetry attributes (`:cfg/prev`, `:cell/set-by`, and `:frame/created-by`) and speed up hot-loop benchmarks.
4. **Benchmark & Documentation Updates:**
    * Modified `bytecode_bench.clj` to pass `{:trace? false}` during the benchmark runs.
    * Updated status and metrics in `cesk-space-optimization.md`.
5. **Validation:** Successfully linted with clj-kondo (0 errors, 0 warnings) and passed all 790 tests in the test suite.
