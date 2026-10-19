# Register images → WebAssembly: implementation plan, revision 2

Date: 2026-10-18. Original source baseline: `c49e565dd60ab6a3611c9c772665eca96d1ecd41`; round-1 findings rechecked against the current working tree (HEAD observed as `c818919d0a3284aba0a99308e7a1dcb7bb092850`). Evidence below refers to source lines read for this revision, not the reviewer’s sometimes stale line numbers. This is the complete revised plan, not an implementation or a performance claim. Only this new findings file is written; the original plan and review remain unchanged.

## Decision and source precedence

Implement a **compiled register backend with resumable body functions and an explicit trampoline**. Each declared body becomes a wasm function with one local per physical register. Intra-body control initially uses generated `br_table` dispatch; guest calls and returns update explicit machine state and return to the trampoline. No guest continuation ever depends on a wasm activation. Retain the existing CLJS engine, value types, store operations, stream protocol, FFI bridge, and linker as the first runtime's host services.

The initial toolchain is a JVM Clojure emitter producing deterministic WAT, assembled and validated by a pinned WABT toolchain, then instantiated by Node with the existing shadow-cljs runtime. This is emission from register images, not compilation of ClojureScript to wasm. Binaryen optimization is optional and comes after parity; neither wasm-ld nor a C toolchain is required. Tool versions and Node feature probes are to be pinned in slice W0; this report does not assume a particular installed version. Browser embedding follows Node parity. JVM and Dart remain reference lanes; native wasm embeddings on those hosts are separate work.

Read authorities: `src/cljc/yin/vm/debruijn/register.cljc`, `debruijn_register_code.cljc`, `debruijn_register_effects.cljc`, `semantic/derive.cljc`, `semantic.cljc`, `engine.cljc`, `values.cljc`, `yin/vm.cljc`, `linker.cljc`; the four requested VM design documents; and the two requested corpora. In particular:

- The register contract is version 4 / `"r2"`, with exactly 23 opcodes. Preserve the image, physical L/T allocation, instruction pcs, BLAKE3 checksum, and six in-band live operands. Do not allocate another guest register namespace.
- The October amendments to the register and semantic designs make **A the code identity**. R is the register image checksum. Existing `:hash`, `:image`, and offset rows still use R for native compatibility. Do not publish wasm binaries as a new code identity, fetch code by a wasm checksum, or bypass A-to-R admission. Retain access to the Universal AST through existing provenance/addressing.
- Older paragraphs in register §5.2 still put code-space fields on return frames and restore code from payloads. Linker §7.3 r6/r7 and the implementation supersede them: return frames contain no code space; restore never rewinds it. Engine documentation also contains historical `:store-updates` wording; current ready entries update private `:resources` through `:resource-updates`.
- Current `vm/machine-data?`, used by register restore and payload validation, recursively accepts kernel-minted Closure/Continuation wrappers by their payloads, as well as exact-integer carriers. It is not identical to `plain-data?`. Arbitrary host functions/errors remain invalid. Preserve the predicate, not an approximate JSON test.
- The current module-slice encoder in `engine/encoder` explicitly refuses reified and parked continuations with `:yin.k/status :yin.k/non-portable`, kind `:non-canonicalizable`. Thus universal linker transport of every register continuation is not already implemented. This lane must support the existing native payload where admissible, and preserve qualified refusals elsewhere; it must not silently promise a new universal migration mechanism.

## 1. Value representation and linear-memory layout

### 1.1 Values: i32 references into a task-owned value arena

Choose **i32 ValueRef locals**, not NaN boxing and not externref registers. Reserve 0 = nil, 1 = false, 2 = true; all other values use checked arena indices. Every integer, float, string, symbol, keyword, collection, primitive, closure, continuation, and sealed stream/cursor reference is an arena entry. Numeric computation initially remains in the existing primitive implementations, preserving integer carriers, float behavior, equality, metadata, and host-specific supported scalar semantics. Zero, empty strings, and empty collections remain truthy: only references 0 and 1 are false.

The host owns an explicit per-task arena vector, supplied at construction, not a process global. Linear memory contains a parallel descriptor table: kind, generation, host-slot, and optional machine-record offset. A handle refers to an entry, never to an arbitrary JS address. Host values remain their actual CLJS values; `values/closure`, `values/continuation`, and engine-minted effects retain their type, ownership, equality, and opaque printing behavior. A guest literal map with `:type :closure` remains a map. Pointer/handle equality is not guest equality; primitive equality uses the underlying values.

Stream and cursor *references* are sealed plain data, not host types in `yin.vm.values`. Their underlying stream handles and mutable cursor resources stay in `:resources`. `:make-stream`, primitive functions, and bridge handlers are composition services, never serialized guest values. Any returned effect must pass `module/effect?` and `engine/check-callee-effect!`; a map shaped like an effect is not dispatched.

Using i32 handles avoids passing i64 values as JS numbers or requiring externref persistence. It costs host crossings and arena indirection. This is acceptable for the first semantic implementation: register instructions themselves contain no numeric arithmetic opcode. A later native scalar fast path needs explicit numeric laws and benchmarks, not a change to the public image contract.

### 1.2 Proposed private memory ABI

Use imported, non-shared wasm32 memory, little-endian and bounds checked. One task owns one memory arena; all its compiled image instances import that arena. Separate tasks receive separate arenas even when compiled Module objects are cached. A supplied coremem can implement the same interface only with task isolation and allocation ownership; shared mutable guest state is not introduced.

