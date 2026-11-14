Created-GMT: 2026-11-12 19:20:00 GMT
Created-Local: 2026-11-13 02:20:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 2aec19d5-5bf2-4c20-9bc9-0d09cdca740d
# Task: engineer — PORT Phase 2: lower-tail rewrite + call-entry bridge (the consumer wiring)
Role: Engineer (port phase 2; Phase 1 landed at 79d2c1b3 — your continuation family is live)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-13 02:20 +0700 | Status: active | Rationale: continuity; your prelude family is what this phase consumes.

## The task (your handoff order, phase 2)

In `src/cljc/yang/clojure.cljc`:
1. **`lower-tail` rewrite**: `throw` → `clj/throw`; `try` → `clj/try` (catch AND finally —
   finally is now expressible, lift the one-catch restriction); the outcome-tuple emission in
   the tail path → plain values.
2. **`call-entry` bridge**: the public entry installs the exception bridge before argument
   binding (R1): reads the supplied `:yang.call/raise`, delivers escaping exceptions AND
   binding failures exactly once, §8.10.8 cleanup first, execution after the call unreachable.
3. **`compile-guest-call` raise-escape**: conditions from non-clojure callees deliver via the
   raise exit as §8.10.7 conditions; an unmarked thrown value → fresh
   `{:yang.call/reason :yang.call/thrown, :yang.call/value v}`.
4. **Retire**: the wrapper/witness machinery (`condition-wrapper-form`, `condition-witness-form`,
   `condition-retained-form`, `dsg__condition_retained`, `dsg__visible_of`,
   `dsg__condition_wrapper`), the `[:yin.k/ok|error]` outcome wrapping, `condition->outcome`,
   `strip-top-outcome`, `bind-outcome`, the `:outcomes` gate. The per-call-site routers go
   (the caught value IS the payload under this encoding).
5. **Guest library** (`:2705` region): the `yin.k/outcome-of`-derived predicates
   (`string?`/`coll?`/`vector?`/`map?`/`set?`) rewrite onto `clj/try`/catch; portable outcome
   adapters become raise-adapters.
6. **REMAIN within R5**: a guest-thrown condition-SHAPED map is data, never an authenticated
   condition; only the bridge's raise exit delivers conditions. The kit's forged-retention
   semantics must hold.

## Preserve

- The intra-unit private tail lane (direct intra-unit calls stay direct tail application).
- The §8.10.7 condition shape and the kit fixtures — UNCHANGED as the acceptance instrument.
- `src/cljc/yin/vm/**` EMPTY diff.

## Acceptance (Phase 2 exit)

`clojure -M:test -n yang.clojure-test` — the 38F/13E `cell/retained?` red must be GONE (the
consumer wiring re-expresses those paths); new reds only where the port deliberately changes
source semantics (finally now allowed, etc.) with each named. kondo on `clojure.cljc` 0/0 (the
pre-existing macro/construct warning is fine). Commit + push `exception-r3-impl`.

End with Completed-GMT/Completed-Local and: **PHASE 2 LANDED: <sha>** or **BLOCKED: <what>**.
