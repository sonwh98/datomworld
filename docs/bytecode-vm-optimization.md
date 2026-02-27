# Bytecode VM Mutable Inner Loop Optimization

## Problem

The bytecode VMs (RegisterVM, StackVM) use persistent immutable data structures throughout execution. Every instruction:
1. Reads bytecode via `(get bytecode pc)`: persistent vector lookup, O(log32 n) tree traversal
2. Allocates a new state map via `assoc`: new persistent map nodes per instruction
3. Updates registers/stack via `assoc`/`conj`: new persistent vector nodes per instruction

This negates the linear-access advantage bytecode VMs have over the AST walker. Both execution models produce the same allocation-dominated profile. The pointer chasing moved from AST node traversal into persistent data structure internals.

## Solution: Two-Layer Execution

Introduce a mutable inner loop for the hot path. Materialize immutable state only at observation boundaries (effects, parking, halting, the `step` protocol).

- **Slow path** (`reg-vm-step` / `stack-step`): unchanged, immutable, used by `IVMStep/step` for debugging and single-stepping
- **Fast path** (`reg-vm-run-fast` / `stack-vm-run-fast`): mutable inner loop, used by `eval`/`run`

Both paths produce identical results. The fast path replaces `engine/run-loop` with a self-contained `loop`/`recur` using mutable locals.

## Cross-Platform Array Helpers

Add to `src/cljc/yin/vm/engine.cljc`:

```clojure
(defn to-array [v]
  #?(:clj  (object-array v)
     :cljs (clj->js (vec v))))

(defn arr-get [arr idx]
  #?(:clj  (aget ^objects arr idx)
     :cljs (aget arr idx)))

(defn arr-set! [arr idx val]
  #?(:clj  (aset ^objects arr (int idx) val)
     :cljs (aset arr idx val)))

(defn arr-copy [arr]
  #?(:clj  (aclone ^objects arr)
     :cljs (.slice arr)))

(defn make-obj-array [size]
  #?(:clj  (object-array size)
     :cljs (js/Array. size)))
```

On JVM, `aget`/`aset` on `object-array` is O(1) direct memory access. On ClojureScript, `aget`/`aset` on a JS Array is O(1) property access. Both eliminate the O(log32 n) trie traversal.

## Register VM Fast Path

File: `src/cljc/yin/vm/register.cljc`

### Mutable State Layout

```clojure
(defn- reg-vm-run-fast [state]
  (let [vbc  (volatile! (to-array (:bytecode state)))   ; bytecode array (swapped on closure calls)
        vpl  (volatile! (to-array (:pool state)))       ; pool array (swapped on closure calls)
        vrs  (volatile! (to-array (:regs state)))       ; registers array (swapped on call/return)
        vpc  (volatile! (:pc state))                    ; program counter
        venv (volatile! (:env state))                   ; env (persistent map, swapped not mutated)
        vk   (volatile! (:k state))                     ; continuation (persistent map or nil)
        vstore (volatile! (:store state))               ; store (persistent map)
        vid  (volatile! (:id-counter state))            ; id counter
        vbc-persistent (volatile! (:bytecode state))    ; persistent bytecode ref (for closures)
        vpl-persistent (volatile! (:pool state))]       ; persistent pool ref (for closures)
    (loop [] ...)))
```

### Instruction Implementations

Simple ops (`:literal`, `:load-var`, `:move`, `:branch`, `:jump`): `arr-get` for reads, `arr-set!` for register writes, `vswap! vpc` for pc advance, `recur` to continue loop. Zero allocation per instruction.

```clojure
:literal
(let [rd (arr-get @vbc (+ @vpc 1))
      v  (arr-get @vpl (arr-get @vbc (+ @vpc 2)))]
  (arr-set! @vrs rd v)
  (vswap! vpc + 3)
  (recur))
```

`:lambda`: create closure map capturing persistent bytecode/pool refs and `@venv`. One map allocation (unavoidable: closure is a reified value).

`:call` to closure: `(vec @vrs)` to snapshot registers into call-frame (O(n) copy at call boundary), `make-obj-array` for callee's registers, swap all volatiles, `recur`. Frame allocation is unavoidable (continuation semantics).

