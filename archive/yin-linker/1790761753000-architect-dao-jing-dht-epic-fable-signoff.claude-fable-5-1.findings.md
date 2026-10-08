Coding-Agent: claude
Session-ID: e4154ec6-1ac3-4b9a-8441-0d572d865cd5
Model: claude-fable-5-1

Completed-GMT: 2026-09-30 09:54:03 GMT
Completed-Local: 2026-09-30 16:54:03 +07 (Asia/Ho_Chi_Minh)

Read-only review; nothing was edited or run. "Synthesis:N" means line N of `collab/1790750376000-architect-dao-jing-dht-epic-mob-synthesis.gpt-6-sol.findings.md`.

## Findings

Severity | location | invariant / evidence | recommended correction

**F1 — HIGH (architectural defect) | synthesis:46-57 | the DHT stream API is a third request/response vocabulary (orchestrator risk 4).**
- **Evidence:** R3 mints `:dao.jing.dht/id|op|address|bytes` for content put/get. The repo already has a stream-shaped convention for exactly this: `{:jing/request r :jing/get a}` and `{:jing/request r :jing/put a :jing/bytes b64}` with their answers (`src/cljc/dao/jing/content.cljc:7-10`). It also already has the stepped client (`dao.jing.content.step`), the JVM blocking driver (`src/clj/dao/jing/content/driver.clj`) and the async facade that `hydrate-async` consumes (`dao.jing.content.async`; `src/cljc/dao/data/btree/storage.cljc:160-164`). The synthesis cites `content.cljc` for Base64 and the driver split but does not reuse its vocabulary. No mob round mentions `:jing/request` or `dao.stream.apply`.
- **Correction:** the DHT interpreter's request and outcome streams speak the `:jing/*` content convention for put and get. The DHT is then one more serving half, a sibling of `serve-step`, whose get may answer later.
  - DHT-only facts (timeout vs not-found, refusal reason) ride as additive open-map keys.
  - `:find` stays the only DHT-qualified op, or is dropped from the public API.
  - Duplicate-ID refusal and completion retention disappear: ids are self-minted, every op is idempotent by content address, and deduplication is the payload's (`docs/design/dao.stream.md:614-617`).
  - Staged hydration and the "optional JVM blocking facade" become the existing `content.step` and `content.driver` clients, so S2 does not build a second facade.

**F2 — HIGH (missing contract) | synthesis:38-42, 57 | explicit causality: how time enters the stepped core is unspecified.**
- **Evidence:** the DHT "owns deadlines, retries", evicts incomplete assemblies and issues "time-limited" cookies, yet it is caller-stepped and portable. The layers beneath it are deliberately clock-free (`src/cljc/dao/stream/udp.cljc:23`; `docs/design/dao.stream.remote.md:299`). The owner's 2026-09-26 linker ruling was that time arrives as data (a tick stream) and that a step-count abort hook is rejected.
- **Correction:** S0 states that time reaches the DHT step only as data: a caller-supplied tick stream, or equivalently a monotonic value the driver passes per step. No ambient clock in portable code.
  - RPC deadlines, partial eviction and cookie epochs are all expressed in ticks.
  - The cookie MAC secret is composition data and the MAC comes from a library per host, not hand-rolled.
  - Without this, the S3 acceptance "under loss and reordering" is not deterministically testable.

**F3 — MEDIUM (migration / slice seam) | synthesis:69-70; `src/cljc/dao/jing/dht.cljc:53-86, 107-131` | fate of `IDhtNet` and ownership of the stepped lookup are not frozen.**
- **Evidence:** `IDhtNet` is a waiting interface (`find-closer` and `fetch-content` return after bounded timeouts) and `lookup` is a synchronous loop over it. Neither can sit under a caller-stepped core, and the synthesis never says they are deleted. S0's freeze list (synthesis:67) omits the core's state and step signature, so S1∥S2 "disjoint ownership" has no frozen seam, and the lookup state machine (the hardest piece) has no owning slice.
- **Correction:** S0 declares:
  - `IDhtNet`, `lookup`, `create-content-dht`, `create-node` and `create-content-dht-udp` are deleted (clean break).
  - The step signature and explicit state: cursors, pending table, lookup state.
  - Lookup-as-state belongs to S2, tested with two or three DHT steps cross-wired through in-memory ring "ports" speaking the raw datagram value shapes. That is the payoff of the owner's layering: S2 needs no socket and no S1.
  - S3 is then chunks plus real sockets.
  - One step owner per DHT state, and who drives the step under the JVM driver.
  - `test/dao/jing/dht_test.cljc`, `test/dao/jing/dht/node_test.cljc` and the fake-net use at `test/yin/vm/linker_test.cljc:904` are rewritten.

