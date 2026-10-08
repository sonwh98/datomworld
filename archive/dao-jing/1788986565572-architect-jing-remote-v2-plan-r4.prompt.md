Created-GMT: 2026-09-09 20:42:45 GMT
Created-Local: 2026-09-10 03:42:45 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 95be8c08-c06c-41e6-9891-fa25ed546126 (resumed)
# Task: dao.jing.remote plan — r4, Phase 0b only
Role: Lead System Architect

Both confirm rounds are in. **N11, J7 and the §9 homes are cleared by both
reviewers and are closed.** Every remaining finding is in **Phase 0b**, and
they converge. Revise in place at
`collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md`;
write no other file. Do not touch anything outside §2.0's J7-J9 rows, §4.0b,
and the N2/D2/§5.2 sentences those change.

- `gpt-6-astra`: `collab/1788986077551-review-jing-remote-v2-plan-r3.gpt-6-astra.findings.md`
- `deepseek-v4-pro`: `collab/1788986077551-adversarial-jing-remote-v2-plan-r3.deepseek-v4-pro.findings.md`

I verified all of the below against the committed `jvm.clj`.

## Blocking

1. **Both reviewers, independently: `onOpen`'s check-and-install is not
   atomic with `close!`.** §4.0b says `onOpen` "checks `:close-request`
   before installing", which is a check, not a transition. The committed
   `onOpen` installs with an unguarded `(swap! connection assoc :socket
   socket)` (`jvm.clj:186`). Interleaving: `onOpen` reads no close request →
   `close!` takes the lock, sets `:close-request`/`:failed?`, cancels → `onOpen`
   resumes and installs. The socket is then **installed while `:failed?` is
   true**: J8's abort never ran, and `close!` on the socket-exists branch
   calls `chain!`, which returns `::failed` and is discarded — so the JDK
   `WebSocket` is never aborted and never closed. It leaks.
   Make check-and-install one `locking`-guarded transition, with the abort of
   a rejected socket performed **outside** the lock, as J5 already requires.
   astra adds that the prescribed test #9 is sequential close-then-open and
   cannot detect this; the pin has to be deliberately racy.

2. **deepseek: J8's `send!` claim is false against the shipped code, which
   makes its own test unwritable.** J8 and §4.0b say `send!` answers `closed`
   "whether or not a socket ever arrived". It does not: the `closed` answer
   is inside the `if-let [socket …]` branch (`jvm.clj:156-167`), and the
   **no-socket branch returns `false`**, which `send-result` maps to
   `:dao.stream/full` (`ws.cljc:101`). J8's own "never installed" case has no
   socket, so test #9 — which asserts the `closed` outcome map with
   `(:socket @connection)` still `nil` — fails as specified. **§4.0b's Build
   list contains no `send!` change.** Either add one (the no-socket branch
   consults `:failed?`) or restate J8 to claim only what the seam does. This
   is the r1 unwritable-test shape again, and deepseek found it by tracing
   the test against the code rather than the prose.

3. **astra: cancelling under the monitor recreates the hazard Phase 0
   removed.** `.cancel` completes the future exceptionally and can run its
   already-registered `whenComplete` **inline**, so `:closed!` deposits while
   `close!` still holds `connection`'s monitor — the monitor-held-across-
   deposit problem `3228d0e` fixed, reappearing in the plan for 0b. Moving
   observer *registration* does not prevent it. Capture the future and mark
   the connection terminal under the monitor, then cancel after releasing it.
   Extend test #8 to assert `Thread/holdsLock` is false inside the
   establishment deposit, including teardown re-entry.

## Must fix

4. **astra: an existing shipped test needs migrating and §4.0b does not say
   so.** `before-open-send-answers-full` (committed `jvm_test.clj`) builds a
   connection with `:future nil` and calls `close!`; Phase 0b's unconditional
   `.cancel` throws on it, and its "nothing is deposited" assertion describes
   the old unwatched setup. Name it in 0b's build list: supply a watched
   establishment future, assert the new close behaviour, keep the healthy
   before-open `full` assertion.

5. **deepseek: J9's two phrases overstate a residual you otherwise state
   honestly.** "J8 is what makes that harmless" is true **only for the local
   handle, never for the peer** — J8 aborts a socket handed to a *late*
   `onOpen`; for a peer that never completes the handshake `onOpen` never
   fires, so J8 never runs and the peer's accepted socket stays open. And
   "bounded by the JDK's own handshake lifetime" is misleading: the
   `HttpClient` `connect!` builds carries no connect or request timeout and
   the JDK imposes no default bound on waiting for the 101 response once TCP
   is up, so that "lifetime" is the process lifetime. Reword to what
   deepseek proposes: **unbounded for a stalled peer; the leak is the
   caller's, and the JDK offers no handle to bound it.** Also note §5.2 #6
   does not close its own accepted `ServerSocket` socket.

## Cleared by both — closed, do not reopen

**N11 and `drain-outboxes`**: deepseek verified the caller's error data
travels in the `request!` *result*, not the outboxes, so draining before the
throw loses nothing; `take-completed`/`take-diagnostics` are pure dissocs so
`settle!` cannot throw; and under N5's lock `settle!` can only ever drain the
current call's own data. **J7**: `cancel(true)` completes exceptionally with
`CancellationException`, the observer `connect!` registers (`jvm.clj:233-238`)
runs with a non-nil error and deposits the terminal once through
`ws/closed!`'s guard; a second `close!` is idempotent. **N2's narrowing** is
the honest call. **§9's homes.** Everything cleared in earlier rounds.

## Output

Rewrite in place; structure and numbering stable; update the header. If a
finding is wrong, say so with your reason — you have been right to accept
every one so far, but findings 2 and 5 are claims about *your* text against
*shipped* code, so check them yourself rather than taking my word.
