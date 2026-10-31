Completed-GMT: 2026-09-09 20:42:45 GMT
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72
Role: Adversarial Review (confirm, r3 new sections) | Model: deepseek-v4-pro

Phase 0b verified against the committed `jvm.clj` and its test, and the new N11/drain-outboxes material traced against `rpc.cljc` (which I re-read). Here is the confirm review, stdout only.

---

# Adversarial confirm — dao.jing.remote plan, r3 (new sections only)

## Verdict

**Close, but not quite ready to promote: J8 is underspecified in two ways, one of which makes its own test unwritable.** J7's cancellation reasoning is correct, and N11's drain is correct. J9 is honest in *naming* its residual but two of its phrases overstate. The two J8 gaps are in the new §4.0b and should be closed before Phase 0b lands.

## Finding 1 (medium) — J8's "`send!` answers `closed` … whether or not a socket ever arrived" is not what the code does, and §4.0b omits the change that would make it true.

J8 claims "the connection stays failed and a later `send!` answers `closed` (J6)," and §4.0b says "Because the connection is marked failed, `send!` keeps answering `closed` (J6) **whether or not a socket ever arrived**." But in the committed `send!` (`jvm.clj:156-167`) the `closed` answer is *inside* the `if-let [socket …]` branch, reached only via `chain!` → `::failed`. The **no-socket branch returns `false`**, which `ws.cljc`'s `send-result` maps to `:dao.stream/full` (`ws.cljc:101`), not `closed`.

So J8's "whether or not a socket ever arrived" is a category error: J6's `closed` requires a socket to reach `chain!`, while J8's own "never installed" case (and the never-opened case) has no socket. To make the claim true, `send!`'s no-socket branch must become something like `(if (:failed? @connection) {:dao.stream/outcome :dao.stream/closed} false)` — and **§4.0b's Build section does not list any `send!` change**. Consequence: test #9 (`a-late-open-after-close-is-aborted-not-installed`) asserts `send!` answers the `closed` outcome map with `(:socket @connection)` still `nil`; against the current seam it returns `false`, so the test fails as specified. This is the same "unwritable test" shape you asked me to trace in Hunt 5, and it is the one place the new tests and the new code diverge.

## Finding 2 (medium-low) — J8's check-before-install is not atomic, so a `close!` racing `onOpen` installs the socket and then never tears it down.

§4.0b says `onOpen` "checks `:close-request` **before** installing the socket," but not under `(locking connection …)`. The committed `onOpen` installs with an unguarded `(swap! connection assoc :socket socket)` (`jvm.clj:186`). Interleaving:

1. `onOpen` reads `(:close-request @connection)` → `nil` (no close yet).
2. `close!` runs (J7): under the lock, sets `:close-request`, `:failed? true`, cancels the future.
3. `onOpen` resumes and installs the socket, `.request`s it.

Now the socket is **installed and `:failed?` is true**. `send!` does answer `closed` here (socket exists → `chain!` → `::failed`), so there is no misrouting — but the socket is *never* aborted (J8's abort branch did not run) and *never* closed (`close!` on the socket-exists branch calls `chain!`, which returns `::failed`, and `close!` discards it). The JDK's `WebSocket` leaks — exactly the "aborted and also installed / installed and never aborted" question in Hunt 3. The fix is to make the check-and-install one `locking`-guarded transition (abort outside the lock, as J5 already requires for the send-failure path). §4.0b does not say this; its "checks before installing" is a check, not a transition.

## Finding 3 (low) — J9 is honest, but "bounded by the JDK's own handshake lifetime" and "J8 makes it harmless" both overstate.

The residual itself is real and correctly *named*: `CompletableFuture.cancel` does not reach the stage producing the socket, so a peer that accepts TCP and stalls the upgrade need not observe EOF. But:

- **"J8 is what makes that harmless" is true only for the local handle**, never for the peer. J8 aborts a socket the JDK hands to a *late* `onOpen`; for a peer that *never* completes the handshake, `onOpen` never fires, so J8 never runs. The peer's accepted socket — and the client's JDK-held connection — stay open. That is the "connection-limited peer / test that leaks sockets" cost Hunt 1 asks about, and J8 does nothing for it.
- **"bounded by the JDK's own handshake lifetime" is misleading.** The `HttpClient` a `connect!` creates carries no connect/request timeout, and the JDK imposes no default bound on waiting for the 101 response once TCP is up. For a peer that accepts TCP and stalls the upgrade, that "lifetime" is the process lifetime (or until GC of the future), not a bounded interval. §5.2 #6 correctly *withdrew* the peer-EOF assertion, but it also does not clean up its own accepted `ServerSocket` socket (and nothing can close the client-side JDK connection), so the leak is real and unmanaged.

This is the honest-resolution shape you asked me to compare against schema's D4: the residual is stated rather than hidden, which is right — but the two phrases above turn a stated residual into a soft guarantee it is not. Recommend rewording to "unbounded for a stalled peer; the leak is the caller's, and the JDK offers no handle to bound it."

---

## Where the plan is right

- **Hunt 2 — J7's cancellation reasoning is correct.** `CompletableFuture.cancel(true)` completes an unfinished future exceptionally with `CancellationException`; the `whenComplete` observer `connect!` registers (`jvm.clj:233-238`) runs with a non-nil `error`, deposits `closed! 1006 "dao.stream/connect-failed"`, and `ws.cljc`'s `closed!` (`:resolution?` + `terminal!`'s `:terminal?`) makes the terminal exactly once. A second `close!` re-cancels an already-cancelled future (`false`, no-op) and re-sets the same keys — idempotent, no second report. That is the observer the plan thinks it is.
- **Hunt 4 — N11 is correct.** The caller's error data is carried in the `request!` *result* (`:dao.stream.rpc/reason` for `request-undeliverable`, `:dao.stream.rpc/diagnostic` for `invalid-request`/`allocator-error`), not in the state's outboxes, so draining before the throw loses nothing. `drain-outboxes` (`take-completed`/`take-diagnostics`) are pure dissocs — `settle!` cannot throw. And under N5's lock there is one awaited call, so `settle!` can only drain (a) the current call's own refusal/abandonment data and (b) retired-id diagnostics — never a live different call's completion. The `:done` path correctly settles *before* `completion-value`, which carries the completion in the step result, not the outbox.
- **N2's narrowing is the honest call**: withdrawing the peer-EOF assertion and pinning the guaranteed facts (`:phase` closed, establishment cancelled, late socket aborted, terminal once, nothing escapes) where they are observable — Phase 0b's #8–#10 — rather than asserting what the transport cannot do.

**Net:** J7 and N11 are right and promotable. J8 needs two tightening edits before Phase 0b ships — the `:failed?` check in `send!`'s no-socket branch (so test #9 can be written as specified) and a `locking`-guarded check-and-install in `onOpen` (so the race in Finding 2 closes) — and J9's "bounded"/"harmless" phrasing should be rewritten to say "unbounded, caller-owned."
