OpenAI Codex v0.159.3
--------
workdir: /Users/sto/workspace/datomworld
model: gpt-6-luna
provider: openai
approval: on-request
sandbox: read-only
reasoning effort: low
reasoning summaries: none
session id: 01a12094-c76d-73c2-acb1-fe2468754baa
--------
user
Created-GMT: 2026-10-09 12:13:00 GMT
Created-Local: 2026-10-09 19:13:00 ICT

# Task: Architecture Review - Feasibility of Compiling Suspended Semantic VM Continuations to Register VM Bytecode

Role: Lead System Architect

Implementers:
- Model: codex (gpt-6-luna) | Assigned: 2026-10-09 19:13:00 ICT | Status: active | Rationale: Architectural authority per team roster

Perform a read-only architecture review of the following architectural inquiry:

"Register VM bytecode (`yin.vm.debruijn.register`) is compiled from Semantic VM Universal AST tuples (`yin.vm.semantic`). Therefore, can continuations paused/parked on `yin.vm.semantic` be dynamically compiled into Register VM bytecode and executed across a migration boundary (e.g. over a `dao.stream` or UCF)?"

Read first:
- `docs/design/datom.world.md`
- `docs/design/yin.vm.universal-continuation-format.md` (specifically §7.0, §7.1, §7.3, §7.4, §7.5)
- `docs/design/yin.vm.debruijn.register.md`
- `docs/design/yin.vm.linker.md`
- `src/cljc/yin/vm/semantic.cljc`
- `src/cljc/yin/vm/debruijn/register.cljc`

Evaluate:
1. Foundational invariants: Interpretive vs execution boundaries, state representation (CESK named environment maps vs de Bruijn positional index frames / register allocation).
2. The dynamic safepoint / deoptimization problem: Why compiling an arbitrary mid-flight continuation is distinct from compiling a closed Universal AST form.
3. The Universal Continuation Format (UCF) rules: `:yin.k/binding :named` vs `:yin.k/binding :positional`, fail-closed `:binding-mismatch` invariants, and whether cross-kernel continuation compilation is architecturally sound or an anti-pattern.
4. Distinguish what is theoretically possible (e.g., safepoint reconstruction maps) from the current repository commitments and contracts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction.
Also provide your comprehensive architectural analysis and ruling on this question.

codex
I’ll inspect the specified architecture docs and implementation files, then ground the review in the repository’s stated invariants and line references. This is a read-only review.
exec
/bin/zsh -lc "sed -n '1,240p' docs/design/datom.world.md && sed -n '1,260p' docs/design/yin.vm.universal-continuation-format.md && sed -n '1,240p' docs/design/yin.vm.debruijn.register.md && sed -n '1,240p' docs/design/yin.vm.linker.md && sed -n '1,260p' src/cljc/yin/vm/semantic.cljc && sed -n '1,280p' src/cljc/yin/vm/debruijn/register.cljc" in /Users/sto/workspace/datomworld
 succeeded in 0ms:
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
- **Agent Delegation**: See [`docs/agents/roles/orchestrator.md`](../agents/roles/orchestrator.md) for collaboration protocols and delegating tasks or reviews through `agy`, `cmd`, `glm`, `deepseek`, and `codex`; the orchestrator's append-only work log is [`docs/orchestrator-log.md`](../orchestrator-log.md).
- **Website & Content**: See [`docs/agents/format.md`](../agents/format.md) for `.chp` and `.blog` EDN/Hiccup file specifications.
## §7. The Universal Continuation Format (Proposed)

> [!WARNING]  
> **Status: Proposed / Deferred.** The Semantic VM's linear CESK state is
> theoretically sound as an architecture-agnostic continuation format, but
> true heterogeneous network migration requires addressing several critical
> defects identified in the 2026-09-14 architectural review. This revision
> (r4) incorporates r2's answers to that review's findings 1-19, the owner
> ruling on S7.3's canonical form (r3), and Rule R's "v3" contract (r4);
> S7.11 lists the acceptance blockers that keep this warning standing until
> an implementation phase closes each of them with tests.

The Semantic VM's linear CESK state (`{:segment id, :pc n, :env E, :stack S, :k K}`) is proposed as the canonical exchange format for network-transparent continuations. Because this lowered representation resolves execution-order ambiguity inherent in the Universal AST, it provides a simpler target for specialized execution engines (e.g., WebAssembly, LLVM, hardware FPGA) to participate in the `datom.world` ecosystem.

To safely "lift on park" and "lower on resume" across heterogeneous boundaries without violating host isolation or concurrency invariants, the following contracts must be fully specified before this feature is accepted:

1. **Safepoint Reconstruction Metadata:** `:yin.code/source` mappings are insufficient for state reconstruction. The safepoint set must be exactly the machine's parking transitions — including effect-producing `:call`s — and reconstruction metadata must separate what is derivable from the canonical code alone (stack *effects*, lexical *requirements*) from what is per-activation state only the frame can carry (absolute depths, captured environments). Foreign engines publish per-segment safepoint maps derived from the canonical stream so arbitrary hardware states can be predictably lifted into the canonical CESK format.
2. **Recursive Portable Encoding:** Section 1.1 permits host functions and local stream handles in the environment. A recursive encoding over a **disjoint tagged grammar** is required — one a program's own map literals cannot counterfeit — to encode these local references into portable descriptors, together with per-primitive **semantic profiles** (a name recovers a binding, not a meaning) and explicit, total failure modes for un-serializable resources. Cursor aliasing is observable and must survive the round trip.
3. **Dependency Closure & Context:** A parked fragment is not a complete configuration. The format must carry its complete scheduler context — required primitives *with profiles*, loaded modules, referenced parked records, the fresh-name counter, and the reachable store slice — discovered by a conservative fixed point that includes explicit store operands, and must restore into an **isolated execution store** so the receiver's own state cannot change resolution.
4. **Ownership Arbitration:** Emitting a continuation does not transfer ownership. Exclusion requires a **grounded authority** — a resource the grantor's boundary actually possesses, named in the value, independent of carriers — plus an **exporting state** that fences the source before publication, and **effect fencing** with authority epochs and stable operation ids, because absence of a lapse record is never evidence of tenure.
5. **Code Identity (Content Addressing):** Because tempids (`:segment 123`) are local to a single machine's database, continuations must refer to code via immutable, versioned semantic profiles — a **canonical instruction vector** (positional tuples, not EAV) whose address is computed over the loader's resolved interpretation, whose addressed payload is the vector itself, and whose interpretation is pinned by a contract stamp versioning the *complete* execution contract.

---

# Universal Continuation Format — protocol draft

Status: Phase 5 design draft, 2026-09-14; revised 2026-09-14 (r2) per the
architectural review `collab/1789387292000-architect-ucf-review.gpt-6-astra.findings.md`;
amended 2026-09-14 (r3) by owner ruling — §7.3's canonical form is the
positional instruction tuple vector, not EAV (§7.3.2).
Amended 2026-09-25 (r4) for Rule R, `yin/def` is syntax, never a name
(collab `1790345200000-architect-yin-def-rule-r-final` findings): the
contract stamp moves to "v3" because the resolution order refuses the
reserved name before env and a `:define` transition is added (S7.3.3);
`yin/def` leaves the primitive registry (S7.5.2) and the free-name set
(S7.6.1); S7.11 records the remaining definition-frame obligations.
Amended 2026-10-04 (r5) for fenced custody (`yin.vm.linker.dht.md` 14.3,
M-next B): the handoff body is published as wire grammar, and
`:yin.k/version 1` adds the custody header, the operation-sequence state,
the fenced envelope, the grant epoch binding, and the admission outcomes
(7.2.1, 7.4.3, 7.7.8, 7.9, 7.11.1). The amendment is published design:
no version-1 code is landed and it closes no blocker.
Revised 2026-10-04 (r6) after the second architect's adversarial review
(collab `1791056670000-architect-ucf-v1-amendment-review`): inherited
operation ids are scoped to the granted checkpoint's carried pendings,
dedup is one namespace per admission resource, admission outcomes are
authenticated, uncertainty is separated from terminal refusal, epoch
exhaustion has a stated precedence, install children validate in their
root's context, and defective envelopes yield a structured diagnostic.
Revised 2026-10-04 (r7) on that architect's confirmation (collab
`1791057600000-architect-ucf-v1-amendment-r6-confirm`): checkpoint
unavailability suspends after the tenure check and mutates nothing,
snapshot variants preserve the operation baseline, the install phase
no longer constrains the child's kind, the defect set is defined in
full, and unknown transport acceptance is neither commitment nor its
absence.
Subordinate to [`datom.world.md`](./datom.world.md); builds on the Phase 0
contract in [`yin.vm.semantic.md`](./yin.vm.semantic.md) (§1–§4), the storage
contract in [`dao.jing.md`](./dao.jing.md), the stream contract in
[`dao.stream.md`](./dao.stream.md), and the lease vocabulary in
[`dao.lease.md`](./dao.lease.md). The five blockers above are answered in
§7.3–§7.7, one section each, in dependency order: code identity first, because
every other part names code.

Every sentence in §7.2–§7.9 is a rule of the proposed protocol. §7.11 records
what is deliberately left open, as acceptance blockers. Nothing below is
implemented; the warning at the top stands until an implementation phase
resolves each blocker with tests.

## §7.1 Stance: a continuation is a value, not a session

The semantic VM already parks as pure data (`yin.vm.semantic.md` §3.5): a
wait entry or a `:parked-continuation` record is `{segment pc env stack k}`
plus a reason and resource ids, with no handle and no closure attached. That
record is *locally* complete and *globally* meaningless: its segment is a
tempid, its stream ids are keys into this VM's store, its environment may hold
host functions, and nothing says which primitives it assumed.

The Universal Continuation Format (UCF) is the **lift** of that local record
into a self-describing value that any conforming engine can **lower** back
into its own registers, and the discipline that governs who may lower it.
Three roles participate, and they are stream roles, not parties to a call:

| Role | Does | Holds |
|---|---|---|
| **Emitter** | lifts a parked configuration into a UCF value and appends it to a medium | nothing, once appended |
| **Carrier** | any stream, file, DHT, or socket that moves the value; sees opaque data | nothing |
| **Resumer** | reads the value, satisfies its declared context, obtains custody, lowers, runs | custody, for the duration of a lease |

Three consequences are load-bearing. First, **a UCF value is content**: it has
a content address under `dao.jing`, equal values converge on one address, and
carrying it twice is harmless (`dao.jing.md`, *Materialization rule*). Second,
**a UCF value on a stream is not a claim**: a stream has no privileged reader
(`datom.world.md`, *Streams*), so emission transfers nothing. Custody is
arbitrated above the stream, by leases over datoms (§7.7), exactly where
`dao.stream.md` *Explicitly Absent* says a take belongs. Third, **content is
not occurrence**: the same parked computation can be encoded more than once
(sharing thresholds differ, §7.5.3), so the encodings address differently,
while remaining one *checkpoint occurrence*. Content identity is for
deduplication and materialization; occurrence identity is what custody names
(§7.3.4, §7.7). Conflating them was a defect of the first draft; this draft
never does.

The reference engine for every rule below is `yin.vm.semantic`. Its
configuration ⟨C, E, S, K⟩ with C = ⟨seg, pc, val, St⟩ (`yin.vm.semantic.md`
§4.1) is the **canonical CESK**. A foreign engine conforms by lifting into and
lowering from this configuration, never by extending it.

## §7.2 Vocabulary and envelope

UCF facts live under `:yin.k/*` (K for the continuation component of CESK).
A UCF value is one open map dispatching on `:yin.k/type`; a consumer ignores
qualified keys it does not understand, as for every DaoStream envelope. The
value must survive the host codec structurally unchanged: no function, host
object, or live handle anywhere inside it (`dao.stream.md`, *Envelopes*).
Every value it contains is encoded in the disjoint tagged grammar of §7.5 —
never a bare marker map.

```clojure
{:yin.k/type        :yin.k/continuation
 :yin.k/contract    {:yin.code/contract "v3" :yin.k/version 0}   ; S7.3.3
 :yin.k/id          :segment/sha256-…          ; §7.3.4  content address of this map minus :yin.k/id
 :yin.k/occurrence  :yin.k/o-…                  ; §7.7    the checkpoint occurrence — the lease subject
 :yin.k/origin      {:yin.k/occurrence :yin.k/o-…                        ; predecessor, or absent on a first park
                    :dao.lease/lease   :dao.lease/l-…                     ; the grant it ran under
                    :yin.k/emitter     <attribution>}
 :yin.k/arbitration {:dao.stream/identity … :dao.stream/descriptor …}     ; §7.7.3  the authority medium, named in the value
 :yin.k/frame       {…}                        ; §7.4  the canonical CESK at a safepoint
 :yin.k/values      {…}                        ; §7.5.3  shared subvalues, by content address
 :yin.k/cells       {…}                        ; §7.5.3  cursor cells: logical identity + position
 :yin.k/scheduler   {…}                        ; §7.6.3  fresh-name state and referenced parked records
 :yin.k/requires    {…}                        ; §7.6  dependency closure, with discovery status
 :yin.k/store       {…}                        ; §7.6.2  the store slice restored as an isolated store
 :yin.k/carried     [ … ]                      ; §7.3.4  canonical instruction vectors shipped inline
 :yin.k/policy      :yin.k/exclusive}          ; §7.7  or :yin.k/fork
```

The parts answer four questions a resumer must be able to ask of the data
alone: *what code* (`:yin.k/requires`, `:yin.k/carried`, and every segment
address inside the frame), *what state* (`:yin.k/frame`, `:yin.k/values`,
`:yin.k/cells`, `:yin.k/store`, `:yin.k/scheduler`), *what world*
(`:yin.k/requires`, `:yin.k/arbitration`), and *who may run it*
(`:yin.k/policy`, `:yin.k/occurrence`, with the custody facts of §7.7 on
their own medium).

A halted computation travels as the sibling value:

```clojure
{:yin.k/type      :yin.k/result
 :yin.k/contract  {…}
 :yin.k/occurrence :yin.k/o-…     ; the occurrence that halted
 :yin.k/value     <encoded>}      ; the final accumulator, in the §7.5 grammar
```

Both envelope kinds take their identity by the same rule: `:yin.k/id` is the
`dao.jing` segment address of the map with `:yin.k/id` removed (§7.3.4).

Outcomes of every UCF lift or lower operation are data: one map
dispatching on `:yin.k/status`, in the DaoStream result convention. Where
an underlying stream outcome is met by a lift or a lower, it is preserved
unchanged under its own dispatch key `:dao.stream/outcome`: for those
direct failures UCF never renames it or nests it under a private key.
Admission outcomes are a second, disjoint family with their own dispatch
key. The full algebra is in 7.9.

### 7.2.1 The handoff body: the task wire grammar, versions 0 and 1

(Amendment r5, 2026-10-04; `yin.vm.linker.dht.md` 14.3 item 2.) The
envelope above is the design shape. The value a driver publishes is the
**handoff body**: one task, the whole blocked machine of 7.6.3, as one
map in canonical CBOR (`dao.jing.cbor.md`) under its content address.
Where the envelope sketch and this grammar differ, this grammar is the
wire contract. A task has an ordered wait set, so the body carries
ordered `:yin.k/frames`, never one `:yin.k/frame`. The scheduler slice of
7.6.3 is flattened into top-level keys. Code travels as an address-keyed
map. The body carries no `:yin.k/id`: its address is the hash of the body
(7.3.4). A halt is a body of kind `:halted`, the `:yin.k/result` sibling.

```clojure
{:yin.k/handoff       true      ; the tag
 :yin.k/version       0         ; the BODY's version: 0 or 1
 :yin.k/kind          :blocked  ; :blocked | :parked | :halted
 :yin.k/contract      {:yin.code/contract "v3" :yin.k/version 0}
 :yin.k/id-counter    n         ; 7.6.3 fresh-name state
 :yin.k/parked        {pid registers}      ; 7.6.3 parked records
 :yin.k/store         {encoded encoded}    ; 7.6.2 isolated slice
 :yin.k/module-stores {module {encoded encoded}}
 :yin.k/cells         {cell {:yin.k/stream marker
                             :yin.k/position p}}   ; 7.5.3
 :yin.k/requires      {:yin.k/cursor-profiles #{profile}
                       :yin.k/segments #{address}}
 :yin.k/code          {address vector}     ; 7.3.4, each verified
 ;; by kind
 :yin.k/frames        [{:yin.k/registers registers
                        :yin.k/pending pending}]   ; in wait order
 :yin.k/parked-id     pid       ; :parked only: the active record
 :yin.k/result        encoded   ; :halted only
 ;; when the task holds live install children (7.4.3)
 :yin.k/installs      {module install}}
```

`registers` is `{:yin.k/segment address :yin.k/pc n :yin.k/env {name
encoded} :yin.k/stack [encoded] :yin.k/k [frame]}`, every key required,
the segment a key of `:yin.k/code` and the pc inside that vector.
`pending` is one variant of 7.4.3. Every value is in the 7.5 grammar.

Shape rules, each a `:yin.k/undecodable` refusal naming its path:

- `:blocked` requires nonempty frames. `:halted` requires
  `:yin.k/result` and forbids frames. `:parked` requires
  `:yin.k/parked-id` naming a key of `:yin.k/parked`. A `:yin.k/result`
  on any other kind is refused.
- Frames restore in their carried order, for `:blocked` and for
  `:parked` alike; a resumer never drops a carried frame (7.4.3).
- A frame's resume pc is a static safepoint whose kinds admit the
  pending's reason; a parked record's resume pc is an `:explicit-park`
  safepoint (`yin.vm.ucf-revisions.md` section 8).
- The cells the body carries are exactly the cells its roots name:
  none missing, none extra. A body with cells claims
  `:dao.stream.remote/v1`, else `:yin.k/unsatisfied`.
- Each code vector hashes to its key (`:yin.k/hash-mismatch`) and is
  well formed under the stamp.

Lift adds these kinds to the `:yin.k/non-portable` set of 7.5.4:
`:incomplete-install`, `:install-response`, `:incomplete-write`,
`:reason-mismatch`, `:inconsistent-halt`, `:unaddressed-segment`,
`:address-mismatch`, and, under version 1, `:unprotected-pending` and
`:op-seq-exhausted` (7.4.3).

**Two version keys.** `:yin.k/version` at the top of the body is the
body's own version and is what this amendment raises. The key of the
same name inside `:yin.k/contract` belongs to the code stamp of 7.3.3.
That stamp is compared whole and stays `{:yin.code/contract "v3"
:yin.k/version 0}`: the amendment changes handoff data and composition
admission, not opcode semantics, so code stamps "v3", "b2" and "r2" and
module manifest schema 1 are unchanged, and cursor vectors keep their
bytes.

**The version gate.** A resumer decodes the bytes, checks the tag, and
then checks the body version before any other rule. A version it does
not speak, an absent version, or a version that is not an integer is
`:yin.k/profile-mismatch`, carrying `:yin.k/version` as found and
`:yin.k/supported`, the set the resumer speaks. The gate runs before
the remaining grammar, before any code hash is verified, before any
stream is attached, before any proposal is made, and before anything
is restored. An install child's body carries the version of the body
that holds it. Validate every nested body's version and structural
role before attachment, proposal, or restoration; a supported but
mixed-version tree is `:yin.k/undecodable`. The integer check is on
the canonical codec's integer kind, never on numeric equality: an
integral float such as `1.0` must not validate as a version on any
host, Node included, where both would compare equal to 1. A reader
that speaks only version 0 refuses a version-1 body by this gate and
never reads it as fork data.

**Version 0** is the grammar above and nothing more. It is a fork: it
names no occurrence, proposes nothing, and runs through no fenced
writer. A version-0 body stays valid under this contract and is never
upgraded to fenced custody, by a resumer or by a carrier. A version-0
reader ignores keys it does not know, so a custody key inside a
# yin.vm de Bruijn register VM

Status: design, revised; not implemented

This document specifies a register execution path that is a peer of the
committed de Bruijn stack path. Both are projections of the same de Bruijn
encoding of the named semantic tuples; neither is derived from the other.
It does not replace the named datoms, the stack image, the stack VM, or the
dormant projection. The first deliverable is a pure resolved-tuples-to-
register lowerer and validator. The register VM kernel is a committed later
phase, authorized by the owner without a benchmark condition (section 8,
DECIDED 1).

## 1. Objective and invariants

The objective is a deterministic register image derived from the de Bruijn
encoding of the named `:yin/*` semantic tuples, at the same level as the
`:yin.debruijn.code/*` stack image. The register lowerer consumes the
resolved tuples and emits a register image; it never reads, rewrites, or
depends on the stack image. The stack image remains independently
addressable by its existing H, and the register image is independently
addressable by its own R.

This follows the project's established compilation topology
(`docs/agents/architecture.md`, "COMPILATION AS STREAM PROCESSING"): one
upstream stream feeds several parallel projections, and no projection is
compiled from another. That pattern is applied here one level down, at the
de Bruijn layer.

