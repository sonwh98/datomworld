# DaoStream Design

## Overview

DaoStream has two explicit parts:

1. A **stream descriptor**, which is a first-class value.
2. A **StreamHost**, which takes that descriptor and constructs or
   attaches to a concrete transport.

The descriptor is canonical. It is serializable, can be stored, can be sent on
other streams, and does not depend on any one runtime backend. The StreamHost
is operational. It realizes the descriptor as an in-memory transport, a
WebSocket transport, or another transport that preserves the same stream
semantics.

Because the descriptor is a value, a stream can be sent on a stream. Any Yin VM
host that receives the descriptor can realize it through its local StreamHost.

DaoStream can transport arbitrary bytes or other values. The most common
Datom.world use case is transport of datoms. Most things in Datom.world are
streams of datoms, and DaoStream is the transport substrate that makes the
principle "Everything is a stream" operational.

The implication of that principle is that interaction in Datom.world is
stream-mediated. Interaction with DaoDB is via streams. FFI is also built on
DaoStream rather than treated as a separate imperative escape hatch.

This applies even to meta-runtime services. JIT and GC can be driven by datoms
emitted on streams. Any StreamHost that observes the relevant streams can
compile, collect, or otherwise optimize in response to those datoms.

## Core Model

### Stream Descriptor

A stream exists as a descriptor value.

Every stream descriptor has three top-level sections:

- `:stream/id` - required canonical stream identity or address
- `:transport` - required transport realization request
- `:shibi` - optional Shibi trust metadata for untrusted nodes

```clojure
{:type :daostream/descriptor
 :stream/id ...
 :transport {:type :ringbuffer
             :mode :create
             :capacity 1024
             :retention {:type :keep-all}
             :backpressure {:type :park-writer}}
 :shibi {...}}
```

Field semantics:

- `:stream/id` names the stream independently of any one runtime transport
- `:transport` is StreamHost-facing pure data that describes how to create or
  attach to a transport
- `:shibi` carries optional authority metadata and is orthogonal to transport
  identity

All `:transport` maps must contain:

- `:type` - transport kind such as `:ringbuffer` or `:websocket`
- `:mode` - `:create` or `:attach`

Example transport maps:

```clojure
{:type :ringbuffer
 :mode :create
 :capacity 1024
 :retention {:type :keep-all}
 :backpressure {:type :park-writer}}
```

```clojure
{:type :websocket
 :mode :attach
 :url "wss://example.test/stream"
 :stream/key ...
 :codec :transit}
```

The descriptor must remain plain serializable data. It must not contain runtime
transport state such as buffer contents, cursor positions, blocked waiters,
socket handles, or continuation state.

### StreamHost

A stream exists as a descriptor value. To become operational, it must be
realized by a **StreamHost**. The StreamHost is the authority or environment that
provides the namespace and realizes descriptors into transports.

The `IDaoStreamHost` protocol defines the primary entry point for the system:

```clojure
(defprotocol IDaoStreamHost
  (open! [this descriptor]
    "Realize a descriptor into an operational transport resource."))
```

The name **StreamHost** is chosen over **Factory** to emphasize the relationship between the system and the resource: a **Factory** implies a "create and forget" relationship, whereas a **StreamHost** suggests an authoritative, ongoing environment that manages the resource's lifecycle, namespace, and routing.

### Transport

A transport is the operational realization of a stream. It is runtime state
that satisfies one or more stream protocols. A transport is not the canonical
identity; it is the resource produced by `open!`.

## Stream Protocols

DaoStream is not one monolithic protocol. A transport implements the specific
protocol boundaries it supports.

### 1. Reader Protocol (`IDaoStreamReader`)
Required for readable streams. Supports non-destructive traversal.
- `(next [this cursor])` -> Returns `{:ok val :cursor next-cursor}`, `:daostream/blocked`, or `:daostream/end`.
- `(seek [this cursor pos])` -> Returns a new cursor at the absolute position.

### 2. Writer Protocol (`IDaoStreamWriter`)
Required for writable streams. Supports append-only writes.
- `(put! [this val])` -> Returns `:ok` or `:daostream/full`.

### 3. Bounding Protocol (`IDaoStreamBound`)
Required for closeable streams. Manages the transition to a finite state.
- `(close! [this])` -> Stops future appends; existing data remains readable.
- `(closed? [this])` -> Returns true if the stream is closed.

