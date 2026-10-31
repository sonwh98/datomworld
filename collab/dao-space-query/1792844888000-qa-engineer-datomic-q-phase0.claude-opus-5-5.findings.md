Completed-GMT: 2026-10-24 12:38:45 GMT
Completed-Local: 2026-10-24 19:38:45 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

All 8 deliverables are in place and the focused JVM gate passes. Only JVM verification was done, and the Datomic oracle itself has not run: no Client backend is selected, so no case is `oracle-executed`.

## Changed files
New:
- `test/resources/dao/space/datomic-q-v1.json`
- `test/resources/dao/space/datomic-q-v1.README.md`
- `test/resources/dao/space/datomic-q-v1.errata.md`
- `test/resources/dao/space/datomic-q-v1.provenance.edn`
- `test/dao/space/datomic_query_fixtures.cljc`
- `test/dao/space/datomic_query_conformance_test.cljc`
- `src/dev/dao/space/datomic_query_oracle.clj`

Modified:
- `deps.edn`: one `:oracle` alias holding `com.datomic/client-api 1.0.69`, plus its comment. It is a 5-line insertion and `:deps` is untouched.

## Corpus
- **Size:** 80 cases, 28 contract points, 9 fixtures.
- **Status split:** 31 executable, 9 difference, 6 refusal, 34 pending.
- **Digest:** BLAKE3 `e129fd6ab179001238e56fc3e0b8e28a81aeb1d94855b2568752b06d662f9d43`, pinned in the test and recorded in provenance.
- **Value kinds:** all five web-app workload classes are covered. Numeric kinds are explicit tags (`decimal`, `float64-bits`, `bigint`, `ratio`).
- **Expectations:** executable expectations are the core's observed output, checked by probing it, and are labelled DaoSpace-side only.
- **Unverified cases:** these stay `pending`. A `daospace-observed` record on a pending case is a regression pin, not a compatibility claim.
- **Generator:** the corpus was authored once with a throwaway generator that I deleted. Future edits are by hand, under the README's promotion procedure.

## Verification
| Check | Outcome |
|---|---|
| `clj -M:test -n dao.space.datomic-query-conformance-test` | 16 tests, 39 assertions, 0 failures, 0 errors |
| Separate fixtures test ns | not added; unit checks (typed-value kinds, refused untagged or fractional data) are folded into the conformance ns |
| `clj -M:kondo --lint` on the 3 new code files, with `cbor_fixtures.cljc` | 0 errors, 0 warnings |
| `mise exec -- bb check:node-discovery` | `Node discovery OK: 212 source files outside test/` |
| `git status --porcelain` | the 7 new files and `deps.edn` are mine; the other entries predate this work or sit outside the file list (`collab/`, `.jolt/`, `bin/claude-cloud`, `docs/orchestrator-log.md`, `docs/design/dao.space.query.datomic-compat.md`, `demo-broke.md`, `foo.clj`) |
| Oracle smoke, `clj -M:oracle -m dao.space.datomic-query-oracle --backend unselected` | 77 eligible cases all reported `:pending`, 3 not eligible, output written to `target/datomic-q-v1-observations.edn` |

Kondo run on the three files alone gives two "unresolved var" warnings, `cfx/hex->bytes` and `fx/parse`, because it has no cache of `cbor_fixtures.cljc`. With that file included they are gone.

The oracle runner also refused `--out test/resources/dao/space/x.edn` ("refusing to write observations into the frozen corpus"), and the frozen JSON was not modified.

## Unresolved concerns and incomplete work
1. **CLJS and CLJD not run.** Only the Node discovery preflight ran; no G1 three-host run. Cases list `cljs` and `cljd` as declared intent. Cases listing only `clj` are numeric-collapse (E1) or float-carrier (E8), plus the JVM-specific reverse-pull observation. Provenance and errata E8 say this.
2. **Oracle execution path is untested.** I wrote the selected-backend loader and `q` runner (`observe-selected`, `load-fixture!`) without a Client backend and never executed them.
   - The `:ref-attrs` configuration for reference attributes is a heuristic.
   - The mapping of allocated entity ids back to logical ids is also a heuristic.
3. **Core defects recorded as errata, not fixed (E2–E7):**
   - String queries return `#{[]}`.
   - An unbound find variable yields a nil row.
   - A trailing map, even a database value, is accepted as options.
   - `(min 2 ?a)` silently drops its parameter.
   - Recursive pull markers fail with a raw host exception.
   - Aggregates over empty input return no row, which is unmeasured against Datomic.
4. **Phase 0 contract points are only partly exercised.** 34 of 80 cases are `pending`: the adapter, option validation and the timeout and bounds machinery don't exist. Pagination cases check only `slice-size`, and no executable case calls them yet.
5. **JSON is dense.** It is 52 KB with one case per line; I kept that deliberately.
