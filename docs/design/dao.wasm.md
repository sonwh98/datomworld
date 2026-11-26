# dao.wasm: Embedded WebAssembly Guest Bridge for Jolt via `dao.stream`

Status: design, owner-directed planning; not implemented. Subordinate to
[datom.world.abi.md](./datom.world.abi.md), [dao.stream.md](./dao.stream.md),
and [dao.stream.wasm.md](./dao.stream.wasm.md). Where they disagree, the
abstraction contract and ABI doctrine win.

## Authorities

- [datom.world.md](./datom.world.md): foundational axioms ("everything is a stream", data is syntax, capability-governed compute).
- [datom.world.abi.md](./datom.world.abi.md): one abstraction boundary (`dao.stream`), custody at the boundary, effects out and events in.
- [dao.stream.md](./dao.stream.md): the `dao.stream` operations, exhaustive outcome maps, cursor discipline, and unknown-outcome rule.
- [dao.stream.wasm.md](./dao.stream.wasm.md): the 7-function `dao.stream` Wasm import surface (`create`, `attach`, `descriptor`, `cursor`, `next`, `append`, `close`) and canonical Jing CBOR buffer protocol.
- [dao.jing.cbor.md](./dao.jing.cbor.md): canonical CBOR encoding and validation across trust boundaries.
- [dao.lease.md](./dao.lease.md): `Shibi` fuel-metered capability governance and bounded execution leases.
- [yang.wasm.md](./yang.wasm.md): `yin.vm` Wasm compilation strategies (Gambit C backend route and register-bytecode lowering).

---

## 1. Motivation and Architectural Role

