Created-GMT: 2026-10-11 10:55:00 GMT
Created-Local: 2026-10-11 17:55:00 Asia/Bangkok

# Task: independent review of 6a round 2 (a different family from the author, claude-opus-5-5)

Read-only. Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD bb4a6558 (round 2 over 4b0e735e; `git diff 4b0e735e HEAD`). Do not edit files; no git add/commit. You may run `clojure -M:test` lanes (write each `-n ns` literally, foreground) and temporary mutants only on a verified byte backup restored byte for byte.

Context: round 1 (codex) was reviewed adversarially by Fable (`collab/1791940000000-reviewer-srvm-phase6a.claude-fable-5-1.findings.md`: 44 mutants, 15 survived, plus findings 1-13). Round 2 was done by claude-opus-5-5 (report `collab/1791980000000-vm-engineer-srvm-phase6a-r2.report.md`, brief with the orchestrator rulings `collab/1791980000000-vm-engineer-srvm-phase6a-r2.prompt.md`). Orchestrator verified JVM: frame-test + rows-test 26 tests / 522 assertions, 0 failures; kondo 0/0. Dart not yet run.

Check:
1. Each of Fable's 15 surviving mutants (R06 R07 R10 R12 R17 R21 R24 R25 R26 R29 R30 R33 R35 R45 R46) and the findings 1-13 with the orchestrator rulings: really addressed? Re-apply at least ten of the survivors yourself and confirm each is KILLED by a named test; judge the author's claim that none is equivalent.
2. The source changes in `frame.cljc` and `rows.cljc` (census key validation, installs guard, marker-collision refusal as `:yin.k/non-portable`, nil `store-of` refusal, census numeric order, budget pass-through): correct, minimal, with no new defect; any change that alters canonical bytes or the design's bijection?
3. The vector multi-key assoc fix (cljd) and any other multi-key assoc on a vector or nil in the changed tests.
4. The new OOM finding (the draft section 6 seven-cell fixture exhausts the heap because budgets count calls, not label bytes): is the author's characterization right, and is the three-cell replacement fixture a legitimate pin of the same phenomenon? State whether this needs a design amendment (charging label bytes) before 6b.
5. Pinned root ids: recompute the two probes from Fable's finding 5 and the new pins.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-5, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
