# yin.vm.conformance-kit: the shared R4 execution conformance kit

Status: design, owner-authorized planning; not implemented.

Consensus provenance (2026-10-18): gpt-6-astra, author; gemini-4-argon,
reviewer; five review rounds, APPROVE on r5. Author thread:
`01a14f30-ca56-7530-bb93-3a870eca7a7b`; reviewer thread:
`f365ef1d-caa2-4379-8e96-51e64ca36af8`. This specification distills the
frozen r5 consensus: semantic fidelity, portability, determinism and
Rust-kernel reconciliation. It does not report an implementation or
runtime acceptance result.

2026-10-19 decomposition and owner-ruling notes:
1. **Decomposition**: Updated cross-references for the owner-directed
   decomposition into [`yin.vm.engine.md`](./yin.vm.engine.md) (`§E.1–§E.5`,
   guest-agnostic execution host and service protocol),
   [`yang.wasm.md`](./yang.wasm.md) (register-image-to-WebAssembly lowering),
   and [`yin.vm.register-wasm.md`](./yin.vm.register-wasm.md) (register-machine
   host instance).
2. **Owner ruling (2026-10-19)**: By explicit owner ruling, the service
   import ABI is renamed `yin.register.host.v1` → `yin.vm.engine.host.v1` and
   its canonical protocol tables live in
   [`yin.vm.engine.md`](./yin.vm.engine.md) `§E.3`. Normative content is
   otherwise unchanged.

## 1. Scope and authorities

This is the single **K0/W0 shared deliverable** of
[yin.vm.rust-kernel.md](./yin.vm.rust-kernel.md),
[yin.vm.engine.md](./yin.vm.engine.md) (`§E.1–§E.5`),
[yang.wasm.md](./yang.wasm.md), and
[yin.vm.register-wasm.md](./yin.vm.register-wasm.md). It is exported from
R4 and extended for wasm lowering, `yin.vm.engine.host.v1` transport, and
runtime conformance, not a second reference interpreter or a second kit. The
execution specifications retain their respective authorization boundaries;
the planned LLVM lane remains behind K3 owner review before K4.

The register, semantic, linker and engine authorities listed in
[`yin.vm.engine.md`](./yin.vm.engine.md) (`§E.4.1`),
[`yang.wasm.md`](./yang.wasm.md), and
[`yin.vm.register-wasm.md`](./yin.vm.register-wasm.md) govern the
reference behavior. Rust K0's fixture description is at
`docs/design/yin.vm.rust-kernel.md:1185–1199`; its equivalence law is at
`:805–831`. Guest-agnostic host and `yin.vm.engine.host.v1` protocol §
references below cite **[`yin.vm.engine.md`](./yin.vm.engine.md)**
(`§E.1–§E.5`); lowering-lane § references cite
**[`yang.wasm.md`](./yang.wasm.md)**; register-instance § references cite
**[`yin.vm.register-wasm.md`](./yin.vm.register-wasm.md)**; Rust-design
sections are explicitly qualified. W0–W6 are the wasm slices; K0–K5 are
the Rust design's phases. No new fixture semantics are introduced here.

## 2. Exported fixtures and execution lanes

The kit exports, per corpus program: the image as canonical data, R, the
B0-normalized result or error, and the format-level trace R4 produces
(payload at every boundary, K depth, saved pairs). Plus: refusal
fixtures for all fourteen validator rules with rule keywords, the
descriptor hash, scalar-encoding vectors, diagnostic message fixtures,
atomicity fixtures (a refused step leaves the state equal), tail-site
fixtures for every row of the 7.3 table (a tail primitive value and a
tail primitive effect returning into a caller in another body, and
halting with K empty), a wide-body fixture whose register count exceeds
65,535, host-limit refusal fixtures, and the million-iteration tail
fixtures.

Here “7.3 table” means the Rust design's call-outcome table. Its fixture
requirements include all tail outcomes, not only closure tail calls.

