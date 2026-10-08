Created-GMT: 2026-09-05 12:37:25 GMT
Created-Local: 2026-09-05 19:37:25 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

# Task: Review descriptor-based stream-observer plan

Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-05 17:15:00 Asia/Ho_Chi_Minh | Status: active | Rationale: Architect primary and independent reviewer of the GPT-authored plan.
- Status-Event: 2026-09-05 17:34:32 Asia/Ho_Chi_Minh | Model: claude-fable-5-1 | Status: changes-required | Rationale: Initial separation plan required corrections to idle-step, cursor commitment, and direct-eval semantics.
- Status-Event: 2026-09-05 17:39:35 Asia/Ho_Chi_Minh | Model: claude-fable-5-1 | Status: approved-with-notes | Rationale: r3 resolved all five original findings; two nonblocking notes were incorporated.
- Model: claude-fable-5-1 | Assigned: 2026-09-05 19:37:25 Asia/Ho_Chi_Minh | Status: active | Rationale: Same Architect session resumes to review the user's descriptor-based attachment revision.

Perform a complete read-only architecture review of:
`collab/orchestrator-stream-observer-descriptor-20260905-123322.plan.md`

This plan supersedes the earlier reader-handle constructor design. The user explicitly requested a fresh Architect review. Permission mode is auto, as the user required; the task remains review-only. No implementation, edits, tests, staging, commits, or further delegation. Return the complete review now; do not return a plan to review later.

Use the context from this exact session and read the replacement plan in full. Read/recheck only relevant evidence from:
- docs/agents/roles/architect.md
- docs/agents/team.md
- docs/design/datom.world.md
- docs/agents/malleability.md
- docs/design/dao.stream.md
- src/cljc/dao/stream.cljc
- src/cljc/dao/stream/ringbuffer.cljc
- src/cljc/dao/stream/ws/ (only attachment, surfaces, and lifecycle code if needed)
- src/cljc/yin/vm.cljc
- src/cljc/yin/vm/{stream_observer,engine,ast_walker,ffi,runtime_adapter}.cljc
- src/cljc/yin/repl/core.cljc
- test/yin/vm/{stream_observer,engine,ast_walker,ffi,parity}_test.cljc
- test/yin/vm/test_utils.cljc
- test/yin/repl_core_test.cljc
- test/dao/stream/ringbuffer_test.cljc (attachment tests, if needed)
- docs/design/yin.vm.{implementation-plan,divergence-register}.md
- src/cljc/yin/vm/docs/yin.repl.md
- collab/architect-stream-observer-separation-20260905-101500-r3.prompt.md
- collab/architect-stream-observer-separation-20260905-101500-r3.claude-fable-5-1.findings.md

The refactor is unimplemented. The working tree only contains the prior namespace rename, these coordination artifacts, and two unrelated blog files. Do not inspect unrelated blog files or private configuration. No need to rerun tests: the prior 40-test/138-assertion result and clean lint cover only the mechanical rename, not this plan.

User intent and constraints:
- The stream exists independently. The observer receives a descriptor and calls existing host-provided attach!; it does not create a stream.
- The descriptor is the only per-attachment stream-specific input. Host attachment and VM wiring are supplied once by composition, without a hidden mutable global registry.
- Keep the public observer contract small. Do not restore the earlier four-operation public API merely for symmetry.
- Reuse existing mechanisms, including VM loading, readiness, step/run, and DaoStream attachment/dispatch. No new accept-datoms, ready-for-program?, VM protocol, or operation registry.
- Observation state is independent of VM execution state. Fan-out and other VM ports are deferred.
- Preserve the previously reconciled idle-step guard, loader-failure cursor semantics, gap behavior, REPL reset behavior, and explicit direct-eval decoupling.

Evaluate:
1. Whether descriptor attachment, reader validation, cursor minting, host composition, and actual ring-buffer attach/resolver behavior fit the existing DaoStream contract.
2. Whether attachment ownership and cleanup on cursor failure/reset/end are sufficiently specified without accidentally closing a producer-owned stream or adding an unjustified lifecycle mechanism.
3. Whether the small public contract and one-time lexical composition can be implemented using existing mechanisms; identify concrete missing decisions only when required to avoid a faulty implementation.
4. Whether prior reviewed fixes remain intact and any new behavior changes are named.
5. Test coverage, portability, dependency direction, and implementation readiness.

Separate blockers, nonblocking refinements, and intentionally deferred features. Assess findings from actual code/contract evidence; do not infer that a composed unary descriptor function implies ambient state. Do not invent findings or recommend new mechanisms merely to make the design more abstract.

Begin the final report exactly with actual completion timestamps:
Completed-GMT: <actual timestamp>
Completed-Local: <actual timestamp and Asia/Ho_Chi_Minh>
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

Then provide:
- Verdict: approve, approve with nonblocking notes, or changes required.
- Findings: severity | plan clause or file:line | concrete evidence | minimum correction.
- Requested properties that passed review, including the prior reviewed fixes.
- Any remaining implementation decisions necessary before approval. If none, say so.

The orchestrator will capture your complete final response in the separate r4 stdout log and promote it verbatim to findings. Prior artifacts must remain unchanged.
