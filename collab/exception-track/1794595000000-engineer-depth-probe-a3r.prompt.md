Created-GMT: 2026-11-13 18:05:00 GMT
Created-Local: 2026-11-14 01:05:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 4a11aaf8-dfd6-4200-a2c5-243a2409124a
# Task: engineer — the UNLINKED DEPTH PROBE per the converged mob ruling (a′, 2/2)
Role: Engineer (the scope is MOB-RULED 2/2 — deepseek-v4-pro and gpt-6-astra converged on (a′);
the spec below is their convergent deliverable, with astra's implementation corrections)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-14 01:05 +0700 | Status: active | Rationale: both
  architects named you (the engine state machine is your code); astra named you explicitly.

## Read first (the ruling + the corrections)

1. `collab/exception-track/1794594000000-consult-depth-driver-scope-mob.deepseek-v4-pro.stdout.log`
   — your own ruling (a′).
2. `collab/exception-track/1794594000000-consult-depth-driver-scope-mob.gpt-6-astra.stdout.log`
   — astra's convergent ruling + TWO IMPLEMENTATION CORRECTIONS you must apply:
   a. **"Empty K is not completion"** — a running top-level activation can have no return
      frames; parking can also clear active K. Use the engine's existing halt/block predicates
      and require the EXPECTED RESULT; reject pending scheduler work. The semantic machine
      distinguishes active control from its K vector (semantic.cljc:149).
   b. **"Pure" means no PARKING — not no effects.** Production runtimes use synchronous cell
      effects that complete inside the engine's effect handling (engine.cljc:2777); the probe
      must preserve them. Do not exclude fixture sets because they use cell operations.
3. `collab/exception-track/1794590000000-consult-depth-probe-scope.gpt-6.1-sol.stdout.log`
   — sol's (C) spec: the fixture list, the exact assertions, mandatory completion.

## The deliverable (astra's numbered spec, verbatim in substance)

Worktree `/Users/sto/workspace/datomworld-r3-orch` (launch cwd), branch `exception-depth-probe`
@ `56cc5ca3` (clean). Files: `test/yang/call/depth_probe_test.cljc` (new) + a test-local
helper/writer + `test/yang/call/depth_probe.edn` (evidence). NOTHING else.

1. **Test-local driver + explicit preparation boundary**: initialize bundled production
   runtimes and fixture definitions before sampling; invoke through normal frontend entry/call
   wrappers; sample semantic `:k` and stack/register `:continuation` before execution and after
   every guest instruction (entry, handler, cleanup, return frames). No unrestricted `vm/run`
   inside the measured interval. Unexpected blocking, pending scheduled work, budget
   exhaustion, initialization failure, or absent samples FAIL explicitly (sol's rule —
   detecting blocking is trivial; servicing it is not required on unlinked lanes).
2. **The complete constant matrix**: 4 frontends × 4 variants × 3 backends × 3 sizes = **144
   completed measurements**. Variants: plain iteration (loop/recur; native source loops for
   py/ts/php), iteration-local try/finally (normal completion + observable finalization),
   iteration-local caught failure/finally, one outer catch surrounding the entire loop.
   Assert results, iteration/catch/finalizer counts, caller handler restoration, positive
   executed-step counts, and `D(16)=D(64)=D(256)` within each frontend/backend/variant.
3. **Unlinked recursive characterization NOW** (not deferred — astra's ruling): named self/
   mutual calls under an outer catch + genuinely nested handlers at 8/32/128, success and
   deepest failure, all four frontends, three backends. Record depths and increments; assert
   outcomes and cleanup/restoration; NEVER assert growth or a minimum slope. Actual cross-unit
   published-call measurements remain in linked-warm coverage; bundled substitutes must not be
   labelled published-link evidence.
4. **Probe, writer, reproducible evidence**: `depth_probe_test.cljc`, the test-local
   helper/writer, `depth_probe.edn` with sol's fields (revision, dirty-state, composition,
   interval, depth, steps, completion, budgets). Label `:unlinked`; the linked-warm follow-up
   stays a named obligation (do NOT close it).

## Constraints

No VM files; no kit contract-file changes; production lowerings only; no mocks; no suite-scale
locally (focused namespace only). If credits interrupt, commit what exists and report — the
orchestrator recovers on codex.

Commit + push `exception-depth-probe`. End with Completed-GMT/Completed-Local and:
**PROBE LANDED: <sha> | constant matrix: 144/144 green | recursive: recorded** or
**BLOCKED: <what>**.
