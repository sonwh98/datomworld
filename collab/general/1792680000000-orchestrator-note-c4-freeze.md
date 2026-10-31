Created-GMT: 2026-10-15 (draft by the semantic-register-vm orchestrator seat; supersedes muse's DRAFT of 1792634000000)
Coding-Agent: claude (interactive orchestrator seat)
# Note for the owner to relay to the yang.python / C4 admission orchestrator: what phase 8 needs from their files, and when

From: the semantic-register-vm orchestrator. To: the owner (please relay). Nothing in this note changes the other seat's scope today; it asks for a freeze window later.

## 1. Already decided by the owner (do not re-ask)
- This track edits the C4 files itself, mechanically, in slice 8f, under a freeze window on those files (owner ruling 2026-10-14, "go with your recommendations" on the phase-8 plan Q1). The old codec (`handoff.cljc`, `checkpoint.cljc`) dies; the design doc is renamed `yin.vm.semantic.md` (Q2, Q6).

## 2. What the other seat will see before the freeze (small, mechanical, no action needed)
- Slices 8ab (merged soon into the integration branch `srvm-phase8`) and 8c move the published manifest addresses: this track regenerates ONLY the pins `manifest-golden` in `test/yang/python/antlr/linked_prelude_test.cljc` (py manifest address, runtime-key count) and `pysp-manifest-golden` in `linked_safepoint_test.cljc`, mechanically, in the same slice commits, exactly as 8ab did. No other file under `test/yang/` is touched before 8e.
- `test/resources/yang/python/dht-import-v1.edn` is NOT touched by this track. Its py manifest pin depends on the H/R checksums and the manifest shape, so it goes stale when 8ab/8c land on master. REQUEST: the other seat regenerates it (and `dht_import_test.cljc` expectations if needed) at the integration landing, with the same generator they used for 72592bd9. Until then `dht-import-test` slow leg is expected red on `srvm-phase8` (it is green on master).

## 3. The freeze (needed before slice 8e starts; asked for the 8e + 8f duration, roughly 8-10 working days)
- Files: the ten C4 tests under `test/yang/python/antlr/` that require the old kernel/lowerers/transport (list: `collab/1792560000000-architect-phase8-plan.report.md` section 1.5 and 3 slice 8e/8f), `test/yang/python/antlr/linked_harness.cljc` lines ~105-120 (the three backend lambdas and the loader), the two manifest goldens above, and `int_heap_test.cljc:241` (still calls the old `handoff/export-task`; that is why `handoff.cljc` survives until 8f).
- Request: the other seat pauses COMMITS to those files for the window and rebases onto `srvm-phase8` after 8f; this track edits them only as API substitution (`vm-load-program` to `load-ast`/`load-vector`, producers to `derive`), with no behavioural change; proof: `bb test:sub yang.python --slow` over the newly published images (the install-child gate).
- Please tell me (via collab/) which of your in-flight slices (I4-b onward) touch those files so the window can be placed around them.

## 4. Where the integration lands
- `srvm-phase8` lands on master only after slice 8g, the slow tier, the C4 suite and three hosts. A heads-up note will follow before that merge.

## UPDATE 2026-10-15 03:40 (Architect ruling on slice 8c; replaces the timing in section 3 for ONE file)
Fable (Architect) ruled that slice 8c cannot land alone: after 8c the published manifests carry v4 semantic vectors and `test/yang/python/antlr/linked_harness.cljc` (lines ~105-120, the three backend lambdas and the loader, still the OLD `yin.vm.semantic` VM) cannot load them, so the Python prelude/safepoint suites fail (8F/2E; the stack and register legs pass). Ruling C: land 8c and the harness port TOGETHER as one slice. REQUEST NOW, owner to relay: the other seat freezes `test/yang/python/antlr/linked_harness.cljc` (that ONE file) from the relay until the `srvm-phase8c` merge into `srvm-phase8` (est. 1 to 2 days: codex ports it mechanically, Fable re-reviews, jupiter/shiva lanes); the other seat rebases any edit of it afterwards; no other test/yang file is touched (the two prelude/safepoint pin files are already under the 8ab precedent). If the owner refuses the freeze now, 8c waits on its branch, unmerged, until 8f's freeze; it never lands red. The rest of section 3 (the ten C4 files for 8e + 8f) is unchanged. Unattended: NOTHING is ported until the owner confirms.

## FREEZE IN EFFECT (owner go-ahead received 2026-10-15 11:38 +07: "go ahead with the harness freeze")
Scope: ONLY `test/yang/python/antlr/linked_harness.cljc`, from now until the `srvm-phase8c` merge into `srvm-phase8`. Verified at the start: no uncommitted edit to that file in datomworld-c4-i5, datomworld-c4-i6, datomworld-ad2, the main checkout; master has not touched it since the 8c base (30d4c72c). The semantic-register-vm seat will edit that one file (mechanical: the :semantic backend and `continues` move to the semantic-register kernel); the other seat should not edit it and rebases any later edit onto srvm-phase8. The end of the freeze will be announced here.

## FREEZE ENDED (2026-10-15 12:34 +07): the harness port landed
`test/yang/python/antlr/linked_harness.cljc` is merged into `srvm-phase8` (commit 19528b7c, merge 31d403e7): the :semantic backend and `continues` load through the semantic-register kernel (`load-ast`); 15 lines, no behaviour change; `linked-prelude-test` + `linked-safepoint-test` 27 tests/198 assertions green. The other seat may edit that file again; edits made on master meanwhile must be rebased onto `srvm-phase8` at the integration landing. The semantic-register-vm seat will not touch it again until slice 8e/8f (which will need its own freeze: ten more C4 files; see section 3 of this note). Still expected red on `srvm-phase8` until the integration landing: the slow `dht-import-test` (the other seat regenerates dht-import-v1.edn).
