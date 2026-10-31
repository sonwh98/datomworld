Created-GMT: 2026-10-16 18:20:00 GMT
Created-Local: 2026-10-17 01:20:00 +07 (+0700)
Coding-Agent: glm
Session-ID: 9c127601-3a5d-40d2-bc27-0ab79ea94c00

# Task: Compute the deferred scanner-golden regeneration for [:c4 :py] and [:c4 :t]

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-10-17 01:20:00 +07 (+0700) | Status: active | Rationale: mechanical pin regeneration with provenance; you have the pins-round context from the sibling worktree

WORK TREE: /Users/sto/workspace/datomworld-scangold (branch yang-python-scangold = c4-i6-r4 rebased onto
aa8dad89: I5 + I6 + the regenerated address pins + the re-minted pysp golden). Do not touch other worktrees
or the main tree. Do not stage or commit.

CONTEXT: the ledger row (docs/agents/known-gaps.md, "C4 scanner pins [:c4 :py] / [:c4 :t] ... stale since
I4-a") defers ONE regeneration: the scanner goldens in the yin.vm.semantic scan test fail (8 assertions in 3
tests on the JVM; scan-test bodies are slow-tagged) because the C4 modules' answers moved under
I4-a/I5/I6. The standing rule: regenerate via a one-off script printing the new answers — NEVER hand-edit a
pin — then Architect review before landing.

TASK:
1. Find the failing golden groups: run DATOM_SLOW_TESTS=1 mise exec -- clojure -M:test -i :slow -n
   yin.vm.semantic.scan-test and capture the 8 failures' exact expected/actual pairs (they should be
   confined to [:c4 :py] and [:c4 :t]; confirm nothing else is red).
2. Produce the new answers mechanically: write a one-off script (checked into the worktree under
   test/resources/ or scripts/ alongside the existing generators, named like the others, e.g.
   scanner-golden-c4.generate.clj) that prints the new-answer AST for those groups from the live scanner
   run — the same provenance discipline as dht-import-v1.generate.clj. Run it, capture the output.
3. Update the golden constants in the scan test from the script's output (this is the sanctioned path — the
   values come from the script, not by hand).
4. Rerun the scan test: all 8 former failures green, the REST of scan-test unchanged and green (the
   non-C4 golden groups must not move — if any other group moves, STOP and report: that would be a
   behavior change, not a pin move).
5. Run the shiva-side sanity is NOT needed; the JVM run here is the evidence. kondo + cljstyle on touched
   files.
6. Report: the old -> new values per group, the script path, exact counts, and confirmation that only the
   two groups moved.

Do not run the full bb test:clj (the orchestrator runs the gates). The scan-test bodies are slow-tagged;
the focused invocation above runs them. Complete the turn; no background runs.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
