Coding-Agent: codex
Role: Code Reviewer
Reviewed: 2026-10-20; worktree codec-f0; base 212c45dd

Verdict: NOT SIGNED OFF

The requested implementation fixes pass review. One previously identified acceptance requirement remains unresolved.

- P1 evidence: RESOLVED. test/fixtures/codec/transit/exceptions.edn preserves list and nested-list Transit text with JVM :throws and Node/Dart :decodes. test/fixtures/codec/cbor/exceptions.edn preserves the envelope ending in f93c00 (1.0), with JVM/Dart :decodes and Node :throws. frozen-baseline-exceptions-keep-their-per-host-outcome (test/dao/stream/codec_fixtures_test.cljc:246) loads both files and asserts the running host's recorded outcome, comparing successful decodes under the fixture equality rule. These entries remain separate from the clean envelopes and canonical re-encoding tests.
- P1 acceptance amendment: OPEN. docs/design/dao.stream.codec.md:754 in the main worktree still requires every fixture to decode successfully and CBOR values to re-encode exactly. The previous review explicitly required an architect-approved scope amendment for these baseline exceptions; the engineer report confirms it remains open. Recording accurate exceptions satisfies the evidence request but does not resolve that acceptance discrepancy. No new implementation defect was found.
- P2: RESOLVED. codec_fixtures_test.cljc:190-196 asserts exact :ws/unsupported-subprotocol for refusals and exact :ws/pending for admission. It inspects the slot codec and compares it with the offered profile or, for no offer, transit/profile. Both profile directories include the no-offer case, so the CBOR-only endpoint's Transit fallback is explicitly covered.
- Cleanliness: CONFIRMED. No tracked changes relative to 212c45dd and no untracked files under src/. Implementation additions are confined to thirteen test files; two untracked engineer reports under collab/ are separate. No code files were edited during review.

Independent verification: bb test:sub dao.stream passed JVM 444, Node 442, Dart 420 tests; bb test:changed passed JVM 21, Node 38, Dart 18 tests. All six lanes exited zero with zero failures.

Commit recommendation: hold F0 acceptance until the architect records the baseline-exception amendment. The test changes are technically ready. After that decision, commit only the thirteen test files, tag and record the F0 old-peer commit as required by §7.1, and run the mandatory post-merge G3 gate. Do not include the worktree collab reports in the implementation commit.
