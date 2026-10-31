Created-GMT: 2026-09-23 07:21:41 GMT
Created-Local: 2026-09-23 14:21:41 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: a00ad15a-2e8b-4319-a92f-b6ef5b3990b8

# Task: hash-registry-h0 — Contract and Evidence for DaoJing Multihash Content Addressing

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-23 14:21:41 +07 | Status: active | Rationale: Phase H0 contract, evidence, test fixtures, call-site classification, and documentation alignment for multihash content addressing

Work in /Users/sto/workspace/datomworld (your launch directory; branch master, HEAD ba88f769). Do NOT stage, commit, merge, or push.

---

## Context & Governing Documents

Read these governing documents completely first:
1. `docs/design/dao.jing.hash-registry.md` (authoritative, architect signed off by `gpt-5.6-sol`)
2. `docs/design/dao.jing.cbor.md` (especially lines 40-50, 480-515)
3. `docs/design/datom.world.md` (architectural invariants and clean-break philosophy)
4. `archive/1790147499393-architect-hash-registry-signoff.gpt-5.6-sol.findings.md` (formal architect sign-off directives)

This is Phase H0 ("Contract and evidence") of the DaoJing Multihash Content Addressing rollout.
Like Phase B0 of the de Bruijn VM epic, Phase H0 adds NO dependency to `deps.edn`/`package.json`/`pubspec.yaml` and edits NO production code in `src/cljc/dao/jing.cljc`. It freezes the contract, evidence, test fixtures, call-site classification, and documentation that subsequent phases (H1 whole-system cutover, H2 verification) will depend on.

---

## File Box (Strictly Bounded)

Allowed edits and new files:
- **EDIT**: `docs/design/dao.jing.cbor.md`
- **NEW**: `docs/design/dao.jing.call-site-classification.md`
- **NEW**: `test/resources/dao/jing/blake3-vectors.json` (or `.edn`)
- **NEW**: `test/resources/dao/jing/digest-table.edn`
- **NEW**: `test/dao/jing/hash_registry_contract_test.cljc`

Must **NOT** change:
- Production code in `src/` (reserved for Phase H1)
- Manifests: `deps.edn`, `package.json`, `pubspec.yaml` (reserved for Phase H1)
- VM format contracts (H and R descriptor hashes, image hash, dimension hash, node hash)
- DHT node-id routing contract
- Universal AST, emitter, or existing test suites outside the named contract test

---

## Directives & Deliverables

### 1. Update `docs/design/dao.jing.cbor.md` (Multihash Alignment)
- Reconcile all SHA-only address phrasing, examples, and pipeline diagrams (e.g. lines 43-44, lines 486-489) to reflect multihash-style addresses:
  - Address shape: `:segment/<algorithm-id>-<lowercase-hex-digest>`
  - Initial algorithms: `blake3` (minting default) and `sha256` (explicit peer)
  - Canonical pipeline: `write: value -> canonical CBOR bytes -> multihash address -> backend`
- Retain the clean-break ruling:
  - Canonical CBOR replaces the current encoder outright;
  - Addresses are regenerated;
  - No old-address reader, alias, or migration layer;
  - No encoding identifier or profile axis in DaoJing addresses.

### 2. Call-Site Classification (`docs/design/dao.jing.call-site-classification.md`)
Create a comprehensive, durable architectural classification document listing every Jing hash and address operation across `src/` and `test/`, grouped strictly into the four classes defined in `docs/design/dao.jing.hash-registry.md`:

1. **Frozen non-Jing hash contracts**:
   - `yin.vm.debruijn_code/descriptor-hash` (SHA-256)
   - `yin.vm.debruijn_code/image-hash` (SHA-256)
   - `yin.vm.debruijn/dimension-hash` (SHA-256) — **must accurately record this as a `def`**, computed once at namespace load
   - `yin.vm.debruijn/node-hash` (SHA-256)
   - `dao.jing.dht/node-id` (SHA-256)
   - Future register VM format contract: register R (`register-descriptor-hash` and `register-hash`, explicitly SHA-256, noting absent register implementation as future integration)
   - `yin.vm/primitive-profile`: classified as an explicit SHA-256 VM contract (`:yin.k.pp/sha256-...`), keeping its label truthful.

2. **New-content mint sites**:
   - All normal calls to `dao.jing/materialize!`, `dao.jing/segment-key`, and `dao.jing/content-hash` where fresh addresses are minted from source data.
   - Classification of `request-materialize` (`dao.jing.remote.step`) and `materialize-async-fn` (`dao.data.btree.storage`) as **default-only remote mint entry points**.
   - `dao.space.index/checkpoint-candidate`: replace bare `:schema-hash` with `:schema-address` minted via `(jing/segment-key schema)`.

