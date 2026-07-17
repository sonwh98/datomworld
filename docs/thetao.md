# Experimental Continuation-Stream VMs on `dao.stream`

## Summary

Build two new experimental VMs from scratch, entirely in `.cljc`, under `src/cljc/thetao`, with no modifications to existing code.

Both VMs must use `dao.stream` as the execution substrate. The main VM loop is:

1. fetch the next runnable continuation from a `dao.stream`
2. reduce that continuation recursively until a hard boundary
3. emit successor continuations back onto a `dao.stream`
4. emit effect/state/event datoms onto explicit streams
5. stop when the execution stream has no runnable continuation available

The two experiments are:

- **Option 2**: continuations carry bytecode control
- **Option 3**: continuations carry continuation-algebra control

This is an isolated experiment to compare VM design tradeoffs, not a replacement for existing Yin VMs.

## Research Question

Is `dao.stream` viable as the execution substrate at competitive throughput, and does bytecode-carried or algebra-carried control give the better observability/perf tradeoff?

A successful Phase 1 answers:

- per-step cost on stream substrate vs a conventional bytecode VM
- whether either option dominates the other on event-trace clarity
- whether content-addressable continuation state unlocks replay and structural sharing in practice

## Architectural Anchor

VM state is content-addressable. Every continuation, environment, and register frame has a canonical hash identity, materialized at stream boundaries. Sharing, memoization, replay, and migration follow from this without further mechanism.

Consequences:

- Continuations are datoms in declared dimensions (see `docs/agents/datom-spec.md`). Each option publishes its own continuation dimension; both project to d3 for observability.
- Compound slot values (environment, parent continuation, register frame) appear by content hash, not by pointer or by entity ID. Structural sharing across continuations is automatic.
- **Persistence invariant** (correctness): a value crossing into a persistent stream (event/fact/effect/effect-response) carries its content hash. The ephemeral-to-persistent transition is the only place content hashes are required for correctness. Replay, content addressing, and dedup depend on this transition holding.
- **Consequence** (derived from the invariant plus the execution stream being ephemeral): the hot loop has no correctness obligation to hash. Inside the execution stream, continuations reference parents, environments, and register frames by local entity ID (the d5 local gauge, see datom-spec "Components"). Hash and local id are two coordinates on the same value; the persistence boundary chooses which one is observable.
- **Performance note** (not an invariant): hashing in the hot loop for dedup, memoization, or content-store lookup is voluntary. Implementations may pay that cost when measurement justifies it; the spec does not require or forbid it. The default should be to avoid speculative hashing.
- Cycles are forbidden at the value layer. Continuation parent chains are acyclic by construction: the parent reference (local id or hash) exists before the child references it. Algebra forms must not introduce self-referential continuation values.
- Hash datoms use `m=1` (`:db/derived`) per the d5 spec and are excluded from their own computation.

## Implementation Changes

### New code location

All code lives under `src/cljc/thetao`.

Recommended structure:

- `src/cljc/thetao/vm/common.cljc`
- `src/cljc/thetao/vm/streams.cljc`
- `src/cljc/thetao/vm/events.cljc`
- `src/cljc/thetao/vm/derived_state.cljc`
- `src/cljc/thetao/vm/bytecode.cljc`
- `src/cljc/thetao/vm/bytecode_vm.cljc`
- `src/cljc/thetao/vm/algebra.cljc`
- `src/cljc/thetao/vm/algebra_vm.cljc`
- `src/cljc/thetao/vm/content_store.cljc`
- `src/cljc/thetao/vm/examples.cljc`

No edits to existing `yin` or `dao` namespaces.

### `dao.stream` execution model

Use explicit `dao.stream` instances for runtime coordination. Each stream declares its dimension and persistence layer.

Required streams:

- **execution stream**
  Carries runnable continuations. The only source of work the VM loop consumes.
  Dimension: a declared continuation dimension per option (`d_cont_bc` for Option 2, `d_cont_alg` for Option 3).
  Persistence layer: ephemeral entity layer. Array-backed, append-only, unindexed, integer cursor.
  Because the stream is ephemeral, emission to it has no correctness requirement to compute content hashes. Inter-continuation references (parent, env, register frame) use local entity IDs. Hash materialization happens at the ephemeral-to-persistent transition. Hashing here for dedup or memoization is permitted but optional.

- **event stream**
  Carries runtime events: blocked, resumed, halted, effect-requested, effect-resolved.
  Dimension: d5 (provenanced temporal facts).
  Persistence layer: persistent. Content hashed on emission.

- **fact/state stream**
  Carries state-affecting datoms emitted by execution.
  Dimension: d5.
  Persistence layer: persistent. Content hashed on emission.

- **effect stream**
  Carries outbound host/effect requests when needed.
  Dimension: d5.
  Persistence layer: persistent.

