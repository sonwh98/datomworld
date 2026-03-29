# DaoStream Design

## Overview

DaoStream has two explicit parts:

1. A **stream descriptor**, which is a first-class value.
2. An **operational realization (`open!`)**, which takes that descriptor and
   constructs or attaches to a concrete transport.

The descriptor is canonical. It is serializable, can be stored, can be sent on
other streams, and does not depend on any one runtime backend. The `open!`
multimethod is operational. It realizes the descriptor as an in-memory transport,
a WebSocket transport, or another transport that preserves the same stream
semantics.

Because the descriptor is a value, a stream can be sent on a stream. Any Yin VM
host that receives the descriptor can realize it through its local `open!`
implementation.

DaoStream can transport arbitrary bytes or other values. The most common
Datom.world use case is transport of datoms. Most things in Datom.world are
streams of datoms, and DaoStream is the transport substrate that makes the
principle "Everything is a stream" operational.

The implication of that principle is that interaction in Datom.world is
stream-mediated. Interaction with DaoDB is via streams. FFI is also built on
DaoStream rather than treated as a separate imperative escape hatch.

This applies even to meta-runtime services. JIT and GC can be driven by datoms
emitted on streams. Any observer that monitors the relevant streams can
compile, collect, or otherwise optimize in response to those datoms.

## Core Model

### Stream Descriptor

A stream exists as a descriptor value.

**Current implementation** uses a simplified form:

```clojure
{:transport {:type :ringbuffer
             :capacity 1024}  ;; nil for unbounded
 :closed false}                ;; snapshot at serialization time
```

**Design target** includes three top-level sections:

```clojure
{:stream/id ...                ;; canonical stream identity
 :transport {:type :ringbuffer
             :mode :create
             :capacity 1024
             :retention {:type :keep-all}
             :backpressure {:type :park-writer}}
 :shibi {...}}                 ;; optional trust metadata
```

Field semantics:

- `:stream/id` names the stream independently of any one runtime transport
- `:transport` is pure data that describes how to create or attach to a transport
  - `:type` - transport kind such as `:ringbuffer` or `:websocket`
  - `:mode` - `:create` or `:attach` (target, not yet implemented)
  - `:capacity` - `nil` for unbounded, int for bounded (ringbuffer only)
  - `:retention` - policy for memory reclamation (target, not yet implemented)
  - `:backpressure` - how to handle full transport (target, not yet implemented)
- `:shibi` carries optional authority metadata and is orthogonal to transport
  identity (target, not yet implemented)

The descriptor must remain plain serializable data. It must not contain runtime
transport state such as buffer contents, cursor positions, blocked waiters,
socket handles, or continuation state.

### Stream Realization (open!)

A stream exists as a descriptor value. To become operational, it must be
realized into an operational transport. The primary entry point is the `open!`
multi-method, which dispatches on transport type:

```clojure
(defmulti open!
  "Realize a descriptor into an operational IStream transport."
  (fn [descriptor] (get-in descriptor [:transport :type])))
```

The dispatcher extracts `:type` from the `:transport` section of the descriptor
(e.g., `:ringbuffer`, `:websocket`). Each transport implementation provides
a `defmethod` for its type.

This multi-method approach decouples descriptor schema from implementation.
New transports can be added without modifying the core `open!` entry point.
Different runtimes may implement `open!` differently while preserving the same
descriptor semantics.

### Transport

A transport is the operational realization of a stream. It is runtime state
that satisfies one or more stream protocols. A transport is not the canonical
identity; it is the resource produced by `open!`.

## Stream Protocols

### Current: IStream Protocol (monolithic)

All transports implement `IStream` with these operations:

- `(put! [this val])` → `:ok` or `:full`; throws if closed.
- `(take! [this])` → `{:ok val}`, `:empty` (open), or `:end` (closed).
  - **Note**: Destructive consumption; not part of canonical model (target: remove).
- `(next [this cursor])` → `{:ok val :cursor cursor'}`, `:blocked`, `:end`, or `:daostream/gap`.
  - Non-destructive cursor-based reading. Cursor advanced without consuming the value.
- `(length [this])` → Count of available (untaken) values.
- `(close! [this])` → Marks stream closed; existing data remains readable.
- `(closed? [this])` → Boolean.

### Target: Separate Protocol Boundaries

Design goal is to split `IStream` into protocol boundaries:

- **Reader Protocol** (`IDaoStreamReader`):
  - `(next [this cursor])` → non-destructive traversal.
  - `(seek [this cursor pos])` → position-based seeking (target, not yet implemented).

- **Writer Protocol** (`IDaoStreamWriter`):
  - `(put! [this val])` → append-only writes.

- **Bounding Protocol** (`IDaoStreamBound`):
  - `(close! [this])` → stop future appends.
  - `(closed? [this])` → query closed status.

- **Clojure Integration** (`Seqable`):
  - `(seq [this])` → snapshot-based lazy sequence at call time.
  - Live growth handled via cursors, not `Seqable`.

