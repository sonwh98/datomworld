# yang.wasm: compiler implementation plan — register bytecode to WebAssembly

Status: design, owner-authorized planning; not implemented.

Consensus provenance (2026-10-18): gpt-6-astra, author; gemini-4-argon,
reviewer; five review rounds, APPROVE on r5. Author thread:
`01a14f30-ca56-7530-bb93-3a870eca7a7b`; reviewer thread:
`f365ef1d-caa2-4379-8e96-51e64ca36af8`. This specification distills the
compiler half of the r5 consensus, subject to the owner supersession below: semantic
fidelity, portability, determinism and Rust-kernel reconciliation. It
does not report an implementation or runtime acceptance result.

**Owner supersession, 2026-10-19 (boundary clarification):** this is the
compiler implementation plan for register bytecode → wasm. The earlier
body-lane-first architecture and `yin.vm.engine.host.v1` service target
(historically renamed from `yin.register.host.v1`) are superseded. Compile the
shared Rust engine crates into the instance; imports are only the dao.stream
implementation specified in [dao.stream.wasm.md](./dao.stream.wasm.md) §§1–3.
The historical service-protocol and taxonomy rulings have one home in
[datom.world.abi.md](./datom.world.abi.md) §§2–5. Engine-in-guest is mandatory,
not a W6 experiment. Owner decomposition, 2026-10-19: this compiler plan now
references the extracted doctrine and stream implementation specs.

The 2026-10-19 decomposition remains: engine representation/conformance
method live in [yin.vm.engine.md](./yin.vm.engine.md) §E; register state,
fixtures and acceptance gates in [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md)
§10. This plan owns compiler mechanics, not host service implementation.
Retired standalone wasm/kit documents remain retired. Unsuperseded r5 semantic
laws, opcode rows, fixture obligations and artifact rules remain binding.

## Authorities

- [datom.world.md](./datom.world.md): foundational axioms and invariants.
- [yang.antlr.md](./yang.antlr.md): frontend compilation into the Universal AST.
- [yin.vm.code-as-tuples.md](./yin.vm.code-as-tuples.md): Universal AST content and projection discipline.
- [yin.vm.semantic.md](./yin.vm.semantic.md): A identity, semantic execution and A→R derivation.
- [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md): register contract, live sets, sparse continuations, and (`§10`) the register dimension's wasm-lane material (the eight materialized register fields, sparse continuations on wasm, `register-restore`, register fixture schema, and register-specific W0–W6 / Rust K1–K5 gates).
- [yin.vm.linker.md](./yin.vm.linker.md): admission, offset tables and module transport.
- [yin.vm.engine.md](./yin.vm.engine.md): scheduler, effects, custody, and (`§E.1–§E.5`) the in-instance engine, reference to the wasm stream implementation, and K0/W0 conformance method.
- [yin.vm.rust.md](./yin.vm.rust.md): shared Rust crates, Value ownership, transition reuse and LLVM relationship.

Source precedence and the explicit Rust-design reconciliations below are
retained from r5.

---

## 1. Pipeline position, decision and source precedence

Pipeline position:

    yang source --(yang.antlr)--> Universal AST --(project)--> semantic vector A
        --(yin.vm.semantic/derive register-image)--> register image R
        --(yang.wasm, this spec)--> WebAssembly module (disposable local artifact)

**A is the only code identity.** R is the register image checksum. The
emitted WebAssembly module is a derived, disposable local execution
artifact with no identity in `dao.jing`, no content address, and no
presence in continuation payloads or module manifests.

Implement a **compiled register backend with resumable body functions and an explicit
trampoline**. Each declared body becomes a wasm function with one local per physical register.
Intra-body control initially uses generated `br_table` dispatch; guest calls and returns update
explicit machine state and return to the trampoline. No guest continuation ever depends on a
wasm activation. Link the shared Rust engine crates into the instance;
compiled bodies use direct internal calls and shared linear memory (§4).
Only effects/facts cross through the dao.stream implementation. Node/browser,
Wasmtime and Wasmer run the same engine/body binary; the CLJS implementation
is the conformance oracle, not the engine of the Node wasm lane.

The body toolchain is a JVM Clojure emitter producing deterministic WAT,
assembled with WABT. The engine uses the pinned Rust toolchain targeting
`wasm32-unknown-unknown`; W0/W1 must demonstrate linking the engine and bodies
under the baseline profile. This is not ClojureScript-to-wasm. Toolchain pins
and probes live in [yin.vm.engine.md](./yin.vm.engine.md) §E.5.3; they are
acceptance targets, not results claimed here. Wasmtime joins Node from W1;
Wasmer passes the full suite by W5. JVM/Dart remain reference/regression lanes;
application integration is separate.

The mandatory feature profile is **core wasm 1.0 with mutable globals and the original function
table / `call_indirect` facility**: i32/i64/f32/f64, structured control, one non-shared wasm32
memory and one function table per module, single-result functions, active data/element segments.
No externref or first-class reference values, typed function references, `call_ref`,
`return_call`, WasmGC, bulk-memory/table instructions, multi-value, multi-memory, memory64,
SIMD, exceptions, threads, component model or JS string builtins are assumed. The baseline table
element kind used by `call_indirect` is not permission to emit the later reference-types
instruction set. Copy/clear uses scalar loops; no implicit `memory.copy`, `table.set` or
`table.grow` instruction may slip in through optimization. Mutable globals need not cross the
import boundary; instance base coordinates can use immutable scalar imports.

