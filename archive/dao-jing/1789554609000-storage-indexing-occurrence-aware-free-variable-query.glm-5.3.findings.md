Created-GMT: 2026-09-16 03:45:44 GMT
Created-Local: 2026-09-16 10:45:44 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 366e0369-c54c-4b83-894c-96fe70ea8458
# Findings: Occurrence-aware free-variable query resolves the mixed free/bound case
Role: Storage & Indexing
Task: collab/1789554609000-storage-indexing-occurrence-aware-free-variable-query.prompt.md
Status: done — test passes against the real engine, contrast assertion included,
nothing staged or committed

## Test results

```
$ clojure -M:test -n dao.space.query-test
Ran 51 tests containing 155 assertions.
0 failures, 0 errors.
```

Prior baseline was 50 tests / 149 assertions; the delta is exactly one
deftest with 6 assertions. Verified individually:

```
$ clojure -M:test -n dao.space.query-test -v dao.space.query-test/occurrence-aware-rules-resolve-mixed-free-and-bound-rows
Ran 1 tests containing 6 assertions.
0 failures, 0 errors.
```

Only `test/dao/space/query_test.cljc` modified. No change to
`dao.space.query.cljc`, `v2.cljc`, or the design doc. No production
occurrence indexer written — the occurrence relation is hand-built
fixture data, as the task specified.

## The exact rule set that worked

Two new `:fns`-supplied helpers beside the existing `member?`:

```clojure
(def ^:private path-pop
  (fn [p] (when (pos? (count p)) (subvec p 0 (dec (count p))))))
```

```clojure
(def ^:private occurrence-rules
  '[[(p-up ?child ?parent) [(path-pop ?child) ?parent]]
    [(occ-anc ?a ?d) (p-up ?d ?a)]
    [(occ-anc ?a ?d) (p-up ?d ?m) (occ-anc ?a ?m)]
    [(occ-bound? ?path ?name)
     (occ-anc ?lam-path ?path)
     [$occ ?r ?lam-path ?lam]
     [?lam :lambda ?params _]
     [(member? ?params ?name)]]])
```

The free-occurrence query (union of free names falls out of `:find
?name` over free occurrences):

```clojure
(qq '[:find ?name :in $ $occ %
      :where
      [$occ ?root ?path ?v]
      [?v :variable ?name]
      (not (occ-bound? ?path ?name))]
    db occ occurrence-rules {:fns {'member? member?, 'path-pop path-pop}})
;; => #{[x]}
```

Per-occurrence bound status of the shared row (parameterized on the row
id and name):

```clojure
(qq '[:find ?path :in $ $occ % ?v ?name
      :where
      [$occ ?root ?path ?v]
      (occ-bound? ?path ?name)]
    db occ occurrence-rules x-id 'x opts)
;; => #{[[2 3]]}   (the lambda-body occurrence only)
```

## The occurrence fixture

Hand-built `[root-address path row-id]` tuples for `((fn [x] x) x)`,
using §2.5's own path encoding (slot steps from the root; an index into
a `:nodes` slot is one pair step `[slot i]`), with row ids taken from
the real `ast->semantic-bytecode` projection so the occurrence relation
joins the actual row relation:

```clojure
[[root [] root]          ; the :application
 [root [2] lam]          ; its operator :lambda (slot 2)
 [root [2 3] x-id]       ; the lambda's body x (slot 3 of :lambda)
 [root [[3 0]] x-id]]    ; the operand x (first of the :nodes slot 3)
```

The pair-step form is load-bearing for the walk's soundness, not
cosmetic: with §2.5 paths every pop of a real path is a real path (pop
of `[[3 0]]` is `[]`, pop of `[2 3]` is `[2]`), so the recursive
`occ-anc` walk from any occurrence reaches every true ancestor. The
prompt's suggested `[:operands 0]` spelling would have generated a ghost
`[:operands]` step mid-walk — harmless when ancestor candidates are
filtered by the occurrence join, but a path to nowhere if the root
itself were ever a binder. Origin (`[:source medium batch j]`) is
elided: one tree, one admission, so it is constant throughout the
fixture.

