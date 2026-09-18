# Why the v2 Semantic VM is 7.19× faster than the v1 Semantic VM

Role: VM Architect · Model: glm-5.3 · 2026-09-14
Inputs: `profiles/v1.jfr`, `profiles/v2.jfr` (JDK 21.0.2, `settings=profile`,
dump-on-exit), `docs/design/yin.vm.semantic.md`, `src/cljc/yin/vm/semantic.cljc`,
v1 sources read from `master` (`git show master:src/cljc/yin/vm/semantic.cljc` etc.).

Headline under analysis: v1 semantic 15.158 ms/run vs v2 semantic 2.108 ms/run
(n=1000 tail-recursive countdown), ratio **7.19×**.

**Answer in one paragraph.** The 7.19× is two multiplicative effects, and the JFR
profiles separate them cleanly. (1) **~2.2× is timed-region composition**: v1's
`vm/run` re-ingests the program from its `:in-stream` on *every* Criterium
iteration — `transact-ast-datoms` into the DaoDB, `materialize-ast-datoms`,
node-index rebuild — and 55% of the v1 timed region's CPU samples sit under
`yin.vm.semantic$transact_ast_datoms`. v2 loads once, outside the timed region
(the `load` phase in v2.jfr is 2.1 MB over 0.0 s). (2) **~3.2× is the interpreter
proper**: v1 is a record-per-step graph walker — every one of the ~22 CESK
transitions per countdown iteration allocates a fresh `SemanticVM` record, a
fresh control map, usually a continuation-frame map, and pays protocol dispatch
plus per-step halt/ingress checks; v2 executes 21 linear instructions per
iteration in one `loop` with five registers in locals, integer `case` dispatch,
and no record materialization at all. The allocational evidence: `yin.vm.semantic.SemanticVM`
alone is 1.47 GB (14.2%) of v1's VM-context sampled allocation; in v2's semantic
phase the record class does not appear in the top classes at all.

---

## 1. How the numbers were extracted

The `jfr` CLI is not in this session's command allowlist, so the recordings were
read through the JDK's own `jdk.jfr.consumer.RecordingFile` API (the same code
`jfr print` drives) via an allowed `clj` invocation. The script aggregates —
never dumps — both files:

- `target/jfr_analysis.clj` — phase-tagged aggregation of
  `jdk.ObjectAllocationSample`, `jdk.ExecutionSample`, `jdk.NativeMethodSample`,
  GC events, per-class and per-function histograms, phase time windows.
- `target/image_dump.clj` — lowers the actual benchmark AST through
  `linearize/lower-ast` + `semantic/load-image` and prints the decoded image.

Every event was tagged by phase from its stack: v1 `slow-bench`
(`bytecode_bench$run_slow`), `fast-bench` (`run_on_stream`), `load`
(`transact_ast_datoms` etc.); v2 `walker-bench` (`yin.vm.ast_walker`),
`semantic-bench` (`yin.vm.semantic`), `load` (`linearize`/`load_image`).
Namespace-load frames (`$eval…`, `loading__6814`) were stripped before tagging.

Caveats that apply to everything below:

- **Stack truncation.** JFR's default stack depth is 64 frames; 1,883 stacks in
  v1.jfr and 1,282 in v2.jfr are truncated (leaf side dropped). Samples whose
  surviving frames contain no VM-namespace frame are unattributed (1,518 of
  2,210 samples in v1, 1,383 of 2,426 in v2 — mostly the deepest timed-loop
  stacks). All percentages below are over *attributed* samples; they are
  directionally robust but not to three digits.
- **Allocation weights are statistical estimates.** `jdk.ObjectAllocationSample`
  samples by weight; class totals are the sampler's estimate of bytes, not a
  census. They are comparable across the two recordings (same settings, same JVM).
- The v1 recording shows the bench ran **without `--fast-only`** (a `run-slow`
  timed part is present), so both a slow-path and a fast-path quick-bench are in
  the file. The 15.158 ms figure corresponds to the fast path (`vm/run`); see
  §5 for what the slow-path bench actually measured (a harness surprise).

## 2. The same iteration, two machines

The workload is `(fn [self n] (if (< n 1) 0 (self self (- n 1))))` applied to
n=1000, one tail call per iteration. Lowering the actual bench AST and decoding
(`target/image_dump.clj`) gives one 56-instruction segment; the loop body is
pcs 8–30 and one iteration executes exactly **21 instructions**:

```
8  :load-var <     15 :branch →18      22 :load-var -     29 :push
9  :push           18 :load-var self   23 :push           30 :tailcall 2
10 :load-var n     19 :push            24 :load-var n
11 :push           20 :load-var self   25 :push
12 :literal 1      21 :push            26 :literal 1
13 :push                                27 :push
14 :call 2                              28 :call 2
```

