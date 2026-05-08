# Design: `dao.flow`

## Summary

`dao.flow` is a workflow composition layer over `dao.stream`.

More precisely:

- `dao.stream` defines streams of values
- `dao.flow` provides the algebra for composing interpreters over those streams
  into workflows

Those values are not limited to ordinary data values. They may also include
continuations carried as values.

A workflow is a composed path of interpretation from input values to downstream
values or terminal effects.

In `dao.flow`, workflows are explicit DAG-like arrangements of:

- input streams
- interpreter stages
- optional intermediate boundaries
- downstream streams or terminal stages

UI, graphics, compute, text, audio, logging, and other domains can all be
modeled on top of this substrate. Those domains are examples, not the
definition of `dao.flow`.

Short distinction:

- `dao.stream` is the stream substrate
- `dao.flow` is the algebra of stream-based workflow composition

## Relationship to `dao.stream`

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

More generally, a workflow may branch and merge into a DAG-like topology:

```text
           -> interpreter-b -> stream-b ->
stream-a ->                               -> terminal
           -> interpreter-c -> stream-c ->
```

The point is not graph theory for its own sake. The point is explicit causality
and explicit boundaries between stages.

## Flow Algebra

`dao.flow` can be described algebraically as a system for composing workflow
fragments over `dao.stream`.

### Carrier Sets

Useful sets for the model are:

- `V`
  - values flowing through streams
- `S`
  - streams carrying values from `V`
- `I`
  - interpreters
- `T`
  - terminals
- `F`
  - workflow fragments

The main compositional carrier is `F`, not raw streams alone.

`V` is intentionally broad. It may include suspended computations represented
as continuations, not only inert data.

A workflow fragment is a partially connected arrangement of:

- input stream boundaries
- interpreter stages
- optional intermediate stream boundaries
- output stream boundaries or terminal boundaries

Composition is valid only when connected boundaries have compatible
vocabularies.

### Core Intuition

- `dao.stream` provides the edges
- interpreters and terminals provide the nodes
- `dao.flow` provides the algebra of wiring them together

So the algebra is not merely “streams composed with streams.” It is the algebra
of composing interpreters over streams into workflows.

### Core Operations

The model should support these capabilities at the workflow level:

- `empty-flow`
- `singleton-interpreter`
- `singleton-terminal`
- `compose`
- `branch`
- `merge`

These names are descriptive, not mandatory API names.

### `empty-flow`

Identity starting point for workflow construction.

```text
empty-flow : -> F
```

### `singleton-interpreter`

Create a fragment containing one interpreter stage with exposed input and output
boundaries.

```text
singleton-interpreter : I -> F
```

### `singleton-terminal`

Create a fragment containing one terminal with an exposed input boundary.

```text
singleton-terminal : T -> F
```

### `compose`

Connect the output boundary of one workflow fragment to the input boundary of
another.

```text
compose : F × F -> F
```

This is the basic pipeline operation.

### `branch`

Expose one upstream boundary to multiple downstream readers.

```text
branch : F × [F] -> F
```

The first argument is the upstream fragment. The second argument is the set of
downstream fragments to connect to that exposed boundary.

### `merge`

Combine multiple upstream branches into one downstream stage when the stage
semantics support it.

```text
merge : [F] × F -> F
```

The legality of merge depends on the interpreter semantics, but the workflow
algebra must be able to represent the topology.

"Illegal merge" means a composition-time error: if the downstream stage
does not declare a compatible multi-input boundary or merge strategy, the
workflow fragment is invalid and merge construction fails explicitly.

## Algebraic Laws

### Closure

Composing valid workflow fragments yields a valid workflow fragment or an
explicit error.

### Identity

`empty-flow` is the neutral construction starting point when vocabularies are
compatible.

Conceptually:

```text
compose(empty-flow, f) = f
compose(f, empty-flow) = f
```

### Associativity

Sequential composition should be associative when the exposed output
vocabularies and input vocabularies of the participating fragments are mutually
compatible.

Conceptually:

