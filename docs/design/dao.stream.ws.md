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
that ring buffer; the endpoint produces a descriptor carrying its address and
the ring buffer's unchanged logical-stream identity, and remote
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
`descriptor` with reachability for the endpoint through which they attached
and with the served stream's transport-independent `:dao.stream/identity`.
Two endpoints may therefore produce different descriptors for the same served
stream, while every such result carries the same identity.
`:dao.stream/attachment` distinguishes connections; neither the descriptor nor
the logical-stream identity does.

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
- **Inbound**: wire framing and protocol validation first either produce a
  valid transport event or perform protocol teardown. The host's adapter then
  deposits every produced event — payloads and connection lifecycle events
  alike, judging none semantically — onto the stream the **host composition** wired for this
  boundary. The deposit medium is an ordinary DaoStream stream the host
  composed; this transport defines no separate event mechanism and no bus of
  its own. (In the v2 migration slice that stream is a ring buffer, under a
  time-boxed exception recorded in ADR 0003; the adapter cannot tell what it
  deposits into, so the later swap is composition-only.) From that moment the traffic has positions, retention, cursors,
  and gaps — the reading model of the contract applies in full, on that
  stream.

On a client boundary, the traffic deposit destination is wired when the host
composes the boundary, before `attach!`; it is not an argument chosen by the
attaching interpreter. A serving endpoint instead receives the fixed
acceptance-handoff slots and boundary control medium defined under Serving;
the acknowledgement supplies each accepted attachment's traffic destination.
These are host-composed media with different retention duties: a traffic
medium records replayable events and may report gaps, while a handoff slot
holds one capability offer until that specific offer is acknowledged. The
composition also hands each reader its medium and cursor. Every interpreter takes its own
perspective on the same deposited truth: one indexes the traffic, another
copies it to a file, a third ignores it. How arrivals are interpreted is the
interpreter's business, not the transport's configuration.

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

**Server-minted attachment identity.** Before offering a server-side
accepted-connection handle to the serving composition, the transport mints
its attachment identity under the contract's server-minted-handle rule. The
value is unique among attachments the boundary can distinguish and is not
reused during that boundary's lifetime.

The acceptance-offer event carries the value under `:ws/attachment`, and
every later deposited event for the connection carries the same value. The
offer, its acknowledgement, the handle, and all later events therefore
denote one attachment. Client-side `attach!` continues to return the
corresponding client-local identity under `:dao.stream/attachment`.

**Deposited events are envelopes**, so that payload and lifecycle are
distinguishable and both are attributable:

```clojure
{:ws/attachment <id>
 :ws/event      :ws/payload  ; or :ws/accepted :ws/opened :ws/closed
                             ;    :ws/ended :ws/error :ws/not-found
                             ;    :ws/transport-error
 :ws/value      <decoded>    ; payload events only
 :ws/handle     {:dao.stream/handle <handle>
                 :dao.stream/surface #{:writer :closable}}} ; accepted offers only; host-local
```

The event kinds divide into four groups. **Acceptance offer** —
`:ws/accepted` is deposited once into a serving handoff slot after the
requested stream resolves and a server-side attachment handle is
constructed. It carries that writer+closable handle and its declared surface
under `:ws/handle`.
**Resolution** — `:ws/opened`, `:ws/not-found`, or `:ws/transport-error` says
how a client attachment turned out; exactly one resolution event is deposited
per attachment, and it is what a retry policy reads. **Lifecycle** —
`:ws/closed` when the connection went away and `:ws/ended` when the served
stream itself ended (see Ending a served stream). **Traffic** —
`:ws/payload`, carrying `:ws/value`, and `:ws/error`. `:ws/error` is a diagnostic and is never terminal by itself. A survivable socket error may be followed by no lifecycle event; a protocol failure is followed by the terminal event produced when its required teardown completes.

