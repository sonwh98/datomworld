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

A WebSocket handle is **writer** and **closable**, plus `descriptor` as every
handle. It has no reader surface: a socket retains nothing, so it cannot make
the reader surface's promise — a positioned, append-only, retained sequence
that cursors can observe and re-observe. `cursor` and `next` do not exist on it.

Both a client handle and a server-side accepted-connection handle answer
`descriptor` with the **served stream's** descriptor — the logical stream the
connection joined. A descriptor names a stream, never a connection, so there
is nothing else for it to name; two connections to the same served stream
answer identically, and `:dao.stream/attachment` is what tells them apart.

The writer surface is on the connection's **ordered outbound path** — the
sequence of values accepted for sending on this attachment — never on the
served logical stream. `:dao.stream/ok` places the value in the outbound path;
what becomes of it at the far end is reported there, not here (contract,
Writing).

Outcomes this transport produces, and why it excludes the rest:

| Operation    | Produces                                       | Excluded, and why                                                                             |
|--------------|------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `create!`    | —                                              | Absent: the transport owns no stream. Dynamic dispatch for this type returns `not-found`.     |
| `attach!`    | `ok`, `invalid-descriptor`, `transport-error`  | `transport-error` covers only failures decidable here and now — no socket API on this host, a constructor that fails, local resources exhausted. `not-found`, and the reachability failures that need a remote answer, are displaced to the deposit medium. |
| `descriptor` | `ok`                                           | — (the contract's whole set)                                                                  |
| `append!`    | `ok`, `full`, `invalid-value`, `closed`, `transport-error` | — (the contract's whole set)                                                      |
| `close!`     | `ok`                                           | — (the contract's whole set)                                                                  |
| `cursor`, `next` | —                                          | No reader surface, per above.                                                                 |

## The Duplex Model

A WebSocket is two independent directions sharing one connection, and the
design keeps them separate:

- **Outbound**: `append!` on the socket handle encodes the value and
  sends it. Sending is appending; nothing is converted.
- **Inbound**: each arriving message is a host event. The host's adapter
  deposits it — payloads and connection lifecycle events alike, judging
  nothing — onto the stream the **host composition** wired for this
  boundary. The deposit medium is an ordinary DaoStream stream the host
  composed; this transport defines no separate event mechanism and no bus of
  its own. (In the v2 migration slice that stream is a ring buffer, under a
  time-boxed exception recorded in ADR 0003; the adapter cannot tell what it
  deposits into, so the later swap is composition-only.) From that moment the traffic has positions, retention, cursors,
  and gaps — the reading model of the contract applies in full, on that
  stream.

The deposit destination is wired once, when the host composes the boundary —
it is not a parameter of `attach!`, and no attaching caller chooses it. The
composition that wires the boundary also hands the medium to whichever
interpreter reads it, which may or may not be the code that called `attach!`. Every interpreter takes its own perspective on the same deposited
truth: one indexes the traffic, another copies it to a file, a third ignores
it. How arrivals are interpreted is the interpreter's business, not the
transport's configuration.

**Attachment identity.** This transport distinguishes its attachments, so its
`attach!` success map carries `:dao.stream/attachment` as the contract
requires: a descriptor names the served stream and cannot tell two attachments
to it apart, and a handle is a host-local object that cannot be compared
against data that crossed a codec.

Every event deposited for that attachment carries the same value under
`:ws/attachment`. The key differs because the scopes differ: result keywords
are the contract's and live under `:dao.stream/…`, while a deposited event is
a stream element, where transport-owned keys live under the transport's
namespace. Correlation is by the value carried, never by the key's name.

Without that identity a shared medium is a multiplexed sequence with no
demultiplexing key: every event present, in order, and unattributable.

**Deposited events are envelopes**, so that payload and lifecycle are
distinguishable and both are attributable:

```clojure
{:ws/attachment <id>
 :ws/event      :ws/payload  ; or :ws/opened :ws/closed :ws/ended
                             ;    :ws/error :ws/not-found
                             ;    :ws/transport-error
 :ws/value      <decoded>}   ; payload events only
```

The event kinds divide into three groups. **Resolution** — how this attachment
turned out: `:ws/opened` when the endpoint accepted the stream, `:ws/not-found`
when it disclaimed it, `:ws/transport-error` when the endpoint could not be
reached at all. Exactly one of these is deposited per attachment, and it is
what a retry policy reads. **Lifecycle** — `:ws/closed` when the connection
went away and `:ws/ended` when the served stream itself ended (see Ending a
served stream). **Traffic** — `:ws/payload`, carrying `:ws/value`, and
`:ws/error` for a socket-level error the connection survived. `:ws/error` is
therefore never a resolution: it says something went wrong on a connection that
had already resolved.

**The adapter registers additively** — `addEventListener` on a browser socket,
`.on` on a Node emitter — never by assigning the `on*` properties. Assignment
is a single slot: whoever writes it last wins, so anyone reaching past the
handle to the underlying socket and installing their own handler would silently
unhook the deposit path for every interpreter reading the medium. Additive
registration makes that impossible — a second handler is a second handler, not
a replacement — and the property then holds whether or not anyone respects the
handle's boundary.

Wrapping is not judging — the adapter never looks inside `:ws/value`. Nothing
of this crosses the wire: the receiving side already knows which socket
delivered a message, so correlation is added locally, on both ends
independently, and the wire carries bare payloads.

**Granularity is the composition's choice.** One medium per boundary
multiplexes every attachment onto one sequence and one retention window, so a
flood from one attachment evicts another's events. One medium per attachment
isolates them, but the boundary must then announce new attachments on a
boundary-level lifecycle stream, since nothing discovers a stream by itself.
Either shape carries the identity; only the sharing changes.

```clojure
(def result (ws/attach! {:dao.stream/type :dao.stream/ws
                         :ws/host "example.org"
                         :ws/port 9090
                         :ws/path "/streams/telemetry"}))

(def sock (:dao.stream/handle result))
(def me   (:dao.stream/attachment result))   ; keep it — nothing else identifies
                                             ; this attachment on the medium

;; write: (append! sock msg)
;; read:  interpreters observe the medium the host boundary deposits into,
;;        keeping events whose :ws/attachment is `me`
```

Nothing accumulates inside the transport: a hidden per-socket inbox would
be a retention window with an eviction policy nobody composed, sized by
nobody. Retention lives in the medium the boundary deposits into, composed
and sized by the host.

There are no callback hooks — no `on-open!` / `on-message!` / `on-close!`
surface of the kind the host's own socket API offers. Every socket event
becomes data on the deposit stream — payload and lifecycle (opened, closed by peer, error)
alike, distinguishable by shape. A boundary deposits everything; whether
anyone reads it is not the boundary's concern.

### Serving

Serving is the mirror of attaching, and the deposit model is the same on both
ends: each accepted connection's inbound messages are deposited — enveloped
with that connection's attachment identity, exactly as on the client — onto a
stream the host composition wired.

**That stream is not the served stream.** A client's `append!` is a request to
the server, never a broadcast: what a client writes lands in a medium only the
serving host's own interpreters read. Nothing a client sends reaches the other
attachments by default.

The alternative — depositing a client's messages straight into the served
stream — would make every client a writer to the stream every other client is
reading, by nothing more than holding a handle. A composition that wants that
writes it explicitly: an interpreter reads the request medium, decides what
qualifies, and appends to the served stream. Then the admission rule is a
piece of code someone wrote, in one place, rather than a consequence of the
transport's shape.

### Ending a served stream

Closing a served stream is its owner's `close!`, and connected clients have to
be able to tell that from a connection that merely dropped. The two call for
opposite responses — a dropped connection means reattach, an ended stream
means stop — and a client has no reader surface, so the answer can only reach
it as a deposited event.

The closing frame carries it. When the serving host closes a stream it closes
each attached connection **with a distinguishing close code**, rather than
dropping the socket. The client's adapter reads that code and deposits
`:ws/ended` instead of `:ws/closed`. (The exact code, in the protocol's
application-defined range, is settled with the handshake wire format; see
Deferred.)

What separates the two cases is not the closing but whether anything was
heard. A connection lost to a network fault delivers no closing frame at all,
and the host reports that distinctly — the client learns that nobody told it
anything, and deposits `:ws/closed`.

Three outcomes follow, and the third is the honest limit:

- **A closing frame arrived** — the stream ended. Stop; there is nothing to
  reattach to.
- **Nothing arrived** — reattach. If the stream is in fact gone, the handshake
  answers with the authoritative disclaimer and the client learns on that
  attempt instead of this one. A frame lost in transit costs one wasted
  reattach, never a wrong conclusion the client cannot escape.
- **The server cannot be reached at all** — unknowable. Nothing can distinguish
  a crashed peer from an unreachable one, so a client keeps retrying, which is
  correct: the stream may still be there. Bounding those retries is the
  consumer's policy, not this transport's.

The close code is the fast path for the common case — a healthy server ending a
stream deliberately. The handshake's disclaimer is what makes every other case
resolve as soon as a server is reachable. Neither is sufficient alone.

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
- The destination must also be able to **carry every envelope this transport
  deposits** — attachment identifiers, lifecycle events, and every value the
  codec admits. A destination that evicts oldest but answers
  `:dao.stream/invalid-value` on some envelope is as unwired as a reject-mode
  one; retention mode alone is not admission.
- Wiring a reject-mode destination is a **host assembly defect**, refused
  when the boundary is composed. Throwing is reserved for defects detected there, before
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
appear — an explicit engineering decision in the host's composition. Whether
that window can be resized after the fact is the destination transport's
declared nature, not this transport's business. An adaptive policy — grow on
rising gap rate, shrink when quiet — is an interpreter reading its own
telemetry, not a transport behavior.

The accounting is then honest, though not complete: declared retention
pressure surfaces as reader-visible `gap`s, and a dead destination tears the
attachment down. But a deposit that fails is a deposit that did not land, and
neither can the teardown event land in the destination that just refused it —
so in that one case the local failure is observable only through the deposit
result the adapter holds and through subsequent handle operations, and the
peer observes it only if teardown reaches the wire.

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

## The Handshake

Attaching presents the served stream's identity, and the endpoint either
accepts it or **authoritatively disclaims** it. Both halves are wire protocol
owned by this transport, not by any composition: a client must recognize the
disclaimer to deposit `:ws/not-found` rather than a generic failure, so a
composition inventing its own would not interoperate with any client. What a
composition owns is only which identities it serves — its resolution table —
and never the vocabulary of asking and disclaiming.

Their exact form is **not specified yet**; see Deferred. Until it is, two
independently written implementations cannot interoperate and no wire-level
conformance test can be written.

## Operations

- There is no `create!`. The transport owns no stream, so it creates none;
  a host serves a stream it composed elsewhere, and starting an endpoint is
  a host composition step, not a DaoStream operation. A creation
  specification naming this transport returns `:dao.stream/not-found`.
- `attach!` begins connecting and returns without waiting, per the
  contract's rule that no operation waits. It answers what is decidable
  locally, at the moment it is called: `:dao.stream/invalid-descriptor` for
  a descriptor it cannot read, `:dao.stream/transport-error` for a handler
  that fails here and now — no socket API on this host, a constructor that
  fails, local resources exhausted — and otherwise `:dao.stream/ok` with a
  handle on an attachment still being established. It never returns an
  answer it has not heard. This is not a concession to convenience: on a host with no
  blocking IO the endpoint's answer cannot arrive before `attach!` returns,
  and an `attach!` that waited for it would have to hand back a promise —
  breaking the result convention on every host that cannot block, and giving
  the same transport different outcome sets per host.
- **How the connection resolved arrives as data**, on the same host-composed
  medium as every other socket event, correlated by attachment identity.
  The distinction survives intact, only its channel moves. These are
  deposited elements, not outcome maps, so they carry the transport's own
  vocabulary, and exactly one resolution event is deposited per attachment:
  `:ws/opened` when the endpoint accepted the stream, and otherwise one of the
  two failures. `:ws/not-found` is the authoritative answer that what the
  descriptor names does not exist — the endpoint responded and disclaimed
  the stream — and is not retried automatically (though a server may serve
  that identity later; retrying on external evidence is interpreter
  policy), while `:ws/transport-error` covers transient
  reachability and connection failures — DNS, timeout, routing, refusal —
  and retrying it may succeed on its own. A caller's retry policy is as
  deterministic as before; it reads the answer from the medium instead of
  from a return value.
- `append!` returns `:dao.stream/full` when the send buffer cannot accept
  the value now — **transient**: the network drains the buffer on its own,
  and a later append succeeds. An attachment still establishing its
  connection answers `full` for the same reason and with the same remedy.
  `:dao.stream/closed` once the connection is down, which includes an
  attachment whose connection never succeeded. A value that cannot be
  encoded for the wire is `:dao.stream/invalid-value`. A local send that
  fails for neither reason is `:dao.stream/transport-error`. Operating the
  handle therefore tells a caller **that** the attachment is not writable,
  needing no reader at all — but never why: an authoritative `not-found` and
  a transient failure both reduce to `closed` here, and the distinction
  survives only as the correlated **resolution** event on the deposit medium
  — never as a lifecycle event, which is precisely the class that does not
  carry it.
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
  descriptor rejoins the same stream. What a client must not confuse with it
  is the served stream *ending*, which is a different fact reaching it by a
  different route (see Ending a served stream).

## Elements and Serialization

The wire is a serialization boundary. Every element must survive the codec
structurally unchanged; a handle can never travel — its portable
descriptor can.

## Deferred

- Exact envelope key set (tracks the contract's descriptor TBD).
- Handshake wire format: how the served stream's identity is presented on
  connect, the exact form of the authoritative disclaimer, and the
  application-range close code that distinguishes an ended stream from a
  dropped connection (see Ending a served stream).
- The value codec. The contract requires elements to survive it structurally
  unchanged; which encoding this transport speaks is not yet chosen, and
  neither is what a receiver does with wire input that fails to decode.
- Liveness: whether an idle connection is probed with the protocol's own
  ping/pong frames or with ordinary deposited values, how often, how many
  unanswered probes end a connection, and — if the probe is not a protocol
  frame — which component is obliged to answer it.
- Resumption protocol: stream identity is stable across reconnects, so
  resumption is possible by design; what history a rejoining client receives
  (and how it states where it left off) is not yet specified.
- Backpressure protocols above the stream (a reader publishing its progress
  as data is interpretation, outside both this transport and the contract).
  Needing nothing from this transport does not mean needing no specification:
  a pause vocabulary both ends must recognize is interoperable wire content,
  and it requires a durable home before it is built — the migration plan that
  currently describes it is consumed when its phases complete.
- Relationship to the RPC layers built over streams.
