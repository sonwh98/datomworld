Created-GMT: 2026-09-23 11:44:00 GMT
Created-Local: 2026-09-23 18:44:00 +07:00

# Task: Register VM Phase R2 Technical Implementation Blueprint

Role: Lead System Architect / Yin.VM Runtime Engineer

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 18:44:00 +07:00 | Status: active | Rationale: Authored normative R2 specification in yin.vm.debruijn.register.md; frontier reasoning for pure effect contracts, sparse continuation layouts, and engine seam integration.

## Mission
You are the author of the Phase R2 specification in `docs/design/yin.vm.debruijn.register.md`.
Your mission is to provide the comprehensive, line-level technical implementation blueprint for **Register VM Phase R2 (effects, stream forms, and engine seam)**.

This blueprint will be handed directly to an implementation engineer to write the code and verify across JVM, Node/CLJS, and ClojureDart. Be thorough, concrete, and unambiguous.

## Context & Inputs to Read
1. `docs/design/yin.vm.debruijn.register.md` (Sections 4.4, 4.5, 4.6, 5.2, and Section 6 R2).
2. `docs/design/yin.vm.engine.md` (Shared engine contract, restore function, wait-entry shapes, stale-wake invariant).
3. `/Users/sto/workspace/worktree-register-r0/src/cljc/yin/vm/debruijn_register_code.cljc` (R1 descriptor, instruction mnemonics, `body-liveness`, validator).
4. `/Users/sto/workspace/worktree-register-r0/src/cljc/yin/vm/debruijn_register_compile.cljc` (R1 lowering from resolved AST records).
5. `src/cljc/yin/vm/engine.cljc` and `src/cljc/yin/vm/debruijn/stack.cljc` (B4 stack VM effect and continuation reference).

## Required Deliverables in Your Report

### 1. `src/cljc/yin/vm/debruijn_register_effects.cljc`
Specify the complete namespace design, dependencies, and exact logic for:
- `(effect-descriptor [instruction registers]) -> effect-map | nil`
  For each R2 instruction: `:store-get`, `:store-put`, `:gensym`, `:stream-make`, `:stream-put`, `:stream-cursor`, `:stream-next`, `:stream-close`, `:ffi-call`, `:current-continuation`, `:park`, `:resume`.
- `(continuation-payload [runtime instruction]) -> payload-map`
  Sparse snapshot construction: extracting only live registers per `(:live instruction)`, framing `:segment`, `:site-pc`, `:pc`, `:frames`, `:registers`, `:destination`, `:resume-mode`, `:format :yin.debruijn.register`, `:hash R`.
- `(continuation-defect [payload]) -> defect-map | nil`
  Validation of continuation payload: checking format, hash, pc bounds, destination bounds, live set match against image operand, sparse register index validity.
- `(wait-entry-defect [entry]) -> defect-map | nil`
  Validation of engine wait entries: stream writer/reader, FFI writer/reader. Validating resource IDs, ensuring no foreign format/R, ensuring removal of ALL stale wake keys (`:value`, `:status`, `:cursor`, `:store-updates`, `:stream`, `:datom`, `:type`, `:id`, `:request-sent`, `:op`). Refusing raw host exceptions.

### 2. `src/cljc/yin/vm/debruijn_register_code.cljc` Extensions
- Bump register descriptor to `:contract-version 3`.
- The 12 opcode definitions, short slots, and operand kind specs (`:reg`, `:regs`, `:uint`, `:str`, `:kw`, `:data`).
- Complete extension of `body-liveness` use/def and successor transitions for all 12 R2 opcodes per §4.5.
- The 4 validator rules: `:live-shape`, `:live-bounds`, `:live-tail`, `:live-exact` for all instructions carrying a `live` slot.

### 3. `src/cljc/yin/vm/debruijn_register_compile.cljc` Extensions
- Lowering logic for each of the 12 resolved node types per §4.6.
- Temporary register allocation and release order for child operands.
- Handling of `:resume` (no target register, control transfer).

### 4. `test/yin/vm/debruijn_register_effects_test.cljc` & Contract Test Extensions
- Test structure for effect descriptors, continuation payloads, defect predicates, EDN round-tripping, and B4 engine seam equivalence.
- Guidance on re-pinning descriptor hashes and golden R vectors for version 3.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
