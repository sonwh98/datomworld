# DaoStream WebSocket Transport

Status: design target, subordinate to `dao.stream.md`. That document is the
contract; this one specifies the WebSocket transport's implementation of it.
Where the two disagree, the contract wins.

## What the Descriptor Names

A WebSocket descriptor names a **server-hosted stream**, never a connection.
The stream exists on the serving host — created there, retained there,
closed there by its owner — and it exists whether or not any client is
currently connected. A connection is the mechanics of one attachment:
connecting joins the stream, disconnecting detaches from it, and neither
event creates, closes, or destroys anything. That is what makes `attach!`
honest here: the same descriptor reaches the same stream every time, because
the stream's identity and lifecycle belong to the server, not to the wire.

This framing generalizes: **serving is what makes any stream remotely
attachable.** A ring buffer is host-local only because nothing speaks for
it. A host that composes a WebSocket endpoint in front of one is serving
that ring buffer; its descriptor now carries an address, and remote
interpreters can attach. The transport owns no stream — it serves streams
the host composed, which is exactly the contract's rule that descriptors
resolve against "the streams a network endpoint serves because serving them
is what it is."

A server that wants per-conversation state does not bend attachment into
creation: it `create!`s a fresh stream for the conversation and offers that
stream's descriptor.

## Surfaces

A WebSocket handle is **writer** and **closable** only. It has no
reader surface: a socket retains nothing, so it cannot make the reader
surface's promise — a positioned, append-only, retained sequence that
cursors can observe and re-observe. `cursor` and `next` do not exist on it.

## The Duplex Model

A WebSocket is two independent directions sharing one connection, and the
design keeps them separate:

- **Outbound**: `append!` on the socket handle encodes the value and
  sends it. Sending is appending; nothing is converted.
- **Inbound**: each arriving message is a host event. The host's adapter
  deposits it — payloads and connection lifecycle events alike, judging
  nothing — onto the stream the **host composition** wired for this
  boundary. Per ADR 0003 there is no separate event bus: dao.space is the
  medium. From that moment the traffic has positions, retention, cursors,
  and gaps — the reading model of the contract applies in full, on that
  stream.

The deposit destination is wired once, when the host composes the boundary
— it is not a parameter of `attach!`, and no attaching caller chooses or
knows it. Deposited events carry the attachment's identity, so every
interpreter takes its own perspective on the same deposited truth: one
indexes the traffic, another copies it to a file, a third ignores it. How
arrivals are interpreted is the interpreter's business, not the
transport's configuration.

```clojure
(def sock (-> (ws/attach! {:dao.stream/type :dao.stream/ws
                           :ws/host "example.org"
                           :ws/port 9090
                           :ws/path "/streams/telemetry"})
              :dao.stream/handle))

;; write: (append! sock msg)
;; read:  interpreters observe the medium the host boundary deposits into,
;;        filtering on this attachment's identity
```

Nothing accumulates inside the transport: a hidden per-socket inbox would
be a retention window with an eviction policy nobody composed, sized by
nobody. Retention lives in the medium the boundary deposits into, composed
and sized by the host.

There are no callback hooks. The legacy `on-open!` / `on-message!` /
`on-close!` surface is retired: every socket event becomes data on the
deposit stream — payload and lifecycle (opened, closed by peer, error)
alike, distinguishable by shape. A boundary deposits everything; whether
anyone reads it is not the boundary's concern.

### Deposit Admission

The adapter runs in a callback context: it cannot wait, cannot park, and
cannot record a refused deposit in the medium that refused it. So the
deposit destination must be one that always admits, and this is enforced
where it can be — at assembly, against the destination's declared nature:

- The destination must declare **evict-oldest retention with a
  host-declared capacity**. It never answers `:dao.stream/full`; under
  pressure, loss surfaces as reported `gap`s at lagging readers — the one
  loss mode the contract blesses. Unbounded in-memory retention is not an
  alternative: it trades a refused deposit for eventual host death, the
  most silent failure of all. (A genuinely durable medium — a disk-backed
  log — plays the same game with a much larger declared window; exhausting
  it at runtime surfaces as `:dao.stream/transport-error` on the deposit,
  like any storage failure.)
