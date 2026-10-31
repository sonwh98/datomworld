Completed-GMT: 2026-09-05 10:39:35 GMT
Completed-Local: 2026-09-05 17:39:35 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

# Architect follow-up: revised stream-observer separation plan

## Verdict

Approve with nonblocking notes. The revised plan resolves all five prior findings and is decision-complete. Every reviewed clause has defined, terminating behavior consistent with the plan.

## Prior findings reconciled

1. Idle-step guard: resolved by placing the existing readiness guard in walker `step` and testing identity on an idle VM.
2. Cursor commit on loader failure: resolved by returning no updated session when loading throws, preserving the caller's previous observer cursor and malformed-batch retry behavior.
3. Direct-eval change: resolved by documenting that direct evaluation no longer drains queued program batches and testing source evaluation after a malformed observed batch.
4. Writer ownership: resolved by keeping `:program-stream` separately in REPL/composition state; observer operations expose no append or close.
5. Observation naming: resolved by renaming the read operation to `observe-next`.

Dependency direction is clean: `stream-observer` requires only DaoStream, `engine` drops observer forwarding and stream-driving code, and `engine/run-loop` remains.

## Nonblocking notes incorporated into the final plan

- Once observer state is separate, blocked/end observations no longer set VM `:halted?` or `:blocked?` flags. Preserve cursor and gap outcome behavior; remove test-helper state forgery that sets `:halted? false` solely to trigger observation.
- The idle-step identity guarantee applies when the existing readiness predicate is true. A halted VM with a non-empty ready queue is not idle under that predicate; its pre-existing behavior remains outside this refactor and should be named in the walker documentation rather than silently widening the guarantee.
