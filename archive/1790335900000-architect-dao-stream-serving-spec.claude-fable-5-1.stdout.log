Completed-GMT: 2026-09-25 11:39:17 GMT
Completed-Local: 2026-09-25 18:39:17 +07

# dao.stream.serve — Serving Any Stream Over a Network

Status: proposed design target, subordinate to `dao.stream.md`. That document is the contract; this one specifies how a handle on any transport is made attachable from another host, over WebSocket or UDP, including behind NAT and peer to peer. Where the two disagree, the contract wins. Draft text is ready to become `docs/design/dao.stream.serve.md`.

## 1. The problem stated in contract terms

The contract separates a **handle** (host-local, the protocol implementation), a **logical-stream identity** (plain data, the same through every handle), a **descriptor** (reachability data naming the stream) and a **cursor** (immutable, interpreter-owned, bound to the logical stream). A handle never travels. A descriptor does. A cursor does once OD-3.2 is accepted.

Today one transport crosses a network, and it does not serve a stream; it serves a **copy** of one. `dao.stream.serving` forwards the source's values into a socket; the far side's adapter deposits them into a local medium with the local medium's identity and positions (`dao.stream.md`, OD-3 evidence: "what a remote host reads is a copy, not the stream"). That is the right shape for broadcast and it is the wrong shape for the Universal Continuation Format, whose cells travel with a **kept cursor** into the **original** logical stream and must observe the source's own `gap` when the source evicts (§7.4.3, §7.5.3).

So what must cross the network is not values but **operations and outcomes**. The contract already makes this possible: every operation's result is an open plain-data map, every outcome set is exhaustive, `next` is non-destructive and keyed by a cursor, and the two existing cursor shapes are plain data. The contract is a wire protocol that has not yet been sent. This document sends it.

Owner requirements, restated as design obligations:

1. A string can have a `dao.stream` interface, and that stream must become network-accessible without its implementation knowing. Obligation: **mechanical derivation** — the server side is computed from the handle and its declared surface, with no per-transport code.
2. Over WebSocket or UDP. Obligation: the protocol is defined over an abstract **channel**, with WebSocket and UDP as two channels and no protocol change between them.
3. Behind NAT. Obligation: serving is decoupled from listening; a host that can only dial out can still serve.
4. P2P over both, behind NAT. Obligation: the protocol is **symmetric** with respect to who dialed, and the reachability model includes rendezvous, hole punching, and relay fallback.

## 2. Layering

Four layers, dependencies one-way, downward only:

```
contract-generic reader/writer code           sees a handle; knows nothing below
  │
proxy handle + proxy-step   ─┐
  │  serve frames (data)     │  dao.stream.serve — this document
serve step + serve table    ─┘
  │
channel                       a duplex message path with a deposit-model adapter
                              (ws today; udp new; a channel over a channel for relay)
  │
reachability                  how a channel is established: direct, relayed, punched
                              (descriptor candidates; rendezvous is a channel peer)
```

Rules that hold across the layers:

- **The serve layer owns the vocabulary of operations, sessions and outcomes.** A channel carries opaque application values and lifecycle events. No channel inspects a serve frame; no serve frame names a channel.
- **Reachability lives in the descriptor** (contract, Envelopes) as an ordered list of channel candidates. The serve layer never interprets an address; it hands each candidate to the host's channel dispatch.
- **Every layer is stepped.** Proxy, server, relay and rendezvous are pure transitions over explicit state driven by a composition-owned driver that passes `now`. Nothing waits, nothing schedules itself, nothing registers a callback above the channel adapter. This is OD-5's rule applied to a whole subsystem.

## 3. The serve protocol

### 3.1 Sessions

A **session** is one attachment to one served logical stream over one channel. It is the serve-layer unit that carries `:dao.stream/attachment`. One channel carries many sessions, tagged by `:serve/session`, minted by the opening side and unique per channel. Multiplexing is here rather than in the channel so that channels stay dumb and so that one channel between two peers carries every stream a migrated continuation references.

Roles are per session, not per channel: on one channel, peer A may serve stream X to B while B serves stream Y to A. This is the P2P case and needs no extra mechanism.

### 3.2 Frames

Serve frames are the application values a channel carries. Keys are qualified under `:serve/…` with one separator, per the ws rule. The operation and its arguments ride in the `dao.stream.apply` envelope, which already owns request identity, operation and arguments:

```clojure
;; session control
{:serve/frame :serve/open     :serve/session s  :dao.stream/identity id}
{:serve/frame :serve/opened   :serve/session s  :dao.stream/attachment a
 :dao.stream/surface #{:reader :writer :closable}
 :serve/oldest <cursor>  :serve/newest <cursor>}                       ; anchors as of open, §3.6
{:serve/frame :serve/disclaim :serve/session s}                        ; the authoritative not-found
{:serve/frame :serve/close    :serve/session s}                        ; either direction, terminal

;; operations
{:serve/frame :serve/request  :serve/session s
 :dao.stream.apply/id n  :dao.stream.apply/op :dao.stream/next  :dao.stream.apply/args [cursor budget]}
{:serve/frame :serve/response :serve/session s
 :dao.stream.apply/id n  :dao.stream.apply/ok <see 3.3>
 :serve/oldest <cursor>  :serve/newest <cursor>}                       ; piggybacked refresh, §3.6

;; channel liveness (per channel, not per session)
{:serve/frame :serve/ping :serve/nonce k}
{:serve/frame :serve/pong :serve/nonce k}

;; reachability control, §7
{:serve/frame :serve/register  :serve/peer p}                          ; outbound-serving host → relay or rendezvous
{:serve/frame :serve/dial      :serve/peer p  :serve/link l}           ; client → relay/rendezvous: reach p
{:serve/frame :serve/introduce :serve/peer q  :serve/link l  :serve/reflexive {…}}   ; rendezvous → p
{:serve/frame :serve/via       :serve/link l  :serve/inner <frame>}    ; relayed carriage, §6.4
{:serve/frame :serve/punch     :serve/link l  :serve/nonce k}          ; hole-punch probe, §7.4
```

A frame with an unknown `:serve/frame`, a missing required key, or a value outside the channel's portable domain is a protocol failure of the **channel** (decode diagnostic then teardown), never a serve outcome: the serve layer only ever sees valid frames, exactly as the ws adapter only deposits valid events.

### 3.3 Mechanical derivation

The server for a handle `h` with declared surface `S` is the apply handler table below, restricted to `S`. Nothing about `h`'s transport appears anywhere; a string-backed stream, a ring buffer and a durable log are served by the same six lines.

| Wire op                | Args                     | Server computes                          | `:dao.stream.apply/ok` value                              |
|------------------------|--------------------------|------------------------------------------|-----------------------------------------------------------|
| `:dao.stream/descriptor` | `[]`                   | `(descriptor h)`                         | the outcome map, verbatim                                 |
| `:dao.stream/cursor`   | `[anchor]`               | `(cursor h anchor)`                      | the outcome map, verbatim                                 |
| `:dao.stream/next`     | `[cursor budget]`        | `next` repeated from `cursor`, following each `ok`'s successor, at most `budget` times, stopping at the first non-`ok` | a vector of outcome maps, in order, verbatim |
| `:dao.stream/append!`  | `[value]`                | `(append! h value)`                      | the outcome map, verbatim                                 |
| `:dao.stream/close!`   | `[]`                     | `(close! h)` only if `h` is an attachment the server minted for this session; never the owner's close | `{:dao.stream/outcome :dao.stream/ok}` |

An op absent from `S` is answered with an apply **error** response, code `:dao.stream.serve/no-surface`: the contract forbids answering a nonexistent operation with an outcome map (Surfaces), and the apply envelope has an error shape for exactly this. The proxy never exposes such an op, so the error only ever reaches a misbehaving client.

Outcome maps cross **verbatim**, including the cursors inside them. The serve layer never constructs, parses or rewrites a cursor. This is the whole basis of cursor-namespace preservation: the proxy hands the reader exactly the cursor the source minted, and hands the source exactly the cursor the reader held.

**Precondition on the source.** Its cursors and outcome maps must lie in the channel's portable value domain. Both existing transports qualify (a string identity and an integer position). A transport whose cursor holds a host object cannot be served; the exporter refuses it before publishing (§9). This is the whole content of the UCF's "portable cursor profile": one profile, `:dao.stream.serve/v1`, meaning *cursor is plain data in the portable domain and the source honors it after serialization*.

### 3.4 Idempotence, loss and duplication

Four of the five operations are idempotent as the contract defines them: `descriptor` and `cursor` are projections; `next` is non-destructive and positioned by its argument; `close!` is idempotent by definition. A lost request is re-sent; a lost response is re-requested; a duplicated request re-computes the same answer or a later, equally true one. **No sequence numbers, acknowledgement vectors or sliding windows are needed for reads: the cursor is the sequence number, and `gap` remains the source's declaration rather than a transport artifact.** This is what reconciles UDP with kept cursors and gap, and why v1 DRDS (`daostream-udp-design.md`) is not revived.

`append!` is the one non-idempotent operation. The protocol makes it safe by the rule OD-2 already states — deduplication and correlation are the payload's — applied at the serve layer so that no application has to:

- A session has **at most one outstanding `append!`**. The proxy does not issue the next until the previous is answered or the session is lost. This is `dao.stream.rpc`'s `unsent` discipline, and it also makes UDP reordering harmless for writes.
- The server keeps, per session, the id and outcome of the **last** `append!` it performed. A request with that id is answered from the record without re-appending. Window size one follows from the one-outstanding rule.
- If the session is lost with an append unanswered, the proxy reports it as `:dao.stream.serve/append-unknown` on its event medium with the retained value. The proxy does not silently retry across sessions: the far end may have appended. This is OD-2's third state named honestly.