This path is worth building on its own merits, and the merit is not
performance. The governing reason, in the owner's words, is that it
demonstrates the philosophy that one universal AST can be interpreted by
different VMs: one truth, many interpretations. The named datoms are this
project's single source of truth. The named VM, the stack VM, and the
register VM are not competing implementations with one canonical and the
others alternates; they are peer witnesses to the same truth, each free to
interpret it by its own execution model, bound only by agreement on
observable behavior under the B0 normalizer and never on internal
mechanism. This is not new here. It is axiom 2 of `datom.world.md`,
"Interpretation Creates Semantics ... one truth, many perspectives", made
concrete for evaluators the way its Streams section already makes it
concrete for a `yin.vm` evaluator and `dao.space` reading one stream with
no privileged reader ("one stream, many interpretations, none of them the
stream's own"), and the way `docs/agents/architecture.md`, "COMPILATION AS
STREAM PROCESSING", already states it for backends ("Same datoms, multiple
interpreters ... same wave function, different measurements"). The same
file's "AGENTS" section, under Continuation Migration, already relies on
it: the AST datoms are the canonical payload, bytecode is a projection for
one execution model, and a destination projects the datoms into whatever
model it prefers. A third evaluator over the same datoms is that
established idea with one more witness.

The specific mechanism for it is the one this epic has been building
toward: a configurable compilation pipeline with `dao.stream` as every
stage boundary, in which several peer executable formats (named, stack,
register) share one upstream de Bruijn encoding and each backend is an
independent, swappable interpreter rather than a hardcoded single
evaluator. The register path also targets an alpha-invariant, content-
addressed encoding rather than reintroducing a VM directly beside the AST
walker. A lowerer alone proves the register format is well defined. Only a
running kernel proves that a second execution backend can be plugged into
the same pipeline and run real programs through it, which is what makes
the philosophy demonstrated rather than asserted. The owner has therefore
authorized the kernel unconditionally; the lowerer and format still ship
first, as their own milestones, and the R3 benchmark reports numbers
without deciding anything.

The following invariants apply:

1. A register image is derived data, derived from the resolved tuples. The
   stack image is never consumed, rewritten, or invalidated by the register
   path, and neither image is derived from the other.
2. `dao.stream` is the stage boundary. Every lowering is a pure stream-to-
   stream function; no stage mutates an input stream or retains a callback.
3. H and R are peer format identities, not competing semantic identities.
   H identifies the exact stack bytes; R identifies the exact register bytes
   that a register-capable host can run. Neither preimage mentions the other.
4. Equal R implies equal register bytes and descriptor contract. It does not
   imply that a stack VM can execute those bytes.
5. Register execution must agree with the named semantic VM, and therefore
   with the stack VM, under the B0 normalizer: results, errors, store
   effects, stream outcomes, tail calls, and lexical resolution.
6. All register allocation choices are data-derived and deterministic across
   runs and hosts.

The existing stack design's D1-D16 decisions remain authoritative. The
register path adds no projection fingerprint and no global registry. B2
is decided as the resolver-and-stack-lowerer split described in section
2.2 below; everything else in `yin.vm.debruijn.stack.md` is unchanged.

### 1.1 Identity and sharing

The register descriptor is `:yin.debruijn.register/*`. Its `register-hash`
function, R, hashes the register descriptor hash and the canonical
positional register vector, exactly as B1's `image-hash` hashes the stack
descriptor hash and the canonical stack vector. R is the only identity used
to fetch, verify, cache, or execute register bytes. A linker request must
name both the format and R, for example
`{:format :yin.debruijn.register :hash R}`.

R is alpha-invariant for binder renames for the same reason H is: the
register vector carries `:load-bound` depth and position operands and
`:closure` arities, and binder names live in a diagnostic side table outside
the hash. Equal R therefore implies agreement on exact scalar spelling, free
name operands, and front-end tail flags, as equal H does. Each format is
deterministically derived from the resolved tuples, so equal resolved
tuples give equal H and equal R, and a binder rename changes neither. No
biconditional between H and R is claimed: two deterministic projections may
preserve different distinctions, and the corpus tests are evidence for the
one-directional law only.

This is not a second semantic answer to alpha-equivalence. A register
lowerer cannot repair differences in exact scalar spelling, free names, or
front-end tail flags; those differences already make the resolved tuples,
and so both H and R, differ.

The pairing of an R with the H of the same named root is a composition
fact, carried beside a request or recorded as a datom by whoever publishes
both, never inside either preimage. A receiver verifies a fetched register
image with R alone and never needs to have seen the stack image.

## 2. Compilation topology and stream contract

The register projection is a peer of the stack projection. Both branch from
the resolved tuples, the de Bruijn encoding of the named AST datoms:

    Source -> Yang -> named AST datoms (:yin/*)
                         |\
                         | \-> named semantic image (:yin.code/*)
                         |
                         +----> resolved tuples (de Bruijn AST, no H)
                                   |\
                                   | \-> stack image (H)      B1 shape, B2
                                   |
                                   +---> register image (R)   R1

The register edge is not chained after the stack image. The named stream,
the resolved tuples, the stack image, and the register image coexist and
remain independently queryable or persistable, per the pipeline invariant
that no stage destroys its input.

The stages have the pure shapes:

    (resolve named-datom-stream)      -> resolved-tuple-stream
    (lower-stack resolved-stream)     -> stack-image-stream     (H)
    (lower-register resolved-stream)  -> register-image-stream  (R)

Each register output item carries a register descriptor reference, the
canonical register vector, R, and a diagnostic side table. The implementation
may build indexes while evaluating one bounded item, but those indexes are
local function data and are not written back into any input.

The register stage refuses malformed resolved tuples, unsupported node
types, or out-of-range bound operands with qualified data diagnostics. It
does not silently fall back inside the lowerer. A composition may route the
same named root to the stack path after receiving that outcome.

### 2.1 The shared artifact: resolved tuples

The resolved tuples are defined by the stack design, section 3.1, and that
definition is normative here. In summary: the named `:yin/*` datoms form a
finite graph in which one source entity may be referenced under several
lexical contexts, and resolution is a fact about an occurrence, the pair
`[source-eid lexical-context]`, not about the entity. The resolver mints one
resolved record per distinct occurrence, with a fresh deterministic id, and
shares it across references under equal contexts. Each record is the source
node's datoms with exactly two changes, the same two changes B1's opcode
table makes to the named instruction table:

1. A `:variable` record is resolved by the public
   `yin.vm.debruijn/resolve-name` against the innermost-first stack of
   enclosing parameter vectors, to either a bound reference `[depth
   position]` or a free reference carrying the exact symbol.
2. A `:lambda` record carries its arity instead of its parameter vector.

Everything else is carried exactly: scalar values with their exact spelling,
free names as supplied, `:yin/tail?` as the front end wrote it, node
structure, and evaluation order. Binder names and provenance go to the
resolver's side table, keyed by resolved record: `:source` maps every
record to its source eid and `:params` maps every resolved lambda to its
exact parameter vector. Nothing is canonicalized: this is not the dormant
merged projection, which canonicalizes scalars, NFC-folds names, and drops
tail flags, and which the stack design's architecture B rejected as a
lowering input for those reasons. It does share that projection's
occurrence key, because the two face the same graph.

The resolver is the graph-level home of name resolution. It validates each
bound reference against the enclosing arity chain, the same nonnegative-
depth-and-position rule B2 checks on the vector, refuses a program whose
resolution fails before either lowerer runs, and exports the one
`validate-resolved` both lowerers call unconditionally on their input. It
reuses only `resolve-name`; projection-only helpers are not reused, and the
resolver inherits the stack design's retirement condition for that helper.

The resolved tuples have a shape contract and a namespace so that two
lowerers and their tests can agree on what they consume. They have no hash,
no identity, and no sharing role:

- Invariant I shares executable code. The resolved tuples are not executable
  by any VM in this project; the executable artifacts are the stack image
  and the register image, identified by H and R.
- D9 and D12 make H the only identity on the stack sharing path, computed by
  one function over the descriptor hash and the canonical vector. R mirrors
  that rule for the register path. A hash of the resolved tuples would be a
  third identity that no request, response, verification, or cache needs.
- "Derive, do not persist" (`datom.world.md`): the resolved tuples are a
  pure function of the named datoms and `resolve-name`. Persisting them or
  giving them an identity would duplicate authority over facts the named
  datoms already hold. They are a stage value: computed, consumed, and
  discardable. A composition that chooses to retain the stream may do so,
  as it may retain any intermediate, but nothing downstream keys on it.
- D2 and section 7.3 of the stack design forbid a second lossless record
  DAG. The resolved tuples are not one: they are never stored as a source
  view, never served, and never a fetch key. With the side table they are
  invertible to the named datoms, but that property is used only by tests.

### 2.2 Relation to B2 as implemented

The owner decided, and B2 now implements, the resolver-and-stack-lowerer
split rather than the earlier fused pass: `yin.vm.debruijn-resolve/resolve`
produces resolved tuples per section 2.1 (occurrence-indexed identity,
cycle refusal, the exported `validate-resolved`), and
`yin.vm.debruijn-linearize/lower-stack` consumes them, calling
`validate-resolved` unconditionally at entry. `adapt`, the public entry
from named datoms to a stack image, is the composition `lower-stack` after
`resolve`. The stack image is therefore literally a peer projection of the
resolved tuples, by construction, not merely an equivalent one proven by a
separate law -- the vector-level scope reconstruction the earlier fused
pass needed (`closure-body-ranges`, `layout-conforms?`, `body-owner`,
`chain-of`) is removed, not moved, because scope is resolved on the graph
before either lowerer runs.

`lower-stack` still cannot call `yin.vm.linearize/lower` directly (it reads
`:yin/name` and `:yin/params`, which resolved tuples no longer carry), so it
reproduces the named linearizer's flattening walk itself: the same
recursion order, the same `:push`/`:call`/label emission, occurrences
expanded positionally exactly as `lower` expands them (a resolved record
referenced twice contributes two emitted instructions, at two pcs). Layout
equality with `lower` no longer holds by construction and is a required
structural-comparison test instead. Every golden H is unchanged: the
# yin.vm.linker: the universal code linker over dao.stream

Status: design, revision r11 (2026-09-24); implemented: milestones M1
to M5 of section 9 are landed on master, confirmed by the 2026-10-01
completion audit. Post-M5 hardening is specified in
`yin.vm.linker.dht.md` section 14. Supersedes the
namespace placement and the two-backend scope of the Phase B6
predecessor `yin.vm.debruijn.linker.md`; that document's fetch
pipeline, format records, refusal vocabulary, and same-root pairing
are carried here in substance and extended to all four `yin.vm`
execution backends. Its remaining load-bearing content was merged
into this document and the predecessor file was deleted (2026-10-01),
on the completion audit's confirmation that all five milestones of
section 9 landed:
collab/1790871424000-qa-yin-vm-linker-spec-completion-audit.md
Revision r2 reconciled the adversarial review
`collab/1790250614225-reviewer-universal-linker-spec.gpt-5.6-sol.findings.md`;
revisions r3 to r11 reconcile the round-2 to round-10 consensus
reviews by `gpt-6-sol` (thread
`01a0d340-f8e7-7e30-9b74-c0a0e6b636fb`). Section
13 records each finding of every round and its resolution. Subordinate to
`datom.world.md`, `dao.stream.md`, and `dao.jing.md`. Builds on the code
identity rules of `yin.vm.universal-continuation-format.md` (UCF) section
7.3, the addressing and derivation rules of `yin.vm.code-as-tuples.md`
sections 4 and 5, and the ledger of `yin.vm.ledger`.

Related documents:

- `docs/design/yin.vm.debruijn.stack.md` - governing decisions D5 to D16
- `docs/design/yin.vm.code-as-tuples.md` - AST rows, segment vectors,
  derivation records
- `docs/design/yin.vm.dependency-completion.md` - the work-item fixed
  point that computes a program's requirements
- `docs/design/yin.vm.universal-continuation-format.md` - code identity,
  contract stamp, safepoints, pending waits, primitive profiles
- `docs/design/dao.jing.md` - content-addressed storage and the stepped
  remote client
- `docs/design/dao.stream.md` - the stream contract, in particular OD-5
- `docs/design/yin.vm.linker.dht.md` - the peer-network composition:
  publishing a module closure, loading it over `dao.jing.dht`, and
  resolving names from signed envelopes in loaded indexes (section 8.2's
  signature proof kind, made concrete)

## 1. Objective and architectural position

`yin.vm` has four execution backends, all reachable from `yin.repl`
(`src/cljc/yin/repl.cljc`, `vm-constructors`):

| Backend       | Kernel                    | Code shape                   |
|---------------|---------------------------|------------------------------|
| `:ast-walker` | `yin.vm.ast-walker`       | universal AST row set        |
| `:semantic`   | `yin.vm.semantic`         | positional instruction vector|
| `:stack`      | `yin.vm.debruijn.stack`   | de Bruijn stack image (H)    |
| `:register`   | `yin.vm.debruijn.register`| de Bruijn register image (R) |

Each backend needs the same service: given an identity for some code, and
nothing else, obtain the code, prove it is the code the identity names,
prove it is well formed, prove the receiver can bind its dependencies
identically to the publisher, and hand it over for loading. Today that
service exists once, for two of the four backends, under a name that says
it belongs to one of them: `yin.vm.debruijn-linker`.

This document makes two corrections and one unification.

**Correction 1: the linker is universal.** The B6 fetch function has no
de Bruijn branch in it. It is parameterized by a format record and runs
the same six steps for a stack image and a register image. The namespace
`yin.vm.debruijn-linker` is therefore a misnomer that invites a second
linker for the semantic VM and a third for the walker. There is one
linker, `yin.vm.linker`, and every backend is a format record it takes.

**Correction 2: local and remote linking are one mechanism.** B6 already
states this (`yin.vm.debruijn.stack.md` section 7.2: "the boundary is
`dao.stream`; local and remote resolution are the same transport-neutral
mechanism"). But `fetch` today reads through a synchronous `dao.jing`
handle, and a local composition hands it an in-memory store whose `get` is
a map lookup. That is a function call across what the axioms say is a
stream boundary. This document makes the request and response a stream
exchange in every composition; what varies is the transport underneath
(ring buffer, file, WebSocket, DHT behind a served boundary), never the
linker's shape.

**Unification: `require` is linking.** Module loading in `yin.vm.module`
resolves `(require 'foo)` against a dictionary the composition built by
hand at wiring time. Linking resolves an identity to content over a stream.
These are the same operation at two grains: a module name is an entry in a
name environment whose value is a manifest address, and a manifest names
the images the linker fetches. `(require 'foo)` lowers to an effect that
parks the VM and emits a link request; the module arrives as verified
content on a response stream and is installed by a child evaluation the
scheduler owns. The dictionary becomes a manifest registry: a value naming
content addresses, never holding code that arrived by linking.

### 1.1 What this document adds over B6

1. The namespace `yin.vm.linker` and its migration from
   `yin.vm.debruijn-linker` (section 9).
2. Two new format records, `:yin.ast/code` and `:yin.semantic/code`, beside
   the two B6 records (section 5).
3. A `:parts-fn` slot in the format record so that a multi-row payload
   (an AST tree) fetches through the same pipeline as a single vector,
   with row-local validation and composition-supplied bounds before any
   child is enqueued (section 4.2, step 2).
4. Identity-directed matching: `:identity-matches-fn` replaces a bare
   hash comparison, so storage-derived identities verify under the
   algorithm the identity carries (section 4.1).
5. Dependency obligations `{name kind expected}` replacing bare free
   names, net of the module's own definitions, discharged by exact
   profile or manifest equality at the receiver (section 4.2, step 5).
6. The stepped link interface (`link-state`, `request-link`, `step`,
   `abandon`) as the portable shape, with a plain-data wire request and
   all indexes and capabilities held in linker-local state; the
   synchronous `fetch` is reclassified as host policy over an explicit
   link runtime (section 6).
7. The `:module/require` effect lowered onto the linker: two wait
   states, cursor-before-append, pair-scoped id correlation,
   installation as a scheduler child task, and export relocation by
   lift and lower with a portable module store and per-task lowering
   (section 7).
8. The module manifest, pinned to one canonical tree by content-addressed
   derivation records verified by re-lowering or explicitly trusted,
   and the manifest registry that replaces the in-memory module
   dictionary (section 8).
9. A fail-closed, proof-carrying name-authority policy for `dao.space`
   assertions, an entry criterion for M4 (section 8.2).
10. Eleven additional refusals (section 4.3).

### 1.2 What this document does not add

- No transport. `dao.stream` and its transports are the only medium.
- No peer routing, caching, or responder. `dao.jing.content` serves
  content over remote streams (`dao.stream.remote.md` section 8);
  `dao.jing.dht` finds it, behind that served boundary.
- No global registry, loader, or callback. Every handle, index, stream,
  and format record is an explicit argument or a value in VM state.
- No execution inside the linker. The linker fetches and verifies. Loading
  an image into a kernel and running a module body belong to the VM's
  scheduler.
- No change to `image-hash`, `register-hash`, `segment-key`, the opcode
  tables, or any kernel's step function.

## 2. Governing invariants

Every rule below derives from `datom.world.md`. The derivations are named
once here so later sections can cite them.

**I1. Code at rest is content in `dao.jing`.** An image, a row, a vector,
a manifest, a derivation record: each is an opaque value at its
`segment-key`. `dao.jing` assigns identity and retrieval and nothing else
(`dao.jing.md`, *Definition*). The linker never asks the store what a
value means.

**I2. Code in motion is a stream in `dao.stream`.** A request for code, a
payload answering it, a refusal, an absence, a timeout: each is a value
at a position in an append-only sequence. No function call crosses a host
boundary (`datom.world.md`, *Host Boundaries*). Consequently every value
on the link or content streams is plain data that survives the host
codec unchanged: no function, handle, or ambient state (`dao.stream.md`,
*Envelopes*).

**I3. Location is not identity.** Whether the content is in this process,
in a file, behind a socket, or on a DHT peer changes how bytes are
obtained, not what the linker checks (`dao.jing.md`, *Reads*). A local
composition and a remote one differ in the creation specification of the
streams they wire, and in nothing the linker can observe.

**I4. No operation waits.** The portable linker interface is a step that
returns what is true now, including "not yet" (`dao.stream.md`, OD-5). A
waiting `fetch` may wrap the step as host policy on hosts that can afford
it and is never the interface another interpreter is written against.

**I5. Verification is address-directed and identity-directed, and both
run.** The storage address and the format identity have different
preimages for two of the four formats and the same preimage for the other
two; in every case both checks execute, because an index entry is a claim
and never a proof: step 2 proves the retrieved value is the content at
that storage address, step 3 proves that content is the exact program
requested, and neither check subsumes the other (D14,
`yin.vm.debruijn.stack.md`). Every check is directed by the algorithm
the address or identity carries; the linker never re-mints under a
default and compares with `=` (Class 3 of
`dao.jing.call-site-classification.md`).

**I6. The VM never invokes a loader.** Absence of code is a park plus a
request emission; the loaded image arrives as a value in VM state
(`yin.vm.debruijn.stack.md` section 7.2). The linker is an observer on a
request stream, not a function the kernel calls.

**I7. The registry is a value.** `yin.vm.module` already keeps the
registry as a map in VM state. This document keeps that and changes what
the map holds: manifests, derivation records, portable slices, and
per-task bindings lowered from them, produced by an evaluation the
scheduler ran, never code that arrived on a stream.

**I8. Fail closed.** Every ambiguity the linker can detect (two manifests
for one name, an image whose derivation does not lead back to the
manifest's tree, an obligation whose profile differs) is a refusal, never
a choice.

## 3. Identities: three kinds, one address space

The linker moves between three kinds of name. Confusing them was the
defect B6 guarded against with D9, and the unification here adds a third.

| Kind             | Example                              | Owner            |
|------------------|--------------------------------------|------------------|
| storage address  | `:segment/blake3-...`                | `dao.jing`       |
| format identity  | H, R, a vector address, a root row id| the format       |
| module name      | `'my.lib`                            | name environment |

**Storage address.** `(jing/segment-key payload)` under the storage
encoder in force. Changes when the encoder changes (the CBOR landing,
`dao.jing.md` *Open items*); when it does, every identity-to-address
index is re-minted, while the contract-pinned identities (H and R) and
the code they name are unchanged. Carries its own hash algorithm.

**Format identity.** What the VM contract calls the code. There are two
families:

- *Contract-pinned identities* (H, R): computed by the format's own hash
  function over a descriptor that embeds the lowering contract version,
  pinned to SHA-256 by contract freeze, independent of the storage
  encoder. Their preimage differs from the storage address's preimage.
  For these two formats, storage address and VM identity are strictly
  decoupled (D9).
- *Storage-derived identities* (`:yin.ast/code`, `:yin.semantic/code`):
  UCF section 7.3.2 defines a segment's `:yin.code/hash` as the
  `segment-key` of its canonical instruction vector, and
  `yin.vm.code-as-tuples.md` section 4.1 defines a tree's address as its
  root row's id, itself a `segment-key`. Here the identity's preimage is
  the payload, so the identity is a storage address, carries a hash
  algorithm of its own, and changes when the storage encoder changes.
  **Strict decoupling therefore holds only for H and R today.** UCF
  accepts this ("UCF must not mint a second addressing scheme"); this
  document inherits it, verifies these identities under the algorithm
  they carry (section 4.1), and records the amendment that would pin
  them as a UCF and code-as-tuples decision, not a linker one (section
  12).

**Module name.** A symbol resolved through a name environment to a
(ns yin.vm.semantic
  "Linear CESK interpreter for executable datoms on DaoStream v2
   (`docs/design/yin.vm.semantic.md`).

   Code arrives as one `:yin.code/*` segment batch per program value. The
   loader (`load-image`, `vm-load-program`) checks §2.6 well-formedness,
   decodes the batch into an image — one vector indexed by pc of
   `[opcode & operands]` with every ref resolved to a pc integer — and
   stores it under `:code {segment-id image}`. Mnemonics in the datoms stay
   semantic and integers in the image stay mechanical (§2.4): the loader maps
   `:const` onto `:literal` and a `:call` carrying `:yin.code/tail?` onto
   `:tailcall`, and the dispatch below switches on the integers.

   The hot loop keeps the five registers — segment, pc, accumulator, operand
   stack, continuation — in `loop` locals and touches the record only at
   effect points and exits (§4.3). K is a vector of frames, innermost last:
   `{:type :return :segment :pc :env :stack-base}` for calls, and the wait
   entries the engine polls carry the same `{segment pc env stack k}` shape
   (§3.5). Park writes that map into `:parked`; resume reads it back. Nothing
   in either is a host object except values the program itself put in `env`
   or on the stack. A wait or ready entry is those registers plus `:reason`
   and resource ids — never a resolved stream handle and never a resume
   closure: `engine/check-wait-set` resolves handles from the store on every
   poll and the scheduler restores a woken entry explicitly, so the wait set
   of a blocked machine survives an EDN round-trip once any closure or
   continuation in its registers, a host type (D7, `yin.vm.values`), is
   encoded as the handoff demo encodes it. A segment id is stable:
   loading different code under an id that continuations already name is a
   load error; an identical reload is accepted.

   `step` is this loop with a fuel of one instruction, so single-stepping and
   full runs execute one transition code path. AST evaluation is not here:
   lowering (`yin.vm.linearize`, Phase 2) is composition-supplied at the
   observer boundary, so `eval` resumes loaded work and refuses an AST."
  (:require [dao.jing :as jing]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.telemetry :as telemetry]
            [yin.vm.ucf :as ucf]
            [yin.vm.values :as values]))


;; =============================================================================
;; SemanticVM Record
;; =============================================================================

(defrecord SemanticVM
  [blocked?       ; boolean, true if blocked
   bridge         ; explicit host-side FFI bridge state
   halted?        ; boolean, true when active continuation has completed
   k              ; continuation, a vector of frames (innermost last) or nil
   program        ; segment id of the last loaded batch
   control        ; {:segment id :pc n} or nil
   env            ; persistent lexical scope map
   stack          ; operand stack, a vector (part of control, §4.1)
   id-counter     ; integer counter for unique IDs
   parked         ; parked continuations map
   primitives     ; primitive operations map
   primitive-profiles ; portable primitive profile projection
   primitive-canonical-names ; declared aliases for identity reverse lookup
   modules        ; module registry value
   make-stream    ; host-supplied stream constructor, or nil
   call-capacity  ; declared capacity of the FFI pair
   ready-queue    ; vector of runnable continuations
   store          ; heap memory map
   value          ; last computed value
   wait-set       ; vector of continuations waiting on a transport
   telemetry      ; telemetry config map ({:stream … :vm-id …}) or nil
   telemetry-step ; telemetry snapshot counter
   telemetry-t    ; telemetry transaction counter
   telemetry-eid  ; telemetry entity-id seed, floored at datom/first-user-id
   vm-model       ; telemetry model keyword
   vm-id          ; telemetry instance id, minted by telemetry/install
   code           ; {segment-id image}; built once per segment, never written
   code-aliases   ; {address segment-id}; additive, one address one id (UCF §7.3.4)
   gc])           ; {:since :base :threshold :pinned}: heap reclamation


(declare semantic-restore scheduler-round)


;; =============================================================================
;; Register materialization
;; =============================================================================

(defn- put-registers
  "Write the five registers back into the record. This is the linear machine's
   cesk-return: the one seam where the running configuration becomes an
   observable value, and therefore where per-step trace emission (§3.6) will
   land when telemetry is designed. A nil segment means the machine has no
   control left, which is the halted state.

   An empty continuation is stored as nil rather than [] so that
   `engine/ready-for-ingress?`, which asks `(nil? (:k vm))`, sees a machine
   between evaluations."
  [vm seg pc val St E K]
  (assoc vm
         :control (when seg {:segment seg, :pc pc})
         :value val
         :stack (or St [])
         :env E
         :k (if (seq K) K nil)
         :halted? (and (not (:blocked? vm)) (nil? seg))))


(defn- response-wait-entry
  "The polling wait entry for a sent call awaiting its correlated response:
   the machine registers {segment pc env stack k} plus the call-out reader
   fields, in the shape `ffi/call-response-wait-entry` gives the walker."
  [seg pc env stack k call-id]
  {:segment seg, :pc pc, :env env, :stack stack, :k k,
   :call-id call-id,
   :reason :next,
   :cursor-ref {:type :cursor-ref, :id vm/call-out-cursor-key},
   :stream-id vm/call-out-stream-key})


(defn- carried-response-route
  "The response route a lowered retained writer carries -- its
   [response cursor key, response stream key] pair, the route data
   `yin.vm.ucf.remote` stamps on the lowered entry
   (`yin.vm.universal-continuation-format.md` S7.4.3). nil when the
   writer carries none: an ordinary retained call waits on the fixed
   local call-out keys."
  [entry]
  (when (and (contains? entry :response-cursor)
             (contains? entry :response-stream))
    [(:response-cursor entry) (:response-stream entry)]))


(defn semantic-restore
  "Restore a wait-set or ready-queue entry into machine registers.

   Two entry shapes carry more than registers:

   - A `:request-sent` entry is a writer whose retained FFI request has now
     been appended. The call starts waiting for its correlated response,
     exactly as an immediately-sent one does, so the entry is replaced by its
     response reader and the machine stays blocked. A writer carrying a
     lowered call's response route (`:response-cursor`, `:response-stream`)
     waits on that carried route; an ordinary one waits on the fixed local
     call-out keys.
   - A `:call-id` entry is a response reader. The woken value is a response
     envelope, so `ffi/call-result` unwraps it, checks correlation, and the
     parked call leaves `:parked` rather than accumulating."
  ([base entry] (semantic-restore base entry (:value entry)))
  ([base entry val]
   (if (:request-sent entry)
     (let [wait (response-wait-entry (:segment entry)
                                     (:pc entry)
                                     (:env entry)
                                     (:stack entry)
                                     (:k entry)
                                     (:call-id entry))
           wait (if-let [[cursor-key stream-key]
                         (carried-response-route entry)]
                  (assoc wait
                         :cursor-ref {:type :cursor-ref, :id cursor-key}
                         :stream-id stream-key)
                  wait)]
       (assoc base
              :wait-set (conj (vec (or (:wait-set base) [])) wait)
              :control nil
              :k nil
              :value :yin/blocked
              :blocked? true
              :halted? false))
     (let [call-id (:call-id entry)
           base (if call-id (update base :parked dissoc call-id) base)
           val (if call-id (ffi/call-result val call-id) val)]
       (put-registers base
                      (:segment entry)
                      (:pc entry)
                      val
                      (:stack entry)
                      (:env entry)
                      (:k entry))))))


(defn- call-park-entries
  "Wait-entry builders for an instruction whose effect may block. The
   continuation of an effect is the machine state after the instruction:
   {segment, pc+1, env, stack, k} plus the transport fields the engine
   fills in (§3.5). `St` is the operand stack with the instruction's operands
   already popped."
  [seg pc E St K]
  {:stream/put (fn [_state _effect result]
                 {:segment seg, :pc (inc pc), :env E, :stack St, :k K,
                  :reason :put,
                  :stream-id (:stream-id result)})
   :stream/next (fn [_state _effect result]
                  {:segment seg, :pc (inc pc), :env E, :stack St, :k K,
                   :reason :next,
                   :cursor-ref (:cursor-ref result),
                   :stream-id (:stream-id result)})
   :module/require (fn [_state _effect _result]
                     {:segment seg, :pc (inc pc), :env E, :stack St, :k K})})


(defn- run-effect
  "Materialize the registers, dispatch one effect through the engine, and
   normalize the answer for the loop: `{:continue [state value]}` to go on at
   pc+1 with `value` in the accumulator, or `{:stop vm}` when the effect
   parked and the machine is blocked.

   No `:restore-fn` crosses this boundary. A parked entry is pure data and
   the scheduler below resumes it explicitly, so nothing live is attached to
   the wait set (§1.1)."
  [vm seg pc val St E K effect park-entry-fns]
  (let [{:keys [state value blocked?]}
        (engine/handle-effect (put-registers vm seg pc val St E K)
                              effect
                              (when park-entry-fns
                                {:park-entry-fns park-entry-fns}))]
    (if blocked?
      {:stop (assoc state :control nil :k nil)}
      {:continue [state value]})))


(defn- apply-call
  "The §4.2 call transition, with the arguments already isolated from the
   operand stack. Returns `{:goto [seg pc val St E K vm image]}` to continue,
   `{:stop vm}` when the call blocked, or throws for a non-function.

   A tail call grows neither K nor St: the frame the caller would have pushed
   is the frame its own caller gave it. A primitive that yields an effect
   parks with the continuation after the call site."
  [code seg pc val St E K vm tail? f args]
  (case (engine/operator-kind vm f)
    :closure
    (let [c (values/payload f)
          E' (merge (:env c) (engine/bind-params (:params c) args))
          frame {:type :return, :segment seg, :pc (inc pc), :env E,
                 :stack-base (count St)}
          seg' (:segment c)]
      {:goto [seg' (:entry c) val St E' (if tail? K (conj K frame))
              vm (get code seg')]})
    :host-fn
    (let [result (apply f args)]
      (if (module/effect? result)
        (let [{:keys [state value blocked?]}
              (engine/handle-effect
                (put-registers (engine/check-callee-effect! vm f result)
                               seg pc val St E K)
                result
                {:park-entry-fns (call-park-entries seg pc E St K)})]
          (if blocked?
            {:stop (assoc state :control nil :k nil)}
            {:goto [seg (inc pc) value St E K state (get code seg)]}))
        {:goto [seg (inc pc) result St E K vm (get code seg)]}))
    ;; An abortive jump: the call site's St/E/K are dropped and the
    ;; captured registers take over, with the argument in val as the
    ;; capture instruction left it (19). The store is not a register.
    :continuation
    (let [v (engine/continuation-argument args)
          c (values/payload f)
(ns yin.vm.debruijn.register
  "R4 (docs/design/yin.vm.debruijn.register.md S5, S6 R4): the register
   execution kernel that interprets positional register images
   `{:bodies [...], :instructions [...]}` under contract version 4
   (the \"r2\" contract).

   The register kernel is a sibling to `yin.vm.debruijn.stack` (B3/B4) and
   `yin.vm.semantic`. It preserves B3's frame direction, closure capture,
   nil-fill and extra-argument rules, and B0 normalization. It executes
   only validator-approved images.

   State: `{:segment :hash :images :pc :frames :free-env :registers
            :continuation :store :blocked? :halted? :wait-set :ready-queue
            :parked :id-counter :value :make-stream :bridge :primitives
            :modules}`.

   Continuation transport: register continuations and stack continuations
   are not interchangeable (section 5.1). Payloads carry `:format
   :yin.debruijn.register`, `:hash R` of the code space at the time, and
   `:image`, the offset-table row their pc falls in (yin.vm.linker.md
   section 7.3, r6). A foreign format or an `:image` the table does not
   hold is refused with qualified `:continuation-format` diagnostics.
   Resume values must be plain data, refusing host exceptions with
   `:resume-value`.

   The kernel implements all 23 opcodes, Rule R's `:define` included,
   across the pure-program tier and the
   effects tier, connecting to `yin.vm.engine` via `register-restore` and
   per-site wait-entry builders."
  (:require [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-effects :as effects]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.values :as values]))


;; =============================================================================
;; State
;; =============================================================================

(defrecord DebruijnRegisterVM
  [segment      ; {:bodies [...], :instructions [...]}
   hash         ; R of segment (yin.vm.debruijn-register-code/register-hash)
   images       ; offset table: [[identity offset length] ...], base first
   pc           ; program counter into :instructions
   frames       ; positional lexical frame stack, outermost first
   free-env     ; initial free-name environment map
   registers    ; persistent vector for current body's register file
   continuation ; vector of return frames (innermost last)
   store        ; heap map
   blocked?     ; engine: boolean
   halted?      ; engine: boolean
   wait-set     ; engine: vector of wait entries
   ready-queue  ; engine: vector of woken entries
   parked       ; engine: map of parked continuations
   id-counter   ; engine: counter for gensym, stream, and park ids
   value        ; engine: last computed value
   make-stream  ; host stream constructor or nil
   bridge       ; host FFI bridge or nil
   primitives   ; primitive registry for :load-free
   modules      ; module registry for :load-free and effect dispatch
   store-of     ; the running body's module store address, or nil
   resources    ; private engine resources: the link pair
   origin       ; this task's origin tag for link ids
   ancestry     ; the modules installing on this task's install chain
   gc])         ; {:since :base :threshold :pinned}: heap reclamation


(def format-tag
  "The model tag every register payload carries as `:format`."
  effects/format-tag)


;; =============================================================================
;; Body & frame addressing
;; =============================================================================

(defn- body-of-pc
  "Return the declared body map covering instruction `pc` in `segment`."
  [{:keys [bodies]} pc]
  (some (fn [{:keys [start end] :as body}]
          (when (and (<= start pc) (<= pc end)) body))
        bodies))


(defn- frame-value
  "Frame 0 for `:load-bound` is the INNERMOST frame, read from the END of
   `:frames`. `frames` is outermost-first, so depth `d` is
   `(count frames) - 1 - d`."
  [frames depth position]
  (let [n (count frames)
        idx (- n 1 depth)]
    (when (or (neg? idx) (>= idx n))
      (throw (ex-info "load-bound: depth out of range"
                      {:rule :load-bound-depth, :depth depth,
                       :frame-count n})))
    (let [frame (nth frames idx)]
      (when (or (neg? position) (>= position (count frame)))
        (throw (ex-info "load-bound: position out of range"
                        {:rule :load-bound-position, :depth depth,
                         :position position, :frame-arity (count frame)})))
      (nth frame position))))


(defn- bind-positional
  "Positional parameter binding: missing arguments are nil-filled,
   extra arguments dropped."
  [arity args]
  (vec (take arity (concat args (repeat nil)))))


;; =============================================================================
;; Image loading & VM construction
;; =============================================================================

(defn- empty-segment?
  [segment]
  (or (nil? segment)
      (empty? segment)
      (and (map? segment) (empty? (:instructions segment)))))


(def ^:private empty-image {:bodies [], :instructions []})


(defn- install-image
  "Install `segment` as the one image. A non-empty image must pass the
   register validator. The offset table restarts at the base row
   `[R 0 n]`."
  [vm segment]
  (let [empty-seg? (empty-segment? segment)]
    (if empty-seg?
      (assoc vm
             :segment empty-image
             :hash nil
             :images [[(rcode/register-hash empty-image) 0 0]]
             :pc 0
             :frames []
             :registers []
             :continuation []
             :halted? true
             :blocked? false
             :value nil)
      (if-let [defect (rcode/register-image-defect segment)]
        (throw (ex-info (str "Invalid register image: " (:rule defect))
                        defect))
        (let [body0 (first (:bodies segment))
              reg-count (or (:registers body0) 0)
              r (rcode/register-hash segment)]
          (assoc vm
                 :segment segment
                 :hash r
                 :images [[r 0 (count (:instructions segment))]]
                 :pc 0
                 :frames []
                 :registers (vec (repeat reg-count nil))
                 :continuation []
                 :halted? false
                 :blocked? false
                 :value nil))))))


(defn load-image
  "Load `segment` (a register image `{:bodies [...], :instructions [...]}`)
   into `vm` as its one image: R is computed, the registers are reset to
   pc 0 with empty frames and continuation, and the machine is running
   unless the segment is empty. The offset table restarts at `[R 0 n]`, so
   a continuation parked under an earlier image names no row of it and
   `register-restore` refuses it.

   `contract` is required and compared with `vm/register-contract` first
   (`:contract-missing`, `:contract-mismatch`); a non-empty image must then
   pass the register validator, Rule R included. An empty segment admits
   no code and needs neither."
  [vm segment contract]
  (when-not (empty-segment? segment)
    (vm/check-contract! vm/register-contract contract))
  (install-image vm segment))


(defn relocate
  "Shift every `:pc`-kind operand of `inst` by `offset`."
  [offset inst]
  (reduce (fn [inst [i [_ kind]]]
            (if (= :pc kind) (update inst (inc i) + offset) inst))
          inst
          (map-indexed vector (get rcode/opcode-table (nth inst 0)))))


(defn attach-image
  "Extend the code space of `vm` with `image`, non-destructively
   (yin.vm.linker.md section 7.3, act 3): `image` is admitted alone as
   `load-image` admits it, relocated by the held instruction count, and
   appended, `:bodies` shifted likewise; one `[identity offset length]`
   row, identity R of `image`, is appended to the offset table; `:hash`
   becomes R of the concatenation. `:pc`, `:registers`, `:frames`,
   `:continuation`, and every other register are unchanged: appending
   moves no instruction already held. An image whose identity is already
   a row is not attached twice, and an empty image, which adds no code,
   is not attached at all."
  [vm image contract]
  (when-not (empty-segment? image)
    (vm/check-contract! vm/register-contract contract)
    (when-let [defect (rcode/register-image-defect image)]
      (throw (ex-info (str "Invalid register image: " (:rule defect))
                      defect))))
  (let [ident (when-not (empty-segment? image) (rcode/register-hash image))]
    (if (or (nil? ident) (some #(= ident (nth % 0)) (:images vm)))
      vm
      (let [held (:segment vm)
            offset (count (:instructions held))
            shift-body #(-> % (update :start + offset) (update :end + offset))
            combined {:bodies (into (:bodies held)
                                    (map shift-body)
                                    (:bodies image)),
                      :instructions (into (:instructions held)
                                          (map #(relocate offset %))
                                          (:instructions image))}]
        (assoc vm
               :segment combined
               :hash (rcode/register-hash combined)
               :images (conj (:images vm)
                             [ident offset
                              (count (:instructions image))]))))))


;; =============================================================================
;; Layouts: the ordered image list a code space is built from (UCF v2, 4.1)
;; =============================================================================

(def max-layout-length
  "2^52-1: the largest instruction-count sum a layout may name."
  4503599627370495)


(defn layout
  "The ordered identities of `vm`'s offset table: its current layout."
  [vm]
  (mapv #(nth % 0) (:images vm)))


(defn compose
  "The code space `images`, the component register images in layout
   order, build: `{:segment :hash :images}` where `:segment` is the
   relocated concatenation (bodies shifted likewise), `:images` the
   offset table derived by prefix sums, and `:hash` its R -- nil while
   no instruction is held, the empty base's convention.  A length sum
   beyond 2^52-1 refuses with `:layout-overflow`; nothing here validates
   an image."
  [images]
  (let [{:keys [bodies instructions rows]}
        (reduce (fn [{:keys [bodies instructions rows]} image]
                  (let [offset (count instructions)
                        n (count (:instructions image))]
                    (when (> (+ offset n) max-layout-length)
                      (throw (ex-info "Layout length overflow"
                                      {:rule :layout-overflow})))
                    {:bodies (into bodies
                                   (map #(-> %
                                             (update :start + offset)
                                             (update :end + offset)))
                                   (:bodies image))
                     :instructions (into instructions
                                         (map #(relocate offset %))
                                         (:instructions image))
                     :rows (conj rows [(rcode/register-hash image) offset n])}))
                {:bodies [] :instructions [] :rows []}
                images)
        segment {:bodies bodies :instructions instructions}]
    {:segment segment
     :hash (when (seq instructions) (rcode/register-hash segment))
     :images rows}))


(defn layout-images
  "The component image of each row of the offset table `images` over
   `segment`, relocated back to its own pc 0, in table order; nil

exec
/bin/zsh -lc "rg -n \"binding-mismatch|binding :named|binding :positional|S7\\.0|S7\\.1|S7\\.3|S7\\.4|S7\\.5|closed|safepoint|compile|continuation|register|named|positional\" docs/design/yin.vm.universal-continuation-format.md docs/design/yin.vm.debruijn.register.md docs/design/yin.vm.linker.md docs/design/yin.vm.semantic.md src/cljc/yin/vm/semantic.cljc src/cljc/yin/vm/debruijn/register.cljc" in /Users/sto/workspace/datomworld
 succeeded in 0ms:
docs/design/yin.vm.semantic.md:5:revision history, per the UCF design's S7.11 contract-revision-publication
docs/design/yin.vm.semantic.md:6:blocker (`yin.vm.universal-continuation-format.md`). Revision 1
docs/design/yin.vm.semantic.md:46:environment a map, the operand stack a vector, the continuation a vector of
docs/design/yin.vm.semantic.md:56:serialization limit the walker has today. A continuation shipped through a
docs/design/yin.vm.semantic.md:78:|                          | loop keeps registers in `loop` locals; the persistent VM record is the only      |
docs/design/yin.vm.semantic.md:108:a closure or continuation names.
docs/design/yin.vm.semantic.md:159:is the accumulator; `St` the operand stack; `K` the continuation of return
docs/design/yin.vm.semantic.md:161:says survives the log-structured reading; a register model would not.
docs/design/yin.vm.semantic.md:203:| `:park`                 | —                                             | `engine/park-continuation` with the current frame                                |
docs/design/yin.vm.semantic.md:205:| `:resume`               | `:yin.code/parked-id`                         | resume value in `val`; `engine/resume-continuation`                              |
docs/design/yin.vm.semantic.md:207:| `:current-continuation` | —                                             | `val ← {:type :reified-continuation :segment :pc :env :stack :k}`                |
docs/design/yin.vm.semantic.md:223:`:closure`→`:lambda`, `:branch-false`→`:branch`, `:current-continuation`→
docs/design/yin.vm.semantic.md:238:(`yin.vm.universal-continuation-format.md`) S7.11 names a
docs/design/yin.vm.semantic.md:280:sets, nil). Host functions never appear in code datoms; a primitive is named
docs/design/yin.vm.semantic.md:367:  continuations name segments by id, so an id must never come to mean
docs/design/yin.vm.semantic.md:406:the divergence register.
docs/design/yin.vm.semantic.md:416:| `:stream-put`    | `val ← value`                        | park (`full`)                 | —           | —                                                    | `closed`, `invalid-value`, `transport-error` are errors   |
docs/design/yin.vm.semantic.md:418:| `:stream-cursor` | `val ← cursor-ref`                   | —                             | —           | —                                                    | `invalid-anchor`, `closed`, `transport-error` are errors  |
docs/design/yin.vm.semantic.md:460:`engine/resume-continuation`, exactly as `ast-walker-restore` is;
docs/design/yin.vm.semantic.md:462:retry that ended `closed`/`cursor-mismatch`/… fails as the immediate
docs/design/yin.vm.semantic.md:501:  walker does. `bind-params` binds each param to its positional argument and
docs/design/yin.vm.semantic.md:505:  argument-to-named-parameter binding rule: arguments match `params` by
docs/design/yin.vm.semantic.md:511:- **K (continuation)**: a vector of frames, innermost last. Frame kinds:
docs/design/yin.vm.semantic.md:513:  effect-continuation frames the engine already defines
docs/design/yin.vm.semantic.md:591:**current-continuation**: $val \leftarrow \{seg, pc{+}1, E, St, K\}$
docs/design/yin.vm.semantic.md:598:keyword `:type` twice (node, then continuation). The linear machine does
docs/design/yin.vm.semantic.md:599:`push` (one vector `conj`) and increments `pc`. The "continuation" of an
docs/design/yin.vm.semantic.md:633:tiering, no recompile-at-boundary, no dirty flag: the engine's versioned
docs/design/yin.vm.semantic.md:634:compile cache exists for programs that grow by append, and a segment is
docs/design/yin.vm.semantic.md:668:| `:vm/park`, `:vm/current-continuation`                  | one instruction each                                               |
docs/design/yin.vm.semantic.md:693:node (2–3 lookups), allocate a continuation frame, and later read the frame
docs/design/yin.vm.semantic.md:716:| every non-hot transition | one `ASTWalkerVM` record                   | 0 (registers stay in `loop` locals until a park point) |
docs/design/yin.vm.semantic.md:732:`datoms->ast` allocated them, and each continuation frame is a fresh
docs/design/yin.vm.semantic.md:733:allocation on the nursery. On V8 and Dart, where keyword `case` compiles to
docs/design/yin.vm.semantic.md:746:path at all — it is paid once at load. The historic 1.0–1.1x register/stack
docs/design/yin.vm.semantic.md:761:  for verifying a shipped continuation against its code.
docs/design/yin.vm.semantic.md:769:> See [`docs/design/yin.vm.universal-continuation-format.md`](./yin.vm.universal-continuation-format.md) for the full protocol design, content-addressing mechanisms, and heterogeneous network migration invariants.
docs/design/yin.vm.semantic.md:794:**Against the target line.** The historic register and stack VMs are deleted,
docs/design/yin.vm.semantic.md:796:run. The historic register VM ran at 1/1.32 = 0.76x the walker and stack at
docs/design/yin.vm.semantic.md:808:clj -M:cljs -m shadow.cljs.devtools.cli compile vm-bench
docs/design/yin.vm.semantic.md:811:clj -M:clojuredart:cljd compile yin.register-bench-cljd
docs/design/yin.vm.semantic.md:812:dart run bin/register_bench_cljd.dart                   # both evaluators, n=50000
docs/design/yin.vm.semantic.md:825:JVM. Both hosts still clear the historic 0.76x register-VM line.
docs/design/yin.vm.semantic.md:836:[`yin.vm.divergence-register.md`](./yin.vm.divergence-register.md#the-semantic-vm-against-the-ast-walker).
docs/design/yin.vm.linker.md:23:identity rules of `yin.vm.universal-continuation-format.md` (UCF) section
docs/design/yin.vm.linker.md:34:- `docs/design/yin.vm.universal-continuation-format.md` - code identity,
docs/design/yin.vm.linker.md:35:  contract stamp, safepoints, pending waits, primitive profiles
docs/design/yin.vm.linker.md:52:| `:semantic`   | `yin.vm.semantic`         | positional instruction vector|
docs/design/yin.vm.linker.md:54:| `:register`   | `yin.vm.debruijn.register`| de Bruijn register image (R) |
docs/design/yin.vm.linker.md:67:the same six steps for a stack image and a register image. The namespace
docs/design/yin.vm.linker.md:124:9. A fail-closed, proof-carrying name-authority policy for `dao.space`
docs/design/yin.vm.linker.md:139:- No change to `image-hash`, `register-hash`, `segment-key`, the opcode
docs/design/yin.vm.linker.md:144:Every rule below derives from `datom.world.md`. The derivations are named
docs/design/yin.vm.linker.md:195:**I8. Fail closed.** Every ambiguity the linker can detect (two manifests
docs/design/yin.vm.linker.md:362:values; and the closure check (step 5) precedes return so an unclosed
docs/design/yin.vm.linker.md:398:   the request's shape against the closed key set of section 6.3
docs/design/yin.vm.linker.md:453:   vector, over the vector; for a register image, including the
docs/design/yin.vm.linker.md:555:     closed-image case and remains supported.
docs/design/yin.vm.linker.md:587:| `:invalid-request`      | 0      | a request outside the closed key set of  |
docs/design/yin.vm.linker.md:629:| `:binding-mismatch`     | 7.3    | a positional closure marker lowered into |
docs/design/yin.vm.linker.md:630:|                         |        | a named kernel, or the reverse           |
docs/design/yin.vm.linker.md:702:Payload: the canonical positional instruction tuple vector (UCF section
docs/design/yin.vm.linker.md:750:### 5.4 `:yin.debruijn.register` (the register VM)
docs/design/yin.vm.linker.md:753:{:format              :yin.debruijn.register
docs/design/yin.vm.linker.md:755: :identity-fn         yin.vm.debruijn-register-code/register-hash
docs/design/yin.vm.linker.md:757:                        (= R (yin.vm.debruijn-register-code/register-hash
docs/design/yin.vm.linker.md:759: :row-defect-fn       yin.vm.debruijn-register-code/register-image-defect
docs/design/yin.vm.linker.md:760: :validate-fn         yin.vm.debruijn-register-code/register-image-defect
docs/design/yin.vm.linker.md:761: :obligations-fn      register-free-names
docs/design/yin.vm.linker.md:762: :definitions-fn      register-definitions
docs/design/yin.vm.linker.md:763: :applications-fn     register-application-sites
docs/design/yin.vm.linker.md:772:Loading is `yin.vm.debruijn.register/load-image`, which requires the
docs/design/yin.vm.linker.md:884:A `request` is plain data and nothing else. Its closed key set is
docs/design/yin.vm.linker.md:899:;; by bare identity: B6's closed-image path, no manifest
docs/design/yin.vm.linker.md:1003:`ex-info`. The registry was populated by `register-module` at wiring
docs/design/yin.vm.linker.md:1069:   registers, resource ids, and plain data only; no handle and no
docs/design/yin.vm.linker.md:1192:  a stack or register closure carries a body pc into the child's held
docs/design/yin.vm.linker.md:1196:  child-to-parent boundary the way a continuation crosses any boundary:
docs/design/yin.vm.linker.md:1226:     restore is a parked task whose registers must survive, so each
docs/design/yin.vm.linker.md:1236:     - *stack* and *register*: the image is relocated by the current
docs/design/yin.vm.linker.md:1237:       held length and appended to `:segment` (and, for the register
docs/design/yin.vm.linker.md:1243:       `:frames`, `:continuation` (every `:return-pc` and `:frames`
docs/design/yin.vm.linker.md:1244:       in it), and `:registers` are unchanged, because appending never
docs/design/yin.vm.linker.md:1249:       continuation that recorded it would be refused by the first
docs/design/yin.vm.linker.md:1258:       current node, environment, and continuation are unchanged.
docs/design/yin.vm.linker.md:1259:     `yin.repl`'s `append-stack-image` and `append-register-image`
docs/design/yin.vm.linker.md:1269:     `stack-restore` and `register-restore` today refuse any entry
docs/design/yin.vm.linker.md:1278:     and the entry restores registers and never assigns `:segment`:
docs/design/yin.vm.linker.md:1281:     what they named. A lifted continuation (act 1) carries its pcs
docs/design/yin.vm.linker.md:1291:     The register VM's call frame today carries the caller's whole
docs/design/yin.vm.linker.md:1301:     register frame drops `:segment` and `:hash` -- its absolute
docs/design/yin.vm.linker.md:1303:     restores pc, frames, registers, and continuation only. The
docs/design/yin.vm.linker.md:1331:  UCF section 7.5.1 defines the closure marker for the named semantic
docs/design/yin.vm.linker.md:1332:  VM. The de Bruijn kernels are positional and nameless, so the marker
docs/design/yin.vm.linker.md:1338:  ;; named (:yin.ast/code, :yin.semantic/code)
docs/design/yin.vm.linker.md:1339:  {:yin.k/tag :yin.k/closure :yin.k/binding :named
docs/design/yin.vm.linker.md:1344:  ;; positional (:yin.debruijn.code, :yin.debruijn.register)
docs/design/yin.vm.linker.md:1345:  {:yin.k/tag :yin.k/closure :yin.k/binding :positional
docs/design/yin.vm.linker.md:1364:  | stack / register `{:type :closure    | offset-table entry with       |
docs/design/yin.vm.linker.md:1373:  | `:named`,      | `local'` = parent alias column at `addr`; `:entry` |
docs/design/yin.vm.linker.md:1376:  | `:named`,      | `:body` = the body slot of the parent's row at     |
docs/design/yin.vm.linker.md:1380:  | `:positional`  | `off'` = parent offset-table entry for `identity`; |
docs/design/yin.vm.linker.md:1420:  A `:named` marker lowers only into a named kernel and a `:positional`
docs/design/yin.vm.linker.md:1421:  marker only into a positional kernel of the same `:format`; the
docs/design/yin.vm.linker.md:1422:  reverse is `:binding-mismatch`. This never arises within one install,
docs/design/yin.vm.linker.md:1425:  elsewhere fails closed rather than being reinterpreted. Converting a
docs/design/yin.vm.linker.md:1484:    empties the continuation -- the closure's body was the whole
docs/design/yin.vm.linker.md:1518:  11, item 12; r8's citation of a "UCF 4.1" named no such section,
docs/design/yin.vm.linker.md:1523:  **Resource lowering (r9).** The continuation format's own decode
docs/design/yin.vm.linker.md:1563:  with a wrong or missing seal fails closed with
docs/design/yin.vm.linker.md:1565:  named: the effect boundary, not the store instruction, was the
docs/design/yin.vm.linker.md:1595:  positional kernel, appended to by `attach-image`; `yin.repl` already
docs/design/yin.vm.linker.md:1636:A manifest is content: one map, materialized in `dao.jing`, named by its
docs/design/yin.vm.linker.md:1645:                          :yin.debruijn.register "r2"}
docs/design/yin.vm.linker.md:1649:                          :yin.debruijn.register :segment/...} ; record
docs/design/yin.vm.linker.md:1686:    contract `"b2"` for the stack image, the register contract `"r2"`
docs/design/yin.vm.linker.md:1687:    for the register image. These are the Rule R revisions; a manifest
docs/design/yin.vm.linker.md:1702:  kernel compares `"v3"` with `"v3"`; the tree's `"v3"` and the register
docs/design/yin.vm.linker.md:1705:  register link of a valid manifest; that was a defect.
docs/design/yin.vm.linker.md:1717:    `:yin.debruijn.code`, the register lowering for
docs/design/yin.vm.linker.md:1718:    `:yin.debruijn.register`, each pinned by its own
docs/design/yin.vm.linker.md:1767:**Fail-closed assertion policy.** This policy is an entry criterion for
docs/design/yin.vm.linker.md:1910:| `register-module r name fns` | `register-host-module r name fns profiles`  |
docs/design/yin.vm.linker.md:1918:| `register-effect-handler`    | unchanged                                   |
docs/design/yin.vm.linker.md:1919:| `stream-module`              | a host module; `register-stream-module`     |
docs/design/yin.vm.linker.md:1920:|                              | calls `register-host-module`                |
docs/design/yin.vm.linker.md:1924:**`register-host-module` enforces UCF profile classes.** Every binding
docs/design/yin.vm.linker.md:1947:- *The composition* is responsible for every host module it registers:
docs/design/yin.vm.linker.md:1952:  composition should register the fewest it can.
docs/design/yin.vm.linker.md:1982:predecessor namespace is renamed, not aliased.
docs/design/yin.vm.linker.md:1997:namespace (`yin.vm.debruijn.stack.md`, `yin.vm.debruijn.register.md`,
docs/design/yin.vm.linker.md:2053:format records; `register-host-module` with profile enforcement;
docs/design/yin.vm.linker.md:2063:same-named primitive of different profile at the receiver is
docs/design/yin.vm.linker.md:2079:with `:segment` never assigned from the entry; a register call in
docs/design/yin.vm.linker.md:2107:matches no row; `(vm :stack)` and `(vm :register)` in `yin.repl` link
docs/design/yin.vm.linker.md:2115:`register-host-module` from the first commit of M4, so every existing
docs/design/yin.vm.linker.md:2122:Renamed:  src/cljc/yin/vm/debruijn_linker.cljc
docs/design/yin.vm.linker.md:2139:          src/cljc/yin/vm/debruijn_register_effects.cljc
docs/design/yin.vm.linker.md:2140:                                  (the register frame check drops
docs/design/yin.vm.linker.md:2152:          debruijn/stack.cljc, debruijn/register.cljc
docs/design/yin.vm.linker.md:2154:             identities on the positional kernels; row index
docs/design/yin.vm.linker.md:2156:             register frame drops :segment and :hash; store
docs/design/yin.vm.linker.md:2160:          docs/design/yin.vm.universal-continuation-format.md
docs/design/yin.vm.linker.md:2170:          yin.vm.debruijn-code, yin.vm.debruijn-register-code
docs/design/yin.vm.linker.md:2172:          image-hash, register-hash, segment-key, the opcode
docs/design/yin.vm.linker.md:2174:          changes its decode, dispatch, or register model; the
docs/design/yin.vm.linker.md:2182:`semantic-format`, `stack-format`, `register-format`, `manifest-format`,
docs/design/yin.vm.linker.md:2231:   identity is a storage address, and under every registered algorithm.
docs/design/yin.vm.linker.md:2249:    `:install` safepoints, section 7.4.3 their pending variants, section
docs/design/yin.vm.linker.md:2279:    register closure exported from a child whose image was appended at
docs/design/yin.vm.linker.md:2283:    a non-empty stack, frames, and continuation resumes after
docs/design/yin.vm.linker.md:2285:    restore identities, and every register the loader would have reset
docs/design/yin.vm.linker.md:2289:    register backend, an image attached by a nested require while a
docs/design/yin.vm.linker.md:2293:    continuation lifted after an attach and lowered into a fresh
docs/design/yin.vm.linker.md:2307:    `:foreign-image`; a positional marker offered to a named kernel is
docs/design/yin.vm.linker.md:2308:    `:binding-mismatch`.
docs/design/yin.vm.linker.md:2432:The round-10 review closed the citation and file-box item and left
docs/design/yin.vm.linker.md:2458:The round-9 review closed the r8 resource-lowering item and raised
docs/design/yin.vm.linker.md:2476:|                                   | missing seal fails closed with    |
docs/design/yin.vm.linker.md:2485:| P2: "UCF 4.1" named no section;   | Agreed; citation corrected. UCF   |
docs/design/yin.vm.linker.md:2497:The round-8 review closed the walker-provenance item (no action);
docs/design/yin.vm.linker.md:2527:The round-7 review closed the grow-only-returns item (no action)
docs/design/yin.vm.linker.md:2545:|                                   | continuation clears the active    |
docs/design/yin.vm.linker.md:2566:The round-6 review closed the dominance and scanner item (no
docs/design/yin.vm.linker.md:2575:| a nested require mid-call         | value. The register frame drops   |
docs/design/yin.vm.linker.md:2577:|                                   | return restores registers only;   |
docs/design/yin.vm.linker.md:2614:The round-5 review closed the wait-entry id and duplicate-authority
docs/design/yin.vm.linker.md:2641:| were published to every waiter    | write registers and never         |
docs/design/yin.vm.linker.md:2642:|                                   | `:segment`; lifted continuations  |
docs/design/yin.vm.linker.md:2663:The round-4 review closed round-3 item 5 and left four items partial.
docs/design/yin.vm.linker.md:2682:| Loaders reset registers; walker   | Agreed. `attach-image` per kernel |
docs/design/yin.vm.linker.md:2684:|                                   | pc, stack, frames, continuation,  |
docs/design/yin.vm.linker.md:2702:The round-3 review closed round-2 items 1, 3, 5, 7, and 10 and left four
docs/design/yin.vm.linker.md:2718:| and the named/positional mapping  | image from the offset table or    |
docs/design/yin.vm.linker.md:2723:|                                   | `:yin.k/binding :named` and       |
docs/design/yin.vm.linker.md:2724:|                                   | `:positional` variants with lift  |
docs/design/yin.vm.linker.md:2726:|                                   | `:binding-mismatch` (7.3).        |
docs/design/yin.vm.linker.md:2740:| stack and register links          | schema` versions the container;   |
docs/design/yin.vm.linker.md:2749:closed and marked seven items partial plus one ordering defect. Each,
docs/design/yin.vm.linker.md:2792:| Host modules implied a sandbox    | Agreed. The host module is named  |
docs/design/yin.vm.linker.md:2847:|                                  | trusted, named; swapped-image tests |
docs/design/yin.vm.linker.md:2851:| P1 authority open at M4          | Agreed. Fail-closed assertion       |
docs/design/yin.vm.linker.md:2856:|                                  | open and is named as such (12).     |
docs/design/yin.vm.debruijn.register.md:1:# yin.vm de Bruijn register VM
docs/design/yin.vm.debruijn.register.md:5:This document specifies a register execution path that is a peer of the
docs/design/yin.vm.debruijn.register.md:7:encoding of the named semantic tuples; neither is derived from the other.
docs/design/yin.vm.debruijn.register.md:8:It does not replace the named datoms, the stack image, the stack VM, or the
docs/design/yin.vm.debruijn.register.md:10:register lowerer and validator. The register VM kernel is a committed later
docs/design/yin.vm.debruijn.register.md:16:The objective is a deterministic register image derived from the de Bruijn
docs/design/yin.vm.debruijn.register.md:17:encoding of the named `:yin/*` semantic tuples, at the same level as the
docs/design/yin.vm.debruijn.register.md:18:`:yin.debruijn.code/*` stack image. The register lowerer consumes the
docs/design/yin.vm.debruijn.register.md:19:resolved tuples and emits a register image; it never reads, rewrites, or
docs/design/yin.vm.debruijn.register.md:21:addressable by its existing H, and the register image is independently
docs/design/yin.vm.debruijn.register.md:27:compiled from another. That pattern is applied here one level down, at the
docs/design/yin.vm.debruijn.register.md:33:different VMs: one truth, many interpretations. The named datoms are this
docs/design/yin.vm.debruijn.register.md:34:project's single source of truth. The named VM, the stack VM, and the
docs/design/yin.vm.debruijn.register.md:35:register VM are not competing implementations with one canonical and the
docs/design/yin.vm.debruijn.register.md:55:stage boundary, in which several peer executable formats (named, stack,
docs/design/yin.vm.debruijn.register.md:56:register) share one upstream de Bruijn encoding and each backend is an
docs/design/yin.vm.debruijn.register.md:58:evaluator. The register path also targets an alpha-invariant, content-
docs/design/yin.vm.debruijn.register.md:60:walker. A lowerer alone proves the register format is well defined. Only a
docs/design/yin.vm.debruijn.register.md:70:1. A register image is derived data, derived from the resolved tuples. The
docs/design/yin.vm.debruijn.register.md:71:   stack image is never consumed, rewritten, or invalidated by the register
docs/design/yin.vm.debruijn.register.md:76:   H identifies the exact stack bytes; R identifies the exact register bytes
docs/design/yin.vm.debruijn.register.md:77:   that a register-capable host can run. Neither preimage mentions the other.
docs/design/yin.vm.debruijn.register.md:78:4. Equal R implies equal register bytes and descriptor contract. It does not
docs/design/yin.vm.debruijn.register.md:80:5. Register execution must agree with the named semantic VM, and therefore
docs/design/yin.vm.debruijn.register.md:83:6. All register allocation choices are data-derived and deterministic across
docs/design/yin.vm.debruijn.register.md:87:register path adds no projection fingerprint and no global registry. B2
docs/design/yin.vm.debruijn.register.md:93:The register descriptor is `:yin.debruijn.register/*`. Its `register-hash`
docs/design/yin.vm.debruijn.register.md:94:function, R, hashes the register descriptor hash and the canonical
docs/design/yin.vm.debruijn.register.md:95:positional register vector, exactly as B1's `image-hash` hashes the stack
docs/design/yin.vm.debruijn.register.md:97:to fetch, verify, cache, or execute register bytes. A linker request must
docs/design/yin.vm.debruijn.register.md:99:`{:format :yin.debruijn.register :hash R}`.
docs/design/yin.vm.debruijn.register.md:102:register vector carries `:load-bound` depth and position operands and
docs/design/yin.vm.debruijn.register.md:112:This is not a second semantic answer to alpha-equivalence. A register
docs/design/yin.vm.debruijn.register.md:117:The pairing of an R with the H of the same named root is a composition
docs/design/yin.vm.debruijn.register.md:119:both, never inside either preimage. A receiver verifies a fetched register
docs/design/yin.vm.debruijn.register.md:124:The register projection is a peer of the stack projection. Both branch from
docs/design/yin.vm.debruijn.register.md:125:the resolved tuples, the de Bruijn encoding of the named AST datoms:
docs/design/yin.vm.debruijn.register.md:127:    Source -> Yang -> named AST datoms (:yin/*)
docs/design/yin.vm.debruijn.register.md:129:                         | \-> named semantic image (:yin.code/*)
docs/design/yin.vm.debruijn.register.md:135:                                   +---> register image (R)   R1
docs/design/yin.vm.debruijn.register.md:137:The register edge is not chained after the stack image. The named stream,
docs/design/yin.vm.debruijn.register.md:138:the resolved tuples, the stack image, and the register image coexist and
docs/design/yin.vm.debruijn.register.md:144:    (resolve named-datom-stream)      -> resolved-tuple-stream
docs/design/yin.vm.debruijn.register.md:146:    (lower-register resolved-stream)  -> register-image-stream  (R)
docs/design/yin.vm.debruijn.register.md:148:Each register output item carries a register descriptor reference, the
docs/design/yin.vm.debruijn.register.md:149:canonical register vector, R, and a diagnostic side table. The implementation
docs/design/yin.vm.debruijn.register.md:153:The register stage refuses malformed resolved tuples, unsupported node
docs/design/yin.vm.debruijn.register.md:156:same named root to the stack path after receiving that outcome.
docs/design/yin.vm.debruijn.register.md:161:definition is normative here. In summary: the named `:yin/*` datoms form a
docs/design/yin.vm.debruijn.register.md:168:table makes to the named instruction table:
docs/design/yin.vm.debruijn.register.md:201:  and the register image, identified by H and R.
docs/design/yin.vm.debruijn.register.md:204:  that rule for the register path. A hash of the resolved tuples would be a
docs/design/yin.vm.debruijn.register.md:207:  pure function of the named datoms and `resolve-name`. Persisting them or
docs/design/yin.vm.debruijn.register.md:208:  giving them an identity would duplicate authority over facts the named
docs/design/yin.vm.debruijn.register.md:215:  invertible to the named datoms, but that property is used only by tests.
docs/design/yin.vm.debruijn.register.md:225:from named datoms to a stack image, is the composition `lower-stack` after
docs/design/yin.vm.debruijn.register.md:235:reproduces the named linearizer's flattening walk itself: the same
docs/design/yin.vm.debruijn.register.md:237:expanded positionally exactly as `lower` expands them (a resolved record
docs/design/yin.vm.debruijn.register.md:245:register lowerer once it exists, not as B2's own coupling mechanism:
docs/design/yin.vm.debruijn.register.md:247:    addresses(B2 stack image) = addresses(register image)
docs/design/yin.vm.debruijn.register.md:252:expanded positionally: the main sequence first, then each out-of-line body
docs/design/yin.vm.debruijn.register.md:263:    | Namespace            | :yin.debruijn.register/*                      |
docs/design/yin.vm.debruijn.register.md:266:    | Register image       | Body ranges plus positional instructions      |
docs/design/yin.vm.debruijn.register.md:272:    | Validation           | Shape, targets, body scope, register bounds,  |
docs/design/yin.vm.debruijn.register.md:274:    | Lift                 | Register image to named semantics             |
docs/design/yin.vm.debruijn.register.md:277:The register descriptor has its own validator because register operands and
docs/design/yin.vm.debruijn.register.md:278:body register counts are new facts. It reuses B1's public data rules and
docs/design/yin.vm.debruijn.register.md:285:    R = sha256(register-descriptor-hash || canonical-register-vector)
docs/design/yin.vm.debruijn.register.md:297:The lift goes from the register image to the named `:yin.code/*` image, so
docs/design/yin.vm.debruijn.register.md:299:lifting" holds for register images too. It is a function of the register
docs/design/yin.vm.debruijn.register.md:305:lowering the lifted result reproduces the original register image byte for
docs/design/yin.vm.debruijn.register.md:309:## 4. Resolved-tuples-to-register lowering
docs/design/yin.vm.debruijn.register.md:316:anything. It does not accept named datoms directly, does not rerun name
docs/design/yin.vm.debruijn.register.md:319:producer of `:yin.debruijn.register/*` register images.
docs/design/yin.vm.debruijn.register.md:324:is known from its record. The walk is the named linearizer's order,
docs/design/yin.vm.debruijn.register.md:327:positionally: a resolved record referenced twice is lowered twice, at two
docs/design/yin.vm.debruijn.register.md:330:already validated against it. The lowerer's own checks are register bounds,
docs/design/yin.vm.debruijn.register.md:335:Each body has two disjoint register banks:
docs/design/yin.vm.debruijn.register.md:342:frame's slot a register number in the current body. Thus frame 0 remains the
docs/design/yin.vm.debruijn.register.md:349:register count is the number of local registers plus the maximum
docs/design/yin.vm.debruijn.register.md:350:simultaneously live temporary registers. If a target requires a bounded
docs/design/yin.vm.debruijn.register.md:351:register file, deterministic spill slots are allocated after the last
docs/design/yin.vm.debruijn.register.md:352:register using the same order; spilling is a later kernel concern and is not
docs/design/yin.vm.debruijn.register.md:358:compiler would have: there are no predecessor traversals to order, and both
docs/design/yin.vm.debruijn.register.md:359:arms of an `if` write the expression's one destination register. The remaining
docs/design/yin.vm.debruijn.register.md:363:definition, lowest available register, and lowest virtual-id tie breaks.
docs/design/yin.vm.debruijn.register.md:365:register and emitted in a fixed cycle-breaking order. No host map iteration
docs/design/yin.vm.debruijn.register.md:366:order may affect an emitted vector. R1 pins golden register vectors and R
docs/design/yin.vm.debruijn.register.md:371:The register instruction set is a positional form of the B1 table:
docs/design/yin.vm.debruijn.register.md:392:    :current-continuation [op rd live]
docs/design/yin.vm.debruijn.register.md:417:    :current-continuation
docs/design/yin.vm.debruijn.register.md:427:`:yin.debruijn.register/*` namespace. R2 adds `:str` and `:kw` operand-kind
docs/design/yin.vm.debruijn.register.md:432:The named AST permits an omitted gensym prefix only before defaults are
docs/design/yin.vm.debruijn.register.md:436:There is no `:push`: operands are named by register, so the stack path's
docs/design/yin.vm.debruijn.register.md:439:explicit function register, an ordered argument-register vector, a
docs/design/yin.vm.debruijn.register.md:440:destination register, the tail flag copied from `:yin/tail?`, and the live
docs/design/yin.vm.debruijn.register.md:441:set of section 4.5. `:branch-false`, `:return`, and `:halt` name registers
docs/design/yin.vm.debruijn.register.md:448:name their input registers explicitly. Every value-producing instruction
docs/design/yin.vm.debruijn.register.md:449:names `rd`. `:store-put` preserves the named operation's exact scalar value
docs/design/yin.vm.debruijn.register.md:451:control to an existing parked continuation and never returns to its own
docs/design/yin.vm.debruijn.register.md:452:successor, so it has no destination register.
docs/design/yin.vm.debruijn.register.md:456:`rs` and emits `:define` instead of a call; the kernel writes register
docs/design/yin.vm.debruijn.register.md:466:Rule R moved the register contract to `contract-version` 4, the "r2"
docs/design/yin.vm.debruijn.register.md:467:contract (`yin.vm/register-contract`). `load-image [vm segment contract]`
docs/design/yin.vm.debruijn.register.md:476:operands are the section 4.5 continuation boundary. They are present only
docs/design/yin.vm.debruijn.register.md:477:where an instruction can suspend or reify the continuation. A normal
docs/design/yin.vm.debruijn.register.md:481:### 4.5 Live sets at continuation boundaries
docs/design/yin.vm.debruijn.register.md:484:`:current-continuation` can save a register body's state. Some registers
docs/design/yin.vm.debruijn.register.md:485:are already dead at each boundary. A saved continuation carries only live
docs/design/yin.vm.debruijn.register.md:486:registers, never dead ones: retaining the whole file would retain dead
docs/design/yin.vm.debruijn.register.md:491:The in-band `live` operand is the set of registers of the current body
docs/design/yin.vm.debruijn.register.md:496:verification. The named datoms remain the authority, and every receiver
docs/design/yin.vm.debruijn.register.md:499:Register numbering: `live` names physical registers by the same indices
docs/design/yin.vm.debruijn.register.md:502:space. Frames captured for `:load-bound` and closures are not registers
docs/design/yin.vm.debruijn.register.md:507:body ranges, `body-liveness`, exported by the register code namespace and
docs/design/yin.vm.debruijn.register.md:525:      :current-continuation rd _                   def {rd}
docs/design/yin.vm.debruijn.register.md:551:Every set is a sorted set of register indices during computation and is
docs/design/yin.vm.debruijn.register.md:568:the existing structural and register-bounds rules and using the defect
docs/design/yin.vm.debruijn.register.md:573:2. `:live-bounds`: every index is below the body's declared register
docs/design/yin.vm.debruijn.register.md:574:   count, the register-bounds rule extended to this operand.
docs/design/yin.vm.debruijn.register.md:593:target-register-passing walk; it does not introduce an effect AST or a
docs/design/yin.vm.debruijn.register.md:611:    :vm/current-continuation      [:current-continuation rd live]
docs/design/yin.vm.debruijn.register.md:617:facts from the resolved record. They are never looked up in the register
docs/design/yin.vm.debruijn.register.md:618:file. Child expressions are assigned temporary registers in fixed child
docs/design/yin.vm.debruijn.register.md:631:`:current-continuation`, and `:park`. No allocator free-list state enters
docs/design/yin.vm.debruijn.register.md:633:for the same reasons as R1: fixed traversal, lowest-register allocation,
docs/design/yin.vm.debruijn.register.md:638:R1 may produce and validate register bytecode without a register kernel. This
docs/design/yin.vm.debruijn.register.md:642:R4 adds `yin.vm.debruijn.register` as a sibling kernel. It owns explicit state
docs/design/yin.vm.debruijn.register.md:643:`{:image :pc :registers :frames :free-env :continuation :store :status
docs/design/yin.vm.debruijn.register.md:645:continuation contracts as B3, including the engine seam B4 implements
docs/design/yin.vm.debruijn.register.md:649:The register kernel must execute only validator-approved images. It must
docs/design/yin.vm.debruijn.register.md:651:rules, and B0 normalization. Register continuations and stack
docs/design/yin.vm.debruijn.register.md:652:continuations are not interchangeable; section 5.1 states the rule.
docs/design/yin.vm.debruijn.register.md:656:A parked continuation resumes only under the VM model, and the image
docs/design/yin.vm.debruijn.register.md:658:gap. Every parked record and reified continuation of either VM carries
docs/design/yin.vm.debruijn.register.md:659:its model and image identity in its register payload, `{:format
docs/design/yin.vm.debruijn.register.md:660::yin.debruijn.code :hash H}` or `{:format :yin.debruijn.register :hash R}`
docs/design/yin.vm.debruijn.register.md:662:image does not match refuses with a qualified `:continuation-format`
docs/design/yin.vm.debruijn.register.md:664:those keys; they are register payload under the section 3 rule of
docs/design/yin.vm.debruijn.register.md:668:wrong shape. A register park site and a stack park site for the same
docs/design/yin.vm.debruijn.register.md:669:program point have no pc correspondence, a register file is not an
docs/design/yin.vm.debruijn.register.md:670:operand stack, and each is a positional artifact of one lowering.
docs/design/yin.vm.debruijn.register.md:671:Translating one bytecode continuation into another is decompilation, and
docs/design/yin.vm.debruijn.register.md:676:whatever model it prefers. The cross-model form of a continuation is
docs/design/yin.vm.debruijn.register.md:677:therefore a continuation over the source, not over either image: pending
docs/design/yin.vm.debruijn.register.md:678:frames named by resolved-tuple occurrence, plus the values those frames
docs/design/yin.vm.debruijn.register.md:680:side tables, so each VM can supply a lift-out from its own continuation
docs/design/yin.vm.debruijn.register.md:682:exists. That form is the universal continuation format the stack design
docs/design/yin.vm.debruijn.register.md:688:observable at that boundary: a register continuation carries only live
docs/design/yin.vm.debruijn.register.md:689:registers, and a dead register is by definition never read again, so a
docs/design/yin.vm.debruijn.register.md:694:continuation is same-model only: a register continuation travels to a
docs/design/yin.vm.debruijn.register.md:695:host with a register kernel, a stack continuation to a host with the
docs/design/yin.vm.debruijn.register.md:698:of lossy lifts between two positional formats that the third VM would
docs/design/yin.vm.debruijn.register.md:705:`yin.vm.debruijn-register-effects`; R4 must reuse them rather than invent
docs/design/yin.vm.debruijn.register.md:710:    effect-descriptor  instruction registers -> effect | nil
docs/design/yin.vm.debruijn.register.md:711:    continuation-payload runtime instruction -> payload
docs/design/yin.vm.debruijn.register.md:712:    continuation-defect payload -> defect | nil
docs/design/yin.vm.debruijn.register.md:716:lexical frames, register vector, and return frames. These functions read no
docs/design/yin.vm.debruijn.register.md:721:The register kernel will interpret the R2 instructions as follows:
docs/design/yin.vm.debruijn.register.md:738:    :current-continuation  produce a reified continuation value
docs/design/yin.vm.debruijn.register.md:739:    :park              engine/park-continuation
docs/design/yin.vm.debruijn.register.md:740:    :resume            engine/resume-continuation
docs/design/yin.vm.debruijn.register.md:753:current-body registers and delivers the resumed value through the normal
docs/design/yin.vm.debruijn.register.md:762:error register and no raw exception in serialized continuation data.
docs/design/yin.vm.debruijn.register.md:764:#### 5.2.2 Sparse continuation representation
docs/design/yin.vm.debruijn.register.md:766:The runtime register file is a vector indexed by the physical register
docs/design/yin.vm.debruijn.register.md:767:numbers in the image. A saved continuation contains only live values. Its
docs/design/yin.vm.debruijn.register.md:770:    {:segment <validated register image>
docs/design/yin.vm.debruijn.register.md:774:     :regs [[register-index value] ...]
docs/design/yin.vm.debruijn.register.md:775:     :live [register-index ...]
docs/design/yin.vm.debruijn.register.md:776:     :continuation [<return frame> ...]
docs/design/yin.vm.debruijn.register.md:777:     :dest <register-index or nil>
docs/design/yin.vm.debruijn.register.md:779:     :format :yin.debruijn.register
docs/design/yin.vm.debruijn.register.md:784:indices. A saved value is read from the running register file at snapshot
docs/design/yin.vm.debruijn.register.md:789:Each non-tail register call pushes a return frame of the same sparse form:
docs/design/yin.vm.debruijn.register.md:791:    {:segment <validated register image>
docs/design/yin.vm.debruijn.register.md:796:     :regs [[register-index value] ...]
docs/design/yin.vm.debruijn.register.md:797:     :live [register-index ...]
docs/design/yin.vm.debruijn.register.md:800:The outer payload's `:continuation` is a vector of these frames. An image
docs/design/yin.vm.debruijn.register.md:802:which register layout owns a frame. R4 may share persistent image values in
docs/design/yin.vm.debruijn.register.md:805:`:current-continuation` constructs
docs/design/yin.vm.debruijn.register.md:806:`{:type :reified-continuation ...payload}` for the point after itself,
docs/design/yin.vm.debruijn.register.md:809:to `engine/park-continuation`; the engine adds
docs/design/yin.vm.debruijn.register.md:810:`:type :parked-continuation` and `:id`, writes that record to the VM value,
docs/design/yin.vm.debruijn.register.md:813:Discarding dead registers is required, not an optimization option. Stream
docs/design/yin.vm.debruijn.register.md:817:frames are not registers and are retained until a separate frame-liveness
docs/design/yin.vm.debruijn.register.md:821:checks image validity, site pc and continuation pc ownership, destination
docs/design/yin.vm.debruijn.register.md:825:`:continuation-format`, `:continuation-hash`, `:continuation-pc`,
docs/design/yin.vm.debruijn.register.md:826:`:continuation-live`, `:continuation-registers`, and
docs/design/yin.vm.debruijn.register.md:827:`:continuation-destination`.
docs/design/yin.vm.debruijn.register.md:831:The future register restore has the shared signature:
docs/design/yin.vm.debruijn.register.md:833:    register-restore : base entry val -> state
docs/design/yin.vm.debruijn.register.md:838:validated register payload and the documented FFI keys.
docs/design/yin.vm.debruijn.register.md:840:For `:resume-mode :write-result`, restore creates a register vector of the
docs/design/yin.vm.debruijn.register.md:855:register file. Raw host exceptions never enter a serialized continuation.
docs/design/yin.vm.debruijn.register.md:874:tags. Explicit `:park` records alone use `:type :parked-continuation`.
docs/design/yin.vm.debruijn.register.md:897:    New: test/yin/vm/debruijn_register_contract_test.cljc
docs/design/yin.vm.debruijn.register.md:899:    Must not change: B0-B3, named datoms, stack image, resolver contract,
docs/design/yin.vm.debruijn.register.md:905:not built here. R0 depends on it and freezes the register descriptor
docs/design/yin.vm.debruijn.register.md:906:version, operand mapping, normalizer fixtures, and the named-root corpus
docs/design/yin.vm.debruijn.register.md:908:mapped or refused with a named diagnostic, the duplicate-parameter and
docs/design/yin.vm.debruijn.register.md:911:checks, and a decision that no register kernel is assumed yet.
docs/design/yin.vm.debruijn.register.md:913:### R1: register dimension and lowerer
docs/design/yin.vm.debruijn.register.md:915:    New: src/cljc/yin/vm/debruijn_register_code.cljc
docs/design/yin.vm.debruijn.register.md:916:    New: src/cljc/yin/vm/debruijn_register_compile.cljc
docs/design/yin.vm.debruijn.register.md:917:    New: test/yin/vm/debruijn_register_compile_test.cljc
docs/design/yin.vm.debruijn.register.md:924:requires every resolved node type mapped or refused with a named diagnostic,
docs/design/yin.vm.debruijn.register.md:925:byte-identical repeated lowerings, golden register vectors, cross-host R
docs/design/yin.vm.debruijn.register.md:926:agreement, the section 2.2 address law extended to the register image, and
docs/design/yin.vm.debruijn.register.md:927:the lift law `lift(lower-register (resolve x), side-table)` alpha-equivalent
docs/design/yin.vm.debruijn.register.md:933:    Existing source: src/cljc/yin/vm/debruijn_register_compile.cljc
docs/design/yin.vm.debruijn.register.md:934:    Existing source: src/cljc/yin/vm/debruijn_register_code.cljc
docs/design/yin.vm.debruijn.register.md:935:    New: src/cljc/yin/vm/debruijn_register_effects.cljc
docs/design/yin.vm.debruijn.register.md:936:    New: test/yin/vm/debruijn_register_effects_test.cljc
docs/design/yin.vm.debruijn.register.md:937:    Existing edits: test/yin/vm/debruijn_register_compile_test.cljc
docs/design/yin.vm.debruijn.register.md:939:      B0-B4 semantics, stack H, named or stack instruction dimensions
docs/design/yin.vm.debruijn.register.md:942:validator with the R2 opcodes, extend `body-liveness` to every continuation
docs/design/yin.vm.debruijn.register.md:945:version 2. Re-pin the descriptor hash, every golden register vector, and
docs/design/yin.vm.debruijn.register.md:951:   order, register allocation, side-table provenance, and effect argument
docs/design/yin.vm.debruijn.register.md:952:   order match the named and stack paths.
docs/design/yin.vm.debruijn.register.md:954:   wrong arity, wrong scalar kind, negative or out-of-body registers,
docs/design/yin.vm.debruijn.register.md:956:   cross-body targets, and a nonterminal `:resume`, with named diagnostics.
docs/design/yin.vm.debruijn.register.md:959:   Hand-built omissions, additions, duplicates, and dead-register captures
docs/design/yin.vm.debruijn.register.md:964:5. Sparse snapshots contain exactly the verified live register pairs,
docs/design/yin.vm.debruijn.register.md:966:   cover current-continuation, explicit park/resume, non-tail and tail
docs/design/yin.vm.debruijn.register.md:971:   ill-formed live set, every stale wake key named in section 5.2.3, and a
docs/design/yin.vm.debruijn.register.md:975:   response error without inventing an error register.
docs/design/yin.vm.debruijn.register.md:983:10. R2 tests do not instantiate a register VM. R4 must later prove that its
docs/design/yin.vm.debruijn.register.md:993:    New: test/yin/vm/debruijn_register_benchmark_test.cljc
docs/design/yin.vm.debruijn.register.md:1005:### R4: register kernel
docs/design/yin.vm.debruijn.register.md:1007:    New: src/cljc/yin/vm/debruijn/register.cljc
docs/design/yin.vm.debruijn.register.md:1008:    New: test/yin/vm/debruijn/register_test.cljc
docs/design/yin.vm.debruijn.register.md:1010:    Must not change: B0-B3, named VM, existing IVM methods
docs/design/yin.vm.debruijn.register.md:1013:a validated register image and its R. R4 follows B3's own precedent and
docs/design/yin.vm.debruijn.register.md:1018:completion requires B0-normalized parity against the named VM and the stack
docs/design/yin.vm.debruijn.register.md:1019:VM over the pure-program corpus, all register validator fixtures, lexical
docs/design/yin.vm.debruijn.register.md:1021:depends on R2, for the register shapes of stream, gensym, FFI, park, and
docs/design/yin.vm.debruijn.register.md:1028:The phase order runs on two parallel tracks after R1: on the register
docs/design/yin.vm.debruijn.register.md:1031:kernel; on the linker track, Phase B6 provides both stack and register
docs/design/yin.vm.debruijn.register.md:1042:Per the owner's directive ("B6 should be used by both the stack and register
docs/design/yin.vm.debruijn.register.md:1043:vm for linking code over dao.stream"), the register format linking capabilities
docs/design/yin.vm.debruijn.register.md:1046:function, the register format record (`:yin.debruijn.register`), the R index,
docs/design/yin.vm.debruijn.register.md:1047:and same-root pairing datoms (`[root :yin.debruijn.register/hash R]`) with
docs/design/yin.vm.debruijn.register.md:1053:This design does not alter any B0-B7 phase's semantics, the stack VM, named
docs/design/yin.vm.debruijn.register.md:1057:new continuation interchange format. B2's adoption of the resolver is the
docs/design/yin.vm.debruijn.register.md:1060:The register lowerer consumes the resolved tuples, never the stack image and
docs/design/yin.vm.debruijn.register.md:1061:never named datoms directly. It is not a second stack lowerer: it emits only
docs/design/yin.vm.debruijn.register.md:1062:`:yin.debruijn.register/*` and B2 remains the sole producer of
docs/design/yin.vm.debruijn.register.md:1065:stack image for the same program by H or by lowering the named datoms.
docs/design/yin.vm.debruijn.register.md:1072:Risks include register-allocation drift, spill policy becoming observable,
docs/design/yin.vm.debruijn.register.md:1073:effect ordering, register continuation shape, drift between the stack and
docs/design/yin.vm.debruijn.register.md:1074:register lowerers, and the permanent second-evaluator maintenance surface
docs/design/yin.vm.debruijn.register.md:1079:1. R0-R2 are lowerer and format work. R4, the register kernel, is
docs/design/yin.vm.debruijn.register.md:1086:   and the register kernel, across JVM, CLJS, and CLJD, permanently. The
docs/design/yin.vm.debruijn.register.md:1091:2. The stack image and the register image are peer projections of the
docs/design/yin.vm.debruijn.register.md:1099:   resolved tuples, stack images, and register images coexist.
docs/design/yin.vm.debruijn.register.md:1101:   disjoint register banks.
docs/design/yin.vm.debruijn.register.md:1104:7. The register kernel is a sibling and cannot silently replace the stack
docs/design/yin.vm.debruijn.register.md:1108:9. Live sets are in-band on every continuation boundary, as strictly
docs/design/yin.vm.debruijn.register.md:1110:   inside R's preimage, and recomputed by every receiver. R2 is register
docs/design/yin.vm.debruijn.register.md:1111:   contract version 3. A saved continuation carries only live registers.
docs/design/yin.vm.debruijn.register.md:1112:10. R2 defines effect opcodes, sparse continuation payloads, wait-entry
docs/design/yin.vm.debruijn.register.md:1118:- Spill representation and register-file limits, if a target requires them.
docs/design/yin.vm.debruijn.register.md:1119:- Register continuation lifting and cross-model park/resume transport.
docs/design/yin.vm.debruijn.register.md:1125:The lowerer-only end condition is a validated, deterministic register image
docs/design/yin.vm.debruijn.register.md:1127:image holds over the parity corpus, and whose named-VM normalized fixtures
docs/design/yin.vm.debruijn.register.md:1128:agree. The full register-VM end condition adds a validator-approved kernel
src/cljc/yin/vm/debruijn/register.cljc:1:(ns yin.vm.debruijn.register
src/cljc/yin/vm/debruijn/register.cljc:2:  "R4 (docs/design/yin.vm.debruijn.register.md S5, S6 R4): the register
src/cljc/yin/vm/debruijn/register.cljc:3:   execution kernel that interprets positional register images
src/cljc/yin/vm/debruijn/register.cljc:7:   The register kernel is a sibling to `yin.vm.debruijn.stack` (B3/B4) and
src/cljc/yin/vm/debruijn/register.cljc:12:   State: `{:segment :hash :images :pc :frames :free-env :registers
src/cljc/yin/vm/debruijn/register.cljc:13:            :continuation :store :blocked? :halted? :wait-set :ready-queue
src/cljc/yin/vm/debruijn/register.cljc:17:   Continuation transport: register continuations and stack continuations
src/cljc/yin/vm/debruijn/register.cljc:19:   :yin.debruijn.register`, `:hash R` of the code space at the time, and
src/cljc/yin/vm/debruijn/register.cljc:22:   hold is refused with qualified `:continuation-format` diagnostics.
src/cljc/yin/vm/debruijn/register.cljc:28:   effects tier, connecting to `yin.vm.engine` via `register-restore` and
src/cljc/yin/vm/debruijn/register.cljc:32:            [yin.vm.debruijn-register-code :as rcode]
src/cljc/yin/vm/debruijn/register.cljc:33:            [yin.vm.debruijn-register-effects :as effects]
src/cljc/yin/vm/debruijn/register.cljc:46:   hash         ; R of segment (yin.vm.debruijn-register-code/register-hash)
src/cljc/yin/vm/debruijn/register.cljc:49:   frames       ; positional lexical frame stack, outermost first
src/cljc/yin/vm/debruijn/register.cljc:51:   registers    ; persistent vector for current body's register file
src/cljc/yin/vm/debruijn/register.cljc:52:   continuation ; vector of return frames (innermost last)
src/cljc/yin/vm/debruijn/register.cljc:58:   parked       ; engine: map of parked continuations
src/cljc/yin/vm/debruijn/register.cljc:73:  "The model tag every register payload carries as `:format`."
src/cljc/yin/vm/debruijn/register.cljc:108:(defn- bind-positional
src/cljc/yin/vm/debruijn/register.cljc:131:   register validator. The offset table restarts at the base row
src/cljc/yin/vm/debruijn/register.cljc:139:             :images [[(rcode/register-hash empty-image) 0 0]]
src/cljc/yin/vm/debruijn/register.cljc:142:             :registers []
src/cljc/yin/vm/debruijn/register.cljc:143:             :continuation []
src/cljc/yin/vm/debruijn/register.cljc:147:      (if-let [defect (rcode/register-image-defect segment)]
src/cljc/yin/vm/debruijn/register.cljc:148:        (throw (ex-info (str "Invalid register image: " (:rule defect))
src/cljc/yin/vm/debruijn/register.cljc:151:              reg-count (or (:registers body0) 0)
src/cljc/yin/vm/debruijn/register.cljc:152:              r (rcode/register-hash segment)]
src/cljc/yin/vm/debruijn/register.cljc:159:                 :registers (vec (repeat reg-count nil))
src/cljc/yin/vm/debruijn/register.cljc:160:                 :continuation []
src/cljc/yin/vm/debruijn/register.cljc:167:  "Load `segment` (a register image `{:bodies [...], :instructions [...]}`)
src/cljc/yin/vm/debruijn/register.cljc:168:   into `vm` as its one image: R is computed, the registers are reset to
src/cljc/yin/vm/debruijn/register.cljc:169:   pc 0 with empty frames and continuation, and the machine is running
src/cljc/yin/vm/debruijn/register.cljc:171:   a continuation parked under an earlier image names no row of it and
src/cljc/yin/vm/debruijn/register.cljc:172:   `register-restore` refuses it.
src/cljc/yin/vm/debruijn/register.cljc:174:   `contract` is required and compared with `vm/register-contract` first
src/cljc/yin/vm/debruijn/register.cljc:176:   pass the register validator, Rule R included. An empty segment admits
src/cljc/yin/vm/debruijn/register.cljc:180:    (vm/check-contract! vm/register-contract contract))
src/cljc/yin/vm/debruijn/register.cljc:199:   becomes R of the concatenation. `:pc`, `:registers`, `:frames`,
src/cljc/yin/vm/debruijn/register.cljc:200:   `:continuation`, and every other register are unchanged: appending
src/cljc/yin/vm/debruijn/register.cljc:206:    (vm/check-contract! vm/register-contract contract)
src/cljc/yin/vm/debruijn/register.cljc:207:    (when-let [defect (rcode/register-image-defect image)]
src/cljc/yin/vm/debruijn/register.cljc:208:      (throw (ex-info (str "Invalid register image: " (:rule defect))
src/cljc/yin/vm/debruijn/register.cljc:210:  (let [ident (when-not (empty-segment? image) (rcode/register-hash image))]
src/cljc/yin/vm/debruijn/register.cljc:224:               :hash (rcode/register-hash combined)
src/cljc/yin/vm/debruijn/register.cljc:246:  "The code space `images`, the component register images in layout
src/cljc/yin/vm/debruijn/register.cljc:269:                     :rows (conj rows [(rcode/register-hash image) offset n])}))
src/cljc/yin/vm/debruijn/register.cljc:274:     :hash (when (seq instructions) (rcode/register-hash segment))
src/cljc/yin/vm/debruijn/register.cljc:289:                           (= ident (rcode/register-hash slice)))
src/cljc/yin/vm/debruijn/register.cljc:296:   order) build, each non-empty one admitted alone under the register
src/cljc/yin/vm/debruijn/register.cljc:297:   contract; the registers are reset.  The store, the parked map, the
src/cljc/yin/vm/debruijn/register.cljc:302:    (when-let [defect (rcode/register-image-defect image)]
src/cljc/yin/vm/debruijn/register.cljc:303:      (throw (ex-info (str "Invalid register image: " (:rule defect))
src/cljc/yin/vm/debruijn/register.cljc:313:           :registers (vec (repeat (or (:registers body0) 0) nil))
src/cljc/yin/vm/debruijn/register.cljc:314:           :continuation []
src/cljc/yin/vm/debruijn/register.cljc:376:            :registers [],
src/cljc/yin/vm/debruijn/register.cljc:377:            :continuation [],
src/cljc/yin/vm/debruijn/register.cljc:404:  "Unified return transition: restores caller frame from continuation if
src/cljc/yin/vm/debruijn/register.cljc:405:   pending, otherwise halts the VM with `val`. Only pc, frames, registers,
src/cljc/yin/vm/debruijn/register.cljc:406:   and continuation are restored (yin.vm.linker.md section 7.3, r7): a
src/cljc/yin/vm/debruijn/register.cljc:411:  (let [continuation (:continuation vm)]
src/cljc/yin/vm/debruijn/register.cljc:412:    (if (seq continuation)
src/cljc/yin/vm/debruijn/register.cljc:413:      (let [frame (peek continuation)
src/cljc/yin/vm/debruijn/register.cljc:416:            reg-count (:registers body)
src/cljc/yin/vm/debruijn/register.cljc:427:               :registers final-regs
src/cljc/yin/vm/debruijn/register.cljc:428:               :continuation (pop continuation)
src/cljc/yin/vm/debruijn/register.cljc:435:(defn- refuse-continuation!
src/cljc/yin/vm/debruijn/register.cljc:437:  (throw (ex-info "Cannot restore a continuation of another model or image"
src/cljc/yin/vm/debruijn/register.cljc:439:                   :rule :continuation-format,
src/cljc/yin/vm/debruijn/register.cljc:454:    (refuse-continuation! base entry)))
src/cljc/yin/vm/debruijn/register.cljc:458:  "Write `entry`'s registers back and deliver `val` via `:write-result` or
src/cljc/yin/vm/debruijn/register.cljc:465:                              :continuation (:continuation entry)
src/cljc/yin/vm/debruijn/register.cljc:471:          reg-count (:registers body)
src/cljc/yin/vm/debruijn/register.cljc:483:             :registers final-regs
src/cljc/yin/vm/debruijn/register.cljc:484:             :continuation (:continuation entry)
src/cljc/yin/vm/debruijn/register.cljc:490:(defn register-restore
src/cljc/yin/vm/debruijn/register.cljc:491:  "The register VM's restore function, `base entry val -> state`
src/cljc/yin/vm/debruijn/register.cljc:496:   effects/continuation-defect, checks that `val` is plain data, handles
src/cljc/yin/vm/debruijn/register.cljc:498:   `:return-result`. The entry restores registers only and never assigns
src/cljc/yin/vm/debruijn/register.cljc:502:  (when-let [defect (effects/continuation-defect entry)]
src/cljc/yin/vm/debruijn/register.cljc:503:    (throw (ex-info "Corrupt or tampered continuation payload"
src/cljc/yin/vm/debruijn/register.cljc:526:  "The continuation payload of `inst` (`effects/continuation-payload`),
src/cljc/yin/vm/debruijn/register.cljc:529:  (cond-> (effects/continuation-payload vm inst)
src/cljc/yin/vm/debruijn/register.cljc:561:               :registers (assoc (:registers state) rd value))))))
src/cljc/yin/vm/debruijn/register.cljc:580:                 :registers (assoc (:registers state) rd value)))))))
src/cljc/yin/vm/debruijn/register.cljc:585:  (let [{:keys [pc registers resources]} vm
src/cljc/yin/vm/debruijn/register.cljc:588:        args (mapv #(nth registers %) arg-regs)
src/cljc/yin/vm/debruijn/register.cljc:593:        parked (engine/park-continuation (assoc (engine/pin-refs vm args)
src/cljc/yin/vm/debruijn/register.cljc:627:  (let [{:keys [segment pc frames free-env registers continuation
src/cljc/yin/vm/debruijn/register.cljc:635:             :registers (assoc registers (nth inst 1) (nth inst 2)))
src/cljc/yin/vm/debruijn/register.cljc:641:               :registers (assoc registers (nth inst 1) val)))
src/cljc/yin/vm/debruijn/register.cljc:649:               :registers (assoc registers (nth inst 1) val)))
src/cljc/yin/vm/debruijn/register.cljc:660:               :registers (assoc registers (nth inst 1) clos)))
src/cljc/yin/vm/debruijn/register.cljc:663:      (let [val (nth registers (nth inst 2))]
src/cljc/yin/vm/debruijn/register.cljc:666:               :registers (assoc registers (nth inst 1) val)))
src/cljc/yin/vm/debruijn/register.cljc:674:            f (nth registers fn-reg)
src/cljc/yin/vm/debruijn/register.cljc:675:            args (mapv #(nth registers %) arg-regs)]
src/cljc/yin/vm/debruijn/register.cljc:682:                callee-reg-count (:registers callee-body)
src/cljc/yin/vm/debruijn/register.cljc:683:                locals (bind-positional callee-arity args)
src/cljc/yin/vm/debruijn/register.cljc:690:                continuation'
src/cljc/yin/vm/debruijn/register.cljc:692:                  continuation
src/cljc/yin/vm/debruijn/register.cljc:693:                  (conj continuation
src/cljc/yin/vm/debruijn/register.cljc:697:                                 :regs (mapv (fn [r] [r (nth registers r)])
src/cljc/yin/vm/debruijn/register.cljc:705:                   :registers callee-regs
src/cljc/yin/vm/debruijn/register.cljc:706:                   :continuation continuation'
src/cljc/yin/vm/debruijn/register.cljc:719:                       :registers (assoc registers rd result)))))
src/cljc/yin/vm/debruijn/register.cljc:721:          ;; A captured continuation: abortive, so the call site's
src/cljc/yin/vm/debruijn/register.cljc:722:          ;; registers, frames and return frames are dropped and the
src/cljc/yin/vm/debruijn/register.cljc:724:          ;; destination register. The store is not a register. An
src/cljc/yin/vm/debruijn/register.cljc:726:          ;; payload-defect and plain-data gates are for a continuation
src/cljc/yin/vm/debruijn/register.cljc:728:          ;; registers may hold closures and primitives.
src/cljc/yin/vm/debruijn/register.cljc:729:          :continuation
src/cljc/yin/vm/debruijn/register.cljc:730:          (let [v (engine/continuation-argument args)
src/cljc/yin/vm/debruijn/register.cljc:736:      (let [c (nth registers (nth inst 1))]
src/cljc/yin/vm/debruijn/register.cljc:743:      (return-transition vm (nth registers (nth inst 1)))
src/cljc/yin/vm/debruijn/register.cljc:747:             :halted? true, :value (nth registers (nth inst 1)), :store-of nil)
src/cljc/yin/vm/debruijn/register.cljc:754:             :registers (assoc registers
src/cljc/yin/vm/debruijn/register.cljc:764:               :registers (assoc registers (nth inst 1) val)))
src/cljc/yin/vm/debruijn/register.cljc:766:      ;; :define -- the definition transition: register `rs` is written
src/cljc/yin/vm/debruijn/register.cljc:770:      (let [val (nth registers (nth inst 3))]
src/cljc/yin/vm/debruijn/register.cljc:773:               :registers (assoc registers (nth inst 1) val)))
src/cljc/yin/vm/debruijn/register.cljc:779:               :registers (assoc (:registers vm') (nth inst 1) id)))
src/cljc/yin/vm/debruijn/register.cljc:786:      (let [sr (nth registers (nth inst 2))
src/cljc/yin/vm/debruijn/register.cljc:787:            vr (nth registers (nth inst 3))]
src/cljc/yin/vm/debruijn/register.cljc:791:      (let [sr (nth registers (nth inst 2))]
src/cljc/yin/vm/debruijn/register.cljc:795:      (let [cr (nth registers (nth inst 2))]
src/cljc/yin/vm/debruijn/register.cljc:799:      (let [sr (nth registers (nth inst 2))]
src/cljc/yin/vm/debruijn/register.cljc:802:      :current-continuation
src/cljc/yin/vm/debruijn/register.cljc:804:            reified (values/continuation
src/cljc/yin/vm/debruijn/register.cljc:806:                      (merge {:type :reified-continuation} payload))]
src/cljc/yin/vm/debruijn/register.cljc:809:               :registers (assoc registers (nth inst 1) reified)))
src/cljc/yin/vm/debruijn/register.cljc:813:        (engine/park-continuation (assoc vm :pc (inc pc)) payload))
src/cljc/yin/vm/debruijn/register.cljc:816:      (let [val (nth registers (nth inst 2))]
src/cljc/yin/vm/debruijn/register.cljc:817:        (engine/resume-continuation vm (nth inst 1) val register-restore))
src/cljc/yin/vm/debruijn/register.cljc:837:                   engine/active-continuation?
src/cljc/yin/vm/debruijn/register.cljc:839:                   #(engine/resume-from-run-queue % register-restore)))
src/cljc/yin/vm/debruijn/register.cljc:845:    (cond (engine/active-continuation? this) (step1 this)
src/cljc/yin/vm/debruijn/register.cljc:847:          (engine/scheduler-round this register-restore)
src/cljc/yin/vm/debruijn/register.cljc:853:        (str "The de Bruijn register kernel executes raw instruction vectors, "
src/cljc/yin/vm/debruijn/register.cljc:855:             "(yin.vm.debruijn-register-compile/adapt), then load and run the "
src/cljc/yin/vm/debruijn/register.cljc:873:             "frame-to-named lift exists")
src/cljc/yin/vm/debruijn/register.cljc:876:  (continuation [this] (:continuation this)))
src/cljc/yin/vm/debruijn/register.cljc:882:;; As the stack kernel: a positional closure lifts by the offset table row
src/cljc/yin/vm/debruijn/register.cljc:885:(defn- binding-mismatch!
src/cljc/yin/vm/debruijn/register.cljc:888:                  {:reason :binding-mismatch,
src/cljc/yin/vm/debruijn/register.cljc:896:  (link-format [_] {:format format-tag, :contract vm/register-contract})
src/cljc/yin/vm/debruijn/register.cljc:914:                :contract vm/register-contract}))
src/cljc/yin/vm/debruijn/register.cljc:915:  (image-identity [_ image] (rcode/register-hash image))
src/cljc/yin/vm/debruijn/register.cljc:916:  (image-holds? [_ image segment] (= segment (rcode/register-hash image)))
src/cljc/yin/vm/debruijn/register.cljc:917:  (attach-module [vm image] (attach-image vm image vm/register-contract))
src/cljc/yin/vm/debruijn/register.cljc:924:               :yin.k/binding :positional,
src/cljc/yin/vm/debruijn/register.cljc:932:    (when-not (and (= :positional (:yin.k/binding marker))
src/cljc/yin/vm/debruijn/register.cljc:934:      (binding-mismatch! marker))
src/cljc/yin/vm/debruijn/register.cljc:956:    {:kernel [(:continuation vm)],
src/cljc/yin/vm/debruijn/register.cljc:957:     :values [(:frames vm) (:registers vm) (:value vm)]})
src/cljc/yin/vm/debruijn/register.cljc:958:  ;; A register payload (a wait, ready or parked entry, or a reified
src/cljc/yin/vm/debruijn/register.cljc:959:  ;; continuation) names the code space as `:segment`; that is code, so
src/cljc/yin/vm/semantic.cljc:14:   The hot loop keeps the five registers — segment, pc, accumulator, operand
src/cljc/yin/vm/semantic.cljc:15:   stack, continuation — in `loop` locals and touches the record only at
src/cljc/yin/vm/semantic.cljc:21:   or on the stack. A wait or ready entry is those registers plus `:reason`
src/cljc/yin/vm/semantic.cljc:26:   continuation in its registers, a host type (D7, `yin.vm.values`), is
src/cljc/yin/vm/semantic.cljc:28:   loading different code under an id that continuations already name is a
src/cljc/yin/vm/semantic.cljc:54:   halted?        ; boolean, true when active continuation has completed
src/cljc/yin/vm/semantic.cljc:55:   k              ; continuation, a vector of frames (innermost last) or nil
src/cljc/yin/vm/semantic.cljc:61:   parked         ; parked continuations map
src/cljc/yin/vm/semantic.cljc:68:   ready-queue    ; vector of runnable continuations
src/cljc/yin/vm/semantic.cljc:71:   wait-set       ; vector of continuations waiting on a transport
src/cljc/yin/vm/semantic.cljc:90:(defn- put-registers
src/cljc/yin/vm/semantic.cljc:91:  "Write the five registers back into the record. This is the linear machine's
src/cljc/yin/vm/semantic.cljc:97:   An empty continuation is stored as nil rather than [] so that
src/cljc/yin/vm/semantic.cljc:112:   the machine registers {segment pc env stack k} plus the call-out reader
src/cljc/yin/vm/semantic.cljc:126:   (`yin.vm.universal-continuation-format.md` S7.4.3). nil when the
src/cljc/yin/vm/semantic.cljc:136:  "Restore a wait-set or ready-queue entry into machine registers.
src/cljc/yin/vm/semantic.cljc:138:   Two entry shapes carry more than registers:
src/cljc/yin/vm/semantic.cljc:175:       (put-registers base
src/cljc/yin/vm/semantic.cljc:186:   continuation of an effect is the machine state after the instruction:
src/cljc/yin/vm/semantic.cljc:205:  "Materialize the registers, dispatch one effect through the engine, and
src/cljc/yin/vm/semantic.cljc:215:        (engine/handle-effect (put-registers vm seg pc val St E K)
src/cljc/yin/vm/semantic.cljc:231:   parks with the continuation after the call site."
src/cljc/yin/vm/semantic.cljc:247:                (put-registers (engine/check-callee-effect! vm f result)
src/cljc/yin/vm/semantic.cljc:256:    ;; captured registers take over, with the argument in val as the
src/cljc/yin/vm/semantic.cljc:257:    ;; capture instruction left it (19). The store is not a register.
src/cljc/yin/vm/semantic.cljc:258:    :continuation
src/cljc/yin/vm/semantic.cljc:259:    (let [v (engine/continuation-argument args)
src/cljc/yin/vm/semantic.cljc:271:  "Run loaded work with the registers in loop locals.
src/cljc/yin/vm/semantic.cljc:277:   operation on locals (§4.3). A machine with no control and no continuation
src/cljc/yin/vm/semantic.cljc:294:          (put-registers vm seg pc val St E K)
src/cljc/yin/vm/semantic.cljc:330:              23 (put-registers vm nil nil val [] (engine/without-store-of E)
src/cljc/yin/vm/semantic.cljc:339:                  (put-registers vm nil nil val []
src/cljc/yin/vm/semantic.cljc:362:              ;; :current-continuation — val ← {seg, pc+1, E, St, K} (19)
src/cljc/yin/vm/semantic.cljc:364:                        (values/continuation (:owner vm)
src/cljc/yin/vm/semantic.cljc:365:                                             {:type :reified-continuation,
src/cljc/yin/vm/semantic.cljc:373:              17 (-> (put-registers vm seg pc val St E K)
src/cljc/yin/vm/semantic.cljc:374:                     (engine/park-continuation {:segment seg,
src/cljc/yin/vm/semantic.cljc:381:              18 (let [vm' (engine/resume-continuation
src/cljc/yin/vm/semantic.cljc:382:                             (put-registers vm seg pc val St E K)
src/cljc/yin/vm/semantic.cljc:386:                               (put-registers base
src/cljc/yin/vm/semantic.cljc:472:                       ;; after park-continuation would strand a continuation
src/cljc/yin/vm/semantic.cljc:477:                       vm' (put-registers (engine/pin-refs vm args)
src/cljc/yin/vm/semantic.cljc:480:                       parked (engine/park-continuation
src/cljc/yin/vm/semantic.cljc:529:  "One round between continuations: poll the wait set, then run whatever
src/cljc/yin/vm/semantic.cljc:539:   machine registers."
src/cljc/yin/vm/semantic.cljc:553:                   engine/active-continuation?
src/cljc/yin/vm/semantic.cljc:569:   :current-continuation :current-cont,
src/cljc/yin/vm/semantic.cljc:654:                       :current-continuation [opcode]
src/cljc/yin/vm/semantic.cljc:695:   Segment identity is stable: continuations, frames, and closures name
src/cljc/yin/vm/semantic.cljc:700:   continuation that names the id."
src/cljc/yin/vm/semantic.cljc:736:   Phase 2 linearizer in front of it. Parked continuations survive a load, so
src/cljc/yin/vm/semantic.cljc:778:   decode the positional operands into the same image `load-image` builds
src/cljc/yin/vm/semantic.cljc:779:   from the projected batch, and register it under `:code` with the
src/cljc/yin/vm/semantic.cljc:792:   S7.5 validation."
src/cljc/yin/vm/semantic.cljc:895:  (continuation [vm] (:k vm)))
src/cljc/yin/vm/semantic.cljc:957:;; A named closure lifts by the alias column: its local segment id's
src/cljc/yin/vm/semantic.cljc:963:(defn- binding-mismatch!
src/cljc/yin/vm/semantic.cljc:966:                  {:reason :binding-mismatch,
src/cljc/yin/vm/semantic.cljc:1010:               :yin.k/binding :named,
src/cljc/yin/vm/semantic.cljc:1020:    (when-not (and (= :named (:yin.k/binding marker))
src/cljc/yin/vm/semantic.cljc:1022:      (binding-mismatch! marker))
src/cljc/yin/vm/semantic.cljc:1056:  ;; and entry, and a frame or continuation its segment and pc, so every
docs/design/yin.vm.universal-continuation-format.md:5:> theoretically sound as an architecture-agnostic continuation format, but
docs/design/yin.vm.universal-continuation-format.md:9:> ruling on S7.3's canonical form (r3), and Rule R's "v3" contract (r4);
docs/design/yin.vm.universal-continuation-format.md:10:> S7.11 lists the acceptance blockers that keep this warning standing until
docs/design/yin.vm.universal-continuation-format.md:13:The Semantic VM's linear CESK state (`{:segment id, :pc n, :env E, :stack S, :k K}`) is proposed as the canonical exchange format for network-transparent continuations. Because this lowered representation resolves execution-order ambiguity inherent in the Universal AST, it provides a simpler target for specialized execution engines (e.g., WebAssembly, LLVM, hardware FPGA) to participate in the `datom.world` ecosystem.
docs/design/yin.vm.universal-continuation-format.md:17:1. **Safepoint Reconstruction Metadata:** `:yin.code/source` mappings are insufficient for state reconstruction. The safepoint set must be exactly the machine's parking transitions — including effect-producing `:call`s — and reconstruction metadata must separate what is derivable from the canonical code alone (stack *effects*, lexical *requirements*) from what is per-activation state only the frame can carry (absolute depths, captured environments). Foreign engines publish per-segment safepoint maps derived from the canonical stream so arbitrary hardware states can be predictably lifted into the canonical CESK format.
docs/design/yin.vm.universal-continuation-format.md:20:4. **Ownership Arbitration:** Emitting a continuation does not transfer ownership. Exclusion requires a **grounded authority** — a resource the grantor's boundary actually possesses, named in the value, independent of carriers — plus an **exporting state** that fences the source before publication, and **effect fencing** with authority epochs and stable operation ids, because absence of a lapse record is never evidence of tenure.
docs/design/yin.vm.universal-continuation-format.md:21:5. **Code Identity (Content Addressing):** Because tempids (`:segment 123`) are local to a single machine's database, continuations must refer to code via immutable, versioned semantic profiles — a **canonical instruction vector** (positional tuples, not EAV) whose address is computed over the loader's resolved interpretation, whose addressed payload is the vector itself, and whose interpretation is pinned by a contract stamp versioning the *complete* execution contract.
docs/design/yin.vm.universal-continuation-format.md:30:positional instruction tuple vector, not EAV (§7.3.2).
docs/design/yin.vm.universal-continuation-format.md:34:reserved name before env and a `:define` transition is added (S7.3.3);
docs/design/yin.vm.universal-continuation-format.md:35:`yin/def` leaves the primitive registry (S7.5.2) and the free-name set
docs/design/yin.vm.universal-continuation-format.md:36:(S7.6.1); S7.11 records the remaining definition-frame obligations.
docs/design/yin.vm.universal-continuation-format.md:70:## §7.1 Stance: a continuation is a value, not a session
docs/design/yin.vm.universal-continuation-format.md:73:wait entry or a `:parked-continuation` record is `{segment pc env stack k}`
docs/design/yin.vm.universal-continuation-format.md:81:into its own registers, and the discipline that governs who may lower it.
docs/design/yin.vm.universal-continuation-format.md:111:UCF facts live under `:yin.k/*` (K for the continuation component of CESK).
docs/design/yin.vm.universal-continuation-format.md:120:{:yin.k/type        :yin.k/continuation
docs/design/yin.vm.universal-continuation-format.md:121: :yin.k/contract    {:yin.code/contract "v3" :yin.k/version 0}   ; S7.3.3
docs/design/yin.vm.universal-continuation-format.md:127: :yin.k/arbitration {:dao.stream/identity … :dao.stream/descriptor …}     ; §7.7.3  the authority medium, named in the value
docs/design/yin.vm.universal-continuation-format.md:128: :yin.k/frame       {…}                        ; §7.4  the canonical CESK at a safepoint
docs/design/yin.vm.universal-continuation-format.md:185: :yin.k/parked        {pid registers}      ; 7.6.3 parked records
docs/design/yin.vm.universal-continuation-format.md:194: :yin.k/frames        [{:yin.k/registers registers
docs/design/yin.vm.universal-continuation-format.md:202:`registers` is `{:yin.k/segment address :yin.k/pc n :yin.k/env {name
docs/design/yin.vm.universal-continuation-format.md:215:- A frame's resume pc is a static safepoint whose kinds admit the
docs/design/yin.vm.universal-continuation-format.md:217:  safepoint (`yin.vm.ucf-revisions.md` section 8).
docs/design/yin.vm.universal-continuation-format.md:327:`123` is different code or no code. A continuation naming code by tempid is
docs/design/yin.vm.universal-continuation-format.md:333:§2.2, becomes required on any segment a continuation may reference. Its
docs/design/yin.vm.universal-continuation-format.md:336:named so no later plan reinvents it: the first draft's sorted `[e a v]`
docs/design/yin.vm.universal-continuation-format.md:347:canonical form is therefore the **positional instruction tuple vector**:
docs/design/yin.vm.universal-continuation-format.md:426:sequences, and compiler-renamed artifacts address **differently**. The
docs/design/yin.vm.universal-continuation-format.md:456:  consulted, so no binding can redirect a definition (S7.5.2);
docs/design/yin.vm.universal-continuation-format.md:481:transition; see the revision log in S7.11. The de Bruijn images carry
docs/design/yin.vm.universal-continuation-format.md:482:their own names, "b2" (stack) and "r2" (register). Every persistent-code
docs/design/yin.vm.universal-continuation-format.md:494:explicitly named path for that, `vm/fresh-code-loader`; nothing assigns
docs/design/yin.vm.universal-continuation-format.md:497:### 7.3.4 References to code inside a continuation
docs/design/yin.vm.universal-continuation-format.md:557:**The continuation's own identity and the occurrence.** `:yin.k/id` is the
docs/design/yin.vm.universal-continuation-format.md:561:`(assoc body :yin.k/id (dao.jing/segment-key body))`. Equal continuations
docs/design/yin.vm.universal-continuation-format.md:573:### 7.4.1 Only safepoints lift, and the table is the machine's
docs/design/yin.vm.universal-continuation-format.md:575:The linear machine's registers are canonical only *between* instructions.
docs/design/yin.vm.universal-continuation-format.md:579:UCF resolves this by rule rather than by metadata: **a continuation may be
docs/design/yin.vm.universal-continuation-format.md:580:lifted only at a safepoint**, and a safepoint is a transition of the
docs/design/yin.vm.universal-continuation-format.md:599:`:current-continuation` is **not** a safepoint: it does not park — it
docs/design/yin.vm.universal-continuation-format.md:601:continuation is a *value class* of the encoding (§7.5.1), never a lift
docs/design/yin.vm.universal-continuation-format.md:627:**Linker safepoints (M4).** Every block marked (linker M4) is specified by
docs/design/yin.vm.universal-continuation-format.md:631:safepoint and resource semantics; only the UCF lift and lower of parked
docs/design/yin.vm.universal-continuation-format.md:633:safepoints to the set above, one per wait state of a `:module/require`
docs/design/yin.vm.universal-continuation-format.md:636:three -- `require` is named there -- and the wait entry moves between the
docs/design/yin.vm.universal-continuation-format.md:668:The reference machine needs no metadata because its registers *are* the
docs/design/yin.vm.universal-continuation-format.md:669:canonical configuration. A foreign engine — a register allocator over the
docs/design/yin.vm.universal-continuation-format.md:686:[[sp :yin.safepoint/segment      :segment/sha256-3f1a…]  ; the code, by address
docs/design/yin.vm.universal-continuation-format.md:687: [sp :yin.safepoint/engine       :wasm32/v1]             ; the engine profile that produced this layout
docs/design/yin.vm.universal-continuation-format.md:688: [sp :yin.safepoint/pc           5]                      ; canonical resume pc
docs/design/yin.vm.universal-continuation-format.md:689: [sp :yin.safepoint/stack-effect -1]                     ; Δ|St| across the parking instruction (static)
docs/design/yin.vm.universal-continuation-format.md:690: [sp :yin.safepoint/lexically-required [x conn]]         ; names this pc can read from E (static)
docs/design/yin.vm.universal-continuation-format.md:691: [sp :yin.safepoint/layout       {…}]]                   ; engine-owned: physical slot → canonical slot
docs/design/yin.vm.universal-continuation-format.md:699:  machine by a conformance test that runs the segment to each safepoint and
docs/design/yin.vm.universal-continuation-format.md:703:- `:yin.safepoint/layout` is opaque to the protocol. It is a memo the engine
docs/design/yin.vm.universal-continuation-format.md:707:  `lower(canonical frame, layout) → engine-state` are total on safepoints and
docs/design/yin.vm.universal-continuation-format.md:711:- A request to lift at a pc that is not a safepoint of the segment returns
docs/design/yin.vm.universal-continuation-format.md:712:  `:yin.k/not-at-safepoint` naming the pc. An engine that wants finer
docs/design/yin.vm.universal-continuation-format.md:716:  invent safepoints a record does not have, and two engines that disagree
docs/design/yin.vm.universal-continuation-format.md:717:  about the safepoint set of one address are not both conforming.
docs/design/yin.vm.universal-continuation-format.md:721:the safepoint set by construction.
docs/design/yin.vm.universal-continuation-format.md:818:safepoint of the same name (`yin.vm.linker.md` 7.2, 7.3). A wait entry's
docs/design/yin.vm.universal-continuation-format.md:880:`:yin.k/parked` under its own id as a `registers` map, a body of kind
docs/design/yin.vm.universal-continuation-format.md:1050:| reified continuation | `:yin.k/frame` | a nested frame (§7.4.1), recursively | `{:type :reified-continuation …}` |
docs/design/yin.vm.universal-continuation-format.md:1051:| parked continuation | `:yin.k/frame` | a nested frame plus `:yin.k/parked-id` | `{:type :parked-continuation …}` |
docs/design/yin.vm.universal-continuation-format.md:1052:| primitive | `:yin.k/primitive` | `:yin.k/name sym` | the named function, after profile check (§7.5.2) |
docs/design/yin.vm.universal-continuation-format.md:1065:because no marker is ever a non-map, which the closed tag table fixes.
docs/design/yin.vm.universal-continuation-format.md:1091:conforming encoder); a tag outside the closed set; a marker missing its
docs/design/yin.vm.universal-continuation-format.md:1100:above was written for the named semantic VM alone. The linker's four
docs/design/yin.vm.universal-continuation-format.md:1101:backends split into named kernels (`:yin.ast/code`, `:yin.semantic/code`)
docs/design/yin.vm.universal-continuation-format.md:1102:and positional ones (`:yin.debruijn.code`, `:yin.debruijn.register`), so
docs/design/yin.vm.universal-continuation-format.md:1108:;; named (:yin.ast/code, :yin.semantic/code)
docs/design/yin.vm.universal-continuation-format.md:1109:{:yin.k/tag :yin.k/closure :yin.k/binding :named
docs/design/yin.vm.universal-continuation-format.md:1114:;; positional (:yin.debruijn.code, :yin.debruijn.register)
docs/design/yin.vm.universal-continuation-format.md:1115:{:yin.k/tag :yin.k/closure :yin.k/binding :positional
docs/design/yin.vm.universal-continuation-format.md:1121:A `:named` marker lowers only into a named kernel and a `:positional`
docs/design/yin.vm.universal-continuation-format.md:1122:marker only into a positional kernel of the same `:format`; the reverse
docs/design/yin.vm.universal-continuation-format.md:1123:is `:binding-mismatch`, so a lifted slice carried elsewhere fails
docs/design/yin.vm.universal-continuation-format.md:1124:closed rather than being reinterpreted. `:binding-mismatch` is a
docs/design/yin.vm.universal-continuation-format.md:1126:kind, so it is not in the closed set of section 7.5.4. In a `:named` marker lifted
docs/design/yin.vm.universal-continuation-format.md:1128:slot is the closure's body and the entry is nil; in a `:positional`
docs/design/yin.vm.universal-continuation-format.md:1132:`:unrooted-body` refusal. The store named by `:yin.k/store-of` travels
docs/design/yin.vm.universal-continuation-format.md:1154:literal with a wrong or missing seal fails closed as
docs/design/yin.vm.universal-continuation-format.md:1155:`:forged-resource-reference`, naming the effect and the id it named. Lift
docs/design/yin.vm.universal-continuation-format.md:1204:- **A host function under no name is refused** (kind `:unnamed-function`) —
docs/design/yin.vm.universal-continuation-format.md:1214:  whose profile address is equal; a same-named function of different profile
docs/design/yin.vm.universal-continuation-format.md:1220:  publication is an acceptance blocker (S7.11).
docs/design/yin.vm.universal-continuation-format.md:1223:  `:define` transition (S7.3.3), which writes its literal key through
docs/design/yin.vm.universal-continuation-format.md:1238:- On encountering a collection, closure, or continuation it has already
docs/design/yin.vm.universal-continuation-format.md:1257:two continuations parked from the same closure share its encoding. This is
docs/design/yin.vm.universal-continuation-format.md:1315:Encoding a continuation with one refused leaf refuses the whole
docs/design/yin.vm.universal-continuation-format.md:1316:continuation:
docs/design/yin.vm.universal-continuation-format.md:1321: :yin.k/kind   :host-object               ; the closed kind set below
docs/design/yin.vm.universal-continuation-format.md:1325:The kind set is closed:
docs/design/yin.vm.universal-continuation-format.md:1326:`:host-object | :unnamed-function | :ambiguous-primitive | :host-state-primitive
docs/design/yin.vm.universal-continuation-format.md:1332:host resources behind stream descriptors and named, profiled primitives,
docs/design/yin.vm.universal-continuation-format.md:1394:  Definitions are syntax (Rule R, S7.5.2): the definition operator
docs/design/yin.vm.universal-continuation-format.md:1411:undeclared footprint cannot be closed — the result distinguishes
docs/design/yin.vm.universal-continuation-format.md:1419:continuation carries the **reachable slice**: every store key the fixed
docs/design/yin.vm.universal-continuation-format.md:1492:existing generated identifier, or fail to resolve a parked continuation a
docs/design/yin.vm.universal-continuation-format.md:1493:migrated segment is about to name (`engine/park-continuation`,
docs/design/yin.vm.universal-continuation-format.md:1494:`engine/resume-continuation`, §3.5). UCF therefore states the unit and
docs/design/yin.vm.universal-continuation-format.md:1497:- **The unit is the whole blocked machine**: registers (the frame), the
docs/design/yin.vm.universal-continuation-format.md:1515:  as it represents corrupted state. Parked records not named by the
docs/design/yin.vm.universal-continuation-format.md:1525:same-named module of different content. Module *code* is segments and is
docs/design/yin.vm.universal-continuation-format.md:1561:### 7.7.1 The race, named
docs/design/yin.vm.universal-continuation-format.md:1638:| recorded report | `:yin.k/custody :yin.k/resumed` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:dao.lease/lease`, `:yin.k/result`, `:yin.k/successor` (for a continuation only: the occurrence the authority verified) |
docs/design/yin.vm.universal-continuation-format.md:1684:  two media does not create a second arbiter, because the authority is named
docs/design/yin.vm.universal-continuation-format.md:1711:other machines' hands. Delaying register restoration is too late; the
docs/design/yin.vm.universal-continuation-format.md:1765:safepoint while it holds any of these.
docs/design/yin.vm.universal-continuation-format.md:1794:parked continuations, issue stamps, descriptors and dependency closure.
docs/design/yin.vm.universal-continuation-format.md:1865:in the closed set `#{:dao.stream/full :dao.stream/invalid-value
docs/design/yin.vm.universal-continuation-format.md:1866::dao.stream/closed :dao.stream/refused}`; `transport-error` and a
docs/design/yin.vm.universal-continuation-format.md:1889:named: a partitioned consumer accepts stale effects (it cannot see the
docs/design/yin.vm.universal-continuation-format.md:1934:the keys named here, and the sequence state behind the operation id.
docs/design/yin.vm.universal-continuation-format.md:1943:The occurrence must be unclosed and unquarantined, epoch advancement supported,
docs/design/yin.vm.universal-continuation-format.md:1958:commitment** — fail closed — or the composition has chosen at-least-once
docs/design/yin.vm.universal-continuation-format.md:1974:A resumed continuation runs to its next safepoint and either halts or parks
docs/design/yin.vm.universal-continuation-format.md:1975:again. Either way it produces a successor value — a `:yin.k/continuation`
docs/design/yin.vm.universal-continuation-format.md:1989:closed occurrence never regains tenure and receives no further grant
docs/design/yin.vm.universal-continuation-format.md:1994:continuation does, but the closure's edge is a terminal edge to the
docs/design/yin.vm.universal-continuation-format.md:2091:{:yin.k/envelope    :yin.k/fenced-v1   ; dispatch key; closed
docs/design/yin.vm.universal-continuation-format.md:2132: :yin.k/defect     :malformed       ; closed, below
docs/design/yin.vm.universal-continuation-format.md:2138:- The closed defect set is `:malformed` for invalid envelope
docs/design/yin.vm.universal-continuation-format.md:2199:  in the named authority's transaction history. A reader holding a
docs/design/yin.vm.universal-continuation-format.md:2211:  identity within the named authority's provenance domain,
docs/design/yin.vm.universal-continuation-format.md:2331:   baseline. A store that is absent, closed or throws is unreadable.
docs/design/yin.vm.universal-continuation-format.md:2394:accept is an orphan, and no ancestry runs through it. A closed
docs/design/yin.vm.universal-continuation-format.md:2422:already closed by policy reclaim. Unmatched journal intents remain history.
docs/design/yin.vm.universal-continuation-format.md:2440:3. `lift(export-record)`: confirm the record is at a safepoint (§7.4.1),
docs/design/yin.vm.universal-continuation-format.md:2441:   else `:yin.k/not-at-safepoint`; canonicalize every referenced segment and
docs/design/yin.vm.universal-continuation-format.md:2483:8. `lower(frame, layout)` into engine registers; re-mint every pending wait
docs/design/yin.vm.universal-continuation-format.md:2486:9. At the next safepoint or halt, produce the successor occurrence
docs/design/yin.vm.universal-continuation-format.md:2496:`:yin.k/status`; the set is closed and every non-`ok` outcome carries
docs/design/yin.vm.universal-continuation-format.md:2503:| `:yin.k/not-at-safepoint` | lift | `:yin.k/segment`, `:yin.k/pc` |
docs/design/yin.vm.universal-continuation-format.md:2515:`closed`, `invalid-descriptor`, …) are surfaced unchanged inside the UCF
docs/design/yin.vm.universal-continuation-format.md:2526:amendment adds no `:yin.k/status` value; the set stays closed.
docs/design/yin.vm.universal-continuation-format.md:2552:and lower failures, does not reach it. The set is closed, with
docs/design/yin.vm.universal-continuation-format.md:2575:  removed, or renamed.
docs/design/yin.vm.universal-continuation-format.md:2578:  and `:yin.k/closed true` when the occurrence is closed.
docs/design/yin.vm.universal-continuation-format.md:2585:  closed-target record drops the payload. Digest equality is
docs/design/yin.vm.universal-continuation-format.md:2625:state's holds ride the closed status set above, never a new dispatch
docs/design/yin.vm.universal-continuation-format.md:2651:| No hidden global state | Every dependency is declared in `:yin.k/requires` — *as computed by the declared fixed point, with its completeness stated* (`:yin.k/discovery`); the store travels as an explicit slice restored into an isolated store; fresh-name state and parked records travel explicitly; custody is facts on a named, possessed medium. |
docs/design/yin.vm.universal-continuation-format.md:2652:| No implicit control flow | Resume happens only at a safepoint the machine's parking transitions define; the successor chain is explicit `:yin.k/origin` links; the exporting state is an observed data state, never a notification. |
docs/design/yin.vm.universal-continuation-format.md:2659:continuation is a stream value (1); its meaning is supplied by the
docs/design/yin.vm.universal-continuation-format.md:2670:closed with a test in the parity or conformance suite. The first four are
docs/design/yin.vm.universal-continuation-format.md:2671:the review's named architectural obligations; they are blockers to
docs/design/yin.vm.universal-continuation-format.md:2710:  map published with profiles (S7.5.2), including the `:effectful`
docs/design/yin.vm.universal-continuation-format.md:2714:  pending definition (a define continuation awaiting its value), stamp
docs/design/yin.vm.universal-continuation-format.md:2715:  comparison at continuation lowering, module-store snapshot lowering
docs/design/yin.vm.universal-continuation-format.md:2727:  on the reference machine to each safepoint of the complete §7.4.1 table —
docs/design/yin.vm.universal-continuation-format.md:2770:closed result union is `{:status :record :position n :author a :record r}`,
docs/design/yin.vm.universal-continuation-format.md:2827:positional adapters and REPL integration; memory-backed test reconstruction is
docs/design/yin.vm.universal-continuation-format.md:2833:are closed. M3 and M4 tests run on JVM, CLJS (Node), and CLJD. A named
docs/design/yin.vm.universal-continuation-format.md:2839:claim that all five blockers have closed.
docs/design/yin.vm.universal-continuation-format.md:2843:- Invariant: A resumer executes the instruction stream named by the
docs/design/yin.vm.universal-continuation-format.md:2844:  continuation's address and contract stamp, or refuses before loading it.
docs/design/yin.vm.universal-continuation-format.md:2857:  its named algorithm. M3 also passes linker section 9's full refusal
docs/design/yin.vm.universal-continuation-format.md:2887:  at one pc, a nonempty ready queue, a nonsafepoint pc, and a kept response
docs/design/yin.vm.universal-continuation-format.md:2897:  `:yin.safepoint/kinds`, stack effect, and lexical reads agree with the
docs/design/yin.vm.universal-continuation-format.md:2903:  `:yin.k/not-at-safepoint`; queued work gives `:yin.k/not-quiescent`.
docs/design/yin.vm.universal-continuation-format.md:2907:  continuation holding a string-backed local `dao.stream` with a kept
docs/design/yin.vm.universal-continuation-format.md:2938:  map keys containing values, closures, reified and parked continuations,
docs/design/yin.vm.universal-continuation-format.md:2941:  Add a host object, unnamed or ambiguous function, host-state primitive,
docs/design/yin.vm.universal-continuation-format.md:2952:  same-named primitive with a different profile is `:yin.k/unsatisfied`,
docs/design/yin.vm.universal-continuation-format.md:2959:  the safepoint row before cross-host values are accepted. Phase 1
docs/design/yin.vm.universal-continuation-format.md:2983:  A re-grant either replays durable inputs or fails closed on divergence.
docs/design/yin.vm.universal-continuation-format.md:3025:This block adds obligations; it changes no row above. The safepoint
docs/design/yin.vm.universal-continuation-format.md:3118:   lease, closed occurrence, a foreign id, an inherited id from a
docs/design/yin.vm.universal-continuation-format.md:3119:   closed ancestor, equal intent, different intent, fresh commit.

exec
/bin/zsh -lc "nl -ba docs/design/yin.vm.universal-continuation-format.md | sed -n '1,150p;573,725p;1095,1135p;2650,2685p' && nl -ba docs/design/yin.vm.debruijn.register.md | sed -n '1,130p' && nl -ba docs/design/yin.vm.linker.md | sed -n '620,640p;1325,1430p' && nl -ba src/cljc/yin/vm/semantic.cljc | sed -n '1,100p;220,275p;430,495p' && nl -ba src/cljc/yin/vm/debruijn/register.cljc | sed -n '1,90p;130,190p' " in /Users/sto/workspace/datomworld
 succeeded in 0ms:
     1	## §7. The Universal Continuation Format (Proposed)
     2	
     3	> [!WARNING]  
     4	> **Status: Proposed / Deferred.** The Semantic VM's linear CESK state is
     5	> theoretically sound as an architecture-agnostic continuation format, but
     6	> true heterogeneous network migration requires addressing several critical
     7	> defects identified in the 2026-09-14 architectural review. This revision
     8	> (r4) incorporates r2's answers to that review's findings 1-19, the owner
     9	> ruling on S7.3's canonical form (r3), and Rule R's "v3" contract (r4);
    10	> S7.11 lists the acceptance blockers that keep this warning standing until
    11	> an implementation phase closes each of them with tests.
    12	
    13	The Semantic VM's linear CESK state (`{:segment id, :pc n, :env E, :stack S, :k K}`) is proposed as the canonical exchange format for network-transparent continuations. Because this lowered representation resolves execution-order ambiguity inherent in the Universal AST, it provides a simpler target for specialized execution engines (e.g., WebAssembly, LLVM, hardware FPGA) to participate in the `datom.world` ecosystem.
    14	
    15	To safely "lift on park" and "lower on resume" across heterogeneous boundaries without violating host isolation or concurrency invariants, the following contracts must be fully specified before this feature is accepted:
    16	
    17	1. **Safepoint Reconstruction Metadata:** `:yin.code/source` mappings are insufficient for state reconstruction. The safepoint set must be exactly the machine's parking transitions — including effect-producing `:call`s — and reconstruction metadata must separate what is derivable from the canonical code alone (stack *effects*, lexical *requirements*) from what is per-activation state only the frame can carry (absolute depths, captured environments). Foreign engines publish per-segment safepoint maps derived from the canonical stream so arbitrary hardware states can be predictably lifted into the canonical CESK format.
    18	2. **Recursive Portable Encoding:** Section 1.1 permits host functions and local stream handles in the environment. A recursive encoding over a **disjoint tagged grammar** is required — one a program's own map literals cannot counterfeit — to encode these local references into portable descriptors, together with per-primitive **semantic profiles** (a name recovers a binding, not a meaning) and explicit, total failure modes for un-serializable resources. Cursor aliasing is observable and must survive the round trip.
    19	3. **Dependency Closure & Context:** A parked fragment is not a complete configuration. The format must carry its complete scheduler context — required primitives *with profiles*, loaded modules, referenced parked records, the fresh-name counter, and the reachable store slice — discovered by a conservative fixed point that includes explicit store operands, and must restore into an **isolated execution store** so the receiver's own state cannot change resolution.
    20	4. **Ownership Arbitration:** Emitting a continuation does not transfer ownership. Exclusion requires a **grounded authority** — a resource the grantor's boundary actually possesses, named in the value, independent of carriers — plus an **exporting state** that fences the source before publication, and **effect fencing** with authority epochs and stable operation ids, because absence of a lapse record is never evidence of tenure.
    21	5. **Code Identity (Content Addressing):** Because tempids (`:segment 123`) are local to a single machine's database, continuations must refer to code via immutable, versioned semantic profiles — a **canonical instruction vector** (positional tuples, not EAV) whose address is computed over the loader's resolved interpretation, whose addressed payload is the vector itself, and whose interpretation is pinned by a contract stamp versioning the *complete* execution contract.
    22	
    23	---
    24	
    25	# Universal Continuation Format — protocol draft
    26	
    27	Status: Phase 5 design draft, 2026-09-14; revised 2026-09-14 (r2) per the
    28	architectural review `collab/1789387292000-architect-ucf-review.gpt-6-astra.findings.md`;
    29	amended 2026-09-14 (r3) by owner ruling — §7.3's canonical form is the
    30	positional instruction tuple vector, not EAV (§7.3.2).
    31	Amended 2026-09-25 (r4) for Rule R, `yin/def` is syntax, never a name
    32	(collab `1790345200000-architect-yin-def-rule-r-final` findings): the
    33	contract stamp moves to "v3" because the resolution order refuses the
    34	reserved name before env and a `:define` transition is added (S7.3.3);
    35	`yin/def` leaves the primitive registry (S7.5.2) and the free-name set
    36	(S7.6.1); S7.11 records the remaining definition-frame obligations.
    37	Amended 2026-10-04 (r5) for fenced custody (`yin.vm.linker.dht.md` 14.3,
    38	M-next B): the handoff body is published as wire grammar, and
    39	`:yin.k/version 1` adds the custody header, the operation-sequence state,
    40	the fenced envelope, the grant epoch binding, and the admission outcomes
    41	(7.2.1, 7.4.3, 7.7.8, 7.9, 7.11.1). The amendment is published design:
    42	no version-1 code is landed and it closes no blocker.
    43	Revised 2026-10-04 (r6) after the second architect's adversarial review
    44	(collab `1791056670000-architect-ucf-v1-amendment-review`): inherited
    45	operation ids are scoped to the granted checkpoint's carried pendings,
    46	dedup is one namespace per admission resource, admission outcomes are
    47	authenticated, uncertainty is separated from terminal refusal, epoch
    48	exhaustion has a stated precedence, install children validate in their
    49	root's context, and defective envelopes yield a structured diagnostic.
    50	Revised 2026-10-04 (r7) on that architect's confirmation (collab
    51	`1791057600000-architect-ucf-v1-amendment-r6-confirm`): checkpoint
    52	unavailability suspends after the tenure check and mutates nothing,
    53	snapshot variants preserve the operation baseline, the install phase
    54	no longer constrains the child's kind, the defect set is defined in
    55	full, and unknown transport acceptance is neither commitment nor its
    56	absence.
    57	Subordinate to [`datom.world.md`](./datom.world.md); builds on the Phase 0
    58	contract in [`yin.vm.semantic.md`](./yin.vm.semantic.md) (§1–§4), the storage
    59	contract in [`dao.jing.md`](./dao.jing.md), the stream contract in
    60	[`dao.stream.md`](./dao.stream.md), and the lease vocabulary in
    61	[`dao.lease.md`](./dao.lease.md). The five blockers above are answered in
    62	§7.3–§7.7, one section each, in dependency order: code identity first, because
    63	every other part names code.
    64	
    65	Every sentence in §7.2–§7.9 is a rule of the proposed protocol. §7.11 records
    66	what is deliberately left open, as acceptance blockers. Nothing below is
    67	implemented; the warning at the top stands until an implementation phase
    68	resolves each blocker with tests.
    69	
    70	## §7.1 Stance: a continuation is a value, not a session
    71	
    72	The semantic VM already parks as pure data (`yin.vm.semantic.md` §3.5): a
    73	wait entry or a `:parked-continuation` record is `{segment pc env stack k}`
    74	plus a reason and resource ids, with no handle and no closure attached. That
    75	record is *locally* complete and *globally* meaningless: its segment is a
    76	tempid, its stream ids are keys into this VM's store, its environment may hold
    77	host functions, and nothing says which primitives it assumed.
    78	
    79	The Universal Continuation Format (UCF) is the **lift** of that local record
    80	into a self-describing value that any conforming engine can **lower** back
    81	into its own registers, and the discipline that governs who may lower it.
    82	Three roles participate, and they are stream roles, not parties to a call:
    83	
    84	| Role | Does | Holds |
    85	|---|---|---|
    86	| **Emitter** | lifts a parked configuration into a UCF value and appends it to a medium | nothing, once appended |
    87	| **Carrier** | any stream, file, DHT, or socket that moves the value; sees opaque data | nothing |
    88	| **Resumer** | reads the value, satisfies its declared context, obtains custody, lowers, runs | custody, for the duration of a lease |
    89	
    90	Three consequences are load-bearing. First, **a UCF value is content**: it has
    91	a content address under `dao.jing`, equal values converge on one address, and
    92	carrying it twice is harmless (`dao.jing.md`, *Materialization rule*). Second,
    93	**a UCF value on a stream is not a claim**: a stream has no privileged reader
    94	(`datom.world.md`, *Streams*), so emission transfers nothing. Custody is
    95	arbitrated above the stream, by leases over datoms (§7.7), exactly where
    96	`dao.stream.md` *Explicitly Absent* says a take belongs. Third, **content is
    97	not occurrence**: the same parked computation can be encoded more than once
    98	(sharing thresholds differ, §7.5.3), so the encodings address differently,
    99	while remaining one *checkpoint occurrence*. Content identity is for
   100	deduplication and materialization; occurrence identity is what custody names
   101	(§7.3.4, §7.7). Conflating them was a defect of the first draft; this draft
   102	never does.
   103	
   104	The reference engine for every rule below is `yin.vm.semantic`. Its
   105	configuration ⟨C, E, S, K⟩ with C = ⟨seg, pc, val, St⟩ (`yin.vm.semantic.md`
   106	§4.1) is the **canonical CESK**. A foreign engine conforms by lifting into and
   107	lowering from this configuration, never by extending it.
   108	
   109	## §7.2 Vocabulary and envelope
   110	
   111	UCF facts live under `:yin.k/*` (K for the continuation component of CESK).
   112	A UCF value is one open map dispatching on `:yin.k/type`; a consumer ignores
   113	qualified keys it does not understand, as for every DaoStream envelope. The
   114	value must survive the host codec structurally unchanged: no function, host
   115	object, or live handle anywhere inside it (`dao.stream.md`, *Envelopes*).
   116	Every value it contains is encoded in the disjoint tagged grammar of §7.5 —
   117	never a bare marker map.
   118	
   119	```clojure
   120	{:yin.k/type        :yin.k/continuation
   121	 :yin.k/contract    {:yin.code/contract "v3" :yin.k/version 0}   ; S7.3.3
   122	 :yin.k/id          :segment/sha256-…          ; §7.3.4  content address of this map minus :yin.k/id
   123	 :yin.k/occurrence  :yin.k/o-…                  ; §7.7    the checkpoint occurrence — the lease subject
   124	 :yin.k/origin      {:yin.k/occurrence :yin.k/o-…                        ; predecessor, or absent on a first park
   125	                    :dao.lease/lease   :dao.lease/l-…                     ; the grant it ran under
   126	                    :yin.k/emitter     <attribution>}
   127	 :yin.k/arbitration {:dao.stream/identity … :dao.stream/descriptor …}     ; §7.7.3  the authority medium, named in the value
   128	 :yin.k/frame       {…}                        ; §7.4  the canonical CESK at a safepoint
   129	 :yin.k/values      {…}                        ; §7.5.3  shared subvalues, by content address
   130	 :yin.k/cells       {…}                        ; §7.5.3  cursor cells: logical identity + position
   131	 :yin.k/scheduler   {…}                        ; §7.6.3  fresh-name state and referenced parked records
   132	 :yin.k/requires    {…}                        ; §7.6  dependency closure, with discovery status
   133	 :yin.k/store       {…}                        ; §7.6.2  the store slice restored as an isolated store
   134	 :yin.k/carried     [ … ]                      ; §7.3.4  canonical instruction vectors shipped inline
   135	 :yin.k/policy      :yin.k/exclusive}          ; §7.7  or :yin.k/fork
   136	```
   137	
   138	The parts answer four questions a resumer must be able to ask of the data
   139	alone: *what code* (`:yin.k/requires`, `:yin.k/carried`, and every segment
   140	address inside the frame), *what state* (`:yin.k/frame`, `:yin.k/values`,
   141	`:yin.k/cells`, `:yin.k/store`, `:yin.k/scheduler`), *what world*
   142	(`:yin.k/requires`, `:yin.k/arbitration`), and *who may run it*
   143	(`:yin.k/policy`, `:yin.k/occurrence`, with the custody facts of §7.7 on
   144	their own medium).
   145	
   146	A halted computation travels as the sibling value:
   147	
   148	```clojure
   149	{:yin.k/type      :yin.k/result
   150	 :yin.k/contract  {…}
   573	### 7.4.1 Only safepoints lift, and the table is the machine's
   574	
   575	The linear machine's registers are canonical only *between* instructions.
   576	Inside a `:call` that has popped its operands but not yet pushed a frame, or
   577	inside a superinstruction the loader fused (§6.4), no ⟨C, E, S, K⟩ exists
   578	that both describes the state and is expressible in the §2.4 vocabulary.
   579	UCF resolves this by rule rather than by metadata: **a continuation may be
   580	lifted only at a safepoint**, and a safepoint is a transition of the
   581	reference machine at which the machine parks. The first draft's table
   582	presented itself as complete while omitting the parks that arrive through
   583	`:call` — a called host function that returns an effect blocks exactly as an
   584	instruction effect does, through `apply-call` → `handle-effect` →
   585	`call-park-entries`, and both ordinary and tail calls reach that path
   586	(`yin.vm.semantic`, `apply-call`). The complete set:
   587	
   588	| Safepoint kind | Raised by | Canonical resume pc | Stack in frame (St) | Accumulator on resume |
   589	|---|---|---|---|---|
   590	| explicit park | `:park` | `pc+1` | St at park | the resume value |
   591	| blocked read | `:stream-next` → `blocked` | `pc+1` | St (cursor ref was in `val`) | the read value; `nil` on `end`, `:dao.stream/gap` on gap |
   592	| blocked write | `:stream-put` → `full` | `pc+1` | St minus the popped target ref | the written value, on retry `ok` |
   593	| FFI call, sent | `:ffi-call` → `:dao.stream/ok` | `pc+1` | St minus the popped args | the correlated response's `ok` value; its `error` raises |
   594	| FFI call, retained | `:ffi-call` → `:dao.stream/full` | `pc+1` | St minus the popped args | as sent, once the request is appended and the response correlates |
   595	| effectful call | `:call`/`:tailcall` whose operator yields a blocking effect (`require`, stream-module functions, effect handlers) | `pc+1` | St minus the popped operator and args | the effect's resume value, per its kind as above |
   596	| halt | `:halt`, `:return` with empty K | — | — | not resumable; a `:yin.k/type :yin.k/result` travels instead |
   597	
   598	Two corrections to the first draft's table stand beside the addition.
   599	`:current-continuation` is **not** a safepoint: it does not park — it
   600	reifies `val ← {seg, pc+1, E, St, K}` and continues, so a reified
   601	continuation is a *value class* of the encoding (§7.5.1), never a lift
   602	point. And `pc+1` as the resume pc is verified sound for every row above:
   603	§2.6 requires the final pc to be a terminator, so every listed instruction
   604	has a successor in its own segment.
   605	
   606	The `:yin.k/frame` is exactly the wait-entry shape of `yin.vm.semantic.md`
   607	§3.5, with segments replaced by addresses and values by their portable
   608	encodings (§7.5):
   609	
   610	```clojure
   611	:yin.k/frame
   612	{:yin.k/segment  :segment/sha256-3f1a…
   613	 :yin.k/pc       5                         ; the resume pc, already pc+1
   614	 :yin.k/reason   :next                     ; :park | :next | :put | :ffi
   615	                                          ; | :ffi-request | :call-effect
   616	                                          ; | :link-request | :link-response
   617	                                          ; | :install
   618	 :yin.k/val      nil                       ; accumulator; nil except where §7.4.3 pre-fills it
   619	 :yin.k/stack    [ … ]                     ; St per the table above, encoded
   620	 :yin.k/env      { sym → encoded value }   ; E
   621	 :yin.k/k        [ {:yin.k/frame-type :return                    ; or :eval-call / :request-sent,
   622	                    :yin.k/segment :segment/sha256-…             ; which also carry :yin.k/call-id
   623	                    :yin.k/pc 5 :yin.k/env {…} :yin.k/stack-base 0} … ]   ; K, innermost last
   624	 :yin.k/pending  {…}}                      ; the wait it was in, §7.4.3 — one variant per reason
   625	```
   626	
   627	**Linker safepoints (M4).** Every block marked (linker M4) is specified by
   628	`yin.vm.linker.md`; the private resources table, sealed references and
   629	install child were implemented in M4 (S3 ships r8-r11, the install child
   630	and link-module; S4 ships manifests). The running VM implements the
   631	safepoint and resource semantics; only the UCF lift and lower of parked
   632	link and install entries stays future. The require flow adds three
   633	safepoints to the set above, one per wait state of a `:module/require`
   634	effect that misses the module registry (`yin.vm.linker.md` 7.2, 7.3); a
   635	hit answers without parking. The frame is the effectful-call row's in all
   636	three -- `require` is named there -- and the wait entry moves between the
   637	three reasons without the machine advancing, so the canonical resume pc
   638	and the stack are that row's in every state:
   639	
   640	+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+
   641	| Safepoint kind | Raised by                              | Resume pc | Stack in frame (St)    | Accumulator on resume                            |
   642	+================+========================================+===========+========================+==================================================+
   643	| :link-request  | the :module/require effect on a        | pc+1      | St minus the popped    | no resume from this state: on ok                 |
   644	|                | registry miss, before the link         |           | operator and args      | the entry becomes :link-response                 |
   645	|                | request envelope is appended ok;       |           |                        |                                                  |
   646	|                | a full retry keeps this state          |           |                        |                                                  |
   647	|                | (the :ffi-request discipline)          |           |                        |                                                  |
   648	+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+
   649	| :link-response | the request append's ok; the kept      | pc+1      | St minus the popped    | a matching refusal, or a failed                  |
   650	|                | cursor polls the link response         |           | operator and args      | discharge, raises as the effect's                |
   651	|                | stream for the correlated id           |           |                        | error                                            |
   652	+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+
   653	| :install       | a matching ok response, its            | pc+1      | St minus the popped    | the required module's symbol on                  |
   654	|                | obligations discharged, while          |           | operator and args      | linked; the refusal as the                       |
   655	|                | the install child runs its             |           |                        | effect's error on refused                        |
   656	|                | phases                                 |           |                        |                                                  |
   657	+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+
   658	
   659	The pending variants of the three reasons are in section 7.4.3.
   660	
   661	**Quiescence.** The migration unit is one task: the whole blocked machine
   662	(§7.6.3). Lifting additionally requires the machine's ready-queue to be
   663	empty — a machine with runnable work queued is mid-scheduling, its next
   664	configuration is not the parked one, and lift answers `:yin.k/not-quiescent`.
   665	
   666	### 7.4.2 Safepoint maps for foreign engines: static facts only
   667	
   668	The reference machine needs no metadata because its registers *are* the
   669	canonical configuration. A foreign engine — a register allocator over the
   670	image, a WebAssembly lowering, a JIT — keeps its state in physical locations
   671	of its own choosing, and the first draft promised it reconstruction data
   672	that is not a function of the code: absolute stack depth and environment
   673	key sets at a pc depend on the *activation* — a closure enters with the
   674	caller's residual operand stack and an environment formed from its captured
   675	environment plus arguments (`apply-call`), and §2.6 enforces no equal stack
   676	heights at joins, so the same pc is legitimately reached at many depths.
   677	None of that is derivable, and none of it needs to be: **the frame carries
   678	it.** E, St, and every K frame's `:stack-base` and env travel in the value;
   679	a foreign engine lowers the canonical frame directly.
   680	
   681	What *is* a function of the canonical vector alone, and what such an engine
   682	must publish — **per segment and per engine profile**, derived from the
   683	canonical stream at its own load time — is the static half:
   684	
   685	```clojure
   686	[[sp :yin.safepoint/segment      :segment/sha256-3f1a…]  ; the code, by address
   687	 [sp :yin.safepoint/engine       :wasm32/v1]             ; the engine profile that produced this layout
   688	 [sp :yin.safepoint/pc           5]                      ; canonical resume pc
   689	 [sp :yin.safepoint/stack-effect -1]                     ; Δ|St| across the parking instruction (static)
   690	 [sp :yin.safepoint/lexically-required [x conn]]         ; names this pc can read from E (static)
   691	 [sp :yin.safepoint/layout       {…}]]                   ; engine-owned: physical slot → canonical slot
   692	```
   693	
   694	Rules:
   695	
   696	- `stack-effect` and `lexically-required` are **derived from the canonical
   697	  vector alone**, by the same static walk the linearizer performs (§5.3).
   698	  They are identical for every engine and checkable against the reference
   699	  machine by a conformance test that runs the segment to each safepoint and
   700	  compares the observed deltas and E-reads. Absolute depth, activation
   701	  bases, captured environments, and physical layouts are **not** claimed,
   702	  not derived, and not in this table; they are the frame's business.
   703	- `:yin.safepoint/layout` is opaque to the protocol. It is a memo the engine
   704	  keeps so that its `lift` is a table lookup. It never travels in a UCF
   705	  value; a resumer that is a different engine has its own.
   706	- `lift(engine-state, layout) → canonical frame` and
   707	  `lower(canonical frame, layout) → engine-state` are total on safepoints and
   708	  undefined elsewhere. The conformance obligation is `lift ∘ lower = id` on
   709	  canonical frames, tested against the reference machine's own frames —
   710	  which is possible precisely because the dynamic half rides in the frame.
   711	- A request to lift at a pc that is not a safepoint of the segment returns
   712	  `:yin.k/not-at-safepoint` naming the pc. An engine that wants finer
   713	  interruption granularity must lower the program to a segment that
   714	  *contains* a `:park` where it wants one: that is a different instruction
   715	  record, a different address, and a different profile. An engine may not
   716	  invent safepoints a record does not have, and two engines that disagree
   717	  about the safepoint set of one address are not both conforming.
   718	
   719	Safepoints are datoms, so a query can ask "where can this program be
   720	migrated?" without running it, and a debugger's breakpoint set is a subset of
   721	the safepoint set by construction.
   722	
   723	### 7.4.3 The pending wait travels as data — complete, and resumable elsewhere
   724	
   725	A parked machine was waiting for something. That something is a resource of
  1095	rejected); and a value table entry not referenced by anything reachable.
  1096	Malformed-but-correctly-hashed input fails here, before any lowering.
  1097	
  1098	**Closure markers carry a binding discipline and a store (linker M4).**
  1099	Specified by `yin.vm.linker.md` and implemented in M4. The closure row
  1100	above was written for the named semantic VM alone. The linker's four
  1101	backends split into named kernels (`:yin.ast/code`, `:yin.semantic/code`)
  1102	and positional ones (`:yin.debruijn.code`, `:yin.debruijn.register`), so
  1103	the marker gains a `:yin.k/binding` key with two variants, and a closure
  1104	defined in a linked module carries `:yin.k/store-of`, the manifest address
  1105	of the module store its body resolves against (`yin.vm.linker.md` 7.3):
  1106	
  1107	```clojure
  1108	;; named (:yin.ast/code, :yin.semantic/code)
  1109	{:yin.k/tag :yin.k/closure :yin.k/binding :named
  1110	 :yin.k/segment addr :yin.k/entry pc
  1111	 :yin.k/params [sym ...] :yin.k/env {sym encoded}
  1112	 :yin.k/store-of m}                       ; when module-defined
  1113	
  1114	;; positional (:yin.debruijn.code, :yin.debruijn.register)
  1115	{:yin.k/tag :yin.k/closure :yin.k/binding :positional
  1116	 :yin.k/segment identity :yin.k/entry pc
  1117	 :yin.k/arity n :yin.k/frames [[encoded ...] ...]
  1118	 :yin.k/store-of m}                       ; when module-defined
  1119	```
  1120	
  1121	A `:named` marker lowers only into a named kernel and a `:positional`
  1122	marker only into a positional kernel of the same `:format`; the reverse
  1123	is `:binding-mismatch`, so a lifted slice carried elsewhere fails
  1124	closed rather than being reinterpreted. `:binding-mismatch` is a
  1125	lower-side refusal (`yin.vm.linker.md` 7.3), not a `:yin.k/non-portable`
  1126	kind, so it is not in the closed set of section 7.5.4. In a `:named` marker lifted
  1127	from the AST walker, the segment names the `:lambda` row whose body
  1128	slot is the closure's body and the entry is nil; in a `:positional`
  1129	marker, the segment is the origin image's identity and the entry is
  1130	relative to it. `yin.vm.linker.md` 7.3 states each kernel's lift and
  1131	lower into these shapes, the walker's included, with its
  1132	`:unrooted-body` refusal. The store named by `:yin.k/store-of` travels
  1133	once per slice, not per closure, under the module-store amendment of
  1134	section 7.6.2.
  1135	
  2650	|---|---|
  2651	| No hidden global state | Every dependency is declared in `:yin.k/requires` — *as computed by the declared fixed point, with its completeness stated* (`:yin.k/discovery`); the store travels as an explicit slice restored into an isolated store; fresh-name state and parked records travel explicitly; custody is facts on a named, possessed medium. |
  2652	| No implicit control flow | Resume happens only at a safepoint the machine's parking transitions define; the successor chain is explicit `:yin.k/origin` links; the exporting state is an observed data state, never a notification. |
  2653	| No callbacks | Lift and lower are step functions; custody is observed, never notified; a lapsed holder stops at its own bound; an unanswered proposal is a step status, not a wait. |
  2654	| No shared mutable state | A UCF value is content-addressed and immutable; occurrence identity separates content variants from the one leased computation; two resumers of one value under `:fork` diverge into distinct incarnations; the isolated execution store shares nothing with the resumer's tasks. |
  2655	| No layer collapsing | Canonicalize, encode, discover, arbitrate, load, restore, execute are separate functions with data between them. The engine's hot loop is untouched: lifting is a pure function over the blocked machine's value, and the exporting state is that value with an emptied wait-set and a retained record — driver discipline, not engine machinery. |
  2656	| No assumed graphs | Sharing is an explicit value table keyed by address; cursor aliasing is an explicit cell graph, not inferred from positions; code refs are pcs relative to an addressed vector; nothing is resolved by pointer; foreign engines reconstruct dynamic state from the frame, never from layouts they assume. |
  2657	
  2658	And the axioms — each claim conditioned on the obligation it depends on: the
  2659	continuation is a stream value (1); its meaning is supplied by the
  2660	resumer's loader and contract stamp, not carried in it (2); its code and its
  2661	state are datoms under `:yin.code/*` and `:yin.k/*` (3); and it pauses,
  2662	travels, and resumes anywhere that **holds what it declares, satisfies the
  2663	declared cursor profiles, and obtains custody where the policy demands it**
  2664	(4) — the unconditional form of that sentence was a claim the unresolved
  2665	state closure, custody enforcement, and effect admission had not earned.
  2666	
  2667	## §7.11 Acceptance blockers for the implementation phase
  2668	
  2669	The warning at the top of this file is retained until each of these is
  2670	closed with a test in the parity or conformance suite. The first four are
  2671	the review's named architectural obligations; they are blockers to
  2672	*accepting the design*, not merely work items.
  2673	
  2674	- **Authority grounding and wiring.** The arbitration `dao.space`, its
  2675	  transactor as grantor, the occurrence ledger with epochs, and the
  2676	  attribution resolver `dao.lease.md` requires, are composition duties; the
  2677	  first composition to ship UCF is `yin.repl.core`'s handoff demo, and
  2678	  its wiring becomes the worked example. A composition without a
  2679	  transactable space offers `:yin.k/fork` only.
  2680	- **Execution identity.** Occurrence minting, the `:yin.k/origin` chain,
  2681	  retry idempotence (one occurrence per park), and successor completion as
  2682	  a grantor ledger transition need conformance tests, including the crash
  2683	  windows of §7.7.5.
  2684	- **Pending-state completeness.** Every pending variant of §7.4.3 must
  2685	  round-trip and resume *elsewhere*: retained writes, sent and retained FFI
     1	# yin.vm de Bruijn register VM
     2	
     3	Status: design, revised; not implemented
     4	
     5	This document specifies a register execution path that is a peer of the
     6	committed de Bruijn stack path. Both are projections of the same de Bruijn
     7	encoding of the named semantic tuples; neither is derived from the other.
     8	It does not replace the named datoms, the stack image, the stack VM, or the
     9	dormant projection. The first deliverable is a pure resolved-tuples-to-
    10	register lowerer and validator. The register VM kernel is a committed later
    11	phase, authorized by the owner without a benchmark condition (section 8,
    12	DECIDED 1).
    13	
    14	## 1. Objective and invariants
    15	
    16	The objective is a deterministic register image derived from the de Bruijn
    17	encoding of the named `:yin/*` semantic tuples, at the same level as the
    18	`:yin.debruijn.code/*` stack image. The register lowerer consumes the
    19	resolved tuples and emits a register image; it never reads, rewrites, or
    20	depends on the stack image. The stack image remains independently
    21	addressable by its existing H, and the register image is independently
    22	addressable by its own R.
    23	
    24	This follows the project's established compilation topology
    25	(`docs/agents/architecture.md`, "COMPILATION AS STREAM PROCESSING"): one
    26	upstream stream feeds several parallel projections, and no projection is
    27	compiled from another. That pattern is applied here one level down, at the
    28	de Bruijn layer.
    29	
    30	This path is worth building on its own merits, and the merit is not
    31	performance. The governing reason, in the owner's words, is that it
    32	demonstrates the philosophy that one universal AST can be interpreted by
    33	different VMs: one truth, many interpretations. The named datoms are this
    34	project's single source of truth. The named VM, the stack VM, and the
    35	register VM are not competing implementations with one canonical and the
    36	others alternates; they are peer witnesses to the same truth, each free to
    37	interpret it by its own execution model, bound only by agreement on
    38	observable behavior under the B0 normalizer and never on internal
    39	mechanism. This is not new here. It is axiom 2 of `datom.world.md`,
    40	"Interpretation Creates Semantics ... one truth, many perspectives", made
    41	concrete for evaluators the way its Streams section already makes it
    42	concrete for a `yin.vm` evaluator and `dao.space` reading one stream with
    43	no privileged reader ("one stream, many interpretations, none of them the
    44	stream's own"), and the way `docs/agents/architecture.md`, "COMPILATION AS
    45	STREAM PROCESSING", already states it for backends ("Same datoms, multiple
    46	interpreters ... same wave function, different measurements"). The same
    47	file's "AGENTS" section, under Continuation Migration, already relies on
    48	it: the AST datoms are the canonical payload, bytecode is a projection for
    49	one execution model, and a destination projects the datoms into whatever
    50	model it prefers. A third evaluator over the same datoms is that
    51	established idea with one more witness.
    52	
    53	The specific mechanism for it is the one this epic has been building
    54	toward: a configurable compilation pipeline with `dao.stream` as every
    55	stage boundary, in which several peer executable formats (named, stack,
    56	register) share one upstream de Bruijn encoding and each backend is an
    57	independent, swappable interpreter rather than a hardcoded single
    58	evaluator. The register path also targets an alpha-invariant, content-
    59	addressed encoding rather than reintroducing a VM directly beside the AST
    60	walker. A lowerer alone proves the register format is well defined. Only a
    61	running kernel proves that a second execution backend can be plugged into
    62	the same pipeline and run real programs through it, which is what makes
    63	the philosophy demonstrated rather than asserted. The owner has therefore
    64	authorized the kernel unconditionally; the lowerer and format still ship
    65	first, as their own milestones, and the R3 benchmark reports numbers
    66	without deciding anything.
    67	
    68	The following invariants apply:
    69	
    70	1. A register image is derived data, derived from the resolved tuples. The
    71	   stack image is never consumed, rewritten, or invalidated by the register
    72	   path, and neither image is derived from the other.
    73	2. `dao.stream` is the stage boundary. Every lowering is a pure stream-to-
    74	   stream function; no stage mutates an input stream or retains a callback.
    75	3. H and R are peer format identities, not competing semantic identities.
    76	   H identifies the exact stack bytes; R identifies the exact register bytes
    77	   that a register-capable host can run. Neither preimage mentions the other.
    78	4. Equal R implies equal register bytes and descriptor contract. It does not
    79	   imply that a stack VM can execute those bytes.
    80	5. Register execution must agree with the named semantic VM, and therefore
    81	   with the stack VM, under the B0 normalizer: results, errors, store
    82	   effects, stream outcomes, tail calls, and lexical resolution.
    83	6. All register allocation choices are data-derived and deterministic across
    84	   runs and hosts.
    85	
    86	The existing stack design's D1-D16 decisions remain authoritative. The
    87	register path adds no projection fingerprint and no global registry. B2
    88	is decided as the resolver-and-stack-lowerer split described in section
    89	2.2 below; everything else in `yin.vm.debruijn.stack.md` is unchanged.
    90	
    91	### 1.1 Identity and sharing
    92	
    93	The register descriptor is `:yin.debruijn.register/*`. Its `register-hash`
    94	function, R, hashes the register descriptor hash and the canonical
    95	positional register vector, exactly as B1's `image-hash` hashes the stack
    96	descriptor hash and the canonical stack vector. R is the only identity used
    97	to fetch, verify, cache, or execute register bytes. A linker request must
    98	name both the format and R, for example
    99	`{:format :yin.debruijn.register :hash R}`.
   100	
   101	R is alpha-invariant for binder renames for the same reason H is: the
   102	register vector carries `:load-bound` depth and position operands and
   103	`:closure` arities, and binder names live in a diagnostic side table outside
   104	the hash. Equal R therefore implies agreement on exact scalar spelling, free
   105	name operands, and front-end tail flags, as equal H does. Each format is
   106	deterministically derived from the resolved tuples, so equal resolved
   107	tuples give equal H and equal R, and a binder rename changes neither. No
   108	biconditional between H and R is claimed: two deterministic projections may
   109	preserve different distinctions, and the corpus tests are evidence for the
   110	one-directional law only.
   111	
   112	This is not a second semantic answer to alpha-equivalence. A register
   113	lowerer cannot repair differences in exact scalar spelling, free names, or
   114	front-end tail flags; those differences already make the resolved tuples,
   115	and so both H and R, differ.
   116	
   117	The pairing of an R with the H of the same named root is a composition
   118	fact, carried beside a request or recorded as a datom by whoever publishes
   119	both, never inside either preimage. A receiver verifies a fetched register
   120	image with R alone and never needs to have seen the stack image.
   121	
   122	## 2. Compilation topology and stream contract
   123	
   124	The register projection is a peer of the stack projection. Both branch from
   125	the resolved tuples, the de Bruijn encoding of the named AST datoms:
   126	
   127	    Source -> Yang -> named AST datoms (:yin/*)
   128	                         |\
   129	                         | \-> named semantic image (:yin.code/*)
   130	                         |
   620	|                         |        | tree unavailable) and refuses to trust   |
   621	| `:unauthenticated`      | 8.2    | an assertion carries no proof the        |
   622	|                         |        | composition's verifier accepts           |
   623	| `:export-missing`       | 7.3    | the evaluated module did not bind an     |
   624	|                         |        | export the manifest names                |
   625	| `:require-cycle`        | 7.4    | a child install requires a module that   |
   626	|                         |        | is itself installing in its ancestry     |
   627	| `:foreign-image`        | 7.3    | an exported closure originates in an     |
   628	|                         |        | image this scheduler never verified      |
   629	| `:binding-mismatch`     | 7.3    | a positional closure marker lowered into |
   630	|                         |        | a named kernel, or the reverse           |
   631	| `:pairing-mismatch`     | verify | B6's same-root check (now a case of      |
   632	|                         |        | `:derivation-mismatch`, kept by name)    |
   633	
   634	`:unsupported-format` is the linker's instance of the host-boundary rule
   635	that a host with no implementation for an effect emits a qualified
   636	unsupported result, "a correct outcome, not a gap to be filled"
   637	(`datom.world.md`). It lets a receiver decline an R request and, where
   638	the composition permits, fall back to H by the manifest's derivations.
   639	
   640	A stepped link additionally reports `:pending` (section 6.3), which is
  1325	     ids, so one task's lowered slice would misplace every body pc
  1326	     and alias in the other's. Every waiter is restored with `'foo`
  1327	     as its value after its own attach and lower; so is every later
  1328	     requirer.
  1329	
  1330	  **The closure marker, and its lift and lower per binding discipline.**
  1331	  UCF section 7.5.1 defines the closure marker for the named semantic
  1332	  VM. The de Bruijn kernels are positional and nameless, so the marker
  1333	  gains a `:yin.k/binding` key with two variants, and a module's
  1334	  closures carry the `:yin.k/store-of` key of the module store; both
  1335	  are amendments to UCF listed in section 11, item 12:
  1336	
  1337	  ```clojure
  1338	  ;; named (:yin.ast/code, :yin.semantic/code)
  1339	  {:yin.k/tag :yin.k/closure :yin.k/binding :named
  1340	   :yin.k/segment addr :yin.k/entry pc
  1341	   :yin.k/params [sym ...] :yin.k/env {sym encoded}
  1342	   :yin.k/store-of m}                       ; when module-defined
  1343	
  1344	  ;; positional (:yin.debruijn.code, :yin.debruijn.register)
  1345	  {:yin.k/tag :yin.k/closure :yin.k/binding :positional
  1346	   :yin.k/segment identity :yin.k/entry pc
  1347	   :yin.k/arity n :yin.k/frames [[encoded ...] ...]
  1348	   :yin.k/store-of m}                       ; when module-defined
  1349	  ```
  1350	
  1351	  | Kernel closure                       | Lift                          |
  1352	  |--------------------------------------|-------------------------------|
  1353	  | semantic `{:type :closure :params p  | `addr` = alias column inverse |
  1354	  | :entry e :segment local :env E}`     | of `local`; `pc` = `e`;       |
  1355	  |                                      | `params` = `p`; `env` =       |
  1356	  |                                      | `encode(E)`                   |
  1357	  | walker `{:type :closure :params p    | `addr` = the closure's        |
  1358	  | :body node :lambda id :env E}` (the  | recorded `:lambda` row id,    |
  1359	  | body is an AST node, not a code      | verified against `node` and   |
  1360	  | coordinate)                          | `p` by the rule below;        |
  1361	  |                                      | `pc` = `nil`;                 |
  1362	  |                                      | `params` = `p`; `env` =       |
  1363	  |                                      | `encode(E)`                   |
  1364	  | stack / register `{:type :closure    | offset-table entry with       |
  1365	  | :arity n :body-pc p :frames F}`      | `off <= p < off + len` gives  |
  1366	  |                                      | `identity` and `pc = p - off`;|
  1367	  |                                      | `arity` = `n`; `frames` =     |
  1368	  |                                      | `mapv (mapv encode) F`,       |
  1369	  |                                      | outermost frame first         |
  1370	
  1371	  | Marker variant | Lower into the parent                              |
  1372	  |----------------|----------------------------------------------------|
  1373	  | `:named`,      | `local'` = parent alias column at `addr`; `:entry` |
  1374	  | semantic       | unchanged; `:params` unchanged; `:env` =           |
  1375	  |                | `decode(env)`                                      |
  1376	  | `:named`,      | `:body` = the body slot of the parent's row at     |
  1377	  | walker         | `addr`, reconstructed as a node by the walker's    |
  1378	  |                | row decoder; `:params` unchanged; `:env` =         |
  1379	  |                | `decode(env)`                                      |
  1380	  | `:positional`  | `off'` = parent offset-table entry for `identity`; |
  1381	  |                | `:body-pc` = `off' + pc`; `:arity` = `n`;          |
  1382	  |                | `:frames` = `mapv (mapv decode) frames`            |
  1383	
  1384	  **The walker rule, exactly.** A walker closure holds its body as a
  1385	  live AST node, so its code coordinate is not a field of the closure
  1386	  but a fact about the rows: the body is the `body` slot of a
  1387	  `:lambda` row in the rows the child holds, and that row has an id
  1388	  (its `segment-key`, `yin.vm.code-as-tuples.md` section 4.1). The
  1389	  row is **not** found by the node alone: rows are content-addressed
  1390	  and shared, and `semantic-bytecode->ast` rebuilds "a row reached
  1391	  through several parents ... once and shared", so one body node can
  1392	  sit under several `:lambda` rows with different params, and a
  1393	  `node -> row id` index cannot select among them. The id therefore
  1394	  crosses in two stages, because two different machines build the
  1395	  two values: the row decoder builds AST nodes at load, while a
  1396	  runtime closure is instantiated only when the `:lambda` node is
  1397	  evaluated. At load, the decoder annotates each decoded `:lambda`
  1398	  node with its source row id -- or files it in a side index
  1399	  `lambda-node -> row id`; the rebuild memoizes one node per row
  1400	  id, so the mapping is unambiguous -- and at evaluation the
  1401	  runtime `:lambda` transition (`ast-walker`'s `case :lambda`)
  1402	  copies the annotation into the closure it instantiates. The lift
  1403	  reads the recorded id and verifies the row's body slot is the
  1404	  closure's node and its params are the closure's params; a
  1405	  disagreement is the same `:yin.k/non-portable` `:unrooted-body`
  1406	  refusal as an id in no held row. A closure with no recorded id,
  1407	  which only a host function can fabricate, resolves through the
  1408	  walker's row index keyed by the pair `[node params]` (the index
  1409	  `vm-load-rows` builds when it decodes rows into nodes and
  1410	  `attach-image` extends), and refuses when the pair matches no
  1411	  row. The origin image of a walker closure
  1412	  is the tree whose rows contain that `:lambda` row; the `:lambda`
  1413	  row id is content-addressed, so the same lambda shared by two
  1414	  trees is one id and either tree serves as origin. The lower reads
  1415	  the parent's row at that id (present after act 3) and decodes its
  1416	  body slot into a node through the same decoder the loader uses, so
  1417	  the closure's `:body` in the parent is structurally equal to the
  1418	  child's and its `:params` and decoded `:env` are carried unchanged.
  1419	
  1420	  A `:named` marker lowers only into a named kernel and a `:positional`
  1421	  marker only into a positional kernel of the same `:format`; the
  1422	  reverse is `:binding-mismatch`. This never arises within one install,
  1423	  because the child is a VM of the same backend as the parent (section
  1424	  7.3, first rule); it is stated so that a lifted slice carried
  1425	  elsewhere fails closed rather than being reinterpreted. Converting a
  1426	  closure between disciplines is a re-lowering of source under a
  1427	  derivation record (section 8.1), never a relocation. `decode` and
  1428	  `encode` recurse through frames and environments, so a captured
  1429	  closure is relocated by the same rules and its origin joins the
  1430	  origin set of act 2.
     1	(ns yin.vm.semantic
     2	  "Linear CESK interpreter for executable datoms on DaoStream v2
     3	   (`docs/design/yin.vm.semantic.md`).
     4	
     5	   Code arrives as one `:yin.code/*` segment batch per program value. The
     6	   loader (`load-image`, `vm-load-program`) checks §2.6 well-formedness,
     7	   decodes the batch into an image — one vector indexed by pc of
     8	   `[opcode & operands]` with every ref resolved to a pc integer — and
     9	   stores it under `:code {segment-id image}`. Mnemonics in the datoms stay
    10	   semantic and integers in the image stay mechanical (§2.4): the loader maps
    11	   `:const` onto `:literal` and a `:call` carrying `:yin.code/tail?` onto
    12	   `:tailcall`, and the dispatch below switches on the integers.
    13	
    14	   The hot loop keeps the five registers — segment, pc, accumulator, operand
    15	   stack, continuation — in `loop` locals and touches the record only at
    16	   effect points and exits (§4.3). K is a vector of frames, innermost last:
    17	   `{:type :return :segment :pc :env :stack-base}` for calls, and the wait
    18	   entries the engine polls carry the same `{segment pc env stack k}` shape
    19	   (§3.5). Park writes that map into `:parked`; resume reads it back. Nothing
    20	   in either is a host object except values the program itself put in `env`
    21	   or on the stack. A wait or ready entry is those registers plus `:reason`
    22	   and resource ids — never a resolved stream handle and never a resume
    23	   closure: `engine/check-wait-set` resolves handles from the store on every
    24	   poll and the scheduler restores a woken entry explicitly, so the wait set
    25	   of a blocked machine survives an EDN round-trip once any closure or
    26	   continuation in its registers, a host type (D7, `yin.vm.values`), is
    27	   encoded as the handoff demo encodes it. A segment id is stable:
    28	   loading different code under an id that continuations already name is a
    29	   load error; an identical reload is accepted.
    30	
    31	   `step` is this loop with a fuel of one instruction, so single-stepping and
    32	   full runs execute one transition code path. AST evaluation is not here:
    33	   lowering (`yin.vm.linearize`, Phase 2) is composition-supplied at the
    34	   observer boundary, so `eval` resumes loaded work and refuses an AST."
    35	  (:require [dao.jing :as jing]
    36	            [dao.stream.apply :as apply2]
    37	            [yin.vm :as vm]
    38	            [yin.vm.code :as code]
    39	            [yin.vm.engine :as engine]
    40	            [yin.vm.ffi :as ffi]
    41	            [yin.vm.module :as module]
    42	            [yin.vm.telemetry :as telemetry]
    43	            [yin.vm.ucf :as ucf]
    44	            [yin.vm.values :as values]))
    45	
    46	
    47	;; =============================================================================
    48	;; SemanticVM Record
    49	;; =============================================================================
    50	
    51	(defrecord SemanticVM
    52	  [blocked?       ; boolean, true if blocked
    53	   bridge         ; explicit host-side FFI bridge state
    54	   halted?        ; boolean, true when active continuation has completed
    55	   k              ; continuation, a vector of frames (innermost last) or nil
    56	   program        ; segment id of the last loaded batch
    57	   control        ; {:segment id :pc n} or nil
    58	   env            ; persistent lexical scope map
    59	   stack          ; operand stack, a vector (part of control, §4.1)
    60	   id-counter     ; integer counter for unique IDs
    61	   parked         ; parked continuations map
    62	   primitives     ; primitive operations map
    63	   primitive-profiles ; portable primitive profile projection
    64	   primitive-canonical-names ; declared aliases for identity reverse lookup
    65	   modules        ; module registry value
    66	   make-stream    ; host-supplied stream constructor, or nil
    67	   call-capacity  ; declared capacity of the FFI pair
    68	   ready-queue    ; vector of runnable continuations
    69	   store          ; heap memory map
    70	   value          ; last computed value
    71	   wait-set       ; vector of continuations waiting on a transport
    72	   telemetry      ; telemetry config map ({:stream … :vm-id …}) or nil
    73	   telemetry-step ; telemetry snapshot counter
    74	   telemetry-t    ; telemetry transaction counter
    75	   telemetry-eid  ; telemetry entity-id seed, floored at datom/first-user-id
    76	   vm-model       ; telemetry model keyword
    77	   vm-id          ; telemetry instance id, minted by telemetry/install
    78	   code           ; {segment-id image}; built once per segment, never written
    79	   code-aliases   ; {address segment-id}; additive, one address one id (UCF §7.3.4)
    80	   gc])           ; {:since :base :threshold :pinned}: heap reclamation
    81	
    82	
    83	(declare semantic-restore scheduler-round)
    84	
    85	
    86	;; =============================================================================
    87	;; Register materialization
    88	;; =============================================================================
    89	
    90	(defn- put-registers
    91	  "Write the five registers back into the record. This is the linear machine's
    92	   cesk-return: the one seam where the running configuration becomes an
    93	   observable value, and therefore where per-step trace emission (§3.6) will
    94	   land when telemetry is designed. A nil segment means the machine has no
    95	   control left, which is the halted state.
    96	
    97	   An empty continuation is stored as nil rather than [] so that
    98	   `engine/ready-for-ingress?`, which asks `(nil? (:k vm))`, sees a machine
    99	   between evaluations."
   100	  [vm seg pc val St E K]
   220	      {:stop (assoc state :control nil :k nil)}
   221	      {:continue [state value]})))
   222	
   223	
   224	(defn- apply-call
   225	  "The §4.2 call transition, with the arguments already isolated from the
   226	   operand stack. Returns `{:goto [seg pc val St E K vm image]}` to continue,
   227	   `{:stop vm}` when the call blocked, or throws for a non-function.
   228	
   229	   A tail call grows neither K nor St: the frame the caller would have pushed
   230	   is the frame its own caller gave it. A primitive that yields an effect
   231	   parks with the continuation after the call site."
   232	  [code seg pc val St E K vm tail? f args]
   233	  (case (engine/operator-kind vm f)
   234	    :closure
   235	    (let [c (values/payload f)
   236	          E' (merge (:env c) (engine/bind-params (:params c) args))
   237	          frame {:type :return, :segment seg, :pc (inc pc), :env E,
   238	                 :stack-base (count St)}
   239	          seg' (:segment c)]
   240	      {:goto [seg' (:entry c) val St E' (if tail? K (conj K frame))
   241	              vm (get code seg')]})
   242	    :host-fn
   243	    (let [result (apply f args)]
   244	      (if (module/effect? result)
   245	        (let [{:keys [state value blocked?]}
   246	              (engine/handle-effect
   247	                (put-registers (engine/check-callee-effect! vm f result)
   248	                               seg pc val St E K)
   249	                result
   250	                {:park-entry-fns (call-park-entries seg pc E St K)})]
   251	          (if blocked?
   252	            {:stop (assoc state :control nil :k nil)}
   253	            {:goto [seg (inc pc) value St E K state (get code seg)]}))
   254	        {:goto [seg (inc pc) result St E K vm (get code seg)]}))
   255	    ;; An abortive jump: the call site's St/E/K are dropped and the
   256	    ;; captured registers take over, with the argument in val as the
   257	    ;; capture instruction left it (19). The store is not a register.
   258	    :continuation
   259	    (let [v (engine/continuation-argument args)
   260	          c (values/payload f)
   261	          seg' (:segment c)]
   262	      {:goto [seg' (:pc c) v (:stack c) (:env c) (:k c) vm
   263	              (get code seg')]})))
   264	
   265	
   266	;; =============================================================================
   267	;; The hot loop
   268	;; =============================================================================
   269	
   270	(defn- vm-hot
   271	  "Run loaded work with the registers in loop locals.
   272	
   273	   `fuel`, when non-nil, is a number of instructions to execute before
   274	   materializing: `step` supplies 1 and `run` supplies nil. The loop exits by
   275	   returning a record when the machine halts, parks, blocks on an effect, or
   430	                     (:stop r)))
   431	              ;; :stream-put — target popped from St, value in val (13)
   432	              13 (let [St' (pop St)
   433	                       r (run-effect vm seg pc val St' E K
   434	                                     (module/make-effect :stream/put {:stream (peek St),
   435	                                                                      :val val})
   436	                                     (call-park-entries seg pc E St' K))]
   437	                   (if-let [[vm' val'] (:continue r)]
   438	                     (recur seg (inc pc) val' St' E K vm' image
   439	                            (and fuel (dec fuel)))
   440	                     (:stop r)))
   441	              ;; :stream-cursor — source ref in val (14)
   442	              14 (let [r (run-effect vm seg pc val St E K
   443	                                     (module/make-effect :stream/cursor {:stream val})
   444	                                     nil)]
   445	                   (if-let [[vm' val'] (:continue r)]
   446	                     (recur seg (inc pc) val' St E K vm' image
   447	                            (and fuel (dec fuel)))
   448	                     (:stop r)))
   449	              ;; :stream-next — cursor ref in val (15)
   450	              15 (let [r (run-effect vm seg pc val St E K
   451	                                     (module/make-effect :stream/next {:cursor val})
   452	                                     (call-park-entries seg pc E St K))]
   453	                   (if-let [[vm' val'] (:continue r)]
   454	                     (recur seg (inc pc) val' St E K vm' image
   455	                            (and fuel (dec fuel)))
   456	                     (:stop r)))
   457	              ;; :stream-close — source ref in val (16)
   458	              16 (let [r (run-effect vm seg pc val St E K
   459	                                     (module/make-effect :stream/close {:stream val})
   460	                                     nil)]
   461	                   (if-let [[vm' val'] (:continue r)]
   462	                     (recur seg (inc pc) val' St E K vm' image
   463	                            (and fuel (dec fuel)))
   464	                     (:stop r)))
   465	              ;; :ffi-call — park-and-call over the FFI pair (21)
   466	              21 (let [ffi-op (nth inst 1)
   467	                       argc (nth inst 2)
   468	                       total (count St)
   469	                       args (subvec St (- total argc))
   470	                       St' (subvec St 0 (- total argc))
   471	                       ;; The pair is checked before parking: an error raised
   472	                       ;; after park-continuation would strand a continuation
   473	                       ;; in :parked and consume an id counter.
   474	                       {:keys [call-in]}
   475	                       (ffi/require-call-pair! (:resources vm) ffi-op)
   476	                       ;; the request carries `args` out of the VM's view
   477	                       vm' (put-registers (engine/pin-refs vm args)
   478	                                          seg pc val St' E K)
   479	                       call-id (ffi/call-id vm' (engine/park-id vm'))
   480	                       parked (engine/park-continuation
   481	                                vm'
   482	                                {:segment seg, :pc (inc pc),
   483	                                 :env E, :stack St', :k K}
   484	                                call-id)
   485	                       request (apply2/request call-id ffi-op args)
   486	                       result (ffi/put-request vm' call-in request)]
   487	                   (case (:dao.stream/outcome result)
   488	                     :dao.stream/ok
   489	                     (-> parked
   490	                         (update :wait-set (fnil conj [])
   491	                                 (response-wait-entry seg (inc pc) E St' K
   492	                                                      call-id))
   493	                         (assoc :control nil
   494	                                :k nil
   495	                                :value :yin/blocked
     1	(ns yin.vm.debruijn.register
     2	  "R4 (docs/design/yin.vm.debruijn.register.md S5, S6 R4): the register
     3	   execution kernel that interprets positional register images
     4	   `{:bodies [...], :instructions [...]}` under contract version 4
     5	   (the \"r2\" contract).
     6	
     7	   The register kernel is a sibling to `yin.vm.debruijn.stack` (B3/B4) and
     8	   `yin.vm.semantic`. It preserves B3's frame direction, closure capture,
     9	   nil-fill and extra-argument rules, and B0 normalization. It executes
    10	   only validator-approved images.
    11	
    12	   State: `{:segment :hash :images :pc :frames :free-env :registers
    13	            :continuation :store :blocked? :halted? :wait-set :ready-queue
    14	            :parked :id-counter :value :make-stream :bridge :primitives
    15	            :modules}`.
    16	
    17	   Continuation transport: register continuations and stack continuations
    18	   are not interchangeable (section 5.1). Payloads carry `:format
    19	   :yin.debruijn.register`, `:hash R` of the code space at the time, and
    20	   `:image`, the offset-table row their pc falls in (yin.vm.linker.md
    21	   section 7.3, r6). A foreign format or an `:image` the table does not
    22	   hold is refused with qualified `:continuation-format` diagnostics.
    23	   Resume values must be plain data, refusing host exceptions with
    24	   `:resume-value`.
    25	
    26	   The kernel implements all 23 opcodes, Rule R's `:define` included,
    27	   across the pure-program tier and the
    28	   effects tier, connecting to `yin.vm.engine` via `register-restore` and
    29	   per-site wait-entry builders."
    30	  (:require [dao.stream.apply :as apply2]
    31	            [yin.vm :as vm]
    32	            [yin.vm.debruijn-register-code :as rcode]
    33	            [yin.vm.debruijn-register-effects :as effects]
    34	            [yin.vm.engine :as engine]
    35	            [yin.vm.ffi :as ffi]
    36	            [yin.vm.module :as module]
    37	            [yin.vm.values :as values]))
    38	
    39	
    40	;; =============================================================================
    41	;; State
    42	;; =============================================================================
    43	
    44	(defrecord DebruijnRegisterVM
    45	  [segment      ; {:bodies [...], :instructions [...]}
    46	   hash         ; R of segment (yin.vm.debruijn-register-code/register-hash)
    47	   images       ; offset table: [[identity offset length] ...], base first
    48	   pc           ; program counter into :instructions
    49	   frames       ; positional lexical frame stack, outermost first
    50	   free-env     ; initial free-name environment map
    51	   registers    ; persistent vector for current body's register file
    52	   continuation ; vector of return frames (innermost last)
    53	   store        ; heap map
    54	   blocked?     ; engine: boolean
    55	   halted?      ; engine: boolean
    56	   wait-set     ; engine: vector of wait entries
    57	   ready-queue  ; engine: vector of woken entries
    58	   parked       ; engine: map of parked continuations
    59	   id-counter   ; engine: counter for gensym, stream, and park ids
    60	   value        ; engine: last computed value
    61	   make-stream  ; host stream constructor or nil
    62	   bridge       ; host FFI bridge or nil
    63	   primitives   ; primitive registry for :load-free
    64	   modules      ; module registry for :load-free and effect dispatch
    65	   store-of     ; the running body's module store address, or nil
    66	   resources    ; private engine resources: the link pair
    67	   origin       ; this task's origin tag for link ids
    68	   ancestry     ; the modules installing on this task's install chain
    69	   gc])         ; {:since :base :threshold :pinned}: heap reclamation
    70	
    71	
    72	(def format-tag
    73	  "The model tag every register payload carries as `:format`."
    74	  effects/format-tag)
    75	
    76	
    77	;; =============================================================================
    78	;; Body & frame addressing
    79	;; =============================================================================
    80	
    81	(defn- body-of-pc
    82	  "Return the declared body map covering instruction `pc` in `segment`."
    83	  [{:keys [bodies]} pc]
    84	  (some (fn [{:keys [start end] :as body}]
    85	          (when (and (<= start pc) (<= pc end)) body))
    86	        bodies))
    87	
    88	
    89	(defn- frame-value
    90	  "Frame 0 for `:load-bound` is the INNERMOST frame, read from the END of
   130	  "Install `segment` as the one image. A non-empty image must pass the
   131	   register validator. The offset table restarts at the base row
   132	   `[R 0 n]`."
   133	  [vm segment]
   134	  (let [empty-seg? (empty-segment? segment)]
   135	    (if empty-seg?
   136	      (assoc vm
   137	             :segment empty-image
   138	             :hash nil
   139	             :images [[(rcode/register-hash empty-image) 0 0]]
   140	             :pc 0
   141	             :frames []
   142	             :registers []
   143	             :continuation []
   144	             :halted? true
   145	             :blocked? false
   146	             :value nil)
   147	      (if-let [defect (rcode/register-image-defect segment)]
   148	        (throw (ex-info (str "Invalid register image: " (:rule defect))
   149	                        defect))
   150	        (let [body0 (first (:bodies segment))
   151	              reg-count (or (:registers body0) 0)
   152	              r (rcode/register-hash segment)]
   153	          (assoc vm
   154	                 :segment segment
   155	                 :hash r
   156	                 :images [[r 0 (count (:instructions segment))]]
   157	                 :pc 0
   158	                 :frames []
   159	                 :registers (vec (repeat reg-count nil))
   160	                 :continuation []
   161	                 :halted? false
   162	                 :blocked? false
   163	                 :value nil))))))
   164	
   165	
   166	(defn load-image
   167	  "Load `segment` (a register image `{:bodies [...], :instructions [...]}`)
   168	   into `vm` as its one image: R is computed, the registers are reset to
   169	   pc 0 with empty frames and continuation, and the machine is running
   170	   unless the segment is empty. The offset table restarts at `[R 0 n]`, so
   171	   a continuation parked under an earlier image names no row of it and
   172	   `register-restore` refuses it.
   173	
   174	   `contract` is required and compared with `vm/register-contract` first
   175	   (`:contract-missing`, `:contract-mismatch`); a non-empty image must then
   176	   pass the register validator, Rule R included. An empty segment admits
   177	   no code and needs neither."
   178	  [vm segment contract]
   179	  (when-not (empty-segment? segment)
   180	    (vm/check-contract! vm/register-contract contract))
   181	  (install-image vm segment))
   182	
   183	
   184	(defn relocate
   185	  "Shift every `:pc`-kind operand of `inst` by `offset`."
   186	  [offset inst]
   187	  (reduce (fn [inst [i [_ kind]]]
   188	            (if (= :pc kind) (update inst (inc i) + offset) inst))
   189	          inst
   190	          (map-indexed vector (get rcode/opcode-table (nth inst 0)))))

codex
The docs make an important distinction: Register VM images are a positional encoding of the shared semantics, while the semantic VM’s named CESK state is the proposed canonical continuation representation. I’m checking the precise conversion and refusal contracts now, including whether current code claims UCF lift/lower support.
exec
/bin/zsh -lc "nl -ba docs/design/yin.vm.debruijn.register.md | sed -n '1,95p' && nl -ba docs/design/yin.vm.linker.md | sed -n '620,635p;1325,1430p' && nl -ba docs/design/yin.vm.universal-continuation-format.md | sed -n '1100,1135p;2650,2680p;2820,2845p' && nl -ba docs/design/datom.world.md | sed -n '1,85p' && nl -ba docs/design/yin.vm.semantic.md | sed -n '35,75p;490,530p;630,640p' && rg -n \"defn (compile|linearize|register|.*lower|.*lift)|universal|register-image|datoms->ast|binding-mismatch\" src/cljc/yin/vm/ucf* src/cljc/yin/vm/debruijn* src/cljc/yin/vm/linearize* src/cljc/yin/vm/semantic.cljc" in /Users/sto/workspace/datomworld
 succeeded in 0ms:
     1	# yin.vm de Bruijn register VM
     2	
     3	Status: design, revised; not implemented
     4	
     5	This document specifies a register execution path that is a peer of the
     6	committed de Bruijn stack path. Both are projections of the same de Bruijn
     7	encoding of the named semantic tuples; neither is derived from the other.
     8	It does not replace the named datoms, the stack image, the stack VM, or the
     9	dormant projection. The first deliverable is a pure resolved-tuples-to-
    10	register lowerer and validator. The register VM kernel is a committed later
    11	phase, authorized by the owner without a benchmark condition (section 8,
    12	DECIDED 1).
    13	
    14	## 1. Objective and invariants
    15	
    16	The objective is a deterministic register image derived from the de Bruijn
    17	encoding of the named `:yin/*` semantic tuples, at the same level as the
    18	`:yin.debruijn.code/*` stack image. The register lowerer consumes the
    19	resolved tuples and emits a register image; it never reads, rewrites, or
    20	depends on the stack image. The stack image remains independently
    21	addressable by its existing H, and the register image is independently
    22	addressable by its own R.
    23	
    24	This follows the project's established compilation topology
    25	(`docs/agents/architecture.md`, "COMPILATION AS STREAM PROCESSING"): one
    26	upstream stream feeds several parallel projections, and no projection is
    27	compiled from another. That pattern is applied here one level down, at the
    28	de Bruijn layer.
    29	
    30	This path is worth building on its own merits, and the merit is not
    31	performance. The governing reason, in the owner's words, is that it
    32	demonstrates the philosophy that one universal AST can be interpreted by
    33	different VMs: one truth, many interpretations. The named datoms are this
    34	project's single source of truth. The named VM, the stack VM, and the
    35	register VM are not competing implementations with one canonical and the
    36	others alternates; they are peer witnesses to the same truth, each free to
    37	interpret it by its own execution model, bound only by agreement on
    38	observable behavior under the B0 normalizer and never on internal
    39	mechanism. This is not new here. It is axiom 2 of `datom.world.md`,
    40	"Interpretation Creates Semantics ... one truth, many perspectives", made
    41	concrete for evaluators the way its Streams section already makes it
    42	concrete for a `yin.vm` evaluator and `dao.space` reading one stream with
    43	no privileged reader ("one stream, many interpretations, none of them the
    44	stream's own"), and the way `docs/agents/architecture.md`, "COMPILATION AS
    45	STREAM PROCESSING", already states it for backends ("Same datoms, multiple
    46	interpreters ... same wave function, different measurements"). The same
    47	file's "AGENTS" section, under Continuation Migration, already relies on
    48	it: the AST datoms are the canonical payload, bytecode is a projection for
    49	one execution model, and a destination projects the datoms into whatever
    50	model it prefers. A third evaluator over the same datoms is that
    51	established idea with one more witness.
    52	
    53	The specific mechanism for it is the one this epic has been building
    54	toward: a configurable compilation pipeline with `dao.stream` as every
    55	stage boundary, in which several peer executable formats (named, stack,
    56	register) share one upstream de Bruijn encoding and each backend is an
    57	independent, swappable interpreter rather than a hardcoded single
    58	evaluator. The register path also targets an alpha-invariant, content-
    59	addressed encoding rather than reintroducing a VM directly beside the AST
    60	walker. A lowerer alone proves the register format is well defined. Only a
    61	running kernel proves that a second execution backend can be plugged into
    62	the same pipeline and run real programs through it, which is what makes
    63	the philosophy demonstrated rather than asserted. The owner has therefore
    64	authorized the kernel unconditionally; the lowerer and format still ship
    65	first, as their own milestones, and the R3 benchmark reports numbers
    66	without deciding anything.
    67	
    68	The following invariants apply:
    69	
    70	1. A register image is derived data, derived from the resolved tuples. The
    71	   stack image is never consumed, rewritten, or invalidated by the register
    72	   path, and neither image is derived from the other.
    73	2. `dao.stream` is the stage boundary. Every lowering is a pure stream-to-
    74	   stream function; no stage mutates an input stream or retains a callback.
    75	3. H and R are peer format identities, not competing semantic identities.
    76	   H identifies the exact stack bytes; R identifies the exact register bytes
    77	   that a register-capable host can run. Neither preimage mentions the other.
    78	4. Equal R implies equal register bytes and descriptor contract. It does not
    79	   imply that a stack VM can execute those bytes.
    80	5. Register execution must agree with the named semantic VM, and therefore
    81	   with the stack VM, under the B0 normalizer: results, errors, store
    82	   effects, stream outcomes, tail calls, and lexical resolution.
    83	6. All register allocation choices are data-derived and deterministic across
    84	   runs and hosts.
    85	
    86	The existing stack design's D1-D16 decisions remain authoritative. The
    87	register path adds no projection fingerprint and no global registry. B2
    88	is decided as the resolver-and-stack-lowerer split described in section
    89	2.2 below; everything else in `yin.vm.debruijn.stack.md` is unchanged.
    90	
    91	### 1.1 Identity and sharing
    92	
    93	The register descriptor is `:yin.debruijn.register/*`. Its `register-hash`
    94	function, R, hashes the register descriptor hash and the canonical
    95	positional register vector, exactly as B1's `image-hash` hashes the stack
   620	|                         |        | tree unavailable) and refuses to trust   |
   621	| `:unauthenticated`      | 8.2    | an assertion carries no proof the        |
   622	|                         |        | composition's verifier accepts           |
   623	| `:export-missing`       | 7.3    | the evaluated module did not bind an     |
   624	|                         |        | export the manifest names                |
   625	| `:require-cycle`        | 7.4    | a child install requires a module that   |
   626	|                         |        | is itself installing in its ancestry     |
   627	| `:foreign-image`        | 7.3    | an exported closure originates in an     |
   628	|                         |        | image this scheduler never verified      |
   629	| `:binding-mismatch`     | 7.3    | a positional closure marker lowered into |
   630	|                         |        | a named kernel, or the reverse           |
   631	| `:pairing-mismatch`     | verify | B6's same-root check (now a case of      |
   632	|                         |        | `:derivation-mismatch`, kept by name)    |
   633	
   634	`:unsupported-format` is the linker's instance of the host-boundary rule
   635	that a host with no implementation for an effect emits a qualified
  1325	     ids, so one task's lowered slice would misplace every body pc
  1326	     and alias in the other's. Every waiter is restored with `'foo`
  1327	     as its value after its own attach and lower; so is every later
  1328	     requirer.
  1329	
  1330	  **The closure marker, and its lift and lower per binding discipline.**
  1331	  UCF section 7.5.1 defines the closure marker for the named semantic
  1332	  VM. The de Bruijn kernels are positional and nameless, so the marker
  1333	  gains a `:yin.k/binding` key with two variants, and a module's
  1334	  closures carry the `:yin.k/store-of` key of the module store; both
  1335	  are amendments to UCF listed in section 11, item 12:
  1336	
  1337	  ```clojure
  1338	  ;; named (:yin.ast/code, :yin.semantic/code)
  1339	  {:yin.k/tag :yin.k/closure :yin.k/binding :named
  1340	   :yin.k/segment addr :yin.k/entry pc
  1341	   :yin.k/params [sym ...] :yin.k/env {sym encoded}
  1342	   :yin.k/store-of m}                       ; when module-defined
  1343	
  1344	  ;; positional (:yin.debruijn.code, :yin.debruijn.register)
  1345	  {:yin.k/tag :yin.k/closure :yin.k/binding :positional
  1346	   :yin.k/segment identity :yin.k/entry pc
  1347	   :yin.k/arity n :yin.k/frames [[encoded ...] ...]
  1348	   :yin.k/store-of m}                       ; when module-defined
  1349	  ```
  1350	
  1351	  | Kernel closure                       | Lift                          |
  1352	  |--------------------------------------|-------------------------------|
  1353	  | semantic `{:type :closure :params p  | `addr` = alias column inverse |
  1354	  | :entry e :segment local :env E}`     | of `local`; `pc` = `e`;       |
  1355	  |                                      | `params` = `p`; `env` =       |
  1356	  |                                      | `encode(E)`                   |
  1357	  | walker `{:type :closure :params p    | `addr` = the closure's        |
  1358	  | :body node :lambda id :env E}` (the  | recorded `:lambda` row id,    |
  1359	  | body is an AST node, not a code      | verified against `node` and   |
  1360	  | coordinate)                          | `p` by the rule below;        |
  1361	  |                                      | `pc` = `nil`;                 |
  1362	  |                                      | `params` = `p`; `env` =       |
  1363	  |                                      | `encode(E)`                   |
  1364	  | stack / register `{:type :closure    | offset-table entry with       |
  1365	  | :arity n :body-pc p :frames F}`      | `off <= p < off + len` gives  |
  1366	  |                                      | `identity` and `pc = p - off`;|
  1367	  |                                      | `arity` = `n`; `frames` =     |
  1368	  |                                      | `mapv (mapv encode) F`,       |
  1369	  |                                      | outermost frame first         |
  1370	
  1371	  | Marker variant | Lower into the parent                              |
  1372	  |----------------|----------------------------------------------------|
  1373	  | `:named`,      | `local'` = parent alias column at `addr`; `:entry` |
  1374	  | semantic       | unchanged; `:params` unchanged; `:env` =           |
  1375	  |                | `decode(env)`                                      |
  1376	  | `:named`,      | `:body` = the body slot of the parent's row at     |
  1377	  | walker         | `addr`, reconstructed as a node by the walker's    |
  1378	  |                | row decoder; `:params` unchanged; `:env` =         |
  1379	  |                | `decode(env)`                                      |
  1380	  | `:positional`  | `off'` = parent offset-table entry for `identity`; |
  1381	  |                | `:body-pc` = `off' + pc`; `:arity` = `n`;          |
  1382	  |                | `:frames` = `mapv (mapv decode) frames`            |
  1383	
  1384	  **The walker rule, exactly.** A walker closure holds its body as a
  1385	  live AST node, so its code coordinate is not a field of the closure
  1386	  but a fact about the rows: the body is the `body` slot of a
  1387	  `:lambda` row in the rows the child holds, and that row has an id
  1388	  (its `segment-key`, `yin.vm.code-as-tuples.md` section 4.1). The
  1389	  row is **not** found by the node alone: rows are content-addressed
  1390	  and shared, and `semantic-bytecode->ast` rebuilds "a row reached
  1391	  through several parents ... once and shared", so one body node can
  1392	  sit under several `:lambda` rows with different params, and a
  1393	  `node -> row id` index cannot select among them. The id therefore
  1394	  crosses in two stages, because two different machines build the
  1395	  two values: the row decoder builds AST nodes at load, while a
  1396	  runtime closure is instantiated only when the `:lambda` node is
  1397	  evaluated. At load, the decoder annotates each decoded `:lambda`
  1398	  node with its source row id -- or files it in a side index
  1399	  `lambda-node -> row id`; the rebuild memoizes one node per row
  1400	  id, so the mapping is unambiguous -- and at evaluation the
  1401	  runtime `:lambda` transition (`ast-walker`'s `case :lambda`)
  1402	  copies the annotation into the closure it instantiates. The lift
  1403	  reads the recorded id and verifies the row's body slot is the
  1404	  closure's node and its params are the closure's params; a
  1405	  disagreement is the same `:yin.k/non-portable` `:unrooted-body`
  1406	  refusal as an id in no held row. A closure with no recorded id,
  1407	  which only a host function can fabricate, resolves through the
  1408	  walker's row index keyed by the pair `[node params]` (the index
  1409	  `vm-load-rows` builds when it decodes rows into nodes and
  1410	  `attach-image` extends), and refuses when the pair matches no
  1411	  row. The origin image of a walker closure
  1412	  is the tree whose rows contain that `:lambda` row; the `:lambda`
  1413	  row id is content-addressed, so the same lambda shared by two
  1414	  trees is one id and either tree serves as origin. The lower reads
  1415	  the parent's row at that id (present after act 3) and decodes its
  1416	  body slot into a node through the same decoder the loader uses, so
  1417	  the closure's `:body` in the parent is structurally equal to the
  1418	  child's and its `:params` and decoded `:env` are carried unchanged.
  1419	
  1420	  A `:named` marker lowers only into a named kernel and a `:positional`
  1421	  marker only into a positional kernel of the same `:format`; the
  1422	  reverse is `:binding-mismatch`. This never arises within one install,
  1423	  because the child is a VM of the same backend as the parent (section
  1424	  7.3, first rule); it is stated so that a lifted slice carried
  1425	  elsewhere fails closed rather than being reinterpreted. Converting a
  1426	  closure between disciplines is a re-lowering of source under a
  1427	  derivation record (section 8.1), never a relocation. `decode` and
  1428	  `encode` recurse through frames and environments, so a captured
  1429	  closure is relocated by the same rules and its origin joins the
  1430	  origin set of act 2.
  1100	above was written for the named semantic VM alone. The linker's four
  1101	backends split into named kernels (`:yin.ast/code`, `:yin.semantic/code`)
  1102	and positional ones (`:yin.debruijn.code`, `:yin.debruijn.register`), so
  1103	the marker gains a `:yin.k/binding` key with two variants, and a closure
  1104	defined in a linked module carries `:yin.k/store-of`, the manifest address
  1105	of the module store its body resolves against (`yin.vm.linker.md` 7.3):
  1106	
  1107	```clojure
  1108	;; named (:yin.ast/code, :yin.semantic/code)
  1109	{:yin.k/tag :yin.k/closure :yin.k/binding :named
  1110	 :yin.k/segment addr :yin.k/entry pc
  1111	 :yin.k/params [sym ...] :yin.k/env {sym encoded}
  1112	 :yin.k/store-of m}                       ; when module-defined
  1113	
  1114	;; positional (:yin.debruijn.code, :yin.debruijn.register)
  1115	{:yin.k/tag :yin.k/closure :yin.k/binding :positional
  1116	 :yin.k/segment identity :yin.k/entry pc
  1117	 :yin.k/arity n :yin.k/frames [[encoded ...] ...]
  1118	 :yin.k/store-of m}                       ; when module-defined
  1119	```
  1120	
  1121	A `:named` marker lowers only into a named kernel and a `:positional`
  1122	marker only into a positional kernel of the same `:format`; the reverse
  1123	is `:binding-mismatch`, so a lifted slice carried elsewhere fails
  1124	closed rather than being reinterpreted. `:binding-mismatch` is a
  1125	lower-side refusal (`yin.vm.linker.md` 7.3), not a `:yin.k/non-portable`
  1126	kind, so it is not in the closed set of section 7.5.4. In a `:named` marker lifted
  1127	from the AST walker, the segment names the `:lambda` row whose body
  1128	slot is the closure's body and the entry is nil; in a `:positional`
  1129	marker, the segment is the origin image's identity and the entry is
  1130	relative to it. `yin.vm.linker.md` 7.3 states each kernel's lift and
  1131	lower into these shapes, the walker's included, with its
  1132	`:unrooted-body` refusal. The store named by `:yin.k/store-of` travels
  1133	once per slice, not per closure, under the module-store amendment of
  1134	section 7.6.2.
  1135	
  2650	|---|---|
  2651	| No hidden global state | Every dependency is declared in `:yin.k/requires` — *as computed by the declared fixed point, with its completeness stated* (`:yin.k/discovery`); the store travels as an explicit slice restored into an isolated store; fresh-name state and parked records travel explicitly; custody is facts on a named, possessed medium. |
  2652	| No implicit control flow | Resume happens only at a safepoint the machine's parking transitions define; the successor chain is explicit `:yin.k/origin` links; the exporting state is an observed data state, never a notification. |
  2653	| No callbacks | Lift and lower are step functions; custody is observed, never notified; a lapsed holder stops at its own bound; an unanswered proposal is a step status, not a wait. |
  2654	| No shared mutable state | A UCF value is content-addressed and immutable; occurrence identity separates content variants from the one leased computation; two resumers of one value under `:fork` diverge into distinct incarnations; the isolated execution store shares nothing with the resumer's tasks. |
  2655	| No layer collapsing | Canonicalize, encode, discover, arbitrate, load, restore, execute are separate functions with data between them. The engine's hot loop is untouched: lifting is a pure function over the blocked machine's value, and the exporting state is that value with an emptied wait-set and a retained record — driver discipline, not engine machinery. |
  2656	| No assumed graphs | Sharing is an explicit value table keyed by address; cursor aliasing is an explicit cell graph, not inferred from positions; code refs are pcs relative to an addressed vector; nothing is resolved by pointer; foreign engines reconstruct dynamic state from the frame, never from layouts they assume. |
  2657	
  2658	And the axioms — each claim conditioned on the obligation it depends on: the
  2659	continuation is a stream value (1); its meaning is supplied by the
  2660	resumer's loader and contract stamp, not carried in it (2); its code and its
  2661	state are datoms under `:yin.code/*` and `:yin.k/*` (3); and it pauses,
  2662	travels, and resumes anywhere that **holds what it declares, satisfies the
  2663	declared cursor profiles, and obtains custody where the policy demands it**
  2664	(4) — the unconditional form of that sentence was a claim the unresolved
  2665	state closure, custody enforcement, and effect admission had not earned.
  2666	
  2667	## §7.11 Acceptance blockers for the implementation phase
  2668	
  2669	The warning at the top of this file is retained until each of these is
  2670	closed with a test in the parity or conformance suite. The first four are
  2671	the review's named architectural obligations; they are blockers to
  2672	*accepting the design*, not merely work items.
  2673	
  2674	- **Authority grounding and wiring.** The arbitration `dao.space`, its
  2675	  transactor as grantor, the occurrence ledger with epochs, and the
  2676	  attribution resolver `dao.lease.md` requires, are composition duties; the
  2677	  first composition to ship UCF is `yin.repl.core`'s handoff demo, and
  2678	  its wiring becomes the worked example. A composition without a
  2679	  transactable space offers `:yin.k/fork` only.
  2680	- **Execution identity.** Occurrence minting, the `:yin.k/origin` chain,
  2820	`owed-control-write?` is pure and total: proposals, pending renewals, releases,
  2821	eligible offers/reports, and required diagnostic/cleanup remain owed until the
  2822	appropriate authenticated evidence discharges them. Inbound acceptance alone
  2823	does not discharge a bracket. Future renewal deadlines, passive ledger waits,
  2824	and uncertain-journal stalls are not executable writes.
  2825	Pending eligible renewal or release remains owed during inbox unavailability;
  2826	unavailable history alone is not an owed write. D15 owns production
  2827	positional adapters and REPL integration; memory-backed test reconstruction is
  2828	simulated process recovery, not proof of durable media.
  2829	
  2830	### 7.11.1 Blocker-closure acceptance matrix
  2831	
  2832	These are observable test contracts, not a declaration that the blockers
  2833	are closed. M3 and M4 tests run on JVM, CLJS (Node), and CLJD. A named
  2834	refusal must be a data outcome before a code image is loaded, a machine is
  2835	restored, or an effect is committed, as applicable. The M4 lift driver
  2836	must consume the UCF table amendments in linker section 11 item 12 before
  2837	its round-trip tests run. Full UCF acceptance waits for every row,
  2838	including the post-M5 gates; passing a linker milestone alone is not a
  2839	claim that all five blockers have closed.
  2840	
  2841	**Code identity (7.3; blocker 5).**
  2842	
  2843	- Invariant: A resumer executes the instruction stream named by the
  2844	  continuation's address and contract stamp, or refuses before loading it.
  2845	- Setup: On each host, make equal resolved vectors from batches with
     1	---
     2	description: Core philosophy, foundational axioms, invariants, primitives (tuples, streams, host boundaries), architectural commitments, design principles, code style, and development workflow for datom.world
     3	---
     4	
     5	# DATOM.WORLD ARCHITECTURAL FOUNDATIONS & DEVELOPMENT RULES
     6	
     7	## Core Philosophy
     8	
     9	Everything is data.
    10	Interpretation > abstraction.
    11	Explicit causality > implicit assumptions.
    12	Restrictions are a feature.
    13	
    14	### Foundational Axioms
    15	
    16	1. **Everything is a Stream**: All IO and data flow through append-only streams.
    17	2. **Interpretation Creates Semantics**: Data is syntax; semantics only emerge through interpretation (one truth, many perspectives).
    18	   At system scale, a "system" is an emergent property of interpreters of
    19	   pure data, not an object, service, or stored artifact. Find the
    20	   interpreters and the data they share to find the system.
    21	3. **Code and State are Datoms**: Code (Universal AST) and runtime execution state are structured datoms, enabling syntax independence and structural reflection.
    22	4. **Everything is a Continuation**: Computation is modeled as serializable, portable continuations that can pause, travel across streams, and resume anywhere.
    23	
    24	## Non-Negotiable Invariants
    25	
    26	- Do not introduce hidden global state.
    27	- Do not introduce implicit control flow.
    28	- Do not introduce callbacks; every callback is an event on a stream.
    29	- Do not introduce shared mutable state.
    30	- Do not collapse interpretation and execution into the same layer.
    31	- Do not assume graphs: graphs must be constructed explicitly from tuples.
    32	
    33	## Tuples and Datoms
    34	
    35	Tuples are elements in an open moduli space, graded by dimension $n$. A tuple can be any dimension/size.
    36	A datom is the canonical persistent tuple `[e a v t m]` (entity, attribute, value, transaction, metadata). Persistent facts are immutable datoms.
    37	Source scope belongs to the interpreter, never to an appended tuple slot. Queries keep physical sources as separate db-values and express union or joins explicitly.
    38	
    39	See [`docs/design/datom.md`](./datom.md) for full specification.
    40	
    41	## Streams
    42	
    43	All IO is modeled as a stream. Functions consume streams and produce streams.
    44	No direct function-to-function coupling without a stream boundary.
    45	Side effects must appear as stream emissions.
    46	Streams are values that can be sent through streams.
    47	Streams carry whatever values the consumer needs: no datom requirement, no privileged payload shape.
    48	
    49	A stream has no privileged reader, and its meaning is not in it. Any number
    50	of interpreters may observe the same medium, and each constructs its own
    51	semantics from the same batches: a `yin.vm` evaluator observing a program
    52	stream constructs CESK state and executes it; `dao.space.index` observing
    53	the very same stream materializes covered indexes, so `dao.space.query/q`
    54	answers Datalog over the program as it grows. Neither knows the other
    55	exists. The medium is their only coupling, and each observer's ignorance of
    56	the other is load-bearing: the evaluator never consults an index to run,
    57	and the index never evaluates a form to index it. This is how axiom 2
    58	becomes architecture: one stream, many interpretations, none of them
    59	the stream's own.
    60	
    61	Functions can take anything and return anything.
    62	Agents are functions, closures, or continuations in `yin.vm` that communicate with the outside world through `dao.stream`. Agent behavior is specified entirely through stream effects.
    63	
    64	See [`docs/design/dao.stream.md`](./dao.stream.md) for full stream specification.
    65	See [`docs/design/yin.vm.streams-all-the-way-down.md`](./yin.vm.streams-all-the-way-down.md) for VM architecture.
    66	
    67	### Host Boundaries
    68	
    69	A host boundary is a stream boundary, never a function call. The one
    70	sanctioned function-shaped contact is a `dao.stream` handle operation, whose
    71	exhaustive outcome map is stream protocol, not an application callback.
    72	
    73	**Effects out.** Portable code appends an effect value describing what must
    74	happen. A host interpreter consumes that stream, performs the operation, and
    75	appends the outcome. Portable code reads the outcome. Correlation is by
    76	identity in the data; neither side holds a reference to the other. A host with
    77	no implementation for an effect emits a qualified unsupported result. That is a
    78	correct outcome, not a gap to be filled.
    79	
    80	**Events in.** When the host initiates (a socket receives, a connection drops,
    81	a timer fires) the adapter appends one plain-data event and returns. It invokes
    82	nothing. An interpreter reads that stream and decides what the event means.
    83	Constructively: **a callback is replaced by a function that transforms an event
    84	to data on a stream.** The host's demand for a callback stops at that function.
    85	
    35	**Interpretation Creates Semantics.** `[e :yin.code/op :call]` means nothing
    36	until `yin.vm.semantic` reads it. The same datoms can be read by a query
    37	("all call sites of this closure"), a renderer (back to source through
    38	`:yin.code/source`), a verifier (every block ends in a terminator), or a
    39	different VM. The mapping from the keyword mnemonic in the datom to the
    40	integer opcode in the image is the loader's interpretation, not a fact in
    41	the datom. `:yin.code/tail?` is a property of a call in the datoms; only the
    42	image decides it is a distinct `tailcall` dispatch.
    43	
    44	**Code and State are Datoms.** Instructions are entities with attributes.
    45	Execution state is plain data: the control is `{:segment id :pc n}`, the
    46	environment a map, the operand stack a vector, the continuation a vector of
    47	frames each `{:pc :env :stack-base :segment}`. The optional trace vocabulary
    48	(§3.6) deposits exactly these as `[e a v t m]` with `t` the step counter,
    49	the "endgame" of `cesk-space-optimization.md` §6: execute fast, emit the
    50	machine image as datoms rather than interpret from them.
    51	
    52	**Everything is a Continuation.** Park writes `{segment pc env stack k}` into
    53	`:parked`; resume reads it back. Nothing in that map is a host object except
    54	values the program itself put in `env` or on the stack (host functions
    55	resolved from primitives, stream handles from the store), which is the same
    56	serialization limit the walker has today. A continuation shipped through a
    57	stream names its segment by id; the receiving host resolves the id against
    58	segment datoms it holds or was sent alongside.
    59	
    60	### 1.2 The six invariants
    61	
    62	+--------------------------+----------------------------------------------------------------------------------+
    63	| Invariant                | How the design honors it                                                         |
    64	+--------------------------+----------------------------------------------------------------------------------+
    65	| No hidden global state   | Opcode table is a value in `yin.vm`. Segments live in the VM record under     |
    66	|                          | `:code`. Primitives, modules, `:make-stream`, bridge handlers are all supplied   |
    67	|                          | at construction, as for the walker. No `defonce`, no registry.                   |
    68	+--------------------------+----------------------------------------------------------------------------------+
    69	| No implicit control flow | Every transfer of control is an instruction datom: `:jump`, `:branch-false`,     |
    70	|                          | `:call`, `:return`, `:halt`. Sequencing is the explicit `:yin.code/pc` order. No |
    71	|                          | fallthrough that is not a `pc + 1` visible in the datoms.                        |
    72	+--------------------------+----------------------------------------------------------------------------------+
    73	| No callbacks             | Host effects go through `engine/handle-effect` and the FFI request/response      |
    74	|                          | pair; a blocked read parks in the polling wait set. The VM invokes nothing and   |
    75	|                          | is invoked by nothing.                                                           |
   490	### 4.1 Configuration
   491	
   492	$$\langle C, E, S, K \rangle \quad\text{where}\quad
   493	C = \langle seg, pc, val, St \rangle$$
   494	
   495	- **C (control)**: the segment id, the program counter, the accumulator
   496	  `val`, and the operand stack `St` (a vector; `St` is control, not store,
   497	  because it is part of what the machine is *doing*, and it is saved in
   498	  frames).
   499	- **E (environment)**: a persistent map `sym → value`, extended on closure
   500	  entry with `merge closure-env (engine/bind-params params args)`, as the
   501	  walker does. `bind-params` binds each param to its positional argument and
   502	  **nil-fills** any parameter left over on an under-arity call — it is not
   503	  `zipmap`, which would silently omit the unbound parameter names from the
   504	  extended environment entirely (`yin.vm.code-as-tuples.md` §7.7.2's
   505	  argument-to-named-parameter binding rule: arguments match `params` by
   506	  position, each bound to its name; under-arity → missing names bound to
   507	  `nil`; extra arguments beyond `params`' length are dropped). Lexical
   508	  addressing is reserved (§6.4).
   509	- **S (store)**: the map `key → value` holding `yin/def` results, stream
   510	  handles, cursor entries, and the FFI pair — unchanged from `engine`.
   511	- **K (continuation)**: a vector of frames, innermost last. Frame kinds:
   512	  `{:type :return :segment :pc :env :stack-base}` for calls, and the
   513	  effect-continuation frames the engine already defines
   514	  (`:dao.stream.apply/eval-call`, `:request-sent`), extended with
   515	  `:segment :pc :stack`.
   516	
   517	Let `I = code[seg][pc]` be the decoded instruction and `I.x` its operands.
   518	
   519	### 4.2 Transitions for the core instructions
   520	
   521	$$\begin{aligned}
   522	&\text{const}:&& \langle seg,pc,val,St,E,S,K\rangle \to \langle seg,pc{+}1,\,I.v,\,St,E,S,K\rangle\\
   523	&\text{var}:&& \to \langle seg,pc{+}1,\,\rho(E,S,I.name),\,St,E,S,K\rangle\\
   524	&\text{closure}:&& \to \langle seg,pc{+}1,\,\mathrm{clo}(I.params,I.entry,seg,E),\,St,E,S,K\rangle\\
   525	&\text{push}:&& \to \langle seg,pc{+}1,\,val,\,St \Vert [val],E,S,K\rangle\\
   526	&\text{jump}:&& \to \langle seg,\,I.target,\,val,St,E,S,K\rangle\\
   527	&\text{branch-false}:&& \to \langle seg,\,(val\ ?\ pc{+}1 : I.target),\,val,St,E,S,K\rangle\\
   528	&\text{halt}:&& \to \text{halted},\ \text{result} = val
   529	\end{aligned}$$
   530	
   630	VM: `eval` lowers its AST; a composition whose program stream carries AST
   631	datoms composes `lower` into the loader it hands the observer; a composition
   632	whose stream carries code datoms hands the loader alone. There is no
   633	tiering, no recompile-at-boundary, no dirty flag: the engine's versioned
   634	compile cache exists for programs that grow by append, and a segment is
   635	immutable once emitted. Streaming delivery of a growing segment (a pc past
   636	the loaded length parks on code rather than erroring) is reserved for a
   637	later phase; it is additive because a pc beyond `length` is already a
   638	defined load-time condition.
   639	
   640	### 5.3 The flattening
src/cljc/yin/vm/ucf.cljc:3:   (`docs/design/yin.vm.universal-continuation-format.md` S7.3, S7.4).
src/cljc/yin/vm/debruijn.cljc:175:      tell 1 from 1.0 (a host limit that stays here, never in the universal
src/cljc/yin/vm/debruijn_register_compile.cljc:358:(defn lower-register
src/cljc/yin/vm/debruijn_register_compile.cljc:443:;; code/register-image-defect`); undefined behaviour results otherwise,
src/cljc/yin/vm/debruijn_register_compile.cljc:596:;; not a named diagnostic -- `register-image-defect` is this path's sole
src/cljc/yin/vm/debruijn_register_compile.cljc:819:(defn lift
src/cljc/yin/vm/semantic.cljc:126:   (`yin.vm.universal-continuation-format.md` S7.4.3). nil when the
src/cljc/yin/vm/semantic.cljc:963:(defn- binding-mismatch!
src/cljc/yin/vm/semantic.cljc:966:                  {:reason :binding-mismatch,
src/cljc/yin/vm/semantic.cljc:1022:      (binding-mismatch! marker))
src/cljc/yin/vm/debruijn_register_code.cljc:248:(defn encode-register-image
src/cljc/yin/vm/debruijn_register_code.cljc:261:(defn register-hash
src/cljc/yin/vm/debruijn_register_code.cljc:268:  [register-image]
src/cljc/yin/vm/debruijn_register_code.cljc:269:  (jing/sha256 (str descriptor-hash (encode-register-image register-image))))
src/cljc/yin/vm/debruijn_register_code.cljc:391:;; `register-image-defect`: the sole admitter of a register image on this
src/cljc/yin/vm/debruijn_register_code.cljc:698:(defn register-image-defect
src/cljc/yin/vm/debruijn_linearize.cljc:212:(defn lower-stack
src/cljc/yin/vm/debruijn_linearize.cljc:398:(defn lift
src/cljc/yin/vm/debruijn/register.cljc:147:      (if-let [defect (rcode/register-image-defect segment)]
src/cljc/yin/vm/debruijn/register.cljc:207:    (when-let [defect (rcode/register-image-defect image)]
src/cljc/yin/vm/debruijn/register.cljc:302:    (when-let [defect (rcode/register-image-defect image)]
src/cljc/yin/vm/debruijn/register.cljc:490:(defn register-restore
src/cljc/yin/vm/debruijn/register.cljc:885:(defn- binding-mismatch!
src/cljc/yin/vm/debruijn/register.cljc:888:                  {:reason :binding-mismatch,
src/cljc/yin/vm/debruijn/register.cljc:934:      (binding-mismatch! marker))
src/cljc/yin/vm/debruijn_register_effects.cljc:269:        (when-let [d (rcode/register-image-defect segment)]
src/cljc/yin/vm/debruijn_register_effects.cljc:273:                  (or (when (pos? len) (rcode/register-image-defect image))
src/cljc/yin/vm/debruijn_register_effects.cljc:277:    (rcode/register-image-defect segment)))
src/cljc/yin/vm/linearize.cljc:181:(defn lower
src/cljc/yin/vm/linearize.cljc:360:(defn lower-rows
src/cljc/yin/vm/linearize.cljc:398:(defn lower-envelope
src/cljc/yin/vm/linearize.cljc:436:(defn lower-ast
src/cljc/yin/vm/debruijn/stack.cljc:943:(defn- binding-mismatch!
src/cljc/yin/vm/debruijn/stack.cljc:946:                  {:reason :binding-mismatch,
src/cljc/yin/vm/debruijn/stack.cljc:992:      (binding-mismatch! marker))
src/cljc/yin/vm/ucf/ledger.cljc:318:      :binding-mismatch
src/cljc/yin/vm/ucf/ledger.cljc:357:      :binding-mismatch
src/cljc/yin/vm/ucf/remote.cljc:4:   (`docs/design/yin.vm.universal-continuation-format.md` S7.4.3) into
src/cljc/yin/vm/ucf/remote.cljc:144:(defn lift-marker
src/cljc/yin/vm/ucf/remote.cljc:162:(defn lower-marker
src/cljc/yin/vm/ucf/remote.cljc:435:(defn lift-frame
src/cljc/yin/vm/ucf/remote.cljc:460:(defn lift-pending
src/cljc/yin/vm/ucf/remote.cljc:694:(defn lower-frame
src/cljc/yin/vm/ucf/remote.cljc:760:(defn lower-pending
src/cljc/yin/vm/ucf/handoff.cljc:85:   (docs/design/yin.vm.universal-continuation-format.v2-amendment.md),
src/cljc/yin/vm/ucf/handoff.cljc:2445:                               (rcode/register-image-defect v))]

exec
/bin/zsh -lc "nl -ba src/cljc/yin/vm/debruijn_register_compile.cljc | sed -n '340,395p;810,855p' && nl -ba src/cljc/yin/vm/ucf.cljc | sed -n '1,75p' && nl -ba src/cljc/yin/vm/ucf/remote.cljc | sed -n '140,190p;430,480p;688,735p' && nl -ba src/cljc/yin/vm/semantic.cljc | sed -n '950,1035p' && nl -ba docs/design/yin.vm.universal-continuation-format.md | sed -n '2667,2710p;2720,2755p'" in /Users/sto/workspace/datomworld
 succeeded in 0ms:
   340	
   341	(defn- fill-live
   342	  "Design section 4.5: overwrite every boundary tuple's placeholder `live`
   343	   operand with `yin.vm.debruijn-register-code/body-liveness`'s own answer,
   344	   one body at a time."
   345	  [{:keys [bodies instructions] :as pre-image}]
   346	  (reduce
   347	    (fn [instrs bi]
   348	      (reduce-kv (fn [ins pc live]
   349	                   (let [t (nth ins pc)
   350	                         slot (rcode/live-slot-index (first t))]
   351	                     (assoc ins pc (assoc t slot live))))
   352	                 instrs
   353	                 (rcode/body-liveness pre-image bi)))
   354	    instructions
   355	    (range (count bodies))))
   356	
   357	
   358	(defn lower-register
   359	  "`{:keys [tuples source params]}` (`yin.vm.debruijn-resolve/resolve`'s
   360	   own return shape) -> `{:image v, :side-table st}`: `v` a `:yin.debruijn.
   361	   register/*`-shaped `{:bodies [...], :instructions [...]}` image, `st`
   362	   the pc-keyed diagnostic side table.
   363	
   364	   Calls `yin.vm.debruijn-resolve/validate-resolved` first and refuses on
   365	   its diagnostic, before doing anything else -- the same seam `lower-
   366	   stack` uses, unconditional and not optional (design section 4.1, 2.1)."
   367	  [{:keys [tuples source params]}]
   368	  (when-let [defect (resolve/validate-resolved tuples source)]
   369	    (throw (ex-info "Cannot lower-register an invalid resolved-tuple set"
   370	                    {:defect defect})))
   371	  (let [{:keys [get-attr root-id error]} (vm/index-datoms tuples)]
   372	    (when error
   373	      (throw (ex-info
   374	               "Cannot lower-register a program with a dangling root"
   375	               error)))
   376	    (when (nil? root-id)
   377	      (throw (ex-info "Cannot lower-register a program with no root" {})))
   378	    (let [bodies-queue (atom [])
   379	          main-ctx (fresh-body-ctx 0)
   380	          main-temp (allocate-temp! main-ctx)
   381	          main-reg (reg-of main-ctx main-temp)]
   382	      (lower-node! get-attr bodies-queue main-ctx root-id main-reg)
   383	      (emit! main-ctx root-id [:halt main-reg])
   384	      (let [descs (loop [i 0, acc [{:ctx main-ctx, :locals 0}]]
   385	                    (if-let [{:keys [source body-entity arity]}
   386	                             (get @bodies-queue i)]
   387	                      (let [ctx (fresh-body-ctx arity)
   388	                            t (allocate-temp! ctx), r (reg-of ctx t)]
   389	                        (lower-node! get-attr bodies-queue ctx body-entity r)
   390	                        (emit! ctx source [:return r])
   391	                        (recur (inc i) (conj acc {:ctx ctx, :locals arity})))
   392	                      acc))
   393	            local-resolved
   394	            (mapv (fn [{:keys [ctx locals]}]
   395	                    {:code (resolve-local-jumps @(:code ctx) @(:labels ctx)),
   810	                   (:jump :branch-false)
   811	                   (assoc tup 1 (+ (nth tup 1) (nth starts bi)))
   812	                   :closure (assoc tup 2 (nth starts (nth tup 2)))
   813	                   tup))]
   814	    (into []
   815	          (mapcat (fn [[bi tuples]] (map #(offset bi %) tuples)))
   816	          (map-indexed vector per-body))))
   817	
   818	
   819	(defn lift
   820	  "The lift (design section 3, mirroring `yin.vm.debruijn-linearize/lift`'s
   821	   own trust rule exactly): a register image `v` and its diagnostic side
   822	   table (may be nil) -> a `:yin.code/*`-shaped named canonical vector. A
   823	   supplied `side-table` is trusted only after `register-round-trips?`
   824	   holds for its parameter names; otherwise this falls back to full
   825	   synthesis, which always round-trips by construction."
   826	  ([image] (lift image nil))
   827	  ([image side-table]
   828	   (let [owner (owner-of-pc image)
   829	         enclosing (enclosing-body-of image owner)
   830	         given-params (when side-table (register-lift-params image side-table))]
   831	     (if (and given-params
   832	              (register-round-trips? image owner enclosing given-params))
   833	       (lift-image-with image owner enclosing given-params)
   834	       (let [synthesized-params (register-lift-params image nil)]
   835	         (if (register-round-trips? image owner enclosing synthesized-params)
   836	           (lift-image-with image owner enclosing synthesized-params)
   837	           (throw
   838	             (ex-info
   839	               "Register lift failed to round-trip even with synthesized names"
   840	               {:rule :lift-round-trip}))))))))
     1	(ns yin.vm.ucf
     2	  "Universal Continuation Format, Phase 1: code identity and safepoints
     3	   (`docs/design/yin.vm.universal-continuation-format.md` S7.3, S7.4).
     4	
     5	   Two static halves of the format live here, both pure functions over
     6	   data:
     7	
     8	   - S7.3 code identity. `batch->canonical-instruction-vector` reads a
     9	     well-formed `:yin.code/*` segment batch through the loader's resolved
    10	     interpretation (index-batch collapse, last value wins) and produces
    11	     the positional instruction tuple vector -- pc is the index, each tuple
    12	     is its mnemonic plus exactly the S2.4 operands in table order, every
    13	     ref a resolved pc, every defaulted operand materialized. The address
    14	     of that vector is `(dao.jing/segment-key v)`; the execution contract
    15	     it is interpreted under is `contract-stamp`. `canonicalize` bundles
    16	     the three into one S7.9 outcome map.
    17	
    18	   - S7.4 safepoints. `safepoints` derives, from the canonical vector
    19	     alone, the static half of the safepoint map a foreign engine
    20	     publishes: the parking transitions, their canonical resume pcs, the
    21	     stack effect across the parking instruction, and the names the code
    22	     reachable from the resume pc can read from E. The dynamic half --
    23	     absolute depth, captured bindings, activation bases -- is never
    24	     derived: `activation-state` reads it off a frame, which is the only
    25	     place it exists.
    26	
    27	   Content addressing is delegated entirely to `dao.jing`; nothing here
    28	   knows how a vector is serialized."
    29	  (:require [dao.jing :as jing]
    30	            [dao.jing.cbor :as cbor]
    31	            [yin.vm :as vm]
    32	            [yin.vm.code :as code]))
    33	
    34	
    35	;; =============================================================================
    36	;; The execution contract (S7.3.3)
    37	;; =============================================================================
    38	
    39	(def contract-stamp
    40	  "The complete execution contract a canonical vector is interpreted under:
    41	   `:yin.code/contract` names the published revision of the tuple grammar,
    42	   S2.6 rules, last-value-wins resolution, opcode table, transitions,
    43	   resolution precedence, effect outcomes and scheduler
    44	   (`yin.vm.semantic.md` S2.4); `:yin.k/version` is this envelope's own
    45	   version. A resumer on another revision answers `:yin.k/profile-mismatch`
    46	   before lowering; a code index is keyed per stamp."
    47	  {:yin.code/contract vm/semantic-contract,
    48	   :yin.k/version 0})
    49	
    50	
    51	(def profiles
    52	  "The version-2 execution profile registry (UCF v2 amendment, section 1):
    53	   the closed set of exact `:yin.k/contract` maps a version-2 body may
    54	   carry, by engine.  The engine names are literal unqualified keywords,
    55	   not the kernels' runtime `:format`s; walker and semantic share the
    56	   contract string `v3` and differ by engine alone.  `contract-stamp`
    57	   stays the frozen version-0/1 stamp."
    58	  {:semantic {:yin.k/engine :semantic
    59	              :yin.code/contract vm/semantic-contract
    60	              :yin.k/version 1}
    61	   :stack {:yin.k/engine :stack
    62	           :yin.code/contract vm/stack-contract
    63	           :yin.k/version 1}
    64	   :register {:yin.k/engine :register
    65	              :yin.code/contract vm/register-contract
    66	              :yin.k/version 1}
    67	   :walker {:yin.k/engine :walker
    68	            :yin.code/contract vm/ast-contract
    69	            :yin.k/version 1}})
    70	
    71	
    72	(def supported-profiles
    73	  "Every profile map a version-2 reader of this composition accepts."
    74	  (set (vals profiles)))
    75	
   140	         (when (= cursor r) cursor))
   141	       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))
   142	
   143	
   144	(defn lift-marker
   145	  "The UCF stream marker (S7.5.1) for local handle `h`, its descriptor
   146	   the remote descriptor `serve!` mints by entering `h` in the
   147	   exporter's table (`dao.stream.remote.md` S2.2). nil when `serve!`
   148	   refuses -- the source cannot be entered, or cannot honor the
   149	   profile -- so a caller mints `:yin.k/unsatisfied` before any value
   150	   is produced (S7.4.3)."
   151	  [serve! h]
   152	  (when-let [served (serve! h)]
   153	    (let [id (:dao.stream/identity served)]
   154	      {:yin.k/tag :yin.k/stream,
   155	       :dao.stream/identity id,
   156	       :dao.stream/descriptor
   157	       {:dao.stream/type :dao.stream/remote,
   158	        :dao.stream/identity id,
   159	        :dao.stream/channel (:dao.stream/channel served)}})))
   160	
   161	
   162	(defn lower-marker
   163	  "The local handle `attach!` gives for UCF stream marker `m` -- a
   164	   reflection on the original logical stream -- or nil when the attach
   165	   does not answer ok: a reflection marked gone, or any other refusal,
   166	   is unsatisfied at the caller."
   167	  [attach! m]
   168	  (let [result (attach! (:dao.stream/descriptor m))]
   169	    (when (= :dao.stream/ok (:dao.stream/outcome result))
   170	      (:dao.stream/handle result))))
   171	
   172	
   173	;; =============================================================================
   174	;; The pending wait (S7.4.3)
   175	;; =============================================================================
   176	;;
   177	;; Local pending-entry shapes this namespace lifts, and lowers back to
   178	;; -- the engine's own wait-set shapes, plain data resolved through
   179	;; the machine's `:resources` table:
   180	;;
   181	;;   {:reason :next          :cursor-ref {:type :cursor-ref, :id cid}
   182	;;                           :stream-id sid}          ; blocked read
   183	;;   {:reason :next          :call-id id :op op        ; a sent call's
   184	;;                           :cursor-ref {:type :cursor-ref, :id cid}
   185	;;                           :stream-id sid}          ; response wait
   186	;;   {:reason :put           :stream-id sid            ; blocked write,
   187	;;                           :datom v}                 ; :datom the retry
   188	;;   {:reason :put           :request-sent true        ; a retained FFI
   189	;;                           :call-id id :op op        ; request; the
   190	;;                           :stream-id sid :datom env}; fixed call-in
   430	
   431	;; =============================================================================
   432	;; Lift: the frame and the single entry
   433	;; =============================================================================
   434	
   435	(defn lift-frame
   436	  "A whole frame set of S7.4.3 pending entries, lifted together:
   437	   `{:yin.k/pending [data ...], :yin.k/cells {cell-id cell},
   438	   :yin.k/requires requires}` on success -- the cells minted across
   439	   the WHOLE set, so two waiters on one engine cursor cell lift to
   440	   one UCF cell (S7.5.3) and `:dao.stream.remote/v1` rides in
   441	   `:yin.k/cursor-profiles` when any entry names a cell; the S7.9
   442	   `:yin.k/unsatisfied` outcome, naming the stream identity where one
   443	   is known, when any entry's stream cannot be served or its kept
   444	   cursor does not survive the codec round trip; the S7.9
   445	   `:yin.k/undecodable` outcome when any entry names no variant.
   446	   Failure is total (S7.5.4): a refused leaf refuses the frame."
   447	  ([serve! resources entries]
   448	   (lift-frame serve! resources entries cursor-codec))
   449	  ([serve! resources entries codec]
   450	   (let [found (atom {:cells {}, :of {}})
   451	         mint (mint-cell serve! found)
   452	         lifted (mapv #(lift-one serve! resources mint codec %) entries)]
   453	     (if-let [refusal (some #(when (contains? % :yin.k/status) %) lifted)]
   454	       refusal
   455	       {:yin.k/pending (mapv :pending lifted),
   456	        :yin.k/cells (:cells @found),
   457	        :yin.k/requires (if (some :cell? lifted) requires {})}))))
   458	
   459	
   460	(defn lift-pending
   461	  "One pending entry of S7.4.3, lifted into its own self-contained
   462	   cell table: `{:yin.k/pending data, :yin.k/cells {cell-id cell},
   463	   :yin.k/requires requires}` on success, with the same `:yin.k/
   464	   unsatisfied` and `:yin.k/undecodable` outcomes `lift-frame`
   465	   gives. Frame assembly that shares cells across entries is
   466	   `lift-frame`'s."
   467	  ([serve! resources entry]
   468	   (lift-pending serve! resources entry cursor-codec))
   469	  ([serve! resources entry codec]
   470	   (let [r (lift-frame serve! resources [entry] codec)]
   471	     (if (contains? r :yin.k/status)
   472	       r
   473	       (assoc r :yin.k/pending (first (:yin.k/pending r)))))))
   474	
   475	
   476	;; =============================================================================
   477	;; Lower: back into resources and a wait-set entry
   478	;; =============================================================================
   479	
   480	(def ^:private fixed-resource-keys
   688	  [receiver-resources]
   689	  (if (map? receiver-resources)
   690	    (keys receiver-resources)
   691	    receiver-resources))
   692	
   693	
   694	(defn lower-frame
   695	  "A whole frame set of S7.4.3 pendings, lowered together as one
   696	   batch: `{:entries [e ...], :resources table}` -- one private
   697	   resource key allocated per distinct served identity and one per
   698	   distinct UCF cell, two waiters on one cell lowering back into one
   699	   shared entry and independent cells staying independent (S7.5.3);
   700	   equal cell ids across separately lowered frames get different
   701	   keys. `receiver-resources` is the receiver's existing resource
   702	   table (or any collection of the resource keys it already holds):
   703	   every allocated key is checked against it, against the VM's fixed
   704	   resource keys, and against keys already allocated in the batch,
   705	   regenerating on collision -- a cross-frame collision is
   706	   structurally impossible, the lower cannot run without it. With
   707	   the same `:yin.k/unsatisfied` and `:yin.k/undecodable` outcomes
   708	   `lift-frame` gives. Where the engine resolves a wait at one of its
   709	   own fixed resource keys, the lower parks the wait there: a link
   710	   wait's pair under `module/link-request-resource` and
   711	   `module/link-response-resource`. A retained request's retry
   712	   writes through its carried request reflection's allocated key and
   713	   stamps the carried response route (`:response-cursor`,
   714	   `:response-stream`) for its restore. A `:ffi-request` pend
   715	   without its verbatim envelope is malformed: the whole frame
   716	   refuses before ANY attachment -- a refusal must mint no
   717	   reflection. `contexts`, parallel to `pendings`, is each entry's
   718	   register context, merged verbatim."
   719	  [attach! pendings cells contexts receiver-resources]
   720	  (if-let [refusal (some (fn [pending]
   721	                           (when (and (= :ffi-request (:yin.k/reason pending))
   722	                                      (nil? (:yin.k/request-envelope pending)))
   723	                             (unsatisfied
   724	                               (get-in pending
   725	                                       [:yin.k/request :dao.stream/identity]))))
   726	                         pendings)]
   727	    refusal
   728	    (let [ms (distinct (remove nil?
   729	                               (concat (map :yin.k/stream (vals cells))
   730	                                       (mapcat pending-markers pendings))))
   731	          a (attach-all attach! ms)]
   732	      (if (contains? a :yin.k/status)
   733	        a
   734	        (let [used (atom (into fixed-resource-keys
   735	                               (occupied-keys receiver-resources)))
   950	         (ffi/attach (:bridge opts))
   951	         (telemetry/emit-snapshot :init)))))
   952	
   953	
   954	;; =============================================================================
   955	;; Module kernel (yin.vm.linker.md section 7.3)
   956	;; =============================================================================
   957	;; A named closure lifts by the alias column: its local segment id's
   958	;; address, its entry pc, its params, and its environment encoded value by
   959	;; value, the module store context read out of the environment. It lowers
   960	;; by the receiving alias column's local id for that address, which act 3
   961	;; minted; existing local ids are never renumbered.
   962	
   963	(defn- binding-mismatch!
   964	  [marker]
   965	  (throw (ex-info "Closure marker of another binding discipline or format"
   966	                  {:reason :binding-mismatch,
   967	                   :binding (:yin.k/binding marker),
   968	                   :format (:yin.k/format marker),
   969	                   :expected-format :yin.semantic/code})))
   970	
   971	
   972	(extend-type SemanticVM
   973	  module/IModuleKernel
   974	  (link-format [_]
   975	    {:format :yin.semantic/code, :contract vm/semantic-contract})
   976	  (spawn-module
   977	    [vm image {:keys [modules origin ancestry capability-secret]}]
   978	    (load-vector (create-vm
   979	                   {:primitives (:primitives vm),
   980	                    :primitive-profiles (:primitive-profiles vm),
   981	                    :primitive-canonical-names (:primitive-canonical-names vm),
   982	                    :modules modules,
   983	                    :make-stream (:make-stream vm),
   984	                    :link-request (get (:resources vm)
   985	                                       module/link-request-resource),
   986	                    :link-response (get (:resources vm)
   987	                                        module/link-response-resource),
   988	                    :origin origin,
   989	                    :ancestry ancestry,
   990	                    :capability-secret capability-secret,
   991	                    :secret-source (:secret-source vm),
   992	                    :attach-stream (:attach-stream vm),
   993	                    :gc-threshold (:base (:gc vm))})
   994	                 image
   995	                 vm/semantic-contract))
   996	  (image-identity [_ image] (jing/segment-key image))
   997	  (image-holds? [_ image segment] (jing/segment-matches? segment image))
   998	  (attach-module [vm image] (attach-image vm image vm/semantic-contract))
   999	  (lift-closure [vm closure encode]
  1000	    (let [local (:segment closure)
  1001	          address (some (fn [[a l]] (when (= l local) a)) (:code-aliases vm))
  1002	          env (:env closure)
  1003	          store-of (engine/env-store-of env)]
  1004	      (when (nil? address)
  1005	        (throw (ex-info "Closure segment has no address"
  1006	                        {:yin.k/status :yin.k/non-portable,
  1007	                         :yin.k/kind :unrooted-body,
  1008	                         :segment local})))
  1009	      (cond-> {:yin.k/tag :yin.k/closure,
  1010	               :yin.k/binding :named,
  1011	               :yin.k/format :yin.semantic/code,
  1012	               :yin.k/segment address,
  1013	               :yin.k/entry (:entry closure),
  1014	               :yin.k/params (:params closure),
  1015	               :yin.k/env (into {}
  1016	                                (map (fn [[k x]] [k (encode x)]))
  1017	                                (engine/without-store-of env))}
  1018	        store-of (assoc :yin.k/store-of store-of))))
  1019	  (lower-closure [vm marker decode]
  1020	    (when-not (and (= :named (:yin.k/binding marker))
  1021	                   (= :yin.semantic/code (:yin.k/format marker)))
  1022	      (binding-mismatch! marker))
  1023	    (let [local (get (:code-aliases vm) (:yin.k/segment marker))
  1024	          entry (:yin.k/entry marker)
  1025	          store-of (:yin.k/store-of marker)]
  1026	      ;; An entry outside the attached image names no code of it either.
  1027	      (when-not (and (some? local)
  1028	                     (nat-int? entry)
  1029	                     (< entry (count (get-in vm [:code local :code]))))
  1030	        (throw (ex-info "Closure origin image is not attached"
  1031	                        {:reason :origin-not-attached,
  1032	                         :segment (:yin.k/segment marker)})))
  1033	      ;; the binders are those of a `:closure` (4) of the attached image
  1034	      ;; whose entry this is, never the wire's
  1035	      (vm/check-params! (:yin.k/params marker))
  2667	## §7.11 Acceptance blockers for the implementation phase
  2668	
  2669	The warning at the top of this file is retained until each of these is
  2670	closed with a test in the parity or conformance suite. The first four are
  2671	the review's named architectural obligations; they are blockers to
  2672	*accepting the design*, not merely work items.
  2673	
  2674	- **Authority grounding and wiring.** The arbitration `dao.space`, its
  2675	  transactor as grantor, the occurrence ledger with epochs, and the
  2676	  attribution resolver `dao.lease.md` requires, are composition duties; the
  2677	  first composition to ship UCF is `yin.repl.core`'s handoff demo, and
  2678	  its wiring becomes the worked example. A composition without a
  2679	  transactable space offers `:yin.k/fork` only.
  2680	- **Execution identity.** Occurrence minting, the `:yin.k/origin` chain,
  2681	  retry idempotence (one occurrence per park), and successor completion as
  2682	  a grantor ledger transition need conformance tests, including the crash
  2683	  windows of §7.7.5.
  2684	- **Pending-state completeness.** Every pending variant of §7.4.3 must
  2685	  round-trip and resume *elsewhere*: retained writes, sent and retained FFI
  2686	  calls with both endpoints and the kept response cursor, and cursor cells
  2687	  through the aliasing scenarios of `check-wait-set`. This depends on
  2688	  `dao.stream.remote.md` and its **portable cursor profile**
  2689	  `:dao.stream.remote/v1`, which preserves the source stream's kept cursor
  2690	  and `gap` outcomes. The M4 string-backed stream test below is the
  2691	  required cross-host proof.
  2692	- **Enforceable fencing.** Epoch-checked, atomic-with-commitment admission
  2693	  and durable op-id plus intent dedup at enrolled consumers are design
  2694	  items for `dao.space.transactor.md`. A consumer without a shared atomic
  2695	  boundary declares at-least-once or fail-stop behavior; it cannot claim
  2696	  exactly-once custody from the lease alone.
  2697	- **Canonical byte encoding.** `:yin.code/hash` and `:yin.k/id` are exactly
  2698	  as portable as `dao.jing/segment-key`. Canonical CBOR landed in
  2699	  3ddaa21b (recorded in `yin.vm.ucf-revisions.md` section 5); the
  2700	  cross-host address checks below still must pass.
  2701	- **Contract revision publication.** `:yin.code/contract "v3"` needs a
  2702	  published revision history naming the complete execution contract
  2703	  (§7.3.3): the tuple grammar — mnemonic set, per-mnemonic arity and operand
  2704	  kinds, saturation/defaults table — the opcode table and transitions, the
  2705	  resolution and last-value-wins rules, the effect outcome map, and the
  2706	  scheduler semantics. The index is in `yin.vm.semantic.md` 2.4 and the
  2707	  history landed in dbae125b (`yin.vm.ucf-revisions.md`); runtime profile
  2708	  checks below remain open.
  2709	- **Primitive profile publication.** The standard `yin.vm/primitives`
  2710	  map published with profiles (S7.5.2), including the `:effectful`
  2720	  cross-activation-`E` cases; module manifests publishing store footprints
  2721	  so `:yin.k/discovery :incomplete` disappears.
  2722	- **Isolated-store lowering.** Conformance tests that the resumed task's
  2723	  store contains exactly the slice and the resumer's pair, and that a
  2724	  receiver-side binding of a name in the slice's name set cannot change
  2725	  resolution.
  2726	- **Safepoint conformance harness.** A test that runs every corpus segment
  2727	  on the reference machine to each safepoint of the complete §7.4.1 table —
  2728	  effect-producing calls included — lifts, lowers into a fresh VM, and
  2729	  checks the parity suite's result; the same harness is what a foreign
  2730	  engine runs to claim conformance, including `lift ∘ lower = id`, the
  2731	  static `stack-effect`/`lexically-required` checks, and the §7.3.4
  2732	  projection/direct-path image-equality assertion.
  2733	- **Exporting-state integration.** The lift driver's exporting transition
  2734	  and its failure/retry paths, tested specifically against the
  2735	  poll-a-blocked-writer-appends hazard of §7.7.4.
  2736	
  2737	### Holder driver scheduling and retained inboxes (D15a)
  2738	
  2739	The holder exposes four composition-local entries over the existing driver
  2740	state: `control-step`, `program-step`, `stop`, and `owed-control-write?`.
  2741	`step` remains the normal-operation convenience entry, using the same machinery
  2742	with one renewal and one program cycle per tick. These entries change no
  2743	handoff body, authority fact, lease fact, or DaoStream outcome vocabulary.
  2744	
  2745	`control-step` retains incoming observations and handles authenticated custody
  2746	answers, renewals, retries, reports, releases, and closure evidence. It never
  2747	lowers a newly granted checkpoint, attaches program resources, applies program
  2748	results, executes guest code, replays inputs, emits program effects, observes
  2749	program streams, or prepares an export. A newly accepted grant remains in
  2750	`:activating` with its original lease observation basis and no machine.
  2751	
  2752	`program-step` independently checks complete authenticated evidence, the same
  2753	live occurrence/lease/epoch, and fresh `lease/holding?` before activation,
  2754	execution, and retained-result application. Unavailable evidence suspends;
  2755	contradiction or expiry uses the existing diagnostic/cleanup run end. The

exec
/bin/zsh -lc "nl -ba docs/design/yin.vm.semantic.md | sed -n '1,35p;260,295p' && nl -ba docs/design/yin.vm.debruijn.register.md | sed -n '14,85p;130,180p' && nl -ba docs/design/yin.vm.linker.md | sed -n '48,72p;1400,1430p' && rg -n \"defn (lift|lower-frame|lower-continuation|lift-continuation)|defn .*safepoint|activation-state|not-at-safepoint|:frame\" src/cljc/yin/vm/ucf.cljc" in /Users/sto/workspace/datomworld
 succeeded in 0ms:
     1	# yin.vm.semantic on dao.stream — linear executable datoms
     2	
     3	Revision: 2 (2026-09-25), Rule R: the execution contract this document
     4	names is `"v3"`, published in full by the S2.4 contract note and its
     5	revision history, per the UCF design's S7.11 contract-revision-publication
     6	blocker (`yin.vm.universal-continuation-format.md`). Revision 1
     7	(2026-09-18; status corrected 2026-09-25) published `"v2"`; `"v3"` adds
     8	`:define` and the reserved name `yin/def`. The revision history of the
     9	`"v2"` contract is in `yin.vm.ucf-revisions.md` (2026-09-25).
    10	
    11	Status: Phase 0 contract. Sections §1–§6 are promoted verbatim from
    12	`collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md`.
    13	The opcode table (§2.4) and the attribute tables (§2.2, §2.3) are the
    14	contract that `yin.vm/code-schema`, `yin.vm/opcode-table`, and
    15	`yin.vm.code/well-formed?` implement. The integration and roadmap
    16	sections remain in the findings note; §8 records the Phase 4 measurements.
    17	
    18	---
    19	
    20	## §1. Foundational axioms and invariant compliance
    21	
    22	### 1.1 The four axioms
    23	
    24	**Everything is a Stream.** Code arrives on a program stream as a segment
    25	value; execution is consumption of that stream by an observer, and then
    26	consumption of the segment by the machine. Effects the program performs
    27	(`:stream-put`, `:stream-next`, FFI) are emissions on streams the composition
    28	supplied, exactly as for the walker. Optional execution traces are emissions
    29	too (§3.6). What is *not* a stream is the instruction fetch inside a loaded
    30	segment, and this is deliberate: a boundary belongs "wherever you need
    31	decoupling — and nowhere else" (`streams-all-the-way-down.md` §1). Fetch has
    32	one consumer, one order, no replay need, and no host boundary; putting a
    33	stream there is the per-value tax §4 of that note names as hopeless.
    34	
    35	**Interpretation Creates Semantics.** `[e :yin.code/op :call]` means nothing
   260	  keeps its last value in datom order, exactly as `code.cljc`'s
   261	  `index-batch` and `yin.vm/index-datoms` both implement it.
   262	- **Effect outcome map**: §3.3's table.
   263	- **Scheduler semantics**: §3.5.
   264	
   265	This is the whole `"v3"` contract by reference, not a duplicate of it:
   266	each piece has exactly one home, and this note is that home's index.
   267	
   268	Revision history: "v2" was the contract above without `:define` and
   269	without the reserved-name refusal. "v3" (2026-09-25, Rule R: `yin/def`
   270	is syntax, never a name) adds the `:define` mnemonic and transition,
   271	opcode 24, well-formedness rule 8 `:reserved-name`, and the refusal of
   272	the reserved name before env in `resolve-var`; `yin/def` is no longer a
   273	primitive. The two are not compatible, and a "v2" segment is refused by
   274	stamp (`:contract-mismatch`) with no migration.
   275	
   276	### 2.5 Constants and literals
   277	
   278	A literal lives inline in the `v` slot of `:yin.code/value`, polymorphic
   279	exactly as `:yin/value` is today (numbers, strings, keywords, vectors, maps,
   280	sets, nil). Host functions never appear in code datoms; a primitive is named
   281	by symbol in `:var` and resolved at run time through the supplied
   282	`:primitives`, which is how the walker already avoids putting host objects in
   283	the program. A shared or large constant pool (`:yin.code/const-ref` to a
   284	constant entity) is reserved and not needed for the current corpus.
   285	
   286	### 2.6 Well-formedness (checked by the loader, tested in Phase 0)
   287	
   288	1. Exactly one entity with `:yin.code/type :segment` per batch.
   289	2. Every instruction entity names this segment (`:yin.code/segment`) and
   290	   carries a §2.4 mnemonic (`:yin.code/op`); the later rules read both, so a
   291	   batch that lacks them cannot be judged by them (`:instruction-shape`).
   292	3. Instruction pcs are exactly `0 .. length-1`, each once.
   293	4. The batch is sorted by pc (so a loader can fill the array in one pass).
   294	5. Every `:yin.code/target` and `:yin.code/body` resolves to an instruction
   295	   of the same segment, and a `:jump`, `:branch-false`, or `:closure`
    14	## 1. Objective and invariants
    15	
    16	The objective is a deterministic register image derived from the de Bruijn
    17	encoding of the named `:yin/*` semantic tuples, at the same level as the
    18	`:yin.debruijn.code/*` stack image. The register lowerer consumes the
    19	resolved tuples and emits a register image; it never reads, rewrites, or
    20	depends on the stack image. The stack image remains independently
    21	addressable by its existing H, and the register image is independently
    22	addressable by its own R.
    23	
    24	This follows the project's established compilation topology
    25	(`docs/agents/architecture.md`, "COMPILATION AS STREAM PROCESSING"): one
    26	upstream stream feeds several parallel projections, and no projection is
    27	compiled from another. That pattern is applied here one level down, at the
    28	de Bruijn layer.
    29	
    30	This path is worth building on its own merits, and the merit is not
    31	performance. The governing reason, in the owner's words, is that it
    32	demonstrates the philosophy that one universal AST can be interpreted by
    33	different VMs: one truth, many interpretations. The named datoms are this
    34	project's single source of truth. The named VM, the stack VM, and the
    35	register VM are not competing implementations with one canonical and the
    36	others alternates; they are peer witnesses to the same truth, each free to
    37	interpret it by its own execution model, bound only by agreement on
    38	observable behavior under the B0 normalizer and never on internal
    39	mechanism. This is not new here. It is axiom 2 of `datom.world.md`,
    40	"Interpretation Creates Semantics ... one truth, many perspectives", made
    41	concrete for evaluators the way its Streams section already makes it
    42	concrete for a `yin.vm` evaluator and `dao.space` reading one stream with
    43	no privileged reader ("one stream, many interpretations, none of them the
    44	stream's own"), and the way `docs/agents/architecture.md`, "COMPILATION AS
    45	STREAM PROCESSING", already states it for backends ("Same datoms, multiple
    46	interpreters ... same wave function, different measurements"). The same
    47	file's "AGENTS" section, under Continuation Migration, already relies on
    48	it: the AST datoms are the canonical payload, bytecode is a projection for
    49	one execution model, and a destination projects the datoms into whatever
    50	model it prefers. A third evaluator over the same datoms is that
    51	established idea with one more witness.
    52	
    53	The specific mechanism for it is the one this epic has been building
    54	toward: a configurable compilation pipeline with `dao.stream` as every
    55	stage boundary, in which several peer executable formats (named, stack,
    56	register) share one upstream de Bruijn encoding and each backend is an
    57	independent, swappable interpreter rather than a hardcoded single
    58	evaluator. The register path also targets an alpha-invariant, content-
    59	addressed encoding rather than reintroducing a VM directly beside the AST
    60	walker. A lowerer alone proves the register format is well defined. Only a
    61	running kernel proves that a second execution backend can be plugged into
    62	the same pipeline and run real programs through it, which is what makes
    63	the philosophy demonstrated rather than asserted. The owner has therefore
    64	authorized the kernel unconditionally; the lowerer and format still ship
    65	first, as their own milestones, and the R3 benchmark reports numbers
    66	without deciding anything.
    67	
    68	The following invariants apply:
    69	
    70	1. A register image is derived data, derived from the resolved tuples. The
    71	   stack image is never consumed, rewritten, or invalidated by the register
    72	   path, and neither image is derived from the other.
    73	2. `dao.stream` is the stage boundary. Every lowering is a pure stream-to-
    74	   stream function; no stage mutates an input stream or retains a callback.
    75	3. H and R are peer format identities, not competing semantic identities.
    76	   H identifies the exact stack bytes; R identifies the exact register bytes
    77	   that a register-capable host can run. Neither preimage mentions the other.
    78	4. Equal R implies equal register bytes and descriptor contract. It does not
    79	   imply that a stack VM can execute those bytes.
    80	5. Register execution must agree with the named semantic VM, and therefore
    81	   with the stack VM, under the B0 normalizer: results, errors, store
    82	   effects, stream outcomes, tail calls, and lexical resolution.
    83	6. All register allocation choices are data-derived and deterministic across
    84	   runs and hosts.
    85	
   130	                         |
   131	                         +----> resolved tuples (de Bruijn AST, no H)
   132	                                   |\
   133	                                   | \-> stack image (H)      B1 shape, B2
   134	                                   |
   135	                                   +---> register image (R)   R1
   136	
   137	The register edge is not chained after the stack image. The named stream,
   138	the resolved tuples, the stack image, and the register image coexist and
   139	remain independently queryable or persistable, per the pipeline invariant
   140	that no stage destroys its input.
   141	
   142	The stages have the pure shapes:
   143	
   144	    (resolve named-datom-stream)      -> resolved-tuple-stream
   145	    (lower-stack resolved-stream)     -> stack-image-stream     (H)
   146	    (lower-register resolved-stream)  -> register-image-stream  (R)
   147	
   148	Each register output item carries a register descriptor reference, the
   149	canonical register vector, R, and a diagnostic side table. The implementation
   150	may build indexes while evaluating one bounded item, but those indexes are
   151	local function data and are not written back into any input.
   152	
   153	The register stage refuses malformed resolved tuples, unsupported node
   154	types, or out-of-range bound operands with qualified data diagnostics. It
   155	does not silently fall back inside the lowerer. A composition may route the
   156	same named root to the stack path after receiving that outcome.
   157	
   158	### 2.1 The shared artifact: resolved tuples
   159	
   160	The resolved tuples are defined by the stack design, section 3.1, and that
   161	definition is normative here. In summary: the named `:yin/*` datoms form a
   162	finite graph in which one source entity may be referenced under several
   163	lexical contexts, and resolution is a fact about an occurrence, the pair
   164	`[source-eid lexical-context]`, not about the entity. The resolver mints one
   165	resolved record per distinct occurrence, with a fresh deterministic id, and
   166	shares it across references under equal contexts. Each record is the source
   167	node's datoms with exactly two changes, the same two changes B1's opcode
   168	table makes to the named instruction table:
   169	
   170	1. A `:variable` record is resolved by the public
   171	   `yin.vm.debruijn/resolve-name` against the innermost-first stack of
   172	   enclosing parameter vectors, to either a bound reference `[depth
   173	   position]` or a free reference carrying the exact symbol.
   174	2. A `:lambda` record carries its arity instead of its parameter vector.
   175	
   176	Everything else is carried exactly: scalar values with their exact spelling,
   177	free names as supplied, `:yin/tail?` as the front end wrote it, node
   178	structure, and evaluation order. Binder names and provenance go to the
   179	resolver's side table, keyed by resolved record: `:source` maps every
   180	record to its source eid and `:params` maps every resolved lambda to its
    48	
    49	| Backend       | Kernel                    | Code shape                   |
    50	|---------------|---------------------------|------------------------------|
    51	| `:ast-walker` | `yin.vm.ast-walker`       | universal AST row set        |
    52	| `:semantic`   | `yin.vm.semantic`         | positional instruction vector|
    53	| `:stack`      | `yin.vm.debruijn.stack`   | de Bruijn stack image (H)    |
    54	| `:register`   | `yin.vm.debruijn.register`| de Bruijn register image (R) |
    55	
    56	Each backend needs the same service: given an identity for some code, and
    57	nothing else, obtain the code, prove it is the code the identity names,
    58	prove it is well formed, prove the receiver can bind its dependencies
    59	identically to the publisher, and hand it over for loading. Today that
    60	service exists once, for two of the four backends, under a name that says
    61	it belongs to one of them: `yin.vm.debruijn-linker`.
    62	
    63	This document makes two corrections and one unification.
    64	
    65	**Correction 1: the linker is universal.** The B6 fetch function has no
    66	de Bruijn branch in it. It is parameterized by a format record and runs
    67	the same six steps for a stack image and a register image. The namespace
    68	`yin.vm.debruijn-linker` is therefore a misnomer that invites a second
    69	linker for the semantic VM and a third for the walker. There is one
    70	linker, `yin.vm.linker`, and every backend is a format record it takes.
    71	
    72	**Correction 2: local and remote linking are one mechanism.** B6 already
  1400	  id, so the mapping is unambiguous -- and at evaluation the
  1401	  runtime `:lambda` transition (`ast-walker`'s `case :lambda`)
  1402	  copies the annotation into the closure it instantiates. The lift
  1403	  reads the recorded id and verifies the row's body slot is the
  1404	  closure's node and its params are the closure's params; a
  1405	  disagreement is the same `:yin.k/non-portable` `:unrooted-body`
  1406	  refusal as an id in no held row. A closure with no recorded id,
  1407	  which only a host function can fabricate, resolves through the
  1408	  walker's row index keyed by the pair `[node params]` (the index
  1409	  `vm-load-rows` builds when it decodes rows into nodes and
  1410	  `attach-image` extends), and refuses when the pair matches no
  1411	  row. The origin image of a walker closure
  1412	  is the tree whose rows contain that `:lambda` row; the `:lambda`
  1413	  row id is content-addressed, so the same lambda shared by two
  1414	  trees is one id and either tree serves as origin. The lower reads
  1415	  the parent's row at that id (present after act 3) and decodes its
  1416	  body slot into a node through the same decoder the loader uses, so
  1417	  the closure's `:body` in the parent is structurally equal to the
  1418	  child's and its `:params` and decoded `:env` are carried unchanged.
  1419	
  1420	  A `:named` marker lowers only into a named kernel and a `:positional`
  1421	  marker only into a positional kernel of the same `:format`; the
  1422	  reverse is `:binding-mismatch`. This never arises within one install,
  1423	  because the child is a VM of the same backend as the parent (section
  1424	  7.3, first rule); it is stated so that a lifted slice carried
  1425	  elsewhere fails closed rather than being reinterpreted. Converting a
  1426	  closure between disciplines is a re-lowering of source under a
  1427	  derivation record (section 8.1), never a relocation. `decode` and
  1428	  `encode` recurse through frames and environments, so a captured
  1429	  closure is relocated by the same rules and its origin joins the
  1430	  origin set of act 2.
24:     derived: `activation-state` reads it off a frame, which is the only
393:(defn safepoints
401:   (`activation-state`)."
416:(defn safepoint-table
426:(defn safepoint-at
428:   S7.9 `:yin.k/not-at-safepoint` outcome naming the pc."
431:      {:yin.k/status :yin.k/not-at-safepoint, :yin.k/pc pc}))
434:(defn activation-state

exec
/bin/zsh -lc "nl -ba src/cljc/yin/vm/ucf.cljc | sed -n '390,490p' && nl -ba docs/design/yin.vm.universal-continuation-format.md | sed -n '705,722p' && rg -n \"compile.*continuation|continuation.*compile|recompile-at-boundary|register continuations|Safepoint\" docs/design/yin.vm.universal-continuation-format.md docs/design/yin.vm.debruijn.register.md docs/design/yin.vm.linker.md" in /Users/sto/workspace/datomworld
 succeeded in 0ms:
   390	      (vec (sort-by str names)))))
   391	
   392	
   393	(defn safepoints
   394	  "The static safepoint map of one canonical vector (S7.4.2): one entry per
   395	   parking instruction, each carrying only what the vector alone fixes --
   396	   the parking pc, the canonical resume pc (pc+1, always inside the segment
   397	   by S2.6), the static kinds under which the machine may park there, the
   398	   stack effect across the instruction, and the lexically required names at
   399	   the resume pc. Absolute depth, activation bases, captured environments
   400	   and physical layouts are not here: they ride in the frame
   401	   (`activation-state`)."
   402	  [v]
   403	  (into []
   404	        (keep-indexed
   405	          (fn [pc t]
   406	            (when (= :parking (get transitions (nth t 0)))
   407	              {:yin.safepoint/at pc,
   408	               :yin.safepoint/pc (inc pc),
   409	               :yin.safepoint/kinds (get parking-kinds (nth t 0)),
   410	               :yin.safepoint/stack-effect (stack-effect t),
   411	               :yin.safepoint/lexically-required
   412	               (lexically-required v (inc pc))})))
   413	        v))
   414	
   415	
   416	(defn safepoint-table
   417	  "`safepoints` published per segment, keyed by the code's address and the
   418	   engine profile that derived it (the reference machine by default)."
   419	  ([v] (safepoint-table v :yin.vm.semantic/reference))
   420	  ([v engine]
   421	   {:yin.safepoint/segment (code-address v),
   422	    :yin.safepoint/engine engine,
   423	    :yin.safepoint/safepoints (safepoints v)}))
   424	
   425	
   426	(defn safepoint-at
   427	  "The static safepoint entry whose canonical resume pc is `pc`, or the
   428	   S7.9 `:yin.k/not-at-safepoint` outcome naming the pc."
   429	  [v pc]
   430	  (or (some #(when (= pc (:yin.safepoint/pc %)) %) (safepoints v))
   431	      {:yin.k/status :yin.k/not-at-safepoint, :yin.k/pc pc}))
   432	
   433	
   434	(defn activation-state
   435	  "The dynamic half of a parked configuration, read off a frame -- a wait
   436	   entry or parked record `{segment pc env stack k}` -- never derived from
   437	   code: the absolute operand-stack depth, the names E actually binds, and
   438	   each return frame's activation base."
   439	  [frame]
   440	  {:yin.k/depth (count (:stack frame)),
   441	   :yin.k/bound (vec (sort-by str (keys (:env frame)))),
   442	   :yin.k/stack-bases (mapv :stack-base (:k frame))})
   443	
   444	
   445	(defn unsatisfied-names
   446	  "The lexically required names of a static safepoint that the frame's
   447	   environment does not bind and that `resolvable` (store, primitives,
   448	   modules -- the rest of `resolve-var`'s order) does not answer: the
   449	   conformance check that a frame carries what its code will read."
   450	  [safepoint frame resolvable]
   451	  (vec (remove (fn [n]
   452	                 (or (contains? (:env frame) n) (resolvable n)))
   453	               (:yin.safepoint/lexically-required safepoint))))
   705	  value; a resumer that is a different engine has its own.
   706	- `lift(engine-state, layout) → canonical frame` and
   707	  `lower(canonical frame, layout) → engine-state` are total on safepoints and
   708	  undefined elsewhere. The conformance obligation is `lift ∘ lower = id` on
   709	  canonical frames, tested against the reference machine's own frames —
   710	  which is possible precisely because the dynamic half rides in the frame.
   711	- A request to lift at a pc that is not a safepoint of the segment returns
   712	  `:yin.k/not-at-safepoint` naming the pc. An engine that wants finer
   713	  interruption granularity must lower the program to a segment that
   714	  *contains* a `:park` where it wants one: that is a different instruction
   715	  record, a different address, and a different profile. An engine may not
   716	  invent safepoints a record does not have, and two engines that disagree
   717	  about the safepoint set of one address are not both conforming.
   718	
   719	Safepoints are datoms, so a query can ask "where can this program be
   720	migrated?" without running it, and a debugger's breakpoint set is a subset of
   721	the safepoint set by construction.
   722	
docs/design/yin.vm.universal-continuation-format.md:17:1. **Safepoint Reconstruction Metadata:** `:yin.code/source` mappings are insufficient for state reconstruction. The safepoint set must be exactly the machine's parking transitions — including effect-producing `:call`s — and reconstruction metadata must separate what is derivable from the canonical code alone (stack *effects*, lexical *requirements*) from what is per-activation state only the frame can carry (absolute depths, captured environments). Foreign engines publish per-segment safepoint maps derived from the canonical stream so arbitrary hardware states can be predictably lifted into the canonical CESK format.
docs/design/yin.vm.universal-continuation-format.md:571:## §7.4 Safepoints — where a canonical configuration exists (blocker 1)
docs/design/yin.vm.universal-continuation-format.md:588:| Safepoint kind | Raised by | Canonical resume pc | Stack in frame (St) | Accumulator on resume |
docs/design/yin.vm.universal-continuation-format.md:641:| Safepoint kind | Raised by                              | Resume pc | Stack in frame (St)    | Accumulator on resume                            |
docs/design/yin.vm.universal-continuation-format.md:666:### 7.4.2 Safepoint maps for foreign engines: static facts only
docs/design/yin.vm.universal-continuation-format.md:719:Safepoints are datoms, so a query can ask "where can this program be
docs/design/yin.vm.universal-continuation-format.md:2726:- **Safepoint conformance harness.** A test that runs every corpus segment
docs/design/yin.vm.universal-continuation-format.md:2878:**Safepoint reconstruction (7.4; blocker 1).**

codex
Completed-GMT: 2026-10-09 12:20:00 GMT
Completed-Local: 2026-10-09 Asia/Ho_Chi_Minh

## Severity findings

| Severity | File:line | Invariant / evidence | Recommended correction |
|---|---|---|---|
| **Blocker** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:3) | UCF §7 is explicitly **Proposed / Deferred**; its rules are not fully implemented, and acceptance blockers remain. A stream carrying a continuation value does not by itself make the continuation portable or transfer execution custody. | Describe cross-kernel migration as a future UCF capability. Require the UCF acceptance conditions before treating it as a supported deployment contract. |
| **Blocker** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:575) | A lift is defined only at machine parking transitions. In-flight states can lack a canonical CESK representation; arbitrary interruption is not a supported lift point. | Lift only a quiescent, recognized safepoint frame. For finer suspension, insert an explicit `:park` into the code and treat the resulting code as a new addressed artifact. |
| **Blocker** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1100) | Named and positional closure markers encode different environments and binding rules. A cross-discipline marker must fail closed as `:binding-mismatch`; conversion requires re-lowering source under a derivation record, not relocation. | Preserve the marker’s binding discipline and reject mismatches. If a backend change is needed, recover/re-lower the source artifact and establish a new derivation and code identity. |
| **High** | [yin.vm.debruijn.register.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.register.md:16) | The register image is lowered from **resolved de Bruijn tuples**, with its own identity and register allocation. It is a peer projection, not a compilation of semantic VM bytecode. | Compile from the shared resolved semantic source through the register lowerer; do not define register bytecode as a translation of the semantic VM’s live continuation. |
| **High** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:17) | Static safepoint data can describe stack effects and lexical requirements, but per-activation depths, bindings, and frame bases come from the parked frame. Source mappings or a closed AST alone cannot reconstruct these values. | Require engine-specific safepoint maps and a frame carrying the dynamic state; validate the lift/lower round trip at those safepoints. |
| **High** | [yin.vm.linker.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.md:1420) | Closure conversion across named and positional kernels is expressly not reinterpreting a closure. Each closure’s capture representation belongs to its kernel’s binding discipline. | Keep code conversion and continuation restoration as separate operations. Do not silently reinterpret named environments as positional frames or vice versa. |
| **High** | [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:24) | Interpretation and execution remain distinct; code and state are data, and migration requires explicit stream values. A carrier stream provides transport, not semantics, dependencies, or exclusive ownership. | Treat `dao.stream` as the medium for a fully described handoff value. Keep interpretation, loading, restoration, custody, and execution as explicit stages. |

## Architectural analysis and ruling

**Ruling: not as an arbitrary mid-flight conversion under current contracts.** The repository does support deriving Register VM bytecode from resolved Universal AST tuples. It also describes a future UCF design in which a conforming backend can lift and lower a *parked* continuation. Those are different capabilities. The first does not imply that a live semantic continuation can be compiled into register code at any point and resumed there.

The compilation topology is a set of peer interpretations of shared source data. The register VM document says its lowerer consumes resolved tuples and produces an independently addressed image; it does not consume the stack image, and the backends are constrained by observable behavioral agreement rather than shared internal representation ([yin.vm.debruijn.register.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.register.md:16), [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:17)). The semantic VM’s code is a positional instruction vector with named lexical state. Its CESK configuration has an environment map `symbol → value`, operand stack, accumulator, and continuation frames with environments and stack bases ([yin.vm.semantic.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:490)). The register VM instead runs validated register images with positional lexical frames and an allocated register file ([register.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/debruijn/register.cljc:44)). Shared behavior does not make those runtime layouts interchangeable.

Compiling a **closed AST form** can choose a register allocation and emit a new image because it starts from code and its lexical structure. A continuation is a code-plus-activation snapshot. Its values include the current accumulator, operand stack, captured environments, return frames, and potentially pending stream/effect state. The compiler cannot infer those dynamic values from the AST. The UCF design states that even a canonical semantic machine lacks a suitable configuration inside some transitions, including a call after operands have been popped, and therefore defines lift points as parking transitions only ([UCF §7.4.1](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:575)).

A theoretically sound approach is possible: pause at a defined safepoint, lift the runtime frame into canonical state, then lower that state into a destination engine using its safepoint layout and activation data. UCF separates static metadata derivable from the canonical instruction vector from dynamic data that only the activation frame carries ([ucf.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf.cljc:393), [ucf.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf.cljc:434)). Its conformance condition requires round-tripping canonical frames at safepoints; a non-safepoint request must return `:yin.k/not-at-safepoint` ([UCF §7.4.2](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:705)). This is reconstruction using frame state and verified maps, not recompilation of the current activation from AST alone.

The binding markers make the target limitation explicit. A semantic closure uses `:yin.k/binding :named`, with named parameters and an environment map. A positional closure uses `:yin.k/binding :positional`, with arity and captured frames. Lowering one into the other is refused as `:binding-mismatch`; the linker says conversion must be a re-lowering of source under a derivation record ([UCF §7.5](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1100), [linker.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.md:1420)). That fail-closed rule prevents a backend switch from quietly changing closure capture or variable resolution.

Finally, even a correct register state is not enough for a migration. UCF requires code identity and contract stamps, portable recursive value encoding, dependency and scheduler closure, resource reconstruction, and custody/fencing. Its own header warns that the protocol is deferred and blockers remain ([UCF §7.11](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:2667)). The repository has UCF code for canonical code identity, safepoint metadata, and portions of marker/frame handling, but that is not evidence that the full cross-kernel migration contract is accepted. For example, `ucf.cljc` derives safepoints and activation data, while §7.11 still calls for conformance and integration closure.

**Recommended architecture:** keep register compilation as a source projection from resolved tuples. For a continuation handoff, require an actual park, encode the complete state under UCF, validate the contract and binding discipline at the destination, and only lower into a kernel whose format accepts the value. A direct semantic-to-register continuation translation is a future UCF implementation path only if it satisfies those rules; compiling an arbitrary paused state from the original AST and resuming it in the register VM is outside current repository commitments.
tokens used
67,683
Completed-GMT: 2026-10-09 12:20:00 GMT
Completed-Local: 2026-10-09 Asia/Ho_Chi_Minh

## Severity findings

| Severity | File:line | Invariant / evidence | Recommended correction |
|---|---|---|---|
| **Blocker** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:3) | UCF §7 is explicitly **Proposed / Deferred**; its rules are not fully implemented, and acceptance blockers remain. A stream carrying a continuation value does not by itself make the continuation portable or transfer execution custody. | Describe cross-kernel migration as a future UCF capability. Require the UCF acceptance conditions before treating it as a supported deployment contract. |
| **Blocker** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:575) | A lift is defined only at machine parking transitions. In-flight states can lack a canonical CESK representation; arbitrary interruption is not a supported lift point. | Lift only a quiescent, recognized safepoint frame. For finer suspension, insert an explicit `:park` into the code and treat the resulting code as a new addressed artifact. |
| **Blocker** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1100) | Named and positional closure markers encode different environments and binding rules. A cross-discipline marker must fail closed as `:binding-mismatch`; conversion requires re-lowering source under a derivation record, not relocation. | Preserve the marker’s binding discipline and reject mismatches. If a backend change is needed, recover/re-lower the source artifact and establish a new derivation and code identity. |
| **High** | [yin.vm.debruijn.register.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.register.md:16) | The register image is lowered from **resolved de Bruijn tuples**, with its own identity and register allocation. It is a peer projection, not a compilation of semantic VM bytecode. | Compile from the shared resolved semantic source through the register lowerer; do not define register bytecode as a translation of the semantic VM’s live continuation. |
| **High** | [yin.vm.universal-continuation-format.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:17) | Static safepoint data can describe stack effects and lexical requirements, but per-activation depths, bindings, and frame bases come from the parked frame. Source mappings or a closed AST alone cannot reconstruct these values. | Require engine-specific safepoint maps and a frame carrying the dynamic state; validate the lift/lower round trip at those safepoints. |
| **High** | [yin.vm.linker.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.md:1420) | Closure conversion across named and positional kernels is expressly not reinterpreting a closure. Each closure’s capture representation belongs to its kernel’s binding discipline. | Keep code conversion and continuation restoration as separate operations. Do not silently reinterpret named environments as positional frames or vice versa. |
| **High** | [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:24) | Interpretation and execution remain distinct; code and state are data, and migration requires explicit stream values. A carrier stream provides transport, not semantics, dependencies, or exclusive ownership. | Treat `dao.stream` as the medium for a fully described handoff value. Keep interpretation, loading, restoration, custody, and execution as explicit stages. |

## Architectural analysis and ruling

**Ruling: not as an arbitrary mid-flight conversion under current contracts.** The repository does support deriving Register VM bytecode from resolved Universal AST tuples. It also describes a future UCF design in which a conforming backend can lift and lower a *parked* continuation. Those are different capabilities. The first does not imply that a live semantic continuation can be compiled into register code at any point and resumed there.

The compilation topology is a set of peer interpretations of shared source data. The register VM document says its lowerer consumes resolved tuples and produces an independently addressed image; it does not consume the stack image, and the backends are constrained by observable behavioral agreement rather than shared internal representation ([yin.vm.debruijn.register.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.register.md:16), [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:17)). The semantic VM’s code is a positional instruction vector with named lexical state. Its CESK configuration has an environment map `symbol → value`, operand stack, accumulator, and continuation frames with environments and stack bases ([yin.vm.semantic.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:490)). The register VM instead runs validated register images with positional lexical frames and an allocated register file ([register.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/debruijn/register.cljc:44)). Shared behavior does not make those runtime layouts interchangeable.

Compiling a **closed AST form** can choose a register allocation and emit a new image because it starts from code and its lexical structure. A continuation is a code-plus-activation snapshot. Its values include the current accumulator, operand stack, captured environments, return frames, and potentially pending stream/effect state. The compiler cannot infer those dynamic values from the AST. The UCF design states that even a canonical semantic machine lacks a suitable configuration inside some transitions, including a call after operands have been popped, and therefore defines lift points as parking transitions only ([UCF §7.4.1](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:575)).

A theoretically sound approach is possible: pause at a defined safepoint, lift the runtime frame into canonical state, then lower that state into a destination engine using its safepoint layout and activation data. UCF separates static metadata derivable from the canonical instruction vector from dynamic data that only the activation frame carries ([ucf.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf.cljc:393), [ucf.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf.cljc:434)). Its conformance condition requires round-tripping canonical frames at safepoints; a non-safepoint request must return `:yin.k/not-at-safepoint` ([UCF §7.4.2](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:705)). This is reconstruction using frame state and verified maps, not recompilation of the current activation from AST alone.

The binding markers make the target limitation explicit. A semantic closure uses `:yin.k/binding :named`, with named parameters and an environment map. A positional closure uses `:yin.k/binding :positional`, with arity and captured frames. Lowering one into the other is refused as `:binding-mismatch`; the linker says conversion must be a re-lowering of source under a derivation record ([UCF §7.5](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1100), [linker.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.md:1420)). That fail-closed rule prevents a backend switch from quietly changing closure capture or variable resolution.

Finally, even a correct register state is not enough for a migration. UCF requires code identity and contract stamps, portable recursive value encoding, dependency and scheduler closure, resource reconstruction, and custody/fencing. Its own header warns that the protocol is deferred and blockers remain ([UCF §7.11](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:2667)). The repository has UCF code for canonical code identity, safepoint metadata, and portions of marker/frame handling, but that is not evidence that the full cross-kernel migration contract is accepted. For example, `ucf.cljc` derives safepoints and activation data, while §7.11 still calls for conformance and integration closure.

**Recommended architecture:** keep register compilation as a source projection from resolved tuples. For a continuation handoff, require an actual park, encode the complete state under UCF, validate the contract and binding discipline at the destination, and only lower into a kernel whose format accepts the value. A direct semantic-to-register continuation translation is a future UCF implementation path only if it satisfies those rules; compiling an arbitrary paused state from the original AST and resuming it in the register VM is outside current repository commitments.