**W0 and Rust K0 are the same conformance-kit deliverable**, exported from R4, not independent
harness authorities (`docs/design/yin.vm.rust-kernel.md:1185–1199`). Consume and extend its
canonical images, R, B0-normalized results/errors, boundary payloads/K depth/saved pairs,
descriptor/scalar vectors, ordered validator refusals/messages and refused-step atomicity
fixtures. Preserve its tail-site matrix, >65,535-register, host-limit and million-tail fixtures.
If K0 exports are not yet available, create that shared deliverable once; do not fabricate a
wasm-only reference interpreter. Record the actual reference revision and fixture version,
including the explicit shared traversal stabilization delta.

One manifest drives **three execution lanes: R4 CLJS reference, Rust kernel/interpreter, wasm**.
Wasm runs the same bytes under Node and native runtimes as gated below; all use the same
scripted inputs, scalar/host-limit declarations and expected refusals. ABI-specific probes
extend K0 as a transport test group without replacing format-level fixtures. Rust interpreter
coverage advances with each W slice; it cannot be omitted because native wasm happens to pass.
B0 results and complete boundary traces remain shared; wasm single-step traces add finer
evidence, not a second oracle.

Build an explicit differential adapter rather than replacing the existing tests' private
`register-run` vars by global mutation. Parameterize/extract shared fixtures when implementation
is authorized. Each case receives independent scripted streams with identical inputs, capability
secret, primitive registry, origin and counter setup. From W1 onward, compile each image once
and run those identical wasm bytes under **Node and Wasmtime**, each against interpreted
register after every guest instruction and scheduler event; Wasmer must also pass the complete
suite by W5. Compare serialized ABI traffic as well as full semantic traces; also compare
semantic execution where the existing A→R fragment permits it.

Use `test/yin/vm/semantic/corpus.cljc` **programs and literal goldens**: project AST to A,
assert the literal vector/address, derive R, validate R/live sets, compile and instantiate wasm.
These goldens specify syntax/address, not a runnable environment or expected final value for
every entry. Supply deterministic f/g/h/k/x/y/c/loop bindings, stream fixtures, and genuine
parked targets for resume cases; otherwise compare the same qualified refusal. Execute both
conditional arms where applicable. Do not silently skip resume/terminal/closure cases or relabel
unsupported cases as successes.

Use all fixtures/assertions of `test/yin/vm/debruijn/register_test.cljc`, including hand images,
B0/B2 parity, stream scripts, continuation corruption, and IVM behavior. B0 normalization is
suitable for final cross-model values but is insufficient for this backend: additionally compare
raw register payload shapes, stores/module-stores, pc, frame order, K, counter, flags,
wait/ready/parked entries and emitted request traces. Compare resources by authenticated logical
identity/cursor progress and stream emissions, not by host object address. With identical
secrets and inputs, hash/owner/seal/profile/address strings, guest-visible ids and slice
ordering must be bit-identical. Compare canonical expanded observable-state encodings, not
pointer layouts; private arena numbers may be renamed only where they cannot affect key
ordering, id minting or guest state. Across independent tasks use the same explicit owner
inputs; never erase owner checks. Test primitive implementations and scripted transports exist
in both native and CLJS compositions with the same declared profiles; a JS function fixture must
get an equivalent native fixture, not disappear from the native run. Normalize only private
memory addresses, arena numbering and runtime object identities; preserve all guest values,
errors, pcs, frames, snapshots, queues, counters, store/heap/custody changes, resource progress
and emitted request order. Compare failures by qualified rule/reason/outcome and relevant
message assertions.

The planned LLVM lane uses this same kit under Rust §7.2: interpreted and
native runs compare boundary states, ordered emissions and refusal
diagnostics. Artifact-drop fallback continues interpreted from the same
boundary. Wasm adds wasm→Rust-interpreter and wasm→CLJS-interpreter
fallback fixtures. No backend forks the validator, live-set rules or
payload vocabulary. K3 may measure wasm on the same harness; a wasm result
does not commission K4.

## 3. Encoding and hash determinism

The shared Rust crates must implement **three distinct codecs**, not substitute the convenient
ABI transport encoding for repository identity bytes:

| Domain | Existing authority / exact obligation | Observable products |
|---|---|---|
| Private import messages | [`yin.vm.engine.md`](./yin.vm.engine.md) §E.3.3 (r5 §4.1.1; target binding in [`yang.wasm.md`](./yang.wasm.md) §4) `yin.vm.engine.host.v1` TLV codec, reference expansion, framing and NaN rules | ABI request/response bytes only; not code identities or seals. |
| Executable register scalar/image encoding | `src/cljc/yin/vm/debruijn_code.cljc` `scalar-class`, `encode-scalar`, and `framed`; `src/cljc/yin/vm/debruijn_register_code.cljc` `encode-register-image`, `descriptor-hash`, `register-hash` | R for empty/base/attached/composed images, layout slice checks and continuation self-consistency. |
| Jing canonical content encoding | `src/cljc/dao/jing/cbor.cljc` `encode`/`decode` and its existing corpus; `src/cljc/dao/jing.cljc` `canonical-bytes`, `content-hash`, `segment-key` | Owner tags, reference seals, primitive-profile addresses, module/semantic addresses and existing content admission. |

Port executable framing exactly: class tags and eight-hex-digit **UTF-8 byte length** prefix
content; some content is hex-encoded numeric bits, while string content is the actual string.
`jing/blake3` hashes the UTF-8 bytes of the resulting **string**, not hex-decoded bytes. R is
BLAKE3 of the string concatenation of descriptor-hash text and encoded-register-image text
(`debruijn_register_code.cljc:258–271`). Do not replace it with a CBOR hash or hash the ABI node
bytes. Preserve descriptor material, body order, tuple operand order, exact scalar
classification, no NFC, unsupported-value rules, numeric endian rules and canonical-key
collision refusals (`debruijn_code.cljc:198–203, 224–242, 350–378, 410–465`). The native backend
matches the admitted CLJS scalar profile where that host rejects JVM-only scalar classes;
broader Jing integer support does not silently widen register-image admission.

Port Jing’s actual canonical CBOR profile, not a generic library’s defaults: keyword/symbol/list
tags, exact integer/bignum, float64 carriers, decimal/rational forms, collection ordering,
metadata stripping/validation, equality-collapse and duplicate checks, depth limits, surrogate
refusals and decode/re-encode identity. The private ABI may carry a lone UTF-16 surrogate for
in-machine fidelity; that does not make it hashable by either repository codec. Keep that domain
distinction and the existing qualified refusal. `jing/content-hash` digests canonical CBOR bytes
directly and returns lowercase hex; `jing/segment-key` applies the exact
namespace/algorithm/digest spelling. The default here is BLAKE3; any advertised alternative
algorithm must reproduce the repository registry’s behavior rather than aliasing BLAKE3.

Required formulas are the actual inputs in source: owner = content-hash of `[:yin.k/owner
secret]` (nil secret → nil owner); seal = content-hash of `[:yin.k/seal secret kind id]` with
issue-ref’s missing-secret refusal; primitive profile = content-hash of the exact description in
`vm/primitive-profile`, followed by the `:yin.k.pp/blake3-…` keyword construction; module
address = segment-key of the manifest. Preserve A’s existing canonical construction as well.
Equal test secrets, identities and input data must yield **bit-identical** R, owner, seal,
profile and address across CLJS and Rust. Do not normalize these fields away or let the native
service trust supplied digest strings instead of recomputing them.

Assign executable scalar encoding to `yin-scalar`, image/descriptor/R and admission to
`yin-image`, Clojure values/equality to `yin-value`, primitive profiles to `yin-prims` with
engine composition, and Jing payload/content serialization to `yin-wire` using the canonical
Jing implementation. The facade alone owns private ABI framing/reference expansion; it calls
these crates for identity bytes and hashes. These are the crate responsibilities in
`docs/design/yin.vm.rust-kernel.md:1136–1145`, extended for the current contracts, never
duplicate codec ports inside each runtime wrapper. W0 ports/verifies these codecs and hashing
primitives before native execution gates. Reuse `test/resources/dao/jing/cbor-v1.json` and its
README/errata plus `test/dao/jing/cbor_conformance_test.cljc`,
`test/dao/jing/hash_registry_contract_test.cljc`, `test/yin/vm/debruijn_code_test.cljc` and
`test/yin/vm/debruijn_register_code_test.cljc`; do not regenerate existing goldens. Add
cross-embedder vectors for descriptor-hash/R (including empty and concatenated images), exact
encoded bytes, owner/seal/profile inputs and manifest addresses. W1 verifies load/primitive
profiles, W2 recomputes continuation hashes, W3 checks every minted reference seal, and W4
checks compose/attach/layout/module addresses and received slices. These are native service
obligations independent of the ABI codec implementation.