## What the test asserts (all six pass)

1. Map side of the premise: the fixture AST really contains two
   `{:type :variable :name x}` nodes (counted by tree walk).
2. Row side of the premise: queried, both collapse to exactly one
   shared row id — `#{[x]}` for `[:find ?v :in $ ?name :where
   [?v :variable ?name]]` with `'x` — §4.4 dedup confirmed empirically,
   not assumed.
3. Bound occurrences of the shared row = `#{[[2 3]]}`: the walk
   `[2 3] → [2]` finds the binding `:lambda`; the operand occurrence is
   not bound.
4. Free occurrences = `#{[[[3 0]]]}`: the operand's walk
   `[[3 0]] → []` reaches only the `:application`.
5. The occurrence-unioned free-name set is `#{['x]}` — conservative
   (never undercounting) because one free occurrence suffices (§7.6.1).
6. **The contrast, actually run**: the prior demo's row-only
   `free-name-rules` / `bound?` on the *same* row db returns `#{}` — it
   finds the `:lambda` through the shared row's body-slot parent edge
   and drops `x` from the free-name set entirely. The §4.5 undercount is
   demonstrated, not narrated.

## Engine mechanics beyond the prior findings

The prior findings' four rules (quoted rule data, predicates can't bind
args, constants unusable as rule-head outputs, `not` requires bound
vars) all still hold and were reused. Four further mechanics, all
verified against `query.cljc` source and empirically:

1. **Multi-source queries work, and rule bodies see every source.**
   `:in $ $occ %` binds a second relation; `clause-db-and-pattern`
   (query.cljc:872-876) reads a leading `$sym` in any pattern clause as
   a source selector, and rule bodies seed from
   `(select-keys binding [::dbs '%])` (query.cljc:1174), which carries
   all `::dbs`. That is what lets `[$occ ?r ?lam-path ?lam]` live
   inside the `occ-bound?` rule body while `[?lam :lambda ?params _]`
   in the same body still refers to `$`. No engine change needed.
2. **A `:fns` clause may bind an output var.** `eval-fn-clause` requires
   its *arguments* bound but unifies the return into the trailing var
   (query.cljc:1112-1135) — same path as the builtins `ground`/`inc`.
   `[(path-pop ?child) ?parent]` is therefore the path-level analogue
   of the row-level `edge` rule, and it is what makes the recursive
   walk possible at all.
3. **Pop of the root path yields nil, which is inert here.**
   `(p-up [] ?a)` binds `?a` to nil; nil is a legal binding value
   (FREE is a distinct sentinel, query.cljc:406/786-796), never equals
   a path in the occurrence relation, and the per-call-key recursion
   guard (query.cljc:1153) ends the chain. No special-casing needed.
4. **A bare non-`?` symbol in a pattern is a binding lookup, and an
   unbound one acts as a wildcard** (`resolve-binding` returns FREE for
   unknown symbols, query.cljc:786-788; `wildcard?` includes FREE,
   :409-411). My first premise check wrote `[?v :variable x]` with bare
   `x` — it *passed* in the probe only because the fixture has a single
   variable row, so the accidental wildcard matched just as many rows
   as the constant would have. Fixed by passing `'x` as a scalar
   `:in` input. This is the sharpest silent-failure trap found in
   either demo: a constant spelled as a symbol widens the match instead
   of erroring.

## What this makes concrete for §4.5

The §4.5 claim is now demonstrated end to end against the real `q`
engine: free/bound per occurrence is computable by a recursive Datalog
rule over an occurrence relation of exactly §2.5's shape
(`[origin root-address path]` keyed, origin constant/elided in the
fixture), joining `$` for row shape and `$occ` for place — no `:global`
tag, no new row structure, no engine change. Whoever wires the real
`:yin.k/requires` computation (open item 5) can lift `occurrence-rules`
as-is; what is missing in production is only the indexer that emits
the occurrence tuples, which this task deliberately did not build.
