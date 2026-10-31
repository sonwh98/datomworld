Created-GMT: 2026-09-30 09:55:29 GMT
Created-Local: 2026-09-30 16:55:29 +07 (+0700)
Coding-Agent: claude
Session-ID: e4154ec6-1ac3-4b9a-8441-0d572d865cd5 (resumed; fable sign-off session)
# Task: DHT epic S0 — rule on the owner's new acknowledgement contract, then write the S0 contract text (docs only)
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-30 16:55:29 +07 (+0700) | Status: active | Rationale: owner routing ("use fable more ... as the architect"); your conditional sign-off makes S0 the gate; S0 is design-contract text, Architect domain

Work in /Users/sto/workspace/datomworld. DOCS ONLY: you may edit docs/design/dao.jing.dht.md and create/edit a design doc
for the raw datagram layer (e.g. docs/design/dao.stream.datagram.md — name it) and the DHT stream API; you may touch
docs/design/dao.stream.remote.md / dao.stream.md only for cross-references. NO src/ or test/ changes. Do not stage or commit.

PART A — rule on the OWNER's new acknowledgement contract (it supersedes mob D3 and your "local verdict with volatile
intent" line). Owner decisions are recorded verbatim in collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md:
  first answer: "i want to take the kafka route. write is acknowledged immediately as long as it is sent out on a
  network. this works because the data is written to a peer"; clarified ack level: "sent to a peer but it must send to
  multiple peers before it can be considered ack"; local copy: "Yes, local + network".
Design it precisely: the number of peers ("multiple" — a fixed minimum or a composition parameter, with a default and a
floor ≥ 2), what "sent" means at the raw layer (handed to the socket per the raw append! ok), behaviour with fewer
reachable/known peers than required (including solo mode and a cold bootstrap) — the write is then NOT acknowledged, and
what the caller sees instead; how this coexists with D8 (no synchronous lookup stalling a REPL round): e.g. the ack is a
stream outcome the caller observes when the runner has sent to the required peers, while the local durable insert is
immediate — reconcile with the stepped core and your F2 (time). Make the outcome vocabulary honest (the ack asserts
"sent to N peers", never "stored by N peers").

PART B — write S0: the frozen contract text incorporating your F1-F8 corrections, GLM's two ratification conditions (raw
descriptor local-bind keys vs UDP channel remote-destination keys; S3 acceptance "shared chunk utility used by both" with a
declared DHT-owned-reassembly fallback), the four owner decisions (publish opt-in allowed; the ack contract from Part A;
root discovery a separate later epic — supplied manifest address; compatibility — caller-stepped DHT + local dao.jing
byte-store adapter + optional JVM blocking facade), fix the stale sha256-only line in dao.jing.dht.md, and the final
slice plan S1-S5 with acceptance criteria per slice.

Inputs: your sign-off collab/1790761753000-architect-dao-jing-dht-epic-fable-signoff.claude-fable-5-1.findings.md; the
synthesis and ratifications named in it.

Final response (headless), beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then: Part A ruling; the files you changed/created; a map of F1-F8 + GLM conditions + owner decisions -> doc sections;
anything that must go back to the OWNER.

## Fix round 1 (2026-09-30 17:27:15 +07, orchestrator) — S0 review findings
gpt-6-sol review (collab/1790763836000-architect-dao-jing-dht-epic-s0-review.gpt-6-sol.findings.md) WITHHELD with three
findings; the orchestrator accepts all three. Docs only, same files; do not stage or commit.
1. HIGH | dao.jing.dht.md ~428 | an unproven request must get a :need-cookie reply no larger than the request, but the wire
   adds a 16-byte cookie and reply fields, so a small first-contact :ping or chunk cannot satisfy it. Specify a minimum
   padded request size, or require silence when the bounded reply would exceed the received datagram — pick one and make
   it airtight (no amplification).
2. MEDIUM | dao.jing.dht.md ~163 vs ~497 | an acknowledged store requires a fresh peer reply carrying the store's cookie,
   S2 must test acknowledged writes, but S2 omits cookies until S4. Define S2's deterministic cookie stand-in and its S4
   replacement, or move cookie issuance/use into S2 and leave MAC validation for S4.
3. MEDIUM | dao.jing.dht.md ~475/~479 | S1 owns the shared Base64 helper S2 needs, so S1 and S2 are not independent.
   Freeze an existing codec as S2's seam, or assign the helper to S0 (specified here, implemented before S1/S2 dispatch —
   say which).
Final response: begin with Completed-GMT / Completed-Local, then the change per finding with doc:line.
