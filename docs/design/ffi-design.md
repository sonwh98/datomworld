# dao.stream.apply Bridge Design: Datom Streams as the Universal Application Interface

## Context

Foreign Function Interface (FFI) and cross-process Remote Procedure Calls (RPC) are unified in Yin.VM via the `dao.stream.apply` protocol. Traditional FFI crosses the host/VM boundary via imperative function calls, which introduces marshaling tax and tight coupling. Yin.VM replaces this with datom streams. The VM emits request datoms to a `call-in` stream; a bridge dispatcher reads them, invokes host functions, and writes responses to a `call-out` stream.

The design rests on three principles:

- **Everything is a stream.** `call-in` and `call-out` are always streams. Whether backed by an in-process ring buffer or a cross-process socket is a transport detail hidden in the stream implementation.
- **No multiplexing on top of multiplexing.** The VM's park/resume scheduler already assigns unique IDs to parked continuations. The parked continuation ID is used as the `:dao.stream.apply/id` for response routing.
- **Platform-agnostic.** All engine changes are pure Clojure data operations and `IFn` calls, working identically on clj, cljs, cljd, and jank.

---

## Architecture

```
                        VM State
                 ┌──────────────────────────┐
                 │ :bridge {:handlers {...} │
                 │          :cursor   {...}}│
                 │ :store                   │
                 │   :yin/call-in           │──► stream (request)
                 │   :yin/call-in-cursor    │──► read head for request (bridge)
                 │   :yin/call-out          │◄── stream (response)
                 │   :yin/call-out-cursor   │◄── read head for response
                 └──────────────────────────┘

:dao.stream.apply/call executes:
  1. evaluate operands
  2. park continuation  →  :parked-0
  3. register reader-waiter on :yin/call-out at current cursor position
  4. stream/put :yin/call-in  {:dao.stream.apply/id   :parked-0
                                :dao.stream.apply/op   :op/name
                                :dao.stream.apply/args [val1 val2]}
  5. run-loop continues with next runnable continuation (no block)

Bridge Dispatcher (Independent process or stepped by engine):
  1. ds/next from :yin/call-in using bridge cursor
  2. if request:
     a. result = (apply (get handlers op) args)
     b. ds/put to :yin/call-out  {:dao.stream.apply/id    :parked-0
                                   :dao.stream.apply/value result}
  3. advance bridge cursor
```

---

## Scheduling

`:dao.stream.apply/call` is a non-blocking operation from the VM's perspective. The continuation parks and registers a waiter on the response stream. The VM's `run-loop` remains free to execute other continuations.

The continuation is unblocked when a matching response arrives on `call-out`. If the stream is an in-process `RingBufferStream`, the `put!` from the bridge wakes the waiter immediately via `IDaoStreamWaitable`. If the stream is remote (e.g. WebSocket), the `check-wait-set` idle handler polls the stream and resumes the continuation when data is available.

---

## New AST Node: `:dao.stream.apply/call`

```clojure
;; Map form — stored in DaoDB, queryable via Datalog
{:type     :dao.stream.apply/call
 :op       :op/name         ; namespaced keyword = bridge dispatcher opcode
 :operands [node1 node2]}   ; argument expressions (evaluated before call)
```

```
;; Datom form [e a v t m]
[e :yin/type     :dao.stream.apply/call  t m]
[e :yin/op       :op/name                t m]
[e :yin/operands [e1 e2]                 t m]
```

The node is explicit and queryable. Datalog can enforce capability policies such as "no continuation tagged `:untrusted` may emit to `:sys/*`".

---

## New Opcode: `:dao.stream.apply/call 21`

The instruction set remains bounded. New host capabilities are added by registering keywords in the bridge handler map, not by adding VM opcodes.

---

## Files and Changes

### `src/cljc/yin/vm.cljc`

- **`schema`**: includes `:yin/op {}`.
- **`opcode-table`**: includes `:dao.stream.apply/call 21`.
- **`ast->datoms`**: handles `:dao.stream.apply/call` node.
- **`empty-state`**: initializes `call-in` and `call-out` streams in the store.

### `src/cljc/yin/vm/host_ffi.cljc` (Bridge implementation)

Provides `bridge-step` to read from `call-in` and write to `call-out`. Provides `maybe-run` to wrap VM execution with bridge stepping.

### `src/cljc/yin/vm/semantic.cljc`, `stack.cljc`, `register.cljc`

Each engine implements the `park-and-call` logic: park, register waiter on `call-out`, put request to `call-in`.

### `src/cljc/yang/clojure.cljc`

Compiles `(dao.stream.apply/call :op/name ...)` to a `:dao.stream.apply/call` AST node.

---

## VM Setup

```clojure
(create-vm {:bridge {:op/echo identity
                     :op/add  +}})
```

The `create-vm` call can also take explicit `:call-in` and `:call-out` stream descriptors to override the default in-memory ring buffers.

---

## Bridge Dispatcher Contract

```clojure
;; call-in request value:
{:dao.stream.apply/id   :parked-0
 :dao.stream.apply/op   :op/name
 :dao.stream.apply/args [val1 val2]}

;; Response value on call-out:
{:dao.stream.apply/id    :parked-0
 :dao.stream.apply/value result}
```

---

## Verification

1. `ast->datoms` on `:dao.stream.apply/call` node: Correct attributes present.
2. VM Execution: `(dao.stream.apply/call :op/echo 42)` returns `42` after bridge step.
3. Concurrent: Multiple continuations route responses via distinct IDs correctly.
4. Transport: Replacing ring buffers with WebSockets works without VM logic changes.
