Created-GMT: 2026-10-20 00:40:10 GMT
Created-Local: 2026-10-20 07:40:10 +07
Coding-Agent: claude
Session-ID: fresh

# Task: revise dao.stream codec design document to address architectural review findings (A1-A5)
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 07:40:10 +07 | Status: active | Rationale: Address findings from independent Codex (gpt-6-astra) architectural review.

Edit /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md in place to address all findings from /Users/sto/workspace/datomworld/collab/1792456650000-architect-stream-codec-design-review.gpt-6-astra.findings.md.
Do not edit any src/ or test/ files.

Findings to address:
1. A1 (Blocking - §4.4, §6.1, C0: Common domain & law test):
   - The proposed base predicate is not a clean subset of CBOR's domain: Transit accepts keywords like (keyword "a/b" "c") and collections with non-portable metadata, whereas CBOR explicitly rejects both (cbor.cljc:26-71, cbor_test.cljc:167-184). §6.1 also misstates Transit metadata handling (lists are admitted, metadata ignored).
   - Fix: Define the actual common swappable domain and its equality/projection rules separately from each legacy admission predicate. Preserve existing wire acceptance without silently narrowing Transit or widening CBOR under existing names. Specify adversarial identifier/metadata test cases.
2. A2 (Blocking - §7.1 C1-C4, §11: Migration sequence):
   - C1 immediately drops defaults and renames keys while remote_channel.cljc still omits codecs until C4, host adapters read :ws/subprotocol until C3, and Jing's old-key comparisons become nil-to-nil (wrongly selecting CBOR branch for Transit).
   - Fix: Restructure migration using expand/migrate/contract:
     - Expand: add compatible keys/accessors in dao.stream.codec, dual-lookup in profile helpers, host-bytes?/bytes? capability.
     - Migrate: update callers (Jing, host adapters, remote-channel, production compositions) to supply and accept explicit codec profiles.
     - Contract: drop legacy defaults, fallback keys, and deprecated aliases once all callers are migrated.
     Ensure every intermediate slice leaves all three lanes cleanly green.
3. A3 (Major - §7.1, §11: Wire proof & frozen fixtures):
   - New-code/new-code peer suites cannot prove wire compatibility because both ends can drift together.
   - Fix: Mandate frozen pre-migration wire fixtures and bidirectional old/new verification across lanes, covering negotiation, descriptors, envelopes, canonical rejection, and Jing wrapping.
4. A4 (Major - §5: Arrow stress test):
   - Arbitrary Arrow record batches with dictionary encoding require dictionary state or delta messages.
   - Fix: Qualify the Arrow sketch: restrict to dictionary-free batches, immutable precomposed dictionaries identified in the profile, or self-contained per-frame IPC payloads. Clarify that full base-domain and envelope mappings remain future codec obligations.
5. A5 (Minor - §2: Wasm boundary text):
   - Limit mandatory canonical CBOR to the wasm import-surface representation; clarify that in-memory representations (e.g., co-resident ring buffers) remain implementation-local per dao.stream.wasm.md.

Check that `grep -ci jolt docs/design/dao.stream.codec.md` remains 0.
Ensure table widths and markdown formatting conform to repo standards.

Write your findings summary to /Users/sto/workspace/datomworld/collab/1792456810000-architect-stream-codec-design-r2.claude-fable-5-1.findings.md.
