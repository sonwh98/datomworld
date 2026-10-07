Created-GMT: 2026-09-19 20:12:00 GMT
Created-Local: 2026-09-20 04:12:00 +07 (Indochina Time)
Session-ID: f69044f8-7920-435a-98be-f70b24d6181d (resumed — your Phase 3 session)
# Task: dao.lease Phase 4 — composition constructors, reference tick driver, use-case sketches

Phase 3 is committed (`cc7f450b`). Your unit adds Phase 4, the final build
phase of the plan. You are on glm-5.3-flash with a bounded window: work
tight.

Read first: `docs/design/dao.lease.implementation-plan.md` §4.4 Phase 4
(your spec, as revised — note the C1–C8 invariants and the medium
DECLARATION contract, not handle introspection), §6 (the owed rows your
unit settles: medium-declaration validation moves to make-*), and the
existing file's sections you compose with.

Scope — the same two files as Phases 1–3:
- `src/cljc/dao/lease.cljc` — `make-judge` and `make-holder` constructors
  (assembly refusal per C2/C3/C4/C7/C8: explicit medium declaration
  `{:retention :evict-oldest|:complete :capacity n :value-domain …}`,
  resolver presence AND medium compatibility, reclaim procedure, tolerance,
  units, drain-budget, `:durable?` gating with the three durable
  prerequisites — an unsettled composition stays process-scoped), plus the
  host-policy reference tick driver under `#?(:cljd)`/`:clj`/`:cljs`
  reader conditionals as the plan specifies (a stepped, caller-driven
  deposit function the host loop calls — NO timer, NO callback, NO
  setInterval/Thread-sleep/Timer.periodic ANYWHERE in dao.lease.cljc: the
  plan's revision moved the driver into the TEST tree as a stepped
  function; the C5 grep must stay zero).
- `test/dao/lease_test.cljc` — the Phase 4 test list verbatim (the refusal
  matrix, the end-to-end grant→renew→lapse cycle through make-judge, the
  process-scoped fallback), plus the test-tree tick driver and its
  non-decreasing-reading + stop-on-demand tests.
- `test/dao/lease_composition_test.cljc` — NEW file, the Phase 4
  composition deftests per §4.4.

The three use-case SKETCHES are documentation-plus-light-exercise: pin
them as commented compositions in the composition test file, not products.

Verification, one single simple command per step (no chaining):
1. `clojure -M:test` — full JVM green.
2. `clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test` —
   same disclosed mise-Java-21 workaround as before if needed.
The orchestrator runs CLJD. No staging, no commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: what you built, per-invariant test coverage, exact counts,
and anything unresolved.
