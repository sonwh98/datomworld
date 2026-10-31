Created-GMT: 2026-09-16 03:18:36 GMT
Created-Local: 2026-09-16 10:18:36 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 50e19b1e-823c-4175-ae00-7b6d27a920a6
# Findings: Free-variable extraction as a Datalog query, not an AST tag
Role: Storage & Indexing
Task: collab/1789552400000-storage-indexing-free-variable-query-demo.prompt.md
Status: done — both tests pass against the real engine, nothing staged or committed

## File chosen

`test/dao/space/query_test.cljc` (two deftests appended, one `:require`
added: `[yin.vm :as v2]`). Justification: `docs/design/yin.vm.code-as-tuples.md`
§4.5 already points at exactly this file ("See `test/dao/space/query_test.cljc`
for a working demonstration"), and the thing under test is the recursive-rule
capability of `dao.space.query/q` itself — the codec only builds the fixture.

## Test results

```
$ clojure -M:test -n dao.space.query-test
Ran 50 tests containing 149 assertions.
0 failures, 0 errors.
```

Both new tests verified individually (not just swallowed by the suite):

- `recursive-rules-compute-free-names-not-tags` — 1 assertion, 0 failures.
- `recursive-rules-resolve-the-nearest-enclosing-binder` — 6 assertions, 0 failures.

No modification to `dao.space.query.cljc`, `v2.cljc`, or the design doc.

## The exact rule set that worked

Quoted data, standard Datomic rule-set shape, bound via `:in $ %` with
`member?` supplied under `:fns` (the options map rides as the one extra
input after the `:in` inputs):

```clojure
(def ^:private member?
  (fn [coll x] (boolean (some #(= % x) coll))))

(def ^:private free-name-rules
  '[[(edge ?p ?c) [?p :lambda _ ?c]]
    [(edge ?p ?c) [?p :application ?c _ _]]
    [(edge ?p ?c) [?p :application _ ?ops _] [?c _ & _] [(member? ?ops ?c)]]
    [(anc ?a ?d) (edge ?a ?d)]
    [(anc ?a ?d) (edge ?p ?d) (anc ?a ?p)]
    [(depth ?a ?d ?n) (edge ?a ?d) [(ground 1) ?n]]
    [(depth ?a ?d ?n) (edge ?p ?d) (depth ?a ?p ?m) [(inc ?m) ?n]]
    [(bound? ?v ?name) (anc ?l ?v) [?l :lambda ?params _] [(member? ?params ?name)]]])
```

Free-name query (case 1, `(fn [x] (+ x 1))`):

```clojure
(qq '[:find ?name :in $ %
      :where
      [?v :variable ?name]
      (not (bound? ?v ?name))]
    db free-name-rules {:fns {'member? member?}})
;; => #{['+]}
```

Binder and nearest-binder queries (case 2) parameterize on `?v ?name`
scalars and reuse `anc` / `depth`.

## What the tests assert

Case 1 (`(fn [x] (+ x 1))`, projected through the committed
`ast->semantic-bytecode`): the free-name set is exactly `#{'+}` — the bound
param `x` is absent.

Case 2 (`(fn [x y] (fn [x] (+ x y)))` — outer binds `x` and `y`, inner
shadows `x`):

- free set is still exactly `#{'+}` (x bound under shadowing, y bound);
- x's enclosing binders are exactly {inner, outer};
- y's only enclosing binder is the outer lambda — the rule walks past the
  inner lambda that does not bind y, which is what an
  immediate-parent-only implementation would get wrong;
- `depth` (a recursive rule that accumulates a value) gives x's nearest
  binder at chain length 2 = the inner lambda (outer is 3), and y's only
  binder at 3 = the outer lambda — the nearest-enclosing-binder case
  literally, not just "bound somewhere in the tree".

## Engine specifics verified against `query.cljc` source (not assumed)

Recursive rules work as-is — nothing about the engine needed papering
over. But four mechanics differ from or are stricter than
Datomic-by-assumption, and the rule set above is shaped by them:

1. **The rule set must be quoted data.** A bare vector def is compiled as
   code: `Unable to resolve symbol: edge` at the `(edge ?p ?c)` head.
   Quoting is safe here precisely because fn references (`member?`) are
   looked up by symbol under `:fns`, never evaluated.
2. **Predicates cannot bind their own arguments** (`eval-fn-clause` throws
   "Unbound variable in fn clause" on a FREE arg), so operand edges cannot
   be `[(member? ?ops ?c)]` with `?c` free. The working shape binds `?c`
   first by joining every row (`[?c _ & _]` — the rest-tail pattern
   matches any row of arity ≥ 2, and row ids are always slot 0), then
   filters with `member?`.
3. **Constants in a rule head are unusable as outputs** — the output step
   does `(get result head-var FREE)` and throws "Rule head var not bound
   by rule body" for a constant head arg. Hence `depth`'s base case binds
   through the fn clause `[(ground 1) ?n]` (`ground` and `inc` are engine
   builtins) instead of writing `(depth ?a ?d 1)` as a head.
4. **`not` requires every variable inside it already bound** (`eval-not`
   throws otherwise), which is why `bound?` takes `?v ?name` as head args
   rather than introducing locals. Rule bodies seed from
   `(select-keys binding [::dbs '%])`, so no outer variable leaks into a
   rule body anyway — everything flows through head args, standard
   Datalog.

Also confirmed: `query/relation` sets no `:fact?`, so the arity-3
`[?v :variable ?name]` pattern takes the general positional-unification
path and is not misread through the 3-slot EAV fast path; `eval-rule`'s
recursion guard is a per-call-key active check (not a fixpoint), which
terminates here because every recursive call climbs a bound parent edge
of a finite acyclic chain.

## Known limitation the demo deliberately avoids (design-known, not engine)

Because rows are content-addressed and deduplicated (§4.4), two
occurrences of the same name are one row. A tree where one occurrence of
a name is free and another is bound (e.g. `((fn [x] x) x)`, whose operand
`x` is free while the lambda body's `x` is bound) cannot be resolved at
row level — the row-level query would call `x` bound. That is §6.1's own
statement that places-not-contents questions need the occurrence relation
(and §4.2 blocks id-equality collapse). Both fixtures use names whose
occurrences are uniformly bound or uniformly free, which is the honest
scope of a demonstration on the row relation alone.
