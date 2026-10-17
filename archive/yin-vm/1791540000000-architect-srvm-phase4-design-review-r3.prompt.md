Created-GMT: 2026-10-10 14:10:00 GMT
Created-Local: 2026-10-10 21:10:00 Asia/Bangkok

# Task: Round 3 -- confirm the three trace-oracle corrections

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

One new commit on `srvm-phase4-design` in
`/Users/sto/workspace/datomworld-srvm-p4-design`, on top of accbc132 (which
you confirmed). Run `git log --oneline -1` and `git diff accbc132 HEAD`.
Fable's report: `collab/1791540000000-architect-srvm-phase4-design-r3.report.md`.
The frozen design is unchanged in this commit; verify that.

Your three round-2 findings: (1) `:none` was both a value and the
no-event sentinel; (2) park was classified from result shape; (3) §4
quoted superseded wording. Check:

1. The tagged result `{:event? true :value v}` / `{:event? false}` is used
   consistently in EVERY place `extract` is specified or consumed (the
   register loop, the extraction table cells, the comparison, the
   diagnostics, §4.4), with no residual sentinel use of `:none`.
2. Park is recognised from the executed walker node (`:vm/park` as the
   pre-state `:control`, `ast_walker.cljc:565-567`) and the executed
   register instruction, never from a returned value; the halt after a
   park is classified correctly on the register side; 4b's park/resume
   rows and the treatment of blocked programs remain consistent.
3. The four new trace rows (`:literal-none`, `:parked-shaped-literal`,
   `:park-then-halt`, `:literal-none-in-call`) are well-formed and their
   expected events are RIGHT when derived independently from the walker
   and from the register machine's transitions. Pay particular attention
   to `:literal-none-in-call` (the closure event, the operand event, the
   body result, and the `:return` relocation) and to `:park-then-halt`'s
   `:parked-0` (the report says it assumes a fresh `create-vm` with no
   earlier gensym; `engine/park-id` at `engine.cljc:2273-2277`): is that
   assumption stated where an implementer will see it?
4. The stale quotation in §4 and §4.5 item 5 is fixed; the two remaining
   old-wording occurrences in §6 F5 are quoted replaced-text blocks and
   may stay.
5. Anything still blocking the phase-4a implementation brief.

Do not edit files. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then findings (severity | file:line | evidence | correction), answers to
items 1-5, and end with one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
