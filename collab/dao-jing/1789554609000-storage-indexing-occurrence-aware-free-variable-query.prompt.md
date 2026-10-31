Created-GMT: 2026-09-16 03:36:49 GMT
Created-Local: 2026-09-16 10:36:49 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 366e0369-c54c-4b83-894c-96fe70ea8458
# Task: Prove the occurrence-aware query resolves the mixed free/bound case the row-only query cannot
Role: Storage & Indexing
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 10:36:49 +07 | Status: active | Rationale: same lane as the prior free-variable-query demo, continuation of that proof

## Context

Read `docs/design/yin.vm.code-as-tuples.md` §4.5 in full (the whole
section, it was substantially revised tonight) before starting. Summary
of what you need to know:

A prior task (`collab/1789552400000-storage-indexing-free-variable-query-demo.prompt.md`,
findings at
`collab/1789552400000-storage-indexing-free-variable-query-demo.claude-sonnet-5.findings.md`)
proved a recursive Datalog rule can compute a tree's free-variable names
from `:variable`-tagged rows alone, with no separate `:global` tag —
verified against the real `dao.space.query/q` engine. That demo's rule
(`bound?`) walks a **row's** parent edges (content-addressed structural
sharing means one row can have multiple parents/occurrences). This is
correct EXCEPT in one case: a name that is free at one occurrence and
bound at another in the same reachable tree (e.g. `((fn [x] x) x)` — the
operand `x` is free, the lambda body's `x` is bound) collapses to one
shared `:variable` row, and the row-level `bound?` finds a binding
ancestor via *either* occurrence's edge, incorrectly calling the WHOLE
row (hence both occurrences) bound — silently dropping the free
occurrence from what should be a conservative (never-undercounting)
dependency set.

§4.5 now states, but does not yet prove, that this is fixable by
querying at the **occurrence** level (walking a specific occurrence's own
path to its ancestors) rather than the row level — using the occurrence
relation §2.5 describes (`[origin root-address path]`-keyed side tables),
which is NOT implemented by any indexer in this codebase yet (verified:
`grep -rln occurrence src/cljc/dao/space/ src/cljc/yin/` finds no
`[origin root-address path]`-shaped relation anywhere — only unrelated
uses of the English word). Your job is to make the §4.5 claim concrete
and verified, the same way the row-level demo did for its claim, without
waiting for a real occurrence indexer to exist.

## Task

Write a test (append to `test/dao/space/query_test.cljc`, alongside the
prior demo's two tests) that:

1. Builds the mixed-occurrence fixture: a map AST equivalent to
   `((fn [x] x) x)` — an `:application` whose operator is `{:type
   :lambda :params '[x] :body {:type :variable :name x}}` and whose
   single operand is `{:type :variable :name x}` (a second, structurally
   identical `:variable` node — same tag, same name). Project it via
   `yin.vm/ast->semantic-bytecode` (same as the prior demo) and
   confirm empirically that both `x` references do collapse to the same
   row id (this is the premise the whole exercise depends on — verify
   it, don't assume it).
2. Since ast->semantic-bytecode doesn't emit occurrence-relation facts
   (they're not implemented anywhere), hand-construct an occurrence
   relation fixture for this specific tree as test data: a `path` is
   simply a vector of steps from the root to a node — invent a
   reasonable, minimal path encoding for the fixture (e.g. `[:operator]`,
   `[:operands 0]`, `[:body]`, matching how §2's tag table names each
   tag's slots) sufficient to uniquely identify the operator-lambda's
   body-x-occurrence versus the application's operand-x-occurrence, even
   though both point at the same row id. This does not need to match any
   production indexer's exact format — it needs to be internally
   consistent and prove the query technique.
3. Write a recursive Datalog rule (or rule set) that computes free/bound
   status **per occurrence** (per path), not per row: for a given
   occurrence's path, walk up by path-prefix (not by row edge) to find
   an enclosing `:lambda` occurrence whose params bind the name. Prove
   this correctly says the operand-`x` occurrence is FREE and the
   lambda-body-`x` occurrence is BOUND, even though both occurrences
   share the same underlying row.
4. Assert the free-name SET computed by unioning per-occurrence results
   correctly includes `x` (because at least one occurrence is free) —
   contrast this explicitly, in a comment or a second assertion, against
   what the prior demo's row-only `bound?` rule would incorrectly
   conclude for this same fixture (that the shared row is bound
   everywhere, so `x` is NOT free) — actually run the row-only rule
   against this fixture too and assert it DOES get it wrong, so the test
   proves the contrast, not just asserts the fix in isolation.

## Contract

The test must run and pass against the real `dao.space.query/q` engine —
same discipline as the prior demo: verify engine mechanics empirically
(quoted rule data, predicate argument-binding order, `not` variable
requirements, etc. — read the prior findings file for what was already
discovered about this engine's specific quirks, don't rediscover them
from scratch) rather than assuming Datomic-standard behavior. If
something about occurrence/path-based querying doesn't work as expected,
report exactly what you tried and what happened — do not weaken the test
to something that doesn't really prove the claim.

Run `clojure -M:test -n dao.space.query-test`, confirm 0 failures,
report exact assertion counts.

## Boundaries

Only `test/dao/space/query_test.cljc`. Do not modify
`dao.space.query.cljc`, `v2.cljc`, or the design doc (the orchestrator
will update §4.5 to point at your test once it's verified). Do not
implement a production occurrence-relation indexer — this is a
demonstration/proof test using a hand-built fixture, not new library
code.

## Deliverable

Report the exact test added, the exact rule set, confirmation the
contrast assertion (row-only rule gets it wrong on this fixture) passes,
and test results. Write findings to
`collab/1789554609000-storage-indexing-occurrence-aware-free-variable-query.glm-5.3.findings.md`
with the same header block as this prompt. Nothing staged or committed.
