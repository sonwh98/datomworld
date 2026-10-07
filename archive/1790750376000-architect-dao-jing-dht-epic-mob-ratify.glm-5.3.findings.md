Coding-Agent: glm
Session-ID: 305a156d-0f83-4b26-8ed3-600af4c94594
Model: glm-5.3

Completed-GMT: 2026-09-30 09:47:35 GMT
Completed-Local: 2026-09-30 16:47:35 +07 (+0700)

Ratifying `1790750376000-architect-dao-jing-dht-epic-mob-synthesis.gpt-6-sol.findings.md` as the glm-5.3 ratifier. I re-verified its load-bearing citations against the tree (all check out: `dao.stream.md:80-81` "No operation waits…", `:445` the reader-surface promise, `:571-574` `blocked` as a read outcome; `content.cljc:11-18`; `node.cljc:171-195,307`; `dht.cljc:167,203,227,299`; `kad.cljc:9`; `udp.cljc:58,151,202,373`; the host seams in `src/{clj,cljs,cljd}/dao/stream/udp/`). The synthesis represents my revision position accurately and improves it in two places, noted below.

## Section 1 — Raw datagram layer (`dao.stream.datagram`)

**RATIFY-WITH-CONDITION.** Namespace, Base64-in-values, destination-per-append, no reader surface on the socket, the outcome vocabulary (`ok/full/invalid-value/closed/transport-error`, `ok` ≠ delivery), symmetric 1200 B budget with inbound oversize dropped as invalid transport input, host-seam migration, and the value-channel rebuild with unchanged public surface — all as I argued in the revision round; ratified as written. **Condition (belongs in S0, before S1 freezes it): the raw descriptor's bind keys must not mirror the UDP channel's destination keys.** The synthesis proposes `{:dao.stream.datagram/host h :dao.stream.datagram/port p}` naming the **local bind**, while the UDP value-channel descriptor `{:dao.stream/udp … :dao.stream.udp/host h :dao.stream.udp/port p}` names the **remote destination** sends go to (`docs/design/dao.stream.remote.md` §3.2:359-366; `udp.cljc:77-86`). The dispatch keywords differ, so no program misroutes — but both descriptors will appear side by side in the same compositions (the value layer is built *on* a raw port), and the same key names with opposite meanings is exactly the confusion a contract freeze exists to prevent. Name the bind explicitly (`:dao.stream.datagram/local-host` / `local-port`, or `bind-*`), or otherwise make the semantics unmistakable in the S0 spec.

## Section 2 — DHT on raw datagrams

**RATIFY.** CBOR with `:ver`, the peers-per-reply cap, DHT-owned chunk records keyed by `[observed source, direction, rpc]`, pre-send total-size check, the full reassembly bound discipline, whole-content verification before storage (`dht.cljc:167-180`), and hardening at the interpreter's raw-event read point including the two best catches of this round (no `:from` observation before validation, against `node.cljc:171-175`; reply matching by expected source, not rpc alone, `node.cljc:176-178`). Two places the synthesis is *better* than my revision round and I want that on record: "codec alone is not a size proof" — my ~2 KB Transit figure showed CBOR was necessary, not that it was sufficient, and requiring the cap (initially ≤ 8) to be validated against encoded size in S0, and the 64 KiB message maximum to be measured against real index nodes before freezing, closes a gap I left open. Likewise "a node larger than the chosen limit must fail explicitly, never become a silently local-only segment" — ratified as an acceptance-grade requirement, since silent degradation is precisely the failure mode `dao.jing.dht.md:157-160` already documents and this epic exists to remove.

## Section 3 — DHT stream API and Jing adapter

