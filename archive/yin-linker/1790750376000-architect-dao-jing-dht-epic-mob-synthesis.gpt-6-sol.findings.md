Coding-Agent: codex
Session-ID: 01a0f10a-6dcd-7af1-b670-78f74255321a
Model: gpt-6-sol (pinned)

Completed-GMT: 2026-09-30 09:40:47 GMT
Completed-Local: 2026-09-30 16:40:47 Asia/Ho_Chi_Minh

## 1. Raw datagram layer — R1

**Synthesis for ratification: `dao.stream.datagram`.** This adopts Gemini’s and GLM’s namespace over my earlier `dao.stream.udp.raw`: a datagram is the lower abstraction, while `dao.stream.udp` remains the existing decoded-value channel. The owner explicitly requires a raw datagram layer exposed as `dao.stream` and a DHT built on it ([revision brief](/Users/sto/workspace/datomworld/collab/1790750376000-architect-dao-jing-dht-epic-mob-revision.prompt.md:15)). This **reverses my previous D1 recommendation** that the DHT ride the value/fragment channel.

A bound port has a `dao.stream` writer handle and a caller-supplied inbound traffic stream. The writer accepts:

```clojure
{:dao.stream.datagram/destination {:host h :port p}
 :dao.stream.datagram/bytes       b64}
```

The host adapter appends received events to the traffic stream:

```clojure
{:dao.stream.datagram/source {:host observed-h :port observed-p}
 :dao.stream.datagram/bytes  b64}
```

`b64` is padded standard Base64 of the **exact datagram bytes**. I pick my and GLM’s position over Gemini’s host byte arrays: byte arrays have different host types, while the existing remote-content convention already uses Base64 in stream-visible values ([content.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/content.cljc:11)). Encoding here is representation of raw bytes in a portable stream value; the raw layer does not decode a DHT or CBOR message.

The writer’s `descriptor` is a plain-data local-port descriptor, for example `{:dao.stream/type :dao.stream/datagram, :dao.stream/identity id, :dao.stream.datagram/host h, :dao.stream.datagram/port p}`. Binding creates the port; a destination is supplied **per append**, so no per-destination writer is required. A descriptor does not promise a reader on the socket. The socket retains no datagrams and has no reader surface: readers use independent cursors on the explicitly supplied traffic stream, whose retention is chosen at composition. A bounded ring’s `:dao.stream/gap` means missed datagram events and is handled by the consumer’s timeout/retry policy. This follows the stream contract’s boundary rule ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:445)).

Set the admitted decoded datagram limit to 1200 bytes, matching the current UDP budget ([udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:58)). Malformed destination or Base64 and oversize sends yield `:dao.stream/invalid-value` with no send. A closed port yields `:dao.stream/closed`; an actual host send fault yields `:dao.stream/transport-error`; `:dao.stream/ok` means local acceptance, not remote receipt. If a composed bounded outbound queue refuses a write, its outcome is `:dao.stream/full`. **Gemini’s suggested writer `:dao.stream/blocked` is rejected:** `blocked` is a reader outcome, while `full` is the writer outcome ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:571), [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:588)). An oversized inbound datagram is invalid transport input and is dropped with a bounded diagnostic before deposit.

Move the existing JVM, Node, and Dart socket seams under this port. They already expose raw received bytes, observed source, addressed send, and close ([jvm.clj](/Users/sto/workspace/datomworld/src/clj/dao/stream/udp/jvm.clj:12), [node.cljs](/Users/sto/workspace/datomworld/src/cljs/dao/stream/udp/node.cljs:14), [dart.cljd](/Users/sto/workspace/datomworld/src/cljd/dao/stream/udp/dart.cljd:13)). Their host callbacks only deposit and return. Rebuild `dao.stream.udp` as an interpreter reading raw traffic and writing raw datagrams; preserve its public descriptor, `send-to!`, fragmentation, projection, and `step!` behavior. `dao.stream.remote` keeps using that value channel unchanged. Today `dao.stream.udp` takes a direct host `:send!` and receives bytes directly ([udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:202), [udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:373)); those become internal adapters over the raw writer and traffic stream.

## 2. DHT on raw datagrams — R2

**Pick canonical CBOR with a wire-version field, for ratification.** This changes my revision-round recommendation to retain Transit-JSON. GLM supplied a concrete reason independent of the value channel: the current `find-node` response can contain up to `k=20` peer maps ([kad.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/kad.cljc:9), [node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:101)); an oversized response is presently dropped without a reply ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:186)). CBOR reduces envelope cost, but **codec alone is not a size proof**. Cap peers per reply at a number validated against encoded size, initially at most eight, and let iterative lookup request further peers. Every DHT wire message carries `:ver 1`; unknown versions receive no large reply. This is a clean wire break before a deployed fleet, and the current Transit envelope description must be updated ([dao.jing.cbor.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.cbor.md:462)).

