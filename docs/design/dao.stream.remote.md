# DaoStream Remote: Any Stream, Reachable As Itself

Status: design target, not implemented. Subordinate to
[`dao.stream.md`](./dao.stream.md), which is the contract and wins on any
disagreement. Every sentence below is a rule. It replaces the earlier serve
draft (git history, `docs/design/dao.stream.serve.md` at `71f3fb93`) and
succeeds `dao.jing.remote`, deprecated whole when this lands (plan section 1).
Slices and companion edits are in
[the implementation plan][impl-plan].

## 1. Invariant, impossibilities, non-goals

The governing requirement, in the owner's words:

> any implementation of dao.stream can be mechanically exposed via websocket
> or udp and communicate p2p. client-server is just one stigmergic behavior
> that interpreters implement. It should be able to traverse a NAT. A toy
> example would be a string with a dao.stream wrapper that can be exposed on
> a websocket on a remote interpreter.

> There should be no concept of a server or client. its P2P but client/server
> model can be implemented by convention. there is no priviledge server or
> client.

`dao.stream` is an abstraction boundary, not a network boundary. This
document makes it a network boundary too, and keeps every network concept on
its own side: nothing here adds an operation, an outcome or a key to the
contract beyond the five amendments listed in implementation plan section 2
(OD-1, OD-2, OD-3, the `:dao.stream/refused` row, and the composed-handle
sentence).

**Impossibilities, stated plainly.**

- Direct exchange needs one end that can receive an unsolicited packet, or a
  coordinated simultaneous send. Where neither exists, a third peer with a
  reachable address is required (section 4).
- The unreachable cases are named by mapping and filtering behavior, not
  product name: endpoint-dependent mapping and filtering on both sides (a
  symmetric NAT; the strict paths of a carrier-grade NAT, which is address
  sharing and not one behavior, since other carrier-grade paths hole-punch)
  defeats punching, and relay is the workaround (section 4).
- A browser cannot listen. It dials, or it is reached through a third peer.
- Every peer needs an outbound path to a reachable WebSocket peer or relay.
  If UDP is blocked and no such path exists, communication is impossible.
- Everything a channel carries is readable by every peer that carries or
  receives it until encryption is composed, and the encryption this version
  specifies is value-level only: a relay still sees every envelope,
  identities, ops, cursors, ids, sizes and timing included (section 7).
- UDP messages are bounded by a composed maximum (section 3.2).
- A reclaimed lease means the resource is gone, not disconnected (section 6).
- A false lapse under partition is possible (`dao.lease.md`, Limits).

**Non-goals.** Authentication, authorization and key distribution (section 7
names the seam; `dao.shibi.md` owns the rest); channel-level encryption
(deferred, section 7); WebRTC data channels; browser inbound; readiness
notification; exactly-once delivery.

## 2. The design: mirror and reflection

One sentence: a peer that holds a handle answers the handle's own operations
as data over a channel; a peer at the other end holds a handle whose
operations are those questions asked across the channel, with the source's
outcome maps returned verbatim. Four concepts.

- **channel**: two ordinary handles with exactly two ends, a writer whose
  `append!` carries one value toward the other end and a reader positioned
  on the values the other end sent; built from a WebSocket attachment and
  its projection (3.1), a UDP socket adapter and its deposit medium (3.2),
  two ring buffers in one process, or two reflections served by a third
  peer (3.3).
- **table**: composition data on a peer, `{identity {:handle h :surface
  S}}` plus the optional keys of sections 6 and 7. A plain map the
  composition owns, not a registry (`dao.stream.md`, No hidden global
  state).
- **mirror step**: the pure step a peer runs over a channel to answer
  requests against its table. It holds no state between calls beyond the
  channel reader's cursor.
- **reflection**: the handle `attach!` returns for a remote descriptor. A
  **link**, one per channel, holds what the reflections on that channel
  share.

