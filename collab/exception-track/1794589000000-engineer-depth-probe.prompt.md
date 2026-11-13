Created-GMT: 2026-11-13 15:20:00 GMT
Created-Local: 2026-11-13 22:20:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 7fdd624c-9b96-4820-b158-5e9593adaa99 (capture thread.started)
# Task: engineer — the UNIFORM DEPTH PROBE for all four frontends (the mob-designed contract)
Role: Engineer (kit-side; this is the mod's Q4 answer made executable)
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 22:20 +0700 | Status: active | Rationale: you own the depth harness and the comparison writer from the port rounds.

## The design (from the depth mod's ruling — implement as specified)

A uniform probe asserting and measuring continuation depth identically across all four
frontends, in `test/yang/call/` (kit-side, new file `depth_probe_test.cljc` + a writer):

1. **CONSTANT-CASE ASSERTIONS (the contract)**: for each frontend, a `loop/recur` fixture and
   an iteration-local `try` fixture (completed before `recur`): assert
   `D(16) = D(64) = D(256)` — identical max-live-continuation-depth at three input sizes.
2. **LINEAR-CASE MEASUREMENTS (characterization, never assertions)**: named self/mutual calls
   with one outer catch; cross-unit published calls; genuinely nested handlers — measured at
   depths 8/32/128, successful return AND deepest-level failure. Record increments in the EDN;
   **never assert `D(128) > D(32)`** — a lower-bound assertion would reject a correct
   optimization.
3. Use the kit's linked semantic/stack/register backends (`conformance.clj:23` — the walker is
   not a linked backend). The four frontends' drivers: compose per `exception_contract.clj`'s
   existing composition helpers.
4. The writer emits `test/yang/latte_oracle/depth_probe.edn` (or a kit-side path — your choice,
   justify) with per-frontend per-fixture measurements + the SHA + lane.

## Constraints

No VM files; no kit contract-file changes (this ADDS a probe file; the contract fixtures stay
untouched); focused runs locally are fine (this is your own new namespace); suite-scale on
shiva is mine. Commit + push on a branch `exception-depth-probe` cut from current master.

End with Completed-GMT/Completed-Local and: **PROBE LANDED: <sha> | constant-case: <green both
lanes> | linear-case recorded: <n fixtures>** or **BLOCKED: <what>**.
