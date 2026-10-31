You are the Lead System Architect for the datom.world project.
Define the architectural specification and implementation recipe for Track B Slice S3a.

Context & Authorities:
- Mob consensus: archive/1791304310868-architect-transport-mob-consensus.gpt-6-astra.findings.md (§D3, §D5)
- S0/S1 sign-off: archive/1791308475375-architect-stream-s0-s1-signoff.glm-5.3.findings.md (§3)
- S2 spec: archive/1791353752491-architect-stream-s2-spec.claude-fable-5-1.findings.md (§4, §5)
- S2d landed on master: docs/design/dao.stream.remote.md, docs/design/dao.stream.ws.md, src/cljc/dao/stream/ws.cljc, src/cljc/dao/stream/ws_project.cljc

Slice Scope (from D5):
"S3a: neutral board attachment/acceptance, stream-side liveness, explicit stop and lifecycle-gap recovery or terminal failure."

Tasks to address in this specification:
1. Exact production bounds profile for S3a (mirror-budget 64, chase-budget 32, step-budget 64, drain-budget 256, max-outstanding 256, max-filed 1024, give-up-after 15000 ms, max-frame-bytes 4 MiB, max-pending-frames 64, max-pending-bytes 1 MiB, outbound-high-water 4 MiB, max-outbound-bytes 16 MiB, max-sessions, idle-timeout).
2. Neutral board composition: attachment and acceptance wiring for read-only board streams (e.g. head board / meet board) over ws-project.
3. Stream-side liveness supervision and correct :answered reporting in the yin follower (distinguishing local blocked from remote acknowledgement).
4. Explicit stop semantics (graceful teardown, draining, closing underlying listeners/attachments).
5. Lifecycle-gap recovery and terminal failure handling on channel drop.
6. Acceptance evidence and test plan.

Write your specification and implementation recipe to:
collab/1791384000000-architect-stream-s3a-spec.claude-fable-5-1.findings.md
