# Stream Design

## Core Model

Streams are append-only logs with external cursors. Reads are non-destructive: cursors advance independently, data remains in the log. Streams are independent of DaoDB. DaoDB is an interpreter that consumes streams and builds indexes.

## Architecture

Three layers, each with a single responsibility:

```
dao.stream            pure data functions (stream + cursor)
dao.stream.storage    pluggable storage protocol (in-memory first)
yin.stream            VM effect descriptors + handlers (bridges dao.stream to CESK machine)
yin.vm.*              scheduler (run-queue + wait-set), drives resumption
```

`dao.stream` knows nothing about the VM. `yin.stream` knows nothing about scheduling. Each VM backend (ast-walker, register, stack, semantic) owns its step loop and scheduler.

## Data Structures

### Stream

```clojure
{:storage <IStreamStorage>   ;; pluggable backend
 :capacity nil|int           ;; nil = unbounded
 :closed false}
```

### Cursor

```clojure
{:stream-ref {:type :stream-ref, :id <keyword>}
 :position int}
```

Cursors are values. Multiple cursors on the same stream advance independently. A cursor does not own or modify the stream.

### Storage Protocol

```clojure
(defprotocol IStreamStorage
  (append   [this val])     ;; -> updated storage
  (read-at  [this pos])     ;; -> value or nil
  (length   [this]))        ;; -> int
```

Current backend: `MemoryStorage` (vector-backed). Storage is pure: `append` returns a new value, no mutation.

## API

### Pure functions (`dao.stream`)

All functions take data, return data. No side effects.

| Function | Signature | Returns |
|---|---|---|
| `make` | `[storage & {:keys [capacity]}]` | stream map |
| `put` | `[stream val]` | `{:ok stream'}` or `{:full stream}` |
| `close` | `[stream]` | closed stream |
| `closed?` | `[stream]` | boolean |
| `length` | `[stream]` | int |
| `cursor` | `[stream-ref]` | cursor at position 0 |
| `next` | `[cursor stream]` | `{:ok val, :cursor cursor'}`, `:blocked`, `:end`, or `:daostream/gap` |
| `seek` | `[cursor pos]` | cursor at position |
| `position` | `[cursor]` | int |

`put` throws on closed streams. `:full` is returned (not thrown) when at capacity, so the VM can park the putting continuation.

`next` returns one of four values:
- `{:ok val, :cursor cursor'}` when data is available
- `:blocked` at the end of an open stream (no data yet)
- `:end` at the end of a closed stream
- `:daostream/gap` when the position was evicted (future, once eviction exists)

### VM effect descriptors (`yin.stream`)

Client code calls these. They return effect descriptor maps that the VM interprets.

```clojure
(stream/make 10)        ;; {:effect :stream/make, :capacity 10}
(stream/put! s 42)      ;; {:effect :stream/put, :stream s, :val 42}
(stream/cursor s)       ;; {:effect :stream/cursor, :stream s}
(stream/next! c)        ;; {:effect :stream/next, :cursor c}
(stream/close! s)       ;; {:effect :stream/close, :stream s}
```

No `take!`. Reads go through cursors.

### AST node types

The VM also supports stream operations as AST nodes (used by the compiler):

```clojure
{:type :stream/make, :buffer 10}
{:type :stream/put, :target <ast>, :val <ast>}
{:type :stream/cursor, :source <ast>}
{:type :stream/next, :source <ast>}
```

Both paths (effect descriptors from module calls, AST nodes from compiled code) converge on the same `yin.stream/handle-*` functions.

## VM Integration

### Scheduler

Every VM backend (ast-walker, register, stack, semantic) has two fields:

- **run-queue**: vector of runnable continuations
- **wait-set**: vector of parked continuations waiting on streams

The captured state differs per backend (ast-walker captures `{:continuation k, :environment env, :value v}`, register captures `{:pc, :regs, :k, :env, ...}`, stack captures `{:pc, :stack, :env, :call-stack, ...}`) but the scheduling protocol is the same:

1. Step active computation until it halts or parks.
2. On park (`:next` blocked or `:put` full): capture continuation, add to wait-set.
3. When blocked: check wait-set entries against current store. Move newly runnable entries to run-queue.
4. Resume from run-queue head. Repeat.
5. Halted when: run-queue empty and no active computation. Blocked when: run-queue empty and wait-set non-empty.

All backends delegate to the same `yin.stream/handle-*` functions for stream operations. The VM-specific part is how the continuation is captured and restored.

### Close semantics

Closing a stream finds all wait-set entries parked on that stream's cursors, moves them to the run-queue with value `nil`.

### Continuation types (ast-walker specific)

The ast-walker uses explicit continuation frames for stream operations that evaluate sub-expressions:

- `:eval-stream-put-target` / `:eval-stream-put-val` for `stream/put`
- `:eval-stream-cursor-source` for `stream/cursor`
- `:eval-stream-next-cursor` for `stream/next`

