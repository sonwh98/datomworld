# One-ABI ruling, round 2 — Fable position

Read: `datom.world.md` Host Boundaries → "Owner ruling, 2026-10-19"; the
ruling notes in `yin.vm.engine.md` §E.3 and `yang.wasm.md` §4; `yang.wasm.md`
§1.1 (Rust kernel is design-only, not authorized) and §6.1 (W0–W6 gates). No
other architect's round-2 answer was read.

## 1. What the ruling changes in my position

**Survives.** The mechanism: a process-local ABI traffics in references into a
heap the composition owns, has one legitimate reader, is not history, and is
collectable. Those four properties are what *any* second ABI is, whichever
reason you give for having one. They remain the spec of the shim, and they
remain the reason the shim cannot be dao.stream *while it exists*. The node
lane (engine-in-guest, dao.stream the only interface) as the place where the
owner's instinct is correct also survives; the ruling adopts it as the
strategic endpoint.

**Revised.** In round 1 I called shared custody "the one indispensable reason"
for a second ABI. That was a category slip and the ruling catches it. Shared
custody is not a reason to draw the line where it is; it is a *consequence*
of drawing it there. The line between machine and world is chosen, and the
custody properties follow from the choice. So I withdraw "indispensable
reason" and keep "indispensable property". I also withdraw the word
"load-bearing": the shim is load-bearing for the body lane, and the body lane
is itself provisional.

**Not conceded.** "Performance" is the wrong word for what justifies the shim
today (see §2), and the round-1 recommendation to shrink the shim by moving
pure work into emitted code was wrong under this ruling (see §4). I revise
both.

## 2. Critique of the ruling

**"Performance is the sole valid justification" is right in shape and wrong in
word.** The shape is right: every candidate justification I can name for a
second ABI evaporates in the node lane, because the node lane moves the whole
machine to one side of the line, and a reason that disappears when the line
moves is not a reason for a second ABI but a reason for where the line is.
That is the ruling's thesis and I now agree with it. But the thing that
justifies the body-lane shim *today* is not per-instruction crossing cost. It
is **availability**: the only engine the project has is CLJC, which cannot be
compiled into a wasm instance, and the Rust kernel that could be is
design-only and unauthorized (`yang.wasm.md` §1.1). The body lane exists
because lowering *images* is buildable now and lowering the *engine* is not.
Crossing cost justifies the shim's *size*; it does not justify its
*existence*. The ruling should say "engineering cost", and the cost ledger
should list "engine not yet compilable into the guest" as the first entry,
because that is the entry whose removal dissolves the ABI.

Candidate reasons the question names, and whether each is a genuine
second-ABI reason:

- **Capability/security granularity.** Not genuine. The body lane gets cheap
  per-crossing checks because trust was established once at A admission and
  the guest bytes are our compiler's output. That is amortization, which is
  cost. ShiBi tokens on the stream give the same granularity in the node lane.
- **Determinism and cross-embedder parity.** Not a second-ABI reason; it is a
  constraint on either ABI. It actually argues *for* dissolution: one engine
  binary running under Node, Wasmtime and Wasmer gives parity for free, while
  the body lane has to prove parity between a CLJS service and a Rust service
  of the same transitions (§E.4.2 "one transition, two dispatchers").
- **Freeze-vs-extend of the ISA.** Half genuine. The register contract is
  frozen at 23 opcodes; services are where semantics evolve without bumping
  the ISA. But the extension point does not have to be a second ABI: the
  effect vocabulary on the stream is also extensible. What the shim buys is
  *freedom to churn* a vocabulary that is not history and need not be
  canonical forever. That is canonicalization and versioning cost, so
  reducible. I flag it as the one reduction with teeth: a wrongly frozen
  stream vocabulary is permanent, so moving a service onto the stream early
  carries a one-way risk the ruling's "every removal is progress" does not
  price.
- **Failure isolation.** Not genuine, and inverted. A wasm trap in the body
  lane kills a task while the host keeps the heap; in the node lane a trap
  loses the heap but stream history survives and the node replays. Node lane
  isolates better.
- **Independent versioning.** Reducible to the same cost as freeze-vs-extend.
  The shim versions with the engine, the stream with the system; that is
  convenient, not necessary.
- **In-machine sharing and identity** (cells, `identical?`, shared
  substructure). Not genuine either, for the same reason as all the others:
  the node lane keeps the heap inside the guest and loses nothing.

Two things the ruling misses that are not reasons for a second ABI but are
corrections to how it will be applied:

- **"Every removal is progress" needs a direction.** Removing a crossing by
  re-implementing the service's semantics in the WAT emitter is not progress
  toward one ABI; it is a third engine (the risk §6.1 already names). Progress
  should be measured by *engine code reuse inside the guest*, not by crossing
  count. A shim can shrink the wrong way.
- **The endpoint is unreachable as planned.** The node lane needs an engine
  that compiles to wasm32. Nothing in the authorized plan commissions one.
  The ruling therefore implies a decision it does not state: either authorize
  the Rust kernel crates for wasm32, or accept that the body-lane shim is
  long-lived and the one-ABI state is aspirational. Review should surface
  that, not let "provisional by principle" paper over it.

## 3. Is the floor sound

