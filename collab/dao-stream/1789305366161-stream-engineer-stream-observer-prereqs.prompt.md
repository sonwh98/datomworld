Created-GMT: 2026-09-13 13:16:06 GMT
Created-Local: 2026-09-13 20:16:06 +07
Coding-Agent: glm
Session-ID: 2edcf7f4-734e-4bd6-a68a-6ead1445b055

# Task: stream-observer-prereqs

Role: DaoStream and Distributed Protocol Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 20:16:06 +07 | Status: active | Rationale: Primary storage, VM, and networking engineer; flat-rate off-peak subscription

Implement partial-session error carrying and kept-cursor attach in `src/cljc/dao/stream/observer.cljc` and `test/dao/stream/observer_test.cljc` in `/Users/sto/workspace/datomworld`.

Read first:
- `docs/design/yin.vm.macro.md` (§5 "Prerequisite on the observer", §7 Phase 0)
- `docs/design/dao.space.index.as-observer.md` (§4.2 "Resumption is bound")
- `src/cljc/dao/stream/observer.cljc`
- `test/dao/stream/observer_test.cljc`

Acceptance criteria:
1. **Kept-cursor `attach` arity**:
   Add 3-arity `(attach attach! descriptor {:keys [cursor ingress-gaps]})` to `dao.stream.observer/attach` that returns `{:stream handle :cursor cursor :ingress-gaps ingress-gaps}` without calling `stream/cursor` to mint at `:dao.stream/oldest`. The 2-arity `(attach attach! descriptor)` remains unchanged, minting at `:oldest` with `ingress-gaps 0`.
2. **Partial-session error carrying in `run-on-stream`**:
   When `load` or `run` throws (or a terminal read occurs after a processed batch), propagate the exception (keeping the throw), but carry `{:session {:observer o' :consumer c'}}` in `ex-data`:
   - On a `load` failure: the carried session's observer cursor remains *before* the failing batch (so the batch is re-read on retry).
   - On a `run` failure: the carried session's observer cursor is *after* the loaded batch (since the batch was observed and loaded), and `:consumer` holds the state returned or left by `run` (so a retry neither re-reads the batch nor duplicates already-delivered work).
   - If a terminal read outcome throws after batches were successfully processed in that round, ensure the carried exception includes the updated `{:session ...}` in `ex-data`.
3. **Tests in `test/dao/stream/observer_test.cljc`**:
   - Verify kept-cursor `attach` returns the specified cursor and ingress-gaps.
   - Verify `run-on-stream` carries `{:session ...}` in `ex-data` on `load` failure with the pre-batch cursor.
   - Verify `run-on-stream` carries `{:session ...}` in `ex-data` on `run` failure with the post-batch cursor and the consumer state.
   - Verify a retry from the carried session succeeds without repeating already-processed batches.
4. **Local Verification**:
   Work only in `src/cljc/dao/stream/observer.cljc` and `test/dao/stream/observer_test.cljc`.
   Run `clj -M:test -n dao.stream.observer-test -n yin.repl.core-test` and `clj -M:kondo --lint src/cljc/dao/stream/observer.cljc test/dao/stream/observer_test.cljc`.
   Inspect the final diff with `git diff`.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any incomplete work. Do not claim edits or tests that did not occur.
