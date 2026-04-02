# DaoStreamApply Design: Clojure `apply` over DaoStream

## Overview

DaoStreamApply is an implementation of Clojure `apply` across streams.
It reifies function application as explicit DaoStream data: an operation plus evaluated
arguments on a request stream, followed by the resulting value on a response stream.
Unlike Clojure `apply`, which is synchronous and returns immediately in the current call
stack, DaoStreamApply is asynchronous: the application is emitted as stream traffic and
the caller resumes only when a response arrives.
The protocol is transport-independent and built on top of DaoStream.
It unifies:
- In-process VM ↔ host bridge calls
- Cross-process VM ↔ remote service calls (via WebSocket or any DaoStream transport)
- Concurrent multi-caller scenarios with response routing via opaque call IDs

The protocol treats the continuation as a stream reader-waiter. When a caller parks and sends
a request, it becomes indistinguishable from any other blocked stream operation. The callee
writes a response to a response stream, waking the caller via the existing DaoStream waiter
mechanism. No special engine idle handlers. No privilege. Same transport layer regardless of
deployment topology.

## Core Philosophy

Everything is a stream. Function application is stream I/O. Continuations are readers. Responses are values.
`apply` does not disappear into a host callback. It is represented directly as stream traffic.
This is why DaoStreamApply is async even though Clojure `apply` is sync: the operation
crosses a stream boundary and completion is represented by a later response.
The protocol is minimal: five datom attributes, two stream descriptors, zero new VM opcodes,
zero new scheduler logic. Transport is DaoStream. Scheduling is free.

---

## Protocol Definition

### Endpoint Descriptor

A DaoStreamApply endpoint is a pair of stream descriptors:

```clojure
;; Immutable, serializable, first-class value
{:dao.stream.apply/request  <stream-descriptor>    ;; callee reads requests here
 :dao.stream.apply/response <stream-descriptor>}   ;; caller reads responses here
```

Both descriptors conform to the DaoStream descriptor schema:
```clojure
{:transport {:type :ringbuffer        ;; or :websocket, :socket, etc.
             :mode :create            ;; or :attach (attach not yet impl.)
             :capacity nil-or-int}}   ;; nil for unbounded
```

Example endpoint (in-process, unbounded):
```clojure
{:dao.stream.apply/request  {:transport {:type :ringbuffer :mode :create :capacity nil}}
 :dao.stream.apply/response {:transport {:type :ringbuffer :mode :create :capacity nil}}}
```

Example endpoint (cross-process, over WebSocket):
```clojure
{:dao.stream.apply/request  {:transport {:type :websocket :mode :connect :url "ws://host:port/in"}}
 :dao.stream.apply/response {:transport {:type :websocket :mode :connect :url "ws://host:port/out"}}}
```

**Key property (Channel Mobility)**: The endpoint descriptor is a plain value. It can be
sent through a stream, persisted, or passed to any process. Any host with `open!`
implementations for the transport types can materialize it locally. The transport
is the detail; the endpoint is the contract.

### Request Datom

A caller emits a request on the `call-in` stream:

```clojure
{:dao.stream.apply/id   <keyword>    ;; opaque return address; usually a parked continuation ID
 :dao.stream.apply/op   keyword      ;; operation name; opaque to the protocol
 :dao.stream.apply/args vector}      ;; evaluated argument values [val1 val2 ...]
```

Example request from a VM `:ffi/call`:
```clojure
{:dao.stream.apply/id   :parked-0
 :dao.stream.apply/op   :op/add
 :dao.stream.apply/args [10 20]}
```

The `:dao.stream.apply/id` is the routing address for the response. The protocol does not
prescribe its format; the caller chooses it. A Yin VM uses the parked continuation's
generated keyword (`:parked-N`). A stateless HTTP gateway might use a UUID.

### Response Datom

A callee emits a response on the `call-out` stream:

```clojure
{:dao.stream.apply/id    <keyword>   ;; must match the request's :dao.stream.apply/id
 :dao.stream.apply/value <any>}      ;; the result value
```

Example response:
```clojure
{:dao.stream.apply/id    :parked-0
 :dao.stream.apply/value 30}
```

The `:dao.stream.apply/value` can be any Clojure value (or transit-serializable value for
cross-process). Errors are represented as values (e.g., `{:error "..." :code ...}`) —
the protocol has no exception semantics.

---

## Scheduling Mechanism

