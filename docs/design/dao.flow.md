# Design: `dao.flow`

## Summary

`dao.flow` is a datomworld-native general workflow orchestrator built from
`yin.vm`, `dao.stream`, `dao.db`, `dao.space`, and a pluggable effect registry.

It provides: continuation scheduling, stream injection, park and resume, fork
and join, batching of compatible work units, causal lineage as datoms, and
stigmergic coordination via `dao.space`. These capabilities apply to any
workload: business processes, data pipelines, agent task graphs, or machine
learning.

The effect registry is pluggable. Any effect type — SQL queries, HTTP calls,
tool execution, vector search, ML kernels — is a registered interpreter that
`dao.flow` dispatches to. Adding ML kernel effect interpreters is what makes
`dao.flow` an ML workflow runtime substrate. That is configuration, not
architecture.

```text
application  (inference engine, training engine, agent runtime, pipeline)
    provides: workflow graph, admission policy, output parsing (where needed)

dao.flow  (general workflow orchestrator)
    provides: scheduling, stream injection, batching, causality

effect registry
    ML kernels      — transformer, diffusion, vision, audio, 3D, biology, ...
    other effects   — SQL queries, HTTP calls, tool execution, vector search, ...

datomworld substrate
    dao.db      — durable fact memory, optimizer state, query
    dao.stream  — append-only event history, checkpointing, causality
    dao.space   — shared resource announcements, batch slot visibility,
                  node capabilities, stigmergic coordination

GPU  (when ML kernels are present)
    provides: parallel compute
```

The workflow graph is what makes `dao.flow` behave as an LLM engine, a
diffusion engine, an audio pipeline, a business process, or anything else. The
scheduler, stream injection, batching, and causality machinery do not change
across workloads. What changes is the graph shape and the effect interpreters
each continuation dispatches.

A workflow may create one `yin.vm` continuation or decompose into many.
Continuations run, park, resume, fork, cancel, accept new streams, and batch
together. This is analogous to cooperative multi-threading. The workflow is like
a process; its continuations are like fibers.

Effect interpreters are subordinate. They are called with explicit inputs and
return explicit results. They do not own sessions, queues, causality, memory
policy, or durable state.

The strategic boundary is:

- `yin.vm` owns the continuation runtime and scheduler semantics.
- `dao.stream` carries requests, effects, results, intents, and errors.
- `dao.space` carries resource announcements, batch slot availability, and node
  capabilities that enable federated scheduling and stigmergic coordination.
- `dao.flow` orchestrates continuations and dispatches effects.
- effect interpreters execute work and return results as explicit values.
- effect interpreters do not own sessions, queues, causality, or durable state.

## Core Claim

Computation should not be modeled as a request/response RPC whose inputs are
fixed when the request starts.

Computation should be modeled as continuation execution over explicit effects:

```text
dao.stream request/control events
-> dao.flow scheduler program in yin.vm
-> many continuations (workflow graph)
-> batched continuation steps
-> explicit effects (kernel calls, queries, HTTP, tools, ...)
-> effect registry executes work
-> result stream
-> yin.vm resumes continuations
-> result/intent/error datoms
```

An inference engine, a training engine, a business process, and a data pipeline
are all applications of this model. The workflow graph each provides determines
what the continuations do and which effects they dispatch. `dao.flow` does not
know or care what kind of workload it is running.

Effect interpreters are not the runtime. Each is one subordinate interpreter
inside a larger stream process. Other interpreters decide how emitted values
become memory, rendering, tool use, or agent coordination.

The key flexibility is stream injection. A running or parked continuation can
receive a new input stream as data, record the cursor and purpose of that stream,
and resume if the stream satisfies a wait condition. This makes mid-computation
input changes a normal runtime transition rather than a cancel-and-restart.

## Pipeline Intuition

`dao.flow` is structurally similar to threading a value through a pipeline.

A Clojure thread macro expresses this directly:

```clojure
(-> request
    admit
    decompose
    schedule
    batch-effects
    dispatch-effects
    resume-continuations
    emit-results)
```

Each step receives the output of the previous one. The topology is fixed, the
execution is single-threaded, every request follows the same path.

`dao.flow` generalizes this in four directions:

**Concurrent** — many continuations flow through the pipeline simultaneously,
not one at a time. Each continuation is an independent thread of execution
through the same stages.

**Dynamic** — the topology can grow at runtime. A continuation that emits a
domain intent or fork intent creates new continuations. The pipeline graph
expands as execution proceeds.

