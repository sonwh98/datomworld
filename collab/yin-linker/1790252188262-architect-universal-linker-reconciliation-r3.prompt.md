Created-GMT: 2026-09-24 12:16:26 GMT
Created-Local: 2026-09-24 19:16:26 +0700
Coding-Agent: claude
Session-ID: f9328487-72e9-44e8-a216-0fbb81bf6a2e

# Task: Reconcile Reviewer Round 2 Findings on yin.vm.linker.md (r3)

Role: Lead System Architect

Implementers:
- Status-Event: 2026-09-24 18:45:40 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Initial draft of yin.vm.linker.md (r1)
- Status-Event: 2026-09-24 19:01:33 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Reconciled to r2 addressing round 1 findings
- Model: claude-fable-5-1 | Assigned: 2026-09-24 19:16:26 +0700 | Status: active | Rationale: Resuming session f9328487-72e9-44e8-a216-0fbb81bf6a2e to address round 2 consensus findings from gpt-6-sol

Resume session f9328487-72e9-44e8-a216-0fbb81bf6a2e in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `collab/1790251711027-reviewer-universal-linker-consensus.gpt-6-sol.findings.md`
- `docs/design/yin.vm.linker.md` (in `/Users/sto/workspace/datomworld-universal-linker`)

The independent adversarial reviewer (`gpt-6-sol`, thread `01a0d340-f8e7-7e30-9b74-c0a0e6b636fb`) completed Round 2 review of Revision r2. While items 1, 7, 9a, 9b, 9c, 9d, 9e, and 10 were verified CLOSED, 7 items were marked PARTIAL and 1 ordering defect was identified.

Update `docs/design/yin.vm.linker.md` to Revision r3 in `/Users/sto/workspace/datomworld-universal-linker`, fully addressing each item:

1. [Wire request purity - L633-647 & L391]:
   - Fix the example in Section 8 wire request schema so it does NOT contain both `:yin.link/name` and `:yin.link/identity` simultaneously (make them strictly mutually exclusive).
   - Add explicit `:invalid-request` refusal reason to the Section 5 refusal table (L391).

2. [Dependency obligations - L352-374]:
   - Add scanner pass for module-local store definitions (e.g. `(def x 1) x`). Local definitions must be bound in the module obligation environment so they are not falsely flagged as `:undeclared-free`.

3. [Pending states & correlation IDs - L758-824]:
   - When child tasks run in distinct VMs, VM-local counters can collide on shared response streams. Specify correlation ID generation scoped to the shared scheduler/link-pair context, or include a unique task origin tag in the ID.

4. [Child installation & closure execution - L832-888]:
   - Stack and Register VM closures contain PC offsets without image ID, and Semantic VM closures contain local segment IDs. Loading them directly into the parent breaks code coordinates.
   - Explicitly specify that exported closures execute in the context of their owning child VM, or specify export relocation of code coordinates before publishing bindings into the parent environment.

5. [4-way derivation verification - L941-965]:
   - Eliminate loophole where `:fallback :verifying` could accept an unverified claim. For verifying requests, mandate fetching the tree and recomputing under the exact per-format profile; refuse with `:unverified-derivation` otherwise.

6. [M4 Dynamic Authority - L997-1026]:
   - Replace bare `:asserted-by` acceptance with required authenticated transaction provenance or verified cryptographic signature checks before honoring authority claims.

7. [Host modules composition boundary - L1047-1060]:
   - State the trusted composition boundary and its verification obligation explicitly: host functions cannot be statically sandbox-proven without runtime capability tokens.

8. [Validation Ordering - L289-300 vs L342-351]:
   - Check the requested contract (`:yin.module/contract`) before running row-local grammar validation in Step 2.

Update the Section 13 Reconciliation Register with Revision r3 details.
Ensure the entire document maintains:
- Pure ASCII (no UTF-8 / non-ASCII characters).
- All lines strictly <= 80 columns.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f9328487-72e9-44e8-a216-0fbb81bf6a2e
