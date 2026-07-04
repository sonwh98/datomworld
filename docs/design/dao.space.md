# DaoSpace: The Tuple Space

`dao.space` is **not a thing you store**. [`dao.jing`](dao.jing.md) holds datoms; `dao.space`
is the **tuple space that emerges** when interpreters (the `dao.space.query` library and the
agents) match over a **shared** `dao.jing` and coordinate through the traces they leave there.
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
- `docs/design/dao.qi.md` — the sibling point in the moduli space: the vector field made from the same n-tuples, matching by geometric proximity (cosine) rather than exact unification
- `docs/design/dao.jing.md` — the storage boundary: the content-addressed store of opaque bytes this space reads as datoms
- `docs/design/dao.stream.md` — the append-only log primitive datoms are written through
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, the gauge/base framing
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

- **Transactor (write)** — **decentralized.** Every agent appending to its own `dao.stream`
  is its own transactor: it enforces local schema and appends datom frames, with no global
  contention and no commit step.
- **Storage** — **[`dao.jing`](dao.jing.md).** The decentralized, content-addressed store of
  opaque bytes. It holds and serves opaque byte segments; it does **not** match or query.
- **Query (read)** — **the `dao.space.query` library any interpreter embeds.** It pulls
  datoms from `dao.jing`, builds an in-memory index, and runs pattern matching and Datalog.
  Pure, in-process, per-interpreter — Datomic's Peer model, as a library rather than a
  service.

**`dao.space` is the Query boundary in action plus the coordination it enables.** It is not a
fourth component beside the three; it is what the Query faculty and the agents *do* over
Storage. This document specifies that tuple space. Storage is specified separately in
[`dao.jing.md`](dao.jing.md).

## What Makes It a Tuple Space

Both moves the intro defines — associative matching and generative communication — are
*read-side / interpreter* behaviors, conferred by the query library and the agents, **not** by
storage. A store that matched would collapse interpretation into storage, which datom.world's
invariants forbid. So the "space-ness" lives here, above `dao.jing`:

- `dao.jing` holds the tuples.
- `dao.space.query` makes them matchable (associative Datalog over the whole store).
- Agents coordinate by what is there (stigmergy), never by addressing each other.

A tuple space is therefore not an artifact you instantiate; it is the behavior that appears
when interpreters match over shared storage. `dao.jing` is *where* the tuples live;
`dao.space` is *what agents do* there.

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
embeds and runs against a `dao.jing`. It is pure: pull datoms from storage, build an
in-memory index, answer. It owns no durable state.

```clojure
(require '[dao.space.query :as query])

;; q — full Datalog (joins, negation, aggregation, predicates) over all of storage
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

Internally the library folds the store's datoms into an index and queries it. Each stream's
datoms are stamped with the stream's namespace before indexing, so two streams' local id
`1025` stay distinct; "same logical entity across streams" is a join on shared *values*,
never on a stream-local id.

```clojure
;; Sketch of the library, rebuild-per-query form (pedagogical; not the v1 path).
;; `read-datoms` parses B-Tree
;; segments pulled from dao.jing into datoms — storage has no datoms API of its own.
(ns dao.space.query
  (:require [dao.jing :as store]))   ; the store handle namespace

(defn- fold [store as-of]
  (index (read-datoms store {:as-of as-of})))   ; build EAVT/AEVT/AVET

(defn q     [query-form store & {:keys [as-of]}] (run-datalog query-form (fold store as-of)))
(defn match [store pattern    & {:keys [as-of]}] (scan-pattern  pattern    (fold store as-of)))
```

How the index is maintained (rebuild-per-query, incremental, or owner-built/peers-merge) is a
storage-layout concern realized on `tonsky/persistent-sorted-set`; see
[`dao.jing.md`](dao.jing.md), *Index Realization*. All variants answer identically.

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
need a throwaway `dao.jing/create-kv-mem` just to query a handful of test datoms. It does
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

**v1 ships public mode only.** Controlled mode — the governed interpreter, capabilities,
`m`-policy — is specified but out of scope for v1 (see the security doc and ADR 0002).

## A Family of Interpreters

The query library above presents one surface: Datalog over the covered-index view
(EAVT/AEVT/AVET/VAET). That is the default and the v1 package, but it is not the definition of
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

### Fault Tolerance (Crash-Only Semantics)

Because the write path uses persistent append-only `dao.stream` files, the space inherits
crash-only semantics natively:

- **Data safety:** Datoms flushed before a crash are safe; append-only files have no
  partial-update corruption window.
- **Reader behavior:** A reader tailing a crashed writer's stream simply reaches the end and
  yields (`ds/next` returns `:blocked`); it waits for new data rather than failing.
- **Write recovery:** A restarted writer reopens its file in append mode; the next `ds/put!`
  lands safely after the last flushed datom.
- **Read recovery:** A reader resumes from a checkpointed cursor offset, so an incremental
  index rebuilds without reprocessing or skipping.

## Coordination: Stigmergy

Agents coordinate by leaving datoms in `dao.jing` for others to query, decoupled in time and
identity. Because streams are append-only there is no destructive `take`: to "claim" work an
agent *appends a new datom* asserting the claim, and "current state" is a read-side query over
the accreted datoms. This is the tuple space working as designed — coordination with no
broker, no message-format negotiation, and no leader election.

```clojure
(defn producer [store]
  (let [log (ds/open! {:type :dao-stream :store store :name "producer"})]
    (ds/put! log {:db/id (random-id) :work/posted true :work/task "process payment"})))

(defn worker [store worker-id]
  (let [log (ds/open! {:type :dao-stream :store store :name worker-id})]
    (loop []
      ;; "posted work nothing has claimed" — negation + join over the whole store,
      ;; the query that justifies a tuple space (not a per-datom scan).
      (let [work (query/q '[:find ?w ?task
                            :where [?w :work/posted true]
                                   [?w :work/task ?task]
                                   (not [_ :work/claims ?w])]
                   store)]
        (when-let [[?w task] (first work)]
          (ds/put! log {:db/id (random-id) :work/claims ?w :work/by worker-id})
          (ds/put! log {:db/id (random-id) :work/result (process task)})
          (recur))))))
```

The naive version hides a familiar race: two workers can run the claim query before either
appends, and both then claim the same task. That is not a storage bug; it is check-then-act
over an append-only log. There is no lock and no `cas!` over a shared stream to force
exclusion — both claims are simply recorded in their own owners' logs. The conflict is
resolved **on the read side**: a downstream reader sees both claims, sorts by timestamp,
breaks ties by worker id, and deterministically yields one winner. Exclusion is a query rule
in the interpreters, not a guarantee the store enforces — which is exactly why the
tuple-space character belongs to `dao.space`, not `dao.jing`.

## Lineage

The tuple space is **Linda's** contribution: generative communication (write into a shared
medium, don't address a receiver), spatial and temporal decoupling, non-destructive
associative matching. The divergences are immutability (append, never `take`) and named
n-tuples (datoms) in place of untyped positional arrays. Matching stays global by default,
because a coordination medium for strangers must let any reader match the whole store, not
only what it bound.

The other two traditions live in the layers below and have their own docs:

- **Datomic** owns [`dao.jing`](dao.jing.md) — the dumb KV store of immutable segments and
  the Peer-as-library read model.
- **Plan 9** owns [`dao.stream`](dao.stream.md) — the independent, location-transparent,
  append-only log substrate.

The synthesis: **`dao.space` is the tuple space that emerges when the embeddable
`dao.space.query` Peer library matches Datalog over `dao.jing`, and agents coordinate by the
traces they leave there.**
