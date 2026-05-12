# Design: `dao.interference`

## Summary

This document sketches a datomworld-native inference architecture built around
`dao.stream`, `dao.db`, `dao.space`, and existing transformer kernels.

The intent is not to build a better matrix multiply engine first. The intent is
to build an inference system whose memory, collaboration, rendering, and
continuation model obey datomworld invariants:

- no hidden global state
- no implicit control flow
- no shared mutable memory
- explicit causality through streams
- interpretation separated from execution

In this design, the model is one interpreter inside a larger stream process.
Durable memory does not live "inside the LLM." It lives in explicit substrates
that the inference engine reads from and writes to.

## Core Claim

Inference should not be modeled as a chat-completion RPC.

Inference should be modeled as a stream process:

```text
input events
-> retrieval / context assembly
-> model execution
-> intent parsing
-> stream emissions
```

The model does not own memory, UI, tools, or collaboration state. It consumes
selected values and emits new values. Other interpreters decide how those
values become memory, rendering, tool use, or agent coordination.

## Scope

`dao.interference` is responsible for:

- coordinating inference steps
- reading observations and prior state from streams and databases
- assembling model context
- executing model steps against an existing runtime
- parsing model outputs into explicit intents
- appending those intents back into `dao.stream`
- supporting branchable continuations

`dao.interference` is not responsible for:

- implementing low-level GPU kernels
- owning durable memory inside hidden session state
- collapsing tool execution into model execution
- collapsing rendering into model execution
- defining `dao.postgraphics`
- replacing `dao.db`, `dao.space`, or `dao.stream`

## Architectural Position

The low-level transformer runtime should reuse existing kernels.

That means the stack is:

```text
existing kernels
-> model execution runtime
-> dao.interference
-> dao.stream / dao.db / dao.space
-> tools / renderers / agents / users
```

The strategic novelty is not in reimplementing commodity tensor kernels. The
strategic novelty is in making inference native to explicit streams, explicit
memory, branchable continuations, and shared collaboration spaces.

## Invariants

### 1. Inference is a stream process

An inference step reads explicit values and emits explicit values.

No inference-relevant state should be smuggled through hidden mutable session
objects when it can be represented as stream values or runtime handles with
explicit provenance.

### 2. Memory is external

The model weights are not durable memory.

Durable memory lives in:

- `dao.stream` for append-only event history
- `dao.db` for stabilized fact memory
- `dao.space` for navigable shared working context

### 3. Continuations are values

Branching inference state should be represented explicitly. A continuation is
not merely "whatever the runtime happens to remember." It is an identified
execution lineage with provenance.

### 4. Intent is not execution

The model emits intents:

- memory intents
- tool intents
- render intents
- message intents

Other interpreters decide how those intents are executed or committed.

### 5. Rendering is separate

If a model wants to produce visual output, it should emit render intent that is
lowered into `dao.postgraphics`, not paint pixels directly unless a downstream
interpreter explicitly chooses that strategy.

## Minimal First-Class Values

The first implementation should begin with a small event vocabulary.

Suggested event kinds:

- `:inference/request`
- `:inference/context-window`
- `:inference/retrieval-plan`
- `:inference/retrieval-result`
- `:inference/step`
- `:inference/token`
- `:inference/message`
- `:inference/tool-intent`
- `:inference/tool-result`
- `:inference/memory-intent`
- `:inference/render-intent`
- `:inference/error`
- `:continuation/id`
- `:continuation/parent`

The goal is not to freeze a final ontology now. The goal is to define a small,
sufficient vocabulary for the first end-to-end loop.

## Interpreter Decomposition

The system should be split into distinct interpreters with narrow
responsibilities.

### `dao.interference.driver`

Coordinates the overall loop.

Responsibilities:

- read `:inference/request`
- allocate or select a continuation
- trigger retrieval
- trigger context assembly
- invoke model execution
- route outputs to parsers and committers

### `dao.interference.context`

Assembles model input from explicit values.

Responsibilities:

- combine request, retrieval results, and continuation lineage
- build context windows
- enforce token budgets
- choose which prior values are in working context

### `dao.interference.retrieve`

Retrieves memory from durable substrates.

Responsibilities:

- query `dao.db`
- inspect relevant stream history
- read or project shared context from `dao.space`
- emit `:inference/retrieval-result`

### `dao.interference.exec`

Talks to the model runtime.

Responsibilities:

- load or address model handles
- start or resume continuation execution
- step the model
- stream tokens
- expose runtime errors explicitly

### `dao.interference.parse`

Parses model output into explicit structured intents.

Responsibilities:

- turn token streams into messages
- extract tool intents
- extract memory intents
- extract render intents
- reject malformed structured output explicitly

### `dao.interference.commit`

Commits accepted outputs to streams and memory substrates.

Responsibilities:

- append message events to `dao.stream`
- route memory intents to memory interpreters
- route tool intents to tool interpreters
- route render intents to rendering interpreters

