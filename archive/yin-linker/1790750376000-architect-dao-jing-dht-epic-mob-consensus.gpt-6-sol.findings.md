Coding-Agent: codex
Session-ID: 01a0f10a-6dcd-7af1-b670-78f74255321a
Model: gpt-6-sol (pinned)

Completed-GMT: 2026-09-30 06:52:12 GMT
Completed-Local: 2026-09-30 13:52:12 Asia/Ho_Chi_Minh

## D1. Fragmentation home

**Final position: (c), use `IDhtNet` over a `dao.stream.udp` port.** I favor the narrower channel adapter described by GLM: keep DHT RPC correlation and retries in `IDhtNet`, while the UDP port owns framing and reassembly. Its `send-to!` path already fragments outbound values, and its receiver reassembles them under source-scoped, bounded state ([udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:230), [udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:316)). The author’s proposed envelope is private and carries `dao.stream.remote` request identity and direction; copying that envelope into the DHT node would create a second implementation with coupled semantics ([udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:151)). This channel choice also follows the stream boundary rule ([datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:41)).

Alternatives: **(a) REJECT**; **(b) ACCEPT-WITH-CONDITION** if the stream channel cannot meet a demonstrated DHT requirement and the extracted fragmenter is genuinely shared by both transports; **(c) ACCEPT**.

## D2. DHT wire codec

**Final position: freeze canonical CBOR now, conditional on D1(c).** I change my earlier preference to keep Transit-JSON: that was sensible for an independent framing fix, but `dao.stream.udp` specifies one canonical CBOR value per datagram ([dao.stream.remote.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:352)). Transit is not itself a portability blocker; the reason to switch is the selected channel contract. The current node encodes Transit-JSON ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:57)). Specify the new DHT RPC value and a clean compatibility break before network deployment; keep content-address hashing and `IDhtNet` unchanged.

Alternatives: **switch now ACCEPT** with D1(c); **keep Transit REJECT** with D1(c), **ACCEPT-WITH-CONDITION** with D1(b); **decide/freeze before a fleet ACCEPT** as a required release gate, not a reason to postpone the choice.

## D3. Replication acknowledgement

**Final position: (a), durable local verdict with volatile best-effort replication intent.** I change my earlier insistence that pending work be retained: the DHT already describes replication as best effort, and immutable local content makes a later reconciliation sweep possible ([dao.jing.dht.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.dht.md:54)). Say precisely that `put` acknowledges only the local backend’s `:inserted` or `:present`; it does **not** guarantee a replica or durable queue position. A `dao.stream.memory-log` can hold live intent but is explicitly process-lifetime ([memory_log.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/memory_log.cljc:1)). Put only the *address* in that log and move lookup, payload read, and send into an explicitly stepped runner: lookup currently blocks on the put path before peer sends ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:203)). A durable claim is valid only when the selected local backend is durable.

Alternatives: **(a) ACCEPT**; **(b) ACCEPT-WITH-CONDITION** if the owner requires “eventual replication of every acknowledged write,” with a durable intent log and recovery semantics specified as a separate stronger contract. The author’s unqualified “durably stored locally and queued” is **REJECT**.

## D4. Offline and local-only

**Final position: (b), a solo `IDhtNet`.** Empty bootstrap does not turn networking off: `create-node` still binds a UDP socket and starts its receiver ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:307)). A solo net returns no peers and opens no transport resources. It does not enqueue volatile replication intent while offline; otherwise a complete-retention memory log grows for work that cannot run. Enabling peers later is an explicit composition change and may request a reconciliation sweep over locally retained content.

Alternatives: **(a) REJECT** as the meaning of “network off”; **(b) ACCEPT**; **(c) ACCEPT-WITH-CONDITION** only under D3(b), or as a separately bounded reconciliation policy. It is not part of D3(a)’s acknowledgement.

## D5. Non-JVM hosts

**Final position: (b), shared channel protocol with host socket seams.** `dao.stream.remote` already defines symmetric WebSocket and UDP channels and names JVM, Node, and Dart UDP primitives ([dao.stream.remote.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:320), [dao.stream.remote.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:386)). Porting the socket adapter that deposits datagrams into `dao.stream.udp/receive!` is smaller and keeps framing in one place. A browser later needs WebSocket or relay; it cannot listen on UDP ([dao.stream.remote.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:32)). This does not require the full remote-stream mirror/link machinery for every DHT RPC.

Alternatives: **(a) REJECT** as the primary design; **(b) ACCEPT**. A direct raw-UDP peer remains **ACCEPT-WITH-CONDITION** only as a compatibility adapter to an already deployed wire, with one shared protocol test suite.

## D6. Hardening timing

**Final position: design it with fragmentation; finish it before non-loopback exposure.** The existing node observes claimed peers before validation and correlates replies by RPC number alone ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:171)). Fragmentation makes large replies and partial-message state available to an unauthenticated sender. Specify at transport design time a small pre-validation reply budget, source-bound and time-bound returnability token, reply-source matching, capped partial reassembly, and bounded per-source *and global* work and storage. The implementation can be a following slice while fragmented traffic is confined to loopback tests. A cookie on an already received large store request does not undo its receive/reassembly cost.

Alternatives: **late slice ACCEPT-WITH-CONDITION** only with that prior design and loopback-only gate; **designed before/with fragmentation ACCEPT**. Unrestricted fragmentation followed by later hardening is **REJECT**.

