# Design: dao.flow - UI and GPU Workflows from Streams and Interpreters

## Context

`dao.flow` builds UI and GPU-oriented workflows from `dao.stream` values and
interpreters of those streams.

A `dao.stream` provides transport and history. Values can be written, read by
cursor, replayed, branched, and observed by multiple readers. `dao.flow`
builds on that capability to construct workflows where interpreters consume
stream values, transform them, and either emit new stream values or produce
terminal effects.

The central claim of `dao.flow` is that UI rendering, GPU rendering, and GPU
compute should not be modeled as separate architectural systems. They are
different interpreter behaviors over flowing data.

- A graphics interpreter may realize values as draw operations and pixels.
- A compute interpreter may realize values as dispatch operations and result
  buffers.
- A textual interpreter may realize values as TUI frames.
- A host UI interpreter may realize values through Flutter or another UI
  runtime.
- A workflow may interleave several of these within the UI/GPU domain.

This means `dao.flow` is not a scene-graph module, not a database layer, and
not generic stream transport alone. Its concern is the construction of
explicit UI and GPU workflows from streams and interpreters.

## Relationship to dao.stream

`dao.stream` and `dao.flow` have different responsibilities.

`dao.stream` defines stream mechanics:

- append and read
- cursor-based consumption
- replay
- branching
- materialized history
- multiple readers

`dao.flow` defines workflow composition over those streams:

- interpreters that read stream values
- interpreters that emit downstream values
- terminal interpreters that produce concrete effects
- optional fusion of intermediate interpreter stages
- equivalence between fused and materialized workflow boundaries

Short distinction:

- `dao.stream` defines how values flow
- `dao.flow` defines how interpreters are composed around those flows

## Workflow Shape

A `dao.flow` workflow is built from:

- one or more `dao.stream` values
- one or more interpreters of those streams
- optional intermediate stream boundaries
- one or more terminal interpreters

Conceptually:

```text
stream -> interpreter -> stream -> interpreter -> terminal
```

Where useful, intermediate boundaries may remain explicit and materialized.
Where intermediate persistence is unnecessary, interpreter stages may be
fused:

```text
stream -> fused interpreters -> terminal
```

The workflow is explicit in both cases. The difference is whether intermediate
values are given their own stream identity.

## Materialized and Fused Boundaries

`dao.flow` supports two equivalent composition modes.

### Materialized

An interpreter reads one stream and writes another. This is used when a
boundary should be inspectable, replayable, branchable, or independently
scheduled.

```text
input-stream -> interpreter-a -> stream-ab -> interpreter-b -> stream-bc -> terminal
```

Materialized boundaries are useful when you need:

- replay
- debugging
- inspection
- branching to multiple readers
- decoupled stage lifecycles
- explicit transport at a boundary

### Fused

When an intermediate boundary does not need to be materialized, interpreters
may be fused so values flow through multiple stages without allocating
intermediate streams purely to hold temporary results.

Transducers are the main tool here. They allow staged interpretation without
forcing every intermediate meaning to become its own `dao.stream`.

Conceptually:

```clojure
(stream-transduce (comp xf-a xf-b xf-c)
                  terminal-rf
                  acc
                  input-stream)
```

In this mode, the stream remains explicit at the workflow boundary, while
intermediate interpretation remains in-flight.

## Main Invariant

Materializing a boundary and fusing a boundary must preserve semantic meaning.

They may differ in:

- observability
- buffering
- scheduling
- cost
- lifecycle separation

They must not differ in result.

This is a core invariant of `dao.flow`: fusion is an execution strategy, not a
semantic rewrite.

## Interpreters

An interpreter in `dao.flow` is a component that gives operational meaning to
values read from a stream.

An interpreter may:

- transform one stream vocabulary into another
- refine or optimize a prior representation
- plan work for a downstream runtime
- realize terminal effects

Examples include:

- geometry interpreter
- dispatch-planning interpreter
- layout interpreter
- TUI frame interpreter
- backend terminal interpreter

These names are illustrative, not mandatory built-ins. `dao.flow` is defined
by the workflow pattern, not by one fixed stage catalog.

## Workflow Variants

Graphics workflow:

```text
scene/value stream -> graphics interpreters -> rendering terminal -> pixels
```

Compute workflow:

```text
compute/value stream -> compute interpreters -> execution terminal -> buffers/results
```

