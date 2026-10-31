Created-GMT: 2026-09-23 07:58:00 GMT
Created-Local: 2026-09-23 14:58:00 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: pending (provider-generated; capture on first response)

# Task: reviewer-debruijn-b4 — independent adversarial review of De Bruijn Stack VM Phase B4 (effects and continuations)

Role: Reviewer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-23 14:58:00 +07 | Status: active | Rationale: independent reviewer for VM and storage runtimes, reviewing claude-fable-5-1's Phase B4 implementation

Work in /Users/sto/workspace/worktree-debruijn-b4 (read-only; do NOT edit files or run git add/commit).
Evaluate git diff against master (HEAD ba88f769).

## Read first, in full

1. `docs/design/yin.vm.debruijn.stack.md`, specifically §4.1 (Engine seam and suspension protocol) and §6 (Phase B4: effects and continuations).
2. `docs/design/yin.vm.engine.md`, §7 (Engine additions and restore contract).
3. `docs/design/datom.world.md`, foundational axioms and non-negotiable invariants.
4. `src/cljc/yin/vm/debruijn/stack.cljc` (the B4 stack VM implementation).
5. `src/cljc/yin/vm/engine.cljc` and `src/cljc/yin/vm/ffi.cljc` (engine seam additions).
6. `test/yin/vm/debruijn/stack_effects_test.cljc` (new B4 test suite, 30 tests, 201 assertions).
7. `test/yin/vm/debruijn/stack_test.cljc`, `test/yin/vm/debruijn_linearize_test.cljc`, `test/yin/vm/engine_test.cljc`.

## Review Mandate & Verification Obligations

Perform an adversarial defect hunt. Look for architectural contradictions, subtle invariant violations, and edge cases. In particular:

1. **Engine Seam Bounds & Protocol:**
   - Did B4 strictly limit `src/cljc/yin/vm/engine.cljc` edits to the three permitted additive changes (`scheduler-round`, 3-arity `(restore-fn base entry val)` while preserving 2-arity compatibility)?
   - Did `src/cljc/yin/vm/ffi.cljc` implement `response-wait-entry` correctly by dissoc'ing only `:request-sent :op :datom` and adding the four reader keys?
   - Is `:status` completely eliminated, and are `halted?`, `blocked?`, and `value` properly delegated to `engine`?

2. **Stack Layout & Operand Order:**
   - For `:stream-put`: does it pop target from the stack and take value from top, matching what `lower-stack` emits?
   - For `:stream-cursor`, `:stream-next`, `:stream-close`: are stream/cursor references popped in exact alignment with bytecode generation?
   - For `:ffi-call`: does it pop arguments correctly according to `argc` and restore the stack slice without corrupting callers?

3. **Restoration Protocol (`stack-restore`):**
   - Does `stack-restore [base entry val]` validate format (`:format :yin.debruijn.code`) and hash (`:hash H`), throwing `:rule :continuation-format` on mismatch?
   - Does it correctly handle `:request-sent` (re-parking), `:call-id` (unwrapping and dropping parked record), and standard resumption (pushing `val` onto the restored stack and clearing `:halted?`)?
   - Does single-image resumption hold? (Cross-program continuation resumption refused by design).

4. **Pure Data & Serialization Safety:**
   - Are parked entries and wait entries pure data (registers and resource IDs only)?
   - Verify that neither live stream handles nor host functions/closures are leaked into waitset entries or serialized continuation frames.

5. **Test Coverage & Coincidence Modes:**
   - Do the 30 tests in `stack_effects_test.cljc` prove true behavioral parity with `yin.vm.semantic` under B0 normalization?
   - Are there coincidence modes or false-positive passes in the tests (e.g. commutative operations masking stack inversion)?

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <session-id>

Followed by your verdict (READY, READY WITH CHANGES, or REJECTED) and categorized findings (P1, P2, P3).
