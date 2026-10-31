Completed-GMT: 2026-09-26 17:24:09 GMT
Completed-Local: 2026-09-27 00:24:09 +07
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# dao.stream.serve, from scratch: the mirror

## 1. The design

**One sentence.** A peer that holds a handle answers the handle's own operations as data over any two-ended channel; a peer that holds the far end gets a handle whose operations are those same questions asked across the channel, with the source's outcome maps returned verbatim.

Four concepts. Nothing else.

| Concept | What it is | New machinery |
|---|---|---|
| **Channel** | Two ordinary handles: a writer whose `append!` sends toward one other peer, and a reader positioned on what that peer sent. Exactly two ends. | None. A WebSocket attachment is already a writer plus a deposit medium (`src/cljc/dao/stream/ws.cljc:189-229`, `:148-174`). Two ring buffers in one process are a channel. Two streams served by a third peer are a channel (see §2 relay). UDP is the same shape with a datagram adapter. |
| **Request** | A plain map: `{:dao.stream/identity id :dao.stream/op op :dao.stream/args [...] :dao.stream/id n}`. `op` is one of `:dao.stream/descriptor :dao.stream/cursor :dao.stream/next :dao.stream/append!`. `n` is minted by the asker, unique per channel reader it holds. | One map shape. |
| **Outcome** | The contract's outcome map for that op, **verbatim**, plus `:dao.stream/id n` and `:dao.stream/identity id`. Legal because result maps are open (`docs/design/dao.stream.md:109-112`). | Zero: it is the contract's own value. |
| **Table** | Composition data on the holding peer: `{identity {:handle h :surface #{...}}}`. Plain map, host-composed, no registry (`dao.stream.md:83-84`, `:351-385`). | One map. |

Two pieces of code, both pure steps, both run by any peer, usually both at once on the same channel:

**Mirror step (the holding side).** `(mirror-step table chan-reader cursor chan-writer) -> cursor'`. For each request read: look up `identity` in the table; if absent, answer `{:dao.stream/outcome :dao.stream/not-found}`; if the op is not in the declared surface, answer `not-found` too (nothing there to call); else apply the op to `h` with `args` and append the outcome map, plus id and identity, to `chan-writer`. It holds **no per-peer state**: reads are positioned by the cursor in the request, `descriptor`/`cursor` are projections, `append!` correlates by the payload (OD-2, `dao.stream.md:883-886`). One optimization, optional and ignorable: a `next` request may carry `:dao.stream/budget k`; the mirror may follow successors up to `k` times and put the further outcome maps, in order, under `:dao.stream/more`; the first outcome stays the map's own. One piggyback: every answer carries `:dao.stream/oldest` and `:dao.stream/newest`, the source's current anchor cursors, so the far side never needs a round trip to mint the two contract anchors.

**Reflection (the far side).** `attach!` on a descriptor `{:dao.stream/type :dao.stream/remote :dao.stream/identity id :dao.stream/channel <channel descriptor>}` returns `ok` at once with a **reflection handle** and a `:dao.stream/attachment` (a local random value), as the contract allows for a remote confirmation (`dao.stream.md:323`). The host-composed attach closure keeps one **link** per channel: the channel's reader cursor, a map of outstanding request ids, a map of answered outcomes, and an optional event writer. Every operation on any reflection first **drains**: it reads the channel reader to `blocked` and files each answer by id (this is where the asynchrony goes, `dao.stream.md:455-471`; no driver step is needed because the caller's own polling is the cadence). Then:

| Op on reflection | Behaviour |
|---|---|
| `descriptor` | Local: returns the remote descriptor it was attached with and `id`. Same identity as the source's own descriptor; different reachability, as the contract intends (`dao.stream.md:262-267`). |
| `cursor anchor` | For `:oldest`/`:newest`: return the latest piggybacked cursor as `ok`; before any answer has arrived, `transport-error` with `:dao.stream/retry? true` (OD-1 key). Other anchors: issue a `cursor` request; until answered, the same `transport-error`; once answered, the source's map verbatim, `invalid-anchor` included. |
| `next c` | If an answered outcome for `c` is filed, return it and drop it (installing any `:more` outcomes at their cursors). Else if no request for `c` is outstanding, send one, return `blocked`. Else `blocked`. |
| `append! v` | Send the request; return the **channel writer's** outcome (`ok` = accepted on the outbound path, exactly `dao.stream.md:570-576`). The source's eventual outcome is appended to the link's event writer if one was composed, else dropped. |
| `close!` | Local only: forget this reflection's outstanding ids. Nothing crosses the wire, because the mirror holds nothing to close. Owner close is never remote. |