Every extension requires a separate named profile, W0 validation/probe on all target engines and
matching cache key; the baseline artifact must continue to work without it. WABT/Binaryen
defaults are not the feature contract. Uneven native-runtime support for post-MVP features is
the reason for this deliberate cut, not an unresolved portability risk. WasmGC remains a
non-goal despite its inclusion in the September 2025 Wasm 3.0 standard; standardization does not
require this backend to adopt it. [Wasm 3.0
announcement](https://webassembly.org/news/2025-09-17-wasm-3.0/).

WASI stays outside the VM core and its imports. A WASI-based embedder may implement stream
stream implementations/FFI through the same adapters; the generated program imports neither WASI nor
runtime-specific system calls. Binaryen is optional after parity. Engine linking uses the pinned Rust/LLVM
linker; W0 records that build and final-module feature audit.

Read authorities (source symbols are semantic specifications for every embedder; calls to
existing CLJC helpers below mean oracle behavior in CLJS and equivalently tested in-instance Rust
transitions, not a requirement for native hosts to load Clojure):
`src/cljc/yin/vm/debruijn/register.cljc`, `debruijn_register_code.cljc`,
`debruijn_register_effects.cljc`, `semantic/derive.cljc`, `semantic.cljc`, `engine.cljc`,
`values.cljc`, `yin/vm.cljc`, `linker.cljc`; the register, semantic, linker and engine design
documents; and the semantic and register test corpora. In particular:

- The register contract is version 4 / `"r2"`, with exactly 23 opcodes. Preserve the image, physical L/T allocation, instruction pcs, BLAKE3 checksum, and six in-band live operands. Do not allocate another guest register namespace.
- The October amendments to the register and semantic designs make **A the code identity**. R is the register image checksum. Existing `:hash`, `:image`, and offset rows still use R for native compatibility. Do not publish wasm binaries as a new code identity, fetch code by a wasm checksum, or bypass A-to-R admission. Retain access to the Universal AST through existing provenance/addressing.
- Older paragraphs in register §5.2 still put code-space fields on return frames and restore code from payloads. Linker §7.3 r6/r7 and the implementation supersede them: return frames contain no code space; restore never rewinds it. Engine documentation (`yin.vm.engine.md` §1.1, §2.1, §3, corrected 2026-10-19) also contained historical `:store-updates` wording; current ready entries update private `:resources` through `:resource-updates`.
- Current `vm/machine-data?`, used by register restore and payload validation, recursively accepts kernel-minted Closure/Continuation wrappers by their payloads, as well as exact-integer carriers. It is not identical to `plain-data?`. Arbitrary host functions/errors remain invalid. Preserve the predicate, not an approximate JSON test.
- The current module-slice encoder in `engine/encoder` explicitly refuses reified and parked continuations with `:yin.k/status :yin.k/non-portable`, kind `:non-canonicalizable`. Thus universal linker transport of every register continuation is not already implemented. This lane must support the existing native payload where admissible, and preserve qualified refusals elsewhere; it must not silently promise a new universal migration mechanism.

### 1.1 Rust-engine authority, scope and explicit reconciliations

[`docs/design/yin.vm.rust.md`](./yin.vm.rust.md) retains the revision 2 technical core and is an authority for
the shared implementation: opening status/authorities, crate workspace (§9)
and decisions (§11). It remains **design-only**; the wasm plan depends on its
shared crates without commissioning unrelated K4/full-K5 work. This
specification, [`yin.vm.engine.md`](./yin.vm.engine.md) (§E.1–§E.5), and
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) (§10) record the owner-authorized wasm plan;
they neither commission K4 LLVM nor the unrelated standalone Rust node/transports. Where native
ownership, Value, transition reuse, fault isolation and crate boundaries govern, this plan is
subordinate to that design. Crate/type names below are proposed by that document, not claims of
implemented Rust symbols.

This plan **supersedes the DEFERRED item “WASM as an artifact target”**
(`yin.vm.rust.md` §11 Deferred). The Rust engine rescope also adds the host adapters (§6.4); unrelated deferred items and K3-owner-review
prerequisite for K4 remain. The following narrower reconciliations prevent silent
contradictions:

- Current A identity and A→R admission above govern over the older Rust topology and R-as-identity wording (§§3, 7.5). R remains the exact executable checksum/offset-row token. Neither backend creates an identity or bypasses canonical admission.
- Rust K-D2b uses u32 indices and qualified host limits (§4.2 and §11 K-D2b); wasm keeps 64-bit **wire pc fields**, not a promise of unrestricted native execution. The internal facade checks every pc/operand/attachment against the Rust domain and declared limits before conversion or mutation and returns the same qualified `:host-limit`. W0 records the common accepted profile; tests outside it require declared refusals, never truncation or a second widened Rust Machine. Wide register tests above 65,535 still fit u32.
- Rust phases exclude existing CLJC edits (§§1, 10). The deterministic engine traversal policy in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.3 (r5 §4.1.2) is a separately identified **wasm-driven shared-reference stabilization prerequisite**, outside that K-phase restriction; it remains an explicit scoped amendment for implementation review, not permission for a second native ordering policy. Export a versioned extension of the common kit after that shared change, preserving original goldens/history. This specification changes no source.
- Rust public `step` always interprets (§5.3). Keep that rule for the Rust interpreter lane. The wasm backend's explicit one-instruction test/inspection mode is a different dispatcher used to verify its generated execution; it does not silently replace Rust's public `step`. Optimized wasm run chunks obey the same boundary equivalence law.
- The in-instance wasm engine needs shared engine services in W2–W4 even though the Rust standalone-node roadmap leaves K5 last (§§6.3, 10). Develop/reuse those **same crates** to the slice's required coverage ([`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.2); the superseded offload role cannot satisfy in-instance wasm parity. This schedules the necessary library subset for wasm, not the full K5 node or a new Rust network client.