`:tailcall` to closure: swap arrays without snapshotting. No frame push, no `(vec @vrs)`, no allocation beyond the new register array.

`:return`: if `@vk` is nil, materialize immutable state and return (halted). Otherwise restore frame from `@vk`: `(to-array (:regs frame))` into `vrs`, restore vpc/venv/vk/vbc/vpl, `arr-set!` return value, `recur`.

`:call` to native fn: `apply fn-val fn-args`. If no effect, `arr-set!` result, `recur`. If effect, materialize immutable state, delegate to `engine/handle-effect`. If blocked, return immutable state. Otherwise re-hydrate volatiles from result state, `recur`.

Stream/park/resume ops: materialize immutable state, delegate to existing engine functions, re-hydrate or return.

### Materialization

```clojure
(defn- materialize-state [base vbc-p vpl-p vrs vpc venv vk vstore vid]
  (assoc base
         :bytecode @vbc-p :pool @vpl-p :regs (vec @vrs)
         :pc @vpc :env @venv :k @vk :store @vstore :id-counter @vid))
```

Called only at boundaries: effects, blocking, halting, parking, reification. Not called per instruction.

## Stack VM Fast Path

File: `src/cljc/yin/vm/stack.cljc`

### Mutable Stack

Pre-allocated array with stack pointer volatile:

```clojure
(let [vstack (volatile! (make-obj-array 256))
      vsp    (volatile! -1)
      push!  (fn [v]
               (let [sp (vswap! vsp inc)]
                 (arr-set! @vstack sp v)))
      pop!   (fn []
               (let [sp @vsp
                     v (arr-get @vstack sp)]
                 (vswap! vsp dec)
                 v))
      peek!  (fn [] (arr-get @vstack @vsp))]
  ...)
```

Resize (2x copy) when `sp` exceeds array length.

### Eliminating subvec in Lambda

Current stack VM closures store `body-bytes` (a `subvec` slice). Change to store `body-start`/`body-end` offsets into the full bytecode. At call time, set `vpc` to `body-start` and use the full bytecode array. Eliminates per-closure `subvec` allocation.

```clojure
:lambda
(let [closure {:type :closure
               :params params
               :body-start (+ @vpc 4)
               :body-end   (+ @vpc 4 body-len)
               :env @venv
               :bytecode @vbc-persistent
               :pool @vpl-persistent}]
  ...)
```

## Scheduler Integration

The fast path handles run-queue/blocked/wait-set internally rather than delegating to `engine/run-loop`:
- After halting or blocking, check run-queue
- If entries exist, re-hydrate mutable state from the entry and `recur`
- If blocked, call `engine/check-wait-set`, check run-queue again
- If nothing remains, return immutable state

## What Stays Persistent

- **`env`**: closures capture env, structural sharing is essential
- **`store`**: shared heap, needs persistence for effects
- **`pool`**: read-only after compilation, converted to array for fast reads but persistent ref kept for closures
- **Closure and frame maps**: semantic operations (reifying execution context), unavoidable
- **`IVMStep/step`**: unchanged slow path for debuggability

## Implementation Phases

### Phase 1: Array helpers in engine.cljc
Add cross-platform `to-array`, `arr-get`, `arr-set!`, `arr-copy`, `make-obj-array`.

### Phase 2: Register VM fast path
Add `reg-vm-run-fast` to `register.cljc`. Start with common opcodes (`:literal`, `:load-var`, `:move`, `:branch`, `:jump`, `:return`), then add `:lambda`, `:call`, `:tailcall`, then stream/continuation ops. Wire `reg-vm-eval` to use it.

### Phase 3: Stack VM fast path
Add `stack-vm-run-fast` to `stack.cljc`. Mutable stack array, eliminate `subvec` closures. Wire `stack-vm-eval` to use it.

### Phase 4: Verification
All existing tests pass. Add slow-vs-fast parity test to `test/yin/vm/parity_test.cljc`.

## Expected Impact

