Completed-GMT: 2026-09-08 12:05:00 GMT  
Completed-Local: 2026-09-08 19:05:00 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# dao.space.query v2 reimplementation review

Verdict: **request changes**. Two P1 findings block the commit. Both concern temporary schema-compatibility scaffolding; the schema eager-dispatch change itself is justified.

## Findings

### P1 — Arbitrary host objects are mistaken for legacy v1 readers

**File:** `src/cljc/dao/space/query.cljc:190-198,224-249`  
**Blocks commit:** yes

`legacy-v1-realization?` is not a capability check. It is the complement of a hand-maintained collection of data predicates. Consequently, a Date, atom, delay, host array, exception, or any future unrecognized host object enters `index/snapshot-datoms`.

That path invokes the v1 `ds/next` protocol and produces a protocol or host error instead of I1’s informative query-input rejection. It also:

- makes `query` depend behaviorally on v1 traversal hidden behind `dao.space.index`;
- weakens Decision 3’s rule that a live stream enters query only through explicit v2 `snapshot`;
- accepts open v1 streams without the former closed/bounded validation;
- applies `index/snapshot-datoms`’ stricter local-datom validation inconsistently with query’s normal canonical-d5 interpretation;
- has host-dependent behavior because different CLJ,/CLJS/CLJD objects fall through the negative predicate differently.

The bridge is not worth this blast radius. Its remaining uses are schema-test compatibility cases, notably `test/dao/space/schema_test.cljc:523-531` and `599-610`. Schema already owns the v1 dependency and drains realizations in `interpret-view` at `src/cljc/dao/space/schema.cljc:258-260`.

**Concrete change:**

- Delete `legacy-v1-realization?`.
- Delete the special realization branches in `current` and `history`.
- Make both functions route every source through `db-source`.
- Change the affected schema tests to pass the closed realization directly to `schema/current`, or explicitly drain it with `/`strict-vec` and wrap it with `query/relation`` schema-side.

Every unsupported object will then receive the same I1 rejection.

### P1 — Query values still advertise v1 transport types

**File:** `src/cljc/dao/space/query.cljc:153-163,214-221`  
**Related schema codeFile:** `src/cljc/dao/space/schema.cljc:228-239,327-348`  
**Blocks commit:** yes

The `:dao.stream/type` keys on relation and view values are not a sound temporary bridge.

Decision 2 gives these finite values query-owned structural tags. Decision 4 eliminates the relation transport specifically because finite data is not a stream descriptor. Advertising `:dao.space.query/relation`, `:dao.space/current`, or `:dao.space/history` through the transport discriminator:

- makes unrelated v1 descriptor code classify non-openable values as transports;
- retains coupling between query values and v1 descriptor validation;
- contradicts the intended relation shape `{:dao.space.query/relation tuples}`;
- preserves the transport typing Decisions 2 and 4 were introduced to remove.

Schema can support both models without leaking transport typing into query.

**Concrete change:**

- Remove `:dao.stream/type` from `relation` and `view-value`.
- In `schema/current`, accept either `(query/value? d)` or a legacy descriptor carrying a keyword `:dao.stream/type`.
- Make `validate-not-nested-view!` inspect `:dao.space.query/view` for query values while retaining its `:dao.space.schema/current` check for legacy schema descriptors.
- Revise or delete `test/dao/space/schema_test.cljc:538-541`; its bound-inheritance assertion currently succeeds only because both the source and result have a missing/nil bound.

## Verified and cleared

### Schema deviation

**Files:** `src/cljc/dao/space/schema.cljc:247-300,306-371`  
**Result:** cleared; does not block

The implementer’s dependency-cycle argument is correct. `dao.space.schema` already requires `dao.space.query`. Moving schema extraction and cardinality-one collapse into query by requiring schema would create a direct `query ↔ schema` cycle.

Because `q` deliberately opens nothing and cannot interpret a schema-owned v1 descriptor, `schema/current` must eagerly interpret query values. Legacy open-dispatchable descriptors correctly retain the existing schema `defopen` route.

The eager path preserves the tested temporal behavior:

