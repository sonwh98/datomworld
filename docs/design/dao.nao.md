# Design: `dao.nao`

## Summary

`dao.nao` is a datomworld-native inference runtime built from
`yin.vm`, `dao.stream`, `dao.db`, `dao.space`, and copied transformer kernels
from vLLM.

The goal is not to wrap vLLM, Ollama, or an OpenAI-compatible server. Those
runtimes model the public operation as a request that produces a token stream.
That shape fixes the input boundary at admission time. Once generation starts,
there is no first-class operation that injects a new `dao.stream` into the
running inference.

`dao.nao` changes that boundary. An inference request may create one
`yin.vm` continuation or decompose into many continuations. Each continuation's
input streams can change through explicit stream injection facts. Multiple
continuations from one request, or from many users, can run, park, resume, fork,
cancel, accept new streams, and batch together.

This is analogous to cooperative multi-threading. The request is like a process,
and its continuations are like fibers. The scheduler runs each continuation
until it yields, parks, completes, or errors. Yielded GPU work from compatible
continuations can then be batched. The hardware advantage comes from exposing a
richer work graph, not from launching one operating-system thread per
continuation.

The heavy tensor math still belongs to transformer kernels. `dao.nao`
does not invent its own GPU kernels. It reuses copied vLLM kernels as
subordinate numeric interpreters for explicit tensor effects emitted by Yin
continuations.

The strategic boundary is:

- `yin.vm` owns inference runtime semantics.
- `dao.stream` carries requests, effects, results, tokens, intents, and errors.
- copied vLLM kernels execute tensor operations.
- kernels do not own sessions, queues, causality, memory policy, or durable
  inference state.

## Core Claim

Inference should not be modeled as a chat-completion RPC whose prompt and
message inputs are fixed when the request starts.

Inference should be modeled as continuation execution:

```text
dao.stream request/control events
-> dao.nao scheduler program in yin.vm
-> many inference continuations
-> batched continuation steps
-> explicit tensor/kernel effects
-> copied vLLM kernels execute numeric work
-> kernel result stream
-> yin.vm resumes continuations
-> token/message/intent/error datoms
```

The model does not own memory, UI, tools, scheduling, or collaboration state. It
is one interpreter inside a larger stream process. Other interpreters decide
how emitted values become memory, rendering, tool use, or agent coordination.

The key flexibility is stream injection. A running or parked continuation can
receive a new input stream as data, record the cursor and purpose of that stream,
and resume if the stream satisfies a wait condition. The alternative in
request/token-stream runtimes is usually cancel-and-restart or build a new
prompt. `dao.nao` should make this a normal runtime transition.

## Scope

`dao.nao` is responsible for:

- admitting inference requests from `dao.stream`
- decomposing inference requests into one or more `yin.vm` continuations
- scheduling continuations that are runnable or have yielded GPU-calculable
  effects
- parking blocked continuations on explicit stream or effect dependencies
- injecting new streams into active continuations
- batching compatible continuation effects to improve hardware utilization
- emitting tokens, completions, errors, telemetry, and intents as datoms
- preserving continuation lineage for branch, replay, and inspection

`dao.nao` is not responsible for:

- using vLLM, Ollama, or OpenAI-compatible APIs as inference engines
- hiding request state inside server sessions
- implementing new transformer kernels from scratch
- letting copied kernels own inference scheduling or causal state
- distributing kernel execution across nodes
- collapsing tool execution, rendering, or memory commit into model execution
- replacing `dao.db`, `dao.space`, `dao.stream`, or `yin.vm`

## Invariants

### 1. An inference request becomes continuations

An inference request is not necessarily one continuation. It is an admitted
intent that may be represented by a root continuation and any number of child
continuations hosted by `yin.vm`.

A continuation carries or references:

- continuation id
- parent continuation id
- root request id
- request id and user or agent identity
- model/program identity
- context state
- sampling state
- KV/cache handles as explicit values
- provenance links to the stream values that produced it

