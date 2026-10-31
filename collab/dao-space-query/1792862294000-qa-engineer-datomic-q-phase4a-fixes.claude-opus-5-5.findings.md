Completed-GMT: 2026-10-24 18:25:21 GMT
Completed-Local: 2026-10-25 01:25:21 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

All five items are done. The kernel is now stack-safe with no depth guard, and the three-host and downstream gates pass. I touched no B-tree, ble, Flutter, `deps.edn` or other docs files, and nothing is staged or committed. Most of the new stack-safety tests ran on JVM, Node and Dart; the exception is the `dao.space.query.execution-test` smoke test, noted under item B.

## A. Stack-safe kernel
- **Mechanism.** The one kernel in `query.cljc` now runs on a trampoline with explicit heap frames. Every evaluator step returns a computation, which is a plain `[state acc]`, a bounce, or a bind. Nothing calls a clause evaluator or a continuation directly; `run-comp` is the only driver. Host stack depth is constant, whether the depth comes from rule recursion, nested evaluation or the number of bindings processed.
- **Unchanged properties.** The kernel is still one evaluator with the state-threading contract. First-witness termination in `not` and `not-join`, rule-local scope restoration with progress retained, and the accounting table (17, 14, 8, …) are unchanged.
- **Guard removed.** The 200-level rule-depth guard, its context key, the adapter's `:rule-depth-limit` mapping and its two tests are all deleted. There is no residual refusal.
- **Direct-style helper.** Shallow folds (pull, projection, input expansion) keep a direct loop helper, `fold-st`, which is already stack-flat.
- **New tests, deterministic, no deadline needed.**
  - **Fresh-value recursion:** caps of 1000, 5000 and 60000 end in structured exhaustion; the former overflow was around 330 levels.
  - **Mutual recursion:** ping/pong exhausts at caps 2000 and 40000.
  - **Deep chains:** a 900-edge chain, beyond the former ~800-level overflow, completes both unbounded and bounded.
  - **Many bindings:** 5000 bindings through an `or` complete.
  - **Nested evaluation:** a recursive rule inside `not-join`, both with no witness and with a witness at depth 850.
  - **First witness:** it stops the negation before an infinite-recursion alternative runs.
  - **Adapter:** caps 1000 and 20000 give `:step-budget-exhausted`.
- **Cost and limits.** The explicit frames live on the heap, so memory grows with recursion depth, bounded by the work cap. Opaque caller functions keep the indivisible-operation limitation.

## B. Real-clock smoke on every host
`default-now-ms` is now public (`^:no-doc`). `real-clock-smoke` in `datomic_execution_test` is no longer guarded, so it runs on JVM, Node and Dart. It checks two adjacent readings are numeric, non-decreasing and within 5s, then runs a 60s-timeout query on the default clock. A separate JVM-only test keeps the `nanoTime` margin check. The machinery smoke in `execution_test` stays JVM-only.

## C. Corpus
- **Reasons.** The stale timeout reasons are updated.
- **New case.** `bounds.published-source-refused` is pending and carries limitation E12. The harness runs core only, so the adapter tests pin the refusal.
- **Errata.** E12 records the Phase 4a timeout/max-steps surface and the admission refusal. Full timeout oracle cases remain Phase 5.
- **Digest.** The new BLAKE3 is `a4983d69d0dae4474387977e3fb75b25e89c766a3edcd148019e89d5b0f39503`. It is pinned once in the test literal and once in provenance, and provenance records the superseded `3b20589f`.
- **Counts.** There are now 82 cases with 36 pending, updated in the test pin, README and provenance.

## D. Gates (exact output)
`mise exec -- bb test:sub dao.space`:
```
lane  namespaces  tests failures seconds  status
clj           15    413        0     105  pass
cljs          14    407        0     161  pass
cljd          14    407        0     175  pass
```
Downstream JVM gate, 8 namespaces (query-numeric, positional-query, index, index-numeric, transactor, dht, transact, schema): `Ran 232 tests containing 38925 assertions. 0 failures, 0 errors.` Adding `query-test` (72/282) brings that to 304 tests and 39,207 assertions, the same as before the rewrite.

## Individual suites
| Suite | Result |
|---|---|
| `dao.space.query-test` | 72 tests, 282 assertions, unchanged |
| `dao.space.query.datomic-test` | 29 tests, 222 assertions |
| `dao.space.query.execution-test` | 39 tests, 246 assertions |
| `dao.space.query.datomic-execution-test` | 18 tests, 172 assertions |
| `dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions |
| kondo, all changed files | 0 errors, 0 warnings |
| `bb check:node-discovery` | `Node discovery OK: 215 source files outside test/` |

## E. Errata only
E13 records the pre-existing `realize-st` laxness: a relation slot that is a delay of a non-sequence yields an empty result instead of a refusal. It is routed to the Phase 2 core slice, with no code change.

## Concerns
- **Speed.** The trampoline allocates per step, so the execution tests are slower (about 18–20s on the JVM). I did not benchmark against the old kernel. The downstream gate took about 107s, and the unbounded paths I ran were semantically unchanged.
- **Stack safety scope.** Stack safety is demonstrated for the kernel's evaluation chains. Pattern parsing and pull nesting still recurse with query-text depth, which is bounded by the query itself.
- **Gate evidence.** The three-host and downstream logs are from my seat; the orchestrator will reproduce them for the landing record.
