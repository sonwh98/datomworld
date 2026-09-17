---
description: Core philosophy, foundational axioms, invariants, primitives (tuples, streams, host boundaries), architectural commitments, design principles, code style, and development workflow for datom.world
---

# DATOM.WORLD ARCHITECTURAL FOUNDATIONS & DEVELOPMENT RULES

## Core Philosophy

Everything is data.
Interpretation > abstraction.
Explicit causality > implicit assumptions.
Restrictions are a feature.

### Foundational Axioms

1. **Everything is a Stream**: All IO and data flow through append-only streams.
2. **Interpretation Creates Semantics**: Data is syntax; semantics only emerge through interpretation (one truth, many perspectives).
3. **Code and State are Datoms**: Code (Universal AST) and runtime execution state are structured datoms, enabling syntax independence and structural reflection.
4. **Everything is a Continuation**: Computation is modeled as serializable, portable continuations that can pause, travel across streams, and resume anywhere.

## Non-Negotiable Invariants

- Do not introduce hidden global state.
- Do not introduce implicit control flow.
- Do not introduce callbacks; every callback is an event on a stream.
- Do not introduce shared mutable state.
- Do not collapse interpretation and execution into the same layer.
- Do not assume graphs: graphs must be constructed explicitly from tuples.

## Tuples and Datoms

Tuples are elements in an open moduli space, graded by dimension $n$. A tuple can be any dimension/size.
A datom is the canonical persistent tuple `[e a v t m]` (entity, attribute, value, transaction, metadata). Persistent facts are immutable datoms.
Source scope belongs to the interpreter, never to an appended tuple slot. Queries keep physical sources as separate db-values and express union or joins explicitly.

See [`docs/design/datom.md`](./datom.md) for full specification.

## Streams

All IO is modeled as a stream. Functions consume streams and produce streams.
No direct function-to-function coupling without a stream boundary.
Side effects must appear as stream emissions.
Streams are values that can be sent through streams.
Streams carry whatever values the consumer needs: no datom requirement, no privileged payload shape.

A stream has no privileged reader, and its meaning is not in it. Any number
of interpreters may observe the same medium, and each constructs its own
semantics from the same batches: a `yin.vm` evaluator observing a program
stream constructs CESK state and executes it; `dao.space.index` observing
the very same stream materializes covered indexes, so `dao.space.query/q`
answers Datalog over the program as it grows. Neither knows the other
exists. The medium is their only coupling, and each observer's ignorance of
the other is load-bearing: the evaluator never consults an index to run,
and the index never evaluates a form to index it. This is how axiom 2
becomes architecture: one stream, many interpretations, none of them
the stream's own.

Functions can take anything and return anything.
Agents are functions, closures, or continuations in `yin.vm` that communicate with the outside world through `dao.stream`. Agent behavior is specified entirely through stream effects.

See [`docs/design/dao.stream.md`](./dao.stream.md) for full stream specification.
See [`docs/design/yin.vm.streams-all-the-way-down.md`](./yin.vm.streams-all-the-way-down.md) for VM architecture.

### Host Boundaries

A host boundary is a stream boundary, never a function call. The one
sanctioned function-shaped contact is a `dao.stream` handle operation, whose
exhaustive outcome map is stream protocol, not an application callback.

**Effects out.** Portable code appends an effect value describing what must
happen. A host interpreter consumes that stream, performs the operation, and
appends the outcome. Portable code reads the outcome. Correlation is by
identity in the data; neither side holds a reference to the other. A host with
no implementation for an effect emits a qualified unsupported result. That is a
correct outcome, not a gap to be filled.

**Events in.** When the host initiates (a socket receives, a connection drops,
a timer fires) the adapter appends one plain-data event and returns. It invokes
nothing. An interpreter reads that stream and decides what the event means.
Constructively: **a callback is replaced by a function that transforms an event
to data on a stream.** The host's demand for a callback stops at that function.

