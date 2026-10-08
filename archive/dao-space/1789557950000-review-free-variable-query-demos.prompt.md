Created-GMT: 2026-09-16 04:25:50 GMT
Created-Local: 2026-09-16 11:25:50 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: deepseek
Session-ID: 12c027e8-bcf9-4afc-b68e-3bce8fcf1f3e
# Task: Review the free-variable Datalog demonstration tests
Role: Adversarial Review
Implementers:
- Model: deepseek-flash | Assigned: 2026-09-16 11:25:50 +07 | Status: active | Rationale: cross-family from glm-5.3 (implementer)

## Context

Read `docs/design/yin.vm.code-as-tuples.md` §4.5 in full first — it cites
both tests you're reviewing as proof of its central claim (that a
retired `:global` AST tag's job is fully replaceable by a Datalog query
over `dao.space.query/q`, with no persisted structure needed).

Two deftests were added to `test/dao/space/query_test.cljc` by glm-5.3,
across two rounds:

1. `recursive-rules-compute-free-names-not-tags` +
   `recursive-rules-resolve-the-nearest-enclosing-binder` — a recursive
   rule set (`edge`/`anc`/`depth`/`bound?`) computing a tree's free-name
   set from row-level parent edges alone, verified against real rows
   from `yin.vm/ast->semantic-bytecode`. Findings:
   `collab/1789552400000-storage-indexing-free-variable-query-demo.claude-sonnet-5.findings.md`.
2. `occurrence-aware-rules-resolve-mixed-free-and-bound-rows` — a second
   rule set (`p-up`/`occ-anc`/`occ-bound?`) proving the row-only rule
   above is UNSOUND when a name is free at one occurrence and bound at
   another (content-addressed row sharing collapses both into one row),
   and that walking a hand-built occurrence relation (§2.5's
   `[origin root-address path]` shape) by path-prefix instead of by row
   edge fixes it. This test deliberately RUNS the first rule set against
   the mixed-occurrence fixture too, asserting it gets the wrong answer
   (`#{}` instead of `#{'x}`), as the contrast proof. Findings:
   `collab/1789554609000-storage-indexing-occurrence-aware-free-variable-query.glm-5.3.findings.md`.

Both findings files report specific `dao.space.query/q` engine mechanics
discovered empirically (quoted rule data required; predicates can't bind
their own args; constants unusable as rule-head outputs; `not` requires
pre-bound vars; a bare non-`?` symbol in a pattern is a silent wildcard
when unbound). Verify these claims against `src/cljc/dao/space/query.cljc`
yourself rather than trusting the write-up.

## Task

1. **Run both tests yourself** (`clojure -M:test -n dao.space.query-test`)
   and confirm 51 tests / 155 assertions / 0 failures (baseline before
   both additions was 49 tests / 143 assertions — confirm this arithmetic
   too, don't just trust the reported deltas).
2. **Verify the contrast assertion is real, not staged.** The second
   test's core claim is that the SAME database, queried with the OLD
   (row-only) rule set, gives the WRONG answer. Confirm this by reading
   the actual test code: does it really invoke the row-only rule against
   the mixed-occurrence fixture, or does it just assert something that
   looks like a contrast without actually exercising the old rule?
3. **Verify the occurrence fixture is honest.** It's hand-built (no real
   indexer emits `[origin root-address path]` facts anywhere in this
   codebase — confirmed by a prior `grep`). Confirm the fixture's paths
   are self-consistent (walking up from each occurrence's path by
   whatever "parent" operation the rules use actually reaches the
   correct ancestor structure) and that the row ids embedded in the
   fixture are the REAL ids from `ast->semantic-bytecode`'s projection,
   not fabricated — i.e., the test isn't quietly assuming its own
   conclusion.
4. **Probe for a case these demos miss.** Try to construct an adversarial
   input (nested occurrence sharing at more than one level, a name bound
   by more than one enclosing lambda simultaneously reachable via
   different paths, or anything else) that would break either rule set.
   You don't need to add a test for it — report it as a finding if you
   find one.
5. **The claimed engine quirks** (bare-symbol-as-wildcard being the
   sharpest one reported) — verify at least this one against
   `query.cljc`'s source directly (`resolve-binding`/`wildcard?` per the
   findings' citations) rather than trusting the write-up.

## Boundaries

Read-only for source files. You may run test commands. Do not edit
anything.

## Deliverable

Report: pass/fail per the five checks, exact citations for anything
flagged, explicit verdict — safe to commit as-is. Produce the complete
deliverable now.