Every peer may run any number of mirror steps and hold any number of
reflections, on the same channel, in both directions. No request names a
kind of peer; the protocol has no peer identity at all. A peer is a channel
end.

### 2.1 Wire shapes

Two logical shapes cross every channel. Keys the contract owns stay under
`:dao.stream/`; keys this document owns are under `:dao.stream.remote/`,
the transport-owned namespace rule of `dao.stream.md` (Envelopes).

```clojure
;; request
{:dao.stream/identity        id
 :dao.stream.remote/op       op        ; :dao.stream/descriptor |
                                       ; :dao.stream/cursor |
                                       ; :dao.stream/next | :dao.stream/append!
 :dao.stream.remote/args     [...]     ; []  | [anchor] | [cursor] | [value]
 :dao.stream.remote/id       n         ; asker-minted, unique per channel
                                       ; reader the asker holds
 :dao.stream.remote/budget   k}        ; optional, next only, a positive integer

;; answer, the ordinary case: the contract's outcome map for the requested
;; operation, verbatim, plus
{:dao.stream.remote/id       n
 :dao.stream/identity        id
 :dao.stream.remote/surface  S         ; descriptor answers only: the
                                       ; entry's declared surface
 :dao.stream.remote/more     [...]}    ; optional, next only: further
                                       ; outcome maps in order

;; answer, a protocol error: one shape for every operation, a key instead of
;; an outcome map, because no operation's outcome set can carry it
{:dao.stream.remote/id       n
 :dao.stream/identity        id
 :dao.stream.remote/error    e}        ; one of the three errors below
```

A request is one value on the channel writer; an answer is one value on the
other end's. The request map is open: keys not named here pass to the mirror
step's middleware chain (section 7) and are otherwise ignored. A value that
is neither a well-formed request nor a well-formed answer is dropped as a
diagnostic below the mirror step and the link, as malformed wire input
(`datom.world.md`, Host Boundaries). Wire ops are exactly four: `close!`
never crosses, because the mirror holds nothing per remote party to close
and the owner's close is never remote; `attach!` never crosses, because it
consumes a descriptor and produces a local handle.

**Protocol errors.** The mirror answers three errors with the error key,
never by fabricating an outcome the operation's closed set forbids
(`dao.stream.md`, Result Convention; Surfaces):

- `not-found`: the identity is absent from the table, whether never served,
  retired by policy, or reclaimed with its lease. An identity this peer does
  not serve has no operations and no surface, so this one error is the
  honest answer for all four ops.
- `no-surface`: the op is outside the entry's declared surface, under the
  fixed mapping: `cursor` and `next` need `:reader`, `append!` needs
  `:writer`, `descriptor` needs no surface and is always answerable.
- `oversize`: the channel writer refused the answer value itself
  (`invalid-value`); the read is not skipped and no cursor advances past
  it. Large media travel as `dao.jing` addresses (`dao.stream.md`,
  Granularity).

All three names live under `:dao.stream.remote/`. The declared surface is
answer data, not descriptor data: the remote descriptor (2.2) carries no
surface, and the `descriptor` answer carries it under
`:dao.stream.remote/surface`, the only place the surface is learned.

### 2.2 The remote descriptor

```clojure
{:dao.stream/type      :dao.stream/remote
 :dao.stream/identity  id                      ; the served logical stream
 :dao.stream/channel   <channel descriptor>}   ; section 3: ws, udp or pair
```

The descriptor names a stream and a channel. It carries no authorization, no
surface and no anchors; the mirror answers those. Two remote descriptors
that name one identity through different channels reach one stream, as the
contract's Envelopes section requires of descriptors for one logical stream
served by one transport (OD-3 (a), accepted). Attaching through this
transport never creates: a dynamic dispatch table has an
`:dao.stream/attach` entry for `:dao.stream/remote` and no
`:dao.stream/create` entry.

### 2.3 The mirror step

