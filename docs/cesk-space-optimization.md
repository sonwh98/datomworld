# CESK-space Optimization Plan

Status: implemented (2026-07-20)
Target: `src/cljc/yin/vm/prototype/cesk_space.cljc`
Baseline comparison: `src/cljc/yin/vm/register.cljc`

## Baseline & Post-Optimization Comparison (measured 2026-07-20, JVM)

Workload: tail-recursive countdown, same program in both VMs
(`yin.vm.bytecode-bench`).

| Metric | Register VM (fast path) | CESK-space prototype (Baseline) | CESK-space prototype (Optimized) |
|---|---|---|---|
| Mean time per run (n=50) | 544.7 us (Criterium) | 20,126.5 ms (3 timed runs) | 33.3 ms (3 timed runs) |
| Ratio (n=50) | 1x | ~37,000x slower | ~61x slower |
| Mean time per run (n=5000) | 28.98 ms (Criterium) | DNF (Estimated ~10+ hours) | 747.2 ms (3 timed runs) |
| Ratio (n=5000) | 1x | - | ~25.7x slower |
| Machine steps (n=50) | - | 817 steps, 6,531 datoms | 817 steps, 5,611 datoms (trace? false) |
| Machine steps (n=5000) | - | - | 80,017 steps, 545,161 datoms (trace? false) |

Scaling of the baseline prototype was superlinear (O(N^2)): 20s at n=50, 65s at n=100.
Scaling of the optimized prototype is strictly linear (O(N)): 33.3 ms at n=50, 84.7 ms at n=500, 747.2 ms at n=5000.


JFR attribution for cesk-space: nearly all CPU sits below
`dao.space.query/fold` (6,015 stack hits) and `q/pull` (5,910); leaf
frames are index-build comparators (`Util.compare`, `Symbol.compareTo`,
`dao.space.index` `eavt/aevt/avet/vaet-cmp`, `Arrays.binarySearch`,
sorted-set `Leaf.add`). The interpreter itself (`step`, `eval-step`,
`continue-step`) is a rounding error.

Diagnosis: every read goes through the `view` chokepoint and re-folds the
raw datom vector into all four sorted indexes, several times per step.
Read cost is O(|space|) and compounds as the space grows. The gap closes
by attacking read cost, not the reduction relation. This is the tiering
the file's own docstring anticipates: log as truth, maps as caches,
promoted at safepoints.

## Plan, in order of leverage

### 1. Incremental read cache behind `view` (the big one)

Keep the datom vector as truth, but maintain a derived `{eid {attr value}}`
map in the machine cursor `st`, bumped inside `add`. Because the space is
append-only and every cell is written exactly once, cache maintenance is
one `assoc-in` per appended datom: no retraction logic, no invalidation.
Then `load-node`, `load-cfg`, `load-kont`, `store-get`, `parent` become
map lookups instead of `q/pull` re-folds. The `view` chokepoint was
designed for exactly this slot; the reduction relation does not change.

Expected: reads go from O(|space| log) to O(1) amortized; n=50 from ~16s
to low milliseconds.

Contract: the query catalog in the test file must still hold against the
raw vector, and cached reads must agree with `q/pull` over the raw space.

### 2. Fix `env-lookup`

It runs a full datalog `q/q` per frame in the chain, per variable
reference: the worst compounding read. With the eid->attr cache the chain
walk remains; better, maintain a `{frame {name addr}}` map updated in
`env-extend`. Variable lookup becomes O(chain depth) map hops.

### 3. Alternative to 1 if full incrementality is too invasive: fold at safepoints

Fold the indexes once every k steps (or at `tick`) and answer reads from
snapshot + linear scan of the suffix appended since. Append-only means the
delta is literally `(subvec space snapshot-len)`. This alone kills the
superlinear blowup with almost no code.

### 4. Emit less per step

The step trace is ~8 datoms/step (6,531 datoms for 817 steps): a fresh
cfg entity plus kont/frame/cell datoms each step. Make the trace
(`:cfg/*` provenance, `:cell/set-by`, `:frame/created-by`) optional via a
flag on `inject`, the way telemetry is opt-in in the real VMs. Smaller
space means cheaper folds and cheaper migration; semantics do not depend
on the trace.

### 5. Do NOT tune comparators or intern symbols

