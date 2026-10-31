Created-GMT: 2026-09-13 17:54:00 GMT
Created-Local: 2026-09-14 00:54:00 +07:00

# Task: Phase 2 Final Fixes (Lowering compiler)

Role: Compiler Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-14 00:54:00 +07:00 | Status: active

The Architect (gpt-6-astra) evaluated your fixes. The structural traversal fix is sound, but two extreme edge cases remain:

1. [P1] §2.5 host-free boundary bypass via metadata. Lowering `(with-meta {:x 1} {:host inc})` preserves the host function in the emitted literal’s metadata.
   - Correction: Recursively validate metadata, or explicitly strip it recursively before emission. Add tests.
2. [P2] ID validation incomplete: passing `false` to `:id-start` silently selects the default instead of failing. Also, CLJS/Node numeric boundaries break distinctness: subtracting from `Number.MIN_SAFE_INTEGER` (`-9007199254740992`) loses precision in JS floats, causing identical IDs to be emitted.
   - Correction: Reject invalid supplied options like `false`. Validate the entire allocation range against host numeric limits (must produce mathematically distinct IDs across CLJ/CLJS/CLJD). Add numeric-boundary regressions.

Modify `src/cljc/yin/vm/linearize.cljc` and `test/yin/vm/linearize_test.cljc` to fix these.
