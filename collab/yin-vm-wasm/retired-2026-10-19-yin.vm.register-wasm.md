# yin.vm.register-wasm: the WebAssembly execution host for register images

Status: design, owner-authorized planning; not implemented.

Consensus provenance (2026-10-18): gpt-6-astra, author; gemini-4-argon,
reviewer; five review rounds, APPROVE on r5. Author thread:
`01a14f30-ca56-7530-bb93-3a870eca7a7b`; reviewer thread:
`f365ef1d-caa2-4379-8e96-51e64ca36af8`. This specification distills the
register-machine runtime instance of the frozen r5 consensus: semantic
fidelity, portability, determinism and Rust-kernel reconciliation. It
does not report an implementation or runtime acceptance result.

2026-10-19 decomposition and owner-ruling notes:
1. **First decomposition step (2026-10-19)**: Register-image-to-WebAssembly
   lowering and all 23 opcode lowerings moved to
   [`yang.wasm.md`](./yang.wasm.md).
2. **Third decomposition step and owner ruling (2026-10-19)**: By explicit
   owner ruling, the host-independent execution contract belongs to
   `yin.vm.engine` and the service ABI is renamed
   `yin.register.host.v1` → `yin.vm.engine.host.v1`. The guest-agnostic
   execution-host sections (the general machine-state-as-data boundary,
   task-owned `ValueRef` arena, native `handle↔Value` facade, three encoding
   domains, the canonical `yin.vm.engine.host.v1` service protocol tables,
   the embedder seam, single-engine-port rule, deterministic service
   traversal, and guest-agnostic stream/park/custody-gate/FFI rules) now
   live in [`yin.vm.engine.md`](./yin.vm.engine.md) (`§E.1–§E.5`). What
   remains here is the **register-machine instance**: the eight materialized
   register fields, the register instance of the synchronization barrier,
   sparse continuation payloads and live sets on wasm, `register-restore`,
   register module attachment, and the register-specific W0–W6 acceptance
   gates. Normative content is otherwise unchanged.

## Authorities

- [datom.world.md](./datom.world.md): foundational axioms and invariants.
- [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md): register contract, live sets and sparse continuations.
- [yin.vm.semantic.md](./yin.vm.semantic.md): A identity and semantic execution.
- [yin.vm.linker.md](./yin.vm.linker.md): admission, offset tables and module transport.
- [yin.vm.engine.md](./yin.vm.engine.md): scheduler, effects, custody, and (`§E.1–§E.5`) the guest-agnostic execution host and `yin.vm.engine.host.v1` service protocol.
- [yin.vm.rust-kernel.md](./yin.vm.rust-kernel.md): shared Rust crates, Value ownership, transition reuse and LLVM relationship.

Companion specifications:
- [yang.wasm.md](./yang.wasm.md): lowering register images to WebAssembly modules, the 10-region linear-memory layout, and all 23 opcode lowerings targeting `yin.vm.engine.host.v1`.
- [yin.vm.conformance-kit.md](./yin.vm.conformance-kit.md): the K0/W0 shared conformance kit.

---

## 1. Register machine state and the register instance of the synchronization barrier

This specification defines the **register-machine instance** (`version 4 / "r2"`,
23 opcodes) of the engine execution host specified in
[`yin.vm.engine.md`](./yin.vm.engine.md) (`§E.1–§E.5`). It executes WebAssembly
modules lowered from admitted register images under [`yang.wasm.md`](./yang.wasm.md)
over the `yin.vm.engine.host.v1` service protocol
([`yin.vm.engine.md`](./yin.vm.engine.md) §E.3, r5 §4.1.1). Host embedder targets
(Node/CLJS, Wasmtime, Wasmer), the task-owned `i32` `ValueRef` arena, the native
`handle↔Value` facade over `yin-value`, the three encoding/hashing domains
(`yin-scalar`, `yin-image`, `yin-wire`), the single-engine-port rule, and
 Rust-kernel reconciliations are specified in [`yin.vm.engine.md`](./yin.vm.engine.md)