- **effect response stream**
  Carries inbound responses that can resume blocked continuations.
  Dimension: d5.
  Persistence layer: persistent.

The VM loop must not use hidden `:ready-queue`, `:wait-set`, or `:parked` tables. If a continuation blocks, the block condition must be represented explicitly in emitted data, and resumption must occur by writing a resumed continuation to the execution stream.

Cursor advance on the execution stream is `cursor++` plus a load, mechanically comparable to an instruction-pointer increment in a conventional bytecode VM. The array-backed, unindexed specification protects this property.

### Shared experimental runtime

Build a small runtime layer around `dao.stream`, separate from existing Yin engine code.

Responsibilities:

- open and manage the required streams with their declared dimensions
- maintain a content-addressed store for compound slot values (environments, parent continuations, register frames) referenced by hash
- read the next continuation from the execution stream via an integer cursor
- reduce one continuation locally until boundary
- write successor continuations to the execution stream
- write events and facts to their streams; compute content hashes on emission to persistent streams
- determine quiescence from stream state, not hidden scheduler fields

The runtime may maintain derived indexes for convenience, but only as caches reconstructed from stream history.

### Continuation representation

Both VMs represent continuations as datoms in a declared dimension.

Each option publishes its dimension via the meta-protocol (datom-spec, "META-PROTOCOL"):

- `:dim/arity`, `:dim/slots`, `:dim/encoding`, `:dim/projection-to d3`, `:dim/lift-from d3`
- the content hash of the dimension subgraph is the dimension's identity

Both dimensions project to d3 so that event traces remain fact-shaped and queryable. Cross-VM observable equivalence is defined as equality of the d3 projection of their event streams, not equality of their continuation tuples.

Slot encoding follows the canonical-encoding rules in the datom spec. Slot values are either inline canonical primitives (no larger than 32 bytes) or 32-byte content hashes. Environments, parent continuation references, and register frames appear as content hashes.

Reserved-entity space:

- VM-universal attributes (`:vm/ip`, `:vm/env`, `:vm/parent`, `:cont/blocked-on`, etc.) live in the reserved range (entities 2 to 1024); their meaning is universal across any VM instance and they must not migrate.
- Per-program user entities start at 1025.

### Shared continuation semantics

Both VMs must share the same observable behavior for the core subset.

Supported subset:

- literals
- variable lookup
- lambda creation with lexical capture
- application
- `if`
- tail calls
- `vm/current-continuation`
- `vm/park`
- `vm/resume`
- `stream/make`
- `stream/put`
- `stream/cursor`
- `stream/next`
- `stream/close`
- `dao.stream.apply/call`
- pure primitives needed for examples

Hard boundaries:

- blocking `dao.stream` read/write
- explicit `park`
- explicit `resume`
- host/module effect
- `dao.stream.apply/call`
- halt

When a boundary is hit, reduction stops and emits data. It does not silently continue through hidden scheduler state.

Boundary discipline (the speed lever):

- Between boundaries, reduction operates on a local mutable register frame. No allocation per instruction.
- At a hard boundary, the resulting state is materialized as a continuation datom in its canonical form and emitted to the execution stream. The emission carries a local entity ID; computing a content hash here is optional and not required for correctness.
- When emission targets a persistent stream (event/fact/effect/effect-response), the content hash is computed at that point and the persisted datom carries the hash. This is the only place hashing is required by the persistence invariant.
- The pre-boundary working frame is not externally referenceable. The post-boundary continuation is referenceable by local id while ephemeral, and by content hash once persisted.
- Performance scales with instructions per boundary, not with total instruction count. A program with 50 reductions per boundary pays one allocation per 50 instructions; no hashing happens on this path unless an implementation chooses to add it.

Dispatch:

- Continuation attributes are interned to small ints. The reducer pattern-matches on the interned attribute, equivalent in cost to a `switch(opcode)` jump table. Generic map lookup on attribute keywords is forbidden in the hot loop.

`vm/current-continuation`:

- A hard boundary. Captures the current continuation. If the captured value must survive quiescence or VM migration, it must be persisted, and persistence triggers hashing as usual (persistence invariant). For purely ephemeral capture (resume in the same session, same VM), the captured reference can be a local id and no hash is required. The captured value's canonical form is the captured value; no special snapshot mechanism is needed.

### Option 2 VM: bytecode-carried continuations

Implement in `thetao.vm.bytecode_vm`.

Design:

- compile supported AST/datoms into a new experimental bytecode format in `thetao.vm.bytecode`
- do not reuse existing Yin stack/register bytecode or frame formats
- declare continuation dimension `d_cont_bc` with slots:
  - bytecode reference or inline bytecode
  - instruction pointer
  - lexical environment (by content hash)
  - local slots/registers/operand data (inline or by content hash)
  - parent continuation (by content hash)
  - suspension metadata if blocked
- publish projection `d_cont_bc -> d3` and lift `d3 -> d_cont_bc`

