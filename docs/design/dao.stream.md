# DaoStream Design

Status: agreed design target. This document is the DaoStream contract. It does
not redesign Yin.VM. Yin.VM is a downstream consumer and will be addressed only
after this contract is implemented and tested.

## Purpose

This contract derives from the foundational axioms and non-negotiable
invariants of [`datom.world.md`](./datom.world.md). Two axioms bear on it
directly, named here once so the sections below can cite rather than restate
them:

1. **Everything is a Stream** — all IO and data flow through append-only
   streams.
2. **Interpretation Creates Semantics** — data is syntax; semantics emerge
   only through interpretation. One truth, many perspectives.

DaoStream is the passive substrate for IO in datom.world — passive because
interpretation and execution must not collapse into one layer. Axiom 1 is
what makes this the layer every host boundary crosses. A stream carries
values and decides nothing about them:

- **What the values mean** is not its business — Axiom 2. An element
  judged, filtered, or typed at this layer destroys the perspectives nobody
  has taken yet.
- **Which interpreter observes them** is not recorded — a registry of who
  is listening would be hidden global state.
- **When an interpreter runs** is not its decision — "do not introduce
  callbacks; every callback is an event on a stream", seen from the stream's
  side: DaoStream invokes nothing, so control is never inverted and
  causality stays explicit (see The IO Model).

The Invariants below are these denials in operational form.

The design separates five concepts:

1. A **creation specification** is data for creating a new logical stream.
2. A **handle** is a host-local operational stream value — the
   implementation of the `dao.stream` protocols.
3. A **logical-stream identity** is transport-independent plain data shared by
   every handle and descriptor for one stream.
4. A **portable descriptor** is reachability data used to attach to an existing
   stream; it includes the logical-stream identity but is not that identity.
5. A **cursor** is immutable interpreter-owned observation state for one
   logical stream.

Creation is not attachment. A descriptor is not a creation specification or an
identity. A handle is not portable identity. A cursor is not handle-owned
state.

Seven operations range over them, each returning an outcome map (see
Result Convention):

| Operation    | Consumes               | Yields             |
|--------------|------------------------|--------------------|
| `create!`    | creation specification | handle outcome     |
| `attach!`    | portable descriptor    | handle outcome     |
| `descriptor` | handle                 | descriptor outcome |
| `cursor`     | handle, anchor         | cursor outcome     |
| `next`       | handle, cursor         | read outcome       |
| `append!`    | handle, value          | write outcome      |
| `close!`     | handle                 | close outcome      |

A handle implements the subset of the **handle surface** its transport
declares (see Surfaces). `create!` and `attach!` are not part of it: they
consume no handle and produce one.

## Invariants

- Stream elements are data. DaoStream assigns them no intrinsic meaning.
- Reading is non-destructive. A reader advances its own cursor, not the stream.
- Multiple cursors may observe the same retained value independently.
- Interpreters own cursors and decide when to call `next`.
- A runtime may drive an interpreter, but DaoStream does not require a runtime.
- Closing a stream changes future availability but does not itself erase
  retained history.
- Retention may be bounded. A cursor over evicted history receives a gap
  outcome as data; the reader decides what missing data means.
- All expected operational outcomes are data, not exceptions or callbacks.
- No operation waits. Every operation returns what is true at the moment it is
  called; an answer not yet knowable arrives later as data.
- No hidden global state. DaoStream keeps no registry of streams, handles, or
  observers, and no operation consults ambient state.
- A logical stream has an identity that is plain data, the same through every
  handle on it, stable across attachment lifecycles and across serialization.
  Projecting that identity never fails.

Every clause below must be consistent with these invariants, and most derive
from one directly. Where a clause is instead an interoperability decision — the
descriptor's properties, the result keyword namespace, the anchor set — it is
stated as a decision rather than dressed as a deduction. A proposed addition
that contradicts an invariant does not belong in this contract; one that merely
cannot be deduced from them must say which decision it is and why.

## Result Convention

Every DaoStream operation returns data.

- Each operation's set of outcomes is **exhaustive**: a transport may produce
  fewer of them, never any outside the set. Which subset it produces is part of
  its declared nature (see Surfaces). An outcome is a map whose
  `:dao.stream/outcome` key names the outcome with a qualified keyword. Every
  operation returns such a map — including projections like `descriptor` and
  `cursor`, whose success map carries the projected value.
- `:dao.stream/outcome` is present in every result map and is never repeated
  in the per-outcome tables below; the keys those tables name are required in
  addition to it.
- Result maps are **open**: the contract fixes which keys are required per
  outcome, and a consumer must ignore keys it does not understand. This is
  what allows future extensions (for example readiness notification) to be
  added without breaking existing consumers.
- Exceptions are reserved for defects in the host's own assembly of
  DaoStream, detected before any operation runs. Everything observable at an
  operation returns data: closed, full, blocked, end, gap, not found,
  malformed input, and transport defects are all outcomes, never exceptions
  and never callbacks.
- All qualified keywords in results live under `:dao.stream/…`.

## The IO Model

Three models are available for expressing IO. DaoStream is deliberately the
third.

**Blocking (synchronous) IO.** The operation parks until it has an answer,
then returns it. Control flow stays sequential and the call site reads as
ordinary code, which is the whole of its appeal. The cost is that something
must wait — a thread per pending operation, held for its duration — and it
requires a host that can park at all.

**Callback (asynchronous) IO.** The operation registers a continuation and
returns at once; the runtime invokes the continuation when the answer
arrives. Nothing waits, so one thread carries many pending operations: this
is the model that scales. Its cost is inverted control. The caller no longer
decides when it observes the answer, and the inversion is viral — on a host
without blocking, a promise cannot be absorbed inside a function, because
awaiting returns to the caller at the first suspension point and makes the
caller's own result a promise in turn. Whatever is reached from a callback
is reached from a callback.

**Non-blocking polling.** The operation returns immediately with whatever is
true now, including the answer "nothing yet, ask again." Nothing parks and
nothing is inverted: the caller keeps control and decides when to ask again.
This is POSIX `O_NONBLOCK` without `select`, and it is DaoStream's model.

`next` is total and non-blocking (see Reading): every state of the stream
has a defined data answer, and `:dao.stream/blocked` is `EAGAIN` — this
position holds nothing yet. `append!`'s `:dao.stream/full` is the same
answer for writes. No operation in this contract parks, and none takes a
callback; the Result Convention's "never exceptions and never callbacks" is
this model stated as a rule about results.

