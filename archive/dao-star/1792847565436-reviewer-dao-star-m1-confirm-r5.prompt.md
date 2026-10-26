Created-GMT: 2026-10-24 13:26:05 GMT
Created-Local: 2026-10-24 20:26:05 +07
Coding-Agent: codex
Session-ID: resumed (thread 01a16da3-9a45-7833-9880-09aef39b6a12)
# Task: dao.star Mode 1 — confirm the r5 resolution of the deferred-grant lifecycle defect
Role: Reviewer (adversarial, confirmation round)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-24 20:26:05 +07 | Status: active | Rationale: same reviewer thread; your one blocking defect was addressed in round r5

Read-only confirmation in
`/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`. Do not
edit, stage, commit, or rerun suites. Verify statically.

The r5 resolution of your HIGH finding (deferred grants outliving an
observed lapse): it took your smaller option. The holder's fact cursor now
advances **only once its reading is usable** — `usable-reading?` mirrors
`observe-grant`'s own requirement (well-shaped duration, unit in the
holder's table, magnitude within the per-unit bound). Facts stay unread on
the medium, in order, until a usable reading can observe them: a grant is
never skipped, and a lapse that follows it is never read ahead of it. The
r4 `:pending-facts` retention is removed entirely, which also dissolves
your foreign-grant storage-growth residual (nothing is retained, so there
is no pending storage to grow). Tests: `a-lapse-behind-a-deferred-grant-is-read-in-order`
(first reading `{:ms 0}`; grant published; lapse recorded; usable reading
arrives; assert not holding, `:lapsed?` set, `renew!` appends nothing, no
projection) and `foreign-grants-are-not-retained` (30 foreign grants, no
pending storage).

The implementer flagged one residual: `usable-reading?` duplicates the
private `reading-ok?` check inside `dao.lease`; correct today, but the two
could drift if `dao.lease` changes. Weigh whether that duplication is
acceptable or must be resolved (note the file-ownership boundary: r5 could
not edit `dao.lease` to expose the check).

Orchestrator evidence since your last pass (not to be rerun): focused
`clojure -M:test` over the eleven test namespaces: 69 tests / 1938
assertions / 0 failures; `bb test:sub:clj dao.stream` 36 ns / 516 tests /
0 failures; `bb test:changed` three lanes green (clj 100, cljs 45, cljd
25); kondo clean.

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>

Then: resolution line for the defect (resolved / partially / unresolved,
with file:line evidence), your weighing of the `usable-reading?`
duplication, any new defects, and a final verdict line: SIGNED OFF /
SIGNED OFF WITH CHANGES / NOT SIGNED OFF.
