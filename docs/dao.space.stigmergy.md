# DaoSpace for Agent Collaboration

Status: design discussion (2026-07-10); a coordinator-mediated prototype ran live the same
day, then was **superseded (2026-07-12) by the streams-native model**: there is no
coordinator. Stigmergy is writing datoms to the agent's own `dao.stream` via `ds/append!` and
reading dao.space with `q`/`match` — nothing else. The living contract is
`test/dao/space/stigmergy_test.clj`: agents coordinate over a network-accessible
`dao.jing.file` content handle (served with `dao.jing.remote/default-handlers` as
`:jing/put-content` and `:jing/get-content` RPC operations), and the finished space persists at
`target/stigmergy-space.db` for inspection with `dao.space.query`.
Describes how `dao.space` serves as a coordination medium for autonomous agents — LLM agents
specifically — and enumerates what exists today versus what is still needed. Nothing here
proposes changing the tuple-space model; the model is the point.

**Related documents:**
- `docs/design/dao.space.md` — the tuple space: associative matching, generative communication, stigmergy
- `docs/design/dao.space.query.md` — the query library: index realization, `read-datoms`, the Target Architecture rulings
- `docs/design/dao.jing.md` — the storage boundary agents ultimately share
- `docs/design/dao.space.security.md`, `docs/design/adr/0002-share-governed-computation-not-data.md` — the controlled-mode model for untrusted participants
- `docs/design/yin.vm.ffi.md` — the confined-evaluation bridge governed agents would run through
- `docs/agents/datom-spec.md` — tuples, canonical d5, provenance (`m`), content addressing

## Why a tuple space fits agents

The tuple space is the formal model of **stigmergy**: coordination through traces left in a
shared medium rather than through messages aimed at recipients. That is not an incidental
fit for LLM agents — it addresses their three structural weaknesses directly:

- **Agents are ephemeral.** An LLM agent exists for the duration of a context window; it
  crashes, restarts, gets superseded by a newer prompt. Generative communication decouples
  in time: the datoms an agent deposited outlive it, and a successor picks up exactly where
  the medium says things stand. No session state to hand off — the medium *is* the state.
- **Agents are strangers.** Two agents built by different people, running different models,
  can coordinate only if neither needs to know the other exists. Associative matching
  decouples in identity: a reader finds work by matching *content* (`[?w :work/posted true]`),
  never by addressing a producer. Adding a tenth agent to a nine-agent system changes
  nothing about the nine.
- **Agents fail mid-action.** Append-only single-writer logs make every action a durable,
  attributable fact with no partial-update window. A crashed agent's log simply stops; a
  reader tailing it blocks rather than erroring (crash-only semantics, `dao.space.md`,
  *Fault Tolerance*).

The alternative most multi-agent frameworks choose — a message bus or an orchestrator that
routes agent-to-agent messages — re-introduces exactly the coupling the tuple space removes:
a broker to keep alive, message formats to negotiate, recipients to name, delivery to
guarantee. Stigmergy needs none of it. Agents read the medium, act, and deposit.

## The agent loop over dao.space

An LLM agent is a loop: build a prompt from what the agent can currently see, let the model
choose an action, execute it, repeat. Mapped onto `dao.space`:

1. **Perceive** — run `q`/`match` over the shared medium; the results become part of the
   model's context ("here is the current state of the work board").
2. **Decide** — the model picks an action. This step is the LLM call itself.
3. **Act** — append datoms to the agent's *own* single-writer log: a claim, a result, an
   observation, a question for whoever matches it later. Never a message to a recipient.
4. Repeat. Other agents' loops perceive the new datoms whenever they next look.

The worked example in `dao.space.md` (*Coordination: Stigmergy*) is precisely this shape:
a producer deposits `:work/posted` facts, workers query for unclaimed work, claim by
appending `:work/claims`, and the claim race is resolved deterministically on the read side.
Two LLM workers claiming the same task is not an error to prevent — it is a fact pattern
every reader resolves identically (order by timestamp, tie-break by agent id).

## Architectural Pattern: The Memory Tree

A powerful pattern for LLM agents is the "Memory Tree" — a hierarchical structure where raw chunks of data are the leaves, and LLM-generated summaries form the branches and root. This solves the LLM context window problem: instead of loading a massive raw history, the agent reads the root summary and navigates down the tree to find specific details.

When a Memory Tree is implemented as datoms in `dao.space`, it upgrades from a local-first store into a multi-agent knowledge graph, unlocking structural superpowers:

1. **Native Graph Traversal**: A Memory Tree is a Directed Acyclic Graph (DAG) connected by `:memory/parent` attributes. Because `dao.space` supports interpreting datoms as graph data, agents can natively traverse these edges without writing complex recursive fetch loops in their runtimes.
2. **Time-Traveling Memory**: Since datoms are immutable and append-only, an agent can run an `as-of` query to see exactly what the Memory Tree looked like at a specific point in time (e.g., "What were the priorities last Tuesday?"), ignoring any subsequent summaries.
3. **Stigmergic Pipelining**: Ingestion and summarization decouple. An ingest agent streams raw leaf datoms. A summarizer agent queries for unparented leaves, writes summary datoms with `:memory/parent` edges, and dies. They coordinate entirely through the shape of the datoms appearing in the space.
4. **Perfect Provenance**: Every summary datom carries an `m` (metadata) entity. This provides an exact, auditable trace of which model generated the summary, at what millisecond, using which system prompt.

## What exists today

The substrate is real and tested:

- **Shared storage** — `dao.jing` with in-memory, file, WebSocket-remote
  (`dao.jing.remote`, `:clj` client), and DHT backends. Agents on different
  machines can share a store today.
- **Associative read** — `dao.space.query/q` (Datalog joins) and `match`
  (positional templates) over exact-bounded DaoStream descriptors or closed
  retained realizations. `current` and `history` add explicit d5 interpretation
  and optional `as-of` bounds.
- **Owner-built indexes** — `publish-index!` persists a stream's covered indexes as
  content-addressed segments. `published-index` turns a resolvable DaoJing
  coordinate plus manifest address into a transportable exact-bounded d5
  descriptor. Its current stream adapter eagerly walks EAVT; lazy restored
  B-tree traversal remains a lower-level index API.
- **Provenance slots** — every datom carries `t` and `m`; the `m` entity is where
  assert/retract and authorship metadata live (`datom-spec.md`).

## What LLM agents need — the gap list

Ordered roughly by how hard each blocks a working system.

### 1. A tool surface (the actual LLM integration)

LLMs act on the world through **tool use** (also "function calling"): the model is given a
list of operations with typed parameters, and it emits a structured call instead of prose;
the runtime executes it and feeds the result back. The de-facto wiring standard is **MCP**
(Model Context Protocol), a JSON-RPC protocol agent runtimes speak natively.

`dao.space` needs a thin tool server exposing, at minimum:

- `query` — run a `q` form or `match` template, return results as EDN/JSON
- `deposit` — append datoms (or entity maps, which the library already normalizes) to the
  calling agent's own log
- `vocabulary` — return the attribute vocabulary in use (see gap 3)

`dao.stream.rpc` already provides the transport shape this wants — a WebSocket server
dispatching a `{op-keyword fn}` handlers map — so the work is an adapter (MCP's JSON-RPC on
one side, `q`/`match`/append on the other), not new infrastructure. Most agent runtimes are
Python or TypeScript, which is also why this must be a protocol bridge rather than an
embedded library: the Peer stays in-process on the JVM; agents reach it over the wire.

### 2. The implemented agent write path

Each agent opens one `:transactor` stream wrapper over its own single-writer
local stream and an explicit DaoJing intake pool. `ds/append!` / `transact!`
append one atomic transaction record and allocate stream-local `t` values;
`publish!` snapshots that retained history and enqueues covered-index payloads
through one selected intake stream. The wrapper creates no registry, owns
neither supplied stream, and cannot coordinate two writers over the same local
stream. One wrapper per local stream is therefore a hard invariant.

### 3. A discoverable vocabulary (the schema is the prompt)

Stigmergic coordination among strangers works only if the *attribute vocabulary* is shared:
`:work/posted`, `:work/claims`, `:work/result` mean the same thing to every participant.
For LLM agents the vocabulary must appear in the **system prompt** (the standing
instructions the model sees every turn) — an LLM cannot match on attributes it has never
been told exist.

The right home for that vocabulary is the space itself: schema-as-datoms, queryable like
everything else, so an agent's runtime can `q` for the vocabulary at startup and inject it
into the prompt. This is ordinary datom data — no new mechanism, just a convention to pin
(and the discipline that vocabulary changes are appends, not edits).

### 4. Negation in `q`

The canonical coordination query — "posted work nothing has claimed" — uses `(not ...)`
or `(not-join ...)`. Both are implemented, including predicate clauses such as
`[(< ?now ?exp)]`, so availability is one declarative query rather than a set difference
in agent code.

### 5. Current-state resolution

Current state is never inferred from tuple arity. `(query/current source)`
explicitly interprets canonical d5: histories are grouped by local `[e a v]`,
the greatest `t` wins, and `dao.datom/retracted?` removes a retracted winner.
`(query/history source)` exposes the exact d5 relation. A bare relation,
including one whose tuples happen to have five positions, has no temporal
semantics.

### 6. Cross-stream identity (interpreter context)

Source identity is interpreter context, never a sixth tuple position. Separate
`:in` database values keep equal stream-local entity ids separate unless a
query deliberately unifies them. Cross-source correlation should use shared
values such as `:task/id "uuid-..."`, or another explicit identity
interpretation supplied by the query. Neither DaoJing nor `q` stamps tuples.

### 7. Comparable time for the claim tie-break

