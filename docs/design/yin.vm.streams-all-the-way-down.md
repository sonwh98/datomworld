# Streams All the Way Down — Calibrating the Stream Principle for yin.vm

Status: exploratory design note (2026-08-18). Nothing here is implemented as a
coherent tier. What is here: a calibrated statement of the "everything is a
stream" rule the codebase already practices selectively, an audit of where
`yin.vm` applies it today, the end-state it implies (a log-structured CESK
machine over ring buffers), the two performance tiers that make that end-state
affordable (coarse boundaries, then stream fusion), and the storage invariant
that bounds its memory cost (store the irreducible, derive the rest). Cost
claims cite the measured figures in `docs/cesk-space-optimization.md`;
proposed machinery names the existing seam it would land in.

**Related documents:**

- `docs/design/yin.vm-in-dao.space.md` — the CESK-in-tuple-space premise this
  note extends; its *Ephemeral State Projection* section is the ancestor of
  §6 below
- `docs/design/jit-design.md` — the advisory trace/patch JIT; the fusion tier
  in §5 is the compiler-side complement to it
- `docs/cesk-space-optimization.md` — measured costs of depositing machine
  state as datoms
- `docs/design/dao.space.index.md` — `publish-index!`, covered indexes, and
  the checkpoint mechanism §6 generalizes
- `docs/design/dao.space.md` — the tuple space and its Three Boundaries
- `docs/design/dao.stream.md` — the DaoStream read/write/bound protocols


## 1. The principle, calibrated

The written rule is stronger than the practiced rule:

> Functions consume streams and produce streams; no direct function-to-function
> coupling without a stream boundary.

Taken absolutely, that would outlaw `(mapv f (filter p coll))` inside a module,
and nothing in `src/` obeys it there. What the rule actually governs is the
seams: components, agents, transports, IO. The defensible form is:

> **Data in motion is a stream. Data at rest is a tuple. Put a boundary
> wherever you need decoupling — and nowhere else.**

A direct call couples two things in three dimensions at once:

- **time** — both parties must be alive in the same moment; the caller blocks;
- **space** — same process, same memory;
- **cardinality** — one caller, one return, once.

A stream boundary decouples all three: buffering decouples time, transport
decouples space, cursors decouple cardinality (non-destructive reads enable
replay and multiple independent readers). A boundary also buys IO honesty:
effects become visible emissions.

So the test for where a boundary belongs is concrete: *do you need replay,
fan-out, temporal or spatial separation, persistence, or schedulability?* If
yes, the boundary pays. If the consumer is synchronous and singular, the
boundary is tax. The query layer already practices this calibration: db inputs
must be bounded DaoStreams (raw vectors rejected, `validate-descriptor!` in
`dao/space/query.cljc`), while scalar/tuple/coll/relation `:in` bindings are
plain data — bindings were never streams, and wrapping them would be ceremony.

The architectural decisions live at the reification points — `open!`,
`collect`, `current`/`history`, `publish-index!` — and those are well placed.


## 2. The audit: yin.vm already applies the principle at its membranes

The premise "yin.vm does not implement this internally" is half wrong, which
is instructive. Every current application coincides exactly with a real
decoupling need — the test works. What the VM does not do is apply the
principle to its own state plumbing.

| Boundary | Mechanism | Anchor |
|---|---|---|
| Program ingress | DaoStream polled for datom batches between evaluations | `yin/vm/stream_driver.cljc` (`ingest-next-program`) |
| Telemetry | Snapshots appended as `[e a v t m]` datoms to a configured stream | `yin/vm/telemetry.cljc` (`emit-snapshot`) |
| Agent-to-agent calls | Shared `call-in`/`call-out` ringbuffers for `dao.stream.apply` requests/responses | `yin/vm.cljc` (`call-in-stream-key` et al.) |
| Blocking on IO | Parked continuations registered as transport waiters; wake converts to run-queue entries | `yin/vm/engine.cljc` (`make-woken-run-queue-entries`) |
| Continuation shipping | Hand-built k-stream with per-VM cursors at the app layer | `datomworld/continuation_transport.cljc` |
| Execution trace | Opt-in deposit of the full CESK configuration as datoms, `t` = step counter | `yin/vm/space.cljc` |

The gaps are named by the code itself. The `:space` docstring admits: every
query folds the whole vector into fresh indexes (O(|space|) reads — seconds at
~10^6 datoms); publishing covered indexes "is the fix and is not wired up";
sharing another machine's space copies by value; "real coordination belongs at
the `dao.jing` layer, not here." The scheduler queues (`:ready-queue`,
`:wait-set`, `:parked`) are private vectors in one VM's state map, popped by
direct calls — while their entries are already plain, serializable data maps.
And `require` returns an effect descriptor (`yin/vm.cljc` primitives) that the
host resolves inline, though module loading is IO: asynchronous, cacheable,
shared across requesters.