**Adapters.** An adapter is a map of those transform functions. That is all an
adapter is. It is a value, not a namespace, and it is general: sockets, files,
timers and windows all deliver events by calling something, and an adapter is
what they call. Each entry takes the host's arguments and returns one value;
the append is the adapter's, not the entry's, so an entry cannot return a value
to the host and cannot invoke application code. The adapter is handed only the
deposit operation and its own host resource. If its deposit channel fails, it
may close that resource to make the boundary observably gone; this is teardown,
not an upward callback.

Host types are classified into plain data in the transform: it is the last
place holding a host error object, and no host type crosses onto the stream. A
transport may use a qualified envelope to preserve correlation, lifecycle, and
additive evolution; that envelope remains plain data and creates no separate
event mechanism. A composition may likewise define its own qualified vocabulary
for facts it owns.

Which stream an adapter writes to is the caller's composition, never the
adapter's knowledge. An adapter cannot tell whether it deposits into one
transport's private stream or into a medium many interpreters read. This keeps
the layering one-way: a boundary depends on nothing above it, because whoever
constructs the adapter supplies the stream.

A transform deposits every event admitted by the boundary protocol and judges
none of them semantically. Wire framing, decoding, and protocol validation
happen below the transform: malformed wire input is transport machinery, not an
event. Once the transport has produced a valid host event, data is syntax
(axiom 2): an event that is noise to one interpreter is signal to another that
does not exist yet, and filtering at the depositing layer destroys perspectives
not yet taken. Retention is the medium's operational policy; meaning is
interpretation. Neither is the depositor's decision.

Two things are not adapters. One that exposes a function for portable code to
call isolates the host library but keeps the coupling, and the effect never
appears as an emission. One that takes a function to invoke has relocated the
callback, not removed it, even when every value it passes is plain data; if
that callback has a meaningful return value, it is a synchronous call in both
directions wearing the shape of an event.

## Architectural Commitments

- **`dao.jing` is syntax, agents are semantics**: the content-addressed encoding stays passive and payload-agnostic.
- **Macros are stream topology**: an evaluator knows nothing about macro expansion; expansion happens on the syntax side of a medium boundary, upstream of any evaluator.
- **Peer observers, one stream**: a generic evaluator and `dao.space` independently observe the same stream topic, as described under Streams. This is not a claim about any two consumers regardless of what they consume.
- **Observers are independent and optional**: nothing requires a medium to have any particular set of consumers. Which observers exist for a stream is a per-composition choice, not a design-time commitment.

## Design Principles

Code quality is measured by malleability: how cheaply can changes adapt without breaking promises?
Stigmergic coordination emerges from sharing data across streams.

**Derive, don't persist.** Before adding a tag, slot, or field to any
canonical content-hashed structure (an AST grammar, a datom schema, a row
shape), check whether a correctly scoped Datalog query over
`dao.space.query/q` can already compute the fact. If it can, it belongs as a
query or projection: new persisted structure migrates every downstream
consumer and duplicates authority over a fact a query derives fresh. The
retired `:global` tag in
[`docs/design/yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md) is the
case study, including the scoping bug that a naive query introduced.

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

## Operational & Specialized Guides

- **Advanced Concepts**: See [`docs/agents/advanced-concepts.md`](../agents/advanced-concepts.md) for parallel transport, capability tokens, and entanglement.
- **Build & Test**: See [`docs/agents/build-n-test.md`](../agents/build-n-test.md) for build, lint, and test commands and TDD guidelines.
- **Engineering Team Roster**: See [`docs/agents/team.md`](../agents/team.md) for engineering team roles and LLM model assignments.
- **Agent Delegation**: See [`docs/agents/roles/orchestrator.md`](../agents/roles/orchestrator.md) for collaboration protocols and delegating tasks or reviews through `agy`, `cmd`, `glm`, `deepseek`, and `codex`; the orchestrator's append-only work log is [`docs/orchestrator-log.md`](../orchestrator-log.md).
- **Website & Content**: See [`docs/agents/file-format.md`](../agents/file-format.md) for `.chp` and `.blog` EDN/Hiccup file specifications.
