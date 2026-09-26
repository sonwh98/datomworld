# DaoStream Serve: Any Stream, Reachable As Itself

Status: design target, subordinate to `dao.stream.md`. That document is the
contract; where the two disagree, the contract wins. This document
specifies how a handle on any transport is made attachable from another
host over WebSocket or UDP, including behind NAT and peer to peer, without
a server, a client, or a privileged node.

## 1. The problem

The contract separates a handle (host-local, the protocol implementation),
a logical-stream identity (plain data, the same through every handle), a
descriptor (reachability data naming the stream) and a cursor (immutable,
interpreter-owned, bound to the logical stream). A handle never travels. A
descriptor does. A cursor does once `dao.stream.md` OD-3 is accepted.

Today one transport crosses a network, and it does not serve a stream; it
serves a copy of one. The WebSocket composition in `dao.stream.serving`
forwards a source's values into a socket; the far side's adapter deposits
them into a local medium with the local medium's identity and positions
(`dao.stream.md`, OD-3: "what a remote host reads is a copy, not the
stream"). That is the right shape for broadcast and the wrong shape for
the Universal Continuation Format, whose stream cells travel with a kept
cursor into the original logical stream and must observe the source's own
`gap` when the source evicts (`yin.vm.universal-continuation-format.md`,
7.4.3 and 7.5.3).

So what must cross the network is not values but operations and outcomes.
The contract already makes this possible: every operation's result is an
open plain-data map, every outcome set is exhaustive, `next` is
non-destructive and keyed by a cursor, and both existing cursor shapes are
plain data. The contract is a wire protocol that has not yet been sent.
This document sends it.

**A served stream is the original logical stream**, reached through a
proxy handle that hands the reader exactly the cursors the source minted
and hands the source exactly the cursors the reader held. It is never a
copy with new positions.

The governing invariant, stated once: any implementation of `dao.stream`
can mechanically be exposed over WebSocket or UDP and traverse NAT in a
peer-to-peer use. There is no concept of a server or a client in this
protocol and no privileged node. A client/server shape may be built on
top of it by convention, and this document names two such conventions
(section 7.3 and section 8) so that they are recognizable as conventions.

## 2. Vocabulary and layering

- A **peer** is a host running this protocol. Every peer runs both steps
  below; nothing distinguishes one peer from another except which streams
  it holds and which channels it has.
- **`serve-step`** answers sessions on the streams in the peer's serve
  table (section 5).
- **`proxy-step`** drives the sessions the peer opened and fills its
  proxy handles (section 4).
- A **channel** is a writer handle and a reader handle the composition
  supplies (section 6). A WebSocket or UDP attachment with its deposited
  medium is a channel; two ring buffers in one process are a channel; two
  proxied streams served by a third peer are a channel.
- A **session** is one attachment to one served logical stream over one
  channel (section 3.1).
- A **proxy** is the handle `attach!` returns for a serve descriptor.

The layers depend one way, downward only:

```
contract-generic reader/writer code     sees a handle; knows nothing below
  |
proxy handle + proxy-step  --+
  |  serve frames (data)     |  dao.stream.serve, this document
serve-step + serve table   --+
  |
channel                       a writer handle and a reader handle
  |
reachability                  how a channel is established: direct,
                              punched, or through a peer that serves an
                              inbox pair under the meeting convention
```

Three rules hold across the layers:

- **The serve layer owns the vocabulary of operations, sessions and
  outcomes.** A channel carries opaque values and lifecycle events. No
  channel inspects a serve frame; no serve frame names a channel.
- **Reachability lives in the descriptor** (`dao.stream.md`, Envelopes)
  as an ordered list of candidates. The serve layer never interprets an
  address; it hands each candidate to the host's channel dispatch.
- **Every layer is stepped.** Proxy, serving side and every convention
  above them are pure transitions over explicit state, driven by a
  composition-owned driver that passes `now`. Nothing waits, nothing
  schedules itself, nothing registers a callback above the channel
  adapter. This is `dao.stream.md` OD-5 applied to a whole subsystem.

Every channel, whoever dialed it, is attached to both steps at both ends.

## 3. The serve protocol

### 3.1 Sessions

A session is one attachment to one served logical stream over one
channel. It is the serve-layer unit that carries `:dao.stream/attachment`.
One channel carries many sessions, so that channels stay dumb and so that
one channel between two peers carries every stream a migrated
continuation references.

A session is identified by `[opener-peer-id n]`, where `n` is unique among
the sessions that peer has opened on this channel and is never reused on
it. Both ends of a channel may open sessions, and a channel may be a
shared medium with many opening peers (section 6.4), so the peer id is
part of the identity. On a channel with one peer at each end the two ids
need only differ; on a shared medium session identity rests on peer-id
uniqueness, which is the same unauthenticated uniqueness the meeting
convention rests on (section 7.3). A collision there is two peers
claiming one session, and the serving side treats the second `:serve/open`
as a conflict (section 3.2).

Within a session the opener allocates every request id from one strictly
increasing sequence shared by all operations, as `dao.stream.rpc` already
does. Request ids are therefore ordered per session, which the append
rule of section 3.4 relies on.

Roles are per session, not per channel or per peer: in one session one
peer holds the stream and the other attaches to it, which is the
contract's own asymmetry between a handle on the stream and a handle on an
attachment. On one channel, peer A may serve stream X to B while B serves
stream Y to A.

### 3.2 Frames

Serve frames are the application values a channel carries. Keys are
qualified under `:serve/...` with one namespace separator, as the
WebSocket transport's rule requires so the vocabulary reads on clj, cljs
and cljd. The operation and its arguments ride in the `dao.stream.apply`
envelope, which already owns request identity, operation and arguments.

```clojure
;; session control
{:serve/frame :serve/open     :serve/session s  :dao.stream/identity id}
{:serve/frame :serve/opened   :serve/session s
 :dao.stream/attachment a
 :dao.stream/surface #{:reader :writer :closable}
 :serve/oldest <cursor>  :serve/newest <cursor>}       ; anchors, 3.6
{:serve/frame :serve/disclaim :serve/session s}        ; not-found
{:serve/frame :serve/close    :serve/session s
 :serve/reason :ended | :detached | :conflict}         ; either direction

;; operations
{:serve/frame :serve/request  :serve/session s
 :dao.stream.apply/id n
 :dao.stream.apply/op :dao.stream/next
 :dao.stream.apply/args [cursor budget]}
{:serve/frame :serve/response :serve/session s
 :dao.stream.apply/id n
 :dao.stream.apply/ok <see 3.3>
 :serve/oldest <cursor>  :serve/newest <cursor>}       ; refresh, 3.6

;; channel liveness, per channel, also the punch probe (7.4)
{:serve/frame :serve/ping :serve/nonce k}
{:serve/frame :serve/pong :serve/nonce k}
```

That is the whole frame set. Nothing in it names a kind of peer. A frame
with an unknown `:serve/frame`, a missing required key, or a value outside
the channel's portable domain is a protocol failure of the channel (a
decode diagnostic, then teardown), never a serve outcome: the serve layer
only ever sees valid frames, exactly as the WebSocket adapter only
deposits valid events.

**Session control is idempotent.** `:serve/open` is the one frame that
creates state on the serving side, and over an unordered channel it is
retransmitted like any other, so its duplicates have a defined answer:

- A `:serve/open` for a session `[peer n]` that already exists on this
  channel with the same `:dao.stream/identity` is answered with the same
  `:serve/opened` (the same `:dao.stream/attachment`, the current anchors)
  and changes nothing: no attachment is re-minted, the append high-water
  mark of section 3.4 is untouched, held demands are untouched.
- A `:serve/open` for an existing `[peer n]` naming a different identity
  is answered `:serve/close` with `:serve/reason :conflict`; the existing
  session is untouched.
- A `:serve/open` for a `[peer n]` the serving side has closed is answered
  `:serve/close` with `:serve/reason :detached` and creates nothing. The
  serving side remembers closed session ids for its retry horizon (the
  same horizon that bounds retransmission, section 6.3); because an opener
  never reuses `n`, that set is bounded by the sessions closed within the
  horizon.
- `:serve/close` and `:serve/disclaim` are idempotent by construction: a
  duplicate names a session that no longer exists and is dropped as a
  diagnostic.

### 3.3 Mechanical derivation

The serving side for a handle `h` with declared surface `S` is the
`dao.stream.apply` handler table below, restricted to `S`. Nothing about
`h`'s transport appears anywhere; a string-backed stream, a ring buffer
and a durable log are served by the same five entries.

+--------------------------+-------------------+-------------------------------------------+------------------------------------+
| Wire op                  | Args              | The serving side computes                 | `:dao.stream.apply/ok` value       |
+==========================+===================+===========================================+====================================+
| `:dao.stream/descriptor` | `[]`              | `(descriptor h)`                          | the outcome map, verbatim          |
+--------------------------+-------------------+-------------------------------------------+------------------------------------+
| `:dao.stream/cursor`     | `[anchor]`        | `(cursor h anchor)`                       | the outcome map, verbatim          |
+--------------------------+-------------------+-------------------------------------------+------------------------------------+
| `:dao.stream/next`       | `[cursor budget]` | `next` repeated from `cursor`, following  | a non-empty vector of outcome      |
|                          |                   | each `ok`'s successor, at most `budget`   | maps, in order, verbatim; the      |
|                          |                   | times, stopping at and including the      | terminating non-`ok` (`blocked`,   |
|                          |                   | first non-`ok`                            | `end`, `gap`, ...) is its last     |
|                          |                   |                                           | element when one occurred          |
+--------------------------+-------------------+-------------------------------------------+------------------------------------+
| `:dao.stream/append!`    | `[value]`         | `(append! h value)`                       | the outcome map, verbatim          |
+--------------------------+-------------------+-------------------------------------------+------------------------------------+
| `:dao.stream/close!`     | `[]`              | `(close! h)` only when `h` is an          | `{:dao.stream/outcome              |
|                          |                   | attachment minted for this session;       |  :dao.stream/ok}`                  |
|                          |                   | never the owner's close                   |                                    |
+--------------------------+-------------------+-------------------------------------------+------------------------------------+

An op absent from `S` is answered with an apply error response, code
`:dao.stream.serve/no-surface`: the contract forbids answering a
nonexistent operation with an outcome map (Surfaces), and the apply
envelope has an error shape for exactly this. A proxy never exposes such
an op, so the error only ever reaches a misbehaving peer.

Outcome maps cross verbatim, including the cursors inside them. The serve
layer never constructs, parses or rewrites a cursor. This is the whole
basis of cursor-namespace preservation.

Three of the five entries deserve a note on when they are used:

- **`next`'s vector always ends where the source stopped.** `budget` is at
  least one, so the vector has at least one element. When the source
  answered `ok` `budget` times the vector holds `budget` `ok` outcomes and
  no terminator; otherwise the first non-`ok` outcome closes the vector
  and is part of it. The proxy installs that terminator at its cursor like
  any other outcome, which is how `blocked`, `end` and `gap` reach the
  reader with the source's own recovery cursor. Dropping it would make the
  tail of every batch indistinguishable from "not fetched yet".
- **`cursor` on the wire serves anchors the piggyback does not.** The two
  contract anchors are answered locally from the piggybacked cursors
  (section 3.6). A transport-owned anchor (a byte offset, a timestamp) is
  issued as a wire `cursor` request; until it is answered the proxy's
  `cursor` for that anchor answers `transport-error` with
  `:dao.stream/retry? true`, and once answered the outcome is relayed,
  `invalid-anchor` included. The serving side also uses the same
  operation on its own handle to refresh the anchors it piggybacks.
- **Two descriptors, one identity.** The wire `descriptor` returns the
  source's native descriptor, reachability as seen from the holding
  peer's own host (a ring buffer's, a memory log's). The proxy's own
  `descriptor` returns the serve descriptor it was attached with. Both
  carry the same `:dao.stream/identity`, which is what the contract
  requires of different descriptors for one stream; only the serve
  descriptor is meaningful reachability to a remote reader, and the proxy
  never issues the wire op. It is listed because every handle answers
  `descriptor` and the derivation is mechanical over the surface.

**Precondition on the source.** Its cursors and outcome maps must lie in
the channel's portable value domain. Both existing transports qualify (a
string identity and an integer position). A transport whose cursor holds
a host object cannot be served; an exporter refuses it before publishing
(section 13). This is the entire content of the one cursor profile,
`:dao.stream.serve/v1`: a cursor is plain data in the portable domain and
the source honors it after serialization.

### 3.4 Idempotence, loss and duplication

Four of the five operations are idempotent as the contract defines them:
`descriptor` and `cursor` are projections; `next` is non-destructive and
positioned by its argument; `close!` is idempotent by definition. A lost
request is re-sent; a lost response is re-requested; a duplicated request
recomputes the same answer or a later, equally true one. No sequence
numbers, acknowledgement vectors or sliding windows are needed for reads:
**the cursor is the sequence number, and `gap` remains the source's
declaration rather than a transport artifact.** This is what reconciles
UDP with kept cursors and gap, and why the reliability layer of the v1
UDP design is not revived (section 17).

`append!` is the one non-idempotent operation. The protocol makes it safe
by the rule OD-2 already states, that deduplication and correlation are
the payload's, applied at the serve layer so that no application has to:

- A session has at most one outstanding `append!`. The proxy does not
  issue the next until the previous is answered or the session is lost.
  This is `dao.stream.rpc`'s unsent-envelope discipline. It bounds what
  the proxy issues; it does not bound what is in flight, because a
  retransmitted copy of an answered append can arrive after a later
  append was performed.
- The serving side keeps, per session, an **append high-water mark**: the
  id of the last `append!` it performed and that append's outcome.
  Request ids are strictly increasing per session (section 3.1), so the
  rule is total: an append whose id is above the mark is performed and
  becomes the mark; one whose id equals the mark is answered from the
  recorded outcome without re-appending; one whose id is below the mark
  is an older duplicate whose original was already answered, and is
  answered with an apply error `:dao.stream.serve/stale` and never
  performed. The proxy classifies that answer as a stale-response
  diagnostic, since the id is no longer outstanding. The state is one id
  and one outcome per session, whatever the retry horizon, and a
  duplicate `:serve/open` never resets it (section 3.2). Under this rule
  reordering and duplication are harmless for writes, which is what
  completion criterion 4 tests.