## Cursor-Based Reading

The canonical read model is cursor-based and non-destructive.

Advancing to the next element advances a cursor, not the stream.

Properties:

- Cursors are values.
- Multiple cursors on the same stream advance independently.
- Reading does not consume values from the stream.
- Prior values remain part of the stream while they are retained by the
  transport.

Illustrative cursor shape:

```clojure
{:stream <descriptor-or-stream-ref>
 :position n}
```

The exact representation is not fixed yet. The invariant is that the cursor
holds read position, and advancing that position is the primitive notion of
"next".

`Seqable` is consistent with cursor semantics, but it is not the live-follow
interface. Sequence traversal observes the stream in append order without
consuming it.

For a bounded stream, `seq` observes the retained stream contents in append
order.

For an open stream, `seq` returns a snapshot of the retained prefix visible at
the moment `seq` is created. That returned seq does not grow as later appends
arrive. To observe later appends, the caller must create a new seq or use
cursors directly.

This keeps `Seqable` in the value world of Clojure core functions. Hidden
blocking and live-growth semantics belong to cursor-based reading and the
`open!` implementation, not to `Seqable`.

## Close, Bounding, and Gaps

Closing or bounding a stream stops future appends. It does not erase the
existing stream prefix.

Core close behavior:

- values already appended remain observable
- reads at the end of a closed stream produce end-of-stream
- close changes future availability, not prior facts

A gap is a discontinuity: a cursor's position refers to a value that the
transport no longer retains. Gaps are not caused by reading. A gap exists when a
transport does not retain the full historical prefix. If a cursor falls behind the retention
boundary, it observes a gap. What happens next (error, skip to earliest retained
position, sentinel value) is a transport-specific policy decision. Different
realizations may handle gaps differently for the same transport type. The core
stream model does not prescribe gap behavior.

## Backpressure

Backpressure is transport behavior, not stream ontology.

An unbounded transport has no backpressure by definition. A bounded transport
may need to slow writers, reject writes, or reclaim space according to its local
policy.

The primary DaoStream model is writer parking:

- the writer attempts an append
- the bounded transport reports full
- the realization layer (`open!`) parks the writer continuation until space exists or the
  transport's policy changes

The descriptor carries boundedness and backpressure policy under `:transport`,
but exact enforcement still lives in the realization and transport. Different
implementations may realize the same descriptor with different mechanics while
preserving the same high-level semantics.

Backpressure does not require destructive reads in the canonical model. If a
transport later regains space through retention, eviction, compaction, or
another policy, that is transport-specific behavior.

## Channel Mobility

Streams can be sent on streams because a stream exists as a descriptor value.
That descriptor can travel unchanged and be realized on any Yin VM host that
implements the corresponding `open!` method.

If stream A carries the descriptor for stream B, the receiver can read that
descriptor from A, then hand it to `open!` to construct or attach
to a transport for B.

No special ontology is required here:

- stream descriptor is data
- streams carry data
- therefore streams can carry stream descriptors
- a Yin VM host can realize a received stream descriptor locally through `open!`

This is the channel-mobility property.

## Trust and Shibi

A descriptor may carry Shibi capability tokens under `:shibi` when a stream
must be interpreted on an untrusted node.

The token is part of the descriptor's authorization context, not part of the
transport's identity. The realization layer (`open!`) is responsible for
deciding whether the descriptor is allowed to resolve into an operational
transport in the current trust context.

Design constraints:

- possession of a descriptor alone is not automatically sufficient authority
- descriptor-carried Shibi tokens may be embedded or referenced
- validation, confinement, and revocation are concerns of the realization layer
- transport mechanics do not define trust semantics

## VM Integration

The VM interacts with streams through effect descriptors and `open!` realizations.
Stream blocking and resumption are not core stream semantics. They are runtime
behavior in the transport and scheduler.

This includes interaction with DaoDB, FFI, host I/O, and meta-runtime services
such as JIT and GC. In Datom.world, these interactions are modeled through
DaoStream transports rather than through direct host-language function calls.
See `docs/design/ffi-design.md` for the FFI-specific protocol built on top of
DaoStream.

Current operational model:

- `next` on an open stream with no value available parks the current
  continuation
- `put` may resume readers that were waiting for future positions
- `close` resumes blocked readers with end-of-stream

This is already one form of stream and continuation unification at the
`open!` layer. It does not change the core descriptor model.

## RingBufferStream Implementation

The reference `RingBufferStream` transport is map-backed with memory reclamation:

```
state-atom: {:buffer {}       ;; map of absolute-index -> value
             :head 0          ;; absolute index of oldest available value
             :tail 0          ;; absolute index of next put! position
             :closed false}
```

**Memory reclamation**: `take!` removes the consumed value from `:buffer` via `dissoc`.
Absolute indexing (`head`, `tail`) enables:
- **Cursor advancement** without stored position state in the transport.
- **Gap detection** when a cursor falls behind the retention boundary: `pos < head` → `:daostream/gap`.
- **Capacity bounding** via `capacity` field: `put!` returns `:full` when `(- tail head) >= capacity`.