`(mirror-step table chan-reader cursor chan-writer) -> cursor'`. For each
request read from `chan-reader`, in order, bounded by a composition budget:

1. Look up `:dao.stream/identity` in the table. Absent: append the answer
   with `:dao.stream.remote/error :dao.stream.remote/not-found`.
2. Present, but the op outside the entry's declared surface under 2.1's
   mapping: append the answer with
   `:dao.stream.remote/error :dao.stream.remote/no-surface`.
3. Otherwise answer `:dao.stream/descriptor` from the entry's handle
   directly, adding `:dao.stream.remote/surface`; and apply
   `:dao.stream/cursor`, `:dao.stream/next` and `:dao.stream/append!` to it
   through `dao.stream.middleware/apply-request`
   (`dao.stream.middleware.md`), with context
   `{:dao.stream.remote/channel <channel attachment identity>}`. The budget
   chase below runs the same `next` through the same path.
4. Append the answer with `:dao.stream.remote/id` and
   `:dao.stream/identity`.

For `:dao.stream/next` with a budget `k` the mirror may follow the successor
cursor of each `ok` outcome up to `k - 1` further times and place the
further outcome maps, in order, under `:dao.stream.remote/more`. The first
outcome is the answer map itself. The vector ends at and includes the first
non-`ok` outcome when one occurs, so `blocked`, `end` and `gap` reach the
reader with the source's own recovery cursor. A mirror that ignores the
budget is correct. A value the channel cannot carry is answered with
`:dao.stream.remote/error :dao.stream.remote/oversize` in place of that
element (2.1).

Steps 1 to 4 are the mirror's only contacts with the entry's handle: no
answer path calls a handle outside `apply-request`, so nothing bypasses the
entry's middleware or its declared surface, and a reflection mints every
anchor, `:oldest` and `:newest` included, by request.

The mirror never constructs, parses or rewrites a cursor, an anchor or an
outcome. The served stream is the original: its cursors, its positions, its
`gap`, its `end`. This is the whole basis of the claim in the title, and it
rests on cursors being plain data that survive the codec (OD-3 decision 2,
accepted). A handle whose cursors hold a host object cannot be served, and
a composition refuses to enter it into a table.

### 2.4 The reflection

`attach!` on a remote descriptor returns `:dao.stream/ok` at once with a
reflection handle and a local, opaque `:dao.stream/attachment`. This is the
contract's deferred remote confirmation (`dao.stream.md`, attach outcomes).
The host-composed attach closure keeps one link per channel descriptor,
shared by every reflection through that channel. A link holds: the channel
writer; the channel reader and the link's own cursor on it; the set of
outstanding request ids with the request each was sent for; filed answers
keyed by id; an optional event writer; and the policy datum
`:dao.stream.remote/resend-after`.

On attach the link sends one `descriptor` request for the identity. Its
answer is the reflection's confirmation. The `not-found` error marks the
reflection **gone**; `ok` records the source's descriptor and declared
surface. Either is appended to the event writer when one is composed.

**Drain.** Every operation on any reflection first reads the link's channel
reader to `blocked`, filing each answer whose id is outstanding, installing
each `:dao.stream.remote/more` outcome at the cursor that precedes it, and
filing each protocol error under its id. Answers whose id is not
outstanding are dropped. Non-answer values are dropped as diagnostics.
This is where the asynchrony goes (`dao.stream.md`, Where the asynchrony
goes): the caller's own polling is the cadence, and no driver step exists.

Then the operation answers as follows.

- `descriptor`: local. `ok` with the remote descriptor it was attached with
  and `:dao.stream/identity`. Same identity as the source's own descriptor,
  different reachability.
- `cursor` `a`: a filed answer for `a` is returned, and a filed protocol
  error is translated per the rules below. Otherwise a `cursor` request for
  `a` is sent if none is outstanding, and the answer is `transport-error`
  with `:dao.stream/retry? true`: the source's map, `invalid-anchor`
  included, arrives later as an answer.
