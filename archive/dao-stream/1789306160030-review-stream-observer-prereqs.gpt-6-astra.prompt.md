Created-GMT: 2026-09-13 13:29:20 GMT
Created-Local: 2026-09-13 20:29:20 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: review-stream-observer-prereqs

Role: Routine Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 20:29:20 +07 | Status: active | Rationale: GPT family; independent of GLM-family author; standing architecture reviewer for dao.stream; resumes thread 01a099f1-87d6-7811-8a69-b3336b956fac

Review the diff for `src/cljc/dao/stream/observer.cljc` and `test/dao/stream/observer_test.cljc` against the governing specs.

Read first:
- `docs/design/yin.vm.macro.md` §5 "Prerequisite on the observer" and §7 Phase 0 test list
- `docs/design/dao.space.index.as-observer.md` §4.2 "Resumption is bound"
- `git diff HEAD src/cljc/dao/stream/observer.cljc test/dao/stream/observer_test.cljc`

Implementation context:
- Author: glm-5.3 (stream-engineer role)
- JVM test result (orchestrator-verified): 38 tests, 139 assertions, 0 failures, 0 errors
- Kondo: 0 errors, 0 warnings

Check in particular:
1. `attach` 3-arity: does it correctly return the kept cursor without calling `stream/cursor`? Does the 2-arity remain unchanged?
2. `carry-session`: is the exception identity correct (original as cause, data preserved, `:session` merged)? Does a caller matching a specific exception type see any risk?
3. `run-on-stream` load failure: is the carried cursor genuinely *before* the failing batch? Can a retry re-read and succeed without repeating already-delivered work?
4. `run-on-stream` run failure: is the carried cursor genuinely *after* the loaded batch with the post-load consumer state? Does this satisfy the macro expander's flush-retry requirement from `yin.vm.macro.md` §5?
5. Terminal read carrying: does the `observation-terminal` wrap correctly carry the most-advanced session from the round?
6. Do the four new tests cover the Phase 0 test list in `yin.vm.macro.md` §7 (A forwarded, B's load throws; A forwarded, B's run throws; terminal read after processed batch)?
7. Any portability concerns for ClojureScript or ClojureDart?

Rate each finding P1 (blocking), P2 (should fix before commit), or P3 (advisory). State your verdict: APPROVE or REQUEST CHANGES.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
