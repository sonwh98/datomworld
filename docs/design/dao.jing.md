# DaoJing: The Storage Boundary

**Related documents:**
- `docs/design/dao.space.md` — the tuple space that emerges when interpreters match over `dao.jing`
- `docs/design/dao.stream.md` — the append-only log primitive datoms are written through
- `docs/design/dao.stream.file.md` — the file-backed byte stream member logs use
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, the gauge/base framing
- `docs/datomic.md` — the deep dive on the Datomic storage architecture this design maps to
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the decision this design records
- `docs/design/dao.space.v0.md` — superseded framing; still the reference for resources, typed streams, and the geometry/gauge material
- `docs/design/dao.space.locality.md`, `dao.space.metaphors.md`, `dao.space.discrete-to-continuous.md` — the geometry/locality cluster: theoretical justification (gauge, spectral, locality) the spec defers to, not required to read it

> **Naming note.** The storage boundary is `dao.jing`. The implementation namespace is
> still `dao.space.kv` (and `src/cljc/dao/space/kv.cljc`); that name predates this
> store/space split and is slated to become `dao.jing.kv`. Code blocks below use the
> target name `dao.jing`, with an inline comment noting the namespace is currently
> `dao.space`; conceptual prose names `dao.jing` throughout.

## What DaoJing Is