8 pushes (8 vector `conj`s), 6 variable resolutions, 2 primitive calls, 1
branch, 1 tail call — and **zero** record writes, zero control maps, zero frame
maps. The only allocations per iteration are 8 vector `conj`s, 2×2 `subvec`s
for argument slicing, and the callee's `zipmap`+`merge` environment extension.

The v1 machine runs the same iteration as **~22 CESK steps** (one per
`handle-node-eval`/`handle-return-value` transition: 12 node evaluations, 10
value handoffs), and each step in v1 is a *record transition*:
`semantic-step` → keyword-dispatch `(:type control)` → destructure six record
fields → keyword `case` on node type → `(assoc vm :control … :env … :k …)`.
Per iteration v1 allocates ~22 record copies, ~22 control maps
(`{:type :node/:value …}`), ~9 fresh continuation frames
(`:if`, `:app-op`, `:app-args` …), ~4 frame `assoc` copies (one per argument
evaluated), and one `bind-variadic-params`+`merge` environment rebuild —
roughly 60 persistent objects per iteration against v2's ~13, before counting
v1's per-step `run-loop` predicate, `step-on-stream`'s `ready-for-ingress?`
(`contains?` + two `empty?` + `nil?` on the record, every step) and the
`telemetry/emit-snapshot` wrapper every `step` pays.

So the *count* of dispatches is nearly identical (22 vs 21). The 7.19× is the
*cost per dispatch* plus v1's loading tax — which is what the profiles show.

## 3. CPU: where the timed region actually goes

### v1 (`vm/run`, the 15.158 ms region — 269 attributed samples)

| deepest `yin.vm` frame | share of attributed samples |
|---|---|
| `yin.vm.semantic$transact_ast_datoms` | **55.0%** |
| `yin.vm.semantic$handle_return_value` | 13.8% |
| `yin.vm.semantic$handle_node_eval` | 8.6% |
| `yin.vm.semantic.SemanticVM/<init>` (record copy) | 7.4% |
| `yin.vm.semantic.SemanticVM/valAt` (field reads) | 5.6% |
| `yin.vm.engine$resolve_var` | 2.6% |

Leaf frames under those: `dao.space.index$cmp_field`, `Object.hashCode`,
`dao.data.btree$search` (the transact machinery), `PersistentArrayMap/indexOf`
(record field reads), `RT/get`, `Util/equiv`.

The dominant term is the surprise worth internalizing: **v1's timed `vm/run`
re-loads the program on every iteration.** The v1 harness queues the program on
an `:in-stream` ring buffer and times `(vm/run loaded)`; because the VM is a
persistent value, every Criterium iteration enters `run-on-stream` with the
cursor back at position 0, polls `ingest-next-program`, and pays
`semantic-vm-load-program`: `ast-datoms->tx-data` → `transact/prepare-tx` (btree
index maintenance over the ~500 schema datoms + program) →
`materialize-ast-datoms` (`current-state-seq`, set, `distinct`, `group-by`) →
`build-semantic-index-from-datoms` → per-node object arrays. That is 55% of the
timed CPU and 5.21 GB of the timed allocation (§4). Execution — the part the
redesign actually targeted — is the other ~45%: 13.8% + 8.6% + 7.4% + 5.6% +
2.6% ≈ 38–40% of samples in step bodies, field reads, and record construction.

### v2 (the 2.108 ms region — 367 attributed samples)

| deepest `yin.vm` frame | share |
|---|---|
| `yin.vm.semantic$vm_hot` (the hot loop) | **59.4%** |
| `yin.vm.semantic$apply_call` (call/tailcall transition) | 28.9% |
| `yin.vm.engine$resolve_var` | 9.5% |

98% of attributed time is the interpreter loop itself; there is no transact, no
indexing, no scheduler, no protocol layer above it worth a sample. Leaf frames
confirm the mechanics the design doc §6.1 predicted: `RT/nthFrom` (instruction
fetch `nth` on the image vector), `RT/get` + `KeywordLookupSite` (instruction
operand and record reads), `Numbers$LongOps/combine` (pc/fuel arithmetic),
`PersistentVector/cons` (`conj St`), `core$subvec` (argument slicing in
`apply-call`).

For contrast, the v2 *walker* bench in the same recording spends 83.4% of its
attributed samples in `ast_walker$cesk_transition` and 36.6% of its allocation
on `ASTWalkerVM` record copies (2.24 GB) — i.e. the walker still pays v1's
record-per-step tax, and the semantic VM's win is not "v2 is generally faster"
but specifically the linear machine's state discipline.

## 4. Allocation: the class tables are the smoking gun

### v1, fast-bench phase (10.32 GB sampled weight, includes the 5.21 GB load sub-phase)

