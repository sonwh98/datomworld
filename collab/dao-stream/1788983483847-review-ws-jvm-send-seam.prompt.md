Created-GMT: 2026-09-09 19:51:23 GMT
Created-Local: 2026-09-10 02:51:23 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: review the repair of the JVM WebSocket send seam
Role: Routine Review

**Read-only. Print to stdout; write no file.** Review `git diff`.

**You found this defect.** Reviewing the `dao.jing.remote` plan you reported
that `src/clj/dao/stream/ws/jvm.clj:157` joined `sendText`'s future, so a
pending send parked inside `stream/append!` and no interpreter deadline could
bound it. The owner ordered it fixed first, ahead of the migration. This is
that fix — Phase 0 of the r2 plan, standing alone.

**The implementer is the orchestrator (`claude-opus-5`, Stream & Network
primary), not a delegate.** You are the independent family here; judge it as
you would any diff, and harder if anything, because nobody else has read it.

Plan section: `collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md`
§2.0 (invariants J1-J4) and §4.0 (Phase 0's build/delete/prove lists).

## Verified by me — spend your budget on the code, not on reproducing green

`clojure -M:test` full: **1440 tests / 165365 assertions / 0 failures 0
errors** (was 1436/165344 — the four new tests, 16 assertions, plus the
five-assertion delta from another already-committed change).
`clj -M:kondo` clean on both files.

**Non-vacuity, checked rather than assumed**: I defeated the chaining in a
scratch copy (issuing `sendText` directly, ignoring the pending future) and
re-ran — **4 failures**, including `expected ["first"], actual ["first"
"second"]`. Then restored. Note that reverting to the *original* `.join`
would make these tests **hang** rather than fail, since the test deliberately
holds the future open; that is why the plan asked for a controllable
incomplete future instead of a network timing test.

## What to judge

1. **Does the repair actually discharge the defect you found?** `send!` must
   return as soon as the host accepts and never join, park or sleep (J1). Is
   there any remaining path — including the retry of an `:unsent` envelope,
   and `close!` — that can block inside a DaoStream operation?
2. **The chaining under `locking` (J2).** I used `locking connection` plus
   `thenCompose`, on the reasoning that a `swap!` retry would issue a second
   `sendText` for one message. Is the lock scope right? Can two threads
   interleave to issue overlapping sends, lose a message, or install a
   `:pending` that is not the one they chained? Is there a deadlock or a
   lock-held-across-a-callback hazard — note `whenComplete` may run inline on
   the completing thread.
3. **Failure reporting (J3).** On exceptional completion I abort the socket,
   deposit `:error!`, then `:closed!` 1006, then reset `:pending` to a
   completed future. Is that order right? Can the reset resurrect a chain that
   should stay dead? Can a failure be reported twice, or lost — and is relying
   on `ws.cljc`'s `:resolution?` guard for once-only terminal deposit correct
   here?
4. **The tests.** Four, no network, no clock, against a reified
   `java.net.http.WebSocket`. Do they pin J1-J4 as the plan states them, or
   do they pin the implementation's shape? Is anything asserted that would
   pass under a wrong implementation, or any J left unpinned?
5. **Blast radius.** `ws.jvm` is required by `yin/repl/host/jvm.clj`,
   `slice_test.clj`, `slice_peer.cljc` and `yin/repl/host/jvm_test.clj`.
   No public signature changed and `client-socket` is newly public. Is the
   extraction to a public fn right, or should it stay private with the tests
   reaching it another way?
6. **Anything the plan's §4.0 asked for that I did not do**, or did
   differently without saying so.

## Report

Ordered by severity, each finding with the concrete failure it would cause.
Distinguish blocking from improvement. If it is clean, say so plainly and say
it is ready to commit.
