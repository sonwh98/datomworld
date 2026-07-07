# DaoJing: The Storage Boundary

**Related documents:**
- `docs/design/dao.space.md` — the tuple space that emerges when interpreters match over `dao.jing`
- `docs/design/dao.stream.md` — the append-only log primitive datoms are written through
- `docs/design/dao.stream.file.md` — the file-backed byte stream member logs use
- `docs/agents/datom-spec.md` — datoms, content-addressed identity, the gauge/base framing
- `docs/datomic.md` — the deep dive on the Datomic storage architecture this design maps to
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the decision this design records
- `docs/postgres.md` — the deep dive on the PostgreSQL architecture this design defines itself against
- `docs/design/dao.space.v0.md` — superseded framing; still the reference for resources, typed streams, and the geometry/gauge material
- `docs/design/dao.space.locality.md`, `dao.space.metaphors.md`, `dao.space.discrete-to-continuous.md` — the geometry/locality cluster: theoretical justification (gauge, spectral, locality) the spec defers to, not required to read it

## What DaoJing Is

`dao.jing` is the **storage boundary**: a decentralized key-value store of **opaque bytes**,
designed to hold immutable, content-addressed segments plus mutable stream-root references. It
is **pure syntax** — it holds *form*, never *meaning*. That design intent is a discipline
callers observe, not a guarantee the protocol enforces: the `IKVStore` contract itself (`put!` /
`cas!` / `get` over caller-supplied keys) does not require keys to be content-derived or values
to be immutable — a `put!` can overwrite any key with anything (see Index Realization, below,
and `dao.jing.dht.md`, "Key classes"). What any byte *denotes* — "datom," "index," "view,"
"query" — is **semantics an interpreter projects onto it**, never something storage knows.
Concretely it is a dumb key-value store (the `IKVStore` protocol), the decentralized analog to
Datomic's storage layer. It is therefore **not strict about what it holds**: it leaves all
interpretation — indexing, materialization, matching — to the readers above it (`dao.space`),
and whatever structures those interpreters build are, to the store, just more bytes. Datom
segments are what it is built to hold, though storage knows them only as bytes.

A store is defined by **what it holds, not the pipe data arrived through.** `dao.jing` holds
opaque bytes — read as datoms by the layers above, never known as datoms by storage; it is
*not* "a set of streams." Datoms enter through `dao.stream` member logs (the write path,
specified in [`dao.space.md`](dao.space.md)), exactly as a transaction log feeds Datomic's
storage, but the streams are upstream of the store, not its identity.

The physical `IKVStore` is deliberately dumb. It does not know what a datom is, it does not
index, it does not pattern-match, and it does not run Datalog. Those belong to the tuple
space above it (`dao.space`, via the `dao.space.query` library). This is the Datomic
discipline taken literally: **storage is a dumb KV blob store; all intelligence lives in the
embeddable reader on top.** Keeping the boundary this thin is what lets storage scale and be
swapped without touching query logic.

## The Storage Interface (storage → reader)

`dao.jing/IKVStore` exposes its stored byte blobs; it does not interpret them. This is
the interface the query library consumes — **not** a query API.

```clojure
;; The dumb storage API (see src/cljc/dao/jing.cljc)
(kv/get store :root nil)               ; read a mutable root reference
(kv/get store :segment-id nil)         ; read an immutable segment chunk
```

There is no `store/datoms` or `store/members` at this layer, because the storage layer does
not know what a datom is. Parsing chunks into datom streams, matching, and querying live
entirely in the tuple space's query library (`dao.space.query`, see `dao.space.md`). The
write path — how those datoms enter via `dao.stream` member logs, and which streams currently
feed the store — is likewise above this boundary; see `dao.space.md`.

## Index Realization