| Region / record | Contents and rules |
|---|---|
| Task header | ABI version, run status, absolute pc as unsigned 64-bit field, body id, active register count/pointer, lexical-chain pointer, return-stack pointer, store-of ValueRef, value ValueRef, host-state token, code-layout generation, scratch/request pointers. Pointer fields are u32. |
| Body directory | Absolute start/end pcs, register/local counts, image row, compiled function slot, body-relative entry mapping. Append-only while attached. |
| Active register shadow | Dense array of u32 ValueRefs of exactly the current body's `:registers` size. L slots are initialized from positional arguments; T slots start nil. Wasm locals cache this array while a body executes. |
| Lexical frames | Immutable vector records: count followed by ValueRefs. Chain is an immutable outermost-first vector of frame pointers. Depth d indexes `count - 1 - d`, then position. |
| Return stack | Immutable/persistent vector of return-record pointers, innermost last. Records contain site-pc, return-pc, frames pointer, sparse pairs pointer, live vector pointer, dest, optional store-of. No segment/hash/layout snapshot. |
| Sparse snapshots | Count followed by sorted `(u32 register-index, u32 ValueRef)` pairs. Records do not alias mutable active-register storage. Only the verified live set is captured. |
| Continuation record | Immutable snapshot metadata: format/hash/image ValueRefs, capture-time segment and optional images ValueRefs, site-pc, pc, frames, sparse regs/live, return stack, destination presence/index, resume mode, optional store-of. Engine/FFI transport fields remain losslessly represented in the host payload. |
| Value descriptors | Kind/generation/host slot plus optional closure or continuation record pointer. Host slot contains the actual immutable value or trusted wrapper. |
| Engine state roots | ValueRefs/tokens for store, module-stores, free-env, primitives, modules, wait-set, ready-queue, parked, resources, heap/gc, owner, origin/ancestry, id-counter, `:yin.k/gate`, `:yin.k/issued`, and `:yin.k/closes`. Preserve all other extension keys as well. The actual issue-counter key is `:yin.k/issued`, distinct from id-counter; do not invent `:yin.k/next-issue`. These are state/arena retention roots, not an expansion of the engine’s logical heap-GC root set. The full existing state map is authoritative for engine extension keys. |
| Exit mailbox | Pending instruction site/op and argument refs, pending result/error ref, completion kind. Private implementation state, never a portable continuation. |

Pcs use 64-bit fields so wasm32 pointers do not silently narrow the existing layout domain (up to `2^52-1` in `compose`). Functions use small body-relative i32 dispatch indices after checked resolution. Reject resource/address exhaustion with a qualified backend diagnostic before allocation/attach; never wrap a pc, handle, or byte offset. The counter remains the existing host integer value and advances only through engine operations.

The program store is logically an immutable map, usually name-keyed but **not restricted to names**: `:store-get`/`:store-put` accept `:data` keys, including keywords and numbers in the corpus. Its linear-memory representation is a root ValueRef to the persistent host map, not a second wasm hash map. All writes go through `engine/put-active` → `engine/store-put`. Non-nil store-of selects only that module's store, with no ambient fallback. Private resources and heap are distinct from program store.

### 1.2.1 Mandatory map ↔ memory synchronization

The public VM is a persistent Clojure/CLJS record with ordinary map keys, not a token that requires callers to use `read-state`. Treat linear memory as a private execution cache. The synchronization protocol is:

1. **Public entry / host-to-wasm:** `step`, `run`, and restore accept the supplied map as authoritative. Import `:pc`, `:registers`, `:frames`, `:continuation`, `:store-of`, `:value`, `:halted?`, and `:blocked?`, plus the current code layout and engine state. W1 imports unconditionally. A later cache may skip only after proving all relevant keys agree with its last materialized snapshot; a retained private token alone is not proof. In particular `(assoc parked :pc 2 :halted? false)` must execute the requested state. Import a copied or privately owned active shadow so running an old or externally modified map never mutates another returned map.
2. **Wasm-to-host:** before every state-consuming `engine/*` call, including handle-effect, pinning, collection, scheduler, park/resume and module work, materialize all eight execution fields onto the VM record. Materialize them also at every public `step`, `run`, restore or inspection return. Keep the record’s protocol implementations so `module/gc-roots` can inspect the current map. Dense memory synchronization without map synchronization is insufficient.
3. **Host completion:** the returned host state is the new authority, including its execution fields and every extension key. Apply only the current opcode’s specified post-service changes to that returned state, then reimport it into memory. Never overlay a pre-service header onto it. In particular handle-effect sets `:value` even for nonblocking effects; preserve that value, resource/heap/GC updates, stores, counters, queues and custody fields. A plain non-tail primitive call, by contrast, only updates pc/rd in the kernel and does not automatically set `:value`.
4. **Seam pc:** before a host service, the map contains the instruction’s absolute site pc, unless the source transition explicitly advances it first (such as parking in the FFI path). Capture the payload at the site; apply the opcode’s pc update exactly once after service completion. Mailbox state does not count as a completed guest instruction.

This is also a GC safety requirement: `:cell/new` may collect *inside* handle-effect, before the adapter receives its result. At that moment map-based registers, frames, continuation and value must already be current. Reuse the engine’s exact logical heap tracing rules; arena retention of a host value cannot substitute for marking the heap cells that value references.

### 1.3 Ownership, spilling, and collection

Start with a dense shadow synchronized on every register write and before every return to the host. The local is the execution operand; the shadow makes complete current state and GC roots available. Sparse saved frames and payloads are nevertheless mandatory: never retain the full dense shadow through a call or park. On a successful call/restore, replace the active shadow; clear abandoned mailbox/argument roots when no longer needed. A parked VM's observable active register vector may still contain dead slots, as the reference VM does; do not confuse those current-state roots with the sparse payload.

No collection runs while wasm holds unreported local values. Initially run arena collection only after full materialization at an exit; engine heap collection may run inside a host service after the mandatory map barrier in §1.2.1; later dirty-slot spilling can replace write-through with a separate GC/inspection barrier. Mark active registers, frames, value, continuation, parked/wait/ready entries, stores, module stores, engine heap and pinned/outgoing values, constants, and pending mailbox arguments. Traverse trusted wrappers via `values/payload` and preserve engine `gc-children` treatment of code. Integrate `engine/pin-refs` for arguments leaving through FFI. Keep JS arena ownership and the existing logical heap GC distinct, with explicit roots connecting them. Reclaim only unreachable implementation storage, not stream history or persistent datoms. Avoid handle reuse initially; later reuse needs generations checked at every host import.

