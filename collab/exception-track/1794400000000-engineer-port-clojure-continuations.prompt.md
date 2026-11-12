Created-GMT: 2026-11-11 09:40:00 GMT
Created-Local: 2026-11-11 16:40:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: de4f7213-1c5d-4822-aa89-a32cbfc19e9e
# Task: engineer — THE PORT: yang.clojure exceptions converge onto the continuation encoding
Role: Engineer (the arc's capstone round)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-11 16:40 +0700 | Status: active | Rationale: deepest context on this file's call/entry/outcome machinery; most honest track record of the arc.

## The directive

Owner decision of record (read `collab/exception-track/1794372000000-owner-decision-r3-vm-retained-seam.md`,
FINAL DIRECTIVE section): **yang.clojure's exception lowering converges onto the continuation
encoding** — the same `%capture` handler-stack scheme as python/typescript/php. No VM change. The
outcome model (slice-5) is retired. R4 becomes port verification.

## Phase 0 — LaTTe depth harness FIRST (blocking prerequisite)

`test/yang/latte_oracle_test.clj` collects no continuation depth today (astra verified at :239).
Add depth collection (max live continuation depth per workload) and record pre-port baselines for
every corpus workload to a committed EDN file. This is the yardstick for criterion 4 below. Do not
proceed to Phase 1 without committed baselines.

## Phase 1 — the port

Branch: extend `exception-r3-impl` (the §8.10.7 bridge work and the six protocol rows' bridge
conformance carry forward; the outcome internals they were expressed through do not). Worktree
`/Users/sto/workspace/datomworld-r3-orch`.

1. **Drop the outcome channel**: guest functions return plain values; `[:yin.k/ok|error]` wrapping,
   `condition->outcome`, `strip-top-outcome`, `bind-outcome`, the token-lane outcome forwarding —
   retired.
2. **Adopt the continuation scheme** (mirror py/ts/php; the shapes are in their preludes):
   a `clj/try` / `clj/try-finally` / `clj/throw` family over `%capture` with a handler stack in a
   runtime cell, `clj/call-ec` for escapes, `clj/unwind-to` running finally frames. `finally`
   becomes EXPRESSIBLE — lift the one-catch restriction and accept `finally` in portable try.
3. **Preserve the intra-unit private tail lane** (deepseek's remedy, mob amendment 3): direct
   intra-unit calls keep direct tail application; only published/cross-unit entries take the
   bridge frame. No VM change — `src/cljc/yin/vm/**` must diff EMPTY against its pre-port state.
4. **§8.10.7 boundary conformance unchanged**: the public entry bridge installs before argument
   binding; escaping exceptions AND binding failures deliver
   `{:yang.call/reason :yang.call/thrown, :yang.call/value v}` (or the qualified protocol
   condition, per R1/R3) to the caller's raise exit, exactly once, with §8.10.8 cleanup first.
   The kit's fixtures and `:expected` values are UNCHANGED — they are the acceptance instrument.
5. **Protocol conditions**: delivered as conditions via the bridge (no retention object needed —
   the replay/mutation limitation dies with the outcome channel).
6. **Retire**: `:outcomes` gating, the witness/wrapper machinery, `dsg__condition_retained`,
   `dsg__visible_of`, `dsg__condition_wrapper` — dead code once outcomes are gone.
7. **Source semantics widen**: portable try accepts catch (including the existing
   Exception/ExceptionInfo shape) AND finally; document the widened surface.

## Phase 2 — acceptance

1. `clojure -M:test -n yang.clojure-test` green — with these expectations moved JUSTIFIED (the
   constant-depth pin `test-outcome-common-call-depth` breaks BY DESIGN: re-benchmark it to the
   honest post-port semantics — constant for handler-free intra-unit chains via the private lane
   and for loop/recur; linear across published entries — and rename/comment accordingly).
   `test-exception-r3-astra-probes`: the seven observation probes become trivially correct (the
   caught value IS the payload) — keep them as pins; the two VM-behavior pins die with the seam
   (already reverted per the NO decision).
2. `clj -M:kondo --lint` on your files — 0/0.
3. **NEVER run `yang.call.exception-contract-test` locally.** Commit + push `exception-r3-impl`.
   The orchestrator runs the kit + LaTTe harness on shiva (no overlay — remember js5 discipline).
4. Report per-phase: Phase 0 baselines (workload → max depth), Phase 1 the new try family shapes
   (file:line), retired machinery list, moved expectations each justified, commands + counts, SHA.

## Escalation thresholds (mob amendment 4)

The orchestrator will measure LaTTe max-continuation-depth pre/post on shiva. If any workload
exceeds >10× baseline or 10,000 frames, the owner reviews before merge. You do not need to gate on
this — build to the criteria above.

## Constraints

Files: `src/cljc/yang/clojure.cljc`, `src/cljc/yang/clojure/prelude.cljc` (if needed),
`test/yang/clojure_test.clj`, `test/yang/latte_oracle_test.clj` (+ the baselines EDN).
`src/cljc/yin/vm/**` stays EMPTY-diff (the declined seam stays declined).
Foreground only; no background server tasks; no suite-scale local runs.

Begin the final response with Completed-GMT/Completed-Local; end with the SHA and one line:
**PORT LANDED** or **PORT BLOCKED: <what>**.