- `next` `c`: a filed answer for `c` is returned and forgotten. Otherwise a
  `next` request for `c` is sent if none is outstanding, and the answer is
  `blocked`: nothing is observable at this position through this handle
  yet.
- `append!` `v`: send the request and return the channel writer's outcome.
  `ok` is acceptance on the outbound path, exactly `dao.stream.md`
  Writing's rule for a transport that carries values toward a stream
  elsewhere. The source's outcome is appended to the event writer when it
  is filed, correlated by id; without an event writer it is dropped.
- `close!`: local. The reflection forgets its outstanding ids; afterwards
  `append!` answers `closed`, `cursor` answers `closed`, `next` answers
  filed outcomes then `end`. Nothing crosses the wire.

**Protocol errors at the reflection.** A filed `not-found` marks the
reflection gone; once gone, every later `cursor`, `next` and `append!`
answers `transport-error` with
`:dao.stream.remote/reason :dao.stream.remote/not-found`, while
`descriptor` still answers `ok`: a name outlives what it named. A filed
`no-surface` or `oversize` marks nothing; each is returned, for the
operation that caused it, as `transport-error` with
`:dao.stream.remote/reason` set to the error's name. A reflection marked
gone is how a remote holder observes reclaim (section 6). A request send
the channel writer refuses with `full` (establishment, back pressure)
leaves the request unsent and the operation answers as though unanswered:
the retry cases above, `blocked` for `next`.

**Channel loss.** A channel ends when its reader answers `end`: the
projection closed it (3.1), the pair adapter closed it on an `in` gap or
end (3.3), or the medium is gone. The link then abandons its outstanding
ids and reports each
abandoned `append!` on the event writer as `append-unknown` (2.5), and
answers later `cursor` and `next` `transport-error` with
`:dao.stream.remote/reason :dao.stream.remote/channel-gone`, not retryable;
`append!` keeps answering the channel writer's own outcome, `closed` once
the channel is down. Filed answers are still returned. Reattachment is the
caller's policy, by `attach!` on the same descriptor.

**Refused.** A filed `:dao.stream/refused` is returned verbatim for the
operation it answers and marks nothing: refusal is per operation
(`dao.stream.md`, Result Convention).

**Declared nature.** A reflection declares the surface its remote
descriptor's source declares plus `:closable`, learned from the attach
probe's answer (2.1); before that answer it declares
`#{:reader :writer :closable}`, and an operation the source turns out to
lack is answered as the `no-surface` case above. Its `blocked` is
handle-relative, as the contract's Concurrency section already makes every
outcome: one perspective at one moment. Its excluded outcomes: `next` never
answers `cursor-mismatch` or `invalid-cursor` from its own judgement, only
relayed, and `append!` never answers the source's `full`, which arrives on
the event writer.

### 2.5 Loss and resend

The reflection reads no clock. A link's `:dao.stream.remote/resend-after k`
means: an outstanding `descriptor`, `cursor` or `next` request is re-sent
when the caller has asked `k` further times and it is still unanswered.
`k` is unbounded on an ordered reliable channel and small on UDP. Those
three operations are idempotent, so a duplicate request recomputes the same
or a later, equally true answer. `append!` is never re-sent:
deduplication and correlation are the payload's (OD-2, accepted), and an
append whose answer never arrives has unknown effect, reported on the
event writer as `{:dao.stream.remote/event :dao.stream.remote/append-unknown
:dao.stream.remote/id n}` when the reflection is closed or the channel ends.
There are no sessions, no acknowledgement vectors, no windows and no result
retention at the answering peer: the cursor is the sequence number for
reads, and `gap` remains the source's declaration, never a channel artifact.

## 3. Channels

A channel declares a portable value domain, a frame budget, and whether it
is ordered and reliable; the link reads the last as composition data for
`:dao.stream.remote/resend-after`, and nothing else in section 2 changes
per channel.

