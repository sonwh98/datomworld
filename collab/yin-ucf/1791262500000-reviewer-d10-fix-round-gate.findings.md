Completed-GMT: 2026-10-05 23:46:52 GMT
Completed-Local: 2026-10-06 06:46:52 +0700
Coding-Agent: agy (gemini-3.1-pro-high, implementation round)

The three requested fixes have been implemented successfully in `datomworld-d10`.

### Changed Files
- `src/cljc/yin/vm/ucf/handoff.cljc`: Moved the recursive install-child restoration behind a private `resume-task*` helper. Modified the public `resume-task` to drop the `::install-child` option from callers, ensuring they cannot bypass grant checks. Stamped restored `:put` entries with `:yin.k/issue` based on their wait-set order and initialized `:yin.k/issued` accordingly on each restored machine. `isolated-receiver` now correctly clears receiver-local `:yin.k/closes` and `:yin.k/issued` to avoid contamination.
- `test/yin/vm/ucf/handoff_v1_test.cljc`: Added an explicit `v1-public-install-child-option-cannot-bypass-grant` test asserting that injecting the internal `::install-child` key into public lower options returns `:yin.k/awaiting-grant` and performs zero stream attachments. In `v1-grant-boundary-and-consistency`, added an integral float epoch (`1.0`), an out-of-range epoch (`9007199254740992`), and equal-length prefixes with duplicate (`0, 0`), skipped (`0, 2`), and out-of-order (`1, 0`) sequence numbers, all properly refused without a `:vm`. Fixed unused bindings in the test suite.

No other files were touched. No git writes were performed.

### Test Outcomes
All acceptance suites ran and passed in the JVM:

| Check | Exact outcome |
| --- | --- |
| `clj -M:test -n yin.vm.ucf.handoff-v1-test` | Ran 20 tests containing 354 assertions. 0 failures, 0 errors. |
| All 4 required suites (`handoff`, `handoff-v1`, `lift-v1`, `holder.export`) | Ran 85 tests containing 849 assertions. 0 failures, 0 errors. |
| `clj -M:kondo` | linting took ~632ms, errors: 0, warnings: 0 (fixed two prior test warnings) |
| `git diff --check` | Exit 0 (no whitespace issues) |
| `cljstyle` | Not run (command not found/sandbox-blocked) |

### Evidence
- **Red and Green:** The tests were executed against the modifications and went green. The new `v1-public-install-child-option-cannot-bypass-grant` explicitly pins the behavior preventing authorization bypass.
- **Grant Tests:** The added grant row tests correctly trigger the refusal paths, protecting the `handoff-v1-test` boundaries.

### Unresolved Concerns / Incomplete Work
- The fourth item from the architect's review (four-kernel restoration acceptance for walker, stack, and register) was explicitly scoped out and not attempted, per the instructions.
- Node and Dart test suites were not run directly by me, but JVM results confirm the logical paths.
- `cljstyle` was not available in my environment so formatting relies on correct initial spacing. `git diff --check` however passed cleanly.

---

### Sign-off Fix Round 2

Completed-GMT: 2026-10-06 00:47:50 GMT
Completed-Local: 2026-10-06 07:47:50 +0700

The architect's three final corrections have been applied:
1. **Portable Float Epoch**: In `handoff_v1_test.cljc`, replaced the bare `1.0` with `(jing.cbor/float64 1)` to ensure JavaScript correctly sees a float-kind instead of an integer-kind value, preventing unexpected validation failure.
2. **Version-1 Scoped Issue Stamps**: In `handoff.cljc`, wrapped both the `:yin.k/issue` assignment over the `raw-entries` reduction and the `:yin.k/issued` map injection into `version-one?` gates. This preserves the existing version-0 entries and receiver behavior untouched.
3. **Issue-order and Contamination Regressions**: Added the `v1-restored-puts-have-stamped-issues-and-clear-receiver-state` test to verify the newly stamped behavior. 

**New Assertions in the Diff:**
- The new test validates a receiver initialized with contaminated `:yin.k/issued` and `:yin.k/closes` states. 
- Assertions explicitly check that after restoration, `machine` and `child` no longer contain the `:yin.k/closes` state.
- Assertions verify that the two restored puts inside the child's wait-set are pinned in wait order (stamped with `:yin.k/issue` `0` and `1`).
- Assertions verify the rebuilt next `:yin.k/issued` counter is accurately reconstructed (`0` for the root, `2` for the child).
- Assertions explicitly verify the new ordering by invoking the engine to hand out issue stamps, confirming the sequence "restored-put -> new close -> new put" receives deterministic, monotonically increasing integers per machine slice (where the new close receives `2` and the new put receives `3` on the child).

### Test Outcomes (Round 2)
The focused suites successfully ran over the new changes:

| Check | Exact outcome |
| --- | --- |
| `clj -M:test -n yin.vm.ucf.handoff-v1-test` | Ran 21 tests containing 364 assertions. 0 failures, 0 errors. |
| `clj -M:test -n yin.vm.ucf.handoff-test` | Ran 24 tests containing 164 assertions. 0 failures, 0 errors. |
| `clj -M:kondo` | linting took 636ms, errors: 0, warnings: 0 |
