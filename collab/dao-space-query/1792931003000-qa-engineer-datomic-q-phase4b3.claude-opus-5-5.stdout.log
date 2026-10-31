Completed-GMT: 2026-10-25 14:01:41 GMT
Completed-Local: 2026-10-25 21:01:41 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

4b-3 is implemented and the full battery passes on all three hosts. I hit no design-level wall and did not stop. Nothing is staged or committed.

## What changed

**`query.cljc`**
- **Range plan.** A pure `range-plan` factors out order selection (EAVT for a bound entity; AVET for attribute plus value; VAET for value; AEVT for attribute; otherwise a full EAVT walk). It supplies the lower seek key in canonical `[e a v t m]` slots and the prefix test. That test is comparator-based (`same-slot?`), continues across kind-tied values, and filters content-strictly afterwards. The restored-tree producer (`index-candidates`) now uses the same plan, with unchanged behavior.
- **Checked producer.** `checked-datoms-st` is the query-side cursor interpreter, one constant-stack loop threading state, cursor and candidates. All index reads funnel through `datoms-st`, so these paths reach it:
  - pattern clauses and joins;
  - the unselective full-walk fallback;
  - pull, nested and wildcard;
  - pull in `:find`;
  - `get-else` and `missing?`;
  - negation, rules and `or`;
  - repeated aggregate scans.
- **Per-step accounting.**
  - **Observation:** every cursor transition observes and charges one `:tree-step`, and stops before advancing if the state is terminal.
  - **Reads:** a `:need-node` observes and charges one `:node-read`, then does exactly one `read-node-blob` inside `execution/read-step`. A node returned after expiry is discarded and never supplied. `KVStorage/-restore` is never on this path.
- **Candidate stop.** The first key outside the range plan's prefix ends the scan, and the usual per-candidate unit applies.
- **One admission policy.** `source-step` is the single classification used by both adapter admission and bounded realization.
  - **Admitted:** a materialized relation, or exactly `current` with no as-of directly over a published value with a structurally valid descriptor. Validation checks version 1, the exact key set, non-nil store/manifest-address/manifest and a manifest of the persisted shape; never identity.
  - **Realization:** an admitted source costs zero fetches and never touches the `:rows` delay.
- **Fallback guard.** An eager relation fallback over a checked source throws `:checked-source-fallback`. It is unreachable through the adapter, which pads to three positions and refuses rest patterns. It is a runtime guard, not a static pre-check.

**`datomic.cljc`**
- **Admission.** It uses `source-step` with the existing accounted hops. Everything outside the table stays refused before any fetch, including unused and mixed sources.
- **Error mapping.** A cursor or read failure becomes `:source-failed` with the original error as `ex-cause`. A runtime `:checked-source-fallback` becomes `:unsupported`.

**`execution.cljc`**
- The unit table gains `:tree-step` and `:node-read` at one unit each. Exhaustion is still terminal data.

## Tests
New `test/dao/space/query/published_bounded_test.cljc` (22 tests, 306 assertions), on explicitly instrumented stores (no `with-redefs`):
- **Parity.** Bounded, unbounded and materialized answers match across access paths, joins, pull, special forms, negation, `or`, aggregates, retraction and a 1 vs 1.0 kind-tie. Conflicting d5 rows fail identically on every path. The `:rows` delay stays unforced.
- **Expiry matrix.** Per path (EAVT, AEVT, AVET, VAET, full walk, join) at fetch ordinals 1, 2, total−1 and total:
  - **During fetch N:** the call times out, fetch N finishes, no fetch N+1 begins, and no partial relation escapes.
  - **Before the first read:** zero fetches.
  - **Exhaustion:** it precedes every fetch's charged units.
  - **Cursor-local advancement:** it observes expiry.
- **Shared allowance.** Joins, negation, rules and two published sources share one allowance, with an exact one-short boundary.
- **Warm trees.** A warmed legacy tree does not bypass the checked path.
- **Special forms and pull.** Pull in `:find` stops after expiry at a later fetch. `get-else` and `missing?` include absence. Rules and negation read the source. A first witness stops future cursor work.
- **Refusals.** Nine excluded source shapes, each under timeout-only and step-only bounds, plus mixed-source orderings, all refused with zero reads and no forcing.
- **Lifecycle.** No puts or closes occur, and the caller keeps using the source after a timeout.
- **Failures.** A malformed node mid-traversal surfaces as `:source-failed` with the cursor's `:unordered-keys` error as `ex-cause` (the carried condition). A missing segment keeps its cause. A malformed node returned after expiry is never interpreted: it is a timeout, not a validation failure.
- **Read budgets.** Bounded equivalents of the ≤4/≤15 regressions use bf 4 with constructor manifest reads counted separately. A cold point query used at most 8 node reads and a two-clause query at most 15. Both are far below the whole-tree count.

I also updated three existing tests whose expectations legitimately changed: the execution unit table, the `published-read` bounded-adapter test (a valid published `current` view is now admitted; a descriptor-less one is still refused), and the earlier refusal expectations.

## Verification
JVM suites:

| Suite | Result |
|---|---|
| `dao.space.query-test` | 72 tests, 282 assertions |
| `dao.space.query.datomic-test` | 29 tests, 222 assertions |
| `dao.space.query.execution-test` | 42 tests, 261 assertions |
| `dao.space.query.datomic-execution-test` | 22 tests, 190 assertions |
| `dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions |
| `dao.data.btree.cursor-test` | 17 tests, 1227 assertions |
| `dao.data.btree.storage-read-test` | 5 tests, 17 assertions |
| `dao.space.published-read-test` | 5 tests, 37 assertions |
| `dao.space.query.published-bounded-test` | 22 tests, 306 assertions |
| query-numeric / positional-query | 18/922 and 11/27 |
| index / index-numeric | 56/531 and 5/345 |
| transactor | 16/100 |
| dht | 46/36,764 |
| transact | 8/26 |
| schema | 72/210 |

- **Node.** `bb test:sub:cljs dao.data dao.space`: 26 namespaces, 548 tests, 0 failures (328s).
- **Dart.** `cljd_agg --only` over the 24 dao.data and dao.space namespaces, which excludes the JVM-only `sorted-user-type-test` (the pre-existing lane item): `All tests passed!`, +545 including the new bounded matrix.
- **kondo.** On changed files: 0 errors, 0 warnings.
- **node-discovery.** `Node discovery OK: 229 source files outside test/`.
- **git status.** Modified `storage.cljc`, `query.cljc`, `datomic.cljc`, `execution.cljc` and `execution_test.cljc`. New files are the cursor, its test, the storage-read test, `published_read_test.cljc` and `published_bounded_test.cljc`. The other entries (docs, collab, ble, blog) are not mine.

## Concerns
- **Fallback is runtime only.** The requirement "refuse BEFORE evaluation" holds only through the adapter's padding and rest-pattern refusal. A direct core caller reaches the runtime guard after earlier clauses may have fetched. I considered a static pre-scan too fragile, since rules arrive as data.
- **Cost is higher than 4a.** The checked path charges `:tree-step` per transition, `:node-read` per fetch and a candidate unit per yielded key. Chunked validation adds steps for large nodes. `:max-steps` calibrated for materialized sources will exhaust sooner here.
- **Read phase.** `read-step` records the phase as `:read`, and I restore the earlier phase afterwards. A read that exhausts the budget would report the `:read`-adjacent phase.
- **Slow Node run.** The Node lane takes about 5.5 minutes, most of it the new matrix loops. Trimming caps would be easy if wanted.
- **Corpus untouched.** I added no corpus pins and re-digested nothing.
