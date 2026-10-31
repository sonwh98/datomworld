Created-GMT: 2026-10-16 22:00:00 GMT
Created-Local: 2026-10-17 05:00:00 +07 (+0700)
Coding-Agent: claude
Session-ID: <to-fill>

# Task: Scanner-golden regeneration — the deferred [:c4 :py]/[:c4 :t] regen

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 05:00:00 +07 (+0700) | Status: active

WORK TREE: /Users/sto/workspace/datomworld-scangold (branch scangold, based on origin/master). Do not
touch other worktrees. Do not stage or commit.

CONTEXT: the known-gaps ledger defers one regeneration — the C4 scanner goldens ([:c4 :py]/[:c4 :t]) in
test/yin/vm/semantic/scanner_goldens.cljc stale since I4-a, moved again by I5/I6. The rule: regenerate via
a one-off script printing the new answers — never hand-edit a pin — then Architect review before landing
(fable via claude-cloud is the designated reviewer per the reserved channel; the orchestrator dispatches
that review after your round).

TASK:
1. Write a one-off script (checked into the worktree, named like the existing generators, e.g.
   scanner-golden-c4.generate.clj) that prints the new answers for the deferred groups from the live
   scanner run.
2. Update the golden constants in the scan test from the script's output (this is the sanctioned path —
   the values come from the script, not by hand).
3. Rerun the scan test: all 8 former failures green, the REST of scan-test unchanged and green (the
   non-C4 golden groups must not move — if any other group moves, STOP and report: that would be a
   behavior change, not a pin move).
4. kondo + cljstyle on touched files.
5. Do not run Architect review — the orchestrator dispatches it after your round.

LANES: foreground, one at a time, waiting; no background runs; complete the turn. mise is trusted; run
gen/build if builds are stale.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the old -> new values per group, the script path, exact counts, and confirmation that only the
two groups moved.