The register and stack VMs do not need these frames; they evaluate operands into registers or onto the stack before executing the stream opcode.

## Design Decisions

1. **Stream synchronization is built on continuations.** Streams are append-only logs with cursors (pure data, `dao.stream`). Blocking, parking, and resumption use the CESK machine's native continuation capture. No core.async, no callbacks.

   A stream is a vector you can append to and read from with a cursor. That is all `dao.stream` is. Pure functions on plain maps. No concurrency, no blocking.

   The interesting part happens when a cursor hits the end of an open stream. `ds/next` returns `:blocked`, a keyword. It does not wait, it does not park, it does not know what a continuation is. It just says "nothing here yet" and returns.

   The VM is where blocking becomes real. When the ast-walker (or register VM, or stack VM) sees `:blocked`, it captures the current CESK continuation (the program counter, environment, registers/stack, and the continuation chain) and puts it in the wait-set. The computation is now parked. The VM moves on to the next runnable entry in the run-queue.

   Later, when something puts a value into that stream, the scheduler scans the wait-set, finds continuations parked on cursors for that stream, moves them to the run-queue, and resumes them with the new value.

   Same for `put` on a full stream: `ds/put` returns `{:full stream}`, the VM captures the continuation, parks it, resumes it when capacity opens up. Same for `close`: the VM finds all continuations parked on that stream's cursors, resumes them with `nil`.

   The stream does none of this. The stream is a log. The continuation capture is what turns "nothing here yet" into "wait until something arrives."

2. **Streams are independent of DaoDB.** DaoDB consumes streams. Streams do not depend on indexes.
3. **Storage protocol is abstract.** In-memory first. Other backends plug in without changing stream or cursor logic.
4. **Append-only log + external cursors.** Reads are non-destructive.
5. **All stream/cursor functions are pure.** Parking and scheduling are the VM's concern.
6. **put can park.** Symmetric with next parking on empty. Capacity enforcement returns `:full`, VM decides to park.
7. **Scheduling and data are decoupled.** Puts do not trigger computation. The VM scheduler drives resumption by polling the wait-set.
8. **Cursor gap = signal.** When a cursor falls behind eviction, return `:daostream/gap` (not yet implemented, eviction is deferred).

## Files

| File | Role |
|---|---|
| `src/cljc/dao/stream.cljc` | Pure stream + cursor functions |
| `src/cljc/dao/stream/storage.cljc` | Storage protocol + MemoryStorage |
| `src/cljc/yin/stream.cljc` | VM effect descriptors + handlers |
| `src/cljc/yin/vm.cljc` | empty-state with run-queue/wait-set, ast->datoms for stream nodes |
| `src/cljc/yin/vm/engine.cljc` | Shared scheduler utilities across all VM backends |
| `src/cljc/yin/vm/ast_walker.cljc` | AST walker: scheduler, CESK transitions for stream continuations |
| `src/cljc/yin/vm/register.cljc` | Register VM: scheduler, stream opcodes + bytecode execution |
| `src/cljc/yin/vm/stack.cljc` | Stack VM: scheduler, stream opcodes + bytecode execution |
| `src/cljc/yin/vm/semantic.cljc` | Semantic VM: scheduler, stream operations |
| `test/dao/stream_test.cljc` | Storage, stream, cursor, and VM integration tests |
| `test/yin/vm/ast_walker_test.cljc` | Cursor-based stream tests within AST walker |

## Channel Mobility (Streams as Values)

Streams are first-class values that can be sent through other streams. This is the pi-calculus channel mobility property: a channel name can be transmitted over a channel, enabling dynamic topology.

Concretely: a `{:type :stream-ref, :id <keyword>}` map put into stream A arrives intact when read from A via cursor. The receiver can then create a cursor on the recovered ref and read from it. No special marshalling or registration is required, because `put`, `append`, and `next` impose no type restrictions on values.

This enables patterns where:

- A coordinator stream distributes work streams to consumers at runtime.
- Streams of streams model hierarchical or evolving topologies.
- "Reply channels" can be sent alongside requests, as in the pi-calculus.

The property holds at both layers:

- **`dao.stream`**: pure data functions pass values through via `conj`/`read-at` with no type inspection.
- **`yin.stream` / AST walker**: effect handlers and the CESK machine forward values unchanged. `handle-put` stores the value, `handle-next` returns it, no intermediate inspection.

## Deferred

- Typed streams (schema as datoms, fixed-size layouts, columnar SoA)
- Bounding (closing a stream at a transaction, producing a stable database value)
- Eviction (bounded retention, `:daostream/gap` signal)
- Cross-language adapters (JS async iterator, etc.), Clojure seq implemented via `dao.stream/->seq`
- DaoDB as stream interpreter (consuming streams to build EAVT/AEVT indexes)
