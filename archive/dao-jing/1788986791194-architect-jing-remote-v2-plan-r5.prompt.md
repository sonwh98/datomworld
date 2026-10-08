Created-GMT: 2026-09-09 20:46:31 GMT
Created-Local: 2026-09-10 03:46:31 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 95be8c08-c06c-41e6-9891-fa25ed546126 (resumed)
# Task: dao.jing.remote plan — r5, drop Phase 0b and narrow N2
Role: Lead System Architect

**The owner has decided: drop Phase 0b entirely, and narrow N2 to what the
transport guarantees today.** This supersedes the r4 brief you were given.

**Read this first about the file's state.** I issued r4 and then stopped it
mid-run when the decision came. The plan's header already reads r4 and edits
were applied before the stop, so the file may be in a partially-revised
state. Check its coherence as you go — §2.0, §4.0b, N2/D2 and §5.2 are the
regions r4 touched — and say in your summary if you found anything
half-applied.

Revise in place at
`collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md`.
Write no other file.

## The decision, and its reason

Phase 0b began as a two-line narrowing question and grew, across three review
rounds, into a second transport project inside a migration plan: an atomicity
transition in `onOpen`, a `send!` change, cancellation ordering, a racy test,
a shipped test migrated, and a residual that is unbounded anyway. Every
finding in it was real — this is not a judgment that the reviewers were
wrong. It is a judgment that **a migration plan is the wrong place to rebuild
the JVM establishment lifecycle**, and that Phase 0 already fixed the defect
that actually blocked this migration.

## What to do

1. **Delete §4.0b and J7-J9.** Phase 0 (`3228d0e`, done) is the only
   transport prerequisite. §2.0's table ends at J6.
2. **Narrow N2 and D2 to what the shipped transport does**, with no
   aspiration mixed in. As committed: `connect-content!` waits for
   `/established`, throws on a terminal lifecycle or on
   `:connect-timeout-ms`, and calls `stream/close!` on the handle before
   throwing — which flips the handle's phase so nothing escapes to the
   caller. What it does **not** do: cancel the establishment. `close!` before
   `onOpen` finds no socket and only records a `:close-request`
   (`jvm.clj:168-176`); nothing touches the pending `buildAsync`. So a peer
   that accepts TCP and stalls the upgrade is not torn down, need not observe
   EOF at any bounded time, and the JDK connection persists — the
   `HttpClient` `connect!` builds carries no connect or request timeout.
   State that as a limit in N2's own words, not as a promise deferred.
3. **§5.2 #6** keeps only what is assertable: `connect-content!` throws
   `{:url :timeout-ms}` against a `ServerSocket` that accepts and never
   writes, and no handle escapes. Drop every cleanup claim. **Add that the
   test closes its own accepted socket**, since nothing else will.
4. **The shipped `before-open-send-answers-full` stays exactly as it is** —
   it needed migrating only because Phase 0b would have made `close!` cancel.
   Remove any instruction to touch it.
5. **The gap does not vanish because we stopped planning it, and the plan
   must not let it vanish.** It is live for `yin.repl` disconnecting while
   connecting — that was Phase 0b's original justification and it still
   holds. Record it in **both** §8 (left owing, by namespace: it belongs to
   `dao.stream.ws.jvm`, not to `dao.jing.remote`) and §9 (what this plan
   carries that no other document does), with a durable home named — my
   reading is `dao.stream.ws.md`'s open items, since it is a transport
   property, but say what you think. Carry the substance a successor needs:
   that `close!` before open only records a request; that cancelling
   `buildAsync` would not reach the stage producing the socket; that a
   guarded check-and-install in `onOpen` is required to close the race, with
   the abort outside the lock; and that the residual is unbounded and
   caller-owned. **This is the §9 rule doing its job**: knowledge earned by
   three review rounds must outlive the section that earned it.

## Unchanged and closed

N11 and `drain-outboxes`; J1-J6 and §4.0's record of `3228d0e`; §9's homes
for J1-J6; `retire-call`; the `non-portable-result` substitution; N5, N10,
D2's cursor ordering, D8; Phases 1 and 2 entire. Do not reopen any of them.
If dropping Phase 0b leaves a dangling reference anywhere in those sections,
fix the reference only.

## Output

Rewrite in place; numbering stable except for the deleted 0b; update the
header to r5 and say what the revision did. If you think dropping 0b is
wrong, say so plainly with your reason — the decision stands either way, but
it should stand with your objection recorded.