**RATIFY.** The three streams, caller-minted ids with duplicate-id refusal and bounded completion retention, terminal put outcome = local verdict, replication progress as a separate non-authoritative stream, solo mode = no raw port and no enqueue, and the two-path Jing compatibility (portable local handle + staged hydration around it; optional JVM blocking facade with deadlines, per the repo's stepped/driver split). One recorded self-correction: my revision round said a non-JVM adapter could answer a remote miss with "an outstanding-fetch marker, never a silent not-found." The synthesis is right to reject any marker *inside* the byte-store contract — `get-bytes-fn` may return bytes or the caller's sentinel and nothing else (`dao.jing.md:507-511`), and "no operation waits" (`dao.stream.md:80-81`) bars the portable handle from holding the answer hostage. The correct shape is the synthesis's: the portable adapter is strictly local, and staging happens *around* the handle — a composition drives the DHT stream until the outcome lands and the content is cached, then reads through the handle. I accept this tightening of my own phrasing, not as a concession forced on me but as the better statement of what I meant.

## Section 4 — Revised slice plan

**RATIFY.** S0 freeze (including the stale `dao.jing.dht.md:17-19` sha256 fix), S1 raw layer with the both-halves acceptance bar (new raw round-trips *and* all existing `dao.stream.udp`/`dao.stream.remote` tests unchanged), S2 core + solo, S3 wire + chunks after S1∧S2, S4 hardening/lookup/storage gating non-loopback exposure, S5 after S4 **and** the durable-store epic, concurrency S0 → S1∥S2 → S3 → S4 → S5 with honest file ownership, nothing touching `yin/repl/*` before S5, and the deferred list. This is the plan I proposed with the two gpt additions (measured caps in S0; S5's explicit S4 dependency) that I adopt.

## Section 5 — Differences and picks

| # | Pick | Ruling |
|---|---|---|
| 1 | Namespace `dao.stream.datagram` | **RATIFY** — my pick; a datagram is the lower abstraction, UDP's value channel stays `dao.stream.udp`. |
| 2 | Stream-visible bytes = Base64 | **RATIFY** — my pick; host-boundary rule + `content.cljc:11-13` convention; no host type crosses onto the stream. |
| 3 | Writer backpressure = `full`, not `blocked` | **RATIFY** — my pick; `dao.stream.md:571-574` vs `:588-599`. |
| 4 | DHT codec = CBOR + `:ver` | **RATIFY** — my pick plus gpt's version field; ratified *with* section 2's own caveat that the peer cap be size-validated, since codec alone is not the size proof. |
| 5 | Large messages: shared chunk utility only if used by both; DHT-owned fields | **RATIFY-WITH-CONDITION** — the condition: "actually used by both" must be an **S3 acceptance criterion with a declared fallback** (DHT-owned reassembly, value layer untouched), not an aspiration. If the two identity semantics (rpc-id vs `:dao.stream.remote/id`, `udp.cljc:151`) resist clean parameterization, a strained shared abstraction is worse than two small honest ones; S1's "existing tests pass unchanged" protects the value layer either way. |
| 6 | Jing adapter: local handle + staged hydration, optional JVM facade; spin-poll rejected | **RATIFY** — my pick; see the self-correction under Section 3. |
| 7 | Slicing: contract freeze first | **RATIFY** — my pick; the raw descriptor, wire break, security gate, and sync seam must be frozen before parallel file lanes run. |

## Section 6 — Owner decisions

**Confirm all four, no amendments.** (1) Publication — correctly framed as governing both originating replication and serving, with "bootstrap configured" explicitly not a public-data declaration; this matches the both-ends enforcement I argued in the consensus round. (2) Acknowledgement — the local-verdict/volatile-intent contract, with the stronger remote-ack guarantee named as what it would really cost (a separate epic). (3) Roots — local HEAD for restart, supplied manifest address for this epic's remote query, signed root facts as separate work. (4) Compatibility — the stepped core + local adapter + optional JVM facade, correctly flagged as a decision because it changes today's synchronous miss behavior (`dht.cljc:227-252`). Nothing to add; the two items I had flagged as architect-decidable (peer cap, raw-layer name) are correctly resolved *inside* the synthesis rather than punted to the owner.

---

**Overall: RATIFY-WITH-CONDITIONS** — the two conditions are Section 1's (disambiguate the raw descriptor's local-bind keys from the UDP channel's remote-destination keys in S0) and Section 5 row 5's (make "the shared chunk utility is used by both" an S3 acceptance criterion with a declared DHT-owned fallback). Neither contests a pick; both are precision requirements on the contract-freeze slice whose whole purpose is to freeze exactly these things.

Read-only as instructed; nothing in the repository was edited.
