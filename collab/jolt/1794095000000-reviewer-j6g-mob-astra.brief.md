# Code-quality review: the J6g workload W harness rewrite

Review the diff at collab/jolt/1794095000000-j6g-mob-astra-review.diff
(a 1799-line diff against origin/master). The implementing model was
gpt-6.1-sol; the review dispatches to kimi, qwen, and deepseek for
independent assessment.

## Context

The workload W harness tests whether the jolt WebSocket adapter serves a
heavy peer (A: one 4 MiB message paced over 120 s) and a light peer
(B: 1 KiB request/reply at 10 Hz) simultaneously without B's latency
degrading. The adapter uses a single owner thread with cooperative
multiplexing (race-free by construction, but serialized).

The ruled criterion (astra ruling, collab/jolt/1794250000000):
- A delivered exactly once
- B: the first 600 scheduled requests after A's first positive write,
  p99 (send-to-receive AND scheduled-arrival-to-reply) ≤ 250 ms
- The exact one-sided 95% binomial bound q_U ≤ 1% (at n=600: at most 1
  violation)
- The REPL step count advances every owner round
- The 500-sample validity floor

## What to review

1. The independently driven B scheduler thread (JVM Thread on its own
   100 ms clock grid, ≤8 outstanding, no tick resets, three timestamps
   per request).
2. The five-point per-round REPL verifier (append/round/result/last-
   value/no-duplicate).
3. The paced A transfer (120 s scheduled writes, no-progress guard).
4. The dual p99 bounds and the binomial confidence check.
5. The 300-second watchdog and the 10-second sampler flush.
6. The W-PROGRESS/W-TIMINGS telemetry (the cursor on both paths).

## Known context

- The adapter files (src/jolt/**, src/cljc/yin/repl.cljc) are frozen.
- jolt cannot parse JVM type hints.
- The previous implementation rounds failed (agy shiva stall, glm
  fix fabrication); this is the mob session's clean rewrite.
- The prior kimi r8 APPROVE was against the PRE-mob implementation
  (which the mob round replaces).

## Deliverable

Findings with P-levels; the final line APPROVE or CHANGES-REQUESTED.