The rule generalizes past `next`: **no operation waits.** An operation that
cannot yet know its answer returns the outcome true at the moment it is
called, and the eventual answer arrives the way every other fact does — as
data, observed later, through an ordinary operation or on a stream. A
DaoStream operation is never a request for something to happen and be
reported back. It is a question about now.

### Why this model and not the other two

The hosts do not offer the same choices. The JVM has both blocking and
asynchronous IO. JavaScript and Dart have only the asynchronous kind: there
is no blocking read to call and no way to wait for one, because the single
event loop that would deliver the answer is the one a waiting call would be
occupying — waiting deadlocks rather than merely costing a thread.

A blocking contract is therefore unimplementable on most of the targets. A
callback contract is implementable on all of them and inverts control on all
of them, including the host that never needed it. Non-blocking polling is
the only one of the three that means the same thing everywhere — the same
operations, the same outcome sets, the same control flow — which is what
lets one surface serve clj, cljs, and cljd without the shape of the API
differing by host.

### What it costs

Polling without readiness notification is O(n) in streams observed: a reader
holding ten thousand idle streams must ask all ten thousand to learn which
have data. This is `select` without `select`, and it is a real limit rather
than an oversight — the extension that answers it is reserved below. The
cost also lands where it can be paid: DaoStream schedules nothing, so
cadence belongs to the runtime driving the interpreters, and a readiness
mechanism belongs there too.

### The readiness extension

This contract has no readiness or waiter extension. A reader that receives
`:dao.stream/blocked` retries `next` when it chooses; retry cadence is the
concern of the interpreter or the runtime driving it, not of the stream.

A future readiness extension would be additive: an optional protocol on
transports that support it, plus optional keys in operation results (for
example a wake token list on `append!`), which the open-map rule already
permits. Nothing in this contract needs to change for that; no consumer of
this contract may depend on it existing. The standing answer to *What it
costs* is `dao.stream.waitset`: poll-based classification over a complete
ordered sweep, with the host cadence layer deciding when the sweep runs —
which is why no consumer needs the reserved extension to sleep between
rounds, and the contract remains untouched by it.

## Creation and Attachment

Creation brings a new logical stream into existence. Attachment joins an
existing one. They are separate operations with separate inputs.

- `create!` consumes a creation specification and returns a handle on a
  new logical stream. `descriptor` on that handle returns `{ok}` with both
  `:dao.stream/descriptor`, the transport-specific reachability value, and
  `:dao.stream/identity`, the transport-independent logical-stream identity.
  The descriptor carries the same identity internally; the sibling projection
  and its value inside `:dao.stream/descriptor` must be structurally equal.
  Both projections are total and outlive any attachment, so `descriptor` has
  no failure outcome.
- `attach!` consumes a portable descriptor and returns a handle on an
  attachment to the **same** logical stream the descriptor names. Attaching
  never creates.
- A creation specification says how to build a stream. A descriptor only
  names one — never executable code, never a construction program, never a
  request to load a plugin.

Streams are values that can be sent through streams — up to a serialization
boundary. An in-memory stream whose admission declaration permits host
references can carry a handle intact. A dynamically delivered handle travels
as composition data together with its declared surface, for example
`{:dao.stream/handle h :dao.stream/surface #{:reader :writer}}`; the receiving
interpreter uses only that declared surface, and attaching to what it already
holds is meaningless. A composition may omit the wrapper only when the
receiver's fixed contract already declares the surface. But a handle does not
survive encoding, and any
transport that encodes its elements — a file as much as a network socket —
is a serialization boundary. Descriptors exist for crossing those
boundaries, where the live value cannot travel and a name must. (What a
transport can carry is transport-owned: handing an encoding transport a
value it cannot encode is reported as data like any other outcome.)
`attach!` therefore consumes only descriptors, and
resolves them against the transport's inherent operational state — the
streams a network endpoint serves because serving them is what it is, the
files a filesystem holds. There is no stream table in this contract: no
registry maps descriptors to live handles, so a descriptor arriving
somewhere with nothing behind it — a ring buffer's descriptor on any other
host, or on its own host absent a directory some composition chose to keep —
honestly resolves to `:dao.stream/not-found`.

### Envelopes

A **creation specification** is a map with one required key:

- `:dao.stream/type` — a qualified keyword naming the transport. This is the
  dispatch key.

