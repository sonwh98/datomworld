Coding-Agent: codex
Session-ID: 01a0f10a-6dcd-7af1-b670-78f74255321a
Model: gpt-6-sol (pinned)

Completed-GMT: 2026-09-30 09:07:00 GMT
Completed-Local: 2026-09-30 16:07:00 Asia/Ho_Chi_Minh

## R1. Raw datagram layer

**I change my prior D1 position.** The owner has chosen a raw datagram boundary, so the DHT must not consume `dao.stream.udp`’s decoded, fragmented value channel.

Add `dao.stream.udp.raw` beneath the existing `dao.stream.udp`. A bound raw port exposes a `dao.stream` **writer handle**. Its `append!` value is plain data:

```clojure
{:dao.stream.udp.raw/to    {:host "198.51.100.7" :port 4040}
 :dao.stream.udp.raw/bytes "<padded standard Base64>"}
```

Base64 represents the exact datagram bytes across CLJ, CLJS, and CLJD without placing host byte-array types in stream values. The raw layer decodes it, checks destination and a **1200-byte decoded datagram limit**, and makes one addressed send. Malformed address, Base64, or oversize yields `:dao.stream/invalid-value`; a closed port yields `:dao.stream/closed`; socket failure yields `:dao.stream/transport-error`. `:dao.stream/ok` means local send acceptance, never delivery, as the stream writing contract requires ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:588)). No raw-layer retry, ordering, reassembly, or content decoding occurs.

The socket adapter deposits each received datagram onto a caller-supplied traffic stream as `{:dao.stream.udp.raw/source {:host h :port p}, :dao.stream.udp.raw/bytes b64}`. It copies bytes before deposit and takes the source from the socket, not the payload. The socket itself has **no reader surface** because it retains nothing; consumers hold cursors on the supplied traffic stream. A bounded ring may report `:dao.stream/gap`, which each DHT or value-channel interpreter must treat as lost datagrams. This follows the explicit boundary and retention rules ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:405), [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:570)). The raw writer’s descriptor names that bound port and its identity; its destination remains in each append value. Binding is a host-composed creation operation, and closing closes the port. Do not imply that attaching to a remote port grants a remote reader surface.

Refactor the existing `dao.stream.udp` value channel to read raw traffic with an owned cursor and send encoded fragment datagrams through the raw writer. Its public value-channel descriptor, `send-to!`, projection, and `dao.stream.remote` behavior stay the same. Today `dao.stream.udp/receive!` accepts raw bytes directly and `make-port` takes a host `:send!` function ([udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:202), [udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:373)); those become composition internals. Existing JVM, Node, and Dart socket seams already receive source addresses and raw bytes ([jvm.clj](/Users/sto/workspace/datomworld/src/clj/dao/stream/udp/jvm.clj:12), [node.cljs](/Users/sto/workspace/datomworld/src/cljs/dao/stream/udp/node.cljs:14), [dart.cljd](/Users/sto/workspace/datomworld/src/cljd/dao/stream/udp/dart.cljd:13)). Their host callbacks may only deposit an event and return, per the host-boundary invariant ([datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:69)).

## R2. DHT directly on raw datagrams

**I also change my prior D2 position:** canonical CBOR is no longer forced by the value channel. Keep the present Transit-JSON encoding for small DHT RPC maps in the first migration, and version the DHT wire before adding chunk messages. The node currently sends one Transit-JSON map per datagram ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:1), [node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:57)). A later CBOR change needs its own interop reason and versioned fleet plan; content payloads remain canonical CBOR regardless of the DHT envelope ([dao.jing.cbor.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.cbor.md:462)).

The DHT interpreter reads raw traffic, decodes only DHT wire values, and emits replies by appending addressed raw datagrams. Small `ping`, `find-node`, `store-content`, `fetch-content`, and reply messages retain RPC IDs. For messages exceeding one datagram, define a **DHT-owned chunk protocol** over raw datagrams: version, sender request ID, request/reply direction, part index, total parts, and chunk bytes. Correlate partials by observed source address and port plus those fields. A shared, transport-neutral fragmentation *utility* is acceptable, but it must not import `dao.stream.udp`’s private envelope or its `dao.stream.remote/id` meaning ([udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:151)). Bound encoded message size, number and bytes of partials per source and globally, and number of parts. Refuse oversize before the first send; duplicate or inconsistent parts do not grow state. Evict incomplete partials under a declared policy; retry the complete RPC after timeout. Verify the complete content against its segment address before storing or caching, as the current DHT does ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:167)).

Hardening belongs to the DHT **at this raw boundary**. A small `ping` can obtain a time-limited cookie bound to the observed source IP and port. Require it before a fragmented reply or allocating substantial incoming reassembly state; put a verifiable token on each incoming chunk so an attacker cannot force assembly before validation. Cap unverified replies to the request size or a small fixed budget. Match RPC replies to the expected observed source, limit work and storage per source and globally, and do not add a claimed `:from` to routing before validation. The current receiver observes `:from` before processing and matches replies only by RPC ID ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:171)). These rules must be specified with chunking; non-loopback exposure waits for their implementation.

## R3. DHT’s stream-shaped API and Jing adapter

Expose two composition-supplied streams: a **request writer** and an **outcome reader**. Request values carry a caller-minted correlation ID and one operation:

```clojure
{:dao.jing.dht/id id :dao.jing.dht/op :put
 :dao.jing.dht/address address :dao.jing.dht/bytes b64}
{:dao.jing.dht/id id :dao.jing.dht/op :get
 :dao.jing.dht/address address}
{:dao.jing.dht/id id :dao.jing.dht/op :find
 :dao.jing.dht/target target-id}
```

The caller’s `append!` outcome says only that the request stream accepted the value. A caller-stepped DHT interpreter reads requests and raw traffic, owns explicit pending-RPC and cursor state, and appends exactly one terminal outcome per accepted request ID: `:stored-locally` with `:inserted|:present`, `:found` with verified bytes, `:not-found`, `:refused`, `:timeout`, or `:error`. Replication progress is a separate event stream keyed by address; it cannot retroactively change the local put verdict. Results use DHT-qualified *values*, while stream operations continue to use the exhaustive `:dao.stream/outcome` vocabulary ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:96)). Duplicate request IDs and completion retention need explicit bounded policy; no callbacks or hidden worker are required.

Keep the existing synchronous Jing byte-store handle as a **local compatibility adapter**. Its `put` validates and writes to the local backend, then emits an address-only replication intent; `:inserted`/`:present` means the local backend verdict. Its `get` answers local verified content immediately. A remote miss is requested and hydrated through the DHT streams, then the caller retries the local read. A synchronous, portable `get` cannot wait for a future network answer: DaoStream explicitly says operations do not wait ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:69)). The current DHT handle *does* synchronously fetch on a miss ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:227)); that behavior must be isolated as a JVM blocking facade, or callers must migrate to staged hydration. Do not silently return `not-found` for an outstanding remote fetch. `yin.repl.index` can continue its synchronous local write and manifest readback ([index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:232)); remote query needs a hydration step before `q`. The repository already distinguishes a stepped content client from its JVM blocking driver ([content.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/content.cljc:18)).

The prior D3, D4, D7, D8, and D9 decisions stand: volatile best-effort replication intent with an honest local acknowledgement; a solo net with no socket; latest-root discovery outside this DHT epic, with local HEAD for owner restart; lookup and resource-bound repairs here; `mem` default and explicit network/publication choices. The raw-layer decision changes none of those contracts.

## R4. Revised slices

| Slice | Work | Acceptance |
|---|---|---|
| **S0: Contract freeze** | Specify raw values/descriptors, DHT request/result values, wire version, chunk and abuse budgets, local-versus-remote Jing behavior. | The design states every `append!` outcome and every asynchronous completion; no claim equates send acceptance with delivery. |
| **S1: Raw datagram stream** | Build `dao.stream.udp.raw` over the existing host seams; make the value channel an interpreter above it. | JVM, Node, and Dart raw streams round-trip exact binary datagrams and observed source addresses; oversize is `invalid-value`; loss and ring gaps are observable; existing value-channel and `dao.stream.remote` tests still pass. |
| **S2: DHT stream core and solo mode** | Request/outcome streams, caller-stepped interpreter, local Jing adapter, volatile address-only replication intent. | Put has no lookup or peer wait; outcomes correlate under concurrent requests; solo mode opens no socket or replication queue; local file-backed acknowledgements survive restart. |
| **S3: DHT raw wire and bounded chunk transfer** | Connect DHT interpreter directly to raw traffic/writer; add DHT-owned chunking and whole-content verification. | Multi-node transfer of index-sized segments succeeds with reordering/loss and explicit retries; oversize and malformed chunks stay within all declared bounds. Only loopback exposure is allowed at this stage. |
| **S4: Hardening and lookup repair** | Cookies, source matching, rate/resource limits, dead-peer replacement. | Spoofed requests cannot trigger fragmented amplification or substantial reassembly; a live next-nearest peer remains fetchable when closer peers fail. |
| **S5: REPL integration** | After the durable-store epic supplies store-spec, HEAD, locking, and recovery, add explicit DHT choice, public-data gate, and remote hydration/query entry point requiring a supplied manifest address. | Local-only restart works without a socket; separate processes use separate locked directories; remote query works from a supplied root; source/index content is neither published nor served without explicit public opt-in. |

S1 can run alongside S2 **only after S0**, with separate file and test ownership; S3 depends on both. S4’s design is part of S0, and its implementation follows S3 before exposure. S5 is externally dependent on the durable-store epic. No earlier slice touches `yin/repl/*`.

## R5. Items for the owner

1. **Confirm the publication policy:** whether explicit public-index opt-in may publish REPL code before ShiBi, or whether all REPL code publication is prohibited until ShiBi. Either choice must be enforced at both origin and serving paths.
2. **Confirm the remote-read scope:** local HEAD covers restart, while a reader given only a peer address still cannot discover the latest manifest. Automatic latest-root discovery would require a separate root-fact design or an added epic slice.
3. **Choose the synchronous compatibility boundary:** permit a JVM-only blocking DHT `get` facade, or require all remote reads to use staged hydration. The stream-shaped API itself remains nonblocking on every host.

The owner’s raw-layer decision settles the previous D1/D2 transport dependency: the DHT owns its datagram protocol, while both the raw port and the DHT expose `dao.stream` interfaces.