**F4 — MEDIUM (contract gaps) | synthesis:28-32 | raw port lifecycle and surface, beyond GLM's key-naming condition.**
- **(a) Bind and descriptor.** `descriptor` is total and stable (`dao.stream.md:227-228, 282`), but bind is asynchronous on Node and Dart (`src/cljs/dao/stream/udp/node.cljs:18-34`; `src/cljd/dao/stream/udp/dart.cljd:15-18`) and an ephemeral port is unknown until bound. S0 must choose: compose the portable writer only after the host bind completes (today's `make-port` pattern), or use `create!` with deferred acquisition (`dao.stream.md:346-352`) and deposit the bound address as a traffic event. It must also say what the descriptor host means (wildcard bind vs advertised) and that off-host `attach!` is `not-found`.
- **(b) Surface.** Declare `#{:writer :closable}`, the excluded outcomes with reasons, and what `close!` does.
- **(c) Host seam signature.** The seams take a `receive-fn` to invoke (`src/clj/dao/stream/udp/jvm.clj:12-18, 41`), which `docs/design/datom.world.md:118-123` says is not an adapter. S1 must hand them the deposit writer instead. "All existing tests pass unchanged" therefore holds for `test/dao/stream/udp_test.cljc` but not for `test/dao/stream/udp/jvm_test.clj`; S1 acceptance should say so.
- **(d) Destination host.** `InetAddress/getByName` at `jvm.clj:50` resolves DNS inside the send path, so `append!` can wait. Require an IP literal; a hostname is `invalid-value`.
- **(e) Async send faults.** Node and Dart cannot answer `transport-error` at call time. Declare them deposited or dropped.
- **(f) Datagram limit.** Make 1200 a composition datum with that default, not a constant of a generic layer.

**F5 — MEDIUM | synthesis:32 | the value-channel rebuild adds a driven stage.**
- **Evidence:** today `udp/receive!` runs inside the host callback (`udp.cljc:373-399`). Rebuilt over raw traffic, something must step raw events into `receive!`. "`dao.stream.remote` keeps using that value channel unchanged" is only true if S0 names that step.
- **Correction:** one raw-cursor owner per port, explicit cursor, a raw gap treated as datagram loss and recovered by the link's resend rule. State whether `udp/step!` drives it or the composition does. Keep `make-port` and `receive!` public so `udp_test.cljc` stays valid.

**F6 — MEDIUM | synthesis:42; `dht.cljc:32-35`; `src/cljc/dao/jing/dht/node.cljc:171-175` | v1 wire freezes an address-derived peer identity.**
- **Evidence:** node id is `sha256(host:port)` of a self-claimed address, and the routing table stores the claimed `:from`. "Observe only after validation" does not say what is recorded. Behind NAT a peer does not know its own reflexive address, so this identity cannot survive the owner's NAT-traversing P2P invariant without a wire v2.
- **Correction:** S0 states that a routing entry holds the observed, cookie-proven source address, never the claimed one, and that the id is not derived from a self-known address (the self-certifying name of `dao.stream.remote.md:503-505` is the repo's own form). NAT meeting itself stays deferred.

**F7 — LOW | synthesis:28, 59 | "never unbounded" is not yet a rule (risk 3).**
- "Retention is chosen at composition" permits a memory-log under a network-facing port, and the consensus round proposed a memory-log for replication intent.
- **Correction:** S0 requires an evicting ring for raw traffic and for the intent log. A gap means dropped datagrams or dropped best-effort intents, repaired by the reconciliation sweep. S1 measures value-channel throughput before and after, since each datagram gains a Base64 round trip and one hop.

**F8 — LOW | synthesis:36-38 | codec and layering precision.**
- "Canonical CBOR" must name `dao.stream.cbor`, not `dao.jing.cbor`.
- Segment and chunk bytes ride the wire as CBOR byte strings; Base64 is only for stream-visible events.
- The strict Base64 helper lives in `dao.jing` (`src/cljc/dao/jing.cljc:417-427`); S1 must place one below `dao.stream.datagram` so the raw layer does not depend upward.
- Declare whether a DHT may share a port with the value channel. If so, give the DHT map a distinguishing qualified version key, and each interpreter silently drops the other's datagrams (`udp.cljc:399` deposits any decodable value today).

## Orchestrator risk list

1. **`ok` means handed to socket, never delivered: pass** (synthesis:30, matching `dao.stream.md:594`), subject to F4(e).
2. **Raw layer stays dumb: pass.** No retry, reliability or fragmentation is specified there. The "composed bounded outbound queue" must remain a separate composed stream, never port-internal.
3. **Bounds: partial.** Gap semantics are stated; mandatory boundedness is not (F7).
4. **Third vocabulary: fail as written** (F1).
5. **Compatibility cost: small.** No `src` namespace outside `dao/jing/dht*` consumes the DHT; only three test files do. With F1, the blocking facade already exists. Owner decision 4 reduces to confirming a clean break.

## Properties that passed

- **Layering** matches the owner direction: datagram layer foundational, `dao.stream.udp` rebuilt on it, DHT on the raw layer with a stream-shaped API.
- **No reader surface on the socket**; inbound events are deposited on a composition-supplied stream (`dao.stream.md:445-458`).
- **Base64 stream values** keep raw traffic servable over any channel, including Transit-JSON WebSocket.
- **Writer outcome set is correct**: `full`, not `blocked`.
- **DHT-owned chunking** leaves the value layer's private envelope alone, with GLM's fallback condition.
- **Verification before storage**: whole content is hash- and canonicality-checked first, and oversize fails explicitly.
- **Reply matching** is by RPC id plus observed source; hardening is gated before non-loopback exposure.
- **Acknowledgement contract** is the local verdict with volatile intent; solo mode opens no port.
- **Store handle stays strictly local**, which preserves the synchronous publish/readback in `yin.repl.index` and "no operation waits".
- **No server/client or privileged node** in either layer; `dao.stream.apply` is untouched.
- **Slice order** S0 → S1∥S2 → S3 → S4 → S5 is sound, with nothing touching `yin/repl/*` before S5.
- **Host portability**: CLJ, CLJS and CLJD socket seams exist, and the stepped core removes the JVM-only guards in `node.cljc`.

Every finding has a determinate correction and none reopens an owner decision, so no further mob round is needed. The design is not ready as written: F1 changes a ratified pick and F2 is absent.

Verdict: READY — conditional. S0 must incorporate F1–F6 (F7–F8 as precision items) alongside GLM's two conditions; S1 and S2 are not dispatched until the S0 text is reviewed against them.

Architect Sign-off: GRANTED for S0 under those conditions; WITHHELD for S1–S5 until the S0 contract passes that review.
