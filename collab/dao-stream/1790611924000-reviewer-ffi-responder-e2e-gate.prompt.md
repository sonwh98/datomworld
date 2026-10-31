Created-GMT: 2026-09-28 16:12:04 GMT
Created-Local: 2026-09-28 23:12:04 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0e8c9-ba64-71d3-988e-fbacc14e5a82 (captured)
# Task: Gate — Slice 3c FFI apply responder, production lease holder, real-VM end-to-end remote FFI

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-28 23:12:04 +07 (+0700) | Status: active | Rationale: standing gate route; implementation Claude-authored (claude-opus-5-5); this slice completes "remote FFI" per the one-envelope ruling you authored

Read-only review in /Users/sto/workspace/datomworld. Do not edit. Treat prior reports as untrusted; cite evidence.
Change under review (uncommitted, on master c2417899): git diff -- src/cljc/yin/vm/ffi/remote_serve.cljc
test/yin/vm/ffi/remote_serve_test.cljc, plus new files src/cljc/yin/vm/ffi/remote_serve/responder.cljc,
src/cljc/yin/vm/ffi/remote_serve/holder.cljc, test/yin/vm/ffi/remote_serve/responder_test.cljc. (docs/orchestrator-log.md
is also modified: orchestrator bookkeeping, out of scope.)

Governing documents:
- collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md, "Serving and end-to-end acceptance"
- collab/1790594862000-architect-ffi-serving-slice3.gpt-6-sol.findings.md sections 1-4 + "Acceptance tests" (mirror
  retention sub-slice / P1 RETRACTED) and round 2 collab/1790594862000-architect-ffi-serving-slice3-r2.gpt-6-sol.findings.md
- the brief (scope A/B/C, acceptance 1-11): collab/1790610416000-vm-engineer-ffi-responder-e2e.prompt.md
- implementer report (untrusted): collab/1790610416000-vm-engineer-ffi-responder-e2e.claude-opus-5-5.report.md
- 3a/3b gate histories: collab/1790596235000-reviewer-ffi-export-binding-gate*.findings.md,
  collab/1790608971000-reviewer-ffi-lease-wiring-gate*.findings.md
- docs/design/dao.stream.remote.md section 6 (lease-grants); src/cljc/dao/stream/apply.cljc; src/cljc/yin/vm/ffi.cljc;
  src/cljc/yin/vm/ucf/remote.cljc; src/cljc/dao/lease.cljc; docs/design/datom.world.md

Orchestrator-verified on this exact tree (do not rerun): kondo 0/0 on 5 files; cljstyle clean (orchestrator fixed
formatting of one file); no leftover mutation code; focused JVM (remote-serve, responder, ffi, ucf.remote, apply,
dao.stream.remote, lease-composition) 106 / 948 / 0; full JVM 2330 / 183892 / 0; Node 2236 / 50422 / 0; CLJD +2198: All tests passed!.

Check correctness against acceptance 1-11 and the ruling's end-to-end definition (unchanged request map incl. extra
keys, same id, VM resumes/raises via call-result, no rpc/request! reissue), invariants, portability, regressions to
3a/3b, and missing tests. Rule explicitly on these (implementer-raised) points:
Q1. VM call ids repeat across VMs (each VM counts its own; call-result checks only the id). A new VM attaching an
    already-used call-out from :oldest can accept another VM's response as its own — a first draft of test 6 hit this.
    Current mitigation: one caller per call pair (by composition). Is that a defect blocking "remote FFI complete", an
    acceptable documented constraint, or an Architect question (a fix touches yin.vm / yin.vm.semantic)? If a fix is
    needed, say what shape, not the code.
Q2. The composition must fetch the call-out cursor before building the VM (a reflection's first cursor answer is
    retry). Documented/enforced adequately, or a foot-gun that needs a guard?
Q3. After a call-in gap the parked VM raises a generic "FFI response envelope is malformed". Acceptable, or must loss
    surface as a specific portable error?
Q4. Responder/holder in separate namespaces (responder interprets apply values; binding's mirror serves stream ops).
    Sound layering?
Q5. Open policy items (owner): per-request handler gate beyond ::admit?, renewal authority until ShiBi, production
    capacities/timings, lease-proposals not served. Anything among them that must be decided BEFORE commit?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q5; where a question
is an owner or Architect decision, say so rather than choosing.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
