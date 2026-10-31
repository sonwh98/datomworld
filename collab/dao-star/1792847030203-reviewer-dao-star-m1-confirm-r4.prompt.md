Created-GMT: 2026-10-24 13:03:50 GMT
Created-Local: 2026-10-24 20:03:50 +07
Coding-Agent: codex
Session-ID: resumed (thread 01a16da3-9a45-7833-9880-09aef39b6a12)
# Task: dao.star Mode 1 — confirm the r4 resolutions of your three remaining gaps
Role: Reviewer (adversarial, confirmation round)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-24 20:03:50 +07 | Status: active | Rationale: same reviewer thread; your three gaps were addressed in round r4

Read-only confirmation of the r4 fixes in
`/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1` against your
three remaining findings and one weighing note. Do not edit, stage, commit,
or rerun suites. Verify statically; check that each fix is complete and that
no new defect was introduced, including interactions between the fixes.

The r4 resolutions:

1. **Tenure on projection paths (your finding 1)** → a new
   `observe-subscriptions!` steps every subscription holder against current
   ticks at the start of each session pass and again before `publish!`
   patches; `patch!` requires each subscription's exchange holder to be
   holding; `flush-subscriptions!` requires both the exchange holder and
   the lineage holder. Tests: input-triggered patch with only the driver's
   clock advanced past the exchange cap while the lineage stays live (input
   answered, no patch); staged-subscription flush with the clock past the
   lineage bound before the exchange grant lands (holder observes grant,
   nothing projected).
2. **Exchange-holder gap cleanup (your finding 2)** → an exchange-holder
   gap now triggers session-wide `forget!`: every subscription cleared, the
   lineage holder marked gapped, nothing renews. Test: two subscriptions
   with differently positioned lease cursors; the sibling with the newer
   cursor is cleared too.
3. **Grants lost to unusable readings (your finding 3)** → `lease/step!`
   keeps an accepted grant in `:pending-facts` when `observe-grant` leaves
   the holder ungranted, retrying on each later pass. Test: first reading
   `{:ms 0}`, then a usable reading; the grant is observed and the begin
   page is answered. The implementer flagged one residual: a rejected
   accepted-grant fact addressed to another holder stays in that holder's
   pending list until its own grant is observed — weigh whether that is
   acceptable or must change.
4. **Docstring** → `signals.cljc` now states `decode` is total on the JVM
   and ClojureScript with the ClojureDart seam as the one exception.

Orchestrator evidence since your last pass (not to be rerun): focused
`clojure -M:test` over the ten test namespaces: 67 tests / 1950 assertions /
0 failures; `bb test:sub:clj dao.stream` 36 ns / 516 tests / 0 failures;
`bb test:changed` three lanes green (clj 98, cljs 45, cljd 25); kondo clean.

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>

Then: a resolution line per gap (resolved / partially resolved / unresolved,
with file:line evidence), any new defects, your weighing of the
pending-facts residual, and a final verdict line: SIGNED OFF /
SIGNED OFF WITH CHANGES / NOT SIGNED OFF.
