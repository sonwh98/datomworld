# SemanticVM Hot-Loop Optimization Plan

## Context

The RegisterVM optimization (documented in `docs/bytecode-vm-optimization.md` and `docs/register-vm-optimization.md`) achieved 4-7x speedups across JVM, Node.js, and ClojureDart by:
1. Eliminating per-instruction state map allocation (mutable inner loop)
2. Arity-specialized env binding (`bind-closure-env`)
3. Arity-specialized native invocation (`invoke-native`)
4. Array-backed registers

The SemanticVM (`src/cljc/yin/vm/semantic.cljc`) has an existing hot loop (`semantic-run-active-continuation`, lines 537-688) that keeps `control`, `env`, `stack`, `vm` as loop locals. However, it still allocates a **control map** (`{:type :value, :val v}` or `{:type :node, :id eid}`) on every single step transition, and uses **`zipmap` + `merge`** for env binding and **`apply`** for native invocations. These are the same bottlenecks RegisterVM eliminated.

## Optimizations

### 1. Eliminate control map allocation (highest impact)

**Current**: Each step transition allocates a map:
```clojure
(recur {:type :value, :val result} env-call new-stack vm)
(recur {:type :node, :id node-id} env stack vm)
```

**New**: Split `control` into two bare loop locals `ctrl-tag` (keyword) and `ctrl-data` (value or node-id):
```clojure
(recur :value result env-call new-stack vm)
(recur :node node-id env stack vm)
```

Dispatch via `case ctrl-tag` instead of `cond (= :value (:type control))` (eliminates map lookup + keyword comparison).

For the benchmark (tail-recursive countdown, n=50000), this eliminates ~500k map allocations.

### 2. Arity-specialized env binding

**Current** (line 605): `(merge env-clo (zipmap params new-evaluated))` creates a transient seq via `zipmap` then merges.

**New**: `bind-semantic-env` with direct `assoc` for 0-3 params (the common case):
```clojure
(case (count params)
  0 env-clo
  1 (assoc env-clo (nth params 0) (nth evaluated 0))
  2 (assoc env-clo (nth params 0) (nth evaluated 0)
                    (nth params 1) (nth evaluated 1))
  ...)
```

### 3. Arity-specialized native invocation

**Current** (line 613): `(apply fn-val new-evaluated)` allocates a seq.

**New**: `invoke-semantic-native` with direct call for 0-4 args:
```clojure
(case (count evaluated)
  0 (fn-val)
  1 (fn-val (nth evaluated 0))
  2 (fn-val (nth evaluated 0) (nth evaluated 1))
  ...)
```

### 4. Minor: eliminate dead `subvec` allocation

Line 585: `rest-args (subvec operands 1)` is computed but never used. Remove it.

### 5. Minor: `nth` instead of `first`

Line 584: Replace `(first operands)` with `(nth operands 0)` (avoids seq creation on PersistentVector).

### 6. Fix: correct effect handling in hot loop

Lines 576-580 and 614-619: The hot loop treats `handle-effect` result as a VM state directly (`(:blocked res)`, `(:control res)`), but `handle-effect` returns `{:state s, :value v, :blocked? b}`. Fix to destructure correctly and pass `park-entry-fns` where needed (matching the slow path).

### 7. Hoist constant fields from vm-init

Bind `primitives` and `datoms` in outer `let` (constant during execution) to avoid repeated record field access.

## Files Modified

- `src/cljc/yin/vm/semantic.cljc`:
  - Add `bind-semantic-env` helper (after `handle-node-eval`)
  - Add `invoke-semantic-native` helper (after `bind-semantic-env`)
  - Rewrite `semantic-run-active-continuation` (lines 537-688)

No other files change. Step path (`semantic-step`), slow path (`handle-return-value`, `handle-node-eval`), protocol implementations, and `semantic-vm-eval` remain unchanged.

## What Stays the Same

- `IVMStep/step` uses `semantic-step` (unchanged, for debugging)
- `handle-return-value` / `handle-node-eval` (slow path, used by step and as hot-loop fallback)
- `semantic-vm-eval` entry point (calls `semantic-run-active-continuation` with same signature)
- All continuation frame representations (maps) - these are a future optimization target
- All stream/park/resume operations delegated to fallback

## Pass 1 Results (commit 74c45f0)

Optimizations 1-7 applied. Benchmark: tail-recursive countdown, n=50000, JVM fast path.

| VM | Mean |
|---|---|
| Semantic VM | 291.3 ms |
| AST Walker VM | 294.1 ms |

Result: parity within noise (< 1% difference). The control map elimination,
arity-specialized env binding, and arity-specialized native invocation bring
the SemanticVM to the same speed as the AST walker, but not faster.

## Why the SemanticVM Is Not Yet Faster Than the AST Walker

The blog thesis (`public/chp/blog/ast-datom-streams-bytecode-performance.blog`)
argues that datom streams should achieve bytecode-like speed through sequential
access and minimal indirection. The current SemanticVM does not realize this
advantage because of a per-step hash map lookup.

