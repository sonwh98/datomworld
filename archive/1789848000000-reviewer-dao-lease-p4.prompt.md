Created-GMT: 2026-09-19 21:07:00 GMT
Created-Local: 2026-09-20 05:07:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your review session)
# Task: adversarial review of dao.lease Phase 4 (composition) delta

Phase 3 is committed (`cc7f450b`). The implementer (glm-5.3-flash) has
since added Phase 4: the `make-judge`/`make-holder` constructors with
assembly refusal, the durable gating with the process-scoped fallback, the
test-tree stepped tick driver, and the three use-case sketches as runnable
composition tests. Orchestrator verification: JVM 1524 tests / 168519
assertions / 0 failures 0 errors; CLJS 1441 / 38388 / 0; CLJD green apart
from the 29 pre-existing voxel failures. The plan's §5/§6 now record all
phases built.

Under review — the delta in this working tree:
- `src/cljc/dao/lease.cljc` — the composition section (`make-judge`,
  `make-holder`, and helpers)
- `test/dao/lease_test.cljc` — the tick driver and its tests
- `test/dao/lease_composition_test.cljc` (new) — the 8 composition
  deftests including the sketches

against the plan's §4.4 and the C1–C8 invariants (as revised), and
`dao.lease.md`'s *Composition duties*.

Priority checks:
1. The refusal matrix: does every C2/C3/C4/C7/C8 refusal actually fire —
   and does any refuse things the contract permits (a composition with no
   answer hook, tolerance nil, a legitimate :complete medium)?
2. The medium-declaration contract: is compatibility (resolver vs medium)
   checked in a way that would catch real mismatches, or is it `fn?`
   theater?
3. Durable gating: does `:durable? true` without all three prerequisites
   really throw, and does the process-scoped fallback actually constrain
   anything, or is it a label?
4. The tick driver: stepped, caller-driven, no timer/callback in
   `dao.lease.cljc` (C5), monotonic readings, stop-on-demand — and does it
   leak across hosts?
5. The sketches: do they pin the C1 wiring meaningfully (real seams named:
   `serving/step!`, `close-session!`, `:ws/attachment`, `call-close!`), or
   are they decorative?
6. End-to-end: does `end-to-end-grant-renew-lapse-test` exercise the
   composed judge's own step (not a hand-built ledger), record exactly
   once, and assert grantor-local `:lapsed` (C8)?

Do not edit. Do not rerun suites. Challenge.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Report findings as P0-P3 | file:line | evidence | concrete fix. State
explicitly whether Phase 4 is ready for commit.
