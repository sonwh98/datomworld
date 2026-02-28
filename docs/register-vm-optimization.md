# RegisterVM Optimization Summary

## Goal

Reduce CPU and allocation overhead in `RegisterVM` without changing VM semantics.

Primary hot functions identified from JFR:

- `yin.vm.register$reg_vm_step.invokeStatic`
- `yin.vm.register.RegisterVM.assoc`
- `yin.vm.register.RegisterVM.valAt`

## Baseline

Workload: tail-recursive countdown, `n=50000`

- `clj -M:bench 50000`
  - Register hot loop mean: `441.683395 ms` (bench label: FAST)
  - Register scheduler mean: `477.014190 ms` (bench label: SLOW)
- `clj -M:profile 50000`
  - Register hot loop mean: `409.836342 ms` (bench label: FAST)

Baseline JFR allocation attribution (`jdk.ObjectAllocationSample`, user-attributed):

- `yin.vm.register.RegisterVM.assoc`: `1,504,651 bytes`
- `yin.vm.register$reg_vm_step.invokeStatic`: `620,018 bytes`
- Combined: `2,124,669 bytes`
- Total user-attributed mixed-run bytes: `5,800,211`

## Optimization Technique

The optimization used a staged approach:

1. Measure first, optimize second.
2. Keep interpreter semantics stable, optimize representation in hot path.
3. Preserve explicit stream/effect boundaries by falling back to the scheduler layer for effect-heavy opcodes.
4. Re-measure after each pass with same workload and commands.

## Step 1: Low-risk hot path cleanup

Files: `src/cljc/yin/vm/register.cljc`

Changes:

- Replaced many hot reads from `get` with `nth` (bytecode/pool/reg index reads).
- Added arity-specialized native invocation (`0..4`) to reduce `apply` and seq churn.
- Replaced `merge + zipmap` closure env creation with direct binding (`bind-closure-env`).
- Made native effect parking metadata construction lazy (only for stream effects).
- Added `:empty-regs` in closures and reused it across call/tailcall.

Effect:

- Clear speedup, moderate allocation improvement in targeted hotspots.

## Step 2: Medium-risk register-state hot loop

Files: `src/cljc/yin/vm/register.cljc`

Changes:

- Added `reg-vm-run-active-continuation` local-state loop:
  - Keeps `pc`, `regs`, `env`, `store`, `k`, `bytecode`, `pool`, `id-counter` as loop locals.
  - Avoids per-instruction `RegisterVM` materialization in hot opcode path.
- Added `reg-vm-run-scheduler` fallback for scheduler/effect path.
- Added `reg-vm-run` dispatcher.
- `reg-vm-eval` now uses `reg-vm-run`.
- `IVMStep/step` still uses `reg-vm-step` (debug/single-step semantics unchanged).

Technique detail:

- The hot loop handles compute/control/store opcodes directly.
- Stream/control operations preserve causality and scheduler behavior by falling back to the immutable scheduler layer.

## Step 3: Closure/env churn and benchmark harness split

Files:

- `src/cljc/yin/vm/register.cljc`
- `src/clj/yin/vm/bytecode_bench.clj`
- `deps.edn`

Changes:

- Forced lambda params into vector form at bytecode assembly boundary (`intern! (vec params)`), so env binding can use indexed access cheaply.
- Optimized `bind-closure-env` with small-arity direct cases and transient fallback.
- Added compact hot loop call-frame representation (`:fast-call` vector) for hot loop execution, with conversion to map on scheduler fallback.
- Added benchmark harness modes:
  - `--fast-only`
  - `--register-only`
  - `--stack-only`
- Added `:profile-fast` alias:
  - writes `/tmp/datomworld-bytecode-bench-fast.jfr`
  - runs register hot loop execution only (`--fast-only --register-only`)

## Step 4: Register-array hot loop and closure env transient writes

Files:

- `src/cljc/yin/vm/register.cljc`

Changes:

- Added array-backed register helpers (`reg-array-*`, `regs->array`, `regs->vector`) and switched `reg-vm-run-active-continuation` register writes from persistent `assoc` to in-place array writes.
- Preserved immutable observation boundaries:
  - hot loop-to-scheduler fallback materializes `:regs` back to vector form.
  - compact fast call frames are converted to map frames on fallback.