Owner resolution 1 (§11) and C1/A3 (§2.7 and §11) align with the owner's
instruction to lower held register images: wasm is a kernel execution strategy over an admitted
image, not a replacement source emitter. Do not reopen that choice or infer authorization for
LLVM. The node-local cache choice aligns with question 2; compile farms remain out of scope.
Worker envelope choice and K3 commissioning threshold remain with the LLVM work; W0's declared
scalar profile fixes only this lane's supported domain, not all future Rust profiles (questions
in §11).

---

## 2. Emitted module contract and linear-memory layout

### 2.1 Values in emitted code: i32 ValueRef locals

Choose **i32 ValueRef locals**, not NaN boxing and not externref registers. Reserve 0 = nil, 1 =
false, 2 = true; all other values use checked arena indices. Every integer, float, string,
symbol, keyword, collection, primitive, closure, continuation, and sealed stream/cursor
reference is an arena entry. Numeric operations run in the linked `yin-prims`
and shared Value implementation, matching the CLJS oracle's declared carriers,
float behavior, equality and metadata. Only references 0/1 are false.

The per-task arena is inside wasm linear memory. The descriptor table has kind,
generation, task-local slot and optional machine-record offset. The internal
handle↔Value facade owns the shared Rust Values through safe Clone/Drop; it does
not serialize them between body and engine. No JS/native pointer is a ValueRef.
See [yin.vm.engine.md](./yin.vm.engine.md) §E.2.1–§E.2.3 for ownership, collection,
target-layout gates and the **two repository codecs**. Internal values retain
their fidelity even when existing custody/encoding predicates refuse transport.

Handles keep codegen register locals uniform and avoid externref or i64 host-call
arguments. The cost is arena indirection, not a service crossing per primitive.
A scalar fast path remains a measured optimization with explicit numeric laws;
it cannot change the public image contract.

### 2.2 Private linear-memory layout

Use imported, non-shared wasm32 memory, little-endian and bounds checked. One task owns one
memory arena; all its compiled image instances import that arena. Separate tasks receive
separate arenas even when compiled Module objects are cached. A supplied coremem can implement
the same interface only with task isolation and allocation ownership; shared mutable guest state
is not introduced.

| Region / record | Contents and rules |
|---|---|
| Task header | Internal layout version, run status, absolute pc as unsigned 64-bit field, body id, active register count/pointer, lexical-chain pointer, return-stack pointer, store-of ValueRef, value ValueRef, engine-state token, code-layout generation, scratch/request pointers. Pointer fields are u32. |
| Body directory | Absolute start/end pcs, register/local counts, image row, compiled function slot, body-relative entry mapping. Append-only while attached. |
| Active register shadow | Dense array of u32 ValueRefs of exactly the current body's `:registers` size. L slots are initialized from positional arguments; T slots start nil. Wasm locals cache this array while a body executes. |
| Lexical frames | Immutable vector records: count followed by ValueRefs. Chain is an immutable outermost-first vector of frame pointers. Depth d indexes `count - 1 - d`, then position. |
| Return stack | Immutable/persistent vector of return-record pointers, innermost last. Records contain site-pc, return-pc, frames pointer, sparse pairs pointer, live vector pointer, dest, optional store-of. No segment/hash/layout snapshot. |
| Sparse snapshots | Count followed by sorted `(u32 register-index, u32 ValueRef)` pairs. Records do not alias mutable active-register storage. Only the verified live set is captured. |
| Continuation record | Immutable snapshot metadata: format/hash/image ValueRefs, capture-time segment and optional images ValueRefs, site-pc, pc, frames, sparse regs/live, return stack, destination presence/index, resume mode, optional store-of. Engine/FFI transport fields remain losslessly represented in the materialized payload. |
| Value descriptors | Kind/generation/arena slot plus optional closure or continuation record pointer. Arena slot contains the actual immutable value or trusted wrapper. |
| Engine state roots | ValueRefs/tokens for store, module-stores, free-env, primitives, modules, wait-set, ready-queue, parked, resources, heap/gc, owner, origin/ancestry, id-counter, `:yin.k/gate`, `:yin.k/issued`, and `:yin.k/closes`. Preserve all other extension keys as well. The actual issue-counter key is `:yin.k/issued`, distinct from id-counter; do not invent `:yin.k/next-issue`. These are state/arena retention roots, not an expansion of the engine’s logical heap-GC root set. The full existing state map is authoritative for engine extension keys. |
| Exit mailbox | Pending instruction site/op and argument refs, pending result/error ref, completion kind. Private implementation state, never a portable continuation. |

Pcs use 64-bit fields so wasm32 pointers do not silently narrow the existing layout domain (up
to `2^52-1` in `compose`). Functions use small body-relative i32 dispatch indices after checked
resolution. Reject resource/address exhaustion with a qualified backend diagnostic before
allocation/attach; never wrap a pc, handle, or byte offset. The counter remains the existing
engine integer value and advances only through engine operations.

