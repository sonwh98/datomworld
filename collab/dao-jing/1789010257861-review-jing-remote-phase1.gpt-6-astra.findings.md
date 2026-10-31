Completed-GMT: 2026-09-10 03:19:07 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (Phase 1) | Model: gpt-6-astra

I’ll review the two-file diff against §4.1, focusing on cleanup, correlation and step outcomes. I won’t rerun the lanes or revisit the settled transport scope.

**Clean and ready to commit.** No blocking findings in the two-file diff.

- **N11:** Test 5 carries the drained state into each subsequent refusal and asserts empty outboxes, so the pin is non-vacuous. `drain-outboxes` also clears allocation-failure completions without depending on their shape. **Wiring all four exits through it remains Phase 2’s `settle!` obligation**; this phase supplies and tests the primitive.
- **`retire-call`:** Drains abandonment on return, removes the retired id, and preserves the allocator. The scripted late-response test proves id 1 receives its own value and leaves neither response bookkeeping nor diagnostics behind.
- **`call-step`:** Performs at most one unsent retry and a budgeted poll, with no waiting loop. Its discard policy is explicitly scoped to one awaited call.
- **Establishment:** The test proves a response advances the cursor without establishing, and the following lifecycle event still establishes.
- **Terminal outcomes:** Both tested shapes are correct: an outstanding awaited call produces `:done` with a loss completion; without that completion, the step returns `:terminal`. Phase 2 already handles both.
- **Requires and neutrality:** Deferring the three unused requires is justified. Phase 2 can add them when their call sites land and the v1 alias is removed. Existing function bodies and tests are untouched; no new helper requires redesign.

One nonblocking documentation improvement: qualify §4.1 test 2’s “`/detached` → `:terminal`” with **“with nothing outstanding”**, and mention the loss-completion case. The implemented tests are more precise than that sentence.
