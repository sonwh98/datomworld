# DaoStream Implementation Plan — the WebSocket Slice

Status: migration plan, derived from and subordinate to `dao.stream.md`
(the contract, review-approved) and `dao.stream.ws.md` (the WebSocket
specification). Where this plan and the contract disagree, the contract
wins. This document is transient: it is consumed as the phases complete,
while the contract remains. Revised against the `gpt-5.6-sol` plan review
of 2026-08-30 (`collab/review-dao-stream-v2-plan.gpt-5.6-sol.stdout.log`).

## Strategy

Two deliberate narrowings:

1. **A `dao.stream.v2` namespace, not an in-place rewrite.** The legacy
   `dao.stream` has 52 `open!` call sites and every subsystem as a
   consumer; rewriting it in place is an atomic big bang. The new
   namespace is `dao.stream.v2` — one new file,
   `src/cljc/dao/stream/v2.cljc` — building the contract greenfield while
   legacy keeps the system running.
   Consumers migrate one at a time, later, under their own plans. The
   legacy implementation and its tests are evidence about behavior, not
   constraints: no compatibility facade, alias, or dual protocol.
2. **The WebSocket slice, end to end, and nothing else.** One vertical
   slice that forces the contract's riskiest promises early: the
   descriptor round trip across a real serialization boundary, Model A
   serving, writer-only surfaces, deposit admission, and observable
   teardown. Other transports (file, UDP, log, relation, RPC) wait until
   this slice stands.

The slice still requires a minimal reader-surface core — the served stream
and the deposit destination are both ring buffers — so the ring buffer is
in scope, but only what the contract requires of it, nothing the legacy
one happens to have.

## Settled before Phase 1

Three questions a phase would otherwise be forced to invent under delivery
pressure. Each is decided here so no implementer decides it alone.

**`attach!` is synchronous and never waits.** The Result Convention makes
every operation return an outcome map; the contract's IO Model generalizes
it — no operation waits. No transport in this slice returns a promise, a
future, or a host-native async value from any of the seven operations, on
any host. A ws `attach!` answers what is locally decidable — `ok`, `invalid-descriptor`,
and `transport-error` for a handler that fails here and now — while how the
connection resolved arrives as deposited
lifecycle data and through the handle's own `append!` outcomes; see
`dao.stream.ws.md`'s Operations section, corrected to match. A promise
leaking out of a v2 operation is a defect, not a host accommodation.

The rule covers `create!` equally, though this slice never exercises it:
the ring buffer is in-memory and creates synchronously on every host, and
the ws transport has no `create!` at all — it owns no stream, and starting
an endpoint is a host composition step. A later transport whose creation
needs host resources it cannot acquire synchronously (binding a port,
opening a handle through an async-only API) answers the same way `attach!`
does: the logical stream's identity exists at once, the handle reports
acquisition state through its own operations, and the result of the
acquisition arrives as data. Nothing waits.

**Deposit admission is declared, never interrogated.** The contract's
public surface has no way to ask a handle its retention policy, and must
not gain one — a predicate answer about a live handle is exactly what
`closed?` was retired for. So the boundary constructor takes the deposit
destination *and its admission declaration as data*, derived from the same
creation specification that produced the handle. The declaration names both
retention (evict-oldest and a capacity) and value domain (`:host-values` or
`:portable-values`); the constructor refuses reject-mode retention or a domain
that cannot carry every event it will deposit. This is configuration
provenance, not runtime introspection. Runtime behavior is unchanged: every
non-`ok` deposit result tears the connection down.

**Logical-stream identity is minted in Phase 2.** `cursor-mismatch` in
Phase 2 and the `:dao.stream/identity` projection carried with descriptors in
Phase 3 use the same value. It is allocated when a logical stream is created;
cursors carry it while descriptors pair it with transport-specific
reachability. Cursor and descriptor representations need not match and need
not be equally serializable — cursor serialization across hosts stays TBD per
the contract — but identity comparison never compares whole descriptors.

## Phase 1 — Contract core (`src/cljc/dao/stream/v2.cljc`)

The protocol and data surface, with no transport. The seven operations are
two kinds, and the split is the point:

- **Five handle operations**, as protocols a handle implements per its
  declared surface: `descriptor` (universal reachability and identity projections), `cursor` and `next`
  (reader), `append!` (writer), `close!` (closable).
