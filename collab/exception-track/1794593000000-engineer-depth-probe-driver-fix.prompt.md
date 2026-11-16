Created-GMT: 2026-11-13 22:15:00 GMT
Created-Local: 2026-11-13 05:15:00 +07 (+0700)... corrected: 2026-11-13 08:15:00 GMT = 15:15:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 8292e813-5b91-4daa-a36c-0e6060577c5a
# Task: engineer — fix the depth-probe driver: dispatch cell effects during stepping (13 failing observations)
Role: Engineer (your engine, your state machine; the probe driver is in your own test file)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-13 15:15 +0700 | Status: active | Rationale: the engine's effect dispatch and heap-write are your code; the probe driver is in your own test file.

## Worktree

`/Users/sto/workspace/datomworld-r3-orch`, branch `exception-depth-probe` @ `56cc5ca3`, with the
probe files present but UNCOMMITTED (646-line `depth_probe_test.cljc` + `depth_probe.edn`).
Commit what exists as your starting point (message: "wip: the depth probe — driver fix pending"),
then fix forward.

## The defect (failure signature verified, 13 failing observations)

`yang.call.depth-probe-test` `constant-matrix-test`: `(:completed? m)` = false; observation slot
reads `""` where fixture results were expected ("512", "32640", "128", "256"). The guest programs
never produce results — the driver's stepping does not dispatch synchronous cell effects (the
`cell/dsg__new-provenance` mint at prelude/adoption sites is an effect that completes INSIDE the
engine per engine.cljc:2777-2812, but the probe's driver apparently doesn't route through the
dispatching step, or stops at the effect-block state).

## The fix

The probe driver's stepping must mirror the engine's own effect handling: effects dispatch
synchronously inside the step; the driver steps until the machine's halt/block predicate says
finished (empty continuation, no pending effects) — distinguishing completion from blocking
(the existing helper conflates them). Read `yang.call.depth-probe-test.cljc`'s stepping loop
and `src/cljc/yin/vm/engine.cljc`'s step/dispatch; the fix is likely the stepping entry point
(the driver may call a restricted stepper that doesn't dispatch effects) or the completion
predicate (the driver may check `:k` emptiness where the machine checks the halt predicate).

## Verification

`clj -M:test -n yang.call.depth-probe-test` — 144 constant-case cells green (D16=D64=D256 per
frontend/backend/variant) + the recursive characterization recorded; no new failures; kondo on
the test file 0/0. NO suite-scale; NO contract test locally. Commit + push
`exception-depth-probe`.

End with Completed-GMT/Completed-Local and: **DRIVER FIXED: <sha> | matrix: 144/144 green** or
**FINDING: <what>**.
