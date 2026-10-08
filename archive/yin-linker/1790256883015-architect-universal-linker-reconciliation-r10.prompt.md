Created-GMT: 2026-09-24 13:34:45 GMT
Created-Local: 2026-09-24 20:34:45 +0700
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9

# Task: Reconcile Reviewer Round 9 Findings on yin.vm.linker.md (r10)

Role: Lead System Architect

Implementers:
- Status-Event: 2026-09-24 18:45:40 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Initial draft of yin.vm.linker.md (r1)
- Status-Event: 2026-09-24 19:01:33 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r2
- Status-Event: 2026-09-24 19:21:24 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r3
- Status-Event: 2026-09-24 19:29:36 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r4
- Status-Event: 2026-09-24 19:36:42 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r5
- Status-Event: 2026-09-24 20:03:29 +0700 | Model: glm-5.3 | Status: completed | Rationale: Reconciled to r6
- Status-Event: 2026-09-24 20:16:19 +0700 | Model: glm-5.3 | Status: completed | Rationale: Reconciled to r7
- Status-Event: 2026-09-24 20:24:32 +0700 | Model: glm-5.3 | Status: completed | Rationale: Reconciled to r8
- Status-Event: 2026-09-24 20:31:32 +0700 | Model: glm-5.3 | Status: completed | Rationale: Reconciled to r9
- Model: glm-5.3 | Assigned: 2026-09-24 20:34:45 +0700 | Status: active | Rationale: Resuming session a2749e73-2e44-4716-8a1f-6ad0cc1d82d9 for Revision r10

Resume session a2749e73-2e44-4716-8a1f-6ad0cc1d82d9 in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `docs/design/yin.vm.linker.md` (in `/Users/sto/workspace/datomworld-universal-linker`)
- `collab/1790256720621-reviewer-universal-linker-consensus-r9.gpt-6-sol.findings.md`

In Round 9, the reviewer (`gpt-6-sol`, session `01a0d340-f8e7-7e30-9b74-c0a0e6b636fb`) closed the r8 resource-lowering finding, but identified one P1 and one P2 item:

1. [P1 — Reference Forgery at Effect Boundaries]:
   - Today, program references are plain maps with predictable IDs (`{:type :stream-ref, :id :stream-0}` or `:cursor-ref`). The engine/AST walker accepts literal maps directly at effect boundaries.
   - If resource lookup merely checks `:resources [key]`, a program or linked module can forge a map literal `{:type :stream-ref, :id :stream-0}` or `{:id :yin/call-out}` and access arbitrary resources or FFI channels.
   - Specify capability-sealed or task-scoped token validation at every effect boundary:
     * Resource handles/cells in `:resources` are bound to a task-scoped unguessable capability token (or ephemeral instance secret).
     * Validating references at every effect dispatch requires matching the active task's capability token; unsealed/fabricated literals fail closed with `:forged-resource-reference`.
     * Define lift and lower remapping for capability-sealed references.
     * Add tests in Criterion 23 and M4 checklist covering forged stream and cursor references, including FFI resource IDs.

2. [P2 — Migration Document Citations]:
   - In Criterion 12, fix citation: "UCF 4.1" does not exist in `yin.vm.universal-continuation-format.md`. Use the correct section title/number.
   - Ensure the M4 file box in Section 10 explicitly lists all UCF amendments, specifically including Section 7.6.2 alongside 7.5.1 and 7.5.3.

Update `docs/design/yin.vm.linker.md` to **Revision r10** addressing both items.
Update Section 13.1 with the Revision r10 reconciliation record.

Ensure the document strictly maintains:
- Pure ASCII (no non-ASCII characters).
- All lines strictly <= 80 columns.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9