The continuation is not the full prompt, the full context window, or an opaque
kernel session. It is the runtime-execution lineage around which context,
cache, and output can be interpreted.

Splitting one request into many continuations is useful for:

- speculative decoding
- multiple candidate branches
- retrieval or tool sub-plans
- parallel context exploration
- ensemble or debate-style agents
- separating long prefill/context work from decode work
- running independent sub-computations that later join

Every child continuation must preserve its root request and parent lineage so
the final output can be traced back to the originating request.

Continuation decomposition can happen in two ways:

- static decomposition, where the request or prompt is analyzed at admission
  time and expanded into a known continuation graph
- dynamic decomposition, where a running continuation yields a fork, tool,
  retrieval, verifier, or subtask intent that creates more continuations

The static case matters because the prompt that starts an inference may already
imply independent work. The scheduler should be able to create that initial
continuation graph before generation begins, schedule runnable nodes, batch
compatible GPU work, and preserve explicit join points.

### 2. The scheduler/runtime is Yin

Admission, ready queues, wait sets, cancellation, fork, resume, and batching
are part of the `dao.nao` program running in `yin.vm`.

The host may bootstrap the VM and provide transports or kernel dispatch, but
the host must not become the inference scheduler. If a scheduling decision
affects causality, fairness, batching, cancellation, or continuation state, it
belongs in Yin-visible data.

### 3. Multiple continuations can run

Many users and agents may have inference continuations alive at once. A single
user request may also have many continuations alive at once.

Continuations may be:

- runnable
- parked on input
- parked on a kernel result
- parked on tool or memory results
- completed
- cancelled
- forked from a parent continuation
- joined into an aggregate continuation

They may share model weights and kernel code, but they must not share mutable
session state. Any shared value must be explicit: a model id, a cache handle, a
stream position, a datom ref, or a kernel batch id.

The runtime is therefore closer to a cooperative thread scheduler than a
request handler. Continuations are the schedulable units. Requests provide the
root identity and may define an initial continuation graph.

### 4. Stream inputs are dynamic

A continuation's input boundary is not fixed at request admission.

An active continuation may be injected with new streams:

- a retrieval result stream
- a tool result stream
- a user correction stream
- a memory stream
- another agent's output stream
- a policy or capability stream
- a kernel result stream

Injection is not host mutation. It is an explicit command and lifecycle event:

```text
inject stream
-> validate target continuation
-> add stream descriptor and cursor to continuation state
-> emit injection applied or rejected
-> resume continuation if the injected stream satisfies a wait
```

The continuation may then read from the new stream in future context assembly
or execution steps. This is the flexibility missing from token-stream-only
inference APIs: they can stream text out, but they do not let the active
inference gain new input streams as a semantic operation.

### 5. Continuations expose hardware utilization

Continuations make better hardware utilization possible because they expose more
schedulable structure than a single request/token-stream abstraction.

A single continuation often yields a small GPU-calculable value or tensor
effect. Running each yielded value alone underutilizes the GPU. The scheduler
should look across continuations that are not parked on unrelated dependencies,
collect compatible yielded values, and issue larger batched kernel work.

```text
many non-parked continuations
-> yielded GPU-calculable values/effects
-> compatible batch group
-> one batched kernel invocation
-> per-continuation result slices
-> independent continuation resumes
```

This gives the practical throughput benefit of vLLM-style batching without
copying vLLM's opaque scheduler or request-state model. vLLM batches opaque
runtime state. `dao.nao` batches explicit continuation effects.

The speedup is not from CPU threads alone. The speedup comes when the
continuation graph lets the scheduler:

- batch compatible GPU effects from within one request and across requests
- overlap CPU-side stream, retrieval, tool, and context work with GPU kernels
- keep parked work from blocking runnable work
- cancel or deprioritize branches that no longer matter
- keep GPU batches dense while preserving per-continuation causality

