Created-GMT: 2026-10-07 06:20:00 GMT
Created-Local: 2026-10-07 13:20:00 +07
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027 (resumed)
# Task: dao.star fix cross-family review findings
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-07 00:30:19 +07 | Status: active | Rationale: resume; codex review returned 3 High, 4 Medium, 2 Low findings against your edits

You may edit only docs/design/dao.star.md and docs/design/dao.stream.http.md
in /Users/sto/workspace/datomworld/.claude/worktrees/dao-star-orch. Do not
stage or commit.

Read the independent review (codex gpt-6.1-sol, a different family) at
collab/1791316000000-reviewer-dao-star-edited-docs.gpt-6-1-sol.findings.md.
For each of the nine findings: verify it against the docs, then either fix it
or rebut it with evidence (say which in your report). The three High findings
are architectural (subscription vs input request kinds; consumed-token
lineage vs lease release; open-exchange derivation vs evicting ingress); give
each a ruling with reasons and rejected alternatives, not a patch.

Constraints, unchanged: the owner's six decisions stand (Q1 wait for UCF v1,
Q2 allow-list, Q3 one-shot + re-projection, Q4 dao.lease on parked
continuations, Q5 token as sole Mode 1 gate, Q6 your unconfirmed ruling).
Do not change an owner decision; if a finding cannot be fixed without one,
stop and name it. The blog is published and must not be contradicted. Fix the
EDN examples so they pass clojure.edn/read, and make dao.stream.http.md
application-agnostic (replace the sentence naming Datastar, SSE and yin.vm).
Keep the text as short as the content allows.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027

Then a per-finding table: fixed | rebutted | needs owner, with the ruling for
each High finding.
