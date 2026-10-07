Created-GMT: 2026-09-10 04:23:45 GMT
Created-Local: 2026-09-10 11:23:45 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)
# Task: dao.jing.remote Phase 2 — round 2, the eighth exit
Role: Stream & Network Engineer

Your Phase 2 is nearly clean. All three deviations were judged justified, the
six `network-*` assertions are confirmed unchanged, N11 holds on all seven
planned exits, the cursor order and its docstring are right, S5 is right, and
the design edits describe the code. Review:
`collab/1789014102417-review-jing-remote-phase2.gpt-6-astra.findings.md`

**One blocking defect, and it is the eighth exit.** I verified it in the code.

## P1 — interruption bypasses `settle!` and permits request-id reuse

`src/cljc/dao/jing/remote.cljc:540`:

```clojure
(if (< (System/currentTimeMillis) deadline)
  (do (Thread/sleep ^long poll-interval-ms)
      (recur (:state step)))
  …)
```

`Thread/sleep` throws `InterruptedException`. When it does, the loop leaves
by exception **without `settle!`** — and by then the request has already been
appended to the wire, while the advanced allocator, cursor and outstanding
table exist only in the loop's *local* `state`. The stored `(:rpc client)` is
still whatever it was before the call.

So: interrupt a call, then reuse the client. `request!` allocates **the same
id again**, and the interrupted call's late response satisfies the new call —
**the wrong result returned to the caller**, not merely a leak. This is a
correctness defect, and it is exactly the class N11 has attracted three times
in this plan: an exit nobody enumerated.

**Fix**: retire the interrupted call from the **latest** state (the loop's
`state`, not the client's stored one) and store it through `settle!` before
propagating the interruption. Preserve the interrupt: re-set the thread's
interrupt flag or rethrow, per normal JVM practice — do not swallow it. The
plan's §5.1 says `settle!` "must be the only way out of the function"; make
that true for eight exits, not seven.

**Prove it deterministically, without a real interrupt race if you can**: a
test that drives the loop to an interrupted sleep, then asserts (a) the
allocator advanced — the next `request!` gets a **new** id — and (b) the
interrupted call's late response cannot satisfy the next call. If you must
interrupt a real thread, own it: run `call!` in a thread you start, interrupt
it, join it, then assert on the client's stored state from the test thread.

Update §5.1's exit enumeration in the plan? **No** — do not touch the plan;
report the delta and I will reconcile it.

## P2, non-blocking — test 6's accepted-socket cleanup races publication

`test/dao/jing/remote_test.cljc:520`: cleanup reads `@accepted` before
closing the listener, so a socket the accepter returns but publishes *after*
that read is never closed. Coordinate acceptance and teardown so a socket
arriving during cleanup is closed too. The test's assertions are otherwise
correct — deadline failure only, no EOF claim.

## Everything else is cleared — do not change it

The seven planned exits, test 9's stored-state assertions, the cursor mint
order, S5's correlated error and non-ok retirement, the six `network-*`
tests, the six gated requires, the `rpc.ws` alias, both prose edits, and all
four design documents.

## Verification

Re-run all three lanes and the demo, with counts. Baseline to beat, my runs
on your current tree: clj **1455 / 165505**, cljs **1357 / 35023**, cljd
**+1311**, demo 212 files 0 warnings, all zero failures. Closure greps must
stay at **0** v1 tokens in the three code files.

## Report

Append to `collab/1789014225034-stream-jing-remote-phase2-r2.glm-5.3.findings.md`
with the same header fields. **Do not stage or commit.**
