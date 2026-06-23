# Design: `dao.flow`

## Summary

`dao.flow` is the datom coordination protocol that runs on `dao.space`. It
is not a separate software component — it is the set of conventions that let
datoms be matched and processed like network packets. `dao.space` (`dao.stream` +
`dao.db`) is the medium; `dao.flow` is the protocol.

`dao.space` on its own is a general-purpose tuple space that already enables
stigmergic coordination: agents write datoms to shared space, other agents
react to those writes without direct coupling. `dao.flow` adds the specific
conventions: a datom vocabulary, agents matching type datoms by attribute/value
pattern, and agents redirecting flow by writing type datoms with different
type values. By convention in datomworld, the type datom has attribute `:a`; its
`v` value is the type value. By themselves, these conventions give `dao.space`
packet-like datom coordination. The scheduler agent adds workflow semantics —
continuation scheduling, batching, stream injection, fork / join, and lineage.
ML kernel agents make it an ML workflow substrate. The workload type is
determined by which agents run, not by `dao.flow` itself.

`dao.space` is realized as DaoStream descriptor types over `dao.stream` (the
append-only event log that carries its tuples); a Datalog query is one
interpreter (`:query`) over that catalog, backed by `dao.db`. `dao.flow` runs on
`dao.space`; it does not call `dao.stream` or `dao.db` directly — it depends on
them only through `dao.space`.

Agents write datoms into `dao.space`. Other agents pattern-match on those datoms
and pick them up. When an agent processes matched datoms, it writes new datoms to
`dao.space` — including a type datom whose `v` value is a different type
value. No central dispatcher directs the flow — the coordination pattern emerges
from what each agent writes and what other agents match. This is stigmergic
coordination: agents act on shared state without direct coupling to each other.
No agent needs to know what other agents exist.

```text
application  (inference engine, training engine, agent runtime, pipeline)
    provides: workflow graph, agent patterns, output handling

dao.space  (coordination medium: a catalog of tuples over dao.stream,
             read by interpreters — Datalog queries are one interpreter)
    provides: datom coordination medium — agents write/read datoms,
              pattern matching determines flow, history is preserved

agents  (datom producers and consumers — all coordinate via dao.space)
    scheduler agent    — Yin program: continuation scheduling, batching,
                         stream injection, fork / join, lineage
    yin.vm agent       — executes Yin programs
    ML kernel agent    — tensor operations, paged KV-cache
    retrieval agent    — dao.db queries, vector search, web
    commit agent       — output to dao.db / dao.stream
    render agent       — matches intent datoms, emits to dao.postgraphics
    other agents       — HTTP, tools, ...

GPU  (when ML kernel agent is present)
    provides: parallel compute
```

Any agent can participate: a Yin scheduler, a GPU kernel, a retrieval service,
a tool executor. The workload type is determined entirely by which agents are
running and what type values they write and match. That is configuration,
not architecture.

The strategic boundary is:

- agents match datoms by attribute/value pattern, Datalog query,
  or predicate.
- agents do not inspect payload datoms outside the groups selected by their
  matching rule during dispatch; they may freely query `dao.space` for facts
  within their declared scope.
- agents do not own durable hidden queues or global causality — scheduler
  state is explicit datoms in `dao.space`.
- durable state lives in `dao.space` itself: `dao.stream` for append-only
  history, `dao.db` for queryable indexed facts.
- the scheduler agent owns continuation scheduling; no other agent does.

## Core Claim

Computation should not be modeled as a request/response RPC whose inputs are
fixed when the request starts, nor as a static graph whose topology is fixed at
design time.

Computation should be modeled as a dynamic flow of datoms coordinated via a
shared tuple space:

```text
agent A writes datoms with type value :some/effect to dao.space
-> agent B matches [?e :a :some/effect], picks it up
-> agent B processes, writes datoms with type value :next/effect to dao.space
-> agent C matches [?e :a :next/effect], picks it up
-> ...
```

An inference engine, a training engine, a business process, and a data pipeline
are all instances of this model. The type values each uses and the agents
that serve those types determine what the system does. `dao.space` does not know
or care what kind of workload it is hosting.

The key flexibility is that flow is fully determined by what agents write and
what agents match. An agent redirects the flow by writing datoms that include a type datom with a
different type value. No central dispatcher is involved. The coordination
pattern emerges from agent behavior.

## Coordination Intuition

