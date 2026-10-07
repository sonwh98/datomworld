Created-GMT: 2026-09-01 09:19:13 GMT
Created-Local: 2026-09-01 16:19:13 Asia/Ho_Chi_Minh

# Role: Lead Systems Architecture Reviewer — round 1 of a team review

## Scope, strictly

Review exactly these three files, which are the staged change under review:

- docs/design/dao.stream.md                        — the contract (authority of the three)
- docs/design/dao.stream.ws.md                     — WebSocket transport spec, subordinate
- docs/design/dao.stream.implementation-plan.md — migration plan, subordinate, transient

Also read, as the governing authority only:

- docs/design/datom.world.md — four foundational axioms, six non-negotiable invariants

**Read no source code.** Not `src/`, not `test/`. This is a design review of
documents, and an implementation cannot be evidence for or against them.

Do not read ADR 0003, the `dao.space` documents, or any previous review log.
The ws spec cites ADR 0003 in one place; treat that citation as unevaluable
from this scope and say so if it matters to a finding. Do not go find it.

Precedence: datom.world.md > dao.stream.md > {ws spec, plan}.

## This is a team review

Two other reviewers are working the same brief independently right now. In a
second round you will each see the others' findings and be asked to converge —
to say which of their findings you accept, which you reject and why, and which
of your own you withdraw. Write accordingly:

- Make each finding **falsifiable**: state the claim so another reviewer can
  check it against the text and disagree concretely.
- Mark each with your **confidence** (high / medium / low) and say what would
  change your mind. A finding you would abandon under mild pushback should be
  marked as such rather than argued at full strength.
- Prefer findings another reviewer could not trivially reproduce. Three lists
  of the same obvious defect is a wasted round.

## What to evaluate

1. **The contract against the authority.** The six non-negotiable invariants
   and the four axioms. The contract states ten invariants of its own and
   claims every clause below them derives from those ten. Test that claim.
2. **Internal coherence of the contract.** Sections against each other:
   outcome tables against the prose qualifying them, the IO Model's "no
   operation waits" against every operation, handle-relative semantics against
   the Concurrency and Writing sections, the exhaustive-outcome-set rule
   against the transports described.
3. **Whether the subordinate documents conform.** Both directions: a
   subordinate overreaching the contract, and a subordinate relying on a
   guarantee the contract never actually grants.
4. **Whether the plan's decomposition is sound.** It splits Phase 4 into a
   transport, a generic forwarding interpreter, a serving composition, and a
   flow-control deliverable. Are those the right seams? Is anything in one
   layer that belongs in another?
5. **Completeness.** Someone holding only these three documents must be able to
   build the transport, a conforming reader transport, and a conformance suite.
   Name what they would have to invent.

## Output

Findings ranked most severe first: severity | file:line | the claim |
confidence | the exact correction you propose.

Then a short section: **the three things you are least sure of**, which is what
the second round will focus on.

Be decisive and specific. State plainly if you find nothing at a given
severity. Read-only; do not edit files.