External state inspection produces immutable host snapshots. Resuming one snapshot cannot mutate another snapshot's frames, values, or registers. Persistent snapshots share immutable arena objects, never a mutable register buffer. The first implementation may copy the active dense vector at every public IVM transition; optimize only after snapshot-alias tests pass.

## 2. Code generation strategy

### 2.1 Choice among the alternatives

| Strategy | Benefits | Costs / semantic obligations |
|---|---|---|
| Fully structured body functions with direct guest `call` | Small fast code for ordinary functions; conventional optimizer input | Captured continuations require every caller activation to be materialized and abandoned; arbitrary resume PCs need secondary entries; suspension cannot retain native stack frames. A shadow stack alone does not unwind native callers. |
| One interpreter loop over instruction records | Small wasm binary, trivial append and pc resume | Runtime operand decoding and memory-indexed registers on every step; does not exploit known register operands as locals. Useful as a test oracle, not the proposed final lowering. |
| **Compiled body dispatch plus trampoline** | Static register locals and operands, arbitrary entry PCs, appendable bodies, constant native stack depth, explicit continuations | More generated code than interpreter; indirect dispatch and spills at transfers; per-body branch tables may be large. |

Choose the third. A body function has conceptual signature `(state-ptr i32, entry-index i32, budget i32) -> status i32`; updated budget/state lives in the header. All bodies share the same signature for a function table. The trampoline resolves absolute pc, calls one body function, then loops on transfer. A body **never invokes a guest callee directly**. Guest call saves a return frame (unless tail), installs callee state, and returns TRANSFER. Return installs caller state and returns TRANSFER; final return returns HALTED. The trampoline's wasm `call_indirect` is an implementation dispatch, not the guest call stack.

Consequently flattening is exact and incremental: every pending guest call is already a sparse memory record before its callee runs. Capturing K copies/shares those immutable records; abortive application replaces the current chain and returns from the one active body. No stack walk, Asyncify, native-stack copying, wasm exception unwinding, or retained JS Promise continuation is needed.

`return_call` is **not required or emitted**. Tail guest calls retain K unchanged and replace the active lexical chain/register file. This gives bounded native stack depth on engines without the tail-call extension. A later direct-call optimization may be allowed only for a proven noncapturing, nonsuspending region with a deoptimization protocol; it is not part of this plan.

### 2.2 Structuring algorithm

1. Admit the image with the exact contract check and `register-image-defect`. Preserve body ranges and the original source instruction vector as metadata. Reuse `body-liveness`/`live-slot-index`; do not derive live sets from the optimized wasm graph.
2. Build each body's CFG using the existing successor rules: conditional two-way edge; jump target; no successor for return/halt/resume or tail call; ordinary call and other operations resume at pc+1. Jumps must remain inside their body. Do not assume a hand-authored valid image is the structured syntax emitted by `derive`.
3. For W1, make every instruction an entry case. Load locals from shadow, enter a wasm `loop` containing nested void `block`s and a `br_table` selecting the pc case. Each case consumes its operands into locals, leaves an empty operand stack, updates the next pc/case, then branches to dispatch or returns one status. All branch targets have the same empty result type. Functions return their status only at the common exit. Invalid entry takes a qualified fault path, not execution of arbitrary memory.
4. For W6, merge straight-line instruction cases into basic blocks, retaining leaders at targets, body entries, all resume pcs, and every externally observable step/exit site. Preserve an entry path for each supported pc; debug one-step mode can retain instruction cases. General irreducible CFGs keep dispatch. A subsequent measured optimization can compute dominators/SCCs and structure single-entry regions as `block`/`loop`/`if`, retaining a local dispatcher for multi-entry SCCs. There is no need to ship a full relooper to get a correct backend.
5. Check budget at instruction boundaries for bounded/debug execution. A budget yield materializes the **full** active state and returns YIELDED; it is not a new guest suspension site, has no invented sparse live operand, and is not serializable as an r2 continuation. `step` counts one guest instruction, even when several host service transitions implement it.

### 2.3 All 23 opcode lowerings

Here `r` means ValueRef local, `next` means pc+1, and “host service” means a synchronous implementation transition on the existing engine state after returning from the body, not an application callback or new external FFI path. The mailbox records the already-read operands so re-entry never repeats an effect.

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
| `:return rs` | Read value. If K nonempty, pop frame, nil-initialize caller register vector, restore saved pairs and dest, frames and store-of, set value and return-pc; TRANSFER. Else set only value, halted true, store-of nil: keep pc at the executing return instruction and preserve active registers, frames, empty K and all other fields. The return-result restore case first changes pc as specified in §3.2. |
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

### 2.4 Exact host-function effect completion

For `:call` whose `apply f args` result satisfies `module/effect?`, first use the state returned by `engine/check-callee-effect!`. Build `payload-of` at the original call site from the materialized state and relocated combined instruction. Tail calls use `:resume-mode :return-result`, `:dest nil`, and their verified empty live set; non-tail calls use `:write-result` and rd. Pass `{:park-entry-fns (park-entry-fns payload)}` to `engine/handle-effect`, with builders for `:stream/put`, `:stream/next`, and `:module/require`. The module handler uses the require builder as fallback for `:module/try-require`; gated stream poll uses the next builder as fallback. Do not wire only the dedicated stream opcodes.

Given `{:state state :value value :blocked? blocked?}` from handle-effect:

- Blocked: return `(assoc state :pc (inc site-pc))`, with no destination write and no return transition, even for a tail call. The payload retains the pending destination/return mode for later delivery.
- Nonblocked tail: run `return-transition` on the **post-effect state** and value. Do not pre-increment pc: if K is empty, this immediate path halts at the call site. This differs observably from a blocked tail effect later restored at the payload’s next pc.
- Nonblocked non-tail: set pc to next and write value into rd of `(:registers state)`, preserving the rest of the post-effect state, including `:value` set by handle-effect.

Never reconstruct the result from the pre-effect wasm header: module attachment, store/module-store writes, resources, heap/GC, counters and custody bookkeeping may have changed. A non-effect host result follows the separate plain-result arm of `register.cljc`, including preservation of old `:value` on a non-tail call.