- **Two transport entry functions**, `create!` and `attach!`, which take no
  handle because none exists yet. These are per-transport functions.
  Dynamic selection is a host-owned map from `:dao.stream/type` to a
  `{create attach}` pair — ordinary data, ordinary lookup, performed by the
  host. DaoStream ships no dispatch machinery: no multimethod, no registry,
  no load-time side effect. Host dispatch is documentation plus an example
  map in tests.

Putting `create!` or `attach!` on a protocol is how the retired registry
grows back; the file must not do it.

Also in Phase 1:

- The result convention: every operation returns an outcome map keyed by
  `:dao.stream/outcome`, open maps, all qualified keywords under
  `:dao.stream/…`, per-operation closed outcome sets exactly as the
  contract's tables state, with no outcome held open for a mechanism that does
  not exist.
- Generic envelope validation: `:dao.stream/type` present and qualified;
  everything else transport-owned.
- `descriptor` on every handle regardless of surface — its `{ok}` result
  requires both `:dao.stream/descriptor` and `:dao.stream/identity`; both
  outlive an attachment, so the operation must not be bundled into the reader
  or writer protocol. The conformance harness asserts that the sibling identity
  equals the identity carried inside the descriptor envelope.

Deliverable: a contract-conformance test suite, **declaration-driven by
construction**. Its input is a manifest — ordinary data, not code the harness
inspects — carrying the transport's entry functions and handle factories, its
declared surfaces, its per-operation outcome subsets with a reason for every
exclusion, and a fixture able to induce each outcome it does declare.

The harness runs the blocks the manifest licenses: result shapes, envelope
validation, and descriptor identity always, since `descriptor` belongs to no
surface; reader laws (cursor provenance — mint / successor / gap-recovery only
— non-destructive multi-reader reads, anchors) only for a declared reader;
writer laws only for a writer; **close laws only for a declared closable**,
which the earlier "always" wrongly assumed of every handle.

Two assertions come from the manifest rather than from any one transport: no
observed outcome falls outside the declared subset, and for each declared
outcome the fixture actually produces it. That is what makes the contract's
"declare which outcomes you produce, and why you exclude the rest" checkable
rather than aspirational — an exclusion whose substitute never materializes
fails, and an outcome that never appears proves nothing on its own.

**Concurrency oracle.** The conformance harness uses bounded, offline
linearizability checking separately for each sequence a handle surface is
on. It records invocation and completion boundaries, arguments, and
outcomes; appended test values are unique tokens. The checker searches for a
sequential history accepted by the transport's pure abstract model.

The only real-time edges it preserves are harness-observable happens-before
edges: when one invocation has completed before another is begun, the first
precedes the second. Overlapping operations receive no real-time order and
may linearize in any model-valid order. Operations on distinct logical
streams or distinct ordered outbound paths are checked independently; the
oracle asserts no global order across them.

For the ring buffer, the model contains logical-stream identity, ordered
positions, the bounded evict-oldest window, immutable cursors, attachment
lifecycle, and logical-stream close. It linearizes each operation against
that abstract sequence — not against atom swap order, wall-clock start
order, thread scheduling, or a particular implementation field.

The Phase 2 concurrency cases include concurrent append/append, append/next,
append/close, cursor/close, and independent readers, with bounded histories
small enough for exhaustive search. A history for which no legal
sequentialization exists fails conformance.

This oracle detects duplicate or lost successful appends, impossible
outcomes, cursor corruption, non-atomic append-versus-close behavior, and
destructive interference between readers. It cannot prove liveness or
fairness, detect failures in schedules the generator did not produce,
validate WebSocket host-library ordering, or prove correctness for unbounded
executions. Stress repetition supplements it but is not itself the oracle.

The suite is *authored* in Phase 1 and first *demonstrated* in Phase 2.
Phase 1 has no transport, so it proves nothing on its own; that is expected
and is not a reason to defer writing it.

## Phase 2 — Ring buffer reference (`src/cljc/dao/stream/v2/ringbuffer.cljc`)

The reference reader+writer+closable transport, scoped to the contract:

- Logical-stream identity minted at `create!`, per *Settled before Phase 1*.
- Evict-oldest retention with declared capacity; `append!` never returns
  `full`; monotonic retention; `gap` with earliest-retained-else-tail
  recovery cursor. Eviction applies whatever any reader is doing — a
  retention policy that waits for readers is forbidden, not deferred (see
  *Boundary of this plan*).