`dao.jing` holds the index's **immutable byte segments** and a **mutable root reference** —
nothing more. The *index* itself — a covered B-Tree (EAVT/AEVT/AVET/VAET) a reader can
traverse and answer queries from — is the interpretation `dao.space.query` projects onto those
bytes; storage never knows the segments form an index. (This is exactly how Datomic persists
its covered indexes into dumb KVStore backends: opaque segment blobs plus a root pointer.) How
a reader builds and maintains those segments is a pure performance choice it makes *above*
storage. All strategies answer identically (the index is the same set of datoms — see ADR
0001's monoid homomorphism):

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

1. `dao.jing` provides a minimal `IKVStore` protocol (`put!` / `cas!` / `get` / `delete!` / `close!`), treating all data as uninterpreted byte blobs. See `docs/datomic.md` for the technical specification.
2. The index builder (a reader concern, in `dao.space`) writes those segment chunks to
   `dao.jing` as opaque bytes with `put!`.
3. The `dao.space.query` library lazily pulls those segments from the store *only* when
   traversed by a query, interpreting the bytes back into B-Tree nodes.

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

## Structural Ignorance: Format Stability Without the Engine

The deliberate dumbness above has a cost question hanging over it: PostgreSQL couples its
storage engine to its data structures precisely because that coupling makes it fast. The
property doing the work there is **format stability** — the on-disk page layout *is* the
in-memory layout, so reads of resident pages need no parse (see [`../postgres.md`](../postgres.md),
§2, for the precise statement and its caveats). PostgreSQL obtains that property by making
the storage engine structurally aware, and the rest of that deep dive (§§3–8) is the bill:
vacuum, WAL record types per structure, a lock manager, and a storage format that admits new
structures only through years of C.

`dao.jing` is built on the decoupling: **in-place readability is a property of the byte
layout, not of the storage engine.** A flat, self-describing layout (Eve slabs; the same move
as FlatBuffers or Cap'n Proto) can be traversed in place by the *reader* — the interpreters
above this boundary — while the store remains the dumb `IKVStore` of opaque blobs specified
above. Structural awareness is one way to obtain format stability; it is not the only way,
and it is the expensive way — it welds the layout to the engine.

This resolves the storage-complexity argument from the Datomic lineage (see Lineage, below).
Rich Hickey's case for delegating storage to commercial databases assumed storage engines are
necessarily complex — true only of *structurally aware* engines managing B-trees, MVCC, and
locks. Strip the awareness and the storage layer becomes a hash table of blobs, simple enough
to own. What remains is supplying format stability in the application layer (the Eve bet — a
research program, not yet integrated; see [`dao.jing.dht.md`](dao.jing.dht.md), Zero-copy)
and, for the distributed backend, the open problems the DHT document records.

In exchange for banning in-place updates, fine-grained retrieval, and storage-level MVCC,
`dao.jing` buys what a structurally aware engine cannot offer: invent a new data structure
without touching storage code, and swap a local disk for a peer-to-peer network without
touching query code — because a store that never knew your structures never needs to learn
new ones.

## v1 Scope

What v1 implements at the storage boundary, and the contracts it pins down.

- **Index: the rebuild-per-query baseline, not the Target Architecture yet.** v1
  (`dao.space.query`) reads a handle's datoms wholesale from one mutable root
  (`default-datoms-key`, `:root/datoms`, as `{:datoms [...]}`) and folds them into a fresh
  in-memory index on every query, using `tonsky/persistent-sorted-set` as the sorted-set
  implementation — not as a lazily-pulled B-Tree segment store. This is the "rebuild per
  query" strategy named above ("kept only as the conceptual baseline"), shipped first for its
  simplicity; it does **not** reuse `dao.db`'s in-memory sorted-set engine. The owner-built,
  peers-merge Target Architecture (immutable B-Tree segments written via `put!`, lazily
  pulled and merged) is designed but not yet implemented — see `dao.space.query`'s namespace
  docstring, which names this gap explicitly.
- **Member layout and discovery.** A stream owner acts as a Transactor: it accepts new datoms
  and performs an atomic `cas!` on the stream's mutable root reference (`:root/datoms`) to
  publish the updated `{:datoms [...]}` blob. Segmenting those datoms into immutable B-Tree
  chunks written with `put!` is Target Architecture work, not yet shipped (above).
- **Querying (reader side).** A read resolves the stream's root reference from the `IKVStore`
  with a single `get` and rebuilds the index from the full datom vector it contains.
  Concurrent writes never disturb an in-flight read because the read targets an immutable
  snapshot of that root's value at the time of the `get`. Lazily pulling only the traversed
  B-Tree chunks is Target Architecture work, not yet shipped.
- **Namespace stamping.** The reader stamps each datom's `e` with a per-member identifier so
  stream-local ids stay distinct. v1 does **not** yet derive that namespace from the
  canonical kickoff hash, because content addressing is not yet load-bearing (above).
- **Compaction / GC.** The `KVFile` backend implements Bitcask-style file compaction via the
  storage backend (e.g., via `dao.jing.file/compact-store!`). Overwritten stream roots and deleted tombstones create dead space 
  in the append-only log; compaction sweeps the in-memory index, rewrites all live values to a 
  new log, and atomically swaps the file beneath the `IKVStore` interface, reclaiming disk space.
- **Encoding: `pr-str`, not canonical.** v1 persists and hashes datom frames via `pr-str`
  (current `yin/content.cljc`), not the canonical byte encoding `datom-spec.md` mandates.
  Consequence: cross-stream identity is stamped, not gauge-invariant — value-joins hold
  within a run, but the content hash is not yet a portable join key. Making the canonical
  encoding load-bearing is the first post-v1 step and does not change this document's API.
- **Deferred (no v1 work):** cross-stream `as-of` (ADR 0001 Open Question 2), the incremental
  index variant (kept only as a conceptual baseline, above), and the owner-built/peers-merge
  Target Architecture itself — rebuild-per-query is what v1 ships (above), not what it defers.

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
tree). Because the built-in set cannot lazily load chunks over the network, Dart peers must
load the entire index into memory. Addressing this requires either a pure-Clojure B-Tree
port or a custom Dart lazy-loading implementation in the future.

## The Block Storage Metaphor (Hardware Analogies)

Because the `IKVStore` protocol is so primitive (`put!`, `get`, `cas!`), it behaves almost
exactly like a hardware block storage device. This means proven hardware patterns map
directly onto it as software abstractions. **Status: illustrative, not implemented.** Only
the first bullet below (`compact-store!`) exists in `src/`; `CachingKVStore`, `RaidKVStore`,
and `NetworkKVStore` are unbuilt middleware named here to show what the thin `IKVStore`
boundary affords, not existing extension points:

- **SSD Flash Translation Layer & Garbage Collection.** Solid State Drives cannot overwrite
  in place; they write to fresh blocks and orphan the old ones, leaving reclamation to a
  Garbage Collector. Because `put!` is meant to write immutable, content-addressed chunks
  (a discipline the caller observes, not the protocol enforces — see What DaoJing Is),
  `dao.jing` updates are naturally out-of-place. As mutable stream roots advance (`cas!`),
  old byte segments are orphaned. The local file backend solves this via `compact-store!`
  (a Bitcask fold that filters out dead records and replaces the log), perfectly mirroring
  an SSD's garbage collection. Implemented today.
- **NVMe Parallel Queues (Zero-Contention Writes).** NVMe solved the SATA bottleneck by
  giving every CPU core its own submission queue to the disk, avoiding locks. The tuple
  space's `dao.stream` intake achieves the same thing in software: every agent writes to
  its own single-writer log. Massive throughput is possible because contention is eliminated
  at the storage boundary.
- **Storage Tiering (L1/L2 Caches), hypothetical.** Hardware uses fast/expensive caches
  (L1/L2/NVMe) in front of slow/cheap storage (spinning disks). In `dao.jing`, this would be
  a Decorator: a `CachingKVStore` wrapping a `NetworkKVStore` and a `MemKVStore` — the tuple
  space calls `get`; the caching store checks memory, faults from the network on a miss, and
  returns the chunk — leaving the interpreters above oblivious to the hierarchy. Neither
  store exists yet.
- **RAID and Erasure Coding, hypothetical.** RAID mirrors or stripes blocks across physical
  disks for redundancy. A `RaidKVStore` middleware could do this for bytes: when the tuple
  space calls `put!`, the store mirrors the chunk to three underlying `IKVStore` instances
  (e.g., local disk + two remote buckets), and `get` fails over to a surviving copy if one
  is lost. Erasure coding refines this: instead of full copies, the store splits each chunk
  into `k` data fragments plus `m` parity fragments (Reed-Solomon), recovering the original
  from any
  `k` of the `k+m` pieces. This buys the same fault tolerance at a fraction of the storage
  cost, all with zero changes to the query logic above.

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

The synthesis: **`dao.jing` is a decentralized store of opaque bytes, designed to hold
immutable, content-addressed segments plus mutable stream-root references — pure syntax;
datoms, matching, and Datalog are semantics an embeddable Peer library (`dao.space.query`)
and the agents project onto it.** (Immutability and content addressing are a discipline
callers observe over the `IKVStore` contract, not a guarantee the protocol enforces; see
What DaoJing Is, above.)
