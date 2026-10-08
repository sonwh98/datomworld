Created-GMT: 2026-09-19 17:52:06 GMT
Created-Local: 2026-09-20 00:52:06 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: d960a79e-633c-4b1c-bd90-99b60837402e (resumed — your Phase 1+2 session)
# Task: dao.lease Phase 1+2 — reconciliation round r4 (final small items)

The reviewer confirmed N1–N4 fixed. Your N5 fix introduced one P2 blocker
and three P3 nits. Read:
`collab/1789839800000-reviewer-dao-lease-p12-r3.claude-fable-5-1.findings.md`
(this tree's copy), section "Remaining defects", R1–R4.

Scope: still exactly `src/cljc/dao/lease.cljc` and `test/dao/lease_test.cljc`.
Failing test first for R1.

Dispositions (all ACCEPT):

- **R1 (P2, blocker):** a judge assembled without a `:resolver` must throw —
  validate in `initial-judge` (assembly), not inside the per-element `try`:
  throw unless `:resolver` is a function. Add the test: a judge with no
  resolver throws at assembly (or on its first step, outside any catch), and
  a constructed judge whose facts arrive never lapses a lease via dropped
  facts.
- **R2 (P3):** `judge-step` resets both — `(assoc judge :abort nil
  :abort-error nil)` — and `:abort-error` starts nil in the
  `initial-judge` map.
- **R3 (P3):** apply the same per-unit `quot` bound to `:tolerance` at
  assembly.
- **R4 (P3):** fix the `drain-ticks` docstring to match the code (or wrap
  `apply-tick-value` for symmetry — either is fine, state which), and
  reorder the new catches to the house order (`:cljd` first, as in
  `serving.cljc:42`).
- **R5:** no code change — Phase 4 design note; the orchestrator has it.

After fixing: rerun the JVM and CLJS lanes, report exact counts. One single
simple command per step; no chaining. Do not stage or commit. Touch nothing
outside the two files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-finding disposition, tests added, exact counts.
