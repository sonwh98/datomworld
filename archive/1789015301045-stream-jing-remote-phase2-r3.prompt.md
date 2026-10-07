Created-GMT: 2026-09-10 04:41:41 GMT
Created-Local: 2026-09-10 11:41:41 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)
# Task: dao.jing.remote Phase 2 — round 3, the ninth exit
Role: Stream & Network Engineer

The eighth exit is fixed correctly and the mutation check is convincing. The
teardown race fix is right. Both are accepted.

**You asked whether `connect-content!`'s establishment-loop sleep should be
closed-and-thrown "for symmetry". The answer is yes, but not for symmetry —
because N2 promises it, and today it is false.**

## The finding, which your question led me to

`remote.cljc:680`, inside `connect-content!`'s establishment loop:

```clojure
(< (System/currentTimeMillis) deadline)
(do (Thread/sleep ^long poll-interval-ms)
    (recur (:state r)))
```

Both other failure branches — `:terminal` and the deadline — call
`(stream/close! handle)` **before** throwing. An `InterruptedException` from
that sleep propagates raw, so the handle is **never closed** and escapes to
nobody: the caller gets an exception while an attached handle and its socket
are left live.

Your reasoning for leaving it — no id, no outstanding request, no client to
reuse — is right about **request-id reuse**, which is why this is not a
P1-class correctness defect like the eighth exit. But it misses the handle.
N2, as written in the plan (§2, N2), says:

> In both failure cases, **before the throw**, `stream/close!` has run on the
> handle, which flips its phase to `:closed` so **no handle, medium or state
> escapes to the caller** and no later call could be made through it.

Interruption is now a **third** failure exit of `connect-content!`, and it
does not honour that. The invariant is about every failure exit, not two of
them.

## Fix

Close the handle before propagating, and preserve the interrupt as you did at
the eighth exit — re-assert the thread's flag, then throw. An `ex-info` with
`{:url url :reason :dao.jing.remote/interrupted}` matches the loop's other
two throws; use your judgement on the message.

**Prove it** the way you proved the eighth exit — set the calling thread's
interrupt flag before `connect-content!` so the sleep throws on entry, against
a `ServerSocket` that accepts and never writes. Assert: the throw carries the
interrupted reason, the flag is preserved (capture it inside the catch, per
the runner wrinkle you found), and **the handle did not escape** — no client
value is returned. If the handle's closedness is not observable from outside,
say so rather than inventing a seam; the no-escape assertion is the one that
matters.

## Not in scope

The ticker's `Thread/sleep` at `:900` — that is a daemon thread the `stop!`
path retires through `running`, with no handle of its own and nothing stored.
Leave it.

## Everything else stays

Do not touch the eighth-exit fix, the teardown-race fix, the seven planned
exits, the six `network-*` tests, the aliases, the prose edits, or the four
design documents. **Do not touch the plan** — I reconcile §5.1's exit list and
`dao.jing.md`'s exit sentence, and I will add this ninth exit to both.

## Verification

All three lanes and the demo, with counts. Baseline to beat, your r2 tree:
clj **1456 / 165512**, cljs **1358 / 35024**, cljd **+1312**, demo 212 files
0 warnings, zero failures throughout. Closure greps stay at **0**.

## Report

Append to `collab/1789015301045-stream-jing-remote-phase2-r3.glm-5.3.findings.md`.
**Do not stage or commit.**
