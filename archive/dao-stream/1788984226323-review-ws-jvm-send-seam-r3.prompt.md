Created-GMT: 2026-09-09 20:03:46 GMT
Created-Local: 2026-09-10 03:03:46 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm round 3 — the JVM send seam
Role: Routine Review

**Read-only. Print to stdout; write no file.** Review `git diff` plus the
untracked `test/dao/stream/ws/jvm_test.clj`.

Both of your P2s were right and both are fixed. Your coverage note was right
too — I had dropped a test in my own rewrite. Still the orchestrator's code,
still only your eyes on it.

## What changed since round 2

1. **The completion observer is registered outside the lock.** `chain!` now
   builds and installs the successor under `locking connection`, returns it,
   and registers `.whenComplete` after leaving the monitor. Your case — an
   already-exceptional future running its observer inline while the enclosing
   `chain!` still held the lock — was real; my "reports outside the lock"
   claim held only for asynchronous completion, and same-thread reentry
   worked because monitors are reentrant, not because I had arranged it.
2. **A failed connection answers `closed`, not `false`.** You are right that
   `false` means `:dao.stream/full` (`ws.cljc:101`) — retryable backpressure
   for a socket that is permanently gone, leaving RPC holding an unsent
   request. `send!` now answers `{:dao.stream/outcome :dao.stream/closed}`
   (`send-result` accepts outcome maps, `ws.cljc:102`). `false` remains for
   genuinely-not-yet-open; `nil` remains for satisfied close.
3. **`close-waits-its-turn-behind-a-pending-send` is restored.** It vanished
   in my round-2 rewrite and nothing else covered a close on a *healthy*
   connection with a pending send.
4. **New:** `an-inline-failure-reports-without-holding-the-submission-lock` —
   a `sendText` returning an already-exceptional future, with the adapter
   recording `(Thread/holdsLock connection)` inside both deposits and the
   test asserting **false** for each.

Seven deftests, 21 assertions.

## Verified by me

`clojure -M:test` full: **1443 tests / 165370 assertions / 0 failures 0
errors**. kondo clean on both files.

**One mutant per finding**, both fixes proven to bite:
- register `whenComplete` inside the lock again → **1 failure**, the inline
  test.
- answer `false` instead of `closed` → **1 failure**, the failed-connection
  test.
Restored, green.

## What to judge

1. **Is the observer-outside-the-lock arrangement correct**, or have I moved
   the hazard rather than removed it? Between installing `:pending` and
   registering the observer there is now a window in which the future may
   already have completed exceptionally with no observer attached — does
   `whenComplete` on an already-completed future still fire (I believe it
   does, and the new test depends on it), and is there any path where a
   failure is now **lost** or reported by the wrong submitter?
2. **Is `closed` the right outcome** for a failed connection, and does
   answering it from `send!` compose correctly with `ws.cljc`'s
   `send-result` and the handle's own phase?
3. **Do the seven tests now cover J1-J4** without pinning implementation
   shape, and is `Thread/holdsLock` a sound way to assert the lock property?
4. **Anything else you would not ship**, including anything you have already
   raised that I have handled incompletely.

## Report

Ordered by severity. If it is clean, say so plainly and say it is ready to
commit.