Every other key is transport-owned, qualified under the transport's namespace
(for example a ring buffer's capacity), and validated by the selected handler
— generic validation checks only the envelope, and a handler that rejects its
own keys returns `:dao.stream/invalid-spec`.

The **portable descriptor** is inspired by CORBA's interoperable object
reference. It is reachability data, not the logical-stream identity itself.
Its properties are settled:

- It carries `:dao.stream/type` — the transport, as above — as its dispatch
  key.
- It carries the logical-stream identity under the contract-owned
  `:dao.stream/identity` key. Different descriptors may reach
  the same logical stream through different endpoints; their reachability data
  may differ, but their logical-stream identity is structurally equal. The same
  descriptor attaches to the same stream every time.
- It is self-contained. If the stream is remote, it contains whatever is
  needed to connect — perhaps an IP address and port. The entry data is
  transport-owned; a handler that cannot make sense of it returns
  `:dao.stream/invalid-descriptor`.
- A transport whose attachments are distinguishable assigns each attachment
  an opaque, structurally serializable value, unique among the attachments
  that transport can tell apart. Every attachment handle the transport mints
  has such an identity, whether the handle is returned by `attach!` or
  minted from a host connection accepted without an `attach!` call. An
  `attach!` success map carries the value under `:dao.stream/attachment`.
  When no `attach!` call produces the handle, the transport specification
  names the deposited event or other stream-native mechanism by which the
  composition receives the identity. Wherever an answer or event for that
  attachment is displaced onto another channel, the same value correlates it
  there. The key and correlation rule are the contract's; minting,
  representation, and the server-side delivery mechanism are the
  transport's. Transports that cannot distinguish attachments omit it.
- It carries no authorization. Nothing in this contract gates who may attach:
  a descriptor is a name, and a host that can reach what it names attaches.
  Whether attachment should be gated at all, and by what, is a question this
  contract does not answer and does not reserve room for — an outcome or key
  held open for an undesigned mechanism constrains that mechanism's design
  from a document with no business specifying it. Should gating arrive, it
  arrives as an addition, which the open-map rule and this section's own
  transport-owned keys already permit.

For the time being, the operative guarantee is the round trip: `descriptor`
on a live handle returns `:dao.stream/ok` with `:dao.stream/descriptor`,
a value that can be sent on a stream and reconstructed on the other end, and
`:dao.stream/identity`, equal through every handle and descriptor for the
logical stream,
where `attach!` on the reconstructed value attaches to the stream it names —
provided the receiving host can reach what it names at all (see Creation and
Attachment on reachability), and allowing that a transport which cannot know
that at call time reports it later as data.

Both envelopes are plain data through and through: every value must survive
the host serialization codec structurally unchanged, so equality is
structural, and no key holds a function, a host object, a live handle, or
identity smuggled in metadata. Like every DaoStream map, envelopes are open —
a consumer ignores qualified keys it does not understand.

`create!` outcomes:

| Outcome                       | Meaning                                       | Required keys        |
|-------------------------------|-----------------------------------------------|----------------------|
| `:dao.stream/ok`              | A new logical stream exists, its identity settled at once. Implies not that every resource backing it has finished being acquired. | `:dao.stream/handle` |
| `:dao.stream/invalid-spec`    | The creation specification is malformed.      | —                    |
| `:dao.stream/not-found`       | No transport here matches `:dao.stream/type`. | —                    |
| `:dao.stream/transport-error` | The handler failed.                           | —                    |

`attach!` outcomes:

| Outcome                          | Meaning                                                                             | Required keys        |
|----------------------------------|-------------------------------------------------------------------------------------|----------------------|
| `:dao.stream/ok`                 | A handle exists through which this attachment is operated. Implies neither that a far end has confirmed the stream it names nor that establishment has completed. Where either answer is one only the far end can give, it arrives later as data, on the channel the transport names (see Surfaces). | `:dao.stream/handle`, and `:dao.stream/attachment` where the transport distinguishes attachments |
| `:dao.stream/invalid-descriptor` | The descriptor is malformed.                                                        | —                    |
| `:dao.stream/not-found`          | The descriptor names no stream here, or no transport matches its type.              | —                    |
| `:dao.stream/transport-error`    | The handler failed.                                                                 | —                    |

Where a transport acquires its medium through a process rather than at once —
binding a port, opening a handle through an API that only answers later — `ok`
still means the logical stream exists: its identity is minted, its sequence is
empty, and the handle is operable. What it does not mean is that the stream can
yet accept values. The handle reports that through its own operations, exactly
as any other transient condition is reported, and the result of the acquisition
arrives as data.

This is permitted only where logical existence is genuinely independent of the
resource. A transport that cannot mint identity and lifecycle before its
acquisition completes is not creating a stream and deferring its medium; it is
deferring the creation itself, and `ok` would be false. Such a transport does
not implement `create!` — what it needs is a request whose result arrives
later, which is an ordinary interpreter over streams, not an operation here.

Creation defers differently from attachment, and the difference is why this is
a caveat rather than a new outcome. `attach!` defers a **remote confirmation**:
an answer only a far end can give, which is why it arrives as deposited data.
`create!` has no far end. What it defers is **local acquisition**, whose
failure surfaces through the handle the caller already holds. A "not yet"
outcome would have to return no handle — leaving the caller an identity it
cannot operate, and nothing to retry against.

### Host Dispatch

When a creation specification or portable descriptor arrives dynamically —
for example off a stream — the host looks at it and dispatches on
`:dao.stream/type`. The type resolves to a map that has the transport's
`:dao.stream/create` and `:dao.stream/attach` implementations:

```clojure
{:dao.stream/ringbuffer {:dao.stream/create ringbuffer/create!
                         :dao.stream/attach ringbuffer/attach!}
 :dao.stream/ws         {:dao.stream/attach ws/attach!}}
```

The entries are per-transport and may be partial. A transport that creates
no streams supplies no `:dao.stream/create` — the WebSocket transport owns
no stream, it serves streams the host composed, so nothing there creates
one. A creation specification naming it finds no create implementation and
returns `:dao.stream/not-found`, the same answer as an absent transport and
the correct one: nothing here creates that.

Entry values are **host-composed**: ordinarily closures produced by a
transport constructor that already received whatever composition state the
transport needs — a boundary's deposit destination and its admission
declaration, a directory some composition keeps. The bare vars above are
schematic. A bare namespace var could reach that state only through namespace
globals, which is the hidden state this contract retires.

This table is ordinary data owned by the host, and the dispatch is an
ordinary map lookup done by the host — DaoStream neither performs nor
standardizes it. A host that cannot dispatch on `:dao.stream/type` returns
`:dao.stream/not-found` — never creation or discovery.

Code that knows its transport statically calls the transport's functions
directly; no dispatch is involved. There is no ambient namespace-global
registry and no namespace-load registration side effect.

## Surfaces

A handle implements the subset of the public surface its transport can
honor — **reader** (`cursor`, `next`), **writer** (`append!`), **closable**
(`close!`) — and the transport declares which. The Cursors, Reading, and
Retention and Gaps sections govern handles with a reader surface only.

`descriptor` belongs to no surface: every handle answers it with both
reachability and logical-stream identity, because both outlive any attachment.
An operation a handle does not
declare is not part of that handle at all: there is nothing to call, so there
is no operation for this contract to give an outcome. What a host does when
asked for a method that is not there is the host's, and no transport may
answer such a call with a DaoStream outcome map — an outcome would imply the
operation exists.

A transport may also offer operations of its own beside the public surface —
answering a question this contract does not ask, such as how far a cursor
trails the newest value. They are transport-owned, they are not DaoStream
operations, and nothing here defines or constrains them. **Code written
against this contract may not reach for one**: it must work on any transport
declaring the surfaces it uses, and a transport-owned operation is by
definition absent from some of them.

An interpreter may still use one, on the same terms as any other thing a
composition supplies. What it may not do is *find* it — reach through a handle
for an operation the contract never promised, or dispatch on which transport
it was given. It receives the operation as an ordinary argument from the
composition that wired it, exactly as it receives the streams it reads and the
policies it applies, and it then depends on a function it was handed rather
than on a transport it detected. Such an interpreter is composed for a
particular medium and says so; it is not contract-generic code, and nothing
contract-generic may be built on it.

The reader surface is a promise: the handle presents its elements as
one positioned, append-only, retained sequence that cursors can observe and
re-observe. A transport whose medium retains nothing cannot make that
promise and has no reader surface: its inbound events are deposited by the
host's adapter onto the stream the host composition wired for that boundary
— where the reading model applies in full. The log lives beside such a
transport, composed explicitly by the host, never hidden inside the
transport. A deposit destination must admit every valid boundary event per
its declared retention; wiring one that can refuse is a host assembly
defect. Deposit admission is declared, never interrogated: the contract's
public surface has no way to ask a handle its retention policy. A constructor
takes the deposit destination and its admission declaration as data. This is
configuration provenance, not runtime introspection. (`dao.stream.ws.md`
specifies one such transport and the admission rule in detail.)

A boundary that can no longer deposit must make itself **observably gone**. Its
one permitted action is closing the host resource it itself holds; it invokes
nothing above itself, retries nothing, and interprets nothing. This is the sole
action a boundary adapter takes beyond appending, and it is not a judgement
about any event — it is the same action for every cause. One corner is
irreducible: when the deposit that failed was the boundary's only channel, the
record of its own departure has nowhere to land, and the failure is observable
only to whoever holds the deposit result and through subsequent operations on
the handle.

A transport also declares **which outcomes it produces**, and for each one it
excludes, why. There are three honest reasons: the outcome's precondition is
impossible by the transport's nature (an unbounded log never evicts, so never
reports `gap`); another policy answers the same condition, named (a ring buffer
evicts rather than refusing, so pressure surfaces as a `gap` to a cursor that
spans the eviction, and never as `full`); or the answer is displaced to another channel, named (a transport
that cannot know a remote answer at call time reports it as data elsewhere). An
exclusion with no stated reason is an unimplemented outcome, not a declaration.

