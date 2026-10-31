Created-GMT: 2026-09-24 09:36:05 GMT
Created-Local: 2026-09-24 16:36:05 +0700
Coding-Agent: claude
Session-ID: 0905c1a1-ff26-4582-9cb5-ec25468e7593

# Task: DaoJing CBOR Codec Swap

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-24 16:36:05 +0700 | Status: active | Rationale: Implementation of DaoJing CBOR codec swap per team.md and dao.jing.cbor.md

Implement the DaoJing CBOR codec swap on branch `dao-jing-cbor-swap` in `/Users/sto/workspace/datomworld`.

Read first:
- `docs/design/dao.jing.cbor.md`
- `docs/design/dao.jing.md`
- `src/cljc/dao/jing.cljc`
- `src/cljc/dao/jing/cbor.cljc`
- `src/cljc/dao/jing/mem.cljc`
- `src/cljc/dao/jing/file.cljc`
- `test/dao/jing_test.cljc`
- `test/dao/jing/hash_registry_contract_test.cljc`
- `test/resources/dao/jing/digest-table.edn`

Architectural Context & Governing Invariants:
1. Clean break, no backwards compatibility per standing owner ruling:
   Do not introduce dual-reading shims, legacy decoders, or compatibility wrappers.
2. Canonical bytes contract:
   `(dao.jing/canonical-bytes v)` must produce canonical CBOR bytes via `(dao.jing.cbor/encode v)`.
   Remove `order-normalize`, `canonical-print`, and `order-normalized-print` from `dao.jing.cljc`.
3. Total predicate contract:
   `dao.jing/segment-matches?` must remain total. If `cbor/encode` throws on an unencodable value (e.g. `::cbor/refusal`), catch it and return `false`.
4. Storage framing (`dao.jing.file` and `dao.jing.mem`):
   Ensure storage backends store/return opaque CBOR binary payloads or frames as specified in `docs/design/dao.jing.cbor.md`.
5. Fixture regeneration:
   - Regenerate `test/dao/data/psset_fixtures.cljc` using `clj -M:dev -m psset-fixtures`.
   - Update canonical value entries in `test/resources/dao/jing/digest-table.edn`.
   - Update pinned hashes in `test/dao/jing_test.cljc` and `test/dao/stream/transit_test.cljc`.
6. Code & formatting standards:
   - Pure ASCII only.
   - Lines strictly <= 80 columns.
   - Zero linter errors or warnings (`clj -M:kondo`).
   - Clean `cljstyle check`.

Verification:
Run focused checks and whole suites:
- `clj -M:kondo`
- `cljstyle check`
- `bb test:clj`
- `bb test:cljs`
- `bb test:cljd`

Work only in named files. Preserve unrelated changes, do not weaken tests, and preserve storage/query separation. Inspect the final diff.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any incomplete work. Do not claim edits or tests that did not occur.
