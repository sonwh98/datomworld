Created-GMT: 2026-09-26 18:34:22 GMT
Created-Local: 2026-09-27 01:34:22 +0700
Coding-Agent: glm
Session-ID: 6d228259-1dbf-4494-8b1f-25b5c5c8e6d3

# Task: fix round for the dao.stream.remote spec set after the gpt-6-sol REJECT

Role: Lead System Architect (fixer)

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-27 01:34:22 +0700 | Status: active | Rationale: owner directives "go ahead with the fix round" and "route architectural role to GPT-6.0-sol and GLM-5.3"; the author was fable (Claude) and the gate was gpt-6-sol, so glm-5.3 is independent of both; the re-gate goes to gpt-6-sol. Fresh session (the GLM reviewer session stays a reviewer).

You are the Lead System Architect and you are REVISING documents (fix round). You have edit permission for the
files under OWNERSHIP only. Work in /Users/sto/workspace/datomworld (main tree; you are the only editor now). Do not
stage, commit, branch or run test suites. You have no memory of the earlier design rounds; this brief and the files it
names are your whole context.

## Owner authorization and routing (verbatim)
"go ahead with the fix round"
"route architectural role to GPT-6.0-sol and GLM-5.3"
The spec set below was AUTHORED by claude-fable-5-1 and REJECTED by an independent gate (gpt-6-sol). You are the fixer. Your work
will be re-gated by gpt-6-sol, so do not weaken a finding to make it pass; if you disagree with a finding, say so with evidence.

## Owner statements that bind the design (verbatim; unchanged)
1. "any implementation of dao.stream can be mechanically exposed via websocket or udp and communicate p2p. client-server is just one stigmergic behavior that interpreters [implement]. It should be able to traverse a NAT. A toy example would be a string with a dao.stream wrapper that can be exposed on a websocket on a remote interpreter."
2. "There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"
3. "dao.stream.serve does not necessarily have to be built on dao.stream.apply ... the simplest design to meet those invariants independent of what is already built"
4. "there are adhoc implementations in the system already that allows dao.stream to communicate over a network. dao.stream.serve should unifiy it"
5. "dao.stream is an abstraction boundary. it is not a network boundary, but dao.stream.serve makes it a network boundary too. as a network resource, it will most likely need to integrate dao.lease"
6. "dao.stream should have ring-like middleware to add things like encryption, authentication/authorization capability tokens, or other custom transformation"
7. "authorization/authentication are the job of shibi capability system which isn't even spec out yet. the design of the shibi capability system must integrate with middleware for dao.stream.serve"
8. "shibi capability system should be built similar to dao.space as a tuple space that emerges from two interpreters: dao.space.index and dao.space.query"
9. The module is named dao.stream.remote; the ShiBi stub is docs/design/dao.shibi.md; dao.jing.remote "will be deprecated once dao.stream.remote is implemented" (the whole module).
10. "I don't care about the details as long as my invariants are met, but if my invariants are technically impossible, let me know." Dev-only repo, no backward compatibility.
Rulings already reached by three architects (keep unless a finding proves them wrong; if so, say so): a reflection answering `blocked` while a request is in flight satisfies
the no-waiting rule; UDP fragments large messages inside the UDP channel in v1; leases only for a table entry served for a remote party and for a relay inbox pair, carried as
ordinary streams with no new wire shape; network keys belong in dao.stream.remote.md, only OD-1/2/3 plus a network-free refused row and a composed-handle sentence in dao.stream.md;
middleware is a position-preserving handle wrapper with two attachment points; a filter that drops elements is an interpreter; a capability-agnostic seam only; lease attribution needs no ShiBi.
No new wire frames beyond request, answer and UDP fragment unless a finding forces one; if one does, flag it as OWNER-VISIBLE.

