Created-GMT: 2026-09-25 12:55:00 GMT
Created-Local: 2026-09-25 19:55:00 +0700
Coding-Agent: codex
Session-ID: resume-of-01a0d870-7702-7890-bb87-299065a201b8

# Task: dao.stream.serve — review Fable's invariant revision (round 1, independent)

Role: Lead System Architect (read-only; do not edit files)

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-25 19:55 +0700 | Status: active | Rationale: owner directive "send it to codex and glm for review"

## Owner statements (verbatim quotes; the standard)

Invariant: "any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"

Rulings on the edges: (1) "Q1 its not privilege . i agree with fable" (a convention some peers choose to run, such as a meeting stream, is not a privileged node); (2) "Q2 i agree with fable" (WebSocket dialer versus acceptor is an acceptable per-channel establishment fact).

## Orchestrator framing (my reading, not the owner's words; challenge it)

You and GLM-5.3 reached consensus on Q1-Q7 (collab/1790339400000-...-mob-consensus.orchestrator.findings.md) against Fable's ORIGINAL design, which had relay and rendezvous as privileged node kinds. Fable then reviewed its own design against the invariant and revised three of your positions: Q2 (include held reads in v1), Q3 (a peer mints its own id, self-certifying as the intended format), Q4 (no relay; the meeting peer bounds the inboxes it serves). Fable authored both documents, and no other model has reviewed the revision. Review it adversarially; do not defer to it or to the earlier consensus.

## Read first
- collab/1790339700000-architect-dao-stream-serve-invariant-review.claude-fable-5-1.findings.md
  (the revision: sections 3 mechanism, 4 effect on Q1-Q7, 5 contradictions, 6 revised draft text)
- collab/1790339400000-architect-dao-stream-serve-q1-q7-mob-consensus.orchestrator.findings.md
- collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md (original design)
- docs/design/dao.stream.md, docs/design/dao.stream.discovery.md

## What to produce

1. The mechanism (section 3): is "meeting stream pair" (meet-requests write-only, meet-announcements read-only single-writer, STUN reduced to a republished fact, relayed channel as proxy-to-inbox, hole punching via serve ping/pong) sound and mechanically derivable from the dao.stream contract alone? Look for: hidden privileged behavior, fairness or amplification problems, loss and reordering over UDP, the double framing, whether "nothing privileged remains" is actually true, and anything the invariant still forbids.
2. For EACH of Q2, Q3, Q4: AGREE with Fable's revised position, or give your own, with rationale and file:line evidence. Say whether the earlier consensus position should stand instead and why.
3. Confirm Q1, Q5, Q6, Q7 are truly unaffected, or say which is affected.
4. Anything in Fable's section 5 (contradictions) or section 6 (revised text) you would change; give exact replacement wording.
Do not concede merely to converge; hold on evidence. Keep it concise; the other model will read your answer verbatim in round 2.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