### Where the asynchrony goes

The IO Model makes this contract non-blocking; hosts are asynchronous
whether or not it is. A network message arrives when the runtime says so, in
a callback, on a thread or event loop DaoStream does not own. DaoStream does
not pretend otherwise; it confines the inversion to one place. The host's
boundary adapter *is* callback code, and its entire job is to `append!` what
arrived. Past that append the event holds a position in a sequence, and
every reader above observes it with its own cursor, in its own control flow,
when it chooses.

This is an un-inversion. The real cost of callback IO is not performance but
that the inversion spreads through everything it touches; depositing into a
retained sequence pays that cost once, at the boundary, and returns ordinary
code to everyone above. The reader-surface rule above is the same thing
stated structurally: a transport that retains nothing has no reader surface,
and its inbound events are deposited onto a stream that does.

## Cursors

A cursor is an immutable value owned by the interpreter that holds it.

- Cursors are minted by the stream: `cursor` on a handle takes an **anchor**
  and returns `:dao.stream/ok` with `:dao.stream/cursor`. The contract
  defines two anchors — `:dao.stream/oldest`, the earliest retained
  position, and `:dao.stream/newest`, positioned after the newest appended
  value so the first `next` observes the next value to arrive. A transport
  whose medium is naturally addressable may accept additional
  transport-owned anchors (a byte offset, a timestamp). An anchor that is
  malformed or not honored by this transport yields
  `:dao.stream/invalid-anchor`. On a handle whose attachment has closed,
  `cursor` yields `:dao.stream/closed`; a handle on the logical stream
  itself keeps minting cursors after close, so retained history remains
  readable. Minting may have to consult the medium — locating the earliest
  retained position on a durable log is itself a read — so where a
  transport's reads can fail at runtime, minting can too, and `cursor`
  yields `:dao.stream/transport-error` for it; a transport whose medium
  cannot fail this way excludes the outcome like any other (see Surfaces).
  `cursor`'s outcome set is therefore `:dao.stream/ok` (with
  `:dao.stream/cursor`), `:dao.stream/invalid-anchor`,
  `:dao.stream/closed`, and `:dao.stream/transport-error`.
- A composition that intends to observe events caused by an operation mints
  its `:dao.stream/newest` cursor **before** invoking that operation. Minting
  after `attach!`, endpoint bind, handoff installation, or any other operation
  that can deposit would intentionally skip events deposited in between.
- **A freshly minted `:dao.stream/oldest` is a position, not a claim about
  completeness.** The anchor names the earliest *retained* position, which on
  an evicting transport advances as values are lost. A `gap` is reported to a
  cursor that spans an eviction; a cursor minted after one is a valid cursor
  onto the surviving suffix and reports nothing, because nothing is wrong with
  the cursor. Retained history is not complete history, and no anchor can make
  it so. A consumer that requires complete history gets it from the transport's
  declared retention (see *Retention and Gaps*), never from an anchor.
- A composition on a transport that **can evict**, intending to detect whether
  history was lost, mints an **origin cursor** — `:dao.stream/oldest` before
  the first append — and keeps it, exactly as a composition that intends to
  observe events caused by an operation mints `:dao.stream/newest` before
  invoking it. A kept origin cursor converts a silent loss into a reported
  `gap`. It cannot be minted afterwards and cannot be reconstructed: a consumer
  that arrives later holds no evidence about what preceded it. On a transport
  that declares complete retention this is unnecessary — a fresh `:oldest` is
  the origin there, however late the consumer arrives.
- Every valid cursor comes from the stream: minted by `cursor`, received as
  a successor from `next`, or recovered from a gap outcome. Consumers never
  construct cursor internals or fabricate positions — there is no `seek`. A
  cursor's representation belongs to the transport.
- A cursor binds to the logical stream, not to a handle: any handle on the
  same logical stream accepts it, so a cursor handed to another interpreter
  works with that interpreter's own handle. Whether a cursor survives
  serialization to another host is transport-owned and TBD, like the
  descriptor envelope.
- A cursor carries the logical-stream identity, not a reachability descriptor.
  `cursor-mismatch` compares that identity, so handles reached through distinct
  descriptors for the same logical stream accept the same cursor.
- Advancing is receiving a successor cursor from `next`. The stream never
  advances a reader; the stream holds no reader positions at all. Dropping a
  cursor is how a reader detaches — there is nothing to unregister.
- Because cursors are immutable values, a reader may retain an old cursor and
  re-read retained history, or hand a cursor to another interpreter. What a
  cursor can still observe is governed only by retention.

## Reading

`next` takes a handle and a cursor and returns an outcome immediately.
It is total and non-blocking: every state of the stream has a defined data
answer.

| Outcome                       | Meaning                                                                       | Required keys                                                                           |
|-------------------------------|-------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `:dao.stream/ok`              | A value was observed.                                                         | `:dao.stream/value`, `:dao.stream/cursor` (successor)                                   |
| `:dao.stream/blocked`         | Nothing at this position yet; retry later.                                    | —                                                                                       |
| `:dao.stream/end`             | What this handle is on is closed and this position is past the retained tail. | —                                                                                       |
| `:dao.stream/gap`             | This position was evicted.                                                    | `:dao.stream/cursor` (earliest retained position, or the tail when nothing is retained) |
| `:dao.stream/cursor-mismatch` | The cursor was minted by a different logical stream.                          | —                                                                                       |
| `:dao.stream/invalid-cursor`  | The value is not a cursor.                                                    | —                                                                                       |
| `:dao.stream/transport-error` | The transport failed to perform the read; nothing was observed.               | —                                                                                       |

