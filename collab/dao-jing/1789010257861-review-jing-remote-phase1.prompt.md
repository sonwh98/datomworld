Created-GMT: 2026-09-10 03:17:37 GMT
Created-Local: 2026-09-10 10:17:37 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: review dao.jing.remote Phase 1 — the portable core
Role: Routine Review

**Read-only. Print to stdout; write no file.** Review `git diff` for
`src/cljc/dao/jing/remote.cljc` and `test/dao/jing/remote_test.cljc` only —
the third dirty file, `test/dao/stream/ws/jvm_test.clj`, is an unrelated
uncommitted docstring note.

Implementer: `glm-5.3`, independent of you and of the plan's author.
Plan (promoted): `docs/design/dao.jing.remote.implementation-plan.md` **§4.1**
Report: `collab/1789007994328-stream-jing-remote-phase1.glm-5.3.findings.md`

**Since you last saw the plan**: the owner dropped Phase 0b and narrowed N2 to
what the shipped transport does; J7-J9 are gone; the establishment-cancel gap
is recorded as owed by `dao.stream.ws.jvm` in §8 with a home in §9. That
is settled and not open.

## Verified by me — spend your budget on the code

All lanes, my runs: clj **1448 / 165465 / 0 failures 0 errors** (from
1443/165370); cljs **1350 / 35016 / 0 failures 0 errors** with
`Testing dao.jing.remote-test` present; cljd **all passed +1304** (from
+1299); demo 212 files 0 warnings. **+5 tests / +95 assertions on both
counting lanes**, matching the hand-counted total of the five new deftests —
so no existing test changed behaviour and the phase is neutral by
measurement, not assertion.

## The disclosed deviation — judge it

Three of §4.1's six new requires were added (`dao.stream.apply`, `.rpc`,
`.ws`); three were not (`dao.stream`, `.rpc.ws`, `.transit`). The stated
reasons: `.rpc.ws` cannot take its natural alias while v1's
`[dao.stream.rpc.ws :as rpc-ws]` stands (demonstrated:
`Alias rpc-ws already exists`), and the other two have no Phase 1 call site,
so unused requires would break the kondo lane whose baseline is clean. All
three are §10 end-state requires that land with Phase 2.
Is that right, and does it leave Phase 2 anything it cannot do?

(Note: the report's own heading miscounts this as "two were" added while its
body correctly lists three. The work is right; the label is not.)

## What to judge

1. **N11** — the invariant with the most history here. Does test 5 pin it on
   the **stored state** (`(count (:completed state))` = 0 after every
   refusal exit, never `n`), and does `drain-outboxes` actually reach the
   four exits the plan names, including `allocation-failure`'s loss of every
   outstanding request into `:completed` (`rpc.cljc:160-166`)?
2. **`retire-call`** — does it drain its own abandonment completion *on
   return*, and does test 3 pin late correlation with scripted media and no
   clock: id 0 retired, its response appended anyway, id 1 getting id 1's
   value and id 0's surfacing nowhere?
3. **`call-step`** — genuinely non-waiting on every path, including the
   `:unsent` retry? It is the seed of the blocking driver's loop, not of a
   stepped client; does the code respect that or does it smuggle policy?
4. **`await-established-step`** — lifecycle-only: does a response element
   before `/established` get consumed as a diagnostic without establishing?
5. **The implementer's own discovery**: with a call in flight, a bare
   `/detached` yields `:done` plus a `:reason` completion (N9's loss) rather
   than `:terminal`. It pinned **both** shapes. Is that the right reading of
   the step, and is the plan's text now wrong anywhere about it?
6. **Neutrality** — anything in the diff that changes existing behaviour, or
   any new function that a later phase will have to rewrite rather than
   call?

## Report

Ordered by severity, each with the concrete failure it would cause.
Distinguish blocking from improvement. If clean, say so plainly and say it is
ready to commit.
