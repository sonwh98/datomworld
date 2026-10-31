Completed-GMT: 2026-09-09 20:37:51 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (confirm, r3 new sections) | Model: gpt-6-astra

I’ll review only Phase 0b, J9’s limit, N11’s cleanup paths, and their documentation homes. I’ll keep this read-only and won’t revisit the cleared decisions.

N11 now covers the named exits and preserves error details outside the drained state. Phase 0b still has two concurrency gaps: `onOpen`’s check and install are not specified as atomic with close, and cancelling under the monitor can run the establishment observer inline under that same monitor.

**Three changes remain before promotion.** All concern the new Phase 0b material.

1. **P1 — §4.0b: the close/onOpen decision must be atomic.** The plan puts close’s updates under `locking connection`, but specifies only “checks `:close-request` before installing” for `onOpen`. That permits: `onOpen` reads no close request → close marks failed and cancels → `onOpen` installs the socket and requests input. J8’s abort is missed.

   Specify that **both** close’s socket-present/absent decision and `onOpen`’s check-and-install use the same monitor. Perform cancellation and rejected-socket abort outside it. Add a deterministic race pin; test #9’s sequential close-then-open cannot detect this interleaving.

2. **P2 — §4.0b: cancellation under the monitor recreates the inline-observer hazard.** `.cancel` completes the future exceptionally and can execute its already-registered `whenComplete` observer inline. As prescribed, `:closed!` therefore deposits while close still holds `connection`’s monitor. This recreates the monitor-held-across-deposit problem Phase 0 removed; moving observer *registration* elsewhere does not prevent it.

   Capture the future and mark the connection terminal under the monitor, then cancel after releasing it. Extend test #8 to assert `Thread/holdsLock` is false inside the establishment deposit, including teardown reentry.

3. **P2 — §4.0b’s build/prove lists omit an existing test that needs migration.** `before-open-send-answers-full` constructs a connection with `:future nil` and calls close. The prescribed unconditional `.cancel` will throw. Its “nothing is deposited” assertion also describes the old unwatched setup.

   Explicitly update this test to supply a watched establishment future and assert the new close behavior, while retaining its healthy-before-open `full` assertion.

**N11 is discharged:** the listed immediate refusals, timeout, completion and terminal exits all route through `settle!`; `retire-call` drains its abandonment completion. Draining does not erase the separately retained outcome, diagnostic or completion used to construct the caller’s answer.

**J9 states the residual honestly**, including potentially lingering JDK handshake resources. “Harmless” should mean unable to revive the local attachment, conditional on fixing J8’s race—not bounded resource cleanup. The narrowed N2/D2 no longer depend on peer EOF. The §9 documentation homes are appropriate.

The plan is **not ready to promote yet**; the remaining corrections are confined to Phase 0b.
