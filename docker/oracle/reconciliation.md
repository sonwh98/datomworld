# datomic-q-v1 oracle reconciliation (Phase 5)

Backend: Datomic Local 1.0.301 through `datomic.client.api` (client-api
1.0.69, client 1.0.146), `:storage-dir :mem`. Corpus: datomic-q-v1, 113 cases,
digest `51e02181…128c` (unchanged by this round). Observations:
`/tmp/jupiter-observations.edn` (docker on jupiter) and a local run of the same
runner (`target/p5-smoke.edn`; the `oracle-out/` file named in the brief was not
present in this worktree, so the local side is my own earlier run of the same
commit). Nothing in the corpus or the contract was changed.

This document is a candidate classification for human promotion review. It is
not evidence the corpus can cite until reviewed.

**Superseded in part by the promotion round.** The architect ruled on U1–U3
(median kept and documented; `min`/`max` n <= 0 accepted; schema-free kept and
documented), the runner's identity mapping became enumerated per-case
`entity-ids` annotations (the ten rerun cases agree on the corrected runner,
run `:local-r2`), and the corpus now carries the per-case `oracle` outcome. The
authoritative record is the corpus, `datomic-q-v1.errata.md` (E15–E17) and
`datomic-q-v1.oracle-observations.edn`; this file is kept as the first-pass
reconciliation.

## 0. Corrections to the brief

- The 3 not-eligible cases are NOT the fixture-load-blocked ones. They are
  excluded by the runner's ineligibility rule (no Client equivalent):
  `api.db-first-refused` (pending), `source.raw-vector-refused` (refusal),
  `source.current-d5-view` (difference). The three fixture-load errors are
  eligible cases (`agg.median-tied-kinds`, `agg.median-tied-kinds-odd`,
  `agg.median-tied-kinds-carrier-refused`).
- The corpus has 29 pending cases (not 30) since Phase 3 promoted
  `rules.required-bound-head`. 28 of them were observed; the 29th is
  `api.db-first-refused`.
- Observed 110 = 56 executable + 8 difference + 18 refusal + 28 pending.

## 1. Cross-execution consistency (local vs jupiter)

Same Datomic jars (client-api 1.0.69, local 1.0.301, client 1.0.146, peer
1.0.7364), same 110 ids, same 3 not-eligible, 90 results / 20 errors on both.
Comparing observation kind, normalized result, error message, phase and error
class: identical for 108 of 110. Two differ, both expected:
`rules.multiple-definitions` (same rows, different order) and
`agg.rand-excluded` (a random value). Raw entity ids differ between runs, as
they must; normalization maps them back to the logical ids. Script:
`docker/oracle/reconcile/compare_runs.clj`.

## 2. Headline numbers (owner)

Of the 29 pending cases, **28 now have an oracle observation**. 25 of the 28
are conclusive, 3 are not comparable (DaoSpace-specific bounds semantics).

| Of the 28 observed pending cases | n |
|---|---|
| AGREE / oracle confirms the contract's stated expectation | 10 |
| AGREE modulo the documented `:db/id` pull difference | 4 |
| Datomic ACCEPTS what DaoSpace deliberately refuses (documented, DaoSpace stricter) | 7 |
| Datomic refusal CONFIRMS DaoSpace's conservative refusal | 3 |
| UNEXPECTED divergence | 1 |
| Not comparable (no Datomic equivalent) | 3 |

Whole observed set (110), by corpus status (per-case tables below):

| Status (n) | Result |
|---|---|
| executable (56) | 41 AGREE direct; 10 AGREE after logical-id mapping (3.2); 1 UNEXPECTED (U1); 4 not representable or comparable (3.3) |
| difference (8) | 7 documented difference CONFIRMED (2 numeric, 5 pull); 1 AGREE (pull.no-match-empty) |
| refusal (18) | 9 same refusal, other message; 2 Datomic stack-overflows where DaoSpace refuses loudly; 1 inconclusive (fulltext); 3 documented exclusions Datomic supports; 1 documented host-carrier case; 1 UNEXPECTED (U2); 1 fixture-unrepresentable |
| pending (28) | table above; 1 UNEXPECTED (U3) |

UNEXPECTED divergences in total: **3** (U1 median, U2 non-positive aggregate
parameter, U3 attribute absent from the schema), see section 7.


## 3. Executable cases (56)

### 3.1 Direct AGREE — 41

