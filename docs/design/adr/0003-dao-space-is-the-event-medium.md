# ADR 0003: dao.space Is the Event Medium; There Is No System Event Bus

**Status:** Accepted (2026-08-29)

**Relates to:** `datom.world.md` invariant 3 (every callback is events on a stream) and its
Host Boundaries section; `dao.space.md` § Coordination: Stigmergy.

## Context

Making invariant 3 conform requires a host boundary to deposit its events somewhere. An
adapter is a map of functions that transform host events to data on a stream
(`datom.world.md`, Host Boundaries), which leaves open what stream.

The first answer proposed was one private ingress stream per transport endpoint, with its
interpreter reading that stream. It is the obvious answer and it is wrong, for a reason worth
recording: an interpreter would have to be handed the endpoint's stream, so something must
know both the producer and the consumer and connect them. That is the coupling invariant 3
exists to remove, moved from a function call to a stream reference and declared solved.

Correcting that raises the real question. If interpreters must find traces without knowing
their source, the traces need a shared medium — and the obvious name for it is a system event
bus.

## Decision

**There is no system event bus. `dao.space` is the medium.**

datom.world is a tuple space because the tuple space is the formal model of stigmergy; the
lineage is blackboard → Linda → `dao.space` (`dao.space.md` § Lineage). A system event bus
would therefore be a *second* stigmergic medium standing beside the one the project exists to
be, with its own retention, its own addressing, and its own set of interpreters — duplicating
the blackboard rather than using it.

Host boundaries deposit into `dao.space`. Interpreters read it, and deposit their derived
facts back into it at a higher level of abstraction.

## Consequences

- **No new mechanism is required, on either side.** `dao.space.transactor/DaoStreamLog`
  already satisfies `dao.stream`'s `IDaoStreamWriter`, and its `-append!` accepts an entity
  map or datom vector and expands it to datoms. An adapter transform returning an ordinary
  map is already a legitimate deposit.

- **The layering stays one-way, and this is why.** `dao.space` requires `dao.stream`;
  `dao.stream` requires nothing from `dao.space`. A boundary that *named* `dao.space` would
  invert that. It does not need to: an adapter is handed a stream and cannot tell what is
  behind it, so the composition happens above, where something already knows about both
  layers. The generality of the adapter definition is what preserves the layering.

- **Volume and durability are retention questions, not admission questions.** Data is syntax
  (axiom 2), so no depositing layer may filter by presumed worth. The operational answer
  lives with the selected medium's transport, which declares its capacity and retention
  policy (evict-oldest versus reject); `dao.stream` standardizes only the outcomes —
  `:dao.stream/full`, `:dao.stream/gap` — so loss is never silent and a reader is never
  silently repositioned.

- **The medium must answer with data.** An adapter entry runs on a host callback thread, where
  a thrown exception has nowhere to go, and `dao.stream`'s axiom is that all expected
  operational outcomes are data. `dao.space.transactor`'s writer face does not yet satisfy
  this: `-append!` throws on a closed wrapper, on invalid input, and on a refused inner
  append — including `:dao.stream/full`, which is ordinary backpressure. Fixing that writer
  face is a precondition of depositing through it.

- **A rejected alternative, recorded because it recurs.** Splitting deposits by kind — events
  to the medium, high-volume payload kept private — is the same error as the private ingress,
  in different clothes: a depositing layer deciding which data deserves the medium. The
  distinction that does survive is addressing, not worth: the contents of a channel resolved
  by name are asked for, not discovered.

## Open Questions

- Whether a connection's payload stream is a deposit or an addressed channel, given that the
  link protocol depends on its positional semantics.
- Retention policy for host-boundary deposits at socket volume.
- Whether `dao.space`'s own `transact!` / `append!` public API should also answer with data,
  or whether the `dao.stream` protocol face alone is governed.

## Amendment (2026-09-01): time-boxed exception for the dao.stream v2 WebSocket slice

The `dao.stream.v2` WebSocket slice
(`docs/design/dao.stream.v2.implementation-plan.md`, Phases 4–5) wires an
in-memory ring buffer as its boundary's deposit destination, not a `dao.space`
writer. This is an exception to the decision above, and it is granted for a
reason this ADR already records: the decision's own precondition — a writer
face that answers with data rather than throwing — is unmet, and `dao.space`
does not yet consume the v2 contract. Requiring the slice to deposit through
`dao.space` today would pull that migration inside a slice whose boundary
explicitly excludes it.

The exception is not a reclassification. A ring buffer used this way is one
composition's retention transport, never a coordination substrate: no
interpreter outside the slice finds anything through it, it is never indexed
or published, and it acquires no addressing of its own. Should that shape
persist, it would be exactly the second stigmergic medium — its own retention,
its own addressing, its own interpreters — that this ADR exists to forbid.

What makes the exception safe to grant is that the swap is composition-only:
the boundary adapter cannot tell what it deposits into, so replacing the
destination changes no adapter code and inverts no layering.

**The exception ends when both hold:** a `dao.space` writer face answers with
data as this ADR requires, and a composition indexes and publishes what the
boundary deposits, so those events are discoverable by matching rather than by
being handed a stream. From that moment this section is void. The trigger is
deliberately stated as those two facts and not as "`dao.space` migrates":
`dao.space` is emergent, not a component, and cannot migrate as a unit.

## References

- `docs/design/datom.world.md` (invariant 3, Host Boundaries, axiom 2)
- `docs/design/dao.space.md` (§ Coordination: Stigmergy, § What Makes It a Tuple Space, § Lineage)
- `docs/design/dao.stream.md` (Axioms, Result Convention, retention and gap semantics)
- [ADR 0001](0001-dao-space-as-storage-boundary.md) (storage boundary)