No high-water figure in the creation specification: the ring buffer holds no
reader positions and so can never act on one. The threshold at which a reader
considers itself behind is that reader's own configuration, belonging to
whatever interpreter measures it — which this slice does not build.
- Cursor anchors `:dao.stream/oldest` and `:dao.stream/newest`;
  `invalid-anchor`; cursors carry the logical-stream identity
  (`cursor-mismatch` / `invalid-cursor` detectable); valid across handles
  of the same logical stream.
- One coherent logical-stream atom containing sequence state, logical close,
  every attachment's open/closed state, and each closed attachment's frozen
  tail. `next` makes one deref and decides identity, retention, attachment
  freeze, and availability from that snapshot, satisfying the Concurrency
  section.
- Phase 2 also implements the ring buffer's attachment entry. It is not a
  namespace-global resolver: `make-attacher` receives a host-owned directory
  or resolver and returns the unary `attach!` function that a host places in
  its dispatch table. The returned function validates the descriptor,
  resolves its logical-stream identity against that captured composition
  state, and returns `ok`, `invalid-descriptor`, or `not-found`.

  A successful attachment is a fresh attachment handle over the same
  logical-stream state, not the creator handle reused by reference. Its own
  `close!` closes only that attachment; `descriptor` and cursors still
  denote the underlying logical stream. Where these attachment lifecycles
  are distinguishable, the success map carries `:dao.stream/attachment` as
  the contract requires.

  Phase 2 tests the entry with a minimal supplied resolver. Phase 3 owns the
  real test composition's directory population, Transit round trip, and
  kept-cursor proof; it does not first implement attachment there.

Explicitly absent: waiters, drain, take, seq views, `closed?`, **live
resize** — compatible with the contract but proving nothing the slice needs,
since fixed declared capacity is sufficient for deposit admission — and the
transport-owned **`lag`** operation, whose only consumer was flow control (see
*Not in this plan*). Both follow the slice.

**Ring-buffer manifest and exclusions.** The manifest records the following
exact subsets and reasons:

| Operation | Produces | Excluded, and why |
|---|---|---|
| `create!` | `ok`, `invalid-spec` | `not-found`: host dispatch answers absence before this handler is selected. `transport-error`: allocation and state transition use only in-memory values and have no operational failure channel after validation. |
| `attach!` | `ok`, `invalid-descriptor`, `not-found` | `transport-error`: lookup against the supplied in-memory resolver has no operational failure channel. |
| `descriptor` | `ok` | None; this is the contract's complete set. |
| `cursor` | `ok`, `invalid-anchor`, `closed` | `transport-error`: cursor minting reads only coherent in-memory state. `closed` is induced through a closed attachment handle; the creator handle remains on the logical stream. |
| `next` | `ok`, `blocked`, `end`, `gap`, `cursor-mismatch`, `invalid-cursor` | `transport-error`: reading coherent in-memory state has no operational failure channel. |
| `append!` | `ok`, `closed` | `full`: evict-oldest answers retention pressure by eviction, reported later as `gap`, so it never refuses for capacity. `invalid-value`: an in-memory reference stream performs no encoding and can carry every host value admitted as a stream element. `transport-error`: its in-memory state transition has no operational failure channel. |
| `close!` | `ok` | None; this is the contract's complete set. |

**Reject mode is not a deferred ring-buffer variant.** It is intentionally
absent because v2 has no destructive drain: once such a buffer reached
capacity, no operation could free a slot, so `full` would be permanent.
Reintroducing reject mode would recreate the withdrawn deadlock rather than
add usable backpressure. Backpressure, if later required, belongs in the
deferred interpreter/lease path, not in this reference transport.

The Phase 1 conformance suite runs here for the first time against a transport
declaring all three surfaces. Laws that need a resolvable descriptor wait for
Phase 3's directory; everything else runs now. Ring-buffer-specific tests cover
retention, anchors, and eviction reporting.

## Phase 3 — Descriptor round trip

The portable identity story, still in-memory, and constructible without
WebSockets: the contract explicitly permits a host composition to keep a
directory, which is what makes a ring buffer's descriptor resolvable on its
own host.

- `descriptor` on a ring buffer handle yields a plain-data envelope that
  survives the **DaoStream v2 portable codec** structurally unchanged. That
  codec is Transit JSON text, implemented behind `dao.stream.v2.transit`,
  with the portable value domain and no-custom-handler profile specified by
  `dao.stream.ws.md`.
- There is **one value codec, not two**: Phase 3 encodes the descriptor
  directly as a Transit value, while WebSocket frames encode their control
  or value envelope with the same codec. Framing differs; value encoding
  does not.