## 3. The end-state: a log-structured CESK machine

Take the principle to the extreme and the VM stops being a machine that *has*
subsystems and becomes one append-only, cursor-readable, positionally
addressed medium that executes. Control, environment, store, and continuation
are logs; cursors are pointers. This is `yin.vm-in-dao.space.md`'s premise
with the plumbing finished.

Ring buffers are the right stream family for it, for two structural reasons
that `RingBufferStream` (`dao/stream/ringbuffer.cljc`) already has:

1. **Reads are positional and non-destructive** (`next stream {:position n}`).
   A cursor *is* a broadcast: instruction fetch, stack pointer, environment
   pointer, and a debugging observer walk the same log with independent
   cursors at zero extra cost. A cursor is literally a program counter —
   `next` with `{:position pc}` is fetch-execute. Contrast destructive
   single-consumer channels, where every fan-out needs a wrapper.
2. **The backing is a persistent map keyed by absolute position**
   (`{:buffer {} :head 0 :tail 0 ...}` behind an atom) — a log with a
   retention window, not a fixed array. Append-only, replayable, evictable at
   the front, with slow readers getting `:daostream/gap` rather than
   corruption.

The design commitments that make it efficient:

- **Internal rings are unbounded.** The codebase already makes this choice for
  VM-local plumbing (`open-local-stream` opens `:capacity nil`, `yin/vm.cljc`).
  Unbounded means `append!` never blocks: internal boundaries carry no
  synchronization, no park/resume, no waiters. Blocking — and the waiter
  machinery — engages only at real IO membranes.
- **Cursors double as pointers.** The continuation stack is an append-only
  frame log; each thread of execution holds a cursor = stack depth. Pushing
  appends, popping rewinds. Nothing is copied; shared prefixes are free.
- **Random access is served by derived indexes, not by the log.** Environment
  and store lookups become covered indexes over the bind/cell logs
  (`dao.space.index`); the log stays the truth, the index is the access path.
- **Immutability is observable only at park points.** Hot logs may run on
  transients and snapshot to the persistent view when the machine parks —
  consistency at rest, speed in flight.

**What it costs, measured.** `docs/cesk-space-optimization.md` is the honest
calibration. Against the register backend (n=5000 countdown): space
`:trace? false` runs at **2.09x**, `:trace? true` at **7.77x**; depositing the
full machine-state trace is a consistent **~3.7x multiplier**, ~145 datoms per
iteration. So the pure extreme — the log as the only state representation —
is roughly an 8x interpreter tax today. That is far outside JIT territory and
irrelevant to agent orchestration (1M steps/sec at ~1µs/step; wall-clock
dominated by IO waits), but fatal for numeric inner loops. Hence the tiers.

**The principle selects the machine model.** A register VM over logs
degenerates into write-ahead logging every `MOV` — a database where you wanted
a CPU. A CESK/stack formulation is naturally append-only: control, environment,
store, and continuation are all logs, and `t` is the step counter with `as-of`
time travel for free. `yin.vm.space` is already closest to this shape; the
register and stack engines are not, and would not survive the translation
gracefully.


## 4. Performance tier 1 — coarse boundaries

The first tier is granularity, not cleverness:

- **Batches, not scalars.** Per-value boundaries on numeric code are hopeless;
  per-batch boundaries are the difference between per-character and per-line
  IO. Ingress already works this way (`ingest-next-program` polls *program
  batches*). The right granularity is per phase — fetch/decode/execute/commit
  as stream stages over vectors of datoms — not per add.
- **Park-point snapshots.** The running machine is its own incremental cache
  of its fold; park/resume boundaries are the natural consistency points, and
  the park machinery already exists.
- **Transients in flight.** Match `yin.vm-in-dao.space.md`'s Ephemeral State
  Projection: mutable structures during a step burst, persistent projection at
  the boundaries where anything can observe.


## 5. Performance tier 2 — stream fusion

Fusion is the compiler proving a boundary unused. It cannot remove a boundary
that is doing boundary work — but machine-local plumbing rings with one
producer, one cursor, and no escapes are doing FIFO and nothing else, and FIFO
with a single synchronous in-order consumer is extensionally a function call.

The DaoStream read protocol is already a step machine —
`{:ok v :cursor c'} | :blocked | :end | :daostream/gap` is
Yield/Blocked/Done/Gap with the cursor as resumption point — so a fused edge
is the composition of producer and consumer steps with the buffer elided: no
atom, no `swap!`, no per-value persistent-map `assoc`, no store round-trip.
The embryo exists: `RelationStream` + `strict-vec` is fused finite data
(`dao/stream/relation.cljc`) — no ring ever allocated, positions index a
vector.

