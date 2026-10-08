Created-GMT: 2026-09-26 17:34:17 GMT
Created-Local: 2026-09-27 00:34:17 +0700
Coding-Agent: deepseek
Session-ID: 7aec5601-a998-45b8-a314-235fd90bcf23

# Task: integrate dao.lease into the converged dao.stream.serve design and place the boundary

Role: Lead System Architect

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-27 00:34:17 +0700 | Status: active | Rationale: owner statement on the dao.stream boundary and dao.lease; resumes this model's own session

You are a HEADLESS read-only Lead System Architect resuming your own earlier session. Produce the
COMPLETE deliverable in your final response now; no questions. Do not edit files. Do not run anything.
Cite file:line for claims about docs or code.

## New owner statement (verbatim; FIXED constraint, in addition to the earlier invariant)
"dao.stream is an abstraction boundary. it is not a network boundary, but dao.stream.serve makes it a network boundary too. as a network resource, it will most likely need to integrate dao.lease"

The earlier owner invariant still binds (any dao.stream mechanically exposed over websocket or udp,
P2P, no server or client or privileged node, NAT traversal, string-on-a-websocket toy). The owner does
not care about details as long as the invariants hold and wants to be told if something is impossible.

## Where things stand
The three architects converged (fable, gpt-6-sol, deepseek-v4-pro) on the "mirror and reflection"
design; read the three consensus answers:
- /Users/sto/workspace/datomworld/collab/1790443612508-architect-serve-converge.claude-fable-5-1.findings.md  (has the fullest CONVERGED DESIGN)
- /Users/sto/workspace/datomworld/collab/1790443612557-architect-serve-converge.gpt-6-sol.findings.md
- /Users/sto/workspace/datomworld/collab/1790443612602-architect-serve-converge.deepseek-v4-pro.findings.md
Two of three ruled that the reflection's `blocked`-while-in-flight satisfies dao.stream.md's no-waiting
rule given a small contract rewording; gpt-6-sol dissented and wanted a pending outcome in the contract.
Read now: /Users/sto/workspace/datomworld/docs/design/dao.lease.md (the operative contract; status
"proposed design target"; it states it adds no operation to any contract and no key to any DaoStream
result map) and, as needed, dao.lease.rationale.md, src/cljc/dao/lease.cljc, and
/Users/sto/workspace/datomworld/docs/design/dao.stream.md and the retired-draft
docs/design/dao.stream.serve.md sections on lifetime (its section 15 lifetime decision, and section 13).

## Decide, as a ruling each (RULING, WHY in 1-3 sentences, RISK):
L1. WHICH NETWORK RESOURCES NEED A LEASE. Walk the converged design and list every resource that has a
    lifetime beyond one call: served table entries, relay inbox pairs on a third peer, reflection link
    state, UDP fragment-reassembly buffers, NAT mappings/hole-punch state, meeting-board postings of
    reflexive addresses, channel connections. For each: does it need a lease, who is the holder, who
    is the grantor/judge under dao.lease.md, and what is the subject. Say which need NO lease (the
    stateless mirror should need none) and why. Keep it minimal: do not lease what a channel close or
    a bounded buffer already governs.
L2. THE MECHANISM. Smallest integration. Lease facts are plain data on ordinary streams: can the lease
    negotiation for a served resource ride the same served-stream primitive (a lease stream that is
    itself exposed through the table and reached through a reflection) so that NOTHING is added to the
    request or answer shapes? State exactly where renewal travels, how a remote holder learns that its
    resource was reclaimed (what it observes through a reflection), and how lease expiry differs from
    channel loss. Say whether any new wire shape is needed; prefer none.
L3. THE BOUNDARY. dao.stream is an abstraction boundary, not a network boundary; serve adds the
    network. Rule where each amendment from the converged design belongs so that network concepts do
    not leak into the dao.stream contract: the `blocked` handle-relative rewording, the "may initiate
    transport work" sentence, OD-1's `:dao.stream/retry?`, `:dao.stream/reason :oversize`/`not-found`,
    OD-2 (append effect unknown), OD-3 (cursor portability). For each: dao.stream.md, or
    dao.stream.serve.md, or dao.lease.md. Then re-answer the earlier no-waiting question (Q1) in this
    light: with the network concerns owned by serve, is a contract amendment to dao.stream.md still
    needed at all, and does the gpt-6-sol objection still stand?
L4. DELTA. What changes in the converged design (tables, descriptors, reflection behaviour, relay
    pair, meeting board), as a diff against the fable CONVERGED DESIGN, in at most 400 words; and
    whether the design is still READY TO SPECIFY or has a blocker. Flag anything owner-visible
    (an impossibility, or a behaviour the owner must accept), and anything that makes dao.lease's
    "proposed design target" status a prerequisite blocker.

Be terse; do not restate the earlier documents.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above, or the provider value>