`agg.avg agg.count agg.distinct agg.distinct-with-multiplicity
agg.grouped-count agg.median agg.median-grouped agg.min-n-grouped
agg.min-n-with-multiplicity agg.parameter-exceeds-count agg.parameterized-max
agg.parameterized-min agg.stddev agg.sum-min-max-count-distinct agg.variance
agg.variance-stddev-grouped agg.variance-stddev-with-multiplicity
agg.with-multiplicity agg.without-with-dedupes api.map-query api.vector-query
find.in-bound-var find.keys find.relation in.collection in.default-source
in.scalar neg.not neg.not-join numeric.builtin-arithmetic or.or or.or-join
pattern.three-position rules.multiple-definitions rules.non-recursive
special.get-else special.missing workload.list-join-duplicates
workload.session-isolation-s1 workload.session-isolation-s2
workload.view-summary-grouped`

Compared with the case's own comparison rule (`exact`, `numeric-value`),
Datomic's relation (a vector) taken as a set. Two kind notes: `agg.median`
and `agg.median-grouped` agree only under `numeric-value` (Datomic returns the
long 41 / 7, DaoSpace the double 41.0 / 7.0; see U1). `agg.avg` is 41.0 on both.

### 3.2 AGREE after logical-id mapping — 10 (initially MISMATCH: runner artifact)

`in.relation in.tuple rules.both-sided-recursion rules.left-recursion
rules.mutual-recursion rules.positive-recursive rules.required-bound-head
rules.right-recursion-cycle rules.stratified-negation rules.unbound-entry-call`

The first-pass observations disagreed (empty results; stratified-negation
returned `[[1][2][5]]`). That was NOT a Datomic divergence: the corpus uses
logical entity ids as literals (`(reach 1 ?x)`, `(r 2 ?e)`, relation inputs
`[[1 "Ada"]]`), and Datomic allocates large real ids, so the literals matched
nothing. The graph fixture also stores nodes as long VALUES, so recursion
joined a long against an entity id. I re-ran these 10 with (a) every integer
equal to a logical id replaced by its real id in the query and inputs and (b)
the graph loaded with `:e` as a ref and a `:node/id` per node
(`docker/oracle/reconcile/idmap_rerun.clj`, not part of the runner). All 10
**AGREE** with the DaoSpace pin, including left, right, both-sided and mutual
recursion over a cycle, the unbound entry call and stratified negation. This is
the strongest compatibility evidence of the phase: Datomic's rule semantics
equal the Phase 3 tabling semantics on every recursion shape in the corpus.

The runner needs this id mapping to promote these cases (§9).

### 3.3 Not representable or not comparable — 4

- `agg.median-tied-kinds`, `agg.median-tied-kinds-odd` (fixture-load error):
  the `ties` fixture puts a long and a double under one attribute
  (`:k/v`: 1, 1.0, 1, 2.0); Datomic attributes have one value type
  (`:db.error/wrong-type-for-attribute`). The scenario (a cross-kind tie in one
  attribute) cannot exist in Datomic. Per-type attributes would change the
  question being asked, so I did NOT change the fixture. These cases stay
  DaoSpace-only (no oracle evidence possible); recommend a note in the errata.
- `in.multiple-sources`: joins `?e` across two databases. Datomic allocates
  different real ids per database, so the join is empty. Not comparable with
  this fixture model.
- `in.empty-source`: error `:db.error/not-an-entity Unable to resolve entity:
  :person/name`, because an empty database has no schema. Not comparable, and
  it exhibits U3.

### 3.4 UNEXPECTED — 1

`agg.median-with-multiplicity`: DaoSpace 3.5, Datomic 3. See U1.

## 4. Documented-difference cases (8 observed; `source.current-d5-view` not eligible)

| Case | Datomic | Verdict |
|---|---|---|
| numeric.scale-collapse | 2 rows (1.0M, 1.00M kept distinct) | CONFIRMED (DaoSpace collapses to 1) |
| numeric.signed-zero-collapse | 2 rows (0.0, -0.0) | CONFIRMED (DaoSpace 1) |
| pull.flat | no `:db/id` | CONFIRMED (only difference is `:db/id`) |
| pull.missing-attribute-omitted | no `:db/id` | CONFIRMED |
| pull.multivalued | no `:db/id`, cardinality-many `["a2@x" "a@x"]` (order unspecified) | CONFIRMED |
| pull.nested | no `:db/id` at either level | CONFIRMED |
| pull.wildcard | `:db/id` present, ref value is `{:db/id 2}` not `2` | CONFIRMED (the `:db/id` row plus the schema-free reference row, compat §237/§291) |
| pull.no-match-empty | `[]` | AGREE (empty on both) |

