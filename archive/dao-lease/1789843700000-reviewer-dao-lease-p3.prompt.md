Created-GMT: 2026-09-19 18:54:00 GMT
Created-Local: 2026-09-20 02:54:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your Phase 1+2 review session)
# Task: adversarial review of dao.lease Phase 3 (the holder delta)

You reviewed this implementation's Phases 1–2 through four gates. The
implementer (glm-5.3-flash this time, not glm-5.3) has since added Phase 3,
the holder. The orchestrator re-verified the full matrix: JVM 1506 tests /
168383 assertions / 0 failures 0 errors; CLJS 1423 / 38252 / 0; CLJD green
apart from the 29 pre-existing voxel failures. The plan's §5/§6 now record
Phases 1–3 as built.

Under review — the DELTA only, in this working tree:
- `src/cljc/dao/lease.cljc`, the holder section (`initial-holder`,
  `renewal-interval`, `observe-grant`, `observe-renewal`, `due-to-renew?`,
  `at-bound?`, `stop`, and the private helpers) — everything after
  `restart`
- the 7 new holder deftests in `test/dao/lease_test.cljc`

against `dao.lease.md`'s *The holder* section, the sizing relations, and
the plan's amended H1–H5/S1. Phases 1–2 are closed; re-read holder code
only, plus whichever vocabulary/judge lines the holder calls (e.g.
`exceeds?` vs the holder's `at-or-past?`).

Priority checks:
1. The H2 asymmetry: the judge classifies silence only past
   duration+tolerance (`exceeds?`), while the holder renews AT its interval
   (`at-or-past?`) — is the strict-below-half sizing guarantee actually
   airtight given equality, cross-unit intervals, and the at-or-past
   firing rule?
2. Attribution: `observe-grant`'s gate — forged grants, other-holder
   grants, defective facts, non-grantor attribution, keep-first semantics.
   Is there any path where a holder acts on a grant it should not hold?
3. Bound arithmetic: `at-bound?`'s earlier-of computation with cap absent
   and present; the activity basis (later of last renewal and grant);
   off-by-one at equality.
4. `stop`'s discipline: exactly one `:released` fact, no `:lapsed` path,
   post-stop renewal recording.
5. Assembly validation consistency with the judge's (the R1 resolver gate,
   unit-table checks).

Do not edit. Do not rerun suites. Challenge the delta — wrong, incomplete,
or invariant-violating code is what you are looking for.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Report findings as P0-P3 | file:line | evidence | concrete fix. State
explicitly whether the Phase 3 delta is ready for commit.