Once an attachment identity exists, **every connection end deposits exactly
one terminal lifecycle event at each endpoint**, whether the close was local,
remote, or caused by failed deposit, protocol teardown, admission expiry, or
endpoint stop. `:ws/ended` is reserved for a served-stream close; every other
end is `:ws/closed`. For a code-4000 served-stream close, both the initiating
server endpoint and the observing client endpoint deposit `:ws/ended`. A
resolution event, if any, is deposited first. Thus a
disclaimer is observed as `:ws/not-found` followed by `:ws/closed`, while a
pre-resolution connection failure is `:ws/transport-error` followed by
`:ws/closed`. Only the host close-completion callback deposits the terminal
event, guarded by attachment state so racing close/error callbacks cannot
duplicate it.

An accepted offer is host-local and never crosses a serialization boundary.
It does not share the traffic medium's retention window; Serving defines its
bounded handoff medium and acknowledgement.

**The adapter is the sole host-socket subscriber, and the raw socket never
escapes the handle.** On hosts with additive registration it uses
`addEventListener` or `.on`; on Dart it owns the socket's single subscription.
In every case it fans transformed events into the composed deposit stream.
“Additive” describes adding interpreters and cursors to that stream, never
adding another listener to the raw socket.

Wrapping is not judging — the adapter never looks inside `:ws/value`.
Deposited-event envelopes do not cross the wire: attachment correlation is
added locally because each side already knows which socket delivered a frame.
Wire frames have their own minimal envelope, specified under The Handshake and
Elements and Serialization, so protocol control cannot be confused with an
application value.

**Traffic granularity is composition data.** A client boundary may reuse one
medium across reconnects because it has one active attachment at a time. A
serving endpoint uses one application-traffic medium per accepted attachment,
supplied with the acknowledgement; only endpoint/pre-accept control facts may
share its boundary control medium. This does not alter acceptance handoff:
server writer capabilities travel through bounded handoff slots, never through
evictable traffic history.

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

No inbound-event inbox accumulates inside the transport: a hidden per-socket
inbox would be a retention window with an eviction policy nobody composed,
sized by nobody. Before acceptance acknowledgement, the endpoint retains only
the bounded pending connection associated with each configured handoff slot.
After acknowledgement, retention of traffic lives exclusively in the
host-composed traffic medium, composed and sized by the host.

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

**Acceptance is a bounded, acknowledged stream handoff.** At endpoint
construction, the host composition supplies a fixed, non-empty collection of
handoff slots. Each slot contains:

- a host-local offer medium with evict-oldest retention and capacity one,
  written by the transport and read by the composition; and
- a host-local acknowledgement medium with evict-oldest retention and
  capacity one, written by the composition and polled by the endpoint driver.

The composition mints the offer cursor it will retain and the acknowledgement
cursor `endpoint-step` will retain for every slot before starting the listener.
No connection can therefore deposit an offer before its reader position exists.

The transport has at most one outstanding attachment in a slot. After
resolving the requested stream, it assigns a free slot, mints the attachment
identity and handle, retains the connection in the endpoint's bounded
pending-accept state, and deposits:

```clojure
{:ws/attachment <id>
 :ws/event      :ws/accepted
 :ws/handle     {:dao.stream/handle <server-side-writer-handle>
                 :dao.stream/surface #{:writer :closable}}}
```

into that slot's offer medium. It does not send the wire `:ws/accept` frame
or enable value delivery yet.

The serving composition polls the known offer slots. After it reads an offer
and retains the handle in its own session state, it appends:

```clojure
{:ws/attachment <id>
 :ws/command    :ws/accept
 :ws/deposit    {:dao.stream/handle <writer-handle>
                 :dao.stream/surface #{:writer}}
 :ws/admission  {:retention :evict-oldest
                 :capacity <positive-integer>
                 :value-domain :host-values}}
```

to that slot's acknowledgement medium. The composition creates this
per-attachment traffic medium and mints its `:dao.stream/newest` cursor before
depositing the acknowledgement, retaining the reader and cursor in session
state. The transport validates the writer and admission declaration before
enabling delivery. A wrong-identity acknowledgement is stale and ignored
without changing the outstanding slot. A matching acknowledgement with a
malformed writer, surface, or admission declaration is rejected:
`endpoint-step` releases the slot, closes the pending connection before
acceptance, and its close-completion deposits `:ws/closed` on the control
medium.

