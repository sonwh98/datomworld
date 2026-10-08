Created-GMT: 2026-10-06 17:51:25 GMT
Created-Local: 2026-10-07 00:51:25 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: e6311dee-c01d-4139-82ed-55fb54017733
# Task: stream-crossmachine-s0-s1-wire-step-budget
Role: Stream & Network Implementation Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 1791309074412 | Status: active | Rationale: Implement Architect ruling item A2 (wire :step-budget into make-acceptor and accept-step! with flood test).

## Instructions
You are the Stream & Network Implementation Engineer for Datomworld.
Work exclusively inside the dedicated feature worktree:
`/Users/sto/workspace/datomworld-stream-s1` (branch `stream-crossmachine-s0-s1`).
DO NOT TOUCH any other worktree.

### Objective: Complete Architect Ruling A2 (§2 of collab/1791308475375-architect-stream-s0-s1-signoff.glm-5.3.findings.md)

1. **`src/cljc/dao/stream/ws_project.cljc`**:
   - `make-acceptor`:
     - Add `:step-budget` to config map destructuring and atom state.
     - Document in docstring: `:step-budget`, optional positive integer or nil, bounds events processed per session per tick.
     - Add validation clause in `when-not`: `(or (nil? step-budget) (and (integer? step-budget) (pos? step-budget)))`.
   - `accept-step!`:
     - Extract `step-budget (:step-budget @acceptor)` (or from acceptor state).
     - At line 363 (the per-session projection step), pass the budget: `(step! (:project session) step-budget)`. (When `step-budget` is nil, it falls back to unbounded reading, preserving 100% backward compatibility).

2. **`docs/design/dao.stream.remote.md` Section 3.0**:
   - In the step event budget bullet, add one sentence explicitly naming the acceptor's `:step-budget` configuration key.

3. **`test/dao/stream/ws_project_test.cljc`**:
   - In `invalid-bounds-are-a-composition-error`: add invalid `:step-budget` rows (`:step-budget 0`, `:step-budget 1.5`, `:step-budget -1`).
   - Add a flood / no-starvation test:
     - Two sessions admitted.
     - First session flooded with multiple events (e.g. 4+ events deposited).
     - Acceptor configured with `:step-budget 2`.
     - Drive one `accept-step!` tick.
     - Assert that the first session's projected ring receives exactly 2 values (budget respected), AND the second session's request/traffic is processed in the exact same tick (fair scheduling / no starvation).

4. **Verify**:
   - Run `clojure -M:test -n dao.stream.ws-project-test`.
   - Report test assertion counts.