The program store is logically an immutable map, usually name-keyed but **not restricted to
names**: `:store-get`/`:store-put` accept `:data` keys, including keywords and numbers in the
corpus. Its linear-memory representation is a root ValueRef to the in-instance engine’s persistent map,
not a second wasm hash map; the map and updates use direct internal helpers on every runtime. All writes go through `engine/put-active` →
`engine/store-put`. Non-nil store-of selects only that module's store, with no ambient fallback.
Private resources and heap are distinct from program store.

The guest-agnostic machine-state-as-data synchronization barrier is specified in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.1 and §E.2.3; the register machine's eight-field
instance and dense-register shadow spilling rules are specified in
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.1.1 and §10.1.2.

### 2.3 Module imports, exports and deterministic emission

The linked instance exposes lifecycle run/drive entry points and test/inspection
step/read-state/snapshot-install hooks, not semantic service imports. Its external
functions are the stream adapters specified once in
[dao.stream.wasm.md](./dao.stream.wasm.md) §§1–3. Components share one task-owned,
non-shared wasm32 memory and original function table, with immutable i32 base
offsets where needed. Memory/table provision and internal component helper
imports are linkage mechanics. Final external semantic imports are allowlisted
to the stream implementation. No host-call signature passes i64, externref, Rust
pointers, JS values, strings or multi-value results; i64 pc/numeric bytes live
in memory and core i64 computation remains allowed inside wasm.

TRANSFER/YIELDED/BLOCKED/PARKED/HALTED/ERROR below denote internal driver control,
not serialized service statuses or adapter results. Public state preserves exact
flags, value and queues. **Blocking is a normal return, never a trap.** Memory
or validation traps are backend faults, not blocked or FFI error envelopes.

Emission is byte-deterministic: equal admitted register image, emitter/internal-layout version, and feature
profile yield byte-identical WAT and assembled `.wasm` binaries across runs and platforms.

---

## 3. Code generation strategy and all 23 opcode lowerings

### 3.1 Choice among the alternatives

| Strategy | Benefits | Costs / semantic obligations |
|---|---|---|
| Fully structured body functions with direct guest `call` | Small fast code for ordinary functions; conventional optimizer input | Captured continuations require every caller activation to be materialized and abandoned; arbitrary resume PCs need secondary entries; suspension cannot retain native stack frames. A shadow stack alone does not unwind native callers. |
| One interpreter loop over instruction records | Small wasm binary, trivial append and pc resume | Runtime operand decoding and memory-indexed registers on every step; does not exploit known register operands as locals. Uses the same K0/W0 oracle; available as the in-instance interpreter fallback and a W6 codegen comparison; engine placement is already fixed. |
| **Compiled body dispatch plus trampoline** | Static register locals and operands, arbitrary entry PCs, appendable bodies, constant native stack depth, explicit continuations | More generated code than interpreter; indirect dispatch and spills at transfers; per-body branch tables may be large. |

Choose the third. A body function has conceptual signature `(state-ptr i32, entry-index i32,
budget i32) -> status i32`; updated budget/state lives in the header. All bodies share the same
signature for a function table. The trampoline resolves absolute pc, calls one body function,
then loops on transfer. A body **never invokes a guest callee directly**. Guest call saves a
return frame (unless tail), installs callee state, and returns TRANSFER. Return installs caller
state and returns TRANSFER; final return returns HALTED. The trampoline's wasm `call_indirect`
is an implementation dispatch, not the guest call stack. State-consuming helper
sites return to a shared **internal helper thunk** after materialization. The
thunk directly calls the linked engine using the same memory, then reloads the
committed state and continues or returns blocked/error. This single replaceable
site centralizes synchronization and lets later codegen inline proven-safe calls;
it has no serialized mailbox or external service protocol. No guest activation
remains across an asynchronous wait. The same body/engine binaries run on every
embedder.

Consequently flattening is exact and incremental: every pending guest call is already a sparse
memory record before its callee runs. Capturing K copies/shares those immutable records;
abortive application replaces the current chain and returns from the one active body. No stack
walk, Asyncify, native-stack copying, wasm exception unwinding, or retained JS Promise
continuation is needed.

`return_call` is **not required or emitted**. Tail guest calls retain K unchanged and replace
the active lexical chain/register file. This gives bounded native stack depth on engines without
the tail-call extension. A later direct-call optimization may be allowed only for a proven
noncapturing, nonsuspending region with a deoptimization protocol; it is not part of this plan.

### 3.1.1 LLVM sibling and shared equivalence law

The wasm path and Rust K4 LLVM path are sibling execution strategies under
`yin.vm.rust.md` §7.2: from equal reachable boundary states they
reach equal later states, ordered effects and refusal diagnostics. Both use body functions,
entry-pc dispatch, live sets as spill maps and data guest calls; their native control
instructions differ, their guest semantics do not (§7.3). W0’s shared kit tests
all lanes; neither translator forks the validator, liveness rules, payload schema or semantic
service implementation. An artifact may be discarded at a materialized boundary and execution
continued by the owning interpreter from the same state. Add wasm→Rust interpreter and wasm→CLJS
interpreter fallback fixtures; no artifact field may be needed to restore.

### 3.2 Structuring algorithm

