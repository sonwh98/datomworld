Completed-GMT: 2026-09-26 17:35:00 GMT
Completed-Local: 2026-09-27 00:35:00 +0700
Coding-Agent: deepseek
Session-ID: 7aec5601-a998-45b8-a314-235fd90bcf23

# dao.stream.serve — the simplest from-scratch design

## 1. THE DESIGN

**The contract is the wire protocol.** `dao.stream.md` already makes every operation's result an open plain-data map, every cursor plain data bound to the logical stream (not the handle), and identity plain data. A *served stream* is therefore not a copy and not a new kind of stream: it is a **proxy handle** that forwards each operation as one frame and relays each outcome map verbatim. The cursors a remote reader holds are the ones the source minted, so the served stream *is* the original logical stream — same identity, same positions, same `gap`.

Five concepts. Nothing else.

1. **channel** — a writer handle + a reader handle over a real transport (a WebSocket socket, a UDP socket). It carries frames (plain-data maps), is codec-agnostic (Transit-JSON text for WS, CBOR for UDP), and knows nothing about streams.
2. **proxy** — the handle `attach!` returns for a serve descriptor. One proxy = one served stream = one channel. It exposes the handle surface and relays outcomes verbatim.
3. **serve table** — a peer's plain-data map `{identity -> {:handle h :surface S}}`. Composition data the host owns, never a registry (contract "no stream table").
4. **serve-step** — the serving side's single pure transition: read one frame, mechanically dispatch to the handle, write the outcome frame. Nothing about `h`'s transport appears in it; a string, a ring buffer and a durable log are served by the same four entries.
5. **reachability** — how a channel is established: **direct** (dial an acceptor) or **relayed** (through a peer that forwards). The one piece that is not a `dao.stream` operation.

### Wire frames (complete set — six)

```clojure
{:frame :attach   :identity id}                 ; proxy -> serve
{:frame :opened   :surface S :attachment a}     ; serve -> proxy
{:frame :disclaim}                              ; serve -> proxy (not-found)
{:frame :op       :op <:cursor|:next|:append!> :args [...]}
{:frame :out      :outcome <outcome-map, verbatim>}
{:frame :ping     :nonce k} / {:frame :pong :nonce k}   ; liveness + punch
```

The mechanical derivation is a three-row table over the handle `h`:

| wire `:op` | serving side computes | `:out` carries |
|---|---|---|
| `:cursor` `[anchor]` | `(cursor h anchor)` | the outcome map, verbatim |
| `:next` `[cursor]` | `(next h cursor)` | the outcome map, verbatim |
| `:append!` `[value]` | `(append! h value)` | the outcome map, verbatim |

`descriptor` and `close!` never cross the wire: `descriptor` returns the serve descriptor locally; `close!` closes the channel, and channel teardown *is* the `close!` of the attachment. The `:opened` `:surface` is the source's declared surface; the descriptor also carries it as a hint, but `:opened` is authoritative.

### Identity and addressing

- A **stream** is named by its logical-stream identity (plain data), the same value through every handle and descriptor (`dao.stream.md` Invariants).
- A **peer** is named by reachability (where to dial it) plus a self-minted opaque peer id used only for correlation in the meet/punch convention. There is no privileged address; nothing distinguishes a peer that serves from one that only dials.
- The **serve descriptor**:

```clojure
{:dao.stream/type     :dao.stream/serve
 :dao.stream/identity id            ; the served logical stream
 :serve/surface       #{:reader}    ; hint; :opened authoritative
 :serve/reach         [c1 c2 ...]}  ; ordered candidates, each one of:
;;   {:via :direct :url "ws://…" }        or  {:via :direct :udp {:host h :port p}}
;;   {:via :relay  :meet <peer-id> :in <serve-desc> :out <serve-desc>}
;;   {:via :punch  :meet <peer-id>}
```

The proxy tries candidates in order; each failure is a deposited event.

