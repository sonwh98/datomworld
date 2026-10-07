Created-GMT: 2026-09-24 12:34:00 GMT
Created-Local: 2026-09-24 19:34:00 +0700
Coding-Agent: claude
Session-ID: f9328487-72e9-44e8-a216-0fbb81bf6a2e

# Task: Reconcile Reviewer Round 4 Findings on yin.vm.linker.md (r5)

Role: Lead System Architect

Implementers:
- Status-Event: 2026-09-24 18:45:40 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Initial draft of yin.vm.linker.md (r1)
- Status-Event: 2026-09-24 19:01:33 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r2
- Status-Event: 2026-09-24 19:21:24 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r3
- Status-Event: 2026-09-24 19:29:36 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r4
- Model: claude-fable-5-1 | Assigned: 2026-09-24 19:34:00 +0700 | Status: active | Rationale: Resuming session f9328487-72e9-44e8-a216-0fbb81bf6a2e to address remaining 4 PARTIAL items from gpt-6-sol Round 4 review

Resume session f9328487-72e9-44e8-a216-0fbb81bf6a2e in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `collab/1790253003747-reviewer-universal-linker-consensus-r4.gpt-6-sol.findings.md`
- `docs/design/yin.vm.linker.md` (in `/Users/sto/workspace/datomworld-universal-linker`)

The independent reviewer (`gpt-6-sol`, session `01a0d340-f8e7-7e30-9b74-c0a0e6b636fb`) evaluated r4. Item 5 (per-format execution contracts) is now CLOSED. Four specific items remain PARTIAL:

1. [Control-Flow Dominance / Conservative Retention & Position Scanners - L257, L390]:
   - Source order alone cannot guarantee a definition executed (e.g. definitions inside conditional branches like `:if`).
   - Specify that where definition execution is conditional or uncertain under control-flow dominance, retain the dependency obligation conservatively.
   - Format record scanner signatures: specify position-bearing scanner functions or conservative retention over the existing symbol set from `yin.vm/free-names`.

2. [Wait-entry scalar ID typo - L754, L890]:
   - The `:link-response` wait-entry example still uses scalar `7`. Update it to `[:t0 7]`.

3. [Non-Destructive Image Attachment & Walker Closure Mapping - L1015, L1057]:
   - Ordinary VM loaders reset VM registers/instruction pointers. Define non-destructive image attachment (`attach-image` / `:code-segment` expansion) that preserves a parked task's program counter, stack, and frame restore identities.
   - For AST-walker closures: runtime closures hold an AST `:body` (not segment/entry). Define the exact walker-body lift/lower rule for AST-walker closures.

4. [Authority Replay vs Equivocation Deduplication - L1263, L1291]:
   - Discarding *both* events on equal sequence numbers creates a denial-of-service vector where replaying a valid message suppresses it as equivocation.
   - First deduplicate identical messages by content ID (exact duplicate is a harmless replay and ignored/accepted once); only treat distinct messages with equal sequence numbers from the same principal as equivocation.

Ensure document maintains:
- Pure ASCII (no non-ASCII characters).
- All lines strictly <= 80 columns.
- Update Section 13.1 with r5 reconciliation entry.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f9328487-72e9-44e8-a216-0fbb81bf6a2e
