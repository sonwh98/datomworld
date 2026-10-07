Created-GMT: 2026-09-16 13:47:14 GMT
Created-Local: 2026-09-16 20:47:14 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: a1d44dfc-7060-456f-91be-df7c9f296100

# Task: U1 — delete dao.await v1

Role: Scoped / Subagent

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-09-16 20:47:14 +07 | Status: active | Rationale: mechanical two-file deletion, no real consumers, already confirmed by an independent grep sweep

## Context

`docs/design/yin.vm.v1-retirement.implementation-plan.md`'s U1 (read the
"### U1 — `dao.await` v1 deletion" section in full first). `dao.await` v1
(`src/cljc/dao/await.cljc`) has a v2 sibling
(`src/cljc/dao/await.cljc`) that is the real, live implementation.
Confirmed tonight by the orchestrator, independently, via
`grep -rn "dao\.await" --include='*.cljc' --include='*.cljs'
--include='*.cljd' --include='*.clj' src test bin | grep -v
"dao\.await\.v2" | grep -v "^src/cljc/dao/await.cljc" | grep -v
"^test/dao/await_test.cljc"` — the only two hits are docstring prose
inside `dao/await.cljc` describing the v1→v2 migration, not actual
`:require` usage. Zero real consumers.

## Task

1. Re-verify this yourself first — re-run the grep above (or your own
   equivalent) and confirm zero real consumers before deleting anything.
2. Delete `src/cljc/dao/await.cljc` and `test/dao/await_test.cljc`.
   Nothing else changes.
3. Do not touch `src/cljc/dao/await.cljc` or any other file.

## Verify

Per the plan's own stated criteria for this unit:
- The same grep sweep, re-run post-deletion, returns only `dao.await`
  hits and `docs/`.
- `clj -M:test` — full suite, must pass (report the count).
- The shadow `:test` build (`bb test:cljs` or the equivalent shadow-cljs
  invocation this repo uses) — must pass.
- `clojure -M:cljd test` (or `bb test:cljd`) — must pass, and confirm
  `Testing dao.await-test` appears in the output (proving the v2 test
  namespace is what's actually exercised, not a stale reference to the
  deleted v1 test).
- `clj -M:kondo --lint` on anything you touch (there's nothing left to
  lint once the files are deleted, but confirm no other file references
  the deleted namespaces after the fact — this should already be covered
  by the grep sweep).

Report the exact commands and full pass/fail counts for all three hosts,
not just confirmation they ran. Do not stage or commit.

## Deliverable

Report back: confirmation both files are deleted and nothing else changed,
the re-run grep sweep's output, and the exact verification commands with
their full output for all three hosts.