Every event before acceptance is enabled — including decode diagnostics,
resolution, and the terminal event for pre-accept teardown — lands in the
boundary control medium. Every payload, diagnostic, and terminal event after
acceptance is enabled lands in that attachment's acknowledged traffic medium.
The serving composition reads both: the control medium retires a pending
offer/session, while the per-attachment medium retires an established session.

The transport exports an explicit
`endpoint-step` operation. The serving composition's single driver calls it
with endpoint state and an explicit `now`; the step accepts only an
acknowledgement whose identity matches the slot's outstanding offer, sends the
wire `:ws/accept` frame, enables value delivery, and releases the slot for
reuse. It never schedules itself. Admission expiry is a constructor policy
expressed in the same clock domain as `now`, and `nil` means no expiry. Stale
acknowledgements are ignored; attachment identities are not reused during the
boundary lifetime.

An outstanding accepted offer cannot be evicted: it is the slot's sole
value, and the transport does not append another offer to that slot before
acknowledgement. Reuse may later evict the acknowledged offer, but by then
the composition has both read the event and retained its handle. A reader
lagging before acknowledgement prevents slot reuse rather than losing the
capability. Thus eviction of an unacknowledged acceptance event is
impossible by construction.

The handoff remains bounded. When no slot is free, the transport admits no
additional connection; it closes the new socket before logical acceptance,
and the client resolves it as `:ws/transport-error`. It neither overwrites
an outstanding offer nor grows an unbounded pending queue.

Ownership remains explicit in every failure case:

- if the offer deposit is non-`ok`, the endpoint closes the pending
  connection;
- before acknowledgement, the endpoint retains the handle and can close it
  on peer loss, endpoint stop, or the configured admission expiry; teardown
  releases the slot, emits the one applicable terminal lifecycle event, and
  makes any later acknowledgement stale;
- after reading the offer, the composition also holds the handle and closes
  it if acknowledgement cannot be deposited;
- after acknowledgement, the composition owns the session handle, while the
  endpoint continues to own its host connection for endpoint-wide stop;
- any non-`ok` traffic deposit after acceptance tears down the connection
  under Deposit Admission.

No callback reaches the composition. Offer and acknowledgement are ordinary
stream values, and the composition-owned driver invokes `endpoint-step`
without waiting or installing a waiter.

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
`:ws/ended` instead of `:ws/closed`. (The exact code is `4000`, mapping to
`:ws/ended`, while `4002` is protocol-error and `4004` is the authoritative
not-found disclaimer; see Elements and Serialization.)

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

Acceptance handoff media obey the same declared-admission rule: both media
in every slot declare host-local reference admission, evict-oldest retention,
capacity one, ability to carry their respective envelope (including the live
handle in an offer), and a lifetime longer than the endpoint. Their additional
one-outstanding-offer discipline — not a different retention mode — is what
prevents capability loss.

The acknowledgement's per-attachment traffic medium obeys the same rule and
must outlive that attachment. Supplying it in the acknowledgement makes its
cursor-before-delivery ordering explicit and isolates one attachment's payload
retention pressure from every other attachment.

The server boundary control medium also declares evict-oldest retention,
capacity, `:portable-values`, and a lifetime longer than the endpoint. Its
cursor is minted before listener start, as are the handoff cursors.

- The destination's assembly declaration contains both **retention** and
  **value domain**. Retention is evict-oldest with a host-declared capacity.
  The value domain is either `:host-values`, which may retain host references,
  or `:portable-values`, which admits exactly the boundary codec's structural
  domain. A WebSocket traffic medium may use either; a handoff offer medium
  must use `:host-values` because it carries a live handle. It never answers
  `:dao.stream/full`; under
  pressure, loss surfaces as reported `gap`s to lagging cursors — the one
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

