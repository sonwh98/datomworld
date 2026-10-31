Created-GMT: 2026-09-13 17:39:00 GMT
Created-Local: 2026-09-14 00:39:00 +07:00

# Task: Semantic VM Phase 2 (Lowering compiler)

Role: Compiler Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-14 00:39:00 +07:00 | Status: active | Rationale: Excellent for AST/Compiler work.

Implement Phase 2 of the Semantic VM.

Read first:
- docs/design/yin.vm.semantic.md (focus on §5 Lowering)
- src/cljc/yin/vm.cljc
- src/cljc/yin/vm/code.cljc

Deliverables:
1. `src/cljc/yin/vm/linearize.cljc`
   - Implement `lower` and `lower-ast` according to the rules in §5 of the design document.
   - Wire `eval` and `vm-load-program` to accept the linearizer's output.
   - Reject unsupported nodes (`:yin/macro-expand`, `:vm/store-update`) with an error naming the node.

2. `test/yin/vm/linearize_test.cljc`
   - Write unit tests proving: pcs are dense, every ref resolves, every lowered instruction has `:yin.code/source`, and evaluation order matches the walker on a program of `yin/def` side effects.
   - Note: Do not run `parity_test.cljc` yet, as the Phase 1 VM is being implemented concurrently. Focus solely on verifying the structural output of the compiler against the contract.

Execute this task and create the necessary files. Do not modify the design docs.
