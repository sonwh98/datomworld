# DaoStream Design

## Overview

DaoStream is a **standalone streaming abstraction** for building message queues, event logs, request-response systems, and distributed data transport. It is runtime-agnostic and VM-independent.

DaoStream has two explicit parts:

1. A **stream descriptor**, which is a first-class value.
2. An **operational realization (`open!`)**, which takes that descriptor and
   constructs or attaches to a concrete transport.

The descriptor is canonical. It is serializable, can be stored, can be sent on
other streams, and does not depend on any one runtime backend. The `open!`
multimethod is operational. It realizes the descriptor as an in-memory ringbuffer,
a WebSocket, Kafka, a file, or any other transport that preserves the same stream
semantics.

Because the descriptor is a value, a stream can be sent on a stream. Any host,
agent, or runtime that receives the descriptor can realize it through its local
`open!` implementation.

### Use Cases

**Standalone (no VM required):**
- Message queues and job systems
- Event logs and event sourcing
- Request-response RPC patterns
- Log streaming and aggregation (like Kafka topics)
- Publish-subscribe systems

**Datom.world specific (optional integration):**
The most common Datom.world use case is transport of datoms. Most things in Datom.world are
streams of datoms, and DaoStream is the transport substrate that makes the
principle "Everything is a stream" operational. DaoDB consumes streams, FFI is built on
DaoStream, and meta-runtime services like JIT and GC can be driven by datoms emitted on streams.

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

### Current: Separate Protocol Boundaries

All transports implement three orthogonal protocols:

- **Reader Protocol** (`IDaoStreamReader`):
  - `(next [this cursor])` → `{:ok val :cursor cursor'}`, `:blocked`, `:end`, or `:daostream/gap`.
    Non-destructive, cursor-based traversal. Multiple cursors on same stream advance independently.
  - `(seek [this cursor pos])` → position-based seeking (target, not yet implemented).

- **Writer Protocol** (`IDaoStreamWriter`):
  - `(put! [this val])` → `{:result :ok, :woke [...]}` or `{:result :full}`; throws if closed.
    Append-only writes. Returns `{:result :ok, :woke [...]}` on success (`:woke` contains any woken reader-waiters). Returns `{:result :full}` if transport is capacity-bounded and full.

- **Bounding Protocol** (`IDaoStreamBound`):
  - `(close! [this])` → `{:woke [...]}` with any woken reader-waiters and writer-waiters; marks stream closed; existing data remains readable.
  - `(closed? [this])` → returns boolean closed status.

This split makes the canonical model explicit: reading, writing, and lifecycle are orthogonal concerns. A transport may implement all three (as RingBufferStream does), or implement only the protocols relevant to its role.

### Non-Protocol Utilities

Destructive consumption and stream metadata are available as utilities, not protocol methods:

- `(drain-one! [stream])` → `{:ok val}`, `:empty` (open), or `:end` (closed).
  Destructively consumes one value. **Not part of the canonical model** — use `next` with cursor-based reading for reliable traversal.
- `(count-available [stream])` → integer count of appended but unconsumed values.
  Transport-specific metadata query. Not part of the canonical model.
- `(->seq [stream])` → lazy Clojure sequence.
  Snapshot-based traversal at call time. Live growth handled via cursors, not sequences.

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
That descriptor can travel unchanged and be realized on any host or runtime
that implements the corresponding `open!` method.

If stream A carries the descriptor for stream B, the receiver can read that
descriptor from A, then hand it to `open!` to construct or attach
to a transport for B.

No special ontology is required here:

- stream descriptor is data
- streams carry data
- therefore streams can carry stream descriptors
- any host or runtime can realize a received stream descriptor locally through `open!`

This is the channel-mobility property.

## Standalone Usage Examples

DaoStream works as a pure message bus or event log without any VM, continuation, or scheduler.

### Simple Message Queue

```clojure
;; Create a queue
(def queue (dao.stream/open! {:transport {:type :ringbuffer :capacity 1000}}))

;; Producer: append messages
(dao.stream/put! queue {:id 1 :msg "hello"})
(dao.stream/put! queue {:id 2 :msg "world"})

;; Consumer: read from position 0 onwards
(let [pos {:position 0}]
  (loop [cursor pos, messages []]
    (let [result (dao.stream/next queue cursor)]
      (cond
        (map? result) (recur (:cursor result) (conj messages (:ok result)))
        (= result :blocked) (println "Waiting for more messages...")
        (= result :end) messages))))
;; → [{:id 1 :msg "hello"} {:id 2 :msg "world"}]
```

