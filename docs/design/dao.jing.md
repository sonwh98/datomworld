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
- `docs/design/yin.vm.ffi.md` — Yin.VM's generic `dao.stream.apply` bridge (`yin/vm/ffi.cljc`, implemented), one consumer `IKVStore` could be exposed through; registering `IKVStore` as handlers there is designed, not built (see Reaching `IKVStore`, below)
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
;; The dumb storage API (see src/cljc/dao/jing.cljc) — all five IKVStore methods
(kv/put! store :segment-id v-map)      ; write a fresh immutable segment chunk
(kv/cas! store :root old-rev v-map)    ; advance a mutable root reference
(kv/get store :root nil)               ; read a mutable root reference
(kv/get store :segment-id nil)         ; read an immutable segment chunk
(kv/delete! store :segment-id)         ; remove an entry by key
(kv/close! store)                      ; release the backend's resources
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

The content-derived key half of that discipline has a concrete mechanism: `dao.jing` itself
(not any one backend) owns `canonical` (order-normalize a value so equal values print
identical bytes, `defn-`, private), `content-hash` (sha256 of the canonical print, excluding
`:rev`), `segment-key` (mint `:segment/sha256-<hash>` from a v-map; the prefix keeps the keyword readable EDN), and `key-class` (dispatch a key
to `:segment` or `:root`) — see `src/cljc/dao/jing.cljc`. `dao.jing.dht` (`dao.jing.dht.cljc`)
consumes these (`jing/segment-key`, `jing/key-class`, `jing/content-hash`) for its own routing
and key-discipline enforcement rather than defining its own copies — see `dao.jing.dht.md`,
*Key classes*. Every backend (Mem, File, DHT alike) can now mint content-addressed keys the
same way, and `put!`'s "fresh, content-derived keys" wording above is something any caller can
actually invoke, not a convention only the DHT backend happened to honor. This also fixes the
dependency direction: `dao.jing.dht` is built *on top of* core `dao.jing`
(`dao.jing.dht.cljc` requires `dao.jing`, implementing the `IKVStore` protocol `dao.jing`
defines) — a downstream backend was never the right place for a property of the storage
boundary itself.

This is not a networking feature that happens to be implemented in the networking backend —
content addressing is a property of what an immutable segment *is* ("What DaoJing Is," above:
"designed to hold immutable, content-addressed segments"), independent of whether that segment
is ever exposed to a peer. A single-process `KVMem` with no network connection at all still
benefits: identical content mints the same key regardless of which caller wrote it first
(dedup for free, no coordination needed), and a segment's identity is stable *before* it is
ever replicated — so a store can be swapped from `KVMem` to `KVFile` to `dao.jing.dht` later
without re-keying anything, because the keys were never a networking artifact to begin with.
A `dao.jing.dht`-only placement would have gotten this backwards: it would make content
addressing look like something that starts mattering once peers exist, when it is actually a
local identity guarantee the network backend merely goes on to *rely on* (hash-verified fetch
against untrusted peers), not one it invents. **Status: done** — the mechanism lives in
`dao.jing.cljc`; `dao.jing.dht.cljc` consumes it. See *Current Scope*, Encoding, below for what
it does and does not buy.

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

## Current Scope

This is one evolving spec, not a series of frozen releases — "not yet" below means exactly
that, work still to land in this same document, not a v2 to be written later. What's
implemented at the storage boundary today, and the contracts already pinned down.

- **Storage roots today: one mutable key per stream; segments shipped.** Each stream's
  root (`:root/<name>`) holds either the stream's full datom vector wholesale
  (`{:datoms [...]}`) or, since 2026-07-10, an owner-built index manifest
  (`{:indexes {:eavt :segment/sha256-<hash> ...}
  :count n}`) whose values point at immutable, content-addressed B-Tree node segments written
  with `put!` under `segment-key` — published by `dao.space.index/publish-index!`. The
  root-naming conventions (per-stream `:root/<name>`, the `:root/members` membership root)
  and both root shapes are reader-owned conventions
  defined in `src/cljc/dao/space/index.cljc`, not `dao.jing` constants — storage only ever
  sees the keywords and blobs its caller hands it, and never knows the segments form an index.
- **Member layout and discovery.** A stream owner performs an atomic `cas!` on its own
  mutable root reference (`:root/<name>`) to publish either shape: the wholesale
  `{:datoms [...]}` blob, or the `{:indexes ...}` manifest after `put!`-ing the segments it
  references. Republishing unchanged data is idempotent — content-derived keys make the same
  segments land at the same addresses. Discovery is the membership root (`:root/members`),
  written once per stream at `open!` and enumerated by readers — `IKVStore` has no scan, so
  reachability starts there.
