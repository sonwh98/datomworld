Created-GMT: 2026-09-26 17:59:41 GMT
Created-Local: 2026-09-27 00:59:41 +0700
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# Task: write the dao.stream.remote spec and companion docs

Role: Lead System Architect (author)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-27 00:59:41 +0700 | Status: active | Rationale: owner authorization "yes, have fable write the spec"; resumes fable's own session, which holds the design history. Reviewer for the result: gpt-6-sol (different family), budget permitting.

You are the Lead System Architect and you are WRITING (not reviewing). You have edit permission
for the files listed under OWNERSHIP only. Work in /Users/sto/workspace/datomworld (the main tree; you are
the only editor). Do not stage, commit, branch or run any test suite. You resume your own session: you
already hold the whole design history. This brief lists the rulings so nothing depends on memory.

## Owner authorization (verbatim)
"yes, have fable write the spec"  (answering: write the new dao.stream.remote spec replacing the 1,197-line draft, plus dao.stream.middleware.md, the dao.stream.md amendments, the companion-doc edits, and a ShiBi stub; codex gates; no commit until review).

## Owner statements that bind the spec (verbatim)
1. "any implementation of dao.stream can be mechanically exposed via websocket or udp and communicate p2p. client-server is just one stigmergic behavior that interpreters [implement]. It should be able to traverse a NAT. A toy example would be a string with a dao.stream wrapper that can be exposed on a websocket on a remote interpreter."
2. "There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
3. "dao.stream.serve does not necessarily have to be built on dao.stream.apply ... you don't have to build on top of whats there. ... the simplest design to meet those invariants independent of what is already built"
4. "there are adhoc implementations in the system already that allows dao.stream to communicate over a network. dao.stream.serve should unifiy it"
5. "dao.stream is an abstraction boundary. it is not a network boundary, but dao.stream.serve makes it a network boundary too. as a network resource, it will most likely need to integrate dao.lease"
6. "dao.stream should have ring-like middleware to add things like encryption, authentication/authorization capability tokens, or other custom transformation"
7. "authorization/authentication are the job of shibi capability system which isn't even spec out yet. the design of the shibi capability system must integrate with middleware for dao.stream.serve"
8. "shibi capability system should be built similar to dao.space as a tuple space that emerges from two interpreters: dao.space.index and dao.space.query"
9. "use dao.stream.remote." (the module is named dao.stream.remote, not dao.stream.serve). Then: "oops i remembered wrong. it was dao.jing.remote which will be deprecated once dao.stream.remote is implemented" (the WHOLE of dao.jing.remote is deprecated once dao.stream.remote is implemented).
10. "I don't care about the details as long as my invariants are met, but if my invariants are technically impossible, let me know." Dev-only repo: no backward compatibility, clean breaks.

## Inputs (read; they are the rulings)
Your own converged design and the three later rounds, all under /Users/sto/workspace/datomworld/collab/:
- 1790443612508-architect-serve-converge.claude-fable-5-1.findings.md (CONVERGED DESIGN: mirror and reflection)
- 1790444057647-architect-serve-lease.claude-fable-5-1.findings.md (+ .gpt-6-sol. and .deepseek-v4-pro. lease findings)
- 1790444750726-architect-stream-middleware.claude-fable-5-1.findings.md (+ gpt-6-sol, deepseek)
- 1790445022693-architect-stream-shibi-seam.claude-fable-5-1.findings.md (+ deepseek); the name rulings are in owner statement 9
- the earlier rounds if you need them: 1790442434240-architect-serve-decisions.* and 1790443205*-architect-serve-clean-slate.*
Majority rulings already reached (do not re-litigate; record them as decided): a reflection answering
`blocked` while a request is in flight satisfies the no-waiting rule (fable, deepseek, and codex after the
boundary point); UDP fragments large messages inside the UDP channel in v1 (fable, codex); leases only for
a table entry served for a remote party and for a relay inbox pair, carried as ordinary streams with no new
wire shape; network keys belong in dao.stream.remote.md, only OD-1/2/3 (and a network-free refused row
and composed-handle sentence) in dao.stream.md; middleware is a position-preserving handle wrapper, with two
attachment points; a filter that drops elements is an interpreter, not middleware; a capability-agnostic
seam only (opaque credential slot, mirror-side gate with pure verify and fold over explicit source streams,
reflection-side present, a capability-free `:dao.stream/refused` outcome); lease attribution needs no ShiBi.
You settle, in the spec, the small open choices the rounds left: whether meeting-board postings carry a
lease id; what a reclaimed entry answers (fable: `not-found` plus a reflection-marks-gone rule under the
`:dao.stream.remote/reason` key); where OD-1's retry key is declared; optional `:more` batching and anchor
piggyback; whether the credential key is `:dao.stream/credential`; the fate of dao.stream.apply.

