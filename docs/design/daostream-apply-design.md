# dao.stream.apply Design: Stream-Native Apply Protocol

## Overview

`dao.stream.apply` is a standalone asynchronous request/response protocol built on top of DaoStream.
Its only dependency should be `dao.stream`.

**Related documents:**
- `docs/design/daostream-design.md` — the stream abstraction and transport model that underpins this protocol
- `docs/design/ffi-design.md` — how Yin VM uses this protocol to implement FFI

The protocol reifies function application as explicit stream traffic:
- a caller emits a request with an opaque call id, an operation keyword, and evaluated arguments
- a callee reads the request, computes a result, and emits a response with the same id
- the caller observes that response later on the response stream and continues however its own runtime chooses

This is broader than Yin VM FFI. The Yin VM is one consumer of the protocol, not its owner.
`dao.stream.apply` must be usable on its own:
- between a plain caller and callee in one process
- across linked processes or nodes
- as a host bridge substrate for Yin VM FFI
- as a testable transport-independent request/response layer

The protocol is async by construction: emitting a request and receiving its response are
distinct stream events, separated in time and not coupled to one synchronous host stack.

The protocol defines values and stream interactions. It does not define scheduling,
continuation layout, waiter entries, or VM engine behavior.

---

## Core Philosophy

Everything is a stream. Apply across a boundary is asynchronous stream I/O.

`dao.stream.apply` exists to make application explicit:
- no hidden callback boundary
- no implicit host call stack crossing
- no transport-specific interface at the semantic layer

The protocol is deliberately small:
- one endpoint descriptor
- one request shape
- one response shape
- opaque ids for routing

Scheduling is a consumer concern. A caller may block, poll, park a continuation,
or hand the response to another stream processor. The protocol does not privilege
any one runtime strategy.

Errors are values. The protocol has no exception semantics.

---

## Protocol Definition

### Endpoint Descriptor

A `dao.stream.apply` endpoint is a pair of stream descriptors:

```clojure
{:dao.stream.apply/request  <stream-descriptor>
 :dao.stream.apply/response <stream-descriptor>}
```

The request descriptor names the stream the callee reads from.
The response descriptor names the stream the caller reads from.

Both descriptors are plain data. They can be stored, transmitted, and opened
wherever the corresponding DaoStream transports are available.

Example endpoint descriptor:

```clojure
{:dao.stream.apply/request
 {:transport {:type :ringbuffer
              :mode :create
              :capacity nil}}
 :dao.stream.apply/response
 {:transport {:type :ringbuffer
              :mode :create
              :capacity nil}}}
```

Example remote endpoint descriptor:

```clojure
{:dao.stream.apply/request
 {:transport {:type :websocket
              :mode :connect
              :url "ws://host:port/in"}}
 :dao.stream.apply/response
 {:transport {:type :websocket
              :mode :connect
              :url "ws://host:port/out"}}}
```

### Request Value

A caller emits a request on the request stream:

```clojure
{:dao.stream.apply/id   <opaque-id>
 :dao.stream.apply/op   <keyword>
 :dao.stream.apply/args <vector>}
```

Example:

```clojure
{:dao.stream.apply/id   :call-7
 :dao.stream.apply/op   :op/add
 :dao.stream.apply/args [10 20]}
```

The protocol does not prescribe the id format. The caller chooses it.
It may be a keyword, UUID, integer, or any other routing token agreed on by the caller.

### Response Value

A callee emits a response on the response stream:

```clojure
{:dao.stream.apply/id    <opaque-id>
 :dao.stream.apply/value <any>}
```

Example:

```clojure
{:dao.stream.apply/id    :call-7
 :dao.stream.apply/value 30}
```

The response id must match the request id.
The protocol treats the id as opaque routing data.

### Handler Contract

The protocol does not require a specific handler representation, but the natural
callee-side model is a map from operation keyword to function:

```clojure
{:op/add +
 :op/echo identity}
```