- If the session is lost with an append unanswered, the proxy deposits
  `:serve/append-unknown` on its event medium with the retained value. It
  does not silently retry across sessions: the far end may have appended.
  This is OD-2's third state, named.
- A `:serve/request` that names a session not in `:sessions` (never
  opened, or in `:closed`) is dropped as a diagnostic and is never
  performed or answered. The high-water mark protects a live session; the
  absent session record protects a closed one, so a delayed append
  retransmit that arrives after `:serve/close` cannot re-append.

A stale response (an id no longer outstanding) is a diagnostic, not an
error, exactly as in `dao.stream.rpc`.

### 3.5 Batching

`next`'s `budget` bounds one response. The serving side answers with
consecutive outcomes; the proxy installs each `(cursor_i -> outcome_i)`
with `cursor_0` the requested cursor and `cursor_(i+1)` the successor in
`outcome_i`. The serving side also bounds the batch by the channel's frame
budget (section 6.3): it stops early when the next value would not fit
and returns what it has. A single value that cannot fit in one frame on
this channel is answered as `transport-error` with
`:dao.stream.serve/reason :oversize` in place of that element; the read is
not silently skipped and the cursor does not advance past it. Large media
belong in the payload as `dao.jing` addresses (`dao.stream.md`,
Granularity).

### 3.6 Anchors across a hop

`cursor` has no `blocked` outcome, yet a proxy cannot consult the source
synchronously. This design does not invent a proxy-namespaced cursor: that
would put a cursor in circulation that the source's own handle rejects,
violating "any handle on the same logical stream accepts it". Instead the
serving side piggybacks fresh `:oldest` and `:newest` cursors on
`:serve/opened` and on every response, and the proxy's `cursor` answers
with the most recently observed one.

