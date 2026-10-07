Completed-GMT: 2026-09-26 17:20:05 GMT
Completed-Local: 2026-09-27 00:20:05 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: pending (provider-generated)

## 1. The design

**Use one operation protocol over a bidirectional channel.** An interpreter publishes a mapping from a portable stream name to a local `dao.stream` handle. Another peer attaches to that name through a WebSocket or UDP channel. Either peer can publish, attach, or do both; these are roles in an interaction, not kinds of node.

A peer is named by a self-chosen stable identifier. A stream address is `{peer-id, stream-name, reachability-hints}`. The hints may contain WebSocket URLs, UDP addresses, or a relay route; they are ways to reach the peer, not stream identity. The publishing peer maps `stream-name` to a handle. The handle and its host-local identity never cross the wire. This distinction follows the contract’s separation of handle, descriptor, and logical-stream identity. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:37) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:521)

There are **two application messages**, encoded as portable maps using one agreed codec:

| Message | Fields | Meaning |
|---|---|---|
| `call` | request ID, stream name, operation, arguments | Invoke one of `descriptor`, `cursor`, `next`, `append!`, `close!` on the named attachment. `attach!` is a call naming the stream; `create!` remains host composition. |
| `result` | request ID, complete outcome map | Return exactly the operation’s outcome, including any cursor, value, gap recovery cursor, or optional keys. |

A channel carries complete encoded messages. WebSocket supplies message boundaries. UDP must supply fragmentation, reassembly, duplicate suppression, and retransmission for complete messages; otherwise it cannot claim to expose arbitrary portable stream values. Request IDs make retransmitted writes idempotent at the serving peer. Results are retained there long enough for retries, with an explicit finite retention policy. A relay forwards opaque channel messages and does not interpret stream operations.

**There is a blocking incompatibility with the current contract.** A remote `cursor` or `next` result cannot be known when its local call returns without waiting for network round trips. The contract requires every operation to return the outcome true *now*, forbids waiting, and says an operation is never a request whose answer appears later. Its current read outcomes have no “pending remote answer.” [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:98) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:141) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:153) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:538)

Therefore the two messages above are the **smallest faithful remote-operation protocol**, but its attached object cannot truthfully implement the present synchronous `dao.stream` handle surface on a nonblocking host. A contract decision is required: permit a separate remote-operation result stream, or add a pending/result-observation form to the handle contract. Calling that object an ordinary conforming handle today would conceal the incompatibility. No proxy may fabricate `empty`, `gap`, a cursor, or a successful remote append while the answer is unknown. Once received, source outcomes and source-minted cursors cross unchanged; `gap` remains source retention loss, never packet loss. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:475) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:538) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:671)

Leave out protocol-level client/server roles, RPC methods, stream copying, batching, subscriptions, service discovery, authentication, and relay selection. Those are separate compositions or conventions.

## 2. Invariant proof sketch

| Clause | Result |
|---|---|
| Any implementation can be mechanically exposed | The publisher invokes only the handle’s declared operations and forwards their complete outcomes. An operation the handle does not offer is not invented. The contract explicitly allows different declared surfaces. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:390) |
| WebSocket or UDP | Both can carry the same two encoded messages. UDP needs the reliability and large-value machinery stated above; a single datagram is insufficient. |
| Peer to peer; no privileged client or server | Every peer may publish and attach. WebSocket’s listener/dialer roles and a relay’s reachability role confer no authority in the stream protocol. |
| NAT traversal | With suitable mappings, full-cone, restricted, and port-restricted NATs may permit UDP hole punching when peers coordinate through a reachable third peer and send from the same sockets. Symmetric NAT, CGNAT, or UDP blocking may prevent direct UDP reachability; an accessible relay is then necessary. No protocol can guarantee a direct path through every NAT or firewall. |
| WebSocket reachability | A browser can dial WebSocket but cannot listen for inbound WebSocket connections. Two browser peers therefore need a reachable third peer to connect their channels. That peer can be any consenting interpreter running a relay convention, but some reachable infrastructure is unavoidable. |
| Original stream, not a copy | The publishing peer performs each operation on the original handle; the remote peer receives its outcome and cursor. This holds at the wire boundary. Under the current no-waiting contract, it cannot also be presented as an ordinary immediately answering remote handle. |

## 3. Toy example

