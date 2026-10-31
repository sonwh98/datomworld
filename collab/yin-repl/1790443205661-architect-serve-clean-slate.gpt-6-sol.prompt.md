Created-GMT: 2026-09-26 17:20:05 GMT
Created-Local: 2026-09-27 00:20:05 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: simplest from-scratch design for dao.stream.serve under the owner invariant

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-27 00:20:05 +0700 | Status: active | Rationale: owner directive 2026-09-27: design from scratch, independent of existing code; mob claude, codex, deepseek. Fresh session on purpose so it is not anchored on the earlier serve-decisions round.

You are a HEADLESS read-only Lead System Architect. Produce the COMPLETE deliverable in your final
response now. Do not wait for approval, do not ask questions, do not end with a plan or a promise.
Do not edit files. Do not run anything. Cite file:line for claims about existing docs or code.

## Owner invariant for dao.stream.serve (verbatim quotes; FIXED constraints)

"any implementation of dao.stream can be mechanically exposed via websocket or udp and communicate p2p. client-server is just one stigmergic behavior that interpreters [implement]. It should be able to traverse a NAT. A toy example would be a string with a dao.stream wrapper that can be exposed on a websocket on a remote interpreter."
Earlier: "There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
Owner, latest, verbatim: "dao.stream.serve does not necessarily have to be built on dao.stream.apply which was envision before the invariant i described. there's also dao.stream.rpc . you don't have to build on top of whats there. if you can build from scratch using my invariants, what is the simplest design to meet those invariants independent of what is already built"
Owner delegation: "I don't care about the details as long as my invariants are met, but if my invariants are technically impossible, let me know." Also: this is a dev-only repo with no deployed stores; no backward compatibility is needed, clean breaks are preferred.

## The question

Design the SIMPLEST mechanism that meets the invariant, from scratch, independent of what is
already built (dao.stream.apply, dao.stream.rpc, dao.stream.ws, dao.stream.serving, the previous
serve spec are NOT a foundation; you may keep or discard any of them). Simplicity means: fewest
concepts, fewest wire frames, fewest special cases, least new machinery, and the strongest reuse of
the dao.stream contract itself. Prefer a design where a served stream is just another dao.stream
handle that happens to sit across a channel.

Read for the constraints only: /Users/sto/workspace/datomworld/docs/design/datom.world.md (the
non-negotiable invariants) and /Users/sto/workspace/datomworld/docs/design/dao.stream.md (the
contract: operations, outcomes, cursors, gap, anchors, no-waiting rule; it wins on any disagreement).
Then, ONLY to know what your design must unify or replace, skim the existing network paths (read as
little as you need): src/cljc/dao/stream/{ws,serving,forward,rpc,apply,transit,cbor}.cljc, the host
adapters src/{clj,cljs,cljd}/dao/stream/ws/, src/cljc/yin/repl/{serve,connect,driver}.cljc,
src/cljc/dao/jing/remote*.cljc, src/cljc/dao/jing/dht/node.cljc (a separate JVM-only UDP DHT), and
the previous attempt docs/design/dao.stream.serve.md (1,197 lines; use it only to see what a heavy
design looks like and what it was trying to satisfy; do not inherit from it). A prior survey (an
unverified subagent report) says the only real network transport today is the DaoStream WebSocket
with Transit-JSON text frames and a CBOR binary profile, with five host adapters, and that yin.repl,
dao.jing.remote, dao.stream.rpc.ws and the DHT are its consumers or siblings; verify what you rely on.

Deliver, in this order, tersely (aim for under 2,500 words; tables welcome; no padding):

1. THE DESIGN. State it in as few concepts as it takes. Include: what is exposed and how ("a
   dao.stream handle becomes reachable through a channel"); the wire format and the complete set of
   frames or messages (ideally very few); identity and addressing (how a peer names a stream and a
   peer); how cursors, outcomes, gap and anchors from the contract cross the channel unchanged so a
   served stream is the original stream and not a copy; what is deliberately left out. A reader
   should be able to implement it from your text.
2. INVARIANT PROOF SKETCH. For each clause of the owner invariant: how the design meets it, or where
   it cannot. Be concrete about NAT: full cone, restricted, port-restricted, symmetric, CGNAT,
   UDP-blocking; WebSocket (a browser cannot listen). Say what needs a third peer to relay, how that
   relay stays a convention and not a server or a privileged node, and what is impossible. Do not
   soften a real impossibility. Include how UDP carries values larger than one datagram (or state
   that the design narrows the claim, and why).
3. THE TOY EXAMPLE. The owner's string wrapped as a dao.stream handle, exposed over WebSocket by one
   interpreter, read by another: step through it in your design with no special case for "string".
4. CLIENT/SERVER AS CONVENTION. One short worked example of a request/response service built as a
   convention over your primitive by peers, with no protocol-level client or server.
5. FATE OF EXISTING MECHANISMS. A table: for each existing network path (ws transport and its four
   host adapters; serving; forward; rpc and rpc.ws; apply; yin.repl.serve/connect/driver and the
   daostream:ws:// URL; dao.jing.remote and remote.step/async; the UDP DHT), one word (subsumed /
   convention-over / retired / unrelated) and one sentence why.
6. COMPARISON. In 5 to 10 lines: what your design drops or simplifies relative to the previous
   1,197-line serve spec, and anything that spec had that you think is genuinely necessary and your
   design must not lose.
7. OWNER-VISIBLE ITEMS ONLY. Decisions that could break the owner invariant, and facts the owner must
   be told (impossibilities, caveats such as plaintext relays before authentication exists). Skip
   everything the owner said they do not care about.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above, or the provider value if it was pending>