Sizing the window is choosing how much cursor lag to tolerate before gaps
appear — an explicit engineering decision in the host's composition. Whether
that window can be resized after the fact is the destination transport's
declared nature, not this transport's business. An adaptive policy — grow on
rising gap rate, shrink when quiet — is an interpreter reading its own
telemetry, not a transport behavior.

The accounting is then honest, though not complete: declared retention
pressure surfaces as `gap`s to cursors that span the eviction, and a dead
destination tears the
attachment down. But a deposit that fails is a deposit that did not land, and
neither can the teardown event land in the destination that just refused it —
so in that one case the local failure is observable only through the deposit
result the adapter holds and through subsequent handle operations, and the
peer observes it only if teardown reaches the wire.

## Envelope

Transport-owned keys are qualified under `:ws/…`; the logical-stream identity
key is contract-owned. The exact key set is settled by the descriptor gate;
these properties constrain it:

- The descriptor is self-contained and exists to cross the network: it
  carries what any host needs to reach the served stream — advertised host,
  advertised port, canonical path, perhaps TLS parameters — plus the distinct
  `:dao.stream/identity`. Endpoint address alone does not name a stream.
- An endpoint constructor receives bind host/port and advertised host/port as
  separate composition data. Bind defaults to `127.0.0.1`. An omitted
  advertised host defaults to a concrete bind host, but a wildcard bind
  requires an explicit advertised host. An omitted advertised port defaults to
  the actual bound port, including the assigned port after a bind to zero.
  Descriptors returned by accepted handles use the advertised values.
- The descriptor carries reachability and logical-stream identity only. Where inbound traffic lands is the
  host boundary's composition (see The Duplex Model) and appears in no
  envelope and no argument of `attach!`.

## The Handshake

Attaching presents the descriptor's canonical lookup path, and the endpoint
either accepts it or **authoritatively disclaims** it. Both halves are wire protocol
owned by this transport, not by any composition: a client must recognize the
disclaimer to deposit `:ws/not-found` rather than a generic failure, so a
composition inventing its own would not interoperate with any client. What a
composition owns is only which identities it serves — its resolution table —
and never the vocabulary of asking and disclaiming.

Attaching presents `:ws/path` as the WebSocket HTTP request-target path.
`:ws/path` is a canonical, non-empty absolute path beginning with `/`. URI dot
segments are removed; percent-encoded unreserved octets are decoded; remaining
percent hex digits are upper-case; an empty URI path becomes `/`; and encoded
slashes remain encoded. Repeated slashes and a trailing slash are significant.
Query and fragment components are ignored for lookup and are absent from the
descriptor. Resolution is an exact string lookup on this canonical form. The
client offers the
WebSocket subprotocol `dao.stream.v2.transit-json`, and the server refuses
the upgrade when that subprotocol is absent.

After upgrade, the first WebSocket message sent by the server is exactly one
Transit-JSON text frame:

```clojure
{:ws/frame :ws/accept}
```

or:

```clojure
{:ws/frame :ws/disclaim}
```

`:ws/accept` authoritatively accepts the presented path and causes the
client boundary to deposit `:ws/opened`. `:ws/disclaim` authoritatively
disclaims it and causes the client boundary to deposit `:ws/not-found`; the
server then closes with code `4004` and reason `dao.stream/not-found`. The
client does not deposit `:ws/opened` merely because the HTTP upgrade
completed.

Path resolution and `:ws/disclaim` happen in the upgrade callback. A
not-found connection never occupies an acceptance slot and does not wait for
`endpoint-step`; slot-exhaustion teardown is likewise immediate and bounded by
the host's upgrade queue.

