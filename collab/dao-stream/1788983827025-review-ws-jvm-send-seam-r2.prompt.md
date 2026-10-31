Created-GMT: 2026-09-09 19:57:07 GMT
Created-Local: 2026-09-10 02:57:07 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm the corrected JVM send seam
Role: Routine Review

**Read-only. Print to stdout; write no file.** Review `git diff` plus the new
untracked `test/dao/stream/ws/jvm_test.clj`.

Your P1 was right and I have fixed it. Your P2 was right and I have rewritten
the failure test. Both of your smaller notes are taken. Still the
orchestrator's own code, still nobody else's eyes but yours.

## What changed since your review

- **Failure is now a once-only connection transition**, claimed under the same
  lock that serializes submission (`:failed?` in the connection map). The
  first exceptional completion wins; every other future in the chain finds the
  claim taken. `chain!` refuses to touch the socket once failed, so a dead
  chain is never resumed.
- **No `:pending` reset.** You said a failed connection should stay terminal
  and that the reset sat outside the submission lock where it could overwrite
  a newer tail. Both true; it is gone.
- **`fail!` reports outside the lock**, so an adapter entry that re-enters
  through `close!` finds the flag already set rather than blocking on a lock
  its own thread holds.
- **Order is now error → abort → closed**, as §4.0 prescribed. My abort-first
  deviation was silent and is withdrawn.
- **`close!` on a failed connection answers `nil`, not `false`** — closing
  something already gone is satisfied, not refused — while `send!` answers
  `false`. That distinction is deliberate and commented.
- **The tests are rebuilt around one ordered trace** of host calls *and*
  adapter deposits, so ordering is asserted rather than inferred. The vacuous
  assertion you caught — calling a freshly built recording adapter and
  expecting it to record — is gone. Five deftests now: chaining; three sends
  queued then one exceptional completion producing exactly
  `[:send-text "one"] [:error!] [:abort] [:closed! 1006 …]`; a failed
  connection refusing later sends; a teardown whose `:error!` deposit
  re-enters `close!`; and before-open.

## Verified by me

`clojure -M:test` full: **1441 tests / 165365 assertions / 0 failures 0
errors**. kondo clean on both files.

**Mutation-tested, since you found my last non-vacuity check insufficient:**
removing the once-only claim → **4 failures**; allowing chaining after failure
→ **1 failure**. Restored, green.

## What to judge

1. **Is the once-only claim actually correct** under concurrency? Two threads
   completing exceptionally at once; a completion racing a submission; inline
   `whenComplete` on a thread already holding the lock. Can a failure still be
   reported twice, or now be **lost** — the new risk the claim introduces?
2. **Does anything still block inside a DaoStream operation** (J1)?
3. **Is refusing to chain after failure right**, or should a late `send!`
   reach the socket for some case I have not considered? Is `false` for
   `send!` and `nil` for `close!` the right pair of answers?
4. **Do the five tests pin J1-J4** as the plan states them, and does the
   reentrancy test model the real `ws/deposit!` path faithfully enough to be
   worth its assertions?
5. **Anything else you would not ship.**

## Report

Ordered by severity. If it is clean, say so plainly and say it is ready to
commit. If I have introduced something new, say that first.