`dao.jing` is the **storage boundary**: a decentralized, content-addressed **repository of
immutable datoms**. Concretely it is a dumb key-value store (the `IKVStore` protocol) that
holds immutable B-Tree segment chunks and mutable stream-root references. It is the
decentralized analog to Datomic's storage layer. Alongside the datoms it also holds the
**derived materialized views** interpreters build over them (covered indexes, and in general
any app-defined structure): the datoms are ground truth, the views are reconstructable cache.
See [Derived Views](#derived-views).

A store is defined by **what it holds, not the pipe data arrived through.** `dao.jing` is a
repository of datoms; it is *not* "a set of streams." Datoms enter through `dao.stream`
member logs (the [intake path](#intake--the-write-path) below), exactly as a transaction log
feeds Datomic's storage, but the streams are upstream of the store, not its identity.

The physical `IKVStore` is deliberately dumb. It does not know what a datom is, it does not
index, it does not pattern-match, and it does not run Datalog. Those belong to the tuple
space above it (`dao.space`, via the `dao.space.query` library). This is the Datomic
discipline taken literally: **storage is a dumb KV blob store; all intelligence lives in the
embeddable reader on top.** Keeping the boundary this thin is what lets storage scale and be
swapped without touching query logic.

```
   dao.space.query/q   …/match          ← the TUPLE SPACE reads (dao.space, separate doc)
        │  lazily pulls B-Tree segments from the
        │  IKVStore, merges them, and runs Datalog
        ▼
╔═══════════════════ dao.jing ═══════════════════╗   ← STORAGE boundary (this doc)
║             IKVStore (put! / get / cas!)        ║
║                                                 ║
║   [Mutable Stream Roots]   [Immutable Chunks]   ║
╚════════╪══════════════════════════════╪═════════╝
         │ byte maps                    │ byte maps
┌────────▼──────────────────────────────▼─────────┐
│              KV Backends (Mem, File)            │
└─────────────────────────────────────────────────┘

   each writer = its own Transactor (decentralized write boundary, via dao.stream)
```

## The Storage Interface (storage → reader)

`dao.space.kv/IKVStore` exposes its stored byte blobs; it does not interpret them. This is
the interface the query library consumes — **not** a query API.

```clojure
;; The dumb storage API (see src/cljc/dao/space/kv.cljc)
(kv/get store :root nil)               ; read a mutable root reference
(kv/get store :segment-id nil)         ; read an immutable B-Tree chunk
```

There is no `store/datoms` or `store/members` at this layer, because the storage layer does
not know what a datom is. Parsing chunks into datom streams, matching, and querying live
entirely in the tuple space's query library (`dao.space.query`, see `dao.space.md`).

## Intake — the write path

A datom enters the store by being appended to a `dao.stream` member log the agent opens
through the store handle. Each open returns a distinct `dao.stream.file` with its own
append-only log, so writers never contend and nothing routes. Each writer is its own
transactor: it enforces local schema and appends datom frames, with no global contention and
no commit step.

```clojure
(require '[dao.jing :as store]    ; namespace currently dao.space; see naming note
         '[dao.stream :as ds])

(def s   (store/open! {:path "/data/work"}))   ; handle to the store (reopen rediscovers members)
(def log (ds/open! {:type :dao-stream :store s :name "agent-1"}))

(ds/put! log {:db/id (random-id) :work/id "w1" :work/status :todo})
(ds/put! log [:db/retract work-id :work/status])     ; retraction is also an append
```

### Membership is intake, not identity

Opening a member log through the handle *is* attaching a writer to the store; closing it
detaches. `store/join!` attaches an already-open independent stream (one written to offline,
or previously detached); `ds/open! {:store …}` is sugar for "open then `store/join!`."
`store/leave!` detaches without closing. `store/join!` and `store/leave!` are the symmetric
pair.

Membership names **which streams currently feed the store**, a write-path concern. It does
not make the store "a set of streams": the store remains a content-addressed repository of
the datoms those streams have contributed.

**Single-writer invariant.** Two agents never write the same stream. If 1,000 agents want to
send messages to one agent, they append to 1,000 distinct single-writer streams, and the
recipient merges them on the read side. The `cas!` on a stream's mutable root reference is
solely for the single owner's mechanical safety: to guard the stream's B-Tree against an
agent racing itself (a network retry duplicating a write, or an orchestrator spinning up a
zombie duplicate during a split-brain partition). It is strictly for structural integrity,
not agent-to-agent semantic coordination.

## Derived Views

The covered index is one **materialized view** over the datoms — the EAVT/AEVT/AVET/VAET
package that makes a relational, Datalog-flavored surface fast (detailed in *Index
Realization* below). It is not privileged. An interpreter may instead materialize
entity-centric maps, AST parent/child tables, graph adjacency, content-hash indexes, ordered
positional collections, capability/provenance projections, or arbitrary app-defined
structures — each a different point in one space of databases over a single canonical datom
history (the moduli-space framing in [`dao.space.md`](dao.space.md)).

So the one dumb KV store holds two tiers of bytes. The storage layer does not distinguish
them; the design does:

- **Canonical — datom segments.** The accreted immutable facts. Ground truth: the history
  every interpreter replays. Lose a datom and history is lost.
- **Derived — materialized-view chunks.** The covered index and every other view.
  Reconstructable cache. Lose a view chunk and an interpreter rebuilds it from the datoms.

Four rules keep views from leaking interpretation into storage:

- **Who computes vs. who holds.** Interpreters in `dao.space` compute views; `dao.jing` holds
  only the resulting opaque blobs. The view *contract* — which views exist, their legal
  traversals, their `as-of`/`since` semantics, what is materialized vs. derived-on-read —
  lives in `dao.space`, never here.
- **Views are derivations, not new ground truth.** A view must be a pure function of the
  datom history plus a declared view definition. Arbitrary *structure* is admissible; primary
  state that bypasses the datom log is not — that is hidden, un-replayable state.
- **Storage never maintains a view.** `dao.jing` does not refresh, invalidate, or recompute.
  A view is an `as-of`-`t` snapshot; "refresh" is an interpreter computing a new view as-of a
  later `t` under a new key.
- **Content-addressed, so caching is free and shared.** Key a view by the hash of
  `(datom-prefix as-of t, view-definition)` and identical views coincide across agents: one
  builds it, peers find it present and merge it — the owner-built/peers-merge model (below)
  generalized from indexes to any view, stigmergic by construction.

View chunks obey the same `put!`-immutable / `cas!`-root keyspace discipline as index segments
(below).

## Index Realization

The store holds the index as immutable B-Tree segment chunks; how those segments are built
and maintained is a pure performance choice. All strategies answer identically (the index is
the same set of datoms — see ADR 0001's monoid homomorphism):

- **Rebuild per query** — each read folds the store's datoms into a fresh index and discards
  it. Simple; O(total datoms) per read.
- **Incremental index** — a long-lived reader keeps a cursor per member stream and folds
  only new frames as they arrive (Datomic-peer style). More machinery; amortized reads.
  Still no transactor and no global clock — each stream advances its own cursor.
- **Owner-built, peers merge (Target Architecture)** — each stream's owner indexes its own
  stream and persists the segments; readers **merge** per-stream indexes instead of
  rebuilding. Index-once, reuse-by-many, and available when the author is offline — the
  decentralized analog of Datomic's transactor-built index (see ADR 0001, ruling 5 and Open
  Question 1).

This index-once/lazy-pull behavior is realized using **tonsky/persistent-sorted-set**, which
provides a B-Tree implementation that natively supports lazy loading and segment chunking.

1. `dao.jing` provides a minimal `IKVStore` protocol (`put!`/`cas!`/`get`/`delete!`/`close!`,
   in `dao.space.kv`) representing Datomic's dumb storage (see `docs/datomic.md` for the
   technical specification).
2. The index builder writes B-Tree segment chunks to `dao.jing` using `put!`.
3. The `dao.space.query` library configures `persistent-sorted-set` with an `IStorage`
   adapter that calls `get` to lazily pull B-Tree segments from the store *only* when
   traversed by a query.

The two write paths share one keyspace under a discipline the store does not enforce:
immutable segments are written with `put!` under fresh, content-derived keys and are never
rewritten; mutable references (the stream root pointer) are written with `cas!` under
optimistic concurrency. Keep these keyspaces disjoint — `put!` re-stamps `:rev` to 0
unconditionally, so a `put!` over a `cas!`-governed key resets its revision and breaks the
optimistic-concurrency guard.

**Implementation status.** Content-addressed identity (the gauge-invariant base `B` in
`datom-spec.md`) is the intended basis for cross-writer unification but is only partially
realized: `yin/content.cljc` hashes via `pr-str` rather than the canonical byte encoding the
spec mandates, and the index still allocates integer entity ids, so stamped
`[namespace offset]` references are not yet first-class join keys. Making the content hash
load-bearing is a prerequisite for treating cross-stream identity as gauge-invariant rather
than stamped.

## v1 Scope

What v1 implements at the storage boundary, and the contracts it pins down.

- **Index: the Target Architecture directly.** v1 builds the index on
  `tonsky/persistent-sorted-set` over the `IKVStore` (`put`/`get`/`cas`) interface, with the
  `IStorage` adapter pulling B-tree segments lazily — the index-once/lazy-pull mechanism
  behind the owner-built/peers-merge target (above). v1 does **not** ship the
  rebuild-per-query stopgap and does **not** reuse `dao.db`'s in-memory sorted-set engine.
- **Views: covered indexes only.** v1 materializes the covered-index package above. Arbitrary
  and app-defined materialized views (see *Derived Views*) are the general architecture, not
  v1 work; the two-tier (canonical datoms vs. reconstructable views) keyspace discipline
  already accommodates them when they arrive.
- **Member layout and discovery.** A stream owner acts as a Transactor: it accepts new
  datoms, indexes them into B-Tree segments, and unconditionally writes those immutable
  segments to the `IKVStore` (`put!`). It then performs an atomic `cas!` on the stream's
  mutable root reference to point to the new B-Tree root.
- **Querying (reader side).** A read resolves the stream's root reference from the `IKVStore`
  and uses the `IStorage` adapter to lazily pull only the traversed B-Tree chunks. Concurrent
  writes never disturb an in-flight read because the read targets an immutable B-Tree root
  snapshot.
- **Namespace stamping.** The reader stamps each datom's `e` with a per-member identifier so
  stream-local ids stay distinct. v1 does **not** yet derive that namespace from the
  canonical kickoff hash, because content addressing is not yet load-bearing (above).
- **Encoding: `pr-str`, not canonical.** v1 persists and hashes datom frames via `pr-str`
  (current `yin/content.cljc`), not the canonical byte encoding `datom-spec.md` mandates.
  Consequence: cross-stream identity is stamped, not gauge-invariant — value-joins hold
  within a run, but the content hash is not yet a portable join key. Making the canonical
  encoding load-bearing is the first post-v1 step and does not change this document's API.
- **Deferred (no v1 work):** cross-stream `as-of` (ADR 0001 Open Question 2), and the
  rebuild-per-query and incremental index variants (kept only as the conceptual baseline).

Access control (public vs controlled mode) is a property of how the tuple space reads the
store, not of storage itself; see `dao.space.md`.

## Implementation Platform

`dao.jing` should be implemented as cross-platform `.cljc` wherever possible. The core
storage and indexing logic must operate identically across Clojure (`clj`), ClojureScript
(`cljs`), and ClojureDart (`cljd`).

**Platform Degradation Note (`cljd`):** the target architecture relies on
`tonsky/persistent-sorted-set` for lazy B-Tree chunking. That library lacks a Dart (`cljd`)
implementation and relies heavily on JVM/JS macros. In `cljd` environments, the indexing
layer gracefully degrades to the built-in `clojure.core/sorted-set-by` (a standard red-black
tree). Because the built-in set lacks an `IStorage` mechanism, Dart peers cannot lazily pull
chunks over the network; they must load the entire index into memory. Addressing this
requires either a pure-Clojure B-Tree port or a custom Dart `IStorage` implementation in the
future.

## Fault Tolerance

Because storage is persistent append-only `dao.stream` files, it inherits crash-only
semantics:

- **Data safety.** Datoms flushed before a crash are safe; append-only files have no
  partial-update corruption window.
- **Reader behavior.** A reader tailing a crashed writer's stream simply reaches the end and
  yields (`ds/next` returns `:blocked`); it waits for new data rather than failing.
- **Write recovery.** A restarted writer reopens its file in append mode; the next `ds/put!`
  lands after the last flushed datom.
- **Read recovery.** A reader resumes from a checkpointed cursor offset, so an incremental
  index rebuilds without reprocessing or skipping.

## Lineage

`dao.jing` is the meeting point of two traditions, one for what it holds and one for what
it is built from:

- **Datomic** gives the storage discipline: a dumb KV store of immutable segments under a
  strict Transactor / Storage / Query separation, with content-addressed identity and the
  Peer-as-library read model. `dao.jing` is the decentralized Storage; the Peer is the
  `dao.space.query` library that reads it.
- **Plan 9** gives the *substrate*, one level down in `dao.stream`: the member logs the store
  is fed through are independent, location-transparent, append-only streams (see
  `dao.stream.md`, *Lineage: Plan 9*). The store inherits this only because its intake is
  `dao.stream`s.

The tuple-space *behavior* — associative matching, generative communication — is **not** here;
it belongs to Linda and lives in `dao.space` (the query library plus the agents that read the
store). See `dao.space.md`.

The synthesis: **`dao.jing` is a decentralized, append-only, content-addressed repository of
datoms; matching and Datalog are an embeddable Peer library (`dao.space.query`) that reads
it.**
