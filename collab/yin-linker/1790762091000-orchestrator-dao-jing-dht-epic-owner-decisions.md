Created-Local: 2026-09-30 16:54:51 +07
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)
# dao.jing.dht epic — OWNER DECISIONS (verbatim), after mob synthesis + ratification

Design: collab/1790750376000-architect-dao-jing-dht-epic-mob-synthesis.gpt-6-sol.findings.md; ratified by gemini-3.1-pro-high
(RATIFY) and glm-5.3 (RATIFY-WITH-CONDITIONS: S0 disambiguate raw descriptor local-bind keys vs UDP channel
remote-destination keys; S3 acceptance criterion "shared chunk utility used by both" with a declared DHT-owned-reassembly
fallback). Final fable sign-off dispatched (collab/1790761753000-architect-dao-jing-dht-epic-fable-signoff.prompt.md).

1. Publication before ShiBi — selected: "Explicit opt-in allowed (Recommended) — Default: never publish. A separate flag,
   distinct from configuring peers, allows publishing that code index; the REPL states what will be shared."
2. Acknowledgement — first answer: "i want to take the kafka route. write is acknowledged immediately as long as it is sent
   out on a network. this works because the data is written to a peer". Clarified: ack level = "sent to a peer but it
   must send to multiple peers before it can be considered ack"; local copy = "Yes, local + network — Local durable insert
   as today, then the network write per the ack level above."
   => SUPERSEDES mob D3 (local ack + volatile best-effort replication). Open for the Architect: the number of peers
   ("multiple"), behaviour with fewer reachable peers (incl. solo mode), and reconciling with D8 (no synchronous lookup
   stalling REPL rounds).
3. Root discovery — selected: "Ratify (Recommended) — Keep this epic's scope; queue root discovery separately."
4. Compatibility — selected: "Ratify (Recommended) — Changes today's synchronous DHT miss behavior; the JVM facade bridges
   callers that need blocking."

5. (2026-09-30 17:21:35 +07) ack-peers — owner, verbatim: "i change my mind. a min of 2 peers not 3". Matches fable's S0 (default 2, floor 2,
   ceiling k=20); no contract change needed.
6. (2026-09-30 17:23:41 +07) No-publish behaviour — selected: "Fetch-only (Recommended) — Fable's S0: refuses inbound stores and serves nothing;
   still routes and caches what it asks for. A pure consumer until you opt in."
7. (2026-09-30 17:23:41 +07) S0 behaviours — selected: "Confirm all (Recommended)": (a) acked = sent to nearest already-known peers, k-nearest
   replication best-effort after; (b) solo never acknowledges; (c) oversize refused before the local insert (64 KiB
   default, measured in S3).
