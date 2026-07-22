# DaoSpace: The Tuple Space

`dao.space` is **not a thing you store or a component you deploy**. It is the **tuple space
that emerges** when agents use two libraries over a shared [`dao.jing`](dao.jing.md):

- **[`dao.space.index`](dao.space.index.md)** — the transactor side: each agent builds
  covered indexes over its own [`dao.stream`](dao.stream.md) and persists them in `dao.jing`
  as immutable, content-addressed B-Tree segments.
- **[`dao.space.query`](#the-query-library)** — the Peer side: agents match over the
  persisted indexes (and each other's local indexes) associatively — by content, never by
  address.

Storage holds facts at rest; the tuple space is those facts *under shared interpretation*.
More precisely, "tuple space" names the **associative coordination surface** of a broader
family of interpreters over those facts (see [A Family of Interpreters](#a-family-of-interpreters)).
A tuple space is defined by two complementary moves: how agents **read** and how they
**write**. Reading is **associative matching** — you locate a tuple by describing its
*content*, never by naming its address. Writing is **generative communication** — you deposit
a tuple into the shared medium and never address a receiver. Take the read side first; its
surfaces form a spectrum along the by-content axis:

- **`match`** — a single positional datom template, Linda-style. One template, matched by
  content, returns the tuples that fit. This is associative matching in its most basic form.
- **`q` (Datalog)** — the *same* by-content matching **generalized to conjunctions**: many
  templates joined on shared logic variables, plus negation, recursion, and aggregation. A
  single-clause `q` *is* a `match`; Datalog keeps the associativity and generalizes
  the arity from one tuple to many tuples joined. So `match ⊂ q`, both associative.
- **Graph / tree / entity-centric / columnar traversal** — these locate data by **following a
  reference or position**. That is navigation, not matching: addressing, not by-content. They
  are useful *local read ergonomics* over an interpreter's own materialized views, but they sit
  *off* the associative axis.

The write side is simpler but just as load-bearing. Generative communication means an agent
**deposits** a datom and names no recipient — it appends to its own stream and is done.
datom.world also drops Linda's one destructive operation: Linda pairs its non-destructive read
(`rd`) with a consuming `take` (`in`) that *removes* a tuple from the medium; datom.world has
no such removal. Writes are **append-only**, so to claim or update something an agent appends a
*new* datom asserting the change, and current state is a read-side query over the accreted
facts. Each writer owns a single-writer log, so there is no
shared write surface and no contention — writers deposit, they never address. (This is the
decentralized Transactor; see [Three Boundaries](#three-boundaries) and
[Coordination: Stigmergy](#coordination-stigmergy).)

`match` and `q` are the privileged default and the contract strangers coordinate through; it
is associative matching over a shared medium that makes `dao.space` a tuple space. The
traversal surfaces are admitted by the family but are not coordination modes. The label
therefore holds as long as two things hold: **coordination stays associative** (agents find
each other by matching content, never by addressing each other or navigating each other's
views by reference — the moment cross-agent coordination runs through traversal, it has left
the tuple space), and **the shared substrate stays datoms** (agents coordinate over matchable
facts; whatever else an interpreter materializes is its own, not the medium).

**Related documents:**
- `docs/design/dao.field.md` — the sibling point in the moduli space: the vector field made from the same n-tuples, matching by geometric proximity (cosine) rather than exact unification
- `docs/design/dao.jing.md` — the storage boundary: the content-addressed store of opaque bytes this space reads as datoms
- `docs/design/dao.space.query.md` — the query library's design record: index realization, the `read-datoms` contract, and the Target Architecture rulings
- `docs/design/dao.space.index.md` — the transactor-side indexing library: every agent indexes its own datoms; the covered-index realization both sides share
- `docs/design/dao.stream.md` — the append-only log primitive datoms are written through
- `docs/agents/datom-spec.md` — tuples and datoms, content-addressed identity, the gauge/base framing
- `docs/datomic.md` — the Datomic architecture the Transactor/Storage/Query split maps to
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the decision this design records
- `docs/design/adr/0002-share-governed-computation-not-data.md` — the access-mode security model
- `docs/design/dao.space.security.md` — the controlled-mode / capability detail
- `docs/design/dao.space.v0.md` — superseded framing; still the reference for resources, typed streams, and the geometry/gauge material
- `docs/design/dao.space.locality.md`, `dao.space.metaphors.md`, `dao.space.discrete-to-continuous.md` — the geometry/locality cluster: theoretical justification (gauge, spectral, locality)

## Three Boundaries

Datomic's central strength is the strict separation of **Transactor**, **Storage**, and
**Query** into *abstraction boundaries* — interfaces, not deployment tiers. They can be
co-located in a single process with zero network overhead, or split across machines, without
changing the contracts. datom.world keeps that separation and maps it onto streams:

- **Transactor (write + index)** — **decentralized.** Every agent appending to its own
  `dao.stream` is its own transactor: it enforces local schema and appends datom frames,
  with no global contention and no commit step. It also carries the transactor's other
  Datomic duty — building the covered indexes over its own datoms — via the
  [`dao.space.index`](dao.space.index.md) library: a local in-memory index over the
  agent's stream, eventually persisted to `dao.jing` as content-addressed B-Tree segments
  when `publish-index!` runs.
- **Storage** — **[`dao.jing`](dao.jing.md).** The decentralized, content-addressed store
  where indexes live persistently. It holds immutable segment blobs (the B-Tree nodes) and
  mutable root references; it does **not** match or query.
- **Query (read)** — **the [`dao.space.query`](#the-query-library) library any interpreter
  embeds.** It reads from two layers — local indexes (the agent's own in-memory cache over
  its stream) and persistent indexes in `dao.jing` (every agent's published segments) — and
  runs pattern matching and Datalog. Pure, in-process, per-interpreter — Datomic's Peer
  model, as a library rather than a service.

**`dao.space` is not one of the three boundaries; it is the coordination surface that spans
all three.** The transactor writes and indexes; storage persists; the query matches. The
tuple space is the behavior that emerges when agents exercise all three faculties — writing
to their streams, indexing for themselves and others, and matching over the result. This
document specifies that tuple space. Storage is specified separately in
[`dao.jing.md`](dao.jing.md).

## What Makes It a Tuple Space

Both moves the intro defines — associative matching and generative communication — are
behaviors of the two libraries agents use, **not** properties of storage. A store that
matched would collapse interpretation into storage, which datom.world's invariants forbid.
So the "space-ness" lives above `dao.jing`, in the composition of index and query:

- `dao.stream` — each agent's append-only log (the write path).
- `dao.space.index` — builds local covered indexes over the stream's datoms, and persists
  them in `dao.jing` when `publish-index!` runs.
- `dao.space.query` — matches over both local indexes and persisted indexes, associatively.
- Agents coordinate by what is there (stigmergy), never by addressing each other.

A tuple space is therefore not an artifact you instantiate; it is the behavior that appears
when agents index and match over shared storage. `dao.stream` is where an agent writes;
`dao.jing` is where indexes persist; `dao.space` is what agents *do* across both.

### The Convention of Datoms

Because the tuple space lives in the interpreters, the requirement that primary state be
datoms is a **social contract**, not a limit the storage layer enforces. `dao.jing` is a dumb
key-value store: it is **not strict about what it holds** and will accept any bytes you write —
datoms, graphs, JSON blobs, binaries. Nothing about storage requires the datom shape.
(Arbitrary structures are fine when an interpreter materializes them *over* the datoms — that
is the interpreter's own responsibility, and `dao.jing` just holds the resulting bytes; see
[A Family of Interpreters](#a-family-of-interpreters). This contract is only about what an
interpreter writes as ground truth.)

The datom shape is the **price of admission** to the tuple space, freely chosen:
- **Opting In:** If an interpreter formats its facts as `(e a v t m)` datoms, its data
  "enters" the tuple space. Because it conforms to the universal substrate, `dao.space.query`
  can match over it, and strangers can query it associatively.
- **Opting Out:** If an interpreter writes arbitrary primary state to `dao.jing` instead, the
  store accepts it without complaint — this is permitted, not forbidden. The cost is simply
  that the data stays *outside* the tuple space: the query library cannot match over it,
  strangers cannot discover it, and it inherits none of the guarantees the datom log confers
  (immutability, replayability, provenance). The interpreter has chosen a private store over a
  shared, queryable medium. Nothing breaks; that data just does not coordinate.

## The Query Library

Pattern matching and Datalog are a **library** (`dao.space.query`) that any interpreter
embeds and runs against `dao.jing` and local indexes. It is pure: pull datoms from the two
layers below, build an in-memory index, answer. It owns no durable state.

The library reads from **two layers**, reflecting the index lifecycle:

- **Local indexes** — the agent's own in-memory sorted sets over its stream's datoms.
  Built by `dao.space.index` as a local cache; `dao.space.query` can read these directly
  for the agent's most recent writes, before `publish-index!` has run.
- **Persistent indexes in `dao.jing`** — every agent's published B-Tree segments.
  Restored lazily on the JVM (only the seek path is fetched), walked eagerly elsewhere.
  This is the shared layer: other agents' data reaches you through `dao.jing`, never
  through their local indexes.

A query over a single agent's store may hit both layers (local cache for recent appends,
persisted segments for previously-published data). A federated query over multiple stores
merges the persistent layers.

```clojure
(require '[dao.space.query :as query])

;; q — Datalog over all of storage. The design contract is full Datalog
;; (joins, negation, aggregation, predicates, recursion); the
;; implementation covers it: not/not-join, :find aggregates (count,
;; count-distinct, sum, min, max, avg) with :with, predicate/function
;; clauses via a caller-supplied {:fns {sym fn}} option, and recursive
;; rules bound to % via :in (Datomic syntax; multiple bodies = OR,
;; terminates on cyclic data).
(query/q '[:find ?id ?task
           :where [?id :work/status :todo]
                  [?id :work/task ?task]]
  store)
;; => #{[id task] ...}

;; match — a positional datom template (Linda-style), lighter than q
(query/match store [_ :work/status :todo])   ; => matching datoms

;; as-of — a read bound: index storage only up to `point`
(query/q query-form store {:as-of t})
(query/match store pattern {:as-of (instant "2026-01-01")})
```

Within a single stream `as-of t` is exact (the stream's own append order). A **cross-stream**
`as-of` needs a time coordinate comparable across streams — a wall-clock instant (as shown)
rather than per-stream `t` — and its precise semantics are deferred (see ADR 0001, Open
Question 2).

Internally the library folds the store's datoms into an index and queries it. The design calls
for each stream's datoms to be stamped with the stream's namespace before indexing, so two
streams' local id `1025` stay distinct; "same logical entity across streams" is meant to be a
join on shared *values*, never on a stream-local id. **Not yet implemented**: `dao.jing.md`'s
Current Scope is explicit that this namespace stamping is not yet derived from the canonical
kickoff hash, and `dao.space.query.md`'s `read-datoms` Contract confirms `read-datoms` today
returns datoms *unstamped* — cross-stream local-id collision is a known, currently-open gap,
not a bug, until content addressing is load-bearing enough to derive a stream's namespace.

```clojure
;; Sketch of the library, rebuild-per-query form (pedagogical; not today's path).
;; `read-datoms` parses B-Tree
;; segments pulled from dao.jing into datoms — storage has no datoms API of its own.
(ns dao.space.query
  (:require [dao.jing :as store]))   ; the store handle namespace

(defn- fold [store as-of]
  (index (read-datoms store {:as-of as-of})))   ; build EAVT/AEVT/AVET

(defn q     [query-form store & {:keys [as-of]}] (run-datalog query-form (fold store as-of)))
(defn match [store pattern    & {:keys [as-of]}] (scan-pattern  pattern    (fold store as-of)))
```

`dao.jing` holds only the index's immutable byte segments and a mutable root reference — the
same substrate Datomic persists its covered indexes into (opaque segment blobs plus a root
pointer; see [`dao.jing.md`](dao.jing.md), *The Segment and Root Keyspace*). The *index* itself — a
covered B-Tree (EAVT/AEVT/AVET/VAET) a reader can traverse and answer queries from — is an
interpretation projected onto those bytes; storage never knows the segments form an index.
That interpretation — the sort orders, the node-blob format, the root-manifest convention —
is owned by [`dao.space.index`](dao.space.index.md), the transactor-side indexing library
this Peer consumes. How the index is maintained (rebuild-per-query, incremental, or
owner-built/peers-merge) is a concern of the layers above storage, realized on
`tonsky/persistent-sorted-set`; see [`dao.space.query.md`](dao.space.query.md), *Index
Realization*. All variants answer identically.

**Current status.** Two root shapes coexist at a stream's root (`:root/<name>`, one per
stream, enumerated by the `:root/members` membership root; the old shared
`:root/datoms` is removed). The
baseline holds a stream's full datom vector wholesale (`{:datoms [...]}`) and folds it into a
fresh in-memory index on every query — the "rebuild per query" strategy ("kept only as the
conceptual baseline"; see [`dao.space.query.md`](dao.space.query.md), *Index Realization*),
with `tonsky/persistent-sorted-set` as the sorted-set implementation. Since 2026-07-10 the
owner-built Target Architecture is also implemented: `dao.space.index/publish-index!`
(the transactor-side indexing library, `docs/design/dao.space.index.md`)
persists a stream's covered indexes as immutable, content-addressed B-Tree segments (`put!`
under `segment-key`) and advances the root to `{:indexes {:eavt :segment/sha256-<hash> ...}}`; a
JVM reader restores those indexes lazily (a bound-`e` lookup fetches only the seek path — 2
segments of 26 in the test suite), while cljs/cljd readers, and the as-of/federated paths
everywhere, read the same segments eagerly by walking the plain-EDN node graph. Laziness is
JVM-only for now (psset durability is a Clojure-only feature); readability is universal. A
read still resolves the stream's root with a single `get` targeting an immutable snapshot,
so concurrent writes never disturb an in-flight read. Remaining gaps: segment GC, non-JVM
laziness, k-way merge of multiple lazy indexes (federated queries fall back to the eager
walk), and general n-tuple matching (`match`/`q` pad templates to the datom 5-tuple; other
dimensions are specified, not implemented — see *Lineage*); see *Index Realization*.

### Source Polymorphism

`q` and `match`'s second argument is a **source**, not narrowly a `dao.jing` handle. The
library must accept, interchangeably:

- **A single `dao.jing` handle** — the case above; `fold` pulls B-Tree segments through
  `read-datoms` and indexes them.
- **A collection of `dao.jing` handles** — a *federated* query over several stores at once,
  e.g. a local `KVFile` plus a peer's `KVDht` node. This is not a new mechanism: ADR 0001's
  monoid-homomorphism proof (`index(S₁ ⊎ … ⊎ Sₙ) = merge(index(S₁), …, index(Sₙ))`) already
  establishes that folding N stores and merging is the same index as one store holding
  everything, so `fold` over a collection is `merge` over the per-store folds, not a
  different code path.
- **A raw Clojure vector of datoms** — `[[e a v t m] ...]`. This skips the byte-parsing step
  entirely (`read-datoms` exists only to turn `dao.jing` bytes into this shape); a caller who
  already has datoms in hand — a REPL scratch value, a test fixture, an in-memory scratchpad
  never destined for storage — indexes them directly.
- **A raw Clojure vector of entity maps** — `[{:work/status :todo, :work/task "x"} ...]`.
  Normalized to datoms first: a map without `:db/id` gets a fresh tempid (mirroring
  `dao.db.in_memory`'s existing map-form-entity convention), then each `k v` pair becomes an
  `[e k v]` datom (`t`/`m` defaulted, same as `dao.datom/default-op`).

A mix is legal too — a collection argument may hold `dao.jing` handles and raw datom/entity
vectors side by side; each element is folded by whichever rule matches its shape and the
results are merged. This makes the library useful standalone, the same way Datomic's `d/q`
takes db values and in-memory rel/collection inputs interchangeably — a caller should never
need a throwaway `dao.jing.mem/create-kv-mem` just to query a handful of test datoms. It does
not change what the tuple space *is*: coordination between agents still runs through shared
`dao.jing` storage (see *What Makes It a Tuple Space*, above), because a raw in-memory vector
is by definition not shared. Source polymorphism is an ergonomic property of the query
*function*, not a second medium.

```clojure
;; source dispatch: each shape folds to the same datom shape before indexing
(defn- source->datoms
  [source as-of]
  (cond
    (satisfies? store/IKVStore source) (read-datoms source {:as-of as-of})
    (and (coll? source) (every? #(satisfies? store/IKVStore %) source))
    (mapcat #(read-datoms % {:as-of as-of}) source)
    (and (coll? source) (every? vector? source)) source          ; already datoms
    (and (coll? source) (every? map? source)) (entity-maps->datoms source)
    :else (throw (ex-info "unrecognized query source" {:source source}))))

(defn- fold [source as-of] (index (source->datoms source as-of)))
```

### Global match and scoping

The library reads `dao.jing`, and `dao.jing` is the *global* repository, so any interpreter
that embeds the library matches over **everything** by default — global, associative,
addressed by content, never by producer. That global reach is the identity of a tuple space;
the per-interpreter *embedding* of the library does not scope it, because the *storage* it
reads is shared.

Two different things hide under "scoping," and only one is a security mechanism.

**Relevance / performance scoping** is a **predicate over content** inside the query — never
an enumeration of producers. A scoped view is "the datoms matching `P`," where `P` names
shapes (`[?e :work/* ?v]`), so the reader still surfaces tuples from writers it never heard
of. This is a *trusted* reader choosing to look at less; it is **not** security — choosing to
read less never stops you from reading more.

**Security and Access Modes**

datom.world's security model rests on the principle: **"share governed computation, not
data."** Sharing bits is losing control of those bits, and encryption only relocates the
problem to key-sharing. Plaintext result-filtering *does* need a mediator — but rather than
pretend to escape that, the model makes the mediator **generic and accountable**: control is
held by never emitting raw datoms, only the bounded result `f(X)` of an authorized
interpreter `f`.

This yields two distinct access modes (see [dao.space.security.md](dao.space.security.md) and
[ADR 0002](adr/0002-share-governed-computation-not-data.md) for full details):

- **Public (pull-to-reader):** the default mode. The reader embeds the library and pulls
  datom streams from `dao.jing` directly. There is no fine-grained control; the only
  security is coarse, per-stream access (POSIX-style filesystem permissions on the member
  logs). Use this when it is safe to ship the datoms.
- **Controlled (push-interpreter-to-data / confined):** when fine-grained per-datom control
  is needed, the topology inverts and the data never leaves its owner's control. The reader
  submits a governed interpreter (a `yin.vm` AST) wrapped in a capability; it runs in a
  confined environment scoped to the authorized datoms, and only the attenuated answer
  returns. The **capability token** is cryptographically authenticated; the **content
  predicate** (the `m` slot carries the policy) is enforced by the evaluation substrate —
  operationally by a confined CESK runtime, or cryptographically by an MPC/FHE circuit —
  which is distinct from authenticating the token. The owner is the mediator by default; MPC
  removes even that (see the security doc).

Trusted peers and public data are the common case: embedded library, direct access, global
match. When control is required, the architecture switches to controlled mode, where the unit
of sharing is the governed interpreter, backed by an immutable accountability log.

**Public mode only, today.** Controlled mode — the governed interpreter, capabilities,
`m`-policy — is specified but not yet implemented (see the security doc and ADR 0002).

## A Family of Interpreters

The query library above presents one surface: Datalog over the covered-index view
(EAVT/AEVT/AVET/VAET). That is the default and what's implemented today, but it is not the definition of
`dao.space`. More generally, **`dao.space` is a family of interpreters over one canonical
datom history** — a *moduli space* of databases. The datoms in [`dao.jing`](dao.jing.md) are
the fixed substrate every member shares; a member is fixed by which **materialized views** it
constructs and which surface it exposes. One point looks relational (covered indexes plus
Datalog); another document-oriented (entity-centric maps); others columnar, graph-oriented, or
logic-oriented. The substrate stays the same; only the materialized construction laid over it
changes — and `dao.jing` stores whatever an interpreter builds as ordinary bytes, since it is
dumb storage that holds anything (see [`dao.jing.md`](dao.jing.md)).

Two consequences follow, and both tighten the existing invariants rather than relax them:

- **Datalog is a capability, not the ontology.** It stays the relational front-end wherever
  relational views exist, but it is one front-end among several, not the essence. The essence
  is lower: datom streams plus *declared* structural interpretation.
- **Views are named and declared, not infinite magic.** An interpreter does not navigate
  every imaginable materialization; it exposes a sanctioned set and answers explicit
  questions: which views exist, which can be built on demand, whether a view is
  relational / graph / tree / associative / sequential, what its legal traversals are, and
  what its `as-of`/`since` semantics are. A query may trigger on-demand derivation, but only
  through a declared interpreter with known semantics — explicit causality and explicit
  capability, nothing implicit. (*Do not assume graphs*: graph structure is one materialized
  interpretation constructed from datoms, never an implicit truth.)

This reframes querying as **compilation** rather than handing a sentence to a fixed engine: a
caller states intent, a planner inspects the declared view capabilities and rewrites that
intent into an access plan, and an executor runs the plan against a materialized view or
triggers a declared derivation. The same "find user by email" intent compiles to an indexed
`AVET` lookup against one interpreter and an entity scan against another — same family,
different database. The concrete compiler pipeline (a planner/optimizer front-end lowering
into a traversal-IR back-end) is a direction, not yet specified here.

## The Write Path

The read side is matching; the write side is how datoms get into the shared medium. A datom
enters by being appended to a `dao.stream` member log — an append-only file the writer owns
and never edits in place. Opening a log is what makes a writer a participant: it attaches a
feeding stream to the space; closing it detaches. This is the mechanics behind the
generative-communication move described in the intro (deposit by appending, name no
recipient) and behind the decentralized Transactor of [Three Boundaries](#three-boundaries).

### Membership is intake, not identity

**Membership** names which streams currently feed the space at a given moment — a write-path
concern, not the space's identity. The space is the shared, queryable medium of the datoms
those streams have contributed, not "a set of streams": streams join and leave at runtime,
while the medium persists. (Storage, [`dao.jing`](dao.jing.md), is the dumb KV the streams
feed; the read side never sees the streams, only the bytes in storage.)

Because each writer owns a single-writer log, two agents never write the same stream. If
1,000 agents want to send messages to one recipient, they append to 1,000 distinct
single-writer streams, and the recipient merges them on the read side — no shared write
surface, no contention.

### Indexing is the writer's duty

The write path does not end at the append. In Datomic the transactor also builds the covered
indexes and saves them to storage; here that duty is decentralized with the rest of the
Transactor. Indexing has two stages, mirroring Datomic's memory-index → disk-index pipeline
but without a central transactor process:

1. **Local indexes** — `dao.space.index` builds in-memory sorted sets over the agent's
   stream datoms. This is the agent's own cache: `dao.space.query` reads it directly, so
   the agent's recent writes are immediately queryable without publishing.
2. **Persistent indexes** — when the agent runs
   [`dao.space.index/publish-index!`](dao.space.index.md), the local indexes are serialized
   as immutable, content-addressed B-Tree segments and stored in `dao.jing`. The root
   advances to the `{:indexes ...}` manifest. Other agents now see this data through
   `dao.jing`; the local cache and the persisted segments cover the same datoms at
   different lifecycle stages.

Publishing is an acceleration, never a semantic change for `q`/`match` — readers answer
identically over a wholesale root, a local index, or a persisted index — and the stages
interact safely: a later `ds/append!` folds a persisted root back to wholesale rather than
dropping it, until the owner republishes. See `dao.space.index.md`, *The agent-transactor
loop*.

The same is not true for a `dao.stream` cursor reading a stream's own root directly
(`ds/next`, not `q`/`match`): publishing reorders the datoms `next` walks from append order to
index order, so a live cursor gaps (`:daostream/gap`) rather than silently landing on a
different datom. See `dao.space.transactor`'s ns docstring for the mechanism
(`:reorder-epoch`) and why this gap's recovery differs from the eviction-based transports.

### Fault Tolerance (Crash-Only Semantics)

Because the write path uses persistent append-only `dao.stream` files, the space inherits
crash-only semantics natively:

- **Data safety:** Datoms flushed before a crash are safe; append-only files have no
  partial-update corruption window.
- **Reader behavior:** A reader tailing a crashed writer's stream simply reaches the end and
  yields (`ds/next` returns `:blocked`); it waits for new data rather than failing.
- **Write recovery:** A restarted writer reopens its file in append mode; the next `ds/append!`
  lands safely after the last flushed datom.
- **Read recovery:** A reader resumes from a checkpointed cursor, so an incremental index
  rebuilds without reprocessing or skipping. Against `:transactor` a checkpoint must persist
  the whole cursor map (`(:cursor result)`, not just the bare `:position` offset) — it carries
  `:epoch`, which is what lets `ds/next` detect a publish! that reordered data since the
  checkpoint was taken and gap instead of resuming into the wrong datom (see
  `dao.space.transactor`).

## Coordination: Stigmergy

Agents coordinate by leaving datoms in `dao.jing` for others to query, decoupled in time and
identity. Because streams are append-only there is no destructive `take`: to "claim" work an
agent *appends a new datom* asserting the claim, and "current state" is a read-side query over
the accreted datoms. This is the tuple space working as designed — coordination with no
broker, no message-format negotiation, and no leader election.

The worker loop below reads with `query/q` and writes with `ds/append!` — two different
concurrency models. `append!` is a per-root CAS: an agent's own write is immediately durable
and visible to any reader that re-folds. `q` folds every member root fresh on each call, so a
claim becomes visible to *other* workers only once they re-query after the claim's `append!`
returns — there is no push, no shared read-your-writes guarantee across agents.

**The example below is runnable.** Every former blocker is implemented: `match`/`q` mask
retracted datoms and supersede cardinality-one values at query time via `current-state-seq`
(see `dao.space.query.md`, *Current-state resolution*); `q` implements `not`/`not-join`
(stratified, over the current-state-resolved index), so the `(not [_ :work/claims ?w])`
clause executes as written; and `{:type :transactor :store store :name ...}` is a
registered `dao.stream` type (`dao.space.transactor`) whose `ds/append!` deposits an entity map
or datom vector into the stream's **own** root, `:root/<name>` — each stream a single-writer
log, no shared write surface. `open!` registers the root in the store's membership root
(`:root/members`, the intake record of *Membership is intake*, above), and the query library
folds every member root and merges (the old shared `:root/datoms` is removed; a store seeded
directly must register its root via `index/register-member!`). Entity-id namespace stamping
(`[stream-ns offset]`, Ruling 3 of `dao.space.query.md`) is still pending, so cross-stream
`:db/id` collision remains the documented open gap until the kickoff-hash namespace lands.
One layering note stands: `dao.jing`'s
file backend still uses `dao.stream.log` internally as its byte transport (see
`dao.space.query.md`, *Reality check*), so the layers remain mutually acquainted even
though the write path now runs top-down:

```clojure
(defn producer [store]
  (let [log (ds/open! {:type :transactor :store store :name "producer"})]
    (ds/append! log {:db/id (random-id) :work/posted true :work/task "process payment"})))

(defn worker [store worker-id]
  (let [log (ds/open! {:type :transactor :store store :name worker-id})]
    (loop []
      ;; "posted work nothing has claimed" — negation + join over the whole store,
      ;; the query that justifies a tuple space (not a per-datom scan).
      (let [work (query/q '[:find ?w ?task
                            :where [?w :work/posted true]
                                   [?w :work/task ?task]
                                   (not [_ :work/claims ?w])]
                   store)]
        (when-let [[?w task] (first work)]
          (ds/append! log {:db/id (random-id) :work/claims ?w :work/by worker-id})
          (ds/append! log {:db/id (random-id) :work/result (process task)})
          (recur))))))
```

The naive version hides a familiar race: two workers can run the claim query before either
appends, and both then claim the same task. That is not a storage bug, and not a transactor
bug either — `dao.jing` never sees "claims," only datoms, and each worker's `append!` is a
local `cas!` over its own single-writer root; neither layer knows the other worker exists, so
neither can prevent or even detect the race. There is no lock and no `cas!` over a shared
stream to force exclusion — both claims are simply recorded in their own owners' logs.

Resolving the race, if resolution is wanted at all, is entirely an **interpreter-level policy
choice** — the same declared-materialized-view freedom [*A Family of
Interpreters*](#a-family-of-interpreters) describes, applied to conflict resolution instead of
read shape. One convention an interpreter could adopt: a downstream reader sees both claims,
sorts by some rule, and picks a winner. But "sort by timestamp" is not free of a pitfall of its
own — each claim's `t` is a per-root watermark (`(count existing)` at CAS-win time,
`dao.space.transactor`'s ns docstring), not a shared clock, so ordering two *different*
streams' claims by `t` is arbitrary, not meaningful, unless the interpreter's convention
supplies its own comparable clock (a wall-clock stamp on the datom, an external sequencer) or
sidesteps the ambiguity entirely (a single shared claims stream, entity-id ownership). Absent
such a convention, both claims simply stay queryable, and it is up to whichever interpreter is
asking whether "two claims" is a conflict to break or two valid answers to return. Exclusion is
a query rule an interpreter can choose to implement, never a guarantee `dao.jing` or
`dao.space.transactor` enforces — which is exactly why the tuple-space character belongs to
`dao.space`, not the storage or write layers below it.

## Lineage

The tuple space is **Linda's** contribution: generative communication (write into a shared
medium, don't address a receiver), spatial and temporal decoupling, non-destructive
associative matching. The divergences are immutability (append, never `take`) and being an
**n-tuple space**: tuples of any dimension (the moduli-space framing of
`docs/agents/datom-spec.md`) in place of untyped positional arrays. The datom — the canonical
persistent 5-tuple `[e a v t m]` — is the special case where `dao.space` behaves like Datomic.
Unlike Datomic, `dao.space.query/q` is specified to match over n-tuples of any dimension, not
just the datom shape; the implementation today is still datom-shaped (`match` and `q` pad
positional templates to five via `pad-to-5` and unify against 5-tuples), so general n-tuple
matching is spec, not yet implemented. Matching stays global by default,
because a coordination medium for strangers must let any reader match the whole store, not
only what it bound.

The other two traditions live in the layers below and have their own docs:

- **Datomic** owns [`dao.jing`](dao.jing.md) — the dumb KV store of immutable segments and
  the Peer-as-library read model.
- **Plan 9** owns [`dao.stream`](dao.stream.md) — the independent, location-transparent,
  append-only log substrate.

The synthesis: **`dao.space` is the tuple space that emerges when agents index their streams
(via `dao.space.index`, persisting covered indexes in `dao.jing`) and match over the result
(via the `dao.space.query` Peer library).** Indexing creates queryable structure from raw
appends; matching finds content associatively across every agent's published data; the tuple
space is the coordination these two moves compose.
