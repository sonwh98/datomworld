Completed-GMT: 2026-09-09 20:04:40 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review | Model: gpt-6-astra

I’ll inspect the corrected failure transition and the five tests, focusing on inline callbacks, submission races, and the failed-state return values. I’ll keep this read-only and won’t rerun the suite.

**Not quite ready to commit.** The once-only claim fixes the duplicate-failure and recursive-teardown defects, but two details remain.

1. **[P2 — must fix] Failure reporting can still run under the submission lock.**

   In `chain!`, `.whenComplete` is registered inside `locking connection`. If `next` is already exceptional, its callback runs inline. `claim-failure!` releases its own reentrant acquisition, but the enclosing `chain!` still holds the monitor while `:error!`, `abort`, and `:closed!` run.

   Thus “reports outside the lock” is not true for synchronous failure or already-completed futures. Other submitting threads remain blocked across adapter notification; same-thread reentry works because JVM monitors are reentrant, not because reporting happens outside the lock.

   **Change:** construct and install the successor under the lock, then register its completion observer after leaving the lock. Add a test whose `sendText` returns an already-exceptional future; have the adapter record `(Thread/holdsLock connection)` and assert false.

2. **[P2 — must fix] Failed-send refusal is spelled as backpressure.**

   `chain!` returns `false` once failed. The enclosing WebSocket transport translates `false` to `:dao.stream/full`, which means retryable refusal—not a terminal connection.

   The outer handle usually catches closedness first, but a concurrent append can read its open phase before failure, then reach `chain!` after `:failed?` is claimed. That append reports `full` and RPC retains an unsent request for a connection this seam knows is permanently dead.

   **Change:** return `{:dao.stream/outcome :dao.stream/closed}` for a failed connection; `send-result` already accepts outcome maps. Keep `false` for the genuinely not-yet-open case and `nil` for satisfied close. Update the failed-connection test accordingly.

**Coverage improvement:** restore `close-waits-its-turn-behind-a-pending-send`. It disappeared in this revision. None of the five current tests calls close on a healthy connection with a pending send, so they would pass if that close regressed to overlapping `sendClose` or blocking. The inline-failure case above is also currently absent.

Otherwise:

- The failure claim is correctly serialized with submission. Competing callbacks cannot both win, and removing the tail reset prevents stale callbacks from overwriting newer state.
- Refusing further chaining is correct; there is no useful reason to send on an aborted connection.
- Successful-send serialization remains correct, and no future join, sleep, or explicit completion wait remains.
- The three-queued-send trace now genuinely pins failure propagation and notification ordering.
- The reentrancy test faithfully models the relevant `ws/deposit!` → socket-close path and catches the recursion defect. It does not establish reporting outside the lock, because its future is completed after submission returns.
- I found no new lost-report path under the real adapter’s normal, nonthrowing behavior.

Read-only inspection; no files written or suites rerun.