### `dao.interference.render`

Lowers visual intent into `dao.postgraphics`.

Responsibilities:

- interpret visual intent
- produce frame programs
- publish frame programs to a `dao.stream`
- keep rendering separate from model execution

## The First End-To-End Loop

The first milestone should be a minimal single-agent loop:

1. A user or process appends `:inference/request`
2. The driver allocates a `:continuation/id`
3. Retrieval gathers relevant values from `dao.db` and `dao.stream`
4. Context assembly builds the model input
5. Execution runs one model step against the runtime
6. Parsing emits:
   - `:inference/message`
   - optional `:inference/memory-intent`
   - optional `:inference/render-intent`
7. Commit appends accepted values back into `dao.stream`
8. Rendering lowers visual intent into `dao.postgraphics` if present

This loop should work before tools, before collaboration, and before advanced
scheduling.

## Memory Model

Use three distinct memory layers.

### Append-only event memory

`dao.stream` captures:

- observations
- requests
- outputs
- tool results
- render emissions
- continuation lineage

This is the replayable causal history.

### Stabilized fact memory

`dao.db` captures durable facts extracted or accepted from event flows.

Examples:

- identity and profile facts
- accepted summaries
- durable references
- semantic links
- environment state worth preserving beyond one stream segment

### Shared working context

`dao.space` captures collaborative working sets and navigable context.

Examples:

- agent-visible working memory
- shared plans
- spatial organization of topics or artifacts
- branch-local collaboration state

These layers should remain distinct even if one implementation reuses another
internally.

## Continuation Model

Continuations should be first-class values with explicit lineage.

A continuation should minimally carry or reference:

- continuation id
- parent continuation id
- model identity
- runtime state handle or KV/cache handle
- retrieval policy or retrieval snapshot
- sampling policy
- provenance links to the input values that produced it

This supports:

- alternate answer branches
- click-driven navigation branches
- speculative execution
- multiple agents starting from a shared state

The continuation is not the same thing as the full context window. It is the
runtime-execution lineage around which context can be rebuilt.

## Runtime Boundary

The runtime should sit behind a stable adapter protocol.

Suggested responsibilities for a runtime adapter:

- load model
- unload model
- start continuation
- step continuation
- stream tokens
- fork continuation if supported
- release continuation

This boundary allows different execution backends without disturbing stream
semantics:

- CUDA-backed
- Metal-backed
- CPU-backed
- remote execution backend

## Tool And Render Integration

Tool use should not be modeled as direct execution by the model.

Instead:

```text
model output
-> tool intent
-> tool interpreter
-> tool result stream
```

Likewise for rendering:

```text
model output
-> render intent
-> render interpreter
-> dao.postgraphics frame stream
```

And likewise for memory:

```text
model output
-> memory intent
-> memory interpreter
-> dao.db / dao.space / dao.stream
```

This preserves the separation between interpretation and execution.

## Multi-Agent Collaboration

Multi-agent support should be added after the single-agent loop is clean.

In a datomworld-native design:

- agents read from shared streams
- agents write proposals, observations, and outputs as datoms
- agents can fork from shared continuation lineage
- coordination happens through explicit values, not hidden mailboxes

This makes collaboration replayable and inspectable.

It also allows different agents to share a memory substrate without sharing
mutable object state.

## Suggested Namespace Sketch

One possible initial namespace layout:

```text
dao.interference
dao.interference.driver
dao.interference.context
dao.interference.retrieve
dao.interference.exec
dao.interference.parse
dao.interference.commit
dao.interference.render
dao.interference.runtime.protocol
dao.interference.runtime.cuda
dao.interference.runtime.metal
dao.interference.runtime.cpu
```

The exact shape should emerge from implementation pressure, but the separation
of concerns should remain visible.

## Suggested Build Order

1. Define the event schema for `dao.interference`
2. Implement a single-agent driver
3. Implement a runtime adapter over existing kernels
4. Implement retrieval from `dao.db` and recent `dao.stream` history
5. Implement a structured output parser
6. Implement the commit layer
7. Add a `dao.postgraphics` render path
8. Add continuation forking
9. Add tool intents and tool-result routing
10. Add multi-agent shared-space support

## First Milestone

The first milestone is small but complete:

- a user appends an inference request
- the system reads relevant memory from `dao.db`
- the model runs one step
- the system appends a response to `dao.stream`
- the system optionally emits memory intents
- the system optionally emits a `dao.postgraphics` frame

If this loop feels clean, the rest of the architecture can emerge from it.

## Design Direction

The main idea is simple:

- do not treat inference as a chat API
- do not treat memory as hidden session state
- do not treat rendering as a side effect hidden inside the model

Treat inference as explicit stream interpretation over explicit memory
substrates, with branchable continuations and explicit outputs.

That is the path by which transformer inference becomes native to datomworld
rather than merely hosted inside it.
