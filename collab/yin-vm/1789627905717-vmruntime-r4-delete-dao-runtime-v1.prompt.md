Created-GMT: 2026-09-17 06:51:45 GMT
Created-Local: 2026-09-17 13:51:45 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 8b4f01b4-216b-4615-a27f-cc4a382681da

# Task: R4 — delete v1 dao.runtime, its host drivers, and their tests

Role: VM Runtime

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-17 13:51:45 +07 | Status: active | Rationale: matches VM Runtime/storage strengths, bounded deletion unit

## Context

`docs/design/dao.runtime.implementation-plan.md`'s "## Phase R4 —
Deletion and the naming decision" — read the whole section, and the
"Consumer census" section above it, before touching anything. R4's gate
("v1 `yin.vm.engine` no longer requiring `dao.runtime`") is confirmed
open — the v1 VM was deleted entirely by
`yin.vm.v1-retirement.implementation-plan.md`'s U6 tonight, which also
already deleted `src/cljc/yin/vm/runtime_adapter.cljc` and its tests (part
of R4's list, done early as a side effect of that unrelated plan — the
doc's own status note at the top of R4 records this). **This task is only
what's left of R4 after that:** the legacy `dao.runtime` namespace itself,
its three host drivers, and their tests. The naming-decision half of R4 is
being handled separately, concurrently, by someone else — do not touch
`dao.runtime.implementation-plan.md`'s naming-decision prose yourself;
someone else owns that file right now for a different section. If you need
to record anything in that doc, stop and report it in your deliverable
instead of editing it.

**No other unit is running concurrently against source/test files
tonight** — the CLJD lane is free for you.

## Task

Per R4's own text, exactly:

1. **Delete** `src/cljc/dao/runtime.cljc` (legacy v1 scheduler),
   `src/clj/dao/runtime/driver.clj`, `src/cljs/dao/runtime/driver.cljs`,
   `src/cljd/dao/runtime/driver.cljd` (the three v1 host drivers — do NOT
   touch their `dao/runtime/driver.*` siblings, those are the live v2
   implementation).
2. **Delete their tests**: `test/dao/runtime_test.cljc`,
   `test/dao/runtime/driver_test.clj`, `test/dao/runtime/driver_cljs_test.cljs`,
   `test/dao/runtime/driver_cljd_test.cljc` (again, do NOT touch the
   `test/dao/runtime/driver_test.*` siblings).
3. **Local hygiene**: delete the corresponding stale `test/cljd-out/`
   twins before running any CLJD verification — confirmed present tonight:
   `test/cljd-out/dao/runtime-test_test.dart`,
   `test/cljd-out/dao/runtime/driver-cljd-test_test.dart` (verify this
   list yourself, don't just trust it; there may be more). This project
   has a documented hazard where a stale compiled test for an
   already-deleted source can silently pass — see
   `docs/agents/build-n-test.md`.
4. **Remove `make-non-waitable-stream` from `test/dao/test_utils.cljc`.**
   Confirmed tonight: its only users are the three v1 driver test files
   you're deleting in step 2 — `test/dao/runtime/driver_test.*` (the
   live v2 driver tests) use their own fixtures, not this helper. Verify
   this yourself before removing it (grep for `make-non-waitable-stream`
   after your own deletions, confirm zero remaining references anywhere
   in the tree). If you find a real remaining user this brief missed,
   leave it and say so explicitly.
5. Do not touch `src/cljc/dao/runtime.cljc` or anything under
   `dao/runtime/`, `yin/vm/`, or any other file not named above.

## Verify

- `clj -M:kondo --lint test/dao/test_utils.cljc` (nothing else needs
  linting — everything else in scope is a deletion).
- `clj -M:test` (full suite) — must pass; report the exact count.
- `bb test:cljs` (full suite) — must pass; confirm `Testing
  dao.runtime-test` and `Testing dao.runtime.driver-test` still
  appear (per this doc's own "Host matrix" section, these must not be
  silently absent from discovery).
- `bb test:cljd` (full suite, clear `test/cljd-out` first) — must pass.
- A final grep sweep: confirm nothing in `src`/`test` still references
  `dao.runtime` (bare, not `.v2`) or `dao.runtime.driver` (bare) after
  your deletions — report the exact command and its (empty, or
  documented-non-hit-only) output.

Do not stage or commit. Do not touch any file outside what this task
names.

## Deliverable

Report back: the exact diff (deletions + the one test_utils.cljc edit),
the exact verification commands and output for all three hosts, the final
grep sweep's output, and explicit confirmation that
`dao.runtime.implementation-plan.md` was not touched by you.
