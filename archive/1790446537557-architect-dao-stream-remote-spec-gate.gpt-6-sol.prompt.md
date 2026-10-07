Created-GMT: 2026-09-26 18:15:37 GMT
Created-Local: 2026-09-27 01:15:37 +0700
Coding-Agent: codex
Session-ID: 01a0debb-6229-79e1-890d-4d1e0b7d8565

# Task: independent gate of the dao.stream.remote spec set

Role: Adversarial Design Reviewer and Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-27 01:15:37 +0700 | Status: active | Rationale: independent gate (different family from the Claude author); resumes the codex thread that held the earlier dissent on the no-waiting question. Owner authorized codex in the mob ("You can use claude, codex, and deepseek models"); budget last relayed 48% on 2026-09-26 16:46, unconfirmed since.

You are a HEADLESS read-only adversarial design reviewer (Lead System Architect gate), resuming your own
earlier session. Produce the COMPLETE review in your final response now: no questions, no plan without a
verdict. Do not edit files. Do not run test suites. You may read files and run read-only git and grep.

## What is under review (written by claude-fable-5-1; you are independent of it)
The working tree at /Users/sto/workspace/datomworld (branch master, uncommitted). Review:
  git diff HEAD            (tracked files: edits and one deletion)
  plus the three NEW untracked files: docs/design/dao.stream.remote.md (725 lines), docs/design/dao.stream.middleware.md (153),
  docs/design/shibi.md (45).
The deleted file docs/design/dao.stream.serve.md (the old 1,197-line draft) is available as `git show HEAD:docs/design/dao.stream.serve.md`.
Fable's own report (untrusted): /Users/sto/workspace/datomworld/collab/1790445581642-architect-dao-stream-remote-spec.claude-fable-5-1.stdout.log
(read from the line starting "Completed-GMT:"). The orchestrator verified mechanically: line counts, the deletion, no src/test file touched,
no non-ASCII in the new files, the dao.lease.md Status line intact. Do not redo those.
Also edited by the orchestrator earlier (not fable): the Status line of docs/design/dao.lease.md and docs/orchestrator-log.md; ignore both.

## Requirements the documents must meet (owner statements, verbatim)
"any implementation of dao.stream can be mechanically exposed via websocket or udp and communicate p2p. client-server is just one stigmergic behavior that interpreters [implement]. It should be able to traverse a NAT. A toy example would be a string with a dao.stream wrapper that can be exposed on a websocket on a remote interpreter."
"There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
"dao.stream.serve does not necessarily have to be built on dao.stream.apply ... simplest design ... independent of what is already built"
"there are adhoc implementations in the system already that allows dao.stream to communicate over a network. dao.stream.serve should unifiy it"
"dao.stream is an abstraction boundary. it is not a network boundary, but dao.stream.serve makes it a network boundary too. as a network resource, it will most likely need to integrate dao.lease"
"dao.stream should have ring-like middleware to add things like encryption, authentication/authorization capability tokens, or other custom transformation"
"authorization/authentication are the job of shibi capability system which isn't even spec out yet. the design of the shibi capability system must integrate with middleware for dao.stream.serve"
"shibi capability system should be built similar to dao.space as a tuple space that emerges from two interpreters: dao.space.index and dao.space.query"
The module is named dao.stream.remote; dao.jing.remote "will be deprecated once dao.stream.remote is implemented" (the whole module).
The owner does not care about details if the invariants hold, and wants impossibilities stated.
Rulings the three architects already reached (a design departing from them must say so): a reflection that answers `blocked` while a request is in
flight satisfies the no-waiting rule; UDP fragments large messages inside the UDP channel in v1; leases only for a table entry served for a remote
party and for a relay inbox pair, carried as ordinary streams with no new wire shape; network keys belong in dao.stream.remote.md and only OD-1/2/3
plus a network-free refused row and a composed-handle sentence in dao.stream.md; middleware is a position-preserving handle wrapper with two
attachment points; a filter that drops elements is an interpreter; a capability-agnostic seam only; lease attribution needs no ShiBi.

