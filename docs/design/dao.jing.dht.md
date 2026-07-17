# Dao.Jing: DHT Backend Architecture

## Overview

`datom.world` keeps a strict separation between the semantic query/tuple space (`dao.space`) and the underlying byte storage boundary (`dao.jing`). `dao.jing` is deliberately dumb: an `IKVStore` of opaque byte maps, agnostic to Datalog, indexing, and datoms. This is the Datomic lineage. Storage is a swappable boundary, and `dao.jing` today ships two backends: an in-memory store and a Bitcask-style append-only file store.

The architectural payoff of keeping the boundary dumb is that a new backend turns local storage into distributed storage without touching any layer above. A Distributed Hash Table (DHT) backend, `dao.jing.dht`, implements `IKVStore` over a decentralized network. The higher layers do not change. The same `put!` / `get` / `cas!` that writes to a local file now writes to a peer grid.

The division of labor is strict: content addressing and storage semantics — `canonical`, `content-hash`, `segment-key`, `key-class`, the segment/root keyspace discipline — are `dao.jing`'s own job (`src/cljc/dao/jing.cljc`; see `dao.jing.md`, *The Segment and Root Keyspace*), true of a single local `KVMem` with no network at all. `dao.jing.dht` contributes none of that; it is purely the networking infrastructure — Kademlia routing, peer discovery, UDP transport, hash-verified fetch against untrusted peers — that takes individual, already-content-addressed `dao.jing` stores and makes them jointly reachable as one distributed hash table. One way to say it: `dao.jing.dht` doesn't invent content addressing, it distributes it.

This document specifies that backend. Throughout, it distinguishes the *contract* (what `IKVStore` must mean over a network, which is firm) from the *bets* (UDP transport, zero-copy slabs, cryptographic sortition), each of which is a research program with open questions.

## Status

| Layer | Status | Notes |
|---|---|---|
| `IKVStore` protocol | Firm | `src/cljc/dao/jing.cljc`; memory + file backends exist. |
| `dao.jing.dht` backend | Partial | First cut in `src/cljc/dao/jing/dht.cljc`: key classes, content addressing with hash-verified fetch, cache-forever segment reads, advisory `delete!` (option 1), and `cas!`/root reads forwarded to the nearest-peer owner as an explicit placeholder for sortition. UDP Kademlia peer in `src/cljc/dao/jing/dht/node.cljc` (JVM-only, one datagram per message; oversized segments stay local until DRDS). Canonical byte form is order-normalized `pr-str` pending the pinned Eve Flat encoding. The `canonical`/`content-hash`/`segment-key`/`key-class` functions implementing this now live in `dao.jing.cljc` (moved from here); this backend consumes them as `jing/segment-key` etc. rather than defining its own copies (see `dao.jing.md`, *The Segment and Root Keyspace*). |
| `dao.stream.udp` transport | Partial | JVM-only, fire-and-forget today. See Transport. |
| DRDS reliable layer | Proposed | Named in the UDP descriptor; not implemented. |
| Eve slab representation | Research | Real library (`SeniorCareMarket/eve`); not yet a dependency. See Zero-copy. |
| Sortition consensus | Research | Unbuilt; open questions. See `cas!`. |
| `dao.space` / `dao.qi` consumers | Design docs | The query and vector layers are themselves unbuilt. |

## The IKVStore Contract over a Network

The protocol is five methods (`src/cljc/dao/jing.cljc`). A DHT backend must account for all five, not only the three the original sketch of this document named.

### Key classes: what `dao.jing.dht` requires beyond `IKVStore`

The protocol itself does not enforce content addressing or immutability; `put!` is documented as "write an entry unconditionally," and the memory backend overwrites and resets `:rev` to 0 on re-`put!`. The DHT backend imposes a stricter contract on its callers, and that restriction must be explicit. The mechanism below (`canonical`, `content-hash`, `segment-key`, `key-class`) lives in `src/cljc/dao/jing.cljc` — it is not a DHT-specific idea, it is `dao.jing`'s own content-addressing discipline, applicable to any backend (see `dao.jing.md`, *The Segment and Root Keyspace*); this backend is simply the first caller that actually needs it enforced over an untrusted network:

- **Segment keys** are content addresses: `k = hash(v-map)`, `put!`-only, write-once. The backend routes by `k`, and because `k` is the content hash, a fetching node can verify that received bytes hash to `k`, giving integrity checking against untrusted peers for free. For the hash to be well-defined, `v-map` needs a canonical byte form: the Flat encoding under the network's pinned Eve version (see Zero-copy). This assumes Flat encoding is deterministic within a version, i.e. map iteration order is fixed by Eve's shared hash function; the integration must verify that assumption before anything is content-addressed.
- **Root keys** are caller-named mutable references, `cas!`-only. They are never written with `put!` and never cached as if immutable (see `get` below).

The class must be recoverable from the key itself, because `get` receives only `k` and (see below) the two classes have different read semantics. A content hash and a caller-named root are not distinguishable by inspection, so the keys are namespaced: `:segment/sha256-<hash>` (the algorithm prefix also keeps the keyword readable EDN; a bare hex name can start with a digit) and `:root/<name>`. The backend dispatches on the namespace; an un-namespaced key is rejected.

A `put!` to a root key, or a `cas!` on a segment key, is a contract violation with undefined behavior. (The local backends also deserve this discipline: an unconditional `put!` over a `cas!`-managed key resets the rev and opens an ABA hazard.)

### `put! [this k v-map]`, immutable write

Writes are content-addressed and immutable: `k` is the hash of `v-map` and never rebinds to a different value. The node routes `k` to the Kademlia neighborhood responsible for it and stores the byte map. Returns `true`. Because the segment is immutable, the write is idempotent: re-`put!` of the same content is a no-op.

Note this is a deliberate tightening of the protocol's "write unconditionally" wording; see Key classes above.

### `get [this k not-found]`, distributed lookup

The node performs a network lookup for `k`. Returns the `v-map`, or `not-found`.

For segment keys, immutability licenses aggressive caching: any node may cache any segment forever, with no invalidation protocol. Hot index segments replicate across the network's memory and traffic falls naturally toward the readers.

Root keys are the opposite case, and the cache-forever claim must not leak onto them. A cached read of a root returns a stale `:rev`, which then feeds the `cas!` retry loop a wrong `old-rev`: at best livelock, at worst a client that never converges. Reads of root keys need a distinct freshness path, a quorum or committee read consistent with whatever consensus `cas!` uses. The two key classes have two `get` semantics, and the backend must dispatch on the class.

This has a cost the original doc elided. Content addressing makes data *retrievable if present*, not *permanently available*. If no peer holds `k`, `get` returns `not-found`. Permanent availability requires a pinning and economic layer (see Operational reality). The cache-forever claim is about the correctness of cached copies, not a guarantee of presence.

### `cas! [this k old-rev v-map]`, the one mutation point

All mutability in `dao.jing` is isolated to the revision guard on a mutable reference (the stream root). `cas!` writes `v-map` only if the current `(:rev ...)` equals `old-rev`, then bumps the rev. This is the sole distributed-consensus problem in the whole design, because everything else is immutable. It is hard enough to deserve its own section. See `cas!` over the network.

### `delete! [this k]`, the revocation problem

This is where content addressing and the cache-forever property collide with the contract. Once an immutable segment has replicated to N peers, `delete!` cannot be an erasure: copies persist in caches outside any single node's control. `delete!` over a DHT is therefore *revocation*, not deletion.

Three options, in order of cost:

1. **Advisory unpin.** `delete!` drops the local reference only. Other cached copies survive until they age out. Cheapest, weakest. Acceptable for ephemeral data.
2. **Revocation tombstone.** A separate mutable set, keyed by content hash, records revoked segments. Reads consult it. The tombstone set is itself mutable and therefore must be carried by `cas!`, so `delete!` costs a consensus round. This re-introduces, on purpose, the one mutable structure the design tried to keep singular.
3. **Quarantine by epoch.** Revoked segments become invisible after an agreed epoch advances. (No epoch primitive is defined anywhere in this design; the sortition section names a monotonic epoch only as a candidate seed. Until one is specified, this option is not actually available.)

The contract requires a choice. The original doc left `delete!` undefined; this must be resolved before the backend is honest about implementing `IKVStore`. Recommended default: option 1 (advisory), with option 2 available for data that must be revocable.

### Compaction (Local only)

Compaction is a local garbage-collection concern: each node compacts its own slab or append-only log. It has no distributed meaning as a single call. On an Eve-backed node, local reclamation is Eve's cooperative GC: old HAMT nodes are freed only after every local reader has advanced past them, so compaction is mostly a nudge to that mechanism. Eve drops processes with no heartbeat for 30s from the epoch scan, so a crashed reader cannot pin memory forever; only a live-but-stalled reader stalls reclamation, a liveness detail the implementation must surface. Distributed GC, reclaiming segments that no live root references anywhere in the network, is a separate and open problem (distributed reference counting, ephemerons). Flagged in Operational reality; not solved here.

