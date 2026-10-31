Created-GMT: 2026-10-18 15:50:00 GMT
Created-Local: 2026-10-18 22:50:00 +07

NEW AUTHORITY DISCOVERED by the owner: docs/design/yin.vm.rust-kernel.md (revision 2,
2026-10-03, design only, not authorized for implementation). Read it in full before
revising. It designs (a) a Rust port of the register kernel as a peer interpreter on
dao.stream (crates yin-value/yin-scalar/yin-image/yin-kernel/yin-prims/yin-engine/
yin-wire; phases K0 conformance kit, K1 Level 0, K2 effects offload, K3 benchmark,
K4 LLVM path, K5 standalone Level 2), and (b) an LLVM lowering of register images
(body-per-function, entry-pc switch, yin call never a native call, live sets as spill
map, artifacts as node-local caches, one transition implementation two dispatchers).
Its DEFERRED list includes "WASM as an artifact target" — which your plan supersedes.

Revise your plan (r4) to reconcile with rust-kernel.md. Required:

1. Cite rust-kernel.md as an authority; state subordination where it governs (it is
   design-only — your plan stays the wasm authorization, but must not contradict it
   silently). Record that your plan supersedes rust-kernel's DEFERRED item "WASM as
   an artifact target".
2. SINGLE ENGINE PORT RULE: the native (Rust) embedder's service library must BE the
   rust-kernel design's crates (yin-value, yin-kernel, yin-engine, yin-wire, ...),
   extended with a handle/ABI facade — never a second, parallel port of the kernel or
   engine semantics. The "two dispatchers" risk applies across your plan's lanes too:
   one transition implementation, reached by the CLJS kernel, the Rust interpreter,
   and the wasm service facade.
3. VALUE FACADE: rust-kernel K-D2 fixes the native Value representation (16-byte
   repr(C) enum, Rc-counted, explicitly not NaN-boxed); your plan fixes the wasm-side
   representation (i32 handles into an embedder arena). Specify the handle<->Value
   facade as an explicit layer of the native embedder: handles are task-local indices
   into the embedder's Value arena; the arena holds yin-kernel Values; generation
   rules per your §1.3. State which of argon's round-3 determinism rules
   (canonical encoded bytes ordering, NaN canonicalization, framing) the facade
   inherits unchanged.
4. SHARED CONFORMANCE KIT: your W0 differential harness and rust-kernel's K0 kit
   (exported from R4) are the same deliverable. Make W0 consume/extend K0's exported
   fixtures (images, R, B0-normalized results, boundary traces, refusal fixtures,
   atomicity fixtures) rather than define a second kit; the three execution lanes
   (R4 CLJS, Rust kernel/interpreter, wasm) share one kit.
5. LLVM RELATIONSHIP: state the wasm path and rust-kernel's K4 LLVM path are sibling
   execution strategies under the same equivalence law (rust-kernel 7.2) and the same
   artifact-cache rules (node-local, no identity, never a payload); no fork in
   validator, kit, or semantics between them. K3's benchmark gate may later measure
   the wasm lane on the same harness.
6. Any rust-kernel owner question your plan implicitly answers (e.g. its §11
   question 1 about kernels translating held images) — note the alignment, do not
   re-litigate.

Verify all citations (rust-kernel line numbers included) against the actual documents.
Deliverable: complete revised plan to
collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md
with a "Round 5 changes" section at the end. Read-only except that file.