### Cursors, outcomes, gap, anchors — unchanged across the channel

Because the contract's cursors are plain data that survive the host codec (`dao.stream.md` OD-3) and carry the logical-stream identity, and because the serve layer never constructs, parses or rewrites a cursor or an outcome map, a remote reader holds the source's own cursor and observes the source's own `gap`, `end`, `blocked`, `invalid-anchor` and recovery cursors. The anchors `:oldest`/`:newest` are ordinary `:cursor` arguments; a transport-owned anchor is issued as a normal `:cursor` request and its `invalid-anchor` relays back. No anchor piggybacking, no proxy-minted cursor.

### Deliberately left out

Multiplexing (one channel carries one stream; open another channel for another stream), `next` batching, held reads, anchor piggybacking, a proxy window/cache (the proxy polls through — a `next` miss returns the source's `blocked`), UDP fragmentation, and authentication/encryption. Each is a later additive concern, and none is needed to meet the invariant.

---

## 2. INVARIANT PROOF SKETCH

**"Any implementation of dao.stream can be mechanically exposed via websocket or udp and communicate p2p."** The serve-step is a fixed three-row dispatch over the handle surface; it names no transport and no payload shape, so it is mechanical for every transport. "Exposed via udp" is the same serve-step over a UDP channel (one frame per datagram). "P2P" holds because every peer runs both `serve-step` and `proxy-step`; the only asymmetry anywhere is the contract's own between a handle on a stream and a handle on an attachment, and per-session roles reverse freely on one channel.

**"There should be no concept of a server or a client … no privileged server or client."** The frame set names no kind of peer. A WebSocket has a dialer and an acceptor because TCP does — "direction is establishment, not authority" (`docs/design/dao.stream.serve.md:562`). The serve table is ordinary composition data a peer owns; a peer with an empty table answers `:disclaim` and is otherwise indistinguishable.

**"Client-server is just one stigmergic behavior interpreters implement."** §4 works this as a convention over the primitive; no frame names it.

**"It should be able to traverse a NAT."**

| NAT | outcome |
|---|---|
| full cone | direct dial works once mapped |
| restricted cone | punch (nonce-matched ping/pong from the observed socket) |
| port-restricted cone | punch |
| symmetric | punch generally fails (per-destination mapping); **relay** |
| CGNAT | often symmetric-like; **relay** |
| UDP blocked | WebSocket only; no UDP punch |
| browser | can only *dial* (no accept/listen); relay for inbound, else initiator-only |

Punching: both peers learn each other's reflexive address from the meet stream (`:meet/seen`, republished from the serving-boundary observation, never from a claimed address), then exchange `:ping`/`:pong` with a fresh nonce from the *same* socket the meet peer observed. The first inbound datagram confirms nothing; a nonce-matched `:pong` from the expected address establishes the channel. A symmetric NAT defeats this and falls back to the relay — a property of the network, reported as a failed candidate, not a protocol failure.

**The relay is a convention, not a server.** A third peer `M` that both ends can reach runs the *ordinary* `serve-step` over two ring buffers (A's inbound and outbound inboxes) plus a forwarder between them. `M` never sees a session it did not itself serve, has no frame for relaying, and any peer may run it — the relay is literally `dao.stream.forward` on a peer with a reachable address. It holds no privilege; it is a peer whose streams happen to be "forward my frames." That the peers using it must trust it is inherent to NAT traversal, not a protocol role.

**Impossible, stated plainly.** Symmetric↔symmetric and CGNAT↔CGNAT cannot punch and need a relay; a browser cannot accept inbound without a relay; with no relay and no peer reachable by both (public/full-cone), two NATed peers have no rendezvous and *cannot meet at all*. This is a fact about the network, not a defect the design can soften.

**UDP values larger than one datagram.** The design narrows the claim: a value must fit one datagram (budget 1200 bytes, the DHT's figure, `src/cljc/dao/jing/dht/node.cljc:46`); a larger value is `invalid-value` on `append!` / `:oversize` on read, and large media travel as `dao.jing` content addresses — which is the contract's own rule (`dao.stream.md` "Granularity"). No fragmentation in this design; it is added later inside the channel and is invisible to the serve layer.

---

## 3. THE TOY EXAMPLE

A string wrapped as a `dao.stream` handle (a memory log holding one value), exposed by interpreter A over WebSocket, read by interpreter B. No special case for "string" anywhere.

1. A holds `h` (memory log), identity `"s1"`, surface `#{:reader}`. A adds `{"s1" -> {:handle h :surface #{:reader}}}` to its serve table and writes a descriptor `{:type :serve :identity "s1" :surface #{:reader} :reach [{:via :direct :url "ws://a-host:9000/s1"}]}`.
2. The descriptor reaches B by ordinary means ("streams are values sent through streams"). B calls `(attach! descriptor)`.
3. The proxy dials `ws://a-host:9000/s1`, sends `{:frame :attach :identity "s1"}`, and returns `{:outcome :ok :handle proxy}` immediately (the contract says `attach!` does not wait for far-end confirmation).
4. A's serve-step answers `{:frame :opened :surface #{:reader} :attachment "a-1"}`.
5. B calls `(cursor proxy :oldest)` → proxy sends `{:op :cursor [:oldest]}` → A computes `(cursor h :oldest)` and returns the outcome verbatim → B holds A's own cursor `c0`.
6. B calls `(next proxy c0)` → `{:op :next [c0]}` → A computes `(next h c0)` → `{:outcome :ok :value "the string" :cursor c1}` relays verbatim → B reads the string. `c1` was minted by A's handle.
7. B calls `(next proxy c1)` → A answers `{:outcome :end}` (single-value stream, already closed) → verbatim.
8. If A's stream evicted while B held `c0`, A's `next` would answer `{:outcome :gap :cursor c-recovery}` — A's own `gap`, with A's recovery cursor, relayed unchanged.

The string is just a value in a map; the mechanism never inspects it.

---

## 4. CLIENT/SERVER AS CONVENTION

A request/response service (a `dao.jing` content lookup) is two streams served behind a **door** — a peer `S` serves a request stream under `"req"` with surface `#{:writer}` and a response stream under `"resp"` with `#{:reader}`.

- A client `C` attaches both (two proxies), appends `{:id "42" :op :get :addr X}` to `"req"`, and reads `"resp"`, selecting the value whose `:id` is `"42"`.
- `S`'s interpreter (above the serve layer, not in it) reads `"req"`, performs the lookup, appends `{:id "42" :result V}` to `"resp"`.

This is `dao.stream.rpc`'s correlation-by-id envelope reused as a *payload* convention over two proxied streams, not a protocol layer. `S` is a peer that happens to serve two streams and run an interpreter between them; `C` is a peer that attaches two. No frame names a client or a server, and the same peer may open a door and use another's on the same channel. `:id` is the writer's correlation/dedup key — the contract's OD-2 rule, applied at the payload.

---

## 5. FATE OF EXISTING MECHANISMS

| existing | verdict | why |
|---|---|---|
| `dao.stream.ws` transport + 4 host adapters | **subsumed** | its socket seams (`:connect!`/`:send!`/`:close!` + the deposit adapter) become the WS *channel*; its `:ws/value`/`:ws/accept`/`:ws/disclaim` frames are replaced by the serve frames. The adapters survive untouched as the channel's host code. |
| `dao.stream.serving` (forward-into-every-socket) | **retired** | the copy/broadcast model; "serve the original" replaces it. At most renamed `serving.copy` as a broadcast convention. |
| `dao.stream.forward` | **convention-over** | the forwarder *is* the relay; a public peer running it unmodified is a relay peer. |
| `dao.stream.rpc` / `rpc.ws` | **convention-over** | the `{:id :op :args}` correlation envelope becomes the payload convention over two proxied streams; `rpc.ws`'s transport binding retires (proxies replace it). |
| `dao.stream.apply` | **subsumed** | its `{:id :op :args}` request/response shape is the serve request/outcome frame; the serve-step *is* `apply`'s `dispatch-request` narrowed to the handle surface. |
| `yin.repl.serve/connect/driver` + `daostream:ws://` | **convention-over** | the REPL is a door; the URL is a descriptor; `:op/eval` is a payload op. The serial-shell interpreter survives unchanged. |
| `dao.jing.remote` (+ `step`, `async`) | **convention-over** | content is a door; the transport half (`connect-content!`, `serve-content!`, WS descriptor, endpoint/slot) retires; `step`/`async`/`content-client`/`default-handlers` survive, their media become proxy handles. |
| `dao.jing.dht.node` (UDP DHT) | **unrelated** | a separate JVM-only Kademlia concern; its 1200-byte datagram budget and "reply to source address, never a claimed address" rules are reused as the UDP channel's facts. |

---

## 6. COMPARISON (vs the 1,197-line spec)

What this drops: sessions and session ids (one channel = one stream), the per-session append high-water mark (reads are idempotent-by-cursor, writes use OD-2 payload dedup), `next` batching and its budget, anchor piggybacking on every response (`cursor` is a normal op), held reads (`:serve/hold`), the proxy window/cache (poll-through instead), the two contract amendments for "deferred remote observation" (unneeded without a window), and the `meeting`/`punch`/`inbox-pair` three-candidate vocabulary (folded into "reachability is an ordered candidate list; direct or relay, punch optional"). Result: ~6 frames and 3 dispatch rows instead of ~8 frames, session control, window management, and 15 completion criteria.

What the spec had that must not be lost: cursors/outcomes/`gap`/anchors cross *verbatim* (the whole point — a served stream is the original, never a copy); OD-2 (dedup and correlation are the payload's — the one rule that makes UDP writes safe); OD-3 (cursors are plain data that survive serialization — the precondition for everything else); the relay-is-a-forwarder-on-a-public-peer realization; and the UDP rule "reply to the source address, never an address claimed in the payload." This design keeps all five and discards the rest.

---

## 7. OWNER-VISIBLE ITEMS ONLY

1. **This design is not implementable without two contract decisions in `dao.stream.md`, currently only proposed (Open Decisions).** OD-3 (a cursor is plain data that survives the host codec) — without it, "the served stream is the original, not a copy" is *impossible*; the best any network can do is deposit a copy with new positions. OD-2 (dedup/correlation are the payload's; an append whose effect is unknown is not auto-retried) — without it, UDP writes can silently duplicate. Both are small text additions; both are already drafted in `dao.stream.md` OD-2/OD-3.
2. **Genuine impossibilities, not softened.** Symmetric-NAT↔symmetric-NAT and CGNAT↔CGNAT cannot be punched and require a relay. A browser cannot accept (only dial) and so needs a relay for inbound. Two NATed peers with no relay and no peer reachable by both cannot meet at all. These are network facts.
3. **No authentication or encryption yet.** Stream identities and peer ids are opaque and unauthenticated; a relay peer sees every frame in plaintext and can read, drop or modify traffic (and is a de facto central point for the peers that use it — inherent to NAT traversal, not a privilege the design grants). WSS is the host's transport encryption; UDP has none. Nothing in the contract gates attachment (`dao.stream.md` Envelopes). If the owner needs confidentiality or access control on a public relay or door, it arrives later as an interpreter rule (postage/capability) or a channel cipher — the protocol itself adds none, by design.

---

The deliverable is also saved to `/Users/sto/.claude-deepseek/plans/read-collab-1790443205708-architect-serv-lazy-avalanche.md`.
