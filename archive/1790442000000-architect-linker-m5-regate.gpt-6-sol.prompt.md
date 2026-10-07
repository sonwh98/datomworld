Created-GMT: 2026-09-26 15:30:00 GMT
Created-Local: 2026-09-26 22:30:00 +0700
Coding-Agent: codex
Session-ID: n/a

# Task: re-gate of yin.vm.linker M5 after fix2

Role: Adversarial Code Reviewer

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-26 22:30:00 +0700 | Status: active | Rationale: same reviewer as first gate; verifies its own findings (owner authorized codex for M5)

HEADLESS read-only: complete review now, cite file:line, no edits, do not rerun suites, end with verdict APPROVE / APPROVE-WITH-FIXES / REJECT and must-fix list.
Worktree /Users/sto/workspace/datomworld-m5 (branch m5, uncommitted). Your REJECT: collab/1790440400000-architect-linker-m5-gate.gpt-6-sol.stdout.log. GLM's fix2 report (untrusted): top "fix2" section of collab/1790437900000-vm-engineer-linker-m5-repl-wiring.glm-5.3-flash.report.md. Read `git -C /Users/sto/workspace/datomworld-m5 diff HEAD` plus the untracked src/cljc/yin/repl/link.cljc and test/yin/repl/require_test.cljc.
Evidence: JVM 2201/182774/0 failures, Node 2113/49443/0, Dart all passed, cljstyle/kondo clean.
Check each finding is closed for real:
1. P0 origin reuse: carry-link-identity carries :id-counter AND :origins across every rollback path (in-round refusal, pending-resume raise, abandon, reset, (vm ...)). Is ANY other identity generator on the surviving link pair still rewound (child origins, link ids, seals/secrets, cursors)? The regression test: does it genuinely fail without the carry (GLM claims so)? Consider the :unknown vs :late change.
2. P1 lost root ids: link-raise carries the interrupted VM identity; verify both catch sites and no other pre-drive snapshot restore remains.
3. P1 abandon: new engine `abandon-installs` (reuses refuse-install) and abandon-pending deriving from the live :wait-set. New engine.cljc public surface: does it break the sealed-resource / install invariants or leave a half-published install? Every pending phase raises the error?
4. P2 input: retained lines evaluate exactly once in order; what if a retained line itself requires (nested pending), errors, or the REPL is reset/abandoned; any unbounded growth of the retained list; can a retained line be evaluated under the wrong VM/backend after (vm ...)?
5. P2 serve progress accuracy.
6. New risks introduced by fix2: Rule R, cljd-unsafe forms, contract stamps, weakened tests, store_write_audit allowlist edits.