The private `yin.vm.engine.host.v1` ABI's complete tag table, control offsets, status values and
message schema are specified in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.3 (r5 §4.1.1; target
binding in [`yang.wasm.md`](./yang.wasm.md) §4). The conformance
vectors enforce its unchanged rules: map keys and set elements sort by
fully expanded canonical value bytes before handle compression, never by
arena-reference bytes; float64 NaN is `0x7ff8000000000000`, with integral
floats and -0.0 preserved by their carriers; each node has exactly one
outer `u8 tag | u32 payload-byte-length (LE) | payload` frame, with no
inner counts. Duplicate/colliding entries and malformed nodes refuse.
Task-local references and trusted descriptors do not confer portable
value authority. Native handles resolve through the safe shared Value
facade, not a separate codec/engine port.

## 4. Deterministic traversal prerequisite

Source verification found real unsorted map/set walks that can mint ids or choose diagnostics.
Rust must not reproduce them with its default map order, and canonicalizing only the final ABI
bytes is too late. Introduce an explicit **shared deterministic traversal policy in the CLJC
helpers and the native service**, then use that revised reference for wasm parity. This is a
planned semantic stabilization of previously host-order-dependent behavior, not an already
satisfied property of current source and not a wasm-only patch. This is a planned prerequisite,
not implemented behavior.

For currently unordered traversal sites use a pure order key: for ordinary machine values, the
fully expanded canonical `yin.vm.engine.host.v1` ABI value bytes defined in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.3.3 (r5 §4.1.1); for trusted values,
their non-minting structural descriptors. Sort maps by key and sets by element, using unsigned
lexicographic comparison, never arena numbering, hash-table iteration or the output of a
resource-lifting encoder. Build keys for a collection before invoking any id-minting traversal
of that collection. Retain vector/list order, ordered exports, wait/ready queue order and
first-occurrence deduplication. Retain **existing explicit source orderings** such as sort-by
str on cursor ids/module-store addresses and sort-by key on attached image identities, with
cross-host fixtures for those comparators; do not replace them with ABI order under the guise of
fixing unordered maps.

| Site (existing symbols in engine.cljc unless qualified) | Required shared traversal change / preserved order |
|---|---|
| `encoder`, `encode-store` | Sort raw literal-map/store keys before visiting key then value; sort sets before recursively encoding. Never sort the already encoded output: `cell-for!` has already minted `:yin.k/c-N` by then. Keep vector/list and explicit export sequence order. |
| `markers` | Maintain pre-order tree walk; for maps visit values in sorted-key order (preserve the source’s values-only walk), for sets sort elements, for vectors/lists preserve sequence order. No incidental host map order may determine first stream discovery. |
| `lower-resources` | Preserve sorted used-cursor ids and the direct-stream-marker pass followed by cursor-derived streams; consume canonically traversed marker sequences, deduplicate by stream identity at first encounter, then mint gensym ids. Do not reorder queues or change aliasing of repeated cursor cells. |
| `receive-module` | Keep its explicit sorted image attach order. Enumerate slice values and fresh-store values in sorted-key order before lower-resources; use the same order for bindings/store decoding and deterministic error selection. Preserve slice-before-fresh-store phase order and first-link-wins stores. |
| `module/module-entries`, `module/list-modules` (module.cljc) | Walk registry child maps in sorted-key order and return top-level module names in that order. Missing-module `:available` vectors and registry consumers no longer inherit host map iteration. |
| `resume-from-run-queue` | Keep FIFO entry selection and terminal-check ordering; sort resource-update keys before selecting the first non-keyword diagnostic. Preserve updates and merge semantics. |
| `lift-slice`, `decode-store` and adjacent consumers | Preserve ordered export input; if a collection is a set, sort before visitation. Audit downstream unordered traversal for id minting, first-error choice or observable vectors, and route those paths through this policy. |

The helpers named above exist; the ordering policy/helper itself is proposed implementation
work. It must land with shared CLJC/native regression tests before the corresponding W3/W4
acceptance gate. This deliberately can change old host-specific `c-N` assignments, stream
ids/seals, slice-entry vectors or diagnostic order; record those deltas as the named
prerequisite, never normalize them out. A fixed CLJS-only historical map order is not a
cross-runtime contract. Existing canonical R/Jing hash algorithms and frozen byte corpora remain
unchanged.

