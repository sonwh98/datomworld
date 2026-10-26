# datomic-q-v1 conformance corpus

The frozen corpus for `dao.space.query.datomic`, the Datomic Client query
compatibility track (`docs/design/dao.space.query.datomic-compat.md`, section
"Conformance corpus and oracle"). It follows the discipline of
`test/resources/dao/jing/cbor-v1.json`: frozen cases, recorded provenance,
explicit exceptions, and no test that regenerates its own expectations.

| File | Role |
|---|---|
| `datomic-q-v1.json` | The corpus (pure ASCII; its text is its bytes) |
| `datomic-q-v1.README.md` | This document |
| `datomic-q-v1.errata.md` | Known limitations; nothing is excluded silently |
| `datomic-q-v1.provenance.edn` | Digest, artifacts, baseline, oracle state |
| `test/dao/space/datomic_query_fixtures.cljc` | Portable loader and typed-value reconstruction |
| `test/dao/space/datomic_query_conformance_test.cljc` | Integrity checks and DaoSpace-side cases |
| `src/dev/dao/space/datomic_query_oracle.clj` | JVM-only Client oracle runner |

## What this corpus does and does not prove

No case is `oracle-executed` yet. A green conformance run means the corpus is
intact and the DaoSpace core behaves as recorded. It does **not** mean
Datomic compatibility: agreement among DaoSpace implementations alone cannot
establish it, and an unverified expectation never counts as a passing
conformance assertion. Invented expected answers are not attributed to
Datomic.

## Evidence classes

Evidence is separate from compatibility classification.

| Evidence | Meaning |
|---|---|
| `local-client-source` | Inspected source in the locally installed Client API 1.0.69 jar |
| `local-dao-source` | Inspected DaoSpace implementation, existing tests, or recorded core behavior |
| `oracle-executed` | Recorded execution through the public Client API against an identified backend (none yet) |
| `unverified` | Not yet established by the necessary evidence |

Classification (`classification`): `exact-target`, `refused`,
`documented-difference`, `unverified`.

## Case statuses

| Status | The test asserts |
|---|---|
| `executable` | `q` + `collect` over the fixture equals the expectation (DaoSpace-side only) |
| `difference` | Same, for a documented difference from Datomic; requires classification `documented-difference` |
| `refusal` | The core throws; the message contains `expect.message-contains` |
| `pending` | Only that the case is pending and carries no answer. An optional `daospace-observed` record is a regression pin of today's core behavior, labelled as not a compatibility claim |

A case whose classification is `unverified` must be `pending`.

## Case schema

Each case is one JSON object (one line in the file):

| Key | Meaning |
|---|---|
| `id` | Stable id; never reused |
| `point` | Supported-domain contract point (declared in `points`) |
| `workload` | Optional: `entity-view`, `list-surface`, `session-scoped-read`, `view-summary`, `bounded-failure` |
| `note` | Optional prose |
| `query` | Typed query form |
| `inputs` | Ordered `:in` inputs: `{"db": fixtureId}` or `{"value": datum}` |
| `options` | Optional typed option map (`:offset`, `:limit`, `:timeout`, ...) |
| `classification`, `evidence`, `status` | As above |
| `expect` | `{"kind":"value","value":..}`, `{"kind":"refusal","message-contains":..}`, or `{"kind":"pending","reason":..}` |
| `daospace-observed` | Optional regression pin, same shape as a value/refusal `expect` |
| `comparison` | Rule below |
| `hosts` | Hosts the case runs on: `clj`, `cljs`, `cljd` (declared intent; see errata E8) |
| `oracle` | `pending` until promoted |
| `limitation` | Optional errata id (`E1`..); the errata entry must exist |

### Comparison rules

| Rule | Meaning |
|---|---|
| `exact` | host `=` over the decoded values (sets and relations are unordered by nature) |
| `unordered-coll` | collection finds: multiset equality (enumeration order is unspecified) |
| `unordered-nested` | pull results: vectors that are map values compare as multisets |
| `row-count` | the expectation is the integer size of the result (used for E1) |
| `slice-size` | pagination: the size of the slice only; never page membership or order |
| `numeric-value` | numerics agree when they compare equal in exact numeric order (kind-loose); sets and sequences structurally; everything else by `=` (used where a host cannot tell an integral double from the integer) |

Offset/limit is an unordered query slice, not stable ordered pagination. No
case promises order or cross-call page membership. A numeric tolerance is
never a blanket rule; one would need a per-case justification recorded in the
case.

## Fixture DSL and identity normalization

`fixtures` maps a fixture id to `{"view","note","facts"}`. `view` is
`facts` (a DaoSpace current-fact relation of d3 rows, via
`query/fact-relation`) or `current-d5` (d5 rows `[e a v t m]`, `m` 1 asserts
and 0 retracts, via the explicit `current` interpreter). Fixture entity ids
are small integers used as logical ids.

The DaoSpace loader (`fixture-source`) takes the ids as is. The Datomic
oracle loader is independent: it transacts the same logical facts under
tempids and maps the allocated entity ids back to the logical ids, including
ids embedded in pull results and reference values. It must not normalize away
cardinality or missing values. Transaction ids are outside the accepted
domain.

## Value representation

Plain JSON numbers never carry identity. `null`, booleans, strings and
safe-range integers (|n| <= 2^53-1) are themselves; a bare fractional or
oversized number is refused by the loader. A JSON array is a vector. An
object has exactly one tag key:

| Tag | Value | Reconstructs |
|---|---|---|
| `kw` | `"ns/name"` | keyword |
| `sym` | `"?e"` | symbol |
| `list` | array | list |
| `set` | array | set |
| `map` | `[[k,v],..]` | map |
| `bigint` | `"123"` | big integer |
| `decimal` | `{"exponent":-2,"mantissa":"100"}` | decimal of exact scale |
| `float64-bits` | 16 hex digits | IEEE-754 double |
| `ratio` | `["1","3"]` | rational |

## Supported-domain inventory

The `points` array mirrors the supported-domain table of the contract
(28 points). The integrity test requires every point to have at least one
case, every case to cite a declared point, and all five web-app workload
classes to appear. Phase 0 totals: 113 cases (56 executable, 9 difference, 19
refusal, 29 pending), 12 fixtures. The digest and counts are in
`datomic-q-v1.provenance.edn`.

## Regeneration and promotion procedure

1. Tests never rewrite the corpus, its digest, or provenance. The conformance
   test pins the digest and case count as literals.
2. The corpus is edited by hand under review (it was authored once with a
   scratch generator that is not part of the repository). To change it: edit
   `datomic-q-v1.json`, re-read the diff, compute the BLAKE3 of the bytes,
   and update `resource-blake3` and `expected-case-count` in the conformance
   test and `:digest`/`:cases` in the provenance file, in one reviewed change.
3. Never edit an expectation to make a failing run green. A changed
   expectation needs an errata entry or a reviewed reclassification.
4. The oracle runner writes candidate observations to a separate file. To
   promote an observation: a reviewer compares it with the frozen case,
   sets `evidence` to `oracle-executed`, the expectation, and `oracle` to the
   recorded backend, and updates the provenance `:oracle` block with the
   backend kind and version, in one reviewed change. If the backend cannot
   run, the case stays `pending` and the missing evidence is reported.
5. A mock, wrapper source, or a DaoSpace-generated answer is never oracle
   evidence.

## Running

```
clj -M:test -n dao.space.datomic-query-conformance-test
clj -M:kondo --lint test/dao/space/datomic_query_conformance_test.cljc
mise exec -- bb check:node-discovery
clj -M:oracle -m dao.space.datomic-query-oracle --backend unselected
```