§E.2 and §E.4.

### 1.1 The eight materialized register fields and the synchronization barrier instance

Each register-wasm task owns one imported, non-shared wasm32 memory arena with
the ten-region layout (`Task header`, `Body directory`, `Active register shadow`,
`Lexical frames`, `Return stack`, `Sparse snapshots`, `Continuation record`,
`Value descriptors`, `Engine state roots`, `Exit mailbox`), 64-bit wire `pc`
fields, and program-store `ValueRef` rules specified in
[`yang.wasm.md`](./yang.wasm.md) §2.2.

As an **instance** of the engine extension's machine-state-as-data boundary
pattern ([`yin.vm.engine.md`](./yin.vm.engine.md) §E.1), the register machine's
declared execution state consists of **eight materialized fields** on the VM
state map:

    :pc  :registers  :frames  :continuation  :store-of  :value  :halted?  :blocked?

Linear memory is a private execution cache over these eight fields plus the
current register code layout (`:segment`, `:hash`, `:images`) and engine state.
The register instance of the four-step synchronization protocol
([`yin.vm.engine.md`](./yin.vm.engine.md) §E.1) is:

1. **Public entry / host-to-wasm:** `step`, `run`, and `register-restore` accept the supplied map as authoritative. Import `:pc`, `:registers`, `:frames`, `:continuation`, `:store-of`, `:value`, `:halted?`, and `:blocked?`, plus the current code layout and engine state. W1 imports unconditionally. A later cache may skip only after proving all relevant keys agree with its last materialized snapshot; a retained private token alone is not proof. In particular `(assoc parked :pc 2 :halted? false)` must execute the requested state. Import a copied or privately owned active register shadow so running an old or externally modified map never mutates another returned map.
2. **Wasm-to-host:** before every state-consuming `engine/*` call, including `handle-effect`, pinning, collection, scheduler, park/resume and module work, materialize all eight register execution fields (`:pc`, `:registers`, `:frames`, `:continuation`, `:store-of`, `:value`, `:halted?`, `:blocked?`) onto the VM record. Materialize them also at every public `step`, `run`, restore or inspection return. Keep the record's protocol implementations so `module/gc-roots` can inspect the current map. Dense memory synchronization without map synchronization is insufficient.
3. **Host completion:** the returned host state is the new authority, including its eight execution fields and every extension key. Apply only the current opcode's specified post-service changes ([`yang.wasm.md`](./yang.wasm.md) §3.3–§3.4) to that returned state, then reimport it into memory. Never overlay a pre-service header onto it. In particular `handle-effect` sets `:value` even for nonblocking effects; preserve that value, resource/heap/GC updates, stores, counters, queues and custody fields. A plain non-tail primitive call, by contrast, only updates `:pc`/`rd` in the kernel and does not automatically set `:value`.
4. **Seam `pc`:** before a host service, the map contains the instruction's absolute site `pc`, unless the source transition explicitly advances it first (such as parking in the FFI path). Capture the payload at the site; apply the opcode's `pc` update exactly once after service completion. Mailbox state does not count as a completed guest instruction.

Because `:cell/new` may collect *inside* `handle-effect` before the adapter
receives its result ([`yin.vm.engine.md`](./yin.vm.engine.md) §E.1), map-based
`:registers`, `:frames`, `:continuation`, and `:value` must already be current
before `handle-effect` is entered.

### 1.2 Dense register shadow, sparse saved frames, and register GC roots

Start with a dense active register shadow (`Active register shadow` in
[`yang.wasm.md`](./yang.wasm.md) §2.2) synchronized on every register write and
before every return to the host. The wasm local is the execution operand; the
shadow makes complete current state and GC roots available. **Sparse saved
frames and payloads are nevertheless mandatory:** never retain the full dense
shadow through a guest call or park. On a successful call/restore, replace the
active shadow; clear abandoned mailbox/argument roots when no longer needed. A
parked VM's observable active `:registers` vector may still contain dead slots,
as the reference register VM does; do not confuse those current-state roots with
the sparse continuation payload.

