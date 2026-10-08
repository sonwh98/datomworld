Created-GMT: 2026-09-30 09:05:18 GMT
Created-Local: 2026-09-30 16:05:18 +07 (+0700)
Coding-Agent: codex (gpt-6-sol, resumed) + agy (gemini-3.1-pro-high, resumed) now; glm (glm-5.3, resumed) after its usage cap resets
Session-ID: codex 01a0f10a-6dcd-7af1-b670-78f74255321a | agy c1020f8c-3056-4129-a680-cdc1337b844a | glm 305a156d-0f83-4b26-8ed3-600af4c94594
# Task: Architect mob — revision round: DHT on a raw datagram layer, dao.stream interfaces at both layers (OWNER DIRECTION)
Role: Lead System Architect (mob participant)
Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 16:05:18 +07 (+0700) | Status: active | Rationale: owner direction reverses mob D1/D2
- Model: gemini-3.1-pro-high | Assigned: 2026-09-30 16:05:18 +07 (+0700) | Status: active | Rationale: same (design author)
- Model: glm-5.3 | Assigned: (after GLM usage-cap reset) | Status: queued | Rationale: same

Read-only; no edits; headless. Your prior consensus positions are in
collab/1790750376000-architect-dao-jing-dht-epic-mob-consensus.{gemini-3.1-pro-high,gpt-6-sol,glm-5.3}.findings.md.

OWNER DIRECTION (verbatim, in order):
1. Owner question: "can the udp transport have the same dao.stream surface API?" — orchestrator answered: yes, today
   dao.stream.udp's attach gives writer handles (append! sends) and readers read ring streams via projection/step!, and
   dao.stream.remote builds full remote streams over it.
2. Owner: "does dao.stream.udp have its own interface other than the dao.stream interface?"
3. Owner: "not all netwroking code is stream base but datagram base. stream base are built on top of datagrams. i want to
   know if udp exposes datagram interface" — orchestrator answered: at the VALUE level yes (send-to!/send-value! =
   connectionless addressed sends of one canonical-CBOR value, fragmented over 1200 B, refused over max-message-bytes;
   receive! deposits {:dao.stream.udp/attachment :dao.stream.udp/source :dao.stream.udp/value} events onto a traffic
   medium), but there is NO portable RAW datagram layer: raw bytes exist only at the host send!/receive! seam.
4. Owner DECISION: "the dht should sit on the raw datagram layer but expose a dao.stream interface"
5. Owner CLARIFICATION (verbatim selected option): "Both — A raw datagram layer exposed as dao.stream, AND a DHT whose
   own API is dao.stream-shaped, built on it."

This supersedes consensus D1 (DHT over the dao.stream.udp VALUE/fragment layer) and reopens D2, D5, D6, D10. D3, D4, D7,
D8, D9 stand unless this direction changes them (say so if it does).

Design, precisely enough to slice:
R1. The raw datagram layer: namespace (split dao.stream.udp into a raw layer + the existing value/fragment message layer
    rebuilt on top of it?), its dao.stream surface — a received-datagram stream ({bytes, source address} events? how are
    bytes represented portably), sending by append! of {bytes, destination} values or per-destination writer handles,
    descriptor shape, bounds (max datagram size, what append! answers for oversize: :dao.stream/invalid-value?), loss
    semantics, cursor/gap/retention, host seams (JVM DatagramSocket, Node dgram, CLJD RawDatagramSocket), and how
    dao.stream.udp's value layer and dao.stream.remote keep working unchanged on top of it.
R2. The DHT on raw datagrams: its own wire (message framing per datagram, RPC correlation, codec — CBOR or other for
    small Kademlia messages), how segment transfer larger than one datagram works (DHT-owned chunking vs reusing a
    shared fragmenter over the raw layer — note the owner wants the DHT on the RAW layer, not the value layer), and
    hardening (return-address cookies, rate limits, amplification bounds) at the raw layer.
R3. The DHT's dao.stream-shaped public API: which streams (e.g. a request stream of put/get/find operations and a result
    stream of outcomes; or content presented as stream handles), their value shapes and outcome vocabulary, correlation,
    and how dao.jing's existing store-handle contract (materialize!/get/close!, used by yin.repl.index and
    dao.jing.content) is satisfied — an adapter over the stream API? Keep the six invariants (no callbacks, explicit
    state, caller-stepped).
R4. Revised slice plan (S0...), concurrency, acceptance criteria, and dependencies (the durable-store epic for REPL
    integration).
R5. Items that must go to the OWNER.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then R1-R5 with file:line evidence; mark where you change your prior consensus position.
