Created-GMT: 2026-09-16 04:36:15 GMT
Created-Local: 2026-09-16 11:36:15 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0a6df-fb45-7122-aab4-06049faa93ba
# Task: r2 review of the :global retirement docs — confirm all r1 findings fixed
Role: Routine Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 11:36:15 +07 | Status: active | Rationale: same reviewer, resumed session, confirming fixes to own r1 findings

## What changed since r1

All four of your r1 findings addressed, verified against source before
editing (not just patched blind):

1. **§7.7's stale row-only query** — both the `$ast` and `$code`
   illustrative queries rewritten. The `$ast` one now joins `$occ` and
   takes `?root`, matching §4.5's actual conclusion (occurrence-joined,
   not row-only). The `$code` one gets an honest note that it's
   unverified (no `lower` implementation exists yet to test against) and
   that instruction-level sharing may not have the same mixed-occurrence
   risk as AST rows, but this is unconfirmed reasoning, not asserted fact.
2. **The "throwing" mischaracterization** — verified against the
   pre-tonight committed text (`git show e1610be:...`, which said "An
   unbound index evaluates to nil", never "throws"). Fixed to
   "evaluating an unbound name to nil rather than falling through."
3. **§4.2 heading and stale block language in §4.4/§6.1/§7.4** — all
   four fixed. The heading now says "ordinary identity use is
   unblocked"; §4.4's sharing-gate paragraph, §6.1's dedup paragraph, and
   §7.4's encoder-domain-rule paragraph all now state the block is
   lifted for ordinary values, residuals remain.
4. **§10 item 1's "seq/list" mislabel** — verified against the actual
   §4.2 table (the second conformance pair compares a vector against a
   seq, not a list against a seq — list/seq are intentionally EQUAL, not
   a distinctness pair). Fixed to "vector/seq", with a note that list/seq
   stay one address intentionally.
5. **§4.5's "lift as-is" overclaim on the occurrence rule** — verified
   the actual rule in `test/dao/space/query_test.cljc` (`occ-anc`/
   `occ-bound?` take only `?path`/`?name`, no root parameter). Added a
   new paragraph stating this precisely: the rule set is not root-scoped
   as written, two different trees can produce the identical literal
   path, and production use needs `?root` threaded through every rule
   head plus a root-constrained `$occ` join — not a hedge, a specific
   required change.
6. **The row-only `edge` rule's tag-coverage gap** (flagged by the
   OTHER reviewer of the test file, not you — read
   `collab/1789557950000-review-free-variable-query-demos.deepseek-flash.stdout.log`
   finding A if you want the detail) — verified `edge`'s clauses really
   are hardcoded for just `:lambda`/`:application` (test docstring
   confirms). Added a caveat to §4.5's first demonstration paragraph.
7. **The residual "future-producer-only" overclaim** — split correctly:
   scalar metadata and byte-array hashing need a future producer; ambient
   print-var bindings on scalars are live today, not conditional.
8. **`datom.world.md`'s "zero storage or migration cost" overclaim** —
   removed; now states deriving still costs query complexity and
   indexing work, paid once rather than in every future migration, and
   the case study note now correctly says the free/bound distinction
   "turned out to be computable by query" (not specifically a
   "row-reachability query", since the final answer needed the
   occurrence-level query, not the row-level one) and mentions the
   two-round correctness process.

## Task

Re-review both files in full — not just the specific citations above,
sweep the whole document again as you did in r1, since your r1 catch of
things outside the original diff hunks is exactly the failure mode worth
re-checking for. Confirm each of the 8 fixes above lands correctly and
introduces no new inconsistency. Pay particular attention to whether the
new §4.5 paragraph about root-scoping reads clearly and doesn't
contradict the "safe to commit as-is" verdict the OTHER reviewer gave the
test file itself (that reviewer judged the test's own scope, single-root,
sound — the doc now needs to accurately describe that scope without
overclaiming production-readiness, which is a documentation-precision
question, not a test-correctness one).

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report: pass/fail per each of the 8 items, exact citations for anything
still wrong, explicit verdict — safe to commit as-is. Produce the
complete deliverable now.
