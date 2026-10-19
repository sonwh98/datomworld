Created-GMT: 2026-10-19 11:00:00 GMT
Created-Local: 2026-10-19 18:00:00 +07
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027 (resumed)
# Task: dao.star round 5, reflected-read blocker
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-19 18:00:00 +07 | Status: active | Rationale: owner said "run round 5"; via claude-cloud (metered API, owner-authorized)

You may edit only docs/design/dao.star.md and docs/design/dao.stream.http.md in
/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-r4. Do not stage or
commit.

Read codex's round-4 findings (different family):
/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-r4/collab/1792407200000-reviewer-dao-star-round4.gpt-6-1-sol.findings.md

One blocking defect and one non-blocking nit, both in docs/design/dao.star.md:

1. BLOCKING (about line 51). Your pass-order rule (read ingress, drain
   session-facts and lease cursors to blocked, then classify) is sound for
   direct reads. Over a reflection, dao.stream.remote.md lines 333-336 return
   blocked while a source request is pending, so blocked proves nothing about
   the source being drained, and the router can still call a published token
   unknown. Verify against dao.stream.remote.md, then either (a) limit the
   rule's claim to direct reads and say plainly what a router over a
   reflection must do, or (b) give the smallest observation barrier for
   reflected reads, with classification pending until it is met. Prefer the
   smaller text; do not add a wait or a second medium if (a) plus a stated
   limit suffices. Give a ruling with reasons and rejected alternatives.
2. NON-BLOCKING (about line 161). The completion criterion reads as an
   immediate guarantee that no driver renews a closed exchange after an ingress
   gap; renewals already in flight are possible. Reword so drivers stop
   renewing once they observe the lapse or their own gap, and reconciliation
   removes closed leases.

Constraints unchanged: the owner's six decisions stand, the blog is published
and must not be contradicted, keep the HTTP doc application-agnostic and EDN
blocks parseable, list anything unresolved under Deferred, name gaps in other
docs as open dependencies.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact session id>

Then a per-item table (fixed | rebutted | deferred | needs owner) and the
ruling for item 1.
