# DaoJing DHT: Content-Addressed Segment Distribution

## Overview

`dao.jing` is deliberately dumb: it observes an explicit intake pool of
`dao.stream` values and materializes opaque payloads into content-addressed
storage (see `dao.jing.md`). This document specifies the distributed
content-store backend for that boundary: a Kademlia-style Distributed Hash
Table that routes only strict content addresses.

The DHT answers one question: given a content address, where on the network
are the bytes? It does nothing else. There are no roots, no CAS records, no
deletes, no source identity, no stream replication, and no consensus. Content
addressing stays in `dao.jing` (`segment-key`); the network layer only
distributes the payloads those addresses name.

## Scope: strict content addresses only

The DHT routes only `:segment/sha256-<64 hex>` content addresses. Its routing
target is derived from the address's content hash alone; a non-segment address
is rejected before any network action. Because the payload hashes to the
address, a fetching node can verify what a peer hands it against the address
it asked for — integrity against untrusted peers for the cost of one hash.

Nothing at this layer knows or records which stream, agent, or peer carried a
payload. Distribution is by content, never by provenance.

## Status

| Component | Status |
|---|---|
| `dao.jing.dht` store handle (`create-content-dht`) | Implemented |
| `IDhtNet` transport protocol | Implemented |
| UDP Kademlia peer (`dao.jing.dht.node`, `create-node` / `create-content-dht-udp`) | Implemented, JVM only |
| Local-first durable write, best-effort replication | Implemented |
| Hash-verified fetch with local caching | Implemented |

## The store handle

`create-content-dht` wraps an `IDhtNet` transport and a local content handle
(e.g. `dao.jing.mem/create-content-mem`) into a plain-data content-store
handle map:

```clojure
{:net net, :local local, :closed-atom a,
 :put-content-fn f, :get-content-fn g, :close-fn c}
```

It is consumed by `dao.jing/materialize!`, `dao.jing/get`, and
`dao.jing/close!` exactly like any other backend. The local handle is this
node's own copy of the content it serves and caches; an acknowledged write
always lands locally first (see *Writes* below).

`create-content-dht-udp` (in `dao.jing.dht.node`) is the convenience
constructor: open a UDP node and wrap it as a DHT content-store handle. The
node and the store share `local`, so the peer serves the same bytes it reads.

## Writes: local durable insert, then best-effort replication

`put` validates the address/payload pair, writes the local backend first, and
only after the local write durably succeeds replicates to the `k` nearest
non-self peers:

1. **Local durable insert.** The payload is inserted into the local content
   handle (insert-if-absent). The backend verdict must be an explicit
   `:inserted` or `:present`; a local collision throws. The address is never
   acknowledged before this write lands.
2. **Best-effort replication.** The `k` nearest non-self peers to the content
   hash are located by an iterative lookup, and `store-content!` is sent to
   each. Replication is best-effort: a transport timeout, refusal, or fault
   simply means that peer is not replicated to. Nothing in the local
   acknowledgement depends on the replication outcome.

The acknowledgement's **value** is independent of replication, but its
**timing** is not. The current content handle completes the bounded replication
attempts before returning the local `:inserted` or `:present` verdict. On the
JVM the peer calls run in parallel and the caller waits for all of them, so the
network portion is bounded by the slowest peer attempt; other hosts run the
attempts synchronously in peer order. An asynchronous outbox would be a
different lifecycle and causality contract, and is not implicit in
"best-effort."

Because payloads are content-addressed, replication is idempotent and
convergent: every copy of a value is byte-identical to every other, so no
ordering or reconciliation is required between the local copy and remote
copies.

## Reads: local first, hash-verified fetch, cached

`get` reads the local backend first. On a hit it returns the value directly,
no network. On a miss it:

1. runs the iterative lookup to converge on the `k` nearest known peers to
   the content hash;
2. asks candidates with `fetch-content` until one answers; every fetch is
   bounded by the transport's timeout and treated as unreachable when it
   expires;
3. verifies the fetched payload by hashing it against the requested address
   (a mismatch is discarded and the next candidate tried);
4. caches the verified value through the local backend (`materialize!`), so a
   second read of the same segment is a local hit; and
5. returns not-found when no candidate produced a verifiable value.

The verification step is what makes `get` safe against a peer that claims to
hold content it does not: the address is the digest of the payload, so a
lying peer fails the check without ceremony.

## The transport protocol (`IDhtNet`)

`IDhtNet` is the per-peer RPC surface the store requires of a transport. It
has exactly six methods:

- `self-peer [net]` — this node's own `{:id :host :port}` peer map.
- `known-peers [net target-id n]` — the `n` locally-known peers nearest
  `target-id`, nearest first; no IO.
- `find-closer [net peer target-id]` — ask `peer` for the peers it knows
  nearest `target-id`; nil when unreachable.
- `store-content! [net peer address payload]` — ask `peer` to hold content;
  a boolean acknowledgement, false/nil when the peer is unreachable or
  refuses. Implementations must own bounded transport timeouts.
- `fetch-content [net peer address]` — ask `peer` for content;
  `{:found? bool :value v}` when reachable, nil when unreachable.
  Implementations must own bounded transport timeouts.
- `close-net! [net]` — release the transport's local resources (socket,
  threads).

The store's iterative lookup (`dao.jing.dht/lookup`) uses only these methods:
it converges on the `k` nearest known peers to a target id, deduplicating
peer ids so a peer is never asked twice in one lookup.

## The UDP wire (`dao.jing.dht.node`)

The reference transport is a UDP Kademlia peer (`create-node`). Its wire ops
are exactly five: `:ping`, `:find-node`, `:store-content`, `:fetch-content`,
and `:reply`. One Transit-JSON message map per datagram: requests carry
`{:op ... :rpc n :from peer}`; replies carry
`{:op :reply :rpc n :from peer :value ...}`.

- **Request-response over fire-and-forget UDP.** Reliability is owned by the
  client as per-attempt timeouts and retries (`:timeout-ms` default 500,
  `:tries` default 3); a peer that stays unreachable is answered as nil by the
  `IDhtNet` contract, never thrown.
- **Routing knowledge accretes from traffic.** Every received `:from` is
  observed into the local k-bucket table, so peers learn each other from the
  messages they exchange, not from a separate discovery service.
- **Peers are untrusted.** An incoming `:store-content` re-verifies the
  content address against the payload before writing; a mismatch is refused.
- **Replies go to the datagram's source address**, not the claimed `:from`.

## Operational limits (current)

- **JVM only.** The UDP node uses `java.net.DatagramSocket`; there is no
  browser or Node peer. Browsers cannot send or receive raw UDP — the closest
  reachable primitives (WebRTC DataChannel, WebTransport) are outside this
  transport. The file stays `.cljc` to leave room for non-JVM branches; on
  other platforms it defines nothing.
- **One datagram per message.** Anything beyond the 1200-byte datagram budget
  is refused on send and dropped on reply, never torn. Segments that exceed
  the budget stay local-only until fragmentation (DRDS) exists: store degrades
  to best-effort, fetch times out.
- **No peer-returnability validation or rate limiting yet.** Incoming
  `:store-content` is content-address verified, but is not guarded by
  returnability cookies or per-peer limits. UDP amplification hardening is
  open.
- **NAT traversal is unaddressed.** A global peer-to-peer grid behind NAT
  requires hole-punching, relay/TURN, and bootstrap discovery. Until then the
  DHT works where peers can reach each other's UDP sockets directly.
- **Storage economics / GC.** Unbounded caching is unbounded storage growth.
  There is no pinning, eviction, or reclamation policy, so `get` is best-effort
  by default and superseded content accumulates.
- **Replication consumes caller time and JVM executor capacity.** A JVM put
  launches up to `k` standard Clojure futures and joins them. Concurrent puts
  can therefore occupy up to `k` executor tasks apiece until the transport's
  bounded attempts finish. Moving this work off the put path requires an
  explicit outbox stream and owned runner lifecycle, not an implicit worker.
- **Dead peers can occupy the closest-`k` shortlist.** Lookup returns the
  nearest known peers, including peers that time out. Fetch and replication
  skip failed calls, but lookup does not yet replace an unreachable shortlist
  member with the next-nearest reachable peer. Pruning plus bounded expansion
  until `k` peers respond (or candidates are exhausted) remains open.

## Relationship to the intake-pool observer

The DHT is a content-store backend, not a stream transport. The intake-pool
observer (`dao.jing/observer-state` / `observe-step!`) materializes whatever
the intake streams carry; whether the content store behind it is a DHT handle
is invisible to the observer. Conversely, the DHT replicates content, never
streams: there is no ordered, push-based replication of a writer's log through
this layer and no single-writer authority to coordinate — each node simply
stores and fetches content-addressed payloads.

## Lineage

The pull-based, content-addressed model is Datomic's storage architecture —
immutable segments fetched by digest — placed over a Kademlia grid. The
verification-by-hash property is the DHT's own trick: because the key is the
digest of the value, distribution can be adversarial without a trusted
directory.