Under the arena and engine heap collection rules of
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.2.3, mark active `:registers`,
`:frames`, `:value`, `:continuation`, `parked`/`wait-set`/`ready-queue` entries,
`store`, `module-stores`, engine heap and pinned/outgoing values, constants,
and pending mailbox arguments. Traverse trusted wrappers via `values/payload`
and preserve the register kernel's `module/gc-children` implementation and its
treatment of code. Persistent snapshots share immutable arena objects, never a
mutable register buffer; the first implementation may copy the active dense
`:registers` vector at every public `IVM` transition and optimize only after
snapshot-alias tests pass.

---

## 2. Sparse continuation materialization, `register-restore`, and layout transport

### 2.1 Materialization and capture-time metadata

At any of the six in-band live-set opcodes (`:call`, `:stream-put`,
`:stream-next`, `:current-continuation`, `:park`, `:ffi-call`) at site `p`, the
exported boundary payload and return-frame shape are those built by
`effects/continuation-payload` as specified in [`yang.wasm.md`](./yang.wasm.md)
§3.5 (`:segment`, `:site-pc p`, `:pc (inc p)`, `:frames`, sparse `:regs` and
`:live`, `:continuation`, `:dest`, `:resume-mode`, `:format
:yin.debruijn.register`, `:hash`, `:image`, and optional `:images` /
`:store-of`). Older paragraphs in `yin.vm.debruijn.register.md` §5.2 that placed
code-space fields on return frames are superseded by `yin.vm.linker.md` §7.3
r6/r7 and current source: return frames contain `{:site-pc :return-pc :frames
:regs :live :dest}` plus optional `:store-of`, with no code space, and restore
never rewinds code space.

Materialization recursively resolves `ValueRef`s and never puts numeric handle
IDs or tag-`13` arena references into payload values. Import performs the
inverse into immutable records. The capture-time `:segment`/`:hash`/`:images`
are validation data and must survive later `attach-image` unchanged. They can
share immutable objects in memory, but a plain-data export must contain the
actual required image data. A wasm function index, memory pointer, instance id,
or private ABI version is not a guest format identity.

### 2.2 `register-restore` algorithm and failure ordering

Expose one adapter restore with the same `(base entry val) -> state` contract
([`yin.vm.engine.md`](./yin.vm.engine.md) §2.1) and use it from both engine
resume paths:

1. Check `:format` equals `:yin.debruijn.register` and entry `:image` is in base `:images`; preserve `:rule :continuation-format`, `:reason :foreign-format`, and diagnostic context. Do **not** compare entry `:hash` to current base `:hash`.
2. Run the existing `effects/continuation-defect` against the entry, preserving deterministic first-defect results, including capture-time hash recomputation (`yin-image` R in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.2.2) and per-component image/table validation.
3. Check the actual `vm/machine-data?` predicate on `val`; reject unsupported host functions/errors with `:rule :resume-value`. Do not turn an error-shaped ordinary map into an exception.
4. If `request-sent`, enqueue `ffi/response-wait-entry` using `call-id` and remain blocked with `:yin/blocked`, `halted` false ([`yin.vm.engine.md`](./yin.vm.engine.md) §4 and §E.4.4). Do not deliver writer acknowledgement into `rd`.
5. Otherwise remove `call-id` from `parked` when present and apply `ffi/call-result` to its response. For `:write-result` allocate the receiving body's register count, nil-fill, copy sparse pairs, then write `dest`; restore `pc`, `frames`, `K`, `store-of`, `value` and `halted` false. For `:return-result` first `assoc` `:pc (:pc entry)`, `:frames (:frames entry)`, `:continuation (:continuation entry)`, `:store-of (:store-of entry)`, and `:halted? false` onto `base`, leaving `:registers` equal to `(:registers base)`; then run the ordinary `return-transition` ([`yang.wasm.md`](./yang.wasm.md) §3.3). With empty captured `K` this halts at the entry's next `pc`, retains `base` registers and captured frames, and clears `store-of`. With nonempty `K`, the popped caller frame replaces `pc`/`registers`/`frames`/`store-of` as usual. Preserve `base`'s `store`, `resources`, queues, counters, `modules`, and code space.

