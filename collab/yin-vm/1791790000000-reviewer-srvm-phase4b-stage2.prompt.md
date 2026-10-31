Created-GMT: 2026-10-10 20:00:00 GMT
Created-Local: 2026-10-11 03:00:00 Asia/Bangkok

# Task: adversarial review of slice 4b Stage 2 (continuing your 4a / 4b-stage-1 review session)

Worktree `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4b`, HEAD bdf48cf4 (Stage 2; Stage 1 is e47fcbd6 + 0f4522b9, already signed off by you). READ-ONLY except your findings file; no git add/commit. Write each `-n ns` literally; foreground only.

Author: codex (gpt-6.1-sol). Read the Stage 2 brief `collab/1791750000000-vm-engineer-srvm-phase4b-stage2.prompt.md`, reports `collab/1791750000000-vm-engineer-srvm-phase4b-stage2.report.md` and `...stage2-r2.report.md`, and the evaluator design (`docs/design/yin.vm.semantic-register-vm.evaluator.md`: §1.10 FFI, §2.2 restore steps 1-2, §3 link and install, §4, §5.2 items 14-17, 19-23, 25-26, §5.3). Diff: `git diff 0f4522b9 HEAD`.

Orchestrator verified: JVM 231 tests / 3313 assertions (17 namespaces), Node 114 / 2006 (codex), Dart 181 passed (13 namespaces incl. the four extended suites), 0 failures. The register backend is excluded from exactly one existing test (`a-body-read-applied-before-its-definition-keeps-its-obligation-test` in `linker_require_test`) because the design's `(constantly [])` fixture cannot satisfy a real-scanner assertion until phase 5c; I authorized that single exclusion.

Do, adversarially:
1. For each Stage 2 acceptance item, is there a test that can FAIL for a broken implementation? Apply mutants yourself to `semantic_register.cljc` (restore byte for byte, verify by `git diff --quiet HEAD -- src/`): a FFI retained-request phase delivering to guest code; a link phase losing the delivery record; `:call-id` not removed before `ffi/call-result`; restore steps in the wrong order; install/link refusal not raised; M-P (`check-callee-effect!` dropped); require builder dropping the tail delivery record; module marker checks (params, entry, format, origin) relaxed; composite FFI id wrong; install child window shared with parent; plus your own. KILLED (which test) or SURVIVED, and compare to the author's 23-mutant table (the one survivor is claimed equivalent: judge it).
2. The four existing-file registrations: strictly additive? No existing assertion changed except the one authorized exclusion? Check each of `continuation_invoke_test`, `ffi_test`, `rule_r_test` (backend selection at `a-parked-read-in-a-definition-resumes-and-writes`), `linker_require_test`. Anything else in the repo outside src `semantic_register.cljc` and the semantic_register tests that changed.
3. `IModuleKernel`: all nine methods against `semantic.cljc` (template) and `module.cljc` (protocol): ownership, origin-image checks, store-of encode/decode, GC, attachment; any divergence.
4. The trace oracle and parity rows with FFI: classification of events by semantic production/delivery, park by executed instruction; independently derive one FFI trace.
5. Design conflicts: anything the author resolved by reading the design differently; the implementer-derived number (require rd 5) and the strengthened parked-child fixture: justified?
6. Scope leak or weakened assertion anywhere.

Write `collab/1791790000000-reviewer-srvm-phase4b-stage2.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-6, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim runs that did not occur.
