### 1. Does the Owner's Ruling Change My Round-1 Position?

#### What Survives (and Is Validated by the Ruling)
- **The core structural diagnosis:** In Round 1, I argued that `yin.vm.engine.host.v1` exists only because the wasm boundary was cut *inside* the VM's inner loop (between compiled register bodies and `yin.vm.engine`), and that compiling the engine crates (`yin-value`, `yin-prims`, `yin-engine`, `yin-wire`) *into* the wasm instance (`node-lane`) dissolves the second ABI so the guest–host boundary literally becomes `dao.stream`. The owner's ruling now codifies this exact endpoint: *"a guest whose engine is compiled inside it needs only `dao.stream`."*
- **The critique of `yin.vm.engine.host.v1`'s TLV wire protocol:** My Round-1 objection that serializing registers into a 14-tag TLV wire format and crossing a host import on every `:load-free`, `:store-get`, and `(+ 1 2)` is pessimal survives — and becomes **even more damning** under the owner's ruling (see §2 and §4 below).

#### What I Would Now Revise
In Round 1, I treated the distinction between synchronous intra-step transitions (`:load-free`, primitive `+`, closure minting) and asynchronous `dao.stream` effects (`FFI`, stream I/O) as an *intrinsic ontological boundary* — arguing that intra-step operations "cannot in principle" be stream operations because they do not yield.

The owner's counter-argument (*"FFI already proves the two-step append-park-resume pattern works; the boundary is drawn, not found"*) is **mechanically correct** and forces a revision:
- **In pure operational semantics (ignoring cost and stream-log hygiene), any synchronous step `y = f(x)` in a machine with first-class continuations can be CPS-transformed into the FFI two-step:** append a request datom, park the continuation as data, let an external interpreter compute `f(x)` and append the response datom, and resume the continuation.
- Historical proof within the repo: contract v4 moved `:define` from a host service to a guest opcode; real ISAs put `+` inside the machine while Yin `r2` puts `+` in the primitive table. Where that line sits is indeed **drawn by the architect, not found in nature**.

**However**, two things at that seam are **not** reducible to "just performance," which brings us to the critique of the ruling itself.

---

### 2. Critique of the Ruling: Is "Performance Is the Sole Valid Justification for a Second ABI" Correct?

The ruling's core maxim — *"every addition to a process-local ABI is a cost to be justified, and every removal is progress toward the one-ABI state"* — is an outstanding architectural razor.

However, the claim that **performance (per-instruction crossing cost) is the *sole* valid justification for a second ABI** is **too narrow in two specific ways, and ironically refutes `yin.vm.engine.host.v1`**.

Let's test each candidate justification and separate what reduces to performance/cost from what is genuinely structural:

#### A. Two Genuine Non-Performance Reasons (When the Boundary Is Drawn Below the Engine)

