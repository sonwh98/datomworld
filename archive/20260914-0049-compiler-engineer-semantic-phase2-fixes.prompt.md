Created-GMT: 2026-09-13 17:49:00 GMT
Created-Local: 2026-09-14 00:49:00 +07:00

# Task: Phase 2 Fixes (Lowering compiler)

Role: Compiler Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-14 00:49:00 +07:00 | Status: active

The Lead System Architect (gpt-6-astra) reviewed your implementation of `src/cljc/yin/vm/linearize.cljc` and issued a "Verdict: request changes" due to three contract gaps:

1. [P1] §2.5 permits literal maps. The unrestricted tree walk in `lower-ast` incorrectly rejects `{:type :literal :value {:type :vm/store-update}}` (a valid literal). 
   - Correction: Traverse only structural AST children. Treat literal values and other data operands as opaque. Add tests for this.
2. [P2] Custom tempids (`:id-start`) bypass collision and negative-integer checks.
   - Correction: Validate the entire allocated range (must be negative integers, distinct IDs, no overlap with input entities). Add tests.
3. [P1] §2.5 prohibits host functions in code datoms. A literal containing a host function (e.g. `{:nested [f]}`) preserves it, violating Axiom 4.
   - Correction: Enforce the plain-data boundary recursively for emitted data operands; reject host functions/objects with source-node evidence. Resolve primitives through symbolic `:var` instructions.

Modify `src/cljc/yin/vm/linearize.cljc` and `test/yin/vm/linearize_test.cljc` to fix these 3 issues.
