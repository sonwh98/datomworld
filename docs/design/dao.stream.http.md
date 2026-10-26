# DaoStream HTTP Boundary

Status: design, not implemented. Subordinate to
[`dao.stream.md`](./dao.stream.md), which is the contract and wins on any
disagreement; sibling of [`dao.stream.ws.md`](./dao.stream.ws.md), whose
deposit model and admission rule it reuses. Every sentence below is a rule.
This document is application-agnostic: it specifies HTTP exchanges as
events and effects and nothing about what they carry.

## What it is

The HTTP boundary is not a transport and owns no stream. It is an adapter
(events in) and a host effect interpreter (effects out) over two ordinary
media the composition supplies: an **ingress** medium it deposits into and
an **egress** medium it reads. It has no reader surface, no `create!`, and
hands no handle upward. It never reads a request body, a query string or a
response chunk. Because nothing host-local crosses either medium, both are
`:portable-values`, and the interpreter answering requests may sit on any
peer (see *No privileged node*).

## Exchange

One request and its response are one attachment; a connection is not
(HTTP/1.1 keep-alive and HTTP/2 multiplexing are connection facts below
the transform). The boundary mints `:http/exchange` under the contract's
server-minted attachment rule (`dao.stream.md`, Envelopes): plain data,
unique among the exchanges it can distinguish, and never reused for the
lifetime of the egress medium. It carries the boundary's incarnation, so a
stale effect retained from before a restart can never match a new socket.

**Inventory.** The set of exchanges currently open is a resource the
boundary possesses. It is not derivable from the ingress medium, which
evicts. The boundary exposes it as one transport-owned operation beside
`egress-step`, `open-exchanges`, answering the open ids, together with the
idempotent `close-exchange`. Both exist for a `dao.lease.md` judge composed
inside this boundary (reclaim procedure and restart inventory); code
written against the contract may not reach for them.

## Events in

Wire framing, header parsing and size limits happen below the transform;
an over-limit or malformed request is answered there (400, 413, 503) and is
not an event. Every valid request is deposited unjudged. In these examples
the exchange id is `[incarnation n]` and strings stand for byte values:

```clojure
{:http/exchange [7 42]
 :http/event    :http/request
 :http/method   :post
 :http/path     "/action"
 :http/query    "a=1"
 :http/headers  {"accept" "text/event-stream"}
 :http/body     "{\"count\":1}"}

{:http/exchange [7 42] :http/event :http/ended}
{:http/exchange [7 42] :http/event :http/closed}
```

`:http/method` is a lower-case keyword; `:http/query` and `:http/body` are
absent when the request has none; header names are lower-cased.

Exactly one terminal event is deposited per exchange, after its request
event, whatever the cause: the peer closed, a write failed, an effect was
out of order, the composition's judge reclaimed it, the boundary stopped.
`:http/ended` is reserved for a response the boundary completed by
performing an `:http/end` effect. A terminal event reports that the
exchange ended; it says nothing about which individual effects reached the
peer.

## Deposit admission

As `dao.stream.ws.md`, Deposit Admission. The constructor takes the
ingress destination and its admission declaration as data: evict-oldest
retention with a declared capacity, value domain `:portable-values`, able
to carry every envelope above, and a lifetime longer than the boundary.
Wiring a reject-mode destination is a host assembly defect refused at
assembly. The constructor also takes `:http/max-open`, the number of
exchanges held open at once, and `:http/max-body-bytes`; a request beyond
either is answered below the transform and never deposited.

Eviction is loss. A reader whose cursor spans an ingress `gap` has missed
request or terminal events and cannot reconstruct them from this medium;
the inventory above is where the possessing side's truth is.

## Effects out

`egress-step` is a step the composition drives at its own cadence, as it
drives `endpoint-step`. It holds one cursor on the egress medium, minted at
`:dao.stream/newest` before the listener starts, and performs effects in
the egress medium's order:

```clojure
{:http/exchange [7 42] :http/effect :http/head
 :http/status 200 :http/headers {"content-type" "text/plain"}}
{:http/exchange [7 42] :http/effect :http/chunk :http/body "hello"}
{:http/exchange [7 42] :http/effect :http/end}
```

`:http/head` is sent once and first; `:http/chunk` writes bytes, a string
as UTF-8, and flushes; `:http/end` completes the response and the
exchange. The step appends no per-effect outcome, and a performed write is
not a delivery. A value that is not an effect for an exchange this boundary
holds open is not this interpreter's and is ignored, whether it names
nothing, names an exchange already ended or closed, or arrives after an
`:http/end`: the egress medium has no privileged reader, and other
interpreters may share it. An effect out of order for an open exchange, a
chunk before the head or a second head, closes that exchange.

Order is per medium. The ingress and egress media are two sequences, and
no ordering holds between them; a writer that must know whether its effect
was performed asks a question the wire answers, by what the peer later
sends, not this boundary.

The step takes no clock and holds no timer. How long a silent exchange is
held open is the composition's, through `dao.lease.md`; `close-exchange` is
the reclaim procedure a judge is composed with.

## Observably gone

A boundary that cannot deposit or cannot write makes itself observably
gone by closing a resource it itself holds, the same action for every
cause (`dao.stream.md`, Surfaces):

- a non-`ok` ingress deposit closes that exchange's socket with no
  synthesized response; the terminal event has nowhere to land, which is
  the contract's irreducible corner;
- a failed socket write closes the exchange and deposits `:http/closed`;
- an egress `gap` closes every open exchange, since the step cannot say
  whose effects were lost, then adopts the recovery cursor;
- an egress `end` or `transport-error` closes every open exchange and the
  listener: the boundary can never answer again.

It invokes nothing above itself, retries nothing, and interprets nothing.

**A host may not report a peer that disconnects while an exchange is idle.** A host whose send buffers and whose close notification does not fire for an idle streaming exchange (http-kit, observed) gives the boundary no failed write and no close to deposit. The boundary invents none. The composition's lease is the detector, and for a responder that renews, only the lease's `:dao.lease/max` ends such an exchange. The boundary takes no clock and does not add one (architect ruling 2026-10-24, `collab/1792841840885-architect-dao-star-m1-reconcile-r2`).

## No privileged node

Which end accepted is an establishment fact: HTTP has a requester and a
responder because TCP has a dialer and an acceptor, and a browser can only
dial (`dao.stream.remote.md`, section 1). The ingress and egress media are
ordinary streams, so a peer that cannot listen may run the answering
interpreter against reflections of them served by a reachable peer that
runs only this boundary. The boundary records no role beyond the exchange.

Hosts: clj (http-kit is already a dependency of the WebSocket transport),
cljs on Node, cljd. A browser has no listener.

## Deferred

- An HTTP channel for `dao.stream.remote.md` (request maps by POST, answers
  by a long-lived response) for peers that cannot open a WebSocket.
- Request bodies as streams rather than one bounded value.
- TLS termination, CORS and compression: below the transform, unspecified
  here.
