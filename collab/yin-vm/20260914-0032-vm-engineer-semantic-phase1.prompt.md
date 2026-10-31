Created-GMT: 2026-09-13 17:32:00 GMT
Created-Local: 2026-09-14 00:32:00 +07:00

# Task: Semantic VM Phase 1 (Core linear interpreter)

Role: VM Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-14 00:32:00 +07:00 | Status: active | Rationale: Exceptional at complex VM runtimes and state machines.

Implement Phase 1 of the Semantic VM.

Read first:
- docs/design/yin.vm.semantic.md
- src/cljc/yin/vm.cljc
- src/cljc/yin/vm/ffi.cljc
- src/cljc/yin/vm/code.cljc

Deliverables:
1. `src/cljc/yin/vm/semantic.cljc`
   - Implement the `yin.vm/IVM` and `IVMState` protocols.
   - Implement a `create-vm` constructor.
   - Implement a `load-image` builder that complies with `yin.vm.code/well-formed?`.
   - Implement the hot loop processing the §4 transitions for: `const`, `var`, `closure`, `push`, `call`, `return`, `jump`, `branch-false`, `halt`, `gensym`, `store-get`, `store-put`, `current-continuation`, `park`, `resume`.

2. `test/yin/vm/semantic_test.cljc`
   - Write tests running hand-assembled segments (no compiler yet).
   - Test cases: arithmetic, closure call, tail-recursive countdown to depth 10⁵ (must verify bounded `k` and `St` memory limits), `if` branches, park/resume, and current-continuation reification round-trip via `pr-str`/`read-string`.