### `close! [this]`, local resources

Releases per-node resources (socket, slab handle). Purely local. Returns `nil`.

## Transport (proposed)

`dao.stream.udp` exists today as a JVM-only, fire-and-forget UDP transport: each datagram is an 8-byte big-endian sequence number plus a Transit-JSON payload (`src/cljc/dao/stream/udp.cljc`). The descriptor carries a `:reliable?` flag and names a Datom Reliable Datagram Streams (DRDS) layer for sequence numbering, fragmentation, and reassembly of chunks above the MTU. DRDS is not implemented; `:reliable?` is stored and never consulted.

The DHT's intended transport posture:

- **Fire-and-forget UDP** for gossip: `ping`, `find_node`, segment announcements. These are genuinely one-way facts where retransmission is cheap and a missed datagram is a missed tuple. A datom is a natural UDP datagram: a discrete, immutable fact in a larger tuple space.
- **Chunk fetch is not gossip.** A `get` for a segment is a request-response RPC: the requester needs the reply, so a lost reply is not acceptable and the client must own timeouts and retries. That is reliability rebuilt in user space, the same cost class the DRDS point below flags; it should be named as a deliberate cost, not smuggled in under fire-and-forget.
- **DRDS** for index segments that exceed the MTU.

Three realities the original doc did not acknowledge:

1. **DRDS reinvents reliable ordered streams in user space.** That is TCP's territory (and QUIC's, which is reliable streams over UDP plus modern crypto and multiplexing). CLAUDE.md says avoid cleverness. Choosing to rebuild reliability is legitimate only if paid deliberately; an accidental TCP is the worst of both. Name the tradeoff, or carry a reliable stream.
2. **The transport is JVM-only today.** Browsers cannot send or receive raw UDP at all (no such API exists); the closest reachable primitive at the browser edge is WebRTC DataChannel or WebTransport, which is not UDP-over-a-DHT. The "uniform across JVM, Node, and browser" claim depends on a transport that does not exist outside the JVM. This limitation is primary: a browser cannot be a full DHT peer regardless of its ability to hold a zero-copy slab in a `SharedArrayBuffer`.
3. **MTU 1450 is optimistic.** IPv6 guarantees only 1280. PMTU on real internet paths is often lower. Fragmentation policy needs the lower bound, not the aspirational one.

## Zero-Copy Representation (research)

The original goal was to remove serialization entirely: transmit raw bytes of Eve slabs so a received chunk is queryable with no parse step. Eve's own internals rule that out across machines, and the honest thesis is narrower: *serialize once on send, rehydrate once on receive, zero-copy on every local read thereafter*. What is removed is per-read deserialization, not the wire encoding.