## Review, in this order (cite file:line for every finding; distinguish defects from taste)
1. INVARIANT. Does dao.stream.remote.md, in vocabulary AND mechanism, avoid any server, client or privileged node (frames, table, mirror, relay, meeting
   board, hole punching, lease grantor, admission, the credential gate)? Are the NAT impossibilities stated honestly and completely (double symmetric NAT,
   CGNAT, UDP-blocked, browsers cannot listen, plaintext without middleware, false lease lapse)? Does the string toy work step by step with no special case?
2. CONTRACT. Compare the dao.stream.md edits against the old text and against OD-1/2/3 as drafted at HEAD: are the accepted amendments faithful, is the
   `:dao.stream/refused` row consistent in every outcome table it should appear in and absent where it should not (descriptor, close!), does the composed-handle
   sentence say what the middleware doc needs and no more, and is the contract still network-free and capability-free? Did anything else in the contract change
   or get silently dropped? Is leaving `blocked` un-reworded actually sound given the reflection?
3. THE PROTOCOL. Soundness of the mirror step, the reflection (drain, blocked, retry, filed answers, resend), request and answer shapes and key namespaces,
   the `no-surface` versus not-found/reclaimed distinction (fable says it was NOT ruled in the rounds: judge it), UDP fragmentation and reassembly bounds and
   reply-to-source rule, the pair/relay, the meeting board, idempotence and OD-2 append rules, and lease integration (subjects, renewal medium, attribution,
   expiry versus channel loss). Any place a served stream could quietly become a copy, mint a cursor, or wait.
4. MIDDLEWARE AND SEAM. Check dao.stream.middleware.md against the six datom.world.md invariants (no raw callbacks, no implicit control flow, no hidden global
   state), the position rule and the four prohibitions, the attachment points, encryption caveats, and the gate/present/verify/fold seam. Does a ShiBi built as
   index-and-query interpreters (dao.space.index and dao.space.query style) genuinely plug in without changing the seam, or is fable's "passes" claim wrong?
   Is shibi.md restricted to a stub (no capability-system design smuggled in)?
5. UNIFICATION AND dao.jing.remote. Verify the fate table's completeness yourself by grepping src/, test/ and docs for every network path (ws and its four host
   adapters, serving, forward, rpc, rpc.ws, apply, yin.repl serve/connect/driver/adapter and the daostream:ws:// URL, dao.jing.remote and remote.step/async,
   dao.jing.coordinate, yin.repl.link, yin.vm.linker M3/M4, dao.data.btree.storage hydrate-async, the UDP DHT, codecs): is any missing or misclassified? Judge the
   one decision the rounds left to fable: the new module dao.jing.content (serve-step, step, driver, async; ingress check moved to dao.jing/accept-bytes!;
   coordinate as two remote descriptors; :url form dropped): sound, minimal, and every consumer accounted for? Check dao.jing/accept-bytes! and the other named
   functions actually exist or are stated as new.
6. COMPANION EDITS. For each edited file (dao.stream.ws.md, UCF, dao.jing.md, yin.vm.linker.md, dao.data.btree.md, dao.jing.dht.md, daostream-udp-design.md,
   dao.lease.md carriage, and the five outside the list: yin.vm.debruijn.linker.md, dao.jing.cbor.md, dao.space.query.md, yin.vm.debruijn.stack.md,
   dao.jing.remote.implementation-plan.md): is every changed sentence now true, and did any edit introduce a contradiction or leave a now-false neighbour?
7. LENGTH AND SIMPLICITY. The spec is 725 lines against a target under 600 and the owner asked for the simplest design. Identify what can be cut or is
   duplicated without losing a rule. Also list anything the old draft had that the new one lost and should not have.
8. CITATIONS. Spot-check at least twelve file:line citations in the new documents against the files they cite; report any that are wrong.

End with VERDICT: APPROVE, APPROVE-WITH-FIXES, or REJECT, then a numbered MUST-FIX list (file:line, what, why) and a separate SHOULD-FIX list. Be terse.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above, or the provider value>
