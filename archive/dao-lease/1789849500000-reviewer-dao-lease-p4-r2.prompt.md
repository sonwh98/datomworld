Created-GMT: 2026-09-19 21:27:00 GMT
Created-Local: 2026-09-20 05:27:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your review session)
# Task: confirm the Phase 4 r2 fixes

Your P1 and five P2s were fixed. Orchestrator verification: JVM 1527
tests / 168550 assertions / 0 failures 0 errors; CLJS 1443 / 38414 / 0;
CLJD green apart from the 29 pre-existing voxel failures.

- P1: `initial-judge` refuses nil `:self` at assembly; matrix row added;
  the nil-resolver-never-counts-as-grantor test pins the authority path.
- P2 declaration: `:medium` rides the wired entry; both derived refusals
  added (`:durable?` + `:host-values`; `:evict-oldest` capacity <
  `:drain-budget`); docstrings state what compatibility does and does not
  mean.
- P2 tolerance: `make-judge` requires an explicit tolerance; `{:ms 0}` is
  the zero; `initial-judge` keeps nil-means-zero.
- P2 holder outbound: `make-holder` requires `:writer` with declaration,
  returns it.
- P2 end-to-end: `composed-both-halves-cycle-test` runs the composed
  holder and judge together through a `:release` lapse.
- P2 sketches: envelope-key attribution from `:ws/attachment`; the
  carriage step filters `:lapsed` (proven non-vacuously); the JVM-gated
  deftest drives a real `serving` session whose reclaim is the real
  close path.
- P3s: check order, capacity only for `:evict-oldest` (+ positive
  `:complete` case), docstring softenings, tick-driver baseline +
  `:cljd`-first, explicit `:refused` keys.

Re-read only the touched lines and new tests. Challenge. Do not edit. Do
not rerun suites.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Return: finding | final disposition | evidence | remaining action.
Explicitly state whether Phase 4 is ready for commit.
