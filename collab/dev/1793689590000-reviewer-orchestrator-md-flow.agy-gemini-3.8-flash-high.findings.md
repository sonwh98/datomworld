### Findings

1. **[blocker] Contradiction with 2026-11-03 write-time filing rule in Workflow Step 11**
   - **Section:** [`## Workflow`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L375-L377), Step 11 (*Archive completed-task artifacts.*), lines 375–377
   - **Quoted Evidence:**
     > *"Never delete, truncate, rename, or overwrite an artifact; use `mv -n` and leave collisions in the flat in-flight space until the reused task name is resolved."*
   - **Details:** Line 376 explicitly calls the flat root `"the flat in-flight space"` and directs the orchestrator to leave collisions in the flat root when resolving name collisions via `mv -n`. This directly contradicts the 2026-11-03 owner amendment established in [`## Artifact protocol`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L96-L112) (lines 96–112) and Step 11 (lines 334–337). Under the new rule, the flat root is exclusively reserved for undetermined-track artifacts; per-track artifacts are written directly into their destination topic subfolder (`collab/<topic>/`) from birth, and *"in-flight vs archived is carried by GIT, not by the directory"* (lines 109–110). Describing the flat root as `"the flat in-flight space"` and prescribing `mv -n` as the universal collision handler preserves the obsolete pre-amendment mental model where all in-flight artifacts lived flat until archiving.

2. **[minor] Out-of-order forward reference disguised as backward reference in Artifact Protocol**
   - **Section:** [`## Artifact protocol`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L141-L145), lines 141–145
   - **Quoted Evidence:**
     > *"This visibility split is why a merged worktree's collab files must be committed before that worktree is deleted (the worktree-deletion rule above), and why in-flight artifacts belong in their topic subfolder from birth: a committed topic file survives checkout deletion, a flat-root file does not."*
   - **Details:** The parenthetical `(the worktree-deletion rule above)` points to a rule that has not yet appeared. Lines 104–106 earlier mentioned only the historical audit incident (`orchestrator-logs/1793358019`), not the operational rule. The actual worktree-deletion rule appears 28 lines later at lines 171–177 (*"A worktree is deleted only after its work has been successfully merged to master under that authority..."*).

3. **[minor] Triplicate explanation of the 2026-11-03 Collab Filing Rule & Drift Risk**
   - **Section:** [`## Artifact protocol`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L96-L148) (lines 96–112, 131–145) vs [`## Workflow`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L334-L353), Step 11 (lines 334–353)
   - **Quoted Evidence:**
     - Lines 96–100: `**Decide the topic before writing (2026-11-03 owner ruling).** An artifact is written directly into its destination topic subfolder (`collab/yang-python/`, `collab/dev/`, … per step 11's folder rules) when the subsystem it belongs to is known... The flat collab/ root is reserved for artifacts whose track is genuinely undetermined...`
     - Lines 131–135: `Artifacts stay untracked while in flight — whether they sit in the flat root (undetermined track) or in their topic subfolder (the normal case, since the filing decision happens at write time) — and become the record only through the archive step (Workflow step 11), which commits them...`
     - Lines 334–346: `Under the write-time filing rule (Artifact protocol, 2026-11-03) the artifacts already sit in their topic subfolder, so archiving is git add collab/<topic>/ plus the commit; the only files that need MOVING are flat-root stragglers... There is no separate archive root: collab/ IS the collaboration record, its topic subfolders are the archive, and the flat root is only the undetermined-track staging space (2026-10-31 owner ruling, superseding the 2026-10-17 separate-top-level-archive ruling; amended 2026-11-03 to move the filing decision to write time)...`
   - **Details:** The rationale for write-time filing, the worktree-deletion audit narrative, the git tracked-ness distinction, and the superseded rulings history are restated three times in the same document.
   - **Canonical Suggestion:** [`## Artifact protocol`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L96-L148) should be the single canonical source for the storage model and write-time filing rule. Step 11 in [`## Workflow`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L332-L379) should be canonical strictly for the mechanical archive execution procedure (`git add`, commit message format, and `src/dev/subsystems.edn` mapping), citing `Artifact protocol` rather than repeating the background rationale.

