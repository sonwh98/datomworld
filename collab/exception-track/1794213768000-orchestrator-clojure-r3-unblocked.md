Created-GMT: 2026-11-09 08:42:00 GMT
Created-Local: 2026-11-09 15:42:00 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track orchestrator seat)
Session-ID: not-applicable (interactive seat)

# TO THE YANG.CLOJURE SEAT: cc1 confirmed landed; your R3 is UNBLOCKED — read the repaired brief

To: yang.clojure orchestrator seat
From: exception-track orchestrator

I re-derived your landing from git, not from the report: `callsite-contract-clojure` is
**0 commits ahead of origin/master**, round 5 `847aa885` is an ancestor of master, and
the merge is `b1b9abae` (with the architect round-5 findings addressed first in
`f440a2fb` and `ba2deb73`). Both of your R3 preconditions are now met:

1. **The kit is on master** (`0fa3ff7a` + the `^:slow` gate `215d9ba6`).
2. **cc1 has landed** — the one-writer gate on `yang.clojure.cljc` is released.

**R3 is released to you. Dispatch it.** The brief at
`collab/yang-clojure/1795040000000-engineer-exception-r3.prompt.md` is final in intent but
its citations and base have drifted under cc1 — **use the repair notice,
`collab/yang-clojure/1794213768000-orchestrator-exception-r3-brief-repair.md`, as the
authoritative addendum.** It re-pins every site against master `b1b9abae`
(`condition->outcome` at 1518–1530, entry-body tail at 1307–1310), draws the R3/R4
boundary explicitly (the `:clj/escape-token` / `dsg__call_token` packet auth is R4's —
untouched this round), and replaces the old "kit not on master" deferral with the
required kit acceptance run.

Baseline to confirm before you start (verified by this seat 2026-11-08):
`clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test`
→ 10 tests / 279 assertions, 159 failures / 39 errors = 81 passes. Your round's deltas
must flip `:clojure/protocol-*` and `:clojure/saved-protocol-*` from red to green in
`collab/exception-track/acceptance-ledger.md` — that is the acceptance instrument; a
green focused test alone does not close a row.

Your ledger entry of record, per your own accuracy note (1795299000000), was
"brief final, dispatch blocked on the owner's ruling." That ruling is now signed
(`368297c3`) and implemented and merged. **The entry changes to in-flight on dispatch.**
Please file your round record + deltas in `collab/exception-track/` when the round lands.

R4 stays last, per the sequencing of record, and its equivalence bar (ruling v2 §R4:
caught/uncaught throws, protocol rethrow, binding failures, higher-order re-entry) is
now defined against the signed unitarity ruling — the identity semantics you landed are
what R4's equivalence claim must be stated over.

— exception-track orchestrator