## What to read
- The gate verdict you are fixing (untrusted only in the sense that you must verify each claim against the tree): /Users/sto/workspace/datomworld/collab/1790446537557-architect-dao-stream-remote-spec-gate.gpt-6-sol.findings.md (REJECT; MUST-FIX 1-8, SHOULD-FIX 1-4; its line numbers are of the tree as it stood; re-locate by text).
- A second independent review, if the file exists when you start (a GLM review of unification completeness, companion edits, length and citations): /Users/sto/workspace/datomworld/collab/1790447123416-reviewer-dao-stream-remote-spec-gate2.glm-5.3.findings.md. Treat its MUST-FIX items as additional must-fix; if it does not exist, ignore this line.
- The author's report: /Users/sto/workspace/datomworld/collab/1790445581642-architect-dao-stream-remote-spec.claude-fable-5-1.stdout.log (from the line "Completed-GMT:").
- The documents: docs/design/dao.stream.remote.md, dao.stream.middleware.md, dao.shibi.md, dao.stream.md, dao.stream.ws.md, dao.jing.cbor.md, dao.jing.md, and the source they cite (src/cljc/dao/stream.cljc, src/cljc/dao/stream/ws.cljc, src/cljc/dao/jing/remote.cljc, dao.jing/coordinate.cljc) plus docs/design/datom.world.md (six invariants) and docs/agents/format.md.

