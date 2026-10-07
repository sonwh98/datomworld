Created-GMT: 2026-09-25 12:42:00 GMT
Created-Local: 2026-09-25 19:42:00 +0700
Coding-Agent: claude
Session-ID: resume-of-05ce85cc-7cbb-407f-b445-1e9756ad2e35

# Task: dao.stream.serve — conformance review against the owner's P2P invariant

Role: Lead System Architect (read-only; do not edit files)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-25 19:42 +0700 | Status: active | Rationale: owner directive "have fable review the design against my invariant"; resumes your own design session

## Owner invariant (verbatim quote; the standard you review against)

"any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"

## Orchestrator framing (my reading, not the owner's words; challenge it)

Your design already says roles are per-session and that a relay is any
host running relay-step. But by my count it uses "server" 27 times and
"client" 9, titles section 5 "The server", separates "ws channel, listen"
from dial in its host table, and names relay and rendezvous as protocol
roles with their own step functions and frames (:serve/register, :serve/dial,
:serve/introduce). I do not know whether those are real conflicts or only
vocabulary. That is the question. The owner's rule may also imply that a
WebSocket connection's dialer/acceptor asymmetry is a per-channel
establishment fact and never an authority; check that your design says so.

## Read first
- collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md (your design)
- collab/1790339400000-architect-dao-stream-serve-q1-q7-mob-consensus.orchestrator.findings.md
  (the codex + GLM-5.3 consensus on your Q1-Q7; it was reached WITHOUT this invariant in the brief)
- docs/design/dao.stream.ws.md (says "server-hosted stream"; lines ~9-28)
- docs/design/dao.stream.md, docs/design/dao.stream.discovery.md

## What to produce

1. Every place your design contains a server or client concept, or a
   privileged node kind (relay, rendezvous, "the serving host", listener vs
   dialer), as: severity | design line | why it conflicts or does not |
   proposed peer-symmetric restatement. Distinguish real conflicts from
   vocabulary. Say what stays as a per-channel establishment fact.
2. Whether relay and rendezvous can be expressed as ordinary peers whose
   register/dial/introduce behavior is ordinary serve-table operations over
   the same protocol, so that no frame or step function belongs to a
   privileged kind of node. If not, say precisely what would remain
   privileged and whether that violates the invariant.
3. Effect on the consensus: for each of Q1-Q7, does the invariant change
   the consensus position? Give special attention to Q3 (peer identity) and
   Q4 (relay admission). State the revised position where it changes.
4. Contradictions with existing docs under the invariant (ws.md, discovery,
   serving.cljc naming), as severity | file:line | evidence | correction.
5. The revised draft spec sections that change, as ready-to-use text.
Do not assume the consensus is right merely because two models agreed.
Where the invariant is ambiguous, list the reading you took and any owner
question it raises.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
