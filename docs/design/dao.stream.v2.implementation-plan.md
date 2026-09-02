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
destination *and its declared retention nature as data*, derived from the
same creation specification that produced the handle, and refuses a
reject-mode declaration at assembly. This is configuration provenance, not
runtime introspection. Runtime behavior is unchanged: every non-`ok`
deposit result tears the connection down.

**Logical-stream identity is minted in Phase 2.** `cursor-mismatch` in
Phase 2 and stable descriptor identity in Phase 3 are the same identity.
It is allocated when a logical stream is created, and both cursors and
descriptors denote it. Their *representations* need not match and need not
be equally serializable — cursor serialization across hosts stays TBD per
the contract — but they must refer to the same thing.

## Phase 1 — Contract core (`src/cljc/dao/stream/v2.cljc`)

The protocol and data surface, with no transport. The seven operations are
two kinds, and the split is the point:

- **Five handle operations**, as protocols a handle implements per its
  declared surface: `descriptor` (universal identity), `cursor` and `next`
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
- `descriptor` on every handle regardless of surface — identity is
  universal and outlives an attachment, so it must not be bundled into the
  reader or writer protocol.

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
considers itself behind is that reader's own configuration, supplied to the
flow-control interpreter (4d) by the composition that wires it.
- Cursor anchors `:dao.stream/oldest` and `:dao.stream/newest`;
  `invalid-anchor`; cursors carry the logical-stream identity
  (`cursor-mismatch` / `invalid-cursor` detectable); valid across handles
  of the same logical stream.
- A transport-owned `lag` operation beside the public surface, under the
  license the contract's Surfaces section grants: it takes a handle and a
  cursor and answers, as data, how many retained values lie between that
  cursor's position and the newest. It is total: eviction is oldest-first, so
  a cursor whose position is no longer retained is behind every retained
  value, and `lag` answers the full retained count — maximally behind. It is not a DaoStream operation and adds
  nothing to the contract's seven. Its one consumer is the flow-control
  interpreter (4d), which is handed it as an argument by the composition that
  wires it — never reaching through a handle to find it — and is therefore
  medium-specific code by construction, as the contract requires of anything
  that uses a transport-owned operation.
- One coherent state per operation (single atom; one deref per `next`),
  satisfying the Concurrency section.

Explicitly absent: waiters, drain, take, seq views, `closed?`, and **live
resize** — resize is compatible with the contract but proves nothing the
slice needs, and fixed declared capacity is sufficient for deposit
admission. It follows the slice.

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
  survives the transit codec structurally unchanged.
- A **host-kept local directory** — a map from stream identity to live
  handle, owned by the test composition, not by DaoStream and not by any
  transport. It is deliberately test-only: the ws serving side in Phase 4
  resolves `:ws/path` against its own served-stream table and does not
  reuse this. Say so in the code, so nobody later mistakes it for
  infrastructure.
- Round-trip test: descriptor → encode → decode → `attach!` against that
  composition → handle on the same logical stream; a kept cursor resumes
  through the new handle. This is where the kept-cursor promise is proven,
  on a transport that has a reader surface.
- `not-found` for descriptors nothing backs.

**Decision gate — descriptor key set.** The contract leaves the descriptor
envelope TBD and this phase produces the evidence for settling it. Phase 4
must not start against provisional keys: review the round trip's key set,
settle it in `dao.stream.md`, and settle `:ws/…` in `dao.stream.ws.md`
against it. Provisional keys carried into a wire format under delivery
pressure become permanent by accident.

**Decision gate — wire contract.** The ws spec's Handshake section says the
wire's exact form is not specified yet, and its Deferred list holds the
pieces: how the served stream's identity is presented on connect, the exact
form of the authoritative disclaimer, the application-range close code that
distinguishes an ended stream from a dropped connection, the value codec, and
what a receiver does with wire input that fails to decode. 4a builds both
ends of that wire and must not start before they are settled — in
`dao.stream.ws.md`, where they live — for the same reason as the descriptor
gate: until then two independently written implementations cannot
interoperate, no wire-level conformance test can be written, and provisional
choices carried into a wire format under delivery pressure become permanent
by accident. The gate settles what the Deferred list already scopes; it adds
nothing to that list and invents no protocol here.

## Phase 4 — WebSocket transport, forwarding, and serving composition

Per `dao.stream.ws.md`, both ends. Three deliverables in three layers —
transport, infrastructure, composition — deliberately separate:

### 4a — The transport (`src/cljc/dao/stream/v2/ws.cljc`)

- **Client side**: `attach!` connects and returns without waiting, answering
  only what is decidable here and now — `ok`, `invalid-descriptor`, or
  `transport-error` for a handler that fails locally; see *Settled before
  Phase 1*. The handle is writer+closable only; `append!` with transient
  `full` (including while the connection is still establishing),
  `invalid-value` for unencodable values, `closed` once down; `close!`
  means disconnect, reattachable;
  connection loss is the same detachment, uninvited.
- **Server side**: the accepted-connection handle — writer+closable, minted
  from a socket the runtime handed over, so neither `create!` nor `attach!`
  produces it — with the same `full`, `closed`, `transport-error`,
  send-racing-close, and identity rules as the client handle; plus that
  connection's own boundary adapter for its inbound events.