### Event Log / Event Sourcing

```clojure
;; Immutable event log with multiple readers
(def events (dao.stream/open! {:transport {:type :ringbuffer}}))

;; Append events
(dao.stream/put! events {:type :user-created :id 42 :name "Alice"})
(dao.stream/put! events {:type :user-updated :id 42 :email "alice@example.com"})

;; Reader 1: replays all events to build current state
(def reader-1 {:position 0})

;; Reader 2: joins late, starts from position 1
(def reader-2 {:position 1})

;; Both advance independently without consuming
(dao.stream/next events reader-1)  ;; reads position 0
(dao.stream/next events reader-2)  ;; reads position 1
```

### Request-Response (No Continuations)

```clojure
;; Separate streams for request and response
(def request-stream (dao.stream/open! {:transport {:type :ringbuffer}}))
(def response-stream (dao.stream/open! {:transport {:type :ringbuffer}}))

;; Client: emit request
(dao.stream/put! request-stream
  {:id "req-1" :op :multiply :args [6 7]})

;; Handler: reads requests, writes responses (runs in different thread/process)
(defn handler []
  (loop [cursor {:position 0}]
    (let [result (dao.stream/next request-stream cursor)]
      (when (map? result)
        (let [{:keys [id op args]} (:ok result)
              res (apply {:multiply *} (assoc {} :* (fn [a b] (* a b))) args)]
          (dao.stream/put! response-stream
            {:id id :value res})
          (recur (:cursor result)))))))

;; Client: wait for response
(loop [cursor {:position 0}]
  (let [result (dao.stream/next response-stream cursor)]
    (if (map? result)
      (:ok result)  ;; → {:id "req-1" :value 42}
      (Thread/sleep 10))))  ;; poll if not ready
```

**Key point:** No continuation parking, no scheduler, no VM. Just streams with cursors and plain polling loops.

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

## Stream and Suspended Demand

Open-stream runtime behavior can be unified with suspended work through
transport-local waiter registration.

The useful formulation is not "a stream is only a continuation". The better
formulation is:

- a bounded stream is stable data
- an open stream transport is stable data plus suspended demand

A stream itself does not know what "suspended demand" means. It only knows:
- `next` → `:blocked` (data not yet available)
- `put!` → `:full` (no room to append)
- `close!` → all readers/writers are done

A **runtime** can interpret these signals and choose how to represent suspended work:
- Continuations (Yin VM)
- Promises (JavaScript)
- Callbacks (event-driven)
- Thread parking (OS threads)
- Actor mailboxes (actor systems)

DaoStream places no constraints on this choice. The `IDaoStreamWaitable` protocol
is one optional optimization for runtimes that want transport-level waiter registration,
but it is not required. Runtimes can always fall back to polling.

## Optional: Runtime Integration Patterns

**This section applies only to runtimes that model suspended work explicitly (e.g., continuation-based VMs).
Standalone message queues and event logs do not use this.**

### Reader and Writer Parking (IDaoStreamWaitable)

A runtime that models blocked work explicitly can store wake entries in the
transport itself as an optimization, bypassing scheduler polling. Transports
that do not implement `IDaoStreamWaitable` still rely on the runtime's polling
fallback.

For waitable transports:

- **Reader parking** (`next` → `:blocked`):
  - Reader registers at transport via `IDaoStreamWaitable.register-reader-waiter!` indexed by cursor position.
  - When `put!` appends at position `p`, transport checks `(get reader-waiters p)` and returns woken readers in `:woke`.
  - The runtime immediately schedules the woken readers (no polling).

- **Writer parking** (`put!` → `:full`):
  - Writer registers at transport via `IDaoStreamWaitable.register-writer-waiter!` when capacity is exceeded.
  - When `drain-one!` frees space, transport atomically writes the waiting writer's datom and returns the woken writer in `:woke`.
  - If a reader is simultaneously waiting at the new position, both wake together.
  - The runtime immediately schedules the woken writers (no polling).

- **Close semantics**:
  - `close!` collects all registered readers and writers, returns them in `:woke` with `:value nil`.
  - Readers get end-of-stream signal; writers get close signal.

### Continuation Parking (Yin VM Example)

In Yin VM specifically:

- `next` on an open stream with no value available parks the current continuation
- `put!` may wake readers that were waiting for future positions
- `close!` wakes all blocked readers with end-of-stream