## 3. Continuation capture and resume

### 3.1 Exact external/native shape

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

Return-frame shape is `{:site-pc :return-pc :frames :regs :live :dest}` plus optional store-of. Frame order, sparse pair order, absent-vs-present optional fields, and keyword values are preserved. The `image-row` convention includes the one-past-last-row endpoint for address helpers; that does not make it a valid write-result continuation pc: continuation-defect still requires the site's and resume pc's body to agree.

Materialization recursively resolves ValueRefs, never puts numeric handle IDs into payload values. Import performs the inverse into immutable records. The capture-time segment/hash/table are validation data and must survive later attach unchanged. They can share immutable objects in memory, but a plain-data export must contain the actual required image data. A wasm function index, memory pointer, instance id, or private ABI version is not a guest format identity.

### 3.2 Restore algorithm and failure ordering

Expose one adapter restore with the same `(base entry val) -> state` contract and use it from both engine resume paths:

1. Check `:format` equals `:yin.debruijn.register` and entry `:image` is in base `:images`; preserve `:rule :continuation-format`, `:reason :foreign-format`, and diagnostic context. Do **not** compare entry hash to current base hash.
2. Run the existing `effects/continuation-defect` against the entry, preserving deterministic first-defect results, including capture-time hash recomputation and per-component image/table validation.
3. Check the actual `vm/machine-data?` predicate on val; reject unsupported host functions/errors with `:rule :resume-value`. Do not turn an error-shaped ordinary map into an exception.
4. If request-sent, enqueue `ffi/response-wait-entry` using call-id and remain blocked with `:yin/blocked`, halted false. Do not deliver writer acknowledgement into rd.
5. Otherwise remove call-id from parked when present and apply `ffi/call-result` to its response. For write-result allocate the receiving body's register count, nil-fill, copy sparse pairs, then write dest; restore pc, frames, K, store-of, value and halted false. For return-result first assoc `:pc (:pc entry)`, `:frames (:frames entry)`, `:continuation (:continuation entry)`, `:store-of (:store-of entry)`, and `:halted? false` onto base, leaving `:registers` equal to `(:registers base)`; then run the ordinary return transition. With empty captured K this halts at the entry’s next pc, retains base registers and captured frames, and clears store-of. With nonempty K, the popped caller frame replaces pc/registers/frames/store-of as usual. Preserve base's store, resources, queues, counters, modules, and code space.

Do not clear blocked universally in the low-level restore: the engine clears it in ready-queue resumption; direct paths preserve the source's exact field behavior. Public APIs should route through engine scheduling instead of manufacturing a partially cleared state. Failed restoration must not commit a mutated arena/state snapshot: stage validation and allocations before publishing the successful transition.

A `:call` of a trusted reified continuation uses the distinct internal path: `engine/operator-kind` ownership classification first, `engine/continuation-argument` arity check second, format/image membership third, then write-back; it deliberately skips continuation-defect/machine-data gates. For an unowned continuation with wrong arity, ownership fails first with `{:reason :foreign-value :kind :continuation}`, not `:continuation-arity` (and not the reviewer’s `:foreign-continuation`). A live primitive in an internal capture is valid and must not be rejected merely because it could not cross a wire. This is abortive, and applying a retained capture more than once is allowed; do not mutate its saved records. Explicit parked-id consumption is separate and removes that parked map entry.

### 3.3 Host transport and different layouts

Same-layout native payloads with transportable values can round-trip between interpreted register and wasm execution. A JS/native boundary inside the same task may carry trusted wrappers through the arena; it is not a serialization boundary. On a real stream boundary use existing lifting/lowering and codecs, preserve literal-map escaping and authenticated resource rebinding, and refuse values that those facilities cannot encode. Never serialize host slots or mint a Closure from an untrusted map.

For the present module/linker path, keep the existing non-canonicalizable continuation refusal described above. If a future authorized transport carries register continuation coordinates image-relatively, it must translate **site-pc, pc, every return frame's site/return pc, and recursively all closure body pcs**, attach admitted origins, reconstruct self-consistent segment/hash/live metadata, and apply normal native restore. Raw payloads with absolute pcs must not be fed to a differently ordered receiving layout merely because one R is present. W0 must gate the native transport API to a verified matching layout/prefix or return qualified `:continuation-format`; this transport restriction does not change same-task `register-restore` semantics. General rebasing and canonical semantic UCF migration remain outside this backend's completion claim.

## 4. Blocking, effects, and FFI protocol

### 4.1 Embedding contract

The instance exports conceptual `run(state, budget)`, `step(state)`, `read-state(state)` and validated state-install operations. The host-facing adapter provides `create/load`, `attach`, `run`, `step`, `resume-parked(id, value)` and `restore-ready(base, entry, value)`; names are proposed ABI, not existing functions. Public native payload imports go through §3 before an internal wasm installer can write records. All these entry/exit paths obey §1.2.1: callers can directly read or assoc the VM map keys, host calls see current map roots, and every post-host state is imported before wasm continues. `read-state` is a convenience, not a prerequisite for correct map contents.

Use finite status values: TRANSFER (continue trampoline), HOST_SERVICE (complete current instruction), YIELDED (budget), BLOCKED, PARKED, HALTED, ERROR. These statuses are private scheduling results; exported VM maps retain exact `:blocked?`, `:halted?`, `:value`, wait-set, ready-queue, and parked behavior. **Blocking is a normal return, never a trap.** Unexpected wasm validation/memory traps are backend faults and never substitutes for `:yin/blocked` or an FFI error envelope.

The CLJS adapter supplies the shared engine's step and restore seams. For strict `IVM/step`, run one guest instruction or the existing scheduler-round when inactive. For `IVM/run`, honor `engine/run-loop` ordering: active work first, then wait polling and ready queue, then halt; wrap bridge dispatch with the equivalent of `ffi/maybe-run`. An optimized run chunk may execute many pure instructions, but returns before every host service and exposes the same boundary state. It must not poll early, reorder ready entries, or stop on a halt while ready work remains. Preserve `halted-with-empty-queue?` rather than equating any private HALTED status with public completion.