### 3.1 WebSocket

`dao.stream.ws` as specified in `dao.stream.ws.md` is the socket layer, and
a channel is one of its attachments plus a projection. **Reused**: the
attachment model and identity; the adapter that deposits every event as
`{:ws/attachment id :ws/event e :ws/value v}` on the medium the composition
wired; the writer handle, whose `append!` frames each value as one
`{:ws/frame :ws/value :ws/value v}` message in the negotiated codec profile
(Transit-JSON text or the CBOR binary profile); the bounded acceptance
handoff with slot and acknowledgement; the resolution and lifecycle
vocabulary. Ordered and reliable; frame budget unbounded.

**New, the projection.** The mirror and the link speak raw request and
answer maps, so each channel end composes one step, `ws-project`, between
the ws deposit medium and the channel. The channel reader is a ring buffer
the composition wires; the channel writer is the ws handle itself.
`ws-project` holds the reading cursor on the attachment's traffic medium,
keeps the events whose `:ws/attachment` names this channel, appends the
`:ws/value` of each `:ws/payload` onto the ring buffer, and drops
`:ws/error` diagnostics. A terminal lifecycle event for the attachment
(`:ws/closed`, `:ws/ended`) or a failure resolution (`:ws/not-found`,
`:ws/transport-error`) makes `ws-project` close the ring buffer: channel
loss is then the link's `end` observation (2.4). `ws-project` is a step the
composition drives at its own cadence, as it drives `endpoint-step`.

Direction is establishment, not authority. A WebSocket has a dialer and an
acceptor because TCP does. Once established the channel is symmetric and
nothing in this document remembers who dialed. Hosts: clj, cljs on Node and
cljd may dial or accept (`src/clj/dao/stream/ws/jvm.clj`,
`src/cljs/dao/stream/ws/node.cljs`, `src/cljd/dao/stream/ws/dart.cljd`);
cljs in a browser may only dial (`src/cljs/dao/stream/ws/browser.cljs`).

### 3.2 UDP

`dao.stream.udp` is a new channel with the WebSocket deposit model: a socket
retains nothing, so it has no reader surface, and its adapter deposits every
datagram as an event carrying the datagram's source address and its decoded
value onto the medium the composition wired. The socket itself, as raw
addressed datagrams, is the layer beneath this channel:
[`dao.stream.datagram.md`](./dao.stream.datagram.md), whose section 7 says how
this channel is fed from it and whose `bind-host`/`bind-port` descriptor keys
name the local socket, not the destination named here.

- Descriptor: `{:dao.stream/type :dao.stream/udp :dao.stream/identity
  <channel identity> :dao.stream.udp/host h :dao.stream.udp/port p}`.
  `attach!` binds or reuses a local socket and returns a handle whose writer
  path is datagrams to that address.
- One CBOR value per datagram, canonical profile. The budget covers the
  whole encoded datagram, fragment envelope included: at most 1200 bytes,
  the figure `dao.jing.dht.node` already uses to clear common path MTUs.
- **Fragmentation is channel-internal.** An encoded message over budget is
  split into `k` datagrams, each
  `{:dao.stream.remote/id n :dao.stream.udp/part i :dao.stream.udp/parts k
  :dao.stream.udp/bytes b}`, where `n` is the carried message's own request
  id. The receiver reassembles by `[channel attachment identity,
  source-address, direction, id]`, because ids are unique only per channel
  reader and one socket may serve several attachments, and delivers only a
  complete message. Loss of any part is loss of the whole message,
  recovered by the link's resend rule. Reassembly is bounded by two
  composition data, `:dao.stream.udp/max-message-bytes` (default 64 KiB)
  and `:dao.stream.udp/max-partial-messages`, evicting the oldest partial
  message; no clock. Neither the mirror step nor the link sees a fragment.
