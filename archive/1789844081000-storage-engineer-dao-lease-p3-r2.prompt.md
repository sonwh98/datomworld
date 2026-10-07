Created-GMT: 2026-09-19 19:04:00 GMT
Created-Local: 2026-09-20 03:04:00 +07 (Indochina Time)
Session-ID: f69044f8-7920-435a-98be-f70b24d6181d (resumed — your Phase 3 session)
# Task: dao.lease Phase 3 — reconciliation round r2

The reviewer audited your holder delta: attribution and bound arithmetic
hold, but state handling and assembly have defects — 1 P1, 5 P2, 3 P3.
Read `collab/1789843700000-reviewer-dao-lease-p3.claude-fable-5-1.findings.md`
(this tree's copy). The orchestrator verified the citations; all are
accepted. Scope: still the same two files. Failing test first for the P1
and each P2.

Dispositions (all ACCEPT):

- **P1 (bound not terminal):** make the bound terminal exactly as
  prescribed — `observe-renewal` advances only when `(not (at-bound?
  holder reading))`; `due-to-renew?` false once `at-bound?` is true; latch
  `:bound-reached?` so a stale/smaller reading cannot reopen; add the
  stall-past-bound scenario test (renew at 30 on a lease the judge lapsed
  at ~12 must NOT move the basis).
- **P2 undersized grant:** in `observe-grant`, check `interval+interval`
  strictly below the GRANTED duration; on failure record `:undersized?`
  true in the state (the holder still holds what it was granted — the
  grantor's word — but every discipline function treats it as at-bound) —
  state which you chose and document it. Tests for both cases.
- **P2 keep-first binds no lease:** `initial-holder` takes optional
  `:proposal` and `:subject` expectations; `observe-grant` requires them
  to match when present. Two-grants-same-holder test.
- **P2 reading validation:** every `reading` argument gated with
  `duration?` + unit-table membership + per-unit bound; invalid reading →
  observe nothing / throw, mirroring the judge's first-tick rule. Document
  which.
- **P2 assembly parity:** `initial-holder`'s interval gets the tolerance
  checks (unit membership, per-unit quot bound) after `check-units!`;
  `renewal-interval`'s overflow becomes an assembly ex-info.
- **P2 stop idempotence:** second `stop` returns `:release nil`; document
  failed-append retry with the same fact. Test added.
- **P3 tick period:** `renewal-interval` takes an optional tick period and
  requires interval+period strictly below half; docstring and §2.5 note.
  (Plan §2.5 edit is the orchestrator's — say what text you need.)
- **P3 renewal reading:** docstring fixed to "the reading drained BEFORE
  the append."
- **P3 holding? predicate:** add `holding?` composing grant, not
  released?, not bound, not undersized; cap-basis question is the
  orchestrator's (already routed to §6 with the tolerance question — no
  code change).

After fixing: rerun the focused and full JVM lanes and the CLJS lane;
report exact counts. One single simple command per step; no chaining. No
staging or commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-finding disposition, tests added, exact counts.