A host service mailbox is implementation glue to the same engine, with no app callback. The adapter may invoke pure primitives and sanctioned stream-handle operations exactly as the reference engine does. External host effects remain request/response streams. JS event handlers only classify and append plain-data events; a separately driven pump polls them and calls the adapter. No browser event callback resumes a retained wasm activation. Service completion is synchronous within the current instruction; asynchronous external results arrive only through the existing stream protocol. Re-entry of the same task while a transition is pending is prohibited.

### 4.2 Stream and park transitions

Stream make/cursor/close finish without suspension under the current contract. Put-full and next-blocked create per-site wait entries using the same builders as register.cljc. Advance pc once, set blocked true/halted false/value `:yin/blocked`, and return to the scheduler. Explicit park instead produces the parked record as value and sets halted true; it is not automatically blocked and has no wait-set entry. The scheduler may still have other ready work.

Resume from a ready entry uses `engine/resume-from-run-queue`: pop FIFO entry, validate resource-update keys, merge updates into resources, clear flags, and handle terminal wake diagnostics before adapter restore. The same errors must occur whether a stream failed immediately or after blocking. Exported entries contain no callbacks, raw stream handles, timers, or stale service-mailbox data. `effects/wait-entry-defect` is an acceptance oracle only for its four newly built stream/FFI forms: put/next writers/readers and request-sent/call-id writer/reader entries. Do not apply it universally to the wait set: link-request, link-response, install and gated observe entries are outside its shape vocabulary. For these, check `effects/continuation-defect` on the register payload plus the owning module/engine reason-specific fields and transitions. Do not apply a newly parked-entry oracle to stale-key-bearing ready entries either.

### 4.2.1 Custody gates are preserved engine semantics

Retain `:yin.k/gate`, `:yin.k/issued`, `:yin.k/closes`, entry issue numbers, and every extension key across synchronization and public map edits. Use `vm/gate-mode` and existing engine/FFI/module handlers; do not replace them with one generic “gate means error” switch.

- Any gate suppresses `ffi/put-request` append and returns full, so FFI retains its request as a request-sent writer. Link misses likewise park as link-request without minting a response cursor or appending; already-resolved module and install-join branches still follow the handler’s ordering.
- Any gate suppresses autonomous link retry/response scans, FFI response polling, and put/next retries in `check-wait-set`. Gated install children are stamped/stepped only in running mode; ungated scheduling remains unchanged.
- Running stream cursor creation creates the sealed reference and unminted resource cell without a stream cursor call. Exporting/ended immediate observation paths refuse through the existing gate checks.
- Running put uses the engine’s deferred observation path and stamps a blocked writer with `:yin.k/issue`, advancing `:yin.k/issued` independently of id-counter. Running blocked poll becomes `:reason :observe, :op :poll`, using the call-site next builder fallback; its application remains the driver’s operation.
- **Current source nuance:** running stream close queues a record in `:yin.k/closes`, increments the issue counter, returns nil and does not call the stream handle. Exporting/ended close refuses. The review’s claim that close refuses under *any* gate does not match the current `handle-close` implementation.

W3 tests these transitions through both direct stream opcodes and effect-returning host calls, with scripted handle-call counts proving that deferred operations do not touch streams. W4 completes the module install/attach gate cases. Keep driver apply operations routed through their existing gate checks and the same synchronization barrier.

### 4.3 FFI two-step, without duplicate sends

1. `ffi/require-call-pair!` runs before allocating an id or parking. Read args in vector order, pin references, build the boundary payload, derive `ffi/call-id` from task identity and park-id, and park under that correlated id. Increment the shared id-counter once through park.
2. Build `apply2/request` and invoke `ffi/put-request`. On ok, enqueue response-wait-entry. On full, enqueue payload plus request-sent true, call-id, op, reason put, call-in stream id and the exact request datom. Other outcomes preserve the existing qualified error. Under any custody gate put-request returns full without appending; retain the request for the driver rather than retrying it autonomously.
3. The ungated engine retries a full writer. Its wake means the request append completed: restore converts it to a response reader, strips stale wake/writer fields through `ffi/response-wait-entry`, and stays blocked. It must neither append the request again nor advance the guest beyond the pending result.
4. On correlated response, terminal response-loss handling runs first, then call-result unwraps success/error, parked call-id is removed, and rd or tail return receives the result exactly once. Use constants from `yin.vm` for reserved resources.

Test full→ok→response, immediate ok→response, unrelated response ids, bridge exceptions, response loss, repeated scheduler polls, and two tasks with the same local counter. Keep the bridge in the existing host interpreter; do not implement wasm imports named after arbitrary FFI operations.

## 5. Modules and code growth

Compile each independently admitted image into a wasm Module whose body entry functions import the same ABI memory and services. Instantiate it for the receiving task and install its functions into the task's function table (or use a host table of exports for the first trampoline implementation). This table maps implementation body ids to uniform functions; it is not the guest offset table.

Attach is a staged, append-only transaction:

1. Apply the same contract/validator gates as `attach-image`. Empty images are no-ops; duplicate component R is a no-op. Production admission retains the existing A-to-R derivation check before executable attachment.
2. Compute offset = held instruction count. Relocate every operand whose opcode-table kind is `:pc`; shift body start/end. Compute the exact combined segment and `register-hash`, and append `[component-R offset length]`.
3. Compile/instantiate the new component, reserve directory/table capacity, install its body entries, then publish the new code-layout generation atomically. Prefer component-relative constants plus an instance base offset so the same compiled Module can serve different task layouts. Every pc generated from a component-relative coordinate adds the instance base exactly once before entering public state: header/exit pc, payload site/next pc, return-frame site/return pc, and minted closure body-pc. Incoming absolute restore/return pcs are already absolute and must not receive another base. Resolve absolute pcs through the combined body directory and subtract the selected body start for local dispatch.
4. Preserve pc, registers, frames, K, all pending payloads, stores, and queues. No existing function slot or instruction pc is renumbered; old compiled instances remain reachable. Failure before publish leaves the old task executable. Attach only while wasm is at a materialized boundary.