DaoStreamApply unifies with stream reading. A parked continuation IS a reader-waiter on the
response stream.

### Call Sequence (Synchronous In-Process Example)

```
Caller (Yin VM executing :ffi/call)
  1. Evaluate operands → [val1 val2]
  2. Call engine/park-continuation → {:type :parked-continuation :id :parked-0 ...}
  3. Advance ::call-out-cursor from position 0 to 1
  4. Call register-reader-waiter! on call-out stream at position 0
     (waiter entry contains the parked continuation's stack, env)
  5. Call put! on call-in stream: {:dao.stream.apply/id :parked-0 :dao.stream.apply/op :op/add
                                    :dao.stream.apply/args [10 20]}
  6. Set :blocked true, return control to run-loop
  7. run-loop continues with next runnable continuation (no spin, no poll)

Idle Loop (engine)
  When run-queue and wait-set exhausted:
    → check-wait-set may be called (see "Fallback Polling" below)
    → no special check-ffi-out

Callee (Host Bridge, running in a thread or event loop)
  1. Call next on call-in stream at cursor {:position 0}
  2. Read request: {:dao.stream.apply/id :parked-0 :dao.stream.apply/op :op/add :dao.stream.apply/args [10 20]}
  3. Dispatch: (get handlers :op/add) → the + function
  4. Call (apply + [10 20]) → 30
  5. Call put! on call-out stream: {:dao.stream.apply/id :parked-0 :dao.stream.apply/value 30}
  6. Transport (RingBufferStream) checks: is there a reader-waiter at this position?
     Yes: returns {:result :ok :woke [{:entry waiter-entry :value response :position 0}]}

Transport (IDaoStreamWaitable)
  put! returns woke items → engine processes via handle-effect → make-woken-run-queue-entries
  Woke entry:
    {:entry {:cursor-ref {:type :cursor-ref :id ::call-out-cursor}
             :stack [...] :env {...}}
     :value {:dao.stream.apply/id :parked-0 :dao.stream.apply/value 30}
     :position 0}

Resume Path (engine/make-woken-run-queue-entries)
  1. Detect :cursor-ref in entry → this is a reader-waiter
  2. Stamp :value with the response map
  3. Compute store-update: advance cursor from {:position 0} to {:position 1}
  4. Build run-queue entry:
     {:cursor-ref {...}
      :stack [...]
      :env {...}
      :value {:dao.stream.apply/id :parked-0 :dao.stream.apply/value 30}
      :store-updates {::call-out-cursor {:position 1}}}

resume-from-run-queue (existing path)
  1. Pop run-queue entry
  2. Merge store-updates into :store
  3. Restore :stack and :env from entry
  4. Set :control {:type :value :val response-map}
  5. Set :blocked false, :halted false
  6. Continue run-loop

VM resumes
  The :control `:value` node evaluates to the response-map. The :ffi/call AST node
  extracts `:dao.stream.apply/value` from it and returns that as the final result.
```

### Fallback Polling (Non-Waitable Transports)

For transports that do not implement `IDaoStreamWaitable` (e.g., `WebSocketStream`
across a network), the caller falls back to the universal `check-wait-set` scheduler.

```
Same setup, but call-out is WebSocketStream (not IDaoStreamWaitable):

Caller parks, calls put! on call-in → no local waiter registration (WebSocket
  has no local reader-waiters). Continuation falls through to wait-set.

Idle Loop (engine)
  check-wait-set executes:
    Sees wait-set entry with :reason :next on ::call-out-cursor
    Calls (ds/next call-out cursor) → :blocked (network delayed)
    Keeps entry in wait-set, retries next idle iteration

Callee writes to call-out via WebSocket link

Network Link
  Receives message, appends to local :remote-stream RingBufferStream

Next check-wait-set iteration
  Calls (ds/next call-out cursor) → {:ok response-map :cursor cursor'}
  Moves entry to run-queue with :value response-map
  resume-from-run-queue restores continuation
```

The code path is unchanged. `check-wait-set` already handles `:reason :next` on
any stream; it does not care whether the stream is waitable or not.

---

## VM Integration

### Store Streams

When a Yin VM is created, `empty-state` initializes two DaoStreamApply streams in the store:

```clojure
{:store
 {:yin/call-in        <RingBufferStream>      ;; callee reads requests
  :yin/call-in-cursor {:position 0}           ;; read head for call-in
  :yin/call-out       <RingBufferStream>      ;; caller reads responses
  :yin/call-out-cursor {:position 0}}}        ;; read head for call-out
```

