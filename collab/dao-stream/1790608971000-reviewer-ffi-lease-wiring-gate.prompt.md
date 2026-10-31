Created-GMT: 2026-09-28 15:22:51 GMT
Created-Local: 2026-09-28 22:22:51 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0e89c-b231-7910-bc49-84d9be2eb768 (captured)
# Task: Gate — Slice 3b lease wiring for the FFI export binding (yin.vm.ffi.remote-serve)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-28 22:22:51 +07 (+0700) | Status: active | Rationale: standing gate route; implementation Claude-authored (claude-opus-5-5)

Read-only review in /Users/sto/workspace/datomworld. Do not edit. Treat prior reports as untrusted; cite evidence.
Change under review: the UNCOMMITTED working-tree diff of src/cljc/yin/vm/ffi/remote_serve.cljc and
test/yin/vm/ffi/remote_serve_test.cljc (git diff -- those two paths), on top of Slice 3a (committed b34643c0).
Note: master may gain an unrelated yin.repl indexing commit while you review; ignore files outside these two.

Governing documents:
- collab/1790594862000-architect-ffi-serving-slice3.gpt-6-sol.findings.md section 3 "Lease lifecycle" (its
  mirror-retention sub-slice and P1 row are RETRACTED); round 2 ruling
  collab/1790594862000-architect-ffi-serving-slice3-r2.gpt-6-sol.findings.md
- the brief with acceptance 1-7: collab/1790606567000-vm-engineer-ffi-lease-wiring.prompt.md
- implementer report (untrusted): collab/1790606567000-vm-engineer-ffi-lease-wiring.claude-opus-5-5.report.md
- docs/design/dao.stream.remote.md section 6; docs/design/dao.lease.md; src/cljc/dao/lease.cljc; datom.world.md
- Slice 3a gate history: collab/1790596235000-reviewer-ffi-export-binding-gate*.findings.md

Orchestrator-verified on this exact tree (do not rerun suites): kondo 0/0, cljstyle clean; focused JVM
(remote-serve, dao.lease, dao.lease-composition, dao.stream.remote, ucf.remote) 140 / 1222 / 0; full JVM
2306 / 183609 / 1 failure = yin.repl.main-test killing-the-connection-is-observable-and-requests-are-lost, the logged
pre-existing cross-process flake (fails ~1 in 4 on clean master; rerun here 1 fail then 2 passes; 3b touches no
yin.repl file); Node 2212 / 50193 / 0; CLJD +2174: All tests passed!.

Check correctness against acceptance 1-7, invariants, portability, regressions to 3a behaviour, and missing tests.
Rule explicitly on these orchestrator-raised questions (implementer's own concerns):
Q1. The binding edits the dao.lease judge's :facts vector directly to remove a finished lease's renewal medium, and
    wires renewal media after assembly via wire-facts without a :medium declaration, because dao.lease has no public
    unwire. Is reaching into dao.lease's judge state acceptable, or a layering/invariant defect that requires a public
    dao.lease function (which would need owner authorization to add)?
Q2. open! does not refuse a ::lease-media source equal to the grantor; facts on such a medium would be attributed to
    the grantor. Defect?
Q3. Anyone learning a renewal medium's descriptor can renew (no ShiBi yet). Acceptable as the design's known,
    capability-agnostic seam (remote spec non-goal), or must it be recorded somewhere?
Q4. The production holder and grant delivery (serving grants as a lease-grants entry per remote spec S6) are left to
    3c / tests. Is that within 3b's scope as the design slices it, or a gap 3b must close?
Q5. reattach! closes the OLD writer on reattachment so the remote's unfinished appends end as append-unknown. Correct
    under the owner's dedicated-channel rule (::channel-exclusive?) and the spec's detach/reattach rules?
Q6. Renewals still unread on the channel count one pass late; tolerance sizing must cover the mirror's read budget.
    Documented/validated adequately?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q6; where a question is
an owner decision, say so rather than choosing.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