For a tight tail-recursive loop (10000 iterations):
- Current: ~10000 state map allocations, ~10000 register vector allocations
- Optimized: 0 state maps, 0 register vectors, ~10000 O(1) array accesses
- Expected: 5-10x speedup for compute-heavy code

For closure-heavy code:
- Call/return still allocates frames and register arrays (unavoidable)
- But instructions between calls are allocation-free
- Expected: 2-5x speedup

For IO-heavy code (frequent effects):
- Improvement is modest since effects materialize immutable state anyway

## Files

- `src/cljc/yin/vm/engine.cljc` - array helpers
- `src/cljc/yin/vm/register.cljc` - `reg-vm-run-fast`
- `src/cljc/yin/vm/stack.cljc` - `stack-vm-run-fast`
- `test/yin/vm/parity_test.cljc` - slow-vs-fast parity test

## RegisterVM Reapplied Optimization Pass (2026-02-27)

Scope:

- Reapplied RegisterVM hot-path env binding optimization.
- Reapplied cleanup to stop passing primitives via `:env` at call sites.

Code changes:

- `src/cljc/yin/vm/register.cljc`
  - `bind-closure-env` small arity (`0..3`) uses direct/chained `assoc` and
    avoids transient + varargs-seq overhead.
  - transient fallback remains for larger arities.
- Call-site cleanup:
  - removed `create-vm {:env vm/primitives}` usage across src/test.
  - removed `:env (merge vm/primitives env)` pattern (now `:env env`).
  - primitives now come from VM `:primitives` (`vm/empty-state`) only.
- Tests updated to assert lexical environment behavior directly (not primitive
  duplication inside lexical `:env`).

Validation:

- `clj -M:test`: 213 tests, 734 assertions, 0 failures.

Benchmark comparison (fast-only register, `n=50000`):

- Baseline commit: `7b0828a`
- Baseline:
  - `clj -M:bench 50000 --fast-only --register-only`
  - mean `148.945135 ms`
- Current:
  - same command
  - mean `125.324975 ms`
- Delta: `15.85%` faster.

Profile-fast comparison:

- Baseline:
  - `clj -M:profile-fast 50000`
  - mean `141.264520 ms`
- Current:
  - same command
  - mean `132.037961 ms`
- Delta: `6.53%` faster.

Allocation comparison (JFR `jdk.ObjectAllocationSample` top-frame weight sum):

- Baseline total: `5,477,362,552`
- Current total: `4,378,212,128`
- Delta: `20.06%` lower sampled allocation.

Hotspot movement:

- Reduced:
  - `PersistentHashMap$ArrayNode.ensureEditable`: `25.52% -> 0.00%`
  - `PersistentHashMap$BitmapIndexedNode.ensureEditable`: `3.90% -> 0.00%`
  - `MapEntry.create`: `19.92% -> 11.42%`
- New dominant small-map churn:
  - `PersistentArrayMap.create`: `6.63%`
  - `PersistentArrayMap.assoc`: `4.60%`

Next steps (RegisterVM):

1. Reduce `RT.longCast`/`nth` cast pressure in `reg-vm-run-fast`.
2. Reduce remaining small-map allocation in `bind-closure-env` for closure calls.

## Node.js Cross-Check vs master (2026-02-27)

Goal:

- Verify that RegisterVM optimizations also improve runtime in the Node.js
  (ClojureScript) execution path, not only on JVM benchmarks.

Method:

- Branch under test: `bytecode-vm-optimization` (`d4b16c1`).
- Baseline branch: `master` (`d66b250`).
- Workload: tail-recursive countdown (`n=50000`) executed by RegisterVM fast path.
- Benchmark parameters: `iterations=20`, `samples=9`, `warmup=4`.
- Node command in both branches:
  - `node target/register-vm-node-bench.js 50000 20 9 4`

Results:

- `bytecode-vm-optimization`:
  - mean per iteration: `122.07972718888888 ms`
  - median per iteration: `122.09532535000001 ms`
- `master`:
  - mean per iteration: `855.3062920055555 ms`
  - median per iteration: `855.861031 ms`

Delta vs `master`:

- Speedup: `7.0061x`
- Runtime reduction: `85.73%` faster

Conclusion:

- The RegisterVM optimization is not JVM-specific. The same optimization pass
  produces a substantial runtime improvement on Node.js as well.

## ClojureDart Benchmark vs master (2026-02-27)

Goal:

- Compare RegisterVM performance on ClojureDart between
  `bytecode-vm-optimization` and `master` using the same benchmark harness.

Harness:

- Benchmark namespace: `yin.register-bench-cljd`
  (`src/cljd/yin/register_bench_cljd.cljd`)
- Dart entrypoint: `bin/register_bench_cljd.dart`
- Command:
  - `clj -M:cljd compile yin.register-bench-cljd && dart run bin/register_bench_cljd.dart`
- Workload/config:
  - `:closure-call`
  - `iterations=20000`
  - `samples=9`
  - `warmup=4`
- Repetitions:
  - 5 benchmark runs per branch (using each run's reported `mean_total_ms`)

Branches:

- Current: `bytecode-vm-optimization` (`d4b16c1`)
- Baseline: `master` (`d66b250`)

Notes for reproducibility:

- In the temporary `master` worktree, a compile-only compatibility patch was
  applied to `vm/opcase` macro expansion so CLJD could compile. Runtime
  workload and benchmark parameters were kept identical.

Run results (`mean_total_ms`):

- Current branch:
  - `109.1380`
  - `124.7046`
  - `110.6156`
  - `105.0791`
  - `106.9176`
- Master:
  - `121.5854`
  - `121.1263`
  - `139.1358`
  - `120.4989`
  - `121.1634`

Aggregates:

- Mean of run means:
  - Current: `111.2910 ms`
  - Master: `124.7020 ms`
  - Delta: `1.1205x` speedup (`10.75%` faster)
- Median of run means (robust):
  - Current: `109.1380 ms`
  - Master: `121.1634 ms`
  - Delta: `1.1102x` speedup (`9.92%` faster)

Conclusion:

- On ClojureDart, this branch is faster than `master` for the benchmarked
  register workload, with ~10% improvement based on both mean and median views.

## JVM Benchmark vs master (2026-02-27)

Goal:

- Compare RegisterVM runtime on JVM between the current optimization branch and
  `master` using an identical harness in both branches.

Method:

- Branch under test: `bytecode-vm-optimization` (`ba6d68e`)
- Baseline: `master` (`d66b250`)
- Workload: tail-recursive countdown (`n=50000`) on RegisterVM.
- Parameters: `iterations=20`, `samples=9`, `warmup=4`.
- Command (both branches): `clojure -M /tmp/register-jvm-compare.clj`
- Repetitions: 5 runs per branch, comparing each run's reported
  `mean_total_ms`.

Run results (`mean_total_ms`):

- Current branch:
  - `1890.9242`
  - `1894.3925`
  - `1958.1901`
  - `1901.9290`
  - `2026.8431`
- Master:
  - `8927.2644`
  - `8342.9546`
  - `8225.7303`
  - `8366.0426`
  - `8071.1745`

Aggregates:

- Mean of run means:
  - Current: `1934.4558 ms`
  - Master: `8386.6333 ms`
  - Delta: `4.34x` speedup (`76.93%` faster)
- Median of run means:
  - Current: `1905.4432 ms`
  - Master: `8339.9745 ms`
  - Delta: `4.38x` speedup (`77.15%` faster)
- Mean per-iteration (cross-check):
  - Current: `96.7228 ms`
  - Master: `419.3317 ms`
  - Delta: `4.34x` speedup (`76.93%` faster)

Conclusion:

- JVM results match the direction seen on Node.js and ClojureDart. The current
  branch is substantially faster than `master` for this register workload.

## Analysis of ClojureDart Performance Delta (2026-02-27)

While Node.js and JVM show 4x-7x speedups, ClojureDart (CLJD) initially shows a
more modest ~10% improvement. Three factors contribute to this delta:

1. **Workload Selection**:
   - JVM/Node benchmarks used `tail-recursive countdown`, which spends 99% of its
     time in the allocation-free instruction loop.
   - The CLJD benchmark used `:closure-call`, which exercises closure creation
     and environment binding. Small-map allocation in `bind-closure-env` is still
     a bottleneck (documented in "Next steps") and affects all platforms, but
     shows up more prominently in this specific workload.

2. **Execution Path (Fast vs. Slow)**:
   - The reported ~10% gain for CLJD was measured on the **slow path**
     (`reg-vm-run-slow`) using the scheduler-backed loop, whereas JVM/Node were
     measured on the **fast path** (`reg-vm-run-fast`).
   - The slow path pays a high per-instruction tax for `atom` updates and
     run-queue checks, masking the efficiency gains of the register-based
     instruction set.

3. **Indirection Overhead in Register Access**:
   - To ensure correctness on Dart (where `object-array` returns a `List` that
     might be mistaken for a standard Clojure vector), CLJD uses a tagged
     wrapper: `[:reg-array <native-list>]`.
   - Every `get-reg`/`set-reg!` on CLJD performs an extra `nth` call to
     destructure this wrapper before accessing the native list.
   - On JVM and Node.js, these are direct `aget`/`aset` operations on native
     arrays.

The next validation step is to re-run the `tail-recursive countdown` on CLJD
using the newly re-enabled fast path (enabled by the `[:reg-array]` fix).

## ClojureDart: Eliminate `[:reg-array]` Wrapper (2026-02-27)

Root cause:

- The `[:reg-array <native-list>]` wrapper was introduced to prevent confusion
  between a bare Dart `List` (from `object-array`) and a ClojureDart
  `PersistentVector`.
- This was unnecessary: CLJD's `vector?` checks `IVector.satisfies()`, which
  only matches `PersistentVector` and `MapEntry`. A bare Dart `List.filled()`
  does NOT satisfy `IVector`.
- Every register access paid for the wrapper: `(nth regs 1)` to unwrap the
  tuple (a full persistent-vector `nth`), plus `(= :reg-array (nth regs 0))`
  keyword comparison on every `reg-array?` check.

Fix:

- Eliminated the wrapper. All 7 CLJD reader-conditional branches now operate
  on bare Dart Lists:
  - `reg-array?`: `(and (some? regs) (not (vector? regs)))`
  - `reg-array-get`: `(aget regs (int r))` (direct `List[]` access)
  - `reg-array-set!`: `(aset regs (int r) v)` (direct `List[]=` assignment)
  - `reg-array-clone`: `(aclone regs)` (direct `List.from`)
  - `make-empty-regs-array`: `(object-array (int n))` (bare `List.filled`)
  - `regs->array`: `(object-array regs)` (bare `List.from`)
  - `regs->vector`: `(vec regs)` (no unwrap)

Validation:

- JVM: 213 tests, 734 assertions, 0 failures.
- Node.js: 154 tests, 435 assertions, 0 failures.
- CLJD: compiles without error (one benign dynamic warning on `[]=`).

Benchmark (tail-recursive countdown, `n=50000`, `iterations=20`, `samples=9`,
`warmup=4`):

- Current branch (3 runs, `mean_per_iter_ms`):
  - `111.81`, `114.63`, `113.56`
  - Mean: `113.33 ms`
- Master (3 runs, `mean_per_iter_ms`):
  - `888.62`, `888.18`, `822.38`
  - Mean: `866.39 ms`
- Delta: `7.65x` speedup (`86.9%` faster)

Closure-call workload (apples-to-apples with previous CLJD benchmark):

- Before (with wrapper): mean `111.29 ms` (20000 iterations)
- After (bare List): mean `90.29 ms` (20000 iterations)
- Delta: `1.23x` speedup (`18.9%` faster)

Cross-platform summary (tail-recursive countdown, `n=50000`):

| Platform | Master (ms/iter) | Current (ms/iter) | Speedup |
|----------|------------------|-------------------|---------|
| Node.js  | 855              | 122               | 7.0x    |
| JVM      | 419              | 97                | 4.3x    |
| CLJD     | 866              | 113               | 7.6x    |
