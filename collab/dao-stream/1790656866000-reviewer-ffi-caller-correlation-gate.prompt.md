Created-GMT: 2026-09-29 04:41:06 GMT
Created-Local: 2026-09-29 11:41:06 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0eb77-8192-7580-968c-b65f6ec8facb (captured)
# Task: Gate — Slice 3d caller-scoped FFI correlation, response router, caller readiness, portable loss error

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-29 11:41:06 +07 (+0700) | Status: active | Rationale: owner decision "gated by gpt-6-sol with glm-5.3's findings in the gate brief"; implementation Claude-authored (claude-opus-5-5). Note: gpt-6-sol also authored the governing ruling; glm-5.3 independently concurred with changes, so review the implementation against BOTH, and do not defend the ruling where the code shows it wrong.

Read-only review in /Users/sto/workspace/datomworld. Do not edit. Treat prior reports as untrusted; cite evidence.
Change under review: the uncommitted diff on master c9313ee0 — git diff -- src test — across yin.vm, engine, ffi,
semantic, ast_walker, debruijn/stack, debruijn/register, debruijn_register_effects, remote_serve/responder, their tests,
test/yin/vm/ucf/remote_test.cljc, plus the new src/cljc/yin/vm/ffi/remote_serve/caller.cljc. (docs/orchestrator-log.md
is orchestrator bookkeeping, out of scope.)

Governing documents:
- ruling: collab/1790594862000-architect-ffi-serving-slice3-r3.gpt-6-sol.findings.md
- second opinion (its "Changes to the ruling" 1-5 are binding): collab/1790654484000-architect-ffi-correlation-second-opinion.glm-5.3.findings.md
- the brief with owner decisions (8 VM files authorized; supplied pair without :call-out-cursor REFUSED) and acceptance
  a-g: collab/1790655248000-vm-engineer-ffi-caller-correlation.prompt.md
- the 3c gate finding being fixed: collab/1790611924000-reviewer-ffi-responder-e2e-gate.gpt-6-sol.findings.md
- one-envelope ruling: collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md
- implementer report (untrusted): collab/1790655248000-vm-engineer-ffi-caller-correlation.claude-opus-5-5.report.md
- docs/design/datom.world.md, docs/design/dao.stream.md

Orchestrator-verified on this exact tree (do not rerun): kondo 0 errors (5 warnings, all in untouched regions:
vm.cljc 1280-1458, ast_walker.cljc 596); cljstyle clean (orchestrator reformatted one file); no leftover mutation
code; full JVM 2341 / 184033 / 0; Node 2247 / 50560 / 0; CLJD +2209: All tests passed!.

Check correctness of acceptance a-g, the invariants (esp. no shared mutable state, no layer collapse:
dao.stream.waitset must be unchanged and ignorant of apply ids; yin.vm ignorant of remote reflections), UCF migration
of composite ids, CLJ/CLJS/CLJD portability (vector map keys, record field additions), regressions in the four VM kinds,
and whether each modified existing test was changed legitimately (not weakened). Rule explicitly on:
Q1. A gap on a response cell wakes EVERY waiter on that cell as lost, though some answers may lie beyond the gap.
    Acceptable (the router cannot know which calls were swallowed), or must it be narrower?
Q2. A request writer woken but not yet restored to a reader stalls its cell (router stops without consuming a response
    addressed to it). Only that VM's cell stalls. Sound, or a liveness defect?
Q3. Caller token: non-blank string or keyword; uuid rejected (fails vm/plain-data?). Correct given plain-data? and the
    channel codec, or should plain-data?/the token rule change?
Q4. The per-cell read budget is a fixed engine constant (64), not a per-VM option, because the walker's record rebuild
    would drop a per-VM setting. Acceptable?
Q5. caller/open leaves its reflections open when the readiness step gives up. Defect?
Q6. Existing tests changed: helpers now pass an explicit oldest :call-out-cursor; two "response for another call"
    tests now assert the call stays parked and is later resumed by its own response instead of raising; responder test
    5 expects the portable loss error. Legitimate behaviour changes per the rulings, or weakened tests?
Q7. Can remote FFI now be claimed complete per the one-envelope ruling's end-to-end definition plus the 3c/3d gates?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q7; where a question
is an owner or Architect decision, say so rather than choosing.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
