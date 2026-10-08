Created-GMT: 2026-10-01 10:09:07 GMT
Created-Local: 2026-10-01 17:09:07 +0700
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c (resumed)
# Task: D6/D7 design — host-typed closures and continuations, qualified refusals, task ownership

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-01 17:09:07 +0700 | Status: active | Rationale: owner directive "use fable ... as the architect"; author of the cell, reclamation and mappability rulings and a mob seat on D6/D7

Read-only architecture design. Do not edit files. Work in /Users/sto/workspace/datomworld (master 60b60898).

OWNER (verbatim): "go ahead with 4 and 5" (plan item 5 = D6/D7 host-typed closures+continuations, alongside phase C).
Mob decision (verbatim from the converged mob, owner "yes"): D6+D7 are not spike gates; "host-typed closures AND
continuations together on a separate track, required before untrusted/multi-author code reaches an evaluator, with
qualified refusals and task-ownership checks (no continuation payload table)". Owner pipeline direction: any number of
interpreters can attach to dao.stream, so program streams are multi-writer by design.
Evidence already gathered: your mob r1/r2 findings (forged closure incl. forged :yin.k/store-of accepted; forged
continuation redirects control); continuation invocation (8f9f90b0); D4 host-typed effects (9a69e58f: deftype Effect,
trusted constructor, plain data on streams); cells (sealed :cell-ref); heap reclamation (60b60898: gc-roots/gc-children
seam — "any host-typed VM value must expose its ref-bearing children as data"); the spike no longer tests continuation
representation (flag cells). Read the gemini reclamation gate's accepted concerns 1-2 (guest-forged kernel shapes; host
closures/lazy seqs opaque to trace): collab/1790836430996-reviewer-heap-reclamation-gate.gemini-3.1-pro-high.findings.md.

Design questions:
Q1. Representation per VM: closures (walker {:type :closure ...}, semantic, stack, register shapes) and continuations
    (:reified-continuation, :parked-continuation) as host types minted only by the VM; how apply, invoke, park/resume,
    lift/lower (UCF encoder/decoder), completion, gc-children and printing change. One deftype per kind or per VM?
Q2. Portability CLJ/CLJS/CLJD (lessons: D4 deftype ILookup worked; CLJD protocol this-casts; reader conditionals).
Q3. Qualified refusals (D6): malformed or foreign closure/continuation values at application/invocation/resume fail with
    one qualified vocabulary (unify with D4's :yin.k/status and the cells' :reason shape?).
Q4. Task ownership: a host-typed value carrying the minting task's identity; refusing a closure/continuation from another
    task (e.g. passed raw over a stream) vs the encoded lift path; interaction with copy-on-lift (slice 2) and modules.
Q5. Equality and identity: = on closures/continuations today is structural; what should it be? Effects on cell-ref =,
    dict keys, the spike prelude (function objects already wrap closures in cells).
Q6. Store-of / module-store forgery: closing the forged :yin.k/store-of hole specifically.
Q7. Minimal slices (D6 refusals vs D7 host types), order, acceptance tests (incl. the forgery repros), and migration
    across the four VMs; what must change in the spike prelude/lowering (if anything).
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c
Then: one recommended design; answers Q1-Q7; owner decisions; severity | file:line | evidence | correction items.
