# Dao.Jing: DHT and Stream Replication Architecture

## Overview

`datom.world` keeps a strict separation between the semantic query/tuple space (`dao.space`) and the underlying stream observer (`dao.jing`). `dao.jing` is deliberately dumb: a generic fold over a single `dao.stream` that projects append-only log records into a materialization target. It is agnostic to Datalog, indexing, datoms, and the distinction between "segments" and "roots."

The architectural payoff of keeping the boundary dumb is that distribution happens below the query logic. Higher layers interact with their materialized projection (a plain map, SQL database, etc.). The distributed backend handles how the stream records and content-addressed segments are replicated across a decentralized network.

This document specifies the distributed backend. It combines two different distribution models for the two different types of data in the system:
1. **Stream Replication (for mutable roots)**: Push-based, ordered replication of append-only logs. 
2. **Distributed Hash Table (for immutable segments)**: Pull-based, content-addressed fetching over Kademlia.

The division of labor is strict: content addressing and storage semantics (`content-hash`, `segment-key`, `key-class`) are the job of the semantic layer above `dao.jing` (`dao.space.address`, planned extraction; currently in `dao.jing.cljc`). The network layer purely provides the infrastructure to replicate streams and fetch segments. `dao.jing` itself treats all keys as opaque — the segment/root distinction is a higher-layer concept that the DHT backend routes on.

## Status

| Layer | Status | Notes |
|---|---|---|
| Stream protocol | Firm | `dao.stream` is the primitive. |
| Materializer | Proposed | `materialize-step` / `materialize-mutable-step!` build materialized projections from streams. |
| Segment DHT | Partial | Content addressing with hash-verified fetch, cache-forever segment reads. UDP Kademlia peer in `src/cljc/dao/jing/dht/node.cljc` (JVM-only). |
| Stream Replication | Partial | Point-to-point replication ships via `dao.stream.ws`; multi-peer gossip/relay topology is proposed. |
| Zero-copy (Eve slab) | Research | Real library (`SeniorCareMarket/eve`); not yet a dependency. |
| Sortition consensus | Eliminated | Stream-local roots replaced the need for distributed CAS consensus. |

## The Segment and Root Keyspace over a Network

`dao.jing` itself is keyspace-ignorant (see `dao.jing.md`, *Keyspace Ignorance*). But the distributed backend routes on key namespaces because segments and roots require entirely different networking strategies.

- **Segment keys** (`:segment/sha256-<hash>`) are content addresses. They hold immutable byte maps (e.g., B-Tree nodes). Identical content mints the same key. The DHT backend routes by `k`, and a fetching node can verify that received bytes hash to `k`, giving integrity checking against untrusted peers for free.
- **Root keys** (`:root/<name>`) are stream-specific mutable references, updated via `[k :cas expected v]` append records in a stream. They point to the latest state (e.g., the root node of an index B-Tree). A query names roots explicitly with `dao.space.query/root-source`; how a remote reader discovers which root sources belong in its pool remains a `dao.space` concern above this backend.

## Stream Replication (Roots)

**The distributed consensus problem (e.g. distributed `cas!`) has been eliminated by the stream-centric design.**

Because every stream has exactly one writer, there is no write contention for roots. A `[k :cas expected v]` record on a root is a **local append** to the writer's own stream. The writer is the single authority for its roots (single-writer-per-root). There is no distributed `cas!` at all.

**Cost of single-writer authority:** no consensus, but the writer is a single point of availability for that stream's advancement. If the writer is down or partitioned, its roots freeze — no peer can append to another writer's stream. The old sortition committee (had it been built) would have tolerated a crashed proposer. The new model trades that away. Writer failover and ownership transfer are the open problem that replaces distributed consensus.

Root updates travel as stream records replicated to interested peers. This means:
- **Push-based**: The writer (or a relay) pushes the log forward to subscribers.
- **Ordered**: Stream records must arrive in order, which is TCP/WebSocket/QUIC territory, not UDP.
- **Materialized locally**: A remote peer consumes the stream and updates its local materialization target via `step-incremental!`.
- **Consistency**: The writer enjoys read-your-writes linearizability against its own materializer. Remote readers see an *eventually consistent* view of the root, lagging the writer by stream propagation delay.

If a remote reader needs a fresh root, it can request "catch up to sequence N" before reading — a synchronous RPC check against the writer (e.g., via `dao.stream.rpc`). This requires writer availability.

## Distributed Hash Table (Segments)

While roots are replicated preemptively via streams, segments are large and numerous. They should be fetched on demand (pulled) from the nearest peer, not streamed to everyone. The DHT is the pull-based distribution mechanism.

### Segment Fetch: Distributed Lookup
When a higher layer (e.g., `dao.space.address`) encounters a `:segment/*` key that is not present in the local materialization target, it issues a DHT `get`. `dao.jing`'s fold itself does not trigger fetches — it processes whatever records arrive in the stream. The DHT fetch is a higher-layer concern.

For segment keys, immutability licenses aggressive caching: any node may cache any segment forever, with no invalidation protocol. Hot index segments replicate across the network's memory and traffic falls naturally toward the readers.

(Root keys are never looked up over the network via DHT `get`; they are always read from the local materialized view of the stream.)

### Transport

The transport posture splits by data type:
- **TCP / WebSocket / QUIC** for stream replication. Ordered, reliable delivery is required for stream logs.
- **Fire-and-forget UDP** for Kademlia gossip (`ping`, `find_node`, segment announcements).
- **Reliable RPC** for segment fetch. A `get` for a segment is a request-response RPC. The shipping path runs over WebSocket (browser-compatible, reliable, implemented). QUIC is a candidate for the same role (reliable ordered, browser-capable via WebTransport) but is not yet built. The UDP path (DRDS for MTU fragmentation and retries) is research; browsers cannot participate. 

