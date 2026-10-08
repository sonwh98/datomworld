Created-GMT: 2026-10-06 17:27:30 GMT
Created-Local: 2026-10-07 00:27:30 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: e6311dee-c01d-4139-82ed-55fb54017733
# Task: stream-crossmachine-s0-s1-reconcile
Role: Stream & Network Implementation Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 1791307641128 | Status: active | Rationale: Reconcile review findings A1 and B1 from independent GLM review.

## Instructions
You are the Stream & Network Implementation Engineer for Datomworld.
Work exclusively inside the dedicated feature worktree:
`/Users/sto/workspace/datomworld-stream-s1` (branch `stream-crossmachine-s0-s1`).
DO NOT TOUCH any other worktree.

### Objective: Reconcile Review Findings from `collab/1791306950355-stream-s0-s1-review.glm-5.3.findings.md`

1. **A1 (`ws_project.cljc:105-107`)**:
   - `step!` 2-arity currently silently degrades invalid non-nil budgets (e.g. `<= 0` or non-integer) into unbounded reading (`remaining -1`).
   - Fix: Validate `budget`. When `(some? budget)`, require `(and (integer? budget) (pos? budget))`. If invalid, throw `(ex-info "invalid DaoStream ws projection step! budget" {:budget budget})` to match the composition-error pattern in `make-acceptor`.
   - In `test/dao/stream/ws_project_test.cljc`, add test assertions confirming that invalid budgets (0, -1, 1.5) throw `ex-info`.

2. **B1 (`docs/design/dao.stream.remote.md:395-398` & `ws_project.cljc`)**:
   - In `docs/design/dao.stream.remote.md` Section 3.0, clarify the idle timeout config:
     - Document `:idle-timeout` (and its alias `:idle-timeout-ms`, where `:idle-timeout-ms` takes precedence if both are specified) as positive integer milliseconds.
   - Ensure the docstring of `make-acceptor` in `ws_project.cljc` clearly notes this precedence.

3. **Verification**:
   - Run focused tests: `clojure -M:test -n dao.stream.ws-project-test`.
   - Ensure all tests pass.

Produce your response directly with the changes made and test outputs.