Do not clear `blocked` universally in the low-level restore: the engine clears
it in ready-queue resumption; direct paths preserve the source's exact field
behavior. Public APIs should route through engine scheduling instead of
manufacturing a partially cleared state. Failed restoration must not commit a
mutated arena/state snapshot: stage validation and allocations before publishing
the successful transition.

The distinct internal `:call` path for a trusted reified continuation
(`engine/operator-kind` ownership first → `engine/continuation-argument` arity
second → format/image membership third → `write-back`, skipping external
`continuation-defect`/`machine-data?` gates) is specified in
[`yang.wasm.md`](./yang.wasm.md) §3.3 and §3.5.

### 2.3 Cross-runtime continuation transport and layout compatibility

Same-layout native payloads with transportable values can round-trip between
interpreted register and wasm execution. Inside the same task, trusted wrappers
travel across `yin.vm.engine.host.v1` as checked task-local capability
references or trusted-constructor results ([`yin.vm.engine.md`](./yin.vm.engine.md)
§E.3.4), without granting external stream portability. For the present
module/linker path, keep the existing `:yin.k/non-portable` /
`:non-canonicalizable` continuation refusal in `engine/encoder`.

If a future authorized transport carries register continuation coordinates
image-relatively, it must translate **site-pc, pc, every return frame's
site/return pc, and recursively all closure body pcs**, attach admitted origins,
reconstruct self-consistent segment/hash/live metadata, and apply normal native
`register-restore`. Raw payloads with absolute pcs must not be fed to a
differently ordered receiving layout merely because one R is present. W0 must
gate the native transport API to a verified matching layout/prefix or return
qualified `:continuation-format`; this transport restriction does not change
same-task `register-restore` semantics. General rebasing and canonical semantic
UCF migration remain outside this backend's completion claim.

### 2.4 Register wait-entry validation scope

Guest-agnostic stream, park, custody-gate, and FFI two-step rules are specified
in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.4. For register wait entries
specifically, `effects/wait-entry-defect` is an acceptance oracle **only for its
four newly built stream/FFI forms**: `put`/`next` writers/readers and
`request-sent`/`call-id` writer/reader entries. Do not apply it universally to
the wait set: `link-request`, `link-response`, `install`, and gated `observe`
entries are outside its shape vocabulary. For these, check
`effects/continuation-defect` on the register payload plus the owning
module/engine reason-specific fields and transitions. Do not apply a newly
parked-entry oracle to stale-key-bearing ready entries either.

---

## 3. Register module attachment, `spawn-module`, and artifact cache

Per-image-row compilation, PC relocation, the four-step staged `attach-image`
transaction, combined-segment boundary payload reads, empty-base
`[[empty-R 0 0]]` offset-table rules, and the 65,536-slot preallocated function
table are specified in [`yang.wasm.md`](./yang.wasm.md) §5. The host
instantiates each admitted component for the receiving task and installs its
body entry functions into the task's imported function table using the runtime
embedding API, outside an active `yin.vm.engine.host.v1` `exchange` after
returning a staged host-work result; the next synchronized state publishes the
new layout. `reset` preserves offset rows as in the register kernel;
`load-image` replaces the code space and resets execution, so old continuation
membership checks can fail.

