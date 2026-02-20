# Stream Design

## Core Model

Streams are append-only logs with external cursors. Reads are non-destructive: cursors advance independently, data remains in the log. Streams are independent of DaoDB. DaoDB is an interpreter that consumes streams and builds indexes.

## Architecture

Three layers, each with a single responsibility:

```
dao.stream            pure data functions (stream + cursor)
dao.stream.storage    pluggable storage protocol (in-memory first)
yin.stream            VM effect descriptors + handlers (bridges dao.stream to CESK machine)
yin.vm.ast-walker     scheduler (run-queue + wait-set), drives resumption
```

`dao.stream` knows nothing about the VM. `yin.stream` knows nothing about scheduling. The AST walker owns the step loop and scheduler.

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

The AST walker VM has two new fields:

- **run-queue**: vector of runnable continuations `[{:continuation k, :environment env, :value v}]`
- **wait-set**: vector of parked continuations `[{:continuation k, :environment env, :reason :next|:put, :stream-id id, ...}]`

The eval loop:

1. Step active computation until it halts or parks.
2. On park (`:next` blocked or `:put` full): capture continuation, add to wait-set.
3. When blocked: check wait-set entries against current store. Move newly runnable entries to run-queue.
4. Resume from run-queue head. Repeat.
5. Halted when: run-queue empty and no active computation. Blocked when: run-queue empty and wait-set non-empty.

### Close semantics

Closing a stream finds all wait-set entries parked on that stream's cursors, moves them to the run-queue with value `nil`.

### Continuation types

Stream operations that evaluate sub-expressions use continuation frames:

- `:eval-stream-put-target` / `:eval-stream-put-val` for `stream/put`
- `:eval-stream-cursor-source` for `stream/cursor`
- `:eval-stream-next-cursor` for `stream/next`

## Design Decisions

1. **Streams are built on continuations.** The CESK machine's native primitive. No core.async, no callbacks.
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
| `src/cljc/yin/vm/ast_walker.cljc` | Scheduler, CESK transitions for stream continuations |
| `test/dao/stream_test.cljc` | Storage, stream, cursor, and VM integration tests |
| `test/yin/vm/ast_walker_test.cljc` | Cursor-based stream tests within AST walker |

## Deferred

- Typed streams (schema as datoms, fixed-size layouts, columnar SoA)
- Bounding (closing a stream at a transaction, producing a stable database value)
- Eviction (bounded retention, `:daostream/gap` signal)
- Cross-language adapters (Clojure seq, JS async iterator, etc.)
- DaoDB as stream interpreter (consuming streams to build EAVT/AEVT indexes)
