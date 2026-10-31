# Register images → WebAssembly: implementation plan

Date: 2026-10-18. Source baseline: `c49e565dd60ab6a3611c9c772665eca96d1ecd41` plus the working tree inspected for this report. This is a plan, not an implementation or a performance claim.

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
| Engine state roots | ValueRefs/tokens for store, module-stores, free-env, primitives, modules, wait-set, ready-queue, parked, resources, heap/gc, owner, origin/ancestry, and id-counter. The full existing state map is authoritative for engine extension keys. |
| Exit mailbox | Pending instruction site/op and argument refs, pending result/error ref, completion kind. Private implementation state, never a portable continuation. |

Pcs use 64-bit fields so wasm32 pointers do not silently narrow the existing layout domain (up to `2^52-1` in `compose`). Functions use small body-relative i32 dispatch indices after checked resolution. Reject resource/address exhaustion with a qualified backend diagnostic before allocation/attach; never wrap a pc, handle, or byte offset. The counter remains the existing host integer value and advances only through engine operations.

The program store is logically an immutable map, usually name-keyed but **not restricted to names**: `:store-get`/`:store-put` accept `:data` keys, including keywords and numbers in the corpus. Its linear-memory representation is a root ValueRef to the persistent host map, not a second wasm hash map. All writes go through `engine/put-active` → `engine/store-put`. Non-nil store-of selects only that module's store, with no ambient fallback. Private resources and heap are distinct from program store.

### 1.3 Ownership, spilling, and collection

Start with a dense shadow synchronized on every register write and before every return to the host. The local is the execution operand; the shadow makes complete current state and GC roots available. Sparse saved frames and payloads are nevertheless mandatory: never retain the full dense shadow through a call or park. On a successful call/restore, replace the active shadow; clear abandoned mailbox/argument roots when no longer needed. A parked VM's observable active register vector may still contain dead slots, as the reference VM does; do not confuse those current-state roots with the sparse payload.

No collection runs while wasm holds unreported local values. Initially collect only after a full materialization at an exit; later dirty-slot spilling can replace write-through with a separate GC/inspection barrier. Mark active registers, frames, value, continuation, parked/wait/ready entries, stores, module stores, engine heap and pinned/outgoing values, constants, and pending mailbox arguments. Traverse trusted wrappers via `values/payload` and preserve engine `gc-children` treatment of code. Integrate `engine/pin-refs` for arguments leaving through FFI. Keep JS arena ownership and the existing logical heap GC distinct, with explicit roots connecting them. Reclaim only unreachable implementation storage, not stream history or persistent datoms. Avoid handle reuse initially; later reuse needs generations checked at every host import.

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
| `:call rd fn-reg arg-regs tail? live` | Read operator and ordered arguments first. Use `engine/operator-kind`. Closure: nil-fill/drop extras to arity, append bound frame to captured chain, initialize L args and nil T slots; push exact sparse caller frame only for non-tail; set closure store-of and body pc; TRANSFER. Host fn: apply registered implementation, check typed effect authorization, route effect through engine; ordinary result writes rd/next or executes return transition for tail. Continuation: require exactly one arg and ownership, check format/image, abort current K and write back captured state; no external data gate on this internal path. |
| `:branch-false cond target` | Branch only when nil/false; all other values next. Empty operand stack on either edge. |
| `:jump target` | Set pc/case to the verified same-body target. |
| `:return rs` | Read value. If K nonempty, pop frame, nil-initialize caller register vector, restore saved pairs and dest, frames and store-of, set value and return-pc; TRANSFER. Else set value, halted true, store-of nil. |
| `:halt rs` | Set value, halted true, store-of nil. Does not pop K as return would; preserve other fields as the kernel does. |
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
5. Otherwise remove call-id from parked when present and apply `ffi/call-result` to its response. For write-result allocate the receiving body's register count, nil-fill, copy sparse pairs, then write dest; restore pc, frames, K, store-of, value and halted false. For return-result restore captured K/frames/store-of then run the ordinary return transition. Preserve base's store, resources, queues, counters, modules, and code space.

Do not clear blocked universally in the low-level restore: the engine clears it in ready-queue resumption; direct paths preserve the source's exact field behavior. Public APIs should route through engine scheduling instead of manufacturing a partially cleared state. Failed restoration must not commit a mutated arena/state snapshot: stage validation and allocations before publishing the successful transition.

