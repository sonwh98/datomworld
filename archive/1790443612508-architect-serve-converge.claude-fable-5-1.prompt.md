Created-GMT: 2026-09-26 17:26:52 GMT
Created-Local: 2026-09-27 00:26:52 +0700
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# Task: consensus round on the from-scratch dao.stream.serve design

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-27 00:26:52 +0700 | Status: active | Rationale: consensus round; resumes this model's own clean-slate session so it keeps its reasoning; sees the other two families' designs

You are a HEADLESS read-only Lead System Architect resuming your own earlier session. Produce the
COMPLETE deliverable in your final response now; no questions, no plan-without-verdict. Do not edit
files. Do not run anything. Cite file:line for claims about docs or code.

## Owner invariant (verbatim, unchanged; FIXED)
"any implementation of dao.stream can be mechanically exposed via websocket or udp and communicate p2p. client-server is just one stigmergic behavior that interpreters [implement]. It should be able to traverse a NAT. A toy example would be a string with a dao.stream wrapper that can be exposed on a websocket on a remote interpreter."
"There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
"dao.stream.serve does not necessarily have to be built on dao.stream.apply ... you don't have to build on top of whats there. if you can build from scratch using my invariants, what is the simplest design to meet those invariants independent of what is already built"
"I don't care about the details as long as my invariants are met, but if my invariants are technically impossible, let me know."

## What happened
Three architects independently designed the simplest from-scratch mechanism. Read all three:
- /Users/sto/workspace/datomworld/collab/1790443205617-architect-serve-clean-slate.claude-fable-5-1.findings.md  ("mirror and reflection": request map + verbatim outcome map, stateless serving side, reflection handle that drains and answers `blocked`, no sessions, no frames beyond request/outcome)
- /Users/sto/workspace/datomworld/collab/1790443205661-architect-serve-clean-slate.gpt-6-sol.findings.md  (two messages `call` and `result`, request-id retry, UDP fragmentation required, and a claim that the contract's no-waiting rule makes an ordinary handle across a channel impossible)
- /Users/sto/workspace/datomworld/collab/1790443205708-architect-serve-clean-slate.deepseek-v4-pro.findings.md  (proxy handle plus six frames incl. attach/opened/disclaim/ping, one channel per stream, UDP narrowed to one datagram)
Contract to check against: /Users/sto/workspace/datomworld/docs/design/dao.stream.md (it wins), and datom.world.md.

## Decide these, as a ruling each (RULING, WHY in 1-3 sentences, exact contract text that would need
## to change if any, RISK)
Q1. NO-WAITING. Does a reflection/proxy that answers `blocked` (or `transport-error` with a retry key)
    while a request is in flight, and files the answer for a later call, satisfy dao.stream.md's
    no-waiting and "the outcome true now" rules with no contract change beyond the drafted OD-1..3, or
    is the objection that a contract change or a separate asynchronous interface is required correct?
    Cite the exact contract lines. If the objection is right, state the smallest contract amendment.
Q2. UDP AND LARGE VALUES. Does "any implementation of dao.stream ... exposed via udp" require
    fragmentation in v1, or is narrowing to one datagram (large values travel as dao.jing addresses)
    consistent with the owner's invariant? If fragmentation is required, give the SMALLEST mechanism
    (no sessions, no windows if avoidable) and its frame; if narrowing is acceptable, state exactly
    what the owner must be told.
Q3. ONE DESIGN. Choose ONE baseline among the three (or name a merge) and list, element by element,
    what to adopt from each of the others and what to reject: per-stream channel vs identity in the
    request; attach/opened/disclaim/ping frames vs none; peer id in the protocol vs none; request id
    and response id; the `:more` batching and anchor piggyback; `:resend-after` loss policy vs
    nothing; the fate of dao.stream.apply (retired / subsumed / convention-over) and of rpc.
    Prefer fewer concepts unless removal breaks the owner invariant or the contract.
Q4. BLOCKERS. Any point where the merged design breaks the owner invariant, the contract, or the six
    datom.world invariants; and whether it is READY TO SPECIFY (write it up as the new
    dao.stream.serve, replacing the 1,197-line draft) or NOT READY with the specific blocker.

Finish with "CONVERGED DESIGN": the final design in at most 800 words with its complete frame/message
list, so the orchestrator can hand it to a spec writer. Be terse. Do not restate the three documents.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above, or the provider value>
