Completed-GMT: 2026-09-19 19:21:27 GMT
Completed-Local: 2026-09-20 02:21:27 +07

# Phase 3 final gate: H1/H2

**The Phase 3 delta is ready for commit.** Both fixes hold and I found no new defect. I edited nothing and did not rerun suites.

| Finding | Disposition | Evidence | Remaining action |
|---|---|---|---|
| H1 `holding?` true past the bound | **Fixed** | `holding?` now takes `[holder reading]`. It requires a grant, not released, not latched, not undersized, `reading-ok?` true and `at-bound?` false, so a stall shows up without any `observe-renewal` call. `r2-holding-predicate-test` pins the unlatched holder at reading 11, which is exactly grant 1 plus duration 10. That boundary case is a stronger check than reading 30. It also pins `nil` answering false. All nine call sites use the new two-argument form. | None. |
| H2 `at-bound?` false on an invalid reading | **Fixed as prescribed** | The docstring now says false on an invalid reading means "UNANSWERABLE, never free to act" and sends flows through `holding?`, which fails closed on the same input. | One cosmetic item: the docstring's last sentence is broken across lines ("it revokes" / "nothing."). Re-wrap it when convenient. |
| P3 notes (tick period not covered by the `:undersized?` check; 2⁵⁴ ClojureScript edge) | **Accepted as documented limits** | The orchestrator reports both are recorded as documented limits; I did not re-read the plan. | None. |

## On not adding `observe-reading`

I agree with leaving it out. Without it, a reading smaller than an earlier one could make `holding?` true again after it had answered false. The contract already rules that out, because readings on the holder's own tick stream never decrease. `holding?` recomputes from the reading on every call. The latch already covers the one path that moves the basis, which is `observe-renewal`.

If a Phase 4 composition ever wires tick sources that are not monotonic, add `observe-reading` then.
