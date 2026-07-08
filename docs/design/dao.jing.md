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
- `docs/design/ffi-design.md` — Yin.VM's `dao.stream.apply` bridge, one consumer of the protocol `IKVStore` can be exposed through (designed, not built)
- `docs/design/dao.space.security.md`, `docs/design/adr/0002-share-governed-computation-not-data.md` — the controlled-mode model that motivates exposing storage through a mediated bridge rather than a direct binding

## What DaoJing Is

`dao.jing` is the **storage boundary**: a decentralized key-value store of **opaque bytes**,
designed to hold immutable, content-addressed segments plus mutable stream-root references. It
is **pure syntax** — it holds *form*, never *meaning*. That design intent is a discipline
callers observe, not a guarantee the protocol enforces: the `IKVStore` contract itself (`put!` /
`cas!` / `get` over caller-supplied keys) does not require keys to be content-derived or values
to be immutable — a `put!` can overwrite any key with anything (see The Segment and Root
Keyspace, below, and `dao.jing.dht.md`, "Key classes"). What any byte *denotes* — "datom,"
"view," "query" — is **semantics an interpreter projects onto it**, never something storage
knows. Concretely it is a dumb key-value store (the `IKVStore` protocol), the decentralized
analog to Datomic's storage layer. It is therefore **not strict about what it holds**: it
leaves all interpretation — materialization, matching, querying — to the readers above it,
and whatever structures those interpreters build are, to the store, just more bytes. Datom
segments are what it is built to hold, though storage knows them only as bytes.

A store is defined by **what it holds, not the pipe data arrived through.** `dao.jing` holds
opaque bytes — read as datoms by the layers above, never known as datoms by storage; it is
*not* "a set of streams." Datoms enter through `dao.stream` member logs (the write path,
specified above this boundary), exactly as a transaction log feeds Datomic's storage, but the
streams are upstream of the store, not its identity.

The physical `IKVStore` is deliberately dumb. It does not know what a datom is, it does not
pattern-match, and it does not run Datalog. Those belong to the embeddable reader above it.
This is the Datomic discipline taken literally: **storage is a dumb KV blob store; all
intelligence lives in the embeddable reader on top.** Keeping the boundary this thin is what
lets storage scale and be swapped without touching query logic.

## The Storage Interface (writer ↔ storage ↔ reader)

`dao.jing/IKVStore` is the single interface a writer publishes bytes through and a reader
reads them back from; it does not interpret what passes through it. This is the storage API
both sides share — **not** a query API.

```clojure
;; The dumb storage API (see src/cljc/dao/jing.cljc)
(kv/put! store :segment-id v-map)      ; write a fresh immutable segment chunk
(kv/cas! store :root old-rev v-map)    ; advance a mutable root reference
(kv/get store :root nil)               ; read a mutable root reference
(kv/get store :segment-id nil)         ; read an immutable segment chunk
```

An agent accumulates datoms by appending to its own `dao.stream` log before anything reaches
`dao.jing`; which streams currently count as members of the space is tracked there too.
`dao.jing` never sees that log or its membership — only the eventual `put!`/`cas!` call that
lands a byte blob at a key, as shown above.

## The Segment and Root Keyspace

The two write paths shown above share one keyspace under a discipline the store does not
enforce: immutable segments are written with `put!` under fresh, content-derived keys and are
never rewritten; the mutable root reference is written with `cas!` under optimistic
concurrency. Keep these keyspaces disjoint — `put!` re-stamps `:rev` to 0 unconditionally, so
a `put!` over a `cas!`-governed key resets its revision and breaks the optimistic-concurrency
guard.

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

- **Storage root for v1: one mutable key, no segments yet.** v1 stores a stream's full datom
  vector under one mutable root key (`default-datoms-key`, `:root/datoms`, as
  `{:datoms [...]}`), written and read as a single blob rather than segmented into immutable
  B-Tree chunks. What a reader builds over that blob is outside this boundary.
- **Member layout and discovery.** A stream owner performs an atomic `cas!` on the stream's
  mutable root reference (`:root/datoms`) to publish the updated `{:datoms [...]}` blob.
  Segmenting those datoms into immutable B-Tree chunks written with `put!` is not yet shipped.
- **Querying (reader side).** A read resolves the stream's root reference from the `IKVStore`
  with a single `get`, which targets an immutable snapshot of that root's value at the time
  of the call — concurrent writes never disturb an in-flight read. What a reader does with
  the resulting bytes is outside this boundary.
- **Namespace stamping.** v1 does **not** yet derive the reader's per-member namespace from
  the canonical kickoff hash, because content addressing is not yet load-bearing (below).
- **Compaction / GC.** The `KVFile` backend implements Bitcask-style file compaction via the
  storage backend (e.g., via `dao.jing.file/compact-store!`). Overwritten stream roots and deleted tombstones create dead space 
  in the append-only log; compaction sweeps the live keyset, rewrites all live values to a 
  new log, and atomically swaps the file beneath the `IKVStore` interface, reclaiming disk space.