On `attach!` the link sends one `descriptor` request for `id`; its answer (`ok` with anchors, or `not-found`) is the deferred confirmation, filed as the reflection's status and appended to the event writer. After `not-found`, every op on that reflection answers `transport-error` with `:dao.stream/reason :dao.stream/not-found`.

**Loss.** The reflection never uses a clock. A link carries one policy datum, `:resend-after k`: an outstanding read request is re-sent when the caller has asked `k` more times and it is still unanswered. `k` is infinite on an ordered reliable channel and small on UDP. `descriptor`, `cursor`, `next` are idempotent, so duplicates are harmless; `append!` is never re-sent (OD-2). Late or duplicate answers whose id is not outstanding are dropped.

**Why the served stream is the original.** Cursors, anchors, `gap` recovery cursors, `blocked`, `end`, `closed`: every one is the source's own map, produced by the source's own handle, forwarded byte for byte. The reflection mints nothing. A cursor obtained through a reflection works on the source's local handle and vice versa. A `gap` through a reflection is the source's eviction, never a channel artifact. There is one precondition, the same as OD-3's second decision: cursors are plain data in the codec's portable domain; both existing transports already are (`src/cljc/dao/stream/ringbuffer.cljc:60-68`).

**Wire format.** Whatever the channel already carries: Transit-JSON text or the CBOR binary profile on WebSocket (`src/cljc/dao/stream/ws.cljc:323-342`; codecs at `transit.cljc:136-145`, `cbor.cljc:111-119`), one CBOR value per datagram on UDP. Two logical shapes: a request, and a contract outcome map. No open, opened, disclaim, close, ping, pong, session or hold frames.

**Identity and addressing.** A stream is named by `:dao.stream/identity`, which the contract already makes plain data, stable, and equal through every handle (`dao.stream.md:85-87`). A peer has **no protocol-level name**: a peer is whatever sits at the other end of a channel. Names for peers appear only in conventions (§4) as self-minted random values.

**Deliberately left out.** Sessions and per-attachment server state; a `next` push or held-read mode; retransmission windows and acks; a wire `close!`; ping/pong; peer ids; authorization; fragmentation; a readiness extension. Each is either a convention peers may run over the primitive, or a channel-internal concern, or absent from the contract itself.

## 2. Invariant proof sketch

| Clause | How it is met, or where it cannot be |
|---|---|
| **Any implementation of dao.stream can be mechanically exposed** | The mirror step is derived from the handle's declared surface and the protocol dispatch alone (`src/cljc/dao/stream.cljc:144-187`). It never inspects the handle's transport or its values. A table entry is the entire act of exposing. |
| **via WebSocket or UDP** | Both are channels: WebSocket exists (writer + deposit medium, five host adapters). UDP needs one adapter of the same shape: socket in, datagrams deposited as events carrying the source address; writer out. One value per datagram, 1200-byte budget as the DHT already chose (`src/cljc/dao/jing/dht/node.cljc:44-50`). **Narrowing:** a value that does not fit is `invalid-value` from the channel writer or, from the mirror, a `transport-error` with `:dao.stream/reason :oversize` in place of that element without advancing the cursor. Large values travel as `dao.jing` addresses, which is the contract's own granularity rule (`dao.stream.md:1096-1098`). Fragmentation can be added inside the UDP channel later and is invisible to this layer. Until then, UDP carries values under one datagram. |
| **communicate p2p; no server or client; no privileged node** | Both ends of every channel run mirror and reflection. A request carries no role. The mirror holds no state about who asks, so there is nothing an "accepting" end can do that a "dialing" end cannot. Which end dialed is a fact about TCP or NAT, forgotten once the channel exists. |
| **client/server is a convention interpreters implement** | §4. The protocol has no frame for it. |
| **traverse a NAT** | See below. |
| **toy: a string exposed on a websocket** | §3. |

**NAT, by case.** A channel needs one end that can receive an unsolicited packet, or a coordinated simultaneous send. The protocol cannot change that; it can only make the workaround a convention.

| Situation | Outcome |
|---|---|
| One side has a reachable address (public, port-forwarded, or full-cone NAT) | Direct: the other side dials. WebSocket or UDP. |
| Restricted-cone or port-restricted NAT on one or both sides, UDP allowed | Hole punch, as a convention: both peers append `{:meet/here me}` to a third peer M's served meeting stream over UDP; M's UDP adapter deposits each datagram's **source address** as an event field, and M's interpreter republishes it as `{:meet/seen peer addr}` on its served board. Both peers read the board and send to each other's reflexive address **from the same socket**. The first datagrams open both mappings. M runs only mirror steps over two ring buffers plus a trivial interpreter; it is STUN as a fact on a stream. |
| Symmetric NAT on **both** sides, or CGNAT | Punching is unreliable (fresh port per destination). **Direct exchange is impossible** without either party being reachable. Fallback: relay, below. |
| UDP blocked | WebSocket only, so the browser rule applies. |
| Browser | Cannot listen (`src/cljs/dao/stream/ws/browser.cljs:8-10`). Two browsers behind NAT **cannot form a direct channel** without WebRTC. Fallback: relay. WebRTC DataChannel is a future channel type; its ICE candidates ride the same meeting board. |