This is honest under the contract's own definitions. A stale `:newest` is
earlier than a fresh one, so a composition that mints `:newest` before
invoking an operation still observes everything the operation causes; it
may additionally observe a few values appended between the last exchange
and the mint, which is re-seeing, never skipping. A stale `:oldest` may
point at a position since evicted, and the contract already says a fresh
`:oldest` is a position rather than a completeness claim: the reader
receives `gap` with the recovery cursor. Before the first `:serve/opened`
the proxy holds no anchors and `cursor` answers `transport-error` with
`:dao.stream/retry? true` (OD-1's key); the composition observes
`:serve/opened` on the proxy's event medium and mints then.

### 3.7 Held reads

Across a network a `blocked` answer costs a round trip, and a reader that
polls a proxied stream pays that round trip on every tick. A `next`
request may carry `:serve/hold true`. A serving side that honors it
records the demand in session state and, on each of its own driver steps,
re-polls the source, answering when the outcome is no longer `blocked` or
when its hold budget elapses, in which case it answers the `blocked` it
has. No operation waits: the session holds state and the step returns.

The recommended position, pending the owner's decision (section 15):
inbound delivery through a meeting peer is demand-polled; polling cadence
sets ongoing wire traffic and inbound-delivery latency. `:serve/hold` is
an optional v1 optimization, off by default, enabled per deployment, never
a condition of meeting reachability or acceptance. A serving peer may
ignore the flag and the proxy remains correct. A peer honoring it bounds
held demands, duration and work per step, and cancels holds on session
loss.

## 4. The proxy handle: declared nature

A proxy is the handle `attach!` returns for a serve descriptor. It
declares exactly the surface the `:serve/opened` frame reports, which is
the source's declared surface; until then it declares the surface the
descriptor's `:serve/surface` hint names, and a mismatch at open is a
session failure deposited as data. `descriptor` returns the serve
descriptor and the identity, total.

A proxy is composed as three explicit pieces: the handle (an object over
an explicit state atom, as the WebSocket handle is), an event medium the
composition wires and the channel adapter deposits into, and `proxy-step`,
which the composition's driver calls with `now`.

The handle's `next` is a read of the proxy's window, a bounded host-local
cache keyed by cursor value (structural equality; every cursor a reader
holds was minted by the source, through this proxy or another). A miss
records a demand in proxy state and answers `blocked`. `proxy-step` issues
demands as requests, installs responses into the window, opens sessions,
retries by the composition's policy, and evicts the window oldest-first.
The window is a cache, not retention: an evicted window entry is
re-fetched, and only the source ever answers `gap`.

+--------------+-----------------------------------------------------------------------------------------------+
| Op           | Produces                                                                                      |
+==============+===============================================================================================+
| `cursor`     | `ok` (last observed anchor, 3.6); `invalid-anchor` (relayed); `closed`; `transport-error`     |
|              | (`:establishing`, retryable, or channel failure)                                              |
+--------------+-----------------------------------------------------------------------------------------------+
| `next`       | every source outcome, relayed verbatim; `blocked` also for "not here yet"; `end` for          |
|              | positions beyond the window after the session ended; `cursor-mismatch` locally by identity;   |
|              | `invalid-cursor` for a non-map; `transport-error` for `:oversize` or channel failure          |
+--------------+-----------------------------------------------------------------------------------------------+
| `append!`    | `ok` (accepted into the session's outbound path, carrying `:dao.stream.serve/id`); `full`     |
|              | (one append already outstanding, or channel outbound full; transient); `invalid-value`        |
|              | (outside the channel's domain); `closed`; `transport-error`                                   |
+--------------+-----------------------------------------------------------------------------------------------+
| `close!`     | `ok`                                                                                          |
+--------------+-----------------------------------------------------------------------------------------------+
| `descriptor` | `ok`                                                                                          |
+--------------+-----------------------------------------------------------------------------------------------+

No outcome is excluded, so no exclusion needs a reason.

The proxy's writer surface is on the session's ordered outbound path, as
the WebSocket handle's is on the connection's; the source's answer to an
append arrives on the proxy's event medium as
`{:serve/event :serve/appended :dao.stream.serve/id n :serve/outcome m}`.
This is the contract's Writing rule and the WebSocket precedent; its
consequence for the UCF is stated in section 13.

Session loss is detachment, not ending: the proxy deposits
`:serve/detached`, `append!` answers `closed`, and `next` answers what the
window holds and then `end`, exactly as the contract specifies for a
closed attachment. Cursors bind to the logical stream, so the reader's
kept cursor survives; a fresh `attach!` with the same descriptor resumes
at it. Re-establishing a lost session under the same proxy handle,
answering `blocked` meanwhile, is a proxy policy the composition supplies
as data (`:reconnect {:attempts n :after-ms t}`); the default is none, so
nothing retries invisibly.

Two subtleties the contract forces:

- `end` from the source is relayed. `end` from session loss is local. Both
  mean "what this handle is on is closed" and both are correct. The
  `:serve/close` frame from the serving side carries `:serve/reason
  :ended` when the source's owner closed it, `:conflict` when the serving
  side refused a session-id collision (section 3.2), and `:detached`
  otherwise. The proxy deposits `:serve/ended` for the first and
  `:serve/detached` for the other two, so a composition can tell which it
  saw. A `:conflict` close is defensive: the proxy never initiates one.
  This is the WebSocket transport's "ending a served stream"
  distinction, moved to the serve layer.
- A proxy is not a retaining medium and has no `gap` of its own. It never
  produces a `gap` the source did not.

### 4.1 Two contract amendments this handle needs

The proxy's `blocked` and its anchors are honest only under two
definitions the contract does not yet state. Both are amendments to
`dao.stream.md` (section 16), reproduced here so this document is
self-contained.

Reading, `blocked`:

> No observation is available through this handle at this cursor now; the
> cursor does not advance, and the reader may retry later. A handle
> declaring deferred remote observation may return blocked while fetching
> even if the source already holds a value or has evicted that position.
> If a source observation completes, its outcome is supplied.

Cursors, anchors:

> A handle declaring deferred remote observation may return a
> source-minted anchor from its last completed source observation. Its
> :newest may precede the source's current tail but must never follow it;
> its :oldest may precede the currently retained head, in which case a
> subsequent next reports the source's gap.

A proxy declares deferred remote observation. Under these two
definitions, `gap` is only ever relayed, never synthesized, and `end` is
relayed or local (attachment exhausted), per the contract's Close section.

## 5. The serving side: serve-step and the serve table

Every peer has a serve table and runs `serve-step`; a peer whose table is
empty answers every `:serve/open` with `:serve/disclaim` and is otherwise
indistinguishable from any other.

State is a value:

```clojure
{:served   {identity -> {:handle h :surface S}}
 :sessions {[peer n] -> {:identity id :attachment a
                         :append-mark {:id n :outcome m}   ; 3.4
                         :held {...}}}
 :closed   #{[peer n] ...}}                               ; 3.2, bounded
```

`serve-step` takes the state and one inbound frame or the driver's `now`,
and returns the next state plus zero or more frames to send. The
composition owns the driver and the channel writer.

The serve table is composition data, not a registry: the peer decides what
it serves, adds and removes entries through `serve` and `unserve`
transitions on its own state, and the table's lifetime is the peer's
business. Nothing here is namespace-global and nothing is consulted
ambiently, which is the contract's "no stream table" preserved: a
descriptor naming an identity this peer does not hold is answered
`:serve/disclaim`, the authoritative `not-found`.

An entry's declared surface may be narrower than the handle's. A peer that
serves a request medium declares `#{:writer}` for visitors while keeping
its own reader handle locally; a peer that serves an announcement stream
declares `#{:reader}` while keeping the writer. The meeting convention
(section 7.3) relies on this.

The serving side is stateless with respect to reads. It holds no reader
positions; every `next` carries its cursor. This is what lets it survive a
peer's silence, a duplicate request, or a session that was lost and
reopened, without any resumption protocol: resumption is the attaching
peer re-sending the cursor it kept.

The serving side never calls the owner's `close!`. A session's
`:dao.stream/close!` closes only an attachment handle minted for that
session; for a source that mints no attachments (a ring buffer, a memory
log) it is a session-scoped no-op, and the session ends. Ending the
served stream is its owner's act on the owner's handle, observed by the
serving side as `end` from `next` and relayed.

**Serving-boundary observation.** For every request it accepts, the
serving side records, in its own state and never in the appended value,
the session it arrived on and that session's channel attachment identity.
The contract inserts no attachment metadata into appended values, so a
convention that needs to know where a value came from (section 7.3's
`:meet/seen`) reads this observation, correlated by the request's stable
id, and never a claim inside the value.

## 6. Channels

### 6.1 Channel contract

The serve layer needs exactly what the WebSocket transport already
provides and nothing more:

1. A writer handle whose `append!` places one value on this channel's
   outbound path, with `ok`, `full`, `invalid-value`, `closed`,
   `transport-error`.
2. A reader handle with the contract's reader surface, positioned on this
   channel's inbound values. A WebSocket or UDP attachment supplies it as
   the medium its adapter deposits into; a proxied stream served by
   another peer supplies it directly, because a proxy has a reader
   surface; two ring buffers supply it in process.
3. A portable value domain and a frame budget (bytes per value; unbounded
   for WebSocket, the datagram budget for UDP).
4. A declaration of ordering (`:ordered` or `:unordered`) and reliability
   (`:reliable` or `:best-effort`), which the proxy's retry policy reads
   as composition data.

A deposited medium is ordered and best-effort under eviction; a proxied
inbox is ordered and best-effort under the inbox's retention; UDP is
unordered and best-effort. The channel's declared nature changes the
proxy's policy, never its protocol.

### 6.2 WebSocket

`dao.stream.ws` is used as it is. A channel is one attachment; the
attachment's traffic medium is the channel's reader handle and the socket
handle is its writer. The channel descriptor's `:dao.stream/identity`
names the channel's own logical stream (the acceptor's channel identity),
which is distinct from any peer id and from any served stream.

**Direction is establishment, not authority.** A WebSocket has a dialer
and an acceptor because TCP does; a browser can only dial. The channel
descriptor's host, port and path are the acceptor's. Once established,
the channel is attached to both steps at both ends and nothing in this
layer remembers who dialed. A composition `dao.stream.serve.ws` wires an
attachment, from either side, to `serve-step` and `proxy-step`. The
existing `dao.stream.serving` composition is not used: it forwards a
source into every accepted socket, which is the copy model.

Hosts: clj, cljs on Node and cljd may accept or dial; cljs in a browser
may dial.

### 6.3 UDP

`dao.stream.udp` is a new channel with the WebSocket deposit model and no
reader surface of its own: a socket retains nothing. It is the datagram
boundary the DHT node already uses, reshaped into the adapter form.

- **Descriptor.** `{:dao.stream/type :dao.stream/udp :dao.stream/identity
  <channel identity> :udp/host h :udp/port p}`. `attach!` binds or reuses
  a local socket and returns a handle whose writer path is "datagrams to
  that address". No handshake exists at this layer; `:serve/open` is the
  handshake.
- **Same-socket sends.** A UDP handle may send to an explicit destination
  on the socket it already holds. Hole punching (section 7.4) depends on
  this: the probe must leave the socket whose reflexive address a meeting
  peer observed, and a fresh `attach!` that bound another socket would
  probe from an address no NAT has mapped.
- **One value per datagram**, encoded with the CBOR codec profile the
  WebSocket transport already specifies (binary, canonical). The frame
  budget is 1200 bytes, the DHT's figure, chosen to clear common path
  MTUs without IP fragmentation. A value over budget is `invalid-value`
  on `append!` and, from the serving side, `:oversize` (section 3.5).
- **Declared nature:** `:unordered`, `:best-effort`. Each outstanding
  request records `:sent-at`; `proxy-step` re-sends when `now` minus
  `:sent-at` exceeds `:retry-after-ms`, up to `:max-attempts`, both
  composition data. Exhaustion is session loss, deposited as data.
- **Lifecycle events** are thinner than WebSocket's: there is no peer
  close, so `:udp/closed` is deposited only on local close or deposit
  failure. Peer silence is the serve layer's business (`:serve/ping` and
  the retry policy), not the channel's.
- **Attachment identity** is the remote host and port as seen by the
  socket; replies go to the datagram's source address, never to an
  address claimed in the payload, as the DHT already rules.
- **Fragmentation is required before content is claimed over UDP.**
  `dao.jing` segments and code images routinely exceed one datagram, and
  a proxied `:oversize` read is terminal for a `dao.stream.rpc` binding.
  Until the UDP channel fragments and reassembles, every request/response
  service carrying content (section 8) is WebSocket-only, and no document
  may claim it over UDP. Fragmentation, when added, lives in this channel
  and is invisible to the serve layer. Channel-over-channel framing
  (section 6.4) consumes budget too: an inner frame inside an outer
  `append!` must fit the outer channel's budget with both envelopes.

Hosts: clj (`DatagramSocket`), cljs on Node (`dgram`), cljd
(`RawDatagramSocket`). Browsers have no UDP. A browser peer reaches NATed
peers through a meeting peer over WebSocket (section 7.3) today, and
through a WebRTC DataChannel channel later (section 12); both slot into
6.1 without touching the serve layer.

### 6.4 A channel over served streams

Two proxied streams are a channel: peer A's writer is a proxy to a stream
B reads, and A's reader is a proxy to a stream B writes. When a third peer
M serves both streams, A and B have a channel without reaching each
other, and M runs nothing but the ordinary `serve-step` over two ring
buffers. Serve frames inside such a channel are values in M's streams,
and M never interprets them. Streams are values sent through streams;
this is the whole of relaying.

Two rules follow from the nesting:

- **An outer `gap` is channel-frame loss, not source eviction.** When the
  proxy on an inbox reports `gap`, frames were lost between the peers;
  the inner proxy treats it as lost requests and responses, re-issues
  outstanding reads (idempotent) and applies the append rule of 3.4. It
  never reports that gap to the inner reader as the final source's `gap`,
  because the final source evicted nothing. Gaps belong to their own
  logical stream.
- **Double framing consumes budget.** An inner value is carried inside an
  outer `append!`; over a UDP-backed inbox both envelopes must fit the
  datagram budget, and an inner value that cannot is `:oversize` at the
  inner layer.

## 7. Reachability

### 7.1 The serve descriptor

```clojure
{:dao.stream/type      :dao.stream/serve
 :dao.stream/identity  "..."         ; the served logical stream
 :serve/peer           "..."         ; the holding peer's id, section 7.3
 :serve/surface        #{:reader}    ; hint; :serve/opened is authoritative
 :serve/candidates     [c1 c2 ...]}  ; ordered, each one of the three below
```

`:dao.stream/identity` names the served stream; `:serve/peer` names the
peer that holds it; a candidate's own `:dao.stream/identity` names the
channel's logical stream. The three are distinct and none is derived from
another.

Each candidate is one of:

- a **channel descriptor** (WebSocket or UDP) naming an acceptor address
  of the holding peer;
- an **inbox pair** through a meeting peer M: the serve descriptors of the
  holding peer's inbound and outbound inbox streams on M, plus M's two
  meeting descriptors (request and announcement) so the attaching peer
  can register its own pair with the same M:

  ```clojure
  {:serve/via        :inbox
   :serve/in         <serve descriptor>   ; write here to reach the peer
   :serve/out        <serve descriptor>   ; the peer writes here
   :serve/meet       {:serve/requests <serve descriptor>
                      :serve/announcements <serve descriptor>}}
  ```
- a **meeting resolution** `{:serve/via :punch :serve/meet {...}}`,
  meaning: register with that meeting, announce a call to the holding
  peer, read both reflexive addresses, and probe (section 7.4).

The proxy tries candidates in order, and candidate resolution is
bounded: each candidate has an attempt budget in the driver's clock
domain, a nested serve descriptor is followed at most one level (an inbox
on M is reached by M's channel candidates, never by another inbox, so M
itself must be directly reachable by a channel descriptor for the inbox
fallback to exist at all), and
the whole list is tried at most once per `attach!` unless the
composition's reconnect policy says otherwise. Each attempt's resolution
is deposited on the proxy's event medium. Sequential trial is a v1
simplification; parallel trial with a preference order is an additive
proxy policy. The descriptor carries no authorization, as the contract
requires.

### 7.2 Direct

A peer with an acceptor channel, a listening WebSocket endpoint or a bound
UDP socket, is reached by a channel descriptor. This is establishment: the
acceptor holds no authority the dialer lacks.

### 7.3 The meeting convention

Any peer M may choose to serve a meeting stream pair. Doing so makes M a
peer running a convention, not a kind of node: the protocol has no frame
for it, and any other peer may run it too. The pair is exactly the request
medium pattern `dao.stream.ws.md` describes, so nothing new is specified:

- `meet-requests`: served with surface `#{:writer}`. Any peer opens a
  session and appends requests. This is M's request medium; many writers,
  and the meaning of their order is M's interpreter's.
- `meet-announcements`: served with surface `#{:reader}`. Single writer,
  M. M keeps the complementary handles locally.

An interpreter of M's composition reads the request medium and appends
facts to the announcement stream. The vocabulary is a convention under
`:meet/...`, not protocol:

```clojure
;; requests, appended by any peer through an ordinary session
{:meet/request <stable id>  :meet/here <peer-id>}
{:meet/request <stable id>  :meet/call <peer-id> :meet/from <peer-id>}

;; facts, appended by M alone
{:meet/request <stable id>  :meet/inbox <peer-id>
 :serve/in <serve descriptor> :serve/out <serve descriptor>}
{:meet/request <stable id>  :meet/seen <peer-id>
 :meet/reflexive {:host h :port p}}
{:meet/request <stable id>  :meet/call <peer-id> :meet/from <peer-id>}
{:meet/request <stable id>  :meet/refused <peer-id> :meet/reason r}
```

**Peer ids.** A peer id is minted by the peer's own composition: at least
128 bits of random entropy, or the intended self-certifying form, the
hash of the peer's public key (`dao.stream.discovery.md`, mechanism 1).
There is no assigner. Neither form authenticates a peer until key-control
and channel-binding verification exists; until then a peer id is an
opaque, unauthenticated correlation value, exactly as a logical-stream
identity is today (`dao.stream.md`, "History is not verifiable").

**Stable request ids and deduplication.** Every meeting request carries a
`:meet/request` id the requesting peer mints, stable across retries.
`:meet/here` allocates resources, so a retry after `:serve/append-unknown`
must not allocate twice: M deduplicates by request id across sessions for
its retry horizon, keeps one active pair per peer id, and answers a
duplicate `:meet/here` with the existing pair's descriptors. A `:meet/here`
for a peer id that already has an active pair from a different session is
answered `:meet/refused` with reason `:active`, never silently replaced;
replacement is M's policy only after that pair's lifetime lapses.
Rereading the announcement stream from a pre-append cursor is recovery
guidance for finding a completed request, not a substitute for the
deduplication, because a missing announcement does not prove the append
failed.

**Reflexive addresses.** On every accepted request M's interpreter reads
the serving-boundary observation (section 5) for that request, which
carries the session and the channel attachment identity it arrived on.
For a UDP channel that identity is the remote host and port as M's socket
saw them: the reflexive address. M announces `:meet/seen` from that
observation and never from a peer-id claim inside the value. That is the
whole of STUN, as a fact republished on a stream.

**Calls.** `:meet/call` is republished by M. Both parties read the
announcement stream anyway, so both learn the other's reflexive address
and that a call is in progress. That is the whole of the introduction.

**What a relayed channel is.** Peer A reaching B through M uses a proxy to
B's inbound inbox as its writer and a proxy to its own inbound inbox as
its reader (section 6.4). The serve protocol runs unchanged over it. M
runs the ordinary `serve-step` over ring buffers and never sees a session
it did not itself serve. Because the inner protocol tolerates loss, an
inbox that evicts under pressure is channel loss the inner layer absorbs.

**Address exposure.** `:meet/seen` facts and announced inbox descriptors
are readable by every peer that reads M's announcement stream. Reflexive
addresses are therefore public to that audience, and a descriptor grants
no authorization (`dao.stream.md`, Envelopes). This convention claims no
private delivery; a deployment that needs confidentiality specifies
access control on M's request stream or encryption of the inbox payloads
as an additional mechanism.

**Bounded meeting work.** M bounds the number of active pairs, each
inbox's retention, the announcement stream's retention, the work its
interpreter does per driver step, and the fanout of any one request. A
request M cannot honor within those bounds is answered `:meet/refused`
with a reason, so refusal is observable; a peer whose cursor on the
announcement stream reports `gap` re-reads from a fresh anchor and
re-issues its request under the same stable id, so announcement loss is
recoverable. Which meeting stream a peer trusts is the peer's choice,
exactly as it chooses whose directory to fold in
`dao.stream.discovery.md`.

**Costs.** A message through M is framed twice and a peer must poll its
inbox on M; section 3.7 is the optimization for the second cost.

### 7.4 Hole punching

After a call is announced, each party has the other's reflexive address
from `:meet/seen`. Each sends `:serve/ping` with a fresh nonce to that
address from the same UDP socket M observed (section 6.3), on a schedule
`proxy-step` reads from `now`. The first probe to arrive opens the NAT
binding in the other direction. A peer that receives a ping answers
`:serve/pong` with the same nonce from the same socket. A direct channel
exists when a pong carrying one of this peer's own outstanding nonces has
arrived from the expected address; the first inbound datagram alone
confirms nothing, since it may be a probe on a path that will not carry a
reply. The composition then attaches that socket and remote address as a
UDP channel and opens the session.

If no matching pong arrives within the punch budget, the proxy falls to
the next candidate, normally the inbox pair on the same M. A symmetric
NAT that assigns a fresh port per destination defeats punching and takes
that path; that is a property of the network, reported as a failed
candidate, not a failure of the protocol. Restricted-cone NATs, which
admit a source only after an outbound datagram to it, are the case the
same-socket rule exists for and are tested (section 14).

### 7.5 Peer symmetry

Once any channel exists between A and B, each may `:serve/open` sessions
on the other's serve table, in both directions, on the same channel.
There is no attaching peer and serving peer, only per-session roles. A
continuation migrating from A to B whose cells reference streams on A is
served by A over the very channel B dialed to fetch it. This falls out of
section 3.1 and needs no additional mechanism.

### 7.6 Liveness

`:serve/ping` and `:serve/pong` are per channel. Cadence and the number of
unanswered pings that end a channel are composition data in the driver's
clock domain. A channel ended by silence deposits its lifecycle event;
every session on it is lost (section 4). A peer reachable only through an
inbox on M is reachable only while its channel to M lives, so its ping
cadence toward M is bounded by NAT binding lifetime: tens of seconds for
UDP, longer for TCP. This resolves the WebSocket transport's deferred
liveness item for channels used by this layer, with ordinary values
rather than protocol frames.

## 8. The service convention

The serve protocol serves one handle per identity. A request/response
service, a `dao.jing` content service being the first, cannot sit behind
one shared request stream: every peer's `dao.stream.rpc` ids start at
zero, so ids would collide, and every response would be readable by every
peer. The meeting convention already answers this for itself, and the
answer generalizes.

A **service door** is a request stream a peer serves with surface
`#{:writer}` under a well-known identity. A peer that wants the service
appends `{:svc/request <stable id> :svc/open <peer-id>}`. The serving
peer's interpreter creates a request and response pair for that peer,
adds them to its serve table (request `#{:writer}`, response
`#{:reader}`), and announces `{:svc/request <id> :svc/pair <peer-id>
:svc/in <descriptor> :svc/out <descriptor>}` on a served announcement
stream. The requesting peer proxies both and runs `dao.stream.rpc` over
them. Stable ids, deduplication, one active pair per peer id, bounded
pairs and retention, observable refusal and the serving-boundary
observation apply exactly as in section 7.3; the door is a meeting whose
pair carries a service rather than a channel.

That this looks like a client and a server is the point of the owner's
clause: the shape is a convention some peers run over a symmetric
protocol, and the same peer may open a door and use another's on the same
channel.

**Consequence for `dao.jing.remote`.** The content service becomes the
existing handler map (`dao.jing.remote/default-handlers`) plus
`dao.stream.apply/serve-once!` over a per-peer pair behind a door. What
serve makes redundant is the transport half of that namespace: the
WebSocket descriptor, `connect-content!`, `serve-content!`, their endpoint
and slot composition, and the dependency on `dao.stream.rpc.ws` and
`dao.stream.serving`. What stays is everything jing-level: the stepped
client `dao.jing.remote.step`, the async backend `dao.jing.remote.async`
that drives it for callback-shaped consumers such as B-tree hydration,
the handle wrapper `dao.jing.remote/content-client`, and the handler map.
A content store is random access by address, a stream is sequential, so
reading by address is a request/response protocol over two streams
whatever the transport, and something must own the operation vocabulary,
correlation of many outstanding requests, the `materialize!` verify hop
and the ingress check on every found reply. A proxy is a transport and
must not know what a payload claims to be. The stepped client's media
become proxy handles; the client does not change. The JVM blocking driver
survives as host policy over that stepped client, which is the shape
`dao.stream.md` OD-5 requires.

The proxy relays payload bytes verbatim and never hashes, decodes or
inspects them. Verification stays at the consumer's door in the
consumer's order: byte cap, then address check, then decode, then
row-local checks (`yin.vm.linker.md`, section 4.2). The cap should apply
to the encoded text's length before it is decoded, so an oversize reply
is refused before it is materialized in memory.

## 9. Host isolation

+---------------------------------------------+-----+--------------+-----------+------+
| Piece                                       | clj | cljs browser | cljs node | cljd |
+=============================================+=====+==============+===========+======+
| serve frames, proxy-step, serve-step,       | yes | yes          | yes       | yes  |
| meeting and service interpreters (.cljc,    |     |              |           |      |
| pure data, no host code)                    |     |              |           |      |
+---------------------------------------------+-----+--------------+-----------+------+
| WebSocket channel, dial                     | yes | yes          | yes       | yes  |
+---------------------------------------------+-----+--------------+-----------+------+
| WebSocket channel, accept                   | yes | no           | yes       | yes  |
+---------------------------------------------+-----+--------------+-----------+------+
| UDP channel, same-socket sends, punching    | yes | no           | yes       | yes  |
+---------------------------------------------+-----+--------------+-----------+------+
| channel over served streams (6.4)           | yes | yes          | yes       | yes  |
+---------------------------------------------+-----+--------------+-----------+------+
| WebRTC DataChannel (deferred)               | no  | later        | no        | later|
+---------------------------------------------+-----+--------------+-----------+------+

A browser peer is a full peer: it serves and attaches over any channel it
can dial, and is reachable by any peer through an inbox pair on a peer it
has dialed. It cannot punch until a DataChannel channel exists. Host code
is confined to the socket seams the WebSocket transport already defines
(`:connect!`, `:send!`, `:close!`) and their UDP twins.

## 10. Invariant compliance

- **No hidden global state.** Serve tables, sessions, windows, pairs and
  meeting state are values inside compositions the host constructs and
  drives; nothing is namespace-global and no operation consults ambient
  state.
- **No implicit control flow.** Every step is called by a driver with
  `now`; the only callback code is the channel adapter, which deposits and
  returns, as the WebSocket adapter does today.
- **No raw callbacks.** Resolution, detachment, append outcomes,
  candidate failures and reflexive addresses are all deposited events,
  read by cursors.
- **No shared mutable state.** A proxy's window and a session record are
  owned by exactly one driver; the source handle is touched only by
  `serve-step`.
- **No layer collapsing.** A meeting or service interpreter interprets its
  own vocabulary and nothing inside the values it carries; the channel
  interprets nothing; the serve layer interprets operations but never
  values; the reader interprets values.
- **No assumed graphs.** Who serves what to whom is the serve table and
  the descriptor's candidates, explicit data, never a topology the
  protocol infers.

## 11. Concurrency and linearization

The source is operated only from `serve-step`, in the order requests are
dequeued. Reads carry their own cursors, so concurrent readers through one
or many proxies neither share nor contend for position. Writes are
linearized per session by the one-outstanding rule, and across sessions
by the source's own sequence; the contract promises nothing more for
concurrent writers and neither does this layer. Over an unordered channel
the proxy never issues an `append!` before every earlier request on that
session has been answered, so a `:newest` mint that must precede an
append does precede it at the source. An inbox pair preserves per-link
order only as far as its underlying channel does, and the proxy's policy
already reads that from the channel's declaration.

## 12. Contract, transport-owned, deferred

**The contract requires**, and this design depends on: OD-3 resolutions
(a) and (2), cursors as plain data that survive serialization; OD-2's
payload-correlation rule with the write fallback worded "effect unknown;
no automatic retry"; OD-1's unrecognized-outcome rule with
`:dao.stream/retry?`; and the two definitions of section 4.1. None is
accepted yet (section 15).

**Transport-owned by this layer:** the frame vocabulary, session
identity, the one-outstanding-append rule, anchor piggybacking, held
reads, candidate order and bounds, retry and reconnect policies, the
meeting and service conventions and their bounds.

**Deliberately deferred:** UDP fragmentation (required before content is
claimed over UDP, section 6.3, but not designed here); parallel candidate
trial; a WebRTC DataChannel channel; authentication of peer ids and
gating of a public meeting or service door (postage or capability, an
interpreter rule on that peer's own request medium; the contract gates
nothing and this layer adds nothing); a lease over served entries and
inbox pairs (section 13); encryption of the channel (WSS is the host's;
UDP has none until a Noise-shaped channel exists, and until then UDP
serving is for trusted networks, as the DHT already says); carrying
content as CBOR byte strings instead of Base64 text.

## 13. UCF integration

What `yin.vm.universal-continuation-format.md` 7.4.3 and 7.5.3 should
say in place of the undefined "standard stream-over-network facade":

- **Lift.** For each stream cell, the exporter checks: the handle's
  declared surface covers what the cell needs; its cursors and outcome
  maps are in the portable domain (`:dao.stream.serve/v1`, the one cursor
  profile); and the exporter has at least one reachability candidate. It
  adds `identity -> {handle, surface}` to its serve table and writes a
  serve descriptor into the cell's `:yin.k/stream`. The cell's
  `:yin.k/position` is the kept cursor, verbatim. Any check failing is
  `:yin.k/unsatisfied` before minting, as the UCF already says. Exporter
  and resumer are per-migration roles, a convention over symmetric peers.
- **Lower.** The resumer `attach!`s each serve descriptor, receiving a
  proxy. `next` at the kept cursor is the first demand; `blocked` until
  served; the source's `gap` arrives verbatim. `:serve/disclaim` on open
  is `not-found` and hence `:yin.k/unsatisfied` naming the identity. An
  exhausted candidate list is the same.
- **Put resume.** Through a proxy, `append!`'s `ok` is acceptance into
  the session's outbound path. A `:put` wait on a served stream therefore
  resumes when the source's outcome is observed on the proxy's event
  medium as `:serve/appended` with `ok`, not on the outbound `ok`. A
  `:serve/append-unknown` leaves the wait undischarged; the program or
  its composition decides, and nothing retries invisibly.
- **One cursor profile.** `:yin.k/cursor-profiles` names
  `:dao.stream.serve/v1` and nothing per transport. A per-transport
  profile would make the resumer know transports, which the proxy exists
  to prevent.
- **Lifetime.** The exporter must keep the entry served while the
  migrated task may need it, and a meeting peer must keep the inbox pair
  the route depends on. The correct home for both obligations is a lease
  (`dao.lease.md`): the served entry and the inbox pair are grants, the
  resumer renews, renewals count only on an observed source `ok`, and
  lapse unserves. Until that lease exists, migration acceptance pins
  both: the exporter retires nothing and M retires no pair while an
  acceptance is in progress, and a resumer that arrives after retirement
  gets `not-found`.
- **Pinning.** Serving from the exporter pins the exporter: the stream is
  where it is, and OD-3 (a) makes a copy a different stream. Re-homing a
  stream is out of scope and the UCF should say so.

## 14. Completion criteria

1. **Loopback conformance.** Serve a memory log and a ring buffer through
   an in-process channel pair (two ring buffers as the two directions).
   The proxy passes the contract's reader and writer tests, and every
   cursor the proxy hands out is structurally equal to one the source
   minted.
2. **Gap fidelity.** Evict at the source under a kept cursor; the proxy
   relays `gap` with the source's recovery cursor, with no replay and no
   skip.
3. **Anchor semantics.** A `:newest` minted through the proxy never skips
   a value appended after the mint; a stale `:oldest` yields the source's
   `gap`.
4. **UDP under loss, reorder and duplication**, through a lossy socket
   seam: no duplicate append at the source, no lost read, no
   proxy-fabricated gap, and `:serve/append-unknown` deposited when the
   session dies with an append in flight.
5. **Reachability without acceptance.** A peer that only dials serves a
   stream to a peer that only dials, through an inbox pair on a third
   peer that runs nothing but `serve-step` and the meeting interpreter;
   the third peer's serve table holds two ring buffers per pair and no
   other state about the inner session.
6. **Punch and fallback.** Two UDP peers behind a NAT simulator obtain a
   direct channel through a meeting stream's `:meet/seen` facts and a
   nonce-matched pong from the observed socket; under a restricted-cone
   simulator the same-socket rule is what makes it succeed; under a
   symmetric-NAT simulator they fall to the inbox pair, reported as a
   failed candidate on the proxy's event medium.
7. **Peer symmetry.** Two peers each serve one stream to the other over
   one channel.
8. **Meeting idempotence and bounds.** A `:meet/here` retried after
   `append-unknown` yields the same pair; a second active `:meet/here`
   for the same peer id from another session is refused; an announcement
   `gap` is recovered by re-issuing under the same id; a meeting peer at
   its pair cap refuses observably.
9. **Loss at both framing layers.** Frames dropped, reordered and
   duplicated on the outer inbox channel are absorbed by the inner layer
   without any inner `gap` and without a duplicate append.
10. **UCF facade acceptance**, as the UCF's acceptance matrix states:
    park with a string-backed stream at a kept cursor, migrate, resume,
    force eviction, observe `gap`; a non-portable source refuses at lift;
    an unreachable endpoint refuses at lower; a meeting peer's pair is
    not retired during acceptance.
11. **Symmetry proof.** The same peer composition, with no configuration
    difference beyond which channels it has, plays the accepting,
    dialing, inbox-holding and inbox-using positions in items 5 and 6.
12. **Ended versus detached.** The source's owner closes the served
    stream: the proxy deposits `:serve/ended`, `next` reaches the
    source's `end`, and a re-attach reads retained history to `end`. The
    channel is lost instead: the proxy deposits `:serve/detached`, `next`
    answers the window then a local `end`, and a re-attach with the kept
    cursor resumes without replay.
13. **Frame budget and oversize.** Over a channel with a small frame
    budget, a batch stops before the value that would not fit and the
    next request resumes at it; a single value over budget is answered
    `transport-error` with `:oversize` at its cursor, the cursor does not
    advance past it, and the outcome reaches the reader; the same value
    crosses an unbounded channel intact.
14. **Session-control idempotence.** A retransmitted `:serve/open` for a
    live session returns the same attachment and leaves the append mark
    and held demands unchanged; a delayed duplicate of an answered append
    that arrives after a later append is refused `:stale` and the source
    holds exactly one copy of each; an open for a closed session id is
    answered `:detached` and creates nothing; a delayed append retransmit
    that arrives after its session is closed is dropped and never
    performed.
15. **Portability.** Items 1 to 4, 7 to 9 and 12 to 14 run on clj, cljs
    on Node and cljd; item 5 also on cljs in a browser through the
    WebSocket path.

## 15. Decisions awaiting the owner

- **Held reads.** Recommended: `:serve/hold` is optional and additive in
  v1, off by default, enabled per deployment, never a condition of
  meeting reachability or acceptance (section 3.7 wording).
- **Accept OD-1, OD-2 and OD-3 in `dao.stream.md` as preconditions.**
  Recommended: accept OD-3 (a) and (2) and OD-2 as drafted; accept OD-1
  with the write fallback corrected to "effect unknown; no automatic
  retry"; add the two definitions of section 4.1 (Reading, `blocked`;
  Cursors, anchors) and a declaration that a proxy is a handle of
  deferred remote observation. This design is not implementable without
  OD-3 and is dishonest without OD-2.