- **Encoding: `pr-str`, not canonical.** v1 persists and hashes datom frames via `pr-str`
  (current `yin/content.cljc`), not the canonical byte encoding `datom-spec.md` mandates.
  Consequence: cross-stream identity is stamped, not gauge-invariant — value-joins hold
  within a run, but the content hash is not yet a portable join key. Making the canonical
  encoding load-bearing is the first post-v1 step and does not change this document's API.

## Implementation Platform

`dao.jing` is implemented as cross-platform `.cljc` — its storage logic (the `IKVStore`
contract and the in-memory, file, and DHT backends) operates identically across Clojure
(`clj`), ClojureScript (`cljs`), and ClojureDart (`cljd`).

## Reaching `IKVStore` via `dao.stream.apply`

**Status: designed, not implemented.** No handler map anywhere in the codebase currently
registers `dao.jing` operations against `dao.stream.apply`.

`dao.stream.apply` (`src/cljc/dao/stream/apply.cljc`; `docs/design/dao.stream.md`,
*"dao.stream.apply Pattern"*) reifies function application as request/response datoms over
a stream pair — independently of any particular caller. Nothing stops registering
`put!`/`cas!`/`get` as handlers under that protocol — e.g. `{:jing/put! ..., :jing/cas! ...,
:jing/get ...}` — so that a caller reaches storage only by appending a request datom to a
request stream and reading the matching response off a response stream, never through a
direct imperative reference to a store. A plain agent can do this today with
`dao.stream.apply/put-request!` and `next-response` on the caller side and
`dispatch-request`/`serve-once!` on the callee side; no VM involvement is required. This
would be a third way to reach `IKVStore`, alongside a plain in-process handle and the
`dao.jing.dht` network backend (see The Storage Interface, above):

- **Cross-process reach.** Because the request/response streams are ordinary streams, the
  transport underneath can be a socket instead of an in-process ring buffer. An agent could
  reach a remote `dao.jing` this way, as an alternative to `dao.jing.dht`'s purpose-built
  `IDhtNet` transport.
- **Controlled-mode confinement (the more load-bearing case).** A governed interpreter —
  per the "share governed computation, not data" model (`dao.space.security.md`, ADR
  0002), specifically a `yin.vm` AST evaluated in a confined runtime — is denied any direct
  binding to storage by construction: its Environment/Store is scoped to only the datoms
  its capability authorizes, with no I/O or exfiltration primitives. Yin.VM already has a
  generic `dao.stream.apply` bridge for exactly this kind of confined host access
  (`yin.vm.host-ffi`; see `docs/design/ffi-design.md`), one consumer of the protocol among
  others, not a dependency that `dao.jing` or `dao.stream.apply` has on `yin.vm`. Registering
  `IKVStore` as capability-gated handlers there — present but refused when the capability
  doesn't cover the call, an empty allow-set equivalent to no handler at all — is exactly
  the mediator the security model requires: one instance of the "effect handlers that
  securely honor capability tokens" the security doc names as not yet built.

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
  giving every CPU core its own submission queue to the disk, avoiding locks. `dao.jing` gets
  the same zero-contention property for free: every writer's log lands at a distinct key, so
  no two agents ever contend for the same storage location. The single-writer-log discipline
  that guarantees this is enforced above this boundary, not by storage itself.
- **Storage Tiering (L1/L2 Caches), hypothetical.** Hardware uses fast/expensive caches
  (L1/L2/NVMe) in front of slow/cheap storage (spinning disks). In `dao.jing`, this would be
  a Decorator: a `CachingKVStore` wrapping a `NetworkKVStore` and a `MemKVStore` — a caller
  calls `get`; the caching store checks memory, faults from the network on a miss, and
  returns the chunk — leaving the interpreters above oblivious to the hierarchy. Neither
  store exists yet.
- **RAID and Erasure Coding, hypothetical.** RAID mirrors or stripes blocks across physical
  disks for redundancy. A `RaidKVStore` middleware could do this for bytes: when a caller
  calls `put!`, the store mirrors the chunk to three underlying `IKVStore` instances
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
  Peer-as-library read model. `dao.jing` is the decentralized Storage; the Peer is the reader
  library above it.
- **Plan 9** gives the *substrate*, one level down in `dao.stream`: the member logs the store
  is fed through are independent, location-transparent, append-only streams (see
  `dao.stream.md`, *Lineage: Plan 9*). The store inherits this only because its intake is
  `dao.stream`s.

The associative-matching, generative-communication *behavior* built on top of these bytes is
**not** here; that lineage (Linda) belongs to the reader above this boundary.

The synthesis: **`dao.jing` is a decentralized store of opaque bytes, designed to hold
immutable, content-addressed segments plus mutable stream-root references — pure syntax.**
Datoms, matching, and querying are semantics an embeddable reader library projects onto it.
(Immutability and content addressing are a discipline callers observe over the `IKVStore`
contract, not a guarantee the protocol enforces; see What DaoJing Is, above.)
