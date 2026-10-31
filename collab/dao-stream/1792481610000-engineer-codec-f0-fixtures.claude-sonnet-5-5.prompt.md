Created-GMT: 2026-10-20 07:33:30 GMT
Created-Local: 2026-10-20 14:33:30 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Implement Slice F0 (Frozen Wire Fixtures) for dao.stream Codec Track
Role: Implementation Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-20 14:33:30 +07 | Status: active | Rationale: F0 implementation in isolated worktree per owner directive to allocate implementation to Claude.

Working Directory: /Users/sto/workspace/datomworld-codec-f0
Governing Design: /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md (§7.1 F0, §7.3 Wire Proof)

Instructions:
You are implementing Slice F0 of the dao.stream codec track. This slice records frozen pre-change wire fixtures and adds the fixture test suite.
DO NOT change any code under src/. This slice touches ONLY test/ and fixture files.

Per §7.3 of the design doc, record frozen pre-change wire fixtures under `test/fixtures/codec/` (or suitable subdirectory loaded by all three lanes: JVM, Node/CLJS, and Dart/CLJD):
- CBOR payloads stored as hex strings, Transit payloads as JSON text, expected values as EDN.
- Areas to cover for both profiles (`dao.stream.transit/profile` and `dao.stream.cbor/profile`):
  1. Negotiation: exact subprotocol strings ("dao.stream.transit-json", "dao.stream.cbor"), one offer that must be refused, no-offer case.
  2. Descriptors: served descriptor and dialed descriptor (port 0 and fixed port) encoded by each profile's descriptor path.
  3. Envelopes: {:ws/frame :ws/value :ws/value v} for the §4.4 law corpus (all base domain D cases) plus each profile's extra values (CBOR: host bytes, metadata, list; Transit: / -bearing keyword).
  4. Canonical rejection: 10 CBOR rejection payloads from cbor_test.cljc:187-196, and Transit text decoding to an out-of-domain value, each expected to be refused.
  5. Jing wrapping: canonical Jing byte string wrapped by dao.jing.stream/wrap-bytes for each lane and encoded by that profile; unwrapped bytes as hex.

Add a fixture test suite (e.g. `test/dao/stream/codec_fixtures_test.cljc` or matching repo conventions):
- Decoding each fixture yields expected value under §4.4 equality rule.
- For CBOR fixtures: re-encoding yields fixture bytes exactly (canonical wire).
- For Transit fixtures: decodes to expected value (semantic interoperability; no strict byte-equality check).
- Rejection payloads are refused.
- Negotiation strings match profile offers.

Verification requirement:
- Run `bb test:sub:clj dao.stream` (G0) and ensure the new fixture test passes cleanly.
- Run `bb test:sub dao.stream` (G1) across all three lanes (JVM, CLJS, CLJD) and ensure all pass.
- Record test and assertion counts before and after.

Output findings report to:
/Users/sto/workspace/datomworld/collab/1792481610000-engineer-codec-f0-fixtures.claude-sonnet-5-5.findings.md
Include exact commands executed, test outputs and assertion counts, files created, and commit-readiness.