The hot leaf frames (`Symbol.compareTo`, `avet-cmp`, `Leaf.add`) are all
inside the re-fold. Once reads stop re-folding, that entire profile
disappears. Optimizing comparators first polishes the wrong layer.

### 6. Endgame: projection, not interpretation

Per the docstring's "where this points": the register/semantic VM executes
compiled code fast and emits the `:cfg/* :cell/* :k/*` vocabulary as a
datom stream, rather than cesk-space interpreting from datoms. Queryability,
time travel, and migration are kept (the space is still the machine's
image) while execution runs at register-VM speed.

Realistic expectations: steps 1-2 remove the superlinear term and should
leave a 10-100x gap versus the register VM. That residue is genuine
interpretation overhead plus persistent-map churn; only step 6 removes it.

## Using the benchmarks to close the gap

Harness: `yin.vm.bytecode-bench` (`src/clj/yin/vm/bytecode_bench.clj`).
The cesk-space entry runs the countdown workload with 1 warmup + 3
manually timed runs (Criterium is unusable at seconds-per-run scale and a
hard kill loses the JFR dump-on-exit).

Commands (n is the countdown size; keep it identical across VMs):

```sh
# cesk-space, wall-clock only
clj -M:bench 50 --cesk-space-only

# cesk-space under JFR -> /tmp/datomworld-cesk-space.jfr
clj -M:profile-cesk-space 50

# register VM fast path under JFR -> /tmp/datomworld-bytecode-bench-fast.jfr
clj -M:profile-fast 50
```

Caution: all `:profile*` aliases write fixed `/tmp/*.jfr` filenames and a
JVM truncates its file at startup. Never run two profile aliases
concurrently, or override the path for one of them:

```sh
clj -J-XX:StartFlightRecording=filename=/tmp/my-run.jfr,settings=profile,dumponexit=true \
    -M:bench 50 --cesk-space-only
```

Analyzing a recording with the JDK `jfr` tool:

```sh
# event counts sanity check
jfr summary /tmp/datomworld-cesk-space.jfr

# top leaf frames (where CPU samples land)
jfr print --events jdk.ExecutionSample --stack-depth 1 FILE.jfr \
  | awk '/stackTrace = \[/{getline; gsub(/^[ \t]+/,""); print}' \
  | sort | uniq -c | sort -rn | head -15

# attribute samples to namespaces of interest (deep stacks)
jfr print --events jdk.ExecutionSample --stack-depth 25 FILE.jfr \
  | grep -oE "(dao\.space\.query|yin\.vm\.prototype)[a-zA-Z0-9_$.]*" \
  | sed 's/\.invoke.*//' | sort | uniq -c | sort -rn | head -15

# top allocation frames by sampled weight (unit-corrected, MB)
jfr print --events jdk.ObjectAllocationSample FILE.jfr \
  | awk '/weight = /{v=$3; gsub(",","",v); u=$4; m=1;
         if(u=="kB")m=1e3; else if(u=="MB")m=1e6; else if(u=="GB")m=1e9;
         w=v*m; tot+=w}
         /stackTrace = \[/{getline; gsub(/^[ \t]+/,""); f=$0;
         sub(/\(.*/,"",f); sum[f]+=w}
         END{printf "%12.0f  TOTAL-MB\n", tot/1e6;
             for (f in sum) printf "%12.0f  %s\n", sum[f]/1e6, f}' \
  | sort -rn | head -15
```

Optimization loop, per step of the plan:

1. Record the baseline: `clj -M:profile-cesk-space 50`, save the mean and
   the top-frames tables above.
2. Make one change (one plan step at a time).
3. `clj -M:test` first: the test file's queries are the contract any
   tiering must preserve. A faster machine that answers queries
   differently is wrong, not fast.
4. Re-run the same benchmark command at the same n; compare mean and
   profile. The next bottleneck is whatever now tops the frame table;
   re-diagnose before starting the next step rather than trusting this
   document's predictions.
5. Once n=50 is in the milliseconds, raise n (500, 5000) to confirm the
   superlinear term is gone, and quote the register VM gap at equal n
   (`clj -M:profile-fast <n>`).

Record before/after numbers in this file as steps land, in the style of
`src/cljc/yin/vm/docs/bytecode-vm-optimization.md`.
