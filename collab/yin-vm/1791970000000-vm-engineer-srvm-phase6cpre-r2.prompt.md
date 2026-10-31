Created-GMT: 2026-10-11 09:20:00 GMT
Created-Local: 2026-10-11 16:20:00 Asia/Bangkok

# Task: 6c-pre round 2 -- close the reviewer's test gaps (continuing your thread)

Worktree `/Users/sto/workspace/datomworld-srvm-p6cpre`, branch `srvm-phase6cpre`, HEAD 7f1cf08e. Do NOT `git add`/commit. TEST-ONLY: `src/cljc/yin/vm/debruijn/stack.cljc` must stay byte-identical (verify with `git diff --quiet HEAD -- src/`). Read the review `collab/1791930000000-reviewer-srvm-phase6cpre.claude-fable-5-1.findings.md`. Take a verified byte backup of `stack.cljc` into `collab/` before any mutant; restore from it.

ORCHESTRATOR RULING on F1 (the v2 handoff stack lane drops `:resume-mode`): accepted as-is. The v2 carrier is the old carrier, frozen until the canonical carrier of phase 6 supersedes it and phase 8 deletes it; after a v2 handoff a stack tail park resumes by the old path, exactly as on master today (no regression). Do NOT edit `handoff.cljc`. Record this ruling in your report as a known limitation.

Fix in `test/yin/vm/debruijn/stack_tail_completion_test.cljc`:
1. F2: add a `stack-restore` case with `:continuation []`, a NON-nil `:store-of` on the entry (as `frame-entry` already does) and a non-nil delivered value (7): assert `:stack [7]`, `(vm/value)` 7, `:halted? true`, `:store-of nil`; keep the nil-delivery case too. Apply the reviewer's mutants M08 (halt value taken from `(peek (:stack vm))`), N04 (empty-K halt delivers constant nil), M09 (empty-K halt keeps `:store-of`), N05 (keeps the entry's store-of): confirm each is KILLED by a named test.
2. F3: give the inner frame in `frame-entry` a `:return-pc` different from the parked pc 4 (e.g. 2) so `(= 4 (:pc done))`-style assertions can fail; apply N08 (pc left at the entry pc) and confirm `return-pops-only-inner-frame` (the test that names the obligation) kills it.
3. F4: no change (pre-existing line); F5: correct the report's mutant claim to what was actually run.

Verify, foreground, literal `-n` flags: JVM for the six suites you ran plus the new test namespace; Node for the new namespace; kondo and cljstyle. Dart: orchestrator runs it.

Report to `collab/1791970000000-vm-engineer-srvm-phase6cpre-r2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: change, mutant killed by which test, exact lane counts. Do not claim runs that did not occur.
