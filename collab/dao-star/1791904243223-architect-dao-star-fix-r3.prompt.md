Created-GMT: 2026-10-13 15:10:43 GMT
Created-Local: 2026-10-13 11:10:43 EDT
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027 (resumed)
# Task: dao.star fix round-3 review findings (Round 4 dispatch)
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-07 00:30:19 +07 | Status: active | Rationale: resume; codex round 3 found 2 High (Blocking) defects

You may edit only docs/design/dao.star.md and docs/design/dao.stream.http.md
in /Users/sto/workspace/datomworld/.claude/worktrees/dao-star-orch. Do not
stage or commit.

Read collab/1791318600000-reviewer-dao-star-edited-docs-r3.gpt-6-1-sol.findings.md
(codex, different family). Same protocol as before: verify each finding, then
fix or rebut with evidence; give a ruling with rejected alternatives for each
High. Owner decisions stand and must not change.

Two blocking defects from Round 3 to resolve:
1. docs/design/dao.star.md:41–59 (High, Blocking):
   Successful append establishes publication, not observation by the router's independent session-facts cursor. The browser can receive a newly published token and request `/s/` before the router indexes its root fact; line 59 then incorrectly treats it as unknown and starts another lineage.
   Recommended correction: Before classifying a token as unknown, bring the facts projection through an explicitly established publication position, or use a stream-mediated readiness acknowledgement before exposing the token. Apply equivalent coordination to successor classification.

2. docs/design/dao.star.md:89 (High, Blocking):
   Appending a gap event does not guarantee that a driver observes it. Session-medium admission/retention and failed-append handling are unspecified. An evicting session medium can lose the notification or terminal events and return its own `gap`, for which no driver policy is stated. The driver can consequently retain and renew forgotten subscriptions, contradicting criterion line 159.
   Recommended correction: Specify ordered notification delivery with checked append outcomes. Require drivers to treat their session medium's own `gap` as subscription loss too; define recovery or teardown when forwarding cannot succeed.

Instructions from the orchestrator:
1. Length: Keep the text minimal. The owner prefers the smallest text that satisfies the point. Where a finding can be met by deleting or deferring machinery instead of adding it, prefer that and say so. Add to the Deferred list if appropriate.
2. Convergence: Name any external dependency outside your two files as an open dependency rather than specifying it here.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027

Then a per-finding table (fixed | rebutted | deferred | needs owner) and the
rulings for the Highs.