Eve (https://github.com/SeniorCareMarket/eve, "Extensible Value Encoding") is the substrate. It provides 32-way HAMT maps, vectors, sets, and lists in shared memory, backed by `SharedArrayBuffer` in-process or memory-mapped files cross-process, with four platforms sharing one format: browser CLJS, Node.js CLJS, JVM Clojure, and Babashka use identical on-disk and in-memory layouts, hash functions, and CAS protocols. Local reads are cheap: `deref` lazily reconstructs the value by walking slab memory, with no bulk deserialization; `swap!` path-copies new nodes within the slab, with no serialization step. Reclamation is cooperative GC keyed on reader progress. Datomic and Datalevin sweat zero-copy off-heap access for the same reasons; Eve is the Plan-9-shaped fit, data not objects.

Why raw slabs cannot cross the network: every node reference inside a slab is a slab-qualified offset, meaningful only within one machine's mmap domain. Eve encodes this boundary in its own wire format. The SAB pointer tags (`0x10` to `0x13`) are in-process references and, per Eve's internals doc, "must never appear in cross-process atom serialization"; the cross-process encoding is the Flat form (`0xED`/`0xEF`), self-contained and length-prefixed. Cross-machine transfer is therefore Flat serialization by Eve's own construction. A `dao.jing.dht` segment is a Flat-encoded Eve value; the receiving peer rehydrates it into its own local slabs through its own allocator, and every query after that is a local zero-copy read.

Integration means adding `eve/eve` as a git dependency in `deps.edn` (Clojure and CLJS source) and `eve-native` in `package.json` (the native mmap addon, needed only for cross-process persistent atoms; in-process `SharedArrayBuffer` atoms work without it). **Eve's API is currently alpha (`eve.alpha`), so the slab format and API surface are not yet stable. More importantly, Eve's Flat layout is deterministic within a version but can change between commits. If peers run different Eve versions, they produce different Flat bytes for the same logical value, causing content addressing to silently fracture. Integration must therefore treat the pinned Eve commit (e.g., `0e084fff`) as a strict network-wide interop invariant, not just local repo hygiene. The mismatch must fail loudly, not silently: carry the format version (the pinned Eve commit, or a format epoch) in every routing message and in the segment envelope, so a version mismatch is rejected on first contact instead of quietly generating alien keys — the DHT is connectionless, so the version rides per message rather than in a handshake. This pinned Flat encoding is also what makes `k = hash(v-map)` well-defined: `k` hashes the Flat bytes only, with the version tag riding alongside as fast-reject metadata rather than inside the hash, so content identity stays bound to the value and not to the version it was minted under. See Key classes.**

Three caveats, sharpened by the Flat-ingest reality above:

1. **The Deployment Topology**: Eve's shared slab is a single-machine construct (mmap files; futex/Unsafe CAS). Each DHT peer runs its own *local* Eve instance (local slabs, local roots, local CAS), and the network ships Flat-encoded, content-addressed segments between per-peer instances. Slab-qualified offsets never cross the wire; they are per-peer artifacts of rehydration. Co-located processes on one machine can still share one Eve domain and get true shared-memory reads.
2. **Validation is parser safety, not pointer safety**: Because ingest is Flat deserialization, the receiving peer's own allocator generates every slab offset; a remote peer cannot inject a crafted pointer, so the arbitrary-memory-read threat does not arise on this path (it would only exist if raw slab bytes were mmap'd, which caveat 1 forbids). The residual ingest risks are the ordinary ones for a length-prefixed format: hostile length fields, recursion depth, allocation exhaustion. Structural validity piggybacks natively on the rehydration pass, which parses and rebuilds the structure with locally-generated trusted offsets. Validate eagerly on ingest via this deserialization pass, then trust local reads unconditionally.
3. **Transport vs. Representation**: Eve makes the *bytes* uniform across JVM, Node, and browser; it does not put UDP in the browser. A browser agent can hold a slab in a `SharedArrayBuffer` and read it zero-copy (provided the page is cross-origin isolated with COOP/COEP headers), but it still reaches peers over a browser-capable transport. See Transport.

## `cas!` over the Network (research)

`cas!` is the only mutation, therefore the only consensus. The guard compares `old-rev` to the current `(:rev ...)` for key `k`; on match it writes `v-map` with the rev bumped. A committee, if one is used, must agree on two things: the current rev, and the new `v-map`.

There is a clean resonance with Eve here. Eve isolates all mutability to a single 32-bit root pointer into the HAMT slab, advanced by lock-free CAS, the same shape as `dao.jing`'s one mutable reference per stream. Eve's CAS already solves the *local* cross-process case: several processes on one machine advancing the same mmap'd root, leaderless, via hardware CAS on a shared word. The network case is what remains hard, because peers on different machines share no memory and do not trust each other; that is what this section is about.

Two precisions keep the Eve framing honest:

- **The consensus value carries no pointer at all.** Eve's 32-bit root is a slab-qualified reference, `[class_idx:3 | block_idx:29]`: a size-class selector plus a block index into that class's local slab. It is meaningful only within one peer's mmap domain, so it can never be the value a network consensus agrees on. What the committee agrees on is the revision counter and a `v-map` of *segment keys* (content addresses); each peer derives its own local root pointer when it rehydrates those segments. Local pointers are downstream of consensus, never inside it.
- **Capacity bounds a domain, and large indexes are multi-segment by construction.** The 29-bit block index gives each size class a finite slab (today roughly 256 MB to 1 GB per class), so a single Eve domain holds a few GB. Eve does not chain slabs; it routes each node to the size class matching its size, and child offsets are valid only within one domain. A few GB covers a typical datom index, so multi-segment is the large-index tail, not the common path. When an index does exceed one domain, it is split across multiple content-addressed segments — `put!` each one, let the root's `v-map` reference them by key — which is exactly the segment model this doc already defines. However, because Eve's native HAMT child pointers are slab-qualified offsets, interior nodes cannot point into another segment without a new reference encoding (e.g., a leaf convention carrying a segment key), and that mechanism is the open requirement for the tail case.

