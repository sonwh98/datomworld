# D1 bounds declaration — DRAFT (static per-fixture gates; prepares the r9 tagged run)

Created: 2026-11-14. Role: VM Engineer (compiler provenance), thread
1794422563000 (D1 correspondence), round r8 deliverable 3.

**Status: DRAFT ONLY — no run has been performed under these ceilings,
and nothing here authorizes a tagged run or reliance.** This declaration
is written AFTER the r8 static corpus run (its numbers are that run's
measurements) and BEFORE any r9 tagged verification run, per the partial
round's requirement that a finite D1 declaration precede any tagged
run. The r8 run itself was NOT a ceiling-governed run: it is the
measurement source. The ceilings in section 3 become binding for the
first run declared under them (r9's tagged run), which must re-state
or tighten them from its own measurements.

The r8 gates are STATIC: no C lanes were run, no guest fuel consumed, no
traces recorded. Lane-quantity ceilings (guest instructions, trace
records and capacity) are therefore NOT declared here; r9 must measure
and declare them before its run. cc3 v2.1 historical maxima remain
baselines only, not D1 numbers.

## 1. Pinned corpus, revision, host, and settings

| Item | Pinned value |
|---|---|
| Worktree / branch | /Users/sto/workspace/datomworld-d1, branch d1-correspondence @ 56cc5ca3 + the uncommitted r1-r8 diff |
| Tracked diff (git diff, all of it test/yang/latte_oracle_test.clj) | 33,521 B, SHA-256 55c766cb5eef958f26b3ed03cb9169022cb66869fb8734777477fd65cd89dff1 |
| New untracked code | src/cljc/yang/correspondence_batch.clj; test yang/correspondence_batch_test.clj (all other r1-r7 files unchanged this round) |
| Corpus (the whole workload) | test/yang/latte_oracle/kernel_gate.edn, SHA-256 8763e273e08259686477315f41956d7005981327b7965bf01e83f9cce487c3e3, 155 fixtures |
| Frozen v1 evidence | test/yang/latte_oracle/slice9_site_tables.edn, SHA-256 e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75 (unchanged since r1) |
| Run record | collab/yang-clojure/1794422563000-engineer-d1-correspondence-r8.outcomes.edn, SHA-256 7c2f9a6a17bdd144ba67be0326dc4b6ebdb6a83a7297ab08ac82e4ef8aef943a — 155 :pass, 0 :refused, 0 :not-run, with per-fixture admission origin, storage delta, elapsed, row counts, site count, mutation-refusal stage |
| Upstream oracle | latte-kernel 86fca0d1d054af005cb37395eae04500d45e6279 (deps.edn :test pin) |
| Host | macOS 26.6.2 arm64, MacBookPro18,3, 17,179,869,184 B RAM |
| JVM / Clojure | OpenJDK 17.0.18 (Homebrew, 64-Bit Server VM), Clojure 1.12.4, -Xss64m via the :test alias |
| Driver | d1-corpus-batch! + test-d1-corpus-static-gates in test/yang/latte_oracle_test.clj; per-invocation run config target/d1-corpus-run.edn |

## 2. Workload and retained object set

One fixture static gate, held one fixture at a time (maximum
simultaneously retained fixtures: 1; each holder released before the
next):

- the admission: a fresh recorded admission coordinate
  [:source :program [run-scope ordinal] 0] (distinct ordinal per fixture
  within a batch run scope; fresh scope per batch invocation);
- the compiled input: compile-program output, stamped AST, site table,
  reconstruction input (captured forms/call/program/options);
- the binding: producer C1 tree + rows, stamped tree + rows,
  correspondence map, site-to-occurrence bindings, and the independent
  reconstructions checked against them;
- the persisted set: the whole v1 evidence map (content-addressed,
  written once for the corpus), the profile (once), and per fixture the
  reconstruction input, the binding snapshot, and BOTH row closures as
  individually addressed rows (the 128-nesting workaround);
- the fetch side: both complete row closures re-fetched with a
  100,000-row bound, the replayed root recompilation, and the
  independent gate (source hash, site declarations, bindings, C1 marker
  coverage) plus the per-fixture declaration-mutation refusal.

One durable store handle per batch invocation (cross-process reopen per
batch: full content-log validate-and-replay at open). The store is the
shared accumulating measurement store; nothing else writes it.

## 3. Ceilings (separate, finite, measured maxima + headroom)

Measured maxima are from the r8 run recorded in the outcomes file
(maximum-based rule below).

| Quantity | r8 measured maximum | Ceiling | Headroom / justification |
|---|---|---|---|
| Storage, per fixture (store growth, content log) | 17,903,978 B | 24,000,000 B | +34 percent over the maximum; covers admission-scope/ordinal string growth and row-set jitter |
| Storage, aggregate full-corpus store (155 fixtures, content log incl. lock) | 734,451,961 B | 1,000,000,000 B | +36 percent; a HEAD manifest and companion records are additional but small (declared before the run that adds them) |
| Row-closure size, per tree (pre-stamp or stamped) | 12,193 rows | 20,000 rows | 1.64x; the fetch bound of 100,000 rows stays as the gate's own hard refusal limit |
| Sites per fixture | 451 | 700 | +55 percent; corpus total 20,805 sites (equals the r2 independent inventory — informational cross-check) |
| Elapsed, per fixture static gate | 136,513 ms (with per-fixture close/reopen protocol) | 300,000 ms | 2.2x; covers the slower early-protocol shape and store-replay growth |
| Elapsed, per batch invocation (25 or fewer fixtures, one cross-process reopen) | about 12 min | 60 min | 5x; the operated hard background limit was 2 h per invocation |
| Peak used heap | not durably recorded in r8 | NOT DECLARED | must be measured and declared by r9's own declaration before its run; cc3 v2.1 1 GiB figure is a baseline, not adopted |
| Guest instructions / trace records | not applicable (static) | NOT DECLARED | r9 (C lanes) must declare; cc3 precedent 4,000,000 / 1,000,000 is a baseline only |

## 4. Exact reproducible measurement procedure

From the worktree root, per batch (the outcomes file is the progress
record: recorded outcomes are final; an interrupted batch loses at most
the in-flight fixture, which re-runs under a fresh admission on resume):

1. Write target/d1-corpus-run.edn with keys :D1_CORPUS_OUTCOMES
   (collab/yang-clojure/1794422563000-engineer-d1-correspondence-r8.outcomes.edn),
   :D1_CORPUS_STORE (target/d1-r8-corpus-store), :D1_CORPUS_BATCH 25 and
   (first batch only) :D1_CORPUS_SPOT "1".
2. Run clojure -M:test -n yang.latte-oracle-test -v
   yang.latte-oracle-test/test-d1-corpus-static-gates — initializes the
   outcomes file with all 155 ids on the first invocation, then records
   the next 25-or-fewer not-yet-run fixtures atomically.
3. Repeat step 2 until the summary reads fixture-count 155, pass 155,
   refused 0, not-run 0.
4. Without the run config present, the same deftest runs scratch mode
   (3 fixtures, fresh scratch store, resumability + finality + spot-check
   asserts) — the standing-battery form.

Actual r8 batch history (all against one shared store): batch 1 was
killed at a 10-minute background limit after 17 recorded fixtures, and
its resume was stopped after 14 more (31 total) to remove the per-fixture
close/reopen (that reopen replays a growing content log: per-fixture wall
had reached 136 s); batches then ran with one store handle per invocation
(cross-process reopen per batch) at 25, 25, 25, 25 and 24 fixtures — 155
total. A killed batch's partial log tail is truncated at the next open
by the store's own validate-then-truncate recovery; the re-run
fixture's identical content dedupes, so per-fixture deltas remained
additive (sum of deltas = final store bytes, exactly).

## 5. Pass/fail rule

Maximum-based (cc3 v2 precedent): a ceiling is met only if the MAXIMUM
observed value is at or under it; any single sample over its ceiling is
EXCEEDED. Medians and totals are informational. The r8 run's samples
are recorded per fixture in the outcomes file and were not governed by
these ceilings (they precede this declaration).

## 6. Limits of these measurements (named, not hidden)

Elapsed figures are warm-JVM wall clock on this host class and mix two
reopen protocols (31 fixtures with per-fixture close/reopen, 124 with
per-batch); storage figures are content-log bytes including dedup across
fixtures (a per-fixture-fresh-store retention would be strictly larger);
the store has no HEAD manifest (a published retention artifact adds one
plus ownership records); heap sampling existed only transiently per
batch and no durable heap maximum was recorded.

## r9g laptop pilot supplement — measurements, not a final declaration

The r9g dispatch explicitly authorizes tagged **pilot** measurements before a
separate declaration-governed verification run. The pilot progress file
`1794422563000-engineer-d1-r9f-measurements.edn`, SHA-256
`1849a6d0fb8d9dece75306d20fafc8408b9d75b45fb12f8a9b01076391911cef`,
records 155 passes, no refusals, each labeled `:pre-declaration true`, and
zero detected loss for 310 lane traces. Pilot safety cap: 4,000,000 vm/step
calls per lane. Process-wide heap sampler: totalMemory minus freeMemory every
5 ms, including batch store opening; sampled r9g batch peaks 3,533,494,976,
3,554,687,936 and 3,485,220,736 bytes. This can miss brief peaks, includes
other process allocations and is not a live-object count. A previous r9e
static batch sampled 3,677,225,328 bytes. Host was the r9e laptop with OpenJDK
21.0.2, Clojure 1.12.4, -Xss64m, pinned at ccd3648d plus the uncommitted
measurement helpers. The first pilot was `:minimal-last`, not
`:beta-eq-false`; the latter was measured in r9g.

| Metric, maximum per fixture/lane | Walker | Semantic |
|---|---:|---:|
| Guest vm/step calls | 2,544,482 | 1,398,649 |
| Trace records | 2,345 | 2,345 |
| Trace vector, printed EDN UTF-8 bytes | 67,500 | 67,500 |
| Lane run wall time | 9,380 ms | 6,245 ms |
| Trace drain wall time | 2 ms | 3 ms |

Maximum fixture fetch wall time was 3,490 ms and total pilot fixture time
19,121 ms. The lane timer includes setup/load/run; the fetch timer includes
row fetch and AST conversion. This does not distinguish compile, expand,
load, publish, query and verify phases or provide B/S baselines. The trace
bytes are not retained ring occupancy or a worst-case per-record byte bound;
ring capacity remains 1,000,000 records and drains after halt. Individual
fixture observations, origins and addresses are in the progress file.

**Still draft, not a declaration**: the input occurrence/depth/path and exact
serialized-object profile is being gathered; byte capacity/peak retained
trace memory, baseline/added-work comparisons, independently enforceable
max-based time/memory limits, and the complete reconstruction/verification
closure remain unresolved. These pilot maxima do not authorize a bounded
verification run by themselves.

### r9g static profile supplement

`1794728000000-engineer-d1-r9g-static-profile.edn` (SHA-256
`afc6ae799b6d4e3747855e292fc603570b75dfe605dd1fd2af6e239feeec6e9a`)
records 155 passes. Maxima respectively for before/stamped trees:
rows 10,732 / 12,193; occurrences 42,848 / 46,005; applications
12,085 / 12,987; depth 462 / 464; sum of per-application canonical-CBOR
encoded path lengths 3,423,911 / 3,730,572 bytes. Maxima are independent
and need not come from the same fixture. The maximum encoded direct-object
sizes were input 4,254,284 bytes, snapshot 12,266,106 bytes, profile 1,102
bytes and whole evidence 6,334,093 bytes. The profiling process sampled
3,776,231,752 bytes process used heap at 5 ms, including store opening; this
is not live-object memory and is not comparable to a tagged-verification peak
without matching procedure. Maximum full profile time was 12,541 ms. This
pass fetched each stored tree, counted its occurrence places and encoded each
application path separately. The original profiling driver encoded the
missing sentinel rather than explicitly refusing if a direct object were
absent; r9e independently fetched all 930 direct addresses after reopening,
and the per-fixture input and snapshot are additionally checked by the
profile's `check-retained-trees!`. The driver has been corrected for future
runs; this limitation is not erased retroactively.

The maxima supply more declaration inputs; they do not fill the outstanding
trace-memory, baseline/delta, per-phase and enforcement gaps listed above.
This remains a draft and does not authorize a bounded verification claim.

### r10 trace graph pilot — measurement, not a finalized ceiling

`1794729000000-engineer-d1-r10-trace-memory.edn` (SHA-256
`42e60986b3ef0fbf4f8574aa678ff75ed12b214cc45a911a8901dc8b9d6431e7`)
records 155 pre-declaration passes, 310 completed lanes and no detected
prefix loss. Maximum per lane: 2,345 records, 67,500 UTF-8 bytes for the
printed vector, 45 bytes for one printed record and 311,144 reachable JVM
heap bytes from a halted trace-ring handle. The graph size sums Java
`Instrumentation.getObjectSize` for identity-deduplicated objects reachable
from the handle; it includes objects also reachable elsewhere, does not count
native memory, assumes the specified JDK layout and is not exclusive retained
size. It is sampled at halt, before drain, with this oracle's drain-after-halt
schedule. Its observed maximum is not a proof of heap usage for 1,000,000
arbitrary trace records. The process used-heap sampler observed a maximum of
3,589,539,800 bytes for the 154-fixture batch, at 5 ms intervals, including
store opening. Its comparability limits remain as above. The agent source and
commands are scratch artifacts named in the r10 findings, not a committed
retention or verifier dependency.

The partially implemented runtime checker can refuse observed step, elapsed,
used-heap, trace-record and printed-byte maxima at VM step boundaries and the
progress writer can preserve a final failed-bound outcome. The checker is not
an OS memory cap: one step may exceed a limit before the next boundary, and
native/process memory is outside the used-heap reading. It has not been wired
into a complete bounded verifier or exercised under a declared corpus run.
The B/S and historical two-field comparisons, phase accounting, complete
recipe and verifier, and the remaining enforceable maxima still need work.

### r10 trace-free benchmark supplement — not complete B/S

The trace-free benchmark record `1794729000000-engineer-d1-r10-baseline-pilot-02.edn`
(SHA-256 `d57b6a06f6a05e8cf2e47efa1ab1c3c4c6c13f52788d306da6137378e2784e92`)
contains 155 passes. The helper checks the retained input's assembled suffix,
removes the fixed trace bootstrap and caller-site prelude, restores captured
untraced source forms and retains the library prefix chosen for the original
instrumented program. Its benchmark roots are different code and are not
claimed as roots admitted under the r9e source origins. Each fixture compiles
twice in one JVM, constructs an ephemeral application-path index and executes
each backend twice on equal benchmark roots; 155 pairs had identical step
counts per lane. Maximum walker/semantic baseline steps were 1,679,842 /
927,761; the largest fixture-paired tagged-pilot increases were 864,640 /
470,888 steps, with maximum fixture-paired ratios approximately 1.822 /
1.810. These compare separately compiled roots and separate processes, and
are instrumentation-cost observations rather than semantic equivalence proof.
Maximum first compile was 60 ms and the local projection/path-index work
6,814 ms, with maximum index size 4,257,499 canonical-CBOR bytes. The named
first and second compiles are in a shared JVM, not fresh-cache cold and warm
experiments. The index is not persisted C1, instruction provenance, or a
query over independently published relations; the column named `:s-steps`
is a repeat execution after creating that local index, not a complete S run.
The first baseline attempt was refused due to metadata on the fixed, discarded
bootstrap; it is preserved separately in `1794729000000-engineer-d1-r10-baseline-pilot.edn`.

These figures do not supply the missing full B/S experiment, historical tracer
comparison, tracked load/publish/query timings or runtime-enforced ceilings.
This remains a draft, not a declaration under which a verification run may
be claimed.

### r10 historical two-field tracer pilot — measured comparison

`1794729000000-engineer-d1-r10-two-field-pilot.edn` (SHA-256
`e6e57f3f2bd22f156bac43c981d3856dadbd7c7859284e2497263a4b293cae8e`)
records 155 pre-declaration passes, with no detected trace-prefix loss. Its
body-entry instrumentation follows `kernel-trace-form` in revision
`f2d7df870e5bb9953d44d1947c7ee4806de087a3`, applied to the current retained
fixture inputs and the library prefix assembled for the current instrumented
program. It is not a reproduction of the entire historical host environment
and its benchmark roots are not claimed as persisted occurrences of the r9e
admissions. Compile repetitions share a JVM.

The maximum walker/semantic steps were 1,705,653 / 941,841; maximum trace
records 2,345 and printed UTF-8 vector bytes 59,017 for either lane. Against
r9g's tagged pilot, the largest fixture-paired walker/semantic step increases
were 838,829 / 456,808; largest fixture-paired ratios approximately 1.777 /
1.766. Record counts agreed per fixture and lane across the two experiments;
that agreement is not a proof of equal records or outcomes. Maxima and ratios
are over separate benchmark executions and roots. These results fill the
requested historical-tracer comparison but not the full provenance B/S,
fresh-cache cold/warm phase study, enforcement, or D1 verifier gaps. This
file remains a draft, not a finalized run declaration.

### r10 candidate ceilings for measured fields — non-binding

These are reviewable candidate numbers, **not a finalized declaration and
not authority to start the bounded tagged verification**. They use maxima
above, rounded upward with at least roughly 50% headroom; they must be pinned
alongside the missing fields and actual enforcement before the run.

| Measured quantity | Observed maximum | Candidate ceiling |
|---|---:|---:|
| Walker guest steps | 2,544,482 | 4,000,000 |
| Semantic guest steps | 1,398,649 | 2,200,000 |
| Trace records per lane | 2,345 | 5,000 |
| One printed UTF-8 trace record | 45 bytes | 100 bytes |
| Printed trace vector per lane | 67,500 bytes | 150,000 bytes |
| Reachable trace-ring JVM graph at halt | 311,144 bytes | 650,000 bytes |
| Sampled process used heap across static and tagged pilots | 3,776,231,752 bytes | 6,000,000,000 bytes |
| Combined tagged pilot fixture wall time | 19,121 ms | 60,000 ms |
| Stamped rows / occurrences / applications | 12,193 / 46,005 / 12,987 | 20,000 / 75,000 / 20,000 |
| Stamped maximum depth / encoded application-path bytes | 464 / 3,730,572 | 750 / 6,000,000 bytes |

All listed candidates are maximum-based: any one observation greater than
its ceiling fails. The graph ceiling is an at-halt observation, not an
online buffer cap; the used-heap candidate is not a native/process RSS bound.
The ring is physically configured for 1,000,000 records, while the proposed
5,000-record and printed-byte limits would have to be checked by a completed
bounded driver. A max of 100 printed bytes and 5,000 records implies at most
505,001 bytes in the printed vector (two brackets plus records and separators)
for an accepted trace; this is a logical serialization bound, not a bound on
physical heap. No guarantee is claimed until the checker is wired, limit
failures are durably recorded, all required independent inputs/phase limits
are present, and the full declaration is frozen before its run.

### r11 integrated guard pilots — still not a declaration

The pre-declaration integrated-gate pilots found that the prior **60,000 ms
combined-fixture candidate is inadequate**: the larger selected fixture took
62,949 ms inside the gate after structural checks, and 69,876 ms including
retained input fetch and replay compilation. Neither measurement includes
opening the full content store. The earlier timed variant without structural
walks observed 59,390 ms inside the gate. Both pilots remained explicitly
pre-declaration and used 700 exploratory static-callee/arity queries, not the
complete upstream credit set; they are not bounded verification results. A
150,000 ms candidate would be over twice the observed 69,876 ms complete
fixture path, but is **not declared**: workload coverage and full host-phase
measurements remain incomplete, and store opening needs a separately justified
limit. The earlier 60,000 ms value was never a frozen bound, so no declared
ceiling has been raised and no bounded run result is overwritten.

The current test-only fixture gate exercises the independent static checker,
a declaration mutation, both guarded lanes and scoped queries; it refuses
missing runtime, tree or trace-graph limits, checks tree size before lane
execution, and can preserve measured exceedances as failures. It is not the
complete D1 verifier, and its fixture timer is checked only at host-phase
boundaries. The actual corpus claim set, cold/warm full C1 baseline,
fetch/replay/store-open limits, executable recipe, derivation/provenance
closure and integrated process-level failure handling remain open. Thus the
candidate table above is not a complete declaration and does not authorize a
corpus verification run.

### r12 full-claim pilot — measurements, not a declaration

The pre-declaration pilot in `1794735000000-engineer-d1-r12-claim-phase-pilot.edn`
ran all 155 retained fixtures through the existing gate with 165 scoped
symbol/arity queries each. All 155 pilot outcomes passed; this is not D1
verification. Store opening took 195,419 ms separately; the 5 ms sampled
process used-heap peak was 3,880,862,784 bytes. The maximum fixture time was
67,358 ms, below the earlier r11 69,936 ms measurement supplied in the r12
handoff. The maximum per-phase times were: row fetch 2,731 ms, direct content
fetch 2,535 ms, replay 1,245 ms, gate 61,628 ms, tree check 4,614 ms,
independent static check 22,099 ms, mutation check 9,367 ms, and query 288 ms.
These are independent maxima, not additive phase timings. Walker and semantic
lane maxima were 18,367 and 7,223 ms respectively. The pilot used safety caps,
not frozen bounds. Samples and phase-boundary checks do not provide a hard
process memory or wall-clock limit.

The 23 supplied carrier relationships were subsequently rederived from the
source and compared in a separate preflight, with agreement; the executable
source and resolution environment are not yet retained as a complete,
independently verifiable recipe. Static C1 comparisons are a separate pilot
and do not fill the remaining end-to-end B/S/T and provenance requirements.
The measurements above therefore do not finalize this draft or authorize a
tagged verification run.
