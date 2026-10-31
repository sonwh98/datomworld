Created-GMT: 2026-10-20 07:55:20 GMT
Created-Local: 2026-10-20 14:55:20 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Update dao.stream codec design doc to record F0 baseline exceptions amendment
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 14:55:20 +07 | Status: active | Rationale: Code reviewer (Codex gpt-6-astra) verified F0 implementation and confirmed it technically ready, but requires the architect to formally record the baseline exception scope amendment in docs/design/dao.stream.codec.md §7.3.

Task:
Edit /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md in place:
In §7.3 (Wire proof: frozen fixtures and bidirectional peers):
Clarify that the frozen fixture suite consists of:
1. Clean corpora (envelopes.edn): all entries must decode successfully under the §4.4 equality rule, and CBOR entries must re-encode exactly.
2. Baseline exceptions (exceptions.edn): records pre-change cross-host divergence and domain incompatibilities that exist in master@212c45dd prior to any slice:
   - Transit lists/nested-lists: JVM Transit decoder throws on list text ("outside portable domain"), while Node and Dart decode to vectors.
   - CBOR 1.0 (f93c00): JVM and Dart decode; Node rejects f93c00 as non-canonical (its encoder emits 01).
   These exceptions are verified to preserve their exact pre-change per-host outcomes across slices, preventing regressions without claiming false universal round-trip on pre-existing defective paths.

Ensure:
- `grep -ci jolt docs/design/dao.stream.codec.md` remains 0.
- Table and line formatting conform to repo rules (<= 170 cols, no em dashes).

Write brief summary to /Users/sto/workspace/datomworld/collab/1792482930000-architect-codec-f0-doc-amendment.claude-fable-5-1.findings.md.
