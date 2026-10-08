Completed-GMT: 2026-09-09 20:15:50 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (confirm round, plan r2 + reconciliation) | Model: gpt-6-astra

I’ll check the reconciled Phase 0 record and trace the other four fixes through the decisions, build lists, tests, and durable documentation requirements. I’ll keep this read-only and independent of the parallel review.

The send-seam record matches the committed repair, and the timeout-retirement and nonportable-response fixes are present in the build lists. I found one remaining lifecycle mismatch: before `onOpen`, the committed seam only records a close request. It does not cancel `buildAsync`, so the proposed stalled-handshake test cannot rely on `stream/close!` to produce EOF at the peer.

**Not ready to promote yet.** The Phase 0 reconciliation is substantively accurate, but the remaining plan has one unmet transport prerequisite and one incomplete bookkeeping rule.

1. **[P1 — blocking] N2, §5.2 #6: closing before establishment does not cancel the handshake.**

   The stalled-handshake test requires the accepted TCP socket to observe EOF after `connect-content!` times out and calls `stream/close!`.

   In the committed seam, [close before `onOpen`](/Users/sto/workspace/datomworld/src/clj/dao/stream/ws/jvm.clj:168) only stores `:close-request`. It neither cancels the pending `buildAsync` future nor closes the connection being established. The request is acted upon only if `onOpen` eventually runs—which the proposed fixture deliberately prevents.

   Thus the constructor can throw while leaving the handshake alive, and the EOF assertion has no implementation supporting it.

   **Change:** explicitly add cancellation/cleanup of an in-progress connection, including the race with `onOpen`, and pin it with this test. This is additional establishment-lifecycle work, not a defect in the send-chain repair already cleared.

2. **[P2 — must address] §5.1: immediate request failures bypass outbox cleanup.**

   The plan handles `request-undeliverable` and `invalid-request` by storing the returned state and throwing immediately. But [`rpc/attempt-unsent`](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:198) appends a completion containing the request arguments on refusal; invalid requests append diagnostics. Neither path reaches `call-step`, which drains those vectors.

   Repeated nonportable requests can therefore accumulate their payloads in `:completed` indefinitely. `retire-call` fixes timed-out outstanding entries, but not this separate retention path.

   **Change:** drain the completed/diagnostic outboxes on immediate-error exits before persisting state and throwing. Add repeated-refusal coverage followed by a successful call, asserting bounded/empty bookkeeping and unchanged error information.

The original findings otherwise are addressed:

- **Phase 0:** J1–J6 and §4.0 accurately describe the shipped chain, once-only claim, observer placement, terminal refusal, and seven tests.
- **D5:** The correlated nonportable-result substitution and non-ok append handling are present in the actual proposed code, not merely the rationale.
- **D3/N6:** Retirement removes outstanding entries, abandons unsent envelopes, and preserves the allocator. The scripted late-response test exercises the right distinction.
- **Timeout recovery:** The latch and longer recovery deadline resolve the original single-server-thread contradiction.
- **Establishment:** Validation is now correctly distinguished from establishment failure. The new cleanup test is useful precisely because it exposes finding 1.
- **N5:** “One locally awaited call” correctly permits remote execution to continue after timeout. The retry path composes with Phase 0’s terminal `closed` outcome.

Three nonblocking reconciliation corrections remain:

- §7 still says **“Phase 0’s four”**; it shipped seven tests.
- §4.0/§9 claim J1–J6 all live in the **namespace docstring**. They are actually distributed across that docstring, `client-socket`’s docstring/comments, and tests. Name those existing homes accurately.
- Remove §5.5’s claim that the latch-server test necessarily detects the old `.join`. Completing `sendText` does not require dispatching the server handler. The controlled incomplete-future tests are the reliable regression pin.

§9’s expanded operational-knowledge list has appropriate homes. No further migration redesign is needed, but the two failure-path fixes above should be explicit before promotion.

Read-only review; no files written or suites run.
