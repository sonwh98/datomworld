Created-GMT: 2026-09-13 13:32:31 GMT
Created-Local: 2026-09-13 20:32:31 +07
Coding-Agent: glm
Session-ID: 2edcf7f4-734e-4bd6-a68a-6ead1445b055

# Task: stream-observer-prereqs-r2

Role: DaoStream and Distributed Protocol Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 20:32:31 +07 | Status: active | Rationale: Resume prior session to address review findings

Resume from the prior session. The independent reviewer (gpt-6-astra) found one P1 and one P2 in the `run-on-stream` implementation. Fix them in the same two files.

Read the full review findings:
  collab/1789306160030-review-stream-observer-prereqs.gpt-6-astra.findings.md

Then read the current source:
  src/cljc/dao/stream/observer.cljc

Fix required:

**P1 — Partial flush progress is lost.**
`carry-session` at line 255 (`:advance` branch) unconditionally carries `loaded` as the consumer state. But the spec (`yin.vm.macro.md` §5) requires that if `run` threw after making partial progress (e.g. flushing `out` succeeded, `log` failed), the carried `:consumer` must reflect that partial state — whatever the failing `run` embedded in its own ex-data — not the pre-run `loaded` value.

The fix: in both `run` catches (`:advance` branch at line 255 and the not-ready branch at line 232), check whether the caught exception's `ex-data` contains a `:consumer` key. If it does, use that as the carried consumer (the failing `run` is reporting its partial state); if not, fall back to the consumer value the catch has in scope (`loaded` in `:advance`, `consumer` in the not-ready branch). The observer-owned cursor position is unchanged (after the batch in `:advance`, current in not-ready).

**P2 — Run-failure test misses the partial-delivery case.**
The existing `a-run-failure-carries-the-session-after-the-loaded-batch-test` throws before B delivers anything. Add a test where B's `run` delivers output then throws (embedding a partial consumer in its `ex-data`). Assert:
- The carried consumer reflects the partial state (output delivered, log not yet flushed), not the pre-run state.
- Retry from the carried session delivers only the remaining log, not a duplicate of the output.

**P3 — Document and test exception wrapping.**
In `carry-session`'s docstring, note that the rethrow is a new `ExceptionInfo` whose cause is the original. Add an assertion to an existing or new test that `(ex-cause caught)` is the original exception (message and type preserved as cause).

After fixing, run:
  clj -M:test -n dao.stream.observer-test -n yin.repl.core-test
  clj -M:kondo --lint src/cljc/dao/stream/observer.cljc test/dao/stream/observer_test.cljc
  git diff

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test outcomes, and any remaining concerns.
