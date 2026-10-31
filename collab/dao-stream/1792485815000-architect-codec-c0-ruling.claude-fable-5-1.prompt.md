Created-GMT: 2026-10-20 08:43:35 GMT
Created-Local: 2026-10-20 15:43:35 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Architect Ruling on Slice C0 Law vs Baseline Exception (R2) and Doc Alignment
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 15:43:35 +07 | Status: active | Rationale: Code reviewer (Codex gpt-6-astra) reported R2 as an acceptance blocker for C0: §4.4 states that the round-trip law holds for every value in D over both profiles, but JVM Transit decoder throws on list payloads (which was frozen as an exception in exceptions.edn in F0). Architect ruling and doc amendment required.

Task:
1. Examine R2:
   - In master@212c45dd, `(transit/encode '(1 2))` produces JSON text, but on JVM `transit/decode` returns an unportable seq rejected by its own post-decode predicate. In Slice F0, this was officially recorded in `test/fixtures/codec/transit/exceptions.edn` as a baseline exception.
   - §4.4 and §7.1 C0 described the law test as running over all of D for both profiles, creating a contradiction between the universal law claim and the requirement to preserve existing behavior without silently patching Transit decoder in C0.
2. Rule and amend `docs/design/dao.stream.codec.md`:
   - Clarify in §4.4 and §7.1 C0 that the shared §4.4 law corpus run over both profiles covers all of D except pre-existing baseline exception paths (specifically JVM Transit list decoding, which is pinned as a baseline exception in exceptions.edn and tested as CBOR-round-trip in C0).
   - Ensure the doc explicitly authorizes testing list round-trip on CBOR while verifying Transit's exact frozen baseline exception behavior.
3. Check `grep -ci jolt docs/design/dao.stream.codec.md` remains 0.

Write brief ruling summary to /Users/sto/workspace/datomworld/collab/1792485815000-architect-codec-c0-ruling.claude-fable-5-1.findings.md.
