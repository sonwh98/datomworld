# Yin REPL Design

## Overview

The **Yin REPL** is an interactive command-line shell for the Yin VM ecosystem. It operates at the **datom level**, treating everything as data. Users can:

1. Submit raw datom streams `[[e a v t m] ...]` directly to a VM for execution
2. Submit AST maps `{:type :application ...}` directly
3. Write source code (Clojure/Python/PHP) which is compiled to datoms via the Yang compiler
4. Execute shell commands as ordinary function calls: `(vm :register)`, `(telemetry)`, `(quit)`

The Yang compiler is a **tool within the shell**, not the shell itself. Users choose the input modality based on their need.

**Supported platforms:** JVM (Clojure), Node.js (ClojureScript), ClojureDart  
**Branch:** `repl`

---

## Philosophy

**Everything is data.** The shell reads Clojure/Python/PHP source code and translates it to a universal AST, which becomes a datom stream. The VM executes datoms. Users see all three representations and can work at the level they need.

**Explicit execution models.** VMs are not hidden. Users can inspect and control which VM is active, watch telemetry, and connect remotely over WebSockets.

**Functions as commands.** Shell commands (`vm`, `lang`, `compile`, `quit`, etc.) are not special syntax. They are ordinary Clojure function calls that the shell intercepts and dispatches before evaluating.

---

## Architecture

```
src/cljc/yin/repl.cljc           — Portable core (all business logic)
src/clj/yin/repl_main.clj        — JVM entry point (stdin I/O)
src/cljs/yin/repl_main.cljs      — Node.js entry point (readline I/O)
src/cljd/yin/repl_main.cljd      — ClojureDart entry point (dart:io)
test/yin/repl_test.cljc          — Portable test suite
```

The **portable core** (`src/cljc/yin/repl.cljc`) contains all shell logic:
- Input parsing and dispatch
- VM lifecycle management
- Datom/AST/source evaluation
- Remote shell connections
- Telemetry streaming
- WebSocket server loop

**Platform entry points** handle only:
- Reading input lines (stdin on JVM, readline on Node.js, dart:io on Dart)
- Printing output
- Command-line flag parsing
- Platform-specific async/threading

---

## Shell State

The shell maintains a state atom:

```clojure
{:vm-type         :semantic              ; VM backend (semantic | register | stack | ast-walker)
 :lang            :clojure               ; Input language (clojure | python | php)
 :vm              <vm-instance>          ; Current VM instance (store persists across evals)
 :telemetry-stream nil | <dao.stream>    ; Stream for VM telemetry datoms
 :remote-stream   nil | <dao.stream>}    ; WebSocket connection to remote shell
```

Each eval preserves `:vm` state (its `:store` accumulates `yin/def` bindings), creating a natural REPL session.

---

## Input Dispatch

When the user enters input, the shell parses it as EDN and dispatches:

1. **Shell commands** — function calls with head `vm`, `lang`, `compile`, `reset`, `connect`, `disconnect`, `telemetry`, `help`, `quit`
   - Example: `(vm :register)` → switches to RegisterVM
   - Example: `(telemetry)` → toggles telemetry to stderr
   - Example: `(quit)` → exits

2. **Remote delegation** — if `:remote-stream` is set, forward the input string to the remote shell via WebSocket
   - Remote shell evaluates and returns result

3. **Raw datom stream** — if input is `[[e a v t m] ...]`
   - Directly execute via `vm/load-program` + `vm/run`

4. **AST map** — if input is `{:type :application ...}`
   - Directly execute via `vm/eval`

5. **Source code** — default (Clojure by default, configurable via `(lang :python)`)
   - Compile via Yang compiler → AST → `vm/eval`

---

## Core Functions

### `eval-input [state input-str]` → `[new-state result-str]`

Main entry point. Parses input and dispatches to the appropriate evaluation path.

### `eval-datoms [state datoms]` → `[new-state result-str]`

Execute a raw datom stream. Calls `vm/load-program` with `{:node root-id :datoms datoms}` and `vm/run`.

### `eval-ast [state ast-map]` → `[new-state result-str]`

Execute an AST map. Calls `vm/eval` which internally translates AST → datoms.

### `compile-source [lang input-str]` → `[ast-map error-msg]`

Compile source code to AST via Yang compiler:
- `:clojure` → `yang.clojure/compile-program` (EDN reader)
- `:python` → `yang.python/compile`
- `:php` → `yang.php/compile`

### `handle-command [state form]` → `[new-state print-msg]`

Dispatch shell commands:

| Command | Effect |
|---------|--------|
| `(vm :register)` | Switch to RegisterVM (creates fresh, warns about lost store) |
| `(lang :python)` | Switch input language to Python (no VM change) |
| `(compile expr)` | Compile expression to AST + datoms; print them; do NOT execute |
| `(reset)` | Create fresh VM of same type |
| `(connect "daostream:ws://host:port")` | Connect to remote shell; forward evals |
| `(disconnect)` | Close remote connection; resume local eval |
| `(telemetry)` | Toggle telemetry to stderr |
| `(telemetry "daostream:ws://host:port")` | Stream telemetry to remote WebSocket |
| `(help)` | Print help text |
| `(quit)` | Exit shell |

### `make-vm [vm-type telemetry-stream]` → `<vm>`

Create a fresh VM instance. If `telemetry-stream` is non-nil, wire the VM to emit `[e a v t m]` datoms to that stream on every step, park, resume, halt, and effect.

### `enable-telemetry! [state-atom sink]`

Start telemetry streaming.
- `sink = :stderr` — background loop reads datoms from telemetry stream, pretty-prints to stderr
- `sink = "daostream:ws://..."` — relay datoms to remote WebSocket

### `serve! [state-atom port]`

Expose the shell as a WebSocket server. Accept remote clients and dispatch `:op/eval`, `:op/vm`, `:op/lang`, `:op/reset` requests via `dao.stream.apply` protocol.

---

## Shell Commands

### `(vm :register | :semantic | :stack | :ast-walker)`

Switch to a different VM backend. Warns that `:store` state is lost and creates a fresh VM.

```clojure
yin> (vm :register)
Switched to RegisterVM (store cleared)
yin> (def x 10)
nil
yin> x
10
```

### `(lang :clojure | :python | :php)`

Switch the input language for source code compilation. Does not affect the current VM.

```clojure
yin> (lang :python)
Switched to Python
yin> 1 + 2
3
```

### `(compile expr)`

Compile an expression to AST and datoms without executing. Useful for inspecting what the compiler generates.

```clojure
yin> (compile (+ 1 2))
AST:
{:type :application
 :operator {:type :variable :name +}
 :operands [{:type :literal :value 1}
            {:type :literal :value 2}]}

Datoms:
[[-1024 :yin/type :application 0 0]
 [-1024 :yin/operator -1025 0 0]
 [-1025 :yin/type :variable 0 0]
 [-1025 :yin/name + 0 0]
 [-1024 :yin/operands [-1026 -1027] 0 0]
 [-1026 :yin/type :literal 0 0]
 [-1026 :yin/value 1 0 0]
 [-1027 :yin/type :literal 0 0]
 [-1027 :yin/value 2 0 0]]
```

### `(reset)`

Reset the current VM to a fresh state.

```clojure
yin> (def x 42)
nil
yin> x
42
yin> (reset)
SemanticVM reset
yin> x
; Unbound variable: x
```

### `(connect "daostream:ws://remote-host:port")`

Connect to a remote Yin REPL via WebSocket. All subsequent evals are forwarded to the remote.

```clojure
yin> (connect "daostream:ws://localhost:7777")
Connected to ws://localhost:7777
yin> (+ 1 2)
3
yin> (def x 100)
nil
; x is defined on remote shell, not locally
```

### `(disconnect)`

Close the remote connection and resume local evaluation.

```clojure
yin> (disconnect)
Disconnected from remote shell
yin> x
; x is unbound locally (only defined on remote)
```

### `(telemetry)` and `(telemetry "daostream:ws://...")`

Enable or disable VM telemetry. Telemetry emits `[e a v t m]` datom snapshots of the VM's CESK state at each step, park, resume, halt, and effect.

```clojure
yin> (telemetry)
Telemetry enabled (stderr)
yin> (+ 1 2)
3
; Stderr receives datom snapshots from the eval
[root-id :vm/type :vm/snapshot 0 0]
[root-id :vm/model :semantic 0 0]
[root-id :vm/phase :step 0 0]
...

yin> (telemetry "daostream:ws://monitor:9000")
Telemetry routed to ws://monitor:9000
```

### `(help)`

Print shell command reference.

### `(quit)`

Exit the shell.

---

## Input Modalities

### 1. Clojure Source Code (Default)

```clojure
yin> (+ 1 2)
3
yin> (def fib (fn [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))))
nil
yin> (fib 10)
55
```

### 2. AST Maps

Submit a universal AST map directly:

```clojure
yin> {:type :literal :value 42}
42
yin> {:type :application
      :operator {:type :variable :name +}
      :operands [{:type :literal :value 1} {:type :literal :value 2}]}
3
```

### 3. Datom Streams

Submit raw `[e a v t m]` tuples:

```clojure
yin> [[-1 :yin/type :literal 0 0]
      [-1 :yin/value 99 0 0]]
99
```

### 4. Other Languages (Python, PHP)

Switch language and write source:

```clojure
yin> (lang :python)
Switched to Python
yin> 1 + 2
3
yin> for i in range(3):
      print(i)
0
1
2

yin> (lang :php)
Switched to PHP
yin> 1 + 2
3
```

---

## WebSocket Server Mode

Start the shell as a headless WebSocket server:

```bash
clj -M:repl --port 7777 --headless
```

Remote clients connect and send requests via `dao.stream.apply`:

```clojure
{:dao.stream.apply/id   "req-123"
 :dao.stream.apply/op   :op/eval
 :dao.stream.apply/args ["(+ 1 2)"]}
```

Server responds:

```clojure
{:dao.stream.apply/id    "req-123"
 :dao.stream.apply/value "3"}
```

---

## Telemetry

When telemetry is enabled, the VM emits `[e a v t m]` datom snapshots to a `dao.stream`. Each snapshot captures the full CESK state:

```clojure
[root-id :vm/type       :vm/snapshot 0 0]
[root-id :vm/vm-id      :semantic-vm-1 0 0]
[root-id :vm/model      :semantic 0 0]
[root-id :vm/step       123 0 0]
[root-id :vm/phase      :step 0 0]
[root-id :vm/blocked?   false 0 0]
[root-id :vm/halted?    false 0 0]
[root-id :vm/control    control-summary-eid 0 0]
[root-id :vm/env        env-summary-eid 0 0]
[root-id :vm/store      store-summary-eid 0 0]
[root-id :vm/k          k-summary-eid 0 0]
[root-id :vm/value      result-value 0 0]
...
```

Snapshots are emitted at phases: `:init`, `:step`, `:effect`, `:park`, `:resume`, `:blocked`, `:halt`, `:bridge`.

Telemetry can be consumed:
- **To stderr** — pretty-printed datom groups appear on stderr in real-time
- **Over WebSocket** — datoms relayed to a remote monitoring service

---

## Implementation Notes

### Portable Core

All shell logic lives in `.cljc` so it compiles on all platforms. No platform-specific I/O.

### VM State Persistence

Each `eval` preserves the `:vm` state. The VM's `:store` field is mutable in the sense that `vm/eval` returns an updated VM with accumulated bindings. By threading the VM through evals, the shell naturally implements a REPL session.

### Error Handling

Parse errors, compile errors, and eval errors return `[state unchanged, error-msg]`. The state is never corrupted by a user error.

### Remote Connection

When `:remote-stream` is set, the shell becomes a thin client. It forwards raw input strings to the remote over WebSocket. The remote parses, compiles, and evals, returning the result string. This preserves the remote's `:store` state transparently.

### Telemetry Streaming

Telemetry and normal eval are concurrent. A background loop reads from `:telemetry-stream` and either prints or forwards. The VM's emissions don't block normal eval.

---

## Command-Line Interface

### JVM

```bash
clj -M:repl [FLAGS]

Flags:
  --port <n>                   Start WebSocket server on port
  --telemetry                  Enable telemetry to stderr
  --telemetry-stream <url>     Stream telemetry to remote WebSocket
  --headless                   Skip CLI loop (server-only mode)
```

### Node.js

```bash
npx shadow-cljs run yin.repl-main.node/-main [FLAGS]

(Same flags as JVM)
```

### ClojureDart

```bash
clj -M:cljd compile      # Compile to Dart
dart run bin/yin_repl_main.dart [FLAGS]

(Same flags as JVM)
```

---

## Testing

All shell logic is tested in portable `.cljc` tests:

```bash
clj -M:test -n yin.repl-test
```

Tests cover:
- Input dispatch (datoms, AST, source, commands)
- State persistence
- VM switching
- Telemetry emission
- Remote shell connection
- WebSocket server requests

---

## References

- **VM Architecture:** `docs/agents/architecture.md`
- **Datom Spec:** `docs/agents/datom-spec.md`
- **Yang Compiler:** `src/cljc/yang/clojure.cljc`
- **DAO Stream:** `src/cljc/dao/stream.cljc`
- **VM Telemetry:** `src/cljc/yin/vm/telemetry.cljc`