For every boundary payload, fetch the instruction as `(nth (:instructions (:segment vm)) (:pc vm))` from the current relocated **combined** segment at the materialized absolute site pc. Pass that instruction and current segment/hash/images to `effects/continuation-payload`; never pair component-relative site metadata or a component-only segment with absolute payload pcs. The cached component image is compilation input, not the current capture-time code space. Even where the live operand is numerically unchanged by relocation, all payload coordinates and validation context must agree.

An empty-image creation seeds `:images [[empty-R 0 0]]`, `:hash nil`, `:pc 0`, `:registers []`, and `:halted? true`. Attaching a nonempty image of length n at offset 0 keeps the empty row and produces `[[empty-R 0 0] [R 0 n]]`; it does not unhalt the task, resize registers, or initialize a body. Reset or a valid execution-entry transition performs that separate work. A later capture includes `:images` because the row count is two, even though only one row contains instructions. Keep the zero-length row in payload validation and layout operations; it is distinct from the rule that attaching an empty image is a no-op.

There is no machine-code patching and no need to grow an existing wasm Module's function section. Table growth/directory append installs new functions. Old call frames use absolute return-pc and resolve in the grown directory. Captures retain their old segment/hash for validation, but resume sees the current code space. Reset preserves offset rows as in the kernel; load-image replaces the code space and resets execution, so old continuation membership checks can fail.

`spawn-module` creates a fresh task/memory/arena/owner/counter/resources context, with exactly the composition options copied by the existing kernel. It does not borrow the parent's mutable register shadow or per-module store. Reuse the module scheduler child-task lifecycle, encoder/decoder and `module/IModuleKernel` seams. Closure lifting uses image-relative body references and store-of; lowering validates binding/format, attached origin, and arity against actual closure instructions before minting the receiver's trusted value. Module store snapshots and authenticated stream/cursor lowering remain engine work. Cache compiled code by A, r2 derivation/checksum, wasm ABI/emitter version and feature profile; R alone is not executable admission authority.

## 6. Implementation slices and acceptance gates

### Test harness common to every slice

Build an explicit differential adapter rather than replacing the existing tests' private `register-run` vars by global mutation. Parameterize/extract shared fixtures when implementation is authorized. Each case receives independent scripted streams with identical inputs, capability secret, primitive registry, origin and counter setup. Compare interpreted register against wasm after each guest instruction and scheduler event; also compare semantic execution where the existing A→R fragment permits it.

Use `test/yin/vm/semantic/corpus.cljc` **programs and literal goldens**: project AST to A, assert the literal vector/address, derive R, validate R/live sets, compile and instantiate wasm. These goldens specify syntax/address, not a runnable environment or expected final value for every entry. Supply deterministic f/g/h/k/x/y/c/loop bindings, stream fixtures, and genuine parked targets for resume cases; otherwise compare the same qualified refusal. Execute both conditional arms where applicable. Do not silently skip resume/terminal/closure cases or relabel unsupported cases as successes.

Use all fixtures/assertions of `test/yin/vm/debruijn/register_test.cljc`, including hand images, B0/B2 parity, stream scripts, continuation corruption, and IVM behavior. B0 normalization is suitable for final cross-model values but is insufficient for this backend: additionally compare raw register payload shapes, stores/module-stores, pc, frame order, K, counter, flags, wait/ready/parked entries and emitted request traces. Compare resources by authenticated logical identity/cursor progress and stream emissions, not by host object address. Across independent tasks use the same explicit owner inputs; never erase owner checks. Compare failures by qualified rule/reason/outcome and relevant message assertions.

### W0 — freeze the ABI and executable oracle

Deliver the test adapter, feature probe, pinned assembler/validator, ABI document and deterministic fixture manifest. No new guest semantics. Gate every nonempty image before codegen. Separate native payload transport from linker export capability.

Acceptance: all semantic goldens retain their literal vector/address; all derived images retain R and exact live operands. Existing register tests pass unchanged on the reference lane. Invalid contract, Rule R, unknown opcode, cross-body target, bad live set and empty-image cases have identical admission outcomes. Validate a minimal wasm instance and explicit resource-limit diagnostics. Establish matching-layout payload transport and qualified refusal for incompatible layouts. Freeze the map/memory authority protocol, including external assoc, old-state replay and preservation of unknown engine extension keys; do not defer that contract to performance tuning.

### W1 — pure register execution and body dispatch

Implement arena, shadow, body dispatcher, constants/moves/loads/branches, closures, explicit calls/returns/halt, primitive results, store/define/gensym, one-step materialization. Effects/continuation execution remains explicitly unavailable until its slice, rather than silently emulated as a completed wasm feature.

Acceptance: register literal/load-bound/free/store/nested-closure/multi-arg/compose/branch/B0/B2/gensym tests; pure semantic corpus entries including nested-if-same-rd, body-queue-order, define-then-call and unreachable structural slots. Add nil-fill/extra-drop, zero truthiness, frame shadowing, backward/irreducible valid CFGs, source/destination aliasing, bounded yields, deep non-tail recursion and long tail recursion. Assert guest K behavior and bounded native call depth. Preserve snapshot immutability between steps. Assert direct map pc/register/frame reads, externally assoc-modified entry state, and halt/final-return preservation of the executing pc, registers and frames (including nonempty K on halt).

### W2 — continuations and restore

Implement payload materialization, reification, internal continuation application, explicit park/resume and external restore. Use existing validators and engine gates.

Acceptance: current-continuation, park-and-resume, bytecode-resume, every tampered/refusal test in register_test; semantic corpus park/resume productions with valid fixture targets or matching errors. Add nested non-tail capture then abortive invocation, multi-shot capture, tail return-result, live Closure versus live primitive distinctions under machine-data?, host-function/error resume refusal, error-shaped data success, dead-slot nil restoration, snapshot alias isolation and both native interpreted→wasm/wasm→interpreted payload round-trips. Assert expected linker non-canonicalizable refusals rather than claiming transport support from same-process tests. Add return-result fixtures with empty and nonempty captured K, deliberately different base pc/registers, and exact post-restore pc/register/frame assertions. Add an unowned wrong-arity continuation case proving foreign-value precedes continuation-arity, and an owned wrong-arity/foreign-format case proving arity precedes format.

