Created-GMT: 2026-10-20 07:48:30 GMT
Created-Local: 2026-10-20 14:48:30 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Address Review Findings for Slice F0 (Frozen Wire Fixtures)
Role: Implementation Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-20 14:48:30 +07 | Status: active | Rationale: Fix review findings P1 and P2 in isolated worktree.

Working Directory: /Users/sto/workspace/datomworld-codec-f0
Reviewer Report: /Users/sto/workspace/datomworld/collab/1792482310000-reviewer-codec-f0-fixtures.gpt-6-astra.findings.md

Reviewer Findings to address:
1. P1: Baseline Incompatibility & Exception Fixtures (test/fixtures/codec/{transit,cbor}/envelopes.edn):
   - Rather than silently dropping the problematic cases from the fixture sets:
     - For Transit lists (JVM decoder failure): Record the pre-change encoded payload in an exceptions fixture or separate section of envelopes/fixtures, and test the exact documented pre-change behavior (e.g. JVM Transit decode throwing out-of-domain exception on list payload) so the pre-change baseline is explicitly preserved as frozen evidence.
     - For CBOR 1.0 (JVM f93c00 vs Node 01): Record the JVM-generated 1.0 payload and document/assert the exact pre-change behavior per host (or test across compatible hosts), preserving the evidence.
2. P2: Exact Admission Assertions (test/dao/stream/codec_fixtures_test.cljc:181-190):
   - In negotiation tests, do not merely assert that status is NOT `:ws/unsupported-subprotocol`.
   - Assert `:ws/pending` (or exact successful admission status) for admitted offers.
   - For no-offer connection attempts, verify that the seam outcome specifically defaults to Transit (`dao.stream.transit/profile`), including when connecting to the CBOR-only endpoint (confirming the pre-change hardcoded default behavior).

Verification:
- Run `bb test:sub dao.stream` across all 3 lanes (clj, cljs, cljd) and confirm all pass with 0 failures.

Write updated findings to /Users/sto/workspace/datomworld/collab/1792482520000-engineer-codec-f0-fixes.claude-sonnet-5-5.findings.md
Do not touch any files under src/. Touch only test/ files in the worktree.
