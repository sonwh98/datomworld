Created-GMT: 2026-09-30 10:29:07 GMT
Created-Local: 2026-09-30 17:29:07 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0f1d7-d4ef-7ab2-998a-32966ea06c6c (resumed, pinned -m gpt-6-sol)
# Task: Architect review round 2 — DHT S0, confirm the three fixes
Role: Lead System Architect
Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 17:29:07 +07 (+0700) | Status: active | Rationale: same review thread

Resume your read-only review of docs/design/dao.jing.dht.md and docs/design/dao.stream.datagram.md (uncommitted). Fable's
fix report (untrusted): collab/1790761753000-architect-dao-jing-dht-epic-s0-r2.claude-fable-5-1.findings.md —
(1) HIGH: silence rule (need-cookie reply only if its encoded length <= the triggering datagram, else silent drop), fixed
reply shape, padded first contact (:pad to >= first-contact-bytes 256, wire v1 constant; cookie-less requests are only
padded :ping), one free re-send on need-cookie, chunks under the same gate; (2) MEDIUM: whole cookie protocol moved into S2
with an unkeyed deterministic SHA-256 stand-in cookie-for (loopback-only exposure), S4 swaps in keyed HMAC; (3) MEDIUM:
S2 uses the existing dao.jing/bytes->base64 / base64->bytes as its frozen seam, S1 creates dao.stream.base64 in the same
format, S3 repoints dao.jing and proves agreement.
Confirm each fix is correct and airtight (no amplification path remains, including chunks and re-sends; S1/S2 truly
independent), and report any remaining finding on the whole S0.
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then findings (or "No actionable findings"); end with Verdict: READY / REQUEST CHANGES and Architect Sign-off: GRANTED / WITHHELD.