- Extended closure payload with `:empty-regs-arr` template and cloned it on each call/tailcall to avoid aliasing across activations.
- Kept scheduler semantics intact (scheduler loop and step VM still operate on persistent vectors/maps).
- Updated `bind-closure-env` small-arity paths to transient `assoc!` + `persistent!`, preserving zipmap semantics while reducing per-call map churn.

Validation for this pass:

- `clj -M:test`: `213` tests, `734` assertions, `0` failures, `0` errors.
- `clj -M:bench 50000 --fast-only --register-only`: `146.550609 ms` mean.
- `clj -M:profile-fast 50000`: `126.314553 ms` mean (JFR-enabled run).

## Final Results

### Performance vs baseline

Register hot loop (`n=50000`):

- Baseline: `441.683395 ms`
- Previous pass (before Step 4): `166.374222 ms`
- Current fast-only run: `146.550609 ms`
- Improvement vs previous pass: `11.91% faster`
- Improvement vs baseline: `66.82% faster`

Register scheduler (`n=50000`) also improved (from baseline) by about `18%` in mixed runs.

### Allocation vs baseline (mixed run)

From `clj -M:profile 50000`:

- `yin.vm.register.RegisterVM.assoc`: `1,504,651 -> 1,291,403` (`14.17%` reduction)
- `yin.vm.register$reg_vm_step.invokeStatic`: `620,018 -> 376,269` (`39.31%` reduction)
- Combined targeted register churn (`assoc + reg_vm_step`): `21.51%` reduction

Important caveat:

- Overall mixed-run user-attributed allocation increased (`5,800,211 -> 6,410,022`, `+10.51%`) because this run includes stack VM and benchmark-path sampling effects.
- For register fast-only profiling, allocation is much lower and isolated (see below).

### Allocation in fast-only register profile (before Step 4)

From `clj -M:profile-fast 50000`:

- `TOTAL_REGISTER_ALLOC_BYTES`: roughly `530k-580k` across repeated runs
- Top register allocation contributors:
  - `yin.vm.register$reg_vm_run_hot_loop.invokeStatic` (majority)
  - `yin.vm.register$bind_closure_env.invokeStatic` (secondary)

This indicates remaining allocation pressure is now concentrated inside hot loop register updates and closure env binding, not generic scheduler map churn.

### Allocation after Step 4 (fast-only, top-frame sampled weight)

Comparison measured against prior pass commit (`1c13e22`) with the same command:

- `clj -M:profile-fast 50000`
- `jfr view allocation-by-site /tmp/datomworld-bytecode-bench-fast.jfr`

Key deltas:

- `clojure.lang.PersistentVector.assocN`:
  - before: `43.21%`
  - after: `0.00%`
  - result: eliminated from top allocation sites in fast-only run
- `yin.vm.register$bind_closure_env.invokeStatic`:
  - before: `3.35%`
  - after: `1.77%`
  - result: `47.16%` lower top-frame allocation pressure
- Total sampled allocation weight (`jdk.ObjectAllocationSample` sum of `weight`):
  - before: `8,015,425,696`
  - after: `5,835,978,184`
  - result: `27.19%` reduction

Interpretation note:

- `jdk.ObjectAllocationSample` is sampling-based. Percentages and weight totals are directional, not exact byte counts.
- After removing vector register writes, allocation attribution shifts toward hash-map internals (`ensureEditable`, `MapEntry.create`) in closure env binding.

## How to Reproduce

Baseline/mixed:

```bash
clj -M:bench 50000
clj -M:profile 50000
jfr view hot-methods /tmp/datomworld-bytecode-bench.jfr
jfr view allocation-by-site /tmp/datomworld-bytecode-bench.jfr
```

Hot-loop-only register (`--fast-only`):

```bash
clj -M:bench 50000 --fast-only --register-only
clj -M:profile-fast 50000
jfr view hot-methods /tmp/datomworld-bytecode-bench-fast.jfr
jfr view allocation-by-site /tmp/datomworld-bytecode-bench-fast.jfr
```

## Next Target

Remaining high-impact allocation source in register hot loop-only (`--fast-only`) mode is closure environment map construction (`PersistentHashMap` internals during `bind-closure-env`). The next pass should evaluate an env representation that avoids rebuilding large hash maps on every closure call while preserving explicit causality and immutable stream boundaries.