A stale response (an id no longer outstanding) is a diagnostic, not an error, exactly as in `dao.stream.rpc`.

### 3.5 Batching

`next`'s `budget` bounds one response. The server answers with consecutive outcomes; the proxy installs each `(cursor_i → outcome_i)` with `cursor_0` the requested cursor and `cursor_{i+1}` the successor in `outcome_i`. A server also bounds the batch by the channel's frame budget (§6.3): it stops early when the next value would not fit and returns what it has. A single value that cannot fit in one frame on this channel is answered as `transport-error` with `:dao.stream.serve/reason :oversize` in place of that element; the read is not silently skipped and the cursor does not advance past it. Large media belong in the payload as `dao.jing` addresses (contract, Granularity).

### 3.6 Anchors across a hop

`cursor` has no `blocked` outcome, yet a proxy cannot consult the source synchronously. The design does not invent a proxy-namespaced cursor (that would put a cursor in circulation that the source's own handle rejects, violating "any handle on the same logical stream accepts it"). Instead the server **piggybacks fresh `:oldest` and `:newest` cursors on `:serve/opened` and on every response**, and the proxy's `cursor` answers with the most recently observed one.

This is honest under the contract's own definitions. A stale `:newest` is *earlier* than a fresh one, so a composition that mints `:newest` before invoking an operation still observes everything the operation causes; it may additionally observe a few values appended between the last exchange and the mint, which is re-seeing, never skipping. A stale `:oldest` may point at a position since evicted, and the contract already says a fresh `:oldest` is a position rather than a completeness claim: the reader receives `gap` with the recovery cursor. Before the first `:serve/opened` the proxy holds no anchors and `cursor` answers `transport-error` with `:dao.stream/retry? true` (OD-1's proposed key); the composition observes `:serve/opened` on the proxy's event medium and mints then.

### 3.7 Held reads (optional, v1 decision needed)

