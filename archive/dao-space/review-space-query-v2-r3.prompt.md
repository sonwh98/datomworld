Created-GMT: 2026-09-08 12:20:45 GMT
Created-Local: 2026-09-08 19:20:45 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: confirm the two P1 corrections (r3)
Role: Routine Review

Both P1 findings from your review were accepted and fixed by `glm-5.3` in the
working tree. The owner also ruled on finding 1 directly — "there should be no
legacy" — so it was never in question.

**The workspace is read-only. Do not write any file. Print your confirmation
to stdout as your final message.**

## Finding 1 — fixed

`legacy-v1-realization?` is deleted. `current` and `history` route every
source through `db-source`; `index/snapshot-datoms` is unreachable from
`dao.space.query`. New test `unrecognized-host-objects-are-rejected` in
`query_test.cljc` pins I1 for an atom, a delay and a Date.

The implementer took your *first* alternative rather than the test-side drain:
the two schema tests now pass the closed realization **directly** to
`schema/current` (`schema_test.cljc:521-536` and `599-622`), so schema's
realization branch and `interpret-view`'s own drain stay exercised. Judge
whether `borrowed-path-does-not-close-again` still proves what it claims.

## Finding 2 — fixed

`relation` returns `{:dao.space.query/relation tuples}` and `view-value`
carries only `:dao.space.query/view`/`:source`/`:as-of` — no
`:dao.stream/type` on any query value. `schema/current` accepts
`(query/value? d)` or a legacy keyword-typed descriptor;
`validate-not-nested-view!` keys on `:dao.space.query/view` while keeping the
legacy `:dao.space.schema/current` check. The published coordinate keeps its
own `:dao.stream/type` (index's shape, untouched).

Your vacuous-assertion catch at `schema_test.cljc:538-541` was replaced, not
deleted: `bound-inherited-from-source` became
`schema-current-returns-a-fact-relation-value`, asserting `query/value?` and
`:fact?`.

## Verification (orchestrator's own, full, on the corrected tree)

- `bb test:clj` — 1424 tests, 165259 assertions, 0 failures, 0 errors
- `bb test:cljs` — 1335 tests, 34844 assertions, 0 failures, 1 error (the
  pre-existing `wasm-eval-emits-telemetry-test`)
- `bb test:cljd` — All tests passed, 1288 tests
- `compile demo` — 212 files, 0 warnings
- kondo — 0 errors on the touched files

Do not rerun them.

## What I need

Review `git diff` since your last pass. Confirm each P1 is genuinely closed,
or say what remains. Flag anything the corrections newly broke — particularly
whether removing `:dao.stream/type` from query values left any consumer
classifying them wrongly, and whether the two schema tests still pin real
properties. Then state plainly whether this is ready to commit.