- Wiring a reject-mode destination is a **host assembly defect**, refused
  when the boundary is composed — the same category as supplying both
  dispatch shapes. Throwing is reserved for defects detected there, before
  the boundary operates; everything at deposit time is data.
- The destination must outlive the boundary, and **every non-`ok` deposit
  result tears the connection down**: `closed` means the destination is
  gone; `full` or `invalid-value` from a supposedly conforming destination
  is a host or transport defect, evidenced by the returned data;
  `transport-error` is runtime storage failure. In every case, a boundary
  that can no longer turn events into data stops claiming to be attached.
  Disconnection is fully observable — the peer's boundary deposits the
  departure, the local handle answers `:dao.stream/closed`, and
  re-attaching after the host repairs its composition is ordinary
  `attach!`.

Sizing the window is choosing how much reader lag to tolerate before gaps
appear — an explicit engineering decision in the host's composition. Where
the destination transport supports it, the window may be resized live
(a transport-owned operation on the owning handle): growing it only slows
the eviction horizon, shrinking it is ordinary early eviction reported as
gaps, and positions never renumber. An adaptive policy — grow on rising
gap rate, shrink when quiet — is an interpreter reading its own telemetry,
not a transport behavior.

The accounting is then complete: overload is a reported `gap`, a dead
destination is an observable disconnection, and silent loss is
unconstructible.

## Envelope

Transport-owned keys, qualified under `:ws/…`. The exact key set is TBD
alongside the portable descriptor's definition in the contract; settled
properties:

- The descriptor is self-contained and exists to cross the network: it
  carries what any host needs to reach the served stream — host and port,
  plus the served stream's identity (a path or stream id; endpoint address
  alone does not name a stream), perhaps TLS parameters.
- The descriptor carries identity only. Where inbound traffic lands is the
  host boundary's composition (see The Duplex Model) and appears in no
  envelope and no argument of `attach!`.

## Operations

- `attach!` connects. `:dao.stream/not-found` is reserved for an
  authoritative answer that what the descriptor names does not exist — the
  endpoint responds and disclaims the stream. Transient reachability and
  connection failures — DNS, timeout, routing, refusal — are
  `:dao.stream/transport-error`, so a caller's retry policy is
  deterministic: `not-found` is not a transient failure to retry
  automatically (though a server may serve that identity later — retrying
  on external evidence is interpreter policy), while retrying
  `transport-error` may succeed on its own.
- `append!` returns `:dao.stream/full` when the send buffer cannot accept
  the value now — **transient**: the network drains the buffer on its own,
  and a later append succeeds. `:dao.stream/closed` when the connection is
  down. A value that cannot be encoded for the wire is
  `:dao.stream/invalid-value`.
- `close!` on an attached handle means **disconnect from the server**: the
  handle is on the attachment, so closing it ends the connection,
  idempotently, and this handle's subsequent `append!` returns
  `:dao.stream/closed`. The served stream is untouched — closing *it*
  belongs to its server-side owner. Disconnection is not final: the
  descriptor remains valid and `attach!` rejoins the same stream. The
  peer's boundary deposits the departure into its host-composed event
  medium, correlated by attachment identity.
- Connection loss is the same detachment, uninvited: the served stream
  persists, its descriptor remains valid, and `attach!` with the same
  descriptor rejoins the same stream.

## Elements and Serialization

The wire is a serialization boundary. Every element must survive the codec
structurally unchanged; a handle can never travel — its portable
descriptor can.

## Deferred

- Exact envelope key set (tracks the contract's descriptor TBD).
- Resumption protocol: stream identity is stable across reconnects, so
  resumption is possible by design; what history a rejoining client receives
  (and how it states where it left off) is not yet specified.
- Backpressure protocols above the stream (a reader publishing its progress
  as data is interpretation, outside both this transport and the contract).
- Relationship to the RPC layers built over streams.
