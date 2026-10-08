Created-GMT: 2026-09-24 13:20:35 GMT
Created-Local: 2026-09-24 20:20:35 +0700
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9

# Task: Reconcile Reviewer Round 7 Findings on yin.vm.linker.md (r8)

Role: Lead System Architect

Implementers:
- Status-Event: 2026-09-24 18:45:40 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Initial draft of yin.vm.linker.md (r1)
- Status-Event: 2026-09-24 19:01:33 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r2
- Status-Event: 2026-09-24 19:21:24 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r3
- Status-Event: 2026-09-24 19:29:36 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r4
- Status-Event: 2026-09-24 19:36:42 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r5
- Status-Event: 2026-09-24 20:03:29 +0700 | Model: glm-5.3 | Status: completed | Rationale: Reconciled to r6
- Status-Event: 2026-09-24 20:16:19 +0700 | Model: glm-5.3 | Status: completed | Rationale: Reconciled to r7
- Model: glm-5.3 | Assigned: 2026-09-24 20:20:35 +0700 | Status: active | Rationale: Resuming session a2749e73-2e44-4716-8a1f-6ad0cc1d82d9 for Revision r8

Resume session a2749e73-2e44-4716-8a1f-6ad0cc1d82d9 in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `docs/design/yin.vm.linker.md` (in `/Users/sto/workspace/datomworld-universal-linker`)
- `collab/1790255808829-reviewer-universal-linker-consensus-r7.gpt-6-sol.findings.md`

In Round 7, the reviewer (`gpt-6-sol`, session `01a0d340-f8e7-7e30-9b74-c0a0e6b636fb`) closed Grow-Only Returns, but identified two specific security and runtime lifecycle items:

1. [PARTIAL — Module-Store Isolation & Private Engine Resources - L1316, L1344, engine.cljc:76, engine.cljc:162]:
   - The `:store-get` fallback to ambient store exposes host resources (such as `stream-0`) because resource keys minted from counters are predictable. A linked module could forge a key and read ambient handles.
   - Separate engine resources into a private engine table inaccessible to user language store instructions; eliminate all `:store-get` fallback to ambient store (fail-closed isolation).
   - Specify clearing the active module-store context when a tail call completes with no remaining caller frame, before the VM admits any new top-level REPL input.

2. [PARTIAL — AST Walker Lambda Node-to-Row Annotation - L1266, vm.cljc:1263, ast_walker.cljc:381]:
   - Clarify the timing of lambda ID recording: the row decoder builds AST nodes, while runtime closures are instantiated during `:lambda` evaluation.
   - Specify an annotation or side-index mapping the decoded lambda AST node to its source `:lambda` row ID. The runtime transition (`case type :lambda ...`) copies this row ID into the runtime closure record, enabling the lift pass to verify it against the row grammar.

Update `docs/design/yin.vm.linker.md` to **Revision r8** addressing both items.
Update Section 13.1 with the Revision r8 reconciliation record.

Ensure the document strictly maintains:
- Pure ASCII (no non-ASCII characters).
- All lines strictly <= 80 columns.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9
