Created-GMT: 2026-10-19 10:42:35 GMT
Created-Local: 2026-10-19 17:42:35 +07
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027 (resume attempt; new session if the store cannot resume)
# Task: dao.star round 4, two blocking defects
Role: Lead System Architect
Implementers:
- Status-Event: 2026-10-13 11:10 EDT | Model: claude-fable-5-1 | Status: failed | Rationale: dispatch 1791904243223 died with "OAuth session expired and could not be refreshed"; no edits made
- Model: claude-fable-5-1 | Assigned: 2026-10-19 17:42:35 +07 | Status: active | Rationale: owner said "run round 4 on the two defects"
- Status-Event: 2026-10-19 17:45 +07 | Model: claude-fable-5-1 | Status: failed | Rationale: "You've reached your Fable limit." Exit 1, no edits made; owner decision pending on the replacement route

You may edit only docs/design/dao.star.md and docs/design/dao.stream.http.md in
/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-r4 (the worktree
holds the 160-line and 153-line saved designs). Do not stage or commit.

Read the codex round-3 findings (different family):
/Users/sto/workspace/datomworld/collab/1791318600000-reviewer-dao-star-edited-docs-r3.gpt-6-1-sol.findings.md
and the Architect round-3 report it reviewed:
/Users/sto/workspace/datomworld/collab/1791318000000-architect-dao-star-fix-r2.claude-fable-5-1.stdout.log

Two blocking defects, both in docs/design/dao.star.md. Verify each against the
text, then fix or rebut with evidence, and give a ruling with reasons and
rejected alternatives for each:

1. Token race (about lines 41-59). A successful append of the root fact is
   publication, not observation by the router's own cursor on the
   session-facts medium. The browser can request /s/<token> before the router
   has indexed it, and the doc then treats the token as unknown and starts a
   second lineage. Apply the same question to successor tokens.
2. Unchecked gap delivery (about line 89 and the criterion near line 159). The
   router appending a gap event to each session medium does not guarantee a
   driver observes it. State the checked append outcomes, what a driver does
   with its own session-medium gap, and the recovery or teardown when
   forwarding fails, so no driver keeps renewing forgotten subscriptions.

Constraints: the owner's six decisions stand (Q1 Mode 2 waits for UCF v1, Q2
allow-listed migration back, Q3 one-shot stale input, Q4 dao.lease expires
parked continuations, Q5 token as sole Mode 1 gate, Q6 your unconfirmed
ruling). The blog (public/chp/blog/daostar-vs-datastar-vs-electric-clojure.blog
in the main tree) is published; do not contradict it. Owner prefers the
smallest text that satisfies the point: prefer deleting or deferring machinery
to adding it, and list unresolved items under Deferred. Name gaps in other docs
as open dependencies, do not specify them here. Keep the HTTP doc
application-agnostic and its EDN blocks parseable by clojure.edn/read.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact session id>

Then a per-defect table (fixed | rebutted | deferred | needs owner) and the two
rulings.
