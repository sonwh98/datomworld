I’ll check the trace-oracle changes against the transitions and expected events, and verify that this commit leaves the frozen design unchanged.
Completed-GMT: 2026-10-10 13:03:33 GMT
Completed-Local: 2026-10-10 20:03:33 Asia/Bangkok

No remaining findings. Reviewed commit `0ff25b58`; its diff changes only the evaluator companion document. The frozen design is unchanged.

1. **Tagged extraction:** Consistent throughout the register loop, extraction table, comparison, and diagnostics. Remaining `:none` occurrences describe ordinary values or regression rows; none serves as a sentinel. Nil and false results also remain observable events.

2. **Park recognition:** Both loops now classify parking from the executed node or instruction. They emit `[:park id]` and stop before generic halt handling. A parked-shaped literal instead produces a value event followed by a halt event. This agrees with 4b’s record-level park/resume checks; blocked programs remain outside the step trace.

3. **Four new rows:** All are well-formed, and their expected traces are correct:
   - `:literal-none`: value, then halt.
   - `:parked-shaped-literal`: the unchanged map as value, then halt.
   - `:park-then-halt`: only `[:park :parked-0]`.
   - `:literal-none-in-call`: closure, operand `:none`, body variable result `:none`, then halt. Closure entry and `:return` add no event.

   The fresh-task assumption is visible in §4.5’s park row and in both algorithms’ fresh VM construction. No preceding operation advances the counter, so `engine/park-id` yields `:parked-0`.

4. **Stale wording:** The §4 introduction and §4.5 item 5 are corrected. The two remaining old formulations occur in explicitly identified replacement quotations under §6 F5 and may remain.

5. **Implementation readiness:** Nothing remains blocking the phase-4a brief. Runtime verification of these pinned traces belongs to the implementation gates; no tests were run for this read-only design review.

VERDICT: READY_TO_MERGE
