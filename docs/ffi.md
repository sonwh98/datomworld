# FFI Design: Datom Streams as the Foreign Function Interface

## Context

Traditional FFI crosses the host/VM boundary via imperative function calls. This
introduces a marshaling tax, synchronous blocking, and tight coupling between the VM
and the host runtime. Yin.VM replaces this with a datom stream. The VM emits request
datoms to `ffi-out`; a bridge dispatcher reads them and invokes native functions.

The design rests on three principles:

- **One model.** `ffi-out` is always a stream. Whether backed by an in-process ring
  buffer or a cross-process socket is a transport detail hidden in the stream
  implementation. No separate code paths.
- **No multiplexing on top of multiplexing.** The VM's park/resume scheduler already
  assigns unique IDs to parked continuations. The parked-id is the response routing
  address. No call-id correlation layer is needed.
- **Platform-agnostic.** All engine changes are pure Clojure data operations and `IFn`
  calls, working identically on clj, cljs, cljd, and jank. There is no sync/async
  distinction to handle: when `:ffi/call` executes, the continuation is already parked.
  Whether the native handler completes in nanoseconds or via a Promise callback is
  irrelevant — the continuation waits until `resume` is called.

---

## Architecture

```
                        VM State
                 ┌─────────────────────┐
                 │ :bridge-dispatcher  │  {:op/name (fn ...) ...}
                 │ :primitives         │
                 │ :store              │
                 │   ::ffi-out         │──► stream (ring buffer / socket)
                 │   ::ffi-out-cursor  │◄── read head
                 └─────────────────────┘

:ffi/call executes:
  1. evaluate operands
  2. park continuation  →  :parked-0
  3. stream/put ::ffi-out  {:op :op/name
                             :args [val1 val2]
                             :parked-id :parked-0}
  4. run-loop continues with next runnable continuation (no block)

engine/run-loop (idle — run-queue and wait-set exhausted):
  check-ffi-out  →  reads ::ffi-out-cursor (non-blocking)
                    if item: dispatch via :bridge-dispatcher
                             resume :parked-0 → into run-queue
  check-wait-set →  (existing stream unblocking)
```

---

## Scheduling

`:ffi/call` is just a park — no different from `:vm/park`. The VM does not block when
a continuation makes an FFI call. The run-loop continues with the next runnable
continuation from the run-queue. Multiple continuations can have in-flight FFI calls
simultaneously.

The VM only becomes idle when the run-queue and wait-set are both exhausted. At that
point `check-ffi-out` runs as the idle handler:

- Non-blocking read from `::ffi-out-cursor`.
- If an item is ready: call `(apply (get bridge-dispatcher op) args)` to get the result,
  then `engine/resume-continuation` with the parked-id and result, putting the
  continuation back into the run-queue.
- If nothing is ready, return the blocked VM to the caller.

This is symmetric with `check-wait-set`: both are idle handlers that attempt to unblock
parked continuations when the VM has nothing else to do.

---

## New AST Node: `:ffi/call`

```clojure
;; Map form — stored in DaoDB, queryable via Datalog
{:type     :ffi/call
 :op       :op/name         ; namespaced keyword = bridge dispatcher opcode
 :operands [node1 node2]}   ; argument expressions (reuses :yin/operands)
```

```
;; Datom form [e a v t m]
[e :yin/type     :ffi/call  t m]
[e :yin/op       :op/name   t m]   ; new attribute
[e :yin/operands [e1 e2]    t m]   ; existing cardinality-many ref
```

The node is explicit and queryable. Datalog can enforce capability policies such as
"no continuation tagged `:untrusted` may emit to `:sys/*`".

---

## New Opcode: `:ffi-call 21`

The op keyword is a constant pool entry, not an opcode. Adding new native capabilities
adds keywords to the constant pool. The instruction set stays bounded.

---

## Files and Changes

### `src/cljc/yin/vm.cljc`

- **`schema`**: add `:yin/op {}`.
- **`opcode-table`** and **`opcase`** macro: add `:ffi-call 21`.
- **`ast->datoms`**: add `:ffi/call` case emitting `:yin/type`, `:yin/op`,
  `:yin/operands`.
- **`empty-state`**: add `:bridge-dispatcher` field (default `{}`).

### `src/cljc/yin/vm/engine.cljc`