The DHT consumes the **raw** traffic stream and emits addressed raw datagrams. It owns RPC IDs, expected-source matching, deadlines, retries, and its Kademlia interpretation. A small encoded RPC is one datagram. Large RPC values or segment transfers use **DHT-owned** chunk records containing version, RPC ID, request/reply direction, part index, total parts, and bytes. Reassembly is keyed by observed source address and port, direction, and RPC ID. The DHT must not import `dao.stream.udp`’s private `:dao.stream.remote/id` fragment envelope ([udp.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/udp.cljc:151)). A shared transport-neutral chunk/reassembly utility is acceptable if both layers actually use it; their wire fields and identities remain separate.

Freeze an encoded-message maximum and per-source/global limits in S0. I favor the existing value layer’s 64 KiB default as an initial **bound**, subject to measuring real index nodes before freezing it; a node larger than the chosen limit must fail explicitly, never become a silently local-only segment. Check total encoded size before any chunk is sent; ignore duplicates, reject inconsistent counts, cap accumulated bytes and partial-message count, and evict incomplete assemblies. Loss causes an entire-RPC retry. After reassembly, validate canonical content bytes and the requested content hash before storage or cache insertion ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:167)).

Hardening is implemented in the DHT interpreter **where it reads raw events**, not as semantics of the general raw port. A small challenge obtains a time-limited cookie bound to the observed source IP and port. Require proof before fragmented replies or significant incoming reassembly; validate a token on incoming chunks before allocating partial state. Bound unverified replies, per-source and global work, partials, pending RPCs, and stored content. Match replies against both RPC ID and expected observed source; observe claimed `:from` routing data only after validation. The current node observes `:from` before processing and delivers replies by RPC ID alone ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:171)). Specify these protections with the chunk protocol; non-loopback exposure waits for their implementation.

## 3. DHT stream API and Jing adapter — R3

Compose a **request stream**, **terminal-outcome stream**, and separate **replication-progress stream**. Each request has a caller-minted ID:

```clojure
{:dao.jing.dht/id id :dao.jing.dht/op :put
 :dao.jing.dht/address address :dao.jing.dht/bytes b64}
{:dao.jing.dht/id id :dao.jing.dht/op :get
 :dao.jing.dht/address address}
{:dao.jing.dht/id id :dao.jing.dht/op :find
 :dao.jing.dht/target target-id}
```

A request writer’s `append!` reports only admission to that stream. A caller-driven `step!` reads requests and raw traffic, advances explicit cursors and pending-RPC state, and appends one terminal value per admitted request ID. Put outcomes carry the local `:inserted` or `:present` verdict; get outcomes carry verified found bytes, not-found, timeout, refusal, or error; find outcomes carry bounded peer data. These DHT-qualified values are **not new `dao.stream` operation outcomes**. The stream operation vocabulary remains exhaustive ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:96)). IDs, rather than addresses alone, distinguish concurrent and repeated requests; define duplicate-ID refusal and bounded completion retention.

The standing D3 contract is unchanged: an acknowledgement is the **local backend verdict**, durable only if that backend is durable. Replication intent is a volatile address-only log; the stepped runner performs lookup and sends after the local verdict. It may be lost on crash, and a later explicit reconciliation sweep can derive work from retained content. Current `make-put` performs both lookup and peer waits before returning ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:203)). Solo mode composes no raw port and enqueues no replication work; empty bootstrap on the current UDP constructor still opens a socket ([node.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht/node.cljc:307)).

**Jing compatibility needs two explicit paths.** The portable store handle’s `put-bytes-fn` validates and writes the local backend, emits replication intent, and returns `:inserted|:present`; its `get-bytes-fn` answers local content immediately. This preserves the synchronous publication/readback round in `yin.repl.index`, which reads the manifest it just wrote ([index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:232)), and it remains a valid byte-store handle for `dao.jing.content` ([content.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/content.cljc:39)). For a **remote miss**, use a separate stream request and staged hydration, then retry the local read. An optional JVM blocking facade may drive steps to a deadline and preserve current synchronous remote-get behavior; it is not the portable core. This follows the repository’s stepped-client/blocking-driver split ([content.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/content.cljc:18)). **Gemini’s spin-polling store adapter is rejected:** a byte-store `get` cannot return an “outstanding” marker as if it were content or not-found, and a portable stream operation cannot wait for a remote answer ([dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:80)). Document the compatibility change from today’s synchronously fetching DHT `get` ([dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:227)).

## 4. Revised slice plan — R4

