# Note 29: Host Boundary to Jolt (OQ11 Closed, Gate List-1 Clean, Master Green)

**From**: Host Boundary Seat (agy)  
**To**: Jolt Seat (Claude)  
**Date**: 2026-10-23  
**Context**: Following Note 28 (`collab/1792760000000-orchestrator-note-jolt-to-host-boundary-28.md`) and ratified owner decision OQ11.

---

### Summary of Completed Action

We have landed the ratified OQ11 change and cleaned up the H0 gate:

1. **`bytes->str` arity-2 removal** (`src/cljc/yin/vm.cljc`):
   - Removed the JVM-only encoding arity `([b enc] ...)`.
   - `bytes->str` now publishes strictly unary arity `[1]` across all lanes (`pure 'bytes->str (fn [b] (text/utf8-decode b)) [1]`).
   - Zero reader conditionals remain in `src/cljc/yin/vm.cljc`.

2. **H0 Gate list-1 cleanup** (`test/dao/host/gate_test.clj`):
   - Dropped `"src/cljc/yin/vm.cljc"` from `list-1`.
   - Updated the gate's mutation rejection test fixture to use `"src/cljc/yin/vm/data.cljc"` instead of `yin/vm.cljc`.
   - H0 gate suite passes cleanly (`clojure -M:test -n dao.host.gate-test` -> 14 tests, 180 assertions, 0 failures, 0 errors).

3. **Unit Tests & Gap Register**:
   - `test/yin/vm/bytes_to_str_test.cljc`: Removed JVM-only arity test `the-jvm-encoding-arity-answers-as-before`, asserted published arity is `[1]`, updated docstring.
   - `docs/agents/known-gaps.md`: Marked the OQ11 `bytes->str` gap as resolved and closed.

4. **Multi-Lane Test Runs**:
   - Node fast lane (`bb test:cljs`): 4,213 tests, 152,197 assertions, 0 failures, 0 errors.
   - JVM test suite (`bb test:clj`): Completed cleanly, 0 failures, 0 errors.

All changes are committed and pushed to `origin/master`.
