Completed-GMT: 2026-09-09 20:04:40 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review | Model: gpt-6-astra

I’ll inspect the send-chain repair and its four tests against J1–J4, focusing on inline completion, concurrent sends, failure handling, and close. I’ll keep this read-only and won’t rerun the suite.

**Request changes before commit.** The `.join` defect is removed, and successful-send chaining is correct. Failure handling still needs repair.

1. **[P1 — blocking] `fail!` is not a once-only terminal transition.**

   In [`client-socket`](/Users/sto/workspace/datomworld/src/clj/dao/stream/ws/jvm.clj:110), every chained future registers its own failure callback. Queue two sends, then fail the first: both futures complete exceptionally, so both callbacks abort, report error/closed, and reset `:pending`.

   The real adapter’s `:terminal?` guard suppresses duplicate terminal **deposits**—`:resolution?` serves a different purpose—but it does not suppress repeated `:error!` calls or aborts. One failed chain can therefore emit errors after its terminal event.

   There is also a reentrancy hazard: if depositing `:error!` fails, `ws/deposit!` invokes the socket’s close function. That attaches to the still-exceptional chain, whose `whenComplete` can immediately invoke `fail!` again. The reset occurs too late to prevent this.

   **Change:** claim a connection-level failed/closing transition before invoking abort or adapter entries. Serialize that transition with submission, reject further chaining, and perform failure notification only for the winner. Do not unconditionally replace the shared tail with a successful future: that reset is outside the submission lock and can overwrite a newer tail. A failed connection should remain terminal.

   The reset cannot itself reopen the outer `WsHandle`, but the outer closed flag is insufficient protection against reentrant teardown or an append already past its phase check.

2. **[P2 — must fix] The failure test does not pin the promised ordering or once-only behavior.**

   In [`a-failed-send-is-reported-as-error-then-closed-exactly-once`](/Users/sto/workspace/datomworld/test/dao/stream/ws/jvm_test.clj:82):

   - Only one send is queued, so propagated failure through successor futures is untested.
   - The final assertion directly calls a newly created recording adapter and expects another recorded close. It exercises neither the seam nor the real terminal guard.
   - Checking `:aborted?` after completion does not establish that abort preceded the terminal notification.

   **Change:** queue multiple sends before failing the first; assert that successors are never issued and failure handling occurs once. Record abort and adapter notifications in one ordered trace. Exercise a later close notification through the real `ws/adapter` and inspect deposited events. Add an inline exceptional-completion case and a reentrant teardown case. These can remain deterministic, without network or clock.

The other judgments are positive:

- **J1:** Neither send nor close joins, sleeps, or waits for future completion now. An `:unsent` retry uses this repaired seam too. Close returns while its queued `sendClose` remains pending.
- **J2, successful path:** Holding `locking connection` across reading the tail, constructing the successor, and installing it prevents concurrent submitters from branching off the same tail. Keeping `sendText` out of a retryable `swap!` body is correct. Inline completion is supported by reentrant locking; the problem is the unguarded failure side effects, not `thenCompose` itself.
- **J4:** The before-open test genuinely pins `false` and retained close intent.
- **Public extraction:** `client-socket` is a reasonable host-adapter test seam and is explicitly prescribed by §4.0. Existing consumer signatures remain unchanged.
- **Plan differences:** The implementation aborts **before** `:error!`; §4.0 prescribes error, abort, then closed. Either ordering needs an explicit decision and an ordered test. The promised second-failure/real-`onClose` proof was not implemented.

Static review only; no files written or suites rerun.
