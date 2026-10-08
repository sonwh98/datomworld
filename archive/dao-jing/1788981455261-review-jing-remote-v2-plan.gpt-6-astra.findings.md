Completed-GMT: 2026-09-09 19:28:17 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review | Model: gpt-6-astra

I’ll review the plan against the v2 RPC contract and the existing remote adapter, focusing on waiting policy, cursor discipline, host portability, and preservation of test properties. I’ll keep it read-only and won’t coordinate with the parallel review.

The host poll loop fits the contract’s separation between non-waiting stream operations and interpreter cadence. I’ve found one concrete problem: the timeout test expects a fast request to finish within 50 ms while the single server thread is still handling the 300 ms slow request. I’m also checking how timed-out requests are retired and whether connection-failure tests exercise establishment.

I found a deeper problem in the existing JVM adapter the plan relies on: its send function calls `.join` on `WebSocket.sendText`’s future. That can block inside `rpc/request!`, before the host loop can check its deadline. The plan therefore needs a transport prerequisite to support its non-waiting claim. The proposed server step also ignores `invalid-value` when sending a response; that outcome need not produce a later disconnect event.

**Request changes before implementation.** The host-driver approach is sound, but the plan relies on an operation that currently blocks and leaves several failure paths insufficiently specified.

1. **[P1 — blocking] D1/D3, §5.1: the chosen JVM transport blocks inside `request!`.**

   [`jvm/connect!`’s send function](/Users/sto/workspace/datomworld/src/clj/dao/stream/ws/jvm.clj:156) calls:

   ```clojure
   (.join (.sendText socket message true))
   ```

   The path is `rpc/request!` → `stream/append!` → this function. A pending send therefore parks **inside the operation**, before the composition can check its request deadline. Retrying an unsent envelope through `call-step` reaches the same path.

   This is an existing adapter defect, but it invalidates this plan’s claim that all waiting occurs in the host driver. A request timeout cannot bound that wait.

   **Change:** name a prerequisite repair to the JVM adapter: acknowledge host acceptance without joining, and report subsequent send failure through the adapter’s stream events. Account for overlapping asynchronous sends explicitly. Pin this with a controllable incomplete send future, not a real-network timing test.

2. **[P1 — blocking] D5: the server silently drops response-write failures that do not imply disconnection.**

   The proposed `inbound-step` ignores every response append result. Its justification discusses `full`, `closed`, and `transport-error`, but [`WsHandle.append!`](/Users/sto/workspace/datomworld/src/cljc/dao/stream/ws.cljc:165) also returns `invalid-value`.

   A handler can return a nonportable value—for example, a locally stored payload containing an out-of-range integer. The response is refused, the socket remains open, and the client receives a timeout instead of an informative failure. N7 covers outgoing requests, not this direction. Moreover, `send-result` can return `transport-error` without itself depositing a terminal lifecycle event, so later session retirement is not guaranteed by that result alone.

   **Change:** specify response-domain validation and append-outcome handling. Return a correlated portable error for an unencodable handler result; explicitly retire the attachment or otherwise report loss when delivery fails. Add a test for a nonportable response, and document the portable-domain restriction in both directions.

3. **[P2 — must address] N6, §5.1: timed-out requests can accumulate indefinitely.**

   The plan deliberately leaves each timed-out request in `:outstanding`. The RPC core retains its operation and arguments until a response or loss event removes it. A live connection that repeatedly produces no response therefore retains every timed-out request indefinitely, even though its caller has finished.

   Dropping completed responses and diagnostics does not bound this map.

   **Change:** retire timed-out local bookkeeping while preserving the monotonically increasing allocator. A later response can then be consumed as unsolicited and discarded. If `:unsent` remains, abandon it explicitly before accepting another call—otherwise `request!` retries the old operation while ignoring the new arguments. Test repeated timeouts without responses and subsequent successful use.

   This also needs precise documentation: timeout does not cancel remote execution, and “one call in flight” means one locally awaited call, not necessarily one unfinished server operation.

4. **[P2 — blocking the prescribed test] §5.2: the timeout-recovery test conflicts with S4.**

   The server has one driver thread. After the slow call times out at approximately 50 ms, its handler continues sleeping until approximately 300 ms. An immediately submitted fast call with the same 50 ms deadline normally times out too; it cannot bypass that handler.

   **Change:** use explicit synchronization to release the slow handler after the first timeout, then establish that the server can process the next request. Give the second call sufficient deadline headroom. Pin late-response correlation separately with scripted media so the test does not depend on a narrow timing window.

5. **[P2 — coverage gap] N2/D2, §7: the existing invalid-URL test does not pin establishment waiting.**

   [`network-invalid-url-test`](/Users/sto/workspace/datomworld/test/dao/jing/remote_test.cljc:265) uses port `99999`. That can fail during URL/descriptor validation without attaching or waiting at all. It does not prove the claimed property that a syntactically valid but unreachable endpoint throws at open.

   **Change:** retain that validation test and add a connection-establishment failure test using a valid endpoint. Also pin timeout cleanup with an attachment that never establishes. Returning a content handle immediately would then fail the suite.

The remaining judgments:

- **D1’s interpretation is correct.** DaoStream operations must not wait; a JVM composition may sleep between completed operations. The three conditions are appropriate, but they depend on fixing finding 1.
- **D3 is a reasonable seam.** It performs at most one retry and a budgeted poll, with no internal wait loop. It does contain explicit policy—discarding other completions and diagnostics—which is appropriate only under its single-owner, single-awaited-call precondition. Call it a non-waiting interpreter step, not a pure function: it performs stream operations.
- **D2’s establishment wait is justified**, and minting the traffic cursor before `attach!` is correct. Budget one also avoids hiding an establishment event behind later events in the same poll result.
- **N5 is a real concurrency reduction from v1**, explicitly accepted by the plan. I found no current remote-content consumer requiring simultaneous calls through one handle.
- **The host spellings are appropriate**, provided the `:cljd`-first exclusion covers every new JVM helper and the test fixture helpers as well as the four named constructors. Portable descriptor tests should use a genuinely portable access seam.
- **The existing 13/6 test split is sound.** Contract assertions can remain unchanged; network setup changes transport. The “six tests unmodified apart from loopback” proof should explicitly allow the server start/stop and sleep edits inside `network-file-restart-test`, which §5.2 itself requires.

**§9 needs a more complete durable record.** Its proposed homes are suitable, but also preserve establishment/cleanup behavior, URL restrictions, diagnostic-discard policy, server shutdown semantics, and the single-threaded handler limitation. §5.4 explicitly carries N5–N9, but merely naming `serve-content!` does not preserve S2–S4. Select a concrete home for the ClojureDart rule before deleting the plan; “beside the note or project memory, owner’s call” is still an unresolved destination.

Read-only static review; no files written or suites run.
