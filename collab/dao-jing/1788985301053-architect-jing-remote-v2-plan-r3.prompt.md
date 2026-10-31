Created-GMT: 2026-09-09 20:21:41 GMT
Created-Local: 2026-09-10 03:21:41 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 95be8c08-c06c-41e6-9891-fa25ed546126 (resumed)
# Task: dao.jing.remote plan — r3, two failure-path findings
Role: Lead System Architect

Both confirm rounds are in and they **split**. Revise the plan in place at
`collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md`;
write no other file.

- `deepseek-v4-pro`: **"promote it."** It verified §4.0 line-by-line against
  `3228d0e`, re-ran the seam's seven tests itself, re-ran its four r1
  interleavings against the shipped seam, and confirmed `retire-call`, the
  `non-portable-result` substitution, and that Phases 1-2 assume nothing about
  the old `.join`. Findings:
  `collab/1788984808027-adversarial-jing-remote-v2-plan-r2.deepseek-v4-pro.findings.md`
- `gpt-6-astra`: **not ready**, two blocking failure-path findings.
  `collab/1788984808027-review-jing-remote-v2-plan-r2.gpt-6-astra.findings.md`

**The split is coverage, not contradiction.** deepseek examined the
reconciliation, `retire-call`'s timeout path, the substitution and the
interleavings; astra examined the establishment path and the *immediate*
request-failure path. Neither looked where the other did. I verified both of
astra's findings against the tree and they are real.

## Blocking — both from astra, both verified by me

1. **§5.2 #6 asserts a cleanup the transport does not perform.** The test
   `connect-times-out-against-a-peer-that-never-completes-the-handshake` says
   "the accepted socket observes EOF within a bound, proving the handle was
   closed before the throw (N2 cleanup)". But in the committed seam,
   `close!` before `onOpen` finds no socket and only stores `:close-request`
   (`jvm.clj:168-176`); nothing cancels the pending `buildAsync` future. The
   fixture deliberately prevents `onOpen`, so the stored request is never
   acted on. `connect-content!` can therefore throw while the handshake stays
   alive, and the EOF assertion has no implementation behind it.

   This is **establishment-lifecycle work the transport still owes**, not a
   defect in the send-chain repair `3228d0e` already made and both reviewers
   cleared. Decide where it belongs: a second commit against
   `dao.stream.ws.jvm` (cancel or abort an in-progress connection, and
   handle the race with a late `onOpen`) named as a prerequisite like Phase 0
   was, or a narrowing of N2's claim to what the transport actually
   guarantees. Say which, and if the former, specify it as you specified
   Phase 0 — including the race.

2. **Immediate request failures bypass the outbox drain.** §5.1 handles
   `request-undeliverable` and `invalid-request` by storing the returned
   state and throwing at once. But `rpc/attempt-unsent`'s undeliverable
   branch calls `append-completion request :reason outcome`
   (`rpc.cljc:216-224`), and `append-completion` carries
   `{:op (:op request) :args (:args request)}` (`:194-196`) — for
   `:jing/put-content` that is `[address payload]`, the whole content
   payload. Invalid requests append diagnostics the same way. Neither path
   reaches `call-step`, which is what drains those vectors.

   So repeated non-portable or undeliverable requests accumulate payloads in
   `:completed` indefinitely. This is the **sibling** of the leak deepseek
   found at r1 — `retire-call` fixed the `:outstanding` path; this is the
   `:completed` one. Drain the completed and diagnostic outboxes on the
   immediate-error exits before persisting state and throwing, and add
   coverage: repeated refusals followed by a successful call, asserting
   bounded bookkeeping and unchanged error information.

## Already applied by me — do not redo, but check I got them right

astra's three reconciliation corrections and deepseek's two nits are fixed in
the file: §7's "Phase 0's four" → seven; J1-J6's homes named accurately as
three (namespace docstring, `client-socket`'s docstring, inline comments)
rather than one; §5.5's claim that Phase 2 #3 is a canary for the `.join`
**withdrawn** (completing a `sendText` does not require the server to
dispatch its handler, so a stalled driver does not hold the send future
open — deepseek endorsed that claim, reading the pre-edit text; astra is
right and it is gone); the "not `swap!`" phrasing reworded, since the code
does call `swap!` inside the lock and atomicity comes from the monitor; and
a note in `jvm_test.clj`'s docstring that a real `abort()` re-enters
`onError` and deposits a second `:ws/error`, which the tests do not
exercise.

## Everything else is cleared by both

The six r1 adversarial findings and astra's other four; §4.0's fidelity to
`3228d0e`; `retire-call` in both branches; the substitution's portability
and correlation; N5, N10, D2's cursor order, D8's reframing; and that
Phases 1-2 carry no assumption about the old seam. Do not reopen them.

## Output

Rewrite in place, structure and numbering stable. Update the header's
Revision/Reconciled block. If either finding is wrong, say so with your
reason.