| class | weight | share | what it is |
|---|---|---|---|
| `[Ljava.lang.Object;` | 2.73 GB | 26.5% | index/node-array + map spine churn (transact + per-step maps) |
| `yin.vm.semantic.SemanticVM` | **1.47 GB** | **14.2%** | one record copy per step |
| `clojure.lang.PersistentArrayMap` | 1.09 GB | 10.6% | control maps + frames + env |
| `clojure.lang.ArraySeq` | 1.03 GB | 10.0% | seq churn in per-step `assoc` paths |
| `clojure.lang.PersistentHashMap` (+ArrayNode, INode[], NodeSeq) | 1.28 GB | 12.4% | DaoDB/transact + env merges |
| `LazySeq`, `KeySeq`, `ChunkedSeq`, `MapEntry`, `ReentrantLock`… | rest | ~28% | materialize/distinct/group-by fallouts |

**1.47 GB of record copies alone.** At ~200–400 B per `SemanticVM` (24 fields),
that is on the order of 4–7 million record copies during the bench — one per
CESK step, exactly what the code says. Add the control/frame maps and the
interpreter proper allocates ~12.5 MB per run (see arithmetic below).

Example v1 hot-path allocation stacks (from the profile):

```
yin.vm.semantic.SemanticVM/assoc ← clojure.core$assoc
  ← yin.vm.semantic$handle_return_value ← semantic_step ← semantic_vm_step
  ← yin.vm.engine$run_loop ← yin.vm.engine$run_on_stream
```

### v2, semantic-bench phase (7.83 GB sampled weight)

| class | weight | share | what it is |
|---|---|---|---|
| `[Ljava.lang.Object;` | 1.82 GB | 23.2% | vector spines for `conj St` + zipmap |
| `clojure.lang.PersistentVector` | 1.12 GB | 14.3% | `push` (8 × `conj` per iteration) |
| `clojure.lang.MapEntry` | 1.08 GB | 13.8% | `zipmap` of closure params |
| `APersistentVector$Seq` + `$ChunkedSeq` | 1.15 GB | 14.6% | argument/vector walking in calls |
| `clojure.lang.PersistentArrayMap` | 0.97 GB | 12.4% | env `merge` + closure maps |
| `APersistentVector$SubVector` | 0.38 GB | 4.9% | `subvec` arg slicing in `apply-call` |
| `KeywordLookupSite$1` | 0.26 GB | 3.3% | record field access at effect points |

No `SemanticVM` in the table — the record is materialized once per `vm/run`
exit (`put-registers`), not per step. Every remaining class maps one-to-one to
the transitions in §6.2 of the design doc: push → vector, closure entry →
zipmap+merge, call → subvec. The two known residual costs are exactly the two
the doc reserves optimizations for (§6.4): persistent `St` and symbol-keyed
environments.

### Per-run arithmetic

