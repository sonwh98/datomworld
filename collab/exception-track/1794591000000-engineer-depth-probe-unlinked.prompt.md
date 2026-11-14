Created-GMT: 2026-11-13 17:00:00 GMT
Created-Local: 2026-11-14 00:00:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 459d2f1d-68ba-4b42-a1b4-27a81e2b2294 (capture thread.started)
# Task: engineer — the UNLINKED DEPTH PROBE per sol's ruling (SCOPE (C), immediate leg)
Role: Engineer
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 00:00 +0700 | Status: active | Rationale: you own the depth collection utility; this implements the unlinked leg of your own scope ruling.

## The ruling (implement IT, not your earlier draft)

`collab/exception-track/1794590000000-consult-depth-probe-scope.gpt-6.1-sol.stdout.log`
— SCOPE (C): the unlinked contract is ASSERTED now; the linked-warm characterization is a
bounded follow-up (NOT this round). Read the full ruling: the fixture list, the exact
assertions, the mandatory-completion rule, the caller-probe rule, and the correction that
ordinary named self/mutual calls are NOT constant-case fixtures.

## The spec (sol §4-§6, in order)

Worktree `/Users/sto/workspace/datomworld-r3-orch` (launch cwd), branch
`exception-depth-probe` @ `56cc5ca3` (clean). New file `test/yang/call/depth_probe_test.cljc`
+ the measurement helper.

1. **Fixtures, four variants, ALL FOUR frontends (clojure, python, typescript, php) as guest
   source**, at n = 16, 64, 256:
   - plain iteration: clojure `loop/recur`; python/ts/php native source loops
   - iteration-local try with normal completion + observable finalization
   - iteration-local try with a caught failure + observable finalization
   - one outer catch surrounding the entire loop
   Every iteration-local handler/finalizer finishes before the next iteration/recur.
2. **Exact assertions per frontend × backend × fixture variant**: correct results, exactly n
   completed iterations, expected catch/finalizer counts, AND `D16 = D64 = D256` — compared
   WITHIN a frontend/backend/fixture (never across frontends or backends).
3. **After caught-failure fixtures**: a caller probe checking handler restoration.
4. **Mandatory completion**: unexpected blocking, instruction-budget exhaustion, initialization
   failure, or missing measurements FAIL the probe. Assert the measurement interval executed
   instructions — three missing/zero placeholder values cannot satisfy the contract.
5. Backends: unlinked semantic + de Bruijn stack + register (three lanes), per the ruling.
   The walker is not a backend.
6. Evidence: `test/yang/call/depth_probe.edn` — revision, dirty-tree status, frontend, backend,
   fixture, variant, input size, composition mode `:unlinked`, measurement interval, max depth,
   executed steps, completion status, budgets. Label `:unlinked` (the linked-warm follow-up is
   a separate future round; do NOT close the linked obligation).

## Constraints

No VM files; no kit contract-file changes; the four guest sources compile through each
frontend's production lowering (compose per the kit's helpers); no mocks.

## Verification

`clj -M:test -n yang.call.depth-probe-test` green locally (your own namespace); kondo 0/0;
no suite-scale. Commit + push `exception-depth-probe`.

End with Completed-GMT/Completed-Local and: **UNLINKED PROBE LANDED: <sha> | constant-case:
<green all four frontends × 3 lanes>** or **BLOCKED: <what>**.