Reading never mutates the stream. Two readers with cursors at the same
position observe the same retained value; neither can starve or affect the
other. There is no destructive read anywhere in the contract — no drain, no
take. An operation that removes an element as it reads would make one
reader's progress every other reader's data loss.

## Writing

`append!` takes a handle and a value and returns an outcome:

| Outcome                       | Meaning                                                                                                                                              | Required keys |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| `:dao.stream/ok`              | The value is accepted at the next position in the sequence this handle's writer surface is on. Implies neither local readability nor remote delivery. | —             |
| `:dao.stream/full`            | The transport does not accept the value; nothing was appended. Whether this is transient or permanent is the transport's declared nature (see Retention and Gaps). | —             |
| `:dao.stream/invalid-value`   | The transport cannot carry this value (for example, it cannot be encoded); nothing was appended.                                                     | —             |
| `:dao.stream/closed`          | What this handle is on is closed to new appends; the value was not appended.                                                                         | —             |
| `:dao.stream/transport-error` | The transport failed to perform the append; nothing was appended.                                                                                    | —             |

Acceptance is handle-relative, as close outcomes are. **Which sequence** a
handle's writer surface is on is part of the transport's declared nature: for a
ring buffer it is the logical stream itself, so `ok` places the value in it; for
a transport that carries values toward a stream elsewhere it is the ordered
outbound path, and what becomes of the value at the far end is reported there,
not here. No remote transport can say more — under *no operation waits*, an
answer from the far end cannot arrive before `append!` returns.

The writer decides what to do about any non-`ok` outcome; DaoStream does not
retry, buffer, or notify on its behalf.

## Close

`close!` takes a handle and returns a map whose outcome set is `{ok}` — no
other outcome exists for it. It is idempotent: closing an already-closed scope
is also `:dao.stream/ok`.

`ok` is the local lifecycle transition, not a report on releasing host
resources: what the handle is on is closed from that moment, irrevocably.
A transport whose close must also flush or release something that can fail
declares where that failure surfaces — an ordinary deposit on a channel it
names — because the outcome set has no room for it and the transition has
already happened.

`close!` closes **what the handle is on**. A handle explicitly designated by
the transport as the logical stream's owner closes that logical stream; a
server-side accepted-connection handle is an attachment and does not gain
ownership merely by being on the serving host. An attached
handle is on an attachment: closing it ends that attachment only — the
stream persists, its descriptor remains valid, and `attach!` may rejoin
later. Which scope a transport's handles carry is part of its declared
nature.

`:dao.stream/closed` and `:dao.stream/end` are handle-relative: they speak of
what *this handle* is on. (Not every outcome is: `gap` is retention-relative
and `cursor-mismatch` is logical-stream-relative.) After an attachment
closes, the handle's `append!` answers `closed`; `descriptor` still answers
`ok` with reachability and identity — both outlive any attachment; a reader surface, where the
handle has one, answers `end` from `next` once observation through this
handle is exhausted, and `closed` from `cursor` — no new cursors are
minted through a closed attachment. Cursors bind to the logical stream,
not to a handle, so a kept cursor resumes through a newly attached handle.

Closing an attachment freezes what that handle can still observe: values
appended to the logical stream afterwards are not observable through it, so
`end` is reached at the position the stream had reached when the attachment
closed. And a logical stream that has itself been closed remains attachable —
`attach!` answers `ok` and the new handle reads retained history to `end` —
because close changes future availability, not identity or reachability.

Closing the logical stream changes future availability only:

- Subsequent `append!` returns `:dao.stream/closed`.
- A reader that exhausts retained history receives `:dao.stream/end` instead
  of `:dao.stream/blocked`.
- Retained history remains readable. Close erases nothing; eviction is a
  retention concern, not a lifecycle one.

There is no `closed?` predicate in the public surface. Operation results are
authoritative; a predicate answer is stale the moment it is returned.

## Concurrency

Each logical stream is one append-only sequence, and a handle's writer
surface is on exactly one sequence — for some transports the logical stream
itself, for others the ordered outbound path toward a stream elsewhere (see
Writing). A successfully appended value occupies exactly one definite position
in the sequence this handle's writer surface is on, and every operation's
outcome reflects that sequence at one moment. DaoStream guarantees nothing
further about concurrent operations.

This follows from Axiom 2. The sequence a handle's surfaces are on is the one
truth for that handle; each outcome is one perspective on it at one moment. A
reader that observes
`:dao.stream/blocked` and then a value has not caught the stream in a
contradiction — it made two observations from its own cursor, and no global
view exists to contradict. Whether an append landed before a close is
answered by the sequence the handle's writer surface is on — the value has a
position in it or it does not — and where that sequence is not observable from
this handle, the answer is read where it is.
What an ordering between writers *means*, and any coordination among them —
a merge interpreter, a one-log-per-writer discipline — is interpretation,
and interpretation happens above the stream.

## Retention and Gaps

Retention and overflow behavior are transport-owned and form part of the
stream or writer surface's declared nature; a locally created stream records
them through its creation specification. A ring buffer retains a bounded window and evicts
the oldest; a file or append-only log retains everything; a network send
buffer refuses transiently. Which outcomes a given stream can produce —
`full` on append, `gap` on read — and whether its `full` is transient or
permanent follow from the transport's declared nature. DaoStream names the
outcomes and standardizes no policy.

**Eviction never waits on a reader.** Retention is the stream's own declared
policy and applies whatever any reader is doing — a stream holds no reader
positions, so it cannot be held back by a slow one. A transport that deferred
eviction until every reader had passed a position would make one reader's
absence every other reader's unbounded growth: a single stalled or departed
reader would hold the stream open forever, and a hostile one could do it
deliberately. Bounded retention is bounded, and a cursor that falls behind
receives a `gap`.

When bounded retention evicts a value, a cursor still pointing at it gets the
gap outcome from the Reading table. The stream reports honestly and decides
nothing: an interpreter replaying a log may treat a gap as fatal; a live
telemetry viewer may adopt the returned cursor and continue. Silently
skipping evicted history is forbidden — a reader holding a cursor across an
eviction is told it missed values. This is a promise to a **cursor**, not to a
handle: it is precisely what a kept cursor is worth, and it is why a consumer
that must know mints one at origin rather than asking later (see *Cursors*).

### Complete history

Some consumers require *complete* history rather than *retained* history: an
interpreter that derives state by replaying a log from its beginning is wrong,
not merely stale, if it replays a suffix. The contract already carries what
such a consumer needs, in two mechanisms that answer different questions.

