### Position Summary

1. **Where I disagree with the framing of the question:**
   The boundary between a compiled `.wasm` module and `yin.vm.engine` in the current design is **not** a guest–host boundary in the sense of `datom.world` doctrine. In `datom.world`, the **guest** is the Yin program (`A` / `R`) and the **host** is the outer world of stream drivers, FFI bridges, and peer interpreters; between them sits the **Yin VM** (`yin.vm.debruijn.register` + `yin.vm.engine`).
   The current wasm design slices **the Yin VM itself in half**: control flow (`br_table`, `:move`, `:branch-false`, `:jump`, guest closure `:call`/`:return`) lives inside `.wasm`, while values, persistent collections, primitive arithmetic (`+`, `-`, `=`), `:load-free`, `:store-get`/`:store-put`, closure/continuation wrappers, GC root tracing, and the scheduler live outside `.wasm` in the embedder (`engine.cljc` or Rust `yin-engine`). Calling `yin.vm.engine.host.v1` a "guest–host ABI" disguises what it actually is: **an intra-VM split-brain RPC protocol**.

2. **Is a separate (non-`dao.stream`) ABI justified if the seam is placed between `.wasm` bodies and `yin.vm.engine`?**
   **Yes — for one indispensable reason:**
   > **`dao.stream` is a causal, append-only, multi-observer log of *effects* over *canonical plain data* (`dao.jing`), whereas the `.wasm ↔ engine` seam carries *synchronous, single-owner micro-architectural state transitions* over *non-canonical in-machine capabilities* inside a single atomic VM step.**

3. **Is `yin.vm.engine.host.v1` (`exchange` + TLV wire codec + `NEED_SPACE`) the right design?**
   **No.** It is an accident of putting the wasm boundary in the wrong place: it puts the boundary *inside* the inner loop of the VM (so even `(+ x 1)` or `:load-free` must cross the host import), and then dresses that internal memory seam in a heavyweight byte-framed wire protocol (inventing a third codec in the repository, canonical expanded-byte map sorting for private IPC, and a `NEED_SPACE`/`fetch-response` retry state machine).

---

### Detailed Analysis of the Five Points

#### 1. Synchronous intra-step services vs. `dao.stream`'s append-observe / `no-privileged-reader` model
In `dao.stream`, appending a value records an immutable fact in a shared log that any number of interpreters may observe; there is no synchronous call-and-return and no privileged reader.
Inside a single register instruction, however, the guest machine must perform private, synchronous, single-owner operations:
- **Lexical/environment/store resolution (`:load-free`, `:store-get`, `:store-put`, `:define`):** Reading a variable or mutating the active module store is an internal state transition of *this* machine instance, not a broadcast event to external observers.
- **Primitive application (`:call` of `+`, `<`, `assoc`):** The register ISA (`r2`) has **no arithmetic or collection opcodes** — every `+` is a `:call` to a host primitive. Turning every `(+ a b)` into a stream append + park + wake would require parking the machine on every arithmetic operation.
- **Mid-instruction GC root materialization (`:cell/new` inside `engine/handle-effect`):** When `:cell/new` triggers `engine/collect` *inside* `handle-effect`, the collector must synchronously inspect the active `:registers`, `:frames`, and `:continuation` *before* `handle-effect` finishes. That is a synchronous memory barrier, not a stream effect.