**Parkable** — a continuation can stop mid-pipeline and wait. It parks until a
stream injection or an effect result satisfies its wait condition. Other
continuations keep moving.

**Batchable** — continuations that reach the same stage at the same time with
compatible effects can be grouped into one batched dispatch, then split back to
independent continuations with their result slices.

The thread macro is a degenerate case of `dao.flow`: one continuation, static
topology, no parking, no branching, no batching. `dao.flow` is the general form.

The intuition for explaining `dao.flow` to someone unfamiliar:

> It is like threading a request through a pipeline, except many requests
> thread concurrently, the pipeline topology can change mid-execution, any
> step can park and wait for new input, and every step is a first-class value
> you can inspect, replay, or redirect.

The deeper connection: `->` in Clojure is itself a syntactic
continuation-passing transformation. Each step receives a value and passes a
value forward. `dao.flow` makes those values explicit stream facts and the steps
explicit continuation states, rather than collapsing them into a single
synchronous call stack.

## Scope

`dao.flow` is responsible for:

- admitting workflow requests from `dao.stream`
- decomposing requests into one or more `yin.vm` continuations
- scheduling continuations that are runnable or have yielded effects
- parking blocked continuations on explicit stream or effect dependencies
- injecting new streams into active continuations
- dispatching effects to the appropriate interpreter in the registry
- batching compatible continuation effects to improve utilization
- emitting results, errors, telemetry, and intents as datoms
- preserving continuation lineage for branch, replay, and inspection

`dao.flow` is not responsible for:

- being an inference engine — inference engines are built on top of `dao.flow`
- using vLLM, Ollama, or OpenAI-compatible APIs as its runtime
- hiding request state inside server sessions
- implementing new GPU kernels from scratch
- letting effect interpreters own scheduling or causal state
- distributing effect execution across nodes
- collapsing tool execution, rendering, or memory commit into effect execution
- replacing `dao.db`, `dao.space`, `dao.stream`, or `yin.vm`

## Invariants

### 1. A workflow request becomes continuations

A workflow request is not necessarily one continuation. It is an admitted
intent that may be represented by a root continuation and any number of child
continuations hosted by `yin.vm`.

A continuation carries or references:

- continuation id
- parent continuation id
- root request id
- request id and user or agent identity
- program or workflow identity
- execution state
- provenance links to the stream values that produced it

The continuation is not an opaque session. It is the runtime-execution lineage
around which inputs, effects, and outputs can be interpreted.

Splitting a workflow request into many continuations is useful for:

- speculative execution
- multiple candidate branches
- retrieval or tool sub-plans
- parallel context exploration
- ensemble or debate-style agents
- separating preparation work from execution work
- running independent sub-computations that later join

Every child continuation must preserve its root request and parent lineage so
the final output can be traced back to the originating request.

Continuation decomposition can happen in two ways:

- static decomposition, where the request or task is analyzed at admission
  time and expanded into a known continuation graph
- dynamic decomposition, where a running continuation yields a fork, tool,
  retrieval, verifier, or subtask intent that creates more continuations

The static case matters because a workflow request may already imply independent
work. The scheduler should be able to create that initial continuation graph
before execution begins, schedule runnable nodes, batch compatible effects, and
preserve explicit join points.

### 2. The scheduler/runtime is Yin

Admission, ready queues, wait sets, cancellation, fork, resume, and batching
are part of the `dao.flow` program running in `yin.vm`.

The host may bootstrap the VM and provide transports or effect dispatch, but
the host must not become the scheduler. If a scheduling decision affects
causality, fairness, batching, cancellation, or continuation state, it belongs
in Yin-visible data.

### 3. Multiple continuations can run

Many users and agents may have continuations alive at once. A single workflow
request may also have many continuations alive at once.

Continuations may be:

- runnable
- parked on input
- parked on an effect result
- parked on tool or memory results
- completed
- cancelled
- forked from a parent continuation
- joined into an aggregate continuation

They must not share mutable session state. Any shared value must be explicit:
a program id, a stream position, a datom ref, or a batch id. When ML kernels
are present, shared values also include model ids and cache handles.

The runtime is therefore closer to a cooperative thread scheduler than a
request handler. Continuations are the schedulable units. Requests provide the
root identity and may define an initial continuation graph.

### 4. Stream inputs are dynamic

A continuation's input boundary is not fixed at request admission.

An active continuation may be injected with new streams:

- an effect result stream
- a user correction stream
- another agent's output stream
- a policy or capability stream
- a domain result stream (retrieval, tool, memory, or kernel result)

