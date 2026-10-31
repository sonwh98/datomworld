Completed-GMT: 2026-10-27 20:55:24 GMT
Completed-Local: 2026-10-28 03:55:24 +07 (+0700)
Coding-Agent: claude
Session-ID: d5fc6e55-2249-4f4d-bdf6-d06cc1fcc63e

**Verdict: RATIFY WITH AMENDMENTS.** The 298/3/216 policy with astra's reconciled close-code treatment is the right policy. The branch does not yet encode it. Nothing on `j6-autobahn-prep` was changed after c03dfcba, locally or on origin.

**What I verified in the tree, not from the reports**

- The harness still implements 299/2/216. The catalogue classes count 299 must-pass, 2 exception-listed, 216 not-applicable. The exception file has four rows, all for 7.13.1 and 7.13.2. The pin hashes match those files, the evaluator tests assert four exceptions, and the README, triage header and design-doc note all say 299/2/216.
- Astra's 7.1.6 finding holds. I fetched `case7_1_6.py` at the pinned source commit 6ed6f439. Its connection-lost handler sets both behavior fields to INFORMATIONAL unconditionally. The branch classes 7.1.6 as must-pass and the evaluator then demands OK/OK, so the committed 301-case gate is unsatisfiable by any adapter. My earlier 299/2/216 view was wrong on this point.
- The 7.13.x treatment is sound. Upstream sends 5000, suppresses its own close and kills after one second, so asserting the testee's 1002 in `remoteCloseCode` is meaningful. The evaluator applies INFORMATIONAL plus 1002 plus false timeout flags exactly as astra describes.
- The host-wire-codes section of `dao.stream.ws.md` has Browser and JVM-client rows only. No jolt row exists yet.
- The evaluator's exception mechanism is top-level field equality only. A 7.1.6 predicate on frame statistics needs a small evaluator extension.

**Exact amendments**

1. **7.1.6 becomes exception-listed** with client and server rows. Required fields: behavior INFORMATIONAL, behaviorClose INFORMATIONAL, remoteCloseCode 1000. Add one new evaluator assertion that the fuzzer received no Pong after its Close. The report field for that is the received-frame statistics keyed by opcode, which must be confirmed against the pinned report schema before locking. Pre-close echo may complete or be cut and is not asserted. Counts become 298/3/216 with six exception rows. Executions stay at 301 per role and 1,204 total. Update the triage header, the README, the design-doc note, the pin hashes, and the evaluator tests, and add negative fixtures for 7.1.6.
2. **A jolt row in Host wire codes.** Any inbound close code outside the accepted set is answered with a 1002 close frame and exactly one terminal closed event. The accepted set under this no-extension profile is 1000 to 1003, 1007 to 1011, and 3000 to 4999. Everything else, including 1004 to 1006, 1015, 1016, and 5000 and above, is refused. This is a host-profile choice, not an RFC necessity, and is distinct from the application-level 4002. The exception reasons then cite that row. Pinning 7.9.x to 1002 as well is optional.
3. **Extension wording.** Replace "reject extension offers" with "ignore offers, negotiate none". The client never offers and fails an unsolicited extension in the 101. RSV frames close with 1002, which group 3 already covers. The handshake gate row goes into §9.6. No 12.x or 13.x sentinels go into the locked policy.
4. **The J6 brief states the 6.4.x requirement.** Invalid UTF-8 must fail fast at read-chunk granularity with carried decoder state. The brief also records the 64 MiB conformance bound against the production bound and tests over-bound refusal separately.
5. **The macOS-native testee launch route is pinned in the brief** and recorded in run metadata. Docker on Linux never qualifies the macOS testee.
6. **The first-run rule is pre-decided.** Any other case yielding INFORMATIONAL or NON-STRICT for a correct adapter is a policy-file defect fixed by an owner-visible re-pin, never a runtime waiver.

**Can J6 be commissioned?** Yes. None of the amendments needs a further owner decision beyond this ratification. The J6 brief should carry amendments 1 to 3 as its first, blocking slice, re-pinning the policy files before any gate run. The prep branch should not be merged as the ratified policy until that re-pin lands. The four-case smoke remains valid plumbing evidence only.

One limit on this review. I confirmed the 7.1.6 and 7.13.1 sources at the pinned commit. I did not sweep every remaining case source for other unconditional informational labels. Amendment 6 covers that risk.
