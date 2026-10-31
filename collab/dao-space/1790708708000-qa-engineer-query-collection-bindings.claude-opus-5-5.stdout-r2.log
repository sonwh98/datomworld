Completed-GMT: 2026-09-30 03:17:58 GMT
Completed-Local: 2026-09-30 10:17:58 +07
Coding-Agent: claude
Session-ID: dc7eb4bb-63f0-4a22-93c8-972bbef01e47

# Report r2: owner decisions on nil and set results

**Status:** done. Both owner decisions are in, and every required check passed in the foreground: kondo, focused JVM, `bb test:cljs` and `bb test:cljd`. The work is on top of master b9665935 in the main tree, nothing is staged or committed, and only the four allowed files changed (cumulative across r1 and r2).

## What changed

Collection (`[?x ...]`) and relation (`[[?a ?b]]`) forms in function clauses now handle results like this:

| Result | Behaviour |
|---|---|
| `nil` | No binding; the row is filtered out, like an empty collection. |
| A set (`#{7 8}`) | One row per element. |
| A set of tuples (`#{[1 :a] [2 :b]}`) for a relation | One row per tuple. |
| A map, string, keyword or number | Still an error: `Function return for a collection\|relation binding must be nil, sequential or a set`. |

- Order doesn't matter: every test compares result sets, and none asserted order.
- Each tuple inside a relation must still be sequential and of the right arity; otherwise the existing "tuple binding arity" error is thrown.

**Code** (`src/cljc/dao/space/query.cljc`, `bind-fn-result`): the check is now `(or (nil? ret) (sequential? ret) (set? ret))`. Iterating `nil` or a set with `keep` already gives the right bindings, so no other code changed. The docstring was updated to match.

**Docs** (`docs/design/dao.space.query.md`, "Predicates & function clauses"):
- Collection and relation forms take a sequential result or a set (a set of tuples for a relation), and order is irrelevant because the result is a set.
- A `nil` result, like an empty one, gives no binding and filters the row out.
- The throw list now says a collection or relation form throws on a result that is neither `nil`, sequential, nor a set (a map, string or scalar).

## Tests (test/dao/space/query_test.cljc)

- `fn-clause-empty-collection-binds-nothing` now covers 4 forms (`[?x ...]`, `[[?a ?b]]`, `[_ ...]`, `[[_ _]]`) × 3 results (`[]`, `#{}`, `nil`); every case gives `#{}`.
- New `fn-clause-set-result-binds-each-element`:
  - `#{7 8}` gives `#{[7] [8]}`;
  - a set of tuples gives one row per tuple;
  - a bare-symbol constant inside a relation over a set still acts as a constant.
- `fn-clause-non-sequential-result-is-an-error` is renamed to `fn-clause-non-collection-result-is-an-error`. It now checks both forms × `42 :k "ab" {:a 1}` and expects the message "sequential or a set". `nil` and the set case moved to the tests above.

### Test-first record (r1 code, updated tests)
`clj -M:test -n dao.space.query-test -n yin.repl.query-test` gave 88 tests, 510 assertions, **8 failures, 11 errors**, all caused by the owner decisions:
- 8 errors in the empty test (the `#{}` and `nil` cases for the 4 forms);
- 3 errors in the set test (all three assertions);
- 8 failures in the non-collection test (the old message text).

After the change: 0 failures, 0 errors.

### Temporary mutations (applied with Edit, reverted)

| Mutation | Result |
|---|---|
| Check narrowed back to `sequential?` only (rejects nil and sets) | 11 errors: empty test ×8, set test ×3 |
| Check widened to `(or (nil? ret) (coll? ret))` (accepts maps) | 2 failures: the map case for both forms |

Revert check: `grep -c "coll? ret\|(or (sequential? ret))"` on `query.cljc` gives `0`.

## Verification (all run in the foreground)

| Check | Result |
|---|---|
| kondo (`clj -M:kondo --lint` on the 3 changed .cljc files) | 0 errors, 0 warnings |
| Focused JVM (`dao.space.query-test`, `yin.repl.query-test`, `yin.vm.linker-test`) | 146 tests / 925 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2290 tests / 50970 assertions, 0 failures, 0 errors; "Testing dao.space.query-test" and "Testing yin.repl.query-test" both appear |
| `bb test:cljd` | 2252 tests, "All tests passed!" |

For `bb test:cljd`, the new tests (`fn-clause-set-result-binds-each-element`, `a-collection-binding-walks-the-operands-vector`) are present in `test/cljd-out/dao/space/query-test_test.dart` and `test/cljd-out/yin/repl/query-test_test.dart`.

Not rerun in r2 (not in the r2 list): full `clj -M:test` (r1: 2384 tests, 0 failures, 0 errors) and cljstyle (blocked by the permission gate in r1).
