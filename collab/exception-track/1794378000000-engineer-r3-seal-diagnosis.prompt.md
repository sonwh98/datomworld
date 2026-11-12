Created-GMT: 2026-11-11 03:20:00 GMT
Created-Local: 2026-11-11 10:20:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: a116dd0e-fc52-4d88-9357-def3c3904fb9
# Task: engineer — diagnose the environment-dependent retained-cell seal failure (R3)
Role: Engineer (diagnosis + fix)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-11 10:20 +0700 | Status: active | Rationale: produced the most honest round of the arc (refuted its own orchestrator with evidence, reverted its own wrong fix); has a shell; knows this code.

## The evidence (both facts are from shiva, clean clone at fbd8da0f, no overlay on the test file)

1. `yang.clojure-test`: **6 failures, 1 error** — every one in the retained-cell machinery:
   - `(cell/get e)` on a retained wrapper caught across the public entry fails with
     `[:thrown {:reason :dead-or-forged-reference, :effect :cell/get}]`
   - `(cell/set! e ...)` fails with `:dead-or-forged-reference` instead of the expected
     `:retained-cell` refusal.
   On the LAPTOP the same namespace at the same commit runs 103 tests / 0 failures (the
   implementer's claim), and deepseek's own round also found the fixtures green locally.

2. The contract kit: the six `:clojure/protocol-*` / `:saved-protocol-*` rows are STILL red with
   `{:reason :yang.call/thrown, :condition-identical false}` (expected: the qualified reason and
   `:condition-identical true`). `:clojure/fresh-*`, `binding`, `shape-*` are green.

Hypothesis to test FIRST: the retained ref's `:seal` does not survive whatever differs between the
laptop run and the shiva run of the SAME namespace — e.g. the seal is derived from something
environment-dependent (object identity, RNG, `System/identityHashCode`, a `gensym` counter seeded
differently under test concurrency), or the public-entry path re-issues refs when the invocation
record crosses the entry (the laptop may exercise one unit in-process; shiva may take the linked/
serialized path). Read `issue-ref`/`check-ref!` (src/cljc/yin/vm/engine.cljc) and the retained
mint; find what the seal is a function OF. Then decide: is the seal environment-dependent (fix the
derivation), or does the boundary legitimately re-issue (then the retained query must not depend on
the seal), or is the guest-visible ref stale after the mint (a real bug in the (A) commit)?

Also re-examine the six red protocol rows with this lens: if the wrapper's seal dies at the
boundary, `dsg__condition_retained` fails its `cell/retained?` check, `retained` = nil, and
entry-body falls into the `(raise (yang.call/thrown err-val))` arm — producing EXACTLY the observed
`{:reason :yang.call/thrown, :condition-identical false}`. One root cause may explain both
symptoms. Verify or refute that chain with the code.

## Deliverable

The seal's derivation (file:line), the environment-dependent step, and the minimal fix. Then
implement it: `src/cljc/yang/clojure.cljc` and/or `src/cljc/yin/vm/engine.cljc` +
`module.cljc` are in scope; `test/yang/clojure_test.clj` for the pin. Verify with
`clojure -M:test -n yang.clojure-test` (this is focused and ALLOWED locally — note it may now FAIL
at fbd8da0f in your environment too; if it does, reproduce, fix, and say so). kondo on your files.
Commit + push `exception-r3-impl`. **Never run `yang.call.exception-contract-test` locally.**

End with: **SEAL FIXED: <mechanism in one line>** or **NOT ENVIRONMENT-DEPENDENT: <what else>**.
