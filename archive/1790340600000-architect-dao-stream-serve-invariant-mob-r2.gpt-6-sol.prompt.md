Created-GMT: 2026-09-25 13:10:00 GMT
Created-Local: 2026-09-25 20:10:00 +0700
Coding-Agent: codex
Session-ID: resume-of-01a0d870-7702-7890-bb87-299065a201b8

# Task: dao.stream.serve — invariant revision, round 2 (cross-read; Q2, Q3 and the defect list)

Role: Lead System Architect (read-only; do not edit files)

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-25 20:10 +0700 | Status: active | Rationale: owner directive; continuing to consensus

## Owner statements (verbatim; the standard)

Invariant: "any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
Rulings: "Q1 its not privilege . i agree with fable"; "Q2 i agree with fable".

## State (orchestrator's mechanical summary; verify against the files)

Both of your round-1 answers are finished, so this cross-read is parallel.
Agreed: Q4 (policy moves to the meeting peer's composition; caps and
lifecycle stay), Q1/Q5/Q7 (keep decisions, with notes), Q6 (operationally
affected: the meeting peer may retire the inbox pair while a migrated task
needs the route). Both found the punch-socket defect (punch from the same
UDP socket whose address the meeting peer observed), the request-to-UDP
attachment correlation gap, and the announcement retention/gap risk. SPLIT:
Q2 (codex: defer held reads; GLM: include in v1) and Q3 (codex: own
composition mints an opaque random id, and a public-key hash without proof of
control gives no binding; GLM: self-minted with hash-of-public-key as the
intended format, but an unauthenticated name until verification lands).

## Read first (the other model's ROUND 1 answer, verbatim)
- Codex r1: collab/1790340000000-architect-dao-stream-serve-invariant-mob-r1.gpt-6-sol.findings.md
- GLM r1:   collab/1790340000000-architect-dao-stream-serve-invariant-mob-r1.glm-5.3.findings.md
- Fable's revision: collab/1790339700000-architect-dao-stream-serve-invariant-review.claude-fable-5-1.findings.md

## What to produce

1. Q2 and Q3: open the other model's cited evidence yourself. State AGREE,
   CHANGE (a position that resolves both) or HOLD, with the decisive reason.
   For Q2 specifically: does reachability through the meeting peer make
   polling a standing cost that the existing retry budget does not bound?
   For Q3: is the disagreement only about what the "intended format" claims,
   and can one sentence resolve it?
2. Defect list: each model found defects the other did not (codex: restricted
   handles, outer-gap versus final-gap, UDP payload budget, request fairness;
   GLM: address privacy on a public announcement stream, non-idempotent
   :meet/here). For each of the other model's extras say TRUE, FALSE or
   PARTLY true with evidence, and whether it needs a spec edit.
3. Give ONE merged, de-duplicated list of the spec edits you would require,
   each as: section | edit | why. Do not concede merely to converge.

End with exactly:
CONSENSUS: <items settled>; OPEN: <items still split, or none>
(items are Q2, Q3, and "defect list")

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
