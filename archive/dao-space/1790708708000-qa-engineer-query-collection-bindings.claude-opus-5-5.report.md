Completed-GMT: 2026-09-29 20:16:37 GMT
Completed-Local: 2026-09-30 03:16:37 +07
Coding-Agent: claude
Session-ID: dc7eb4bb-63f0-4a22-93c8-972bbef01e47

# Report: dao.space.query — collection and relation binding forms for function-clause results

Status: done. Main tree, on top of master b9665935. Nothing staged or committed. Only the four allowed files changed.

## Result

Function-clause results now accept the same binding forms as `:in`, with Datomic semantics:
- scalar `?x` binds the whole result (unchanged);
- tuple `[?a ?b]` binds by position (unchanged, including the arity error);
- collection `[?x ...]` gives one binding per element;
- relation `[[?a ?b]]` gives one binding per tuple. A tuple of the wrong arity throws the existing "tuple binding arity" error.

`[(identity ?ops) [?arg ...]]` over `[21 :yin/operands [23 24]]` now walks the operands. At the REPL, after `(defn inc [i] (+ i 1))`, "all literal operands of yin/def" returns `#{[inc]}` on every VM.

Terms inside a form go through `unify`, as pattern terms do:
- `_` matches anything and binds nothing;
- a bare symbol stays a constant comparison (`[foo ...]` keeps only elements equal to the symbol `foo`);
- an already-bound variable filters the elements.

An empty collection or relation produces no binding, so the row is filtered out. A result that is not sequential (`42`, `:k`, `nil`, a map or a set) for a collection or relation form throws `Function return for a collection|relation binding must be sequential`. Malformed forms throw `Unsupported binding form — use ?x, [?a ?b], [?x ...] or [[?a ?b]]`. Examples: `[?a ?b ...]`, `[[?a ?b] ...]`, `[[?a] [?b]]`, `[?a [?b]]`, `[[?a ...]]`.

## Implementation (src/cljc/dao/space/query.cljc)

- New `unify-tuple`: checks the arity, then unifies position by position. This is the old inline tuple logic, pulled out so relation tuples can share it.
- New `bind-fn-result`: the plain (non-special-form) path of `eval-fn-clause` now calls it. Special forms are unchanged and still take only a scalar binding.
- Reuse of the `:in` machinery: `bind-fn-result` classifies the form with the existing `classify-in-pattern`, so there is one grammar for binding forms. It does not reuse `expand-in-binding`, because that function builds bindings with `zipmap`. That skips unification: it would overwrite an already-bound variable, turn `_` into a key, and bind a bare symbol instead of comparing it. Function results must unify against the current binding, so the expansion step goes through `unify`/`unify-tuple`.
- Portability: plain CLJC with no host conditionals.

## Docs (docs/design/dao.space.query.md, "Predicates & function clauses")

- States that `[(f ?a ...) <form>]` accepts the same binding forms as `:in`, with their semantics, the `:yin/operands` example, the term rule inside forms, and the empty-collection behaviour.
- Replaces "collection/relation binding forms … not implemented … throw" with the actual errors: malformed form, tuple/relation-tuple arity, and non-sequential results (nil, map and set included).
- I used `<form>` rather than `out` because the earlier Terms paragraph uses `out` as its bare-symbol constant example.

## Tests

`test/dao/space/query_test.cljc` (fixture `def-application-datoms`, a yin/def application with one vector-valued `:yin/operands`):
- `fn-clause-collection-binding-binds-each-element`: yields both operands; literal operands of yin/def give `#{[inc]}`; a bound `?arg` filters.
- `fn-clause-relation-binding-binds-each-tuple`: binds each tuple; a bound var filters; a wrong-arity tuple throws.
- `fn-clause-empty-collection-binds-nothing`: `[?x ...]`, `[[?a ?b]]`, `[_ ...]` and `[[_ _]]` over `[]` all give `#{}`.
- `fn-clause-non-sequential-result-is-an-error`: both forms × `42 :k nil {:a 1} #{1}`.
- `fn-clause-blank-and-constants-inside-collection-and-relation`: `_` in both forms; bare-symbol constants in both forms.
- `fn-clause-tuple-and-scalar-bindings-are-unchanged`: tuple, scalar, tuple-arity error, and five malformed forms.

`test/yin/repl/query_test.cljc`:
- `a-collection-binding-walks-the-operands-vector`: after `(defn inc [i] (+ i 1))`, the query gives `#{[inc]}` on every VM type.

### Test-first record (unmodified source)
`clj -M:test -n dao.space.query-test -n yin.repl.query-test` gave **87 tests, 501 assertions, 15 failures, 14 errors**. All of them were in the new tests:
- the collection, relation, empty and blank/constant tests errored with `Unsupported binding form — only a scalar ?out or tuple [?a ?b]`;
- the non-sequential test failed 10 times (the wrong message);
- the relation-arity case failed once;
- the REPL test failed 4 times, one per VM, with `query failed: Unsupported binding form …`.

`fn-clause-tuple-and-scalar-bindings-are-unchanged` passed on the old source, which confirms existing behaviour is preserved.

After the change: 87 tests, 501 assertions, 0 failures, 0 errors.

### Temporary mutations (applied one at a time with Edit, each reverted)
| Mutation | Result |
|---|---|
| M1: collection branch returns `[]` | 9 failures: collection test ×3, blank/constant ×2, REPL test ×4 (all VMs) |
| M2: collection `assoc` instead of `unify` (ignores bound vars and constants) | 2 failures: bound-`?arg` filter, `[foo ...]` over `[bar baz]` |
| M3: sequential check disabled | 10 failures: every non-sequential case, including nil/map/set, which would otherwise silently yield nothing or iterate |
| M4: relation binds only its first tuple | 5 failures: relation ×3, relation `_` and constant cases |

Revert check: `grep -c "take 1 ret\|kind) false\|assoc binding term" src/cljc/dao/space/query.cljc` gave `0`. `git diff --stat` shows only the four allowed files.

## Verification
- clj-kondo (`clj -M:kondo --lint` on the three changed .cljc files): errors 0, warnings 0.
- cljstyle check: **BLOCKED**. The `cljstyle check` invocation was denied by the harness permission gate in this session, so it was not run.
- Focused JVM (`clj -M:test -n dao.space.query-test -n yin.repl.query-test -n yin.vm.linker-test`): 145 tests, 916 assertions, 0 failures, 0 errors.
- Full JVM (`clj -M:test`): 2384 tests, 184495 assertions, 0 failures, 0 errors.
- `bb test:cljs`: 2289 tests, 50961 assertions, 0 failures, 0 errors. "Testing dao.space.query-test" and "Testing yin.repl.query-test" both appear.
- `bb test:cljd`: not run, per the brief.

## Notes for the orchestrator
- `nil` as a collection/relation result throws; it is not treated as empty. The brief said "non-sequential result → clear query error", and Datomic's handling of nil here is loosely specified, so I took the strict reading. A fn that can return nil should be wrapped (e.g. `(or ?x [])`) or guarded by a predicate. Changing this later is a one-line edit if the owner wants nil to mean empty.
- Maps and sets are rejected as well (they are not `sequential?`), so there is no hash-order iteration.
- A collection binding with `_` or a bare constant can produce duplicate bindings. These collapse in the existing set/aggregate pipeline, the same as a pattern with `_`.
