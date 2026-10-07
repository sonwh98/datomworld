Created-GMT: 2026-09-23 10:09:08 GMT
Created-Local: 2026-09-23 17:09:08 +07 (Indochina Time)
Coding-Agent: agy
Session-ID: b71a7066-b2ee-4d2f-a87c-c62b0d73a28a

# Task: DaoJing Multihash Content-Addressing Phase H2 (Multi-Algorithm Verification & Hardening)

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: gemini-pro | Assigned: 2026-09-23 17:09:08 +07 | Status: active | Rationale: Specialized multi-algorithm integration and hardening

Coordinate and implement Phase H2 in `/Users/sto/workspace/worktree-hash-registry-h2`.

Read first:
- `docs/design/dao.jing.hash-registry.md` (§H2: multi-algorithm verification and hardening, lines 582-630)
- `docs/design/dao.jing.call-site-classification.md`
- `test/dao/jing/hash_registry_contract_test.cljc`
- `src/cljc/dao/jing.cljc`
- `src/cljc/dao/jing/file.cljc`
- `src/cljc/dao/space/index.cljc`
- `src/cljc/yin/vm/semantic.cljc`

Acceptance criteria for Phase H2:
1. **Source & Diagnostic Hardening:**
   - In `src/cljc/dao/jing.cljc`: remove unused private var `hex-digits-set`; generalize ns docstring line 8 (`get reads only registered :segment/<algorithm>-... content addresses`); clarify `sha256-bytes` docstring.
   - In `src/cljc/dao/jing/file.cljc`: update `validate-codec-round-trip!` to accept `[payload algorithm]`, hashing both sides with `{:algorithm algorithm}` per §290-302; thread `(jing/segment-algorithm address)` from `make-put`; generalize collision docstring ("a hash collision").
   - In `src/cljc/dao/space/index.cljc`: generalize `valid-manifest?` docstring; update `read-manifest` mismatch diagnostic to derive algorithm from `manifest-address`; generalize `restore` docstrings to reference `:schema-address`.
   - In `src/cljc/yin/vm/semantic.cljc`: update mismatch diagnostic at line 624 to derive algorithm from `claimed`.
   - In `src/cljc/dao/jing/dht.cljc`, `src/cljc/dao/jing/remote/step.cljc`, `src/cljc/dao/data/btree/storage.cljc`: generalize residual SHA-only docstrings.
2. **Contract & AST Lint Activation:**
   - In `test/dao/jing/hash_registry_contract_test.cljc`: remove unused private vars (`hex-digit-pattern`, `digest-bytes-hex`); activate source-sweeping architectural AST lint guard (rejecting default-following equality validation across all `src/cljc` files, exempting `dao/jing.cljc` and `dao/jing/file.cljc`).
   - Add full official BLAKE3 vector conformance test (10 byte-length vectors, 5 non-ASCII UTF-8 vectors) verifying against host BLAKE3 provider on all platforms.
3. **Comprehensive Multi-Algorithm Integration Tests:**
   - In `test/dao/jing_test.cljc`: test storing and retrieving the same payload under both `:blake3` and `:sha256` in the same memory store and the same file store.
   - Test file replay of a file store containing records from both algorithms simultaneously.
   - Test cross-algorithm mismatch rejection in `segment-matches?`.
   - In `test/dao/jing/dht_test.cljc`: test caching and routing of both `:blake3` and `:sha256` addresses, ensuring SHA-256 addresses are preserved rather than re-minted.
   - In `test/dao/data/btree_durability_test.cljc`: test synchronous hydration (`pull!`), asynchronous hydration (`fetched!`), and `store-tree-async` flushing preserving explicitly SHA-256 node blobs.
4. **Documentation Alignment:**
   - In `docs/design/dao.jing.md`: distinguish algorithm, digest, address, minting, verification, and copying; state "Verification must be address-directed; minting primitives are not validators"; state copying addressed content preserves the address-carried algorithm.
   - Record dependency versions and MIT licenses for JVM, Node/CLJS, and Dart BLAKE3 providers.
5. **Clean Verification:**
   - All code ASCII-only, <= 80 columns, cljstyle clean.
   - Pass full test suite across JVM (`bb test:clj`), Node/CLJS (`bb test:cljs`), and ClojureDart (`bb test:cljd`).