1. **In-Machine Capability Custody vs. Canonical Wire Representation (`machine-data?` $\supsetneq$ `canonical-stream-data?`) — *Genuinely Structural (Not Performance)***
   Look at the paragraph immediately preceding the owner ruling in [`datom.world.md:84-88`](file:///Users/sto/workspace/datomworld/docs/design/datom.world.md#L84-L88):
   > *"First, what crosses a trust domain is a strict subset of what exists inside a process: closures and continuations cross only as portable plain data; host types, resources, and code artifacts never do — the custody gates mark exactly this seam (`yin.vm.linker.md`)."*

   By explicit repository contract (`engine/encoder` and `dao.jing.cbor`), **reified continuations, parked continuations, raw host primitives, and lone UTF-16 surrogates are non-canonicalizable** (`:yin.k/non-portable`, `:non-canonicalizable`). They are *forbidden* from crossing `dao.stream`.
   Yet inside a running machine, active registers and lexical frames routinely hold reified continuations and primitive capabilities. If you draw the boundary between `.wasm` bodies and `yin.vm.engine`, live registers holding those non-portable values *must* cross that boundary on calls, effects, and GC root scans.
   - That is **not** a performance issue: even if you had infinite CPU time and zero latency, `dao.jing.cbor` **refuses to encode** a reified continuation or primitive capability in a live register.
   - **Implication for the ruling:** If you split the machine below the engine, a non-`dao.stream` representation (`ValueRef` handles) is forced by **custody/representation semantics**, not just speed. Conversely, to achieve the One-ABI state (`dao.stream` only), the boundary **must** enclose the engine and value arena so non-portable in-machine capabilities never cross it.

2. **Broadcast Log Hygiene & Cross-VM Semantic Equivalence (`B0`) — *Genuinely Structural (Not Performance)***
   `dao.stream` is not a private point-to-point RPC pipe; it is a **multi-observer stigmergic medium** (*"no privileged reader; an event that is noise to one interpreter is signal to another"*), and Yin's four VMs (AST, semantic, stack, register) are bound by `B0`/stream equivalence: executing the same program `A` on any of the four VMs must produce the **same external stream history**.
   - If a split register-wasm VM emitted `:load-free`, register spills, or GC root materializations onto `dao.stream`, its stream history would diverge from the AST, semantic, and stack VMs and flood `dao.space` / replay observers with register-allocation implementation noise.
   - If you reply, *"Put those micro-ops on a private stream that only the owning engine may read and no observer may index,"* then that channel violates `no-privileged-reader` and `observers-are-independent` — it is a synchronous private RPC bus wearing stream syntax, not `dao.stream`.

#### B. Candidate Reasons That *Do* Reduce to Cost (or Argue *Against* a Second ABI)

| Candidate Justification | Verdict | Why |
|---|---|---|
| **Freeze-vs-extend of the ISA** (23 opcodes vs. open primitive table) | **Reduces to engineering cost (not a second-ABI reason)** | Deciding whether `+` or `:define` is an opcode vs. an engine primitive is an *internal* kernel/engine boundary choice. Once the engine and primitive table are compiled inside the wasm node, extending primitives never touches the outer `dao.stream` ABI. |
| **Capability / security granularity** | **Argues *against* a second ABI** | `dao.stream` + sealed references/ShiBi is already the capability model. A second ABI (`yin.vm.engine.host.v1`) *duplicates* authority checks (requiring tag-13 arena-handle validation and trusted-constructor gates alongside stream seals). |
| **Determinism & cross-embedder parity** | **Argues *against* a second ABI** | Putting the engine outside wasm forced the design to maintain *two* host engines (CLJS and Rust) and invent the `§E.4.3` deterministic map-traversal prerequisite so JS and Rust wouldn't diverge across `exchange`. Putting the engine *inside* wasm runs the exact same wasm bytes on Node, Browser, Wasmtime, and Wasmer. |
| **Failure isolation & independent versioning** | **Reduces to engineering cost** | Wasm linear-memory sandboxing isolates faults identically whether imports are `exchange` or `dao.stream`. Maintaining version compatibility across *two* ABIs (`dao.stream` + `yin.vm.engine.host.v1`) is strictly harder than across one. |

#### C. The Crucial Critique: Under the Ruling's Own "Performance" Criterion, `yin.vm.engine.host.v1`'s TLV Protocol Is Invalid
Look at what the ruling says:
> *"The sole valid justification for a second, process-local ABI is **performance**: the measured engineering cost of per-instruction boundary crossings."*

Now apply that test to `yin.vm.engine.host.v1`:
- Does serializing registers into a 14-tag TLV byte buffer, sorting map keys by recursively expanded canonical bytes (`§E.3.3`), calling `exchange(0)`, decoding TLV bytes on the host, and running a `NEED_SPACE`/`fetch-response` state machine on every `:load-free`, `:store-get`, and `(+ 1 2)` **improve per-instruction performance**?
- **No — it is catastrophic for per-instruction performance.** It is orders of magnitude slower than keeping `yin-value`/`yin-prims`/`yin-engine` inside wasm (`node-lane`), and slower than a host interpreter.
- Why was `yin.vm.engine.host.v1` actually designed? Not for runtime performance, but for **staged compiler bring-up convenience** (allowing `yang.wasm` to emit only the 23-opcode control skeleton while reusing `engine.cljc` on the CLJS host).
- **Even as a process-local performance shim, `yin.vm.engine.host.v1` has the wrong shape:** a process-local ABI justified by speed would pass `i32` `ValueRef` handles directly and read/write the fixed `u32[]` register shadow in shared linear memory with **zero byte serialization**. Inventing a canonical TLV wire format for an in-process per-instruction shim contradicts the only justification the ruling allows it to have.

---

### 3. Is the Floor Argument Sound, or Can Even the Floor Move?

The owner's floor argument states: *"The floor itself is not movable: operations whose answers are facts about the world cannot be computed by shipped code."*

We must distinguish **two floors** that look identical in a traditional OS process but separate cleanly in `datom.world`:

1. **The Epistemic / Causal Floor (Immovable):**
   A closed deterministic transition function cannot manufacture new external entropy (what packet arrives from a remote peer at time $t$, what a hardware sensor reads, what a human types). New facts from outside the causal cone of the machine's initial state must enter via `dao.stream`. That floor is mathematically immovable.

2. **The Wasm Host-Boundary Floor (Movable — and `datom.world` Moves It All the Time):**
   Does "cannot be computed by the guest instruction alone" mean "must cross a wasm host-import boundary at runtime"? **No!** Because in `datom.world`, "the world" is represented as **streams of datoms (`dao.stream`)**, and streams can live *inside* the wasm arena:
   - **Recorded / Replay / Deterministic Simulation Worlds:** Once world-facts have been deposited into a stream log, that log is pure `dao.jing` data. A wasm node loaded with a recorded stream segment (for time-travel debugging, conformance testing, speculative branching, or audit verification) executes "world-fact" stream reads **entirely inside wasm memory with zero host imports**.
   - **Co-Resident Interpreters in One Wasm Arena (Stigmergy Inside the Node):** Many "external" facts requested by a Yin task are answered by other `datom.world` interpreters — `dao.space` (`index`/`query`), macro expanders, ShiBi capability/metering interpreters, or sibling Yin tasks (`spawn-module`). If those interpreters also run inside the wasm node over in-memory `dao.stream` buffers, the Yin task still speaks pure `dao.stream` (append request, park, wake on response), **but the stream never crosses the wasm-to-host boundary**.
   - **Logical vs. Physical Facts:** Operations that POSIX treats as host world-facts (generating unique IDs via randomness/clocks) are already pulled above the floor in Yin via deterministic task state (`id-counter`, `:yin.k/issued`).

**Verdict on the floor:** `dao.stream` is indeed the immovable **semantic** floor, but the **physical wasm host-import surface** can shrink even further than per-effect crossings: only *live, unrecorded, external-to-the-node* stream transceivers need to cross the wasm host boundary.

---

### 4. Consequences for the Plan (W0–W6): Does Body-Lane-First Still Make Sense?

**No. With the owner's ruling in force, the current W0–W5 plan (`body-lane` + `yin.vm.engine.host.v1` TLV mailbox in W0–W5, deferring `node-lane` to a W6 experiment) is upside-down.**

Look at the contradiction in the current W0–W6 schedule:
1. To pass W1–W4 on Wasmtime and Wasmer, the current plan **already requires writing the entire shared Rust workspace (`yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, `yin-wire`) during W0–W4**.
2. Those Rust crates are pure, safe, single-threaded (`Rc`-based) Rust with no OS dependencies — meaning **they compile directly to `wasm32-unknown-unknown`**.
3. Yet the current plan puts those Rust crates *outside* wasm on the host side of Wasmtime/Wasmer, spends W0–W4 building a 14-tag TLV wire codec (`§E.3.3`), expanded-byte map sorting, and `NEED_SPACE` retry logic in **both** CLJS and Rust so wasm bodies can talk to `yin-engine` across `exchange`, and then in W6 proposes compiling those same Rust crates *into* wasm so `yin.vm.engine.host.v1` can be bypassed!

Under the owner's ruling (*"one ABI is the principle; every addition to a process-local ABI is a cost to be justified"*), spending five milestones building a third wire codec (`yin.vm.engine.host.v1` TLV) across two host languages — when its per-instruction crossing cost is guaranteed to be slower than interpretation and its destination (`node-lane`) makes that entire codec obsolete — cannot be justified.

#### What I Would Change in W0–W6

I would make **two decisive changes** to the W0–W6 plan:

1. **Commit to the Engine-in-Guest (`node-lane`) as the Wasm Architecture, or Reduce Any Split-Mode Shim to Unserialized `i32` Handles:**
   - **Primary path (Node-Lane from W0/W1):** Compile `yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, and `yin-wire` to `wasm32-unknown-unknown` as the **shared Wasm runtime/engine module**, sharing linear memory with the `yang.wasm`-compiled register body module(s).
     - **Result:** Node, Browser, Wasmtime, and Wasmer all instantiate the **exact same wasm engine + compiled body modules**.
     - The **only** host import ABI is `dao.stream` (depositing and polling canonical `dao.jing` CBOR datoms on external streams, plus canonical state snapshot read/write for test inspection).
   - **If a CLJS-hosted split mode (`body-lane`) is kept for W1/W2 bootstrap before `yin-engine` in Rust is complete:**
     - **Kill the `yin.vm.engine.host.v1` TLV wire codec (`§E.3.3`) anyway.** In a same-process split mode, the host already owns the `ValueRef` arena table and has direct `u32` typed-array access to wasm linear memory. Pass `i32` `ValueRef` handles and read/write the linear-memory register/frame shadow directly. Never serialize intra-step state into TLV wire bytes.

2. **Revised W0–W6 Milestones Under the Ruling:**
   - **W0 (Foundation & One-ABI Transport):**
     - **Delete** the third codec (`§E.3.3` 14-tag TLV framing, tag-13 wire compression, and private ABI expanded-byte map-key comparator). Keep only the two canonical repository codecs: `dao.jing` CBOR and `debruijn_register_code`.
     - Specify the **Wasm `dao.stream` transport ABI**: a minimal linear-memory ring/buffer import for external `dao.stream` `put`/`poll`/`make`/`close` carrying **canonical `dao.jing` CBOR bytes** (plus a test-inspection hook exporting the canonical/B0 or register-snapshot state).
     - Verify `yin-scalar`, `yin-image`, `yin-value`, and `yin-wire` (`dao.jing` CBOR + BLAKE3) compiled to `wasm32-unknown-unknown` running identically on **Node, Wasmtime, and Wasmer**.
   - **W1 (Pure Register Bodies + In-Wasm Kernel):**
     - `yang.wasm` lowers register bodies to wasm functions; `yin-kernel` + `yin-value` + `yin-prims` run inside the wasm instance (`wasm32-unknown-unknown`).
     - `:const`, `:load-bound`, `:load-free`, `:store-get`, `:store-put`, `:define`, `:closure`, `:call` (including all pure primitives), `:branch-false`, `:jump`, `:return`, `:halt`, and `:gensym` execute **100% inside wasm with zero host calls**.
   - **W2 (Continuations & Restore Inside Wasm):**
     - `:current-continuation`, `:park`, `:resume`, and `register-restore` execute inside wasm; admissible continuation payloads cross the outer boundary only as canonical data.
   - **W3 (Streams, Scheduler, Custody Gates & FFI over the One ABI):**
     - `yin-engine` scheduler (`wait-set`, `ready-queue`, `parked`, custody gates, `:cell/new` heap GC) runs inside wasm.
     - Intra-node streams stay in wasm memory; **external streams and FFI bridge requests cross the wasm host boundary exclusively as `dao.stream` datoms (`dao.jing` CBOR)** — realizing the owner's One-ABI principle in W3 instead of deferring it to W6.
   - **W4 (Dynamic Module Attachment, `spawn-module`, & Linker Slices):**
     - Per-image-row wasm component compilation and table installation; `lift-slice` and `receive-module` exchange canonical `dao.jing` module slices across `dao.stream`. Bring up Wasmer alongside Node and Wasmtime on the identical binaries.
   - **W5 (Release Gate):**
     - Full corpus parity across Node, Wasmtime, and Wasmer over the single `dao.stream` ABI.
   - **W6 (Measured Codegen & Representation Optimizations):**
     - Basic-block coalescing, structured control flow (`block`/`loop`/`if`), and unboxed scalar fast paths inside wasm — pure codegen optimization, with no ABI rewrite needed.
