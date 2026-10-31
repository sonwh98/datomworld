Created-GMT: 2026-09-07 07:59:01 GMT
Created-Local: 2026-09-07 14:59:01 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: review the allocator-error fix in dao.stream.rpc
Role: Adversarial Code Reviewer and Security Auditor
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-07 14:59:01 +07 | Status: active | Rationale: resumed session; it found this defect and specified this fix, so it is both the best-informed reviewer and the one whose own prescription needs checking

This is a **code review of an uncommitted working-tree change**, not a plan
review. Read the real diff:

```
git diff src/cljc/dao/stream/rpc.cljc test/dao/stream/rpc_test.cljc
```

Two files, +69 -1. Nothing is staged or committed.

## What changed and why

You filed this defect during the `dao.jing.v2` plan review:
`allocation-failure` (rpc.cljc) sets `:terminal` without calling
`lose-outstanding`, so every request already in `:outstanding` is stranded —
no completion ever published, `poll!` short-circuits on terminal, `rebind`
refuses a non-`/detached` terminal. It is the only one of six terminal paths
that skips the conservative-loss discharge. The user asked for it fixed
directly rather than deferred.

The fix substitutes `(lose-outstanding state :dao.stream.rpc/allocator-error
true)` for the bare `(assoc :terminal …)`. `lose-outstanding` with
`terminal? true` sets `:terminal` to the same reason, so the assoc is
subsumed. A `(declare lose-outstanding)` was needed because it is defined
below `allocation-failure`; `declare` is already used in this namespace family
(`ringbuffer.cljc:36`, `transit.cljc:43`, `ws.cljc:108`).

Three tests were added to a file that had **zero** coverage of this path:
collision-with-bystander, exhaustion-with-bystander, and
nothing-outstanding-publishes-no-completion.

## Verification already done — do not rerun; spend your budget on analysis

You have no authority to run tests. These were run by the orchestrator:

- **clj** — full suite 1426 tests / 167179 assertions, 0 failures (baseline
  1423 / 167165, so +3 tests / +14 assertions and nothing else moved).
  `clj -M:test -n dao.stream.rpc-test` → 11 tests / 55 assertions, matching
  the file's 11 `deftest` forms.
- **cljs (Node)** — 1345 / 34797, 0 failures; `Testing dao.stream.rpc-test`
  confirmed present in the full log.
- **cljd (Dart)** — `All tests passed!`, 1290 tests; all three new tests
  confirmed by name.
- **Red/green** — with the source fix stashed and the tests left in place, the
  two bystander tests fail with `:outstanding` still holding
  `{0 {:op :math/add, :args [20 22]}}` and `completions` empty; restoring the
  fix returns 0 failures, source verified byte-identical.
- `clj -M:kondo` — 0 errors, 0 warnings on both files.

## What to judge

1. **Is the fix correct and complete?** Does substituting `lose-outstanding`
   preserve every property the old line had — terminal value, diagnostic,
   `:next-id`, the `rpc-result` shape and its `:dao.stream.rpc/diagnostic`
   key — and does it now discharge exactly what was owed and nothing more?
2. **Does it regress the five other `lose-outstanding` call sites**
   (rpc.cljc:371, 377, 403, 410, 415) or anything downstream — `ids-in-use?`
   now seeing those ids in `:completed`, `rebind`, `abandon-unsent`,
   `take-completed` bounding, `yin.repl_adapter.cljc:117`?
3. **Your own prescription.** You specified this fix. Check it as something to
   attack rather than confirm: is there a case where losing outstanding
   requests at allocation failure is *wrong* — a caller that depended on the
   old silence, an ordering hazard between the loss and the diagnostic, or a
   state where `:unsent` and `:outstanding` interact badly?
4. **Are the three tests adequate and honest?** Do they assert the right
   invariants, do they discriminate, and is anything untested that should be —
   including the interaction with an `:unsent` envelope present at allocation
   failure.
5. **`declare` vs. moving the function** — acceptable here, or should
   `lose-outstanding` be relocated above its first use?
6. **Commit readiness**, and the subject line
   `fix(stream): report outstanding requests lost when allocation fails`.

Report `P0-P3 | file:line | evidence | concrete fix`, or "no actionable
findings". Say plainly whether this is ready to commit.

Do not edit any file. Produce the complete response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
