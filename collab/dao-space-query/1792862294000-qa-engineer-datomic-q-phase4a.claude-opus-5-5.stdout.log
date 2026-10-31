Completed-GMT: 2026-10-24 17:58:48 GMT
Completed-Local: 2026-10-25 00:58:48 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

**Phase 4a is implemented, and its qualification is limited to materialized sources.** Bounded queries over opened published indexes remain refused and unqualified (Phase 4b). The overall Phase 4 and the published-index backend objective are incomplete. Every verification passed on all three hosts, but I never ran the full `bb` gates: only the `dao.space` subsystem, plus the JVM suites for the downstream namespaces listed below.

## Delivered
- **One state-passing evaluator (`query.cljc`).**
  - **Shape:** the lazy evaluator is rewritten as a continuation-passing, depth-first kernel returning `[state result]`. Legacy `q` runs it under `exec/unbounded`. The bounded entry is the new public `q-with-state` (`[st driver query & inputs]` → `[st result]`).
  - **Unchanged and preserved:** the public signatures and result-value shape are unchanged, and there is no second evaluator. `plan-where` and `estimate-clause-cost` are unchanged (moved, planning still never forces a source), and published indexes (unbounded) keep the same selective index route and the same forcing points.
  - **Existential checks:** `not` and `not-join` stop at the first witness. Rule bodies run in an extended lexical scope while the returned progress continues, so active-rule membership never leaks into sibling branches and progress is never rolled back.
  - **Accounted work:** deduplication is a seen-set carried in the accumulator. Index scans are collected one candidate at a time, charged per tuple. Pull, aggregation, input expansion and projection are charged too. Realization charges the work first, then observes after the indivisible host operation.
  - **Error data:** I removed `:binding` from the `not-join` and special-form error data, as the contract asked.
- **`execution.cljc` corrections per the ruling.** Terminal states are fully absorbing (the whole state is returned unchanged, phase included). `read-step` returns `::discarded` when the post-read observation expires. I added `step` (observe, then charge), `enter`, `phase` and `bounded?`. Expiry is `now >= deadline`.
- **Adapter (`datomic.cljc`).**
  - **Options:** `:timeout` and `::max-steps` take positive integers. A new `::now-fn` extension injects a clock.
  - **Clock and deadline:** the deadline is minted before parsing. The default clock is monotonic ms on JVM and JS, with a documented wall-clock fallback on Dart.
  - **One state:** a single state runs from parse through translation and the kernel to output, then `finish`.
  - **Errors:** exhaustion becomes `:timeout` or `:step-budget-exhausted` (category `:interrupted`) with only `:option`, `:phase` and `:steps` as context.
  - **Admission:** bounded calls check every declared source before any realization or read. Only a vector relation/fact-relation, or a view over one, is admitted. Published, deferred and unrecognized sources are refused with `:unsupported` and `:reason :bounded-source-unqualified`. Unbounded calls keep their existing source support.
- **`datomic_examples.cljc`.** The five workload classes, over an in-memory current view. I ran them and they return sensible data. They are not covered by the test suite, because `src/dev` is not on the `:test` classpath.

## Verification
| Command | Outcome |
|---|---|
| `dao.space.query-test` | 72 tests, 282 assertions, 0 failures (unchanged) |
| `dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions, 0 failures |
| `dao.space.query.datomic-test` | 29 tests, 222 assertions, 0 failures |
| `dao.space.query.execution-test` | 35 tests, 234 assertions, 0 failures |
| `dao.space.query.datomic-execution-test` | 17 tests, 169 assertions, 0 failures |
| Those five together | 170 tests, 948 assertions, 0 failures |
| JVM, downstream: query-numeric, positional-query, index, index-numeric, transactor, dht, transact and schema tests | 304 tests, 39,207 assertions, 0 failures |
| `bb test:sub dao.space` | 408 JVM, 402 Node, 402 Dart tests, 0 failures on all three |
| `clj -M:kondo --lint` on every changed file | 0 errors, 0 warnings |
| `bb check:node-discovery` | `Node discovery OK: 215 source files outside test/` |

`dao.space.query.datomic-test` went from 226 to 222 assertions because I removed the assertions that pinned the old `:timeout` refusal. The new coverage includes:
- **Integration:** exact accounting numbers published as the unit table, the exact allowance boundary, and sibling branches, nested negation and rules sharing one allowance. Also first-witness termination, rule-scope restoration, rejected-candidate scans, empty scans and fresh-value recursion exhausting caps.
- **Realization and output:** checked realization, input expansion, pull and aggregation, deterministic deadline expiry at every observation point, and a host function returning past the deadline whose answer is not interpreted.
- **Admission and ownership:** source-admission refusals (direct, wrapped, cached, mixed and deferred sources) with zero reads, including a source unused by the plan, and ownership preserved.

## Concerns
1. **Rule-depth guard — your decision.** The kernel's stack depth grows with nested rule expansion, and the old lazy evaluator had the same limit (about 400 levels unbounded). A generous work allowance on fresh-value recursion would otherwise end in a host `StackOverflowError`, already at `:max-steps 1000` on the JVM.
   - **What I added:** a bounded-only limit of 200 nested rule levels (`max-bounded-rule-depth`). It refuses with `:reason :rule-depth-limit`, which the adapter maps to `:unsupported`. Small caps still exhaust first. Unbounded behavior is unchanged.
   - **Why it needs you:** this is a heuristic. Removing the limit properly needs a trampolined or explicit-frame kernel, which is a larger rewrite. Whether `:unsupported` is the right category is also your call, as is the 200 limit. Phase 3 tabling would affect it as well.
2. **Static validations moved earlier.** The `or`/`or-join` checks now run eagerly when a clause list is entered. A malformed disjunction that is unreachable now errors where the old evaluator never reached it. No existing test hit this.
3. **Indivisible operations.** Checkpointing is per unit, not per host call:
   - Index construction and current-state resolution run once after their units are charged.
   - Host functions, the EDN reader and bulk adapter parsing run as one operation, charged beforehand (reader by length).
   - The deadline can overshoot inside one such operation. This is documented in the code, but I have not covered it with a test.
4. **No published-index evidence.** I added no new regression test for unbounded selective reads over lazy published indexes. The existing index, DHT and transactor suites pass and use that path.
5. **Corpus.** I did not add executable timeout cases to the corpus and did not re-digest it (no pins changed).
6. **Real-clock smoke is JVM-only.** On Node and Dart the clock-reading path runs only through the fake `now-fn`.
7. **File fixes.** I fixed my own Phase 1 and Phase 4 tests, which would have broken the Dart build: `real-clock-smoke` and `unexpected-evaluator-failure-keeps-its-cause` are now guarded with `:cljd nil` first, as is the `thrown?` host-type conditional.

I touched no B-tree, ble, Flutter, `deps.edn` or other docs files. The dirty `docs/design/dao.space.query.datomic-compat.md` and `docs/orchestrator-log.md` are not mine. `demo-broke.md` and `foo.clj` are stray files I didn't create. Nothing is staged or committed.