- Replies go to the datagram's source address, never to an address claimed
  in a payload.
- A send may name an explicit destination from an already-bound socket. Hole
  punching depends on it (section 4).
- Unordered and best-effort. Attachment identity is the remote host and port
  as the socket saw them; it is the reflexive address a meeting peer
  republishes.

Hosts: clj (`DatagramSocket`), cljs on Node (`dgram`), cljd
(`RawDatagramSocket`). Browsers have no UDP.

### 3.3 Pair

A pair descriptor names two remote descriptors:

```clojure
{:dao.stream/type      :dao.stream/pair
 :dao.stream/identity  <channel identity>
 :dao.stream.remote/in  <remote descriptor>    ; this peer reads here
 :dao.stream.remote/out <remote descriptor>}   ; this peer writes here
```

`attach!` on it returns a channel whose reader is a reflection of `in` and
whose writer is a reflection of `out`. When a third peer serves both
streams from its table, two peers that cannot reach each other have a
channel, and the third peer runs nothing but mirror steps over two ring
buffers; the requests and answers inside are values in its streams it never
interprets. Streams are values sent through streams. Ordered; best-effort
under the inboxes' retention. **An `in` gap ends the channel.** The pair
adapter holds the reading cursor on `in`; when `next` there answers `gap`,
frames were lost and the adapter cannot say which, so it closes the channel
reader for this link. The link observes `end` and runs its channel-loss
path (2.4): outstanding ids are abandoned, not re-sent, each abandoned
`append!` is reported `append-unknown`, and reattachment is the caller's,
by `attach!` on the same pair descriptor, which mints a fresh reading
cursor on `in` at `:newest`. The gap is never reported to an inner reader
as a source's `gap`. Double framing consumes budget: an inner value inside
an outer `append!` must fit the outer channel's budget with both envelopes.

## 4. Reachability and NAT

A channel needs one end that can receive an unsolicited packet, or a
coordinated simultaneous send. The protocol cannot change that; it makes
the workarounds conventions. One peer, M below, is reachable and serves two
ordinary streams from its table; which peer that is carries no protocol
meaning, and any reachable peer can take its place.

- One side receives unsolicited (public address, forwarded port, or
  endpoint-independent mapping and filtering): direct; the other side dials,
  WebSocket or UDP.
- Endpoint-dependent mapping or filtering on one or both sides, punching
  still possible: hole punch through M (below), UDP allowed.
- Endpoint-dependent mapping and filtering on both sides (a symmetric NAT;
  the strict carrier-grade paths): punching is unreliable; relay through a
  pair served by M.
- UDP blocked: WebSocket only, to a reachable peer, and the browser rule
  applies; without one, impossible (section 1).
- Browser at one end: it dials, and two browsers relay through a pair. A
  WebRTC data channel is a future channel type whose candidates ride the
  same meeting stream.

**Hole punching, as a convention.** M serves a `meet-requests` stream with
surface `#{:writer}` and a `meet-board` stream with surface `#{:reader}`,
both ordinary table entries. Over a UDP channel a peer appends
`{:meet/here <name>}`; M's interpreter reads the request and the channel
attachment identity it arrived on, which for UDP is the reflexive address,
and appends `{:meet/seen <name> :meet/reflexive {:host h :port p}
:dao.lease/lease L}` to the board. Both parties of a call read the board,
then each sends a `descriptor` request with a fresh id to the other's
reflexive address from the same socket M observed. A path exists when an
answer, even a `not-found` error, carrying one of this peer's outstanding
ids arrives from the expected address; the first inbound datagram alone
confirms nothing. That is the whole of STUN, as facts on a stream; no probe
frame exists.

