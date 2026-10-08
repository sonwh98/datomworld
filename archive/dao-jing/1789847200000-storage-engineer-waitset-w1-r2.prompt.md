Created-GMT: 2026-09-19 20:37:00 GMT
Created-Local: 2026-09-20 04:37:00 +07 (Indochina Time)
Session-ID: e2bdcac2-3d4f-400f-8a73-e8825e773dc9 (resumed — your W1 session)
# Task: W1 reconciliation — the review's 2 P2 + 3 P3

The review confirmed the sweep mechanics but blocked commit on two P2s.
Read `collab/1789846800000-reviewer-waitset-w1.claude-fable-5-1.findings.md`.
Scope: the same two files. Failing test first for each P2.

Dispositions (all ACCEPT):

- **P2 invalid answers:** wrap each poll answer in
  `stream/valid-outcome?`; an invalid one folds into a qualified terminal
  status `:dao.stream.waitset/invalid-answer` (entry wakes, leaves
  `:waiting`, no `:advance`). Add the register row. For host dispatch
  errors: wrap the per-entry poll in a try/catch that folds a throw into
  `:dao.stream.waitset/invalid-answer` as well (the entry cannot be
  trusted again) — state the choice in the docstring, add the register
  row and tests (nil outcome, non-map answer, protocol-failing handle).
  A resolver throw mid-sweep: document in `check`'s docstring that
  resolvers must not throw and a throw voids the sweep (faithful to the
  engine); add the register row.
- **P2 advance counters:** `check-one` returns the counter; assert 1
  advance for reader `ok` and `gap`, 0 for every other reader outcome and
  for the rogue keyword; add the missing `zero?` assertion in the
  resolve-without-stream test.
- **P3 park total:** `(update waitset :waiting (fnil conj []) entry)` plus
  the assertion.
- **P3 missing `:value`:** docstring note — the transport answers
  `invalid-value`; the engine is faithfully loose here too.
- **P3 (test:377-388):** covered by the counter assertions above.

Verification, single simple commands: focused lane, full JVM lane, CLJS
lane (mise workaround as before). No staging, no commit. The orchestrator
runs CLJD.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-finding disposition, tests added, exact counts.