Injection is not host mutation. It is an explicit command and lifecycle event:

```text
inject stream
-> validate target continuation
-> add stream descriptor and cursor to continuation state
-> emit injection applied or rejected
-> resume continuation if the injected stream satisfies a wait
```

The continuation may then read from the new stream in future execution steps.
This is the flexibility missing from request/response APIs: they fix the input
boundary at admission time and have no first-class operation for injecting new
input into a running computation.

### 5. Continuations enable batching

Continuations make better resource utilization possible because they expose
more schedulable structure than a single request/response model.

A single continuation often yields one small effect. Dispatching each effect
alone underutilizes available resources. The scheduler should look across
non-parked continuations, collect compatible yielded effects, and dispatch them
as a batch.

```text
many non-parked continuations
-> yielded effects with compatible batch keys
-> compatible batch group
-> one batched dispatch
-> per-continuation result slices
-> independent continuation resumes
```

The speedup comes when the continuation graph lets the scheduler:

- batch compatible effects from within one request and across requests
- overlap CPU-side stream and coordination work with effect execution
- keep parked work from blocking runnable work
- cancel or deprioritize branches that no longer matter

When the effect registry includes ML kernels, this batching extends to GPU
kernel invocations. See the ML Extension section for GPU-specific details.

### 6. State is external

Effect interpreters do not own durable state.

Durable state lives in:

- `dao.stream` for append-only event history
- `dao.db` for stabilized fact memory
- `dao.space` for navigable shared working context

These layers remain distinct even if an implementation projects one into
another for performance.

### 7. Intent is not execution

A continuation may emit intents:

- message intents
- memory intents
- tool intents
- render intents

Other interpreters decide how those intents are executed or committed. A
continuation emits values. It does not directly mutate the world.

## First-Class Values

The first implementation should begin with a small event vocabulary.

Core workflow event kinds (apply to any workload):

- `:workflow/request`
- `:workflow/accepted`
- `:workflow/queued`
- `:workflow/batch`
- `:workflow/effect`
- `:workflow/effect-result`
- `:workflow/inject-stream`
- `:workflow/inject-applied`
- `:workflow/inject-rejected`
- `:workflow/completed`
- `:workflow/cancelled`
- `:workflow/error`
- `:workflow/intent`
- `:intent/kind` (values: `:intent/tool`, `:intent/render`, `:intent/memory`)

  ```clojure
  [intent-id :workflow/intent true      t m]
  [intent-id :intent/kind    :intent/tool  t m]
  ```
- `:continuation/id`
- `:continuation/parent`
- `:continuation/root-request`
- `:continuation/join`
- `:continuation/status`

ML-specific event kinds (apply when any ML application profile is active):

- `:ml/kernel-effect`
- `:ml/kernel-result`

Inference-specific event kinds (apply when the inference profile is active):

- `:inference/token`
- `:inference/message`

The goal is not to freeze a final ontology now. The goal is to define enough
values for the first continuation scheduler and effect loop to be replayable.

## Interpreter Decomposition

The implementation should separate interpreters by what they are allowed to
decide.

### `dao.flow.scheduler`

A Yin program that owns workflow scheduling policy.

Responsibilities:

- read request and control streams
- allocate, decompose, or resume continuations
- create initial continuation graphs from admission-time decomposition plans
- maintain ready queues and wait sets
- apply stream injection commands
- group compatible yielded effects into batches
- emit lifecycle, completion, cancellation, and telemetry datoms

### `dao.flow.effects`

Defines effect values emitted by workflow continuations.

Responsibilities:

- describe effect payloads as explicit data
- define batch compatibility key schema
- describe injected stream descriptors and cursors
- preserve provenance from continuation effect to result

ML-specific effects (tensor operations, KV/cache handles) are part of the ML
application package. See the ML Extension section.

## Continuation Runtime Loop

The first runtime loop should be continuation-first:

1. A user, agent, or process appends a `:workflow/request`.
2. The Yin scheduler validates and accepts the request.
3. The scheduler creates a root continuation or statically decomposes the
   request into an initial continuation graph.
4. Input interpreters provide explicit values from the continuation's current
   input streams, when the workflow requires them.
5. The continuation runs until it emits an intent, yields an effect, waits,
   completes, or errors.
6. Yielded effects from non-parked continuations are grouped with compatible
   yielded effects from other continuations.
