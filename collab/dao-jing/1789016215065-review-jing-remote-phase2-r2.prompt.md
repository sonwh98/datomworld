Created-GMT: 2026-09-10 04:56:55 GMT
Created-Local: 2026-09-10 11:56:55 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm dao.jing.remote Phase 2 — the eighth and ninth exits
Role: Routine Review

**Read-only. Print to stdout; write no file.** Review `git diff` for
`src/cljc/dao/jing/remote.cljc`, `test/dao/jing/remote_test.cljc`,
`docs/design/dao.jing.remote.implementation-plan.md` and
`docs/design/dao.jing.md`. **Ignore `docs/agents/roles/orchestrator.md` and
`docs/agents/team.md`** — the owner's own parallel edits.

Your P1 was right and is fixed. Your P2 was right and is fixed. **And your
finding produced a second one**, described below.

## The eighth exit — your P1

`Thread/sleep` in `call!`'s poll loop is now wrapped: on
`InterruptedException` it `retire-call`s from the loop's **latest** state,
stores through `settle!`, re-asserts the thread's interrupt flag, and throws.
`recur` stays outside the `try`. Proved deterministically without a second
thread — the test sets its own interrupt flag before the call so `sleep`
throws on entry — asserting the allocator advanced, outboxes empty, flag
preserved, and that the next call gets its own answer and never the
interrupted call's late one. **Mutation-checked**: fix reverted → 4 failures
including the wrong-result symptom you predicted.

## The ninth exit — mine, from the implementer's own question

It asked whether `connect-content!`'s establishment-loop sleep should be
closed-and-thrown "for symmetry", reasoning that with no id and no client
there was no P1-class defect. Right about id reuse, wrong about the handle:
both other failure branches call `stream/close!` before throwing, so a raw
interrupt left an attached handle and its socket live while the caller held
nothing — a failure of **N2**, not N11. Now closes the handle, re-asserts the
flag, throws. Mutation-checked: raw sleep → 3 failures.

On observability the implementer **declined to invent a seam**: the handle's
`:closed` phase has no external observer (all local until the constructor
returns; the stalled peer never sees the close cross the wire), so the test
asserts no-escape structurally with a `::returned` sentinel and records why
in a comment. Judge that call.

## Document reconciliation — mine, judge it

I updated, and the implementer did not touch: **N11** now names the interrupt
exit as the eighth with why it was a correctness defect; **N2** now says all
three failure cases close the handle first and notes the third is a failure of
that clause, not N11; **§5.1** says eight exits, not seven, with the interrupt
exit listed last and marked as found in review; **`dao.jing.md`** gains
interruption in both the driver's exit list and the failed-open sentence.

## Verified by me

clj **1457 / 165515**, cljs **1359 / 35025** (remote-test present), cljd
**+1313**, demo 212 files 0 warnings — all zero failures. Closure greps
**0** in all three code files; `reset! (:rpc` appears **once**, inside
`settle!`.

## What to judge

1. **Is the eighth-exit fix correct** — retiring from the loop's latest state
   rather than the client's stored one, and is there any remaining path out
   of `call!` that skips `settle!`? A tenth, if one exists.
2. **Is the ninth-exit fix correct**, and is the no-seam judgement right?
3. **Do my document edits describe the code**, or have I overstated?
4. **Anything the two fixes broke** in what you already cleared.

## Report

Ordered by severity. If clean, say so plainly and say it is ready to commit.
