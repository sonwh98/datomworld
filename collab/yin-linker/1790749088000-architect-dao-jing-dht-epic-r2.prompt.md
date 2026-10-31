Created-GMT: 2026-09-30 06:39:11 GMT
Created-Local: 2026-09-30 13:39:11 +07 (+0700)
Coding-Agent: agy
Session-ID: c1020f8c-3056-4129-a680-cdc1337b844a (resumed)
# Task: DHT epic design round 2 — acceptance criteria per slice and outbox durability
Role: Lead System Architect
Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-30 13:39:11 +07 (+0700) | Status: active | Rationale: same conversation; round 1 lacked implementable acceptance criteria

Read-only; do not edit files; your final response is the deliverable. Your round-1 design is at
collab/1790749088000-architect-dao-jing-dht-epic.gemini-3.1-pro-high.findings.md. Do NOT implement anything (the
orchestrator dispatches implementation separately). Round 1 is not yet implementable; complete it:

1. Outbox durability contradiction: you define a put acknowledgement as "durably stored locally and queued for
   replication" but place the outbox in a dao.stream.memory-log, which a crash loses. Rule precisely: is the outbox
   durable (where, how recovered on restart, how duplicates are handled — replication is idempotent by content), or is
   replication explicitly best-effort-after-ack with loss on crash (then restate the ack contract)? How is the runner
   driven (explicit step, no hidden threads/callbacks per the invariants), and what happens with an empty bootstrap
   ("queues or drops" — pick one)?
2. For each of slices 1-5: exact files, public API changes (names/signatures), wire format changes (CBOR message shape,
   fragment envelope fields, limits), and ACCEPTANCE TESTS as concrete bullet cases (inputs -> expected outcomes),
   including failure cases (lost fragment, oversize, hash mismatch, peer timeout, rate-limit hit, returnability failure,
   crash/restart with a pending outbox), and which host lanes (CLJ/CLJS/CLJD) each test must pass on.
3. Returnability cookies: the exact challenge-response exchange (messages, what is cached, expiry) and which ops require
   it.
4. Concurrency with in-flight work: confirm slices 1-4 touch no yin/repl/* file; list every file each slice touches so
   parallel worktrees can be planned; state which slices can run in parallel with each other.
5. Verify, don't assume: cite file:line for every existing function you build on (fragment-envelope in
   dao.stream.udp, accept-bytes, make-put, the node receive loop).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-5.