If continuations are stepped too finely or GPU effects are launched one by one,
this model can be slower than vLLM. The runtime must preserve coarse enough
batch boundaries for the copied kernels to stay efficient.

### 6. Kernels are subordinate interpreters

Copied vLLM kernels perform tensor math:

- matrix multiplication
- attention
- softmax
- normalization
- activation functions
- KV/cache reads and writes
- quantized operations where supported

They do not decide which request runs next, what a session means, when memory is
committed, or how tool/render/memory intents are interpreted. Kernel inputs and
outputs are values in explicit effect streams.

### 7. KV-cache bytes live in VRAM

For GPU inference, the hot KV-cache is VRAM state. `dao.nao` runs in
`yin.vm` on the CPU and should not carry or mutate raw K/V tensor bytes.

The split is:

```text
dao.nao / yin.vm / RAM:
  cache identity
  cache ownership
  cache position
  cache lineage
  cache lifecycle
  cache capability
  cache handles and block-table refs

kernel layer / VRAM:
  actual K/V tensor blocks
  device pointers
  kernel-specific block layout
  low-level cache reads and writes
```

The continuation references cache handles, not cache tensors:

```clojure
[k-id :cache/handle cache-id t m]
[cache-id :cache/device :cuda:0 t m]
[cache-id :cache/position 2048 t m]
[cache-id :cache/status :cache/active t m]
```

A kernel effect uses the handle:

```clojure
[effect-id :kernel/op :kernel/decode t m]
[effect-id :kernel/cache cache-id t m]
[effect-id :kernel/input input-buffer-id t m]
```

The kernel interpreter resolves `cache-id` to VRAM buffers, runs copied vLLM
kernels, advances the cache, and returns explicit result facts:

```clojure
[result-id :kernel/result-for effect-id t m]
[result-id :cache/advanced-to 2049 t m]
[result-id :kernel/logits logits-handle t m]
```

`dao.nao` does not own KV-cache bytes. It owns KV-cache meaning:
identity, lifecycle, lineage, position, permission, and causal relationship to
continuations.

The cache handle should identify a local kernel host/device. `dao.nao`
may move or resume continuations on different nodes, but it should not split one
kernel invocation across nodes.

### 8. Memory is external

Model weights and KV cache are not durable memory.

Durable memory lives in:

- `dao.stream` for append-only event history
- `dao.db` for stabilized fact memory
- `dao.space` for navigable shared working context

These layers remain distinct even if an implementation projects one into
another for performance.

### 9. Intent is not execution

The model may emit intents:

- message intents
- memory intents
- tool intents
- render intents

Other interpreters decide how those intents are executed or committed. The
inference continuation emits values. It does not directly mutate the world.

## First-Class Values

The first implementation should begin with a small event vocabulary.

Suggested event kinds:

- `:inference/request`
- `:inference/accepted`
- `:inference/queued`
- `:inference/batch`
- `:inference/kernel-effect`
- `:inference/kernel-result`
- `:inference/inject-stream`
- `:inference/inject-applied`
- `:inference/inject-rejected`
- `:inference/token`
- `:inference/message`
- `:inference/tool-intent`
- `:inference/tool-result`
- `:inference/memory-intent`
- `:inference/render-intent`
- `:inference/completed`
- `:inference/cancelled`
- `:inference/error`
- `:continuation/id`
- `:continuation/parent`
- `:continuation/root-request`
- `:continuation/join`
- `:continuation/status`

The goal is not to freeze a final ontology now. The goal is to define enough
values for the first continuation scheduler and kernel-effect loop to be
replayable.

## Interpreter Decomposition

The implementation should separate interpreters by what they are allowed to
decide.

### `dao.nao.scheduler`

A Yin program that owns inference runtime policy.

Responsibilities:

- read request and control streams
- allocate, decompose, or resume continuations
- create initial continuation graphs from admission-time decomposition plans
- maintain ready queues and wait sets
- apply stream injection commands
- group compatible yielded tensor values/effects into batches
- emit lifecycle, token, completion, cancellation, and telemetry datoms