These are **always** in-process ringbuffer streams. The VM does not directly connect to
remote services; the bridge code does. The VM's view is: two local streams that somehow
get populated and drained.

A deployed system may replace these with descriptors pointing to WebSocket transports
before VM creation:

```clojure
(-> (vm/create-vm {:call-in  {:transport {:type :websocket :url "..."}}
                   :call-out {:transport {:type :websocket :url "..."}}})
    (vm/load-program program)
    (vm/run))
```

But the VM code does not change. It always sees `open!` applied to the descriptors,
returning a stream object, which it reads/writes identically.

### AST Node: `:ffi/call`

The `:ffi/call` AST node is unchanged:

```clojure
{:type     :ffi/call
 :op       keyword         ;; e.g. :op/add (becomes :dao.stream.apply/op)
 :operands [node1 node2]}  ;; expressions; evaluated before call
```

Datom representation (from `ast->datoms`):
```clojure
[e :yin/type     :ffi/call    t m]
[e :yin/op       :op/add      t m]
[e :yin/operands [e1 e2]      t m]
```

No new attributes. The `:yin/op` already exists.

### Opcode: `:ffi-call` (21)

The bytecode opcode is unchanged. Stack and register VMs emit and execute `[:ffi-call op argc]`.
No semantic change.

### Execution: `park-and-call` in AST Walkers

Replace the old `park-and-enqueue-ffi` (which wrote to a stream and returned immediately)
with `park-and-call` (which registers a waiter, writes a request, and parks).

**Old (current) behavior**:
```
:ffi/call → park → write to ::ffi-out → return blocked
           (no waiter registration; polling happens at idle via check-ffi-out)
```

**New behavior**:
```
:ffi/call → park → register reader-waiter on ::call-out at next position
         → write request to ::call-in → return blocked
         (waiter wakes when response arrives, via IDaoStreamWaitable or check-wait-set)
```

Pseudocode for `park-and-call`:

```clojure
(defn- park-and-call
  "Park continuation and enqueue call to request stream with response waiter."
  [vm op args stack env]
  (let [;; 1. Park the continuation
        parked       (engine/park-continuation vm {:stack stack :env env})
        parked-id    (get-in parked [:value :id])  ;; :parked-N

        ;; 2. Get response stream from store
        call-out     (get-in parked [:store ::vm/call-out])
        cursor-data  (get-in parked [:store ::vm/call-out-cursor])
        cursor-pos   (:position cursor-data)

        ;; 3. Register as reader-waiter on response stream
        ;;    The waiter entry contains stack & env for resumption
        waiter-entry {:cursor-ref {:type :cursor-ref :id ::vm/call-out-cursor}
                      :reason :next
                      :stream-id ::vm/call-out
                      :stack stack
                      :env env}
        _            (when (satisfies? ds/IDaoStreamWaitable call-out)
                       (ds/register-reader-waiter! call-out cursor-pos waiter-entry))
        ;;    (If not waitable, waiter-entry goes to wait-set instead — handled by handle-effect)

        ;; 4. Get request stream from store
        call-in      (get-in parked [:store ::vm/call-in])

        ;; 5. Build and emit request
        request      (dao.stream.apply/request parked-id op args)
        _            (ds/put! call-in request)]

    ;; 6. Return blocked state
    (assoc parked
           :control nil
           :value   :yin/blocked
           :blocked true
           :halted  false)))
```

This is called from the same place as the old `park-and-enqueue-ffi`, triggered by
`:ffi/call` evaluation in each AST walker (semantic, stack, register).

**Important**: If the transport does NOT implement `IDaoStreamWaitable`, the waiter
registration is skipped, and `handle-effect` for the `:stream/next` effect adds the
entry to the scheduler's wait-set (fallback path). This is automatic; no special logic needed.

### Response Extraction

When a continuation resumes with the response map, the `:ffi/call` evaluation extracts
`:dao.stream.apply/value` and returns it as the application result.

In `handle-return-value` (semantic.cljc), after the response is resumed:

```clojure
;; response-map is: {:dao.stream.apply/id :parked-0 :dao.stream.apply/value result}
:ffi/call
(let [result-value (:dao.stream.apply/value (get-in vm [:control :val]))]
  (assoc vm :control {:type :value :val result-value}))
```

This is the final result of the `:ffi/call` expression. If the response map has
additional fields (e.g., error info), they are present in the full response value
and the VM code can inspect them if needed.

