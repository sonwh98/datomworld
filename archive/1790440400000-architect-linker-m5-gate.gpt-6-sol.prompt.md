Created-GMT: 2026-09-26 13:35:00 GMT
Created-Local: 2026-09-26 20:35:00 +0700
Coding-Agent: codex
Session-ID: n/a (thread id captured from stdout)

# Task: gate for yin.vm.linker M5, yin.repl wiring (GLM-authored, non-GLM reviewer)

Role: Adversarial Code Reviewer and Security Auditor + Lead System Architect (combined commit gate)

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-26 20:35:00 +0700 | Status: active | Rationale: owner authorization "yes, send M5 to codex"; author was glm-5.3-flash

You are a HEADLESS read-only reviewer. Produce the COMPLETE review in your final response now. No questions, no plan-only answer, no edits, do not rerun the suites. Cite file:line. Rank P0 (blocks commit) / P1 (fix before merge) / P2 / P3. End with a verdict APPROVE / APPROVE-WITH-FIXES / REJECT and a must-fix list.
Rules in force: Rule R (yin/def is syntax, never a name); require stays an ordinary function that lowers to the linker; no contract stamp is assigned to external input; code content-addressed, names via authority policy; fetch has no deadline (owner ruling: composition owns liveness, dao.lease is the pointer); ClojureDart trap (#?(:clj) does not exclude code from cljd; use :cljd first); no backward-compat shims. The implementer report is UNTRUSTED.

## Under review
Uncommitted work in /Users/sto/workspace/datomworld-m5 (branch m5, from master 78163e92 which has M1-M4). Modified: src/cljc/yin/repl.cljc (+~320), src/cljc/yin/vm/linker.cljc (+44/-~), test/yin/vm/store_write_audit_test.clj (+4 allowlist); NEW: src/cljc/yin/repl/link.cljc, test/yin/repl/require_test.cljc. Read `git -C /Users/sto/workspace/datomworld-m5 diff HEAD` and the new files directly. Spec: docs/design/yin.vm.linker.md section 9 "M5", sections 6.1, 6.3, 7.2-7.4, 12 bullet 4 (failure policy), completion criteria (last clause: `(vm :stack)` and `(vm :register)` link the same manifest by H and R with B0-equal results). Report (untrusted): collab/1790437900000-vm-engineer-linker-m5-repl-wiring.glm-5.3-flash.report.md.
Orchestrator evidence: JVM 2198 tests/182742 assertions/0 failures, Node 2110/49420/0, Dart 2072 all passed (after a test-only fix: three assertions compared rendered text "'mod" vs ClojureDart "(quote mod)", now compare :last-value), cljstyle/kondo clean.

## Scrutinize
1. The additive `:defer-discharge` opt on link-manifest (linker.cljc): step 5b is the receiving task's per spec 7.2. Is deferring correct, or does it let an undischarged obligation reach install/run? Any path where defer is on by default or reachable from untrusted input?
2. The link-id counter fix: "the shell's failure rollback reset the VM's link-id counter so the next require could mint a reused [:t0 0] and settle on a stale response; the parked counter now carries across every rollback path". Verify every rollback path (error, reset, (vm ...), abandon). Can a late response from an abandoned/pending link settle a LATER require (id reuse, wrong correlation)?
3. The :pending-run state: can a stuck link wedge the shell, hide input, or leak resources? `(abandon)`, `(reset)`, `(vm ...)` semantics. Exactly-once consumption of input.
4. The link box (yin/repl/link.cljc): content source handling (:content-store, :content-client, none); bounded serve rounds; does the REPL serve content in a way that gives a program a path around the authority policy or the sealed-resource private table (link pair placed in :resources)? Do children inherit the link pair safely (spawn-module)?
5. The composition owns no clock/atom/global (repl ns docstring); fetch/engine stay clock-free.
6. Tests: the H/R criterion is really exercised on both backends; no weakened existing assertion; the store_write_audit allowlist entry ("composition" :map) is justified (is `:store` in a content-source descriptor really never a VM store write?).
7. Failure policy: the implementer recommends own pending state now, adopt dao.lease when the driver has a tick clock. Give your own view for the owner's decision (dao.jing.remote timing options vs dao.lease vs own).
8. cljd-unsafe forms, Rule R, contract stamps.