In Yin VM's model, open streams are viewed as continuations:
- A bounded stream is stable data
- An open stream is stable data plus suspended demand (via continuation mechanics)

The VM uses `IDaoStreamWaitable.register-reader-waiter!` to let the transport store wake entries,
bypassing scheduler polling. If a transport does not implement `IDaoStreamWaitable` (e.g.,
networked transports), the VM falls back to polling via `check-wait-set`.

See `docs/design/ffi-design.md` for how Yin VM uses DaoStream for FFI (function calls across stream boundaries).

### `dao.stream.apply` Pattern (Cross-Stream RPC)

`dao.stream.apply` follows the same pattern: suspended work emits a request on a stream, then later resumes when a response is observed.

**Full specification:** see `docs/design/daostream-apply-design.md` for the complete protocol definition and standalone usage examples.

In a runtime that uses continuations:
- park continuation (await)
- emit request on a stream
- observe matching response later
- resume the parked continuation

In another runtime, suspended work might be represented as:
- Promises (JavaScript)
- Callbacks (Node.js)
- Actor mailboxes (Akka)
- Thread parking (Java)

DaoStream does not prescribe which one.

### Correctness Property

This unification should not collapse the descriptor into continuation state. The
descriptor remains the serializable identity of the stream. Waiter state may
live either in a waitable transport or in a runtime scheduler fallback, but in
both cases it is ephemeral runtime state and excluded from serialization.

**Key point:** This is one optional integration pattern. DaoStream itself makes no assumptions
about how runtimes represent suspended work.

## Relation to DaoDB

Streams are independent of DaoDB. DaoDB consumes streams.

In Datom.world, DaoStream is the general stream substrate and DaoDB is a datom
consumer over that substrate. DaoStream can carry arbitrary bytes or values,
but the dominant system use case is datom transport. That is why most streams
in Datom.world are streams of datoms.

An open stream is still a stream. A bounded stream is a stable value that DaoDB
can consume to build indexes or answer queries. Stream semantics do not depend
on those indexes.

## Transport Examples

DaoStream's `open!` multi-method is transport-agnostic. New transports are added by registering a `defmethod` for a new `:type`.

### RingBufferStream (Implemented)

In-process, bounded queue with absolute indexing.

```clojure
{:transport {:type :ringbuffer
             :capacity 10000}}

;; Use case: low-latency message queue, event log within a single process
```

**Implementation notes:**
- Map-backed: `{:buffer {0 val1 1 val2} :head 0 :tail 2}`
- Cursor advancement: `:position` counter (immutable)
- Memory: reclaimed via `dissoc` in `drain-one!`
- Gap detection: `pos < head` → `:daostream/gap`

### WebSocketStream (Target)

Networked, bidirectional transport.

```clojure
{:transport {:type :websocket
             :mode :connect
             :url "ws://broker.example.com/stream"
             :capacity nil}}

;; Use case: distributed message queue, cross-process RPC (dao.stream.apply)
```

**Design notes:**
- Sends/receives serialized values over WebSocket
- No local buffering (transport is remote)
- Cursor semantics: each `next` call sends a query to remote
- Polling or callback-based wake mechanism (not yet `IDaoStreamWaitable`)

### Kafka Transport (Example)

Distributed event log transport.

```clojure
{:transport {:type :kafka
             :broker "localhost:9092"
             :topic "my-events"
             :consumer-group "my-app"
             :capacity nil}}

;; Use case: durable event sourcing, fan-out to multiple consumers
```

**Design notes:**
- Cursor maps to Kafka offset: `{:position <offset>}`
- `next` calls `consumer.poll(timeout)`
- `put!` calls `producer.send(key, value, partition)`
- Gaps: Kafka offset lag → gap detection
- Multiple cursors: multiple independent consumer instances

### File Transport (Example)

Append-only file with cursor as byte offset.

```clojure
{:transport {:type :file
             :path "/data/events.log"
             :format :edn}}

;; Use case: durable audit log, cheap persistence
```

**Design notes:**
- Descriptor is serializable; realization opens the file
- Cursor: byte offset into file
- `put!`: append and fsync
- `next`: seek + read
- Retention: size-based rotation (e.g., max 1GB per file)

### In-Process Pub-Sub (Example)

Single-writer, multiple-reader pattern.

```clojure
{:transport {:type :pubsub
             :channels ["user.created" "user.updated"]}}

;; Use case: within-process event dispatch
```

