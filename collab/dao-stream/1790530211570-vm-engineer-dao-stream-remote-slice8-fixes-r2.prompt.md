Created-GMT: 2026-09-27 19:40:00 GMT
Created-Local: 2026-09-28 02:40:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 8 fixes r2)

# Task: Slice 8 — Fix the confirmation gate's two P1 findings

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; the slice-8
facade + fix round 1 are uncommitted). The confirmation gate found two
P1s: lowered link waits and retained FFI requests do not RESUME
correctly through the engine. Read:
collab/1790522276329-architect-dao-stream-remote-slice8-confirm.gpt-6-sol.findings.md

1. P1 (remote.cljc:535): the lower installs link waits under
   identity-derived resource IDs, but the engine polls the response at
   the FIXED module/link-response-resource key (engine.cljc:1215) and
   request retry uses the fixed link-request-resource key
   (module.cljc:403). A lowered link wait therefore never resumes.
   Fix: lower link waits onto the engine's FIXED resource keys (the
   identity is already carried in the entry context per the engine's
   own shapes), so the engine's poll/retry paths find them. The UCF
   lift must correspondingly read link waits from those fixed keys.
   Tests: a lowered link wait is actually polled and resumes (drive
   the engine's link-response path); a lifted link wait round trips.
2. P1 (remote.cljc:292, :520): a retained FFI request lifts only the
   request stream with no response cell; after retry succeeds,
   semantic-restore creates a response wait on the emitter's fixed
   local call-out keys (semantic.cljc:132), but the response arrives
   on the emitter's pair -- the wait strands. Fix: lift must also
   carry the response-route cell (the emitter's call-out keys as UCF
   cells); lower must install the response wait on the receiver's
   local call-out keys so the retried response resumes it. Tests: a
   retained FFI request migrates, the retry succeeds, and the resumed
   frame receives the response (resume the retry in the test).

Constraints: touch only the two slice-8 files
(src/cljc/yin/vm/ucf/remote.cljc, test/yin/vm/ucf/remote_test.cljc).
Preserve rounds 1-2 confirmed behavior (real parked shapes, codec
portability, frame-level cell sharing). ASCII, <= 80 cols, cljstyle/
kondo clean, no commit/stage/checkout/reset/stash, no diagnostics.
Verify: the facade namespace green on all three lanes (focused runs;
the full suite is mid-flight with concurrent slice-5 work -- attribute
embed/main failures). Exact counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
