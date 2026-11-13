Created-GMT: 2026-11-12 21:50:00 GMT
Created-Local: 2026-11-13 04:50 +07 (+0700)
Coding-Agent: codex
Session-ID: 602faf9e-983a-4c28-ad7a-f9e671761d47
# Task: engineer — PORT Phase 4: test updates to green (the final implementation leg)
Role: Engineer
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 04:50 +0700 | Status: active | Rationale: continuity (you hold the baseline/corpus context now); owner routing (codex primary, sol reserved review).

## State

Branch `exception-r3-impl` @ `6adb9b94` (pushed). Phases 1-3r-b landed; thresholds CLEARED
(max ratio 1.20×, max depth 28). You work in `/Users/sto/workspace/datomworld-r3-orch` — it is
your launch cwd; writes permitted there.

## The task

Make `clojure -M:test -n yang.clojure-test` FULLY GREEN on the port branch:
1. Run the focused suite; catalogue every failure/error with its cause.
2. Re-express remaining test expectations to the continuation-encoding semantics — the
   established pattern: deliberate source-semantics changes are NAMED in the test
   (finally now allowed; retention retired; outcome-of retired; failing? retired; adapters
   retired; capture-bridge→clj/raise). Each moved expectation must be JUSTIFIED by the
   port's design (docs/design/yang.antlr.md §8.10.7 + the ruling chain), never to make red go
   green. Do NOT weaken assertions; do NOT delete tests; a genuinely-broken product path is a
   FINDING, not a test edit.
3. Also make `yang.call.python-exception-test`-style pin expectations consistent where the port
   touched shared surfaces (should be none — python/js are merged and independent).
4. `clj -M:kondo --lint` on changed files: 0 errors (pre-existing warnings noted).
5. **NEVER run `yang.call.exception-contract-test` locally** (shiva-only). Commit + push
   `exception-r3-impl`.

## Report

Completed-GMT/Completed-Local; per-failure disposition table (moved | justified-by | sha-relevant);
suite counts before/after; final line **PHASE 4 GREEN: 103/… 0F/0E** or **FINDINGS: <broken
product paths, not test problems>**.