### `dao.nao.context`

Assembles model input from explicit values.

Responsibilities:

- combine request, retrieval results, and continuation lineage
- build context windows
- enforce context budgets
- record which context values were included

### `dao.nao.retrieve`

Retrieves memory from durable substrates.

Responsibilities:

- query `dao.db`
- query vector indexes or vector databases
- call web search interpreters when capability and policy allow it
- scan or subscribe to `dao.stream` sources
- inspect relevant stream history
- read or project shared context from `dao.space`
- execute approved retrieval plans
- emit `:inference/retrieval-result`

## External Data And Context

External data enters an inference through explicit retrieval plans and injected
streams. `dao.nao` should not silently browse, query a vector database, or
invent Datalog queries while assembling context.

The normal flow is:

```text
continuation needs context
-> request, policy, template, or planner supplies a retrieval plan
-> retrieval interpreter reads an external source
-> retrieval results are written to a result stream
-> result stream is injected into the continuation
-> context assembler records what entered the context window
```

Supported retrieval sources may include:

- `dao.db` Datalog queries
- vector indexes or vector databases
- web search
- explicit `dao.stream` histories
- `dao.space` working context
- tool output streams
- memory or profile streams
- policy and capability streams

The source of the retrieval plan must be explicit. It may come from:

- request-declared streams or queries
- named context policies
- schema-specific query templates
- deterministic indexes such as tags, recency, entity links, or embeddings
- injected streams
- a prior continuation that proposes a retrieval intent

### Query Planning

At the point where context is needed, the runtime is not automatically
intelligent. It does not know the right Datalog query by itself.

If an LLM is used to create a query, that LLM work should be represented as a
query-planner continuation:

```text
main continuation needs context
-> scheduler creates query-planner child continuation
-> planner proposes Datalog, vector, stream, or web retrieval intent
-> deterministic validator checks the proposal
-> approved retrieval interpreter executes it
-> result stream is injected into the main continuation
```

The LLM proposes. A validator approves. A retrieval interpreter executes. The
main continuation consumes the result stream.

Validation should check:

- query syntax
- known schema attributes
- capability scope
- source allow-list
- boundedness and result limits
- timeout and cost budget
- whether variables or search terms are grounded enough

Rejected proposals should emit explicit rejection facts. Approved proposals
should record the plan, source, limits, and provenance before execution.

### Context Assembly

Context assembly consumes retrieval result streams and other input streams. It
chooses what enters the model context under an explicit context policy.

Every assembled context window should record:

- continuation id
- root request id
- context policy
- source streams and cursors
- `dao.db` facts or query ids included
- vector/web result ids included
- token or context budget decisions
- provenance of injected streams used

This makes the prompt/context explainable without making external retrieval a
hidden side effect.

### `dao.nao.effects`

Defines effect values emitted by inference continuations.

Responsibilities:

- describe tensor operations as data
- describe KV/cache access as explicit handles
- describe batch compatibility keys
- describe injected stream descriptors and cursors
- preserve provenance from continuation effect to kernel result

### `dao.nao.kernel`

Host-side interpreter for tensor effects.

Responsibilities:

- receive batched tensor effects from streams
- resolve cache and buffer handles to VRAM resources
- call copied vLLM kernels
- return per-continuation result slices
- report cache advancement and logits handles as result facts
- expose kernel errors explicitly
- avoid owning inference sessions or scheduler state
- keep kernel execution local to the selected kernel host/device

### `dao.nao.parse`

Parses model output into explicit structured intents.

Responsibilities:

- turn token streams into messages
- extract tool intents
- extract memory intents
- extract render intents
- reject malformed structured output explicitly

### `dao.nao.commit`

Commits accepted outputs to streams and memory substrates.

Responsibilities:

- append accepted messages to `dao.stream`
- route memory intents to memory interpreters
- route tool intents to tool interpreters
- route render intents to rendering interpreters

## Continuation Runtime Loop

The first runtime loop should be continuation-first:

1. A user, agent, or process appends `:inference/request`.
2. The Yin scheduler validates and accepts the request.
3. The scheduler creates a root continuation or statically decomposes the
   request into an initial continuation graph.
4. Retrieval and context interpreters provide explicit context values from the
   continuations' current input streams.
5. The continuation runs until it emits a token, an intent, yields a
   GPU-calculable value or tensor effect, waits, completes, or errors.
6. Yielded GPU values/effects from non-parked continuations are grouped with
   compatible yielded values/effects from other continuations.
7. The kernel interpreter executes the batch with copied vLLM kernels.
8. Kernel results are split into per-continuation values.
9. Each continuation resumes independently with its result slice.
10. Tokens, messages, intents, telemetry, and terminal facts are appended to
    streams as datoms.

During execution, continuations may dynamically create child continuations. Join
continuations aggregate results from child branches and decide what should be
emitted, cancelled, or continued.

At any point before a terminal state, a control stream may inject a new stream
into the continuation. If the continuation is waiting for that class of input,
the injection can make it runnable. Otherwise, the stream becomes part of the
continuation's future input set.

This loop should work with deterministic fake token generation before real
kernel integration. The first proof is the runtime invariant: multiple
inference continuations are hosted, injected with streams, batched, parked, and
resumed by Yin.

## Batch Compatibility

Batching is a scheduler interpretation over explicit yielded values and
effects. It is the mechanism that turns the richer continuation work graph into
better hardware utilization.

Two continuations can share a GPU batch when each has yielded a value or effect
that the scheduler can issue as one kernel batch without changing either
continuation's semantics. A continuation parked on input, tool output, memory,
policy, or a prior kernel result is not eligible for a new batch until it is
resumed and yields again. Compatibility may include:

- same model or compatible model shard
- same kernel operation
- same dtype and layout
- compatible device placement
- compatible sequence/cache shape
- compatible quantization mode
- compatible sampling stage

The exact compatibility key should be data emitted with the yielded value or
effect. The scheduler groups by that key, emits a batch fact, and records which
continuations participated.

Every batch result must preserve the mapping:

```text
batch id
-> root request id
-> continuation id
-> effect id
-> result slice
```

This is the causal bridge between GPU utilization and independent stream
semantics.

Batching does not require all continuations in a batch to come from different
requests. A batch may contain sibling continuations created by one request,
continuations from unrelated requests, or both.

## Kernel Boundary

`dao.nao` may copy vLLM kernel source and adapt it to this runtime, but it
must not copy vLLM's opaque runtime boundary.

Kernel execution is local. `dao.nao` is distributed; kernels are not. A
kernel effect may be routed to a node that owns the relevant GPU, model weights,
and cache handles, but the copied kernel invocation itself should run on that
local host/device. Cross-node distribution belongs to continuations, streams,
retrieval, tools, planners, verifiers, and joins, not to one tensor kernel.

Accepted from vLLM:

- attention kernels
- paged/KV-cache kernels
- matmul or quantized matmul kernels
- normalization and activation kernels
- kernel launch patterns needed to use those kernels correctly

Not accepted as the runtime boundary:

- vLLM request scheduler
- vLLM session model
- vLLM OpenAI-compatible server API
- vLLM request queues as source of truth
- opaque per-request runtime state
- distributed kernel execution as an inference primitive

If copied vLLM code requires supporting metadata, that metadata should be
projected from explicit continuation and batch values.

Copied kernel source must preserve upstream license notices, provenance, and
local modification history.

## Tool And Render Integration

Tool use should not be modeled as direct execution by the model.

