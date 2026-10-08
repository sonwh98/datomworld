Completed-GMT: 2026-09-04 07:50:00 GMT
Completed-Local: 2026-09-04 14:50:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: fde8b517-9c3f-48c6-a4d9-c455489fbebc

# Phase 5 R3/R4 follow-on LOW corrections — architect verification round 4

SIGN-OFF: GRANTED

Both implementation findings are closed. `abandon-unsent-now` now publishes
the old adapter unconditionally before replacement while abandoning only an
actual unsent envelope, preserving exactly-once movement through the RPC,
adapter, and driver outboxes. A successful fresh connection also reports the
exact number of queued remote lines before `attach-remote` clears them; failed
opens retain the queue and same-URL reattachment behavior is unchanged.

One LOW test-coverage weakness remained: the completion-publication regression
seeded its completion before `poll-remote`, so the earlier conditional code
would also pass. The reviewer recommended creating the completion after polling
by processing `(disconnect)` and a different-URL `(connect ...)` in the same
tick, then asserting the `operator-disconnect` completion is published.

The unrelated CLJD test compile failure comes from unchanged reader
conditionals in `test/dao/stream/ws_test.cljc:77,139` which omit a CLJD
branch. It does not affect sign-off on this pure `.cljc` delta.
