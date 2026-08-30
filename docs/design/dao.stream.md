# DaoStream Design

Status: agreed design target. This document is the DaoStream contract. It does
not redesign Yin.VM. Yin.VM is a downstream consumer and will be addressed only
after this contract is implemented and tested.

## Purpose

DaoStream is the passive substrate for IO in datom.world. A stream carries
values. It does not decide what those values mean, which interpreter observes
them, or when an interpreter runs.

The design separates four concepts:

1. A **creation specification** is data for creating a new logical stream.
2. A **handle** is a host-local operational stream value — the
   implementation of the `dao.stream` protocols.
3. A **portable descriptor** is data used to attach to an existing stream.
4. A **cursor** is immutable interpreter-owned observation state for one
   logical stream.

Creation is not attachment. A descriptor is not a creation specification. A
handle is not portable identity. A cursor is not handle-owned state.

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

Every clause below derives from these invariants. A proposed addition that
cannot be derived from one of them does not belong in this contract.

## Result Convention

Every DaoStream operation returns data.

- Each operation has a closed set of outcomes. An outcome is a map whose
  `:dao.stream/outcome` key names the outcome with a qualified keyword. Every
  operation returns such a map — including projections like `descriptor` and
  `cursor`, whose success map carries the projected value.
- Result maps are **open**: the contract fixes which keys are required per
  outcome, and a consumer must ignore keys it does not understand. This is
  what allows future extensions (for example readiness notification) to be
  added without breaking existing consumers.
- Exceptions are reserved for defects in the host's own assembly of
  DaoStream, detected before any operation runs. Everything observable at an
  operation returns data: closed, full, blocked, end, gap, not found,
  malformed input, and transport defects are all outcomes, never exceptions
  and never callbacks.
- All qualified keywords in results live under `:dao.stream/…`. (The legacy
  `:daostream/gap` spelling is retired.)

## Creation and Attachment

Creation brings a new logical stream into existence. Attachment joins an
existing one. They are separate operations with separate inputs.

- `create!` consumes a creation specification and returns a handle on a
  new logical stream. The handle's portable identity is available as a
  descriptor via `descriptor`.
- `attach!` consumes a portable descriptor and returns a handle on the
  **same** logical stream the descriptor names. Attaching never creates.
- A creation specification says how to build a stream. A descriptor only
  names one — never executable code, never a construction program, never a
  request to load a plugin.

Streams are values that can be sent through streams — up to a serialization
boundary. An in-memory stream holds references, so a handle can ride it
intact, and an interpreter that receives a handle simply uses it —
every operation takes a handle, and attaching to what you already hold
is meaningless. But a handle does not survive encoding, and any
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
reference. Its exact definition is not designed yet; these properties are
settled, and the key set that realizes them is TBD:

- It carries `:dao.stream/type` — the transport, as above — as its dispatch
  key.
- It names exactly one logical stream, and that identity is stable: the same
  descriptor attaches to the same stream every time.
- It is self-contained. If the stream is remote, it contains whatever is
  needed to connect — perhaps an IP address and port. The entry data is
  transport-owned; a handler that cannot make sense of it returns
  `:dao.stream/invalid-descriptor`.
- Like an IOR, it may carry authorization: an optional `:dao.stream/shibi`
  key holding Shibi capability tokens that gate whether an interpreter may
  attach. Shibi is not yet designed or built; until it is, the key is
  reserved, no attachment is gated, and the `:dao.stream/denied` outcome
  reserved in the `attach!` table below is unreachable.

For the time being, the operative guarantee is the round trip: `descriptor`
on a live handle returns `:dao.stream/ok` with `:dao.stream/descriptor`,
a value that can be sent on a stream and reconstructed on the other end,
where `attach!` on the reconstructed value reaches the stream it names —
provided the receiving host can reach what it names at all (see Creation and
Attachment on reachability).

Both envelopes are plain data through and through: every value must survive
the host serialization codec structurally unchanged, so equality is
structural, and no key holds a function, a host object, a live handle, or
identity smuggled in metadata. Like every DaoStream map, envelopes are open —
a consumer ignores qualified keys it does not understand.

`create!` outcomes:

| Outcome                       | Meaning                                       | Required keys        |
|-------------------------------|-----------------------------------------------|----------------------|
| `:dao.stream/ok`              | A new logical stream exists.                  | `:dao.stream/handle` |
| `:dao.stream/invalid-spec`    | The creation specification is malformed.      | —                    |
| `:dao.stream/not-found`       | No transport here matches `:dao.stream/type`. | —                    |
| `:dao.stream/transport-error` | The handler failed.                           | —                    |

