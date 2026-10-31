Created-GMT: 2026-09-19 16:32:43 GMT
Created-Local: 2026-09-19 23:32:43 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: d960a79e-633c-4b1c-bd90-99b60837402e (resumed — your Phase 1+2 session)
# Task: dao.lease Phase 1+2 — reconciliation round r3

The reviewer re-examined your r2 fixes: fourteen of eighteen confirmed, but
the round introduced new defects — one a P1 regression. Read:

- `collab/1789833800000-reviewer-dao-lease-p12-r2.claude-fable-5-1.findings.md`
  (this tree's copy) — findings N1–N6 with prescribed fixes and dispositions
  of the original 18 (14 confirmed fixed, P2-4 relocated, P2-5 and the
  assembly P3 partly fixed)

Scope: still exactly `src/cljc/dao/lease.cljc` and `test/dao/lease_test.cljc`.
Failing test first for every N.

Dispositions (all ACCEPT):

- **N1 (P1 regression):** remove the 10⁶ bound everywhere. Replace with the
  reviewer's scheme: raw magnitudes bounded at 2⁵² (`duration?`/`tolerance?`
  — tolerance permitting zero), and against a supplied unit table also
  `n ≤ (quot 2⁵² unit-magnitude)` — division-based, so overflow-free. Then
  every product ≤ 2⁵² and every sum ≤ 2⁵³: exact on JVM, exact/representable
  on cljs, far from Dart wrap; ms readings stay valid ~140,000 years.
  Update `initial-judge`'s table validation and every test that pins
  `{:s 1000000}` as a legal maximum. New tests: a tick far past 10⁶ advances
  `now`; an over-bound reading is a structural defect; an over-bound unit
  table is refused at assembly; an over-bound tick on a tick cursor is
  visible (counted under a signal or `:dropped`), never silent.
- **N2 (P2-4 relocated):** key answers by the *proposal's author*:
  `[proposal-author pid]`. The `:answer` hook receives the drained proposals
  with their authors, so it can return the proposer alongside each refusal
  and each answering grant; `deliver-authored` records and checks against
  that key. Design the exact shape, document it, and add the two-holder
  same-pid collision test (B's refused `:p1` must not block A's `:p1`).
- **N3 (P2):** register a lease on a medium ONLY from holder-authored facts
  (an eligible renewal or release). Remove the self-authored-grant
  registration clause. Add the test: a grant drained from a fact cursor, the
  holder's medium truncated before its first renewal, expecting NO silence
  lapse.
- **N4 (P2):** `initial-judge` validates `:tolerance` — `tolerance?` shape
  AND its unit present in the table — throwing at assembly. Add the test.
- **N5 (P2/P3):** separate the catches. A throw while PROCESSING a value
  advances the cursor past that element, counts it under `:dropped`, and
  continues (store the message under an `:abort-error` key only for real
  aborts). A throw from the READ itself still aborts the pass as
  `:dao.stream/transport-error`. Catch `Exception` under `:clj` (never
  `Throwable`), the host-appropriate error type on cljs/cljd. Correct the
  false "no cursor is left wedged" docstring claim to describe the actual
  two behaviors. Test: a resolver that throws on a hostile envelope — the
  element is dropped, the cursor advances, judging continues.
- **N6:** no code change — the orchestrator has recorded it in §6.

After fixing: rerun the JVM and CLJS lanes and report exact counts; the
orchestrator runs CLJD. One single simple command per step; no chaining. Do
not stage or commit. Touch nothing outside the two files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-finding disposition, new/updated tests, exact counts, and
anything unresolved.