W0 inventories unordered observation sites and freezes order-key fixtures. W3 implements
resource-update diagnostic order and validates counters/seals; W4 completes module lift/lower
ordering and tests equal maps built with different insertion histories, small and large maps
spanning CLJS map representations, nested maps/sets, repeated stream/cursor refs, multi-store
modules and multiple invalid update keys. Compare exact minted ids, seals, `:yin.k/entries`,
`:cells`, `:available`, counters and resource maps. A parity failure here blocks the slice; it
cannot be hidden as implementation-private arena variation.

The old Rust K-phase restriction on CLJC edits remains distinct from this
explicit wasm-driven shared-reference prerequisite. Version the kit after
that stabilization and retain the original goldens/history, as specified
in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.2 and [`yang.wasm.md`](./yang.wasm.md) §1.1's Rust-authority reconciliation; do not silently choose
Rust map order or normalize away changed guest-visible ids.

## 5. W0 transport, profile and toolchain gates

Deliver/extend K0’s single exported fixture kit and its CLJS, Rust-interpreter and wasm
adapters, the core-profile feature probe, pinned assembler/validator, ABI codec/schema and
operation-by-operation conformance fixtures. The following are **exact baseline pins** for W0,
not “latest” constraints or a claim of an already working build:

| Tool | Pin / scope | Evidence |
|---|---|---|
| Node | 25.6.1 | Existing `mise.toml` node pin; keep the repository selection. |
| Clojure CLI / shadow-cljs | 1.12.4.1602 / 3.3.5 | Existing `mise.toml` and `deps.edn` selections. JVM emitter uses the repository Java 21 lane; record resolved vendor/build in the W0 manifest. |
| WABT | 1.0.37 | [Release](https://github.com/WebAssembly/wabt/releases/tag/1.0.37); assemble and validate with an explicit baseline feature allowlist. |
| Wasmtime native crate/runtime | `=38.0.0` | [Versioned embedding API](https://docs.rs/wasmtime/38.0.0/wasmtime/); required native runtime from W1. |
| Wasmer native runtime | 6.0.1 | [Release](https://github.com/wasmerio/wasmer/releases/tag/v6.0.1); second wrapper and completion gate by W5. |
| Rust / Cargo | 1.90.0 toolchain | [Release](https://blog.rust-lang.org/2025/09/18/Rust-1.90.0/); host service/wrapper builds, not guest wasm generation. |

W0 must build these pins together, freeze transitive native dependencies in a lockfile, record
checksums and target triples, and run the ABI smoke tests. They are reproducibility starting
points, not assertions that all engines’ later features work. If a compatibility or security
issue requires a pin change, document the exact replacement and rerun all baseline probes; no
silent floating version. Binaryen is absent from the baseline toolchain, and any later
introduction needs its own pin/profile gate. Browser engine/version is recorded when its adapter
is qualified; browser API convenience never expands the baseline.

Probe imported memory, byte-order/bounds behavior, single-result i32 imports, scalar i64 memory
fields, mutable globals, active segments, table installation/call_indirect, memory growth/view
refresh, and multiple image instances sharing one task memory. Explicitly reject every
opcode/type outside the baseline allowlist, even when an engine enables extensions by default.
Test same-binary import/export signatures on Node, Wasmtime and Wasmer with no WASI imports.
Native custom imports require the embedding harness; invoking a bare runtime CLI with no imports
is not this test. Freeze deterministic fixture manifests. No new guest semantics. Gate every
nonempty image before codegen. Separate native payload transport from linker export capability.

Acceptance: all semantic goldens retain their literal vector/address; all derived images retain
R and exact live operands. Existing register tests pass unchanged on the reference lane. Invalid
contract, Rule R, unknown opcode, cross-body target, bad live set and empty-image cases have
identical admission outcomes. Validate a minimal wasm instance and explicit resource-limit
diagnostics. Establish matching-layout payload transport and qualified refusal for incompatible
layouts. Freeze the map/memory authority protocol and its byte-message equivalents, including
external assoc/snapshot replacement, old-state replay and preservation of unknown engine
extension keys; do not defer that contract to performance tuning.

Additional W0 acceptance: run all three codec domains against frozen bytes and hashes before
executing native register images. Include map/set canonical collisions, metadata, numeric
carriers, surrogate rejection in repository codecs versus private ABI preservation, deep
compound referenced keys with permuted arena ids, canonical NaN and float/integer distinction,
and exact outer-only framing (odd text lengths, odd map node count, truncated/extra children).
Keep private message-codec and repository hash failures separately diagnosable.

W0 transport acceptance must force small/zero response capacity:
COMPLETE→NEED_SPACE→fetch-response, repeated fetch, acknowledgement/release, wrong
sequence/task, repeated sequence with changed content, BAD_MESSAGE, ABI_MISMATCH and
RESOURCE_LIMIT. Validate bounds/overlap/overflow and confirm malformed/unsupported requests do
not execute a semantic transition. A counter-only test service proves that retained responses do
not re-execute the action. These tests run on Node and native harnesses during W0, not first in
W5.

W/K dependency map: W0 consumes/extends K0; W1 shares K1 pure kernel/value/image work; W2 shares
K2 payload/restore work plus the required `yin-engine` park subset; W3–W4 require the Level-2
library services described in Rust §6.3, not a second engine. W5 is wasm release qualification,
not certification of the full K5 standalone node. W6 can contribute wasm measurements to K3’s
same corpus/harness; K4 still requires the owner’s review of K3
(`docs/design/yin.vm.rust-kernel.md:1167–1182, 1215–1228`). No dependency authorizes unrelated
implementation.

## 6. Per-slice differential acceptance

### W1 — pure register execution and body dispatch

Required runtime gate: Node + Wasmtime, identical image binaries and full step/ABI traces;
provide those services through the shared Rust crates and require the Rust interpreter to pass
the same pure fixtures. Also test refused-step atomicity and per-machine fault isolation from
K0/K1, including the wide-register and million-tail cases.

Acceptance: register
literal/load-bound/free/store/nested-closure/multi-arg/compose/branch/B0/B2/gensym tests; pure
semantic corpus entries including nested-if-same-rd, body-queue-order, define-then-call and
unreachable structural slots. Add nil-fill/extra-drop, zero truthiness, frame shadowing,
backward/irreducible valid CFGs, source/destination aliasing, bounded yields, deep non-tail
recursion and long tail recursion. Assert guest K behavior and bounded native call depth.
Preserve snapshot immutability between steps. Assert bit-identical load R, owner tags and
primitive-profile addresses, plus direct map pc/register/frame reads, externally assoc-modified
entry state, and halt/final-return preservation of the executing pc, registers and frames
(including nonempty K on halt).

### W2 — continuations and restore

Required runtime gate: Node + Wasmtime full continuation/restore traces, plus native↔CLJS
admissible payload round-trips and identical qualified refusals. Implement native
payload/owner/type validation before this gate.

Acceptance: current-continuation, park-and-resume, bytecode-resume, every tampered/refusal test
in register_test; semantic corpus park/resume productions with valid fixture targets or matching
errors. Add nested non-tail capture then abortive invocation, multi-shot capture, tail
return-result, live Closure versus live primitive distinctions under machine-data?,
host-function/error resume refusal, error-shaped data success, dead-slot nil restoration,
snapshot alias isolation and both native interpreted→wasm/wasm→interpreted payload round-trips.
Assert expected linker non-canonicalizable refusals rather than claiming transport support from
same-process tests. Assert native continuation-defect recomputes the same repository R on
original and tampered images, without trusting the supplied hash or hashing ABI bytes. Add
return-result fixtures with empty and nonempty captured K, deliberately different base
pc/registers, and exact post-restore pc/register/frame assertions. Add an unowned wrong-arity
continuation case proving foreign-value precedes continuation-arity, and an owned
wrong-arity/foreign-format case proving arity precedes format.

### W3 — streams, shared scheduler and FFI

Required runtime gate: Node + Wasmtime full scheduler/event/ABI traces using independent
scripted transports; native scheduler, custody, collection and two-step FFI services are
required now.

Acceptance: every immediate/blocking stream and bridge success/error test in register_test;
semantic corpus streams/ffi/park with scheduled events. Add full-writer two-step and stale-key
checks, pending-ready-after-halt, lost/closed stream outcomes, missing call-pair before id
allocation, call-result errors, repeated polling without duplicate append, effect permission
checks and resource-update validation. Compare value/store/parking and external emission traces
after each wake, not only final values. Exercise the [`yang.wasm.md`](./yang.wasm.md) §3.4 (r5 §2.4) host-call matrix: immediate/blocked ×
tail/non-tail, including put/next, module require/try-require builder fallback (full module
installation in W4), and running poll’s observe fallback. Assert blocked pc advances without rd
write or tail return, and post-effect state mutations survive each completion. Compare immediate
empty-K tail completion at site pc with restored blocked completion at entry pc.

Add a low-threshold `:cell/new` host-effect test whose only live cell roots occur in newly
materialized registers/frames/K, forcing collection inside handle-effect; it must retain those
cells. Check `:value` after each nonblocking effect and verify a plain non-tail primitive leaves
the previous value unchanged. Test all supported gate modes (ungated/running/exporting/ended),
no handle calls for deferred FFI/cursor/put/poll/close/link work, separate issue/id counters,
queued running closes versus exporting/ended refusals, suppressed wait polling, and driver apply
synchronization. Scope wait-entry-defect to its four shapes; use payload plus owning-shape
checks for observe/link/install. These are W3 correctness gates, not deferred GC optimizations.
Assert exact seals on stream/cursor/cell refs with the same secrets and ids, and deterministic
resource-update diagnostic order under §4 ([`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.3 / r5 §4.1.2).

W3 also forces NEED_SPACE after real effectful transitions: stream append, FFI request
send/retained-writer conversion, gensym/id allocation and parked-state update. Repeated
fetch-response must return the same response bytes/state revision without a second append, new
id, repeated GC transition or repeated resource update. Verify
BAD_MESSAGE/ABI_MISMATCH/RESOURCE_LIMIT paths do not accidentally execute an effect, and
semantic error responses obey the same retained-response protocol. Repeat on Node and Wasmtime;
Wasmer repeats this suite in W4.

### W4 — attach, modules and lifetime correctness

Required runtime gate: Node + Wasmtime full module/state/ABI traces; implement native admission,
module stores and resource/closure transport. Bring up Wasmer with the same native service
library and run W1–W4 suites before W5.

Acceptance: rerun both corpora completely; add A-calls-B-while-C-attaches followed by ordinary
return, park-before-attach then resume, stale hash but present image success, absent-image
refusal, duplicate/empty attach no-op, empty-base row, layout-image rehashing, independent
spawn-module counters/stores and module require in a tail call. Cross-layout closure lower
validates arity/format and private store behavior. GC tests retain captured live values and
FFI-pinned refs, release unreachable scratch records, and never free values held by old
immutable state snapshots. No return or resume shrinks the code space. Reuse one compiled
component in two different attachment orders and assert absolute exit/capture/return/closure pcs
and combined-segment payload checks. Assert empty-base attachment retains both offset rows,
halted true and registers [], then reset and capture with the two-row table. Complete gated
install-child scheduling and link-response/install oracle tests. **W4 does not pass until Wasmer
passes the entire W1–W4 full-trace suite** on the identical binaries and its native service,
alongside Node and Wasmtime.

Require native↔CLJS module-slice parity in both directions: lift with `engine/lift-slice`,
transfer real canonical content, then receive with `engine/receive-module` semantics into
matched receiver layouts/secrets/resources. Compare exact slice-entry vectors, cells/aliasing,
store snapshots, component image hashes/addresses, minted stream/cursor ids and seals,
owner/profile fields, counters, diagnostics and subsequent module execution. Vary insertion
history and map size, nested sets/maps, multiple module stores and repeated references; verify
the shared traversal prerequisite, not just final return values. Recompute repository hashes on
receiving native hosts. Also test distinct receiver secrets: compare each receiver against its
own CLJS oracle, since valid resealing intentionally differs across different secrets.

### W5 — lane completion and release gate

Register wasm as an embedder-agnostic backend with Node/CLJS, Wasmtime and Wasmer adapters, with
no changes to the r2 opcode table. Freeze `yin.vm.engine.host.v1` ABI versioning and deterministic binary generation.
Run all named register tests and every semantic golden through the harness; report unsupported
canonical-semantic fragment cases as named refusals with reasons. The wasm backend must not
silently narrow behavior relative to an admitted register image except documented resource
limits or preexisting transport refusal boundaries.

Acceptance: required corpora pass in full, every opcode exercised, public IVM/IVMState behavior
matches (including eval/environment refusals), differential traces agree, no host handle leaks
into wire payloads, and all binary artifacts validate. Run focused JVM reference tests with the
existing `clojure -M:test -n yin.vm.debruijn.register-test` invocation, the existing Node tests
through the documented `clj -M:cljs -m shadow.cljs.devtools.cli compile test` lane, and the new
wasm harness in that lane. Add dedicated native Wasmtime and Wasmer harness tasks and a shared
cross-runtime trace runner; run all W1–W5 cases on Node and both native runtimes against the
reference. No native lane may be reduced to final-value checks. Verify byte-identical artifacts,
`yin.vm.engine.host.v1` import allowlist, malformed ABI/overflow/NEED_SPACE/repeated-fetch handling, no duplicate
effects and no JS/WASI dependencies in native execution. Register these build/tasks during
implementation rather than pretending they exist today. Follow repository quick/slow phase gates
and existing Dart regression lane for shared CLJC edits. These acceptance tests are planned, not
reported execution results.

### W6 — measured optimization, after correctness

Keep Node + Wasmtime + Wasmer full-trace parity mandatory for every optimization. Measure
compilation latency, binary size, native stack depth, arena/heap retention, dispatch cost,
serialized message bytes/copy/codec time, call and suspension cost, and end-to-end time on each
embedder; compare Node against interpreted register and report native results separately without
attributing service-language speed to wasm codegen. Only then coalesce blocks, reduce host
crossings, add dirty spilling, or use optional Binaryen passes. Re-run all parity and binary
validation gates for each transformation. If crossing cost dominates, prototype the
self-contained interpreter-loop variant with native-to-wasm migration of value/scheduler
services and only stream/FFI ABI crossings. Compare it against compiled bodies with batched
definitions/block dispatch on the same workloads; select by measurements while keeping explicit
resumable state. Any service migration reuses/compiles the same Rust crate semantics where the
toolchain/profile permits it, rather than authoring a third engine; the i32-handle `yin.vm.engine.host.v1` ABI and full
kit remain the contract. K3 may measure wasm on this same harness alongside its JVM/Rust/AOT
comparisons; these measurements do not commission K4. This optional service migration is outside
W5’s implementation, not excluded from W6 investigation. A performance regression is possible;
making wasm work is not evidence it is faster.

## 7. Rust and LLVM acceptance retained

The same kit retains the Rust phase gates
(`docs/design/yin.vm.rust-kernel.md:1201–1228`):

- K1: pure fixtures and R/refusal parity; constant stack and one file per
  live activation on tail fixtures; refused-step atomicity; no panic.
  Fault-injected decoded images refuse with `:kernel-invariant` or poison
  at most the one machine, while another task completes.
- K2: payload/effect descriptor canonical bytes equal R4, including every
  return frame's `:live`; bidirectional Rust↔R4 restore and equal
  continuation-defect refusal rules.
- K3: benchmark evidence for owner review before K4 is commissioned.
- K4: deterministic translator bytes across runs/platforms, full-kit
  interpreted/native boundary and refusal equality, artifact-drop
  fallback, and interpretation with no toolchain plus `:unsupported`.
- K5: Level-2 engine parity and blocked-machine wire round-trips with
  JVM R4 in both directions.

Wasm completion does not certify the full K5 standalone node. Rust
interpreter coverage advances alongside the W slices and cannot be
omitted because native wasm passes. Native runtime execution is not CLI
validation, replay, a JS subprocess or a success-only primitive stub.

## 8. Open items

The frozen r5 ambiguities recorded in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.3, [`yang.wasm.md`](./yang.wasm.md)'s, and [`yin.vm.register-wasm.md`](./yin.vm.register-wasm.md)'s **Open items** remain
open here as well, including the W0 `COMPLETE→NEED_SPACE→fetch-response`
notation. The source requirements above are retained without resolving
those details or changing the transport state machine.