Sound, with a sharper definition than the ruling gives. The machine is a
deterministic function of (shipped code, stream prefix). The floor is the set
of operations whose outputs are *not* such a function. "World-facts cannot be
computed by shipped code" is that statement; it is correct and it is the
right floor. The stream is the floor because a stream prefix is the only form
in which a world-fact can be an input to a deterministic function.

Can the floor move? Downward, yes, and that is desirable; upward, no.

- **Recorded worlds / replay** do not move the floor. The recording *is* the
  stream; a replayer is just another host that answers from history. The
  machine still reads facts off a stream. What replay changes is which host
  answers, not where the line is.
- **Replicated agreement** does not move it either. Consensus turns many
  hosts' facts into one agreed fact that still arrives by append. It changes
  the fact's authority, not its mechanism.
- **Changing what counts as a fact** does move it, downward. Time, randomness,
  scheduling order, allocation order: each can be a world-fact (floor) or a
  function of history (machine) depending on design. Logical clocks, seeded
  PRNGs fed by one stream fact, and deterministic scheduler rounds pull them
  inside the machine. `:module/require` is already an effect, so module
  resolution is correctly above the floor. The project should keep moving
  things *into* the machine this way because every such move enlarges what
  replay can reproduce.
- **Upward movement would mean** shipped code computing a fact about the
  world without reading it, which is a contradiction in terms. The one honest
  near-exception is *assumptions*: code can embed a belief about the world
  (a constant, a cached module) and act on it. That is not computing a fact;
  it is risking a stale one, and the stream is still where it finds out it was
  wrong.

One nit on the ruling's wording: "a guest whose engine is compiled inside it
needs only `dao.stream`" still imports memory and a function table and a
stream-transport function. Those are not an ABI in the ruling's sense (they
carry no semantics), and the transport is the sanctioned function-shaped
handle operation. Say so explicitly, or someone will count the imports and
claim the node lane has two ABIs too.

## 4. Consequences for the plan

The ruling commits the project to the node lane as destination. Body-lane
first is still the right *order*, for the availability reason in §2, but
several things in W0–W6 should change so that the body lane is built as a
bridge to the node lane rather than as a permanent structure.

1. **Decide the Rust-kernel question now, not at W6.** W6 currently says
   "if crossing cost dominates, prototype the self-contained interpreter-loop
   variant". Under the ruling that is backwards: the node lane is the endpoint
   regardless of the measurement, and the measurement decides only *when*.
   The owner should rule whether K-phase crates are authorized for a wasm32
   target. If yes, items 2–4 follow. If no, the ruling should be amended to say
   the shim is long-lived until that changes.
2. **W2–W4: the Rust service crates must compile to wasm32 from the first
   commit.** `no_std`-compatible, no WASI in the core, no host-pointer types
   in the transition API. §1.1 already says the native embedder reuses "the
   same crates"; make wasm32 compilability a gate, not a hope. This is the
   single concrete step that turns the node lane from a W6 experiment into a
   build target, and it costs almost nothing if done at the start and a
   rewrite if done late.
3. **Add a node-lane gate (call it N0) beside W5, not inside W6.** Engine
   crate compiled to wasm, one stream-transport import, run the register
   corpus under Node, Wasmtime and Wasmer. Pass/fail is parity with the CLJC
   reference, not speed. This is where the ABI dissolves; it deserves its own
   gate.
4. **Compiled bodies survive into the node lane as an optimization.** An
   engine-in-guest instance can still link per-image compiled body modules
   into its own function table (§5's attach mechanism with the engine on the
   inside). The body's "service call" then becomes an intra-instance call to
   the engine's exported transitions over shared linear memory and the
   engine's own Value layout, with no host crossing and no TLV. Design W5's
   body modules so their service thunks are *replaceable* with direct calls:
   keep the thunk as the one site that knows how a service is reached. That
   is the cheapest way to make the emitter's output outlive the shim.
5. **Mark §E.3 as bridge-era and stop investing in it beyond CLJS needs.**
   Keep golden fixtures (they are cheap and protect CLJS/Rust parity), but do
   not extend the envelope, do not add operations without a cost entry, and
   do not let any other spec depend on it (the "one home" rule already
   helps). The expanded-canonical-bytes ordering rule and tag-13 descriptors
   are the parts most likely to grow; freeze them at W0's set.
6. **Measure crossings from W1, not W6.** A counter of exchanges per guest
   instruction and bytes per exchange costs nothing and makes "shrink as
   measurements allow" an actual loop instead of a promise.
7. **Withdraw my round-1 "shrink the shim" items** that moved engine
   semantics into emitted code (constant minting, store-get of immutable
   module stores, cached free-name resolution). Under the ruling those are
   anti-progress: fewer crossings bought with a third implementation of
   engine semantics. The only shrinking that counts is moving the engine into
   the guest. The emitter should keep exactly what it must know to compile
   control flow, moves and closure calls, and nothing it could get wrong
   about the engine.

**One-line answer.** The ruling is right that the boundary is drawn and that
only cost justifies a second ABI; it is wrong to name the cost "performance",
because what justifies the shim today is that no engine exists that can live
inside the guest, and the plan should be changed to build one, with the body
lane explicitly engineered as scaffolding the node lane will reuse rather
than replace.
