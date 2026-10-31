# S2a reconcile report (claude-opus-5-5)

Worktree `/Users/sto/workspace/datomworld-stream-s2`, branch `stream-crossmachine-s2`, uncommitted.

## R1: dial mirror effects inside an atom retry (fixed)

- `src/cljc/dao/stream/ws_project.cljc`, `dial-step!`: `remote/mirror-step` no longer runs inside `swap!`. It now runs once over `@mirror-cursor`, and `reset!` stores the cursor it returns. This matches `accept-step!`'s single-driver discipline, and a comment says so.
- Regression test: `ws_project_test.cljc` `dial-mirror-effects-run-once-per-step`.
  - The served table entry is a writer that, during its first `append!`, replaces the dial's mirror cursor with an equal but distinct map. On the JVM that forces a CAS retry if the mirror runs inside `swap!`.
  - It asserts that the source append ran exactly once and was answered once.
  - Checked against the old `swap!` form: 2 failures (`[:v :v]` applied, `[1 1]` answered). With the fix it passes.
  - The interference is one-shot. Interfering on every append livelocked the old form, and the run died with no report.
  - On CLJS and CLJD `swap!` never retries, so there the test only confirms the normal path.

## Coverage: writer `full` on `cursor` and `next` (added)

- `remote_test.cljc` `a-full-writer-rewinds-idempotent-answers-and-drops-append-answers` gains a `cursor and next answers refused full rewind too` block. It runs once with a `cursor` request and once with a `next` request.
- Each case puts a descriptor request ahead of the cursor/next request. The writer carries the first answer and refuses the second once with `full`.
- It asserts that the step returns the cursor preceding the refused request, not the input cursor, and that the next call answers the same id.

## Verification

- `clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test`: Ran 64 tests containing 487 assertions, 0 failures, 0 errors.
- `mise exec -- cljstyle check` on the 4 files: clean, no output.
- `clj -M:kondo --lint` on the 4 files: errors 0, 1 warning (`remote_test.cljc:661:13 unused binding peer2`). That warning was already there and is not in code this slice touched.
- Not run: `bb test:clj` and the full 3-host `bb test`.
