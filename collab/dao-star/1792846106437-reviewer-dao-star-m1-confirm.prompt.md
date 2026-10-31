Created-GMT: 2026-10-24 12:48:26 GMT
Created-Local: 2026-10-24 19:48:26 +07
Coding-Agent: codex
Session-ID: resumed (thread 01a16da3-9a45-7833-9880-09aef39b6a12)
# Task: dao.star Mode 1 — confirm the resolutions of your twelve findings
Role: Reviewer (adversarial, confirmation round)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-24 19:48:26 +07 | Status: active | Rationale: same reviewer thread; your findings were addressed and need your confirmation

This is the confirmation round for your review (verdict NOT SIGNED OFF, 12
findings). The same worktree `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`
has been fixed in two rounds. Read-only: do not edit, stage, or commit; do
not rerun suites (the orchestrator's evidence is below); verify statically
that each finding is genuinely resolved and that no fix introduced a new
defect or left a finding half-addressed.

The two HIGH findings that challenged architect rulings were re-ruled first
(`/Users/sto/workspace/datomworld/collab/1792841840885-architect-dao-star-m1-reconcile-r2.claude-fable-5-1.findings.md`);
the design docs were amended per that ruling before the code changed.

The resolutions to confirm:

1. **claim! duplicate terminal events** → rewritten on `swap-vals!`: the
   entry is read from the committed prior state, so of any number of
   competing claims exactly one answers non-nil. New tests: four competing
   closers over 200 trials give exactly one terminal event.
2. **finish! lost terminal event** → the park-or-deposit decision derives
   from the committed transition (`swap-vals!` old state), not from flags
   set inside a retryable swap. Interleaving test added.
3. **dead-peer bound unsupported** → architect r2 ruling: the exchange
   lease carries `:dao.lease/max` (required for subscription exchanges;
   `make-exchange-lease` refuses without it, `:refused :subscription-cap`;
   gap-reconciled grants are capped since their path is unknown; one-shot
   exchanges stay uncapped so the never-answered exchange still lapses by
   `:silence`). Holders stop renewing at the bound; the judge reclaims with
   cause `:cap`; `close-exchange` then deposits `:http/closed`.
   `dao.star.md` (Keep-alive, exchange grant, Sizing) and
   `dao.stream.http.md` (Honest Limit) now carry the cap-based text. Test:
   a subscription the driver renews every tick ends at its cap, with all
   four assertions above.
4. **evicting session-facts medium** → session facts moved to
   `dao.stream.memory-log/create!` (complete history, no capacity);
   `lease/facts-declaration` returns `:retention :complete`;
   `check-facts-declaration!` refuses non-complete or capacity-carrying
   declarations (`:refused :session-facts`) in `make-driver`,
   `make-router` and `make-lineage-lease`, checked on the declaration.
   Tests create facts with memory-log; a test appends 512 facts past the
   old 256 ring capacity and asserts the root is still readable from the
   origin and the lineage still routes live. `:facts-capacity` became
   `:lease-capacity` (lease media stay evict-oldest).
5. **acting-before-grant / fail-open** → the begin response and a
   subscription's head+first projection are staged until the relevant
   grant is observed; leased actions gate on `holding?`, failing closed on
   missing/invalid readings and gaps; past the bound, input is handled as
   begin.
6. **publish! bypasses the bound** → tenure rechecked before publishing
   pending work; past bound or lapsed the staged transition is dropped
   (requester sees silence); while gapped it stays staged.
7. **consumption tied to emission** → every consumed input stages a
   successor and fact regardless of emission; the previous model is
   retained when the program emitted nothing. Multi-input-between-emissions
   test added.
8. **gap handling per medium** → gaps on tick, lineage-lease,
   exchange-lease, view and begin cursors all make the driver forget its
   subscriptions and renew nothing; a lineage-lease gap clears the
   session's subscriptions.
9. **grants skipped before first tick** → the holder reads its fact cursor
   only once it has a tick reading, so earlier-published grants are
   observed later, not skipped.
10. **failed release never retried** → each session pass retries a pending
    release.
11. **test adequacy** → the observation-order test interleaves the fact
    publication and the request deposit within one pass (a reversed drain
    order would classify the request as begin); the renewal test asserts
    gaps of at least 30 and under 50 ms (half the 100 ms duration); a
    single-pass chase test replaced the three-round pump.
12. **CLJD decode silently {}** → JVM and ClojureScript seams implemented;
    on ClojureDart `decode` throws `ex-info` naming the seam; the namespace
    docstring states the scope. The throw is untested (no CLJD lane here) —
    weigh whether that is honest enough or must block.

Also corrected: the loopback test renamed
`boundary-reclaim-ends-the-response-and-deposits-http-closed`, its comment
now says the client reads the chunked terminator, not TCP EOF.

Known remaining items the implementer flagged for your weighing: (a) the
router and driver retain facts-medium gap-handling code that is now
unreachable (session facts cannot gap); (b) a request waiting for a grant
stays unread in its session medium — if the driver never receives a tick,
nothing consumes it; (c) the `:subscription-max` is a composition choice
(tests use 200 ms against a 100 ms duration).

Orchestrator evidence since your review (not to be rerun): focused
`clojure -M:test` over the nine test namespaces: 63 tests / 1924 assertions
/ 0 failures; `bb test:sub:clj dao.stream` 36 ns / 516 tests / 0 failures;
`bb test:changed` three lanes green (clj 94, cljs 45, cljd 25); kondo clean
on all touched files.

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>

Then: a per-finding resolution table (finding → resolved / partially
resolved / unresolved, with file:line evidence), any new defects the fixes
introduced, your weighing of items (a)-(c) and the untested CLJD throw, and
a final verdict line: SIGNED OFF / SIGNED OFF WITH CHANGES / NOT SIGNED OFF.