```text
compose(compose(a, b), c) = compose(a, compose(b, c))
```

This is a law about valid compositions, not arbitrary fragments.

### Referential Transparency at the Workflow Level

The same workflow over the same input streams should yield the same downstream
values or terminal effects, modulo allowed differences in observability,
scheduling, or buffering.

### Materialized/Fused Equivalence

Materializing a boundary and fusing it are semantically equivalent execution
strategies.

This is the most distinctive law of `dao.flow`.

### Structure Preservation

Branching, merging, and sequential composition should preserve explicit
causality. A workflow is defined by the path values take through interpreters,
not by hidden runtime coupling.

## Materialized and Fused Boundaries

`dao.flow` supports two equivalent composition modes.

Materialization and fusion belong to execution planning, not to the core
workflow algebra.

At the algebra level, a workflow specifies causality and stage connectivity.
At the execution level, a runner may choose whether a boundary is:

- materialized as an explicit `dao.stream`
- fused as an in-flight execution strategy

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

In this mode, the stream remains explicit at the workflow boundary, while
intermediate interpretation remains in-flight.

One implementation technique for fused execution is `stream-transduce`:

```clojure
(stream-transduce xform rf acc input-stream)
```

Where:

- `xform` is a transducer or composed transducer stack
- `rf` is a reducing function or terminal interpreter
- `acc` is the initial accumulator or terminal state
- `input-stream` is the upstream `dao.stream`

`stream-transduce` is an implementation technique for fused execution. It is
not part of the abstract algebra itself.

### Invariant

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

- layout interpreter
- geometry interpreter
- dispatch-planning interpreter
- text-frame interpreter
- audio-mixing interpreter
- logging interpreter
- backend terminal interpreter

These names are illustrative, not mandatory built-ins. `dao.flow` is defined
by the workflow pattern, not by one fixed stage catalog.

## Terminals

A terminal interpreter is the point where a workflow stops producing further
stream values and instead produces concrete effects.

A terminal may target:

- Flutter drawing
- GPU rendering
- GPU compute
- TUI rendering
- SVG emission
- audio output
- logging sinks
- file output

The distinction is not between "real" terminals and "debug" terminals. The
distinction is whether the interpreter continues the workflow as a stream or
realizes it into effects.

## Lifecycle and Failure

`dao.flow` defines workflow composition, but a runner still needs lifecycle
rules.

Minimal workflow lifecycle:

- a workflow starts when its input boundaries are attached and the runner begins
  advancing ready stages
- a workflow continues while stages can consume inputs or produce downstream
  values/effects
- a workflow stops when terminals complete, or when upstreams are exhausted and
  no runnable work remains

Failure handling must be explicit:

- stage failure must surface as an explicit runner-level failure
- it must not be silently swallowed
- whether failures are transformed into downstream values is domain-specific,
  not a universal `dao.flow` rule
- when an upstream stage fails, downstream propagation behavior is runner policy,
  but it must be explicit rather than implicit or silent

Reduced termination is the case where a reducing or terminal stage explicitly
signals early completion. The runner should stop that path cleanly without
inventing additional downstream values.

## Scheduling

Scheduling in `dao.flow` means the policy by which a runner advances ready
stages over available input values.

It may affect:

- when a ready stage runs
- which ready stage runs first
- where a stage runs
- how buffered values are drained over time

It must not affect semantic result, except where observability or timing is
explicitly outside the semantic contract.

## Workflow Domains

`dao.flow` is domain-agnostic at the workflow level.

Example domains that can live on top of it:

- UI workflows
- graphics workflows
- compute workflows
- text workflows
- audio workflows
- telemetry workflows
- logging workflows

Examples:

UI workflow:

```text
scene stream -> layout interpreters -> graphics bytecode -> Flutter terminal
```

Compute workflow:

```text
compute intent stream -> planning interpreters -> execution terminal -> buffers/results
```

Text workflow:

```text
state stream -> text interpreters -> TUI terminal -> terminal frame
```

Audio workflow:

```text
audio event stream -> mixing interpreters -> audio terminal -> samples
```

The important point is that these are all `dao.flow` workflows. They differ in
payload vocabulary and terminal realization, not in the underlying composition
model.

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

- UI namespaces may define scene or layout vocabularies
- graphics namespaces may define geometry or bytecode vocabularies
- compute namespaces may define intent-to-dispatch transformations
- audio namespaces may define event-to-mix transformations
- backend namespaces may define terminal realizations for Flutter, GPU APIs,
  TUI runtimes, audio devices, logs, or files

`dao.flow` provides the workflow model in which these interpreters run. It
does not absorb their semantics.

## Relationship to Specific Designs

Scene-graph walking, camera semantics, graphics bytecode schemas, layout
semantics, dispatch vocabularies, painter details, and similar domain concerns
belong to domain-specific design documents and namespaces. They may be good
users of `dao.flow`, but they are not the definition of `dao.flow`.

This separation keeps the flow layer stable while allowing multiple producer
and interpreter ecosystems to emerge on top of it.

## Comparison Intuition

`dao.flow` is closer to a stream-composed workflow or DAG substrate than to a
UI-only or GPU-only layer.

At small scale, the most useful intuition is Unix pipelines:

- if `dao.stream` is the pipe
- `dao.flow` is the pipeline algebra

At larger scale, it is somewhat analogous to topology-oriented stream systems
such as Apache Storm or Red Planet Rama in that:

- stages consume and emit flowing values
- topologies can branch and merge
- execution can be understood as a graph of processing stages

But `dao.flow` differs in important ways:

- it is built on `dao.stream` value semantics
- replay and branching are part of the substrate
- fused and materialized boundaries are treated as semantically equivalent
  execution strategies
- it is not defined primarily as a distributed systems runtime

So the right mental model is:

- a workflow/DAG composition layer over `dao.stream`
- not merely a rendering subsystem

## Non-Goals

`dao.flow` should not:

- depend on `dao.db`
- define scene-graph semantics as universal
- define GPU semantics as universal
- define audio semantics as universal
- define logging semantics as universal
- make domain terms such as `walk`, `paint`, `camera`, `mix`, or `log` foundational
- require intermediate `dao.stream`s merely to name temporary computations
- collapse workflow composition and domain interpretation into the same layer

Domain interpreters belong in domain namespaces. `dao.flow` defines the
workflow model they use.

## Test Contracts

Tests for `dao.flow` should verify workflow behavior, not one particular
graphics, compute, UI, or audio domain.

Examples:

- fused execution equals materialized execution for the same inputs
- stream order is preserved through interpreter composition
- replay reproduces the same downstream values
- multiple readers observe the same materialized boundary values
- terminal invocation order is stable
- reduced termination stops the workflow cleanly
- composition with incompatible boundaries fails explicitly
- merge without a declared compatible multi-input boundary fails explicitly
- upstream failure handling is explicit at the runner level

Domain correctness belongs in domain-level tests.

## Risks and Trade-offs

- Materialized boundaries improve inspection and replay but cost storage,
  buffering, and scheduling overhead.
- Fused execution reduces intermediate allocation but removes explicit stream
  identity for those boundaries.
- Slow terminals or stages can create backpressure and unbounded buffering if a
  workflow or transport does not constrain it explicitly.
- Over-generalizing stage vocabulary inside `dao.flow` would collapse domain
  interpretation into the workflow layer.
- Over-materializing every boundary would turn streams into mandatory temporary
  storage rather than intentional observability points.
- Continuation-bearing workflows increase expressive power but also increase the
  importance of explicit lifecycle, replay, and failure semantics.

The design goal is explicit causality without unnecessary transport.

## Summary

`dao.flow` is the layer above `dao.stream` where workflows are constructed from
streams, interpreters, and terminals. UI, graphics, compute, text, audio, and
other domains can all be expressed within this architectural model, and
workflow boundaries may be either materialized as streams or fused when
intermediate persistence is unnecessary.
