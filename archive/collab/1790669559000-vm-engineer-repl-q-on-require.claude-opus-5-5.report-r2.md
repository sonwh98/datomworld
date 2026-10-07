Completed-GMT: 2026-09-29 09:05:31 GMT
Completed-Local: 2026-09-29 16:05:31 +07 (+0700)
Coding-Agent: claude
Session-ID: c2557c35-ba40-4377-b51a-aad2152a95cb (resumed)

# Report r2: q on require, fix round 1 (gate P2s)

Both P2s from the gate are fixed, each covered by a new test on all four VMs and proved by a temporary mutation. Lint, the focused JVM set and the CLJS lane pass. `cljstyle check` was blocked by the permission gate, so formatting is still unchecked.

Worktree `/Users/sto/workspace/datomworld-q-require`. Nothing is staged or committed. Files touched are the same allowed set:
- `src/cljc/yin/repl.cljc`
- `src/cljc/yin/repl/query.cljc`
- `test/yin/repl/query_test.cljc`

## P2 1: rollback left the base VM's response cursor behind evicted answers

**Fix.** Added `yin.repl.query/discard-answers`. It moves a rolled-back VM's call-out cursor (`[:resources vm/call-out-cursor-key]`) to the call-out's newest position, using the public `vm/cursor-entry`.
- This is safe because a rolled-back base has no call outstanding. Every response since its round began answers a call of the abandoned run.
- Call ids stay unique because each rollback already carries the abandoned run's id counter forward (`carry-link-identity`).

It is applied at all three rollback sites:
- `run-evaluation`'s catch
- `recheck-pending*`'s catch
- `abandon-pending`, which also restores a round base and was not in the gate's list

The `query-pair-capacity` docstring is updated: a reader now never falls more than one call behind.

**Test.** `a-failed-round-of-more-calls-than-the-pair-holds-leaves-no-gap`, on all four VMs:
- A failed round makes `query-pair-capacity + 6` (70) `q` calls and then raises.
- The next `q` returns `42`.

## P2 2: a program calling q without end never left the drive

**New constants in `yin.repl`:**
- **`query-drive-budget` = 1024.** This is the most `q` calls one drive answers, where a drive is one evaluation or one pending-run re-check.
  - Each call re-reads the published index, so 1024 calls is already a long round.
  - The budget also caps each serve (`min query-serve-budget (budget − calls)`), so a drive can never answer more than 1024.
- **`query-call-limit-text`**: the text of the stopped outcome.

**Outcome when the budget is exhausted (bounded and visible):**
- The drive stops serving. The VM, still waiting on its 1025th call, raises this as its own error:
  - It goes through `link-raise`, so the id counter and the query pair are carried.
  - The interpreter's cursor is first moved past the unanswered request (`query/abandon-requests`), so that request is never answered.
- The user sees: `Error: the evaluation called dao.space.query/q more than 1024 times and was stopped (:yin.repl.query/call-limit)`.
- The ex-data is `{:reason :yin.repl.query/call-limit :limit 1024}`.
- The round then ends like any failed round:
  - The VM rolls back to the round's base, with answers discarded as in P2 1.
  - Output already printed is drained.
  - The session stays usable.
- In a pending-run re-check, the same raise takes `recheck-pending*`'s error path: the run is dropped, the error is printed, and retained lines are reported as dropped.

**Test.** `a-program-calling-q-without-end-is-stopped-at-the-drive-budget`, on all four VMs:
- `(spin 2049)` returns exactly that error text.
- The next `q` returns `42`.
- Call ids on the call-in are all distinct.

## Mutation proofs

Each mutation was applied, the test ns run, then the file restored; `cmp` against a pristine copy confirmed each restore. A grep for the mutation text found nothing left.

| Mutation | Failures |
|---|---|
| `discard-answers` made a no-op | 8: 4 in the >64 test, and 4 in the budget test, whose stopped round also makes more than 64 calls |
| drive budget removed (check disabled and serve cap removed) | 4: budget test, every VM (the loop ran to `:done`) |

## Verification

| Check | Result |
|---|---|
| `clj -M:kondo --lint` (3 files) | 0 errors, 0 warnings |
| `cljstyle check` | **BLOCKED** by the session permission gate. Not run. |
| Focused JVM: `yin.repl.query-test`, `yin.repl-test`, `yin.repl.index-test`, `yin.vm.ffi-test` | 77 tests, 588 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2261 tests, 50744 assertions, 0 failures, 0 errors (includes "Testing yin.repl.query-test") |
| `bb test:cljd` | Not run, as instructed |

`yin.repl.main-test` is not in the focused set and did not flake in the CLJS run. The full `clj -M:test` was not re-run this round.

## Notes

- The call-in and call-out media are 64-element rings. After a stopped run, the test's call-id uniqueness check only sees the last 64 requests; uniqueness overall comes from the carried id counter.
- The owner still needs to approve the r1 limits (1000 rows, 256 KiB) and now also `query-drive-budget` 1024.
