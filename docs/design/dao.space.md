# DaoSpace: The Storage Boundary

**Related documents:**
- `docs/design/dao.stream.md` — the stream transport DaoSpace is built from
- `docs/design/dao.stream.file.md` — the file-backed byte stream member logs use
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, the gauge/base framing
- `docs/datomic.md` — the deep dive on the Datomic storage architecture this design maps to
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the decision this design records
- `docs/design/dao.space.v0.md` — superseded framing; still the reference for resources, typed streams, and the geometry/gauge material
- `docs/design/dao.space.locality.md`, `dao.space.metaphors.md`, `dao.space.discrete-to-continuous.md` — the geometry/locality cluster: theoretical justification (gauge, spectral, locality) the spec defers to, not required to read it

## Three Boundaries

Datomic's central strength is the strict separation of **Transactor**, **Storage**, and
**Query** into *abstraction boundaries* — interfaces, not deployment tiers. They can be
co-located in a single process with zero network overhead, or split across machines,
without changing the contracts. datom.world keeps that separation and maps it onto
streams:

- **Transactor (write)** — **decentralized.** Every agent appending to its own
  `dao.stream` is its own transactor: it enforces local schema and appends datom frames,
  with no global contention and no commit step.
- **Storage (this document)** — **`dao.space`.** The decentralized, durable datom log: a
  dynamic collection of append-only member streams. It persists and serves datoms. It
  does **not** match or query.
- **Query (read)** — **a library any interpreter embeds.** It pulls datoms from
  `dao.space`, builds an in-memory index, and runs pattern matching and Datalog. Pure,
  in-process, per-interpreter — Datomic's Peer model, as a library rather than a service.

This document specifies the **Storage** boundary. Matching and Datalog are specified by
the query library (`dao.space.query`, below), which *operates on* a `dao.space` but is not part
of it.

## What DaoSpace Is

`dao.space` is the **storage boundary**: a dumb key-value store (the `KVStore` protocol) that holds immutable B-Tree segment chunks and mutable stream root references. It is the decentralized analog to Datomic's storage layer.

The physical `KVStore` is deliberately dumb. It does not know what a datom is, it does not index, it does not pattern-match, and it does not run Datalog. Those are the query library's job. This is the Datomic discipline taken literally: **storage is a dumb KV blob store; all intelligence lives in the embeddable reader on top.** Keeping the boundary this thin is what lets storage scale and be swapped without touching query logic.

```
   dao.space.query/q   …/match          ← QUERY boundary (a library, separate doc/ns)
        │  lazily pulls B-Tree segments from the
        │  KVStore, merges them, and runs Datalog
        ▼
╔═══════════════════ dao.space ═══════════════════╗   ← STORAGE boundary (this doc)
║             IKVStore (put! / get / cas!)        ║
║                                                 ║
║   [Mutable Stream Roots]   [Immutable Chunks]   ║
╚════════╪══════════════════════════════╪═════════╝
         │ byte maps                    │ byte maps
┌────────▼──────────────────────────────▼─────────┐
│              KV Backends (Mem, File)            │
└─────────────────────────────────────────────────┘

   each writer = its own Transactor (decentralized write boundary)
```

## Why the Split

A tuple space's promise — *agents match tuples and query them with Datalog* — is
satisfied by **`dao.space` (storage) and the query library (read) together**, not by
storage alone. `dao.space` holds the tuples; the library makes them matchable. Two
reasons to keep them as separate boundaries rather than one component:

- **Storage stays dumb and swappable.** Member streams are append-only logs and nothing
  more. The whole index/Datalog machinery sits above the boundary, so it can evolve
  without changing how datoms are stored, and storage backends can change without
  touching query.
- **Query is embedded, not centralized.** Every interpreter links the library and runs
  its own index in-process, exactly as a Datomic Peer does. There is no query service to
  bottleneck on; reads scale by adding readers.

## Writing — member streams (the Transactor boundary)

A datom enters storage by being appended to a member stream the agent opens through the
handle. Each open returns a distinct `dao.stream.file` with its own append-only log, so
writers never contend and nothing routes. Each writer is its own transactor.

```clojure
(require '[dao.space :as space]
         '[dao.stream :as ds])

(def s   (space/open! {:path "/data/work"}))   ; handle to the storage (reopen rediscovers members)
(def log (ds/open! {:type :dao-stream :space s :name "agent-1"}))

(ds/put! log {:db/id (random-id) :work/id "w1" :work/status :todo})
(ds/put! log [:db/retract work-id :work/status])     ; retraction is also an append
```

### Membership

