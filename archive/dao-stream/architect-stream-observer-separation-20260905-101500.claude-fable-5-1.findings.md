Completed-GMT: 2026-09-05 10:34:32 GMT
Completed-Local: 2026-09-05 17:34:32 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

# Architect review: stream-observer separation

## Verdict

Changes required. The ownership boundary and composition shape are sound. Two clauses are not decision-complete and could produce a throwing implementation; one direct-evaluation behavior change is unrecorded.

## Findings

| Severity | Location / clause | Invariant and evidence | Recommended correction |
|---|---|---|---|
| High | “Make walker `step` advance execution only, retaining idle no-op behavior” | The idle no-op currently belongs to `stream_observer/step-on-stream`, not the walker. `vm-step` calls `cesk-transition` directly; an idle VM has nil control and continuation and reaches the unknown AST-node branch, throwing. | Require the walker’s `step` implementation to guard the idle case and return the VM unchanged. Add an idle-step contract test. This guard is a VM responsibility. |
| Medium | “Successful reads advance the cursor” / loader delivery | Current observation advances the cursor and invokes the loader in one expression. If `vm/datoms->ast` throws, the updated VM is discarded and the caller retains the old cursor; the malformed batch is re-read. Splitting observer and loader leaves commit timing ambiguous. | Specify that the advanced observer is committed only when the composition helper returns successfully. Preserve the existing poison-batch behavior and document it as known. |
| Medium | “Make walker run … not poll the program stream” while preserving `eval` | Current `vm/eval` runs through the stream-aware runner and may drain queued ingress after the direct AST halts. Removing polling changes direct evaluation behavior, including behavior after a malformed queued batch. | Record this as an intentional divergence and add a REPL test proving source evaluation after a failed datom-literal batch still evaluates. |
| Low | “Datom-literal evaluation appends through the observer’s program handle” | The observer is described as read-only, but this wording makes the host reach into observer state for a writer. DaoStream handles are host-local and surfaces are explicit. | Keep the program writer handle as a separate shell/composition field; construct the observer from that handle and append through the separate writer field. |
| Low | `ingest-next-program` after separation | It no longer ingests into a VM; it observes a stream value. | Rename to `observe-next` or `next-program` if the extra clarity is worth the small diff. |

The proposed move of readiness logic into `engine` has clean dependency direction: `engine` should drop its observer require, while the observer should continue requiring only DaoStream. Existing dead code `ast-walker-run-scheduler` may be reused; unrelated dead code remains out of scope.

## Properties that passed review

- Observation and execution are separate responsibilities, with the observer map holding the current ingress fields and composition supplying VM operations.
- The plan introduces no `accept-datoms`, `ready-for-program?`, VM protocol, or registry; it reuses existing mechanisms.
- FFI bridge ordering, readiness during suspension, gap accounting, immutable state, independent cursors, host isolation, and CLJ/CLJS/CLJD portability are preserved by the proposed shape.
- The bounded observer interface and v1 compatibility scope are appropriate.

## Minimum implementation-ready changes

1. Add the idle-step guard and test.
2. Specify observer cursor commit semantics when loading throws; preserve the old state on loader failure.
3. Document and test the deliberate direct-eval behavior change.
4. Prefer a separate shell writer handle for appends; optionally rename the now-misleading observation function.