### W3 — streams, shared scheduler and FFI

Implement typed effect mailbox completion, site wait builders, scheduler seam, call-effect handling including tail effects, bridge wrapper, FFI request/response logic and pinning.

Acceptance: every immediate/blocking stream and bridge success/error test in register_test; semantic corpus streams/ffi/park with scheduled events. Add full-writer two-step and stale-key checks, pending-ready-after-halt, lost/closed stream outcomes, missing call-pair before id allocation, call-result errors, repeated polling without duplicate append, effect permission checks and resource-update validation. Compare value/store/parking and external emission traces after each wake, not only final values. Exercise the §2.4 host-call matrix: immediate/blocked × tail/non-tail, including put/next, module require/try-require builder fallback (full module installation in W4), and running poll’s observe fallback. Assert blocked pc advances without rd write or tail return, and post-effect state mutations survive each completion. Compare immediate empty-K tail completion at site pc with restored blocked completion at entry pc.

Add a low-threshold `:cell/new` host-effect test whose only live cell roots occur in newly materialized registers/frames/K, forcing collection inside handle-effect; it must retain those cells. Check `:value` after each nonblocking effect and verify a plain non-tail primitive leaves the previous value unchanged. Test all supported gate modes (ungated/running/exporting/ended), no handle calls for deferred FFI/cursor/put/poll/close/link work, separate issue/id counters, queued running closes versus exporting/ended refusals, suppressed wait polling, and driver apply synchronization. Scope wait-entry-defect to its four shapes; use payload plus owning-shape checks for observe/link/install. These are W3 correctness gates, not deferred GC optimizations.

### W4 — attach, modules and lifetime correctness

Implement independent image instances/function-table append, module-kernel adapter, closure/resource transport, store-of restoration, reset/load distinctions and arena/heap root integration.

Acceptance: rerun both corpora completely; add A-calls-B-while-C-attaches followed by ordinary return, park-before-attach then resume, stale hash but present image success, absent-image refusal, duplicate/empty attach no-op, empty-base row, layout-image rehashing, independent spawn-module counters/stores and module require in a tail call. Cross-layout closure lower validates arity/format and private store behavior. GC tests retain captured live values and FFI-pinned refs, release unreachable scratch records, and never free values held by old immutable state snapshots. No return or resume shrinks the code space. Reuse one compiled component in two different attachment orders and assert absolute exit/capture/return/closure pcs and combined-segment payload checks. Assert empty-base attachment retains both offset rows, halted true and registers [], then reset and capture with the two-row table. Complete gated install-child scheduling and link-response/install oracle tests.

### W5 — lane completion and release gate

Register wasm as an explicit Node backend selection with no changes to the r2 opcode table. Freeze ABI versioning and deterministic binary generation. Run all named register tests and every semantic golden through the harness; report unsupported canonical-semantic fragment cases as named refusals with reasons. The wasm backend must not silently narrow behavior relative to an admitted register image except documented resource limits or preexisting transport refusal boundaries.

Acceptance: required corpora pass in full, every opcode exercised, public IVM/IVMState behavior matches (including eval/environment refusals), differential traces agree, no host handle leaks into wire payloads, and all binary artifacts validate. Run focused JVM reference tests with the existing `clojure -M:test -n yin.vm.debruijn.register-test` invocation, the existing Node tests through the documented `clj -M:cljs -m shadow.cljs.devtools.cli compile test` lane, and the new wasm harness in that lane. Register a dedicated wasm build/task if necessary rather than pretending one exists today. Follow repository quick/slow phase gates and existing Dart regression lane for shared CLJC edits. No such tests were run for this report; only the findings file is changed.

### W6 — measured optimization, after correctness

Measure compilation latency, binary size, native stack depth, arena/heap retention, dispatch cost, call and suspension cost, and total end-to-end time against interpreted register on the same Node runtime. Only then coalesce blocks, reduce host crossings, add dirty spilling, or use optional Binaryen passes. Re-run all parity and binary validation gates for each transformation. A performance regression is possible; making wasm work is not evidence it is faster.

### Explicit non-goals

No new opcode/contract version, alternate A→R allocator, native stack continuations, async-stack capture, universal cross-model continuation conversion, new portable continuation schema, full semantic VM feature parity beyond the established native fragment, direct arbitrary host FFI imports, WASI runtime, threads/shared memory, wasm GC dependency, numeric NaN boxing, standalone host-free runtime, or immediate JVM/Dart wasm embedding. No promise to execute malicious wasm from the network: wasm is generated locally from admitted images. No optimization requirement before W5 correctness.

## 7. Risks and open questions