A `:call` of a trusted reified continuation uses the distinct internal path: ownership and one-argument check, format/image membership, then write-back; it deliberately skips continuation-defect/machine-data gates. A live primitive in an internal capture is valid and must not be rejected merely because it could not cross a wire. This is abortive, and applying a retained capture more than once is allowed; do not mutate its saved records. Explicit parked-id consumption is separate and removes that parked map entry.

### 3.3 Host transport and different layouts

Same-layout native payloads with transportable values can round-trip between interpreted register and wasm execution. A JS/native boundary inside the same task may carry trusted wrappers through the arena; it is not a serialization boundary. On a real stream boundary use existing lifting/lowering and codecs, preserve literal-map escaping and authenticated resource rebinding, and refuse values that those facilities cannot encode. Never serialize host slots or mint a Closure from an untrusted map.

For the present module/linker path, keep the existing non-canonicalizable continuation refusal described above. If a future authorized transport carries register continuation coordinates image-relatively, it must translate **site-pc, pc, every return frame's site/return pc, and recursively all closure body pcs**, attach admitted origins, reconstruct self-consistent segment/hash/live metadata, and apply normal native restore. Raw payloads with absolute pcs must not be fed to a differently ordered receiving layout merely because one R is present. W0 must gate the native transport API to a verified matching layout/prefix or return qualified `:continuation-format`; this transport restriction does not change same-task `register-restore` semantics. General rebasing and canonical semantic UCF migration remain outside this backend's completion claim.

## 4. Blocking, effects, and FFI protocol

### 4.1 Embedding contract

The instance exports conceptual `run(state, budget)`, `step(state)`, `read-state(state)` and validated state-install operations. The host-facing adapter provides `create/load`, `attach`, `run`, `step`, `resume-parked(id, value)` and `restore-ready(base, entry, value)`; names are proposed ABI, not existing functions. Public native payload imports go through §3 before an internal wasm installer can write records.

Use finite status values: TRANSFER (continue trampoline), HOST_SERVICE (complete current instruction), YIELDED (budget), BLOCKED, PARKED, HALTED, ERROR. These statuses are private scheduling results; exported VM maps retain exact `:blocked?`, `:halted?`, `:value`, wait-set, ready-queue, and parked behavior. **Blocking is a normal return, never a trap.** Unexpected wasm validation/memory traps are backend faults and never substitutes for `:yin/blocked` or an FFI error envelope.

The CLJS adapter supplies the shared engine's step and restore seams. For strict `IVM/step`, run one guest instruction or the existing scheduler-round when inactive. For `IVM/run`, honor `engine/run-loop` ordering: active work first, then wait polling and ready queue, then halt; wrap bridge dispatch with the equivalent of `ffi/maybe-run`. An optimized run chunk may execute many pure instructions, but returns before every host service and exposes the same boundary state. It must not poll early, reorder ready entries, or stop on a halt while ready work remains. Preserve `halted-with-empty-queue?` rather than equating any private HALTED status with public completion.

A host service mailbox is implementation glue to the same engine, with no app callback. The adapter may invoke pure primitives and sanctioned stream-handle operations exactly as the reference engine does. External host effects remain request/response streams. JS event handlers only classify and append plain-data events; a separately driven pump polls them and calls the adapter. No browser event callback resumes a retained wasm activation. Service completion is synchronous within the current instruction; asynchronous external results arrive only through the existing stream protocol. Re-entry of the same task while a transition is pending is prohibited.

### 4.2 Stream and park transitions

Stream make/cursor/close finish without suspension under the current contract. Put-full and next-blocked create per-site wait entries using the same builders as register.cljc. Advance pc once, set blocked true/halted false/value `:yin/blocked`, and return to the scheduler. Explicit park instead produces the parked record as value and sets halted true; it is not automatically blocked and has no wait-set entry. The scheduler may still have other ready work.

Resume from a ready entry uses `engine/resume-from-run-queue`: pop FIFO entry, validate resource-update keys, merge updates into resources, clear flags, and handle terminal wake diagnostics before adapter restore. The same errors must occur whether a stream failed immediately or after blocking. Exported entries contain no callbacks, raw stream handles, timers, or stale service-mailbox data. `effects/wait-entry-defect` is an acceptance oracle for newly built waits.

