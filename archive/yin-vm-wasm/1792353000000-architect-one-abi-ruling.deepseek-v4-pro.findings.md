# Round-2 answer — the one-ABI ruling (deepseek-v4-pro)

## Q1 — Does the ruling change my round-1 position?

Yes, and in a way I should state plainly rather than paper over: **round-1 me was
right about the diagnosis and wrong about the category.**

What survives:

- The *diagnosis* of the two opposed properties survives, but downgraded from
  "two different kinds of boundary" to "one boundary placed where crossing is
  synchronous and state-local." The synchronous/state-local vs async/canonical
  contrast, the one-privileged-reader vs no-privileged-reader contrast — all of
  it is still the correct *cost accounting* of *why* the seam, as currently
  drawn, is expensive. It is no longer a proof that the seam *must* be a
  different kind of thing.
- The *value-representation point* survives intact, and the ruling actually
  strengthens it (see Q3): in-machine handles vs canonical bytes, the three
  encoding domains, tag-13, non-canonicalizable continuations. This is a *hard
  lower bound* that is orthogonal to the one-ABI/two-ABI question and does not
  dissolve at the node lane.
- The red-herring status of "one `exchange` vs N typed imports" survives: the
  import-surface shape was never doctrine, and the ruling confirms it by
  locating all the *doctrinal* weight in the one-ABI principle instead.
- My round-1 identification of "move the interpreter loop / compile the engine
  into the guest" as the *only real alternative axis* is now **vindicated** — the
  ruling names exactly that (the engine-in-guest node lane) as the destination.

What I now revise:

- **I retract "no design in which guest-host is dao.stream."** That was the
  category error. The correct statement is "no design in which guest-host is
  `dao.stream` *while the engine lives in the host*." Once the engine is
  compiled into the guest, the synchronous services (`:load-free`, primitive
  apply, GC materialization, closure minting) *cease to be boundary crossings at
  all* — they become in-machine operations — and the only thing left crossing
  the boundary is `dao.stream`. My round-1 "indispensable reason" was really a
  description of *why the line is expensive to draw where the current design
  draws it*; I elevated an economics fact to a kind-necessity. The ruling is
  correct that this was wrong.
- Correspondingly, I demote my "indispensable reason" from "the seam is a
  different kind of boundary" to "the seam, as currently placed, has
  per-instruction synchronous crossings whose cost is the justification for the
  second ABI." That is a substantive concession, and it is the right one.

Net: round-1's mechanism survives, round-1's metaphysics does not. The ruling
moves the argument from "kinds" to "placement," and on that axis the ruling wins.

## Q2 — Critique of the ruling: is "performance is the sole valid justification" correct?

**The conclusion is correct, and I want to say that without hedging, because the
candidate "other reasons" are mostly either ABI-agnostic or actually cut the
*other* way. But the ruling's stated form is incomplete in two specific places,
and I'll name them.**

The cleanest way to see the ruling's truth is to notice what the second ABI has
that `dao.stream` lacks: **synchrony, and task-local (non-canonical) value
identity.** Those two things, jointly, *are* the second ABI. Every candidate
justification must either reduce to those two, or fail. Going through them:

- **Capability / security granularity.** Real, but **reducible**. The narrow,
  checked, versioned surface that prevents the guest from forging closures,
  continuations, or typed effects is a property of the *host-side checks*
  (custody gates, check-then-commit), not of the medium. `dao.stream` already
  enforces the same thing — effects are plain data, custody gates check them,
  an unimplemented effect yields a qualified `unsupported` result. The handle
  mechanism (tag-13) does let the second ABI pass *non-portable* capabilities
  (a live cursor, a just-minted closure) to the guest without canonicalizing
  them — but that is the *canonicalization cost* term wearing a capability hat:
  the reason you pass a handle rather than a descriptor is that re-attaching to
  a descriptor per instruction is absurd, i.e. *performance*. The fact that a
  handle *happens to be* non-portable (and therefore can't leak as authority) is
  a consequence, not a motivation. Reducible.

- **Determinism / cross-embedder parity.** This is the one the question lists as
  a possible justification that is in fact a **reason to prefer `dao.stream`,
  not a second ABI**. Determinism is guaranteed by the *shared transition
  implementation* (single-engine-port, one-transition-two-dispatchers), not by
  having two surfaces — and it is *strictly harder* on the private ABI. §E.4.3
  (the deterministic-traversal prerequisite, minted `c-N` ids, sorted map/set
  walks) exists *only because* the private ABI carries unordered in-machine
  values and mints ids. At the `dao.stream` floor, determinism reduces to
  "canonical encoding is total and ordered," which is comparatively trivial. So
  determinism is a *cost the second ABI imposes*, not a justification for it.
  The ruling would be *stronger* if it said this explicitly.

- **Freeze-vs-extend of the ISA.** Real as an axis, but **reducible — it is the
  placement axis itself, already covered by the ruling.** The frozen opcode set
  (r2, 23 opcodes, "no new guest register namespace") vs the extensible service
  operations is a genuine distinction, but it maps onto "opcode vs effect," not
  "opcode vs second-ABI-operation": a second-ABI operation (`exchange` for
  `resolve-var`) is *also* extensible (versioned), and a `dao.stream` effect is
  *also* extensible (additive data). So freeze-vs-extend does not distinguish a
  second ABI from the stream; it is the *content* of the line-drawing decision
  (the `:define` promotion is exactly a move along it).

- **Failure isolation.** Real, but **reducible / ABI-agnostic**. Check-then-
  commit atomicity, "a refused transition leaves guest state unchanged," "a
  panic poisons only its machine," `BAD_MESSAGE` before semantic work — these
  are properties of the host engine's implementation, satisfied identically
  whether the medium is a mailbox or a stream (`dao.stream` has its own
  qualified-unsupported / malformed-input failure model).

- **Independent versioning.** **Reducible** — a *consequence* of having a second
  surface (you must version it), not a reason for it. `dao.stream` effect
  vocabularies version the same way (qualified envelopes).

So: every candidate is either ABI-agnostic (capability, freeze-vs-extend,
failure isolation, versioning) or actually a *cost* of the second ABI
(determinism). The ruling's conclusion holds — and I'd sharpen it into a
statement the ruling itself gestures at but doesn't quite say: **the second ABI
has exactly one property `dao.stream` lacks — synchronous, task-local,
non-canonical identity — and that property is nothing other than an expression
of per-instruction crossing cost. Performance is not merely "a" justification;
it is the *only possible* one, because it is the only thing the second ABI is.**

My two corrections to the ruling's *form* (not its substance):

1. **"Performance" is doing two jobs it should separate.** Crossing *frequency*
   (how many synchronous crossings per instruction) and *canonicalization cost*
   (how much must be serialized per crossing, and whether the value even *has* a
   canonical form — closures, live cursors, reified continuations). The ruling
   lumps them. They behave differently under the ruling's own logic: crossing
   frequency dissolves at the node lane; canonicalization cost does *not* (the
   node lane still appends its effects-out and reads its facts-in as canonical
   `dao.stream` values). The ruling is about the first; the second is the
   immovable-floor cost and belongs in Q3, not in "performance."

2. **The ruling lists the second ABI's benefit but not its own cost.** It names
   "crossing cost" as the price of the second ABI, but the second ABI *also*
   imposes a determinism/parity burden (§E.4.3) and a versioning surface that
   `dao.stream` does not. Stating the second ABI's full cost — not just its
   benefit's absence — makes the one-ABI case stronger than the ruling's own
   phrasing does.

## Q3 — Is the floor argument sound? Can the floor move?

**The core is sound; the word "immovable" is overstated, and the question's
parenthetical is the right instinct.** There are three distinct claims bundled
under "the floor," and they need to be pulled apart.

1. **The non-computability seam — sound, and immovable.** A shipped program
   computes a function of its inputs; a "world-fact" (wall clock, socket bytes,
   another machine's state, a sensor) is *not* a function of the machine's
   state, because the machine is a closed computation and the world is outside
   that closure. No relocation of the line makes the world computable. This is
   the existential core, and it is immovable. Note the ruling states it
   asymmetrically ("answers are world-facts, in"), but it is *symmetric*: the
   machine's *effects out* are equally non-computable — an effect's *outcome*
   (did the world accept the write?) is a world-fact. The floor is "the
   machine/world boundary, in both directions, is data," and `dao.stream` is the
   minimum because it is the *only* thing that is simultaneously the effect-out
   channel and the fact-in channel, unified as append/park/resume.

2. **The frontier — movable, and the ruling already concedes this.** "Which
   operations are world-facts" is a *design decision*, not a fact about the
   world. The `:define` promotion (v4: host service → opcode) is exactly a
   frontier move: `define` was recognized as a function of store state, not a
   world side-effect, and pulled inside. So the floor is not a fixed *set* of
   operations; it is a *frontier*, and the frontier is the design surface. The
   ruling means to freeze only the seam; its language ("the floor itself is not
   movable") risks freezing the frontier by accident. I'd rewrite it: **the seam
   is immovable; the frontier is movable, and moving it is precisely the work.**

3. **The parenthetical examples — real, but they don't dissolve the seam, they
   relocate which facts count.** *Determinism / recorded worlds*: a recorded
   world (replay log, prior-input snapshot) *is* computable — it's a function of
   the recorded history, which lives in (or is retrievable from) the machine's
   state. So a deterministic closed machine has a *trivially small* floor (its
   only world-fact is the seed of its input tape). This genuinely moves the
   floor *extensionally*. *Replicated agreement*: does **not** move the floor at
   all — "whatever the replicas agree" is *itself* a world-fact (a process in
   other machines), and its outcome enters your machine as data. Replication
   moves the answer's *producer* further into the world and *adds* stream
   traffic; it changes *which* facts are world-facts, not whether any exist.

So the synthesis — and it is the part that actually *strengthens* the ruling:
**only the seam is immovable, in the existential sense; the frontier is movable,
in the extensional sense.** That yields a clean partition that is the ruling's
real content: a *world-fact must* cross on `dao.stream` (it has nowhere else to
go); a *non-world-fact* sits on the synchronous ABI *only* for performance. Once
you see that, the one-ABI principle has more teeth than the ruling's own wording
gives it: the second ABI is not "justified by performance," it is "reserved
exclusively for non-world-facts whose stream-crossing is too expensive."

One further refinement that ties back to round 1: the canonicalization cost I
flagged there is the *floor's* cost, not the second ABI's. Even the node lane —
engine fully in-guest, only `dao.stream` crossing — still must encode its
effects-out and decode its facts-in as canonical values, and must *refuse* to
canonicalize the non-canonicalizable (reified continuations). That cost is
permanent, orthogonal to the one-ABI question, and the ruling should own it as a
*floor* fact rather than silently folding it into "performance."

## Q4 — Consequences for the plan (W0–W6)

**The ruling changes the plan's *teleology and accounting*, but not its
*sequence*. I would not resequence to node-lane-first, and I'd argue against any
reading that does. What I would change is concrete and mostly about making the
current plan *honest* under the ruling.**

Why not resequence: the node lane (engine-in-guest) is a *harder correctness
target* — you must compile collection, custody, module stores, and the FFI
two-step *into* wasm and get byte-identical parity with the host engine — and it
is **unverifiable without the host engine's conformance oracle first** (the
K0/W0 oracle, §E.5.1). The mailbox ABI is not merely the provisional thing to be
shrunk; it is the *measurement instrument* and the *oracle* that the node lane's
correctness depends on. You cannot dissolve a cost you have not yet quantified,
and you cannot verify the dissolution without the reference. So W0–W5
mailbox-first is **prerequisite**, not alternative, to the node lane.

What I would change:

1. **Flip the W6 attitude from "fallback" to "destination."** Today engine-in-
   guest / node-lowering is written as a contingency ("*if* crossing cost
   dominates, prototype the interpreter-loop variant," `yang.wasm.md` §6.1). The
   ruling inverts this: the node lane is the *target*, the mailbox ABI is the
   *provisional* thing. The *measurement* may still gate *when*, but not
   *whether*. Keep "no optimization before W5 correctness" as a sequencing guard;
   remove the implication that service-migration is optional.

2. **Make service-migration-into-guest a hard, ruling-mandated W6 deliverable,
   with a per-operation cost partition.** W6 should produce a table of the
   required services (§E.3.4's list: handshake, value definition/lookup,
   free-name/store/gensym, primitive apply, stream/effect/FFI, scheduler-round,
   collect/pin, module attach/spawn) partitioned into three buckets:
   (a) already-compiled pure control; (b) **compilable into the guest engine**
   (resolve-var, primitive apply, closure/continuation minting, store ops, GC —
   the engine crates compiled *into* the instance, argon's Option 1); and
   (c) irreducibly world-fact (stream/effect/park/FFI) → `dao.stream`. The plan
   currently treats (b) as a vague "service migration" footnote; the ruling makes
   (b) *the* W6 program.

3. **Add a W0 crossing-cost measurement fixture.** The ruling's governing number
   is measured per-instruction crossing cost, and it must be *engine-independent*
   (the economics only bind if the cost is identical across Node/Wasmtime/Wasmer).
   W0 should include a micro-benchmark counting, per opcode and per required
   service, serialized bytes + crossing count + codec time for a representative
   instruction mix. This feeds the W6 "dissolve or shrink" decision from the
   first slices instead of as a late afterthought.

4. **Add a W0 required-operations *minimality audit*.** The ruling says every
   addition to a process-local ABI is a cost to be justified. W0 should therefore
   state, per required operation, *why it is not yet compilable into the guest*
   — i.e. is it a world-fact (bucket c), or a not-yet-migrated service (bucket b)?
   This makes the cost explicit from day one rather than discovering it at W6.

5. **Correct the non-goal language.** §6.2 / §E.5.4 currently list "native-to-
   wasm migration of value/scheduler services" and the interpreter-loop variant
   among non-goals/later axes. Under the ruling, that migration is *the goal*. I'd
   reframe the non-goal to "no *silent second semantic implementation* during
   migration" (single-engine-port still binds) rather than "no migration."

Bottom line: the ruling does **not** commit the project to building the node lane
first. It commits the project to *treating the mailbox ABI as a cost curve with a
known, measured, and shrinking area, whose zero-point is the node lane* — and the
mailbox ABI is the instrument you need to draw that curve. The single most
important concrete change is item 2: make service-migration a *mandated* W6
deliverable with an explicit per-operation partition, and add the item-3 W0
measurement that makes it a decision rather than a contingency.
