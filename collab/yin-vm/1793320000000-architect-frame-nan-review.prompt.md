Created-GMT: 2026-10-16 03:00:00 GMT
Created-Local: 2026-10-16 10:00:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
# Task: review of the uncommitted frame.cljc / rows.cljc NaN follow-up (sign-off or changes)
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-16 10:00 +07 | Status: active | Rationale: sign-off gate before commit

cwd = worktree datomworld-srvm-p8frame (branch srvm-frame-nan @ srvm-phase8 6dceede9, uncommitted, 5 files +71/-5). Read ./collab/*engineer-frame-nan.report.md and your ruling ./collab/1792057828000-architect-8f3-nan-ruling.report.md section 5. Judge: (1) frame.cljc bindings= : correct for NaN (content=), signed zero, callables/closures/continuations/refs/cursor cells guards matching data-content=; key-set comparison correct; no change of behaviour for non-NaN values (could any value that compared = before now compare unequal, or the reverse: e.g. 1 vs 1.0, collections of different kinds, metadata, records)? (2) the SECOND site codex changed in src/cljc/yin/vm/ucf/rows.cljc decode* row-order check (was host = on whole ordered slots): is that really the same bug, is cbor/content= on corresponding slots equivalent to the old check for all non-NaN inputs (kind-strictness: content= distinguishes 1 and 1.0 where = may not; does any decoded row now refuse or accept differently?), and did codex miss another site; (3) the tests are real (21 pass / 9 fail under the host-= mutant; the rows_test case with cursor references to satisfy the reference-free-row rule: is that test still testing the NaN path); (4) known-gaps row closure accurate; (5) scope: no docs/design, no test/yang. State what Node and Dart lanes should show (the orchestrator runs them after you sign). Report ./collab/<ms>-architect-frame-nan-review.report.md with Completed-GMT/Completed-Local/Coding-Agent/Session-ID, P1/P2/P3, ending SIGN-OFF or CHANGES-REQUIRED. Read-only except the report.
