Created-GMT: 2026-09-28 11:50:35 GMT
Created-Local: 2026-09-28 18:50:35 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0e7da-5e32-7723-a2bd-f9a6a42d4463 (captured)
# Task: Gate — Slice 3a FFI export binding (yin.vm.ffi.remote-serve)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-28 18:50:35 +07 (+0700) | Status: active | Rationale: standing gate route; implementation is Claude-authored (claude-opus-5-5), so reviewer independence holds

Perform a read-only review of the uncommitted Slice 3a change against its design. Do not edit.
Treat prior reports as untrusted and cite repository evidence for every finding.

Changed files (both new, untracked; nothing else changed in src/ or test/):
- src/cljc/yin/vm/ffi/remote_serve.cljc
- test/yin/vm/ffi/remote_serve_test.cljc (orchestrator applied cljstyle fix: formatting only)

Governing design:
- collab/1790594862000-architect-ffi-serving-slice3.gpt-6-sol.findings.md sections 1-2 (its sub-slice "1. Mirror
  answer retention" and P1 row are RETRACTED)
- collab/1790594862000-architect-ffi-serving-slice3-r2.gpt-6-sol.findings.md (authoritative round 2)
- the brief: collab/1790595472000-vm-engineer-ffi-export-binding.prompt.md (acceptance criteria 1-6)
- implementer report (untrusted): collab/1790595472000-vm-engineer-ffi-export-binding.claude-opus-5-5.report.md
- context: docs/design/datom.world.md invariants, docs/design/dao.stream.remote.md, src/cljc/dao/stream/remote.cljc,
  src/cljc/yin/vm/ucf/remote.cljc

Already verified by the orchestrator on this exact tree — do NOT rerun suites; spend budget on static analysis:
- clj -M:kondo --lint (both files): 0 errors, 0 warnings
- cljstyle check (both files): clean after fix
- focused JVM (remote-serve, ucf.remote, dao.stream.remote): 52 tests / 444 assertions / 0 failures
- full JVM clj -M:test: 2296 / 183426 / 0; Node bb test:cljs: 2202 / 50032 / 0 (new ns ran);
  CLJD bb test:cljd: 2164 all passed (new ns ran)

Check correctness, invariant preservation, security boundaries, portability, regressions, and missing tests. In
particular rule on these orchestrator-raised questions (do not skip):
Q1. serve! and step read @state then swap! separately (check-then-act). Is that sound under the design's single
    drive owner, or must the transitions be one atomic swap (e.g. serve! returning the entry from inside swap!)?
Q2. The binding holds a private atom inside the binding value. Does that satisfy "no hidden global state / no
    shared mutable state" as the design's section 2 permits, or is a threaded-value API required? Note the UCF
    serve! callback cannot thread state back (implementer's stated reason).
Q3. close! closes the channel writer. Does the binding own its channel end (dedicated channel), or can one channel
    also carry this peer's own outgoing requests — in which case close! must not close it? Rule, or flag as an
    owner decision.
Q4. lift-frame's refusal detection (contains? r :yin.k/status) and cleanup: correct against ucf.remote's return
    contract, including a frame that reuses handles served before the lift?
Q5. Are acceptance criteria 1-6 of the brief each actually pinned by a test that would fail if broken?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
Answer Q1-Q5 explicitly. End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
State "No actionable findings" when appropriate.