Evidence class can upgrade to oracle-executed with the difference confirmed
for all 7 (and AGREE for the 8th).

## 5. Refusal cases (18)

Datomic also errors — 9 (same refusal, different message/category):
`agg.arity-mismatch` (ArityException), `agg.median-non-numeric`
(ClassCastException, a raw host exception), `agg.non-variable-argument`
(IllegalArgumentException), `find.keys-arity-refused`, `find.unbound-var`,
`find.unbound-var-in-aggregate` (`:db.error/unbound-query-variables`),
`in.arity-mismatch-refused` (`:db.error/too-few-inputs`),
`rules.call-arguments-are-flat` (`:db.error/invalid-lookup-ref Invalid list
form: [2]`), `rules.required-bound-unbound-call`
(`:db.error/insufficient-binding [?z] not bound in clause`). Datomic's
required-bound error is `:db.error/insufficient-binding`; DaoSpace's maps to
`:invalid-request`/`:incorrect` — the same semantic refusal. This supplies the
Phase 5 oracle measurement E16 reserved.

Datomic fails differently — 3:
- `rules.recursion-through-negation` and `…-vector-head`: Datomic throws
  `StackOverflowError` (no diagnosis, the evaluation recurses). DaoSpace
  refuses loudly before evaluation. Same outcome class (the query does not
  run) but DaoSpace's behavior is clearly better; documented-difference
  framing applies. Not a compatibility break.
- `ambient.fulltext-refused`: Datomic CompilerException, but the fixture
  declares no fulltext attribute, so this does not show whether fulltext is
  supported. Inconclusive.

Datomic ACCEPTS where DaoSpace refuses — 5:
- `agg.rand-excluded`, `ambient.rand-refused`, `ambient.symbol-resolution-refused`
  (`clojure.core/inc` resolved): documented exclusions (compat §241,
  randomness/ambient resolution). CONFIRMED Datomic supports them.
- `agg.variance-carrier-refused`: Datomic returns 0.0 (JVM evaluates
  BigDecimal as a host number, exactly like DaoSpace on the JVM); the refusal is
  the documented JS/Dart carrier difference. Consistent.
- `agg.parameter-not-positive`: `(min 0 ?a)` — Datomic returns an empty
  collection (also `(min -1 …)`). DaoSpace refuses. **UNEXPECTED (U2).**

`agg.median-tied-kinds-carrier-refused` is the 18th (fixture-load, §3.3).

## 6. Pending cases (28 observed)

| Case | Datomic | Verdict |
|---|---|---|
| find.scalar, find.tuple, find.collection | `Only find-rel elements are allowed in client :find` | CONFIRMS the adapter's conservative refusal (Phase 1); the core pins that accept them are DaoSpace extensions |
| api.arg-map, api.list-query | accepted, `[[1]]` | AGREE (contract exact-target) |
| api.string-query | accepted (read as EDN), `[[1]]` | AGREE with the adapter intent; the core pin `#{[]}` is the known E2 |
| opts.limit / limit-default-unlimited / limit-zero / offset / offset-large-past-end | 2 / 6 / 0 / 2 / 0 rows | AGREE with the contract slicing semantics |
| opts.timeout-positive-integer | accepted | AGREE (contract: positive integer timeout) |
| pattern.two-position-padding | `[?e :person/name]` returns all 4 entities | AGREE with the adapter's padding; the core pin `#{}` (exact arity) is the documented core difference |
| pull.alias-option, default-option, limit-option, reverse-attribute | `{:n "Ken"}`, `{:person/age 0}`, `{:person/email ["a2@x"]}`, reverse `:person/_friend` vector | AGREE modulo `:db/id` (documented) and list-vs-vector; 4 |
| opts.ret-refused | `:ret :coll` accepted and IGNORED (plain relation) | Documented-stricter: confirms "no documented :ret contract" (compat §224) |
| opts.unknown-option-refused | `:bogus` silently ignored | Documented-stricter (compat §254) |
| opts.query-stats-refused | `:query-stats` honored, returns stats | Documented scope (compat §225) |
| opts.negative-offset-refused | `-1` accepted, all 6 rows | Documented adapter policy (compat §257–262 says not a Client claim) |
| pattern.five-position-history-refused | accepted, `[]` | Documented (compat §229) |
| pull.recursive-marker-refused | recursion supported, nested result | Documented (compat §281) |
| pull.list-form-option | `(:person/age :default 0)` accepted | DaoSpace adapter is stricter (E10/E11); was `unverified`, now observed — decision needed whether to support |
| agg.empty-input | `:db.error/not-an-entity Unable to resolve entity: :person/nothing` | **UNEXPECTED (U3)** |
| bounds.deadline-exhausted, bounds.eager-before-pagination, bounds.published-source-refused | `[]` / `[[205]]` / rows | Not comparable: these pin DaoSpace-specific work/admission semantics that have no Datomic Local equivalent |