**Relay as a convention, not a server.** A relay is a peer M that creates two ring buffers and mirrors both, one with writer surface for A, one with reader surface for A, and the reverse for B. A's channel to B is then *two reflections*: writer = reflection of B's inbox, reader = reflection of A's inbox. The protocol runs unchanged inside it: requests and outcomes are just values in M's streams, which M never interprets. Streams are values sent through streams (`docs/design/datom.world.md:43`). M has no frame of its own, holds no authority, runs the same code as everyone, and is chosen per composition. Any peer with a reachable address can be M. A `gap` on an inbox is channel loss, handled by the link's `:resend-after`, never reported as the source's gap.

**What is impossible and stays impossible.** Two peers that are both unreachable (double symmetric NAT, double CGNAT, two browsers) cannot exchange a byte without a third reachable peer. That third peer is a network necessity, not a protocol role. The invariant's "no privileged node" is met in the only sense that is achievable: the relay is indistinguishable in code and in protocol from any other peer, and it can be replaced by any other reachable peer at any time.

## 3. The toy example

Peer B holds a string handle `s` (a complete-history reader over `["hello"]`, identity `"str-1"`, cursor `{:dao.stream.string/identity "str-1" :dao.stream.string/position 0}`). B's table: `{"str-1" {:handle s :surface #{:reader}}}`. B's host binds a WebSocket endpoint exactly as today (`src/clj/dao/stream/ws/jvm.clj:347-387`), accepted connections deposit into a medium, and B's driver calls `mirror-step` over that medium's reader and each connection's writer. B publishes, anywhere, the descriptor:

```clojure
{:dao.stream/type :dao.stream/remote
 :dao.stream/identity "str-1"
 :dao.stream/channel {:dao.stream/type :dao.stream/ws
                      :ws/host "b" :ws/port 9000 :ws/path "/"}}
```

Peer A calls `attach!`. The link dials, sends `{:identity "str-1" :op :dao.stream/descriptor :args [] :id 1}`. B answers the source's `descriptor` map plus `:oldest c0 :newest c1 :id 1`. A's interpreter calls `cursor :oldest` on the reflection; after the drain it has `c0`, so `ok c0` (before the answer arrived it would have got `transport-error retry? true` and asked again). A calls `next c0`: no answer filed, the link sends `{... :op :dao.stream/next :args [c0] :budget 64 :id 2}` and returns `blocked`. B applies `(next s c0)` → `ok "hello" c1`, follows to `(next s c1)` → `end`, answers `{:outcome :ok :value "hello" :cursor c1 :more [{:outcome :end}] :oldest c0 :newest c1 :id 2}`. A calls `next c0` again: drain files the answer, `ok "hello" c1` is returned and `end` is installed at `c1`. A calls `next c1` → `end`. A read B's string as B's stream, with B's cursors. Nothing knew it was a string; the same steps serve a ring buffer or a durable log.

## 4. Client/server as convention

A content-lookup service. Peer S puts two ring buffers in its table: `"lookup-requests"` with surface `#{:writer}` and `"lookup-answers"` with surface `#{:reader}`, and runs an interpreter over the local ends: read a request, resolve the address, append `{:svc/req r :svc/value v}` or `{:svc/req r :svc/missing true}`. Any peer P attaches reflections to both, mints `:newest` on the answers reflection *first*, then appends `{:svc/req 42 :svc/get addr}` through the requests reflection and reads answers until it sees `:svc/req 42`. Request ids are P's, stable across retries, and dedup is S's interpreter's concern (OD-2). S is a "server" only because its table has those two entries and its interpreter has that policy; P could serve the same pair to S over the very same channel. For private answers, S's interpreter can create a fresh pair per caller and post their descriptors on the answers stream, which is the relay trick of §2 reused as a service door.

## 5. Fate of existing mechanisms