### 4.3 FFI two-step, without duplicate sends

1. `ffi/require-call-pair!` runs before allocating an id or parking. Read args in vector order, pin references, build the boundary payload, derive `ffi/call-id` from task identity and park-id, and park under that correlated id. Increment the shared id-counter once through park.
2. Build `apply2/request` and invoke `ffi/put-request`. On ok, enqueue response-wait-entry. On full, enqueue payload plus request-sent true, call-id, op, reason put, call-in stream id and the exact request datom. Other outcomes preserve the existing qualified error.
3. The engine retries a full writer. Its wake means the request append completed: restore converts it to a response reader, strips stale wake/writer fields through `ffi/response-wait-entry`, and stays blocked. It must neither append the request again nor advance the guest beyond the pending result.
4. On correlated response, terminal response-loss handling runs first, then call-result unwraps success/error, parked call-id is removed, and rd or tail return receives the result exactly once. Use constants from `yin.vm` for reserved resources.

Test full→ok→response, immediate ok→response, unrelated response ids, bridge exceptions, response loss, repeated scheduler polls, and two tasks with the same local counter. Keep the bridge in the existing host interpreter; do not implement wasm imports named after arbitrary FFI operations.

## 5. Modules and code growth

Compile each independently admitted image into a wasm Module whose body entry functions import the same ABI memory and services. Instantiate it for the receiving task and install its functions into the task's function table (or use a host table of exports for the first trampoline implementation). This table maps implementation body ids to uniform functions; it is not the guest offset table.

Attach is a staged, append-only transaction:

1. Apply the same contract/validator gates as `attach-image`. Empty images are no-ops; duplicate component R is a no-op. Production admission retains the existing A-to-R derivation check before executable attachment.
2. Compute offset = held instruction count. Relocate every operand whose opcode-table kind is `:pc`; shift body start/end. Compute the exact combined segment and `register-hash`, and append `[component-R offset length]`.
3. Compile/instantiate the new component, reserve directory/table capacity, install its body entries, then publish the new code-layout generation atomically. Prefer component-relative constants plus an instance base offset so the same compiled Module can serve different task layouts. Existing absolute pcs resolve through the body directory; body/closure targets emitted at runtime add the base.
4. Preserve pc, registers, frames, K, all pending payloads, stores, and queues. No existing function slot or instruction pc is renumbered; old compiled instances remain reachable. Failure before publish leaves the old task executable. Attach only while wasm is at a materialized boundary.

There is no machine-code patching and no need to grow an existing wasm Module's function section. Table growth/directory append installs new functions. Old call frames use absolute return-pc and resolve in the grown directory. Captures retain their old segment/hash for validation, but resume sees the current code space. Reset preserves offset rows as in the kernel; load-image replaces the code space and resets execution, so old continuation membership checks can fail.

`spawn-module` creates a fresh task/memory/arena/owner/counter/resources context, with exactly the composition options copied by the existing kernel. It does not borrow the parent's mutable register shadow or per-module store. Reuse the module scheduler child-task lifecycle, encoder/decoder and `module/IModuleKernel` seams. Closure lifting uses image-relative body references and store-of; lowering validates binding/format, attached origin, and arity against actual closure instructions before minting the receiver's trusted value. Module store snapshots and authenticated stream/cursor lowering remain engine work. Cache compiled code by A, r2 derivation/checksum, wasm ABI/emitter version and feature profile; R alone is not executable admission authority.

## 6. Implementation slices and acceptance gates

### Test harness common to every slice

Build an explicit differential adapter rather than replacing the existing tests' private `register-run` vars by global mutation. Parameterize/extract shared fixtures when implementation is authorized. Each case receives independent scripted streams with identical inputs, capability secret, primitive registry, origin and counter setup. Compare interpreted register against wasm after each guest instruction and scheduler event; also compare semantic execution where the existing A→R fragment permits it.

Use `test/yin/vm/semantic/corpus.cljc` **programs and literal goldens**: project AST to A, assert the literal vector/address, derive R, validate R/live sets, compile and instantiate wasm. These goldens specify syntax/address, not a runnable environment or expected final value for every entry. Supply deterministic f/g/h/k/x/y/c/loop bindings, stream fixtures, and genuine parked targets for resume cases; otherwise compare the same qualified refusal. Execute both conditional arms where applicable. Do not silently skip resume/terminal/closure cases or relabel unsupported cases as successes.

