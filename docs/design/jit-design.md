# Datom-Native VM Execution Trace Surface for JIT Observation and Runtime Optimization

## Summary
Introduce a first-class execution trace stream for VM runs, represented as datoms (`[e a v t m]`), so JIT can observe runtime behavior without hidden callbacks or mutable side channels.  
JIT remains advisory: it emits optimization patch datoms, and VM applies them only through explicit, causal patch-stream processing.

## Surface API (Decision Complete)
Add a trace protocol and kernel helpers in `src/cljc/yin/vm.cljc`.

1. New protocol: `IVMTrace`
- `run-traced [vm opts]`
- Contract: executes VM to halt/blocked and returns:
  `{:vm final-vm :trace trace-datoms :run-id run-eid}`

2. New protocol method for stepwise tracing:
- `step-traced [vm opts]`
- Contract: executes one VM step and returns:
  `{:vm next-vm :trace-step step-datoms :run-id run-eid :step step-idx}`

3. Keep existing `vm/run` unchanged
- `vm/run` remains non-observability baseline for compatibility and low overhead.
- JIT and tooling use `run-traced` or repeated `step-traced`.

4. Trace stream access pattern
- Bounded run stream: `(:trace (vm/run-traced ...))`
- Open stream behavior: repeated `step-traced` calls produce append-only step streams in `t` order.

## Trace Datom Schema (What State Goes in Stream)
Define trace attributes (new namespace, for example `:yin.trace/*`) and emit only JIT-relevant state.

1. Run-level facts
- `[:run-eid :yin.trace/type :yin.trace/run t m]`
- `[:run-eid :yin.trace/vm-kind :register|:stack|:semantic t m]`
- `[:run-eid :yin.trace/program-id <hash-or-id> t m]`
- `[:run-eid :yin.trace/start-ip <int> t m]`
- `[:run-eid :yin.trace/end-reason :halted|:blocked|:error t m]`

2. Step-level facts (one step entity per executed instruction)
- `[:step-eid :yin.trace/run :run-eid t m]`
- `[:step-eid :yin.trace/idx <int> t m]`
- `[:step-eid :yin.trace/opcode <keyword|int> t m]`
- `[:step-eid :yin.trace/ip-before <int> t m]`
- `[:step-eid :yin.trace/ip-after <int> t m]`
- `[:step-eid :yin.trace/reg-read [r...] t m]`
- `[:step-eid :yin.trace/reg-write [r...] t m]`
- `[:step-eid :yin.trace/branch-taken true|false t m]` (when branch op)
- `[:step-eid :yin.trace/call-target <symbol|closure-id|primitive-id> t m]` (when call)
- `[:step-eid :yin.trace/argc <int> t m]` (when call)
- `[:step-eid :yin.trace/ret-val-tag <type-tag> t m]` (when return)
- `[:step-eid :yin.trace/deopt-guard-failed? true|false t m]` (if guard exists)

3. Periodic frame snapshots for reconstruction (not every step)
- `[:snap-eid :yin.trace/run :run-eid t m]`
- `[:snap-eid :yin.trace/step-idx <int> t m]`
- `[:snap-eid :yin.trace/ip <int> t m]`
- `[:snap-eid :yin.trace/regs <compact-reg-view> t m]`
- `[:snap-eid :yin.trace/env-shape <shape-id> t m]`
- `[:snap-eid :yin.trace/k-depth <int> t m]`

4. Minimal required tags for JIT specialization
- Value type tags only by default (`:int`, `:float`, `:bool`, `:closure`, `:nil`, `:map`, `:vec`, etc.)
- No full value payload unless explicitly enabled in opts.

## JIT Patch Stream (Advisory Authority)
JIT emits a separate patch stream, never direct mutation.

1. Patch datoms example attributes (`:yin.jit/*`)
- `:yin.jit/target-program-id`
- `:yin.jit/target-ip-range`
- `:yin.jit/guard` (shape/type predicates)
- `:yin.jit/replacement` (optimized block reference)
- `:yin.jit/version`
- `:yin.jit/status :proposed|:accepted|:rejected|:rolled-back`

2. VM application model
- VM checks patch stream at explicit safe points (function entry, loop header, backward jump target, return boundary).
- On accepted patch, VM switches to new versioned block with guard.
- On guard failure, VM emits deopt datom and falls back to baseline bytecode.

## Options/Defaults (Locked)
For `run-traced` and `step-traced`:
- `:snapshot-every` default `64` steps
- `:emit-values?` default `false`
- `:emit-env?` default `:shape-only`
- `:max-trace-steps` default `nil` (no cap)
- `:trace-level` default `:jit-core` (fields listed above)

## Implementation Plan (Files)
1. `src/cljc/yin/vm.cljc`
- Add `IVMTrace` protocol.
- Add shared helpers for run-id, step-id, and trace datom emitters.

2. `src/cljc/yin/vm/register.cljc`
- Implement `step-traced` by instrumenting `step-bc` boundary.
- Implement `run-traced` as loop over `step-traced`.
- Keep `reg-vm-run` unchanged.

3. `src/cljc/yin/vm/stack.cljc` and `src/cljc/yin/vm/semantic.cljc` (optional phase 2)
- Implement same protocol for cross-backend consistency.
- If phased rollout: register VM first, others return `ex-info` “trace not implemented yet”.

4. `test/yin/vm/register_test.cljc`
- Add tests asserting trace causality and required fields.
- Keep existing `compile-and-run-bc` tests unchanged.

## Test Cases and Scenarios
1. Trace determinism
- Same bytecode + env yields same opcode/ip sequence trace.

2. Step/run consistency
- Concatenated `step-traced` events equals `run-traced` events for same input.

3. Causality correctness
- `t` strictly monotonic within run.
- `m` links step datoms to run entity and patch/deopt causes.

4. JIT signal sufficiency
- Hot loop program produces repeated `(ip-before, opcode)` signatures.
- Branch-heavy program records `branch-taken` distribution.
- Polymorphic call site records multiple `call-target`/type tags.

5. Patch safety
- Accepted patch emits status transitions and version facts.
- Guard failure triggers deopt event and baseline fallback with correct final value.

6. Backward compatibility
- Existing `vm/run` behavior and current tests remain unchanged.

## Assumptions and Chosen Defaults
1. Canonical observability format is datoms, not callbacks or mutable listeners.
2. JIT is advisory and cannot mutate active execution implicitly.
3. Default trace is compact and type-tag oriented to limit overhead.
4. Register VM is the first backend to implement tracing, others follow same interface.
5. `vm/run` remains untouched for compatibility and low-overhead execution paths.
