Created-GMT: 2026-09-27 03:30:00 GMT
Created-Local: 2026-09-27 10:30:00 +0700
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6 (resume; owner-authorized for this round)

# Task: Fix Round (parallel) — the re-gate's five findings

Role: Lead System Architect

You authored the dao.stream.remote spec set. gpt-6-sol's re-gate
returned five findings; the owner has authorized you to fix them in
parallel with gpt-6-sol (same brief, independent pass; the orchestrator
reconciles both into one text). Resume of your design sessions for
context continuity.

Read first:
- collab/1790463200000-architect-dao-stream-remote-regate.gpt-6-sol.findings.md
  (the five findings, with file:line)
- The current uncommitted docs in /Users/sto/workspace/datomworld:
  docs/design/dao.stream.remote.md (675 lines), dao.stream.middleware.md,
  dao.shibi.md, dao.stream.remote.implementation-plan.md,
  docs/design/dao.stream.md, docs/dao.space.stigmergy.md

Work items (gpt-6-sol's findings, restated):
1. P1 middleware.md:102-124: fully specify the gate's decision reads:
   initial cursor acquisition from the decision handle, the state
   transition for every outcome (element / gap / end), repeated-gap
   behavior, and concurrency semantics for a wrapped handle. Decision
   medium stays a capacity-1 medium written by a composed index
   interpreter.
2. P1 middleware.md + dao.shibi.md + remote.md:608-618: complete or
   bound the ShiBi fit. Owner direction: ShiBi is a tuple space
   emerging from two interpreters (dao.space.index, dao.space.query).
   Either (a) define how dao.space.query consumes requests and
   publishes request-specific decisions through ordinary streams, or
   (b) declare verify as the query interpreter's required adapter
   contract and state its limits precisely. Mark the unchosen path as
   a non-goal.
3. P2: right-size remote.md to under 600 lines without dropping a
   rule: move or condense the fate table and amendment inventory into
   the implementation plan.
4. P2: docs/dao.space.stigmergy.md:242-245 — mark the
   connect-content!/default-handlers passage historical, point to the
   planned dao.jing.content successor.
5. P2: remote.md pair-channel gap-to-loss: state explicitly that the
   pair adapter turns an in-gap into channel termination (end) for the
   affected link, resend absorbing the loss; or define equivalent
   loss reporting.

Owner invariants that bind every edit: P2P, no server/client, no
privileged node (client/server only as interpreter convention);
dao.stream is an abstraction boundary, network concepts stay remote-
side; authn/authz belong to ShiBi, only the capability-agnostic seam
here (no tokens); below the boundary prefer libraries over hand-rolled
crypto; ShiBi = tuple space of index and query interpreters.

Constraints: edit only the six listed files; ASCII; <= 80 columns on
added/edited lines; preserve everything the re-gate marked FIXED; do
not reopen settled decisions (mirror and reflection, one answer shape,
piggyback removal); no new modules or files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize per work item: what you changed, with file:line evidence, and
the resulting line count of remote.md.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