**Design notes:**
- Each cursor is a separate reader
- All readers see the same append position
- No persistence; snapshots are empty
- Wake mechanism: immediate callback or waiter registration

### Key Pattern: New Transports

Adding a new transport is orthogonal to DaoStream core:

```clojure
(defmethod open! :my-transport [descriptor]
  (let [{:keys [host port]} (:transport descriptor)]
    ;; Realize the descriptor into a concrete transport object
    ;; Implement IDaoStreamReader, IDaoStreamWriter, IDaoStreamBound
    (->MyTransport host port)))
```

DaoStream makes no assumptions about the transport's internals. The only contract is:
- `next` returns `{:ok val :cursor cursor'}`, `:blocked`, `:end`, or `:daostream/gap`
- `put!` returns `{:result :ok :woke [...]}` or `{:result :full}`
- `close!` returns `{:woke [...]}`

## Current Implementation Status

**What's implemented:**
- `src/cljc/dao/stream.cljc` defines three orthogonal protocols:
  - `IDaoStreamReader` with `next` for non-destructive cursor-based reading.
  - `IDaoStreamWriter` with `put!` for append-only writes.
  - `IDaoStreamBound` with `close!` and `closed?` for lifecycle.
- `open!` multi-method dispatches on `(get-in descriptor [:transport :type])`.
  - `:ringbuffer` method creates `RingBufferStream` with capacity from descriptor.
- `RingBufferStream` reference implementation:
  - Map-backed storage with memory reclamation via `dissoc` in `drain-one!` utility.
  - Absolute indexing (`head`, `tail`) enabling cursor-based reads and gap detection.
  - Bounded capacity with `:full` return on overflow.
  - Factory: `open!` with `:ringbuffer` transport type.
  - Extends `IDaoStreamReader`, `IDaoStreamWriter`, `IDaoStreamBound`.
- Cursor-based `next` method returns `{:ok val :cursor cursor'}`, `:blocked`, `:end`, or `:daostream/gap`.
- `IDaoStreamWaitable` protocol for transport-local waiter registration:
  - `register-reader-waiter!` stores reader wake entries indexed by cursor position.
  - `register-writer-waiter!` stores writer wake entries; woken when space becomes available.
  - `RingBufferStream` implements both: `:reader-waiters {}` (position → entry) and `:writer-waiters []` (queue).
- Utility functions (not protocol methods):
  - `->seq` lazy sequence walks with cursor; terminates at `:blocked`, `:end`, or gap.
  - `drain-one!` destructively consumes one value AND wakes registered writers (returns `{:ok val, :woke [...]}`).
    When a writer is woken, its datom is atomically written to the stream. If a reader is waiting at the new position, both wake together.
  - `count-available` returns count of appended but unconsumed values (transport-specific metadata).
- Descriptor shape (current): `{:transport {:type :ringbuffer :mode :create :capacity nil-or-int}}`.
- `check-wait-set` remains the universal polling fallback for non-waitable streams,
  handling `:next`, `:put`, and `:take` in current runtimes. Waitable transports
  bypass it for normal reader and writer parking.

**What still needs work:**
- `WebSocketStream` and cross-process transports: `open!` method for `:websocket` transport type (target, not yet implemented).
- Full descriptor schema expansion:
  - `:stream/id` for canonical identity.
  - `:transport :mode` (`:create` vs `:attach`).
  - `:transport :retention` policy for eviction strategy.
  - `:transport :backpressure` policy (to codify `:park-writer` in descriptor).
  - `:shibi` trust metadata support.
- Gap policy: current implementation returns `:daostream/gap` (behavior defined). Document policy for
  gap handling (error, skip to earliest, sentinel).
- Seek operation: `(seek [this cursor pos])` for position-based jumping (target, not implemented).

The design target is descriptor-first, transport-agnostic via `open!` multi-method, cursor-based, gap-aware, and
with orthogonal protocol boundaries that enable precise capability expression.

## Deferred

- Additional transport schemas beyond the initial descriptor contract (`:stream/id`, `:transport :mode`, `:retention`, `:backpressure`, `:shibi`).
- Typed streams (fixed-size datom streams for cache-efficient layouts and SIMD operations).
- Retention and eviction policies.
- Gap signaling and policy for bounded-retention transports.
- Seek operation for position-based jumping.
- DaoDB-specific stream consumption contracts.
- Seqable integration (currently via `->seq` utility).
