Created-GMT: 2026-09-09 20:34:37 GMT
Created-Local: 2026-09-10 03:34:37 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm round — dao.jing.remote plan at r3, new sections only
Role: Routine Review

**Read-only. Print to stdout; write no file.** An adversarial confirm runs in
parallel; do not coordinate with it.

Both of your blocking findings were accepted and neither disputed. Plan:
`collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md` (1182 lines, header r3).

**Scope: the new material only.** Everything else has been cleared by you or
by `deepseek-v4-pro` across two rounds and is not open — the six r1
adversarial findings, your other four, §4.0's fidelity to `3228d0e`,
`retire-call`, the `non-portable-result` substitution, N5, N10, D2's cursor
order, D8, and Phases 1-2's independence from the old seam. Do not reopen
them.

## What is new since you read it

1. **Phase 0b (§4.0b, J7-J9)** — your finding 1 became a *second*
   independently committable transport prerequisite rather than a narrowing
   alone, because the gap is live for `yin.repl` disconnecting while
   connecting.
   - **J7**: `close!` before the socket exists cancels the establishment
     future and marks the connection failed; cancellation completes that
     future exceptionally, so the observer `connect!` already registers
     deposits the terminal `:ws/closed` 1006 once, through `ws/closed!`'s
     guard.
   - **J8**: a socket the JDK hands to a late `onOpen` is aborted on the
     spot — never installed, never sent on, never `sendClose`d — and the
     connection stays failed, so a later `send!` answers `closed` (J6).
   - **J9**: what the seam does **not** promise — `CompletableFuture`
     cancellation does not propagate to the stage producing the socket, so
     the handshake may run to its own completion inside the JDK and a peer
     stalled mid-handshake need not observe EOF at any bounded time. J8 is
     what makes that harmless.
   - N2 and D2 are narrowed accordingly and §5.2 #6's peer-EOF assertion is
     withdrawn.
2. **N11 and `drain-outboxes`** — your finding 2. Every exit of `call!` now
   goes through one `settle!` that drains `:completed` and `:diagnostics`
   before storing state. N11 names four exits that never reached `call-step`,
   the only drain: `request-undeliverable`, `invalid-request`,
   `allocator-error`, `terminal` — and notes `allocation-failure` loses every
   outstanding request into `:completed` (`rpc.cljc:160-166`).
   `retire-call` drains too.
3. **§9's J-invariant homes** — you found my "namespace docstring" claim
   wrong; the Architect then found my correction incomplete (§9 still said
   the old thing). Now three homes for J1-J6, plus J7-J9's.

## What to judge, and only this

1. **Is Phase 0b correct and buildable?** J7's cancellation-completes-
   exceptionally reasoning; J8's abort-on-late-`onOpen` and the race it
   closes; whether the three new seam tests pin them without a network.
2. **Is J9 honest and correctly scoped?** A stated non-guarantee is exactly
   where a plan can hide a defect. Is "J8 makes it harmless" true, or does
   something still depend on the peer noticing?
3. **Does N11 actually bound the bookkeeping** on every exit it names, and is
   `settle!` on every path? Does draining before the throw lose error
   information the caller needs?
4. **Do Phase 0b and N11 disturb anything already cleared** — J1-J6 as
   shipped, N2/D2 after narrowing, `retire-call`?
5. **Is the plan implementable as it stands?**

## Report

Ordered by severity. If clean, say it is ready to promote to `docs/design/`.
