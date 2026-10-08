Created-GMT: 2026-09-09 20:13:28 GMT
Created-Local: 2026-09-10 03:13:28 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm round — the dao.jing.remote plan at r2
Role: Routine Review

**Read-only. Print to stdout; write no file.** An adversarial confirm runs in
parallel; do not coordinate with it.

You reviewed this plan at r1 and requested changes on five counts. The
Architect revised it; **Phase 0 has since been implemented, reviewed by you
across three rounds, and committed as `3228d0e`**; and I have reconciled the
plan's §2.0 and §4.0 against what actually shipped. Plan: `collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md` (956 lines,
header marked r2 + Reconciled).

## How your five findings were settled

1. **The `.join` (your P1).** Became Phase 0, committed. You cleared the
   implementation at round 3: "clean and ready to commit."
2. **The server dropping `invalid-value` (your P1).** D5's inbound step now
   substitutes a correlated `:dao.jing.remote/non-portable-result` and
   retires the attachment on any non-ok append, with a test.
3. **Timed-out requests accumulating (your P2).** A new portable
   `retire-call` drops the outstanding entry and abandons a still-unsent
   envelope; the docs now say a timeout retires the local wait, not the
   remote execution.
4. **The unwritable timeout test (your P2).** Rebuilt around a latch the test
   owns; late correlation pinned separately at step level with scripted media.
5. **`network-invalid-url-test` not pinning D2 (your P2).** Three
   establishment tests replace it.

## What I changed after Phase 0 landed — judge this specifically

§2.0's invariant table and §4.0 were written **before** your three
implementation rounds, so they described the draft, not the commit. I
rewrote them:

- **J5 added**: the failure report is once per connection and happens outside
  the submission monitor, with the reasons you gave (every future in a chain
  completes exceptionally when its predecessor does; an already-exceptional
  future runs its observer inline).
- **J6 added**: a failed connection stays terminal and answers `closed`, not
  the retryable `full`, for the append-past-the-phase-check case you
  constructed.
- **J4 extended**: `close!` answers `nil` whatever the state.
- **§4.0 rewritten to describe the shipped code**, marked **DONE, `3228d0e`**,
  with the two divergences from the draft called out in block quotes — the
  observer registered inside the lock plus the `:pending` reset, and the
  reliance on the handle's phase to catch a late `send!`.
- Its Prove list is now the seven tests that exist, with the mutation results
  and the note that reverting the `.join` would hang rather than fail.

## What to judge

1. **Does §2.0/§4.0 now describe `3228d0e` accurately?** You have read that
   code three times; this is the text a successor will rely on after the plan
   is deleted. Anything misdescribed, overstated, or missing?
2. **Are your other four findings actually discharged in the plan's text**, or
   discharged in prose while the phase lists still say the old thing?
3. **Phases 1 and 2 are unchanged since your r1 review** except where those
   findings touched them. Does anything in them now contradict the shipped
   Phase 0 — the `call-step` retry path, the `:unsent` handling, N5's "one
   call in flight", the establishment wait — given that the seam beneath them
   changed?
4. **§9's carried-knowledge list** now names J1–J6. Is it complete against
   what shipped, and are the homes right?
5. **Is it implementable as it stands?** If yes, say so plainly.

## Report

Ordered by severity. If clean, say it is ready to promote to `docs/design/`.
