Created-GMT: 2026-10-18 17:08:35 GMT
Created-Local: 2026-10-19 00:08:35 +07

# Task: re-decompose the wasm specs — create yang.wasm.md as the lowering spec

Role: Lead System Architect (implementation dispatch — owner-directed; the usual
reviewer-family separation is waived for this task by the owner: "claude fable is
out too. use argon").

Owner ruling (2026-10-19 00:0x +07): the current decomposition
(yin.vm.register-wasm.md + yin.vm.conformance-kit.md) is not good. "First we need a
yang.wasm which is a spec to lower the register vm bytecode into wasm." The yang.*
family is the compiler/toolchain side (cf. yang.antlr.md); the lowering deserves its
own spec there, with the runtime/host content staying on the yin side.

Normative content is FROZEN: the five-round consensus text lives in
docs/design/yin.vm.register-wasm.md (distilled from
collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md).
This is a decomposition of that text across files, plus cross-reference repair and
short "where this sits" framing — NOT a redesign. Any tension you meet is flagged in
an Open items note, never resolved.

## Target decomposition

### 1. NEW docs/design/yang.wasm.md — the lowering spec

Title: "yang.wasm: lowering register images to WebAssembly modules". Family/style:
match yang.antlr.md's and rust-kernel.md's conventions (Status header, provenance,
authorities).

Content (moved from yin.vm.register-wasm.md, re-framed as the compiler spec):
- Pipeline position: yang source → Universal AST → semantic vector A → register
  image R (existing stages: yang.antlr.md, yin.vm.semantic.md,
  yin.vm.semantic/derive register-image) → wasm module (this spec). A is the only
  code identity; the emitted wasm is a disposable local artifact with no identity
  (keep the artifact-cache law here — it is a property of the lowering's output).
- The emitted module's contract: core wasm 1.0 feature profile; what the module
  imports (memory, the yin.register.host.v1 exchange import), its table/exports and
  entry convention, byte-determinism of emitted modules (equal image + ABI version
  → equal bytes).
- Code generation: body-per-function, the driver trampoline, structuring (W1
  br_table cases → W6 block coalescing), ALL 23 opcode lowering rows, tail-call
  treatment, boundary live sets and budget/yield points, per-image-row compilation
  and attach/relocation (code growth from the lowering's perspective).
- The target interface specification: yin.register.host.v1 as the ABI the emitted
  code is written against — control-block layout, message framing (TLV tags),
  status codes, the expanded-canonical-bytes ordering and NaN canonicalization
  rules. (The ABI belongs to the lowering spec: it is the interface the compiler
  emits against. The HOST IMPLEMENTATION of the ABI belongs to the yin spec.)
- Sibling path note: the K4 LLVM lowering (rust-kernel.md) as the sibling execution
  strategy under the same equivalence law.
- Open item: the compiled-closure-calls vs single-engine-port tension moves here
  (it is a codegen concern).

### 2. SLIM docs/design/yin.vm.register-wasm.md — the runtime/host spec

Re-title around the host: the wasm execution host for register images — value
arena and linear-memory layout, mandatory map↔memory synchronization, ownership and
collection discipline, the embedder implementations (CLJS adapter; native service =
rust-kernel crates + handle facade; single-engine-port rule), stream/park/custody/
FFI host wiring, W0–W6 slices with toolchain pins, runtime-side risks and non-goals.
Keep the runtime-side Open item (COMPLETE→NEED_SPACE→fetch-response notation).
The host implements the ABI specified in yang.wasm.md — say so with a cross-link,
and do not duplicate the ABI tables here; reference them.

### 3. REPAIR docs/design/yin.vm.conformance-kit.md and docs/design/yin.vm.rust-kernel.md

- conformance-kit.md: cross-references follow the split (lowering-lane gates cite
  yang.wasm.md; host-lane gates cite yin.vm.register-wasm.md).
- rust-kernel.md: the two pointer edits may cite both specs (update the superseded-
  WASM note to name yang.wasm.md as the lowering spec; keep register-wasm for the
  host). Nothing else moves.

### 4. Provenance notes

Each affected file gets a one-line dated note (2026-10-19) that the owner directed
the decomposition of the 2026-10-18 consensus; no Status-line changes beyond what
accuracy requires; rust-kernel.md line coordinates still must not shift.

## Constraints

- Move text; do not rewrite it. Where a section genuinely spans both sides, split it
  at the natural seam and cross-link. No new normative content. No src/ or test/
  changes. Cite .cljc lines only where the current docs already do.
- When done, reply listing the files written, each file's section list, and every
  cross-reference you created or changed.