```text
model output
-> tool intent
-> tool interpreter
-> tool result stream
-> continuation resume if needed
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

Multi-agent support emerges naturally from multiple continuations.

In a datomworld-native design:

- agents read from shared streams
- agents write proposals, observations, and outputs as datoms
- agents can fork from shared continuation lineage
- one request can decompose into multiple cooperating continuations
- prompt-derived continuation graphs can run like cooperative threads
- richer continuation graphs can expose CPU/GPU overlap and more batchable work
- coordination happens through explicit values, not hidden mailboxes
- GPU batching can include compatible continuations from different users or
  agents

This makes collaboration replayable and inspectable while still allowing
throughput-oriented batching.

## Distributed Inference

`dao.nao` can be distributed because continuations and streams are
distributed values.

Different nodes may host different parts of the continuation graph:

- retrieval continuations
- query-planner continuations
- tool continuations
- verifier continuations
- branch continuations
- join continuations
- scheduler shards over explicit stream partitions

These continuations coordinate through `dao.stream` and preserve lineage through
datoms. A continuation can be moved, resumed, or forked on another node if its
required streams, capabilities, and cache handles are available.

Tensor kernels are not distributed by `dao.nao`. If a continuation yields
a GPU effect, the scheduler routes that effect to a local kernel interpreter
that owns the relevant model weights, VRAM cache handles, and device. The
result returns as stream facts and resumes the continuation.

The boundary is:

```text
distributed:
  continuations
  streams
  retrieval
  planning
  tools
  verification
  joins

local to kernel host:
  copied vLLM kernel invocation
  VRAM KV-cache bytes
  device pointers
  low-level tensor buffers
```

This keeps the semantic runtime distributed without turning the kernel layer
into a cross-node tensor runtime.

## Suggested Namespace Sketch

One possible initial namespace layout:

```text
dao.nao
dao.nao.scheduler
dao.nao.context
dao.nao.retrieve
dao.nao.effects
dao.nao.kernel
dao.nao.parse
dao.nao.commit
dao.nao.render
```

The exact shape should emerge from implementation pressure, but the scheduler
must remain a Yin-visible program and kernel execution must remain subordinate
to explicit effects.

## Suggested Build Order

1. Define the stream event vocabulary for `dao.nao`.
2. Implement the Yin scheduler with request admission, ready queue, wait set,
   cancellation, and continuation status.
3. Prove one request can statically decompose into a continuation graph and
   that multiple continuations can run with deterministic fake token generation.
4. Add explicit tensor/kernel effect values and batch compatibility keys.
5. Batch compatible yielded GPU values/effects and resume each continuation
   from per-continuation result slices.
6. Integrate copied vLLM kernels behind the kernel-effect interpreter.
7. Add real model identity, weight handles, and KV/cache handles as explicit
   values.
8. Add retrieval, context assembly, structured output parsing, and commit
   interpreters.
9. Add render and tool intent paths.
10. Add continuation forking and branch comparison.

## First Milestone

The first milestone is small but complete:

- a user appends an inference request
- the Yin scheduler creates a root continuation or an initial continuation graph
  for that request
- several continuations can be runnable at once
- continuations yield GPU-calculable values or tensor effects
- the scheduler batches compatible yielded values/effects
- fake token generation returns per-continuation result slices
- each continuation resumes independently
- join continuations aggregate child results when the graph requires it
- token, completion, batch, and telemetry datoms are appended to `dao.stream`

If this loop feels clean, copied vLLM kernel integration can replace fake token
generation without changing the public stream semantics.

## Design Direction

The main idea is simple:

- do not treat inference as a chat API
- do not treat memory as hidden session state
- do not treat vLLM as the runtime
- do not let kernels own causality
- do batch continuations to keep the GPU busy
- do use continuation graphs to expose more schedulable hardware work

Treat inference as explicit Yin continuation execution over explicit streams.
Batch compatible continuation effects for hardware utilization, overlap stream
and context work with GPU execution, run copied vLLM kernels as subordinate
numeric interpreters, then resume each continuation with inspectable causal
facts.