7. The effect interpreter executes the batch via the effect registry.
8. Results are split into per-continuation values.
9. Each continuation resumes independently with its result slice.
10. Results, intents, telemetry, and terminal facts are appended to streams
    as datoms.

During execution, continuations may dynamically create child continuations. Join
continuations aggregate results from child branches and decide what should be
emitted, cancelled, or continued.

At any point before a terminal state, a control stream may inject a new stream
into the continuation. If the continuation is waiting for that class of input,
the injection can make it runnable. Otherwise, the stream becomes part of the
continuation's future input set.

This loop should work with deterministic fake effect results before real
effect interpreter integration. The first proof is the runtime invariant:
multiple continuations are hosted, injected with streams, batched, parked, and
resumed by Yin.

## Batch Compatibility

Batching is a scheduler interpretation over explicit yielded effects. It is the
mechanism that turns the richer continuation work graph into better resource
utilization.

Two continuations can share a batch when each has yielded an effect that the
scheduler can dispatch together without changing either continuation's
semantics. A continuation parked on input, tool output, memory, policy, or a
prior effect result is not eligible for a new batch until it is resumed and
yields again.

The compatibility key is data emitted with the yielded effect:

```clojure
[effect-id :effect/compat-key compat-key t m]
```

The scheduler groups by that key, emits a batch fact, and records which
continuations participated.

For generic workflows the compat key may be as simple as the effect type. For
ML workloads it includes model identity, kernel operation, dtype, device
placement, and cache shape. Both cases use the same batching mechanism.

Every batch result must preserve the mapping:

```text
batch id
-> root request id
-> continuation id
-> effect id
-> result slice
```

This is the causal bridge between resource utilization and independent stream
semantics.

Batching does not require all continuations in a batch to come from different
requests. A batch may contain sibling continuations created by one request,
continuations from unrelated requests, or both.

## Multi-Agent Collaboration

Multi-agent support emerges naturally from multiple continuations.

In a datomworld-native design:

- agents read from shared streams
- agents write proposals, observations, and outputs as datoms
- agents can fork from shared continuation lineage
- one request can decompose into multiple cooperating continuations
- continuation graphs can run like cooperative threads
- richer continuation graphs can expose more batchable work
- coordination happens through explicit values, not hidden mailboxes
- compatible continuations from different users or agents can share a batch

This makes collaboration replayable and inspectable while still allowing
throughput-oriented batching.

## Federated And Distributed Execution

`dao.flow` can be federated and distributed because continuations and streams
are distributed values. Distributed execution means moving or placing
continuations across `dao.flow` nodes, not splitting one effect invocation
across nodes.

Different nodes may host different parts of the continuation graph:

- retrieval continuations
- query-planner continuations
- tool continuations
- verifier continuations
- branch continuations
- join continuations
- scheduler shards over explicit stream partitions

These continuations coordinate through `dao.stream` and preserve lineage through
datoms.

### Continuation Migration

A continuation can migrate to another `dao.flow` node for computation when that
node has better locality or authority for the next step. The migrated unit is
not an output stream or an opaque session. It is explicit continuation state
plus dependencies:

- continuation id
- root request id
- parent lineage
- current status
- required stream descriptors and cursors
- injected input stream set
- required capabilities
- model or program identity
- pending wait/effect state
- cache handle references, when relevant

Migration is useful when another node has better access to:

- private or local `dao.db` facts
- local `dao.stream` history
- tools or web/search capabilities
- policy or capability authority
- a compute host with available capacity for this effect type
- a continuation branch's required working context
- a node with an open batch for a compatible effect

The receiving node validates capabilities, hydrates required streams or context,
resumes or parks the continuation, and emits result or lifecycle datoms back to
the appropriate streams.

```text
node A root request
-> creates continuation graph
-> migrates retrieval continuation to node B near private data
-> migrates web-search continuation to node C with web capability
-> migrates compute continuation to node D with the required effect host
-> receives result streams
-> join continuation aggregates
```

### Effect Host Locality

Some effects must execute on a specific host. A continuation that has yielded
such an effect should route to the node that owns the required resource — a
private data store, a specific tool, or a compute device with the needed
capabilities — rather than require the resource to move to the continuation.

The scheduler distinguishes continuations that are free to migrate from those
bound to a host by their effect requirements. Migration is cheap for work with
no host-binding constraint. Host-bound work stays in place; the scheduler pulls
compatible effects toward it.

When ML kernels are present, GPU KV-cache locality is the primary host-binding
constraint. See the ML Extension section.

