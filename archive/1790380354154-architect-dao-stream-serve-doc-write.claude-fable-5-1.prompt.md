Created-GMT: 2026-09-25 23:52:34 GMT
Created-Local: 2026-09-26 06:52:34 +0700
Coding-Agent: claude
Session-ID: resume-of-05ce85cc-7cbb-407f-b445-1e9756ad2e35

# Task: write docs/design/dao.stream.serve.md (the dao.stream serving spec)

Role: Lead System Architect (author)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-26 06:52:34 +0700 | Status: active | Rationale: owner directive "yes, have fable write it"; you authored the original design, the invariant revision and the remote.step analysis in this session

Repository: /Users/sto/workspace/datomworld (the main checkout, branch master). You may
create exactly ONE file: docs/design/dao.stream.serve.md. Edit no other file. Do not stage
or commit; the owner decides that. Write the whole document; do not stop at an outline.

## Owner statements (verbatim quotes)

"there should be a dao.stream spec on how to mechanically make any dao.stream implementation available over websocket or UDP"
"this spec must also have a solution to work behind a NAT"
"the spec should allow P2P use cases via websocket and UDP behind NAT too"
"my invariant is any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
"why isn't dao.strea.serve its own design spec in docs/design?"
"yes, have fable write it"
Edge-case rulings: "Q1 its not privilege . i agree with fable" (a convention some peers run, such as a meeting stream, is not a privileged node); "Q2 i agree with fable" (WebSocket dialer versus acceptor is an acceptable per-channel establishment fact). NOTE: that "Q2" is the dial/accept question. It is NOT about held reads.

## Orchestrator framing (my reading; challenge it)
You have already produced every input; this task is to consolidate them into ONE
self-contained, reviewable design document. Do not carry over a mistake you made in your
last answer: you wrote that held reads (:serve/hold) are something "the owner has agreed to
include". The owner has NOT ruled on held reads. The codex and GLM-5.3 mob consensus is
that :serve/hold is OPTIONAL and additive in v1, off by default, enabled per deployment,
and never a condition of meeting reachability. State that as the recommended position and
list it under "Decisions awaiting the owner".

## Inputs (all under /Users/sto/workspace/datomworld/collab/)
- 1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md (your original design and draft text)
- 1790339700000-architect-dao-stream-serve-invariant-review.claude-fable-5-1.findings.md (your P2P-invariant revision: the meeting-stream convention replaces relay and rendezvous)
- 1790339400000-architect-dao-stream-serve-q1-q7-mob-consensus.orchestrator.findings.md (Q1 to Q7 consensus, codex and GLM-5.3)
- 1790342800000-architect-dao-stream-serve-invariant-mob-consensus.orchestrator.findings.md and the codex r3 table 1790341500000-architect-dao-stream-serve-invariant-mob-r3.gpt-6-sol.findings.md (the final merged edit list: peer ids, optional holds, restricted surfaces, identity split, stable request ids, serving-boundary observation, address exposure, punch from the observed socket, outer gap, migration lifetime)
- 1790379900000-architect-jing-remote-step-vs-serve.claude-fable-5-1.findings.md (your remote.step analysis: the per-peer service convention, UDP fragmentation required before content over UDP, the retirement of the dao.jing.remote transport half)
- the contract and neighbours: docs/design/dao.stream.md, dao.stream.ws.md, dao.stream.discovery.md, dao.lease.md, yin.vm.universal-continuation-format.md (7.4.3, 7.5.3), and daostream-udp-design.md (superseded prior art)

## What the document must contain
1. A status line in the house style ("Status: design target, subordinate to dao.stream.md. That document is the contract; where the two disagree, the contract wins.") and a short statement of the problem: a served stream is the ORIGINAL logical stream with its kept cursors and gap, not a copy.
2. The serve protocol: sessions, frames, mechanical derivation of the serving side from a handle's declared surface, the proxy handle and its declared nature (deferred remote observation, with the two contract amendments the consensus produced, worded as codex's final draft), idempotence and loss, anchors across a hop.
3. Channels: the channel contract, WebSocket, UDP (with the same-socket punch rule, the nonce-bearing pong, the frame budget and the fragmentation requirement before content is claimed over UDP), and the statement that dialer versus acceptor is a per-channel establishment fact and confers no authority.
4. Reachability and NAT WITHOUT relay or rendezvous as privileged node kinds: the serve descriptor, direct, the meeting stream pair convention, hole punching, the relayed channel as a proxy to an inbox, P2P symmetry, liveness. Use the peer-symmetric vocabulary throughout: no "server" or "client" as roles, no privileged node. Where the word appears it must be a convention.
5. Peer ids (minted by the peer's own composition, 128+ random bits or the public-key-hash form, unauthenticated until verification exists), the stable request-id and dedup rules for :meet/here, the serving-boundary observation that ties a request to its session and attachment, the stated address-exposure of shared announcements, and bounded meeting work.
6. The service convention (a per-peer request and response pair for request/response services such as a jing content service) and how dao.jing.remote's transport half becomes redundant while remote.step stays.
7. Host isolation (JVM, Node, browser, Dart: raw UDP is unavailable in browsers), invariant compliance, concurrency and linearization, and an explicit split of what is contract, what is transport-owned and what is deferred.
8. UCF integration: how the exporter serves carried cells and the resumer attaches through the proxy, the put-resume rule, and one cursor profile.
9. Completion criteria as tests.
10. A section "Decisions awaiting the owner" listing every open decision with your recommendation (at minimum: held reads optional and off by default; accepting OD-1, OD-2 and OD-3 in dao.stream.md as preconditions; lease-governed served-entry lifetime; the rename of dao.stream.serving; relay admission as a composition policy).
11. A section "Edits this document implies in other files", as a list only (dao.stream.ws.md, dao.stream.md OD entries and the two definitions, UCF 7.4.3 and the cursor profile, dao.lease.md, the UDP doc superseded, dao.jing.md and yin.vm.linker.md, dao.jing.remote): describe them; do not make them.

## Format rules (docs/agents/format.md and the house style)
Markdown design document. Any table is an ASCII box table (+---+ borders, a +===+ header rule), never a markdown pipe table, at most 170 columns. Prose wraps at about 80 columns like the neighbouring design docs. Plain ASCII. Do NOT cite paths under collab/ (they are untracked working files); make the document self-contained and cite only committed docs and source. Do not mention the review process or which model wrote what.

## Report
When the file is written, return a short final response: the path, its length, the section list, the decisions you left open and any place where the inputs contradicted each other and how you resolved it. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
