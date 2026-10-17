Created-GMT: 2026-10-15 07:00:00 GMT
Created-Local: 2026-10-15 14:00:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
# Task: 8e Architect ruling on the NaN row-collision stop, plus review of the uncommitted 8e diff
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-15 14:00 +07 | Status: active | Rationale: design question reserved for the Architect; different family from the codex author

cwd is the 8e worktree (branch srvm-phase8e, uncommitted diff of codex). Read ./collab/1792047012394-engineer-8e.report.md. 
PART 1 (ruling, decide, do not survey): codex stopped because `sr-linearize/project ast` throws `Semantic bytecode address collision` for ASTs with two separately boxed NaN literals; src/cljc/yin/vm.cljc ~1214 compares prior/current rows with host `=` and same-meta?, and NaN is not = NaN. The Python C4 tests (int_ops_test nan-comparisons, float-division-edge) hit it on the native backends. Pick: (a) fix the collision comparison to a bit/identity-aware scalar comparison (say exactly how, and whether the address of the NaN row is then stable across hosts and which NaN payload canonicalisation applies), or (b) another route; justify against the invariants (A is the only code identity; tree always available). Write the exact fix spec codex must implement, including tests.
PART 2: review the uncommitted diff (git diff, git status) for correctness, lost coverage (the 84-row ledger in the report), scope (allowed: ten C4 files only; no docs, no src/yang, no semantic/linearize/code.cljc edits), and the dropped facts (source=resolved-tuples, lift=to-named-semantics). List P1/P2/P3 findings.
Report ./collab/<ms>-architect-8e-ruling.report.md starting with Completed-GMT/Completed-Local/Coding-Agent/Session-ID, ending SIGN-OFF, CHANGES-REQUIRED or BLOCKED. Read-only: do not edit files except the report.
