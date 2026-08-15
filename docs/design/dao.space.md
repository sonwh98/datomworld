# DaoSpace: The Tuple Space

`dao.space` is **not a thing you store or a component you deploy**. It is the **tuple space
that emerges** when agents use two libraries over a shared [`dao.jing`](dao.jing.md):

- **[`dao.space.index`](dao.space.index.md)** — the transactor side: each agent builds
  covered indexes over its own [`dao.stream`](dao.stream.md) and enqueues them through a
  DaoJing intake pool into content storage as immutable, content-addressed B-Tree segments.
- **[`dao.space.query`](#the-query-library)** — the Peer side: agents match over published
  sources (and over in-process tuple relations) associatively — by content, never by
  address.

Storage holds facts at rest; the tuple space is those facts *under one specific
interpretation*: exact associative tuple matching. DaoSpace is one point in
Datom.world's broader moduli space of database interpreters (see
[DaoSpace Is One Point](#daospace-is-one-point)).
A tuple space is defined by two complementary moves: how agents **read** and how they
**write**. Reading is **associative matching** — you locate a tuple by describing its
*content*, never by naming its address. Writing is **generative communication** — you deposit
a tuple into the shared medium and never address a receiver. Take the read side first; its
surfaces form a spectrum along the by-content axis:

- **`match`** — a single positional tuple template, Linda-style. One template, matched by
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
**deposits** a tuple and names no recipient — it appends to its own stream and is done.
datom.world also drops Linda's one destructive operation: Linda pairs its non-destructive read
(`rd`) with a consuming `take` (`in`) that *removes* a tuple from the medium; datom.world has
no such removal. Writes are **append-only**, so to claim or update something an agent appends a
*new* tuple asserting the change. Under the canonical datom interpretation, current state is a
read-side query over the accreted facts. Each writer owns a single-writer log, so there is no
shared write surface and no contention — writers deposit, they never address. (This is the
decentralized Transactor; see [Three Boundaries](#three-boundaries) and
[Coordination: Stigmergy](#coordination-stigmergy).)

`match` and `q` are the privileged default and the contract strangers coordinate through; it
is associative matching over a shared medium that makes `dao.space` a tuple space. The
traversal surfaces are useful derived read conveniences but are not coordination modes. The label
therefore holds as long as two things hold: **coordination stays associative** (agents find
each other by matching content, never by addressing each other or navigating each other's
views by reference — the moment cross-agent coordination runs through traversal, it has left
the tuple space), and **the shared substrate stays tuples** (agents coordinate over matchable
immutable values; the d5 datom is the canonical persistent fact interpretation, not the only
admissible tuple dimension).

**Related documents:**
- `docs/design/dao.field.md` — the sibling point in the moduli space: the vector field made from the same n-tuples, matching by geometric proximity (cosine) rather than exact unification
- `docs/design/dao.jing.md` — the storage boundary: the content-addressed store of opaque payloads. Today this space consumes published covered-index sources from it; explicitly requested positional indexed snapshots are the future extension
- `docs/design/dao.space.query.md` — the query library's design record: index realization, the `read-datoms` contract, and the decisions
- `docs/design/dao.space.index.md` — the transactor-side indexing library: every agent indexes its own datoms; the covered-index realization both sides share
- `docs/design/dao.stream.md` — the append-only log primitive tuples and descriptors are written through
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
  `dao.stream` is its own transactor: it writes atomic transaction records to its local
  stream, with no global contention and no commit step. It also carries the transactor's
  other Datomic duty — building the covered indexes over its own datoms — via the
  [`dao.space.index`](dao.space.index.md) library: `publish-index!` snapshots the stream,
  builds the four B-tree indexes, and appends them (node blobs, then the manifest) to an
  intake stream that a DaoJing observer materializes into content storage.
- **Storage** — **[`dao.jing`](dao.jing.md).** The content-addressed store where indexes
  live persistently. It observes an explicit pool of intake streams and materializes each
  opaque payload insert-if-absent under its content address (`:segment/sha256-<hash>`).
  It holds the B-tree node blobs and the top-level manifests; it maintains no membership,
  no mutable roots, and no CAS surface, and it does **not** match or query.
- **Query (read)** — **the [`dao.space.query`](#the-query-library) library any interpreter
  embeds.** It reads from two layers — in-process relation/entity-map sources, and
  published sources (a content store plus a manifest address) resolved through the
  covered-index realization — and runs pattern matching, Datalog, and pull. Pure,
  in-process, per-interpreter — Datomic's Peer model, as a library rather than a
  service.

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
- `dao.space.index` — snapshots the stream's datoms and builds the covered indexes,
  enqueuing them through a DaoJing intake pool when `publish-index!` runs.
- `dao.space.query` — matches over published sources and in-process tuple sources,
  associatively.
- Agents coordinate by what is there (stigmergy), never by addressing each other.

A tuple space is therefore not an artifact you instantiate; it is the behavior that appears
when agents index and match over shared storage. `dao.stream` is where an agent writes;
`dao.jing` is where indexes persist; `dao.space` is what agents *do* across both.

### Tuples and the Datom Convention

Because the tuple space lives in the interpreter, DaoSpace accepts immutable
tuples of any finite dimension. A tuple's arity is structure, never an implicit
interpretation. `dao.space.query` matches arbitrary and mixed dimensions with
exact arity by default; explicit rest syntax requests prefix matching.

The d5 datom `(e a v t m)` remains the canonical persistent fact protocol. An
explicit datom view gives those positions entity, attribute, value,
transaction, and metadata meaning and enables automatic covered indexes. A
generic five-tuple does not become a datom merely because its count is five.

`dao.jing` is even less restrictive: it materializes any opaque payload an
intake stream carries. It is an interpretation of DaoStream as
content-addressed key/value storage, but it assigns no domain semantics. Any
point in Datom.world's database moduli space may use DaoJing for storage; the
consumer above storage decides whether a payload is a tuple, index, graph,
model, or something else.

## The Query Library

Pattern matching and Datalog are a **library** (`dao.space.query`) that any interpreter
embeds and runs against content stores and in-process sources. It is pure: fold a source
into an index, answer. It owns no durable state and never writes.

The library reads from **two layers**, reflecting where its tuple inputs live:

- **In-process sources** — an exact-bounded descriptor made by
  `query/relation` for arbitrary n-tuples or `query/entity-map-relation`, or an already-opened closed
  realization. No storage involved; this is the ergonomic path for a
  scratchpad, a test fixture, or the agent's own recent writes.
- **Published sources in `dao.jing`** — every agent's published B-tree segments, exposed
  as a bounded DaoStream descriptor.
  The implemented adapter validates and eagerly walks the manifest into a
  retained logical d5 realization. This is the shared layer: other
  agents' data reaches you through the content store, never through their in-process
  state.

A query reads exactly the db-values the caller names. Several published
index descriptors are separate inputs, including descriptors over multiple stores.
DaoJing intake pools — which streams an observer materializes — are a separate physical
topology and never imply what a query reads (`dao.jing.md`, *Physical intake versus
semantic composition*).

```clojure
(require '[dao.space.query :as query])

(def source
  (index/published-index
    {:dao.jing/type :dao.jing/file
     :path "/srv/datom-world/content.log"}
    manifest-address))

;; q — Datalog over the supplied source pool. The design contract is full Datalog
;; (joins, negation, disjunction, aggregation, predicates, recursion); the
;; implementation covers it: not/not-join, or/or-join, :find aggregates (count,
;; count-distinct, sum, min, max, avg) with :with, predicate/function
;; clauses via a caller-supplied {:fns {sym fn}} option, and recursive
;; rules bound to % via :in (Datomic syntax; multiple bodies = OR,
;; terminates on cyclic data). q returns a closed bounded result DaoStream.
;; query/collect materializes conventional Datalog shapes.
(query/collect
  (query/q '[:find ?id ?task
             :where [?id :work/status :todo]
                    [?id :work/task ?task]]
    source))
;; => #{[id task] ...}

;; match — an exact positional tuple template (Linda-style), lighter than q
(query/match source [_ :work/status :todo])   ; => matching tuples

;; pull — declarative entity projection (entity → tree), the third read verb
(query/pull source 1025 [:name :age {:friend [:name]}])

;; as-of — a read bound: index storage only up to `point`
(query/q query-form (query/current source t))
(query/match (query/history source t) pattern)
```

Within a single stream `as-of t` is exact (the stream's own transaction order). A
**cross-stream** `as-of` needs a time coordinate comparable across streams — a wall-clock
instant (as shown) rather than per-stream `t` — and its precise semantics are deferred
(see ADR 0001, Open Question 2).

Datom interpreters expose one physical d5 source through `(query/current
source)` or `(query/history source)`. Several sources remain separate
database inputs in `:in`; source-qualified clauses express deliberate unions
or joins. Equal stream-local ids therefore never collide through an implicit
flattening step, and source scope never becomes a tuple position.

Opening a descriptor is an ownership boundary: `q`, `match`, or `pull` drains
and closes the realization it opened. If the caller supplies an already-opened
closed realization, the query operation drains it but never closes it.

`dao.jing` holds only opaque, content-addressed payloads — the index's immutable B-tree
node blobs and the top-level manifest — and answers reads by strict segment address; it has
no mutable root pointer and no datoms API (`dao.jing.md`, *Reads*). The *index* itself — a
covered B-Tree (EAVT/AEVT/AVET/VAET) a reader can traverse and answer queries from — is an
interpretation projected onto those payloads; storage never knows the segments form an
index. That interpretation — the sort orders, the node-blob format, the manifest
convention — is owned by [`dao.space.index`](dao.space.index.md), the transactor-side
indexing library this Peer consumes. How the index is maintained (rebuild-per-query,
incremental, or owner-built/peers-merge) is a concern of the layers above storage, realized
on `dao.data.btree`; see [`dao.space.query.md`](dao.space.query.md), *Index Realization*.
All variants answer identically.

**Current status.** A query reads exactly the descriptors the caller names;
several descriptors remain
separate semantic inputs, and no shared membership record exists. The
owner-built architecture is implemented: `dao.space.index/publish-index!` (the
transactor-side indexing library, `docs/design/dao.space.index.md`) snapshots the agent's
local stream, builds the four covered indexes as immutable, content-addressed
`dao.data.btree` segments, and appends the node blobs then the manifest to one intake
stream selected from an explicit pool; a DaoJing observer materializes them into content
storage. A published index descriptor opens through a DaoJing coordinate and
eagerly walks EAVT into a retained canonical d5 stream on every platform. The
explicitly wrapped raw-datoms source path
remains the "rebuild per query" baseline for data that was never published. Remaining
gaps: segment GC, lazy published indexed snapshots, k-way merge, and generic
positional indexes; see
[`dao.space.query.md`](dao.space.query.md), *Index Realization* and *Open
items*.

### Source Polymorphism

`dao.space.query/q` accepts database values only as:
- A serializable exact-bounded DaoStream descriptor
- An already-opened closed fully-retained realization

Raw maps/vectors must be explicitly wrapped. Each database input is one logical relation:

- **A published index descriptor** — `q` opens, owns, drains, and closes the realization.
- **A wrapped raw relation** — a collection of vectors wrapped in `query/relation`.
  Arity never selects an interpretation.
- **An explicit datom view** — `(current source)` resolves d5 history and
  projects it to d3; `(history source)` exposes exact d5.
- **A wrapped entity-map collection** — `(query/entity-map-relation [{:db/id e, :work/status :todo, ...} ...])`.
  Projected explicitly to `[e k v]` facts. Identity is explicit: every map
  must carry `:db/id`.

A query composes several db-values through `:in $a $b ...`. An `or` over
source-qualified clauses expresses union; shared variables express a
deliberate join. A collection of physical sources is not an implicit merged
database. A
bare content-store handle is **not** a source: it must be wrapped in a valid
DaoStream descriptor, and passing one unwrapped throws (`bare-content-store-handle-has-no-implicit-source`).
This preserves the line the tuple space depends on: coordination between agents still runs
through shared content storage (see *What Makes It a Tuple Space*, above).
Source polymorphism is an ergonomic property of the query *function*, not a second medium.

### Source-aware match and scoping

The library reads exactly the db-values named by the query. Composition is
explicit data, with no hidden registry and no storage key whose mutation
changes membership.

Two different things hide under "scoping," and only one is a security mechanism.

**Relevance / performance scoping within a pool** is a **predicate over content** inside the
query. A scoped view is "the datoms matching `P`," where `P` names
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
  published datoms from content storage directly. There is no fine-grained control; the only
  security is coarse, per-store access (e.g. POSIX-style filesystem permissions on the
  content file). Use this when it is safe to ship the datoms.
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

## DaoSpace Is One Point

Datom.world admits a moduli space of databases because semantics belongs to
the interpreter observing DaoStream. **DaoSpace is one point in that larger
space, not the space itself.** It is fixed by tuple-space semantics:

- writers communicate generatively by appending immutable tuples without
  naming recipients;
- readers discover tuples associatively by exact positional content;
- `q` generalizes that matching through Datalog unification over shared
  variables; and
- explicitly supplied bounded sources remain independently scoped, with no
  implicit global history or membership.

`dao.field` is a sibling point whose defining observation is metric proximity.
Graph, document, columnar, temporal, or other interpreters may define further
points when they introduce distinct observation semantics. They are not
alternative DaoSpace implementations merely because they can observe the same
values.

Within DaoSpace, covered indexes, positional indexes, indexed snapshots,
entity projections, and physical B-tree traversals are access paths or derived
views. They may change cost and ergonomics but not tuple-space semantics. A
planner may select among available indexes without moving the database to a
different point. Traversal becomes a different point only when navigation,
rather than associative matching, becomes the defining observation.

DaoJing can store the materializations of DaoSpace or any sibling point. It
observes a DaoStream pool and maps each opaque payload to a strict content
address in a key/value store. That is a representation-level interpretation
with no domain semantics: DaoJing never learns which database point produced
or consumes the payload.

## The Write Path

The read side is matching; the write side is how datoms get into the shared medium. A datom
enters by being appended to a writer-owned `dao.stream` — an append-only log the writer owns
and never edits in place. Opening a log is what makes a writer a participant: it attaches a
feeding stream to the space; closing it detaches. This is the mechanics behind the
generative-communication move described in the intro (deposit by appending, name no
recipient) and behind the decentralized Transactor of [Three Boundaries](#three-boundaries).

### Intake is explicit topology, not identity

Two explicit topologies must stay distinct, because they sit on different sides of the
storage boundary (`dao.jing.md`, *Physical intake versus semantic composition*):

- **DaoJing intake pools** — the streams a DaoJing observer materializes into content
  storage. Physical ingestion topology.
- **Query pools** — the published index descriptors a reader currently opens. Semantic composition:
  an explicit collection of DaoStream descriptors.

Pools may change at runtime by producing a new collection, while each published manifest
and its materialized history persist independently. Storage, [`dao.jing`](dao.jing.md),
remains the dumb materialization; it never interprets or registers pool membership.

Because each writer owns a single-writer log, two agents never write the same stream. If
1,000 agents want to send messages to one recipient, they append to 1,000 distinct
single-writer streams, and the recipient merges them on the read side — no shared write
surface, no contention.

### Indexing is the writer's duty

The write path does not end at the append. In Datomic the transactor also builds the covered
indexes and saves them to storage; here that duty is decentralized with the rest of the
Transactor. Indexing has two stages, mirroring Datomic's memory-index → disk-index pipeline
but without a central transactor process:

1. **Append** — the agent's writes land in its own local `dao.stream` as atomic transaction
   records (see `dao.space.transactor`). The local stream is the durable record; no
   storage handle is touched.
2. **Publish** — when the agent runs
   [`dao.space.index/publish-index!`](dao.space.index.md), the local stream is snapshotted,
   the four covered indexes are built as immutable, content-addressed `dao.data.btree`
   segments, and the node blobs plus the manifest are appended to one intake stream
   selected from an explicit pool. A DaoJing observer materializes them into content
   storage; other agents consume the persisted covered indexes by naming the manifest
   address with a published source. The datoms themselves were already readable by anyone
   who folds the published manifest; publication changes access cost, not visibility.

Publishing is an acceleration, never a semantic change for `q`/`match` — readers answer
identically over a published manifest or a raw datom source. `publish-index!` returns the
manifest and its address and never mutates DaoJing directly; append success means enqueued,
not yet materialized. A partial immutable node prefix on a full intake stream is retry-safe
because the manifest is always appended last. See `dao.space.index.md`, *The
agent-transactor loop*.

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
  rebuilds without reprocessing or skipping. A checkpoint persists the whole cursor map
  (`(:cursor result)`, not just a bare `:position` offset when the transport's cursors carry
  more); a cursor that has fallen behind a retention boundary returns `:daostream/gap`, and
  resynchronization is the caller's decision. Because `publish-index!` never rewrites the
  local stream — it appends to a separate intake stream — publishing cannot reorder what a
  local-stream cursor walks.

## Coordination: Stigmergy

Agents coordinate by leaving datoms in `dao.jing` for others to query, decoupled in time and
identity. Because streams are append-only there is no destructive `take`: to "claim" work an
agent *appends a new datom* asserting the claim, and "current state" is a read-side query over
the accreted datoms. This is the tuple space working as designed — coordination with no
broker, no message-format negotiation, and no leader election.

The worker loop below reads with `query/q` and writes with `ds/append!` — two different
concurrency models. `append!` is a local append to the agent's own single-writer stream (one
atomic transaction record); it is never a CAS over a shared surface. Visibility to other
workers requires publication: `publish!` enqueues the indexes, a DaoJing observer
materializes them, and a reader folding the new manifest address sees the claim. `q` folds
the sources it is given fresh on each call, so a claim becomes visible to *other* workers
only once they re-query after republish — there is no push, no shared read-your-writes
guarantee across agents.

**The example below is representative, not a shipped binary.** Every mechanism it names is
implemented: the explicit `current` view masks assertions retracted for the
same `[e a v]` via `current-state-seq` (see `dao.space.query.md`,
*Current-state resolution*); `q`
implements `not`/`not-join` (stratified, over the current-state-resolved index), so the
`(not [_ :work/claims ?w])` clause executes as written; `{:dao.stream/type :transactor :local-stream s
:intake-pool [...]}` is a registered `dao.stream` type (`dao.space.transactor`) whose
`ds/append!` deposits one atomic transaction record into the wrapper's own local stream —
calls through one wrapper serialize timestamp allocation and append, while each stream remains
a single-writer log with no shared write surface; and `publish!` delegates to
`dao.space.index/publish-index!`, which snapshots the local stream and enqueues the covered
indexes through the intake pool. `open!` writes no registration record. The caller
constructs explicit published index DaoStream descriptors and passes each as its own
database input. The query states any union or cross-source join; no implicit
merge can collide stream-local entity ids:

```clojure
(require '[dao.jing :as jing]
         '[dao.jing.file :as file]
         '[dao.stream :as ds]
         '[dao.space.index :as index]
         '[dao.space.query :as query]
         '[dao.space.transactor :as transactor])

;; shared physical substrate: one content store, one intake stream, and a DaoJing
;; observer that materializes whatever the intake stream carries
(def store-coordinate {:dao.jing/type :dao.jing/file
                       :path "target/stigmergy-content.log"})
(def store (file/create-content-file (:path store-coordinate)))
(def intake (ds/open! {:dao.stream/type :ringbuffer}))
(def observer (jing/observer-state [intake]))

(defn pump! []
  (loop [obs observer]
    (let [{:keys [state signal]} (jing/observe-step! store obs)]
      (when (= :ok signal) (recur state)))))

(defn agent-log [agent-id]
  (ds/open! {:dao.stream/type :transactor
             :local-stream (ds/open! {:dao.stream/type :ringbuffer})
             :intake-pool [intake]
             :name agent-id}))

(defn producer []
  (let [log (agent-log "producer")]
    (ds/append! log {:db/id (random-id) :work/posted true :work/task "process payment"})
    (let [{:keys [manifest-address]} (transactor/publish! log)]  ; enqueue the indexes
      (pump!)                                                    ; materialize them
      (index/published-index store-coordinate manifest-address)))) ; read coordinate

(defn worker [worker-id source]
  (let [log (agent-log worker-id)]
    (loop []
      ;; "posted work nothing has claimed" — negation + join over the explicit pool,
      ;; the query that justifies a tuple space (not a per-datom scan).
      (let [work (query/collect
                   (query/q '[:find ?w ?task
                              :where [?w :work/posted true]
                                     [?w :work/task ?task]
                                     (not [_ :work/claims ?w])]
                     (query/current source)))]
        (when-let [[?w task] (first work)]
          (ds/append! log {:db/id (random-id) :work/claims ?w :work/by worker-id})
          (ds/append! log {:db/id (random-id) :work/result (process task)})
          (recur))))))
```

The naive version hides a familiar race: two workers can run the claim query before either
appends, and both then claim the same task. That is not a storage bug, and not a transactor
bug either — `dao.jing` never sees "claims," only opaque payloads, and each worker's
`append!` is a local append over its own single-writer stream; neither layer knows the other
worker exists, so neither can prevent or even detect the race. There is no lock and no shared
stream to force exclusion — both claims are simply recorded in their own owners' logs.

Resolving the race, if resolution is wanted at all, is entirely an **interpreter-level policy
choice** — the same interpreter freedom [*DaoSpace Is One Point*](#daospace-is-one-point)
describes, applied to conflict resolution instead of
read shape. One convention an interpreter could adopt: a downstream reader sees both claims,
sorts by some rule, and picks a winner. But "sort by timestamp" is not free of a pitfall of its
own — each claim's `t` is a per-local-stream transaction counter (`dao.space.transactor`
derives the next `t` from the retained history), not a shared clock, so ordering two
*different* streams' claims by `t` is arbitrary, not meaningful, unless the interpreter's
convention supplies its own comparable clock (a wall-clock stamp on the datom, an external
sequencer) or sidesteps the ambiguity entirely (a single shared claims stream, entity-id
ownership). Absent such a convention, both claims simply stay queryable, and it is up to
whichever interpreter is asking whether "two claims" is a conflict to break or two valid
answers to return. Exclusion is a query rule an interpreter can choose to implement, never a
guarantee `dao.jing` or `dao.space.transactor` enforces — which is exactly why the
tuple-space character belongs to `dao.space`, not the storage or write layers below it.

## Lineage

The tuple space is **Linda's** contribution: generative communication (write into a shared
medium, don't address a receiver), spatial and temporal decoupling, non-destructive
associative matching. The divergences are immutability (append, never `take`) and being an
**n-tuple space**: tuples of any dimension (the moduli-space framing of
`docs/agents/datom-spec.md`) in place of untyped positional arrays. The datom — the canonical
persistent tuple `[e a v t m]` is the special case where `dao.space` behaves
like Datomic. Unlike Datomic, `dao.space.query/q` matches arbitrary mixed
n-tuples. Plain clauses are exact-arity; explicit rest syntax requests prefix
matching. Meaning remains in the interpreter rather than in dimension.

The other two traditions live in the layers below and have their own docs:

- **Datomic** owns [`dao.jing`](dao.jing.md) — the dumb store of immutable segments and
  the Peer-as-library read model.
- **Plan 9** owns [`dao.stream`](dao.stream.md) — the independent, location-transparent,
  append-only log substrate.

The synthesis: **`dao.space` is the tuple space that emerges when agents index their streams
(via `dao.space.index`, enqueuing covered indexes through a DaoJing intake pool into content
storage) and match over the result (via the `dao.space.query` Peer library).** Indexing
creates queryable structure from raw appends; matching finds content associatively across
every agent's visible data; the tuple space is the coordination these two moves compose.
