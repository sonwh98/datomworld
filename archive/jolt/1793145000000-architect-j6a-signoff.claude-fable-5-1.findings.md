Completed-GMT: 2026-10-27 21:06:42 GMT
Completed-Local: 2026-10-28 04:06:42 +07 (+0700)
Coding-Agent: claude
Session-ID: 710b3aa2-da9c-46e2-952c-099861cfdda9

**SIGNED OFF CONDITIONAL.** The diff implements amendments 1 to 3 faithfully and adds nothing beyond them except the orchestrator-authorized README test-command fix. Luna's confirmation did not return ready-to-commit, and I found one defect in the jolt wire-code row that traces to my own verdict. Both are small doc or evaluator edits in files already in the diff.

**Conditions** (all three, then commit):

1. Fix the P2 below in the jolt row of dao.stream.ws.md.
2. Apply luna's open item as a P3: require `createStats` to be `True` before the opcode assertion runs, with missing and false fixtures.
3. Luna confirms both and returns ready-to-commit. Re-pin nothing: neither cases.json nor exceptions.json changes.

**Findings**

- **P2 | docs/design/dao.stream.ws.md:530 | evidence:** the accepted set stops at 1011, so inbound 1012 to 1014 are answered 1002. The same document has served connections send 1013 for pending-overflow at line 321, and the JVM-client row at line 528 says served connections are the only sender of 1013. A jolt client attached to a JVM or Node server would therefore treat a legitimate overflow close from a sibling host as a protocol error. Nothing forces this: at the pinned release the 7.9.x rows probe 0, 999, 1004 to 1006, 1016, 1100, 2000 and 2999, never 1012 to 1014, and §9.5 line 1282 lists the invalid codes as below 1000, 1004 to 1006 and 1015 to 2999. The engineer transcribed my verdict exactly. The defect is in the verdict's set, and I amend it here as Architect. **Fix:** accepted set becomes 1000 to 1003, 1007 to 1014, and 3000 to 4999. Make the parenthetical exhaustive: 0 to 999, 1004 to 1006, 1015 to 2999, and 5000 and above. No evaluator, exception row or hash depends on it.
- **P3 | tools/jolt-autobahn/harness.py:124 | evidence:** luna's open item. I fetched fuzzing.py at pinned commit 6ed6f439. `createStats` is hardcoded `True` in `connectionMade` and no spec or config path sets it, so the pinned image cannot emit a report with it false. The harness's own standard elsewhere is that a missing field fails, and the check costs one line. **Fix:** fail unless `r.get('createStats') is True` whenever `forbid_rx_opcodes` applies; add missing-flag and false-flag fixtures in both roles.

**Verified as faithful**

- Amendment 1. Six exception rows, 7.1.6 client and server with INFORMATIONAL, INFORMATIONAL, 1000 and no Pong. Counts 298/3/216, 301 per role, 1,204 total, in the triage header, README, design-doc note, pin hashes and tests. Negative fixtures cover Pong after Close, wrong and missing close code, OK, FAILED, timeout, and the malformed-count cases from luna's first P2. The verdict's open item is now closed: the per-case report dict at the pinned commit always carries `rxFrameStats`, keyed by opcode and stringified by JSON. The fuzzer drops the socket when its own close is answered, so a Pong sent before the testee's close reply is counted and a correct testee produces none.
- Amendment 2. Row placed in Host wire codes with the host-profile and 4002 wording. All four 7.13.x reasons cite it. 7.9.x left optional, as allowed.
- Amendment 3. "Ignore offers, negotiate none" in the README and in all 216 N/A reasons of cases.json and triage.md. No remaining "reject extension" hit in the tool or the two design docs. The handshake gate row sits in §9.6 and says no 12.x or 13.x sentinels. Nothing contradicts §9.5 or §9.6.
- Pins. I recomputed both sha256 values and they match pin.json.

**Scope note.** Luna's reasoning on the statistics flag was correct in direction and I lowered it to P3 only because the flag is unreachable from the pinned image. If the orchestrator prefers to ledger it as a known gap under the Q6 rule instead of fixing it, condition 2 can be dropped. Condition 1 cannot.
