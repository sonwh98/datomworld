Created-GMT: 2026-09-24 10:25:19 GMT
Created-Local: 2026-09-24 17:25:19 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Adversarial Code Review of DaoJing CBOR Codec Swap

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-24 17:25:19 +0700 | Status: active | Rationale: Independent adversarial review of Storage Engineer implementation (Claude family author -> GPT family reviewer)

Perform a read-only adversarial review of the DaoJing CBOR codec swap in `/Users/sto/workspace/datomworld` on branch `dao-jing-cbor-swap`.

Read first:
- `docs/design/dao.jing.cbor.md`
- `docs/design/dao.jing.md`
- `docs/design/dao.jing.hash-registry.md`
- `docs/design/datom.world.md`

Inspect changed files:
- `src/cljc/dao/jing.cljc`
- `src/cljc/dao/jing/cbor.cljc`
- `src/cljc/dao/jing/file.cljc`
- `src/cljc/dao/jing/mem.cljc`
- `test/dao/jing_test.cljc`
- `test/dao/jing/hash_registry_contract_test.cljc`
- `test/dao/jing/mem_test.cljc`
- `test/resources/dao/jing/digest-table.edn`
- `test/dao/data/psset_fixtures.cljc`
- `test/dao/data/btree_durability_test.cljc`

Evaluate:
1. Canonical Bytes & Codec Integrity:
   - Does `dao.jing/canonical-bytes` strictly use `cbor/encode`?
   - Is `order-normalize` / `order-normalized-print` / `canonical-print` completely eliminated?
   - Is `segment-matches?` total (never throws; returns false on invalid addresses, digest mismatches, or `cbor/encode` refusal)?
   - Are `segment-bytes` and `segment-value` sound?
2. Clean Break Principle:
   - Verify zero backwards compatibility shims, dual decoders, or legacy print aliases.
3. Storage Framing & Immutability:
   - Does `dao.jing.file` frame binary payloads cleanly as `[digest payload-bytes]` CBOR arrays without leaking host types?
   - Does `dao.jing.mem` compare duplicate payloads by byte equality rather than object identity?
   - How does frame algorithm recovery behave during file replay?
4. Invariants & Hygiene:
   - Pure ASCII only.
   - Lines <= 80 columns.
   - No hidden mutations or host leakage.

Do not edit files. Treat prior claims as untrusted. Cite repository evidence (file:line) for every finding.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate.
