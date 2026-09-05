---
description: Core philosophy, foundational axioms, invariants, fundamental primitives (tuples, streams), design principles, code style, and development workflow for datom.world
---

# DATOM.WORLD ARCHITECTURAL FOUNDATIONS & DEVELOPMENT RULES

## Core Philosophy

Everything is data.
Interpretation > abstraction.
Explicit causality > implicit assumptions.
Restrictions are a feature.

### Foundational Axioms

1. **Everything is a Stream**: All IO and data flow through append-only streams. Persistent facts are immutable tuples and datoms `[e a v t m]`.
2. **Interpretation Creates Semantics**: Data is syntax; semantics only emerge through interpretation (one truth, many perspectives).
3. **Code and State are Datoms**: Code (Universal AST) and runtime execution state are structured datoms, enabling syntax independence and structural reflection.
4. **Everything is a Continuation**: Computation is modeled as serializable, portable continuations that can pause, travel across streams, and resume anywhere.

## Non-Negotiable Invariants

- Do not introduce hidden global state.
- Do not introduce implicit control flow.
- Do not introduce callbacks; every callback is events on a stream.
- Do not introduce shared mutable state.
- Do not collapse interpretation and execution into the same layer.
- Do not assume graphs: graphs must be constructed explicitly from tuples.

## Tuples and Datoms

Tuples are elements in an open moduli space, graded by dimension $n$. A tuple can be any dimension/size.
A datom is the canonical persistent tuple `[e a v t m]` (entity, attribute, value, transaction, metadata).
Source scope belongs to the interpreter, never to an appended tuple slot. Queries keep physical sources as separate db-values and express union or joins explicitly. Streams carry whatever values the consumer needs.

See [`docs/design/datom.md`](./datom.md) for full specification.

## Streams

All IO is modeled as a stream. Functions consume streams and produce streams.
No direct function-to-function coupling without a stream boundary.
Side effects must appear as stream emissions.
Streams are values that can be sent through streams.

Functions can take anything and return anything.
Agents are functions, closures, or continuations in Yin.VM that communicate with the outside world through DaoStream. Agent behavior is specified entirely through stream effects.

See [`docs/design/dao.stream.md`](./dao.stream.md) for full stream specification.
See [`docs/design/agent.tzu.md`](./agent.tzu.md) and [`docs/design/yin.vm.streams-all-the-way-down.md`](./yin.vm.streams-all-the-way-down.md) for agent and VM architecture.

### Host Boundaries

Host-specific boundaries are isolated with DaoStream and interpreters of
DaoStream. A host boundary is a stream boundary, never a function call.

DaoStream handle operations are the one sanctioned function-shaped contact
with that boundary: their exhaustive outcome maps are stream protocol, not an
application callback. Above those operations, portable code still expresses
host work as effect values and observes outcomes as stream data.

Portable code appends an effect value describing what must happen. A host
interpreter consumes that stream, performs the operation, and appends the
outcome. Portable code reads the outcome. Correlation is by identity in the
data; neither side holds a reference to the other.

An adapter that exposes a function for portable code to call is not an
interpreter. It isolates the host library but keeps the coupling, and the
effect never appears as an emission.

A callback is an event on a stream. That is the same rule in the other
direction: when the host initiates -- a socket receives, a connection drops, a
timer fires -- the adapter appends one plain-data event and returns. It invokes
nothing. An interpreter reads that stream and decides what the event means.

Constructively: **a callback is replaced by a function that transforms an event
to data on a stream.** The host's demand for a callback stops at that function.

**An adapter is a map of those functions. That is all an adapter is.** It is a
value, not a namespace, and it is general: sockets, files, timers and windows
all deliver events by calling something, and an adapter is what they call. The
host library is handed the map. Each entry takes the host's arguments and
returns one value; the append is the adapter's, not the entry's, so an entry
cannot return a value to the host and cannot invoke application code. The
adapter is handed only the deposit operation and its own host resource. If its
deposit channel fails, it may close that resource to make the boundary
observably gone; this is teardown, not an upward callback. Host types are
classified into plain data here -- a transform is the last place holding a host
error object, and no host type may cross onto the stream.

Which stream an adapter writes to is the caller's composition, never the
adapter's knowledge. An adapter cannot tell whether it deposits into one
transport's private stream or into a medium many interpreters read, and it does
not need to. This is what keeps the layering one-way: a boundary depends on
nothing above it, because whoever constructs the adapter supplies the stream.

A transform deposits every event admitted by the boundary protocol and judges
none of those events semantically. Wire framing, decoding, and protocol
validation happen below the transform: malformed wire input is transport
machinery, not an event the adapter is obliged to deposit. Once the transport
has produced a valid host event, data is syntax (axiom 2), so worth is not a
property of the datum -- an event that is noise to one interpreter is signal to
another that does not exist yet, and filtering at the depositing layer destroys
the perspectives that have not been taken. How much is retained is the medium's
retention policy, which is operational; what an event means is interpretation.
Neither is the depositor's decision.

An adapter that takes a function to invoke has relocated the callback, not
removed it, even when every value it passes is plain data. An adapter whose
callback has a meaningful return value is worse: that is a synchronous call in
both directions wearing the shape of an event.

Host events are ordinary values on an ordinary stream. A transport may use a
qualified envelope to preserve correlation, lifecycle, and additive evolution;
that envelope remains plain data and creates no separate event mechanism. A
composition may likewise define its own qualified vocabulary for facts that it
owns. Neither requires a bespoke bus or callback API.

Host error types never cross the boundary. A transform function classifies them
and emits a qualified value.

A host with no implementation for an effect emits a qualified unsupported
result. That is a correct outcome, not a gap to be filled.

## Design Principles

Code quality is measured by malleability: how cheaply can changes adapt without breaking promises?
Stigmergic coordination emerges from sharing data across streams.

See [`docs/agents/malleability.md`](../agents/malleability.md) for complete design guidance (cohesion, coupling, bounded complexity, evolution, testing strategy).
See [`docs/agents/vocabulary.md`](../agents/vocabulary.md) for domain vocabulary reference (biology, economics, physics, philosophy).

## Code Style

- Prefer simple data over rich types.
- Prefer total functions over partial ones.
- Prefer declarative pipelines over imperative logic.
- Avoid cleverness.
- Name things after what they do to streams.

## Development Workflow

- Do not invent abstractions that hide streams.
- Do not suggest mainstream frameworks unless explicitly asked.
- Do not optimize prematurely.
- When uncertain, ask at the architectural level, not the implementation level.
- Explanations should align with Plan 9 / Datomic / Lisp lineage.

### Problem Decomposition

If a problem appears complex:
1. Decompose it into streams.
2. Identify invariants.
3. Restrict degrees of freedom.
4. Make causality explicit.
5. Let structure emerge from constraints.

For advanced topics (parallel transport, capability tokens, entanglement):
See [`docs/agents/advanced-concepts.md`](../agents/advanced-concepts.md)

## Operational & Specialized Guides

- **Build & Test**: See [`docs/agents/build-n-test.md`](../agents/build-n-test.md) for build, lint, and test commands and TDD guidelines.
- **Engineering Team Roster**: See [`docs/agents/team.md`](../agents/team.md) for engineering team roles and LLM model assignments.
- **Agent Delegation**: See [`docs/agents/roles/orchestrator.md`](../agents/roles/orchestrator.md) for collaboration protocols and delegating tasks or reviews through `agy`, `cmd`, `glm`, `deepseek`, and `codex`.
- **Website & Content**: See [`docs/agents/website.md`](../agents/website.md) for `.chp` and `.blog` EDN/Hiccup file specifications.
