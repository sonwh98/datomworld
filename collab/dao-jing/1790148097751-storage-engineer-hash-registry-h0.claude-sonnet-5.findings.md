Completed-GMT: 2026-09-23 07:34:00 GMT
Completed-Local: 2026-09-23 14:34:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: a00ad15a-2e8b-4319-a92f-b6ef5b3990b8

## 1. Files touched

| File | Status | Diffstat |
|---|---|---|
| `docs/design/dao.jing.cbor.md` | edited | +38/-21 |
| `docs/design/dao.jing.call-site-classification.md` | new | 267 lines |
| `test/resources/dao/jing/blake3-vectors.edn` | new | 38 lines |
| `test/resources/dao/jing/digest-table.edn` | new | 132 lines |
| `test/dao/jing/hash_registry_contract_test.cljc` | new | 441 lines |

`git status` confirms no other paths changed — no `src/`, `deps.edn`, `package.json`, or `pubspec.yaml` touched.

Note: vectors file named `blake3-vectors.edn` rather than `.json` — EDN shares one portable `clojure.edn` reader with `digest-table.edn` across JVM/CLJS/CLJD.

## 2. Test outcomes

- `clojure -M:test -n dao.jing.hash-registry-contract-test` → **13 tests, 62 assertions, 0 failures, 0 errors**
- `clojure -M:test -n dao.jing-test` (regression) → **39 tests, 229 assertions, 0 failures, 0 errors**
- `clj -M:kondo --lint test/dao/jing/hash_registry_contract_test.cljc` → **0 errors, 0 warnings**

## 3. Call-site classification summary

`docs/design/dao.jing.call-site-classification.md` inventories, by class:
- **Class 1 (frozen)**: the 5 direct `jing/sha256` consumers, `yin.vm.debruijn/dimension-hash` recorded accurately as a `def`, `primitive-profile`'s mislabel flagged as the one Class-1 site needing an H1 source edit, and the absent register contract recorded as future integration.
- **Class 2 (mint)**: 10 direct mint sites, `request-materialize`/`materialize-async-fn` classified as default-only remote mint entry points, and `checkpoint-candidate`'s bare `:schema-hash` flagged as non-conforming (H1 → `:schema-address`).
- **Class 3 (validate)**: 20 call sites (covering the design's 18 enumerated bullets — two bullets each cover more than one distinct site), all currently using one of two non-conforming comparison shapes, all requiring `segment-matches?` in H1.
- **Class 4 (copy)**: all four address-preserving paths, with `store-tree-async` detailed as needing a *new* address-supplying async remote operation (not a parameter bolted onto `materialize-async-fn`'s payload-only signature).

Inventories documentation-only SHA assumptions (for H1's docstring pass) and pins the architectural-lint target (`dao.jing/content-hash`, `dao.jing/segment-key`, fully-qualified-var-resolved, excluding `dao.jing.cbor/content-hash`).

## 4. Frozen contracts

No production code was touched; VM H/R, de Bruijn dimension/node hashes, and DHT node-id contract are untouched by construction.

## 5. Notes for H1

- `docs/design/dao.jing.cbor.md`: generalized file-backend frame and collision-resistance text.
- `hash_registry_contract_test.cljc` defines a private reference implementation of the frozen grammar/parser/predicate rules and pins it against fixtures — plus real `dao.jing/sha256-bytes`, `canonical-bytes`, `content-hash`, and `segment-key`. H1 will replace the reference `parse-segment-address`/`segment-matches?` with calls into `dao.jing`.
- Architectural lint specifies the decision procedure against representative forms.
- Digest table canonical-value rows are pinned to current print encoder; contract test guards this.