- Phase 3 owns the cross-host codec conformance corpus and round trip on
  clj, cljs, and cljd. `dao.stream.v2.transit` has host implementations:
  Cognitect Transit CLJ on clj, Cognitect Transit CLJS on Node, and a new
  v2-owned cljd implementation adapted from the algorithms in
  `src/cljd/dao/stream/transit.cljd`. The v2 namespace must not depend on that
  legacy namespace merely because it uses the same Transit format.
- A **host-kept local directory** — a map from stream identity to live
  handle, owned by the test composition, not by DaoStream and not by any
  transport. It is deliberately test-only: the ws serving side in Phase 4
  resolves `:ws/path` against its own served-stream table and does not
  reuse this. Say so in the code, so nobody later mistakes it for
  infrastructure.
- Round-trip test: descriptor → encode → decode → the Phase 2
  `make-attacher` closure over that composition's directory → handle on the
  same logical stream; a kept cursor resumes through the new handle. This is
  where the kept-cursor promise is proven, on a transport that has a reader
  surface.
- `not-found` for descriptors nothing backs.

**Decision gate — descriptor key set.** The contract fixes
`:dao.stream/identity` and leaves the transport-specific descriptor envelope
TBD; this phase produces the evidence for settling the remaining keys. The
gate must keep identity distinct from reachability and prove that distinct
descriptors for two endpoints serving one stream carry equal identity. Phase 4 must not start
against provisional keys: review the round trip's key set, settle it in
`dao.stream.md`, and settle `:ws/…` in `dao.stream.ws.md` against it.
Provisional keys carried into a wire format under delivery pressure become
permanent by accident.

**Decision gate — wire contract, settled in `dao.stream.ws.md`.** The
Handshake and Elements and Serialization sections there now specify the
request-target presentation, the `:ws/accept`/`:ws/disclaim` first frame,
close codes 4000/4002/4004, the Transit-JSON value codec, and decode-failure
behaviour. Phase 4a implements that wire verbatim and its wire-level
conformance tests are a prerequisite to Phase 5 — for the same reason as the
descriptor gate: provisional choices carried into a wire format under
delivery pressure become permanent by accident. The gate adds nothing beyond
what `dao.stream.ws.md` now settles.

## Phase 4 — WebSocket transport, forwarding, and serving composition

Per `dao.stream.ws.md`, both ends. Three deliverables in three layers —
transport, infrastructure, composition — deliberately separate:

### 4a — The transport (`src/cljc/dao/stream/v2/ws.cljc`)

- **Client side**: `attach!` connects and returns without waiting, answering
  only what is decidable here and now — `ok`, `invalid-descriptor`, or
  `transport-error` for a handler that fails locally; see *Settled before
  Phase 1*. The composition creates its traffic medium and mints its
  `:dao.stream/newest` cursor before it calls `attach!`. The handle is
  writer+closable only; `append!` with transient `full` (including until the
  client has received `:ws/accept`),
  `invalid-value` for unencodable values, `closed` once down; `close!`
  means disconnect, reattachable;
  connection loss is the same detachment, uninvited.
- **Server side**: the accepted-connection handle — writer+closable, minted
  from a socket the runtime handed over, so neither `create!` nor `attach!`
  produces it — with the same `full`, `closed`, `transport-error`,
  send-racing-close, and identity rules as the client handle; plus that
  connection's own boundary adapter for its inbound events.
- **Transport constructor**: a client host supplies its traffic deposit
  destination and admission declaration. A serving endpoint receives bind
  host/port, advertised host/port, a canonical-path resolution table, a
  boundary control medium, and a fixed collection of capacity-one
  acceptance-handoff slots —
  offer and acknowledgement media plus their declarations — and the
  composition's policy for expiring unacknowledged offers. The entry
  functions a host puts in its dispatch table are closures this constructor
  produces from that state. These are composition-supplied streams, not
  namespace-global state; bare namespace vars could reach them only through
  namespace globals.
- **Endpoint stepping**: the transport exports `endpoint-step`, a total,
  non-waiting operation over endpoint state and explicit `now`. It polls
  acknowledgement slots, applies the constructor's admission-expiry policy,
  sends accept control, and returns the next endpoint state. Path disclaimers
  and slot-exhaustion closes happen immediately in the bounded upgrade callback
  and never occupy acceptance state. `endpoint-step` never
  self-schedules; the composition driver in 4c owns cadence.
