Created-GMT: 2026-09-09 20:35:05 GMT
Created-Local: 2026-09-10 03:35:05 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72 (resumed)
# Task: adversarial confirm — dao.jing.remote plan at r3, new sections only
Role: Adversarial Review

**Read-only. Print to stdout. Write nothing.** A routine confirm runs in
parallel; do not coordinate with it.

You said **promote it** at r2, and you were right about everything you looked
at. But the routine reviewer looked where you did not — the establishment
path and the *immediate* request-failure path — and found two real defects. I
verified both. So the plan gained material after your clearance, and that
material is your target now. Plan: `collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md` (1182 lines, header r3).

**One correction to your r2 review, for the record**: you approvingly cited
§5.5's claim that Phase 2's timeout test is a canary for the old `.join`. It
is not — completing a `sendText` does not require the server to dispatch its
handler, so a stalled driver does not hold the send future open. You were
reading text I had already withdrawn on the other reviewer's finding.

**Scope: the new sections only.** What you cleared at r2 stays cleared.

## What is new

1. **Phase 0b (§4.0b, J7-J9)** — a second transport prerequisite. J7:
   `close!` before the socket exists cancels the establishment future, whose
   exceptional completion makes the existing observer deposit the terminal
   once. J8: a socket handed to a late `onOpen` is aborted, never installed.
   **J9: a stated non-guarantee** — cancellation does not reach the stage
   producing the socket, so a stalled peer need not observe EOF at any
   bounded time; "J8 is what makes that harmless."
2. **N11 / `drain-outboxes`** — one `settle!` on every exit of `call!`
   drains `:completed` and `:diagnostics` before storing state. Four refusal
   exits never reached `call-step`, the only drain, and `allocation-failure`
   loses every outstanding request into `:completed`.

## Hunt these

1. **J9 as a hiding place.** You have seen this shape before — schema's D4
   documented a limit rather than fixing it, and you judged that honest. Is
   *this* one honest? Construct the composition where a peer that never sees
   EOF actually costs something: a server holding an accepted socket forever,
   a connection-limited peer, a test that leaks sockets across a run. Is "J8
   makes it harmless" true for the **peer**, or only for the local handle?
2. **J7's cancellation reasoning.** Does cancelling a `CompletableFuture`
   whose `whenComplete` observer is already registered actually deposit the
   terminal exactly once — and is the observer `connect!` registers the one
   the plan thinks it is? Read `jvm.clj` as committed.
3. **J8's race.** Between the cancel and a late `onOpen`, what else can run?
   Can a socket be aborted and *also* installed, or installed and then
   aborted by a second path? Does `request`-ing on an aborted socket throw
   somewhere the listener cannot report?
4. **N11's drain-before-throw.** Draining is now on the error exits. Does
   anything the caller needs for its own error live in those outboxes and
   get dropped with them? Can `settle!` itself throw, or drain a completion
   belonging to a *different* call on a shared handle?
5. **Whether Phase 0b's tests can be written as specified** — you caught the
   unwritable timeout test at r1 by tracing the server's single thread. Trace
   these three the same way.

## Report

Ordered by severity, each with the interleaving that makes it real. Say
plainly where it is right, and whether it is ready to promote.