| Path | Verdict | Why |
|---|---|---|
| `dao.stream.ws` transport, four host adapters (jvm, browser, node, dart) | **subsumed** | It is already exactly a channel: writer handle plus deposit medium (`ws.cljc:189-229`, `:148-174`); the `:ws/accept`/`:ws/disclaim` handshake and served-path table (`ws.cljc:483-497`, `:555`) become unnecessary because `not-found` is now an outcome for an identity, but keeping them is harmless. |
| `dao.stream.serving` | **retired** | It forwards a source into every accepted socket (`serving.cljc:310-327`), the copy model, and flattens a source `gap` into a plain detach (`:327`), which the mirror makes unnecessary. |
| `dao.stream.forward` | **unrelated** | A local-to-local copy step (`forward.cljc:67-154`), still useful for replication by convention; it is not part of exposing a stream. |
| `dao.stream.rpc` and `rpc.ws` | **convention-over** | Request/response over two streams is §4; the current envelopes (`apply.cljc:31-45`, `:77-97`) can stay as one such vocabulary, or be replaced by the reflection's own request/outcome shape, which already correlates by id. |
| `dao.stream.apply` | **retired** | Its envelope duplicates the contract's outcome map with an `ok`/`error` wrapper (`apply.cljc:77-97`); the mirror needs no wrapper because the outcome map is the answer. |
| `yin.repl.serve` / `connect` / `driver`, `daostream:ws://` URL | **convention-over** | An eval service over one request stream and one answer stream (`src/cljc/yin/repl/serve.cljc:556-586`, `connect.cljc:337-384`); the URL becomes a remote descriptor for that pair; the shared-shell privilege it grants is a policy of its interpreter, not of the transport. |
| `dao.jing.remote` and `remote.step` / `remote.async` | **convention-over** | A put/get service (`src/cljc/dao/jing/remote.cljc:71-108`); `remote.step` is already the stepped client shape and survives; the JVM-only listen/connect half and `async` are host policy to retire per OD-5. |
| UDP DHT (`dao.jing.dht.node`) | **unrelated** | Raw JVM `DatagramSocket` RPC with its own Kademlia envelope (`node.cljc:104-139`, `:182`), never a dao.stream; its socket handling and 1200-byte budget are the template for the UDP channel adapter, nothing more. |

## 6. Comparison with the 1,197-line serve spec

- Drops sessions, session ids `[peer n]`, per-session roles, `:serve/open`/`opened`/`disclaim`/`close`, idempotent-open rules, closed-session memory: the mirror holds no per-peer state, so none of it exists.
- Drops the append high-water mark: OD-2 puts dedup in the payload; the reflection never re-sends an append.
- Drops the `dao.stream.apply` envelope and its error shape: the outcome map is the wire value.
- Drops ping/pong and channel liveness from the protocol: silence is observed by the composition through the channel's own lifecycle events; a `descriptor` request is a ping if one is wanted.
- Drops held reads, candidate lists, sequential candidate trial, the punch state machine and serve-descriptor `:serve/peer`: reachability is a convention over the meeting board, not descriptor structure.
- Drops a separate proxy driver step: draining happens inside each operation, so cadence is the caller's, as the contract wants.
- **Keeps, and must not lose:** outcome maps and cursors verbatim (its §3.3); `next` batching with the terminator included; anchors piggybacked on answers (its §3.6); the two-reflections relay (its §6.4); the outer-gap-is-channel-loss rule; the datagram budget and the same-socket rule for punching; the OD-1, OD-2, OD-3 preconditions on the contract.

## 7. Owner-visible items only

1. **One genuine impossibility.** Two peers that are both unreachable (symmetric NAT or CGNAT on both, or two browsers) cannot form a direct channel. A third reachable peer is required. In this design that peer runs the same code and holds no protocol role, which is the strongest form of "no privileged node" the Internet permits.
2. **UDP is narrowed** to values that fit one datagram until a fragmentation layer is added inside the UDP channel. Larger values travel as `dao.jing` addresses.
3. **Plaintext and unauthenticated.** A relay peer sees every request and value it carries. A descriptor grants no authorization, so anyone who can reach a channel can read and append to every stream in that peer's table. Reflexive addresses published on a meeting board are visible to every reader of that board. Confidentiality and gating are additions, not present.
4. **Three contract amendments are preconditions:** OD-1 (unrecognized outcome rule and `:dao.stream/retry?`), OD-2 (append effect may be unknown; dedup is the payload's), OD-3 decision 2 (cursors are plain serializable data). Without OD-3 the "same stream, not a copy" claim cannot be made.
5. **Reads cost one round trip per batch and are polled.** A push or held-read mode is left out on purpose; it can be added later as an optional key without changing any frame.
6. **`append!` through a reflection reports channel acceptance only.** The source's outcome arrives on an event stream the composition may or may not wire. This is exactly what the contract already says for remote writers.
