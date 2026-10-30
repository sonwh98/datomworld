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

1. **Everything is a Stream**: Portable I/O and system data flow through append-only streams; hosts may also install explicit local functions that perform direct I/O.
2. **Interpretation Creates Semantics**: Data is syntax; semantics only emerge through interpretation (one truth, many perspectives).
   At system scale, a "system" is an emergent property of interpreters of
   pure data, not an object, service, or stored artifact. Find the
   interpreters and the data they share to find the system.
3. **Code and State are Datoms**: Code (Universal AST) and runtime execution state are structured datoms, enabling syntax independence and structural reflection.
4. **Everything is a Continuation**: Computation is modeled as serializable, portable continuations that can pause, travel across streams, and resume anywhere.

## Non-Negotiable Invariants

- Do not introduce hidden global state.
- Do not introduce implicit control flow.
- Do not introduce callbacks; every callback is an event on a stream.
- Do not introduce shared mutable state.
- Do not collapse interpretation and execution into the same layer.
- Do not assume graphs: graphs must be constructed explicitly from tuples.
- Do not lose information (unitarity): streams are append-only and no datom is destroyed, only reinterpreted. The Universal AST is never lost: it is always retrievable wherever code is lowered, by address or by a query over its datoms. Every derived form (semantic vector, native images, indexes) is an interpretation of it and may drop facts only because the AST remains.

## Tuples and Datoms

Tuples are elements in an open moduli space, graded by dimension $n$. A tuple can be any dimension/size.
A datom is the canonical persistent tuple `[e a v t m]` (entity, attribute, value, transaction, metadata). Persistent facts are immutable datoms.
Source scope belongs to the interpreter, never to an appended tuple slot. Queries keep physical sources as separate db-values and express union or joins explicitly.

See [`docs/design/datom.md`](./datom.md) for full specification.

## Streams

`dao.stream` is an I/O monad and the system's portable I/O ABI. Functions consume streams and produce streams.
Hosts may install arbitrary local functions, including direct I/O; their availability and migration semantics must be explicit.
Portable behavior cannot assume them; portable side effects appear as stream emissions. Direct-I/O host modules are explicitly local and non-migratable (owner decision, 2026-10-23).
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
Agents are functions, closures, or continuations in `yin.vm` that communicate portably with the outside world through `dao.stream`. Portable agent behavior is specified through stream effects; host-local functions may also perform direct I/O.

See [`docs/design/dao.stream.md`](./dao.stream.md) for full stream specification.
See [`docs/design/yin.vm.streams-all-the-way-down.md`](./yin.vm.streams-all-the-way-down.md) for VM architecture.

### Host Boundaries

A host boundary is the dao.stream abstraction boundary; implementations do not add contracts.
[datom.world.abi.md](./datom.world.abi.md) owns the doctrine, owner rulings, taxonomy, floor and custody rules.

## Architectural Commitments

- **`dao.jing` is syntax, agents are semantics**: the content-addressed encoding stays passive and payload-agnostic.
- **Macros are stream topology**: an evaluator knows nothing about macro expansion; expansion happens on the syntax side of a medium boundary, upstream of any evaluator.
- **Peer observers, one stream**: a generic evaluator and `dao.space` independently observe the same stream topic, as described under Streams. This is not a claim about any two consumers regardless of what they consume.
- **Observers are independent and optional**: nothing requires a medium to have any particular set of consumers. Which observers exist for a stream is a per-composition choice, not a design-time commitment.

## Design Principles

Code quality is measured by malleability: how cheaply can changes adapt without breaking promises?
Stigmergic coordination emerges from sharing data across streams.

**Systems emerge from interpreters of pure data.** Streams are the
composition medium; each interpreter gives the same data a perspective
without collapsing interpretation into execution or owning the truth.

- `dao.space` emerges from `dao.space.index` and `dao.space.query` over
  ordinary streams.
- ShiBi capability, metering, and budget emerge from different
  interpreters over the same tuples; no token object is stored.
- The four VMs interpret one Universal AST; many syntaxes feed that AST.
- The semantic AST in `dao.space` is queryable facts projected by the
  auto-index observer.
- Client and server are roles in one stigmergic behavior implemented by
  interpreters, not distinct kinds of nodes.

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
- Program store writes go through `engine/store-put`; any other store write
  the store-write audit (`test/yin/vm/store_write_audit_test.clj`)
  detects must be on its allowlist; its docstring states the residual it
  leaves to review.

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
- **Agent Delegation**: See [`docs/agents/roles/orchestrator.md`](../agents/roles/orchestrator.md) for collaboration protocols and delegating tasks or reviews through `agy`, `cmd`, `glm`, `deepseek`, and `codex`; each orchestrator session has its own append-only log at `orchestrator-logs/<unix-time-stamp>-<brief-orchestrator-descriptive-name>.md` under the repository root.
- **Website & Content**: See [`docs/agents/format.md`](../agents/format.md) for `.chp` and `.blog` EDN/Hiccup file specifications.