Every node transition does `(get index node-id)` where `index` is a
PersistentHashMap. This is hash computation + tree traversal on every step.
The AST walker avoids this: child nodes are direct object references stored
in AST maps.

Both VMs pay roughly equal per-step costs via different indirections:

- AST Walker: `(:test ast-node)` -- keyword scan on small PersistentArrayMap
- Semantic VM: `(get index entity-id)` -- hash map lookup, then
  `(aget node-arr ATTR_TEST)` -- array access

The SemanticVM's Object array for attributes is fast (O(1) aget), but the
hash map lookup to reach the node-arr nullifies the advantage.

## Pass 2: Array-Backed Node Index

Entity IDs from `vm/ast->datoms` are contiguous negative integers
(-1025, -1026, ...). Replaced the `{eid -> node-arr}` PersistentHashMap with
an Object array indexed by `(- eid min-id)`, built once in `load-program`:

```clojure
;; Before: PersistentHashMap lookup per step
(get index node-id)

;; After: O(1) array access per step
(aget ^objects index-arr (unchecked-subtract-int (int node-id) index-base-id))
```

Result: no measurable improvement. The node index lookup is only ~5.6% of
hot loop time. The dominant cost is elsewhere.

## Pass 2 Results

| VM | Mean |
|---|---|
| Semantic VM | 291.6 ms |
| AST Walker VM | 295.4 ms |

Still parity. The array-backed index saved ~5% of hot loop time but this
is within benchmark noise.

## JFR Profiling Analysis (tail-countdown n=50000, 100 iterations)

2137 JFR execution samples inside `semantic-run-active-continuation`:

| % | Samples | Source |
|---|---|---|
| 9.6% | 206 | `PersistentArrayMap.indexOf` -- keyword lookups on continuation frame maps |
| 6.9% | 148 | `KeywordLookupSite.get` -- keyword dispatch on frame destructuring |
| 6.1% | 130 | `conj` -- `(conj stack frame)`, `(conj evaluated val)` |
| 5.6% | 119 | `aget` node array lookup (line 758) |
| 4.7% | 100 | `PersistentVector.cons` -- stack vector growth |
| 4.4% | 95 | `:app-args` frame destructuring (line 688) |
| 3.2% | 69 | `RT.nth` -- operand/evaluated indexing |
| 2.8% | 59 | `String.hashCode` -- keyword hashing |
| 2.5% | 54 | `Util.dohasheq` -- more hashing |
| 2.2% | 48 | `RT.count` -- `(count operands)`, `(count evaluated)` |
| 2.0% | 42 | `PersistentArrayMap.createHT` -- allocating new frame maps |

**Dominant bottleneck: continuation frame maps (~30% combined)**

The hot loop allocates a PersistentArrayMap for every continuation frame
(`{:type :app-args, :fn fn-val, :evaluated [], ...}`,
`{:type :if, :consequent c, :alternate a, :env env}`,
`{:type :app-op, :operands ops, :env env, :tail? t}`,
`{:type :restore-env, :env env}`),
then destructures each frame by keyword on return. This involves:
- `PersistentArrayMap.createHT` to allocate the frame (2.0%)
- `PersistentArrayMap.indexOf` + `KeywordLookupSite` + `String.hashCode`
  + `dohasheq` to read fields back (21.8%)
- `conj`/`PersistentVector.cons` to push onto the stack (10.8%)

Total: ~34.6% of hot loop time on frame allocation + keyword lookup.

## Next: Vector-Backed Continuation Frames

The RegisterVM solved this exact problem with `fast-call-frame`: a plain
vector `[:fast-call rd pc regs env bytecode pool parent-k]` accessed via
positional `(nth frame N)` instead of keyword lookup. This eliminates
keyword hashing, `PersistentArrayMap.indexOf`, and `createHT` entirely.

### Design

Replace map frames with positional vectors, accessed by constant index.
Define frame type tags and slot constants:

```clojure
;; Frame type tags
(def ^:private FRAME_IF 0)
(def ^:private FRAME_APP_OP 1)
(def ^:private FRAME_APP_ARGS 2)
(def ^:private FRAME_RESTORE_ENV 3)

;; Slot indices (slot 0 is always the type tag)
;; :if frame: [FRAME_IF consequent alternate env]
(def ^:private IF_CONSEQUENT 1)
(def ^:private IF_ALTERNATE 2)
(def ^:private IF_ENV 3)

;; :app-op frame: [FRAME_APP_OP operands env tail?]
(def ^:private APP_OP_OPERANDS 1)
(def ^:private APP_OP_ENV 2)
(def ^:private APP_OP_TAIL 3)

;; :app-args frame: [FRAME_APP_ARGS fn evaluated operands next-idx env tail?]
(def ^:private APP_ARGS_FN 1)
(def ^:private APP_ARGS_EVALUATED 2)
(def ^:private APP_ARGS_OPERANDS 3)
(def ^:private APP_ARGS_NEXT_IDX 4)
(def ^:private APP_ARGS_ENV 5)
(def ^:private APP_ARGS_TAIL 6)

;; :restore-env frame: [FRAME_RESTORE_ENV env]
(def ^:private RESTORE_ENV_ENV 1)
```

