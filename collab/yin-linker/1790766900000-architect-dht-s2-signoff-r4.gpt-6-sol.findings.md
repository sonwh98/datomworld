Completed-GMT: 2026-09-30 12:52:58 GMT  
Completed-Local: 2026-09-30 19:52:58 +07

**SIGN-OFF GRANTED.**

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| — | — | No blocking findings. | — |

The late-reply check now drops both full and need-cookie replies at the query deadline, including when that query’s owner was skipped by a budgeted step. The owner’s later advance records the failed try, so the late reply cannot prove a peer or change a write verdict.

I traced query creation, resend, full reply, refused resend, expiry, and `drop-owner`: each path keeps `:queries` and `:query-index` aligned. Expiry visits only selected owners’ indexed queries, and the advance cursor selects at most `budget` operations per step without starving a persistent operation.

Changing the timeout test to 100-tick rounds does not weaken its stated checks: it still asserts two timed-out tries retain the silent peer, the third removes it, the live peer remains, and the write ends `too-few-peers` with one counted send. The new deadline and index tests cover the r3 findings. I found no regression in the earlier S2 acceptance paths or rulings. The implementer reports passing JVM, Node, Dart, and lint checks; I did not rerun them for this read-only review.
