Created-GMT: 2026-09-25 23:59:19 GMT
Created-Local: 2026-09-26 06:59:19 +0700
Coding-Agent: deepseek
Session-ID: 1b25b60b-b390-41a6-bf52-1f43420f0d3a

# Task: independent review of docs/design/dao.stream.serve.md

Role: Adversarial Design Reviewer and Lead System Architect

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-26 06:59:19 +0700 | Status: active | Rationale: owner directive "send the serve spec to deepseek for review"; codex is down. The document was authored by claude-fable-5-1; you are independent of its author.

You are a HEADLESS plan-mode reviewer. Produce the COMPLETE review in your final
response now. Do not wait for approval, do not ask questions, do not end with a plan
or a promise of a verdict. Read-only: do not edit files. Cite file:line (of the document
under review, and of the sources it cites). Do not run anything.

## Owner statements (verbatim quotes; the requirements the document must meet)

"there should be a dao.stream spec on how to mechanically make any dao.stream implementation available over websocket or UDP"
"this spec must also have a solution to work behind a NAT"
"the spec should allow P2P use cases via websocket and UDP behind NAT too"
"my invariant is any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
Edge-case rulings: "Q1 its not privilege . i agree with fable" (a convention some peers choose to run, such as a meeting stream, is not a privileged node); "Q2 i agree with fable" (WebSocket dialer versus acceptor is an acceptable per-channel establishment fact; this is NOT about held reads).
"send the serve spec to deepseek for review"

## What is under review
/Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md (1,085 lines, untracked,
17 sections). Read it in full. It must be consistent with the contract and its
neighbours, all under /Users/sto/workspace/datomworld/docs/design/: dao.stream.md (the
contract; it wins wherever they disagree), dao.stream.ws.md, dao.stream.discovery.md,
dao.lease.md, yin.vm.universal-continuation-format.md (sections 7.4.3 and 7.5.3),
daostream-udp-design.md (superseded prior art), and dao.jing.md. The source it cites, under
/Users/sto/workspace/datomworld/src/cljc/dao/stream/ (rpc.cljc, ws.cljc, serving.cljc,
forward.cljc) and src/cljc/dao/jing/remote*.cljc.

## Orchestrator framing (my reading; challenge it)
The document consolidates a design that went through several review rounds. Two
model reviewers (codex, GLM-5.3) reached a consensus on seven open questions and on a set
of spec edits, and the author revised it under the owner's P2P invariant. YOU have not seen
any of that; judge the document on its own merits. In particular the document states two
positions as RECOMMENDATIONS that the owner has NOT ruled on: :serve/hold (held reads)
optional, additive and off by default; and peers minting their own opaque, unauthenticated
ids. Do not treat either as settled. I have not verified the document against the contract
myself; my mechanical checks only confirm format (ASCII, box tables, no em dashes, no
paths into the untracked working directory).

## What to produce
1. INVARIANT COMPLIANCE: does the document, in vocabulary AND mechanism, avoid a server, a
   client and any privileged node? Check every place a role could hide: the door stream,
   the meeting convention, hole punching, the service convention, peer ids, admission,
   liveness. List any violation or any place the convention leaks into the protocol. Do
   the WebSocket dialer/acceptor statements stay an establishment fact with no authority?
2. CONTRACT CONSISTENCY: does it contradict dao.stream.md (operations, outcomes, cursors,
   gap, anchors, the exhaustiveness of outcomes, the no-waiting rule, the accepted or
   proposed OD entries it relies on)? Check its two proposed amendments to the definitions
   of blocked and of anchors against the contract text they change. Does a served stream
   really stay the ORIGINAL logical stream (kept cursors, the source's own gap), or does any
   mechanism quietly become a copy?
3. PROTOCOL SOUNDNESS: sessions and their ids, the eight frames, idempotent reads by
   cursor, append-through-a-proxy and the unknown-effect case, batching, held reads,
   duplicate and reordered frames, session loss and resumption, the outer-gap versus
   final-gap distinction, the double framing. Find any state that can be lost, duplicated,
   deadlocked or that requires a privileged party.
4. NAT AND P2P FEASIBILITY: is the meeting convention plus same-socket hole punching plus the
   relayed-inbox fallback actually sufficient for the cases claimed (both peers behind NAT;
   one behind NAT; symmetric NAT)? What breaks, and does the document say so honestly?
   Browser limits (no raw UDP, no listener), the 1,200-byte budget and the claim that UDP
   fragmentation is required before content is served over UDP.
5. SECURITY AND RESOURCE HONESTY: address exposure, admission and bounded work at a meeting
   peer, peer-id squatting, amplification, unauthenticated ids, what the document claims
   versus what it can guarantee. Is anything claimed that the design does not deliver?
6. THE SERVICE CONVENTION AND dao.jing.remote: the document claims the transport half of
   dao.jing.remote becomes redundant while the jing-level stepped client stays. Check that
   claim against the source (dao/jing/remote*.cljc). Is the per-peer pair convention
   sufficient for a request/response service?
7. COMPLETION CRITERIA: are the 12 tests falsifiable and sufficient for the claims? What is
   untested? SECTION 15 (decisions awaiting the owner) and SECTION 16 (edits implied in
   other files): are they complete and correctly stated? Any decision the document makes
   silently that belongs in section 15?
8. Anything internally inconsistent, unclear or overclaimed. Distinguish DEFECTS (must fix
   before the document is committed) from DEFERRED work. Your overall verdict on whether the
   document is ready to be committed as a design target.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
(meaning: whether the document is ready to commit as a design target.)