`dao.flow` coordination emerges from agents writing and reading datoms through a
shared tuple space. It is packet-like in behavior — agents match typed facts and
process them — but the unit is still a datom in `dao.space`, not a separate
message abstraction.

A datom is an n-tuple — a single fact, a sequence of values at named positions.
There is no fixed arity or mandatory position schema.

Related datoms sharing the same entity identity form a group. A group is
authored by one agent into one stream, so its datoms share that agent's
stream-local entity id; cohesion comes from that shared id (not from atomic
authoring — a group's datoms may land as several non-atomic put!s), and survives
folding (see `dao.space.md`, "Entity Identity Is Stream-Local"). One of those
datoms can be the type datom, which interpreters match on to identify work they
can process.
The remaining datoms are payload facts carrying the data the handler needs.

By convention in datomworld, the type datom has attribute `:a`; its `v` value
is the type value — what interpreters match on to select work. Outside
the convention, no datom is designated as the type datom; the interpreter's
matching pattern determines which datom plays which role.

```clojure
[effect-id :a            :ml/kernel-effect  t m]   ; type datom: attribute :a, type value :ml/kernel-effect
[effect-id :kernel/op    :kernel/decode     t m]   ; payload datom
[effect-id :kernel/cache cache-id           t m]   ; payload datom
```

`effect-id` is the entity identity. The ML kernel agent matches
`[?e :a :ml/kernel-effect]` — attribute `:a`, value `:ml/kernel-effect` — which
yields `effect-id`; it then reads the payload datoms by querying all datoms
where `e = effect-id`.

Matching is interpreter-dependent: which attribute or metadata position an
interpreter matches on is up to the interpreter. One interpreter may match on
attribute `:a`; another on metadata carried in `m`; another on a combination.
The same group of datoms can satisfy multiple interpreters simultaneously.

Under the `dao.flow` convention, a group of datoms is active if and only if it
includes a type datom `[e :a type t m]`. A group without one — such as
`[optimizer-id :adam/step 1024 t m]` — is queryable state. Outside the
convention, any interpreter may match on any attribute or position; the shared
vocabulary of `dao.flow` uses attribute `:a` as the type marker so that
agents remain decoupled from each other's internal state.

Placement selection — which node or device processes the matched datoms — is
separate from semantic matching. By convention it is carried in the `m` position,
consumed by a placement interpreter after the `:a` attribute match. The `m`
position also carries: provenance, lineage, and capability tokens.

An agent processes matched datoms by reading their payload datoms, then writes
new datoms to `dao.space` with type datom `[e :a new-type t m]`, so that a
different agent matches the result.

Agents match datoms in three ways:

- **By attribute and value** — the common case: `[?e :a <type>]` finds all
  groups whose type datom has the matching type (attribute `:a`, value
  `<type>`). Any attribute works; `:a` is the datomworld convention for
  semantic matching; `m` carries placement affinity.
- **By Datalog query** — for patterns that require multiple positions or joins
  (e.g., pending requests where a cache block is available).
- **By predicate or function** — a continuation or function tests a datom or
  group directly against an in-process condition.

Attribute/value matching is the primary mechanism. Datalog is for coordination
logic that spans multiple positions or entity identities across the shared space.

This is different from a central router:

- A central router maintains a dispatch table and actively delivers datoms.
- `dao.flow` has no central router. Agents read from `dao.space` directly.
  The coordination is the tuple-space pattern match.

This is also different from direct function calls:

- Direct function calls couple caller to callee by name.
- `dao.flow` agents are coupled only by type value. An agent writing
  `:ml/kernel-effect` does not know or care whether a GPU kernel agent or
  a simulation agent picks it up.

The underlying coordination model is stigmergy. In stigmergic systems, agents
do not send messages to each other — they modify a shared environment, and other
agents react to those modifications. `dao.space` is the environment. Datoms
are the modifications. The workflow emerges from agent reactions, not from a
plan imposed by a coordinator.

### Why Not Actual UDP?

The matching principle resembles UDP: agents write datoms into
the shared space; other agents match on type. But actual UDP is the wrong
mechanism:

- UDP sends bytes between network addresses. `dao.flow` agents match datoms by
  attribute/value pattern, not by network address.
- UDP is stateless and history-free. `dao.space` preserves the full datom
  history as an append-only log — queryable, replayable, and auditable.
- UDP requires network transport. `dao.flow` coordination works in-process
  across JVM, JS, Dart, and WASM without serialization or round-trip cost.

### dao.space and dao.stream

`dao.space` is built on `dao.stream`. Their roles differ:

| Dimension    | dao.space / dao.flow               | dao.stream                                |
|--------------|------------------------------------|-------------------------------------------|
| Role         | Coordinate agents via shared space | Deliver events reliably and in order      |
| Abstraction  | Coordination medium: catalog of tuples (DaoStream descriptor types) | Append-only event log      |
| State        | Stateful: full queryable history   | Stateful: cursors, positions, causality   |
| Addressing   | Type value → agent                 | Stream id + cursor → subscriber           |
| Query power  | Full Datalog (a `:query` interpreter over the catalog) | Cursor-based read             |

## Scope

The `dao.flow` protocol covers:

- agents writing datoms into `dao.space`
- agents matching on `dao.space` for type datoms of their type value
- flow redirection: agents write datoms with a type datom `[e :a new-type t m]`
  so that a different agent matches the result
- full history: datom writes are preserved by `dao.space` (via
  `dao.stream`) for replay, debugging, and audit — at no extra cost to agents

The `dao.flow` protocol does not cover:

- continuation scheduling — the scheduler agent's responsibility
- batching compatible effects — the scheduler agent or ML kernel agent
- stream injection — `dao.stream` and the scheduler agent
- fork / join — the scheduler agent
- GPU kernel execution — the ML kernel agent
- being an inference engine — inference engines are built using this protocol

## Invariants

### 1. Payload-opaque coordination

Agents match datoms by attribute/value pattern, by Datalog query, or
by predicate. During dispatch, they do not inspect payload datoms outside the
groups selected by their matching rule. Agents may freely query `dao.space` via
Datalog for facts within their declared scope — cache allocations, macro
definitions, optimizer state. That is stigmergic reading of the shared
environment, not dispatch payload inspection.

```text
scheduler agent matches   :inference/request  ->  allocates cache, forms batch
ML kernel agent matches   :ml/kernel-effect   ->  runs GPU kernel
result agent matches      :inference/token    ->  streams to caller
```

By convention, the type datom has attribute `:a`; its `v` value is the type
value — what agents match to select which agent class handles the work. The
`m` position is the metadata envelope: provenance, lineage, capability tokens,
and placement affinity. Placement selection — which node or device runs the
handler — uses `m`, but only after the `:a` attribute match has already
occurred. These are two distinct matching concerns: attribute `:a` selects the
interpreter; `m` guides where it executes.

### 2. State is external

Agents do not own durable state. All state lives in `dao.space`:

- `dao.stream` for append-only event history
- `dao.db` for stabilized queryable facts
- working context, resource announcements, and coordination metadata as datoms

Agents may buffer in-memory (for example, the ML kernel agent accumulates
effects before issuing a batch). They do not persist state outside `dao.space`.
`dao.space` is the single source of truth.

### 3. Intent is not execution

An agent may emit intent datoms:

- message intents
- memory intents
- tool intents
- render intents

Other agents decide how those intents are executed or committed. An agent
emits datoms. It does not directly mutate the world.

### 4. Entity ids are stream-local; cross-group references are stamped

Entity ids are local to the authoring stream (see `dao.space.md`, "Entity
Identity Is Stream-Local"). Two consequences for `dao.flow`:

- **Flow advances by appending typed groups, not by mutating across streams.** An
  agent does not update another stream's entity in place; it writes a *new* group
  with a new type value that references the prior one (e.g.
  `[result-id :kernel/result-for effect-id t m]`). The flow chain is immutable
  groups linked by references; "state" is reconstructed by reading the chain.
- **A cross-group reference value is a stamped id.** An agent references another
  group by the stamped `[stream-ns offset]` it observed when reading the folded
  space, never by a bare offset. Bare offsets are valid only inside the group's
  own authoring stream. Entities that genuinely evolve (e.g. KV-cache lifecycle)
  have a single owning agent, so their mutations stay within one stream.

## First-Class Values

The `:workflow/*` types are the scheduler agent's protocol vocabulary for
workflow lifecycle events. Agents do not need to use them directly — they are
the scheduler's internal language for tracking what stage a unit of work is in.
Domain-specific types (`:ml/kernel-effect`, `:retrieval/result`,
`:intent/render`) are the content of those stages. Using `:workflow/effect` as
a wrapper around a domain effect is optional; agents may use domain types
directly when scheduler lifecycle tracking is not needed.

Scheduler lifecycle types:

- `:workflow/request`
- `:workflow/accepted`
- `:workflow/effect`
- `:workflow/effect-result`
- `:workflow/completed`
- `:workflow/cancelled`
- `:workflow/error`
- `:workflow/intent`

Intent datoms use direct types as the `v` value of the type datom:

- `:intent/tool`
- `:intent/render`
- `:intent/memory`
- `:intent/message`

When the scheduler needs to intercept all intent types before dispatching, the
scheduler-owned generic wrapper is also valid: `[e :a :workflow/intent t m]`
plus an `:intent/kind` payload datom carrying the specific kind. Direct types are
simpler; the generic form is useful when one scheduler path handles all intents
uniformly.

ML-specific types (apply when any ML application profile is active):

- `:ml/kernel-effect`
- `:ml/kernel-result`

Inference-specific types (apply when the inference profile is active):

- `:inference/token`
- `:inference/message`

Direct intent datoms:

```clojure
[intent-id :a          :intent/tool  t m]
[intent-id :intent/body tool-params  t m]
```

Generic form (when the scheduler handles all intents before dispatching):

```clojure
[intent-id :a           :workflow/intent  t m]
[intent-id :intent/kind :intent/tool      t m]
[intent-id :intent/body tool-params       t m]
```

Scheduler-specific vocabulary — continuation identity, batch keys, inject
commands, wait sets — belongs to the scheduler agent's vocabulary. Other agents
have no dependency on it.

## Application Profiles

The following sections describe how the `dao.flow` coordination protocol is
applied to specific workloads. Each profile defines the type values and the
agents that coordinate via `dao.space`. The coordination substrate is unchanged
across profiles.

### LLM Inference

The inference profile implements continuous batching and paged KV-cache
coordination via `dao.space`:

```text
HTTP / queue
  -> datoms with type value :inference/request written to dao.space

scheduler agent (matches [?e :a :inference/request])
  -> Datalog: find pending requests + available KV-cache block datoms
  -> claims cache blocks (writes allocation datoms to dao.space)
  -> writes datoms with type value :ml/kernel-effect (:kernel/op :kernel/prefill)

ML kernel agent (matches [?e :a :ml/kernel-effect])
  -> resolves VRAM handles from cache datoms in dao.space
  -> runs prefill + decode loop internally
  -> writes datoms with type value :ml/kernel-result back to dao.space

inference adapter agent (matches [?e :a :ml/kernel-result])
  -> converts logits / sampled token results into datoms with type value
     :inference/token

result-collector agent (matches [?e :a :inference/token])
  -> streams tokens to caller
```

**Paged KV-cache:** block metadata (handle, device, position, status) lives in
`dao.space` as datoms; actual tensor bytes live in VRAM. The scheduler queries
for available blocks with Datalog, claims them, writes the allocation back.

**Continuous batching:** the scheduler agent queries `dao.space` for all
pending datoms with type value `:inference/request` on each step and adds new
prefills to the running batch. For high-throughput scenarios the scheduler agent is
implemented natively (Clojure/Dart/Rust); `dao.space` remains the coordination
medium regardless.

**What this adds over vLLM:** scheduler decisions, cache allocations, and
request state are queryable datoms — observable, replayable, and debuggable.
Preemption and cache swaps are explicit datom writes visible in the history.
Multi-node coordination uses `dao.space` resource announcements and batch-slot
datoms via Datalog.

Kernel source: vLLM (attention, paged KV-cache, matmul, normalization). The
ML kernel agent resolves handles to VRAM pointers, calls the kernel, and
returns result datoms. It does not own scheduling or session state.

### Diffusion

```text
datoms with type value :diffusion/request
-> datoms with type value :encode/clip-positive (CLIP positive encoder agent)
-> datoms with type value :encode/clip-negative
   (CLIP negative encoder agent, parallel)
-> datoms with type value :denoise/unet-step (N steps, UNET agent)
-> datoms with type value :decode/vae (VAE agent)
-> datoms with type value :intent/render (render agent)
```

Kernel source: diffusers, xFormers. Same coordination mechanics as LLM
inference; the kernel types and workflow graph differ.

### Training

Training is a valid workload. The training workflow uses the same coordination
mechanics with a deeper datom graph.

**Training workflow:**

```text
:training/request
-> :op/forward-pass    (forward pass agent, records computation graph as datoms)
-> :op/loss            (loss agent)
-> :op/backward-pass   (backward pass agent, reads op datoms in reverse)
-> :op/gradient        (gradient agents, one per parameter, run in parallel)
-> :op/optimizer       (optimizer agent, emits updated weight handles)
-> :db/write           (commit agent writes weight handles to dao.db)
```

The forward pass agent records each op as datoms in `dao.space`:

```clojure
[op-id :op/kind     :op/matmul      t m]
[op-id :op/input-a  tensor-a-id     t m]
[op-id :op/input-b  tensor-b-id     t m]
[op-id :op/output   tensor-out-id   t m]
[op-id :op/vjp-fn   :vjp/matmul     t m]
```

The backward pass agent reads those datoms via Datalog and emits gradient
kernel datoms in reverse order.

#### How datomworld fills the training gaps

**Distributed gradient aggregation** — gradient result datoms from different
nodes are written to `dao.space`. A join agent Datalog-queries all gradient
results and aggregates them.

**Optimizer state** — Adam buffers, momentum estimates, and step counts are
datoms in `dao.db` (queryable via `dao.space`):

```clojure
[optimizer-id :adam/step      1024       t m]
[optimizer-id :adam/beta1     0.9        t m]
[optimizer-id :adam/momentum  tensor-id  t m]
[optimizer-id :adam/variance  tensor-id  t m]
```

**Checkpoint and resume** — `dao.stream` (the foundation of `dao.space`) is
the append-only history. Resuming from a checkpoint is replaying to a known
cursor. No special checkpoint infrastructure needed.

**Mixed precision** — dtype, layout, and scaling factors are explicit values
on kernel datoms. Loss scaling state lives in `dao.db`.

In every case the pattern is the same:

- state that needs to persist: `dao.db`
- events that need to be replayed: `dao.stream`
- shared working context and coordination: `dao.space`
- numeric execution: ML kernel agent

### Tool And Render Integration

```text
agent output -> datoms with type value :intent/tool
             -> tool agent -> :tool/result datoms
agent output -> datoms with type value :intent/render
             -> render agent -> dao.postgraphics
agent output -> datoms with type value :intent/memory
             -> memory agent -> dao.db / dao.space
```

### Retrieval and Context

```text
agent needs context
-> datoms with type value :retrieval/plan (from planner or admission policy)
-> retrieval agent reads external source
-> datoms with type value :retrieval/result written to dao.space
-> scheduler agent injects result into waiting continuation
```

Supported retrieval sources: `dao.db` Datalog queries, vector indexes, web
search, `dao.stream` histories, `dao.space` working context, tool outputs.

Query planning:

```text
main agent needs context
-> datoms with type value :query/plan (planner agent matches it)
-> planner proposes Datalog, vector, stream, or web retrieval
-> validator agent checks proposal
-> retrieval agent executes approved plan
-> result injected into main agent
```

### Compilation

Each compiler pass is an agent that matches specific type values. `dao.space`
coordinates the passes — no agent understands another agent's internal data.

```text
datoms with type value :compile/request
-> parse agent           (source string -> :ast/datoms)
-> macro-expand agent    (matches datoms with type value
                          :macro/definition)
-> cps agent             (:ast/datoms -> :cps/datoms)
-> closure-convert agent (:cps/datoms -> :closure/datoms)
-> codegen agent         (:closure/datoms -> :bytecode/artifact)
-> optimization agents   (N parallel passes per function)
-> commit agent          (writes artifact to dao.db)
```

**Macro expansion:** the macro-expand agent opens a live `:query` stream for
`[:find ?d :where [?d :a :macro/definition] [?d :macro/name macro-name]]` in
`dao.space`. When a sibling compilation agent writes the macro definition datoms,
the macro-expand agent resumes.

**Multi-module builds:** N independent `:compile/request` groups of datoms run in
parallel. A join agent queries for all `:bytecode/artifact` datoms from the
module set and emits the linked artifact.

**Incremental compilation:** a changed source file writes datoms with type value
`:ast/datoms` to `dao.space`. The recompilation agent picks them up. Unchanged
module artifacts in `dao.db` remain valid.

## ML Extension

This section applies only when ML kernel agents are registered.

### GPU Batching

The ML kernel agent decides when to batch GPU effects. It buffers incoming
datoms with type value `:ml/kernel-effect` from `dao.space` and issues batched
kernel invocations.

A single group of datoms with type value `:ml/kernel-effect` often represents
one small tensor operation. The ML kernel agent groups compatible effects and
issues one batched invocation.

ML kernel batch compatibility fields: model identity, kernel operation, dtype,
layout, device placement, cache shape, quantization mode, sampling stage.

### GPU Cache Locality

After the `:a` attribute match selects the ML kernel agent class, the `m`
position carries placement affinity — the node address of the GPU that owns the
relevant model weights and VRAM cache handles. A placement interpreter reads
`m` and delivers the matched datoms to that node. The kernel invocation runs
locally.

Once a continuation has a deep hot KV-cache on a specific GPU, the scheduler
agent addresses subsequent kernel datoms to that node rather than migrating
them.

### Kernels Are Subordinate Agents

ML kernel agents perform tensor math:

- matrix multiplication, attention, softmax, normalization, activation
- KV/cache reads and writes
- quantized operations

They do not decide which request runs next, what a session means, when memory
is committed, or how intent datoms are handled. Kernel inputs and outputs are
datoms in `dao.space`.

### Domain State On ML Effects

When the inference or diffusion profile is active, the yielded datoms carry
domain-specific fields:

- model identity
- sampling state
- KV/cache handles

These are explicit values on the datoms. The agent references them by handle;
it does not own or mutate the underlying tensors.

### KV-Cache Bytes Live In VRAM

For GPU inference, the hot KV-cache is VRAM state. Coordination state in
`dao.space` runs in RAM; it carries only handles, not tensor bytes.

```text
dao.space / RAM:
  cache identity, ownership, position, lineage, lifecycle, handles

ML kernel agent / VRAM:
  actual K/V tensor blocks, device pointers, reads and writes
```

Cache handle datoms in `dao.space`:

```clojure
[cache-id :cache/handle   cache-id       t m]
[cache-id :cache/device   :cuda:0        t m]
[cache-id :cache/position 2048           t m]
[cache-id :cache/status   :cache/active  t m]
```

Kernel effect datoms (attribute `:a`, type value `:ml/kernel-effect` in
`v`):

```clojure
[effect-id :a            :ml/kernel-effect  t m]   ; type datom
[effect-id :kernel/op    :kernel/decode     t m]   ; payload
[effect-id :kernel/cache cache-id           t m]   ; payload
[effect-id :kernel/input input-buffer-id    t m]   ; payload
```

The ML kernel agent resolves `cache-id` to VRAM buffers, runs the kernel, and
writes result datoms back to `dao.space`:

```clojure
[result-id :a               :ml/kernel-result  t m]   ; type datom
[result-id :kernel/result-for effect-id        t m]   ; payload
[result-id :cache/advanced-to 2049             t m]   ; payload
[result-id :kernel/logits     logits-id        t m]   ; payload
```

### Kernel Boundary

Accepted kernel sources and ops:

- attention kernels (vLLM, flash-attn, xFormers)
- paged/KV-cache kernels (vLLM)
- matmul or quantized matmul kernels (vLLM, cuBLAS, CUTLASS)
- normalization and activation kernels (vLLM, PyTorch)
- UNET attention and conv kernels (diffusers, xFormers)
- VAE encode and decode kernels (diffusers)
- CLIP text encoder kernels (diffusers, transformers)

Not accepted as the runtime boundary:

- vLLM request scheduler
- vLLM session model
- vLLM OpenAI-compatible server API
- vLLM request queues as source of truth
- opaque per-request runtime state

Copied kernel source must preserve upstream license notices, provenance, and
local modification history.

## Design Direction

The main idea is simple:

- `dao.flow` is the datom coordination protocol running on `dao.space` —
  not a separate software component
- `dao.space` (`dao.stream` + `dao.db`) is the medium; agents coordinate by
  writing and reading datoms
- flow is emergent: agents match datoms and write new datoms; no central
  dispatcher is involved
- the scheduler agent makes `dao.flow` behave as a workflow runtime — it is an
  agent in `dao.space`, not a separate infrastructure layer
- ML kernel agents make it an ML workflow runtime substrate — pluggable, not
  structural
- inference engines, training engines, and data pipelines are all applications
  expressed as type values coordinated via `dao.space`
- do not treat any workload as a fixed request/response RPC
- do not treat memory as hidden agent state
- do not let agents own causality or scheduling
- do not fix the workload type in the coordination substrate
- do treat the workflow graph as type values that configure the workload

`dao.flow` without the scheduler agent is raw datom coordination over
`dao.space`. With the scheduler agent and ML kernel agents, it is a complete
workflow runtime and ML substrate. The difference is which agents are running,
not the architecture of `dao.space` itself.