3. **Address-directed validation sites**:
   Must all use `segment-matches?`. Detail each of the 18 identified validation sites:
   - `dao.jing/materialize!` on `:present` read-back
   - `dao.jing.mem/validate-address-payload!`
   - `dao.jing.file/validate-address-payload!`
   - `dao.jing.remote/validate-address-payload!`
   - `dao.jing.remote.step` verification of `:present` response
   - `dao.jing.dht/validate-address-payload!`
   - `dao.jing.dht` peer-fetched content verification before caching
   - `dao.jing.dht.node` store-request validation
   - `dao.data.btree.storage` optional fetched-blob verification in ordinary KV reader
   - `dao.space.index/read-manifest`
   - `dao.space.index/restore` schema validation
   - `yin.vm.content` row fetch verification
   - `yin.vm.content` vector fetch verification
   - `yin.vm.macro` row-address verification
   - `yin.vm.semantic` claimed-segment verification
   - `yin.vm.completion` reconstructed-image address verification
   - `yin.vm.ledger` row-address verification
   - `yin.vm.ledger` output-address verification
   - `yin.vm.ledger` record/derivation verification
   - `yin.vm` semantic-bytecode row validation

4. **Address-preserving copy paths (Four sites)**:
   - `dao.jing.dht/make-get`
   - Synchronous `dao.data.btree.storage/hydrate!`, `pull!`
   - Asynchronous `dao.data.btree.storage/hydrate-async`, `fetched!`
   - `dao.data.btree.storage/store-tree-async` remote flush of cache-minted blobs:
     - Must carry the source address (and algorithm) with the blob.
     - Must use an address-supplying operation (e.g. `put-content [address payload]`) rather than calling un-parameterized `request-materialize` / `materialize-async-fn`.
     - Remote store must validate the incoming address with `segment-matches?`.

### 3. Conformance Evidence & Test Fixtures
In `test/resources/dao/jing/`:
- **`blake3-vectors.json`**: Official BLAKE3-256 test vectors:
  - Empty input (0 bytes) -> `af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262`
  - Canonical official test vectors covering input lengths: 0, 1, 2, 3, 64, 1023, 1024, 1025, 2048, 16384 bytes (using the standard official vector sequence `input[i] = i % 251`).
  - Non-ASCII UTF-8 vectors (e.g. multilingual strings, emoji).
- **`digest-table.edn`**: Cross-provider digest table recording known digests across all three planned providers (`io.github.rctcwyvrn/blake3` 1.3 JVM, `@noble/hashes` 2.4.0 CLJS, `blake3_dart` 1.0.0 CLJD) for:
  - Empty bytes
  - Standard ASCII strings (e.g. `"datom.world"`, `"Hello, world!"`)
  - Canonical Clojure data structures encoded via the current canonical encoder
  - 1023, 1024, 1025 byte boundary buffers.

### 4. Contract Specification Tests (`test/dao/jing/hash_registry_contract_test.cljc`)
Implement contract tests that verify the specifications of Phase H0 (using SHA-256 fixtures and frozen BLAKE3 vector tables):
1. **Address Grammar & Parsing Specification**:
   - Pinned canonical forms: `:segment/blake3-<64 lowercase hex>` and `:segment/sha256-<64 lowercase hex>`.
   - Algorithm identifier regex: `^[a-z0-9]+$`.
   - Parsing specification: splits on first `-`, looks up algorithm in registry table.
   - Rejection cases: malformed prefixes (`:segment/`, `:invalid/sha256-...`), unknown algorithm (`:segment/sha512-...`, `:segment/md5-...`), uppercase hex (`:segment/sha256-ABCD...`), wrong length (`:segment/sha256-1234` or 63/65 chars), non-hex characters.
2. **Total Predicate Contract for `segment-matches?`**:
   - Test specification verifying that `segment-matches?` returns `false` on:
     - Malformed addresses
     - Unknown algorithms
     - Mismatched digests
     - Canonical encoder refusal (e.g. record types or values not supported by canonical encoder)
   - In contrast, minting operations (`segment-key`, `materialize!`) throw on encoder refusal.
3. **EDN Print/Read Round-Trip**:
   - Keyword addresses round-trip cleanly through Clojure reader/printer across JVM and JS.
4. **Architectural Lint / Guard Specification**:
   - Define and test a static check function / test that inspects ASTs or namespaces to ensure `dao.jing/content-hash` and `dao.jing/segment-key` are never called in equality-based validation outside `dao.jing`.
   - Specifically verify that it does NOT falsely flag `dao.jing.cbor/content-hash`.

---

## Verification

Before declaring completion:
1. Run focused tests:
   ```sh
   clojure -M:test -n dao.jing.hash-registry-contract-test
   clojure -M:test -n dao.jing-test
   ```
2. Lint:
   ```sh
   clj -M:kondo --lint test/dao/jing/hash_registry_contract_test.cljc
   ```
3. Inspect diff with `git status` and `git diff` to ensure:
   - Only files in the File Box were touched.
   - No production code in `src/` or dependency manifests were changed.
   - Formatting and style rules are respected.

---

## Response Format

Begin your response exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: a00ad15a-2e8b-4319-a92f-b6ef5b3990b8
```

Report:
1. List of created and modified files with diffstat.
2. Exact test outcomes and assertion counts.
3. Call-site classification summary.
4. Verification of frozen contracts (VM H/R, DHT node ID).
5. Any deviations, questions, or notes for Phase H1.