New `check-ffi-out` function (parallel to `check-wait-set`): non-blocking read from
`::ffi-out-cursor`; if a request is ready, dispatch via `:bridge-dispatcher` and
resume the parked continuation into the run-queue.

Update `run-loop` idle branch to call `check-ffi-out` alongside `check-wait-set`.

### `src/cljc/yin/vm/ast_walker.cljc`

Add `:ffi/call` case in `cesk-transition`: evaluate operands via the existing
`eval-operand` continuation chain, then park the continuation and `stream/put` the
request to `::ffi-out`.

### `src/cljc/yin/vm/stack.cljc`

- **`ast-datoms->asm`**: emit `[:ffi-call op argc]` after compiling operand nodes.
- **Bytecode**: `[:ffi-call op argc]` → `[21 pool-idx argc]`.
- **`step` `opcase :ffi-call`**: pop argc args, park, put request to `::ffi-out`.

### `src/cljc/yin/vm/register.cljc`

- **`ast-datoms->asm`**: emit `[:ffi-call rd op arg-regs]` after compiling operands.
- **`step` `opcase :ffi-call`**: read arg registers, park, put request to `::ffi-out`.

### `src/cljc/yin/vm/semantic.cljc`

Add `:ffi/call` case: resolve operand datoms, park, put request to `::ffi-out`.

### `src/cljc/yang/clojure.cljc`

Add `ffi/call` special form: `(ffi/call :op/name arg1 arg2)` compiles to a `:ffi/call`
AST node. Add `'ffi/call` to `special-form?` set and a `compile-ffi-call` branch in
`compile-form`.

---

## VM Setup

```clojure
(create-vm {:bridge-dispatcher {:op/echo   identity
                                 :op/add    +}})
```

`create-vm` sets up `::ffi-out` stream and `::ffi-out-cursor` in the store. No
separate thread. No wrapper function. The engine loop handles dispatch when idle.

---

## How to use `ffi/call`

`ffi/call` always follows the same flow:

1. Register host handlers in `:bridge-dispatcher`.
2. Compile or construct a `:ffi/call` AST node.
3. Load program into a VM and run.
4. Read the resumed value from `vm/value`.

### Clojure example

```clojure
(require '[yang.clojure :as yang]
         '[yin.vm :as vm]
         '[yin.vm.ast-walker :as ast-walker])

(defn compile-and-run
  [form vm-opts]
  (-> (ast-walker/create-vm vm-opts)
      (vm/load-program (yang/compile form))
      (vm/run)
      (vm/value)))

(compile-and-run
  '(ffi/call :op/echo 42)
  {:bridge-dispatcher {:op/echo identity}})
;; => 42
```

### Python example

Use `ffi.call("op/name", ...)` in Python source. The first argument is the FFI op key
as a string and is compiled to a keyword:

```clojure
(require '[yang.python :as py]
         '[yin.vm :as vm]
         '[yin.vm.ast-walker :as ast-walker])

(-> (ast-walker/create-vm
      {:bridge-dispatcher {:op/echo identity}})
    (vm/load-program (py/compile "ffi.call(\"op/echo\", \"hello from python\")"))
    (vm/run)
    (vm/value))
;; => "hello from python"
```

If no handler exists for the op keyword, the VM throws `ex-info` with `{:op ...}`.

---

## Bridge Dispatcher Contract

```clojure
;; ffi-out request value:
{:op        :op/name     ; dispatch key
 :args      [val1 val2]  ; evaluated runtime values
 :parked-id :parked-0}   ; continuation to resume with result

;; Dispatcher entry:
{:op/name (fn [val1 val2] result)}
```

New native capabilities are added by registering new keywords in the dispatcher map.
No new opcodes are required.

---

## Verification

1. `ast->datoms` on `:ffi/call` node: `:yin/type`, `:yin/op`, `:yin/operands` present.
2. AST walker: `create-vm` with `{:op/echo identity}`, eval `(ffi/call :op/echo 42)` → `42`.
3. Stack VM: `ast-datoms->asm` → `[:ffi-call :op/echo 1]` in assembly; result `42`.
4. Register VM: same.
5. Yang: `(ffi/call :op/echo 42)` compiles to `:ffi/call` node.
6. Concurrent: two continuations each calling `:op/echo` resume via distinct parked-ids.
7. No handler: missing op → `ex-info` with `:op`.
8. Transport: replace in-memory stream with socket-backed stream — no VM code changes.