Read-side conflict resolution ("sort by timestamp, break ties by agent id") requires a time
coordinate comparable *across* streams, and `t` is stream-local; cross-stream `as-of`
semantics are an open question in ADR 0001. Interim convention: agents stamp a wall-clock
instant as a datom attribute (or in `m`-entity metadata) on claim datoms, and every reader
applies the same documented tie-break rule. Weak clocks are acceptable here because the rule
only needs to be *deterministic given the data*, not globally correct.

### 8. Context economy (result shaping)

An LLM's **context window** — the bounded number of tokens it can see — is the scarcest
resource in the loop. Global associative match is the identity of the tuple space, but "all
matching datoms" can be far more than a prompt can hold. Agents need relevance scoping as a
first-class query habit (predicates over content, `dao.space.md`, *Global match and
scoping*) plus mundane result shaping the library does not yet have: limits, ordering,
count-only. These are ergonomics on the query surface, not model changes.

### 9. Trust boundaries (later, but already designed)

Public mode assumes trusted peers: any agent can read everything and any reader trusts every
writer's datoms. For untrusted or third-party agents the architecture already names the
answer — controlled mode (ADR 0002, "share governed computation, not data"): the foreign
agent submits a governed interpreter (a `yin.vm` AST) under a capability token, it runs
confined against only the datoms the capability authorizes, and only the bounded answer
returns. None of it is built, and none of it blocks trusted-fleet use today. The prompt-level
corollary matters now, though: datoms read from the space are *data*, and an agent's prompt
should treat them as untrusted input, never as instructions — the same discipline as any
retrieval-augmented system.

### 10. Fair claim allocation (observed in the live prototype)

The first multi-model run (GLM vs. DeepSeek workers over the prototype in
`prototypes/dao-space-stigmergy/`) exposed a dynamic the claim protocol permits but the doc had
not named: **first-claim-wins selects for latency, not competence.** Every one of the four
posted tasks was raced by both models; the faster model's claim `t` landed a few seconds
earlier every time, so it won all four. The slower model correctly detected every loss from
the shared facts and declined to duplicate work — the coordination was flawless — but the
*allocation* degenerated to "fastest model does everything," which wastes the fleet and, in
the degenerate case, routes all work to whichever model spends the least time thinking.

Two stigmergy-compatible remedies, both read-side rules over ordinary datoms — no locks, no
broker, and no change to the medium:

- **Claim leases** (*implemented in the prototype, 2026-07-10*). A claim is a lease, not a
  title: it wins only while live (now < `:claim/expires`) or once a matching `:result/task`
  lands, which settles the task permanently. An expired claim without a result counts for
  nothing — the datom stays in the log forever; only its *interpretation* changes — so the
  task reappears as unclaimed and anyone may re-claim. This also heals the crashed-winner
  case, which previously orphaned the task forever. With no coordinator, the *depositing
  agent* stamps `:claim/expires` (= `t` + lease window) the same way it stamps wall-clock
  `t` and `:dao/agent`, and every reader applies the lease and winner rules as the same
  documented queries (a `not-join` with a `[(< ?now ?exp)]` predicate for availability; a
  claims query binding the datom `t` for the `[t agent]` tie-break). Exercised by the
  `claim-leases` test.
- **Randomized backoff before claiming** (*still open*). Each worker waits a small random
  interval before depositing a claim, decorrelating claim times from model latency and
  spreading work across the fleet. Cheap, prompt-level, and composable with leases.

Neither needs library or storage changes: leases are a writer convention plus a read-side
query rule, exactly as predicted.

## The minimum viable stack

For a working multi-agent system on today's code, trusted agents only — no coordinator, no
deposit API, no new namespaces:

1. **One `dao.jing.file` content handle**, made network-accessible with
   `dao.jing.remote/default-handlers`. A remote reader uses
   `dao.jing.remote/connect-content!`; both handles expose the same plain-data content
   effects.
2. **Writes**: each agent owns a local `dao.stream` and opens one `:transactor` with that
   stream plus an explicit DaoJing intake pool. It commits entity maps or datom vectors
   through `ds/append!`/`transact!`, using integer stream-local entity ids; the wrapper
   assigns transaction `t`. `publish!` appends covered-index blobs and a manifest to one
   selected intake stream.
3. **Materialization and reads**: a DaoJing observer consumes the supplied intake pool and
   materializes its opaque payloads into `dao.jing`. Readers query exactly the
   inputs they care about: immutable DaoStream descriptor db-values that may
   resolve content locally or remotely. "Available work" is one query (negation + lease predicate);
   claims and results are joins; every reader applies the documented winner rule.
4. A pinned attribute vocabulary and these conventions in every agent's system prompt
   (gap 3, manual version).

The contract is executable: `test/dao/space/stigmergy_test.clj` runs the full loop —
post, associative discovery, racing claims, leases, retraction, settle — over the wire,
and leaves the space on disk for post-hoc inspection with `dao.space.query`.

Everything else — discovery, arranged streams, controlled mode — makes
the system better without changing what the agents already do: read the medium, decide,
deposit a trace.
