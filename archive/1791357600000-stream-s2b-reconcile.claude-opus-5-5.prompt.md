Created-GMT: 2026-10-07 07:18:00 GMT
Created-Local: 2026-10-07 14:18:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: 100c763e-7cdb-415a-9407-1e38d5954b48

# Task: stream-crossmachine-s2b-reconcile
Role: Stream & Network Implementation Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 1791357600000 | Status: active | Rationale: Fix two blocking P1 findings from independent Codex review (collab/1791357300000-stream-s2b-review.gpt-6.1-sol.findings.md).

## Instructions
You are the Stream & Network Implementation Engineer for Datomworld.
Work exclusively inside the dedicated feature worktree:
`/Users/sto/workspace/datomworld-stream-s2` (branch `stream-crossmachine-s2` @ `e9645a2d`).
DO NOT TOUCH any other worktree.

### Objective: Fix Findings 1 & 2 from Codex S2b Review

1. **Finding 1 (P1): Refused attach probes exceed max-outstanding and deadlock retries**:
   - Location: `src/cljc/dao/stream/remote.cljc:395-429`, `:653-657`.
   - Issue: When `send!` refuses a new probe due to `count(:outstanding) + count(:pending) >= max-outstanding`, `send-request!` still unconditionally retains the probe in `:pending`. With `max-outstanding 1` and multiple attachments, pending probes deadlock each other during `retry-pending!` because each retry counts the other pending probe and refuses itself.
   - Solution: Enforce the cap when retaining probes in `:pending` (or reject new attachment when capacity is full), and ensure `retry-pending!` has a clear progress/recovery path without deadlock or unbounded queue growth.
   - Add tests: multiple attachments sharing a capped link, accepting writer vs full writer, and recovery after first accepted answer.

2. **Finding 2 (P1): Prefetch eviction makes ordinary next request permanently blocked**:
   - Location: `src/cljc/dao/stream/remote.cljc:524-558`, `:625-628`.
   - Issue: The primary answer is filed with age `[id 0]`, and prefetched outcomes are installed with later ages `[id i]`. When `max-filed 1` and `budget 2`, the prefetched outcome installed after the primary answer evicts the primary answer before the caller can consume it! On re-ask, the same thing happens, permanently blocking `next 0`.
   - Solution: Limit requested/installed prefetch to the link's retention capacity (`max-filed`), so a fresh request's primary answer is never evicted by its own prefetch! Specifically, `more` should never install so many outcomes that it evicts its own primary answer from the filed set.
   - Add test: single-reader regression retrieving first value with `max-filed 1` and `budget 2`, advancing normally.

3. **Verify**:
   - Run `clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test`.
   - Run `mise exec -- cljstyle check`.
   - Run `clj -M:kondo`.

Report your fixes and verification evidence in:
`collab/1791357600000-stream-s2b-reconcile.claude-opus-5-5.findings.md`