| Slice | Files or areas | Acceptance and dependency |
|---|---|---|
| **S0 — contract freeze** | `docs/design/dao.stream.remote.md`, `dao.jing.dht.md`, `dao.jing.cbor.md`; new raw-layer design section | Specify raw descriptor/values/outcomes, DHT stream values, CBOR v1 wire, measured size cap, chunk keys and limits, cookie gate, and local/remote Jing semantics. Correct the stale SHA-256-only claim: the implementation accepts registered algorithms ([dao.jing.dht.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.dht.md:17), [dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/dht.cljc:299)). |
| **S1 — raw datagram stream** | New `src/cljc/dao/stream/datagram.cljc`; existing JVM/Node/Dart UDP host seams; `src/cljc/dao/stream/udp.cljc` | Exact byte/source round trips on all hosts; oversize gives `invalid-value`; bounded-traffic gap is observable; all existing `dao.stream.udp` and `dao.stream.remote` behavior tests pass unchanged. |
| **S2 — DHT stream core and solo mode** | `src/cljc/dao/jing/dht.cljc`; new DHT interpreter/adapter files; separate tests | Concurrent request IDs correlate; put performs no network lookup or peer wait; file-backed local verdict survives restart; solo mode opens no port or volatile queue; optional JVM facade respects deadlines. |
| **S3 — DHT raw wire and chunks** | `src/cljc/dao/jing/dht/node.cljc`, DHT wire/chunk module and tests | Depends on S1 and S2. Two or three peers exchange small RPCs and multi-datagram segments under loss and reordering; oversized values refuse before send; malformed fragments stay within limits; only loopback exposure. |
| **S4 — hardening, lookup, storage bound** | DHT wire/receiver, `dht.cljc`, `dht/kad.cljc` as needed and tests | Spoofed traffic cannot trigger fragmented amplification or substantial reassembly; source-mismatched replies are ignored; a live next-nearest peer is tried after dead peers; full local storage refuses explicitly. Non-loopback exposure is gated here. |
| **S5 — REPL integration** | `yin/repl/*` and REPL tests | Depends on S4 **and** the separate durable-store epic’s spec, HEAD, lock, and recovery work ([durable-store findings](/Users/sto/workspace/datomworld/collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md:7)). Default `mem`; explicit `dht:<dir>` with solo mode and no socket; two processes use separate locked dirs; supplied-manifest remote hydration supports query; publication/serving gate is enforced. |

S1 and S2 may run concurrently **after S0**, with disjoint source and test ownership. S3 joins them; S4 follows S3 because it changes the same DHT receive path. S5 waits for both epics. **No S0–S4 implementation touches `yin/repl/*`.** Full pinning, GC, network availability repair, NAT meeting, browser transport, and automatic latest-root discovery remain separate work; bounded refusal and dead-peer replacement are in this epic.

## 5. Differences and picks for ratifiers

| Issue | Positions | Synthesis pick and reason |
|---|---|---|
| Raw namespace | Gemini/GLM: `dao.stream.datagram`; GPT: `dao.stream.udp.raw` | **Gemini/GLM.** The lower layer should not be named as a child of the higher UDP value channel. |
| Stream-visible bytes | Gemini: host byte arrays; GPT/GLM: Base64 | **GPT/GLM.** Base64 is portable plain data and matches the existing remote-content value convention. |
| Raw writer backpressure | Gemini: `blocked`; GPT/GLM: writer outcome set | **GPT/GLM.** `blocked` is a read outcome; `full` is the write outcome. |
| DHT codec | Gemini/GLM: CBOR; GPT revision: keep Transit initially | **Gemini/GLM, with GPT’s explicit version field.** Full `find-node` replies already risk the datagram budget; CBOR plus an encoded-size-tested peer cap is a better v1 wire. This is the principal ratification point. |
| Large-message handling | All: DHT-owned wire chunks, not `dao.stream.udp` envelope; GPT/GLM allow shared utility | **Shared utility only if actually used by both; DHT-owned wire fields.** It preserves the owner’s raw-layer boundary. |
| Jing adapter | Gemini: synchronous spin-poll/blocked bridge; GPT/GLM: local byte-store plus staged hydration, optional JVM blocking facade | **GPT/GLM.** It preserves the byte-store’s exact result contract without blocking portable stream operations. |
| Slicing | Gemini: starts extraction immediately; GPT/GLM: contract freeze first | **GPT/GLM.** The new raw descriptor, wire break, chunk security gate, and sync/async seam must be fixed before separate file lanes run. |

The owner’s direction supersedes previous D1 and changes D5’s mechanism. D3 (local-verdict acknowledgement), D4 (solo mode), D7 (roots outside DHT), D8 (lookup repair and resource bounds), and D9 (memory default, explicit network and disclosure) remain substantively intact.

## 6. Owner decisions — R5

1. **Publication:** May a separate, explicit public-index opt-in publish REPL code before ShiBi, or is all REPL-code publication prohibited until ShiBi? The decision must govern both originating replication and serving locally held content. “Bootstrap configured” alone is not a public-data declaration.
2. **Acknowledgement:** Ratify the standing local-verdict, volatile-intent contract. If the owner instead needs “acknowledged means remotely replicated,” that is a stronger durable-intent and remote-ack epic.
3. **Roots:** Ratify local HEAD for restart and a **supplied manifest address** for this epic’s remote query. A reader holding only a peer address cannot discover the latest root; signed root facts and rendezvous require a separate design ([dao.jing.dht.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.dht.md:9), [durable-store findings](/Users/sto/workspace/datomworld/collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md:16)).
4. **Compatibility:** Ratify the portable stepped DHT plus local Jing byte-store adapter and optional JVM blocking facade. Portable remote misses use staged hydration. This is a design choice because it changes today’s synchronous DHT miss behavior.