## OWNERSHIP (only these)
Edit: docs/design/dao.stream.remote.md, docs/design/dao.stream.middleware.md, docs/design/dao.shibi.md, docs/design/dao.stream.md, docs/design/dao.jing.cbor.md, and any other file under
docs/design/ that the gate or the GLM review identifies as now false because of what you change (list each one).
Create: docs/design/dao.stream.remote.implementation-plan.md (see SHOULD-FIX 3).
DO NOT touch: any source or test file (a finding that needs a source change, such as adding :dao.stream/refused to the closed outcome sets in src/cljc/dao/stream.cljc, is DESCRIBED as an
implementation slice, not made), docs/orchestrator-log.md, docs/agents/*, collab/, and do not delete anything.

## The fixes (verify each claim against the tree first; fix, or dispute with evidence)
MUST-FIX 1. The protocol error surface. The mirror answers `:dao.stream/not-found` for a missing identity, gone resource, or unsupported op, but the contract's outcome sets are closed per operation
  (descriptor permits only `ok`; cursor, next, append! have their own), and a handle cannot answer an operation it does not declare. Define a protocol-level error distinct from the source's
  operation outcome map, and use it consistently for missing identity, reclaimed entry and unsupported surface (a key on the answer, for example, so the answer shape stays one shape). Define the mapping
  from operations to declared surfaces (cursor and next need :reader; append! needs :writer; descriptor is always available; state close!). Fix surface discovery: the descriptor carries no surface yet the
  reflection claims to learn it from the attach probe; put the surface where it is actually carried (the descriptor answer, or a descriptor field) and make the reflection's behaviour follow. Reconcile the
  reflection's `transport-error` with reason keys accordingly. Keep the lease reclaim observation ("gone, not disconnected") intact.
MUST-FIX 2. The WebSocket projection. The existing WebSocket writer encodes `{:ws/frame :ws/value :ws/value value}` and the host adapters deposit `:ws/payload`, lifecycle and resolution events onto a stream (see
  src/cljc/dao/stream/ws.cljc and docs/design/dao.stream.ws.md), while the mirror expects raw request maps. Specify the channel adapter/projection that unwraps values, correlates attachments and turns lifecycle events
  into the reflection's channel-loss observations, so the string toy composes on WebSocket. Say what is reused from dao.stream.ws and what is new. Keep it as small as the design allows.
MUST-FIX 3. Anchor piggyback calls `cursor` on the table handle directly, bypassing middleware and the reader surface. Either route anchors through the declared reader path and middleware, or drop the piggyback
  (the simpler option; state the cost). You decide; state which and why.
MUST-FIX 4. The gate versus the middleware prohibitions, and the ShiBi fit. dao.stream.middleware.md prohibits a transform from looping, then lets `gate` drain source streams inside a call, and gives a pure
  function no place to keep an advancing cursor. Resolve it honestly. One direction the gate reviewer suggested: ShiBi-like index and query interpreters run as separate interpreters and PUBLISH a decision or
  snapshot stream; the gate reads an already-composed decision input through ordinary nonblocking operations under a defined contract, so the gate stays a simple synchronous check and the fold/index work lives
  in an interpreter (which is what the owner's tuple-space direction says). Choose your own resolution if better; it must satisfy datom.world.md's six invariants, the four prohibitions as amended, and the owner
  statement that ShiBi emerges from index and query interpreters. State plainly whether the seam can host such a ShiBi; if only with a stated limitation, say the limitation. The prior author's claim that the seam
  "passes" is disputed by the gate: do not carry it forward unless you can show why.
MUST-FIX 5. Encryption. Value-level middleware does not hide values or metadata from a relay that carries the operation result. Either specify the channel-level wrapper (its attachment interface, and its
  ordering against codec, fragmentation and framing: for example encode, then encrypt, then fragment) or state that remote channel encryption is deferred and remove the overclaim. Make the owner-visible caveat exact.
MUST-FIX 6. docs/design/dao.stream.md, the OD-3 sentence about a cursor outliving its stream ("not-found at attach! or cursor-mismatch at next"): attach! takes no cursor. Correct the wording (attachment to a
  missing stream is a separate case from a stale cursor) without changing any other contract text.
MUST-FIX 7. docs/design/dao.jing.cbor.md framing claim ("Base64-inside-Transit" / Transit-only framing) is false for the codec-neutral remote path: message boundaries come from the channel codec, payload bytes are
  Base64 in the application value. Correct it and any sibling sentence in dao.stream.remote.md.
MUST-FIX 8. dao.stream.remote.md says only three contract decisions are accepted while its own amendment inventory lists more (the refused row, the composed-handle sentence, the OD-1 retry key). Make the count and
  scope consistent everywhere they appear.
SHOULD-FIX 1. UDP: state whether the 1200-byte budget covers the whole encoded fragment including metadata, and key reassembly on channel or socket identity as well as the message id.
SHOULD-FIX 2. NAT: describe the traversal cases by endpoint-dependent mapping and filtering behaviour (CGNAT is address sharing, not one behaviour, and some CGNAT paths can hole-punch); state that peers need an outbound
  path to a reachable WebSocket peer or relay, and that if UDP is blocked and no such path exists, communication is impossible.
SHOULD-FIX 3. Length. The spec is 725 lines against a target under 600 lines. Move the dao.jing.content migration design (the current section 8 detail beyond the fate table), the completion criteria and slices, and
  the companion-edit inventory into the NEW docs/design/dao.stream.remote.implementation-plan.md; consolidate the repeated relay and reachability explanation; leave in the spec only rules. Do not drop a rule.
SHOULD-FIX 4. src/cljc/dao/stream.cljc closed outcome sets lack :dao.stream/refused: record it as an implementation slice in the plan (do not edit the source).
If the GLM review file exists, add every MUST-FIX it lists and any SHOULD-FIX that is cheap. Do not fix unrelated problems; list them.

## Constraints on the writing
Follow docs/agents/format.md (ASCII, box tables as in dao.stream.md, no em dashes, no links into untracked files or collab/). Rules, not narrative. Do not use the words server or client for protocol roles.
Verify every citation you write against the file you cite. Run no build. Use single, simple commands (the harness denies chained shell commands); prefer Read, Grep, Glob, Edit and Write.

## Final response
Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above>
Then: (1) a table with one row per finding (MUST-FIX 1-8, SHOULD-FIX 1-4, and any GLM must-fix): FIXED / DISPUTED (evidence) / DEFERRED (why), with the file and section changed; (2) the design choices you made and any
that touch a ruling above or an owner statement (OWNER-VISIBLE); (3) files created, edited, with new line counts; (4) new contradictions found and not fixed; (5) mechanical checks you ran and what you did not verify; (6) ready for re-gate?
