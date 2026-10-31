Coding-Agent: claude
Session-ID: e4154ec6-1ac3-4b9a-8441-0d572d865cd5
Model: claude-fable-5-1

Completed-GMT: 2026-09-30 10:01:54 GMT
Completed-Local: 2026-09-30 17:01:54 +07 (Asia/Ho_Chi_Minh)

S0 is written as docs only; nothing in `src/` or `test/` was touched, and nothing is staged or committed.

## Part A — ruling on the acknowledgement contract

**A write is acknowledged when the local insert has succeeded and every datagram of its store message has been handed to the socket toward at least `ack-peers` distinct peers.**

- **How many peers:** `:dao.jing.dht/ack-peers` is composition data with default 2, floor 2 and ceiling `k` (20). A value outside that range is a composition defect and throws at construction.
- **What "sent" means:** the raw writer's `append!` answered `:dao.stream/ok` for every datagram of the message, chunks included. A peer where any datagram answered otherwise is not counted and the next candidate is tried.
- **Which peers count:** only proven routing entries (the observed address has answered us) that are fresh (a matched reply within the last cookie epoch, by our own ticks). A stale peer is pinged first.
- **Honest vocabulary:** the acknowledgement fact is `:dao.jing.dht/sent` with a peer count. It never says stored or received. "Stored" appears only as `/confirmed` in the later `/replicated` progress fact, and that number is peers' own claims.
- **Local copy:** the local insert happens first and immediately, as today. The byte-store handle's `put-bytes-fn` returns the local verdict at once; that return value is explicitly not the acknowledgement.
- **No stalled REPL round (D8):** the acknowledgement never waits on a lookup. Candidates are the nearest peers already in the routing table (a table read, no IO), so with a warm table the write is acknowledged in the same step that read it. Lookup toward the true `k` nearest continues afterwards, best effort.
- **Too few peers:** if the count has not reached `ack-peers` within `ack-ticks` (default 5000), the write is not acknowledged. The caller sees an `/unacknowledged` fact with a reason and how many peers it did reach, and a waiting `:jing/put` gets `{:jing/request r :jing/unacknowledged reason}`. The local copy stays; retrying is the caller's policy.
- **Reasons:** `solo`, `unpublished`, `oversize`, `absent`, `busy` (all answered at once, nothing sent) and `too-few-peers` (at the deadline; this is the cold-bootstrap case).
- **Time (F2):** every deadline is a tick difference on a composition-wired tick stream, using the existing `dao.lease` tick fact. The step reads no clock.

One correction to my own F1: the stepped content client matches answers by exact key set, so "additive open-map keys" on `:jing/*` answers would be classed malformed. The contract therefore keeps answers exact, puts every DHT-specific detail on a separate facts stream, and adds exactly one new answer shape, `:jing/unacknowledged`.

## Files

- **Created** `docs/design/dao.stream.datagram.md` — the raw datagram layer.
- **Rewritten** `docs/design/dao.jing.dht.md` — the frozen DHT contract, the fate of today's code, and the S1–S5 slice plan.
- **Cross-reference only** `docs/design/dao.stream.remote.md` §3.2 — one sentence pointing at the datagram layer.

## Map to doc sections

| Item | Where |
|---|---|
| F1 — reuse the `:jing/*` convention, no third vocabulary | `dao.jing.dht.md` §3, §5.1 |
| F2 — time as data | `dao.jing.dht.md` §5.3; cookie epochs §8 |
| F3 — `IDhtNet` deleted, step signature, lookup as state, S2 on in-memory sockets | `dao.jing.dht.md` §2, §6, §10 |
| F4 — bind lifecycle, surface, seam signature, IP literals, async send faults, `max-bytes` as data | `dao.stream.datagram.md` §2–§4 |
| F5 — `port-step!` for the value channel | `dao.stream.datagram.md` §7 |
| F6 — id not address-derived, observed addresses only | `dao.jing.dht.md` §6 |
| F7 — evicting rings everywhere, throughput measured | `dao.stream.datagram.md` §5; `dao.jing.dht.md` §9; S1 acceptance |
| F8 — `dao.stream.cbor`, byte strings on the wire, Base64 helper below `dao.jing`, shared-socket discriminator | `dao.jing.dht.md` §7; `dao.stream.datagram.md` §2, §6 |
| GLM condition 1 — `bind-host`/`bind-port` vs UDP destination keys | `dao.stream.datagram.md` §3; `dao.stream.remote.md` §3.2 |
| GLM condition 2 — shared chunk utility as S3 acceptance with DHT-owned fallback | `dao.jing.dht.md` §10, S3 |
| Owner 1 — publication opt-in | `dao.jing.dht.md` §4.4; S4 and S5 acceptance |
| Owner 2 — acknowledgement | `dao.jing.dht.md` §4 |
| Owner 3 — root discovery separate | `dao.jing.dht.md` §1; S5 acceptance |
| Owner 4 — stepped DHT, local adapter, optional JVM facade | `dao.jing.dht.md` §5.1, §5.2 |
| Stale sha256-only line | `dao.jing.dht.md` §1 |
| Slice plan with acceptance | `dao.jing.dht.md` §10 |

## Back to the owner

1. **Default `ack-peers` of 2.** "Multiple" gives a floor of 2; I made the default equal the floor. Say if you want 3.
2. **Acknowledged means sent to the nearest peers already known, not the true `k` nearest.** That is what makes the acknowledgement immediate. Replication to the true nearest follows best effort, so a reader's lookup may miss briefly after an acknowledgement.
3. **Publish off means every write is unacknowledged.** Combining your decisions 1 and 2: without the publish flag nothing is sent, so nothing can be acknowledged (reason `unpublished`). I also made such a node fetch-only: it refuses inbound stores and serves nothing, but still routes and caches what it asks for. Confirm that is the intended default experience for `dht:<dir>` with peers but no publish flag.
4. **Solo mode never acknowledges.** Every write reports `unacknowledged` / `solo`; the local copy is still durable if the backend is.
5. **Oversize now fails before the local insert, in every mode**, so a segment can never be silently local-only. The 64 KiB default is unmeasured; S3 measures real index nodes and confirms or raises it before S4.

For the orchestrator: `dao.jing.md` (backend list, `IDhtNet` mention) and `dao.jing.cbor.md` ("Remote and DHT", which still describes the Transit envelope and Base64 on the DHT wire) were outside my edit scope and are now stale. I assigned those edits to S2 and S3 in the slice plan. S1 now also touches nothing S2 owns, so the two can be dispatched together once this text is reviewed.