4. **[minor] Sibling reference recipes direct output to the flat collab root**
   - **Section:** [`docs/agents/delegate-invocation-reference.md`](file:///home/sto/workspace/datomworld/docs/agents/delegate-invocation-reference.md#L111), lines 111, 295, 301, 308, 314, 319, 326, 331, 339
   - **Quoted Evidence:**
     - Line 111: `> Document the exact tool call here and invoke directly, capturing output into collab/.`
     - Line 295: `> collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1`
   - **Details:** All invocation recipes in `delegate-invocation-reference.md` route stdout logs directly to `collab/<timestamp>-...` (flat root) rather than `collab/<topic>/<timestamp>-...`. An orchestrator using these recipes verbatim will violate the 2026-11-03 write-time filing requirement.

5. **[minor] Test harness / CLJD concurrency constraint misplaced in Artifact Protocol**
   - **Section:** [`## Artifact protocol`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L163-L164), lines 163–164
   - **Quoted Evidence:**
     > *"Only one process owns the CLJD lane because `bb test:cljd` writes shared generated output."*
   - **Details:** This is a test execution concurrency constraint, not an artifact protocol rule. It is wedged inside a paragraph discussing worktree artifact copying. It belongs under Step 4 (*Choose execution and review routes*) or Step 6 (*Verify locally*), or cross-referenced to [`docs/agents/build-n-test.md`](file:///home/sto/workspace/datomworld/docs/agents/build-n-test.md).

6. **[nit] Redundant worktree artifact copy-out instructions**
   - **Section:** [`## Artifact protocol`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L167-L171), lines 167–171
   - **Quoted Evidence:**
     > *"Before removing a worktree, diff its `collab/` against the main tree's and copy over anything unique — a worktree merge moves only committed content. Under the one-record `collab/` regime this is not a copy-out burden: collab files generated in a worktree are added to git there (step 11's `docs(collab):` commit) and reach master through the merge."*
   - **Details:** Line 167 prescribes manual diffing and copying out of `collab/`, while lines 169–171 state that committing in the worktree prior to merging makes copying out unnecessary. Committing in the worktree before merging should be stated as the canonical procedure, with diffing against main described only as a safety check against forgotten untracked files before deletion.

7. **[nit] Unfinished-work log entry kind lacks schema specification**
   - **Section:** [`## Work log`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L198-L220), lines 198–220 vs [`## Workflow`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L380-L383), Step 11, lines 380–383
   - **Quoted Evidence:**
     > *"Three entry kinds only: one per coherent unit (a delegated round, a fix, a review reconciliation, a readiness report), an unfinished-work entry when work stops before the next unit finishes, and a final handoff entry when the seat is passed on."*
   - **Details:** `Work log` specifies three distinct entry types, but only provides the template schema for a completed unit. It does not provide the concrete header/field schema for an unfinished-work entry (e.g. how `Completed-GMT` and `Done` should be populated when a unit is halted mid-flight).

8. **[nit] Handoff template omits trivial-edit exemption in commit authority summary**
   - **Section:** [`## Orchestrator Seat Handoff Template`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L426-L427), lines 426–427 vs [`## Coordination contract`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L61-L74), lines 61–74
   - **Quoted Evidence:**
     > *"do not stage, commit, or merge without an Architect sign-off or explicit user instruction;"*
   - **Details:** [`## Coordination contract`](file:///home/sto/workspace/datomworld/docs/agents/roles/orchestrator.md#L61-L74) establishes three distinct commit authorizations: (1) Architect sign-off, (2) explicit user instruction, and (3) trivial-edit exemption. The handoff template brief omits the trivial-edit exemption.

VERDICT: revise — Workflow step 11 (line 376) directly contradicts the 2026-11-03 write-time filing rule by referring to "the flat in-flight space" and directing collisions to remain in flat storage, alongside an inverted forward reference and duplicated rationale.