Across a network a `blocked` answer costs a round trip. A `next` request may carry `:serve/hold true`; a server that honors it records the demand in session state and, on each of its own driver steps, re-polls the source, answering when the outcome is no longer `blocked` or when its hold budget (a constructor policy in the driver's clock domain) elapses, in which case it answers the `blocked` it has. No operation waits: the session holds state, the step returns. A proxy must tolerate a server that ignores the flag. Whether v1 includes this is an owner decision (Q2).

## 4. The proxy handle: declared nature

A proxy is a handle minted by `attach!` on a serve descriptor. It declares exactly the surface the `:serve/opened` frame reports, which is the source's declared surface; until then it declares the surface the descriptor's `:serve/surface` hint names, and a mismatch at open is a session failure deposited as data. `descriptor` returns the serve descriptor and the identity. The proxy is composed as three explicit pieces: the handle (an object over an explicit state atom, as `WsHandle` is), an **event medium** the composition wires and the channel adapter deposits into, and `proxy-step`, which the composition's driver calls with `now`.

The handle's `next` is a read of the proxy's **window**, a bounded host-local cache keyed by cursor value (structural equality; every cursor a reader holds was minted by the source, through this proxy or another). A miss records a **demand** in proxy state and answers `blocked`. `proxy-step` issues demands as requests, installs responses into the window, opens sessions, retries by the composition's policy, and evicts the window oldest-first. The window is a cache, not retention: an evicted window entry is re-fetched, and only the source ever answers `gap`.

| Op        | Produces                                                                 | Excluded, and why |
|-----------|--------------------------------------------------------------------------|-------------------|
| `cursor`  | `ok` (last observed anchor, §3.6), `invalid-anchor` (relayed), `closed`, `transport-error` (`:establishing`, retryable; or channel failure) | — |
| `next`    | every source outcome, relayed verbatim; `blocked` also for *not here yet*; `end` for positions beyond the window after the session ended; `cursor-mismatch` locally by identity; `invalid-cursor` for a non-map; `transport-error` for `:oversize` or channel failure | — |
| `append!` | `ok` (accepted into the session's outbound path, carrying `:dao.stream.serve/id`), `full` (one append already outstanding, or channel outbound full; transient), `invalid-value` (outside the channel's domain), `closed`, `transport-error` | — |
| `close!`  | `ok`                                                                    | — |
| `descriptor` | `ok`                                                                 | — |

The proxy's writer surface is on the **session's ordered outbound path**, as ws's is on the connection's; the source's answer to an append arrives on the proxy's event medium as `{:serve/event :serve/appended :dao.stream.serve/id n :serve/outcome <map>}`. This is the contract's Writing rule and the ws precedent, and it has a cost for the UCF (Q1).

Session loss is detachment, not ending: the proxy deposits `:serve/detached`, `append!` answers `closed`, and `next` answers what the window holds and then `end`, exactly as the contract specifies for a closed attachment. Cursors bind to the logical stream, so the reader's kept cursor survives; a fresh `attach!` with the same descriptor resumes at it. Re-establishing a lost session under the same proxy handle, answering `blocked` meanwhile, is a **proxy policy the composition supplies as data** (`:reconnect {:attempts n :after-ms t}`); the default is none, so nothing retries invisibly.

Two subtleties the contract forces:

- `end` from the source is relayed. `end` from session loss is local. Both mean "what this handle is on is closed" and both are correct; a program that reads `end` as *the stream ended* will misread a channel loss unless its composition reconnects. This is the same limit ws documents under *Ending a served stream*, and the same answer: the serve `:serve/close` frame from the server carries `:serve/reason :ended` when the source's owner closed it, so the proxy can deposit `:serve/ended` versus `:serve/detached` and a composition can tell which it saw.
- A proxy is not a retaining medium and has no `gap` of its own. It never produces a `gap` the source did not.

## 5. The server

Server state is a value: `{:served {identity → {:handle h :surface S}} :sessions {session-id → {:identity … :attachment a :last-append {…} :held {…}}}}`. `serve-step` takes the state, one inbound frame or the driver's `now`, and returns the next state plus zero or more frames to send. The composition owns the driver and the channel writer.

The **serve table** is composition data, not a registry: the host decides what it serves, adds and removes entries through `serve`/`unserve` transitions on its own state, and the table's lifetime is the host's business. Nothing here is namespace-global and nothing is consulted ambiently, which is the contract's "no stream table" preserved: a descriptor naming an identity this server does not hold is answered `:serve/disclaim`, the authoritative `not-found`.

The server is **stateless with respect to reads**. It holds no reader positions; every `next` carries its cursor. This is what lets it survive a client's silence, a duplicate request, or a session that was lost and reopened, without any resumption protocol: resumption is the client re-sending the cursor it kept.

The server never calls the owner's `close!`. A session's `:dao.stream/close!` closes only an attachment handle the server minted for that session; for a source that mints no attachments (a ring buffer, a memory log) it is a session-scoped no-op, and the session ends. Ending the served stream is its owner's act on the owner's handle, observed by the server as `end` from `next` and relayed.

## 6. Channels

### 6.1 Channel contract

The serve layer needs exactly what `dao.stream.ws` already provides and nothing more:

1. A **writer handle** whose `append!` places one application value on an ordered (WebSocket) or unordered (UDP) outbound path, with `ok`, `full`, `invalid-value`, `closed`, `transport-error`.
2. A **deposit-model adapter** that turns every inbound application value and every lifecycle event into one plain-data event on a medium the composition wired, correlated by an attachment identity, judging none of them.
3. A **portable value domain** and a **frame budget** (bytes per value; unbounded for WebSocket, the datagram budget for UDP).
4. A declaration of **ordering** (`:ordered` or `:unordered`) and **reliability** (`:reliable` or `:best-effort`), which the proxy's retry policy reads as composition data.

The channel's declared nature is what changes the proxy's policy, never its protocol.

### 6.2 WebSocket channel

`dao.stream.ws` is used as is, with one composition and one wording change:

- **Composition.** A serve endpoint serves one path (by convention `/dao.stream.serve`) whose "served stream" is the channel itself: the descriptor's `:dao.stream/identity` is the serving **peer id**, not a stream. The accepted attachment's traffic medium feeds `serve-step`; the accepted socket handle is `serve-step`'s writer. `dao.stream.serving` is not used: it forwards a source into every accepted socket, which is the copy model. A small sibling composition, `dao.stream.serve.ws`, wires an accepted attachment to `serve-step` instead of to `forward-step`.
- **Direction.** A host that dials **out** with `ws/attach!` and then runs `serve-step` over that attachment is serving. Nothing in the ws state machine cares who dialed; only its prose does (§ Contradictions, C1).

Hosts: clj (JDK client; server via the existing endpoint seam), cljs browser (client only; no listener exists), cljs node (both), cljd (both under `dart:io`; Flutter web is a browser).

### 6.3 UDP channel

`dao.stream.udp` is a new channel with the ws deposit model and no reader surface: a socket retains nothing. It is the datagram boundary the DHT node already uses, reshaped into the adapter form.

- **Descriptor.** `{:dao.stream/type :dao.stream/udp :dao.stream/identity <peer-id> :udp/host … :udp/port …}`. `attach!` binds a local socket (ephemeral port) and returns a handle whose writer path is "datagrams to that address". No handshake is needed at this layer; the serve layer's `:serve/open` is the handshake.
- **One value per datagram**, encoded with the CBOR codec profile (binary, canonical, already specified in ws). Frame budget 1200 bytes, the DHT's figure, chosen to clear common path MTUs without fragmentation. A value over budget is `invalid-value` on `append!` and, from the server's side, `:oversize` (§3.5). IP fragmentation is not relied on; application fragmentation is deferred and, if added, lives in this channel, invisible to the serve layer.
- **Declared nature:** `:unordered`, `:best-effort`. The proxy's retry policy therefore has real work: each outstanding request records `:sent-at`; `proxy-step` re-sends when `now - sent-at` exceeds `:retry-after-ms`, with `:max-attempts`, both composition data. Exhaustion is session loss, deposited as data.
- **Lifecycle events** are thinner than ws: there is no peer close, so `:udp/closed` is deposited only on local close or deposit failure; peer silence is the serve layer's business (`:serve/ping` and the retry policy), not the channel's.
- **Attachment identity** is the remote `(host, port)` pair as seen by the socket; replies go to the datagram's source address, never to a claimed address in the payload, as the DHT already rules.
- Hosts: clj (`DatagramSocket`), cljs node (`dgram`), cljd (`RawDatagramSocket`). **Browsers have no UDP.** The browser's P2P path is a WebSocket to a relay (§7.3) today and a WebRTC DataChannel channel later (§14); both slot into §6.1 without touching the serve layer.

### 6.4 A channel over a channel

A relayed path is a channel whose writer wraps every value as `{:serve/frame :serve/via :serve/link l :serve/inner v}` and whose adapter unwraps `:serve/via` frames with its link from the underlying channel's medium, depositing `:serve/inner`. It declares the underlying channel's ordering and reliability. Nothing else is new: streams are values that travel through streams, and a channel is a stream. The relay itself (§7.3) sees only `:serve/via` and a link table.

## 7. Reachability

### 7.1 The serve descriptor

```clojure
{:dao.stream/type      :dao.stream/serve
 :dao.stream/identity  "…"                                   ; the served logical stream
 :serve/peer           "…"                                   ; the serving peer, opaque string
 :serve/surface        #{:reader}                            ; hint; :serve/opened is authoritative
 :serve/candidates     [{:dao.stream/type :dao.stream/ws  :ws/host "a.example" :ws/port 9090 :ws/path "/dao.stream.serve" :dao.stream/identity "peer-A"}
                        {:dao.stream/type :dao.stream/udp :udp/host "203.0.113.5" :udp/port 4000 :dao.stream/identity "peer-A"}
                        {:dao.stream/type :dao.stream/udp :udp/host "r.example" :udp/port 4000 :dao.stream/identity "relay-R" :serve/via :punch :serve/peer "peer-A"}
                        {:dao.stream/type :dao.stream/ws  :ws/host "r.example" :ws/port 9090 :ws/path "/dao.stream.serve" :dao.stream/identity "relay-R" :serve/via :relay :serve/peer "peer-A"}]}
```

Each candidate is an ordinary channel descriptor, optionally marked `:serve/via :relay` or `:punch` with the target peer. The proxy tries candidates in order; each attempt's outcome is data on the proxy's event medium (`:serve/candidate-failed` with the index and the channel's own resolution event), and the first channel that yields `:serve/opened` is the session's. Sequential trial is a v1 simplification; parallel trial with a preference order (ICE's shape) is an additive proxy policy. The descriptor carries no authorization, as the contract requires.

### 7.2 Direct

The serving host listens; the candidate is a plain ws or udp descriptor. This is the existing ws model and the only one `dao.stream.ws.md` describes.

### 7.3 Relay: outbound-only serving

A host behind NAT dials a **relay** R (any host with a reachable address running `relay-step`) and sends `:serve/register` with its peer id, then keeps the channel alive with `:serve/ping`. A client dials R and sends `:serve/dial` naming the peer and a link id it minted. R's state is `{:peers {peer-id → channel-writer} :links {link → [writer-a writer-b]}}`; on `:serve/dial` it pairs the link with the registered peer's channel and thereafter forwards `:serve/via` frames between the two writers by link, copying and judging nothing. Both sides then run the serve protocol over the §6.4 channel. R never sees a session or an operation. A relay is a forwarder: its step is `forward-step` with a link-keyed destination.

This is the browser's whole NAT story and every host's fallback. Its costs are honest: the relay carries every byte, and a registered peer is reachable only while its outbound channel lives, so `:serve/ping` cadence is bounded by NAT binding lifetime (tens of seconds for UDP, longer for TCP). A relay is composition, not infrastructure: anyone runs one, and `dao.stream.discovery`'s rendezvous topics are how a peer finds one.

### 7.4 Rendezvous and UDP hole punching

Both peers behind NAT, both with UDP. Each registers with a rendezvous S over UDP; S records the **reflexive address** (source host and port as S observed them) per peer. On `:serve/dial`, S sends `:serve/introduce` to both peers carrying the other's reflexive address and the link. Each peer then sends `:serve/punch` probes with the link's nonce to the other's reflexive address on a schedule `proxy-step` reads from `now`; the first probe to arrive opens the NAT binding in the other direction, and the first `:serve/punch` received is answered with a `:serve/pong`. When both sides have a pong the direct udp channel exists and the serve session opens over it. If no pong arrives within the punch budget, the proxy falls to the next candidate, normally the relay through the same S (a rendezvous is also a relay, and one channel to it serves both roles).

This is STUN and ICE reduced to what the datom.world model needs: reflexive addresses are facts deposited by S, probes are values, and the state machine is a step. Symmetric NATs that assign a fresh port per destination defeat punching and take the relay path; that is a property of the network, reported as a failed candidate, not a failure of the protocol.

### 7.5 P2P symmetry

Once any channel exists between A and B, each may `:serve/open` sessions on the other's serve table, in both directions, on the same channel. There is no client host and server host, only per-session roles. A continuation migrating from A to B whose cells reference streams on A is served by A over the very channel B dialed to fetch it. This falls out of §3.1 and needs no additional design.

### 7.6 Liveness

`:serve/ping` and `:serve/pong` are per channel. Cadence and the number of unanswered pings that end a channel are composition data in the driver's clock domain. A channel ended by silence deposits its lifecycle event; every session on it is lost (§4). This resolves the ws document's deferred *Liveness* item for channels used by this layer, with ordinary values rather than protocol frames, because a protocol frame would be a fact deposited by nobody.

## 8. Host isolation

| Piece                                  | clj | cljs browser | cljs node | cljd | Where the host code lives |
|----------------------------------------|-----|--------------|-----------|------|---------------------------|
| serve frames, proxy, server, relay, rendezvous steps | ✓ | ✓ | ✓ | ✓ | `.cljc`, pure data, no host code |
| ws channel, dial out                   | ✓   | ✓            | ✓         | ✓    | existing `:connect!` seams |
| ws channel, listen                     | ✓   | —            | ✓         | ✓    | existing endpoint seam |
| udp channel                            | ✓   | —            | ✓         | ✓    | new socket seam, same shape as ws's `:send!`/`:close!` |
| relayed channel (§6.4)                 | ✓   | ✓            | ✓         | ✓    | `.cljc` |
| hole punching                          | ✓   | —            | ✓         | ✓    | `.cljc` over the udp seam |
| WebRTC DataChannel (deferred)          | —   | later        | —         | later (Flutter) | a future channel |

A browser peer therefore serves and proxies through a relay; a browser can be a P2P participant behind NAT, just not a hole-punching one until a DataChannel channel exists.

## 9. UCF integration

What §7.4.3 and §7.5.3 should say, replacing the undefined "standard stream-over-network facade":

- **Lift.** For each stream cell, the exporter checks: the handle's declared surface covers what the cell needs; its cursors and outcome maps are in the portable domain (`:dao.stream.serve/v1`, the one cursor profile); and the exporter has at least one reachability candidate. It adds `identity → {handle, surface}` to its serve table and writes a serve descriptor into the cell's `:yin.k/stream`. The cell's `:yin.k/position` is the kept cursor, verbatim. Any check failing is `:yin.k/unsatisfied` before minting, as the UCF already says.
- **Lower.** The resumer `attach!`s each serve descriptor, receiving a proxy. `next` at the kept cursor is the first demand; `blocked` until served; the source's `gap` arrives verbatim. `:serve/disclaim` on open is `not-found` and hence `:yin.k/unsatisfied` naming the identity. A candidate list that is exhausted is the same.
- **Lifetime.** The exporter must keep the entry served while the migrated task may need it. The correct home for that obligation is a lease (`dao.lease.md`): the served entry is a grant, the resumer renews, and lapse unserves. Until that lease exists, the exporter keeps the entry until its own composition retires it, and a resumer that arrives after retirement gets `not-found`. This is stated as an open question (Q6) rather than designed here.
- **Pinning.** Serving from the emitter pins the emitter: the stream is where it is, and OD-3(a) makes a copy a different stream. Re-homing a stream is out of scope and should be said so in the UCF.

## 10. Invariant compliance

- **No hidden global state.** Serve tables, sessions, links, peers and windows are values inside compositions the host constructs and drives; nothing is namespace-global and no operation consults ambient state.
- **No implicit control flow.** Every step is called by a driver with `now`; the only callback code is the channel adapter, which deposits and returns, as the ws adapter does today.
- **No raw callbacks.** Resolution, detachment, append outcomes, candidate failures and reflexive addresses are all deposited events, read by cursors.
- **No shared mutable state.** A proxy's window and a server's session record are owned by exactly one driver; the source handle is touched only by `serve-step`.
- **No layer collapsing.** Relay and rendezvous interpret nothing; the channel interprets nothing; the serve layer interprets operations but never values; the reader interprets values.
- **No assumed graphs.** Who serves what to whom is the serve table and the descriptor's candidates, explicit data, never a topology the protocol infers.

## 11. Concurrency and linearization

The source is operated only from `serve-step`, in the order requests are dequeued. Reads carry their own cursors, so concurrent readers through one or many proxies neither share nor contend for position. Writes are linearized per session by the one-outstanding rule, and across sessions by the source's own sequence; the contract promises nothing more for concurrent writers and neither does this layer. Over an unordered channel the proxy never issues an `append!` before every earlier request on that session has been answered, so a `:newest` mint that must precede an append does precede it at the source. The relay preserves per-link order only if its underlying channel does; a relayed UDP link is unordered and the proxy's policy already accounts for that through the channel's declaration.

## 12. What is contract, transport-owned and deferred

- **The contract requires** (and this design depends on): OD-3 resolutions (a) and (2), cursors as plain data that survive serialization; OD-2's payload-correlation rule; OD-1's unrecognized-outcome rule with `:dao.stream/retry?`. None of the three is accepted yet (see C3).
- **Transport-owned by this layer:** the frame vocabulary, session identity, the one-outstanding-append rule, anchor piggybacking, held reads, candidate order, retry and reconnect policies, relay and rendezvous state.
- **Deliberately deferred:** UDP application fragmentation; parallel candidate trial; WebRTC DataChannel; authorization and postage for `:serve/register` and `:serve/dial` (a relay is a spam surface, and the contract's no-gating stance plus `dao.stream.discovery`'s postage note apply); self-certifying peer ids (v1 peer ids are opaque strings the composition assigns; the discovery document's kickoff-hash identities slot in as an additive rule); a lease over served entries; encryption of the channel (WSS is the host's; UDP has none until a Noise-shaped channel exists, and until then UDP serving is for trusted networks, as the DHT already says).

## 13. Completion criteria

1. **Loopback conformance.** Serve a memory log and a ring buffer through an in-process channel pair (two ring buffers as the two directions). The proxy passes the contract's reader and writer tests, and every cursor the proxy hands out is structurally equal to one the source minted.
2. **Gap fidelity.** Evict at the source under a kept cursor; the proxy relays `gap` with the source's recovery cursor, with no replay and no skip.
3. **Anchor semantics.** A `:newest` minted through the proxy never skips a value appended after the mint.
4. **UDP under loss, reorder and duplication** (a lossy socket seam): no duplicate append at the source, no lost read, no proxy-fabricated gap, and `:serve/append-unknown` deposited when the session dies with an append in flight.
5. **Outbound serving.** A peer that only dials out serves a stream to a peer that dialed the same relay; the relay's state never holds a session.
6. **Punch and fallback.** Two UDP peers behind a NAT simulator open a direct channel through a rendezvous; with a symmetric-NAT simulator they fall to the relay candidate, reported as data.
7. **P2P symmetry.** Two peers each serve one stream to the other over one channel.
8. **UCF §7.11 facade acceptance**, as written there: park with a string-backed stream at a kept cursor, migrate, resume, force eviction, observe `gap`; a non-portable source refuses at lift; an unreachable endpoint refuses at lower.
9. **Portability.** Items 1–4 and 7 run on clj, cljs node and cljd; item 5 also on cljs browser through the ws relay path.

## 14. Deferred

Listed in §12. Additionally: a copy-serving mode remains useful (broadcast to many readers with local retention) and is what `dao.stream.serving` already is; it should be renamed or re-described so that "serving" no longer suggests attachment to the original stream (C2).

---

# Open questions for the owner

- **Q1. Append through a proxy.** As designed, `append!` on a proxy answers `ok` for acceptance into the session's outbound path and the source's outcome arrives later on the event medium, as ws does and the contract's Writing section allows. The UCF's `:put` rule ("retries its retained value and resumes on `ok`") would then resume a program on an append the source may still refuse. Alternative: the proxy answers `full` until the source's outcome arrives and then reports that outcome, holding the value meanwhile, which gives the writer the source's truth from `append!` itself at the cost of a proxy-level convention (one held value, writer retries the same value). Recommendation: keep the contract-consistent design and amend UCF §7.4.3 so that a `:put` on a served stream resumes when the source's outcome is observed, not on outbound `ok`.
- **Q2. Held reads in v1.** They cut network chatter for `blocked`-heavy readers such as a parked VM polling a response cell, at the cost of per-session server state and a hold budget. Recommendation: include, since the UCF's dominant pattern is exactly a kept response cursor polled until a value lands.
- **Q3. Peer identity.** Opaque composition-assigned strings now, self-certifying kickoff-hash identities later, or self-certifying from the start. Recommendation: opaque now; the relay routes by string equality either way, and signature verification is an additive rule on `:serve/register`.
- **Q4. Relay and rendezvous gating.** v1 accepts any `:serve/register` and `:serve/dial`. Recommendation: accept for the dev-only repo, record postage as the intended answer.
- **Q5. Accept OD-1, OD-2 and OD-3 (a)+(2) now.** This design is not implementable without OD-3 and is dishonest without OD-2; OD-1 is needed only for the `:establishing` answer. Recommendation: accept all three as a precondition of the implementation phase.
- **Q6. Lifetime of a served entry after migration.** Lease-governed (recommended, via `dao.lease`), or composition-retired with `not-found` as the honest late answer.
- **Q7. Naming.** `dao.stream.serve` for this layer and a rename of today's `dao.stream.serving` to `dao.stream.serving.copy` or similar, so the two models are not confused.

# Contradictions found

| Severity | File:line | Evidence | Recommended correction |
|---|---|---|---|
| high | `docs/design/dao.stream.ws.md:18-26` | "serving is what makes any stream remotely attachable … remote interpreters can attach" | Only true of copies: the client reads a local medium with local positions (OD-3 evidence, `dao.stream.md:906-911`). Reword to: the ws transport serves copies; attaching to the original stream is `dao.stream.serve`. |
| high | `docs/design/dao.stream.ws.md:9-16, 455-459` | descriptor "names a server-hosted stream"; endpoint constructor is bind-host/advertised-host only; no outbound-serving mode | Add: direction of establishment is independent of the serving role; a dialed-out attachment may carry `serve-step`. The state machine already permits it; only the prose forbids it. |
| high | `docs/design/dao.stream.md:523-525` (OD-3), `:851-894` (OD-2), `:811-849` (OD-1) | "Whether a cursor survives serialization to another host is transport-owned and TBD"; append's third state undefined; unrecognized outcome undefined | This design requires OD-3 (a)+(2) and OD-2, and uses OD-1's `:dao.stream/retry?`. Accept them; move the text into the sections they amend. |
| high | `docs/design/yin.vm.universal-continuation-format.md:520-534` | "standard stream-over-network facade derived from the dao.stream operation and outcome contract … `dao.stream.rpc.ws` demonstrates … does not itself supply the UCF facade" | Cite `dao.stream.serve.md`; the facade is the serve protocol, the proxy is its handle, and the exporter's obligation is §9 of this design. |
| medium | `docs/design/yin.vm.universal-continuation-format.md:554-556` | "a `:put` wait retries its retained value and resumes with it on `ok`" | Through a proxy, `ok` is outbound acceptance. Resume on the observed source outcome (Q1), or adopt the alternative and say so. |
| medium | `docs/design/yin.vm.universal-continuation-format.md:780, 929` | `:yin.k/cursor-profiles #{:dao.stream/file-v1}` — a per-transport profile set | One profile, `:dao.stream.serve/v1` (cursor and outcomes in the portable domain). Per-transport profiles would make the resumer know transports, which the proxy exists to prevent. |
| medium | `docs/design/daostream-udp-design.md:199-284, 470` | DRDS sequence numbers, ACK vectors, sliding window; "NAT traversal (STUN / ICE)" as deferred | Superseded: serve reads are idempotent by cursor, so no DRDS; a datagram stream with its own positions is a different logical stream under OD-3(a). Mark the document superseded prior art; NAT moves to this spec. |
| medium | `docs/design/dao.stream.ws.md:692-697` | Liveness and resumption protocol listed as deferred | Resumption is answered by kept cursors plus a stateless server (§5); liveness by `:serve/ping` at the serve layer (§7.6). Point the deferred items here. |
| low | `docs/design/dao.jing.dht.md:165-167` | "NAT traversal is unaddressed … hole-punching, relay/TURN, and bootstrap discovery" | Cite §7.3–7.4; a DHT node is a natural rendezvous host and rendezvous topics (`dao.stream.discovery.md` §4) are the bootstrap. |
| low | `src/cljc/dao/stream/serving.cljc:1-11, 234-237` | "Host-owned WebSocket serving composition"; forwards from `:forward-anchor` (default oldest) into every accepted socket | Not wrong, but it is copy-serving. Rename or re-describe (Q7) so that "serving" does not imply attachment to the original stream. |
| low | `docs/design/dao.lease.md:286-289` | "A remote holder learns of a reclaim by observing … an ordinary `:ws/closed`. No close code is assigned here" | With serve sessions, reclaim of a served entry is `:serve/close` with `:serve/reason`, independent of the channel's close codes; update once Q6 is decided. |

The task's read-only constraint was honored: no repository file was edited. The plan-mode plan file at `~/.claude/plans/read-collab-1790335900000-architect-dao-declarative-otter.md` records the context and verification items for this design.