Opening a member stream through the handle *is* joining; closing it leaves.
`space/join!` attaches an already-open autonomous stream (one written to offline, or
previously detached); `ds/open! {:space …}` is sugar for "open then `space/join!`."
`space/leave!` detaches without closing. `space/join!` and `space/leave!` are the
symmetric pair.

## Reading DaoSpace — the storage interface (storage → query)

`dao.space.kv/IKVStore` exposes its stored byte blobs; it does not interpret them. This is the interface the query library consumes — **not** a query API.

```clojure
;; The dumb storage API (see src/cljc/dao/space/kv.cljc)
(kv/get store :root nil)               ; read a mutable root reference
(kv/get store :segment-id nil)         ; read an immutable B-Tree chunk
```

There is no `space/datoms` or `space/members` at the storage layer, because the storage layer does not know what a datom is. Parsing chunks into datom streams, matching, and querying live entirely in the query library (`dao.space.query`).

## The Query Library

Pattern matching and Datalog are a **separate library** that any interpreter embeds and
runs against a `dao.space`. It is pure: pull datoms from storage, build an in-memory
index, answer. It owns no durable state and is not part of `dao.space`.

```clojure
(require '[dao.space.query :as query])

;; q — full Datalog (joins, negation, aggregation, predicates) over all of storage
(query/q '[:find ?id ?task
           :where [?id :work/status :todo]
                  [?id :work/task ?task]]
  s)
;; => #{[id task] ...}

;; match — a positional datom template (Linda-style), lighter than q
(query/match s [_ :work/status :todo])   ; => matching datoms

;; as-of — a read bound: index storage only up to `point`
(query/q query-form s {:as-of t})
(query/match s pattern {:as-of (instant "2026-01-01")})
```