## OWNERSHIP (the only files you may create or edit)
Create:
- docs/design/dao.stream.remote.md  (THE spec; replaces dao.stream.serve.md; keep it as short as the design
  allows, target under 600 lines; every sentence a rule; a one-line status header saying not implemented)
- docs/design/dao.stream.middleware.md
- docs/design/shibi.md  (STUB, one page: it is a capability system, unspecified, a tuple space emerging from
  dao.space.index-like and dao.space.query-like interpreters; it integrates through the middleware seam;
  it must supply a verify, a present, a fold with its source streams and a reason vocabulary; the
  capability-versus-currency question in dao.stream.discovery.md is unresolved and blocks it being
  load-bearing. Do not design ShiBi.)
Edit:
- docs/design/dao.stream.md: accept OD-1 (write fallback corrected to "effect unknown; no automatic retry";
  retry key), OD-2, OD-3 decision 2 (and (a)'s endpoint sentence) by moving their text into the sections
  they amend and removing them from the open-decision list; add the `:dao.stream/refused` row under OD-1's
  rule on cursor/next/append! (not descriptor or close!), the composed-handle sentence, and only the
  `blocked` handle-relative reword if you decide it is needed; add nothing network-specific and never the word
  "capability". Keep every other part of the contract unchanged.
- The section-16 companion edits, retargeted to dao.stream.remote and to the deprecation of ALL of
  dao.jing.remote: docs/design/dao.stream.ws.md, docs/design/yin.vm.universal-continuation-format.md (7.4.3,
  7.5.3, the put resume rule, `:yin.k/cursor-profiles` naming `:dao.stream.remote/v1`, the facade acceptance
  row), docs/design/dao.lease.md (ADD only the carriage sentence and naming served entries and inbox pairs as
  lease subjects; the orchestrator already edited the Status line at the top, leave it exactly as is),
  docs/design/daostream-udp-design.md (mark superseded prior art), docs/design/dao.jing.md,
  docs/design/yin.vm.linker.md (6.1 remote-content row; M3/M4 no longer "over dao.jing.remote.step"),
  docs/design/dao.data.btree.md (5.4 remote row), docs/design/dao.jing.dht.md (NAT limitation cites the new spec).
  Any other doc under docs/design/ that describes a network path you now unify: edit only the sentences that
  become false, and list each one.
Delete: docs/design/dao.stream.serve.md once dao.stream.remote.md is written, and only that file, with a
single `rm docs/design/dao.stream.serve.md` (git keeps it in history at 71f3fb93).
DO NOT touch: any source or test file (this round is documents only; renaming src/cljc/dao/stream/serving.cljc and
retiring src/cljc/dao/jing/remote.cljc are implementation slices you only DESCRIBE), docs/orchestrator-log.md,
docs/agents/*, collab/, or anything not listed. Do not fix unrelated problems; list them.

## What the dao.stream.remote.md spec must contain (use these headings or better)
1. Status, invariant and non-goals. State the owner invariant verbatim as the governing requirement and
   the impossibilities plainly: two peers that are both unreachable (double symmetric NAT or CGNAT, two
   browsers) need a third reachable peer that runs ordinary code with no protocol role; a browser cannot
   listen; everything is plaintext and unauthenticated unless middleware is composed; UDP messages are
   bounded by a composed maximum; a reclaimed lease means gone, not disconnected; false lapse under
   partition is possible.
2. The design (the mirror and reflection): channel, table, mirror step, reflection; the request, answer and
   UDP fragment shapes and their keys under the `:dao.stream.remote/...` and `:dao.stream/...` namespaces you
   choose; the remote descriptor `{:dao.stream/type :dao.stream/remote ...}`; how cursors, anchors, gap and
   outcomes cross verbatim (the served stream is the original); the reflection's per-operation behaviour
   including `blocked`, retry, not-found/reclaimed, refused; loss and resend policy; no sessions.
3. Channels: WebSocket (per-host adapters; dialer versus acceptor is an establishment fact), UDP
   (datagram budget, fragmentation, reassembly bounds, reply-to-source-address, same-socket), pair (relay).
4. Reachability and NAT, with the case table, hole-punching as a convention over a meeting stream, relay as a
   convention, browser rule.
5. Conventions that are NOT protocol: request/response service (with the string toy and a worked
   client/server example), meeting board, relay pair, peer names.
6. Lease integration: which resources, holder and judge, the two conventional lease streams, the per-lease
   renewal medium and attribution, reclaim observed as not-found, expiry versus channel loss.
7. Middleware attachment points and the ShiBi seam (cite dao.stream.middleware.md for the mechanism). State
   the seam so a ShiBi built as index-and-query interpreters plugs in without changing it, and say explicitly if
   the seam as designed CANNOT host that; that would be a blocker to report to the owner.
8. Unification: a fate table for every existing network mechanism (ws transport and its four host adapters;
   serving; forward; rpc and rpc.ws; apply; yin.repl.serve/connect/driver/adapter and the daostream:ws://
   URL; ALL of dao.jing.remote including remote.step and remote.async, dao.jing.coordinate, yin.repl.link,
   yin.vm.linker M3/M4 and dao.data.btree.storage hydrate-async; the UDP DHT; codecs), each subsumed /
   convention-over / retired / unrelated with one sentence, PLUS what replaces the stepped content client and the
   shared ingress check now that dao.jing.remote is deprecated whole (you rule; likely content as a
   request/response convention over remote streams with the ingress check kept in one shared function; the
   JVM blocking driver must survive as host policy for its callers or its replacement is named).
   Verify every consumer in the tree by grep before you write the table; do not rely on the earlier
   unverified survey.
9. Completion criteria and implementation slices (each slice: files, host lanes, what proves it), so the
   orchestrator can dispatch engineers. Order them; name what is deferred (authentication, key
   distribution, WebRTC, browser inbound, ShiBi).
10. Contract amendments and companion edits (a list of what changed in other docs).

## Constraints on the writing
Read docs/agents/format.md and follow it (ASCII, box tables as in dao.stream.md, no em dashes, no links into
untracked files or collab/; do not cite collab/ artifacts in the design docs). Match dao.stream.md's voice
(rules, not narrative). The six datom.world.md invariants bind. Do not use the words server or client for
protocol roles (only for conventions and for the dialer/acceptor establishment fact). Verify every citation
you write against the file you cite. Run no build. Use single, simple commands (the harness denies chained
shell commands); prefer the Read, Grep, Glob, Edit and Write tools.

## Final response
Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above>
Then: (1) files created, edited and deleted, with a line count for each new file; (2) the small choices you
settled and any place you departed from a ruling above (and why); (3) contradictions or errors you found in
existing docs and did not fix; (4) anything owner-visible or blocking, especially the ShiBi-seam check and the
dao.jing.remote replacement; (5) the mechanical checks you ran (ASCII, no em dashes, citations verified) and
what you did not verify; (6) whether the set is ready for an independent gate.
