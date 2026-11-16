Created-GMT: 2026-11-15 (dispatch-time)
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: J7 fix round — the review findings P1-P2x3
Role: engineer
Read collab/jolt/j7-review.findings.md (the review verdict: request changes) and fix:

1. P2 (udp.jolt:155,162): check native/close-on-exec! boolean and the [result errno] from the IPV6_V6ONLY set-option; on failure close the acquired descriptor and deposit bind-failed with the actual failure.
2. P2 (udp.jolt:151 + 199,235): establish fd/buffer cleanup immediately after every native acquisition; on a bind-path throw release everything and report bind-failed preserving the original reason; same ownership discipline for send/receive temporary buffers.
3. P2 (udp.jolt:244): retain instance-owned receive diagnostics including errno; close the seam once on fatal descriptor errors; interruption stays recoverable and would-block stays silent; no retry inside a turn.
4. P1 (acceptance reconciliation): update the stale J7 exclusion reasons in test/resources/yin/repl/jolt-applicability.edn and the jolt_j2_lane exclusions to cite the new landed state (slice 1+2 landed; the remaining slices per the design sections 6-7 stay excluded with their real reasons). Add the injected-failure regression tests for fixes 1-3 as a jolt probe namespace in the style of bb probe:jolt-udp (extend it or add bb probe:jolt-udp-failures). Do NOT claim full J7 gate completion; the design sections 6-7 process/interop acceptance stays open and is recorded, not closed.

Acceptance to run and EVIDENCE in collab/jolt/j7-fix.findings.md: bb probe:jolt-udp (still 37/37), the new failure-injection probe green, the dht: solo smoke still boots and evaluates (+ 1 2) => 3, JVM udp-test still 0 failures. Commit per finding on this branch with evidence.
