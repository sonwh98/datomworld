Created-GMT: 2026-09-10 04:58:41 GMT
Created-Local: 2026-09-10 11:58:41 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)
# Task: dao.jing.remote Phase 2 — round 4, the tenth exit and a vacuous pin
Role: Stream & Network Engineer

Both interruption fixes are confirmed correct — the eighth retires from
`(:state step)`, stores through `settle!`, preserves the interrupt and
prevents late-response miscorrelation; the ninth closes before restoring the
flag and throwing; the accepted-socket protocol fixes the race. My document
reconciliation was judged accurate. Review:
`collab/1789016215065-review-jing-remote-phase2-r2.gpt-6-astra.findings.md`

**Two things left. The first is a tenth exit; the second is a pin of yours
that does not bite.**

## P1 — invalid timing options bypass `settle!` after the request is sent

`remote.cljc:530`:

```clojure
(let [id (:dao.stream.rpc/id requested)
      request-timeout-ms (:request-timeout-ms client)
      poll-interval-ms (:poll-interval-ms client)
      deadline (+ (System/currentTimeMillis) request-timeout-ms)]
```

The deadline is computed **after** `rpc/request!` has already appended. A
client carrying `{:request-timeout-ms "bad"}` sends its request, then throws
in the arithmetic — **without `settle!`**, so the advanced allocator is never
stored and the next call reuses the id and can take the abandoned call's
answer. That is the eighth exit's failure mode reached by a different door.
A negative `:poll-interval-ms` does the same through `Thread/sleep`'s
`IllegalArgumentException`, which both of your new catches step past because
they catch only `InterruptedException`.

**Fix**: validate the timing options **before anything is submitted**.
`connect-content!` is the obvious place, but the reviewer's point is that
`call!` is public and a client value can be `assoc`ed, so validate at
`call!`'s entry too — before `request!`, so an invalid option sends nothing.
Argument defects throw, per the contract; the requirement is only that they
throw **before** the wire, not after.

**Prove**: invalid `:request-timeout-ms` and invalid `:poll-interval-ms` each
send nothing and leave the allocator unchanged — the next call with a
corrected client gets a **fresh** id. Mutation-check it as you did the other
two.

## P2 — the establishment test's close assertion does not bite

Your `connect-throws-on-interruption-with-nothing-escaping` asserts the
no-return sentinel, the error map and the interrupt flag — all valid — but
**deleting `stream/close!` from that branch leaves them all passing**. The
comment claiming the close is "pinned together" with them overstates what the
test covers.

You were right not to invent a *production* seam. The reviewer's suggestion
avoids one: in the test, wrap the existing `stream/close!` **var**, delegate
to the real implementation, and record that it was invoked. That is a
test-only seam over an existing public function, not a hook added to
production code for testing's sake. Do that, or — if you judge the wrap
worse than the gap — leave the test as it is and **change the comment** to
say the close is statically reviewed, not tested. Either is acceptable; the
comment claiming coverage it lacks is not.

## Everything else is cleared

The eighth and ninth fixes, the teardown protocol, the seven planned exits,
test 9, the six `network-*` tests, the aliases, the prose edits, the four
design documents, and my plan/`dao.jing.md` reconciliation. Do not touch the
plan or the design docs — if the tenth exit needs N11's wording changed, say
so and I will do it.

## Verification

All three lanes and the demo, with counts. Baseline: clj **1457 / 165515**,
cljs **1359 / 35025**, cljd **+1313**, demo 212 files 0 warnings, zero
failures. Closure greps stay at **0**; `reset! (:rpc` stays at **one**
occurrence, inside `settle!`.

## Report

Append to `collab/1789016321024-stream-jing-remote-phase2-r4.glm-5.3.findings.md`.
**Do not stage or commit.**
