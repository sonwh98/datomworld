Created-GMT: 2026-10-07 06:40:50 GMT
Created-Local: 2026-10-07 13:40:50 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: 100c763e-7cdb-415a-9407-1e38d5954b48
# Task: stream-crossmachine-s2a-reconcile
Role: Stream & Network Implementation Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 1791355239843 | Status: active | Rationale: Fix blocking review finding R1 (dial mirror effects executing inside atom retry) and non-blocking coverage notes from independent Codex review.

## Instructions
You are the Stream & Network Implementation Engineer for Datomworld.
Work exclusively inside the dedicated feature worktree:
`/Users/sto/workspace/datomworld-stream-s2` (branch `stream-crossmachine-s2` @ `65f635d0`).
DO NOT TOUCH any other worktree.

### Objective: Fix Finding R1 from `collab/1791355023304-stream-s2a-review.gpt-6.1-sol.findings.md`

1. **Fix R1 in `src/cljc/dao/stream/ws_project.cljc`**:
   - In `dial-step!` (lines 583-591):
     `remote/mirror-step` is currently called inside `(swap! mirror-cursor (fn [c] (remote/mirror-step ...)))`.
     Because `mirror-step` executes side-effects (reading streams, invoking source operations like `append!`, and writing answers to the channel), executing it inside `swap!` means a CAS conflict / retry re-executes those side-effects, duplicating appends!
   - Follow the single driver ownership model (same discipline as `accept-step!` at line 391):
     Read `@mirror-cursor`, execute `(remote/mirror-step table names ring @mirror-cursor handle (mirror-bounds d))` outside the atom update, and then store/reset the resulting cursor.
   - In `test/dao/stream/ws_project_test.cljc`, add a regression test confirming that mirror effects are not re-executed.

2. **Non-blocking coverage items from Codex review**:
   - In `test/dao/stream/remote_test.cljc`, add tests for writer `full` on `cursor` and `next` requests (verifying rewind to preceding cursor).

3. **Verify**:
   - Run `clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test`.
   - Ensure `mise exec -- cljstyle check` is clean.
   - Ensure `clj -M:kondo` has 0 errors.

Report your changes and verification output.