Use all fixtures/assertions of `test/yin/vm/debruijn/register_test.cljc`, including hand images, B0/B2 parity, stream scripts, continuation corruption, and IVM behavior. B0 normalization is suitable for final cross-model values but is insufficient for this backend: additionally compare raw register payload shapes, stores/module-stores, pc, frame order, K, counter, flags, wait/ready/parked entries and emitted request traces. Compare resources by authenticated logical identity/cursor progress and stream emissions, not by host object address. Across independent tasks use the same explicit owner inputs; never erase owner checks. Compare failures by qualified rule/reason/outcome and relevant message assertions.

### W0 — freeze the ABI and executable oracle

Deliver the test adapter, feature probe, pinned assembler/validator, ABI document and deterministic fixture manifest. No new guest semantics. Gate every nonempty image before codegen. Separate native payload transport from linker export capability.

Acceptance: all semantic goldens retain their literal vector/address; all derived images retain R and exact live operands. Existing register tests pass unchanged on the reference lane. Invalid contract, Rule R, unknown opcode, cross-body target, bad live set and empty-image cases have identical admission outcomes. Validate a minimal wasm instance and explicit resource-limit diagnostics. Establish matching-layout payload transport and qualified refusal for incompatible layouts.

### W1 — pure register execution and body dispatch

Implement arena, shadow, body dispatcher, constants/moves/loads/branches, closures, explicit calls/returns/halt, primitive results, store/define/gensym, one-step materialization. Effects/continuation execution remains explicitly unavailable until its slice, rather than silently emulated as a completed wasm feature.

Acceptance: register literal/load-bound/free/store/nested-closure/multi-arg/compose/branch/B0/B2/gensym tests; pure semantic corpus entries including nested-if-same-rd, body-queue-order, define-then-call and unreachable structural slots. Add nil-fill/extra-drop, zero truthiness, frame shadowing, backward/irreducible valid CFGs, source/destination aliasing, bounded yields, deep non-tail recursion and long tail recursion. Assert guest K behavior and bounded native call depth. Preserve snapshot immutability between steps.

### W2 — continuations and restore

Implement payload materialization, reification, internal continuation application, explicit park/resume and external restore. Use existing validators and engine gates.

Acceptance: current-continuation, park-and-resume, bytecode-resume, every tampered/refusal test in register_test; semantic corpus park/resume productions with valid fixture targets or matching errors. Add nested non-tail capture then abortive invocation, multi-shot capture, tail return-result, live Closure versus live primitive distinctions under machine-data?, host-function/error resume refusal, error-shaped data success, dead-slot nil restoration, snapshot alias isolation and both native interpreted→wasm/wasm→interpreted payload round-trips. Assert expected linker non-canonicalizable refusals rather than claiming transport support from same-process tests.

### W3 — streams, shared scheduler and FFI

Implement typed effect mailbox completion, site wait builders, scheduler seam, call-effect handling including tail effects, bridge wrapper, FFI request/response logic and pinning.

Acceptance: every immediate/blocking stream and bridge success/error test in register_test; semantic corpus streams/ffi/park with scheduled events. Add full-writer two-step and stale-key checks, pending-ready-after-halt, lost/closed stream outcomes, missing call-pair before id allocation, call-result errors, repeated polling without duplicate append, effect permission checks and resource-update validation. Compare value/store/parking and external emission traces after each wake, not only final values.

### W4 — attach, modules and lifetime correctness

Implement independent image instances/function-table append, module-kernel adapter, closure/resource transport, store-of restoration, reset/load distinctions and arena/heap root integration.

Acceptance: rerun both corpora completely; add A-calls-B-while-C-attaches followed by ordinary return, park-before-attach then resume, stale hash but present image success, absent-image refusal, duplicate/empty attach no-op, empty-base row, layout-image rehashing, independent spawn-module counters/stores and module require in a tail call. Cross-layout closure lower validates arity/format and private store behavior. GC tests retain captured live values and FFI-pinned refs, release unreachable scratch records, and never free values held by old immutable state snapshots. No return or resume shrinks the code space.

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