Reduction is a local recursive interpreter over one continuation value, mutating a local register frame between boundaries.

### Option 3 VM: continuation algebra

Implement in `thetao.vm.algebra_vm`.

Design:

- define an explicit continuation algebra in `thetao.vm.algebra`
- use forms such as:
  - evaluate node
  - apply function
  - evaluate argument n
  - branch
  - return
  - await stream fact
  - emit effect
  - resume continuation
- declare continuation dimension `d_cont_alg` with slots matching the algebra forms
- publish projection `d_cont_alg -> d3` and lift `d3 -> d_cont_alg`
- execution-stream items are bare algebraic continuations
- reduction rewrites continuation values directly until a hard boundary

Do not lower option 3 into bytecode in the first experimental phase. The point of Option 3 is to surface causality directly in the continuation algebra, not to compete on raw throughput with Option 2. Forbid self-referential continuation rewrites; parent links must always be acyclic content-hash references.

### Event and fact model

Define explicit stream-written datums for:

- continuation enqueued
- continuation started
- continuation blocked
- continuation resumed
- continuation halted
- effect requested
- effect resolved
- fact/state emitted

Events and facts are d5 datoms. Content hashes are computed on emission to these persistent streams. Use the same event vocabulary for both VMs so their traces can be compared directly. Comparison is by d3 projection (subject, attribute, value), tolerant of differences in continuation-tuple shape.

### Content store

A by-hash lookup table populated when continuations and their compound slot values are materialized at boundaries.

Responsibilities:

- map content hash to canonical-encoded bytes
- support lazy fetch of compound slot values referenced by hash
- act as the deduplication mechanism: two continuations with the same hash share storage

The content store is part of the runtime, not part of any one stream. It is the bridge that makes structural sharing, memoization, and replay concrete.

## Public Interfaces

Expose a local Thetao API rather than integrating with existing Yin protocols.

Both VMs should provide parallel functions such as:

- `create-vm`
- `load-program`
- `enqueue-root`
- `step`
- `run`
- `execution-stream`
- `event-stream`
- `fact-stream`
- `content-store`
- `quiescent?`
- `drain-events`

The VM value should hold stream references, an integer cursor, and a content-store handle, but runnable computation itself must live on `dao.stream` as continuations.

## Test Plan

### Shared behavior tests

For both VMs:

- fetching a root continuation from `dao.stream` starts evaluation correctly
- literals, variables, closures, and `if` evaluate correctly
- tail calls do not accumulate parent continuations (structural sharing via parent-hash reuse is observable)
- `vm/current-continuation` produces a resumable continuation value whose hash equals the continuation it captured
- `vm/park` stops local reduction and emits a blocked/parked event
- `vm/resume` writes a resumed continuation back to the execution stream
- `stream/next` with no data produces explicit blocked state, not hidden waiters
- adding matching data results in a runnable continuation appearing on the execution stream
- `dao.stream.apply/call` emits request/resume events through streams only
- quiescence: execution-stream cursor equals write head, and no blocked continuation has a pending resume condition on any other stream

### Cross-VM comparison tests

For the supported subset, assert both VMs match on:

- final value
- d3 projection of the event trace (event vocabulary is shared; continuation-tuple shape need not match)
- continuation blocking/resume behavior
- `dao.stream` execution behavior

### Replay tests

- Given the recorded persistent streams (event + fact + effect-response) of a program, a fresh VM driven by them reaches the same final value and the same projected-to-d3 event trace.
- Two programs that reach structurally identical continuation states emit identical hashes on their event streams.
- Replay divergence is detected by hash inequality at the first divergent emission.

### Tradeoff tests

Include explicit comparison tests for:

- continuation payload size/shape
- number of continuation emissions per program
- event/fact verbosity
- bytecode versus algebra reduction differences

Throughput at varying boundary frequencies:

- parameterize a benchmark so that a hard boundary occurs every N reductions, varying N across a range
- measure wall-clock per instruction for both VMs at each N
- the curve answers whether the per-step cost is in fact dominated by boundary frequency, as predicted by the boundary-discipline design

## Assumptions

- This is an isolated experiment under `src/cljc/thetao`.
- No existing code is modified.
- All code is `.cljc`.
- `dao.stream` is the execution substrate, not just an external IO mechanism.
- The execution stream is the only source of runnable work.
- VM state is content-addressable. Continuations and their compound slot values have canonical hash identity, materialized at stream boundaries.
- Persistence invariant: values crossing into persistent streams (event/fact/effect/effect-response) carry their content hash. The ephemeral-to-persistent transition is the only place content hashes are required for correctness. Hashing elsewhere (hot-loop dedup, memoization, content-store lookup) is voluntary, not required, and should be justified by measurement.
- Derived in-memory indexes are allowed only as caches over stream history.
- Phase 1 targets the core subset first, not full parity with existing VMs.