### Resource Announcement via dao.space

For federated nodes to coordinate — migration decisions, batch filling,
capability routing — they need to discover each other's resources without
explicit message passing. This is stigmergic coordination, made possible by
`dao.space`.

Each `dao.flow` node writes its current resource state as datoms into a shared
`dao.space`. Other nodes query that space to make scheduling decisions. No node
needs to know about any other node directly. Coordination emerges from the
shared fact space.

A node announces its resources:

```clojure
[node-id :node/capabilities    #{:web-search}     t m]
[node-id :node/effect-types    #{:http :sql}      t m]
[node-id :node/status          :ready             t m]
```

When ML kernels are present, a node also announces GPU resources. See the ML
Extension section for GPU-specific resource datoms.

A node announces open batch slots:

```clojure
[batch-id :batch/node            node-id          t m]
[batch-id :batch/compat-key      compat-key       t m]
[batch-id :batch/slots-remaining 4                t m]
```

A federated scheduler finds nodes with compatible open slots via Datalog:

```clojure
[:find ?node ?slots
 :where [?b :batch/compat-key   compat-key]
        [?b :batch/slots-remaining ?slots]
        [?b :batch/node         ?node]
        [(> ?slots 0)]]
```

A scheduler looking to route a retrieval continuation queries for nodes with
the required capability:

```clojure
[:find ?node
 :where [?node :node/capabilities ?caps]
        [(contains? ?caps :private-dao-db)]]
```

This is stigmergy: nodes leave facts in a shared space; other nodes act on
those facts without any direct coupling. The full coordination history is
preserved in `dao.stream` for replay, audit, and debugging. No hidden
mailboxes, no direct RPC between schedulers, no centralized coordinator.

### Federated Batch Filling

Batch compatibility is not constrained by node boundaries. A continuation that
has yielded a compatible effect can migrate to another node not because of data
locality but because that node has room in a compatible batch.

```text
node A: 3 continuations yielded compatible effects, batch not full
node B: 5 continuations yielded compatible effects, batch not full

federated scheduler:
  8 compatible effects visible across both nodes
  -> migrate node A continuations to node B
  -> one full batch dispatched on node B
  -> per-continuation result slices returned
  -> continuations resume on node B or migrate back
```

This turns batch filling from a local problem into a cluster-wide optimization.

A node with low traffic would normally underutilize its resources — batches stay
thin. With federated batch filling, the scheduler pulls compatible continuations
from across the cluster to fill every batch. A busy node can shed continuations
to less loaded nodes to keep batches dense without dropping requests.

**What makes this possible:**

- continuations are explicit mobile values, not opaque server sessions
- effects carry explicit batch compatibility keys
- the compatibility key is the same regardless of which node the continuation
  originated from
- migration cost is streaming the continuation state facts, not copying data
- `dao.space` makes open batch slots visible cluster-wide as queryable datoms —
  no direct node-to-node messaging required

**Batch-filling migration is cheapest for:**

- retrieval, planning, and tool continuations
- continuations with no host-bound state
- early steps of any workflow before effect-host binding is established

When ML kernels are present, GPU KV-cache depth constrains migration further.
See the ML Extension section.

## Application Profiles

The following sections describe how dao.flow is configured for specific
workloads. Each profile provides a workflow graph, a set of effect interpreters,
and domain-specific event vocabulary. The core runtime is unchanged.

### LLM Inference

```text
request
-> prefill continuation (process prompt tokens)
-> decode loop continuation (autoregressive token generation)
-> parse continuation (extract intents from output)
-> commit continuation
```

Kernel source: vLLM (attention, paged KV-cache, matmul, normalization). The
kernel interpreter (`dao.ml.kernel`) resolves handles to VRAM pointers, calls
the kernel, and returns result datoms. It does not own scheduling or session
state.

### Diffusion

```text
request
-> encode-text continuation (CLIP positive)
-> encode-text continuation (CLIP negative, parallel)
-> denoise loop continuation (N steps of UNET forward pass)
-> decode continuation (VAE)
-> emit render intent
```

Kernel source: diffusers, xFormers (UNET attention and conv, VAE encode/decode,
CLIP text encoder). Same dispatch and batching mechanics as LLM inference; the
kernel ops and workflow graph shape differ.

### Training

Training is a valid workload. The training workflow graph is deeper than the
inference workflow graph but uses the same runtime mechanics.

**Training workflow:**