---

## New File: `src/cljc/dao/stream/apply.cljc`

This file defines the DaoStreamApply protocol at the stream level, independent of any VM.

### API

```clojure
(defn make-endpoint
  "Create an endpoint descriptor from two stream descriptors.

  Args:
    request-descriptor  - stream descriptor where callee reads requests
    response-descriptor - stream descriptor where caller reads responses

  Returns:
    {:dao.stream.apply/request <request-desc>
     :dao.stream.apply/response <response-desc>}

  Example:
    (make-endpoint {:transport {:type :ringbuffer :capacity nil}}
                   {:transport {:type :ringbuffer :capacity nil}})"
  [request-descriptor response-descriptor]
  {:dao.stream.apply/request request-descriptor
   :dao.stream.apply/response response-descriptor})

(defn request
  "Create a request datom.

  Args:
    id   - keyword or other opaque ID; must be present in response
    op   - keyword naming the operation
    args - vector of argument values

  Returns:
    {:dao.stream.apply/id <id>
     :dao.stream.apply/op <op>
     :dao.stream.apply/args <args>}"
  [id op args]
  {:dao.stream.apply/id id
   :dao.stream.apply/op op
   :dao.stream.apply/args args})

(defn response
  "Create a response datom.

  Args:
    id    - keyword; must match request's :dao.stream.apply/id for routing
    value - any value (result, error, etc.)

  Returns:
    {:dao.stream.apply/id <id> :dao.stream.apply/value <value>}"
  [id value]
  {:dao.stream.apply/id id :dao.stream.apply/value value})
```

**No scheduling or transport logic in this file.** Transport is DaoStream (caller's
responsibility). Scheduling is the caller's responsibility.

---

## Implementation Details

### Changes to `src/cljc/yin/vm.cljc`

#### 1. Rename stream keys

```clojure
;; OLD (still in code)
(def ffi-out-stream-key  :yin/ffi-out)
(def ffi-out-cursor-key  :yin/ffi-out-cursor)

;; NEW
(def call-in-stream-key  :yin/call-in)
(def call-in-cursor-key  :yin/call-in-cursor)
(def call-out-stream-key :yin/call-out)
(def call-out-cursor-key :yin/call-out-cursor)
```

Keep old keys as aliases for compatibility during transition, or remove if breaking.

#### 2. Update `empty-state`

```clojure
(defn empty-state [opts]
  (let [call-in  (or (:call-in opts)
                     (ds/open! {:transport {:type :ringbuffer :capacity nil}}))
        call-out (or (:call-out opts)
                     (ds/open! {:transport {:type :ringbuffer :capacity nil}}))]
    {:store
     {call-in-stream-key  call-in
      call-in-cursor-key  {:position 0}
      call-out-stream-key call-out
      call-out-cursor-key {:position 0}}
     ;; ... other fields
     }))
```

The `:call-in` and `:call-out` options allow overriding the default streams
(e.g., to inject WebSocket descriptors or mock streams for testing).

#### 3. Remove `:bridge-dispatcher`

The `:bridge-dispatcher` map moves from VM state to host bridge code.
It is no longer part of `empty-state`. Any tests or code that passed
`:bridge-dispatcher {:op/echo identity}` to `create-vm` must be updated.

### Changes to `src/cljc/yin/vm/engine.cljc`

#### 1. Remove `check-ffi-out`

Delete the entire `check-ffi-out` function (currently around line 193).
It is no longer called from `run-loop`.

#### 2. Remove `enqueue-ffi-request`

Delete the `enqueue-ffi-request` function (currently around line 176).
Its work is now done by the `park-and-call` functions in semantic/stack/register.

#### 3. Update `run-loop`

Remove the call to `check-ffi-out` from the idle branch:

```clojure
;; OLD
(let [v' (-> v check-wait-set check-ffi-out)]
  ...)

;; NEW
(let [v' (check-wait-set v)]
  ...)
```

That's it. `check-wait-set` already handles all stream blocking scenarios,
including reader-waiters on `call-out`.

### Changes to `src/cljc/yin/vm/semantic.cljc`

Replace `park-and-enqueue-ffi` with `park-and-call`:

```clojure
(defn- park-and-call
  "Park and register as reader-waiter on call-out response stream."
  [vm op args stack env]
  (let [parked       (engine/park-continuation vm {:stack stack :env env})
        parked-id    (get-in parked [:value :id])
        call-out     (get-in parked [:store vm/call-out-stream-key])
        cursor-data  (get-in parked [:store vm/call-out-cursor-key])
        cursor-pos   (:position cursor-data)
        waiter-entry {:cursor-ref {:type :cursor-ref :id vm/call-out-cursor-key}
                      :reason :next
                      :stream-id vm/call-out-stream-key
                      :stack stack
                      :env env}
        _            (when (satisfies? ds/IDaoStreamWaitable call-out)
                       (ds/register-reader-waiter! call-out cursor-pos waiter-entry))
        call-in      (get-in parked [:store vm/call-in-stream-key])
        request      (dao.stream.apply/request parked-id op args)
        _            (ds/put! call-in request)]
    (assoc parked
           :control nil
           :value   :yin/blocked
           :blocked true
           :halted  false)))
```

Update the `:ffi/call` case in `handle-node-eval`:

```clojure
:ffi/call
(let [operands (or (aget node-arr ATTR_OPERANDS) [])]
  (if (empty? operands)
    (park-and-call vm op [] stack env)
    ;; push frame to evaluate operands, then call park-and-call
    {:type :ffi-args ...}))
```

Add requires:
```clojure
[dao.stream.apply :as dao.stream.apply]
```

### Changes to `src/cljc/yin/vm/stack.cljc` and `src/cljc/yin/vm/register.cljc`

Same `park-and-call` replacement in the appropriate execution paths.
Both have their own AST walkers and need the update in their execution logic.

### Changes to Test Files

Tests that passed `:bridge-dispatcher` to `create-vm` must be rewritten to use
a bridge helper function that reads from `call-in` and writes to `call-out`:

```clojure
;; OLD
(vm/eval (ast-walker/create-vm {:bridge-dispatcher {:op/echo identity}})
         ast)

;; NEW
(let [vm (ast-walker/create-vm)]
  (loop [v (vm/load-program vm ast) (vm/run)]
    (if (vm/halted? v)
      v
      (bridge-step v {:op/echo identity} {:position 0}))))

(defn bridge-step [vm handlers cursor]
  (let [call-in (get (vm/store vm) ::vm/call-in)
        {:keys [ok cursor']} (ds/next call-in cursor)]
    (when ok
      (let [{:dao/keys [call-id call-op call-args]} ok
            result (apply (get handlers call-op) call-args)
            call-out (get (vm/store vm) ::vm/call-out)]
        (ds/put! call-out (dao.stream.apply/response call-id result))
        [vm cursor'])))
```

---

## Data Structures Reference

### Endpoint Descriptor
```clojure
{:dao.stream.apply/request  {:transport {:type :ringbuffer
                                  :mode :create
                                  :capacity nil}}
 :dao.stream.apply/response {:transport {:type :ringbuffer
                                  :mode :create
                                  :capacity nil}}}
```

### Request Map
```clojure
{:dao.stream.apply/id   :parked-0
 :dao.stream.apply/op   :op/add
 :dao.stream.apply/args [10 20]}
```

### Response Map
```clojure
{:dao.stream.apply/id    :parked-0
 :dao.stream.apply/value 30}
```

### Waiter Entry (registered on call-out stream)
```clojure
{:cursor-ref   {:type :cursor-ref :id :yin/call-out-cursor}
 :reason       :next
 :stream-id    :yin/call-out
 :stack        [...]
 :env          {...}}
```

### Woke Item (returned by put! on call-out)
```clojure
{:entry    <waiter-entry>
 :value    {:dao.stream.apply/id :parked-0 :dao.stream.apply/value 30}
 :position 0}
```

---

## Transport Examples

### In-Process (RingBufferStream)

Default. VM and bridge share ringbuffer streams in memory.

```clojure
(let [call-in  (ds/open! {:transport {:type :ringbuffer :capacity nil}})
      call-out (ds/open! {:transport {:type :ringbuffer :capacity nil}})
      vm       (ast-walker/create-vm)
      _        (vm/load-program vm program)
      vm-task  (future (vm/run vm))

      bridge   (future
                 (loop [c {:position 0}]
                   (let [call-in (get (vm/store vm) ::vm/call-in)
                         {:keys [ok cursor']} (ds/next call-in c)]
                     (when ok
                       (let [{:dao/keys [call-id call-op call-args]} ok
                             result (apply (get handlers call-op) call-args)
                             call-out (get (vm/store vm) ::vm/call-out)]
                         (ds/put! call-out (dao.stream.apply/response call-id result))))
                     (recur (or cursor' c)))))]

  @vm-task))
```

