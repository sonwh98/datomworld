Created-GMT: 2026-11-16 (dispatch-time)
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: J7 fix round 2 — the two reopened P2 findings
Role: engineer
Read collab/jolt/j7-review-confirm.findings.md (request changes; two REOPEN findings). Fix exactly:

1. Acquisition cleanup (udp.jolt:269 area): the receive helper length-cell allocation and the bind address-string allocation each leak on partial failure (128 and 16 bytes in the reviewer injection). Make every helper exception-safe: wrap each acquisition sequence so a failure releases everything acquired so far; add a probe leg for EACH helper acquisition failure (extend bb probe:jolt-udp-failures).
2. Receive-error classification (udp.jolt:291 area): per Linux udp(7), an unconnected UDP socket may report asynchronous errors (ECONNREFUSED 111, ECONNRESET, EMSGSIZE-if-oversize-async) from EARLIER packets — these are NOT descriptor-fatal. Deliver them as instance-owned receive diagnostics without closing the seam; close once ONLY on genuine descriptor errors (EBADF, ENOTSOCK, EPIPE-class). EINTR stays recoverable, would-block silent. Add probe legs: injected ECONNREFUSED leaves the seam open and the diagnostic observable; injected EBADF closes exactly once.

Acceptance with evidence in collab/jolt/j7-fix2.findings.md: the two new probe legs + bb probe:jolt-udp-failures all green, bb probe:jolt-udp still 37/37, the dht: solo smoke still boots and evaluates, JVM udp-test 0 failures. Commit per finding.