**Bounded meeting work.** M's interpreter bounds its own work: the board's
retention (composition data, evict-oldest by default), the number of active
pairs in its table, and the fanout of one driver step. A meeting request M
cannot honor within its bounds is refused by a gate on its `meet-requests`
entry and observed by the asker as `:dao.stream/refused` through its
reflection: exhaustion is a present answer, not silence. A peer reachable
through M is reachable only while its channel to M lives, so its keep-alive
cadence is bounded by the NAT binding's lifetime, tens of seconds for UDP,
longer for TCP.

**Relay, as a convention.** A peer asks M, through `meet-requests`, for a
pair; M creates two ring buffers, enters them in its table under a lease,
and posts their remote descriptors on the board; the asking peers attach a
pair channel over them (3.3). M interprets nothing it relays, and that two
unreachable peers need M at all is a fact about the network, not a role in
this protocol. Reflexive addresses on a board are readable by every reader
of the board; this convention claims no private delivery.

## 5. Conventions that are not protocol

None of these has a frame. Each is peers using sections 2 and 3.

**The toy.** Peer B holds a handle `s` over the single value `"hello"`,
identity `"str-1"`, complete history, surface `#{:reader}`. B's table is
`{"str-1" {:handle s :surface #{:reader}}}`. B accepts WebSocket connections
as `dao.stream.ws.md` specifies and drives, per accepted connection,
`ws-project` and then `mirror-step` over the projected reader and the
socket handle. B publishes `{:dao.stream/type :dao.stream/remote
:dao.stream/identity "str-1" :dao.stream/channel <B's ws descriptor>}`.
Peer A calls `attach!`; the link dials and sends the `descriptor` probe;
the answer carries `:dao.stream.remote/surface #{:reader}`. A calls
`cursor` with `:oldest`: no answer filed, the link sends the request and
answers `transport-error` with `:dao.stream/retry? true`; on the next call
the drain files the source's `ok` with `c0`. `next c0` proceeds the same
way: `blocked`, then `ok "hello" c1`, and `next c1` is `end`. Nothing knew
it was a string.

**Request and response service.** A peer S enters `requests` with surface
`#{:writer}` and `answers` with surface `#{:reader}` in its table and runs
an interpreter over the local ends; a peer P attaches reflections to both,
mints `:newest` on `answers` first, appends `{:svc/request r ...}` through
`requests`, and reads `answers` until it observes `:svc/request r`. Request
ids are self-minted random values so callers sharing one `answers` stream
do not collide; a caller that needs private answers asks S for a pair. S is
a "server" only in that its table has those entries and its interpreter has
that policy, and either peer may serve such a pair to the other over the
same channel. This is Linda's request medium, and the shape `yin.repl` and
content lookup take (implementation plan, section 1). The meeting board and
the relay pair are section 4's instances of it.

**Peer names.** A name is a self-certifying hash of a public key, the
kickoff-hash form of `dao.stream.discovery.md`. It appears only inside
convention payloads. Nothing authenticates it until authentication exists.

## 6. Lease integration

A served stream is a network resource, so its lifetime beyond one call is
governed by `dao.lease.md`. The judge is composed inside the peer that
possesses the resource, as that contract requires. What a lease governs:

- A table entry a peer serves by its own policy: no lease; the peer retires
  it when it likes.
- A table entry served for a remote party (a stream a migrated continuation
  still reads): yes; the remote party holds it, the holding peer grants and
  judges, the identity is the subject.
- A relay pair on a meeting peer: yes; the requesting peer holds it, the
  meeting peer grants and judges, the two identities are the subject.
- Link state, UDP reassembly buffers, channel sockets: no; governed by close
  or by a bounded buffer.
- NAT mappings and punch state: no; possessed by the NAT box, so no judge
  can exist, and keep-alive cadence is the peer's own (section 4).
- Board postings: no; records under the board's retention, each carrying the
  lease id of the registration or pair it belongs to, and a reader treats a
  posting whose lease has a `:lapsed` on the board as stale.