`attach!` outcomes:

| Outcome                          | Meaning                                                                             | Required keys        |
|----------------------------------|-------------------------------------------------------------------------------------|----------------------|
| `:dao.stream/ok`                 | Attached to the existing logical stream.                                            | `:dao.stream/handle` |
| `:dao.stream/invalid-descriptor` | The descriptor is malformed.                                                        | —                    |
| `:dao.stream/not-found`          | The descriptor names no stream here, or no transport matches its type.              | —                    |
| `:dao.stream/denied`             | Attachment refused by capability gating (reserved; unreachable until Shibi exists). | —                    |
| `:dao.stream/transport-error`    | The handler failed.                                                                 | —                    |

### Host Dispatch

When a creation specification or portable descriptor arrives dynamically —
for example off a stream — the host looks at it and dispatches on
`:dao.stream/type`. The type resolves to a map that has the transport's
`:dao.stream/create` and `:dao.stream/attach` implementations:

```clojure
{:dao.stream/ringbuffer {:dao.stream/create ringbuffer/create!
                         :dao.stream/attach ringbuffer/attach!}
 :dao.stream/ws         {:dao.stream/create ws/create!
                         :dao.stream/attach ws/attach!}}
```

This table is ordinary data owned by the host, and the dispatch is an
ordinary map lookup done by the host — DaoStream neither performs nor
standardizes it. A host that cannot dispatch on `:dao.stream/type` returns
`:dao.stream/not-found` — never creation or discovery.

Code that knows its transport statically calls the transport's functions
directly; no dispatch is involved. There is no ambient namespace-global
registry and no namespace-load registration side effect. (The legacy `open!`
multimethod and `defopen` macro are retired.)

## Surfaces

A handle implements the subset of the public surface its transport can
honor — **reader** (`cursor`, `next`), **writer** (`append!`), **closable**
(`close!`) — and the transport declares which. The Cursors, Reading, and
Retention and Gaps sections govern handles with a reader surface only.

The reader surface is a promise: the handle presents its elements as
one positioned, append-only, retained sequence that cursors can observe and
re-observe. A transport whose medium retains nothing cannot make that
promise and has no reader surface: its inbound events are deposited by the
host's adapter onto the stream the host composition wired for that boundary
— where the reading model applies in full. The log lives beside such a
transport, composed explicitly by the host, never hidden inside the
transport. A deposit destination must admit every valid boundary event per
its declared retention; wiring one that can refuse is a host assembly
defect. (`dao.stream.ws.md` specifies one such transport and the admission
rule in detail.)

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
  readable.
- Every valid cursor comes from the stream: minted by `cursor`, received as
  a successor from `next`, or recovered from a gap outcome. Consumers never
  construct cursor internals or fabricate positions — there is no `seek`. A
  cursor's representation belongs to the transport.
- A cursor binds to the logical stream, not to a handle: any handle on the
  same logical stream accepts it, so a cursor handed to another interpreter
  works with that interpreter's own handle. Whether a cursor survives
  serialization to another host is transport-owned and TBD, like the
  descriptor envelope.
- A cursor carries enough identity to detect misuse — the mismatch outcomes
  in the Reading table depend on it.
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

Reading never mutates the stream. Two readers with cursors at the same
position observe the same retained value; neither can starve or affect the
other. There is no destructive read anywhere in the contract — no drain, no
take. An operation that removes an element as it reads would make one
reader's progress every other reader's data loss.

## Writing

`append!` takes a handle and a value and returns an outcome:

| Outcome                     | Meaning                                                                                                                     | Required keys |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------|---------------|
| `:dao.stream/ok`            | The value is accepted at the next position in the stream's sequence. Implies neither local readability nor remote delivery. | —             |
| `:dao.stream/full`          | The transport cannot accept the value now; nothing was appended.                                                            | —             |
| `:dao.stream/invalid-value` | The transport cannot carry this value (for example, it cannot be encoded); nothing was appended.                            | —             |
| `:dao.stream/closed`        | What this handle is on is closed to new appends; the value was not appended.                                                | —             |

The writer decides what to do about full or closed; DaoStream does not retry,
buffer, or notify on its behalf.

## Close

`close!` takes a handle and returns a map whose outcome is
`:dao.stream/ok`. It is idempotent: closing an already-closed scope is also
`:dao.stream/ok`.

