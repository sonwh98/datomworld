Created-GMT: 2026-10-10 18:40:00 GMT
Created-Local: 2026-10-11 01:40:00 Asia/Bangkok

# Task: adversarial review of slice 4b Stage 1 (continuing your 4a review session)

Role: Adversarial Reviewer / Lead System Architect. Worktree `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4b`, HEAD = the Stage 1 commit (diff against master: `git diff master...HEAD`). READ-ONLY except your findings file; no git add/commit. Beware zsh word-splitting: write each `-n ns` literally.

Author: codex (gpt-6.1-sol); you are the non-author family. Read the brief `collab/1791690000000-vm-engineer-srvm-phase4b-stage1.prompt.md` (Stage 1 scope: acceptance items 1-13, 18, 24, the continuation/resume/park-then-halt trace rows, and evaluator §5.3 items 3-5), the author's report `collab/1791690000000-vm-engineer-srvm-phase4b-stage1.report.md`, and the evaluator design `docs/design/yin.vm.semantic-register-vm.evaluator.md` (§1.8-1.9, §2.2, §4, §5.2, §5.3).

Orchestrator verified on JVM: effects-test 20 tests / 90 assertions; vm-test + walker-trace-test + parity-test 46 / 570; kondo 0/0. Node and Dart not yet run by the orchestrator.

Do, adversarially:
1. For each of the Stage 1 acceptance items and §5.3 items 3-5, is there a test that can FAIL for a broken implementation? Apply mutants yourself to `semantic_register.cljc` temporarily (restore byte for byte, verify by checksum): at least the eight the brief names (woken entry writing the wrong register; an entry saving too much or too little of the window; a `tail` entry restoring into a window; delivering twice; a continuation invoke that is not abortive; a `:window` missing from `gc-roots`; the tail host-fn pop ungated (M21); callee-E-kept (M20)) plus any you think of. Report each as KILLED (which test) or SURVIVED. Check the author's own mutation table against yours.
2. Trace oracle: tagged results only, park by executed node/instruction, explicit destinations; are the continuation/resume/park-then-halt rows derived independently?
3. The `return-to` factoring and the removal of the four redundant materializations (§5.3 item 4): sound, and is the design amended or silently contradicted? The row `((fn [x] (list ((fn [x] 2) 9) x)) 1)` -> `(2 1)`.
4. Scope: nothing outside src `semantic_register.cljc` and the semantic_register tests; no existing test assertion weakened; the vm_test deferral changes are legitimate.
5. Anything in the Stage 2 deferral list that Stage 1 should already have.

Write `collab/1791710000000-reviewer-srvm-phase4b-stage1.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-5, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim reads or runs that did not occur.
