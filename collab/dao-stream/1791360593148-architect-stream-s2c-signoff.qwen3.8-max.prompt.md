Created-GMT: 2026-10-07 08:10:00 GMT
Created-Local: 2026-10-07 15:10:00 Asia/Ho_Chi_Minh
Coding-Agent: cmd
Model: qwen/qwen3.8-max

# Task: stream-crossmachine-s2c-architect-signoff
Role: Lead System Architect
Implementers:
- Model: qwen/qwen3.8-max | Assigned: 1791360593148 | Status: active | Rationale: Architectural review and sign-off for Slice S2c (request liveness and channel expiry). Dispatched via cmd per owner routing directive conserving Claude/Codex quotas.

## Architecture Review & Sign-Off Request
Review the complete, verified, and reviewer-accepted diff for Slice S2c in `/Users/sto/workspace/datomworld-stream-s2`, branch `stream-crossmachine-s2` @ `91ab84f7`.

### Context & Evidence
1. **Specification**:
   - `docs/design/dao.stream.remote.md` (§2.4 Channel loss and Expiry)
   - `docs/design/dao.stream.ws.md`
   - Initial S2 architectural spec: `collab/1791353752491-architect-stream-s2-spec.claude-fable-5-1.findings.md`
2. **Author Implementation Report**:
   - Claude Opus 5-5: `collab/1791358000000-stream-s2c.claude-opus-5-5.findings.md`
3. **Independent Code Review**:
   - Qwen 3.8 Max: `collab/1791359934597-stream-s2c-review.qwen3.8-max.findings.md`
   - Verdict: **ACCEPT** with non-blocking P3/P4 findings (P3 link-step! with nil `now` when deadline already set; P3 requests existing before first timed step).
4. **Local Verification**:
   - `clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test`: 83 tests, 609 assertions, 0 failures, 0 errors.
   - Code formatting & linting: cljstyle clean, clj-kondo clean.

### Architectural Invariants to Rule On:
1. **Expiry Contract & Behavior**:
   - Does `:step` ordering (drain incoming first, then check least expired request) uphold the remote stream protocol without dropping valid concurrent answers?
   - Is whole-channel loss (`channel-loss!`) with `append-unknown` and non-retryable `channel-gone` the proper architectural response to request deadline expiry?
2. **Dial Teardown**:
   - Is `dial-step!` closing the underlying WebSocket handle upon channel expiration architecturally sound and idempotent?
3. **Findings Ruling**:
   - Rule on finding 1 (`(step cd nil)` guard) and finding 2 (requests prior to first timed step). Are these acceptable as follow-ups for S3a driver composition?
4. **Sign-off Verdict**:
   - Explicitly grant or withhold formal Lead System Architect sign-off for staging and committing Slice S2c (`feat(dao.stream.remote): request deadlines, channel-expired and link step (cross-machine stream slice S2c)`).

Save your sign-off findings report to:
`collab/1791360593148-architect-stream-s2c-signoff.qwen3.8-max.findings.md`
