# DaoSpace and the Mathematics of Locality

> Physics and the computer science of distributed systems keep arriving at the
> same structures because they are the same problem in different costumes: how do
> many parts, each holding only local information under a bound on how fast
> information can travel, reconcile their partial views into consistent shared
> structure? This document records that correspondence, why it holds, and — with
> the calibration discipline of the other geometry docs — exactly where it stops.
>
> **Related:** `dao.space.md` (gauge framing), `dao.space.discrete-to-continuous.md`
> (the *Descent* section, where the static half of this correspondence is made
> rigorous), `docs/agents/datom-spec.md` (entity ID as local gauge).

---

## The shared root: a theory of locality

Both fields are forced into the same mathematics by four ingredients:

1. **Many parts** — spacetime points / compute nodes.
2. **Only local information** — no part has access to a global instantaneous truth.
3. **A propagation bound** — speed of light / message latency.
4. **A demand for consistency** — invariants / conservation laws must hold anyway.

Given these four, the abstract problem is identical regardless of substrate, so
the same objects appear: partial orders, gauge/connection structure, sheaves and
descent, cohomological obstructions, fixed points. The substrate differs; the
problem does not.

## The dictionary

| Distributed computing | Physics |
|---|---|
| no global clock; Lamport / vector clocks | relativity: no absolute simultaneity, only local proper time |
| happens-before (causal order) | the light cone (causal structure) |
| concurrent events (no fixed order) | spacelike-separated events |
| local node state in local IDs; translate to compare | gauge: local frame, compare via parallel transport |
| content hash / invariant payload | gauge-invariant content |
| reassembling a consistent global state | descent / gluing local sections |
| the cases that require consensus | obstruction to a global section (curvature / `H^1`) |
| CRDTs: invariants preserved under reordering | Noether: symmetry implies a conserved quantity (commutativity is a symmetry) |
| message-latency bound | speed-of-light bound |
| interpreters / incompatible reads | measurement / non-commuting observables |

The rows vary in tightness. The clock/light-cone, gauge, and descent rows are
near-exact. The **CRDT ↔ Noether** row is the loosest and should be read only as
suggestive: Noether's theorem relates *continuous symmetries of an action
functional* to conservation laws, which is not the same thing as
operation-commutativity preserving a data invariant. The shared intuition
(a symmetry yields something preserved) is real; the literal theorem does not
transfer.

The historical tell: Lamport's 1978 *Time, Clocks, and the Ordering of Events in a
Distributed System* — the foundation of the field — took its model directly from
special relativity. "Happens-before" *is* the light cone. The correspondence was
present at the birth of distributed systems, not imposed afterward.

## The sharpest mapping: consistency models are relativity

The hierarchy of consistency models maps almost exactly onto the move from
Newtonian to relativistic time:

- **Linearizability / strong consistency** = a single global timeline =
  **Newtonian absolute time**. It posits one privileged frame in which every event
  has a definite order. Coherent, but expensive: you pay coordination to sustain
  the illusion of a global "now."
- **Causal consistency** = respect the partial order, allow no global order =
  **relativistic**. Cheap, because it enforces only what the light cones force.
- **Eventual consistency** = weaker still; reconcile later.
- **CAP theorem** = under partition you cannot keep a global consistent state and
  remain available, because partition means the light cones do not overlap in
  time. CAP is, structurally, a no-global-simultaneity result: instantaneous
  global agreement is impossible under a propagation bound.

Going strong → causal → eventual is, almost literally, going from Newton to
Einstein: giving up absolute simultaneity because maintaining it costs more than
it is worth.

## Why the convergence is real, not mystical

The mathematics of locality is **substrate-independent**. Sheaves, connections,
cohomology, partial orders, and least-fixpoint constructions do not know whether
their points are events in spacetime or processes on a network. Formalize
"reconcile local views under bounded information flow" and you land on the same
objects either way. This is why category theory and order theory recur in both
fields: they are the language of that problem with the physical or computational
particulars stripped away.

It is the same reason the bundle/descent correspondence holds (see *Descent* in
`dao.space.discrete-to-continuous.md`): ACID-reassembly and gauge-gluing are one
obstruction in two costumes — a global consistent view exists iff a global section
exists, and the failure case is the `F != 0` curvature already documented there.

## Where they diverge (the honest boundary)

The correspondence is exact for the **static** question — whether a consistent
global structure exists and how many inequivalent ones there are — and breaks for
the **dynamic and adversarial** parts. This is the same boundary drawn in the
*Descent* section.

- **Physics is descriptive, non-adversarial, law-driven.** Particles do not lie,
  crash, or choose a protocol; the universe evolves by fixed laws.
- **Distributed computing is constructive, adversarial, agentive.** It has
  Byzantine faults (parts that actively deceive — no physics analog), crash and
  partition failures, and *choices*: which protocol, when to commit, and what to
  trade off (latency vs availability vs cost — economics, which physics lacks).

So the split is clean:

- **Physics supplies the geometry of the constraint** — causal order, gauge, the
  obstruction to a global view.
- **Computer science adds the algorithm and the adversary** — the dynamic act of
  *reaching* agreement, and defending it against malice and failure.

The static obstruction is cohomology (shared with physics); the act of achieving
consensus, and surviving adversaries, is algorithmic and has no counterpart in a
non-adversarial, law-governed universe.

## What this means for datom.world

This is the real justification for the gauge/bundle framing being more than
decoration: dao.space *is* a locality problem (many streams, local entity IDs, no
global clock, consistency on demand), and physics is the most mature mathematics
available for locality problems. Borrowing its machinery is borrowing the right
tool.

The same analysis fixes the limit of the borrowing. Use the geometry for **what
is** — the causal structure, the gauge-invariant content, the obstruction to a
single global view. Reach for distributed-systems theory for **what to do** — the
consensus protocol, the failure handling, the adversary model. Physics will give
you the shape of the constraint; it will never give you the protocol or the
defense. That residue is exactly the consensus/adversary boundary the *Descent*
section places outside static bundle theory.