**A transport that excludes `gap` gives completeness.** This is the existing
declaration in its existing form — the outcome's precondition is impossible by
the transport's nature, an unbounded log never evicts, so never reports `gap`
(see *Surfaces*). Retention is declared through the transport's nature and, for
a locally created stream, through its creation specification; it is
configuration provenance. There is no way to ask a handle about it and none is
added. A consumer requiring complete history therefore requires *a transport of
that declared nature*, and the composition that wires it is what supplies one.

**A kept origin cursor gives detection.** On a transport that can evict, an
origin cursor turns a loss that would otherwise be invisible into a reported
`gap`, and the consumer aborts or recovers on its own policy. It does not make
the history complete; it makes incompleteness observable.

Both stand, and they are not substitutes. Where completeness is a correctness
requirement, only the declared transport delivers it, and a kept origin cursor
is the honest failure mode that remains when completeness was not wired. Where
a consumer can proceed on a suffix but must know that it is on one, the origin
cursor is right and sufficient.

**Wiring a log onto an evicting transport is a host assembly defect**, of the
same kind as wiring a deposit destination that can refuse. A durable log is not
a window: if a consumer treats a stream as the record of everything that
happened, the transport under it must declare that it retains everything.
Capacity is the wrong knob for this — a larger window makes loss less likely
and never makes it reported — so sizing never substitutes for the declaration.

The obligation a complete-history transport takes on is one sentence: **a
complete-history reader begins at its logical sequence's origin and never
evicts an element of that sequence.** Every element present at creation, and
every value an append places on that same sequence with `ok`, remains part of
it for as long as the logical stream exists. If the medium cannot serve
retained history it reports `transport-error`; it never silently substitutes a
suffix. Everything else follows. Its `:oldest` anchor stays at the origin, so
`gap` is impossible and is excluded for that reason.

What it owes on the writing side depends on what it is. A complete-history
transport need not be writable at all — a finite immutable history is a valid
one, and owes no writer outcome. A writable one that declares a finite capacity
returns `full` on reaching it, without appending and without evicting: refusing
and evicting are the two answers to the same condition, and a transport that has
given up one owes the other. A writable one with no declared capacity — a
logically unbounded log, however it grows or spills beneath — may exclude
`full`. Logically unbounded is not physically infinite, and the line runs
between failures an operation can observe and return from and those it cannot:
unexpected exhaustion the transport can observe and return from is
`transport-error`, while fatal host or runtime exhaustion, from which the
operation produces no result at all, lies outside the outcome algebra
altogether. Neither is ever converted into the eviction of acknowledged
history. A transport whose every operation either completes its state
transition or does not return therefore has no firing condition for
`transport-error` and excludes it like any other outcome.

## Composition

DaoStream defines only streams, cursors, and the operations above. Anything
that transforms one stream into another is an interpreter — it reads via its
own cursor and appends to an ordinary output stream — and that lives entirely
outside this contract, needing no support from it. As in a Unix pipeline, the
pipe is dumb; the transforming happens in the processes on either end.

A forwarder between streams is a single step (not a callback loop) over the
transport-agnostic stream algebra. Because the stream never blocks, cadence is
owned by the composition-supplied driver that repeatedly calls the step,
yielding execution as needed by the host runtime.

Flow control (a reader pausing a sender) is an interpreter's concern. A pause
has to be a lease and not a switch to avoid permanently stuck states. Those
semantics live in `dao.lease.md`, whose facts are datoms on a medium
(`dao.space`). DaoStream provides the honest baseline: a cursor that falls
behind is evicted past and told so with a `gap`, and the reader holding it
decides what that means.

## Explicitly Absent

Absent from the public surface, by derivation from the invariants:

- **an ambient registry** — a namespace-global dispatch table, or registration
  as a load-time side effect. Host dispatch is ordinary data the host owns.
- **a `closed?` predicate** — a predicate answer is stale the moment it
  returns; operation results are authoritative.
- **destructive reads** — a read that removes what it observes makes one
  reader's progress every other reader's data loss. It is coherent only while
  exactly one observer exists; a forwarder, an indexer, or a DHT replica makes
  it theft. Where the capability went, so no later plan reinvents it: **taking
  is a write.** Removing a tuple from an append-only log is not a mutation but
  an assertion that it no longer holds, which `dao.space` already expresses as
  a retraction datom — Linda's `in` is observe-then-retract, not a stream
  operation. And the hard part of a take was never the removal but the
  *exclusion* of competing takers, which is a lease over datoms
  (`dao.lease.md`), not something a stream can promise. v1's `drain-one!`
  appeared to solve exclusion only because it ran in one process against a
  local ring buffer.
- **`seek`** — every valid cursor comes from the stream; anchored minting and
  kept cursors cover repositioning.
- **a blocking take** — no operation waits.
- **waiter registration** — there is no readiness extension.
- **throwing conveniences** — operational outcomes are data.
- **a retention or completeness predicate** — retention is declared, through
  the transport's nature and the creation specification. Asking a handle would
  be the same defect as asking whether it is closed: the answer is
  configuration the composition already holds, and reading it back at runtime
  invites code that branches on what it should have been wired with.

## Open Decisions

Status: **proposed, binding nothing.** Every other section of this document is
contract; this one is not. Each entry names an assumption the contract makes
without stating it, the use that would break it, and a proposed resolution.
An entry leaves this section in one of two ways: accepted, its text moves into
the section it amends and the entry is deleted; or rejected, the entry is
replaced by one sentence in *Explicitly Absent* recording why. Drafted
2026-09-19 from a review of this document against `dao.stream`, its three
transports (`ringbuffer`, `memory-log`, `ws`), and their consumers.

OD-1 to OD-4 are ordered by cost of delay. Each is a paragraph today, while
three transports and a handful of consumers exist, and a migration once a
durable or replicated transport ships. OD-5 was added afterwards and is
numbered by arrival; it concerns the layers above this contract rather than the
contract's own operations.

### OD-1. An unrecognized outcome has no defined meaning

**The assumption.** The Result Convention makes each operation's outcome set
exhaustive and makes result *maps* open. The two rules are asymmetric: a future
extension may add a key, never an outcome. The contract assumes the seven
outcome sets are complete for every transport that will ever exist.

**Evidence.** Consumers are already total over the closed sets and treat
anything else as a fault: `dao.stream.observe/step` classifies an unrecognized
read or effect outcome as `transport-error` and reports `:defect` or `:failed`;
`yin.vm.engine/handle-put` and `handle-next` throw. *Envelopes* declines to
reserve an outcome for gating and says gating "arrives as an addition", but
under the exhaustive rule a new outcome is not an addition.