Within a single stream `as-of t` is exact (the stream's own append order). A
**cross-stream** `as-of` needs a time coordinate comparable across streams — a wall-clock
instant (as shown) rather than per-stream `t` — and its precise semantics are deferred
(see ADR 0001, Open Question 2).

Internally the library folds the space's datoms into an index and queries it. Each
stream's datoms are stamped with the stream's namespace before indexing, so two streams'
local id `1025` stay distinct; "same logical entity across streams" is a join on shared
*values*, never on a stream-local id.

```clojure
;; Sketch of the library, rebuild-per-query form.
(ns dao.space.query
  (:require [dao.space :as space]))

(defn- fold [s as-of]
  (index (space/datoms s {:as-of as-of})))   ; build EAVT/AEVT/AVET

(defn q     [query-form s & {:keys [as-of]}] (run-datalog query-form (fold s as-of)))
(defn match [s pattern   & {:keys [as-of]}] (scan-pattern  pattern    (fold s as-of)))
```

### Global match and scoping

The library reads `dao.space`, and `dao.space` is the *global* storage, so any
interpreter that embeds the library matches over **everything** by default — global,
associative, addressed by content, never by producer. That global reach is the identity
of a tuple space; the per-interpreter *embedding* of the library does not scope it,
because the *storage* it reads is shared.

Two different things hide under "scoping," and only one is a security mechanism.

**Relevance / performance scoping** is a **predicate over content** inside the query —
never an enumeration of producers. A scoped view is "the datoms matching `P`," where `P`
names shapes (`[?e :work/* ?v]`), so the reader still surfaces tuples from writers it
never heard of. This is a *trusted* reader choosing to look at less; it is **not**
security — choosing to read less never stops you from reading more.

**Security and Access Modes**

datom.world's security model rests on the principle: **"share governed computation, not data."**
Sharing bits is losing control of those bits, and encryption only relocates the problem to
key-sharing. Plaintext result-filtering *does* need a mediator — but rather than pretend to escape
that, the model makes the mediator **generic and accountable**: control is held by never emitting
raw datoms, only the bounded result `f(X)` of an authorized interpreter `f`.

This yields two distinct access modes (see [dao.space.security.md](dao.space.security.md) and [ADR 0002](adr/0002-share-governed-computation-not-data.md) for full details):

- **Public (pull-to-reader):** the default `dao.space` mode described above. The reader embeds the library and pulls datom streams directly. There is no fine-grained control; the only security is coarse, per-stream access (POSIX permissions, Plan 9-style mounts). Use this when it is safe to ship the datoms.
- **Controlled (push-interpreter-to-data / confined):** when fine-grained per-datom control is needed, the topology inverts and the data never leaves its owner's control. The reader submits a governed interpreter (a `yin.vm` AST) wrapped in a capability; it runs in a confined environment scoped to the authorized datoms, and only the attenuated answer returns. The **capability token** is cryptographically authenticated; the **content predicate** (the `m` slot carries the policy) is enforced by the evaluation substrate — operationally by a confined CESK runtime, or cryptographically by an MPC/FHE circuit — which is distinct from authenticating the token. The owner is the mediator by default; MPC removes even that (see the security doc).

Trusted peers and public data are the common case: embedded library, direct access, global match.
When control is required, the architecture switches to controlled mode, where the unit of sharing is the governed interpreter, backed by an immutable accountability log.

### Realization choice: rebuild vs incremental

How the library maintains its index is a pure performance choice; all forms answer
identically (the index is the same set of datoms — see ADR 0001's monoid homomorphism):

- **Rebuild per query** — each call folds the space's datoms into a fresh index and
  discards it. Simple; O(total datoms) per read. (What the `fold` sketch above does.)
- **Incremental index** — a long-lived reader keeps a cursor per member stream and folds
  only new frames as they arrive (Datomic-peer style). More machinery; amortized reads.
  Still no transactor and no global clock — each stream advances its own cursor.
- **Owner-built, peers merge (Target Architecture)** — each stream's owner indexes its own stream and persists
  the segments; readers **merge** per-stream indexes instead of rebuilding. Index-once,
  reuse-by-many, and available when the author is offline — the decentralized analog of
  Datomic's transactor-built index (see ADR 0001, ruling 5 and Open Question 1).

This index-once/lazy-pull behavior is realized using **tonsky/persistent-sorted-set**, which provides a B-Tree implementation that natively supports lazy loading and segment chunking.
1. The `dao.space` layer provides a minimal `IKVStore` protocol (`put!`/`cas!`/`get`/`delete!`/`close!`, in `dao.space.kv`) representing Datomic's dumb storage (see `docs/datomic.md` for the technical specification).
2. The index builder writes B-Tree segment chunks to `dao.space` using `put!`.
3. The `dao.space.query` Peer library configures `persistent-sorted-set` with an `IStorage` adapter that calls `get` to lazily pull B-Tree segments from the store *only* when traversed by a query.

The two write paths share one keyspace under a discipline the store does not enforce:
immutable segments are written with `put!` under fresh, content-derived keys and are never
rewritten; mutable references (the stream root pointer) are written with `cas!` under
optimistic concurrency. Keep these keyspaces disjoint — `put!` re-stamps `:rev` to 0
unconditionally, so a `put!` over a `cas!`-governed key resets its revision and breaks the
optimistic-concurrency guard.

**Implementation status.** Content-addressed identity (the gauge-invariant base `B` in
`datom-spec.md`) is the intended basis for cross-writer unification but is only partially
realized: `yin/content.cljc` hashes via `pr-str` rather than the canonical byte encoding
the spec mandates, and the library's index still allocates integer entity ids, so stamped
`[namespace offset]` references are not yet first-class join keys. Making the content
hash load-bearing is a prerequisite for treating cross-stream identity as gauge-invariant
rather than stamped.

## v1 Scope

What v1 implements, and the contracts it pins down. Everything here stays inside the
Storage boundary; none of it changes the API specified above.

- **Public mode only.** v1 is the pull-to-reader path: readers embed the library,
  enumerate members, and fold their datoms into the index locally. The only security is
  coarse, per-stream filesystem permissions on the member logs. Controlled mode — the
  governed interpreter, capabilities, `m`-policy — is out of scope (see
  [dao.space.security.md](dao.space.security.md) and
  [ADR 0002](adr/0002-share-governed-computation-not-data.md)).
- **Index: the Target Architecture directly.** v1 builds the index on
  `tonsky/persistent-sorted-set` over the `KVStore` (`put`/`get`/`cas`) interface, with the
  `IStorage` adapter pulling B-tree segments lazily — the index-once/lazy-pull mechanism
  behind the owner-built/peers-merge target (above). v1 does **not** ship the
  rebuild-per-query stopgap and does **not** reuse `dao.db`'s in-memory sorted-set engine;
  the `fold` sketch names the read role, not the implementation.
- **Member layout and discovery.** A stream owner acts as a Transactor: it accepts new datoms, indexes them into B-Tree segments, and unconditionally writes those immutable segments to the `KVStore` (`put!`). It then performs an atomic `cas!` on the stream's mutable root reference to point to the new B-Tree root. 
- **Querying (Peer library).** A query reads the stream's root reference from the `KVStore` and uses the `IStorage` adapter to lazily pull only the traversed B-Tree chunks. Concurrent writes never disturb an in-flight read because the read targets an immutable B-Tree root snapshot.
- **Namespace stamping.** The peer library stamps each datom's `e` with a per-member
  identifier so stream-local ids stay distinct. v1 does **not** yet derive that namespace
  from the canonical kickoff hash, because content addressing is not yet load-bearing
  (below).
- **Encoding: `pr-str`, not canonical.** v1 persists and hashes datom frames via `pr-str`
  (current `yin/content.cljc`), not the canonical byte encoding `datom-spec.md` mandates.
  Consequence: cross-stream identity is stamped, not gauge-invariant — value-joins hold
  within a run, but the content hash is not yet a portable join key. Making the canonical
  encoding load-bearing is the first post-v1 step and does not change this document's API.
- **Deferred (no v1 work):** cross-stream `as-of` (ADR 0001 Open Question 2), the
  rebuild-per-query and incremental index variants (kept only as the conceptual `fold`
  baseline), and all of Controlled mode.

## Coordination: Stigmergy

Agents coordinate by leaving datoms in storage for others to query, decoupled in time and
identity. Because streams are append-only there is no destructive `take`: to "claim" work
an agent *appends a new datom* asserting the claim, and "current state" is a read-side
query over the accreted datoms.

```clojure
(defn producer [s]
  (let [log (ds/open! {:type :dao-stream :space s :name "producer"})]
    (ds/put! log {:db/id (random-id) :work/posted true :work/task "process payment"})))

(defn worker [s worker-id]
  (let [log (ds/open! {:type :dao-stream :space s :name worker-id})]
    (loop []
      ;; "posted work nothing has claimed" — negation + join over the whole store,
      ;; the query that justifies a tuple space (not a per-datom scan).
      (let [work (query/q '[:find ?task
                            :where [?w :work/posted true]
                                   [?w :work/task ?task]
                                   (not [_ :work/claims ?w])]
                   s)]
        (when-let [[task] (first work)]
          (ds/put! log {:db/id (random-id) :work/claims task :work/by worker-id})
          (ds/put! log {:db/id (random-id) :work/result (process task)})
          (recur))))))
```

## Implementation Platform

Both the `dao.space` storage boundary and the `dao.space.query` library should be implemented as cross-platform `.cljc` files wherever possible. The core logic, indexing, and pattern matching must operate identically across Clojure (`clj`), ClojureScript (`cljs`), and ClojureDart (`cljd`). 

**Platform Degradation Note (`cljd`):** 
The target architecture relies on `tonsky/persistent-sorted-set` for lazy B-Tree chunking. However, this library lacks a Dart (`cljd`) implementation and relies heavily on JVM/JS macros. In `cljd` environments, the indexing layer gracefully degrades to the built-in `clojure.core/sorted-set-by` (a standard red-black tree). Because the built-in set lacks an `IStorage` mechanism, Dart peers cannot lazily pull chunks over the network; they must load the entire index into memory. Addressing this requires either a pure-Clojure B-Tree port or a custom Dart `IStorage` implementation in the future.

## Fault Tolerance

Because storage is persistent append-only `dao.stream` files, it inherits crash-only
semantics:

- **Data safety.** Datoms flushed before a crash are safe; append-only files have no
  partial-update corruption window.
- **Reader behavior.** A reader tailing a crashed writer's stream simply reaches the end
  and yields (`ds/next` returns `:blocked`); it waits for new data rather than failing.
- **Write recovery.** A restarted writer reopens its file in append mode; the next
  `ds/put!` lands after the last flushed datom.
- **Read recovery.** A reader resumes from a checkpointed cursor offset, so an
  incremental index rebuilds without reprocessing or skipping.

## Lineage

DaoSpace and its query library are the meeting point of three traditions:

- **Linda** gives the essence: generative communication (write into a shared medium,
  don't address a receiver), spatial and temporal decoupling, non-destructive associative
  matching. The divergences are immutability (append, never `take`) and named n-tuples
  (datoms) in place of untyped positional arrays.
- **Datomic** gives the architecture: the strict Transactor / Storage / Query separation,
  immutable datoms, Datalog, time (`as-of`), content-addressed identity, and the
  Peer-as-library read model. `dao.space` is the storage boundary; the query library is
  the Peer.
- **Plan 9** gives the storage stance: streams are autonomous, location-transparent,
  append-only logs, and storage is assembled from them — while the *default* stays global
  match, because a coordination medium for strangers must let any reader match the whole
  store, not only what it bound.

The synthesis: **`dao.space` is a decentralized, append-only datom log built from
`dao.stream`s; matching and Datalog are an embeddable Peer library that reads it.**
