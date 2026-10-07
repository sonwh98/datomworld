Created-GMT: 2026-09-19 18:26:00 GMT
Created-Local: 2026-09-20 02:26:00 +07 (Indochina Time)
Session-ID: e2f29617-226a-408f-b3f2-2fcefc8308d4
# Task: reconcile the waitset plan against its architect audit

Role: Architect (revision round — apply, do not re-audit)

The waitset implementation plan was audited by gpt-5.6-sol (verdict
unsound; 5 blocking, 5 should-fix, 1 note). Your job is to revise the plan
so every finding is resolved. You are revising design prose, not writing
code.

Read first:
- `collab/1789840265741-architect-dao-stream-waitset-plan.gpt-5.6-sol.findings.md`
  — the authoritative findings list with prescribed fixes
- `docs/design/dao.stream.waitset.implementation-plan.md` — the plan (note:
  its W0 census section was just corrected and architect-signed separately;
  do not disturb W0's rows or their adoption columns)
- `docs/design/dao.stream.md` — the stream contract (outcomes, the
  no-callback rule, retention)
- `docs/design/datom.world.md` — the foundational invariants the audit
  cites (skim the invariants sections)

Apply all eleven findings per the auditor's prescribed fixes. The
load-bearing ones:
- W3's cadence layer becomes an explicit stepped interpreter returning
  state and woken data; no shared queue, no host atom across threads, no
  callback that executes anything — host callbacks may only APPEND
  plain-data cadence events to a caller-supplied control stream.
- `nudge!` leaves the library API; model hints as plain-data events on the
  caller-owned control stream, define every transition eligible to emit
  one (close, retention advancement, acknowledgement — not just append).
- The resolver gets a complete synchronous algebra (resolve + pure
  commit-cursor, never retained), or a waitset-owned cursor overlay
  returned as data; the seam must be testable with a non-map store.
- `:budget` starvation: either drop the budget for a complete O(n) pass or
  add an explicit pure continuation (rotated order) with a
  more-entries-than-budget test in W1's test list.
- Writer `:dao.stream/closed` keeps its own terminal status — never maps
  to `:end` (this preserves W2's unchanged-VM-suite condition).
- W4's adoption list is replaced by the exact W0 census decisions
  (adoption sites: the engine, `dao.stream.serving`'s tick, the REPL tick
  owner; unchanged: observer, GUI pump, `rpc/poll!`, the ack-slot sweep
  under the transport exclusion).
- W5 expands to cover all obsolete `dao.runtime` references in
  `dao.await.md`.
- The outcome-classification test pins an explicit expected map whose
  domain must equal the declared outcome sets.
- `check` is described as a state-threaded, synchronous interpreter step —
  not "pure".

Where you disagree with a finding, you may rebut it from the contracts'
text in a one-paragraph reconciliation note at the end of the plan rather
than applying it — but apply by default. Add a short "Reconciliation —
2026-09-20" section listing each finding number with its disposition.

Scope: ONLY `docs/design/dao.stream.waitset.implementation-plan.md`. Do
not touch the W0 section's table rows. No staging, no commit. One single
simple command per step if any.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: the per-finding disposition list, any rebuttal with its
contract-text grounds, and anything unresolved.
