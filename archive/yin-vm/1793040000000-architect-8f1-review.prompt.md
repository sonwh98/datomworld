Created-GMT: 2026-10-15 09:25:00 GMT
Created-Local: 2026-10-15 16:25:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
# Task: 8f-1 review of the uncommitted diff (sign-off or changes), incl. three deviations to confirm
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-15 16:25 +07 | Status: active | Rationale: sign-off gate before commit; reviewer family differs from codex

cwd = worktree datomworld-srvm-p8f1k (branch srvm-phase8f1 @ d43afbe3, uncommitted diff). Read ./collab/1792051967324-engineer-8f1c.report.md, ./collab/1792051089473-engineer-8f1b.report.md and your ruling. Review the diff against your ruling steps 0-5. CONFIRM OR OVERRULE these deviations: (D1) vm/opcode-table and the opcase macro NOT deleted in 8f-1, deferred to 8f-3 with semantic.cljc (orchestrator-authorised: ruling omitted that semantic.cljc reads them); (D2) query_test/segment-db kept because a third caller `code-joins-store-put-key-to-ast-by-value` needs it (with yin.vm.code/linearize requires): is that the right call or must that join test be ported/dropped now, and what is the old-code dependency in it; (D3) the completion_test count was 18 deftests not 19 (deftest count 4440 -> 4418, delta -22): verify nothing else vanished (diff the deftest-name sets before/after). Also verify: the three pinned descriptor/golden literals byte-identical; the literal 23-mnemonic table equals what the derivation produced (the pins prove it: say if you see any gap); scope (no src/yang, no docs, no C4 file beyond int_heap_test); the removed v3 facts live where you said. Report ./collab/<ms>-architect-8f1-review.report.md with Completed-GMT/Completed-Local/Coding-Agent/Session-ID, findings P1/P2/P3, D1-D3 verdicts, ending SIGN-OFF or CHANGES-REQUIRED. Read-only except the report.