- **Lifetime of a served entry and of a meeting inbox pair after
  migration.** Recommended: lease-governed through `dao.lease.md`, with
  composition retirement as an explicitly weaker pre-grant stopgap and
  migration acceptance pinning both until then.
- **Rename `dao.stream.serving`.** Recommended: this layer is
  `dao.stream.serve`; today's forwarder-into-every-socket composition
  becomes `dao.stream.serving.copy` or another name that says copy or
  broadcast, and its docstring says it implements a client/server
  broadcast convention over the symmetric transport.
- **Admission to a meeting or service door.** Recommended: composition
  policy of the peer that runs the door, open in trusted deployments,
  with the bounds of section 7.3 required in every deployment; postage
  or capability is the intended answer for a public door and is an
  interpreter rule, not a protocol frame.
- **Peer id format.** Recommended: self-minted, at least 128 random bits
  now, the public-key-hash form as the intended format, verification
  additive later.
- **Append through a proxy.** Recommended: keep the outbound-path `ok`
  with the source's outcome on the event medium, and amend the UCF's put
  resume rule as section 13 states, rather than a proxy that holds the
  value and reports the source's outcome from a retried `append!`.
- **Retirement of `dao.jing.remote`'s transport half.** Recommended:
  after the service convention lands, retire the WebSocket descriptor,
  `connect-content!`, `serve-content!` and their composition; keep
  `dao.jing.remote.step` and unify the three copies of the ingress check
  into one function.