In the hot loop, replace:
```clojure
;; Before: map allocation + keyword destructuring
(conj stack {:type :if,
             :consequent (aget node-arr ATTR_CONSEQUENT),
             :alternate (aget node-arr ATTR_ALTERNATE),
             :env env})
;; ... later:
(let [{consequent :consequent, alternate :alternate, env-restore :env} frame] ...)

;; After: vector allocation + positional access
(conj stack [FRAME_IF
             (aget node-arr ATTR_CONSEQUENT)
             (aget node-arr ATTR_ALTERNATE)
             env])
;; ... later:
(let [consequent (nth frame IF_CONSEQUENT)
      alternate (nth frame IF_ALTERNATE)
      env-restore (nth frame IF_ENV)] ...)
```

Dispatch on frame type via `(case (int (nth frame 0)) ...)` instead of
`(case (:type frame) ...)`.

The slow path (`handle-return-value`) needs a conversion function
`fast-frame->map` (same pattern as RegisterVM's `fast-frame->map`) to
translate vector frames back to map frames for the step-by-step path.

### Expected impact

- Eliminates `PersistentArrayMap.createHT` (2.0%)
- Eliminates `PersistentArrayMap.indexOf` (9.6%)
- Eliminates `KeywordLookupSite.get` (6.9%)
- Eliminates `String.hashCode` + `dohasheq` for frame access (5.3%)
- Reduces `conj` cost (vectors of vectors are cheaper than vectors of maps)

Conservative estimate: ~20-25% reduction in hot loop time.

### Files modified

- `src/cljc/yin/vm/semantic.cljc`:
  - Add frame slot constants (after ATTR constants)
  - Add `fast-semantic-frame->map` conversion helper
  - Update `semantic-run-active-continuation`: vector frames + positional access
  - Update fallback paths to convert fast frames before calling slow path

### What stays the same

- `handle-return-value` / `handle-node-eval` (slow path, map-based)
- `semantic-step` (slow path, map-based)
- All stream/park/resume operations
- `semantic-vm-eval` entry point

## Pass 3 Results

Vector-backed continuation frames + mutable Object array stack applied.

| VM | Mean (Pass 2) | Mean (Pass 3) |
|---|---|---|
| Semantic VM | 291.6 ms | 245.1 ms |
| AST Walker VM | 295.4 ms | 281.6 ms |

Result: Semantic VM is now ~13% faster than the AST Walker VM. The frame
optimization reduced hot loop time by ~16% from Pass 2.

### Key implementation details

- Outer control dispatch uses `(if (= ctrl-tag :value) ... (if (= ctrl-tag :node) ... default))`.
  `case` cannot be used because CLJS `case` breaks `recur` tail position.
  `identical?` cannot be used because CLJS does not guarantee keyword identity
  across compilation.
- Fallback paths (`:else` in frame dispatch, node type fallback) must re-populate
  the mutable `s-arr` from `(:stack next)` before recurring, because
  `handle-return-value` / `handle-node-eval` may push new map-based frames that
  differ from the stale contents of `s-arr`.
- Frame type tags use keywords (`:ft/if`, `:ft/app-op`, etc.) not integers,
  dispatched via `cond` with `=`.

## Final Standings (Post-Review Refinement)

A code review identified correctness issues in effect handling snapshots and
unnecessary allocations in the fallback boundaries. The final pass addressed
these while maintaining the "Linear Datom Stream" optimizations.

Final standing ($n=1000$):

| VM | Mean | Relative |
|---|---|---|
| Register VM | 2.60 ms | 1.0x |
| Stack VM | 4.03 ms | 1.5x |
| **Semantic VM** | **4.76 ms** | **1.8x** |
| AST Walker VM | 6.31 ms | 2.4x |

### Code Review Findings Addressed

1. **Incorrect Snapshot Value**: Fixed effect handling in hot loop to capture
   the correct return value (not the function itself).
2. **Non-Integer ID Guard**: Added a guard for `(integer? node-id)` before
   primitive casting to prevent `ClassCastException` on non-integer IDs.
3. **Boundary Cleanup**: Refactored `semantic-return` to eliminate redundant
   parameter passing and ensure consistency between `control` and `value`.
4. **Structural Issues**: Extracted `s-arr->map-stack` to safely synchronize
   the mutable hot-loop stack with the immutable record model on exit/fallback.

## Verification

1. **JVM tests**: `clj -M:test` - 213 tests, 0 failures.
2. **Benchmark**: `clj -M:bench 1000 --fast-only` - verified consistent standings.
3. **Integrity**: All protocols (`IVMStep`, `IVMState`) preserved and functional.
