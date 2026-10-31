Created-GMT: 2026-10-18 18:49:17 GMT
Created-Local: 2026-10-19 01:49:17 +07

# Task: independent fidelity review of the final wasm spec tree

You authored the original consensus (r1–r5). The committed spec tree has since been
restructured in three owner-directed steps, all executed by gemini-4-argon under an
owner waiver of reviewer independence — so YOU are now the independent check.

## The final tree (what should exist)

1. docs/design/yin.vm.engine.md — existing body + new "Extension (2026-10-19,
   design only)": guest-agnostic execution host — value arena, machine-state-as-data
   boundary, the service protocol renamed `yin.vm.engine.host.v1` (owner ruling),
   embedder seam (rust-kernel crates + handle facade, single-engine-port), and
   §E.5 the conformance METHOD (oracle export, differential lanes, B0 comparison,
   determinism gates). Also a dated correction of the stale `:store-updates` prose.
2. docs/design/yin.vm.debruijn.register.md — existing doc + new "§10 Wasm execution
   instance (2026-10-19, design only)": eight-field register instance, §10.3
   attachment/spawn/artifact-cache, §10.4 register fixture schema (K0/W0 kit's
   register half), §10.5 register W0–W6 slice gates (incl. §10.5.8 Rust K1–K5),
   §10.6 risks/open items.
3. docs/design/yang.wasm.md — the lowering spec: pipeline position, emitted module
   contract, codegen (all 23 opcodes, trampoline, structuring, tail, live sets,
   per-image-row attach), the target ABI REFERENCES engine.md's tables (not
   duplicated), LLVM-sibling note.
4. docs/design/yin.vm.rust-kernel.md — pointer-only amendments; line coordinates
   1–1334 unchanged.

Retired (content folded, files archived as
collab/retired-2026-10-19-yin.vm.register-wasm.md and
collab/retired-2026-10-19-yin.vm.conformance-kit.md):
- yin.vm.register-wasm.md (register instance → register.md §10)
- yin.vm.conformance-kit.md (method → engine.md §E.5; fixtures → register.md §10.4)

## Review tasks

1. SECTION→HOME COMPLETENESS: for every section of both retired files (their full
   texts are the two collab/retired-2026-10-19-* copies), verify its normative
   content exists in its new home. Read both retired copies in full and diff
   section by section against the survivors. A dropped normative element is a
   FAIL finding.
2. NO-DUPLICATION: the ABI tables live exactly once (engine.md); yang.wasm.md and
   register.md reference, not restate. The conformance method lives exactly once
   (engine.md §E.5); register.md §10.4 holds fixtures, not method.
3. RENAME: `yin.vm.engine.host.v1` everywhere live; surviving mentions of the old
   name are provenance notes only.
4. rust-kernel.md: verify coordinates 1–1334 unchanged (the footer notes claim it);
   pointers correct.
5. LINKS: no markdown link in docs/design/*.md resolves to a retired file.
6. GENERALIZATION SEAMS: where the engine extension states a pattern generically
   (machine-state-as-data, acceptance properties), confirm the register instance
   in register.md §10 still states the register-specific facts completely
   (eight fields, payload/live sets, opcode coverage) — generalization must not
   have erased register facts.
7. Frozen-content rule: the restructuring was move-and-generalize-only. Flag any
   NEW normative claim introduced during the reshape (vs the retired texts and r5)
   that is not a provenance/amendment note.

Write findings to:
collab/1792349357932-architect-wasm-spec-tree-fidelity.gpt-6-astra.findings.md
End with PASS (ready to commit) or FAIL with specific corrections. Read-only except
that findings file.