1. Admit the image with the exact contract check and `register-image-defect`. Preserve body ranges and the original source instruction vector as metadata. Reuse `body-liveness`/`live-slot-index`; do not derive live sets from the optimized wasm graph.
2. Build each body's CFG using the existing successor rules: conditional two-way edge; jump target; no successor for return/halt/resume or tail call; ordinary call and other operations resume at pc+1. Jumps must remain inside their body. Do not assume a hand-authored valid image is the structured syntax emitted by `derive`.
3. For W1, make every instruction an entry case. Load locals from shadow, enter a wasm `loop` containing nested void `block`s and a `br_table` selecting the pc case. Each case consumes its operands into locals, leaves an empty operand stack, updates the next pc/case, then branches to dispatch or returns one status. All branch targets have the same empty result type. Functions return their status only at the common exit. Invalid entry takes a qualified fault path, not execution of arbitrary memory.
4. For W6, merge straight-line instruction cases into basic blocks, retaining leaders at targets, body entries, all resume pcs, and every externally observable step/exit site. Preserve an entry path for each supported pc; debug one-step mode can retain instruction cases. General irreducible CFGs keep dispatch. A subsequent measured optimization can compute dominators/SCCs and structure single-entry regions as `block`/`loop`/`if`, retaining a local dispatcher for multi-entry SCCs. There is no need to ship a full relooper to get a correct backend.
5. Check budget at instruction boundaries for bounded/debug execution. A budget yield materializes the **full** active state and returns YIELDED; it is not a new guest suspension site, has no invented sparse live operand, and is not serializable as an r2 continuation. `step` counts one guest instruction, even when several internal engine transitions implement it.

### 3.3 All 23 opcode lowerings

Here `r` means ValueRef local and `next` means pc+1. The retained r5 rows use
“Host”/“host fn” to name the oracle's kernel-helper/primitive cases: in this
architecture these **all execute inside the linked Rust engine**, through the
internal thunk (§4), never through an imported engine service. Pending scratch
retains already-read operands so continuation/re-entry cannot repeat effects.
The rows specify transitions; generated dispatch invokes shared helpers rather
than copying semantic transition implementations.

