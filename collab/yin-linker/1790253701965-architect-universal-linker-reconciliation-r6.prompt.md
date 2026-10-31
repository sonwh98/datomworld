Created-GMT: 2026-09-24 12:41:45 GMT
Created-Local: 2026-09-24 19:41:45 +0700
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9

# Task: Reconcile Reviewer Round 5 Findings on yin.vm.linker.md (r6)

Role: Lead System Architect

Implementers:
- Status-Event: 2026-09-24 18:45:40 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Initial draft of yin.vm.linker.md (r1)
- Status-Event: 2026-09-24 19:01:33 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r2
- Status-Event: 2026-09-24 19:21:24 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r3
- Status-Event: 2026-09-24 19:29:36 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r4
- Status-Event: 2026-09-24 19:36:42 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r5
- Model: glm-5.3 | Assigned: 2026-09-24 19:41:45 +0700 | Status: active | Rationale: Assigned to glm-5.3 per owner instruction to conserve Claude quota and author Revision r6

Coordinate and edit `docs/design/yin.vm.linker.md` in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `docs/design/yin.vm.linker.md` (in `/Users/sto/workspace/datomworld-universal-linker`)
- `collab/1790253425525-reviewer-universal-linker-consensus-r5.gpt-6-sol.findings.md` (in `/Users/sto/workspace/datomworld`)

In Round 5, the independent reviewer (`gpt-6-sol`, session `01a0d340-f8e7-7e30-9b74-c0a0e6b636fb`) closed wait-entry formatting and duplicate-authority events, but identified 2 PARTIAL issues and 1 NEW P1 architectural defect in Revision r5:

1. [PARTIAL — Dominance, Lambda Discharges, and Scanner Signatures - L257, L439, engine.cljc:54]:
   - A read inside a lambda body must NOT be discharged by a later top-level definition unless all possible applications are strictly proven to follow the definition. Otherwise, an early application reads an uninitialized binding or ambient host binding. Retain the dependency obligation unless control-flow dominance strictly proves the definition executes before every application.
   - Update the format-record examples (Section 4.1 & 5.1-5.4) so they explicitly return position-bearing records matching the scanner API, rather than legacy name sets.

2. [PARTIAL — Attachment, Positional Hashes, and Per-Task Lowering - L1077, L1099, stack.cljc:230, register.cljc:224]:
   - Appending bytecode via `attach-image` changes positional `:hash`. Parked task entries contain the old image hash; restore functions refuse hash mismatches or restore the entry's old `:segment`, potentially discarding attached images. Define hash-safe restore/rebasing for parked parent tasks.
   - Lowering bindings into *one parent's* coordinate space and publishing them to all waiters breaks waiters with differing segment offsets or local IDs. Keep bindings portable in the registry and attach/lower per receiving task upon restore, or define a genuinely shared code space.

3. [NEW P1 — Module-Local Store Isolation for Exported Closures - L1011, L1099, engine.cljc:54, UCF.md:812]:
   - A child runs with an isolated store, but the `linked` transition currently transfers only exported values.
   - If a module defines a local store variable `(def x 1)` and exports a closure `(fn [] x)`, that closure's free read evaluates against the *parent's* store after relocation, where `x` is unbound or collides with a parent binding.
   - Specify a portable, isolated module-store context for exported closures (including later store writes) rather than dumping child keys into the parent's ambient store.

Update `docs/design/yin.vm.linker.md` to **Revision r6** addressing each finding.
Update Section 13.1 with the Revision r6 reconciliation record.

Ensure the document strictly maintains:
- Pure ASCII (no non-ASCII characters).
- All lines strictly <= 80 columns.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: a2749e73-2e44-4716-8a1f-6ad0cc1d82d9
