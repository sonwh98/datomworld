Created-GMT: 2026-09-13 19:10:00 GMT
Created-Local: 2026-09-14 02:10:00 +07:00

# Task: Semantic VM Phase 4 (Benchmarks, divergence, default decision)

Role: Performance Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-14 02:10:00 +07:00 | Status: active

The first three phases are complete and committed. You are tasked with implementing the final Phase 4 deliverables (as defined in `docs/design/yin.vm.semantic.md` §8 Phase 4):

1. **Benchmarks:** Write and run Criterium benchmarks (`bench` or `quick-bench`) over `vm/run` alone (VM construction and load outside the timed region).
2. **CLJS runs:** Run the existing benchmark harnesses for ClojureScript on Node.js (and CLJD if it exists).
3. **Results Table:** Append the resulting performance table to the bottom of `docs/design/yin.vm.semantic.md` under a new "§8. Phase 4 Benchmarks" section (replacing the note that says "Acceptance in Phase 4 is measured, not asserted").
4. **Divergence Register:** Create `docs/design/yin.vm.divergence-register.md` documenting any behavioral differences between the Semantic VM and the AST Walker (for example, the stream retention or any FFI differences discovered in Phase 3).
5. **Default Decision:** If the Semantic VM hits the targeted performance line (close to the 1.0–1.1x register/stack line compared to walker's 1.32x), make it the default evaluator in `yin.repl.core` if it isn't already.

Run the tests/benchmarks and commit the result.
