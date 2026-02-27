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