**Mechanism: no new wire shape.** A grantor enters two conventional streams
in its table, `lease-proposals` with surface `#{:writer}` and
`lease-grants` with surface `#{:reader}`, published as remote descriptors
beside its meeting descriptors. A holder appends a proposal through a
reflection on the first and reads its grant through a reflection on the
second. The grant creates one more table entry, a ring buffer with surface
`#{:writer}`, and names its identity as `:dao.lease/holder`: this is the
holder's **renewal medium**, and whatever lands on it is attributed to the
holder by the medium, the attribution resolver `dao.lease.md` names as
per-author media. The judge reads it with a local cursor. The renewal
medium lives and dies with its lease; the reclaim procedure removes the
subject entries and the renewal entry together, idempotently, and reports
success. A table entry under lease carries `:dao.lease/lease L`.

Until ShiBi exists, a renewal medium is reachable by anyone who learns its
descriptor and accepts a third party's renewal, extending a lease it does
not hold: attribution needs no credential, extension may want one, and a
composition that must not accept it composes a gate on the renewal entry.

**Renewal.** `append!` of `{:dao.lease/event :dao.lease/renewal
:dao.lease/lease L}` on the holder's reflection of its renewal medium. The
reflection's `ok` is outbound acceptance only; the holder advances its
renewal bound only on the source's `ok` observed on the link's event
writer. A holder with no event writer stops at its bound early, never late.

**Reclaim observed.** The mirror answers the `not-found` protocol error for
a reclaimed identity and the reflection marks itself gone (2.4). The holder
may also read the grantor's own `:lapsed` record through its reflection on
`lease-grants`: that is observing the grantor's stream, not carriage. And
the holder stops at its own bound regardless.

**Expiry versus channel loss.** Channel loss is the absence of answers or
the channel's `end` (2.4); the table entry persists, so a holder that
reconnects within duration plus tolerance finds its entry, its cursors and
its lease intact. Expiry is a present answer, the `not-found` error, on a
healthy channel. Only expiry frees anything. A meeting peer that restarts
loses its table and ledger, so every pair is gone, holders observe
`not-found` and re-propose; this is `dao.lease.md`'s reclaim-and-regrant
with an empty inventory, correct because the table is process-scoped.

Units are the composition's; these conventions fix `{:ms 1 :s 1000}`.

## 7. Middleware and the ShiBi seam

Middleware preserves positions and outcome kinds as specified in
`dao.stream.middleware.md`. Network attachment points are the table entry
(gate, value encryption, redaction or metering), the reflection (credential
presentation, value decryption or metering), and the channel (reserved for
deferred channel-level wrapping).

Keys and chains are composition data, never descriptor fields. Value-level
encryption hides values only; identity, ops, cursors, sizes and timing stay
visible. Channel encryption is deferred: a relay sees every envelope and
any value not hidden by middleware. A future channel wrapper must order
codec, encryption and fragmentation in that order. Key distribution is
unspecified; use library ciphers.

The capability-agnostic seam is an opaque per-request credential key,
mirror-supplied channel context, mirror-side gate and reflection-side
`present`; refusal is `:dao.stream/refused`. No token or verification
policy is specified. The gate consumes an index-published decision. The
one ShiBi fit this version chooses: its index interpreter publishes the
tuple space's current query value as the decision, and its query
interpreter is `verify`, a query over that value with the request as
bindings, the shape `dao.space.query/q` has. `dao.shibi.md` states the
adapter contract and its limits (one snapshot, bounded cost, no waiting);
request-specific decisions awaited over streams are a non-goal there.

Lease attribution uses per-author media; renewal gates are optional.

## 8. Unification and contract boundary

`dao.jing.content` succeeds `dao.jing.remote` whole. The implementation
plan lists the successor vocabulary (section 1), the network-path fates
(section 5) and the five `dao.stream.md` amendments (section 2), which add
no network or capability concept.

[impl-plan]: ./dao.stream.remote.implementation-plan.md
