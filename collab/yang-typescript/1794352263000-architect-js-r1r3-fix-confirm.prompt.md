Created-GMT: 2026-11-10 23:11:03 GMT
Created-Local: 2026-11-11 06:11:03 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: 01a1c79a-399d-72e1-84c0-19a5a05f0563 (resume of your review thread)
# Task: JS R1+R3 fix round — step-7 confirmation + one ruling you must make

Role: Lead System Architect

You returned FIX-LIST on `c0114a42` (P1: retained-condition authentication
forgeable via admitted Clojure `cell/new`; P2: comment overclaim at :1586).
The fix round is complete: commit `ce99e65e` on the same branch. This is the
resumed confirmation you asked to be kept for ("repair the R5 provenance bypass
and add focused forgery coverage before sign-off").

## Evidence

- Fix diff (c0114a42..ce99e65e):
  `/Users/sto/workspace/datomworld/collab/yang-typescript/1794350496000-engineer-js-r1r3-fix.diff.txt`
- Engineer findings (mechanism, hunks, probe evidence):
  `/Users/sto/workspace/datomworld/collab/yang-typescript/1794350496000-engineer-js-r1r3-fix.muse-wonder-1.0.findings.md`
- Worktree for any further reading: `/Users/sto/workspace/datomworld-jsr13`
  (post-change `prelude.cljc` ~:1584-1592, :1653-1714, :4133, :4293;
  `test/yang/call/exception_probes.clj`, `exception_contract_test.clj:59-84`)

The mechanism: `js/raise-protocol` allocates a sealed companion cell
(`{:yang.call/wrapper e, :yang.call/seal js.rt/retained-seal,
:yang.call/condition ...}`) and stores only the companion *reference* on the
wrapper; authentication now requires: authentic candidate cell → `:js/class
:error` → its provenance field is an authentic cell → the companion's
`:wrapper` is identical to the candidate → the companion's `:seal` is
identical to the per-task `js.rt/retained-seal` allocated in `js/init!`. No
ambient registry; the old readable contents-key is gone.

The engineer EXECUTED the P1 before fixing: both forgery probes reproduced the
forwarding (6 failures across the 3 VMs); post-fix both refuse, and
provenance 30/30 + kit 6/131 + source-call 2/22 stay green. (The orchestrator
is independently re-running these gates; your verdict and my re-run both stand
before any merge.)

## Your P2 and standing rulings: check they were honored

P2 narrowed the :1586 comment to ordering-only and kept the lifetime claim
explicitly open; nil raise-exit remains operational (no fallback added);
`js/construct-with-nt` un-claimed; donor tweak untouched. Confirm or object.

## The ruling only you can make: the seal-extraction residual

The engineer states it plainly: **deliberate seal extraction from a genuinely
held wrapper is undetectable without the ambient registry you ruled out.** An
admitted source that already holds a genuine protocol wrapper can
`cell/get` it, follow the provenance reference, read the seal, and mint its
own companion stamped with that seal — swapping the forwarded condition
(pure fabrication without a genuine wrapper still fails; copied-metadata
still fails via the back-reference).

Two readings, your call as written into the round's record:
(a) The residual is WITHIN R5's threat model closure — forging requires
already possessing a genuine wrapper, and rethrowing THAT wrapper forwards
only its own genuine condition; the delta (condition swap via deep raw
cell-reads of runtime state) is the same class of power as raw `cell/set!` on
any cell — a source-semantics authority the ruling never tried to close; or
(b) The residual violates R5's "never authenticate" and the fix must close it,
which forces reopening the no-ambient-registry constraint (e.g. provenance
stored where source cell-reading cannot reach it).

If (a): record the boundary precisely (what a wrapper-holder can and cannot do)
and SIGN OFF with the residual named as an accepted design limit.
If (b): state the mechanism direction you will accept despite the registry ban.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report per-item: P1 closed or not (severity if not) | P2 | standing
rulings | the residual ruling with reasoning | final verdict: SIGN-OFF or
FIX-LIST.