```text
request
-> forward pass continuation (compute output, record computation graph as datoms)
-> loss continuation (compute scalar loss)
-> backward pass continuation (walk recorded ops in reverse)
-> gradient continuations (one per parameter, run in parallel)
-> optimizer continuation (apply gradient updates, emit updated weight handles)
-> emit updated weight handles to dao.db
```

The forward pass records each op and its inputs and outputs as datoms:

```clojure
[op-id :op/kind     :op/matmul      t m]
[op-id :op/input-a  tensor-a-id     t m]
[op-id :op/input-b  tensor-b-id     t m]
[op-id :op/output   tensor-out-id   t m]
[op-id :op/vjp-fn   :vjp/matmul     t m]
```

The backward pass continuation reads those datoms and emits gradient kernel
effects in reverse order. Gradient continuations across a batch can run in
parallel and join before the optimizer step.

#### How datomworld fills the training gaps

Training at scale requires state and infrastructure beyond what `dao.flow`
provides directly. The rest of datomworld covers each gap naturally.

**Distributed gradient aggregation** — gradient results from different nodes
are stream facts. A join continuation aggregates them via `dao.stream`.
Continuation migration already handles cross-node coordination.

**Optimizer state** — Adam momentum buffers, second moment estimates, and step
counts are facts in `dao.db`. Durable, queryable, and explicit:

```clojure
[optimizer-id :adam/step      1024       t m]
[optimizer-id :adam/beta1     0.9        t m]
[optimizer-id :adam/momentum  tensor-id  t m]
[optimizer-id :adam/variance  tensor-id  t m]
```

**Checkpoint and resume** — `dao.stream` is append-only event history. A
training run is already a stream of facts. Resuming from a checkpoint is
replaying the stream to a known cursor and continuing. No special checkpoint
infrastructure is needed.

**Mixed precision and gradient scaling** — dtype, layout, and scaling factors
are explicit values on kernel effects. The batch compatibility key already
includes dtype. Loss scaling state lives in `dao.db` alongside optimizer state.

In every case the pattern is the same:

- state that needs to persist: `dao.db`
- events that need to be replayed: `dao.stream`
- shared working context: `dao.space`
- scheduling and causality: `dao.flow`
- numeric execution: effect registry

`dao.flow` does not need to reinvent checkpointing, optimizer state, or
gradient aggregation because the rest of datomworld already solves those
problems in their proper layers.

### Tool And Render Integration

Tool use should not be modeled as direct execution by a continuation.

```text
continuation output
-> tool intent
-> tool interpreter
-> tool result stream
-> continuation resume if needed
```

Likewise for rendering:

```text
continuation output
-> render intent
-> render interpreter
-> dao.postgraphics frame stream
```

And likewise for memory:

```text
continuation output
-> memory intent
-> memory interpreter
-> dao.db / dao.space / dao.stream
```

This preserves the separation between interpretation and execution regardless
of workload.

### Retrieval and Context

External data enters a workflow through explicit retrieval plans and injected
streams. The retrieval pattern is optional — not all workflows require it.

The normal flow when retrieval is needed:

```text
continuation needs context
-> request, policy, template, or planner supplies a retrieval plan
-> retrieval interpreter reads an external source
-> retrieval results are written to a result stream
-> result stream is injected into the continuation
-> context assembler records what entered the context window
```

Supported retrieval sources:

- `dao.db` Datalog queries
- vector indexes or vector databases
- web search
- explicit `dao.stream` histories
- `dao.space` working context
- tool output streams
- policy and capability streams

If an LLM is used to create a retrieval query, that work should be a
query-planner child continuation:

```text
main continuation needs context
-> scheduler creates query-planner child continuation
-> planner proposes Datalog, vector, stream, or web retrieval intent
-> deterministic validator checks the proposal
-> approved retrieval interpreter executes it
-> result stream is injected into the main continuation
```

The LLM proposes. A validator approves. A retrieval interpreter executes.

## ML Extension

This section specifies the behavior of dao.flow when the effect registry
includes ML kernels. Nothing here applies to a dao.flow deployment without
ML kernels.

### GPU Batching

When ML kernels are present, effect batching maps directly to GPU kernel
batching. This is where the hardware utilization benefit of the continuation
model is realized.

A single continuation often yields one small tensor effect. Dispatching each
effect alone underutilizes the GPU. The scheduler collects compatible yielded
kernel effects across non-parked continuations and issues them as one batched
kernel invocation.

The speedup comes from:

- batching compatible GPU effects from within one request and across requests
- overlapping CPU-side stream, retrieval, and context work with GPU kernel execution
- keeping parked work from blocking runnable work
- cancelling or deprioritizing branches that no longer matter
- keeping GPU batches dense while preserving per-continuation causality

If continuations are stepped too finely or GPU effects are launched one by one,
this model can be slower than vLLM. The runtime must preserve coarse enough
batch boundaries for the kernels to stay efficient.

ML kernel compat key fields include: model identity, kernel operation, dtype,
layout, device placement, cache shape, quantization mode, and sampling stage.

### GPU Resource Announcements

When ML kernels are present, a node announces its GPU resources:

```clojure
[node-id :node/gpu-device      :cuda:0            t m]
[node-id :node/gpu-free-vram   16384              t m]
[node-id :node/models-loaded   #{:llama-3-8b}     t m]
```

Open GPU batch slots:

```clojure
[batch-id :batch/node            node-id                              t m]
[batch-id :batch/compat-key      {:op :decode
                                  :dtype :bfloat16
                                  :model :llama-3-8b}                 t m]
[batch-id :batch/slots-remaining 4                                    t m]
```

Gradient continuations from different training jobs with compatible effects can
share a batch across nodes, improving GPU utilization across the entire cluster.

### GPU Cache Locality

A continuation that yields a GPU effect is routed to the node that owns the
relevant model weights, VRAM cache handles, and device. The kernel invocation
runs locally on that node. The result returns as stream facts and resumes the
continuation.

Migration must respect KV-cache locality. A continuation that depends on a hot
VRAM cache handle cannot freely resume on another node unless one of these is
true:

- the destination already has the cache materialized
- the cache is explicitly transferred or reconstructed
- the continuation restarts from a replayable prefix
- the migrated work does not need the hot cache, such as retrieval, planning,
  tool use, verification, or joining

Once a continuation has a deep hot KV-cache on a specific GPU, it is cheaper
to keep it there and pull compatible effects toward it rather than migrate it
away. Batch-filling migration and cache-locality migration are complementary
strategies the scheduler applies based on the continuation's cache depth.

### Kernels Are Subordinate Interpreters

Kernels in the ML effect registry perform tensor math:

- matrix multiplication
- attention
- softmax
- normalization
- activation functions
- KV/cache reads and writes
- quantized operations where supported

They do not decide which request runs next, what a session means, when memory
is committed, or how tool/render/memory intents are interpreted. Kernel inputs
and outputs are values in explicit effect streams.

### Domain State On ML Effects

When the inference or diffusion profile is active, the yielded effect carries
domain-specific fields that the core continuation record does not include:

- model identity
- sampling state
- KV/cache handles

These are not an extension of the core continuation record. They are explicit
values on the pending effect or the domain state the effect references. The
continuation references them by handle; it does not own or mutate them.

### KV-Cache Bytes Live In VRAM

For GPU inference, the hot KV-cache is VRAM state. `dao.flow` runs in
`yin.vm` on the CPU and should not carry or mutate raw K/V tensor bytes.