### 4. Clojure Integration (`Seqable`)
All transports must implement `clojure.lang.Seqable`.
- `(seq [this])` -> Returns a snapshot-based lazy sequence of the current
  retained prefix. The sequence terminates at the length of the stream at the
  moment `seq` was called.
- Live growth is handled via `next` and cursors, not via `Seqable`.

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
StreamHost, not to `Seqable`.

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
position, sentinel value) is a StreamHost policy decision. Different StreamHosts may
handle gaps differently for the same transport type. The core stream model does
not prescribe gap behavior.

## Backpressure

Backpressure is transport behavior, not stream ontology.

An unbounded transport has no backpressure by definition. A bounded transport
may need to slow writers, reject writes, or reclaim space according to its local
policy.

The primary DaoStream model is writer parking:

- the writer attempts an append
- the bounded transport reports full
- the StreamHost parks the writer continuation until space exists or the
  transport's policy changes

The descriptor carries boundedness and backpressure policy under `:transport`,
but exact enforcement still lives in the StreamHost and transport. Different
StreamHosts may realize the same descriptor with different mechanics while
preserving the same high-level semantics.

Backpressure does not require destructive reads in the canonical model. If a
transport later regains space through retention, eviction, compaction, or
another policy, that is transport-specific behavior.

## Channel Mobility

Streams can be sent on streams because a stream exists as a descriptor value.
That descriptor can travel unchanged and be realized on any Yin VM host that
implements the corresponding StreamHost.

If stream A carries the descriptor for stream B, the receiver can read that
descriptor from A, then hand it to a StreamHost to construct or attach
to a transport for B.

No special ontology is required here:

- stream descriptor is data
- streams carry data
- therefore streams can carry stream descriptors
- a Yin VM host can realize a received stream descriptor locally through its StreamHost

This is the channel-mobility property.

## Trust and Shibi

A descriptor may carry Shibi capability tokens under `:shibi` when a stream
must be interpreted on an untrusted node.

The token is part of the descriptor's authorization context, not part of the
transport's identity. The StreamHost is responsible for deciding whether the
descriptor is allowed to resolve into an operational transport in the current
trust context.

Design constraints:

- possession of a descriptor alone is not automatically sufficient authority
- descriptor-carried Shibi tokens may be embedded or referenced
- validation, confinement, and revocation are StreamHost concerns
- transport mechanics do not define trust semantics

## VM Integration

The VM interacts with streams through effect descriptors and StreamHosts.
Stream blocking and resumption are not core stream semantics. They are runtime
behavior in the StreamHost and scheduler.

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
StreamHost layer. It does not change the core descriptor model.

## Stream and Continuation Unification

Open-stream runtime behavior is already unified with continuations in the
current code.

The useful formulation is not "a stream is only a continuation". The better
formulation is:

- a bounded stream is stable data
- an open stream transport is stable data plus suspended demand

In current implementations, blocked readers and writers live in the VM
scheduler's wait-set rather than inside the transport value itself. That still
constitutes unification at the StreamHost layer:

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
scheduler into transport-local StreamHost state, indexed by cursor position or
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

The current code does not fully match this design yet.

- `src/cljc/dao/stream.cljc` currently conflates descriptor and transport in a
  stateful `IStream` implementation.
- `take!` exists in the current code, but destructive consumption is not part of
  the canonical DaoStream model.
- `dao.stream/->seq` is currently lazy and may observe appends that happen
  during later realization before it terminates at `:blocked`, so it does not
  yet match the target snapshot semantics for `Seqable` on open streams.
- `src/cljc/dao/stream/ws.cljc` is a concrete transport realization.
- `src/cljc/dao/stream/link.cljc` is a replication/link protocol over stream
  transports.
- `src/cljc/yin/stream.cljc` bridges stream operations into the VM effect layer.

The design target is descriptor-first, StreamHost-driven, and cursor-based.

## Deferred

- additional transport schemas beyond the initial descriptor contract
- typed streams
- retention and eviction policies
- gap signaling for bounded-retention transports
- explicit transport protocol names in code
- stream-local continuation waiters for open-stream StreamHosts
- DaoDB-specific stream consumption contracts
