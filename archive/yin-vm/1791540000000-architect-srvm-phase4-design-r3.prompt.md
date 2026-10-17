Created-GMT: 2026-10-10 13:55:00 GMT
Created-Local: 2026-10-10 20:55:00 Asia/Bangkok

# Task: Revision 3 -- two trace-oracle defects and one editorial fix

Role: Lead System Architect (continuing session 07b93752-3d80-4d06-b564-07394c529fed)

Same worktree `/Users/sto/workspace/datomworld-srvm-p4-design`, branch
`srvm-phase4-design`. Docs only. Edit ONLY
`docs/design/yin.vm.semantic-register-vm.evaluator.md` plus your report.
Do NOT edit the frozen design (its amendments are applied and confirmed
by the reviewer). Do not `git add`/`git commit`.

The codex confirmation round
`collab/1791540000000-architect-srvm-phase4-design-review-r2.gpt-6.1-sol.findings.md`
(read it) confirms that your eight revisions are fixed, the recounts are
right (26 B0 rows, 32 register-corpus rows, 25 in 4a, six events for the
lambda, register numbers), and that my application of F1-F7 to the frozen
design matches the rulings. It leaves exactly three defects, all in §4:

1. **`:none` is both a value and the sentinel (Medium, around line 760).**
   `extract` returns `:none` for "no event", but `:none` is also a legal
   Yin result value, so a literal `:none` result loses its event.
   Replace the sentinel with a tagged result, e.g. `{:event? true :value
   v}` / `{:event? false}`, everywhere `extract` is specified and used
   (both machines' sides, the comparison, the diagnostics). Add a
   literal-`:none` case to the trace corpus rows (name it, give the
   program and the expected single event).
2. **Parking is classified by result shape (Medium, around line 734).** A
   legal literal map shaped like a parked continuation would be recorded
   as `[:park ...]`, and the register algorithm similarly misclassifies
   its eventual halt. Detect an actual park from the EXECUTED walker node
   or register instruction (the `:park` node / `:park` instruction
   that just ran), never from the returned value. Add a parked-shaped
   literal-map case to the corpus rows (name it, program, expected
   events). Check that 4b's park/resume acceptance rows and the trace's
   treatment of blocked programs remain consistent with this.
3. **Stale quotation (Low, around line 623).** §4's introduction quotes
   the superseded "value-producing steps with register writes" wording of
   the frozen §2.5. Refer to the amended production/delivery
   correspondence (the frozen §2.5 now reads as the amendment you proposed
   as F5). Historical quotations that §6 explicitly identifies as
   replaced text may stay.

After fixing, re-grep the whole evaluator doc for any other place that
(a) uses `:none` as an absent-event sentinel, (b) classifies park or
halt from a value's shape, or (c) quotes the old wording. Fix all of them
and list them in the report.

## Report

Write `collab/1791540000000-architect-srvm-phase4-design-r3.report.md`.
Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then a table: each defect -> every section/line you changed -> the new
corpus rows added (names, programs, expected events). State anything you
disagreed with and why, and anything you could not ground. Do not claim
edits that did not occur.
