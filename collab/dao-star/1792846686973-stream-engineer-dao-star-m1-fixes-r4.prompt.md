Created-GMT: 2026-10-24 13:04:46 GMT
Created-Local: 2026-10-24 20:04:46 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224 (resumed)
# Task: dao.star Mode 1 — fix round r4: the confirmation round's three gaps
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-24 20:04:46 +07 | Status: active | Rationale: same seat (session resumed); r3 verified green by the orchestrator

The reviewer's confirmation round confirmed ten of your twelve original
findings resolved, and located three remaining gaps — all in the code the
r2/r3 fixes introduced. Read the findings first:
`/Users/sto/workspace/datomworld/collab/1792846106437-reviewer-dao-star-m1-confirm.gpt-6-1-sol.findings.md`

Work in `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`.

**Gap 1 (HIGH, `driver.cljc:138`, `:336`, `:428`) — projection paths do not
consistently enforce tenure.** `publish!` and `read-messages!` can emit
subscription patches before `tick-subscriptions!` observes that pass's
exchange ticks and lapses; `patch!` checks only that a subscription is
staged. Advancing an exchange to its cap while the lineage is live, then
processing an input, appends a patch before the holder notices its bound.
The new `flush-subscriptions!` likewise checks only the exchange holder, so
a subscription staged while the lineage was live can receive its first
projection after the lineage reached its bound. Fix: observe the
exchange-holder state before any subscription projection, and require both
lineage and exchange tenure at every projection path. Add
independently-scheduled holder/judge tests for both paths (input-triggered
patch; staged-subscription flush).

**Gap 2 (HIGH, `driver.cljc:412`) — exchange-lease gaps get incomplete
cleanup.** An exchange-holder gap drops only that subscription; a sibling
subscription with a newer cursor remains and keeps renewing, including the
lineage. Fix: propagate an exchange-holder gap to session-wide
`forget!`-style handling before further renewals. Test with two
subscriptions whose lease cursors sit at different positions.

**Gap 3 (MEDIUM, `lease.cljc:100`) — invalid first readings permanently
discard grants.** The r3 guard distinguishes nil from non-nil, not usable
from unusable: a non-nil invalid reading (e.g. `{:ms 0}`) advances the fact
cursor while `observe-grant` rejects it, and the grant is lost. Fix: retain
grants until a valid, in-table reading can observe them (or retain pending
grants explicitly). Test: invalid first reading, then a valid reading, then
assert the grant is observed and the holder proceeds.

**Docstring (from the reviewer's CLJD weighing):** `signals.cljc`'s opening
docstring still promises total/never-throwing behavior; qualify it to match
the documented CLJD scope exception.

Acceptance: all nine `dao.star` test namespaces plus `dao.stream.http-test`
green (`clojure -M:test -n ...`), `clj -M:kondo --lint` clean on touched
files. Do not run the three-lane gate (orchestrator runs G2). File
ownership: `src/cljc/dao/star/*.cljc`, `test/dao/star/*.clj`. Excluded:
`jvm.clj`, `dao.lease`, `dao.stream.*` sources, docs, deps.edn, bb.edn.

Report to
`/Users/sto/workspace/datomworld/collab/1792846686973-stream-engineer-dao-star-m1-fixes-r4.claude-opus-5-5.findings.md`
(a denied write goes in your final response). Do not stage or commit. Begin
the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
