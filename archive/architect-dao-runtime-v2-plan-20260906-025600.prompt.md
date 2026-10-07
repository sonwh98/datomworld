Created-GMT: 2026-09-06 02:56:00 GMT
Created-Local: 2026-09-06 09:56:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: pending (provider-generated)
# Task: Draft implementation plan for dao.runtime
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-06 09:56:00 Asia/Ho_Chi_Minh | Status: active | Rationale: Architect required to design the v2 migration for dao.runtime.

The `dao.stream`, `yin.repl`, and `yin.vm` slice is complete. The ultimate goal is to deprecate and remove `dao.stream` (v1). However, `dao.runtime` still heavily depends on v1 APIs.
We notice that `src/cljc/dao/runtime.cljc` already exists and seems to implement a cooperative scheduler over DaoStream v2, but there is no `docs/design/dao.runtime.implementation-plan.md` that dictates the remaining migration path (e.g. migrating `dao.runtime.driver` or other consumers, ensuring feature parity, handling divergence, and the exact phases required to replace `dao.runtime` entirely).

Your task:
Draft `docs/design/dao.runtime.implementation-plan.md`. 
The plan must follow the format of our other v2 migration plans (e.g., `dao.stream.implementation-plan.md`, `yin.vm.implementation-plan.md`).

It should include:
1. The problem (why `dao.runtime` needs v2 migration, removing `open!`, `drain-one!`, etc.)
2. The architectural contract for `dao.runtime` (how it differs from v1).
3. Divergence register (what v1 behaviors are intentionally dropped or changed, e.g., removal of waiters and destructive take).
4. The concrete execution phases (what remains to be built/tested to finalize the slice and deprecate v1 `dao.runtime`).

Begin your response exactly with:
Completed-GMT: <actual timestamp GMT>
Completed-Local: <actual timestamp Asia/Ho_Chi_Minh>
Coding-Agent: claude
Session-ID: <exact initial Session-ID value>

Then briefly summarize the resulting plan.
