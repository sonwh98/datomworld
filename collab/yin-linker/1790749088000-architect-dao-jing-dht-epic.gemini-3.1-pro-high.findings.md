Coding-Agent: agy
Session-ID: c1020f8c-3056-4129-a680-cdc1337b844a
Model: gemini-3.1-pro-high

Completed-GMT: 2026-09-30 06:20:29 GMT
Completed-Local: 2026-09-30 13:20:29 +0700

Here is the architectural design requested:

1. Fragmentation/reassembly (DRDS or other):
- **Implementation gap**: `src/cljc/dao/jing/dht/node.cljc:78-81` (`send-datagram!` throws if payload exceeds `max-datagram`), confirmed by `docs/design/dao.jing.dht.md:157` ("Anything beyond the 1200-byte datagram budget is refused").
- **Where it lives**: `dao.jing.dht.node` should transition from Transit-JSON to the canonical CBOR codec, reusing the `fragment-envelope` already defined in `src/cljc/dao/stream/udp.cljc:151` (`fragment-envelope`).
- **Integrity**: Per-fragment hashes are unnecessary. Hash verification occurs strictly on the reassembled whole in `src/cljc/dao/jing/dht.cljc:182` (`accept-bytes`).
- **Limits & Loss/Retry**: Receiver enforces `max-message-bytes` during reassembly (`udp.cljc:332`). Missing fragments cause the reassembly to time out; the client owns the request timeout and retries the entire segment.

2. Replication lifecycle:
- **Architectural defect**: `src/cljc/dao/jing/dht.cljc:216` (`make-put` uses `run! deref (mapv #(future ...) ...)`, blocking the caller and occupying JVM executors until all peer attempts finish).
- **Implementation gap**: Asynchronous outbox.
- **Contract**: An acknowledgement from `put` means "durably stored locally and queued for replication".
- **State**: The DHT handle `create-content-dht` (`dht.cljc:312`) must embed an explicit `:outbox` (a `dao.stream.memory-log`) and a runner state, with no hidden callbacks.
- **Blocking avoidance**: A REPL round appends the target address to the outbox and immediately receives `:inserted`, allowing the round to proceed while the runner drains the outbox to the network.

3. Non-JVM hosts:
- **Implementation gap**: `src/cljc/dao/jing/dht/node.cljc:14` states "JVM only today (java.net.DatagramSocket); no browser/Node peer".
- **Design**: Direct UDP transport implementing `IDhtNet` must be written for CLJD (via `dart:io` `RawDatagramSocket`) and Node/CLJS (via `dgram`).
- **Deferred work**: Browser CLJS support. Browsers cannot use raw UDP and will require a `dao.stream.remote` relay to satisfy `IDhtNet`, consistent with the P2P no-privileged-node invariant.

4. Hardening:
- **Architectural defect / Implementation gap**: `src/cljc/dao/jing/dht/node.cljc:22` ("No address validation or rate limiting yet") and `docs/design/dao.jing.dht.md:161` (no returnability validation).
- **Required before on-by-default**: Returnability cookies (a challenge-response mechanism to verify the `:from` IP before accepting large `store-content` payloads) to prevent UDP amplification attacks. Token-bucket rate limiting per peer must be added to the receiver loop (`node.cljc:144`).

5. Latest-root discovery:
- **Architectural rule**: `docs/design/dao.jing.dht.md:11` explicitly dictates "The DHT answers one question: given a content address, where on the network are the bytes? It does nothing else. There are no roots...".
- **Deferred work**: Root discovery strictly belongs OUTSIDE the DHT. A reader learns the latest manifest address via a `dao.stream` of root facts over `dao.stream.remote` rendezvous, not through mutable Kademlia records.

6. Bootstrap/config:
- **Implementation gap**: `src/cljc/dao/jing/dht/node.cljc:304` (`:bootstrap` seq exists, but relies on direct IP reachability).
- **Deferred work**: NAT traversal, hole punching, and relay (specified in `docs/design/dao.stream.remote.md`).
- **Offline behaviour**: A "local-only DHT" is instantiated with an empty `:bootstrap` list. It routes exclusively to its `dao.jing.file` or `mem` local backend and queues or drops outbound replication.

7. Integration with yin.repl:
- **Implementation gap**: `src/cljc/yin/repl/index.cljc:148` (`make-indexer`) currently expects a generic `content-store`. The startup specification mechanism (`1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md:10`) must be extended to accept `{:type :dht :dir ... :bootstrap [...]}` or a `dht:<dir>` string.
- **Published**: The covered datom index and B-tree nodes are appended to the intake stream and materialized by the DHT's local store, which then asynchronously replicates them.
- **Query entry point**: `dao.space.query/q` (`docs/design/yin.repl.dao.space-index.md:55`) resolves nodes. A local miss cascades to the `IDhtNet` grid fetch, which verifies the payload and caches it locally.

8. Slicing:
- **Slice 1**: CBOR codec transition and DRDS fragmentation in `dao.jing.dht.node`.
- **Slice 2**: Asynchronous outbox replication in `dao.jing.dht`.
- **Slice 3**: Non-JVM UDP node implementations (CLJD, Node/CLJS).
- **Slice 4**: Returnability hardening and rate limits.
- **Slice 5**: `yin.repl` startup integration (adding the `:dht` store spec).
- **Concurrency**: Slices 1-4 do not touch `yin/repl/*` and can run concurrently with the in-flight `yin.repl` work. Slice 5 is the dependency integration.

9. OWNER decisions:
- **Default store behaviour**: Default remains `mem`. `dht:` must be explicitly requested.
- **Network on/off**: Network is OFF by default (local-only mode, empty bootstrap) to prevent unwanted broadcast/listening.
- **Access policy**: The DHT is an open network. No sensitive REPL data should be published until ShiBi authorization exists.

I have also captured this architecture design in an implementation plan artifact for your review as per the `/plan` instruction. Let me know if you would like me to proceed with implementing any of the slices!
The task outlined in `collab/1790749088000-architect-dao-jing-dht-epic.prompt.md` is explicitly defined as a read-only architectural design task:

> "Perform a read-only architecture design. Do not edit files. You are headless; your final response is the deliverable."

As such, the "execution" of this specific prompt is complete with the delivery of the architecture design and the implementation plan artifact. 

If you would like me to override the "Do not edit files" directive from the prompt and proceed with implementing the slices (e.g., starting with Slice 1: Codec & Fragmentation), please let me know! Otherwise, this architectural pass is fully complete.

