Created-GMT: 2026-10-18 17:05:00 GMT
Created-Local: 2026-10-18 23:05:00 +07

OWNER DIRECTIVE: the plan cannot live in collab/ (collab files are not committed).
Distill the approved consensus into committed design specs under docs/design/.

Source of truth: your r5 plan
(collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md),
as approved by gemini-4-argon through five rounds (r1 semantic fidelity, r3
portability, r4 determinism, r5 rust-kernel reconciliation — verdicts in the
collab/ review files). The normative content is FROZEN: this is a distillation into
repository design-document form, not a redesign. If you find an inconsistency while
distilling, keep the r5 text and flag it in a short "Open items" note rather than
resolving silently.

Deliverables — three committed files:

1. docs/design/yin.vm.register-wasm.md — the main spec: the register image lowered
   to WebAssembly (execution backend: value arena and linear-memory ABI, import ABI
   and embedder contract, all 23 opcode lowerings, continuation capture/restore,
   effects/FFI/custody, modules and code growth, W0–W6 slices and gates, non-goals,
   risks). Follow the repository design-document conventions: `Status:` header
   (Status: design, owner-authorized planning; not implemented — one line plus the
   consensus provenance: five review rounds, gpt-6-astra author, gemini-4-argon
   reviewer, APPROVE on r5, thread ids, dated), authorities list (datom.world.md,
   yin.vm.debruijn.register.md, yin.vm.semantic.md, yin.vm.linker.md, yin.vm.engine.md,
   yin.vm.rust-kernel.md), and the same prose/table style as
   docs/design/yin.vm.rust-kernel.md. Cite code as file:line from the current tree
   ONLY where you have verified the line in r5; keep rust-kernel.md citations as-is.
2. docs/design/yin.vm.conformance-kit.md — the shared conformance kit spec: one
   fixture kit exported from R4 serving all execution lanes (R4 CLJS, Rust
   interpreter, wasm/Node/Wasmtime/Wasmer, and the planned LLVM lane). Content = the
   rust-kernel K0 kit description extended with the wasm lane's differential-harness
   requirements (trace comparison, determinism rules, cross-runtime gates). Status
   header same style; note it is the K0/W0 shared deliverable of both specs.
3. Amend docs/design/yin.vm.rust-kernel.md minimally: in the DEFERRED list, mark
   "WASM as an artifact target" as superseded by yin.vm.register-wasm.md (owner
   authorized the wasm plan 2026-10-18); add the related-spec cross-references
   (register-wasm, conformance-kit); do NOT change its Status line or any normative
   content — the edit is the two pointer changes plus a dated one-line note.

Constraints: write only these three files; change nothing in src/ or test/;
do not renumber rust-kernel revisions. Every claim in the new docs must be present
in r5 or rust-kernel.md — no new normative content. Line references to .cljc files
must be ones r5 already verified.

When done, reply with the list of files written and their section counts.