## Zero-Copy Representation (research)

Eve (https://github.com/SeniorCareMarket/eve, "Extensible Value Encoding") is the substrate for zero-copy reads. It provides 32-way HAMT maps, vectors, sets, and lists in shared memory.

Why raw slabs cannot cross the network: every node reference inside a slab is a slab-qualified offset, meaningful only within one machine's mmap domain. Cross-machine transfer uses the Flat encoding (`0xED`/`0xEF`), self-contained and length-prefixed. A segment is a Flat-encoded Eve value; the receiving peer rehydrates it into its own local slabs through its own allocator, and every query after that is a local zero-copy read.

Eve's Flat layout is deterministic within a version but can change between commits. If peers run different Eve versions, they produce different Flat bytes for the same logical value, causing content addressing to silently fracture. Integration must therefore treat the pinned Eve commit as a strict network-wide interop invariant, not just local repo hygiene. The mismatch must fail loudly, not silently: carry the format version (the pinned Eve commit, or a format epoch) in every routing message and in the segment envelope, so a version mismatch is rejected on first contact instead of quietly generating alien keys — the DHT is connectionless, so the version rides per message rather than in a handshake. This pinned Flat encoding is also what makes `k = hash(v-map)` well-defined: `k` hashes the Flat bytes only, with the version tag riding alongside as fast-reject metadata rather than inside the hash, so content identity stays bound to the value and not to the version it was minted under.

## Deletion and Distributed GC

In the stream model, deletion is a `:cas` record: `[k :cas current-v absent]`. Once an immutable segment has replicated to N peers, this local deletion cannot be an erasure: copies persist in caches outside any single node's control. Deletion over a DHT is therefore *revocation*, not deletion.

Recommended default: **Advisory unpin**. The local materialization target drops the key. Other cached copies survive until they age out or are garbage collected locally.

**Revocable tombstone** (single-writer alternative): Under the old distributed-consensus model, a shared mutable tombstone set required a consensus round — expensive enough to be a non-starter. Under the new single-writer-per-stream thesis, distributed consensus is irrelevant, but a new problem replaces it: a shared tombstone stream needs a single writer. Arbitrary peers wanting to record a revocation must route through the designated tombstone writer (a coordination bottleneck, not consensus, but still a coordination point). This makes advisory unpin the considered default, with a tombstone stream applicable where a single revoker is acceptable. 

## Operational Reality (open problems)

- **NAT traversal.** A global peer-to-peer grid over UDP/TCP, between peers behind NAT, does not deliver without hole-punching, relay/TURN, and bootstrap discovery. This is where real P2P DHTs (libp2p, IPFS) spend the bulk of their effort. Unaddressed here; it is the largest practical gap between "distributed" and "works across the public internet."
- **UDP amplification.** Connectionless `get` and `find_node` over UDP are reflection-amplification vectors (the UDP BitTorrent tracker and DNS both learned this the hard way). Fire-and-forget routing needs address validation (a returnability cookie, QUIC-Retry shaped) and rate limiting, or the network becomes a DoS instrument.
- **Browser transport gap.** Browsers cannot send or receive raw UDP (no such API exists). The closest reachable primitive at the browser edge is WebRTC DataChannel or WebTransport. A browser cannot be a full DHT peer regardless of its ability to hold a zero-copy slab in a `SharedArrayBuffer`. Browser-capable paths: root updates via WebSocket/QUIC stream replication; segment fetch via WebSocket RPC (the shipping path, not the UDP-DRDS research path); QUIC is proposed but not implemented. Kademlia UDP gossip requires server-side relay or a browser-capable DHT transport (e.g., WebRTC).
- **Storage economics.** Unbounded caching is unbounded storage growth. The design has no pinning, no eviction, no scarcity, and therefore no account of when `get` returns `not-found`. CLAUDE.md draws vocabulary from economics; this is where it belongs: a storage market or pinning layer that creates negative feedback on growth. Without it, hot data replicates and cold data vanishes, and `get` is best-effort by default.
- **Distributed garbage collection.** Reclaiming segments that no live root references, across a peer network, is distributed reference counting. Open.

## Emergent Behavior

The point of keeping `dao.jing` dumb is that the higher abstractions inherit the network for free. When an agent, eventually, executes a Datalog query in `dao.space`, the engine will simply ask its local materialization target for nodes; it will not know the index was fetched from a peer, validated and rehydrated into a local slab, or that the root advanced via a replicated stream. 

The interpretation remains pristine; the execution scales to the globe.

## Lineage: Two Shipping Paths, One Research Program

The stream-replication thesis is the part that ships, and it requires none of UDP, Eve, or Kademlia. Datomic's swappable storage backends (S3, DynamoDB, Cassandra, Postgres) are TCP-based and mainstream. Point-to-point stream replication ships today via `dao.stream.ws` and `dao.stream.link`; the multi-peer gossip/relay topology is proposed. The UDP DHT, zero-copy Eve slabs, and sortition consensus (the last now eliminated by single-writer streams) are a separate research program bolted onto that one good idea.

The philosophical commitment to UDP ("a datom is a datagram") is consistent with the project ethos. It should be made with open eyes: DRDS is reliable ordered streams rebuilt in user space, which is TCP's job. If the goal is datagram semantics with reliability, the tradeoff must be chosen deliberately, not arrived at by rebuilding TCP accidentally. CLAUDE.md: avoid cleverness.
