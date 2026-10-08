Completed-GMT: 2026-09-09 20:21:41 GMT
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72
Role: Adversarial Review (confirm round) | Model: deepseek-v4-pro

Phase 0 verified against the committed tree (`3228d0e`): I read `src/clj/dao/stream/ws/jvm.clj` and `test/dao/stream/ws/jvm_test.clj` in full, and re-ran the seam's test suite — **7 tests, 21 assertions, 0 failures**, matching §4.0's claim exactly. Here is the confirm review, stdout only.

---

# Adversarial confirm — dao.jing.remote plan, r2

## Verdict

**§4.0 describes `3228d0e` faithfully; the plan is ready to promote.** The six r1 findings are settled correctly, the reconciliation is honest (it does not flatter the commit), and the five hunts surface no new defect — only two wording/fidelity nits, neither of which requires a plan change.

## Hunt 1 — the reconciliation: faithful, with two nits

I checked §4.0 line-by-line against the code. Everything material matches:

- `client-socket` is a public fn (`jvm.clj:98`) and `connect!` calls it (`:239`). ✓
- `:send!` → `false` with no socket; otherwise `chain!` uses `thenCompose` behind `:pending` and returns at once (`:156-167`, `:133-155`). ✓
- The chain is built and installed under `locking connection`; the completion observer is registered **after** the lock (`:149-154`); failure is claimed once under the same lock via `claim-failure!` (`:117-122`) and the deposits (`:error!` → `.abort` → `:closed!` 1006) run outside it (`:123-132`). ✓
- `ws.cljc:285-294` is indeed `closed!`, whose `terminal!` guard plus `:resolution?` make the terminal once-only. ✓
- J6: `chain!` returns `::failed` on a failed connection, `:send!` maps it to `{:dao.stream/outcome :dao.stream/closed}` (`:156-166`). ✓
- `:close!` chains `sendClose` and answers `nil` whatever the state (`:168-173`). ✓
- No `.join` anywhere in the file. ✓
- The docstring's "nothing here joins, parks, or sleeps" matches the code. ✓

Two nits, both non-blocking:

1. **"The tail is read … installed under `(locking connection …)` — not `swap!`."** The code *does* call `swap!` (`:147`), inside the lock. The substance is right — atomicity comes from `locking`, not from `swap!`'s retry — but the phrase "not `swap!`" reads as if `swap!` is absent. One reword (`"under `locking` rather than an unguarded `swap!`"`) removes the ambiguity.
2. **The "exactly once" trace is the seam's, not the real socket's.** The scripted socket's `abort` only records `[:abort]` (`jvm_test.clj:41`), whereas the real `WebSocket.abort()` invokes the listener's `onError`, which re-enters the adapter's `:error!` and deposits a second `:ws/error`. That second deposit is a harmless diagnostic (`:ws/error` → `:dao.stream.apply/diagnostic`, dropped by the blocking client), and the terminal stays once-only, so J5's claim is true for the terminal. But the test does not exercise the abort→onError re-entry (test 6 models a different re-entry, `:error!`→`close!`). Worth one line of awareness in the test docstring, not a change.

## Hunt 2 — J5/J6 don't break anything cleared at r1

Re-ran the four interleavings against the shipped seam:

- **Socket drops mid-call:** the send is accepted (returns at once), the response never arrives, `fail!` deposits the terminal once, the call's `poll!` reads it and throws N8. Same as r1, but the failure is now *observed as data* rather than thrown out of the parked `.join` — which is exactly J3's point.
- **Response after timeout:** unchanged — `retire-call` drops the entry, the late response classifies `:unsolicited-response`, dropped.
- **`close!` during a call:** unchanged; N10. `close!` chains `sendClose` behind `:pending`, so it cannot race an in-flight send into `IllegalStateException`.
- **Two threads on one handle:** unchanged; N5's `:lock` serializes calls.

The one real change is J6: an append that read the handle's open phase just before the failure claim now gets `closed` → `rpc/request!` → `request-undeliverable` → an immediate loss, instead of the retryable `full` that would have held `:unsent` for a socket that is never coming back. That is an improvement, and it introduces no collapse (absence / nil / timeout / transport-error stay distinct).

## Hunt 3 — `retire-call` is correct

The spec achieves exactly what N6 needs, in both branches:

- **Already `requested`** (in `:outstanding`): `(update :outstanding dissoc id)` drops the entry; a late response hits `rpc.cljc:363-364` and is classified `:unsolicited-response`, then drained with the diagnostics. Ids are monotonic (`:next-id` never moves), so it can never be delivered to a later call.
- **Still `:unsent`** (append answered `full`, i.e. the handle was `:connecting`): `abandon-unsent` clears `:unsent` and appends a `:reason` completion, which the next step's per-id `mine` filter discards.

The "already on the wire" worry doesn't arise: an `:unsent` envelope is, by definition, pre-establishment and never accepted by `append!`; and after J6 the only `full` source is the pre-open phase, so an `:unsent` envelope is never on a wire that could be going away. The `(= id (get-in state [:unsent :id]))` guard reads `:unsent` from the un-dissoc'd state, but `dissoc :outstanding` doesn't touch `:unsent`, so the check is equivalent either way.

## Hunt 4 — the `non-portable-result` substitution is sound

`portable-response` substitutes `{:id id :error {:code :dao.jing.remote/non-portable-result :message "…"}}`. The substituted value is always portable (safe-int id, qualified-keyword code, string message) and always carries the original response's id, so it cannot fail to encode and cannot correlate to the wrong request. The one edge — a non-portability that lives in the **id** (a malicious client's non-safe id) — is not fixed by the substitution, but the substituted error then also fails the append, which S5's `non-ok → stream/close!` branch turns into a terminal loss (N8). That is the correct outcome for a hostile client, not silence.

## Hunt 5 — no stale assumptions in Phases 1/2

Phase 1 is pure and host-neutral (ring-buffer media, no seam). Phase 2's `call!` depends on `request!`→`append!`→`send!` returning without parking, which is exactly what Phase 0 now guarantees; D5's inbound-step already treats `closed` as retire-worthy, so J6 is absorbed; and §5.2 #3 is explicitly wired as the Phase 0 canary ("with the join still in place, the 50 ms timeout is not honoured"). Nothing in the remaining phases assumes the old `.join`.

## Where the plan is right

- The six r1 findings are all settled the way they were agreed: the latch-based timeout test (with the discard pinned separately at step level), `retire-call` for N6, N10 for close scope, the ClojureDart fact homed in project memory and D2's cursor order homed in §9 + the docstring, D3 reworded to the blocking driver's loop, and D8 reframed as an inbound-decode-policy plan.
- The reconciliation (§2.0/§4.0) is honest: it flags both divergences from the draft explicitly rather than rewriting them away, and the mutation-tested invariants (J1–J6) are actually pinned by the seven deftests I ran.

**Bottom line:** promote it. The two nits (a "not `swap!`" rewording, and a test-docstring note that the real `abort()` re-enters `onError`) are cosmetic and can ride along with Phase 1.