Given a request, the callee:
1. reads `:dao.stream.apply/op`
2. looks up a handler
3. applies the handler to `:dao.stream.apply/args`
4. emits `{:dao.stream.apply/id id :dao.stream.apply/value result}`

That contract is protocol-level. It does not depend on any VM.

---

## Standalone Usage

### Plain Caller/Callee Flow

Standalone `dao.stream.apply` usage requires only `dao.stream` and `dao.stream.apply`.

```clojure
(require '[dao.stream :as ds]
         '[dao.stream.apply :as dao-apply])

(let [request-stream  (ds/open! {:transport {:type :ringbuffer :capacity nil}})
      response-stream (ds/open! {:transport {:type :ringbuffer :capacity nil}})
      handlers        {:op/add +}

      _ (ds/put! request-stream
                 (dao-apply/request :call-1 :op/add [10 20]))

      {:keys [ok]} (ds/next request-stream {:position 0})
      {:dao.stream.apply/keys [id op args]} ok
      result (apply (get handlers op) args)
      _ (ds/put! response-stream
                 (dao-apply/response id result))

      {:keys [ok]} (ds/next response-stream {:position 0})]
  ok)
```

Result:

```clojure
{:dao.stream.apply/id :call-1
 :dao.stream.apply/value 30}
```

The key point is that no VM is involved. The protocol is complete enough to model
request/response application by itself.

### Transport Independence

The same request and response shapes work over any DaoStream transport:
- in-memory ring buffers
- linked streams
- websocket-backed streams
- future transports that satisfy the DaoStream interfaces

Transport changes only how the streams are opened and delivered.
It does not change the `dao.stream.apply` data model.

---

## Public API Direction for `src/cljc/dao/stream/apply.cljc`

`src/cljc/dao/stream/apply.cljc` should be the public operational surface for this protocol.
A reader should be able to understand how to use `dao.stream.apply` without reading
Yin VM namespaces.

### Responsibilities

This namespace should own:
- endpoint construction and endpoint accessors
- request and response construction
- request and response predicates or validation helpers
- request and response accessors
- stream helpers for emitting requests and responses
- stream helpers for reading requests and responses
- optional callee-side helpers for dispatching a request through a handler map

### Non-Responsibilities

This namespace should not own:
- VM state
- continuation parking
- waiter entry layout
- scheduler or wait-set policy
- run-queue synthesis
- AST node evaluation
- bytecode opcode handling
- host bridge state tied to a specific VM runtime

### Dependency Boundary

`dao.stream.apply` should depend only on `dao.stream`.

It should not depend on:
- `yin.vm`
- `yin.vm.engine`
- `yin.vm.host-ffi`
- any semantic, stack, register, or AST-walker interpreter namespace

### Public API Shape

The namespace should read like a self-contained protocol module. In addition to the
existing constructors, its public surface should grow toward helpers in this family:

```clojure
make-endpoint
endpoint-request
endpoint-response

request
request?
request-id
request-op
request-args

response
response?
response-id
response-value

put-request!
put-response!
next-request
next-response

dispatch-request
serve-once!
```

The exact function set can evolve, but the principle is fixed:
`dao.stream.apply` should expose the protocol in a form usable without VM knowledge.

---

## Yin VM Integration

### Role of the VM

The Yin VM uses `dao.stream.apply` to implement FFI.
That is an integration choice made by the VM.
It does not redefine the protocol.

In Yin:
- `:dao.stream.apply/call` is the AST and opcode-level projection of the protocol
- the VM evaluates operand expressions
- the VM chooses a call id
- the VM emits a request using the protocol
- the VM resumes when it receives a response according to its own runtime rules

### VM-Specific State

The Yin VM may keep request and response streams in its store:

```clojure
{:store
 {:yin/call-in         <stream>
  :yin/call-in-cursor  {:position 0}
  :yin/call-out        <stream>
  :yin/call-out-cursor {:position 0}}}
```

Those keys are integration details of the VM.
They are not part of the `dao.stream.apply` protocol itself.

### VM Scheduling and Waiting