- data rows use `as-of`;
- schema rows use `schema-as-of` when supplied, otherwise `as-of`;
- schema extraction occurs before cardinality-one collapse;
- `:schema-as-of 1` and `:schema-as-of 5` continue to select the corresponding schema state;
- query-value published indexes remain borrowed and open;
- legacy schema-published descriptors are opened, pre-forced, closed, and then interpreted as before.

The third changed schema region is therefore necessary. The issue is the compatibility mechanism chosen around it, covered by the two findings above.

### `snapshot` and `observe/step`

**Files:** `src/cljc/dao/space/query.cljc:304-351`; `src/cljc/dao/stream/observe.cljc:58-126`  
**Result:** cleared; does not block

The implementation follows `observe/step` correctly:

- cursor mint failure becomes a `:defect` value carrying the raw mint answer;
- the effect is total for admitted values: it retains the value with `conj` and returns `:dao.stream/ok`;
- `:advance` adopts the successor only after the effect succeeds;
- `:retry`, `:ended`, `:gap`, and `:defect` retain the pre-read cursor;
- gap recovery is returned separately;
- the defensive `:failed` path is classified as a defect without advancing;
- no cursor advances past a value not retained in the accumulator;
- the handle is never closed.

### Published-index ownership

**File:** `src/cljc/dao/space/query.cljc:256-297`  
**Result:** cleared; does not block

`open-published!` validates the coordinate against `index/published-index`, opens exactly one content-store handle, reads the manifest, and constructs lazy rows/restored indexes.

If manifest reading or restored-index construction throws, the catch covers CLJ, CLJS, and CLJD, closes the opened store, and rethrows.

`close-published!` uses a per-opened-value guard, so:

- query evaluation never closes a borrowed opened index;
- the caller closes the store;
- a second close is a no-op before reaching the backend;
- concurrent close attempts result in only one backend close attempt.

### Dropped `[T✗]` invariants

**Specification:** `docs/design/dao.space.query.implementation-plan.md:85-175`  
**Result:** cleared; does not block

Each dropped invariant was removed for its stated reason:

- **I6:** exact bounds and closedness validation disappeared because query inputs are finite values, not stream realizations.
- **V6:** views no longer implement v1 reader/bound protocols; `rows` supplies their realized contents.
- **R3:** query results are tagged local values consumed by `collect`, not cursor-drainable result streams.
- **O2:** `q`, `match`, and `pull` no longer open descriptors, so their owned-source/finally machinery was correctly removed.

The old blocked/gap traversal exceptions were intentionally replaced by Decision 3’s explicit snapshot outcome data.

### Deleted `dao.stream.relation`

**File:** `src/cljc/dao/stream/relation.cljc`  
**Result:** cleared; does not block

No source consumer remains. Repository search found only:

- the deleted transport’s own test and require in `test/dao/stream_test.cljc`;
- documentation describing its planned deletion;
- historical/collaboration records.

The removed test exclusively specified the deleted v1 inline-relation transport. Query relations now preserve arbitrary mixed-dimensional tuples as plain query values, so no surviving consumer silently changes transport behavior.

### Evaluator and lazy published path

**File:** `src/cljc/dao/space/query.cljc:378-1505`  
**Result:** cleared; does not block

The evaluator remains substantively unchanged outside the planned value realization and result-materialization seams.

Static tracing confirmed:

- current/no-`as-of` over an opened published index retains restored-index pushdown;
- history, temporal bounds, and row consumers force the eager relation;
- planning receives the delayed relation without forcing it;
- independent `:in` database values remain separate;
- `match`, `q`, `pull`, `pull-many`, and `entity-attrs` open and close nothing;
- result values carry the find specification and no external stream bound.

### Portability

**Files:** `src/cljc/dao/space/query.cljc`; `src/cljc/dao/space/schema.cljc`  
**Result:** cleared apart from the host-object predicate finding

New exception reader conditionals explicitly cover all three targets:

```clojure
#?(:cljd Object
   :clj Throwable
   :cljs :default)
```

The relevant schema catch likewise includes `:cljd Object`. No new `#?(:clj ...)` branch incorrectly relies on CLJD exclusion. The v2 snapshot APIs, atoms, delays, volatile accumulation, and compare-and-set close guard are present on the compiled hosts, consistent with the supplied successful CLJ, CLJS, and CLJD runs.

No test suites were rerun, per the review brief.
