# J6g rebase fix findings

Inspected checkout and origin/master: `746b2c34`. Read the requested
`git diff 05a596c5 origin/master -- src/cljc/yin/repl.cljc src/cljc/yin/repl/main.cljc`.
No workload was launched, locally or remotely. Session ID is not exposed by
this session's tools; no ID invented.

## Attribution

The demonstrated failure is a watchdog/worker wire-format mismatch, not an
established composition deadlock. `src/dev/jolt_workload_w.clj:212` emits
`W-SAMPLE` followed by an EDN **map**, containing timestamps and both latency
measurements. The original `src/dev/jolt_workload_w_run.clj:28` required
`(number? sample)` and set `:sample-error` for every such map. Its kill branch
(now at `src/dev/jolt_workload_w_run.clj:155`) reports `:invalid-sample`.
The actual deadline is still 300000 ms (`:hard-cap`, now line 167); a failure
at ~46 s with `:invalid-sample` is not that cap firing.

The original W-STATE was emitted AFTER both `driver/repl-step`/outbox draining
and `wake/sleep-fds!` returned (original worker lines 246–253). Thus a
W-STATE containing owner-rounds 1 proves round 1 got past those calls.
Furthermore, the invalid-sample branch is reached only after a W-SAMPLE line
arrives. A well-formed worker sample means B received a reply, which occurs
after upgrades, frame construction and A's first positive write. The
watchdog's samples remain zero because it rejects the map. Its phase stays
"loading" because the original worker emitted no W-PHASE lines. These
observations cannot establish a first-round block. Exact time spent before
the first sample needs the next instrumented run; no measured attribution
to frame construction, compilation, or a particular composition call is
claimed here.

Composition candidates ruled out as explanations of this rejection:

- `src/cljc/yin/repl/main.cljc:282` retains the one-argument boot by delegating
  to `(boot opts nil)`. Lines 287–289 open a contributed store only when a
  composition provides its hooks. Lines 303–310 still return driver state
  with engine at `:repl`, adding transport state at sibling `:session` only
  when present. This harness selected no durable/DHT store before the fix.
- `src/cljc/yin/repl/main.cljc:823` owns the full owner tick; lines 850–875
  step the composition, recheck, step the driver and drain its outbox.
  Using only the frozen driver bypassed these host responsibilities, but
  that is not evidence of a synchronous first-round block.
- `src/cljc/yin/repl.cljc:1007` selects generic publication policy, falling
  back to the store's `:publish-opts` at line 1009. The memory store comes
  from `src/cljc/yin/repl/store.cljc:153`. No DHT publication policy is
  injected in this harness. A's payload is carried by a separate wsj
  instance, never appended to the engine's code-index stream. Code-index
  byte budgets therefore do not gate A's paced TCP writes.
- `src/cljc/yin/repl.cljc:2895` still exposes the index-publication summary
  from `[:indexer :materialization]`; the worker's two cursor paths at
  `src/dev/jolt_workload_w.clj:189` remain correct. No path migration was
  necessary.

Crucially, BOTH harness files also have zero committed diff between 05a596c5
and origin/master. The map-versus-number defect exists in that base too.
The supplied historical passing run therefore needs its actual launcher,
worker and verifier artifacts reconciled by the orchestrator: this exact
watchdog cannot accept this exact worker's first sample on either base.
This finding does not invalidate separately verified measurements, but
prevents attributing their difference to composition drift from these
sources alone.

## Harness edits

Only the two authorized source files were edited, plus this requested brief.

- Worker boot explicitly selects `:mem` and supplies nil composition, keeping
  the existing memory workload topology. Owner rounds use public
  `repl.main/step-all` with no served endpoint and retain its returned state
  and output lines. That function performs the single driver step and
  outbox drain; the harness does not drain twice.
- Added first-two-round W-CALL enter/leave records around step/drain and
  socket pumping, and W-PHASE markers for boot, B upgrade, A upgrade, A frame
  encoding and running. These expose an actual blocking boundary without
  continuous call tracing during the latency cohort.
- Ten-second W-PROGRESS includes the original statistics and both original
  cursor paths. Watchdog telemetry now retains that progress (and accepts
  legacy W-STATE) rather than presenting a permanently empty progress view.
- Watchdog accepts the worker's sample maps, validates finite nonnegative
  timing fields and timestamp/latency consistency, and samples only the
  prospective cohort. It reports both p99 values with the worker's
  nearest-rank quantile. It recognizes FAILURE output and requires a passing
  W-RESULT alongside a zero process exit.

A's 120 s pacing, independent B scheduler at 100 ms, 600-offer cohort,
500-sample floor, both 250 ms latency gates, binomial confidence calculation,
all 12 worker gates, native socket handling and independent 300 s watchdog
are unchanged. No external five-point verifier was edited.

## Validation and expected next verification

Passed local cljstyle check on both files and `git diff --check`. A temporary
Babashka check loaded only watchdog definitions (never its process-launching
entry point) and passed valid/map sample acceptance, malformed and NaN sample
rejection, timestamp/latency mismatch rejection, cohort filtering,
nearest-rank p99, balanced/mismatched call tracing, result/failure/progress
parsing, and partial-line assembly. No workload performance claim follows
from these checks.

Next orchestrator run should survive its first valid B sample, accumulate
cohort samples and show both p99s and cursor paths. Setup delays should now
have named phases; an actual early owner call stall will retain an active
W-CALL until completion or the unchanged 300 s cap. If execution completes,
the existing worker gates decide pass/failure; this patch does not promise
600 samples or any particular latency. If a composition call actually
blocks, send its active trace and run artifacts to the convergence/exception
owners. No composition-side change is justified by the current read evidence,
and none was made.

Historical figures are supplied pre-drift evidence only: 600 samples,
3.85 ms RTT p99 / 5.79 ms scheduled-arrival p99 for the named j6ggate29 run;
the separately cited 8.75 ms p99 is also historical, not a new measurement.
Current-master performance remains unmeasured in this fix round.