When the Yin VM uses `dao.stream.apply`, it may:
- park a continuation
- register a waiter on the response stream
- poll through its wait-set
- synthesize run-queue entries

Those behaviors belong to the VM implementation.
They must not migrate into `dao.stream.apply`, because they depend on VM-specific
continuation and scheduler structures.

### Why Keep `:dao.stream.apply/call`

The protocol namespace is `dao.stream.apply`.
The Yin VM's concrete AST and opcode tag is `:dao.stream.apply/call`.

That distinction matters:
- `dao.stream.apply` names the protocol family
- `:dao.stream.apply/call` names one VM operation that consumes the protocol

The tag should stay `:dao.stream.apply/call`, not collapse to `:dao.stream.apply`.

---

## Transport Examples

### In-Process RingBuffer

Caller and callee share in-memory streams:

```clojure
(let [request-stream  (ds/open! {:transport {:type :ringbuffer :capacity nil}})
      response-stream (ds/open! {:transport {:type :ringbuffer :capacity nil}})
      handlers        {:op/echo identity}]
  ...)
```

This is the simplest standalone setup and the natural default for tests.

### Cross-Process WebSocket

Caller and callee can also communicate over remote streams:

```clojure
{:dao.stream.apply/request
 {:transport {:type :websocket
              :mode :connect
              :url "ws://localhost:8000/in"}}
 :dao.stream.apply/response
 {:transport {:type :websocket
              :mode :connect
              :url "ws://localhost:8000/out"}}}
```

The request and response values are unchanged. Only stream realization changes.

---

## Error Handling

Errors are values carried in `:dao.stream.apply/value`.

Example:

```clojure
{:dao.stream.apply/id :call-9
 :dao.stream.apply/value
 {:error "division by zero"
  :code :math/divide-by-zero}}
```

The protocol does not define exception behavior, retries, or timeouts.
Consumers may layer those policies on top.

---

## Verification

### Protocol-Level Verification

Protocol-level tests should focus on standalone behavior:
1. `make-endpoint` returns the expected descriptor pair.
2. `request` and `response` produce the expected map shapes.
3. Request and response accessors round-trip the expected fields.
4. A standalone caller/callee exchange succeeds over in-memory DaoStreams.
5. Response routing works by opaque id.
6. Missing-handler behavior is explicit at the callee layer.

### Yin Integration Verification

Yin VM tests should prove that the VM consumes the protocol correctly:
1. `:dao.stream.apply/call` emits the expected request shape.
2. The VM resumes with `:dao.stream.apply/value` from the matching response.
3. Stack, register, semantic, and AST-walker paths preserve the same protocol behavior.
4. Waitable and non-waitable transports remain VM scheduling concerns, not protocol changes.

---

## Design Rationale

### Why Standalone First

If `dao.stream.apply` is useful only through the VM, then it is not really a protocol.
Making it standalone keeps the abstraction honest:
- the protocol can be tested without the VM
- non-VM callers and callees can use it directly
- the VM becomes a consumer, not a special case
- the dependency boundary stays clean

### Why Only Depend on `dao.stream`

The protocol layer should know only about streams.
Bringing in VM namespaces would collapse interpretation and execution into one layer
and make a general request/response abstraction depend on one specific runtime.

### Why Opaque Call IDs

Ids are routing tokens, not semantic identities.
Different consumers can choose different id schemes:
- Yin parked continuation ids
- UUIDs
- sequence numbers
- transport-specific correlation ids

The protocol should not privilege one choice.

### Why Errors Are Values

A stream protocol should transport facts, not implicit control transfers.
Representing failures as values keeps causality explicit and works across process boundaries.

---

## Deferred

- Expand `src/cljc/dao/stream/apply.cljc` from constructors into the full standalone public API described above.
- Add protocol-level helper functions for request and response stream I/O.
- Add standalone dispatch helpers so a callee can serve requests by reading only `dao.stream.apply`.
- Add optional metadata fields such as timeout or tracing without making them mandatory.
- Implement push-based wakeup for remote transports where appropriate, but keep that as a transport or runtime concern, not a protocol concern.