- **Transport constructor**: the entry functions a host puts in its dispatch
  table are closures this constructor produces, having received the deposit
  destination and its admission declaration. Bare namespace vars could reach
  that state only through namespace globals.
- **Handshake**: encode and recognize the served-stream presentation and the
  authoritative disclaimer as settled at the wire-contract gate and recorded
  in `dao.stream.ws.md` — wire protocol, so
  both ends of it are transport, not composition.
- **Boundary adapter**: deposits every inbound event — payload and
  lifecycle, including how the connection resolved, judging nothing — into the
  host-composed deposit destination — one medium per boundary in this slice,
  the granularity being composition policy per `dao.stream.ws.md` — as
  `:ws/attachment`-tagged envelopes — the same value `attach!` returns under
  `:dao.stream/attachment`, per `dao.stream.ws.md` — enforcing admission at assembly
  against the declaration passed alongside the destination, and tearing the
  connection down on any non-`ok` deposit result.
- No callbacks in the public surface; the legacy `on-open!`/`on-message!`/
  `on-close!` shape does not reappear.
- **Dependency check**: `v2/ws.cljc` requires only `dao.stream.v2` — never
  the ringbuffer namespace. The ws transport depends on the protocols; the
  ring buffer is the slice's composition partner (deposit destination,
  served stream), wired by the host composition, not by the transport. A
  transport requiring another transport is the legacy registry defect
  reborn.

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
never moved, which is what makes flow control (4d) need no protocol for
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

### 4d — Flow control

Without this, a reader that falls behind loses data: the deposit medium evicts
its oldest values and the reader is told it missed them. That is honest, and
right for a live feed where the newest value is the one that matters. It is
wrong as the only option, and it makes eviction the normal mode rather than
the backstop it should be.

Nothing here changes the ws transport or the contract; the one piece of
mechanism, the measurement, is a ring buffer transport-owned operation
delivered in Phase 2. Pause requests travel as ordinary values
on the socket the client already writes to, and the serving side reads them
off its own request medium (see `dao.stream.ws.md`, *Serving*). Two
interpreters, one per end.

- **The reader publishes its own backlog.** Only it can: a stream holds no
  reader positions, so neither the medium nor the adapter can know how far
  behind anyone is. Occupancy relative to *your* cursor is private to you.
  It measures that occupancy with the deposit medium's transport-owned `lag`
  operation (Phase 2), handed to it by the same composition that supplies
  its threshold; the contract's public surface gains no operation for this.
  When its lag passes the threshold its composition configured, the reader
  asks for a pause.

  A `gap` on the measured medium outranks any measurement: the reader
  recovers its cursor and measures again before requesting or renewing
  anything. An evicted cursor is maximally behind by definition, so without
  this rule the reader renews a pause forever against a position that no
  longer exists — pausing harder in exactly the situation that calls for gap
  recovery instead.
- **A pause is a lease, not a switch.** The request carries a duration — hold
  for *n* — and the reader renews while it is still behind. A switch has a
  stuck state: a lost resume, or a reader that dies mid-pause, leaves the
  sender stopped forever. A lease expires, so absent renewal the system
  returns to sending on its own, and a dead reader is indistinguishable from
  one that caught up.
- **The serving composition acts on it**: stop stepping the connection's
  forwarder, resume stepping when the lease lapses or the reader stops
  renewing. The kept state's cursor never moved, so nothing says where to
  resume.
- **Liveness**: the serving side pings on an interval and drops a connection
  after two unanswered. A connection can die without either side being told,
  and a paused forwarder would otherwise sit indefinitely on a peer that is
  gone.
- **Caps**: a maximum lease duration, so one request cannot buy an hour, and a
  maximum consecutive paused time, after which the connection is dropped
  rather than renewed forever.

**Why this cannot be turned against the server.** The served stream evicts on
its own schedule regardless of who is attached, so a client that pauses and
never returns causes the server to retain nothing extra — it simply falls
behind and comes back to a `gap`. The loss moves upstream, bounded by the
served stream's retention, and lands where the producer can be told about it.
A client that pauses forever harms only itself. The caps above make that
bounded rather than merely self-correcting.

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

## Host matrix

- **Phases 1–3** are pure `.cljc` with no transport: they must compile and
  pass on clj, cljs (Node), and cljd. Nothing in them is host-specific, and
  a parity break here is cheapest to find here.
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
| `descriptor` via handle metadata     | `descriptor` operation        | Identity is universal, answered by every handle, and outlives any attachment.              |

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

## End condition — v2 is not a permanent fork

The v2 namespace exists to protect the running system during migration,
not to live forever. The slice is complete when Phases 1–5 pass on clj and
cljs; it is explicitly **incomplete on cljd** until the cljd ws transport
lands, and the end condition is not met before then.

When the slice is complete and the last consumer has migrated (under its
own plan), legacy `dao.stream` is deleted and a single decision is taken
explicitly: `dao.stream.v2` is renamed to `dao.stream`, or keeps its name
permanently. An undecided coexistence of both namespaces is a defect of the
migration, not a steady state.