Text workflow:

```text
state/value stream -> text interpreters -> TUI terminal -> terminal frame
```

Mixed workflow:

```text
value stream -> planning interpreter -> render+compute+text terminals
```

The important point is that these are all `dao.flow` workflows. They differ in
interpreter behavior and terminal realization, not in the underlying
architectural model.

## Terminals

A terminal interpreter is the point where a workflow stops producing further
stream values and instead produces concrete effects.

A terminal may target:

- GPU rendering
- GPU compute
- Flutter drawing
- TUI rendering
- SVG emission

The distinction is not between "real" terminals and "debug" terminals. The
distinction is simply whether the interpreter continues the workflow as a
stream or realizes it into effects.

## stream-transduce

`stream-transduce` is the bridge from `dao.stream` to transducer-driven
workflow execution.

It reads values from a stream cursor, drives a composed transducer stack over
those values, and feeds outputs into a reducing function or terminal.

Conceptually:

```clojure
(stream-transduce xform rf acc input-stream)
```

Where:

- `xform` is a transducer or composed transducer stack
- `rf` is a reducing function or terminal interpreter
- `acc` is the initial accumulator or terminal state
- `input-stream` is the upstream `dao.stream`

This lets a workflow use explicit stream boundaries where they matter while
still avoiding unnecessary intermediate streams in hot paths.

## Public Surface

The public API of `dao.flow` should remain workflow-level.

Appropriate responsibilities include:

- driving transducers from `dao.stream`
- connecting interpreters across materialized boundaries
- preserving semantic equivalence between fused and materialized workflows
- supplying generic terminal adaptation helpers where useful

It should not define one domain's stage vocabulary as if it were universal.

## Domain Ownership

Domain namespaces own their own payload vocabularies and interpreters.

Examples:

- graphics namespaces may define scene-to-draw transformations
- compute namespaces may define intent-to-dispatch transformations
- UI namespaces may define layout-to-widget or value-to-frame transformations
- backend namespaces may define terminal realizations for Flutter, GPU APIs,
  TUI runtimes, and SVG emitters

`dao.flow` provides the workflow model in which these interpreters run. It
does not absorb their semantics.

## Relationship to Specific Designs

Scene-graph walking, camera semantics, draw-op schemas, layout semantics,
dispatch vocabularies, and painter details belong to domain-specific design
documents and namespaces. They may be good users of `dao.flow`, but they are
not the definition of `dao.flow`.

This separation keeps the flow layer stable while allowing multiple producer
and interpreter ecosystems to emerge on top of it.

## Non-Goals

`dao.flow` should not:

- depend on `dao.db`
- define scene-graph semantics as universal
- define GPU semantics as universal
- define non-UI domains such as audio pipelines or logging pipelines
- make `walk`, `paint`, `camera`, or similar domain terms foundational
- require intermediate `dao.stream`s merely to name temporary computations
- collapse workflow composition and domain interpretation into the same layer

Domain interpreters belong in domain namespaces. `dao.flow` defines the
workflow model they use.

## Test Contracts

Tests for `dao.flow` should verify workflow behavior, not one particular
graphics, compute, or UI domain.

Examples:

- fused execution equals materialized execution for the same inputs
- stream order is preserved through interpreter composition
- replay reproduces the same downstream values
- multiple readers observe the same materialized boundary values
- terminal invocation order is stable
- reduced termination stops the workflow cleanly

Domain correctness belongs in domain-level tests.

## Risks and Trade-offs

- Materialized boundaries improve inspection and replay but cost storage,
  buffering, and scheduling overhead.
- Fused execution reduces intermediate allocation but removes explicit stream
  identity for those boundaries.
- Over-generalizing stage vocabulary inside `dao.flow` would collapse domain
  interpretation into the workflow layer.
- Over-materializing every boundary would turn streams into mandatory temporary
  storage rather than intentional observability points.

The design goal is explicit causality without unnecessary transport.

## Summary

`dao.flow` is the layer above `dao.stream` where UI and GPU workflows are
constructed from streams and interpreters. It allows GPU rendering, GPU
compute, Flutter rendering, TUI rendering, and related UI terminal behaviors
to be expressed within the same architectural model, and it allows workflow
boundaries to be either materialized as streams or fused when intermediate
persistence is unnecessary.