- **Querying (reader side).** A read resolves the stream's root reference from the `IKVStore`
  with a single `get`, which targets an immutable snapshot of that root's value at the time
  of the call — concurrent writes never disturb an in-flight read. For an `{:indexes ...}`
  root the reader then `get`s the immutable segments that root references (all of them on an
  eager walk; only the traversal path on the JVM's lazy path). What a reader builds from the
  resulting bytes is outside this boundary.
- **Namespace stamping.** Not yet: the reader's per-member namespace is not yet derived from
  the canonical kickoff hash, because content addressing is not yet load-bearing (below).
- **Compaction / GC.** The `KVFile` backend implements Bitcask-style file compaction via the
  storage backend (e.g., via `dao.jing.file/compact-store!`). Overwritten stream roots and deleted tombstones create dead space 
  in the append-only log; compaction sweeps the live keyset, rewrites all live values to a 
  new log, and atomically swaps the file beneath the `IKVStore` interface, reclaiming disk space.
- **Encoding: canonical bytes are unbuilt in both layers; what separates `dao.jing` from
  `yin.content` is the shape of the hash, not the byte encoding.** `dao.jing` hashes a whole
  opaque v-map blob for key-minting (no entity structure, no `[a v]` pairs, no `:db/derived`
  bookkeeping); `yin.content` computes an AST/entity-level Merkle hash over sorted `[a v]`
  pairs with ref-resolution and a dependency-ordered hash cache (`compute-content-hashes`).
  That structural difference — not "one is canonical, the other isn't" — is why `dao.jing`
  must not depend on `yin/content.cljc`: it would be pulling in a higher-layer, entity-shaped
  mechanism to solve a lower-layer, blob-shaped problem.

  The evidence for "unbuilt in both": where `dao.jing` mints a content-derived segment key
  (`segment-key`, above), today's implementation hashes an order-normalized `pr-str` print of
  the v-map (sort map keys and set members so equal values print identical bytes, then sha256)
  — the same stand-in `dao.jing.dht.md` names for the pinned Eve Flat encoding, not the
  canonical byte encoding `datom-spec.md` mandates (little-endian ints, IEEE754 floats,
  per-type length-prefixing, the inline-or-hash-over-32-bytes threshold). `yin/content.cljc` is
  in the same position: its `sha256` is also computed over a plain `pr-str`
  (`(dw/sha256 (pr-str resolved))`, not per-type canonical bytes), despite its own docstring
  calling the result "gauge-invariant" — that claim holds only up to this codebase's one
  `pr-str` printer, not across languages/platforms with a different printer, the same narrower
  gap `dao.jing`'s own hash has. Both currently share that non-canonical printer underneath;
  neither is a solved problem the other is missing.

  Making `dao.jing`'s segment-key hash canonical (Eve Flat encoding, per `dao.jing.dht.md`) is
  a `dao.jing`-internal follow-up, orthogonal to whatever `yin.content` eventually does about
  its own encoding, and does not change this document's API. Whether entity-level namespace
  stamping (above) ever needs `datom-spec.md`'s per-type canonical encoding is a separate
  question for the reader layer, out of scope here.

## Implementation Platform

`dao.jing` is implemented as cross-platform `.cljc` — its storage logic (the `IKVStore`
contract and the in-memory, file, and DHT backends) operates identically across Clojure
(`clj`), ClojureScript (`cljs`), and ClojureDart (`cljd`).

## Reaching `IKVStore`: `dao.stream.apply` and `dao.stream.rpc`

**Status: implemented** for the cross-process case via `dao.jing.remote.{server,client}`
(`src/cljc/dao/jing/remote/{server,client}.cljc`; tests in
`test/dao/jing/remote_test.cljc`), which exposes a local `IKVStore` over the network today.
The other two cases below — a direct in-process `dao.stream.apply` exposure of `IKVStore`,
and controlled-mode confinement through `yin.vm.ffi` — are patterns this document names as
the right shape, not code that exists yet: nothing in `src/` currently registers `:jing/*`
handlers against an in-process `dao.stream.apply` pair or against `yin.vm.ffi`; the only
`:jing/*` handlers map in the codebase is `dao.jing.remote.server/default-handlers`, built
for `dao.stream.rpc`.

`dao.stream.apply` and `dao.stream.rpc` are not two interchangeable ways to reach `IKVStore`
— they are two different interface shapes, and the choice follows directly from whether a
network is actually involved:

- **`dao.stream.apply` directly, in-process (pattern, not yet built for `IKVStore`).** An
  in-memory `IKVStore` should never cross a network to reach itself. It could be modeled as a
  request/response stream pair over an in-process ring buffer (`dao.stream.apply/put-request!`
  + `next-response` on the caller side, `dispatch-request`/`serve-once!` on the callee side) —
  no WebSocket, no RPC framing, no serialization. This is the same primitive-level shape
  `yin.vm.ffi`'s confined-host bridge already uses for other ops (below) — but no `:jing/*`
  handlers are registered there yet, for this or the in-process case.
- **`dao.stream.rpc`, only when a network is involved.** `dao.stream.rpc` is a *request/response
  call* abstraction, not a *stream* abstraction — its public surface is `connect!`/`call!`/
  `close!` (client) and `start!`/`stop!` (server), where `call!` sends one `(op args)` and
  returns one result (blocking on `:clj`; a Promise/Future on `:cljs`/`:cljd`). It does not
  hand the caller a `dao.stream` to read or write. Internally it *is* built on one — `connect!`
  opens a `dao.stream.ws` WebSocket stream, and `call!` drives it through
  `dao.stream.apply/put-request!` (`dao/stream/rpc/client.cljc:209`) followed by a poll loop
  that calls `next-response` (`dao/stream/rpc/client.cljc:80`, inside `wait-for-response`,
  `client.cljc:210-214`) — but that stream is private framing plumbing the RPC layer owns,
  never exposed for general stream use. `dao.jing.remote.client`'s
  `RemoteKVStore` keeps a `:stream` field only so `close!` can call `ds/close!` on it; that is
  not an invitation to treat it as a generic stream elsewhere.

The layering, bottom to top:

- **`dao.stream.apply`** (`src/cljc/dao/stream/apply.cljc`; `docs/design/dao.stream.md`,
  *"dao.stream.apply Pattern"*) is the primitive: it reifies function application as
  request/response datoms over a stream pair, independently of any particular caller.
- **`dao.stream.rpc.{server,client}`** (`src/cljc/dao/stream/rpc/`) is the generic transport
  built directly on `dao.stream.apply` (both require `dao.stream.apply`; `rpc.server`'s own
  docstring: "exposes a caller-supplied handlers map over a WebSocket via `dao.stream.apply`").
  It knows nothing about `dao.jing` — `rpc.server/start!` takes any `{op-keyword fn}` handlers
  map, and `rpc.client/call!` sends `(op args)` and waits for the matching response.
- **`dao.jing.remote.{server,client}`** is the `dao.jing`-specific adapter over that generic
  layer: `remote.server/default-handlers` builds `{:jing/put! ..., :jing/cas! ...,
  :jing/get ..., :jing/delete! ...}` from a local store and hands it to `rpc.server/start!`;
  `remote.client/connect!` returns a `RemoteKVStore` whose every `IKVStore` method calls
  `rpc.client/call!` against the matching `:jing/*` op. This is a third way to reach
  `IKVStore`, alongside a plain in-process handle and the `dao.jing.dht` network backend (see
  The Storage Interface, above) — a WebSocket instead of `dao.jing.dht`'s purpose-built
  `IDhtNet`/Kademlia transport. (`RemoteKVStore`/`connect!` are `:clj`-only today: reconciling
  `IKVStore`'s synchronous-return contract with `rpc.client/call!`'s Promise/Future return on
  `:cljs`/`:cljd` is unscoped; the `dao.stream.rpc` layer underneath is portable regardless.)

Not yet built: **controlled-mode confinement**, the more load-bearing case. A governed
interpreter — per the "share governed computation, not data" model (`dao.space.security.md`,
ADR 0002), specifically a `yin.vm` AST evaluated in a confined runtime — is denied any direct
binding to storage by construction: its Environment/Store is scoped to only the datoms its
capability authorizes, with no I/O or exfiltration primitives. Yin.VM already has a generic
`dao.stream.apply` bridge for exactly this kind of confined host access (`yin.vm.ffi`; see
`docs/design/yin.vm.ffi.md`), one consumer of the protocol among others, not a dependency
that `dao.jing` or `dao.stream.apply` has on `yin.vm`. Registering `IKVStore` as
capability-gated handlers there — present but refused when the capability doesn't cover the
call, an empty allow-set equivalent to no handler at all — would be exactly the mediator the
security model requires: one instance of the "effect handlers that securely honor capability
tokens" the security doc names as not yet built. Unlike the plain `dao.jing.remote` handlers
above (which trust every caller unconditionally), this variant does not exist yet.

## The Block Storage Metaphor (Hardware Analogies)

Because the `IKVStore` protocol is so primitive — a handful of operations, dominated by
`put!`/`get`/`cas!` (`delete!`/`close!` are housekeeping, not part of the analogy below) — it
behaves almost exactly like a hardware block storage device. This means proven hardware patterns map
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