| Opcode | Lowering and observable behavior |
|---|---|
| `:const rd value` | Load interned literal reference into rd; next. Preserve actual scalar/collection value and metadata. |
| `:load-bound rd depth position` | Read frame chain at `count-1-depth`, then position. Preserve `:load-bound-depth` and `:load-bound-position` failures. Never substitute reserved L locals for the runtime chain. |
| `:load-free rd name` | Host `engine/resolve-var` with free-env, active store, primitives, modules. Preserve lookup order, present-nil bindings, Rule R, and module resolution. Write rd; next. |
| `:closure rd arity body-pc` | Mint owned Closure with type, arity, absolute body-pc, immutable captured frames, and optional store-of. Write rd; next. Captured frames outlive active body. |
| `:move rd rs` | Read rs before writing rd; next. |
| `:call rd fn-reg arg-regs tail? live` | Read operator and ordered arguments first. Use `engine/operator-kind`. Closure: nil-fill/drop extras to arity, append bound frame to captured chain, initialize L args and nil T slots; push exact sparse caller frame only for non-tail; set closure store-of and body pc; TRANSFER. Host fn: apply f to args. A typed effect first passes `engine/check-callee-effect!`, then follows the complete call-effect contract below (including blocked results and site builders). An ordinary result writes rd/next or executes return transition for tail. Continuation: `engine/operator-kind` checks ownership first, then `engine/continuation-argument` checks exactly one arg, then check format/image, abort current K and write back captured state; no external data gate on this internal path. |
| `:branch-false cond target` | Branch only when nil/false; all other values next. Empty operand stack on either edge. |
| `:jump target` | Set pc/case to the verified same-body target. |
| `:return rs` | Read value. If K nonempty, pop frame, nil-initialize caller register vector, restore saved pairs and dest, frames and store-of, set value and return-pc; TRANSFER. Else set only value, halted true, store-of nil: keep pc at the executing return instruction and preserve active registers, frames, empty K and all other fields. The return-result restore case first changes pc as specified in [`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.2.2 (r5 §3.2). |
| `:halt rs` | Set value, halted true, store-of nil. Does not pop K as return would; preserve pc at the halt instruction (no increment), active registers, frames, K, blocked flag and all other fields. |
| `:store-get rd key` | Read literal key from active store, absent → nil; rd/next. |
| `:store-put rd key value` | Write the **literal value operand**, not a source register, via put-active; return that value in rd; next. |
| `:define rd name rs` | Read rs, put-active under literal name, write rd; next. Never resolve the definition operator. |
| `:gensym rd prefix` | Engine gensym using the same task counter as stream/park ids; rd/next. |
| `:stream-make rd capacity` | Mint authorized `:stream/make` effect; engine calls supplied make-stream. Preserve default-capacity handling; rd/next. Nonblocking under current contract. |
| `:stream-put rd sr vr live` | Build site payload; typed put effect from read sr/vr. On success rd/result and next. On full enqueue exact writer wait entry, advance pc, BLOCKED. Other outcomes follow engine. |
| `:stream-cursor rd sr` | Typed cursor effect, engine resource/cell handling, rd/next; nonblocking. |
| `:stream-next rd cr live` | Build site payload; typed next effect. Immediate result → rd/next; blocked → reader wait entry, advance pc, BLOCKED; all other outcomes follow engine. |
| `:stream-close rd sr` | Typed close effect and engine result, rd/next; nonblocking. |
| `:current-continuation rd live` | Snapshot before writing rd at next pc, mint owned Continuation with `:type :reified-continuation`, write rd; next. Destination is excluded from saved live set. |
| `:park rd live` | Snapshot and advance pc, call engine park-continuation. Adds id/type to parked record, increments counter, sets value to record and halted true. PARKED status; do not invent a stream wait. |
| `:resume parked-id rs` | Literal keyword id and value from rs. Engine resume-continuation performs custody gate/missing-id checks and removes parked entry, then register restore. Abort current path; no next-pc fallthrough. |
| `:ffi-call rd op arg-regs live` | Require call pair first; snapshot and read ordered args; pin outgoing refs; park under correlated call-id and advance pc; append request; enter reader wait or full writer wait. Always yields to protocol; no direct call of bridge handler from wasm. |

### 3.4 Exact primitive (oracle host-function) effect completion

For `:call` whose `apply f args` result satisfies `module/effect?`, first use the state returned
by `engine/check-callee-effect!`. Build `payload-of` at the original call site from the
materialized state and relocated combined instruction. Tail calls use `:resume-mode
:return-result`, `:dest nil`, and their verified empty live set; non-tail calls use
`:write-result` and rd. Pass `{:park-entry-fns (park-entry-fns payload)}` to
`engine/handle-effect`, with builders for `:stream/put`, `:stream/next`, and `:module/require`.
The module handler uses the require builder as fallback for `:module/try-require`; gated stream
poll uses the next builder as fallback. Do not wire only the dedicated stream opcodes.

Given `{:state state :value value :blocked? blocked?}` from handle-effect:

- Blocked: return `(assoc state :pc (inc site-pc))`, with no destination write and no return transition, even for a tail call. The payload retains the pending destination/return mode for later delivery.
- Nonblocked tail: run `return-transition` on the **post-effect state** and value. Do not pre-increment pc: if K is empty, this immediate path halts at the call site. This differs observably from a blocked tail effect later restored at the payload’s next pc.
- Nonblocked non-tail: set pc to next and write value into rd of `(:registers state)`, preserving the rest of the post-effect state, including `:value` set by handle-effect.

Never reconstruct the result from the pre-effect wasm header: module attachment,
store/module-store writes, resources, heap/GC, counters and custody bookkeeping may have
changed. A non-effect host result follows the separate plain-result arm of `register.cljc`,
including preservation of old `:value` on a non-tail call.

### 3.5 Boundary continuation payload and internal continuation call

At a six-op boundary at p, export exactly the payload built by `effects/continuation-payload`:

```clojure
{:segment capture-time-segment
 :site-pc p :pc (inc p)
 :frames outermost-first-frame-vectors
 :regs [[live-index value] ...] :live [live-index ...]
 :continuation [return-frame ...]
 :dest rd-or-nil :resume-mode :write-result-or-return-result
 :format :yin.debruijn.register
 :hash capture-time-register-hash
 :image identity-of-row-containing-resume-pc
 ;; :images capture-time-offset-table when count > 1
 ;; :store-of module-address when present
}
```

Return-frame shape is `{:site-pc :return-pc :frames :regs :live :dest}` plus optional store-of.
Frame order, sparse pair order, absent-vs-present optional fields, and keyword values are
preserved. The `image-row` convention includes the one-past-last-row endpoint for address
helpers; that does not make it a valid write-result continuation pc: continuation-defect still
requires the site's and resume pc's body to agree.

A `:call` of a trusted reified continuation uses the distinct internal path:
`engine/operator-kind` ownership classification first, `engine/continuation-argument` arity
check second, format/image membership third, then write-back; it deliberately skips
continuation-defect/machine-data gates. For an unowned continuation with wrong arity, ownership
fails first with `{:reason :foreign-value :kind :continuation}`, not `:continuation-arity` (and
not `:foreign-continuation`). A live primitive in an internal capture is valid and must not be
rejected merely because it could not cross a wire. This is abortive, and applying a retained
capture more than once is allowed; do not mutate its saved records. Explicit parked-id
consumption is separate and removes that parked map entry.

In-instance payload materialization, the 5-step `register-restore` algorithm, and cross-layout
transport refusals are specified in [`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.2.

---

## 4. Target linkage: in-instance engine and dao.stream implementation

The compiler links `yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`,
`yin-prims`, `yin-engine` and `yin-wire` for `wasm32-unknown-unknown`. The
import-surface implementation's operations, signatures, Jing payloads and stream outcomes have one
home: [dao.stream.wasm.md](./dao.stream.wasm.md) §§1–3. The compiler emits no private
service protocol, codec, transport-status decoder or response-refetch loop.
The owner boundary clarification of 2026-10-19 supersedes the former target
interface and its earlier performance-based exception.

Compiled bodies and engine functions reach each other by **direct calls and
shared linear memory**, internal linkage within the task's instance group.
The shared helper thunk is the one replaceable generated call site. It flushes
the eight register fields, calls the owning crate's transition, and adopts the
returned state before opcode-specific completion (§3.4). Full fields and barriers
are owned by [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md) §10.1
and [yin.vm.engine.md](./yin.vm.engine.md) §E.1; they are not import messages.

The compiler owns pc dispatch, operand access, branch structure and entry maps.
Shared helpers own call binding/return/restore, ownership, primitives, stores,
collection and effects. This resolves the old compiled-call/service division
without duplicating transitions. The in-instance interpreter reaches those same
helpers. An imported helper between separately instantiated linked components
is private bundle linkage, not a composition-provided semantic operation.

Only the in-instance engine's stream adapter layer calls external semantic
imports. It encodes canonical Jing data and consumes the exact stream outcomes.
Resolve-var, frame/root materialization and scheduler operations never become
stream history. The compiler must preserve the thunk barrier on every path
that can collect, park, attach or return a snapshot. External adapters carry no
live stack frames, engine records or ValueRef definitions.

## 5. Per-image-row compilation, relocation, and artifact-cache law

Compile each independently admitted image into a wasm Module whose body entry functions import
the same task memory/table and linked in-instance engine helpers. Instantiate it for the receiving task
and install its functions into the task's imported function table using the runtime embedding
API. This table maps implementation body ids to uniform functions; it is not the guest offset
table.

Attach is a staged, append-only transaction:

1. Apply the same contract/validator gates as `attach-image`. Empty images are no-ops; duplicate component R is a no-op. Production admission retains the existing A-to-R derivation check before executable attachment.
2. Compute offset = held instruction count. Relocate every operand whose opcode-table kind is `:pc`; shift body start/end. Compute the exact combined segment and `register-hash`, and append `[component-R offset length]`.
3. Compile/instantiate the new component, reserve directory/table capacity, install its body entries, then publish the new code-layout generation atomically. Prefer component-relative constants plus an instance base offset so the same compiled Module can serve different task layouts. Every pc generated from a component-relative coordinate adds the instance base exactly once before entering public state: header/exit pc, payload site/next pc, return-frame site/return pc, and minted closure body-pc. Incoming absolute restore/return pcs are already absolute and must not receive another base. Resolve absolute pcs through the combined body directory and subtract the selected body start for local dispatch.
4. Preserve pc, registers, frames, K, all pending payloads, stores, and queues. No existing function slot or instruction pc is renumbered; old compiled instances remain reachable. Failure before publish leaves the old task executable. Attach only while wasm is at a materialized boundary.

For every boundary payload, fetch the instruction as `(nth (:instructions (:segment vm)) (:pc
vm))` from the current relocated **combined** segment at the materialized absolute site pc. Pass
that instruction and current segment/hash/images to `effects/continuation-payload`; never pair
component-relative site metadata or a component-only segment with absolute payload pcs. The
cached component image is compilation input, not the current capture-time code space. Even where
the live operand is numerically unchanged by relocation, all payload coordinates and validation
context must agree.

An empty-image creation seeds `:images [[empty-R 0 0]]`, `:hash nil`, `:pc 0`, `:registers []`,
and `:halted? true`. Attaching a nonempty image of length n at offset 0 keeps the empty row and
produces `[[empty-R 0 0] [R 0 n]]`; it does not unhalt the task, resize registers, or initialize
a body. Reset or a valid execution-entry transition performs that separate work. A later capture
includes `:images` because the row count is two, even though only one row contains instructions.
Keep the zero-length row in payload validation and layout operations; it is distinct from the
rule that attaching an empty image is a no-op.

There is no machine-code patching and no need to grow an existing wasm Module's function
section. The baseline preallocates table capacity (W0 test profile: 65,536 slots) and uses host
table installation plus directory append for new functions; exhaustion is a qualified resource
refusal. No guest `table.grow`/`table.set` instruction is required. Optional host-API growth
must pass the W0 probe on each runtime and must not change the baseline module feature profile.
Dynamic code instantiation uses the runtime's module lifecycle at a quiescent,
materialized boundary, never a reentrant semantic service call. Core wasm cannot
instantiate new modules itself: the composition installs derived cached body
functions via the embedding API; this physical linkage does not perform guest
attachment/admission. The in-instance engine owns that transaction and its
state publication. Without a ready artifact, execute the attached image in the
same in-instance interpreter; cache preparation cannot change guest semantics.
Artifact preparation/availability is node-local, never a portable module field.

The same component bytes and import names are used on all runtimes. Old call frames use absolute return-pc and resolve in
the grown directory. Captures retain their old segment/hash for validation, but resume sees the
current code space. Reset preserves offset rows as in the kernel; load-image replaces the code
space and resets execution, so old continuation membership checks can fail.

In-instance `spawn-module`, `module/IModuleKernel` closure/resource lifting and lowering, and
module store snapshots are specified in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.4 and
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.3. Cache compiled code by A, r2
derivation/checksum, wasm layout/emitter version and feature profile; R alone is not executable
admission authority.

Artifact policy is also shared with LLVM: `yin.vm.rust.md` §7.5, §8.4 and §11 K-D7. Wasm binaries are disposable **node-local caches**, never dao.jing
content identities, refs, continuation fields or portable module payloads. Include
target/profile, translator and internal-layout versions in the cache key alongside the admitted A/R
provenance above. No cross-node artifact fetching/sharing is introduced. A missing/dropped
artifact falls back to the same in-instance register interpreter; no compiler or LLVM worker is
required to resume. Install derived artifacts only at materialized boundaries, with no pending
mutation or active guest stack. Body-relative code and task base offsets follow the same
row-local cache rule in both backends.

---

## 6. Compiler toolchain, optimization axis (W6), non-goals, and risks

### 6.1 Toolchain and W0–W6 lowering gates

The full W0–W6 slice schedule, runtime acceptance gates, and exact toolchain pins (`Node 25.6.1`,
`Clojure CLI 1.12.4.1602 / shadow-cljs 3.3.5`, `WABT 1.0.37`, `Wasmtime =38.0.0`, `Wasmer 6.0.1`,
`Rust / Cargo 1.90.0`) are specified in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.3 and
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.5.

For the lowering pipeline specifically (acceptance detail is in register §10.5):

- **W0 — Oracle and feasibility:** export/extend K0 from CLJS first; freeze admitted images/R/traces/refusals; probe crate wasm32 compilation, linker/profile, internal layout and stream-adapter bounds. Pin deterministic emitter/assembler. No host-service fallback.
- **W1 — In-instance pure execution:** link the required Rust engine/primitive/value/image crates and compiled body dispatcher; prove actual wasm32 execution and single-step parity under Node + Wasmtime.
- **W2 — Continuations:** compile sparse capture/restore, internal continuation calls and park/resume against the linked engine; retain layout/custody/refusal and snapshot gates.
- **W3 — Effects:** use Jing stream adapters with the in-instance scheduler, GC, custody and two-step FFI; test full/blocked and once-only retry on Node + Wasmtime.
- **W4 — Modules:** compile per-image rows, relocate once, install artifacts at materialized boundaries, preserve attach/spawn/store/GC law and CLJS↔wasm module-slice parity.
- **W5 — Complete lane:** all 23 opcodes and complete W1–W5 full traces on Node, Wasmtime and Wasmer; deterministic binaries and external import/profile audit. Wasmer's formerly W4 full-suite gate is superseded by this owner-directed schedule, not removed.
- **W6 — Codegen optimization only:** measure compile latency, binary size, dispatch/indirection, stack depth, arena/heap retention, call/suspension, stream-byte/copy time and end-to-end time. Compare compiled bodies with the same in-instance interpreter; coalesce/structure blocks, reduce redundant spills or add pinned Binaryen only with full three-runtime parity. No engine migration or architecture-selection experiment remains. K3 may use the same benchmark harness; K4 still requires owner review.

The ruling supersedes the former W0–W5 private-mailbox work and W6 service
migration. Correctness gates survive in their in-instance or stream-adapter
form; two repository codecs and the shared oracle remain mandatory.

### 6.2 Lowering non-goals and risks

Non-goals for the lowering: no new opcode/contract version, alternate A→R allocator, native
stack continuations, async-stack capture, universal cross-model continuation conversion, new
portable continuation schema, direct arbitrary host FFI imports, WASI imports in the VM core,
threads/shared memory, WasmGC dependency, numeric NaN boxing, cross-node artifact service, or
optimization before W5 correctness. Guest-agnostic and register-runtime non-goals are listed in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.4 and
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.6.

Lowering-specific risks (retained from r5 §7; host/runtime risks in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.4 and
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.6):
- **Large bodies and dispatch size.** One instruction per case can create deep block nesting and large binaries. W0 should pin practical limits; W6 can shard dispatch into bounded pages/helpers while keeping entry-pc precision. Such helpers must never hold suspended guest state in their native stack.
- **Module growth and code retention.** Wasm module code cannot be appended in place. Many image instances may increase memory and startup cost; function-table limits and compile failures require explicit resource refusals. Code unloading while live absolute pcs exist is out of scope.
- **Canonical admission and provenance.** Bare hand images remain useful under the existing raw kernel API; production module execution must retain A derivation verification. Compiled wasm caches are disposable artifacts. They cannot become another authority for code or continuation identity.

---

## Reference conventions

Reference convention: `register.cljc` means `src/cljc/yin/vm/debruijn/register.cljc`; `engine`,
`ffi`, `module`, `values`, and `debruijn_register_effects` filenames are under
`src/cljc/yin/vm/`; `vm` means `src/cljc/yin/vm.cljc`; `apply2` is the alias for
`dao.stream.apply` at `src/cljc/dao/stream/apply.cljc`. Unqualified register helpers such as
return-transition, write-back, payload-of, park-entry-fns, attach-image and spawn-module are in
register.cljc; spawn-module/gc-roots are protocol implementations, not invented defns.
`module/IModuleKernel` and its gc-roots method are declared in module.cljc. IVM step/run are
methods of `yin.vm/IVM`, not a namespace named IVM. `effects/*` names denote
debruijn_register_effects.cljc. `register-image-defect`, `body-liveness`, `live-slot-index`,
`register-hash` and `opcode-table` belong to `src/cljc/yin/vm/debruijn_register_code.cljc`
(alias rcode); the `register-image` derivation belongs to
`src/cljc/yin/vm/semantic/derive.cljc`. `halted-with-empty-queue?`, `scheduler-round`,
`check-wait-set`, `handle-close` and `collect` are engine.cljc functions. The quoted
restore/call helper names are oracle semantics; planned internal Rust helper and
physical import names are not claims of implemented symbols.

The `.cljc` line references in this specification, [`yin.vm.engine.md`](./yin.vm.engine.md)
(§E.1–§E.5), and [`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) (§10) are the r5-verified
references, checked against the current tree for this distillation. Rust-design references now use sections of yin.vm.rust.md under the
2026-10-19 owner decomposition; the old line-coordinate constraint is lifted. New import names and internal entry names describe planned interfaces, not existing repository
functions.

## Open items

The owner ruling resolves the former compiled-call/service split (§4) and
removes the response-refetch question with the dissolved protocol. W0 must
prove crate/linker wasm32 feasibility and the baseline feature audit. The
engine's remaining adapter-reservation and noncanonical internal-ordering gates
are in [yin.vm.engine.md](./yin.vm.engine.md) §E.5.5. No new protocol is a fallback.