`close!` closes **what the handle is on**. A handle that owns a stream — its
creator, or its server-side host — closes the logical stream. An attached
handle is on an attachment: closing it ends that attachment only — the
stream persists, its descriptor remains valid, and `attach!` may rejoin
later. Which scope a transport's handles carry is part of its declared
nature.

Outcome meanings are handle-relative throughout: `:dao.stream/closed` and
`:dao.stream/end` speak of what *this handle* is on. After an attachment
closes, the handle's `append!` answers `closed`; `descriptor` still answers
`ok` — identity outlives any attachment; a reader surface, where the
handle has one, answers `end` from `next` once observation through this
handle is exhausted, and `closed` from `cursor` — no new cursors are
minted through a closed attachment. Cursors bind to the logical stream,
not to a handle, so a kept cursor resumes through a newly attached handle.

The bullets below describe closing the logical stream.

Close changes future availability only:

- Subsequent `append!` returns `:dao.stream/closed`.
- A reader that exhausts retained history receives `:dao.stream/end` instead
  of `:dao.stream/blocked`.
- Retained history remains readable. Close erases nothing; eviction is a
  retention concern, not a lifecycle one.

There is no `closed?` predicate in the public surface. Operation results are
authoritative; a predicate answer is stale the moment it is returned.

## Concurrency

Each logical stream is one append-only sequence: a successfully appended
value occupies exactly one definite position in that sequence, and every
operation's outcome reflects the sequence at one moment. DaoStream
guarantees nothing further about concurrent operations.

This follows from the second axiom of datom.world — data is syntax;
semantics emerge through interpretation. The sequence is the one truth; each
outcome is one perspective on it at one moment. A reader that observes
`:dao.stream/blocked` and then a value has not caught the stream in a
contradiction — it made two observations from its own cursor, and no global
view exists to contradict. Whether an append landed before a close is
answered by the sequence itself: the value has a position or it does not.
What an ordering between writers *means*, and any coordination among them —
a merge interpreter, a one-log-per-writer discipline — is interpretation,
and interpretation happens above the stream.

## Retention and Gaps

Retention and overflow behavior are transport-owned, declared by the
creation specification. A ring buffer retains a bounded window and evicts
the oldest; a file or append-only log retains everything; a network send
buffer refuses transiently. Which outcomes a given stream can produce —
`full` on append, `gap` on read — and whether its `full` is transient or
permanent follow from the transport's declared nature. DaoStream names the
outcomes and standardizes no policy.

When bounded retention evicts a value, a cursor still pointing at it gets the
gap outcome from the Reading table. The stream reports honestly and decides
nothing: an interpreter replaying a log may treat a gap as fatal; a live
telemetry viewer may adopt the returned cursor and continue. Silently
skipping evicted history is forbidden — a reader must be able to know it
missed values.

## Composition

DaoStream defines only streams, cursors, and the operations above. Anything
that transforms one stream into another is an interpreter — it reads via its
own cursor and appends to an ordinary output stream — and that lives entirely
outside this contract, needing no support from it. As in a Unix pipeline, the
pipe is dumb; the transforming happens in the processes on either end.

## Readiness and Polling

This contract has no readiness or waiter extension. A reader that receives
`:dao.stream/blocked` retries `next` when it chooses; retry cadence is the
concern of the interpreter or the runtime driving it, not of the stream.

A future readiness extension would be additive: an optional protocol on
transports that support it, plus optional keys in operation results (for
example a wake token list on `append!`), which the open-map rule already
permits. Nothing in this contract needs to change for that; no consumer of
this contract may depend on it existing.

## Public Surface

| Operation    | Consumes               | Yields             |
|--------------|------------------------|--------------------|
| `create!`    | creation specification | handle outcome     |
| `attach!`    | portable descriptor    | handle outcome     |
| `descriptor` | handle                 | descriptor outcome |
| `cursor`     | handle, anchor         | cursor outcome     |
| `next`       | handle, cursor         | read outcome       |
| `append!`    | handle, value          | write outcome      |
| `close!`     | handle                 | close outcome      |

A handle implements the subset of these operations its transport
declares (see Surfaces).

Explicitly absent, by derivation from the invariants: `open!` and `defopen`
(ambient registry), `closed?` (stale predicate), `drain-one!` and any
destructive read (readers advance cursors, not the stream), `seek`
(every valid cursor comes from the stream; anchored minting and kept
cursors cover repositioning), `take!!` (blocking), waiter registration
(no readiness extension), and throwing conveniences such as `strict-vec`
(operational outcomes are data).
