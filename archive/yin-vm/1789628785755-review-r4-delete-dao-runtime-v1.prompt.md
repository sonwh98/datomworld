Created-GMT: 2026-09-17 07:06:25 GMT
Created-Local: 2026-09-17 14:06:25 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: Review R4 — delete v1 dao.runtime, its host drivers, and their tests

Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-17 14:06:25 +07 | Status: active | Rationale: cross-family review of a GLM-family implementer

## Context

`docs/design/dao.runtime.implementation-plan.md`'s "## Phase R4 —
Deletion and the naming decision" and the "Consumer census" section above
it — read both in full first. R4's gate ("v1 `yin.vm.engine` no longer
requiring `dao.runtime`") opened tonight when the v1 VM was deleted by
`yin.vm.v1-retirement.implementation-plan.md`'s U6, which also already
deleted `src/cljc/yin/vm/runtime_adapter.cljc` and its tests as a side
effect (recorded in R4's own status note). This unit is the rest of R4:
the legacy `dao.runtime` namespace, its three host drivers, and their
tests.

Two independent, concurrent pieces of work landed in the same working
tree tonight, touching disjoint files: this deletion (8 files removed, 1
edited), and a separate prose-only edit recording R4's naming decision
(`docs/design/dao.runtime.implementation-plan.md` itself — NOT touched
by this deletion's implementer, confirmed). Review only the deletion; the
naming-decision prose is a separate, already-settled documentation change
you don't need to re-review, though you may glance at it for context on
why the rename itself isn't happening in this unit.

Current uncommitted diff (unstaged, for your reading): `git status --short`
shows 8 deletions (`src/cljc/dao/runtime.cljc`,
`src/{clj,cljs,cljd}/dao/runtime/driver.*`, `test/dao/runtime_test.cljc`,
`test/dao/runtime/driver_{test.clj,cljs_test.cljs,cljd_test.cljc}`) and one
edit (`test/dao/test_utils.cljc`, removing `make-non-waitable-stream`).

Verified independently by the orchestrator, not trusted from the
implementer's report: `clj -M:kondo --lint test/dao/test_utils.cljc` —
clean. `clj -M:test` (full suite) → 1384 tests, 0 failures. `bb test:cljs`
(full suite) → 1283 tests, 0 failures, `Testing dao.runtime-test` and
`Testing dao.runtime.driver-test` both confirmed present. `bb test:cljd`
(full suite, `test/cljd-out` cleared first) → 1241 tests, all pass;
regenerated `test/cljd-out/dao/runtime/` confirmed to contain only `v2/`
artifacts, no v1 twins. A grep sweep for `dao\.runtime(\.driver)?` across
`src`/`test` returns only two documented non-hits: prose in the still-live
v1 `dao.stream.cljc` docstring (out of scope, retires with the separate,
not-yet-started `dao.stream` v1 plan).

## Task

1. Confirm the delete list matches R4's own text exactly (cross-check
   against the "Consumer census" table and R4's own bullet list — note
   the census table says the v1 VM's `runtime_adapter.cljc` migrates
   "with the v1 VM", already done; this unit's job is only the remaining
   four production files plus their four test files).
2. Verify `make-non-waitable-stream`'s removal is actually safe:
   independently grep for it yourself, confirm zero remaining callers.
   Confirm the `NonWaitableStream` defrecord itself, left in place, is a
   deliberate, reasonable scope boundary (the implementer's own report
   frames it as "take it with v1 `dao.stream`'s retirement" — judge
   whether that's the right call or whether it should have been removed
   here too, since its own constructor is now gone and nothing
   References the record directly by name outside `make-non-waitable-stream`
   itself — check this precisely).
3. Confirm nothing in `dao.runtime` (the live implementation) was
   touched, and that its own driver tests
   (`test/dao/runtime/driver_test.*`) were correctly left alone,
   using their own fixtures rather than the removed helper.
4. Independently re-run or spot-check the verification claims above if
   useful, or trust them and focus your budget on static review — your
   choice, state which you did.
5. Confirm this diff is genuinely disjoint from the concurrent
   naming-decision edit to the same design doc, and that nothing was
   double-edited or conflicting.

Do not edit any file.

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
Architect sign-off, or not.
