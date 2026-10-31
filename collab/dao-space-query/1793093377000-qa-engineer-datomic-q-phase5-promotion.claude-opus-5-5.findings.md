Completed-GMT: 2026-10-27 15:00:00 GMT
Completed-Local: 2026-10-27 22:00:00 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

The Phase 5 promotion is committed on `datomic-q-p5-ci` in three commits and not pushed. The JVM suites I ran and kondo are clean. Node, Dart, node-discovery and the Docker re-run were not run here, so the cljs/cljd behavior of the changed code and the new conformance assertions is unverified.

**Commits**
- `0f51e4ee` — U2: `min`/`max` with `n <= 0` now return an empty vector, implemented in the generic core. The adapter's positive-only check is relaxed too, so non-integers are still refused.
- `4ae6328f` — runner identity correction, in `datomic_query_fixtures.cljc` and `datomic_query_oracle.clj`:
  - A case now lists the exact positions that hold logical entity ids. No other number is ever rewritten.
  - A collision test pins that an ordinary number equal to a logical id stays unchanged.
  - `:e` is loaded as a reference, and graph nodes that appear only as reference targets are given `:fx/id`.
  - Each observation file records the runner version and the corpus digest.
- `8dbf6af2` — the promotion itself: corpus, observations archive, errata, provenance, README, contract doc and conformance test.

**Architect rulings applied**
- **U1:** the median text is transcribed verbatim into errata E15 and the contract. `agg.median-with-multiplicity` is now a `difference` case with its DaoSpace expectation (3.5) kept. The result-kind differences that `numeric-value` hides are recorded in E15: `agg.median` (long 41 against double 41.0) and `agg.median-grouped` (long 7 against double 7.0).
- **U2:** `agg.parameter-not-positive` is now a result assertion, and I added 5 cases:
  - `max 0`, `min -1` and `max -1`
  - grouped `min 0`
  - grouped `max 0`

  I measured all of them through the oracle, and `min` and `max` agree on every one (empty vector). Core and adapter tests cover min and max, grouped and ungrouped.
- **U3:** the supported-domain row is transcribed verbatim and there is a new contract point, `schema.absent-attribute`. `agg.empty-input` and `in.empty-source` are now `difference` cases (errata E17). I added `agg.empty-input-declared-count` and `agg.empty-input-declared-sum`, which name a declared attribute with no matching fact. Datomic returns no row for both, as DaoSpace does, so they are executable.
- **Carry-forwards:**
  - Fulltext is deferred: `ambient.fulltext-refused` stays an executable DaoSpace refusal with the Datomic observation marked inconclusive.
  - Both oracle runs are preserved in `datomic-q-v1.oracle-observations.edn`. The jupiter run is marked superseded for the 10 cases whose ids were not mapped.
  - `oracle-executed` evidence is set only where the oracle result was conclusive. The per-case `oracle` field keeps the outcome distinct, and the conformance test enforces the pairing.

**Final numbers for ratification** (corpus: 120 cases, 62 executable, 12 difference, 18 refusal, 28 pending; digest `692ba5a0…c54b`)

| Oracle outcome | Cases |
|---|---|
| Agrees, with an asserted DaoSpace value | 60 |
| Both refuse (different message or category) | 9 |
| Agrees with the contract expectation (pending, no DaoSpace-side pin yet) | 13 |
| Documented difference, confirmed | 17 |
| Datomic accepts what DaoSpace deliberately refuses | 10 |
| Inconclusive | 8 |
| Not observed | 3 |

- **Conclusive:** 109 cases carry `oracle-executed` evidence.
- **Inconclusive (8):** the 3 mixed-type `ties` cases that Datomic cannot hold, `in.multiple-sources`, the fulltext refusal and the 3 `bounds.*` cases.
- **Not observed (3):** the cases with no Client equivalent.
- **Documented differences (17):** numeric collapse, pull `:db/id`, median, absent attribute, recursion through negation, and the JavaScript/Dart carrier case.

All 10 of the earlier id-literal cases agree on the corrected runner. The 17 "contract expectation" or "documented" pending cases are not asserted against a DaoSpace-side pin.

**JVM results, all 0 failures**

| Suite | Tests | Assertions |
|---|---|---|
| conformance | 19 | 54 |
| `query-rules-test` | 24 | 78 |
| `query-test` | 72 | 282 |
| `query.datomic-test` | 30 | 238 |
| `query-aggregates-test` | 16 | 82 |
| `query.execution-test` | 42 | 261 |
| `query.datomic-execution-test` | 22 | 190 |
| `query.published-bounded-test` | 24 | 312 |
| `query-numeric-test` | 18 | 922 |
| `positional-query-test` | 11 | 27 |

Kondo on 7 changed files: 0 errors, 0 warnings.

**Needs your attention**
- **Node and Dart lanes:** the changed core file, the fixtures namespace and the conformance test are shared code. Node and Dart need the remote three-host run, which you asked for on the affected suites.
- **Re-observation:** the local run `:local-r2` was made against the structural corpus, digest `3bc9132f…`, before the outcome fields were added. I recorded that in provenance. To make the claim airtight you can re-run `docker/oracle/run-oracle.sh` on jupiter against the final corpus. Only the corpus digest in the observation file would change.
- **Pending agreements:** 13 pending cases agree only with the contract's stated expectation, not a DaoSpace-side pin. The adapter-level pins live in the adapter tests.

I did not commit `docs/orchestrator-log.md`, which was already modified. I added no Co-Authored-By trailer, following your standing commit rule over the harness attribution reminder.
