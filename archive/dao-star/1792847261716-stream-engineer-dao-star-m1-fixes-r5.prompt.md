Created-GMT: 2026-10-24 13:21:01 GMT
Created-Local: 2026-10-24 20:21:01 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224 (resumed)
# Task: dao.star Mode 1 — fix round r5: the one blocking defect left
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-24 20:21:01 +07 | Status: active | Rationale: same seat (session resumed); r4 verified green by the orchestrator

The reviewer's latest pass confirmed gaps 1, 2 and the docstring resolved.
One HIGH defect remains — introduced by your r4 gap-3 fix — plus one
resource residual to address. Read the findings:
`/Users/sto/workspace/datomworld/collab/1792847030203-reviewer-dao-star-m1-confirm-r4.gpt-6-1-sol.findings.md`

Work in `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`.

**HIGH (`lease.cljc:112`) — deferred grants can outlive an observed lapse.**
While the holder is ungranted, every fact takes the first conditional
branch. With an unusable reading: an `:accepted` grant is retained in
`:pending-facts`; a following `:lapsed` for that lease is discarded
(`observe-grant` rejects it, only accepted facts are retained); on a later
usable reading the retained grant establishes tenure while the lapse is
already behind the advanced cursor. The holder then reports holding, can
renew, and can permit staged projection despite having read its lease's
retirement. Fix per the reviewer: either defer fact-cursor advancement
until a usable reading exists, or preserve ordered lifecycle evidence —
including lapses — alongside deferred grants. Choose the smaller change
consistent with the holder discipline. Test (reviewer-specified): grant →
lapse → usable reading, asserting no tenure, no renewal, and no
projection.

**Resource residual (reviewer-weighed, non-blocking but fix now): pending
storage can grow indefinitely if the holder's own grant never arrives** —
foreign-grant facts pile up in `:pending-facts`. `observe-grant`'s
attribution/addressing checks already distinguish permanently inapplicable
grants from applicable ones deferred only by an unusable reading; drop the
permanently inapplicable ones instead of retaining them.

Acceptance: all ten `dao.star` test namespaces plus `dao.stream.http-test`
green (`clojure -M:test -n ...`); `clj -M:kondo --lint` clean on touched
files; no three-lane gate (orchestrator runs G2). File ownership:
`src/cljc/dao/star/*.cljc`, `test/dao/star/*.clj`. Excluded: `jvm.clj`,
`dao.lease`, `dao.stream.*` sources, docs, deps.edn, bb.edn.

Report to
`/Users/sto/workspace/datomworld/collab/1792847261716-stream-engineer-dao-star-m1-fixes-r5.claude-opus-5-5.findings.md`
(a denied write goes in your final response). Do not stage or commit. Begin
the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