Chez Scheme lacks a native WebAssembly code generation backend, and while
compiling the entire `yin.vm` + Jolt stack to WebAssembly (via Chez `pb32l` +
`pbchunk-convert-file` or Gambit's C backend to Emscripten/`wasi-sdk`) is viable
for browser or standalone guest deployments, native `datom.world` nodes already
run Jolt directly on Chez Scheme (`jolt-toolchain.edn`).

Inverting the relationship—**embedding a WebAssembly runtime (`libwasmer` via the
Wasm C API) inside Jolt on Chez Scheme**—turns Jolt into the native
`datom.world` host and makes WebAssembly a universal, sandboxed guest execution
lane:

1. **Zero Scheme-to-Wasm Porting for the Host:** Jolt, `yin.vm`, `dao.space`,
   `dao.jing`, and existing qualified native facades (`native/jolt-crypto`,
   `native/jolt-file`, `native/jolt-parser`) remain native on Chez Scheme.
2. **Universal Wasm Ecosystem Bridge:** The embedded Wasmer runtime executes both
   `yang.wasm`-emitted register modules and third-party WebAssembly modules
   compiled from Rust, C, C++, Zig, or Go.
3. **Algebraic Effects as `dao.stream`:** External Wasm guests interact with the
   `datom.world` ecosystem exclusively through `dao.stream` algebraic effects.
   No POSIX, WASI, or ad-hoc host FFI surface is exposed. The 7 `dao.stream`
   imports specified in [dao.stream.wasm.md](./dao.stream.wasm.md) are the
   complete effect vocabulary.
4. **Fills the Retired Host-Adapter Role:** This design replaces the retired
   Rust host-adapter role (`yin.vm.rust.md` §6.4, retired 2026-11-17) with a
   native Jolt/Chez host implementation over `libwasmer`.

---

## 2. Strategic Landscape: Running `yin.vm` and Wasm Together

This document establishes Strategy A (Host-Embedded Wasmer Bridge) as the
primary mechanism for integrating the WebAssembly ecosystem with Jolt on Chez
Scheme, while preserving compatibility with whole-stack or register-compiled
guest lanes:

| Strategy | Host Runtime | Guest Payload | Boundary ABI | Best Fit |
| :--- | :--- | :--- | :--- | :--- |
| **A. Jolt embeds Wasmer (`dao.wasm`, this spec)** | Jolt on native Chez Scheme (`10.4.1`) | Third-party Wasm (Rust/C/Zig/Go) or `yang.wasm` binaries | `dao.stream` 7-import surface via `libwasmer` C API | Native `datom.world` nodes hosting polyglot Wasm plugins/actors via algebraic effects |
| **B. Gambit C Backend → Wasm ([yang.wasm.md](./yang.wasm.md))** | Browser, Node, Wasmtime, or Wasmer | Whole `yin.vm` + Jolt portable runtime compiled via Gambit → C → Wasm | `dao.stream` 7-import surface | Running the full `yin.vm` interpreter inside an external Wasm host |
| **C. Chez `pb32l` + `pbchunk` → Wasm** | Browser, Node, or WASI runtime | Chez portable bytecode kernel + C-chunked Jolt boot files | `dao.stream` 7-import surface | Running Chez-hosted Jolt inside Wasm without porting off Chez FFI |
| **D. Register Bytecode → Wasm ([yang.wasm.md](./yang.wasm.md) §§2–6)** | Any Wasm 1.0 host (including Strategy A's embedded Wasmer) | 23 `yin.vm` register opcodes lowered to Wasm body functions + trampoline | `dao.stream` 7-import surface | Minimal-footprint, stackless `yin.vm` compute kernels |

Notice that **Strategy A and Strategy D/B compose cleanly**: a native Jolt node
using `dao.wasm` (Strategy A) can host both third-party Wasm modules and
`yang.wasm`-compiled `yin.vm` guests under the exact same `dao.stream` import
surface.

---

## 3. Architecture of the `dao.wasm` Host Bridge

```
+-----------------------------------------------------------------------+
| Jolt on Chez Scheme (Native Host)                                     |
|                                                                       |
|  +---------------+   +---------------+   +-------------------------+  |
|  |   dao.space   |   |   dao.jing    |   | Shibi / dao.lease       |  |
|  | Tuple Space   |   |  CBOR / BTree |   | Fuel & Capability Gate  |  |
|  +-------+-------+   +-------+-------+   +------------+------------+  |
|          |                   |                        |               |
|          +-------------------+------------------------+               |
|                              |                                        |
|                     +--------v--------+                               |
|                     |   dao.stream    |                               |
|                     | Host Scheduler  |                               |
|                     +--------+--------+                               |
|                              |                                        |
|             Chez FFI (jolt.ffi / foreign-callable)                    |
|             Locked entry points + Task handle table                   |
+------------------------------+----------------------------------------+
                               |
+------------------------------v----------------------------------------+
| native/jolt-wasm (C Wrapper around libwasmer / wasm_c_api)            |
|                                                                       |
|  - Owns wasm_engine_t, wasm_store_t, wasm_instance_t, wasm_memory_t   |
|  - Validates (p, n) and (out, cap) bounds against wasm_memory_data()  |
|  - Bridges wasm_func_new callbacks to locked Chez foreign-callables   |
|  - Enforces Wasmer memory limits and deterministic fuel metering      |
+------------------------------+----------------------------------------+
                               |
             Wasm Import Module: "dao.stream" (i32 only)
      create | attach | descriptor | cursor | next | append | close
                               |
+------------------------------v----------------------------------------+
| WebAssembly Guest Instance (wasm32 linear memory, non-shared)         |
|                                                                       |
|  Option 1: Third-Party Guest (Rust / C / Zig / Go)                    |
|            + Guest-side dao.stream Algebraic Effect Library           |
|  Option 2: yang.wasm Compiled Guest (yin.vm Register Trampoline)      |
+-----------------------------------------------------------------------+
```

### 3.1 The Native Wrapper (`native/jolt-wasm`)

Following the established pattern of `native/jolt-crypto` and `dao.host.hash`:
- `native/jolt-wasm` is a small, auditable C wrapper linked against a pinned,
  qualified `libwasmer` (`wasmer-c-api`) declaration in `jolt-toolchain.edn`.
- Rather than constructing complex `wasm_val_vec_t` structures across Chez FFI on
  every stream operation, `native/jolt-wasm` registers C trampolines with
  `wasm_func_new_with_env` for each of the 7 `dao.stream` imports.
- Each C trampoline extracts the raw `i32` parameters `(p, n, out, cap)` (or
  `(handle, ...)`) from `wasm_val_vec_t`, validates pointer/length bounds and
  disjointness against the instance's `wasm_memory_data(mem)` and
  `wasm_memory_data_size(mem)`, and invokes a single locked Chez
  `foreign-callable` with direct host pointers `(task_id, op_code, handle,
  in_ptr, in_len, out_ptr, out_cap) -> written_len`.
- Because `wasm_memory_data(mem)` is only dereferenced during the synchronous
  host call and never retained after return, zero-copy views never outlive
  linear-memory growth ([dao.stream.wasm.md](./dao.stream.wasm.md) §1).

### 3.2 The 7 `dao.stream` Imports as Algebraic Effect Handlers

Per [dao.stream.wasm.md](./dao.stream.wasm.md) §1, every guest module imports
only from module `"dao.stream"`. All arguments and results are `i32`, where `p, n`
address one canonical Jing CBOR request in guest linear memory, `out, cap`
address a disjoint writable response buffer in guest linear memory, and the
return value is the **encoded response byte length**:

| Import | Signature | Jolt Host Action |
| :--- | :--- | :--- |
| `create` | `(p, n, out, cap) -> len` | Decode Jing CBOR creation spec, check `Shibi` capability/lease, invoke `dao.stream/create!`, allocate task-local `i32` handle slot, encode outcome map into `(out, cap)`. |
| `attach` | `(p, n, out, cap) -> len` | Decode portable stream descriptor, check capability admission, invoke `dao.stream/attach!`, allocate task-local `i32` handle slot, encode outcome map. |
| `descriptor` | `(handle, out, cap) -> len` | Look up task-local `handle`, invoke `dao.stream/descriptor`, encode canonical descriptor outcome map. |
| `cursor` | `(handle, p, n, out, cap) -> len` | Decode anchor from `(p, n)`, invoke `dao.stream/cursor`, encode cursor value outcome map. |
| `next` | `(handle, p, n, out, cap) -> len` | Decode cursor from `(p, n)`, perform **non-blocking** `dao.stream/next`, encode `ok`, `blocked`, `end`, `gap`, `cursor-mismatch`, `invalid-cursor`, `refused`, or `transport-error`. |
| `append` | `(handle, p, n, out, cap) -> len` | Decode canonical Jing value from `(p, n)`, perform **non-blocking** `dao.stream/append!`, encode `ok`, `full`, `invalid-value`, `closed`, `refused`, or `transport-error`. |
| `close` | `(handle, out, cap) -> len` | Look up task-local `handle`, invoke `dao.stream/close!`, encode `ok` outcome map. |

### 3.3 Why Everything-is-a-Stream Eliminates Host Stack / `call/cc` Hazards

Chez Scheme's Foreign Interface has two hard rules when mixing C and Scheme
([Chez Scheme User's Guide, Ch. 4](https://cisco.github.io/ChezScheme/csug9.5/foreign.html)):
1. Any `foreign-callable` code object passed to C must be pinned in memory with
   `lock-object` so Chez's generational copying GC does not relocate it while
   `libwasmer` holds the function pointer.
2. Invoking a Scheme continuation (`call/cc`) across nested `foreign-procedure`
   → `libwasmer` → `foreign-callable` C stack frames does not unwind or restore
   the C/Wasm stack pointer and corrupts subsequent re-entry.

The `dao.stream.wasm.md` contract eliminates Hazard #2 by design:
- **Every `dao.stream` import is strictly synchronous and non-blocking at the
  host FFI boundary.**
- When a stream has no data ready (`next`) or a bounded stream is at capacity
  (`append`), the Jolt `foreign-callable` handler **never parks the OS thread
  and never invokes a Scheme continuation across Wasmer**. Instead, it encodes
  the `:blocked` or `:full` outcome map in Jing CBOR into `(out, cap)` and
  returns the byte length immediately to the Wasm guest.
- Control returns cleanly through the Wasm guest's exported step/poll entrypoint
  back to Jolt's host scheduler with **zero C or Wasm frames retained**.
- When Jolt's `dao.stream.waitset` observes that the stream is readable or
  writable (or a new event has arrived), Jolt calls back into the Wasm instance's
  exported step/wake entrypoint.

---

## 4. Guest-Side Algebraic Effect Library for Third-Party Wasm

Because `dao.stream` is the universal effect boundary ("everything is a stream"),
third-party languages (Rust, C, Zig, Go) do not need language-specific host
bindings in Jolt. Instead, a thin guest-side `dao.stream` algebraic-effect SDK
is compiled into the guest `.wasm` module.

### 4.1 Higher-Order Effects Over `dao.stream`

In `datom.world`, higher-level interactions (`dao.space` Datalog queries, `Shibi`
lease negotiations, RPC/FFI requests, logging, clock/timer reads, and UI/network
events) are not separate Wasm host imports. They are **effects out and events
in** over streams ([datom.world.abi.md](./datom.world.abi.md) §6):

1. **Performing an Effect (`perform`):** The guest library encodes an effect
   value (a canonical Jing map or `[e a v t m]` datom batch with a correlation
   ID) into linear memory and calls `dao.stream/append` on its outbound effect
   stream handle.
2. **Observing the Outcome (`resume`):** The guest library calls
   `dao.stream/next` with its immutable cursor on its inbound outcome stream
   handle.

### 4.2 Guest Suspension Strategies (No Host Coupling)

When `dao.stream/next` returns `:blocked` or `dao.stream/append` returns `:full`,
the guest SDK bridges the asynchronous wait back to the Jolt host scheduler
using one of three guest-internal strategies—none of which require changes to
Jolt's 7 host imports:

1. **Explicit Poll / State-Machine Loop (`wasi_reactor`-style):**
   The guest exports `dao_guest_init()` and `dao_guest_step()`. On `:blocked` or
   `:full`, the guest records the pending cursor/handle in guest memory and
   returns a `WAIT` status from `dao_guest_step()`. Jolt registers the stream
   handle/cursor in its host wait-set and calls `dao_guest_step()` again only
   when the stream advances.
2. **Guest-Local Stackless Coroutines / Rust `Future` Executor:**
   In Rust (`no_std` + `alloc`) or C++20/Zig, the guest SDK provides a minimal
   single-threaded executor inside Wasm linear memory. Calling
   `stream.next(cursor).await` or `perform!(effect).await` polls
   `dao.stream/next`; on `:blocked`, the `Future` returns `Poll::Pending`, the
   guest executor unwinds to the top of `dao_guest_step()`, and returns control
   to Jolt.
3. **Binaryen Asyncify (for Direct-Style C / Legacy Guests):**
   For third-party C/C++ code written in direct blocking style without `async` or
   coroutines, an optional load-time or compile-time Binaryen Asyncify pass
   transforms the guest Wasm module so that a `:blocked` outcome saves the
   guest's Wasm locals into a reserved linear-memory unwind buffer and returns to
   Jolt, rewinding transparently on the next `dao_guest_step()`.

---

## 5. Custody, Capability Governance (`Shibi`), and Determinism

Embedding Wasmer inside Jolt enforces strict zero-trust isolation aligned with
`Shibi` and `dao.lease`:

1. **Capability-Gated Stream Handles:**
   A Wasm guest starts with **zero** ambient authority. Its initial capabilities
   are passed solely as portable stream descriptors (or pre-attached task-local
   handle slots `0..k`) authorized by the spawning `dao.lease` contract. Any
   `create` or `attach` request outside the task's granted `Shibi` capability
   set returns a canonical `:refused` outcome map—never a host exception or crash.
2. **Task-Isolated Handle Namespace:**
   Operational stream handles returned by `create`/`attach` are indices into a
   task-local handle vector owned by Jolt (`0..max-handles`). They cannot be
   forged, guessed across tasks, or serialized into stream payloads
   ([dao.stream.wasm.md](./dao.stream.wasm.md) §1).
3. **`Shibi` Fuel Metering and Memory Caps:**
   `native/jolt-wasm` configures Wasmer with deterministic instruction metering
   and a hard `wasm_memorytype_t` maximum pages limit derived from the guest's
   `Shibi` token and `dao.lease` budget. Exhausting fuel returns a structured
   host yield/lease-exhausted status to Jolt without terminating the Chez Scheme
   process.
4. **Trap Isolation:**
   Any Wasm trap (out-of-bounds memory access, integer division by zero, stack
   overflow, unreachable instruction, or invalid adapter buffer pointer) is
   caught by `wasm_func_call` inside `native/jolt-wasm` and translated into a
   qualified Jolt fault map (`{:dao.wasm/fault :wasm-trap ...}`). A misbehaving
   guest never aborts Chez Scheme.

---

## 6. Toolchain & Provenance Integration (`jolt-toolchain.edn`)

To satisfy the repository's strict native dependency and provenance discipline
(`jolt-toolchain.edn`, patterned after `:libcrypto` and `:blake3`):

1. **Toolchain Pinning:**
   Declare `:wasmer` under `:native` in `jolt-toolchain.edn` with exact version,
   platform archive SHA-256 (`darwin-arm64`, `linux-x86_64`), and compiler
   backend configuration (Cranelift or Singlepass for fast deterministic startup
   and metering).
2. **Wrapper Layout & Provenance Gate (`native/jolt-wasm/jolt_wasm.c`):**
   Export a narrow, versioned C surface:
   - `jolt_wasm_layout_version()`
   - `jolt_wasm_provenance(buf, cap)` (verifying resolved `libwasmer` path and
     SHA-256 via `dladdr` before any module is compiled)
   - `jolt_wasm_engine_new(config)` / `jolt_wasm_engine_free(engine)`
   - `jolt_wasm_task_new(engine, wasm_bytes, wasm_len, max_pages, fuel_limit, callback_fn_ptr, task_id)`
   - `jolt_wasm_task_step(task, fuel_delta, out_status)`
   - `jolt_wasm_task_free(task)`
3. **Jolt Host Namespace (`src/jolt/dao/wasm.jolt`):**
   - Verifies `jolt_wasm_layout_version` and `jolt_wasm_provenance` at namespace
     load time; refuses to load on any digest or version mismatch.
   - Allocates and pins the single multiplexed `foreign-callable` dispatcher via
     Chez's `lock-object` once at initialization.
   - Maps `(task_id, op_code, handle, in_ptr, in_len, out_ptr, out_cap)` to
     `dao.stream.cbor` decode → `dao.stream` protocol operation → `dao.stream.cbor`
     encode into `out_ptr`.

---

## 7. Phased Implementation Plan

### Phase W-H0: `libwasmer` Qualification & `native/jolt-wasm` Skeleton
- Add `:wasmer` pin and platform SHA-256 digests (`darwin-arm64`, `linux-x86_64`)
  to `jolt-toolchain.edn`.
- Implement `native/jolt-wasm/jolt_wasm.c` with `jolt_wasm_layout_version`,
  `jolt_wasm_provenance`, and a smoke test that instantiates a minimal Wasm
  module and traps cleanly on invalid memory access.
- Wire `bb build:jolt-native` to build `libjolt_wasm.{dylib,so}`.

### Phase W-H1: Chez `foreign-callable` Bridge & 7 `dao.stream` Imports
- Implement `src/jolt/dao/wasm.jolt` with a `lock-object`-pinned
  `foreign-callable` entry point and per-task handle table.
- Wire all 7 `dao.stream` imports (`create`, `attach`, `descriptor`, `cursor`,
  `next`, `append`, `close`) with strict `(p, n)` and `(out, cap)` disjoint
  bounds validation in `jolt_wasm.c` and canonical Jing CBOR decoding/encoding
  in Jolt.
- Verify buffer discipline and exhaustive outcome maps against the
  [dao.stream.wasm.md](./dao.stream.wasm.md) conformance cases (`ok`, `blocked`,
  `full`, `end`, `closed`, `refused`, `invalid-value`, `invalid-cursor`).

### Phase W-H2: Host Wait-Set Scheduler & `Shibi` Fuel Metering
- Integrate `dao.wasm` guest tasks with `dao.stream.waitset`: when a guest's
  `next` returns `:blocked` or `append` returns `:full` and the guest yields from
  `jolt_wasm_task_step`, register the task in the host wait-set and resume on
  stream readiness.
- Enforce `Shibi` fuel budgets and memory page limits per task step.

### Phase W-H3: Guest Algebraic-Effect SDK & `yang.wasm` Interop
- Provide a reference `no_std` Rust/C guest library (`dao-stream-wasm-guest`)
  wrapping the 7 `dao.stream` imports into ergonomic `perform` / `next` /
  `append` algebraic effect primitives.
- Run an end-to-end integration test where a compiled Wasm guest exchanges
  `[e a v t m]` datoms and algebraic effects with `yin.vm` and `dao.space`
  running natively on Jolt/Chez.
