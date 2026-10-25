Completed-GMT: 2026-10-24 13:31:00 GMT
Completed-Local: 2026-10-24 20:31:00 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

All five accepted corrections are applied and every re-verification passes. The completion times above are estimates, not a clock reading. I only edited the files in my authority.

## Corrections
1. **`slice-size` (conformance test).** `same?` now handles `("row-count" "slice-size")` as `(= expected (count actual))`. Two new integrity checks:
   - A case whose `comparison` rule is not in `implemented-comparisons` may not be non-pending or carry a `daospace-observed` record.
   - Size comparisons need an integer expectation when they assert.
   A new test `every-vocabulary-comparison-is-implemented` also checks that the corpus vocabulary equals that implemented set.
2. **Pull-option cases.**
   - **Re-encoded:** `pull.default-option`, `pull.alias-option` and `pull.limit-option` now use the nested-vector form (`[[:person/age :default 0]]` etc.).
   - **Re-pinned:** I probed the core myself and pinned what it does:
     - `:default` returns `{:person/age 0 :db/id 4}`.
     - `:as :n` returns `{:n "Ken" :db/id 4}`.
     - `:limit 1` returns `{:person/email ("a2@x") :db/id 1}`.
   - **Classification:** all three are now `unverified`, `pending` and `local-dao-source`. The false "not admitted until specified" reasons now say the core honors the option and Client pull acceptance is unmeasured.
   - **Host list:** `pull.limit-option` is now `clj` only, because which email survives depends on enumeration order.
   - **Errata E10** records that the original pins captured list-shape refusal only and that the old digest `e129fd6a…` is superseded.
3. **`pull.reverse-attribute`.** New errata **E9** (a new entry, not an extension of E8) explains why it is `clj` only: the core returns reverse-reference values and `:limit` results as seqs, the kept element is enumeration order, and Datomic's shape is unmeasured. The case now cites E9 in `limitation`, and `pull.limit-option` cites E9 too, with E10 in its note.
4. **README oracle line.** It now reads `clj -M:oracle -m dao.space.datomic-query-oracle --backend unselected`.
5. **`:once` fixture.** `use-fixtures :once` re-asserts the `resource-blake3` pin and the ASCII check after the whole namespace runs. It is JVM-only, guarded by `#?(:cljd nil :clj …)`, as in the CBOR precedent.

## Digest
The new BLAKE3 is `109f88d7da8b3f2e9a9d06312fc7b49b2caaf8a31fbdde7ecfeb2611731143df`.
- It is pinned in the test literal and in `provenance.edn` `:digest :hex`, which I confirmed with a grep count of 1 in each file.
- The old `e129fd6a…` value no longer appears in `test/` or `src/`, apart from the historical mention in errata E10 and provenance.
- Provenance also gains E9 and E10 and a revision note on `:authored`.
- Case counts are unchanged: 80 cases, 31 executable, 9 difference, 6 refusal, 34 pending.

## Re-verification
| Check | Outcome |
|---|---|
| `clj -M:test -n dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions, 0 failures, 0 errors. It first failed only on the digest pin, as expected, before I updated the pins. |
| `clj -M:kondo --lint` on the fixtures, conformance test, oracle runner and `cbor_fixtures.cljc` | 0 errors, 0 warnings |
| `mise exec -- bb check:node-discovery` | `Node discovery OK: 213 source files outside test/` |
| `clj -M:oracle -m dao.space.datomic-query-oracle --backend unselected` | 77 eligible, 3 not eligible, `{:pending 77}`, output only to `target/datomic-q-v1-observations.edn` |

`git status` for `test`, `src` and `deps.edn` shows my 8 artifacts. It also shows `src/cljc/dao/stream/ble.cljc`, which I did not create or touch.

## Concerns
- **Still JVM-only:** CLJS and CLJD remain unrun. `pull.default-option` and `pull.alias-option` list all three hosts as declared intent, and E8 covers that.
- **`:once` fixture on other hosts:** it is not active on CLJS or CLJD, and the digest test itself still runs there.