**What breaks.** Any condition the present outcomes cannot express honestly:
attachment or append refused for lack of authority; a descriptor that is valid
but served elsewhere now; a transport that is throttling rather than full; an
append whose effect is unknown (OD-2). Each must either be squeezed into an
existing outcome that misdescribes it (`not-found` for unauthorized, `full` for
throttled) or break every consumer.

**Proposed resolution.** Keep each outcome set exhaustive *for this version of
the contract* and add one rule to the Result Convention that makes later
outcomes safe to introduce:

> A consumer that receives an outcome it does not recognize treats it as: not
> `ok`; nothing observed and nothing appended; and not retryable, unless the
> result map carries `:dao.stream/retry? true`. It does not throw, because an
> unrecognized outcome is a newer contract, not a defect in this host's
> assembly.

With that rule a newer transport degrades to "refused, do not retry" on an
older consumer instead of to an exception, and `:dao.stream/retry?` lets it
say when waiting is the right response. The existing `full` and `blocked` keep
their meanings and need not carry the key.

**Cost if deferred.** Every consumer written in the meantime hard-codes
"unknown means defect", and the first new outcome is a coordinated change
across all of them.

### OD-2. `append!` assumes its own effect is always knowable

**The assumption.** *Writing* defines `transport-error` as "the transport
failed to perform the append; nothing was appended", and `ok` as acceptance at
a definite position. Both assume the transport knows, at return time, whether
the value is in the sequence.

**Evidence.** True of all three transports today: `ringbuffer` and `memory-log`
are single in-memory state transitions, and `ws` sidesteps the question because
its writer surface is the outbound path, so `ok` claims only enqueueing. No
transport yet has a medium that can fail *after* accepting bytes.

**What breaks.** A durable file whose write succeeds and whose sync fails; a
log replicated to a quorum; an append forwarded to a remote sequencer. Each has
a third state, *unknown*, that the table cannot express. A consumer written
against the present text retries on `transport-error`, which is correct only if
nothing was appended, and duplicates the value when something was.