```text
dao.flow / yin.vm / RAM:
  cache identity, ownership, position, lineage, lifecycle, handles

kernel layer / VRAM:
  actual K/V tensor blocks, device pointers, low-level cache reads and writes
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

The kernel interpreter resolves `cache-id` to VRAM buffers, runs the kernel,
and returns explicit result facts:

```clojure
[result-id :kernel/result-for effect-id t m]
[result-id :cache/advanced-to 2049 t m]
[result-id :kernel/logits logits-handle t m]
```

`dao.flow` does not own KV-cache bytes. It owns KV-cache meaning: identity,
lifecycle, lineage, position, permission, and causal relationship to
continuations.

### Kernel Boundary

`dao.flow` may copy vLLM kernel source and adapt it to this runtime, but must
not copy vLLM's opaque runtime boundary.

Kernel execution is local. `dao.flow` is distributed; kernels are not. A
kernel effect is routed to the node that owns the relevant GPU, model weights,
and cache handles. The kernel invocation runs locally on that node. Cross-node
distribution belongs to continuations, streams, and joins — not to one tensor
kernel.

Accepted kernel sources and ops:

- attention kernels (vLLM, flash-attn, xFormers)
- paged/KV-cache kernels (vLLM)
- matmul or quantized matmul kernels (vLLM, cuBLAS, CUTLASS)
- normalization and activation kernels (vLLM, PyTorch)
- UNET attention and conv kernels (diffusers, xFormers)
- VAE encode and decode kernels (diffusers)
- CLIP text encoder kernels (diffusers, transformers)
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

## Suggested Namespace Sketch

Core namespaces — apply to any workload:

```text
dao.flow             — core runtime entry point
dao.flow.scheduler   — continuation scheduler, ready queues, wait sets
dao.flow.registry    — effect interpreter registry, dispatch, batching
dao.flow.effects     — effect value vocabulary and compat key schema
dao.flow.lineage     — continuation lineage, provenance, branch tracking
dao.flow.migration   — continuation migration across nodes
dao.flow.space       — dao.space integration for stigmergic coordination
```

General extension package — optional, not ML-specific:

```text
dao.flow.retrieve    — retrieval from dao.db, vector indexes, dao.stream
dao.flow.commit      — output commit to streams and memory substrates
dao.flow.render      — render intent routing to dao.postgraphics
```

ML kernel package — shared across inference, diffusion, and training profiles:

```text
dao.ml.kernel        — ML kernel effect interpreter (resolve, call, return)
```

Inference package — application-layer code for LLM workloads:

```text
dao.nao.context      — context assembly for LLM workloads
dao.nao.parse        — structured output parsing, intent extraction
```

`dao.nao.*` provides the "output parsing" role described in the layer diagram
summary. It is application package code that sits above the effect registry and
depends on core. Core and extension namespaces must not depend on it.

The general extension package and the ML kernel package depend on core. The
inference package depends on core, the extension package, and the ML kernel
package. The exact shape should emerge from implementation pressure.

## Suggested Build Order

### Core

1. Define the core event vocabulary (`:workflow/*`, `:continuation/*`).
2. Implement the Yin scheduler: request admission, ready queue, wait set,
   cancellation, continuation status.
3. Prove one request can statically decompose into a continuation graph and
   that multiple continuations can run with deterministic fake effect results.
4. Add explicit effect values and batch compatibility keys.
5. Batch compatible yielded effects; resume each continuation from its result
   slice.
6. Add continuation forking, joining, and branch comparison.
7. Integrate the first real effect interpreter behind the effect registry.

### General Extension Package

1. Add retrieval interpreter (`dao.flow.retrieve`).
2. Add commit interpreter (`dao.flow.commit`).
3. Add render intent routing (`dao.flow.render`).
4. Wire intent dispatch for `:intent/tool`, `:intent/render`, `:intent/memory`.

### ML Kernel Package (dao.ml.kernel)

1. Define `:ml/kernel-effect` and `:ml/kernel-result` event kinds; specify
   model identity, weight handles, and KV/cache handles as explicit fields on
   kernel effects.
2. Implement `dao.ml.kernel` — resolve handles, call the interpreter, return
   result slices.
3. Integrate the first real kernel: vLLM attention and paged KV-cache.

### Inference Package (dao.nao.*)

1. Add `dao.nao.context` — context assembly for LLM workloads.
2. Add `dao.nao.parse` — structured output parsing and intent extraction.

## First Milestone

The first milestone is small but complete:

- a user appends a workflow request
- the Yin scheduler creates a root continuation or an initial continuation graph
  for that request
- several continuations can be runnable at once
- continuations yield effects
- the scheduler batches compatible yielded effects
- fake effect results return per-continuation result slices
- each continuation resumes independently
- join continuations aggregate child results when the graph requires it
- results, completion, batch, and telemetry datoms are appended to `dao.stream`

If this loop feels clean, real effect interpreter integration can replace fake
results without changing the public stream semantics.

## Design Direction

The main idea is simple:

- `dao.flow` is a general workflow orchestrator, not an inference engine or training framework
- ML kernel effect interpreters make it an ML workflow runtime substrate — they are pluggable, not structural
- inference engines, training engines, business processes, and pipelines are all applications built on top
- do not treat any workload as a fixed request/response RPC
- do not treat memory as hidden session state
- do not let effect interpreters own causality or scheduling
- do not fix the workload type in the runtime
- do batch continuations to keep work units dense
- do use continuation graphs to expose more schedulable work
- do treat the workflow graph as data that configures the workload
- do treat effect interpreters as subordinate, nothing more

Each layer does one thing. No layer reaches into the layer above it. The layer
diagram is in the Summary.

`dao.flow` without ML kernel effect interpreters is a complete workflow
orchestrator. With them, it is an ML workflow runtime substrate. The difference
is configuration, not architecture. The rest of datomworld — `dao.db`,
`dao.stream`, `dao.space` — fills the gaps that the runtime deliberately does
not own.
