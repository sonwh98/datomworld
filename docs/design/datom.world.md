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
- Do not introduce callbacks without explicit stream representation.
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

- Do not stage or commit changes until explicitly asked by the user.
- When asked to stage or commit, follow these rules:
  - Stage only files that were explicitly modified or created for the requested work.
  - When committing, only commit staged changes; never commit unstaged changes.
  - Create clear, descriptive commit messages explaining the "why" behind the changes.
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
- **Engineering Team Roster**: See [`docs/agents/team/TEAM.md`](../agents/team/TEAM.md) for engineering team roles and LLM model assignments.
- **Agent Delegation**: See [`docs/agents/calling-agents.md`](../agents/calling-agents.md) for delegating tasks/reviews to other coding agents (`agy`, `cmd`, `glm`, `deepseek`, `codex`).
- **Website & Content**: See [`docs/agents/website.md`](../agents/website.md) for `.chp` and `.blog` EDN/Hiccup file specifications.
