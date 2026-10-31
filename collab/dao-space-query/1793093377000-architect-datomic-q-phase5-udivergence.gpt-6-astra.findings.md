Completed-GMT: 2026-10-27 09:45:13 GMT
Completed-Local: 2026-10-27 16:45:13 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

Reviewed the reconciliation, probe scripts, runner, E15, and aggregate implementation at `datomic-q-p5-ci@e4eed5f5`. These are convention rulings for the promotion round, not final qualification sign-off. No files changed.

**U1 — retain DaoSpace’s median convention; document the difference.**

Choose **(a)**. Odd counts retain the middle operand; even counts return the double mean within the existing supported arithmetic domain. Preserve the current carrier refusals and kind ordering.

This is a deliberate convention choice, **not a consequence of CBOR identity**. Matching the observed Datomic behavior would be architecturally possible, but would replace an already-pinned, portable arithmetic convention with backend-specific truncation and mixed-kind failures. The compatibility cost must be explicit.

Transcribe into E15 and the aggregate contract:

> Median is a documented difference. DaoSpace preserves the middle operand for odd counts and computes the double mean of the middle pair for even counts, subject to its existing arithmetic-carrier restrictions. Datomic Local 1.0.301 observations include `[1 2 3 4 4 10] → 3`, versus DaoSpace `3.5`; `[1.0 2.0] → 1.0`, versus DaoSpace `1.5`; and a mixed `[1 2.0]` refusal. These observations do not redefine DaoSpace’s convention or establish behavior for unmeasured numeric domains.

Reclassify `agg.median-with-multiplicity` as a documented difference, retaining its DaoSpace expectation. Preserve individual agreements under their declared comparator; they must not imply general median parity. Record result-kind differences separately where `numeric-value` comparison hides them.

**U2 — accept non-positive integer parameters and return an empty aggregate collection.**

Choose **(b)**. Unlike U1, the positive-only restriction contributes no required numeric or architectural guarantee. Removing it improves compatibility without changing any previously successful positive-parameter result.

Normative replacement:

> Parameterized `(min n ?x)` and `(max n ?x)` accept integer parameters within the supported parameter domain. For an existing aggregate group, `n <= 0` produces an empty vector; positive parameters retain the established ordering, multiplicity, and tie rules. Non-integer parameters and invalid arities remain refusals. This does not bypass query evaluation, source admission, or execution accounting.

Implement this in the generic aggregate core, not through adapter query rewriting. An empty aggregate value occupies its normal result cell; it does **not** mean an empty result relation. This ruling does not settle aggregation over zero input rows.

Before promotion:

- Replace the zero/negative refusal pins with result assertions.
- Add grouped and ungrouped tests for both `min` and `max`, preserving positive and invalid-parameter coverage.
- Measure `max 0` and `max -1` through the oracle before claiming their parity; the supplied observations establish only `min`.
- Update E15, parameter-validation documentation, corpus provenance, and digest; run the three-host affected suites.

**U3 — retain schema-free behavior; add an explicit documented-difference row.**

Transcribe into the supported-domain table:

| Contract point | Classification | Boundary |
|---|---|---|
| Attribute identifier absent from Datomic schema | Documented difference | DaoSpace fact matching requires no attribute-schema declaration: an unmatched attribute yields no bindings. The observed Datomic backend refuses unresolved attribute identifiers with `:db.error/not-an-entity`. DaoSpace does not infer or consult a schema solely to reproduce that refusal. |

Do not add schema ownership or schema lookup to this adapter.

The current observations for `agg.empty-input` and `in.empty-source` establish this schema difference. **They do not establish Datomic’s empty-input aggregate behavior.** Preserve those observations, then add separate oracle cases with declared attributes but no matching facts. Empty-input aggregate parity must remain unverified until those cases execute.

**Carry-forward rulings**

1. **Land the runner’s identity correction before promoting the ten rerun cases.** Then obtain observations from the corrected production runner. The exploratory script’s blanket replacement of every matching integer is unsuitable as the general mechanism: it can rewrite an ordinary numeric value or aggregate parameter that happens to equal a logical entity ID. Use explicit, source-scoped fixture/case identity annotations or enumerated substitution positions. Pin a collision test demonstrating that ordinary numbers remain unchanged. Record graph reference loading and value-only node materialization in loader provenance.

2. **Defer fulltext oracle qualification to a future fulltext profile.** Fulltext remains excluded from this release. Keep `ambient.fulltext-refused` as an executable DaoSpace refusal, with the Datomic observation marked inconclusive. The current compiler error proves neither equivalent refusal nor successful fulltext support. A future measurement needs a fulltext-enabled fixture and a valid backend-supported query; it is not a prerequisite for qualifying the present exclusion.

3. **Keep evidence promotion precise.** Preserve historical observations and identify superseding runner versions and corpus digests. “Oracle executed” must remain distinct from “agreement,” “documented difference,” and “inconclusive.” The final qualified claim must name these boundaries and exclude unresolved cases from its exact domain.