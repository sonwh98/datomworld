Created-GMT: 2026-09-19 15:42:13 GMT
Created-Local: 2026-09-19 22:42:13 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947
# Task: adversarial review of dao.lease Phase 1+2 (vocabulary and judge)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-19 22:42:13 +07 | Status: active | Rationale: independent family from the glm-5.3 implementer and the deepseek plan author

Perform a read-only review, in this working tree (branch `lease-impl`), of:

- `src/cljc/dao/lease.cljc` (new, ~1140 lines) — the fact vocabulary and the judge
- `test/dao/lease_test.cljc` (new, ~796 lines) — 28 deftests, 267 assertions

against:

- `docs/design/dao.lease.md` — the operative contract; every sentence a rule
- `docs/design/dao.lease.implementation-plan.md` — as revised in this tree
  (see its "Revision — 2026-09-19 reconciliation" section: invariants
  V1–V7, J1–J16, D1–D7; an independent audit found the previous draft
  unsound and the plan was amended accordingly)
- `docs/design/dao.lease.rationale.md` — binds nothing, but records intent
- `src/cljc/dao/stream.cljc`, `src/cljc/dao/stream/forward.cljc`,
  `src/cljc/dao/stream/ringbuffer.cljc` — the surface the judge composes against

Already verified by the orchestrator — do not spend budget rerunning suites:
the full JVM lane passes (1478 tests, 168215 assertions, 0 failures) and the
implementation claims kondo-clean and C5-grep-clean. Spend your budget on
static analysis: invariant fidelity against the contract, edge cases the
tests could pass while an invariant is violated, portability, hidden state,
and the test list's adversarial gaps (the plan's own finding 20 lists tests
that can pass while their named invariant is false — check the implementation
against that spirit).

Check with priority:

1. The six-step pass (`judge-step`) against the contract's *The pass* section:
   ordering, pass-wide `now` stamping, budget-bounded drains, cursor retirement
   vs pass-abort, and the claim that an aborted pass reclaims nothing.
2. Authority: only grantor-authored facts establish terms; renewals count only
   from the holder; proposals create no state; `:seen`/`:answered` admissibility
   (V7) — including whether any path lets a non-holder or a repeat fact poison
   a later legitimate fact.
3. Incomplete evidence: gap→unknown marking per medium, the `:resumed` silence
   rule, tick-gap ≠ fact-gap, seed-from-grant, and `restart`'s
   reclaim-then-re-grant.
4. The vocabulary gate: `defective?` (structural, V4) vs `admissible?`
   (history-dependent, V7) — is any history rule leaking into the structural
   gate or vice versa, and do the constructors reject everything the contract
   calls a defect?
5. Prohibitions: no clock, no timer, no callback, no registry; the ledger is
   threaded, not global; `dao.stream` untouched (C6).

Do not edit anything. Treat the implementer's report and the plan's own claims
as untrusted; cite repository evidence for every finding.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate, and state explicitly whether
the change is ready for commit.