## 16. Edits this document implies in other files

Described, not made:

- `docs/design/dao.stream.ws.md`: "the stream's identity and lifecycle
  belong to the server" becomes "to the peer that holds the stream";
  "serving is what makes any stream remotely attachable" becomes a
  statement that the transport serves copies and that attachment to the
  original stream is this document; "client handle" and "server-side
  accepted-connection handle" become dialer-side and acceptor-side;
  outbound-dialed serving is stated as permitted; the deferred liveness
  and resumption items point here.
- `docs/design/dao.stream.md`: accept OD-1 (with the corrected write
  fallback), OD-2 and OD-3 (a) and (2), moving their text into the
  sections they amend; add the two definitions of section 4.1; reword
  "server-side" and "serving host" in Close to acceptor-side and the host
  holding the stream.
- `docs/design/yin.vm.universal-continuation-format.md`: 7.4.3 and 7.5.3
  cite this document for the facade; the put resume rule becomes section
  13's; `:yin.k/cursor-profiles` names `:dao.stream.serve/v1` only; the
  acceptance matrix's facade row adds the inbox-pair pinning; re-homing
  is stated out of scope.
- `docs/design/dao.lease.md`: the carriage note that a remote holder
  learns of a reclaim through `:ws/closed` becomes `:serve/close` with a
  reason; served entries and inbox pairs are named as subjects a lease
  may govern.