Two related gaps compound it. `append!`'s `ok` carries no correlation value, so
where the real answer is displaced to another channel (*Writing*: "what becomes
of the value at the far end is reported there, not here") nothing in the
contract ties that answer to the append that caused it. `attach!` has
`:dao.stream/attachment` for exactly this; `append!` has no counterpart. And
`ok` promises neither readability nor delivery nor durability, with no named
way to learn any of the three later.

**Proposed resolution.** Three sentences, no new operation:

1. Reword `transport-error` on `append!`: "The transport failed to perform the
   append. Whether the value was appended is unknown unless the transport
   declares that its failures are clean." The three existing transports declare
   clean failure, so nothing changes for them.
2. State the rule the RPC layer already follows: **deduplication and
   correlation are the payload's.** A writer that must retry safely puts an
   identity in the value; a reader that must not act twice deduplicates on it.
   The stream never promises exactly-once.
3. Name where later answers land: a transport whose acceptance is not its
   final answer (durability reached, far-end refusal) declares the channel on
   which that answer is deposited, as *Close* already requires for a failing
   flush.

**Cost if deferred.** The first durable transport either lies (reports clean
failure it cannot guarantee) or changes the meaning of an outcome that every
retry loop already depends on.

### OD-3. A cursor binds to the logical stream but is shaped by one transport

**The assumption.** *Cursors* promises that "any handle on the same logical
stream accepts it", and *Envelopes* allows that "different descriptors may
reach the same logical stream through different endpoints". *Cursors* also says
"a cursor's representation belongs to the transport". Together these hold only
if a logical stream lives on exactly one transport for its whole life.

**Evidence.** Cursor maps are keyed in the minting transport's namespace
(`:dao.stream.ringbuffer/identity` and `/position`,
`:dao.stream.memory-log/identity` and `/position`), so a cursor from one
transport is `invalid-cursor` on any other, whatever identity it names. Across
hosts the promise has never been exercised: `ws` has no reader surface, so
inbound values are deposited on a local stream with its own identity and its
own positions. What a remote host reads is a copy, not the stream. Finally,
"whether a cursor survives serialization to another host is transport-owned and
TBD", while *Explicitly Absent* removes `seek`: a kept cursor is therefore the
only checkpoint a consumer has, and whether it can be written down is
undecided.

**What breaks.** Replication and DHT replicas (which replica's positions are
the stream's?); re-homing a stream from memory to a durable medium without
invalidating every kept cursor; a durable consumer resuming after restart;
comparing two replicas to detect a forked history.

**Proposed resolution.** Two decisions, the first a choice:

1. **Choose what a logical identity ranges over.** Either
   (a) *one origin, one transport*: a logical stream is the sequence one
   transport instance owns. A copy on another host or medium is a different
   logical stream with its own identity; that it mirrors the first is a fact for an
   interpreter to record. This is how the code already behaves:
   `dao.stream.forward` copies into a destination with its own identity. The
   sentence
   about different descriptors reaching one stream narrows to "different
   endpoints of the same owning transport". Or
   (b) *contract-level position*: the contract defines a position within an
   identity (an ordinal), cursors carry it under a `:dao.stream/…` key, and any
   transport serving that identity honors it.
   Recommended: (a). It matches Axiom 2 (sameness between sequences is an
   interpretation), costs a paragraph, and leaves (b) available later as an
   additive key. (b) commits every future transport, including ones with no
   natural ordinal, now.
2. **Settle serialization.** Replace "TBD" with: "A cursor is plain data and
   survives the host serialization codec structurally unchanged, under the same
   rule as the envelopes. A cursor that outlives the stream it names yields
   `not-found` at `attach!` or `cursor-mismatch` at `next`, never a defect."
   Both existing transports already satisfy this.

**Cost if deferred.** The first durable or replicated transport decides both
questions implicitly, by whatever its cursor happens to look like, and every
consumer that persisted a cursor inherits that accident.

### OD-4. A logical stream can close but cannot end

**The assumption.** *Complete history* obliges a transport to retain every
element "for as long as the logical stream exists", and *Close* says close
"erases nothing". Nothing says how a logical stream stops existing. The
contract assumes storage is either bounded by eviction or kept forever.

**Evidence.** There is no destroy, truncate, compact, or redact anywhere in the
surface or in any transport. `memory-log` ends only when its process does. What
`next`, `cursor`, or `attach!` answer for a stream whose medium was removed out
of band is undefined; the nearest outcomes are `transport-error` and
`not-found`, and the document chooses neither.

**What breaks.** Reclaiming disk under a durable log; snapshot-and-truncate
compaction, the ordinary way a replayed log stays bounded; erasure that law or
a user requires. All three are forbidden to a transport that declared complete
history, and consumers are wired to that declaration as a correctness
requirement.

**Proposed resolution.**

1. Name a third retention nature beside *evicting* and *complete*:
   **compacted**. A compacted transport may drop a prefix it has summarized. A
   cursor into the dropped prefix receives `gap` exactly as under eviction, and
   the result map may carry, under a transport-owned key, a descriptor or
   content address for the summary that replaces the prefix. A consumer that
   requires complete history is not wired to one; a consumer that can resume
   from a summary is. No new outcome is needed.
2. State the end of existence: **destruction is transport-owned**, like any
   operation outside the seven. After it, `attach!` answers `not-found`; a live
   handle's `next` and `cursor` answer `end` if the handle was closed first and
   `transport-error` otherwise; `descriptor` still answers `ok`, because a name
   outlives what it named. Redaction of a single element is not a stream
   operation at all: under *taking is a write* it is a retraction datom, and
   what must be unreadable is made so by discarding a key held elsewhere.

**Cost if deferred.** The first durable transport ships as "complete", gains
consumers that depend on that word, and can then never reclaim space without
breaking them.

### OD-5. "No operation waits" binds the seven operations and nothing above them

**The assumption.** *The IO Model* argues that a blocking contract is
"unimplementable on most of the targets" and that a callback contract "inverts
control on all of them", and concludes that non-blocking polling is the only
model that means the same thing on clj, cljs, and cljd. The argument is about
hosts, not about streams, so it applies with equal force to any interface an
interpreter publishes. The contract nevertheless binds only its own seven
operations. It assumes the layers above will inherit the model without being
told to.

**Evidence.** Content lookup, the one random-access service in the system, is
currently published in three shapes:

- **Waiting.** `dao.jing/get` is `(get handle address not-found) -> value`, and
  `materialize!` returns an address "only after the backend reports success".
  Over a local store this is one in-memory or file read. Over a remote store it
  must wait, which `dao.jing.md` records honestly: `connect-content!` is "a
  blocking driver as host policy… a JVM thread polls and sleeps", and both
  halves of `dao.jing.remote` are JVM-only. Nine call sites depend on this
  shape: three in `dao.space.index` (index walks and manifest reads), four in
  `dao.data.btree.storage`, two in `yin.vm.content`.
- **Callback.** `dao.data.btree.storage/hydrate-async` takes `on-ok` and
  `on-err` or returns a Promise / Future / CompletableFuture, tracking
  completion in atoms, over an async content handle
  (`:get-content-async-fn`). This is the second of the three IO models, the
  one this document rejects by name, and it sits one layer above the contract.
- **Stepped.** `dao.jing.remote.step` is "a pure function over explicit data…
  no socket, atom, promise, callback, or scheduler": `request-get`, `step`,
  `abandon` over `dao.stream.rpc`. This is the third model, and the only one of
  the three shapes that is the same on every host.

`dao.jing.md` already lists the consequences as deferred items (*The content
write path as an effect stream*, *Async hydration*). What is undecided is not
whether the problem exists but which shape is the interface.

**What breaks.** Any interpreter that must reach content it does not hold
locally, on a host that cannot wait: a browser or Dart agent querying a remote
`dao.space` index; a VM loading code by address from another node
(`yin.vm.content`); a game client fetching meshes or textures by address.
Today each has two options. It can hydrate the whole reachable graph before
reading, which turns a selective index read into a full download. Or it can
adopt the callback shape, which reintroduces inverted control into every
caller above it, the viral cost *Why this model and not the other two*
describes.

The general form matters more than this instance. Every future service that
answers a question (a lookup, a query against a remote peer, a lease grant, a
name resolution) faces the same three shapes. Without a rule each will choose
by local convenience, and the convenient choice on the JVM is the one that
does not port.

**Proposed resolution.** One rule, stated here because this is where the
argument for it lives, and one consequence, stated in `dao.jing.md`:

1. Add to *Composition*:

   > The IO model is inherited. An interpreter's portable interface is
   > expressed the way this contract's is: a step that returns what is true
   > now, including "not yet", over streams the caller supplied. A waiting
   > call or a callback may wrap that step as **host policy**, named as such
   > and confined to the hosts that can afford it, exactly as
   > `connect-content!` wraps the stepped client. It is never the interface
   > another interpreter is written against.

2. In `dao.jing.md`, name the stepped request/response pair as the
   agent-facing interface to content, and reclassify the synchronous handle
   (`:get-content-fn`) as what it already is in practice: the store
   interpreter's boundary adapter onto its own medium, plus a cache readers may
   consult. The handle map does not change. What changes is which of the three
   shapes new code is allowed to depend on.

Two costs follow and should be accepted with open eyes. The tree walks in
`dao.space.index` and `dao.data.btree.storage` become steppable: fetch, receive
"not yet", return state, resume when the response is observed. Inside `yin.vm`
this is the existing park and wait-set path; in library code it is the shape
`forward-step` and `rpc/poll!` already have. And `hydrate-async` is either
rewritten as a step or explicitly labelled host policy. The hydration cache
itself survives unchanged: prefetching a graph so that a walk never misses is a
legitimate optimization of a stepped interface, not an alternative to one.

**Cost if deferred.** The nine call sites grow. Every new consumer of content
written on the JVM is written against the waiting shape, and each one is a
separate port when the first browser or Dart agent needs to read a remote
index.

### Recorded, not proposed

These came out of the same review. Each is reachable by addition under the
rules the contract already has, so none needs a decision now. They are listed
so that a later plan does not rediscover them as surprises.

- **A writer does not learn where its value landed.** `append!`'s `ok` carries
  no cursor. Read-your-writes and causal references between streams arrive as
  an optional `:dao.stream/cursor` key on `ok`. Conditional append ("only if
  the tail is still here") would be an eighth operation; the design does not
  need it because it assumes **one writer per logical stream**, which
  `dao.space` practices and this document should say once in *Concurrency*.
- **History is not verifiable.** Identity is an opaque value nothing
  authenticates, and append-only is the transport's promise, not something a
  reader can check. Self-certifying identities and signed or hash-chained
  elements live in the payload, above the stream. The obligation on this layer
  is only the one it already meets: identity is compared structurally and never
  parsed.
- **Readiness.** Polling is O(n) in streams observed (*What it costs*). The
  extension is reserved, and `dao.stream.waitset.implementation-plan.md` pays
  the cost in the runtime without it.
- **Granularity.** An element is a whole value; there is no byte offset within
  one and no chunked element. Large media travel as content addresses in the
  payload, resolved through `dao.jing`.
