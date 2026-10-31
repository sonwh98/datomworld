Created-GMT: 2026-09-23 09:37:28 GMT
Created-Local: 2026-09-23 16:37:28 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: pending (provider-generated; capture on first response)

# Task: reviewer-hash-registry-h1 — independent adversarial review of DaoJing Multihash Content-Addressing Phase H1

Role: Reviewer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-23 16:37:28 +07 | Status: active | Rationale: independent reviewer for storage runtime and multihash content-addressing contracts, reviewing Phase H1 implementation

Work in /Users/sto/workspace/worktree-hash-registry-h1 (read-only; do NOT edit files or run git add/commit).
Evaluate git diff against master (`git diff master`).

## Read first, in full

1. `docs/design/dao.jing.hash-registry.md` (normative specification for multihash content-addressing and algorithm registry).
2. `docs/design/dao.jing.call-site-classification.md` (normative call site classification: Class 1 frozen, Class 2 mint, Class 3 validation, Class 4 copy).
3. `docs/design/datom.world.md` (foundational axioms and non-negotiable invariants).
4. `test/dao/jing/hash_registry_contract_test.cljc` (frozen contract tests).

## Review Mandate & Verification Obligations

Perform an adversarial defect hunt across the Phase H1 diff. Look for architectural contradictions, subtle invariant violations, and edge cases. In particular:

1. **Closed Algorithm Registry & Host Implementations (`src/cljc/dao/jing.cljc`):**
   - Registry is immutable and closed: `{:blake3 {:address-id "blake3", :digest-bytes 32}, :sha256 {:address-id "sha256", :digest-bytes 32}}`.
   - `default-hash-algorithm` is `:blake3`.
   - Host BLAKE3 primitives:
     - JVM: `io.github.rctcwyvrn.blake3.Blake3`
     - Node/CLJS: `@noble/hashes/blake3.js` and `noble-utils/bytesToHex`
     - ClojureDart: `blake3_dart` with `Uint8List` safety conversion
   - `parse-segment-address`: rejects malformed prefixes, unknown algorithms, wrong digest lengths, uppercase hex, and non-canonical spellings. Never throws.
   - `segment-matches?`: total verification predicate. Dispatches strictly on the algorithm carried by the address. Never throws (catches canonical-encoder exceptions and returns false).
   - Implicit minting (`content-hash`, `segment-key`, `materialize!`): defaults to `:blake3`, accepts explicit `{:algorithm :sha256}`. Throws on unsupported algorithms or canonical-encoder refusal.

2. **Class-3 Validation Sites (20 sites converted to `segment-matches?`):**
   - Verify that none of the 20 sites re-mint with default `segment-key` or `content-hash` to compare against an existing address.
   - Verify all 20 call sites use `(jing/segment-matches? address payload)`:
     1. `dao.jing/materialize!` (:present read-back)
     2. `dao.jing.mem/validate-address-payload!`
     3. `dao.jing.file/validate-address-payload!`
     4. `dao.jing.remote/validate-address-payload!`
     5. `dao.jing.remote.step` (:present response)
     6. `dao.jing.dht/validate-address-payload!`
     7. `dao.jing.dht/make-get` (peer-fetch verification)
     8. `dao.jing.dht.node` (store-content)
     9. `dao.data.btree.storage` (`kv-storage` -restore)
     10. `dao.space.index/read-manifest`
     11. `dao.space.index/restore` (schema validation)
     12. `yin.vm.content` (row fetch)
     13. `yin.vm.content` (vector fetch)
     14. `yin.vm.macro` (row-address)
     15. `yin.vm.semantic` (claimed-segment)
     16. `yin.vm.completion` (image->vector)
     17. `yin.vm.ledger` (row-address in verify-derivation)
     18. `yin.vm.ledger` (output-address in verify-derivation)
     19. `yin.vm.ledger` (derivation-mismatch check)
     20. `yin.vm` (semantic bytecode row validation)

3. **Class-4 Copy Sites (4 sites converted to algorithm preservation):**
   - Site 1: `dao.jing.dht/make-get` (caching preserves address algorithm)
   - Site 2: `dao.data.btree.storage/hydrate!` (`pull!` preserves address algorithm)
   - Site 3: `dao.data.btree.storage/hydrate-async` (`fetched!` preserves address algorithm)
   - Site 4: `dao.data.btree.storage/store-tree-async` (flushes unacked blobs carrying address via address-supplying put operation)

4. **Class-1 & Class-2 Updates:**
   - `yin.vm/primitive-profile`: uses explicit `{:algorithm :sha256}`.
   - `dao.space.index/checkpoint-candidate`: mints `:schema-address` via `(jing/segment-key schema)` without legacy `:schema-hash`.

5. **Style and Invariant Checks:**
   - Formatting: `cljstyle` clean.
   - Line width: strictly <= 80 columns across all modified lines.
   - Character encoding: ASCII-only in all source code.

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <session-id>

Followed by your verdict (READY, READY WITH CHANGES, or REJECTED) and categorized findings (P1, P2, P3).
