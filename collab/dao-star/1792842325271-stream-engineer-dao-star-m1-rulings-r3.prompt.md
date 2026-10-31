Created-GMT: 2026-10-24 11:45:25 GMT
Created-Local: 2026-10-24 18:45:25 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224 (resumed)
# Task: dao.star Mode 1 — round r3: implement the two r2 architect rulings (review findings 3 and 4)
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-24 18:45:25 +07 | Status: active | Rationale: same seat (session resumed); r2 fixes verified green by the orchestrator

The architect re-ruled the two findings your r2 round fenced off. Read the
ruling first:
`/Users/sto/workspace/datomworld/collab/1792841840885-architect-dao-star-m1-reconcile-r2.claude-fable-5-1.findings.md`
(The doc amendments it dictates are already applied to the design docs by
the orchestrator; docs are not yours.)

Work in `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`.

## Ruling 3 — the exchange lease gets a tenure cap

The dead-peer detection bound on a host that reports neither close nor
failed write (http-kit) is the exchange lease's `:dao.lease/max`, not its
duration: renewals do not move the cap, so a holder that renews forever
still reaches its bound and stops; the lease then lapses, reclaim runs
`close-exchange`, and `:http/closed` is deposited. Every healthy
subscription reconnects once per max (subscribe consumes nothing).

**Verification gate first (the architect flagged this as untraced):**
confirm in `src/cljc/dao/lease.cljc` that the grant surface your
exchange-grantor wrapper uses accepts `:dao.lease/max` (the lineage grant
takes it optionally per `dao.star.md`), and that the holder surface exposes
`at-bound?` in a form your exchange holder can honor. If the exchange grant
path cannot carry the cap without editing `dao.lease` (which is not yours),
STOP and report instead of editing it.

Then:
- The subscription exchange's grant carries a required `:dao.lease/max`
  (composition config; a missing cap for a subscription exchange is an
  assembly defect, ex-info `:refused :subscription-cap`).
- The driver's exchange holders honor `at-bound?`: past the bound they stop
  renewing, drop the subscription, and let the lease lapse to reclaim (the
  same discipline the lineage holder already has).
- TDD: the ruling's honest test — a silent subscription whose driver keeps
  renewing on every tick; the tick clock advances past the exchange lease's
  `:dao.lease/max`; assert renewal stops, `close-exchange` ran,
  `:http/closed` was deposited, and `:lapsed` was recorded. Keep the
  existing never-answered-exchange `:silence` test as is.

## Ruling 4 — session-facts medium is complete history

The ratified spec (`dao.star.md` line 49) requires a complete-history
transport for session facts; your r1 ring-buffer wiring was wrong and the
scoping ruling is superseded.

- Session facts move to `dao.stream.memory-log/create!` with spec
  `{:dao.stream/type :dao.stream/memory-log}` and no capacity key.
- The lease declarations for the facts medium become
  `:retention :complete` with no `:capacity`, mirroring the other keys
  (`:value-domain`, attribution) from the existing evict-oldest declaration
  helper in `src/cljc/dao/star/lease.cljc` (the r2 ruling cites line 217).
- The driver and router constructors refuse a session-facts medium whose
  declaration is not `:retention :complete` — ex-info
  `:refused :session-facts` — checked on the declaration, not handle
  introspection.
- Tests: the Phase B tests create session facts with `memory-log/create!`;
  one test that the constructor refuses an `:evict-oldest` declaration;
  one test that drives past what the old ring-buffer capacity would have
  been and asserts the root fact is still readable from the origin.
- With no `gap` possible on session facts, the router's and driver's
  gap handling for that medium is unreachable: move any facts-medium gap
  test coverage to the lease medium or ingress (the lease medium stays
  evict-oldest). Grep `ringbuffer` and `:evict-oldest` under
  `src/cljc/dao/star/` and `test/dao/star/` for leftovers.
- Note your r2 concern is resolved by this: roots can no longer be lost to
  a facts-medium gap.

## Acceptance

- All seven `dao.star` test namespaces plus `dao.stream.http-test` green:
  `clojure -M:test -n ...` (0 failures).
- `clj -M:kondo --lint` clean on everything you touch.
- Do not rerun the three-lane gate (orchestrator runs G2 after this).

## File ownership

`src/cljc/dao/star/*.cljc` and `test/dao/star/*.clj` only. Excluded:
`jvm.clj`, `dao.lease`, `dao.stream.*` sources (compose, never edit), docs,
deps.edn, bb.edn. If the cap requires a `dao.lease` change, stop and report.

Report to
`/Users/sto/workspace/datomworld/collab/1792842325271-stream-engineer-dao-star-m1-rulings-r3.claude-opus-5-5.findings.md`
(a denied write goes in your final response). Do not stage or commit. Begin
the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
