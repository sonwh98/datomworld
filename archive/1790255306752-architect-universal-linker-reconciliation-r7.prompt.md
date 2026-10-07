Created-GMT: 2026-09-24 13:08:30 GMT
Created-Local: 2026-09-24 20:08:30 +0700
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9

# Task: Reconcile Reviewer Round 6 Findings on yin.vm.linker.md (r7)

Role: Lead System Architect

Implementers:
- Status-Event: 2026-09-24 18:45:40 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Initial draft of yin.vm.linker.md (r1)
- Status-Event: 2026-09-24 19:01:33 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r2
- Status-Event: 2026-09-24 19:21:24 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r3
- Status-Event: 2026-09-24 19:29:36 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r4
- Status-Event: 2026-09-24 19:36:42 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r5
- Status-Event: 2026-09-24 20:03:29 +0700 | Model: glm-5.3 | Status: completed | Rationale: Reconciled to r6
- Model: glm-5.3 | Assigned: 2026-09-24 20:08:30 +0700 | Status: active | Rationale: Resuming session a2749e73-2e44-4716-8a1f-6ad0cc1d82d9 for Revision r7

Resume session a2749e73-2e44-4716-8a1f-6ad0cc1d82d9 in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `docs/design/yin.vm.linker.md` (in `/Users/sto/workspace/datomworld-universal-linker`)
- `collab/1790255038476-reviewer-universal-linker-consensus-r6.gpt-6-sol.findings.md`

In Round 6, the independent reviewer (`gpt-6-sol`, session `01a0d340-f8e7-7e30-9b74-c0a0e6b636fb`) closed Dominance & Scanners, but raised 2 PARTIAL issues and 1 relocation edge case on Revision r6:

1. [PARTIAL — Register Return Frames & Nested Image Attachments - L1128, L1152, register.cljc:184]:
   - While parked task restore was made hash-safe, ordinary register function return frames (`:return`) still restore a saved `:segment` and `:hash` from the stack.
   - If a nested `require` attaches an image during a function call, returning from that call will overwrite the VM `:segment` with the old pre-attachment slice, discarding newly attached code.
   - Amend register/stack return transitions and frame formats to be grow-only/offset-table aware: return transitions must rebase or preserve newly attached segments rather than blindly restoring stale slices. Add test for attach-during-call followed by function return.

2. [PARTIAL — Module Store Routing & Transitive Stores - L1274, L1295, L1817, stack.cljc:477]:
   - Specifying that free reads/writes use the module store is incomplete without amending direct store instructions: stack, register, and semantic `:store-put` currently write directly to ambient `:store`.
   - Specify module-store routing for direct store instructions, and thread/save/restore the active store context across function calls.
   - For transitive re-exports: if a child re-exports a closure from a dependency, that closure carries `:store-of` for the dependency. The portable slice must include transitively referenced module-store snapshots (including mutations made in the child), not just the immediate child's store, so receiving tasks can instantiate the full closure environment.

3. [Additional Relocation Edge — Walker Lambda Disambiguation - L1243, vm.cljc:1189]:
   - In AST walker closure lifting, a shared, content-addressed body node can belong to multiple distinct `:lambda` rows with different parameters.
   - The lookup cannot be `node -> row id` alone; disambiguate using the pair `[node params]` or record the source lambda ID directly to accurately locate the exact `:lambda` row.

Update `docs/design/yin.vm.linker.md` to **Revision r7** addressing each point.
Update Section 13.1 with the Revision r7 reconciliation record.

Ensure the document strictly maintains:
- Pure ASCII (no non-ASCII characters).
- All lines strictly <= 80 columns.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9