- **Handshake**: encode and recognize the served-stream presentation and the
  authoritative disclaimer as settled at the wire-contract gate and recorded
  in `dao.stream.ws.md` — wire protocol, so
  both ends of it are transport, not composition.
- **Boundary adapter and acceptance handoff**: client resolution, payload,
  and lifecycle events are deposited into the client's ordinary host-composed
  traffic medium. A server-side accepted handle is first offered through a free
  capacity-one handoff slot under `:ws/event :ws/accepted`; the transport
  retains the connection in bounded pre-accept state and sends no wire
  `:ws/accept` until `endpoint-step` observes the matching stream
  acknowledgement. That acknowledgement carries a composition-created
  per-attachment traffic writer and its admission declaration; its reader
  cursor was minted before the acknowledgement. Accepted payload is deposited
  only there, while the endpoint control medium carries only pre-accept and
  endpoint lifecycle facts. Every deposited event carries the client-side identity
  returned by `attach!`, or the server-side identity carried by the accepted
  offer, under `:ws/attachment`. Admission is enforced at assembly against
  the declarations passed alongside the media, and every non-`ok` deposit
  follows `dao.stream.ws.md`'s teardown rule.
- No callbacks in the public surface; the legacy `on-open!`/`on-message!`/
  `on-close!` shape does not reappear.
- **Dependency check**: `v2/ws.cljc` requires only `dao.stream.v2` — never
  the ringbuffer namespace. The ws transport depends on the protocols; the
  offer, acknowledgement, traffic, and served-stream media are all
  composition partners — including the concrete capacity-one handoff slots —
  wired by the host composition, not by the transport. A transport requiring
  another transport is the legacy registry defect reborn.
- **Acceptance-handoff tests**: no wire `:ws/accept` or value delivery
  before matching acknowledgement; an unread offer survives arbitrary
  traffic-medium eviction; a slot is not reused before acknowledgement; slot
  exhaustion closes the new connection without overwriting an offer; stale
  or wrong-identity acknowledgements do not accept a connection; a matching
  malformed acknowledgement releases its slot and closes pre-accept; pre-accept
  terminal events use the control medium while post-accept events use the
  acknowledged per-attachment medium; and
  offer-deposit failure, acknowledgement-deposit failure, endpoint stop,
  peer loss, and admission expiry each close the connection, release the slot,
  ignore a later stale acknowledgement, and deposit exactly one terminal
  lifecycle event per endpoint.
- **Wire-close conformance**: on every supported host library, prove codes
  `4000`, `4002`, and `4004` and their reasons can be sent and observed, and
  prove the required event sequences for ended, protocol failure, disclaimer,
  locally initiated `close!`, and a host close failure. If any library fails
  this gate, Phase 4a does not ship the code-only design: first amend the wire
  spec to send `{:ws/frame :ws/end}` before close and treat code 4000 as a
  secondary fast path, then rerun cross-host conformance.

### 4b — Forwarding (`src/cljc/dao/stream/v2/forward.cljc`)

Serving is what makes a stream remotely attachable, and nothing in the
transport transfers a served stream's values to a connected socket. Under
the contract that transfer is an interpreter: it reads one stream through
its own cursor and appends to another. Streams notify no one, so nothing
drives it from below — a driver does, by calling it.

That transfer is not WebSocket-specific. It reads *any* reader handle and
appends to *any* writer handle: ring buffer to socket, ring buffer to ring
buffer, the code is identical. Its transport-agnosticism is the proof that
it belongs neither inside a transport nor inside one composition. It is a
third layer between them.

The step-versus-loop question is settled: the deliverable is a **single
step**, `forward-step`, not a self-rescheduling loop. A loop that hands
itself to a scheduler is a relocated callback — a continuation handed to
host machinery to invoke later — and it needs a cancellation mechanism
nothing here names. A step needs neither: a driver calling a function
repeatedly is ordinary control flow. `forward-step` consumes the source
handle, the destination handle, its state — the cursor to read from — and
the two policies below; it drains at most the batch budget and returns its
next state: the cursor to continue from, plus a status saying either that it
should be called again (progress made, or a retryable `blocked` or `full`)
or that it is finished, with the terminal condition carried as data.

`forward-step` owns the three disciplines that are the same everywhere and
are the easiest things in this slice to get wrong:

- `full` **does not advance the cursor** — nothing was appended, and
  treating the value as forwarded drops it silently;
- `blocked` returns to the driver rather than spinning — on a cooperative
  event loop a spin starves the runtime, so the send buffer never drains
  and `full` never clears; returning is the yield;