## D7. Root discovery

**Final position: outside the DHT and outside this epic’s minimum persistent-REPL path.** I change my earlier recommendation for a mandatory root-fact slice. The approved durable-store contract uses a local `<dir>/HEAD` naming the latest validated manifest; that suffices for restart by the owning REPL ([durable-store findings](/Users/sto/workspace/datomworld/collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md:16)). The DHT deliberately has no roots ([dao.jing.dht.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.dht.md:9)). A *remote reader that is given only a peer address* still cannot know which manifest is current: for that use case, make root-fact discovery a separate epic with writer identity, trust, retention, and conflict rules. The DHT integration can test remote queries with an explicitly supplied manifest address. Do not claim it has automatic latest-root discovery.

Alternatives: **root-fact slice in this epic ACCEPT-WITH-CONDITION** if the owner makes automatic remote latest-root query an acceptance requirement; **separate epic ACCEPT** for the stated persistent-store goal; **“deferred outside DHT” ACCEPT-WITH-CONDITION** only if the limitation is explicit and the remote query entry point accepts a manifest address.

## D8. Missed defects

**Final position: fix synchronous lookup and dead-peer shortlist handling here; bound storage now, defer full availability policy.** The outbox runner must own `lookup`; merely moving `store-content!` leaves network round trips in `put` ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:212)). The lookup takes only the closest `k` candidates and can return failed peers while excluding a live next-nearest one ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:107), [dao.jing.dht.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.dht.md:180)); correct that before claiming network fetch works under failure. Inbound content, caches, and reassembly need explicit limits for an open peer. Pinning, garbage collection, and durable availability repair need a separate policy because the present design promises only best effort ([dao.jing.dht.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.dht.md:169)).

Alternatives: **all out REJECT**; **all in ACCEPT-WITH-CONDITION** if the epic is expanded to promise durable network availability; **the split above ACCEPT**.

## D9. Owner-policy recommendations

1. **Default `mem`; `dht:` explicit — ACCEPT.** The durable-store design already fixes omission to memory ([durable-store findings](/Users/sto/workspace/datomworld/collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md:10)). A networked default would add observable resource and disclosure effects.

2. **Network off means solo net — ACCEPT.** Empty bootstrap on a UDP node is **REJECT** because it still opens a socket ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:307)). Explicit peer configuration selects a network transport.

3. **Structurally enforce the disclosure policy — ACCEPT.** Documentation alone is **REJECT**. Treat all REPL-published code, literals, provenance, manifests, and covered-index nodes as sensitive by default: Jing stores opaque payloads and cannot classify them ([dao.jing.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.md:30)). Before ShiBi, a network-enabled REPL must not *originate* index publication unless the user separately opts into publishing that specific public code index. The opt-in is an explicit declassification decision, not authorization; it cannot make secrets safe. Serving locally held sensitive content to peers also requires the same gate, since “fetch only” can disclose it.

## D10. Final slice plan

| Order | Slice | Acceptance criterion |
|---|---|---|
| 0 | **Freeze contracts**: D1–D6 choices, wire version, size and exposure limits, acknowledgement wording. Correct the stale SHA-256-only line: code accepts registered algorithms ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:299)). | One written contract covers offline, live, crash, oversized-message, and untrusted-peer outcomes. |
| 1 | **JVM stream-channel DHT transport**, CBOR and fragmentation, initially loopback only. | Multi-node put/fetch above 1200 bytes works; loss retries whole requests; over-limit messages fail explicitly; malformed fragments stay bounded; content hash is verified before caching. |
| 2 | **Async volatile replication and solo net.** | `put` performs no lookup or peer wait; a stepped runner resolves addresses and records bounded outcomes; restart loses in-flight intent without losing durable local content; solo mode opens no socket or queue. |
| 3 | **Hardening and lookup repair.** | Spoofed sources cannot elicit fragmented replies; source-mismatched replies are ignored; per-source and global resource bounds hold; fetch reaches a live next-nearest peer when closer peers fail. |
| 4 | **Node and CLJD socket seams.** | JVM↔Node and JVM↔CLJD multi-fragment put/fetch pass the same wire tests and limits. |
| 5 | **REPL integration**, gated on completion of the durable-store epic’s spec, HEAD, lock, and recovery work. | `mem` remains default; `dht:` with no peers restarts and queries locally with no socket; two processes use separate locked directories and exchange content when explicitly configured; a reader with a supplied manifest address can query remote content; sensitive publishing and serving are blocked without separate public opt-in. No earlier slice touches `yin/repl/*`. |

Slices 1 and 2 can be worked on concurrently **only after slice 0**, with separate ownership of `node.cljc`/UDP transport and `dht.cljc`/runner, and separate test files. Slice 3 depends on the fragmenting transport and overlaps its receiver code; slice 4 depends on that hardened port contract. Slice 5 waits for both this work and the independently implemented durable-store epic. The author’s claimed parallelism of slices 1–4 is therefore **rejected**.

**Resolved this round:** fragmentation home, codec conditional on that home, local-verdict acknowledgement, solo-net semantics, non-JVM adapter shape, hardening gate, and the split treatment of missed defects. **For the owner:** confirm the three policy choices in D9, and decide whether automatic latest-root discovery is part of this epic’s acceptance criteria. That choice determines whether D7 remains a separate epic or adds a required root-fact slice.
