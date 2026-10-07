Created-GMT: 2026-09-10 05:19:51 GMT
Created-Local: 2026-09-10 12:19:51 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)
# Task: dao.jing.remote Phase 2 — round 5, close the gate's upper end
Role: Stream & Network Engineer

The gate's **placement** is confirmed correct, including for options a caller
`assoc`s in, and "entry gate" is confirmed the right framing. The
interruption fixes remain sound. Review:
`collab/1789017485459-review-jing-remote-phase2-r3.gpt-6-astra.findings.md`

**Two items. The first is the same tenth path, incompletely repaired — not an
eleventh.**

## P1 — the gate has no upper bound, so a valid-looking option still overflows after submission

`validate-timing-options!` (`remote.cljc:443`) accepts any positive integer.
`Long/MAX_VALUE` passes, and then `(+ (System/currentTimeMillis)
request-timeout-ms)` at `:560` throws `long overflow` **after `request!`** —
I confirmed the arithmetic throws. Stale allocator, reusable id: the eighth
exit's failure mode again, through the door the gate was meant to shut. An
oversized positive BigInt passes too and cannot become a JVM sleep duration.

`connect-content!`'s deadline addition has the same shape, failing **after**
`attach!`.

**Fix**: define and enforce a supported upper bound for the timing options,
and make both deadline computations safe over that domain. Choose the bound
yourself and say why — something defensible as a millisecond duration rather
than an arbitrary round number. Validate on **both** paths: `call!`'s entry
gate and `connect-content!` before it attaches.

**Prove** with boundary cases: at the bound, just over it, `Long/MAX_VALUE`,
and an oversized BigInt — each rejected with **nothing submitted** on the
call path and **nothing attached** on the connect path, and the allocator
unchanged. Mutation-check as before.

## P2 — a close-recording mechanism does exist, and the reviewer named one

You were right that `with-redefs` cannot reach a `defprotocol` method, and
right to take the alternative rather than invent a production seam. But
"no mechanism exists" would be wrong, and I nearly recorded it: in the test,
**replace `jvm/connect!`** with a scripted raw socket whose `:close!`
records invocation. The real `WsHandle` and protocol dispatch stay intact;
production code is untouched; a pending establishment followed by an
interrupt must invoke that recorder.

Implement it if it works — measure, as you did the var wrap. If it does not,
say what you measured and keep the honest comment; static review stays a
defensible choice, but only after this has been tried.

## Not in scope

The plan and design docs — I reconcile them. My N11 text says "argument
defects throw before the wire", which the reviewer notes currently exceeds
the gate's numeric coverage; that becomes true when P1 lands, and I will
tighten the wording either way.

## Verification

All three lanes and the demo, with counts. Baseline: clj **1458 / 165526**,
cljs **1360 / 35026**, cljd **+1314**, demo 212 files 0 warnings. Closure
greps stay **0**; `reset! (:rpc` stays at **one**, inside `settle!`.

## Report

Append to `collab/1789017591266-stream-jing-remote-phase2-r5.glm-5.3.findings.md`.
**Do not stage or commit.**
