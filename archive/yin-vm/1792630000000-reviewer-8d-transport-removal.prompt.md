Created-GMT: 2026-10-14 08:25:00 GMT
Created-Local: 2026-10-14 15:25:00 +07
Coding-Agent: claude
Session-ID: 43fb870b-1216-4a82-a6e4-e7e4ab97f1f2

# Task: phase-8d old-transport-removal review

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-14 15:25:00 +07 | Status: active | Rationale: codex (gpt-6.1-sol, different family) authored; owner relay reserves fable for review only

Work in /Users/sto/workspace/datomworld-srvm-p8d (branch srvm-phase8d, commit 249f8799).
The prompt that produced this change and the implementer's report are at these
ABSOLUTE paths (your worktree has no collab/ of its own; do not write there):
- /Users/sto/workspace/datomworld/collab/1792620000000-engineer-8d.prompt.md
- /Users/sto/workspace/datomworld/collab/1792620000000-engineer-8d.report.md

Perform a read-only review of commit 249f8799 (31 files, +330/-8399;
`git diff 67b98907..249f8799`) against:
- /Users/sto/workspace/datomworld/collab/1792560000000-architect-phase8-plan.report.md (§1.1, §1.3 test-support hazard, §1.5)
- the unitarity invariant in docs/design/datom.world.md
Inspect the changed tests, the consolidated `rows_support.cljc`, the ported C4
test, and the three new `rows_test` deftests. Check correctness, invariant
preservation, portability, regressions, and missing tests. Treat the
implementer's report (including its 141-deftest coverage audit) as untrusted:
spot-check at least six audit mappings yourself (two from handoff_v1_test, two
from lift_v1_test, two from elsewhere) and cite repository evidence for every
finding. Do not edit any file.

Pay particular attention:
1. No production behavioral change: `git diff` touches no file under
   `src/cljc/` — confirm, and confirm `rows-checkpoint-v3.txt`,
   `ledger-v2.txt`, corpus, layout, and harness address literals are
   byte-identical to HEAD (no hash reminted).
2. The borrowed helpers moved into `rows_support.cljc` (stream/ring
   construction, `new-machine`, program fixtures) are complete: frame, layout,
   harness, compose-rows, and fencing-rows tests require nothing deleted.
   Confirm no duplicate requires and no leftover references to the deleted
   `lift_support` / `v2_support` / `checkpoint_fixtures` anywhere.
3. The three new tests genuinely cover what they replace: header refusal
   (`:yin.k/profile-mismatch :version` — confirm this is the code's ACTUAL
   answer and the brief's `:yin.k/undecodable` wording was the plan defect the
   report claims, not a behavior change), numeric scalar code/value round-trip,
   result-only census. Confirm each fails without its production path (the
   report's mutant table is a claim — reason about it, and confirm the two
   supplemental tests were needed, not coverage theater).
4. The C4 caveat: `int-heap-test/cell-refusal-test` still calls
   `handoff/export-task`, so `handoff.cljc` + `checkpoint.cljc` are retained.
   Confirm (a) the retention is truly forced (no trivial port exists within the
   freeze rules), (b) NOTHING else still requires the retained pair, and
   (c) both files are byte-identical to HEAD.
5. `int-contract-test` port: confirm the single halted numeric result now uses
   the carrier APIs the holder uses, with integer composition,
   no-carried-limits, and wide/small results still asserted — and that no other
   C4 file changed.
6. Deftest inventory: 4599 -> 4461 = -141 + 3. Confirm no SURVIVING deftest
   outside the 16 retired files was removed or weakened (the plan's 4574 is
   stale; use the branch's own count).
7. No compatibility namespace, no added version dispatch, no widened backend
   fragment, no revived legacy dispatch — confirm all four negatives.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <the exact Session-ID value from this brief>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
Blocking = in-scope P0/P1, or a P2 in claimed acceptance or newly introduced
behavior. State "No actionable findings" when appropriate, and state explicitly
whether the slice is ready to merge into the srvm-phase8 integration branch.
Write no files; your stdout is captured by the orchestrator.