Interpreter A wraps `"hello"` in any local stream implementation and exposes its handle as stream name `greeting`. Interpreter B dials A’s WebSocket address and sends `call(id=1, attach!, greeting)`. It then requests `cursor(:dao.stream/oldest)` and sends `next` with the returned cursor. A performs both operations on the wrapped stream and returns their complete outcome maps; B observes `"hello"` from the `next` outcome. The protocol knows nothing about strings. B must observe these remote results asynchronously until the contract incompatibility above is resolved. The defined anchors and successor cursors remain those of A’s stream. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:475) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:517)

## 4. Client/server as convention

Peer A exposes a writable `requests` stream and a readable `responses` stream. Peer B appends `{id: 42, question: ...}` to `requests` and reads `responses` with its own cursor. An interpreter on A reads requests and appends `{id: 42, answer: ...}`. The ID correlation and question handling belong to this convention. Either peer can host the pair, and both streams use the same primitive.

## 5. Fate of existing mechanisms

| Path | Fate | Why |
|---|---|---|
| `dao.stream.ws` and its host adapters | **Subsumed** | Keep host socket seams where useful; replace its accept/value transport vocabulary with the common operation messages. The current adapters cover JVM, Node, browser, Dart, and another host variant; the visible JVM, Node, browser, and Dart paths already split socket mechanics from stream logic. [ws.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/ws.cljc:297) [jvm.clj](/Users/sto/workspace/datomworld/src/clj/dao/stream/ws/jvm.clj:347) [node.cljs](/Users/sto/workspace/datomworld/src/cljs/dao/stream/ws/node.cljs:201) [browser.cljs](/Users/sto/workspace/datomworld/src/cljs/dao/stream/ws/browser.cljs:73) [dart.cljd](/Users/sto/workspace/datomworld/src/cljd/dao/stream/ws/dart.cljd:258) |
| `serving` | **Retired** | Its WebSocket-specific offer, control, traffic, and forwarding composition is replaced by a name-to-handle table and operation dispatch. [serving.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/serving.cljc:210) |
| `forward` | **Convention-over** | Stream-to-stream forwarding remains an interpreter behavior, separate from exposing a handle. [forward.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/forward.cljc:67) |
| `rpc`, `rpc.ws` | **Convention-over** | Correlated request/response behavior can use exposed streams; the WebSocket-specific decoder becomes unnecessary. [rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:74) [rpc/ws.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc/ws.cljc:66) |
| `apply` | **Convention-over** | Its request/response pair and handler dispatch remain optional application behavior, not serve’s foundation. [apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:123) [apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:187) |
| `yin.repl.serve/connect/driver`, `daostream:ws://` | **Convention-over** | REPL request handling remains a consumer; its transport-specific URL and driver wiring should use stream addresses. [connect.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/connect.cljc:233) [driver.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/driver.cljc:55) [serve.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/serve.cljc:781) |
| `dao.jing.remote`, `remote.step/async` | **Convention-over** | Content requests remain domain behavior over exposed request/response streams. [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/remote.cljc:730) [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/remote.cljc:907) |
| UDP DHT | **Unrelated** | It is a separate JVM UDP Kademlia network interface; it neither supplies generic served-stream semantics nor needs replacement for this design. [node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:2) [node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:299) |

## 6. Comparison

The previous specification defines open/opened/disclaim/close/request/response/ping/pong frames, sessions, batching, held reads, a meeting convention, and detailed channel composition. [dao.stream.serve.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md:96) [dao.stream.serve.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md:128) [dao.stream.serve.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md:314) [dao.stream.serve.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md:350) This design reduces the application wire vocabulary to `call` and `result`, uses request IDs for retry, and leaves discovery and relay behavior to compositions. It retains the previous spec’s essential concern that UDP fragmentation is required before claiming arbitrary content support and that a source `gap` must stay distinct from channel loss. [dao.stream.serve.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md:605) [dao.stream.serve.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md:633)

## 7. Owner-visible items only

The strict combination “an ordinary `dao.stream` handle across a channel,” **unchanged immediate outcomes**, and **no operation waits** is technically impossible for remote reads when the answer is not already local. The contract itself identifies cross-host cursor serialization as unsettled. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:901) The owner must choose a contract change or accept an explicitly asynchronous remote-operation interface; the design must not claim both.

NAT traversal means direct connection where possible and relay fallback where necessary, not guaranteed direct peer connectivity. Browser-to-browser WebSocket communication needs a reachable third peer. Before authentication and encryption are specified, a relay can observe or alter plaintext traffic; peer IDs alone do not authenticate peers.
