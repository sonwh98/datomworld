Created-GMT: 2026-09-19 21:33:00 GMT
Created-Local: 2026-09-20 05:33:00 +07 (Indochina Time)
Session-ID: f69044f8-7920-435a-98be-f70b24d6181d (resumed — your Phase 4 session)
# Task: dao.lease Phase 4 — reconciliation round r3 (final small items)

Read `collab/1789849500000-reviewer-dao-lease-p4-r2.claude-fable-5-1.findings.md`
(this tree's copy), "Remaining defects". Scope: the same three files.

Dispositions:

- **F1 (P2):** DELETE the capacity-below-drain-budget refusal and its
  matrix row and its test (the reviewer retracted it: a budget larger
  than capacity is the safe case, a smaller one is already covered by the
  silence-suppression rule, and S3 relates retention to facts-per-cadence,
  not to the budget). RESTORE honest capacities in `standard-medium` and
  the handle fixtures (8/16 etc.); validate the budget as an integer
  BEFORE the derived check so no raw host error escapes.
- **F2 (P2):** ORCHESTRATOR DECISION — option 2, record as owed. Do NOT
  add an `:unwrap` seam. In the plan §6 the orchestrator adds the row;
  your part: add the comment to both sketches stating the flattened shape
  is not the transport's (`ws.cljc` deposits
  `{:ws/attachment :ws/event :ws/value payload}`) and that the key, as
  modelled, is self-asserted.
- **F3 (P3):** `get-in` not `get` for the ledger assertion; drain
  `holder-ticks` in the both-halves test so its wiring is exercised.

After fixing: full JVM lane + CLJS lane, report exact counts. Single
simple commands; no staging or commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: dispositions, tests changed, exact counts.