No value frame may be sent before `:ws/accept`. A frame of any other shape in
the first position is a protocol failure. If the connection closes before
either `:ws/accept` or `:ws/disclaim` is received, the client deposits
`:ws/transport-error` as that attachment's resolution.

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
  and a later append succeeds. An attachment is establishing until the client
  has **received** `:ws/accept` (or, server-side, until `endpoint-step` has sent
  it and enabled delivery); throughout that interval `append!` returns `full`
  and sends nothing.
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
  close-completion callback at each endpoint deposits the departure into its
  host-composed event medium, correlated by attachment identity. If releasing
  the host socket fails after the local state transition, the local traffic
  medium first receives
  `{:ws/attachment <id> :ws/event :ws/error :ws/reason :ws/close-failure}`;
  the later close-completion still contributes the single terminal lifecycle
  event. A diagnostic deposit that itself fails follows Deposit Admission.
- Connection loss is the same detachment, uninvited: the served stream
  persists, its descriptor remains valid, and `attach!` with the same
  descriptor rejoins the same stream. What a client must not confuse with it
  is the served stream *ending*, which is a different fact reaching it by a
  different route (see Ending a served stream).

## Elements and Serialization

The wire is a serialization boundary. Every element must survive the codec
structurally unchanged; a handle can never travel — its portable
descriptor can.

The wire codec is **Transit JSON encoded as one UTF-8 WebSocket text
message per value**. Every application value is framed as:

```clojure
{:ws/frame :ws/value
 :ws/value <value>}
```

The portable value domain is `nil`, booleans, strings, qualified or
unqualified keywords and symbols, safe integers in
`[-9007199254740991, 9007199254740991]`, finite doubles, vectors, lists,
sets, and maps recursively composed from that domain. No custom Transit
handlers or metadata participate in this protocol. A sender unable to
encode a value in this domain returns `:dao.stream/invalid-value` and sends
nothing. Across hosts, numeric structural equality is by mathematical value
within this domain: an integral finite double and the equal safe integer are
equivalent even if a host codec materializes different numeric classes.

All protocol keywords use exactly one namespace separator — for example
`:ws/frame`, never a multi-slash spelling such as `:ws/wire/frame` — so the
vocabulary reads on clj, cljs, and cljd.

A text message that is not valid Transit JSON, a binary message, an unknown
`:ws/frame` value, a missing required key, a value outside the portable
domain, or a `:ws/value` frame received before `:ws/accept` has been sent is
a protocol failure. Once an attachment identity exists, the
receiver first deposits:

```clojure
{:ws/attachment <id>
 :ws/event      :ws/error
 :ws/reason     :ws/decode-failure}
```

and then closes the connection with code `4002` and reason
`dao.stream/protocol-error`. The malformed input and host error object are
not deposited. If the error deposit itself fails, the ordinary
deposit-admission teardown rule applies.

Close code `4000`, reason `dao.stream/ended`, maps to `:ws/ended`. Code `4004` is the authoritative not-found close following the disclaimer frame. Code `4002` is a protocol-error teardown. For an established attachment, completion of a 4002 teardown deposits exactly one `:ws/closed` at both the endpoint that initiated the close and the peer that observed it. The detecting endpoint therefore deposits `:ws/error` first and `:ws/closed` when the connection terminates. Before client resolution, closure before `:ws/accept` or `:ws/disclaim` deposits `:ws/transport-error` and then the single `:ws/closed`; after `:ws/disclaim`, it deposits `:ws/not-found` and then `:ws/closed`. Every other peer close or connection loss maps to `:ws/closed` under the exactly-once lifecycle rule.

## Deferred

- Exact envelope key set (tracks the contract's descriptor TBD).
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
  and it requires a durable home before it is built. `dao.lease.md` is the
  intended home for its semantics and does not yet claim them; the wire form
  is owed by neither that document nor this one until it does. The v2
  migration plan has deferred flow control out of its slice for the same
  reason, so nothing is being built against an unsettled vocabulary.
- Some host socket APIs expose no reliable outbound high-water signal after
  establishment. On such a host `append!` may accept into the host's opaque
  buffer and the manifest excludes transient `full` for that state; this
  bounded-observability asymmetry is explicit rather than an invented signal.
- Relationship to the RPC layers built over streams.