### 5.1 The two proof obligations

Fusing is valid only when the buffer's decoupling is unused:

1. **Exclusivity.** Exactly one consumer cursor, in-order, read once; the
   realization never escapes — not stored, not passed as an operand, not
   captured by a lambda body, not closed into a parkable continuation.
2. **Effect ordering.** Fusion introduces demand-driven execution: the
   producer runs when the consumer needs a value. The producer's appends must
   be provably complete-before-consumer, or the producer segment pure apart
   from its appends — otherwise laziness reorders observable effects
   (telemetry snapshots, woken waiters).

Blocking does not enter the analysis: internal rings are unbounded, so
`append!` never blocks and there is no scheduler coupling to preserve.

### 5.2 The static tier: fusion as a datalog query over the program

The analysis obligation — "find stream edges with exactly one producer
region, one cursor, no escapes" — is a query over `:yin/*` AST datoms,
because the program is datoms. A `stream/make` node whose target/source
references form a closed put→cursor→next chain, with no other operand
referencing them, is a `q` over the canonical program; the compile step
already builds the program index (`build-program-index`,
`yin/vm/engine.cljc`).

The landing zone is the existing compile boundary:
`ensure-compiled-version` / `maybe-recompile-at-boundary` /
`compiled-by-version` (versioned, pinned, trimmed). Fusion is another thing
`compile-fn` does. The canonical datom program still says `:stream/put` and
`:stream/next` — the semantic layer is unchanged and inspectable — but the
compiled artifact emits, for a fused edge:

```
before:  :stream-put   (append! → swap! → persistent assoc)
         :stream-next  (deref → cursor map allocation)
after:   :move         (value flows in a register)
```

No store entries for the stream or cursor ids are ever created. The datoms are
the contract; the fused artifact and the ring are two realizations of the same
descriptor — interpretation over abstraction, again.

### 5.3 Relation to `jit-design.md`

`jit-design.md` defines an advisory JIT: a trace surface (`run-traced`,
`step-traced`) emits `:yin.trace/*` datoms; the JIT proposes patch datoms; the
VM applies them at explicit safe points, with guards and deopt datoms. That is
*observation proposing rewrites*. The fusion tier here is *proof performing
rewrites*: static exclusivity and ordering proofs over the program datoms, no
guards needed. They compose — the trace supplies profile facts the static
analysis cannot have (which stream-ids ever actually had a second cursor;
whether parks occurred mid-drain), the static pass does the rewriting, and
both land in the same versioned-recompile machinery. The trace-informed
variant is tiering: `:program-version` recompile is currently triggered by
program appends; it can also be triggered by accumulated profile datoms.

Deoptimization is where the designs must agree, and where the cursor model
earns its keep: a violated speculation is answered by *re-deriving from a
prefix* (§6), not by shadow-writes. A destructive-channel design cannot deopt
at all — the values are gone — but a retained log is always reconstructible,
and execution is deterministic over the datom log with park points as
checkpoints.


## 6. The storage invariant: store the irreducible, derive the rest

The extreme VM does not need to *store* its transient state at all, because
transient state is a function of the log:

> **State at `t` = `fold(events[0..t])`.** The log stores only the
> irreducible; everything else is derived and cached.

`as-of` stops being a feature and becomes lazy evaluation of the fold at a
prefix.

### 6.1 The irreducibility test

Something must be logged iff it is not a function of what is already logged:

- **Must log**: the program, external inputs (the world does not offer
  replay), and every *nondeterministic choice* — scheduler decisions, gensym
  seeds, which waiter woke first. A cursor position after a park is a
  scheduling decision, so the park event is logged and the cursor derived.
- **May derive**: env frames, store cells, continuation chains, cursors,
  telemetry, indexes, compiled artifacts.

The test doubles as a discipline: **nondeterminism is exactly what you must
store; determinism is exactly what you may derive.** The log becomes, by
construction, the complete record of the non-derivable — which is why replay
is exact, and why "explicit causality > implicit assumptions" becomes a
storage invariant rather than a slogan.

### 6.2 The codebase already believes this

- `current` derives the present from history rows (`d5->current-facts`) —
  store history, derive now, never both.
- Compiled artifacts are cached per version and trimmed
  (`yin/vm/engine.cljc`); the
  datom program is truth, bytecode is a memo. `:datom-index` is *invalidated*
  on append, not maintained.
- Covered-index relations ride in `delay`s — "memoization belongs to the
  delay" (`dao/space/query.cljc`).
- `:space` deposits the trace and recovers any configuration via `as-of`.
- Merkle content addressing makes derivation self-verifying: equal folds land
  on the same address, so a cache audits against the log by re-derivation.

