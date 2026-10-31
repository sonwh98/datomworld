Created-GMT: 2026-09-07 11:06:57 GMT
Created-Local: 2026-09-07 18:06:57 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: review P0 — the shared observation core and its two refactored callers
Role: Adversarial Code Reviewer and Security Auditor
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-07 18:06:57 +07 | Status: active | Rationale: resumed session; non-Claude family, independent of this author, and it has reviewed every revision of the plan this phase implements

**Code review of an uncommitted working-tree change**, written by the
orchestrator (a Claude model), so you are the independent seat. Nothing is
staged or committed.

Read the real diff and the two new files:

```
git diff src/cljc/dao/stream/forward.cljc src/cljc/yin/vm/stream_observer.cljc
```
- `src/cljc/dao/stream/observe.cljc` (new, 107 lines)
- `test/dao/stream/observe_test.cljc` (new, 150 lines, 9 tests)

## What this is

P0 of `docs/design/dao.jing.implementation-plan.md` (committed as `5898bfb`),
whose Decision 0 you helped shape. `yin.vm.stream-observer` and
`dao.stream.forward` were two implementations of one skeleton — read a
value, act on it, advance the cursor only if the act succeeded. P0 extracts
that skeleton to `dao.stream.observe/step` and refactors **both** onto it.
No DaoJing code is touched.

`step` takes `source`, `cursor`, `effect` and returns data for every outcome:
`:advance` (cursor = successor, effect's answer under `:effect`), `:retry`
(read `blocked` or effect `full`), `:ended`, `:gap` (with `:recovery`),
`:defect` (source-side), `:failed` (effect-side). `:cursor` and `:read` are
present in every case. It decides no policy and owns no loop.

`forward-step` keeps its namespace, signature, options and helpers, and
becomes its existing loop over `step`; `malformed-result` moved into the core.
`observe-next` is `step` with a null effect; `run-on-stream` passes the loader
as the effect, with the loaded VM carried in the effect's outcome map.

## Verification already done — do not rerun; spend your budget on analysis

- **clj** 1435 tests / 167265 assertions, **cljs (Node)** 1354 / 34883,
  **cljd (Dart)** 1299 tests, all 0 failures.
- Baselines were 1426 / 167181, 1345 / 34799, 1290. The delta is **+9 tests
  and +84 assertions on every host** — exactly the new suite — so no existing
  test changed its assertion count. All 9 core tests confirmed by name in the
  Dart output.
- `clj -M:kondo` clean on all four files.

## What to judge

1. **Is `step` correct and total?** Every branch, the classification of
   malformed answers on both sides, and whether `:cursor`/`:read`/`:effect`/
   `:recovery` are present exactly where the docstring claims.
2. **Is `forward` genuinely unchanged in behaviour?** Walk its subtleties
   against the new dispatch: the budget loop, the separately-bounded resume
   allowance, the fixed-point rule (`(= recovery cursor)` → `:source-gap`),
   terminal-state re-stepping as a no-op, the budget-zero early return, and
   that `blocked` and `full` still produce the identical `:retry` result.
   Its one production consumer is `dao.stream.serving` (131, 255, 312).
3. **Is the VM genuinely unchanged in behaviour?** In particular the
   `answered-outcome` helper: the core classifies an out-of-contract answer as
   `transport-error` and retains the raw under `:dao.stream/answer`, and the
   VM reports the raw outcome so its scripted `:dao.stream/wholly-unexpected`
   still reaches the ex-data. Is that recovery correct for every shape of
   malformed answer, including a non-map one? And is `run-on-stream`'s
   loaded-VM-in-the-outcome-map legitimate, or does it abuse the contract?
4. **A behaviour change I believe is benign, and want challenged.** The old
   `observe-next` never validated the read: a malformed `ok` — outcome `ok`
   with no `:dao.stream/cursor` — would have produced `{:status :ok :batch nil}`
   and installed a nil cursor. Under the core it is a `:defect` and throws. No
   test scripts that shape, so nothing failed. Is the new behaviour right, and
   is the change worth recording?
5. **New defects in the 250-line diff and the two new files**, including the
   test suite: does it actually discriminate, or does it assert the
   implementation back to itself? The declaration-driven tables compare
   against `stream/outcomes-next` and `outcomes-append` so the suite fails if
   the contract grows — is that the right construction?
6. **Commit readiness**, and the subject
   `refactor(stream): extract the observation step forward and the VM share`.

Report `P0-P3 | file:line | evidence | concrete fix`, or "no actionable
findings". Say plainly whether this is ready to commit.

Do not edit any file. You have no authority to run tests. Produce the
complete response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
