Completed-GMT: 2026-10-25 07:09:46 GMT
Completed-Local: 2026-10-25 14:09:46 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

Both P1s and the syntax-depth residual are fixed, and every gate passes. Both of the architect's repros now give structured outcomes. Nothing is staged or committed, and I did not touch B-tree, ble, Flutter, `deps.edn` or other docs files.

## P1-1: function-result binding is now accounted
- **Incremental producer.** `bind-fn-result` no longer builds a vector for collection and relation results. It returns the items plus a per-item attempt, and `emit-fn` folds over them. It charges one `:candidate` per attempted element, rejected ones included. It checks for a halt before advancing the source, and never builds the complete output first.
- **Halt before advance.** Both fold helpers now check for a halt before calling `next`, so a first-witness stop no longer realizes the following element. Previously `next` ran first and exposed the problem in the first-witness test.
- **Input expansion.** The pre-accounting `(count val)` is gone, and `expand-in-binding` is lazy. A collection or relation input is pulled one binding at a time and charged per binding.
- **Accounting number.** The input-expansion step count in the published unit table changes from 19 to 17, because the pre-count was removed.

Behavior on your repro query (`[(identity ?xs) [0 ...]]`, `:max-steps 100`):

| Case | Before | After |
|---|---|---|
| 10 candidates, core | 9 steps | completes |
| 10 candidates, adapter | `[]` | `[]` |
| 10,000 candidates, core | 9 steps | `{:kind :step-budget-exhausted :phase :join :steps 100}`, result nil |
| 10,000 candidates, adapter | `[]` | `:step-budget-exhausted`, category `:interrupted`, phase `:join`, steps 100 |
| lazy 10,000-item input, adapter | realized all 10,000 first | same structured exhaustion |

## P1-2: source chains are iterative and accounted
- **Admission.** `source-admissible?` is now a loop that charges one `:parse` unit per descriptor hop and returns `[state admissible?]`. A published or deferred terminal is still refused before any read.
- **Realization.** `realize-st` walks the view chain down in one loop, one unit per hop, and applies the views back up in a second loop.

Behavior on a 10,000-deep `(query/current ...)` chain over a materialized relation:

| Call | After |
|---|---|
| `:max-steps 100` | `:step-budget-exhausted`, phase `:parse`, steps 100 |
| `:max-steps 100000000` | completes with `[]` |
| unbounded | completes with `[]` |
| 10,000-deep chain over a published terminal, generous cap | `:unsupported` / `:bounded-source-unqualified`, zero reads |
| same chain, cap 100 | structured exhaustion, zero reads |

## Syntax depth
There is now a documented public constant, `dao.space.query.datomic/max-syntax-depth` = 64.
- **Where it applies.** An iterative extent check covers native query maps and vectors, pull patterns, and rules passed at `%`. Over-deep syntax gets `:unsupported` with reason `:syntax-depth-limit`, before anything recurses. The string scanner uses the same constant, and the node count it returns drives the parse charge.
- **What stays unmeasured.** Errata E14 records the paths not measured: data in `:args`, direct callers of core `q` with deep syntax, and the public `rows` helper over view chains.

## New tests
- **Function-result charging:** rejected, relation and infinite-collection inputs, exact boundary, and first-witness over a lazy result that would throw on its second element.
- **Deep chains:** structured exhaustion, safe refusal over a published terminal, and unbounded completion.
- **Syntax depth:** nested `not`, pull and rules past the limit.

## Verification
| Command | Result |
|---|---|
| `dao.space.query-test` | 72 tests, 282 assertions (unchanged) |
| `dao.space.query.datomic-test` | 29 tests, 222 assertions |
| `dao.space.query.execution-test` | 42 tests, 259 assertions |
| `dao.space.query.datomic-execution-test` | 22 tests, 190 assertions |
| `dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions |
| `bb test:sub dao.space` | clj 15 namespaces / 420 tests / 0 failures; cljs 14 / 414 / 0; cljd 14 / 414 / 0 |
| Downstream JVM (query-numeric, positional-query, index, index-numeric, transactor, dht, transact, schema) | 232 tests, 38,925 assertions, 0 failures, 0 errors |
| kondo on all changed files | 0 errors, 0 warnings |
| `bb check:node-discovery` | `Node discovery OK: 215 source files outside test/` |

Adding `query-test` to the downstream run gives 304 tests and 39,207 assertions, the same as before. The corpus JSON is untouched, so the digest pins are unchanged.

## Concerns
- **Syntax bound is a profile restriction.** Refusing syntax deeper than 64 is a new declared limit, and you should confirm the constant and the `:unsupported` category. A legitimate query that nests deeper is now refused.
- **Descriptor-hop cost.** Each hop costs one unit even when the chain is cheap, so a 10,000-deep legitimate chain needs a cap of at least about 10,000. I judged that correct accounting.
