Created-GMT: 2026-09-30 06:18:08 GMT
Created-Local: 2026-09-30 13:18:08 +07 (+0700)
Coding-Agent: agy
Session-ID: c1020f8c-3056-4129-a680-cdc1337b844a (captured conversation_id)
# Task: Architect design — dao.jing.dht epic: make the DHT viable as a persistent (and eventually default) store for yin.repl code

Role: Lead System Architect

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-30 13:18:08 +07 (+0700) | Status: active | Rationale: OWNER "Yes" to queuing the DHT epic and starting its Architect pass concurrently; team.md strengths (deep reasoning, system-wide design); flat route; spreads load off codex, which gates the two in-flight workstreams

Perform a read-only architecture design. Do not edit files. You are headless; your final response is the deliverable.

Owner questions that led here (verbatim): "What if the dao.jing is the dht?"; "Can dao.jing.dht be the default persistent
store of yin.repl compiled code?"; "Is dao.jing.dht complete enough for this use case?"; "Can the dht epic be done
concurrently?" — answered yes; the owner said "Yes" to queuing it and starting this pass now.

Read first:
- docs/design/datom.world.md (axioms, six invariants), docs/design/dao.stream.md, docs/design/dao.jing.md,
  docs/design/dao.jing.dht.md (NOTE: it says the DHT routes only :segment/sha256-... addresses, but
  src/cljc/dao/jing/dht.cljc ~299 accepts any registered :segment/<algorithm>-... and the code index uses blake3 — treat
  the doc as stale and verify), docs/design/dao.stream.remote.md
- src/cljc/dao/jing/dht.cljc (store handle, lookup, put/get), src/cljc/dao/jing/dht/node.cljc (UDP Kademlia peer),
  src/cljc/dao/jing/dht/kad.cljc, src/cljc/dao/jing/file.cljc, src/cljc/dao/jing/mem.cljc, src/cljc/dao/stream/udp.cljc
  (fragment envelope, 1200-byte figure), test/dao/jing/dht_test.cljc and test/dao/jing/dht/*
- The yin.repl use case: src/cljc/yin/repl/index.cljc (per round: fresh intake, transactor/publish!, drain into the
  store, read-manifest back), docs/design/yin.repl.dao.space-index.md; the approved durable-store design
  collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md (startup store selection mem |
  file:<dir>, <dir>/HEAD, exclusive lock, rehydration; a dht: store kind would plug into its store-spec mechanism
  later); the approved $ast design keeps $ast/$occ in memory (collab/1790708845000-architect-repl-ast-row-relation.gpt-6-sol.findings.md);
  docs/design/yang.antlr.md §9.3/§9.6 (preludes and packages content-addressed and distributed via the dao.jing DHT).
- Owner invariant (memory): any dao.stream exposable as ws/UDP, NAT-traversing P2P, no server/client concept, no
  privileged node.

Current blockers found by the orchestrator (verify each): (1) one datagram per message, 1200-byte budget; larger
segments "stay local-only until fragmentation (DRDS) exists: store degrades to best-effort, fetch times out" — B-tree
index nodes and manifests likely exceed it; (2) UDP node JVM-only (java.net.DatagramSocket); (3) put replicates
synchronously before returning (500 ms timeout x 3 tries per peer by default) — a REPL round publishes many blobs;
(4) no returnability validation / rate limiting / amplification hardening; (5) no mutable names or roots by design, so
readers need another way to learn the latest manifest/program roots.

Design, precisely enough to slice into implementation without another design round:
1. Fragmentation/reassembly (DRDS or other): where it lives (dao.jing.dht.node vs a shared dao.stream.udp fragment
   envelope), integrity per fragment and whole segment, limits, loss/retry, and interaction with hash verification.
2. Replication lifecycle: asynchronous outbox vs synchronous; the causality contract (what an acknowledgement means),
   explicit state (no hidden global state, no callbacks), durability of the outbox, and how a REPL round avoids
   blocking on the network.
3. Non-JVM hosts: a Node/CLJS UDP peer, a dao.stream-based transport (ws / dao.stream.remote) satisfying IDhtNet, or a
   defined non-JVM mode — consistent with the P2P no-privileged-node invariant; CLJD.
4. Hardening: returnability, per-peer rate limits, amplification bounds; what is required before any on-by-default use.
5. Latest-root discovery: how a reader learns the current manifest/program roots without mutable DHT names (e.g. a
   dao.stream of root facts over dao.stream.remote, signed records, or other) — respect "the DHT answers one question";
   say whether root discovery belongs outside the DHT.
6. Bootstrap/config: peer lists, ports, NAT traversal, offline behaviour; what "local-only DHT" means (no peers).
7. Integration with yin.repl: the dht: store kind in the durable store-spec mechanism, what is published (the covered
   datom index, and/or the canonical code rows), and the read-only cross-process query entry point.
8. Slicing: ordered, independently committable slices with acceptance tests; which slices can run concurrently with
   the in-flight yin.repl work (they must not touch yin/repl/* until the integration slice); dependencies.
9. OWNER decisions, listed separately (e.g. default store behaviour, network on/off by default, access policy until
   ShiBi exists).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-9 with file:line evidence, marking architectural defects vs implementation gaps vs deferred work.