## 7. UNEXPECTED divergences (the deliverable)

**U1 — even-count `median` (case `agg.median-with-multiplicity`).** DaoSpace
(E15) returns the mean of the middle pair as a double: `[1 2 3 4 4 10]` →
3.5. Datomic returns 3. Probed directly
(`docker/oracle/reconcile/probe_aggregates.clj`): long inputs `[1 2]` → 1,
`[3 4]` → 3, `[1 2 3 4]` → 2, `[37 40]` → 38, `[-3 -4]` → -3 — an
integer (truncating) mean of the middle pair; doubles `[1.0 2.0]` → 1.0 (NOT
1.5); mixed `[1 2.0]` → ClassCastException; odd counts return the middle
element. The corpus cases that look like agreement (`agg.median` 41,
`agg.median-grouped` 7) agree only because those means are exact integers.
This is a genuine, contract-relevant divergence in a ratified Phase 2
convention: E15 and the aggregate table need an explicit documented-difference
entry, or the median convention must change. Datomic's double result looks
like a Datomic quirk, not a standard to copy; this is an owner decision.

**U2 — non-positive aggregate parameter (`agg.parameter-not-positive`).**
`(min 0 ?a)` and `(min -1 ?a)` are accepted and return an empty collection.
DaoSpace refuses "positive integer". A Datomic-compatible client can send
this; DaoSpace turns it into an error. Decide: accept (empty) or keep the
refusal as a documented difference.

**U3 — attribute absent from the schema (`agg.empty-input`, also
`in.empty-source`).** Datomic refuses a pattern naming an attribute the
database has no schema for (`:db.error/not-an-entity`). DaoSpace, schema-free,
returns no rows. This affects every query over a misspelled or absent
attribute and is not in the contract; it is the schema-free cost, and the
contract should say so (compat §237 mentions schema-free pull only).

Not unexpected but worth the owner's eye: Datomic's required-bound and flat-call
refusals are ordinary exceptions (insufficient-binding, invalid-lookup-ref),
and recursion through negation is a StackOverflowError — DaoSpace's refusals
are strictly clearer.

## 8. Decisions on the brief's fixture question (item 5)

- `ties` (mixed long/double in one attribute): no per-type fixture change.
  Datomic cannot hold the scenario; splitting attributes would test a
  different property. No re-digest.
- The logical-id literal problem is a runner-model issue, not a fixture
  problem: fixtures are unchanged. Fix belongs in the runner (§9).

## 9. What promotion would need (not done this round)

1. Runner: map logical ids to real ids in query and input literals, and load
   value-only graph nodes as entities (the `idmap_rerun.clj` approach). Then
   re-run so `in.relation in.tuple rules.*` (10 cases) carry real observations.
2. `in.multiple-sources`: needs both fixtures in one shared id space.
3. `ambient.fulltext-refused`: needs a fulltext-enabled attribute to observe
   Datomic's actual fulltext behavior.
4. Owner decisions on U1, U2, U3; errata entries for the unrepresentable ties
   cases, the Datomic-permissive options (`:ret`, unknown options, negative
   offset, five-position, recursive pull, list-form pull option).
5. Evidence-class upgrade to `oracle-executed` and the provenance `:oracle`
   block (backend `datomic-local`, versions above, 110 observed) are the human
   promotion step; the corpus README forbids automatic promotion.

## Method and scripts

`docker/oracle/reconcile/`: `compare_runs.clj` (§1), `classify.clj` (per-case
table against corpus status and expectation), `idmap_rerun.clj` (§3.2),
`probe_aggregates.clj` (U1, U2). Run with `clj -M:oracle <file>`; they read
the observation files by path and never write the corpus. The first-pass
`classify.clj` compares relations as sets, which hides row-count differences;
the numeric `row-count` cases (§4) were judged on the raw vectors.
