Coding-Agent: codex
Role: Independent Code Reviewer
Reviewed: 2026-10-20; worktree codec-f0; base 212c45dd

Verdict: NOT SIGNED OFF

| Priority | Location | Finding / required change |
|---|---|---|
| P1 | test/fixtures/codec/transit/envelopes.edn; test/fixtures/codec/cbor/envelopes.edn | §7.3 requires every D corpus case for each profile. Transit list/nested-list cases and CBOR integral-double are omitted. These are real baseline incompatibilities, not implementation regressions, but a green reduced corpus does not satisfy that requirement. Obtain an explicit architect-approved scope amendment; preserve the problematic pre-change payloads and documented host outcomes as exception evidence without changing runtime code. |
| P2 | test/dao/stream/codec_fixtures_test.cljc:181–190 | Accepted and no-offer entries assert only that status is not `:ws/unsupported-subprotocol`. `:ws/full`, `:ws/offer-failed`, or an incorrect fallback codec would pass. Assert `:ws/pending` for successful admission and verify that no-offer selects Transit, including on the CBOR-only endpoint. |

Review notes

Scope: zero tracked changes relative to 212c45dd, including zero edits under src/. Eleven new implementation files are confined to test/. Strictly, the worktree also contains the untracked engineer report under collab/; exclude it from the implementation commit.

All five fixture categories exist for both profiles. CBOR payloads are exact hex; Transit payloads are JSON text inside EDN strings. All ten specified CBOR rejection payloads match the existing corpus; all thirteen combined rejections assert decoder exceptions. CBOR envelope checks compare exact bytes from both expected values and decoded values; descriptors and Jing also check exact encoding. Transit correctly avoids canonical byte comparisons.

Equality follows §4.4 for the supplied corpus: recursive structural equality, list/vector equivalence, metadata blindness, byte-content comparison, and integral-double normalization. Minor robustness limitation: `norm` does not explicitly enforce finite/safe bounds before integer conversion; this is not exercised by the valid frozen corpus.

Gap assessment:
- Transit lists: independently reproduced JVM encoding followed by domain-rejection on decoding. Omitting a universal-success assertion is justified; dropping mandatory evidence without approval is not.
- CBOR 1.0: independently confirmed JVM `f93c00`; the reported Node integer representation and canonical re-encoding guard explain the incompatibility. This needs the same explicit exception decision.
- Slash keyword: freezing the decoded ns/name is appropriate for a legacy decode fixture, not evidence that the original keyword round-trips.
- NaN/Infinity: Dart's `z` handler returns these strings. Choosing unsafe integers and UUID as shared rejection cases legitimately satisfies §7.3.
- Descriptors: generic CBOR encoding is the available path; port zero is served-only. The three chosen descriptor cases are appropriate. Avoiding incidental reader metadata is also justified.

Verification independently rerun: `bb test:sub dao.stream` passed JVM 443 tests/3322 assertions, CLJS 441/3327, CLJD 419 tests. `bb test:changed` passed JVM 20, CLJS 37, CLJD 17 tests. All lanes exited zero; no observed regressions. Full repository G3 was not run and remains the post-merge gate.

Commit recommendation: hold pending the two findings. Then commit only the eleven test files, tag and record the F0 old-peer commit as §7.1 requires; HEAD currently has no tag. No code files edited during review.
