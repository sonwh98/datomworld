# yin.vm.v2 consumers — The Archival Plan

Status: implementation plan for migrating remaining v1 VM components (`yin.vm.macro`, `yin.vm.space`, `yin.vm.wasm`, and related test suites) to `yin.vm.v2` and `dao.stream.v2`. 
Subordinate to `yin.vm.v2.divergence-register.md` and `dao.runtime.v2.implementation-plan.md`.

## The Problem and Context

The `yin.vm.v2` architecture strictly decouples interpretation from evaluation and simplifies the runtime. The divergence register (`docs/design/yin.vm.v2.divergence-register.md`) specifies that:
1. The v2 corpus is macro-free by construction, and user-defined macros stop evaluating in v2. The `ast-walker` has no macro-expand branch.
2. The scope of `yin.vm.v2` is explicitly limited to the `ast-walker` slice. Experimental VM models such as `semantic`, `register`, `stack`, `space`, and `wasm` are explicitly excluded and not ported.

These components (`yin.vm.macro`, `yin.vm.space`, `yin.vm.wasm`, etc.) are heavily coupled to the v1 `yin.vm` interfaces, including the legacy `dao.stream` mechanisms and internal VM state structures. Porting them to `dao.stream.v2` and `yin.vm.v2` would require massive rewrites that violate the bounded complexity principle, especially for experimental features that are now obsolete under the v2 design.

## The Strategy

Rather than adapting obsolete and experimental components to the v2 stream-observer paradigm, they will be explicitly deprecated and deleted. This respects the invariants of the v2 design and unblocks Phase R4 of the `dao.runtime.v2` implementation plan.

## Implementation Phases

### Phase 1: Deletion of Experimental v1 VMs
The experimental VM models are not part of the v2 `ast-walker` scope and rely on legacy v1 streams.
- **Delete Files:**
  - `src/cljc/yin/vm/space.cljc`
  - `src/cljc/yin/vm/wasm.cljc`
  - `src/cljc/yin/vm/semantic.cljc`
  - `src/cljc/yin/vm/stack.cljc`
  - `src/cljc/yin/vm/register.cljc`
- **Delete Test Suites:**
  - `test/yin/vm/space_test.cljc`
  - `test/yin/vm/wasm_test.cljc`
- **Criteria for Completion:** The files are deleted, and no references to them remain in the active codebase (such as in `test/yin/vm/parity_test.cljc` or `src/clj/yin/vm/bytecode_bench.clj`).

### Phase 2: Deletion of Macro Engine
The v2 stream observer and evaluator operate on a macro-free corpus. The `yin.vm.macro` engine, which evaluates `:yin/macro-expand` nodes at runtime, is fundamentally incompatible with the v2 design where macros are not evaluated.
- **Delete Files:**
  - `src/cljc/yin/vm/macro.cljc`
- **Delete Test Suites:**
  - `test/yin/vm/macro_test.cljc`
  - `test/yang/macro_test.clj`
- **Criteria for Completion:** The files are deleted. Any bootstrap macro registries previously passed during VM instantiation are removed from caller code.

### Phase 3: Cleanup and Documentation Updates
Remove all stale references to the deleted modules to ensure a clean build.
- **Update Benchmarks and Utilities:**
  - Clean up `src/clj/yin/vm/bytecode_bench.clj` and `test/yin/vm/test_utils.cljc` to remove dependencies on the deleted components.
- **Update Documentation:**
  - Add notes to `docs/cesk-space-optimization.md` and `docs/design/yin.vm.streams-all-the-way-down.md` indicating that the experimental CESK space and WASM targets have been archived and deleted in favor of the v2 `ast-walker` architecture.
- **Criteria for Completion:** The test suite passes cleanly on all hosts (clj, cljs, cljd) and no broken links or stale references to the deleted components exist in the repository.

## End Condition
This plan is complete when all obsolete modules and their test suites are deleted from the repository, and the build is green across all platforms. This fulfills the prerequisites for safely executing Phase R4 of the `dao.runtime.v2.implementation-plan.md` (deletion of legacy `dao.runtime`).