- `docs/design/daostream-udp-design.md`: marked superseded prior art; its
  DRDS reliability layer is not revived, and its NAT non-goal moves here.
- `docs/design/dao.jing.md`: the async hydration status says the stepped
  client's media are any `dao.stream` handles, served or local, and that
  the WebSocket composition is retired in favour of the service
  convention.
- `docs/design/yin.vm.linker.md`: the section 6.1 table's "remote
  content" row becomes "served pair + rpc"; section 9 M3 drops "over
  `dao.jing.remote.step`" and names `dao.stream.rpc` on a request and
  response pair, local or served, using the shared ingress check; the
  byte cap is applied to encoded text length before decoding.
- `docs/design/dao.data.btree.md`: the section 5.4 table's
  `dao.jing.remote` row becomes "over a served pair; async on every
  host".
- `src/cljc/dao/jing/remote.cljc`: the transport half retired as section
  8 states, once the service convention exists; the ingress check
  extracted once and shared with `dao.jing.remote.step` and the linker.
- `src/cljc/dao/stream/serving.cljc`: renamed and re-described as the
  copy convention.
- `docs/design/dao.jing.dht.md`: the NAT limitation cites section 7; a
  DHT node is a natural peer to run the meeting convention, and
  `dao.stream.discovery.md`'s rendezvous topics are where a meeting's
  descriptors are found.

## 17. Prior art and lineage

`daostream-udp-design.md` (v1) carried datoms as datagrams with a
reliability layer of sequence numbers, acknowledgement vectors and a
sliding window, and listed NAT traversal as a non-goal. Under this
document a datagram stream with its own positions is a different logical
stream (OD-3 (a)), and the serve protocol needs no read reliability layer
because reads are idempotent by cursor; writes need only the
one-outstanding rule and the append mark of section 3.4. The v1 document
is superseded.

`dao.stream.ws.md`'s serving composition is the copy model and remains
useful as a broadcast convention. `dao.stream.discovery.md`'s
self-certifying identities are the intended peer id format, and its
rendezvous topics are where a meeting's descriptors are published. The
meeting stream itself is coordination by traces on a shared medium, the
same stigmergic shape the rest of the system is built from.