- `end` returns the terminal state `source-ended` and closes nothing.
  `forward-step` is transport-agnostic, so it cannot select the close code
  `dao.stream.ws.md` requires for an ended served stream — a generic
  `close!` is a plain disconnect, and the client's fast path would silently
  degrade to reattach-and-be-disclaimed. The transport-specific close
  belongs to the composition that reads the state and knows its transport
  (4c).

It owns nothing else. Two policies are arguments, because each has different
right answers in different compositions:

- **the `gap` policy** — resume at the recovery cursor, or terminate. A
  serving pump may skip evicted values; an interpreter that dropped
  submitted work must not. Same outcome, opposite correct responses;
- **the batch budget** — how many values to drain before returning, which
  exists only because a cooperative event loop needs yielding.

Everything else the loop form would have owned belongs to the **driver**,
the composition-supplied code that calls `forward-step`. Cadence is the
driver's — "run this again later" is `setTimeout` on Node, a sleeping thread
on the JVM, and the concern of the runtime driving the interpreter, never of
the stream (contract, *The readiness extension*). Starting is the driver's
first call; nothing self-installs. Stopping is the driver ceasing to call it
while keeping the returned state — which is also the cancellation mechanism —
and restarting is calling it again with that state. The kept state's cursor
never moved, which is why a future flow-control layer needs no protocol for
saying where to continue: the sender never lost its place, so a paused
connection resumes without being told anything.

`forward-step` is written against the contract rather than against a named
transport, so it is the one interpreter that must be **total over the outcome
algebra** — every outcome of `next` and of `append!`, including those its
particular endpoints never produce. Beyond the disciplines above that means an
explicit branch for destination `closed`, `invalid-value`, and
`transport-error`, and for source `cursor-mismatch`, `invalid-cursor` (its
start cursor is caller-supplied and may not belong to the source), and
`transport-error`. These return a terminal state carrying the outcome rather
than retrying: only `full` and `blocked` are the retryable answers. Otherwise
the contract's totality is bought by moving partiality into its first
consumer.

Testable with two ring buffers and no socket, so it may be written any time
after Phase 2; only its *use* is Phase 4.

### 4c — The serving composition (not in `v2/ws.cljc`, not in `forward.cljc`)

What is left once the step is infrastructure is the wiring and policy only
the host has. This is a named deliverable, not test wiring, and it must
define:

- the serving lifecycle: what starts and stops an endpoint, and who owns it;
- the single driver that calls transport-owned `endpoint-step` with its clock,
  cadence, and admission-expiry policy before advancing application sessions;
- the `:ws/path` → served-stream resolution table, host-owned data;
- which identities this endpoint serves — the resolution table's contents.
  The handshake's wire vocabulary is not composition; it belongs to 4a;
- how a connection is associated with the served stream it joined, and how
  that identity reaches deposited events;
- one forwarder per connection — a driver stepping `forward-step` from the
  served stream to that connection's ws handle — with the cadence, batch
  budget, and `gap` policy this composition chooses;
- whether inbound payload events are interpreted into appends on the served
  stream, and if so by which interpreter — the boundary itself judges
  nothing;
- close behavior: what a server-side owner closing the logical stream does
  to connected attachments. When a connection's forwarder returns
  `source-ended`, this composition performs the transport-specific close —
  the ended-stream close code per `dao.stream.ws.md`, settled at the
  wire-contract gate — which `forward-step` deliberately cannot (see 4b).

## Phase 5 — The slice, end to end

Two processes, not two compositions in one. A same-process socket test is
worth writing earlier for diagnostics, but it cannot show that a descriptor
is self-contained or that no accidental shared state carries identity
across — and those are central contract promises.

Process A creates a ring buffer, serves it over ws, appends. Process B
receives the descriptor as data, attaches, and appends outbound; B's host
boundary deposits inbound traffic into B's medium; an interpreter in B
reads that medium with cursors. State the bootstrap channel explicitly:
how B obtains A's descriptor is part of the test, not an assumption.

Kill the connection, then verify four separable facts:

1. Detachment is observable on both sides — B's handle answers `closed`,
   A's boundary deposits the departure.
2. The served stream survives on A, and `attach!` with the same descriptor
   rejoins the same logical stream.
3. Two simultaneous attachments to the same stream carry distinct
   `:dao.stream/attachment` values in their `attach!` results, and every event
   deposited for one — payload, resolution, and lifecycle alike — carries that
   same value under `:ws/attachment`.
