Created-GMT: 2026-10-20 01:03:40 GMT
Created-Local: 2026-10-20 08:03:40 +07
Coding-Agent: claude
Session-ID: fresh

# Task: revise dao.stream codec design document (r3) to address round 2 review findings (A1-r2 through A4-r2)
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 08:03:40 +07 | Status: active | Rationale: Address focused round 2 review findings from Codex (gpt-6-astra).

Edit /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md in place to address all findings from /Users/sto/workspace/datomworld/collab/1792457440000-architect-stream-codec-design-r2-review.gpt-6-astra.findings.md.
Do not edit any src/ or test/ files.

Findings to address:
1. A1-r2 (Blocking - §4.4:328-360, §6.1: Symbol metadata in common domain D):
   - D restricts metadata on collections, but its identifier clause admits symbols regardless of metadata. Thus `(with-meta 'x {:k <host-object>})` falls into D. Dart CBOR encoding recursively encodes symbol metadata and throws on host objects (cljd.cljd:158-165,185-187).
   - Fix: Require symbol metadata to be checked recursively in D (with constructor-metadata normalization). Keep legacy predicates unchanged; qualify the claim that CBOR's domain is exactly D plus bytes. Add direct and nested symbol-metadata adversarial cases in C0 test specification.
2. A2-r2 (Major - §3.2:206-217, §7.1 C1/C4, §10: Slice acceptance precision):
   - C1: Change "unknown-name profile is rejected" to "malformed profile is rejected", and positively require testing a complete unfamiliar-name profile with each bytes capability (true and false) to verify capability-based dispatch works.
   - C4: Make C4's grep and gate tests explicitly conditional on OQ3 (if OQ3 retains :ws/*, C4 does not assert absence of :ws/*).
   - Specify transition for C0's dual-key gate tests when C4 contracts dual acceptance.
3. A3-r2 (Minor - §1:94, §7.3:671-693, §11: Wire proof precision):
   - State unchanged wire contract and interoperability; distinguish canonical CBOR byte stability from Transit semantic wire interoperability (since Transit output is not strictly byte-compared).
   - Do not report a recorded Dart gap as verified coverage.
4. A4-r2 (Minor - §5:395-403, §8: Arrow multi-IPC payload clarity):
   - Fix frame-kind row: state that one WebSocket message carries one self-contained codec payload, potentially containing multiple IPC messages (schema, dictionaries, and record batch).

Verify:
- `grep -ci jolt docs/design/dao.stream.codec.md` MUST remain 0.
- All ASCII tables formatted cleanly, line width <= 170 columns, no em dashes.

Write your findings summary to /Users/sto/workspace/datomworld/collab/1792458220000-architect-stream-codec-design-r3.claude-fable-5-1.findings.md.