#### 2. Machine-internal traffic in stream history breaks cross-VM semantic equivalence (`B0`)
A foundational invariant of `datom.world` ([`yin.vm.semantic.md`](file:///Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md), [`yin.vm.debruijn.register.md`](file:///Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.register.md)) is that the AST walker, semantic VM, stack VM, and register VM are different mechanical realizations of the same computation: given the same program `A` and stream inputs, they produce **identical `B0` results and identical external `dao.stream` effect traces**.
- An AST walker has no register spills; a stack VM has no register file; a register VM has `L`/`T` slots and sparse live sets.
- If register reads, root materializations, or `resolve-var` lookups were emitted onto `dao.stream`, the stream log would record micro-architectural implementation noise specific to the register machine, destroying stream-trace equivalence across the four VMs and violating the rule that streams carry *semantic effects*, not internal CPU bus traffic.

#### 3. Value representation: in-machine fidelity vs. `dao.jing` canonical wire bytes
**The representation problem survives any transport redesign.**
- `dao.stream` and `dao.jing.cbor` operate on **canonicalizable portable data**. By explicit contract (`engine/encoder`), reified continuations, parked continuations, and raw host primitive capabilities are **refused** on canonical streams (`:yin.k/status :yin.k/non-portable`, `:kind :non-canonicalizable`), and lone UTF-16 surrogates are rejected by `dao.jing.cbor`.
- Inside a running machine, however, registers, lexical frames, and `K` routinely hold **reified continuations, closures, primitive function capabilities, and lone surrogates** (`vm/machine-data?` $\neq$ `plain-data?`).
- Whenever a running wasm body spills its live registers across the boundary (for GC root scanning, non-tail calls, or continuation capture), those live registers can legitimately contain a primitive capability or a reified continuation. A boundary restricted to `dao.jing` canonical stream bytes would **fail to serialize valid in-machine state whenever a live register holds a primitive or continuation**. An in-machine handle/capability representation (`ValueRef`s) is therefore irreducibly distinct from `dao.jing` wire encoding.

#### 4. Import-surface design: one generic `exchange` vs. `N` typed imports
Core wasm 1.0's restriction to `i32`/`i64`/`f32`/`f64` signatures (avoiding `externref`, JS objects, or Rust pointers across imports) is real and sound: using **`i32` `ValueRef` arena handles** for guest locals and linear-memory arrays is the right representation choice.
Where `yin.vm.engine.host.v1` goes wrong is collapsing all intra-VM operations into `exchange(control_ptr: i32) -> i32` over a **serialized TLV byte mailbox**:
- Only **6 of the 23 register opcodes** (`:const`, `:load-bound`, `:move`, `:branch-false`, `:jump`, and the guest-closure/continuation arms of `:call` + `:return`/`:halt`) stay inside wasm.
- The other 17 opcodes — **and every primitive `:call` like `+` or `=`** — must exit the body function, serialize the execution snapshot into TLV bytes (including sorting map keys by recursively expanded canonical value bytes!), invoke `exchange(0)`, decode TLV bytes on the host, encode the response into TLV bytes (with a `NEED_SPACE` + `fetch-response` retry loop), and re-import the state into wasm linear memory.
- That gives you the worst of both worlds: thewasm module is not standalone (80% of the VM still lives in the host), yet every primitive operation pays the cost of a full wire-protocol serialization round-trip.

#### 5. Is the shim load-bearing doctrine or an accident of starting from wasm?
The shim (`yin.vm.engine.host.v1`) is **an accident of where the wasm cut was made**.
It happened because we started from *"compile register bytecode `R` into wasm functions, while leaving `engine.cljc` and `values.cljc` in the host"* — which forced the wasm module boundary to cut right through the middle of the VM's execution loop. Once you cut the VM in half, you are forced to invent a private intra-VM shim (`yin.vm.engine.host.v1`) and explain why it isn't `dao.stream`.

---

### What I Would Build Instead

There are two architecturally clean designs, depending on whether the engine runs inside or outside wasm. **Option 1 is strictly superior** and makes the guest–host ABI literally `dao.stream`.

#### Option 1 (Recommended): Compile the Engine (`yin-value` + `yin-prims` + `yin-engine`) *into* Wasm; the outer Wasm ABI *is* `dao.stream`
Notice that the current plan already requires building the shared Rust crates (`yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, `yin-wire`) for W1–W4. Those pure-Rust crates (`Rc`-backed 16-byte `Value`, persistent collections, primitive profiles, scheduler, heap GC, `dao.jing` CBOR codec) **compile directly to `wasm32-unknown-unknown`**.

If you compile the runtime/engine crates into the wasm instance (sharing wasm linear memory with the compiled register bodies):
1. **All 23 opcodes, all pure primitives (`+`, `-`, `=`, `assoc`), all lexical/store operations, closure/continuation minting, heap GC (`:cell/new`), and the `engine` scheduler (`wait-set`, `ready-queue`, `parked`) run 100% inside wasm** with direct memory access and zero host boundary crossings.
2. **The only thing that crosses the wasm module boundary to the embedder (Node, Browser, Wasmtime, Wasmer) is `dao.stream`**:
   - A small set of stream-transport imports (`stream_put`, `stream_poll`, `stream_make`, `stream_close`) exchanging **canonical `dao.jing` CBOR bytes** (plus optional whole-VM snapshot export/import for host inspection).
3. **What this eliminates immediately:**
   - **Eliminates `yin.vm.engine.host.v1` (`exchange`, `NEED_SPACE`, `fetch-response`)** as an intra-step RPC protocol.
   - **Eliminates the third codec** (the 14-tag TLV codec and its custom expanded-byte comparator). Only the two real repository codecs (`dao.jing` CBOR and `debruijn_register_code`) exist.
   - **Eliminates CLJS vs. Rust host-service drift on wasm** — Node, Browser, Wasmtime, and Wasmer run the *exact same* compiled wasm engine + guest bodies, rather than maintaining two separate host-service adapters (one in CLJS, one in Rust) behind a TLV wire protocol.
   - **Aligns 100% with doctrine:** the wasm boundary *is* a real host boundary, and the only contract that crosses it is `dao.stream`.

*(Note: [`yang.wasm.md`](file:///Users/sto/workspace/datomworld/docs/design/yang.wasm.md#L500) §6.1 mentions "native-to-wasm migration of value/scheduler services and only stream/FFI ABI crossings" as a late W6 experiment. Treating that as a W6 afterthought means spending W0–W5 building and verifying a complex throwaway TLV mailbox protocol across two host languages just to tear it down when crossing cost inevitably dominates.)*

#### Option 2 (If CLJS must remain the direct in-memory engine without a wasm-compiled engine): Direct `i32`-Handle Typed Imports Over Shared Linear Memory (No TLV Wire Protocol)
If the goal is specifically to let the existing CLJS `engine.cljc` (and separately Rust `yin-engine`) drive compiled wasm body functions *without* compiling the engine into wasm, then treat the `.wasm` module honestly as a **private bytecode-dispatch accelerator** inside the host VM, not a serialized message bus:
- Keep `i32` `ValueRef` handles in wasm locals and the linear-memory active register shadow (`u32[]`).
- Replace the serialized `exchange` TLV mailbox with **direct, typed `i32`-handle imports** matching the kernel's actual internal transitions:
  - `resolve_var(state_ptr, name_ref) -> val_ref`
  - `store_get(state_ptr, key_ref) -> val_ref` / `store_put(state_ptr, key_ref, val_ref)`
  - `mint_closure(state_ptr, arity, body_pc_lo, body_pc_hi, frames_ptr) -> val_ref`
  - `call_host(state_ptr, fn_ref, args_ptr, argc, site_pc_lo, site_pc_hi, live_ptr, flags) -> status_i32`
  - `effect_op(state_ptr, opcode, a_ref, b_ref, site_pc_lo, site_pc_hi, live_ptr) -> status_i32`
- Because the host already holds the `ValueRef → Value` table in memory and has direct `u32` array views into the wasm instance's linear-memory register/frame shadow, **no TLV byte serialization, no canonical map-key byte-sorting comparator, and no `NEED_SPACE`/`fetch-response` retry loop is ever needed** across the `.wasm ↔ engine` seam.