1. **Native continuation transport is narrower than the prompt's broad wording.** The sources support same-model payloads and qualified transport refusal, while the module encoder currently refuses continuations. Keep this distinction visible in API docs/tests. A full portable continuation design would require separate authorization and compatibility work, not an ad hoc wasm-only decoder.
2. **Absolute pc layout compatibility.** The kernel's format check tests image membership, not complete receiving-layout equivalence. That suffices for grow-only same-task use; it is not proof that arbitrary imported absolute pcs mean the same thing. The transport adapter must enforce matching layout/prefix or use a separately specified rebaser. Preserve legacy restore diagnostics without presenting the legacy check as stronger than it is.
3. **Host boundary overhead.** A ValueRef implementation deliberately leaves semantic services in CLJS. Measure whether compiled dispatch helps before building a second collection/value/store implementation. It remains a real lowering: instruction selection, register operands and branches are compiled, not fetched as bytecode by a host interpreter.
4. **Persistent public state over mutable memory.** Snapshot aliasing and old-state retention are correctness concerns, not just GC details. Decide in W0 whether each public state owns a copy-on-write arena root or a copied active shadow; require old-state replay tests before optimizing.
5. **Host exceptions and partial effects.** Catch host failures at the adapter boundary for shaped diagnostics, then reproduce the existing public exception behavior. Do not roll back an already appended stream event or retry a completed effect. The mailbox needs a single completion/commit rule.
6. **Module growth and code retention.** Wasm module code cannot be appended in place. Many image instances may increase memory and startup cost; function-table limits and compile failures require explicit resource refusals. Code unloading while live absolute pcs exist is out of scope.
7. **Large bodies and dispatch size.** One instruction per case can create deep block nesting and large binaries. W0 should pin practical limits; W6 can shard dispatch into bounded pages/helpers while keeping entry-pc precision. Such helpers must never hold suspended guest state in their native stack.
8. **Predicate drift.** machine-data?, value wrappers, integer carriers and effect authorization have evolved beyond old doc prose. Reuse the source helpers, and make ABI tests fail when their contracts change. External plain-data serialization and internal trusted-wrapper values are separate gates.
9. **Canonical admission and provenance.** Bare hand images remain useful under the existing raw kernel API; production module execution must retain A derivation verification. Compiled wasm caches are disposable artifacts. They cannot become another authority for code or continuation identity.
10. **Host coverage.** First shipping target is Node/CLJS with pinned wasm feature probes. Browser, JVM-embedded and Dart-embedded engines may have different resource limits. They can share the binary ABI later, but must rerun value/stream/transport parity rather than claiming portability from wasm validation alone.

The implementation is complete when an admitted r2 image has the same values, store transitions, continuation records, refusals, parking/wake behavior and module growth under the wasm lane as under the existing register kernel, with no suspended computation held on the wasm stack.


## Reviewer round 1 responses

The review was read in full. All seven requested corrections are accepted in substance after checking source; narrower incorrect details in the review are explicitly refuted below. References use the current source paths and line numbers inspected for r2. No source behavior was changed to fit the review.

| Finding | Disposition and evidence | Folded into |
|---|---|---|
| **MAJOR-1 (:pc on empty-K return-result)** | **ACCEPTED.** `src/cljc/yin/vm/debruijn/register.cljc:461–468` assocs entry pc/frames/K/store-of/halted before return-transition, without assigning registers. The empty-K arm at `:432` changes only halted/value/store-of; nonempty K writes return-pc/registers at `:424–431`. | §3.2 exact pre-return assignment and empty-K outcome; W2/W3 differentiated pc tests. |
| **MAJOR-2 (Clojure-map/linear-memory synchronization at engine seams)** | **ACCEPTED.** `register.cljc:955–957` reads map fields for GC roots. `engine.cljc:2541–2558`, `:2587–2614`, and `:2720` collect through those roots inside cell allocation; `:2743–2749` writes effect value to returned state. `test/yin/vm/debruijn/register_test.cljc:444–445` directly assocs pc/halted before run; `:490–491` directly reads pc/registers. A memory-only snapshot would fail both cases. | §1.2.1 authority/barrier protocol, §1.3 GC distinction, §4.1, W0/W1/W3 acceptance tests. |
| **MAJOR-3 (:call host-fn effect contract)** | **ACCEPTED.** `register.cljc:709–719` distinguishes typed effects from plain results; `:533–545` supplies all three builders; `:564–580` advances pc only on blocked/non-tail paths and returns through post-effect state on immediate tail completion. `src/cljc/yin/vm/module.cljc:579–584` gives try-require its require fallback. | §2.3 call row, new §2.4 full completion contract, W3 call-effect matrix and W4 module tests. |
| **MINOR-1 (continuation check order)** | **ACCEPTED**, with the reviewer’s error-name claim **REFUTED**. `register.cljc:676` calls operator-kind before the continuation arm at `:729–733` checks arity then format. `engine.cljc:83–90` emits `:reason :foreign-value` with kind continuation, **not** `:foreign-continuation`; `:103–112` emits continuation-arity. The original plan’s format/image and internal-gate semantics remain unchanged. | §2.3 and §3.2 explicit check order and actual diagnostic; W2 precedence fixtures. |
| **MINOR-2 (halt/final-return state preservation)** | **ACCEPTED.** `register.cljc:432` and `:742–747` preserve pc/registers/frames and other fields when halting; `register_test.cljc:285–295` reads retained registers after halt. This instruction-path rule does not override the prior pc assignment in return-result restore or imply an immediate tail call increments pc. | §2.3 halt/return rows, §2.4 distinction, W1/W2/W3 tests. |
| **MINOR-3 (wait-entry oracle scope + custody-gate parity in W3)** | **ACCEPTED**, with two reviewer details **REFUTED**. `src/cljc/yin/vm/debruijn_register_effects.cljc:371–379` recognizes only four shapes; `module.cljc:601–636` supplies install/link waits; `engine.cljc:2696–2709` creates observe waits. Gated FFI is `src/cljc/yin/vm/ffi.cljc:55–63`; suppressed polling is `engine.cljc:2162–2189`. However `engine.cljc:554–559` uses **`:yin.k/issued`**, not next-issue, and `:744–761` **queues close under running**, while `:484–492` / `:2713–2716` refuse exporting/ended. The original plan’s generic reuse of engine gate semantics is retained and made specific to this code, not the reviewer’s “close refuses under any gate” claim. | §1.2 state roots, scoped §4.2 oracle, new §4.2.1 gates, §4.3 retained FFI, W3/W4 cases. |
| **MINOR-4 (module base offsets, combined-segment payload reads, empty-base rows)** | **ACCEPTED.** `register.cljc:184–227` relocates/appends without touching execution state; `:135–146` seeds empty-R row and halted/empty registers. `:525–530` supplies payload-of; `debruijn_register_effects.cljc:97–120` captures absolute site/next pc, runtime combined segment/hash, row identity and optional multirow table. `register.cljc:694–700` saves absolute call coordinates. | §5 base conversion and combined payload read requirements, explicit empty-base lifecycle, W4 multi-layout and zero-length-row tests. |

Validation of this revision: checked the seven findings against the cited source transitions and reviewed the full revised document for all seven required sections, 23 opcode rows, and W0–W6. Runtime tests are proposed acceptance work, not claimed as executed for this documentation-only revision.