4. B's cursor **on B's deposit medium** still resumes — that medium
   outlives the socket. The cursor is not handed to `attach!` and not used
   through the ws handle, which has no reader surface. WebSocket-level
   resumption — what history a rejoining client receives — is deferred in
   `dao.stream.ws.md` and is not tested here.
5. Serving A's stream through a second endpoint produces different reachability
   descriptors whose `:dao.stream/identity` values are equal; cursors compare
   that identity, never either descriptor.

## Host matrix

- **Phases 1–2** are pure `.cljc` with no host-specific codec: they must compile
  and pass on clj, cljs (Node), and cljd. Phase 3 is the first host-specific
  phase because `dao.stream.v2.transit` selects a Transit implementation per
  host; its shared corpus must pass identically on all three.
- **Phase 4–5** target clj and cljs (Node) — Node server plus Node client is
  the slice. Browser-client behavior is not validated by a Node target and
  is out of the slice. cljd ws follows once the slice is proven; beware the
  `#?(:cljd nil :clj ...)` reader-conditional trap.
- **`forward.cljc` is host-agnostic** — `forward-step` takes no scheduler
  and has no host dependency at all; cadence lives in the driver — so it
  compiles and its ring-buffer-to-ring-buffer tests pass on all three hosts,
  including where the ws transport does not yet exist.
- Per host, name explicitly: how transient send-buffer `full` is detected,
  how a send racing a concurrent close is handled, and that the codec
  produces structurally identical values across all three.

## What v1 callers lose, and what replaces it

The contract is a clean-room redesign: `dao.stream.md` neither mentions v1 nor
owes it compatibility, and nothing in `dao.stream.v2` may consult this table.
It lives here because this plan is the migration, and because the 52 `open!`
call sites need to know what happened to the call they were making. It is
consumed with the rest of this document.