The proposed mechanism is Algorand-style cryptographic sortition: instead of a fixed leader, a verifiable random function (VRF) selects a small, randomized committee for each update; the proposer sends the `cas!` to the committee; on a t-of-n quorum the update is accepted and gossiped. This avoids a targeted leader. The mechanism has several open questions the original doc left implicit:

1. **Sybil resistance.** Kademlia node IDs are self-generated. With no stake and no admission control, an attacker mints a majority of IDs and wins every committee. Sortition is only meaningful when weighted by a Sybil-resistant registry (stake, identity, admission). Unspecified.
2. **Committee discovery.** A proposer must find its committee's addresses. Kademlia `find_node` locates nodes by ID, but the committee IDs are the output of the VRF over a seed plus each candidate's key and stake. The lookup procedure (who evaluates the VRF, against what membership view) is unspecified.
3. **Seed circularity.** Algorand seeds sortition from the *previous block*, a value every honest node has already agreed on. Seeding from "the root's current state" seeds from the contested value itself; under partition, two proposers can form two committees from two views of the current state. The rev counter has the same defect: it is the contested value. An agreed seed source outside the contested key (a monotonic epoch) is needed. Unspecified.
4. **Finality model.** A single voting phase cannot be both safe and live under asynchrony (the FLP result). Algorand buys speed with probabilistic finality and explicit fallback rounds. "Single rapid voting phase, lock-free" hides that. Either accept probabilistic finality and specify the fallback, or specify a multi-phase protocol. Unspecified.
5. **Lost-CAS retry.** `cas!` returns `false` when another writer won (the committee already moved past `old-rev`). The proposer must re-read the new rev and retry; re-reading is itself a distributed read against a committee that may have rotated again. The retry and re-read path is unspecified.

Resolving these five is the precondition for `cas!` over the network. Until then, this section is a sketch of an approach, not a protocol.

## Operational Reality (open problems)

- **NAT traversal.** A global peer-to-peer grid over UDP, between peers behind NAT, does not deliver without hole-punching, relay/TURN, and bootstrap discovery. This is where real P2P DHTs (libp2p, IPFS) spend the bulk of their effort. Unaddressed here; it is the largest practical gap between "distributed" and "works across the public internet."
- **UDP amplification.** Connectionless `get` and `find_node` over UDP are reflection-amplification vectors (the UDP BitTorrent tracker and DNS both learned this the hard way). Fire-and-forget routing needs address validation (a returnability cookie, QUIC-Retry shaped) and rate limiting, or the network becomes a DoS instrument.
- **Storage economics.** Unbounded caching is unbounded storage growth. The design has no pinning, no eviction, no scarcity, and therefore no account of when `get` returns `not-found`. CLAUDE.md draws vocabulary from economics; this is where it belongs: a storage market or pinning layer that creates negative feedback on growth. Without it, hot data replicates and cold data vanishes, and `get` is best-effort by default.
- **Distributed garbage collection.** Reclaiming segments that no live root references, across a peer network, is distributed reference counting. Open. Tied to the `delete!` question above.

## Lineage: UDP, TCP, and the Dumb Boundary

The dumb-backend thesis is the part that ships, and it requires none of UDP, Eve, or sortition. Datomic's swappable storage backends (S3, DynamoDB, Cassandra, Postgres) are TCP-based and mainstream. A network `IKVStore` can be delivered today over the transports `datom.world` already has (`dao.stream.http`, `dao.stream.ws`, both TCP), and the result is a working distributed store. The UDP transport, the zero-copy slabs, and the sortition consensus are a separate research program bolted onto that one good idea.

The philosophical commitment to UDP, "reject the false semantics of TCP, a datom is a datagram," is consistent with the project ethos. It should be made with open eyes: DRDS is reliable ordered streams rebuilt in user space, which is TCP's job. If the goal is datagram semantics with reliability, the tradeoff should be chosen deliberately, not arrived at by rebuilding TCP accidentally. CLAUDE.md: avoid cleverness.

## Emergent Behavior

The point of keeping `dao.jing` dumb is that the higher abstractions inherit the network for free. When an agent, eventually, executes a Datalog query in `dao.space` or a similarity search in `dao.qi`, the engine will simply ask `dao.jing` for nodes; it will not know the index was fetched from a peer, validated and rehydrated into a local slab, and committed by a committee. (`dao.space` and `dao.qi` are themselves design documents today, so this is a statement of intent for the whole stack, not a present capability.)

The interpretation remains pristine; the execution, once the bets are paid, scales to the globe.