### 6.3 What it unifies

A checkpoint is `publish-index!` generalized: a content-addressed fold-prefix,
deduplicating equal derivations, verifiable against its source. With that,
four previously distinct mechanisms collapse into one operation —
**re-derive from a prefix**:

| Mechanism | As re-derivation |
|---|---|
| Crash recovery | reopen at checkpoint, replay tail |
| Fusion deopt | discard fused cache, rebuild from the log |
| Rollback / speculation | rewind cursors, re-fold a branch |
| Cache invalidation | version mismatch, recompute |

### 6.4 The two honest limits

- **Replay is O(n).** Derivation is free semantically, not computationally —
  hence tier 1 (the running machine holds itself as its own incremental
  cache) and park-point snapshots. Derived ≠ cheap; cache policy is where the
  performance engineering lives.
- **Effects don't derive.** State can be re-folded; a packet already sent
  cannot. External IO is the irreducible monad boundary — those events are
  logged precisely because the world will not replay them, and rollback past
  them needs compensation, not recomputation.

The refined summary of the whole edifice: **a minimal causal log that
executes, plus caches.** Memory = irreducible events + chosen caches +
published checkpoints. The "medium that remembers everything" only ever
remembers what it cannot recompute.


## 7. What the extreme buys

Each capability is a *reader* of the medium, not a mechanism bolted on:

- **Total history.** Every transition is an append; the machine cannot avoid
  leaving evidence. Time travel (`as-of`), exact replay (inputs are recorded
  too), causal debugging ("why did this value exist?" is a join, not a
  re-run), and failing runs whose logs are the test fixture.
- **Mobility and durability.** A VM's complete state is `{stream prefixes,
  cursor set, program}` — plain maps and content-addressable manifests.
  Freeze/ship/resume across process or runtime (the whole stack is `.cljc`);
  durable execution as a corollary: checkpoint = publish, recover = reopen
  and replay. The continuation serialization problem dissolves because
  continuations are logs.
- **Observation and meta-circularity.** A debugger is a cursor behind the
  head; an invariant checker is a query against a lagging cursor; a second VM
  can shadow-execute the same input prefix. The JIT of `jit-design.md` — and
  the fusion profiler of §5.3 — are exactly such observers. The VM can query
  its own state with `dao.space.query` and self-modify by appending to the
  program stream, which the versioned compile already tolerates.
- **Topology freedom.** Every edge is a stream and streams have transports;
  descriptors are addresses. Any boundary can be stretched across a machine
  boundary with zero code change: split the ready-queue stream across workers
  for work-stealing; open an agent's streams on a shared transport for a
  distributed machine. With `m`-slot ownership, shared-medium stigmergy:
  multiple machines appending to one space, every fact carrying its writer.
- **Governance.** When all coupling is explicit and enumerable, the set of
  streams you can reach *is* your permission set — object-capability security
  as plumbing. Every edge has a capacity and eviction policy, so overload
  behavior is data, not scheduler chaos.
- **Speculation.** Append-only plus non-destructive reads makes rollback safe
  by construction — rewind a cursor, nothing is destroyed. Forked machines
  share common prefixes structurally (Merkle), so whole-machine A/B execution
  is cheap and comparable by query.


## 8. What stays a stream

Fusion removes only boundaries proven unused. Real membranes — file, HTTP,
WS, UDP, dao.jing publication, a published index — keep their streams
unconditionally, because there the decoupling is the point, not an accident.
The criterion, one line: **the compiler may elide a boundary it can prove
unused; nothing may elide a boundary that is doing boundary work.** Locally,
fused pipelines run at call speed; globally, the system still speaks streams
at every seam that matters, and the descriptor remains the honest description
either way.


## 9. Candidate next steps

Concrete, each landing in an existing seam:

1. **Wire `publish-index!` into the `:space` trace** — the fix its own
   docstring names. Index-served reads for machine-state queries; traces
   shareable across machines via content-addressed manifests.
2. **Pilot the ready-queue as a DaoStream** in one engine. `drain-one!`'s
   docstring already names "the yin.vm engine" as its consumer;
   `make-woken-run-queue-entries` already converts transport wakeups into
   queue entries — the scheduler becomes a stream consumer and cross-VM
   scheduling falls out.
3. **Fusion prototype**: static put/next pair elision in one backend's
   `compile-fn`, measured against the register baseline harness
   (`clj -M:bench`), reusing the methodology of
   `docs/cesk-space-optimization.md`.
4. **Storage-invariant audit**: enumerate the minimal log a parked space VM
   must retain to re-derive its configuration (program, inputs, park and
   scheduling events); verify by replay test — derive, compare addresses.
