You are the Stream & Network Implementation Engineer for Datomworld.
Work exclusively inside the dedicated worktree:
`/Users/sto/workspace/datomworld-stream-s2` (branch `stream-crossmachine-s2`).

The Lead System Architect has reviewed Slice S2d and issued findings:
`collab/1791380000000-architect-stream-s2d-signoff.gpt-6-astra.findings.md`

Sign-Off is WITHHELD pending 3 items:
1. **B1: Browser close codes 1008/1009/1013 rejected by WHATWG DOM WebSocket API**:
   - The DOM WebSocket `close(code, reason)` API only accepts `1000` or `3000-4999`. Any other code throws `InvalidAccessError`.
   - In `src/cljs/dao/stream/ws/browser.cljs`, wrap `:close!` or map close codes: when a close code outside `#{1000}` and `[3000, 4999]` is requested (e.g. 1008, 1009, 1013), fall back to `1000` with the reason string, or close without code `(.close ^js socket)`, so that the host DOM socket actually closes and generates the terminal close event rather than throwing `InvalidAccessError` caught silently by `invoke-close!`.
   - Update tests in `test/dao/stream/ws/browser_test.cljs` with DOM close-code validation verifying that overflow triggers real closure.
   - Update documentation in `docs/design/dao.stream.ws.md` regarding browser host wire codes.

2. **B2: JVM WebSocket client sendClose cannot send 1009**:
   - `java.net.http.WebSocket.sendClose` rejects 1009 (`IllegalArgumentException`).
   - In `src/clj/dao/stream/ws/jvm.clj`, handle fragment reassembly overflow cleanly: when reassembly exceeds `:ws/max-frame-bytes`, abort the socket with `.abort` or map close code appropriately so teardown does not fail exceptionally with 1006. Document and test this behavior.

3. **Same-tick adoption assertion in `test/dao/stream/ws_project_test.cljc`**:
   - In `adoption-isolated`, assert `:answered` immediately after tick 2 (when the bad offer was handled), before stepping tick 3.

Run tests, ensure cljstyle check is clean, and write your reconciliation findings report to:
`collab/1791381000000-stream-s2d-reconcile.claude.findings.md`