Under the `spawn-module` isolation rules of
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.4, register `spawn-module` creates
a fresh task/memory/arena/owner/counter/resources context with the composition
options copied by the register kernel; it does not borrow the parent's mutable
active register shadow or per-module store. Reuse the module scheduler
child-task lifecycle, encoder/decoder, and `module/IModuleKernel` seams.
Register closure lifting uses image-relative body references and `store-of`;
lowering validates binding/format, attached origin, and arity against actual
closure instructions before minting the receiver's trusted Closure value.
Cache compiled wasm modules by `A`, `r2` derivation/checksum `R`,
`yin.vm.engine.host.v1` ABI/emitter version, and feature profile; `R` alone is
not executable admission authority. A missing or dropped artifact falls back to
the same host's register interpreter at any materialized boundary.

---

## 4. Register-specific W0–W6 implementation slices and acceptance gates

### 4.1 Register test harness and corpora

The shared K0/W0 conformance kit, the three execution lanes (`R4` CLJS
reference, Rust kernel/interpreter, wasm), baseline toolchain pins, transport
probes, and guest-agnostic parity rules are specified in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.1 and
**[`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md)** §2–§6.

For the register-wasm backend specifically:
- Compile each admitted register image once from W1 onward and run those identical wasm bytes under **Node and Wasmtime** (and **Wasmer** in W4–W5), comparing against interpreted register after every guest instruction and scheduler event.
- Use `test/yin/vm/semantic/corpus.cljc` **programs and literal goldens**: project AST to A, assert the literal vector/address, derive R, validate R/live sets, compile and instantiate wasm. Supply deterministic `f`/`g`/`h`/`k`/`x`/`y`/`c`/`loop` bindings, stream fixtures, and genuine parked targets for resume cases; otherwise compare the same qualified refusal.
- Use all fixtures/assertions of `test/yin/vm/debruijn/register_test.cljc`, including hand images, B0/B2 parity, stream scripts, continuation corruption, and `IVM` behavior. Beyond B0 normalization, compare raw register payload shapes, `store`/`module-stores`, `pc`, frame order, `K`, counter, flags, `wait-set`/`ready-queue`/`parked` entries, and emitted request traces.

### 4.2 W0 — Register admission, codec, and synchronization gates

In addition to the guest-agnostic W0 toolchain, `yin.vm.engine.host.v1`
transport (`COMPLETE→NEED_SPACE→fetch-response`, `BAD_MESSAGE`, `ABI_MISMATCH`,
`RESOURCE_LIMIT`), and three-codec gates in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.1 and
[`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md) §5:
- All semantic goldens retain their literal vector/address; all derived register images retain `R` and exact live operands. Existing register tests pass unchanged on the reference lane.
- Invalid contract, Rule R, unknown opcode, cross-body target, bad live set, and empty-image cases have identical admission outcomes.
- Establish matching-layout register payload transport and qualified `:continuation-format` refusal for incompatible layouts (§2.3).
- Freeze the eight-field register map/memory authority protocol (§1.1) and its byte-message equivalents, including external `assoc`/snapshot replacement, old-state replay, and preservation of unknown engine extension keys.

### 4.3 W1 — Pure register execution and body dispatch

Required runtime gate: Node + Wasmtime on identical image binaries and full
step/ABI traces, plus Rust interpreter parity on the same pure fixtures,
refused-step atomicity, per-machine fault isolation, wide-register (`>65,535`),
and million-tail cases ([`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md)
§6 W1).

Implement arena, dense register shadow, body dispatcher,
constants/moves/loads/branches, closures, explicit calls/returns/halt, primitive
results, `store`/`define`/`gensym`, and one-step materialization.
Effects/continuation execution remains explicitly unavailable until its slice.

Acceptance: register
literal/load-bound/free/store/nested-closure/multi-arg/compose/branch/B0/B2/gensym
tests; pure semantic corpus entries including `nested-if-same-rd`,
`body-queue-order`, `define-then-call`, and unreachable structural slots. Add
nil-fill/extra-drop, zero truthiness, frame shadowing, backward/irreducible
valid CFGs, source/destination aliasing, bounded yields, deep non-tail recursion
and long tail recursion. Assert guest `K` behavior, bounded native call depth,
snapshot immutability between steps, bit-identical load `R`/owner/primitive-profile
addresses, direct map `pc`/register/frame reads, externally `assoc`-modified
entry state, and `:halt` / final `:return` preservation of the executing `pc`,
`:registers`, and `:frames` (including nonempty `K` on `:halt`).

### 4.4 W2 — Sparse continuations and `register-restore`

Required runtime gate: Node + Wasmtime full continuation/restore traces, plus
native↔CLJS admissible payload round-trips and identical qualified refusals
([`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md) §6 W2).

