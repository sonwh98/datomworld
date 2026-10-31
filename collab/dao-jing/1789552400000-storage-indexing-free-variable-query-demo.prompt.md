Created-GMT: 2026-09-16 03:06:40 GMT
Created-Local: 2026-09-16 10:06:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 50e19b1e-823c-4175-ae00-7b6d27a920a6
# Task: Demonstrate free-variable extraction as a Datalog query, not an AST tag
Role: Storage & Indexing
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 10:06:40 +07 | Status: active | Rationale: dao.space.query/q is storage/indexing-layer machinery, team.md's strength pick for VMs/storage/indexing

## Context

The owner ruled tonight to retire a proposed `:global` AST tag (a
separate node type for free variable references) from
`docs/design/yin.vm.code-as-tuples.md`. The reasoning: whether a
`:variable` row is free (not bound by any enclosing `:lambda`'s params)
or bound is derivable from the existing row structure via a Datalog query
over `$ast` — it does not need to be a separate persisted tag. Read
`docs/design/yin.vm.code-as-tuples.md` §4.5 ("Free variables are queried,
not tagged") for the full rationale and the illustrative (not exact)
query shape already sketched there in §7.7.

This task is to make that claim concrete: write a test that actually
proves a recursive Datalog rule, run through the real
`dao.space.query/q` engine, correctly computes the free-name set of a
small row tree that uses only `:variable` — no `:global` tag anywhere.

## What you're working with

- `src/cljc/yin/vm.cljc`'s `ast->semantic-bytecode` (committed,
  `84f8eef`) projects a map AST into flat rows `[id tag & slots]` per
  `docs/design/yin.vm.code-as-tuples.md` §2's tag table. Relevant tags for
  this test: `:lambda [[:params :syms] [:body :node]]`, `:variable
  [[:name :sym]]`, `:application [[:operator :node] [:operands :nodes]
  [:tail? :bool]]`, `:literal [[:value :data]]`. A `node`-kind slot holds
  a child row's id; a `nodes`-kind slot holds a vector of child row ids.
- `src/cljc/dao/space/query.cljc`'s `q` supports Datalog rules bound via
  `:in $ %` (see `eval-rule`, around line 1138) — the standard
  Datomic-style recursive rule mechanism. **No existing test in this
  codebase currently exercises a recursive rule** (checked: no `:in $ %`
  usage in `test/dao/space/*.cljc`). You may be the first to exercise
  this path for real — verify everything empirically against the actual
  engine rather than assuming Datomic-standard rule syntax works
  identically here; read `query.cljc`'s `eval-rule` and its neighboring
  functions directly if the obvious syntax doesn't work.
- `test/dao/space/query_test.cljc` has the test harness pattern: `rel`
  (wraps a tuple collection as a relation), `qq`/`qcur` (collect a query
  result). Read the file's existing tests for the exact calling
  convention of `query/q` with rule sets.

## Task

Write a test (in `test/dao/space/query_test.cljc`, or
`test/yin/vm_test.cljc` if you judge that a better fit given it needs
`ast->semantic-bytecode` to build the row fixture — your call, but justify
it in your findings) that:

1. Builds a small map AST exercising the free/bound distinction
   meaningfully — at minimum: a `:lambda` binding one param, whose body
   is an `:application` calling a FREE name (e.g. `+`, not bound by any
   enclosing lambda) with the bound param and a literal as operands. Something
   like the §2.1 worked example:
   `(fn [x] (+ x 1))` — `+` is free, `x` is bound.
2. Projects it via `ast->semantic-bytecode` to get real rows (not
   hand-typed row literals) — the point is proving this works on the
   actual output of the committed codec.
3. Writes a recursive Datalog rule (or rule set) that computes, for the
   row set, the set of `:variable` names that are FREE — i.e., not bound
   by any enclosing `:lambda` reached by following `node`/`nodes`-kind
   child-id edges from the root to that `:variable` row. The rule needs
   two things: (a) a way to know which slot positions are `node`/`nodes`
   kind for the purpose of finding "children" (you can hardcode this for
   the tags in your test fixture — `:lambda`'s 3rd position is body,
   `:application`'s 3rd position is operator, 4th is operands-vector —
   rather than trying to build a fully generic schema-driven walker,
   since that's out of scope for a demonstration test), and (b) the
   actual recursive "is this variable bound by an ancestor lambda" logic.
4. Asserts the query returns exactly `#{'+ }` (or whatever your worked
   example's actual free name is) as the free-name set, and does NOT
   include `'x` (the bound param).
5. Add a second case: nested lambdas with shadowing (an outer lambda
   binding `x`, an inner lambda also binding `x`, and a variable
   reference to `x` inside the inner lambda's body) — assert the inner
   reference resolves as bound (by the inner lambda), demonstrating the
   rule correctly handles the nearest-enclosing-binder case, not just
   "is this name bound anywhere in the tree."

## Contract

The test must actually run and pass against the real `dao.space.query/q`
engine — not a hand-simulated or mocked version. If recursive rules turn
out not to work as expected in this engine, report exactly what you tried
and what actually happened; do not paper over a real limitation by
weakening the test to something that doesn't really prove the claim.

Run `clojure -M:test -n dao.space.query-test` (or `yin.vm-test`,
whichever file you chose), confirm 0 failures, and report the exact
recursive rule you ended up with.

## Boundaries

Only the one test file you choose. Do not modify `dao.space.query.cljc`,
`v2.cljc`, or the design doc (already correct). Do not implement a
general-purpose "extract free names from any AST" production function —
this is a demonstration/proof test, not new library code.

## Deliverable

Report the exact test added, the exact rule syntax that worked (or
didn't — report honestly either way), and test results. Write findings
to
`collab/1789552400000-storage-indexing-free-variable-query-demo.claude-sonnet-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.
