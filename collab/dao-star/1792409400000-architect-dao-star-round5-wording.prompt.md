Created-GMT: 2026-10-19 11:30:00 GMT
Created-Local: 2026-10-19 18:30:00 +07
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027 (resumed)
# Task: dao.star two wording fixes
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-19 18:30:00 +07 | Status: active | Rationale: owner said "apply the two fixes then adopt"; via claude-cloud (metered API, owner-authorized)

You may edit only docs/design/dao.star.md in
/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-r4. Do not stage or
commit. Smallest possible diff: change only the sentences named below.

Apply codex's two non-blocking corrections from
/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-r4/collab/1792408600000-reviewer-dao-star-round5.gpt-6-1-sol.findings.md
Verify each against dao.lease.md (lines 148-150) and the doc text, then apply:

1. dao.star.md about line 163. Replace the claim that in-flight renewals are
   ignored with: in-flight renewals may count while the lease remains live;
   renewals observed after retirement do not revive it. (A driver's own gap
   does not itself remove the lease.)
2. dao.star.md about line 53. "Only the HTTP boundary's ingress and egress may
   be reflections" is broader than the rule. Scope the restriction to the
   router's classification media (session-facts and lease) and say a driver
   separated from the HTTP boundary may observe exchange grants and lapses
   over a reflection.

If you judge either correction wrong, rebut it with evidence instead. Owner
decisions, the blog, and the HTTP doc stay untouched. EDN blocks must still
parse.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact session id>

Then the two changed passages quoted before and after, and the final line count.