Implement sparse payload materialization (§2.1), reification, internal
continuation application, explicit `park`/`resume`, and external
`register-restore` (§2.2).

Acceptance: `current-continuation`, `park-and-resume`, `bytecode-resume`, every
tampered/refusal test in `register_test`; semantic corpus `park`/`resume`
productions. Add nested non-tail capture then abortive invocation, multi-shot
capture, tail `:return-result`, live Closure versus live primitive distinctions
under `machine-data?`, host-function/error resume refusal, error-shaped data
success, dead-slot `nil` restoration, snapshot alias isolation, and both native
interpreted→wasm / wasm→interpreted payload round-trips. Assert expected linker
`:non-canonicalizable` refusals, native `continuation-defect` recomputation of
repository `R` on original and tampered images, `:return-result` fixtures with
empty and nonempty captured `K` and deliberately different `base` `pc`/registers,
unowned wrong-arity continuation (`:foreign-value` precedes
`:continuation-arity`), and owned wrong-arity/foreign-format (arity precedes
format).

### 4.5 W3 — Streams, shared scheduler, and FFI on register images

Required runtime gate: Node + Wasmtime full scheduler/event/ABI traces using
independent scripted transports, plus `NEED_SPACE` / repeated `fetch-response`
gates ([`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md) §6 W3).

Implement typed effect mailbox completion, register site wait builders,
scheduler seam, call-effect handling including tail effects
([`yang.wasm.md`](./yang.wasm.md) §3.4), bridge wrapper, FFI request/response
logic, and pinning.

Acceptance: every immediate/blocking stream and bridge success/error test in
`register_test`; semantic corpus streams/ffi/park with scheduled events. Exercise
the [`yang.wasm.md`](./yang.wasm.md) §3.4 (r5 §2.4) 2×2 host-call matrix:
immediate/blocked × tail/non-tail, including `put`/`next`, module
`require`/`try-require` builder fallback, and running `poll`'s `observe`
fallback. Assert blocked `pc` advances without `rd` write or tail return,
post-effect state mutations survive each completion, and immediate empty-`K`
tail completion at site `pc` differs as specified from restored blocked
completion at entry `pc`. Pass the low-threshold `:cell/new` GC-inside-`handle-effect`
test, all custody gate modes, four-shape `wait-entry-defect` scoping (§2.4),
seal parity, and deterministic `resource-updates` diagnostic order
([`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.3).

### 4.6 W4 — Runtime `attach-image`, modules, and lifetime correctness

Required runtime gate: Node + Wasmtime + **Wasmer** full module/state/ABI traces
across the complete W1–W4 suite, plus bidirectional native↔CLJS module-slice
parity (`engine/lift-slice` ↔ `engine/receive-module`) under the deterministic
traversal policy ([`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md) §6
W4).

Implement independent image instances/function-table append, `IModuleKernel`
adapter, closure/resource transport, `store-of` restoration, `reset`/`load-image`
distinctions, and arena/heap root integration (§3).

Acceptance: rerun both corpora completely; add A-calls-B-while-C-attaches
followed by ordinary return, park-before-attach then resume, stale hash but
present image success, absent-image refusal, duplicate/empty attach no-op,
empty-base row (`[[empty-R 0 0] [R 0 n]]` retaining `halted` true and
`registers []`, then `reset` and capture with the two-row table), layout-image
rehashing, independent `spawn-module` counters/stores, module `require` in a
tail call, cross-layout closure lower validation, one compiled component reused
in two attachment orders with absolute exit/capture/return/closure pcs, and GC
retention of captured live values, FFI-pinned refs, and old immutable state
snapshots.

### 4.7 W5 — Register lane completion and release gate

Register wasm as an embedder-agnostic backend with Node/CLJS, Wasmtime, and
Wasmer adapters, with no changes to the `r2` opcode table. Freeze
`yin.vm.engine.host.v1` ABI versioning and deterministic binary generation. Run
all named register tests and every semantic golden through the harness; report
unsupported canonical-semantic fragment cases as named refusals with reasons
([`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md) §6 W5).

### 4.8 W6 — Measured optimization and register non-goals

After W5 correctness, measure compilation latency, binary size, native stack
depth, arena/heap retention, dispatch cost, serialized `yin.vm.engine.host.v1`
message bytes/copy/codec time, call/suspension cost, and end-to-end time across
Node, Wasmtime, and Wasmer before coalescing basic blocks, adding dirty
register-shadow spilling, or prototyping the self-contained interpreter-loop
variant ([`yang.wasm.md`](./yang.wasm.md) §6.1,
[`yin.vm.conformance-kit.md`](./yin.vm.conformance-kit.md) §6 W6).

Register-specific non-goals (in addition to the guest-agnostic non-goals in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.2): no new register opcode/contract
version, no alternate A→R register allocator, no native stack continuations or
async-stack capture, and no full semantic VM feature parity beyond the
established native register fragment.

---

## 5. Register-specific risks, Reference conventions, and Open items

Guest-agnostic host, arena, single-port, and embedder risks are specified in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.2; lowering-specific risks (body
dispatch size, wasm module code growth, canonical admission) are in
[`yang.wasm.md`](./yang.wasm.md) §6.2. Register-host-specific risks retained
from r5 §7:

1. **Native continuation transport has a limited scope.** The sources support same-model register payloads and qualified transport refusal, while the module encoder currently refuses continuations. Keep this distinction visible in API docs/tests. A full portable continuation design would require separate authorization and compatibility work, not an ad hoc wasm-only decoder.
2. **Absolute `pc` layout compatibility.** The register kernel's format check tests image membership, not complete receiving-layout equivalence. That suffices for grow-only same-task use; it is not proof that arbitrary imported absolute pcs mean the same thing. The transport adapter must enforce matching layout/prefix or use a separately specified rebaser (§2.3). Preserve legacy restore diagnostics without presenting the legacy check as stronger than it is.

The implementation is complete when the same emitted baseline binary for an
admitted `r2` image has the same values, store transitions, continuation
records, refusals, parking/wake behavior and module growth under Node, Wasmtime
and Wasmer as under the existing register kernel, with full-trace parity and no
suspended computation held on the wasm stack.

### Reference conventions

Reference conventions follow [`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.3
and [`yang.wasm.md`](./yang.wasm.md) `## Reference conventions`: `register.cljc`
means `src/cljc/yin/vm/debruijn/register.cljc`; `effects/*` denotes
`src/cljc/yin/vm/debruijn_register_effects.cljc`; `rcode` denotes
`src/cljc/yin/vm/debruijn_register_code.cljc`. Rust-design line references
retain r5's coordinates (`1–1334`).

### Open items

The frozen r5 open items are recorded in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.3 and
[`yang.wasm.md`](./yang.wasm.md) `## Open items` (the compiled-closure-call vs.
single-port transition boundary under `yin.vm.engine.host.v1`, and the W0
`COMPLETE→NEED_SPACE→fetch-response` transport-test notation).