Criterium quick-bench is wall-time-targeted, so each timed bench ran roughly
the same wall time (v1 fast-bench window 6.2 s; v2 semantic window 7.1 s — from
the recordings' phase windows):

| | v1 fast-bench | v2 semantic |
|---|---|---|
| mean per run | 15.158 ms | 2.108 ms |
| ≈ runs in window | ≈ 410 | ≈ 3,400 |
| sampled allocation | 10.32 GB | 7.83 GB |
| **≈ allocation per run** | **≈ 25 MB** | **≈ 2.3 MB** (≈11× less) |
| of which loading | ≈ 12.7 MB | 0 (outside timed region) |
| of which interpreting | ≈ 12.5 MB | ≈ 2.3 MB (≈5.5× less) |

Cross-check independent of the window assumption: allocation per attributed
CPU sample (both recordings sample at the same interval) is 38.4 MB/sample (v1
fast-bench) vs 21.3 MB/sample (v2 semantic) — v1 allocates ~1.8× more per unit
of CPU, and burns 7.19× more CPU per run. Both normalizations agree to within
the sampling slop.

### GC

| | v1.jfr | v2.jfr |
|---|---|---|
| young GCs | 75 (113 ms) | 242 (143 ms) — includes the walker bench |
| old / full | 2 / 19 (366 ms) | 1 / 18 (283 ms) |
| total stop-the-world pauses | 458 ms | 420 ms |
| peak heap after GC | 231.9 MB | 137.4 MB |

The ~18–19 full GCs in *both* files are Criterium's inter-phase collections,
not workload. The workload signal is the young-GC + heap picture: v1 drives the
heap to 232 MB and fills it ~9–11× faster per run; v2's recording did strictly
more benchmark work (walker + semantic, ~3,400 semantic runs vs ~410) in a
smaller heap.

## 5. A harness finding you should know about

The v1 run executed without `--fast-only`, so the file also contains the
slow-path quick-bench `(quick-bench (run-slow loaded))`. Its profile is
degenerate: 332 samples, 99.7% in `RT/count` under
`yin.vm.engine$halted_with_empty_queue_QMARK_`, with essentially zero
allocation. Reason: `run-slow` checks `(vm/halted? v)` *before* the first
`vm/step`, and a freshly queued v1 semantic VM reports `:halted? true` (it
hasn't ingested the program yet — `create-vm` initializes `:halted? true` and
`queue-vm` only attaches the stream). So the "slow path" timed loop returned
immediately on every iteration and measured nothing but the halt-check itself.
The 15.158 ms figure is therefore the fast path — the only meaningful number
that run printed. (Side effect: this also means the historic harness's
slow-path numbers for stream-fed stepping VMs measure the predicate, not the
machine; worth a follow-up on `master`.)

## 6. The decomposition

Putting the CPU shares on the 15.158 ms mean:

- ≈ 55% × 15.158 ≈ **8.3 ms/run is program loading** (transact → DaoDB →
  materialize → node index) that v1 pays inside every timed `vm/run` and v2
  pays once, outside timing, at ~0 cost (`load_image` is a single linear pass;
  the whole v2 load phase is 2.1 MB / 0.0 s).
- ≈ 45% × 15.158 ≈ **6.8 ms/run is v1's interpreter**: ~22 record transitions
  per iteration (~60 persistent objects), keyword dispatch, protocol hops,
  per-step halt/ingress checks — against v2's 2.108 ms of 21 flat
  instructions with registers in locals.

Ratios: loading ≈ 2.2×, interpreter ≈ 3.2×, and 2.2 × 3.2 ≈ 7.2 ≈ the observed
7.19×. The interpreter-only 3.2× is the like-for-like architectural number;
it is also consistent with the walker-relative measurements in
`docs/design/yin.vm.semantic.md` §8 (semantic 0.115× of walker on the JVM,
where the walker itself is the surviving record-per-step machine).

## 7. Why each architectural difference is worth what it is (code → JFR)

1. **Registers in `loop` locals, record touched only at exits**
   (`vm-hot`, `put-registers`). Deletes v1's 1.47 GB of `SemanticVM` copies
   (14.2% of its allocation) and the `valAt`/`indexOf` field-read tax visible
   in v1's leaves. v2's record class is absent from its own alloc table.
2. **Linear image + integer `case`** (`load-image`, opcode table). Instruction
   fetch is `nth` on a vector and dispatch an int compare; v1 paid keyword
   `case` + attribute-array indirection + control/frame map reads per step.
   Measured as `vm_hot` 59.4% self time with `RT/nthFrom` 11.4% of leaves —
   i.e. fetch is now a visible-but-small cost, and §6.4's host-array image
   remains in reserve.
3. **Load once, at the boundary** (`vm-load-program`, observer-owned stream).
   Removes the transact/materialize/index tax from every run (55% of v1's
   timed CPU, ~12.7 MB/run). This is also the invariant-driven part: the v1 VM
   owning its `:in-stream` is what made the reload per run possible at all.
4. **Continuation = next pc, not a frame map** (`:push`, `:call` with
   `:tail?`). The walker/v1 operand sequence allocates a frame map per
   operand and `assoc`s it per argument; v2 allocates one `conj` per push.
   Visible as PersistentVector 14.3% vs v1's ArrayMap+frame share.
5. **One scheduler check per run, not per step** (`engine/run-loop` around
   `vm-hot`, not around `step`). v1's `active?` + `ready-for-ingress?` +
   telemetry wrapper per step are the `RT/count`/`PersistentArrayMap/indexOf`
   leaves; in v2's semantic phase `run_loop` and the scheduler do not register
   a single attributed sample.
6. **Shared, unavoidable remainder**: closure entry still does `zipmap`+`merge`
   (v2's MapEntry 13.8% + ArrayMap 12.4%), exactly the two §6.4 reservations
   (lexical addressing, host-array operand stack). That is the honest floor of
   the current design on this workload.

## 8. Reproduction

```sh
# aggregate a profile (jfr CLI is not allowlisted in this repo's settings;
# the script uses jdk.jfr.consumer, which is what jfr print uses):
clj -M target/jfr_analysis.clj profiles/v1.jfr v1
clj -M target/jfr_analysis.clj profiles/v2.jfr v2

# dump the lowered image the v2 machine actually executes:
clj -Sdeps '{:aliases {:bench {:extra-paths ["test"]}}}' \
  -M:bench target/image_dump.clj

# v1 sources for the code-level reading:
git show master:src/cljc/yin/vm/semantic.cljc
git show master:src/cljc/yin/vm/engine.cljc
git show master:src/cljc/yin/vm/stream_driver.cljc
git show master:src/clj/yin/vm/bytecode_bench.clj
```

Tooling note: adding `Bash(jfr:*)` to `.claude/settings.json` would let future
sessions use `jfr print/summary` directly instead of re-deriving it through
Clojure.