Wake mechanism: `IDaoStreamWaitable` (push).

### Cross-Process (WebSocketStream)

VM connects to remote bridge via WebSocket.

```clojure
;; Server (host bridge)
(require '[dao.stream.ws :as ws])

(defn start-bridge-server [port]
  (ws/listen! port
    (fn [call-in]
      ;; Each client connection gets a paired call-out stream
      (let [call-out (ws/listen! (inc port))]
        (bridge-handler call-in call-out handlers)))))

;; Client (VM)
(let [call-in  {:transport {:type :websocket :url "ws://localhost:8000/in"}}
      call-out {:transport {:type :websocket :url "ws://localhost:8000/out"}}
      vm       (ast-walker/create-vm {:call-in call-in :call-out call-out})]
  (vm/load-program vm program)
  (vm/run vm))
```

Wake mechanism: `check-wait-set` polling (fallback, since `WebSocketStream` is not
`IDaoStreamWaitable`). Future: implement `IDaoStreamWaitable` on `WebSocketStream`
for push-based wakeup.

---

## Error Handling

The protocol has no exception semantics. Errors are values.

```clojure
;; Handler that fails
(let [result (try
               (apply handler args)
               (catch Exception e
                 {:error (str e) :type :exception}))]
  (ds/put! call-out (dao.stream.apply/response call-id result)))

;; VM receives error as a value
{:error "..." :type :exception}
```

The VM can inspect the response and decide how to handle it.

---

## Verification

### Protocol Level (`test/dao/stream/call_test.cljc`)

1. `request` produces the correct datom shape.
2. `response` produces the correct datom shape.
3. `make-endpoint` bundles descriptors correctly.
4. Endpoint descriptor round-trips through a stream (channel mobility).

### VM Level (`test/yin/vm/ffi_daocall_test.cljc`)

1. `:ffi/call` parks the VM and emits a request on `call-in`.
2. `call-out` is empty until the bridge writes.
3. VM resumes with the correct response value after bridge responds.
4. Operands are evaluated and passed as args vector.
5. Handler return value becomes the call result.
6. VM remains blocked if bridge does not respond.
7. Bridge throws if handler is missing.
8. Two concurrent VMs with shared streams route responses by `:dao.stream.apply/id` correctly.
9. No `check-ffi-out` symbol in `engine.cljc`.
10. No `:bridge-dispatcher` in `vm/empty-state`.

### Integration

1. Stack and register VMs follow the same pattern as semantic VM.
2. Macro expansion, if applicable, works with DaoStreamApply (existing macro system).
3. Bytecode encoding/decoding of `:ffi-call` opcode is unchanged.

---

## Design Rationale

### Why Streams?

Streams are the universal abstraction. By making function application stream-based, DaoStreamApply:
- Inherits bidirectionality (can go either direction over a link).
- Inherits transport abstraction (ringbuffer, websocket, socket, file, etc.).
- Inherits concurrency semantics (multiple concurrent calls, out-of-order responses).
- Inherits observability (streams can be monitored, recorded, replayed).

### Why No Special Idle Handler?

`check-ffi-out` is a special case that breaks the stream abstraction. By treating
calls as stream reads, they use the same `check-wait-set` fallback that any other
blocked operation uses. The engine learns nothing new. The scheduler learns nothing new.

### Why Reader-Waiters?

A parked continuation waiting for a response IS a reader waiting for data. Using
`IDaoStreamWaitable` for local transports gives push-based wakeup (no polling). The
fallback to `check-wait-set` handles network latency and non-waitable transports
without special code.

### Why Opaque Call IDs?

The protocol does not dictate how call IDs are chosen. A VM uses its parked-continuation
keywords. An HTTP gateway might use UUIDs. A function-based RPC might use sequential
integers. The response stream does not care; it just includes the ID so callers can
route responses to the right continuation.

---

## Deferred

- Implement `IDaoStreamWaitable` on `WebSocketStream` for push-based cross-process wakeup.
- Add `:dao/call-timeout` to request datoms (callee can enforce SLAs).
- Add `:dao/call-metadata` for tracing, audit, or capability tokens.
- Typed call-in/call-out streams (fixed-size datom layout for SIMD dispatch).
- Automatic bridge code generation from schema (Clojure spec, or dedicated DSL).
