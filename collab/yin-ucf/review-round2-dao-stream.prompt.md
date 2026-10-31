Created-GMT: 2026-08-31 05:40:00 GMT
Created-Local: 2026-08-31 12:40:00 +07

# Role: Adversarial design reviewer

Read-only review of three revised design documents:

- docs/design/dao.stream.md            (the contract — authority)
- docs/design/dao.stream.ws.md         (subordinate transport spec)
- docs/design/dao.stream.implementation-plan.md   (subordinate, transient)

Their governing authority is docs/design/datom.world.md (four foundational
axioms, six non-negotiable invariants). Read it first.

## What just changed, and what you are checking

These documents have now been through two review rounds:

- `collab/review-dao-stream-plan.gpt-5.6-sol.stdout.log` — 8 findings on the
  plan.
- `collab/architect-dao-stream-review.claude-fable-5.stdout.log` — 9 findings
  on all three documents, plus an outcome-subsetting proposal.
- `collab/adjudicate-architect-findings.gpt-5.6-sol.stdout.log` — cross-family
  adjudication of those 9 (verdicts: 1-3 CONFIRM, 4-5 REFINE, 6 CONFIRM,
  7 REFUTE, 8-9 CONFIRM), plus three items both reviews missed (A: three
  operations lacked stated outcome sets; B: `forward!` is not total over the
  outcome algebra; C: lifecycle events had no normative data shape).

All confirmed findings have since been applied. Your job is to review the
**current text**, not the history. Specifically:

1. Are the applied corrections actually correct, and did any of them introduce
   a new contradiction, ambiguity, or unintended widening of the public
   surface? The substantive changes were:
   - `append!` gained `:dao.stream/transport-error`.
   - `append!`'s `ok` became handle-relative ("the sequence this handle's
     writer surface is on") rather than naming the logical stream.
   - A ninth invariant: "No operation waits."
   - Outcome sets declared **exhaustive** (a transport may produce a subset),
     with three permitted reasons for excluding an outcome, stated in Surfaces.
   - Stated outcome sets for `descriptor`, `cursor`, `close!`.
   - Host Dispatch entries declared host-composed closures.
   - ws: required `:dao.stream/attachment`; a specified deposited-event
     envelope; deposit-medium granularity as composition policy; a new
     Handshake section owning the wire vocabulary.
   - plan: server-side connection handle, transport constructor, `forward!`
     totality requirement, narrowed serving composition, identity tests.

2. What remains wrong or unstated? Three rounds of review have passed over
   this text; assume the obvious defects are gone and look for what survives
   repeated reading — in particular contradictions **between** the three
   documents, and places where a subordinate document assumes something the
   contract never grants.

3. Judge the handle-relative `append!` change hardest. It is a deliberate
   semantic relaxation: `ok` no longer promises a position in the logical
   stream a descriptor names. Is that sound, is it the minimal relaxation, and
   does anything else in either document still depend on the stronger reading?

## Constraints

- Precedence: the contract wins over the ws spec and the plan.
- Deliberate deferrals, not findings: Shibi, the readiness/waiter extension, ws
  resumption protocol, cursor serialization across hosts, live ring buffer
  resize, the exact descriptor key set, consumer migration.
- Do not edit files. Do not re-run tests; there is no code yet.

Report: severity | file:line | invariant or evidence | recommended correction.
Rank most severe first. State plainly if you find nothing at a given severity.