| v1 (`src/cljc/dao/stream.cljc`)     | v2                            | Why                                                                                       |
|-------------------------------------|-------------------------------|---------------------------------------------------------------------------------------------|
| `IDaoStreamReader` — `next`         | reader — `cursor`, `next`     | Cursors are minted by the stream instead of built inline as `{:position 0}`, so a transport owns its cursor representation and `cursor-mismatch` becomes detectable. |
| `IDaoStreamWriter` — `append!`      | writer — `append!`            | Unchanged in shape; the outcome set is now exhaustive and `append!` never throws on a closed stream — it answers `closed`. |
| `IDaoStreamBound` — `close!`        | closable — `close!`           | Outcome set is `{ok}`, idempotent.                                                        |
| `IDaoStreamBound` — `closed?`       | **gone**                      | A predicate answer is stale the moment it returns; operate the handle and read the outcome. |
| `IDaoStreamWaitable` — 2 fns        | **gone**                      | No readiness extension. A `blocked` reader retries when it chooses; cadence belongs to the runtime driving it. |
| `IDaoStreamDrainable` / `drain-one!` | **gone**                     | Destructive read makes one reader's progress every other reader's data loss.               |
| `open!` multimethod, `defopen` macro | **gone**                     | Ambient namespace-global registry with load-time registration. Replaced by an ordinary host-owned map from `:dao.stream/type` to `{create attach}`, looked up by the host. |
| `open!` on a descriptor              | `create!` or `attach!`        | v1 conflated them. Creation takes a creation specification; attachment takes a portable descriptor and never creates. |
| `take!!`                             | **gone**                      | Blocking. JVM-only by construction, and unimplementable on cljs/cljd.                      |
| `strict-vec`                         | **gone**                      | Throws on ordinary operational signals; those are outcomes.                                |
| `->seq`                              | **gone**                      | An interpreter over a stream, not part of the contract (see the contract's Composition).   |
| bare `:ok` / `:blocked` / `:end` / `:daostream/gap` returns | outcome maps under `:dao.stream/…` | Every operation returns an open map keyed by `:dao.stream/outcome`, so extensions add keys without breaking consumers. |
| `{:position 0}` cursors              | transport-owned cursor values | There is no `seek`; every valid cursor comes from the stream.                              |
| `descriptor` via handle metadata     | `descriptor` operation        | Every handle returns distinct reachability and logical-stream identity projections, both surviving attachment close. |

Migrating a call site is therefore mostly mechanical — `open!` splits, results
become maps, cursors come from `cursor` — with two places that need a decision
rather than a translation: anything relying on `closed?` must be restructured
to act on an outcome, and anything relying on `drain-one!` or `take!!` needs a
cursor and a retry policy its runtime owns.

## Boundary of this plan

Carried, with a term:

- The slice's boundary deposits into a ring buffer rather than into the
  medium ADR 0003 names, under a time-boxed exception that ADR records. The
  exception ends when a conforming writer answers with data and a composition
  indexes and publishes what the boundary deposits. Until then this plan is
  where the exception is being spent, so it is named here and not only there.

Forbidden, not deferred:

- **Retention that waits for readers.** A stream that declines to evict until
  every attached cursor has passed a position is one line from the design
  above and has a failure mode none of the rest do: a single stalled or
  departed reader holds the stream open indefinitely, and a hostile one does
  it on purpose. Bounded retention must stay bounded. Loss is reported as
  `gap`; it is never prevented by waiting.

Not in this plan, by design:

- Migration of any consumer (dao.runtime, yin.vm, dao.jing, dao.space)
  — each gets its own plan against the finished slice.
- Other transports (file, UDP, log, relation, apply/RPC layers).
- The waiter/readiness extension, ws resumption protocol, cursor
  serialization across hosts, live ring buffer resize — deferred in the
  contract, deferred here.
- Gating who may attach. The contract reserves nothing for it, so neither does
  this plan; it would arrive as an addition, not as the filling-in of a slot
  left open here.
- **Flow control — a reader pausing a sender.** Deferred, with its dependency
  named rather than left as a date. A pause has to be a lease and not a switch:
  a switch has a stuck state, since a lost resume or a reader that dies
  mid-pause leaves the sender stopped forever, while a grant that lapses absent
  renewal returns the system to sending on its own. Those semantics now live in
  `dao.lease.md`, whose facts — the grant, its renewals, and the release or
  reclaim left as a trace — are datoms on a medium, which for this system means
  `dao.space`. `dao.space` is not yet built against this contract, so the chain
  bottoms out beyond this slice.

  What the slice ships without it is the honest default the contract already
  specifies: a reader that falls behind is evicted past and told so with a
  `gap`. That is right for a live feed and wrong only as the *sole* option, so
  its absence costs an alternative rather than a correctness property.

  Two items travel with it. The **pause wire vocabulary** — which the ws spec's
  Deferred list calls interoperable wire content needing a durable home — is
  therefore not owed by the wire-contract gate, and the gate's enumeration
  stands as written. And **liveness probing** stays deferred in
  `dao.stream.ws.md` on that document's own terms; this plan sets no interval
  and no unanswered-probe threshold, because a subordinate transient document
  may not settle what its superior left open.

  Resuming it needs three things in order: `dao.space` on the v2 contract, a
  `dao.lease.md` that claims the pause and liveness semantics its *Neighbouring
  deferrals* section currently declines, and a reconciliation of the pause
  request with that contract's negotiation — a reader asking "hold for *n*" is
  a proposal, and a proposal creates no state until the serving side grants.

## Explicit residual risks

- A reader that repeatedly accepts a recovery cursor while producers evict
  faster than it advances can livelock in repeated `gap` outcomes. Budgets and
  caller policy bound each step; the contract does not promise catch-up.
- Cursor authenticity is provenance-by-construction, not cryptographic
  anti-forgery. Tamper-resistant portable cursors remain deferred with cursor
  serialization.
- The bounded linearizability oracle runs wherever the host can support the
  harness, but useful concurrency exploration is principally the clj run;
  single-threaded cljs/cljd parity tests are not equivalent evidence.
- Attachment identities are unique only during one boundary lifetime. A
  process restart may reuse a representation, so persisted observations must
  scope it with boundary/session identity rather than treating it as global.
- During migration, v1/v2 coexistence can drift. The per-consumer migration
  plans and the end condition below are the control; coexistence is not a
  permanent compatibility promise.

## End condition — v2 is not a permanent fork

The v2 namespace exists to protect the running system during migration,
not to live forever. The slice is complete when Phases 1–5 pass on clj and
cljs — Phase 4 being 4a, 4b and 4c, flow control having been deferred out of
it — and it is explicitly **incomplete on cljd** until the cljd ws transport
lands, so the end condition is not met before then. Flow control is not part
of the end condition and does not hold it open.

When the slice is complete and the last consumer has migrated (under its
own plan), legacy `dao.stream` is deleted and a single decision is taken
explicitly: `dao.stream.v2` is renamed to `dao.stream`, or keeps its name
permanently. An undecided coexistence of both namespaces is a defect of the
migration, not a steady state.
