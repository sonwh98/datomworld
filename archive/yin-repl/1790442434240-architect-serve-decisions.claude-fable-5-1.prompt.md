Created-GMT: 2026-09-26 17:07:14 GMT
Created-Local: 2026-09-27 00:07:14 +0700
Coding-Agent: claude
Session-ID: fcb393fa-700d-41ae-b1a3-2e8152ac5b60

# Task: decide dao.stream.serve section 15 under the owner invariant

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-27 00:07:14 +0700 | Status: active | Rationale: owner directive 2026-09-27: mob claude, codex, deepseek on the serve open questions; fable authored the spec, so its answer is compared against two independent families

You are a HEADLESS read-only Lead System Architect. Produce the COMPLETE deliverable in your
final response now. Do not wait for approval, do not ask questions, do not end with a plan or a
promise. Do not edit files. Do not run anything (no test suites). Cite file:line for every claim
about the document or source.

## Owner invariant for dao.stream.serve (verbatim quotes; these are FIXED constraints)

"any implementation of dao.stream can be mechanically exposed via websocket or udp and communicate p2p. client-server is just one stigmergic behavior that interpreters [implement]. It should be able to traverse a NAT. A toy example would be a string with a dao.stream wrapper that can be exposed on a websocket on a remote interpreter."

Earlier owner statements on the same design (also verbatim):
"my invariant is any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
"Q1 its not privilege . i agree with fable" (a convention some peers choose to run, such as a meeting stream, is not a privileged node); "Q2 i agree with fable" (WebSocket dialer versus acceptor is an acceptable per-channel establishment fact).

Owner delegation, verbatim: "I don't care about the details as long as my invariants are met, but if my invariants are technically impossible, let me know."
The owner will NOT decide the details. YOU (the architects) decide them. Only the invariants above bind you.

## What to decide

Read in full: /Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md (section 15
"Decisions awaiting the owner" lists eight open questions with the author's recommendations;
section 16 lists the doc edits the spec implies). It sits beside, and must stay consistent
with, under /Users/sto/workspace/datomworld/docs/design/: datom.world.md (invariants),
dao.stream.md (the contract; it wins over the spec wherever they disagree), dao.stream.ws.md,
dao.stream.discovery.md, dao.lease.md, yin.vm.universal-continuation-format.md (7.4.3, 7.5.3),
daostream-udp-design.md (superseded prior art), dao.jing.md, yin.vm.linker.md. Source it cites,
under /Users/sto/workspace/datomworld/src/cljc/dao/: stream/{rpc,ws,serving,forward}.cljc and
jing/remote*.cljc. The spec was authored by claude-fable-5-1 and reviewed by deepseek-v4-pro
and earlier by codex/GLM; you have no obligation to agree with its recommendations.

Deliver, in this order:

1. INVARIANT FEASIBILITY. For each clause of the owner invariant, say whether it is achievable
   as stated, achievable with a stated caveat, or technically impossible. Be concrete about
   NAT: which NAT combinations (full cone, restricted, port-restricted, symmetric NAT,
   CGNAT, UDP-blocking networks) allow direct P2P over UDP and over WebSocket (WebSocket is
   TCP over HTTP; a browser cannot listen), which need a third peer to relay, and whether a
   relay by a peer running a convention keeps "no server, no client, no privileged node"
   honest or quietly reintroduces a server. If any clause is impossible or only conditionally
   possible, say exactly what the owner must be told. Do not soften a real impossibility.
2. TOY EXAMPLE WALKTHROUGH. Take the owner's toy example: a string wrapped as a dao.stream
   handle, exposed over WebSocket by one interpreter, and read remotely by another. Trace it
   through the spec's mechanism end to end (serve descriptor, session, frames, proxy handle,
   cursor, gap/outcomes). Say whether the spec as written supports it mechanically with no
   special case for "string", and name any step that does not work.
3. THE EIGHT DECISIONS. For each of the eight items in section 15, in order: DECISION
   (accept the spec's recommendation, or your replacement, stated as one sentence a
   spec editor can apply), WHY (one to three sentences tied to the owner invariant or the
   contract), RISK (what breaks if you are wrong), and INVARIANT-CRITICAL? (yes if the
   decision could violate the owner invariant, no if it is a detail the owner need not
   care about). Keep to decisions; do not redesign the spec.
4. CROSS-DECISION CONFLICTS. Any two decisions that interact or contradict, and any decision
   that forces an edit in a section-16 file beyond what section 16 describes.
5. VERDICT on the whole: READY TO IMPLEMENT, READY AFTER the doc edits in section 16 are
   applied, or NOT READY (with the specific blocker).

Be terse. Tables are fine. Do not restate the spec. Do not pad. The owner reads only your
conclusions; the orchestrator will reconcile three independent answers.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above, or the provider value if it was pending>
