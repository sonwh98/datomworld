Created-GMT: 2026-09-23 07:37:33 GMT
Created-Local: 2026-09-23 14:37:33 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 95116c74-2f68-4276-99a8-56291df8587e (resume of your prior hash-registry review session)

# Task: reviewer-hash-registry-h0 — Independent Review of Phase H0 (Contract and Evidence)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-23 14:37:33 +07 | Status: active | Rationale: independent adversarial review of Phase H0 implementation; resuming existing reviewer session to conserve weekly quota.

Work in /Users/sto/workspace/datomworld (your launch directory; branch master). READ-ONLY STATIC REVIEW. Do NOT edit any files, do not stage, commit, merge, or push.

---

## Review Scope

Review the Phase H0 deliverables implemented by Claude Sonnet 5 in the working tree against:
1. `docs/design/dao.jing.hash-registry.md` (authoritative, architect signed off by `gpt-5.6-sol`)
2. `archive/1790147499393-architect-hash-registry-signoff.gpt-5.6-sol.findings.md` (Lead System Architect sign-off directives)
3. `collab/1790148097751-storage-engineer-hash-registry-h0.claude-sonnet-5.findings.md` (implementer's report)

Inspect the uncommitted working tree diff:
- `git diff docs/design/dao.jing.cbor.md`
- `docs/design/dao.jing.call-site-classification.md` (new)
- `test/resources/dao/jing/blake3-vectors.edn` (new)
- `test/resources/dao/jing/digest-table.edn` (new)
- `test/dao/jing/hash_registry_contract_test.cljc` (new)

---

## Review Checklist

Check each of the following areas adversarially against repository source and governing documents:

1. **Contract Boundaries & Address Grammar**:
   - Pinned canonical forms: `:segment/blake3-<64 lowercase hex>` and `:segment/sha256-<64 lowercase hex>`.
   - Algorithm charset: `^[a-z0-9]+$`. First hyphen splitting followed by exact table lookup.
   - Total predicate contract: `segment-matches?` returns `false` on malformed addresses, unknown algorithms, mismatched digests, and documented canonical-encoder refusal.
   - Minting operations (`segment-key`, `materialize!`) throw on encoder refusal.
   - No encoding identifier or profile axis in addresses.

2. **Call-Site Classification (`docs/design/dao.jing.call-site-classification.md`)**:
   - **Class 1 (Frozen non-Jing contracts)**:
     - 5 direct `jing/sha256` consumers listed.
     - `yin.vm.debruijn/dimension-hash` accurately recorded as a `def`, not a function.
     - `primitive-profile`: classified as an explicit SHA-256 VM contract requiring an H1 source edit to stay truthful.
     - Register R contract: absent register implementation accurately recorded as future integration.
   - **Class 2 (New-content mint sites)**:
     - 10 direct mint sites listed.
     - `request-materialize` and `materialize-async-fn` classified as default-only remote mint entry points.
     - `dao.space.index/checkpoint-candidate`: `:schema-hash` replacement with `:schema-address` identified.
   - **Class 3 (Address-directed validation sites)**:
     - All 18 enumerated validation bullets (20 actual call sites) cataloged with line numbers and current non-conforming shapes.
     - All requiring `segment-matches?` in H1.
   - **Class 4 (Address-preserving copy paths)**:
     - All four copy paths detailed (`make-get`, synchronous `hydrate!`, asynchronous `hydrate-async`, and `store-tree-async` remote flush).
     - `store-tree-async`: confirms requirement for an address-supplying remote operation (`put-content [address payload]`) with remote `segment-matches?` validation.
   - **Architectural Lint Target**:
     - Qualified to `dao.jing/content-hash` and `dao.jing/segment-key`.
     - Explicitly excludes `dao.jing.cbor/content-hash`.

3. **Evidence & Fixtures**:
   - `blake3-vectors.edn`: Official BLAKE3 known-answer vectors (empty input, chunk boundaries 1023, 1024, 1025, multi-chunk 2048, 16384, non-ASCII UTF-8 strings).
   - `digest-table.edn`: Cross-provider digest table for empty bytes, ASCII strings, canonical Clojure structures, and boundary buffers.

4. **Contract Tests (`test/dao/jing/hash_registry_contract_test.cljc`)**:
   - Coverage of grammar, rejection of malformed addresses, unknown algorithms, uppercase hex, wrong lengths.
   - Total `segment-matches?` predicate tests (returning `false` on encoder refusal vs throwing on minting).
   - EDN print/read round-trip.
   - Architectural lint test verifying rejection of equality validation using `content-hash`/`segment-key` while asserting non-misclassification of `dao.jing.cbor/content-hash`.

5. **CBOR Document Alignment (`docs/design/dao.jing.cbor.md`)**:
   - Multihash address alignment cleanly integrated.
   - Clean-break ruling preserved.

6. **File Box & Invariant Compliance**:
   - No production code in `src/` was changed.
   - No manifests (`deps.edn`, `package.json`, `pubspec.yaml`) were changed.
   - Frozen VM H/R and DHT routing contracts are untouched.

---

## Response Format

Begin your response exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 95116c74-2f68-4276-99a8-56291df8587e
```

Report:
1. Verdict: **READY to commit**, **READY WITH CHANGES**, or **NOT READY**.
2. Actionable findings categorized by severity:
   `P0-P3 | file:line | evidence | concrete fix`
   State "No actionable findings" if none exist.
3. Specific evaluation of call-site classification completeness, test fixture validity, and CBOR document alignment.