**Cursor semantics**:
- Cursor is a plain map: `{:position n}`.
- `next` at position `pos` returns:
  - `{:ok val :cursor {:position (inc pos)}}` if `head <= pos < tail`.
  - `:daostream/gap` if `pos < head` (value evicted).
  - `:end` if `pos >= tail` and stream is closed.
  - `:blocked` if `pos >= tail` and stream is open (live data pending).

**Lazy sequence** via `->seq`:
Walks the stream with a cursor starting at position 0. Terminates when `next` returns
`:blocked`, `:end`, or `:daostream/gap`. Lazy realization allows tailing open streams.

## Stream and Continuation Unification

Open-stream runtime behavior is already unified with continuations in the
current code.

The useful formulation is not "a stream is only a continuation". The better
formulation is:

- a bounded stream is stable data
- an open stream transport is stable data plus suspended demand

In current implementations, blocked readers and writers live in the VM
scheduler's wait-set rather than inside the transport value itself. That still
constitutes unification at the `open!` dispatch layer:

- `next` either returns a value or causes the current continuation to be parked
  on behalf of the stream read
- `put` appends and may make parked readers runnable again
- `close` resumes remaining parked readers with end-of-stream
- FFI uses the same pattern: park continuation, emit request on a stream, then
  resume the parked continuation when the request is observed and handled

The existing FFI implementation makes this connection especially clear. A parked
continuation is turned into a stream-level request carrying a resumption address
(`:parked-id`), and a stream observer resumes that continuation later. This is
not merely analogous to stream behavior. It is the same causal pattern.

What remains deferred is not the existence of unification, but the location of
the waiter state. A future refinement may move continuation waiters from the VM
scheduler into transport-local state, indexed by cursor position or
a similar read or write condition.

This unification should not collapse the descriptor into continuation state. The
descriptor remains the serializable identity of the stream.

## Relation to DaoDB

Streams are independent of DaoDB. DaoDB consumes streams.

In Datom.world, DaoStream is the general stream substrate and DaoDB is a datom
consumer over that substrate. DaoStream can carry arbitrary bytes or values,
but the dominant system use case is datom transport. That is why most streams
in Datom.world are streams of datoms.

An open stream is still a stream. A bounded stream is a stable value that DaoDB
can consume to build indexes or answer queries. Stream semantics do not depend
on those indexes.

## Current Implementation Status

**What's implemented:**
- `src/cljc/dao/stream.cljc` defines monolithic `IStream` protocol.
- `open!` multi-method dispatches on `(get-in descriptor [:transport :type])`.
  - `:ringbuffer` method creates `RingBufferStream` with capacity from descriptor.
- `RingBufferStream` reference implementation:
  - Map-backed storage with memory reclamation via `dissoc` in `take!`.
  - Absolute indexing (`head`, `tail`) enabling cursor-based reads and gap detection.
  - Bounded capacity with `:full` return on overflow.
  - Factory: `open!` with `:ringbuffer` transport type.
- Cursor-based `next` method returns `{:ok val :cursor cursor'}`, `:blocked`, `:end`, or `:daostream/gap`.
- Lazy `->seq` utility walks with cursor; terminates at `:blocked`, `:end`, or gap.
- Descriptor shape (current): `{:transport {:type :ringbuffer :capacity nil-or-int} :closed bool}`.

**What still needs work:**
- `take!` is destructive; not part of canonical model (target: remove or move to separate protocol).
- Full descriptor schema expansion:
  - `:stream/id` for canonical identity.
  - `:transport :mode` (`:create` vs `:attach`).
  - `:transport :retention` policy for eviction strategy.
  - `:transport :backpressure` policy (target: `:park-writer`).
  - `:shibi` trust metadata support.
- Protocol separation: split `IStream` into `IDaoStreamReader`, `IDaoStreamWriter`, `IDaoStreamBound`.
- Gap policy: current implementation returns `:daostream/gap` (behavior defined). Document policy for
  gap handling (error, skip to earliest, sentinel).
- Backpressure and writer parking: `open!` dispatch to transport-specific logic; currently `put!` returns `:full`.
- Seek operation: `(seek [this cursor pos])` for position-based jumping (target, not implemented).
- WebSocket transport alignment: `src/cljc/dao/stream/ws.cljc` needs review for protocol compliance.
- VM bridge: `src/cljc/yin/stream.cljc` integration with effect layer via descriptor-based `open!`.

The design target is descriptor-first, transport-agnostic via `open!` multi-method, cursor-based, and gap-aware.

## Deferred

- additional transport schemas beyond the initial descriptor contract
- typed streams
- retention and eviction policies
- gap signaling for bounded-retention transports
- explicit transport protocol names in code
- transport-local continuation waiters for open-stream realizations
- DaoDB-specific stream consumption contracts
