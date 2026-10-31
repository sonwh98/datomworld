Created-GMT: 2026-10-16 15:00:00 GMT
Created-Local: 2026-10-16 22:00:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
# Task: review the uncommitted G2 diff (retained-FFI pre-attach refusal): sign-off or changes
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-16 22:00 +07 | Status: active | Rationale: sign-off gate before commit; reviewer family differs from codex author

cwd = worktree datomworld-srvm-retained-ffi (branch srvm-retained-ffi @ origin/master 6b001f87, uncommitted, 5 files +72/-6). Read ./collab/*engineer-retained-ffi.report.md and your gap list row G2/section 1.3 in ./collab/1793430000000-architect-gap-list.report.md. Verify: (1) the predicate refuses EXACTLY a retained FFI request (a :put wait with :request-sent true, becoming :ffi-request in rows/pending-node) and nothing that legitimately lowers: check the positive control is the right counter-example (an ordinary :ffi response pending), and that no other lowering test or harness row that lowers into a backend is now refused (law 4/5 counts unchanged: quick 16/92); (2) the refusal vocabulary matches lower-defect's siblings and lower-task throws before any attach; (3) the semantic kernel receiver (lowering INTO the semantic kernel, not a backend) is NOT refused: is lower-defect applied per receiver kind? if the gate is receiver-independent, say whether the semantic kernel honouring a carried pair makes that a regression; (4) the docs sentences (continuation 4.6 limit 5, UCF 7.11) are accurate and minimal; (5) the harness change keeps the table meaning and adds the exact refusal assertion; (6) Node/Dart expectations (what the lanes should show). Report ./collab/<ms>-architect-retained-ffi-review.report.md with Completed-GMT/Completed-Local/Coding-Agent/Session-ID, P1/P2/P3, ending SIGN-OFF or CHANGES-REQUIRED. Read-only except the report.
