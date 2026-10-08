Completed-GMT: 2026-10-06 16:19:02 GMT
Completed-Local: 2026-10-06 23:19:02 +07
Coding-Agent: claude
Session-ID: 642b5698-f626-4028-9068-0704d093d269

# Task: Architect evaluation of published head trace cross-machine step off loopback (Design Section 8.3)
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 23:14:21 +07 | Status: active | Rationale: Architecture evaluation of the 5 unverified items in Section 8.3 before engineering slicing for cross-machine WebSocket transport off loopback

## 1. Executive Summary & Verdict

**Blockers: not ready to slice as "remove the loopback condition of 5.1".** Of the five items, two do not hold (1 and 5), one holds only because a ring happens to have capacity 64 (2), and two hold with gaps (3 and 4). Three things outside the five also block a usable step: the token advertises the bind address, the dial URL breaks on IPv6 literals, and the board has no stop that closes sessions.

None of this touches the trace, `judge`, the floor or `heads.edn`. Section 8.2's claim "nothing in `dao.stream.*` if the unverified items hold" fails, though: the bounds need small additive changes in `dao.stream.ws-project`, `dao.stream.remote` and `dao.stream.ws`.

It becomes ready once the owner answers the question below and the bounds land before the lift (slices S1 to S3 in section 3).

**Owner question:** off loopback the token prints the UDP socket's local address (`yin/repl/dht.cljc:613, 622`), which for a wildcard bind is `0.0.0.0`. The step needs an advertised host, such as a `--dht-advertise <ip>` flag or requiring a concrete bind address. Section 8.2 risk 3 leaves the grammar to this step. I recommend requiring a concrete, non-wildcard IP literal for now and deferring the flag.

## 2. The five items

### Item 1: acceptor session bounding
**High | architectural defect in the composition | `ws_project.cljc:225, 240-250`; `ws.cljc:584-597`**

- **The slots do not bound sessions.** `accept-slot!` releases the handoff slot at acknowledgement, so the 8 slots bound pending handoffs per tick only. `adopt!` adds to `:sessions` with no cap.
- **Sessions leave only when the projection closes.** `reaped` removes closed projections and nothing else. Each held connection costs a host socket plus two 64-entry rings (`head/ws.cljc:152-160`).
- **Half-open clients are never reaped.** No host sends pings or has an idle timeout, and the server writes only when asked, so TCP never notices a vanished client. A session cap alone would therefore fill permanently.
- **Slot starvation is denial only.** A peer can take all 8 slots each tick; later upgrades are closed with 1013 (`ws.cljc:525-527`).
- **`yin.repl.serve` has the same exposure** if it is ever bound off loopback (`serve.cljc:23`).

**Recommended contract:**
- Add optional `:max-sessions` to `make-acceptor`; nil keeps today's behaviour, so `yin.repl.serve` is unchanged.
- Record a last-activity tick per session from the `now` that `accept-step!` already receives.
- At the cap, close the idlest session and adopt the new one. A cut-off reader sees `:source-lost` and redials.
- Treat a session whose handle is closed as reapable, so reaping does not depend on the host close callback firing.
- Per-address limits need the remote address through the `accept!` seam; defer them.

### Item 2: step duration and loop bounding
**Medium-high | spec and code diverge, plus implementation gaps | `remote.cljc:269-282`; `ws_project.cljc:103-126, 275-282`; `ws.cljc:318-320`**

- **The budget the spec names does not exist.** `dao.stream.remote.md` 2.3 says requests are "bounded by a composition budget"; `mirror-step` loops to `blocked` or `end` with none.
- **The effective bound is accidental.** The channel ring and traffic medium are 64-entry evict-oldest, so on Node and Dart a session costs at most about 64 projected events and 64 answers per tick. Step time is then sessions × 128 small operations, which is bounded only once item 1 is.
- **The JVM has no hard bound.** `step!` reads to `blocked` while http-kit threads keep depositing, so a fast enough flooder could hold the single tick owner. In practice the projection outruns transit decoding.
- **The peer-chosen `next` budget is harmless here.** `chase` is bounded by the board's content, which is one entry. It is unbounded for a general table.
- **One bad session stops every session.** `accept-step!` has no per-session isolation; a throw (for example from a middleware) aborts the tick for all later sessions and reaches the REPL's ticker. The plain ringbuffer path answers malformed cursors and anchors as data, so I found no throw on the board today.
- **`:pending-frames` is unbounded.** Frames that arrive between upgrade and the next tick queue without limit. This contradicts `dao.stream.ws.md` ("No inbound-event inbox accumulates inside the transport").
- **Outbound is unbounded on all three hosts.** A peer that asks and never reads grows the host send buffer. The JVM and Node adapters declare no high-water signal (`jvm.clj:8-10`, `node.cljs:26-31`); Dart's `.add` buffers.
- **Frame size limits are host defaults.** As I recall them, unchecked: Node `ws` 100 MiB, http-kit 4 MiB, Dart none. Decoding runs on the callback thread.

**Recommended contract:**
- Give `mirror-step` an optional budget arity (the spec already has the words) and `step!` a per-call event budget.
- Wrap each session's pass in a catch that closes that session.
- Cap `:pending-frames` at the admission capacity; beyond it is a protocol failure.
- Treat a `gap` on the session ring as flooding and close the session.
- Set Node `maxPayload` and http-kit `:max-ws` at the listener.
- Record outbound growth as a stated limit, mitigated by idle eviction. Node could report `bufferedAmount` as `full` later.

### Item 3: listener seams outside `yin.repl.serve`
**Low | holds; three implementation gaps | `yin/repl/dht.cljc:587-592`; `head/ws.cljc:219-229`**

- **It composes without moving code.** `yin.repl.dht` already passes `(:bind! ws)` from `yin.repl.host/websocket` into `head.ws/serve` on JVM, Node and Dart. None of the three listeners is loopback-specific (`jvm.clj:381`, `node.cljs:243`, `dart.cljd:269`). Synchronous and asynchronous bind failures both arrive as `:yin.head.ws/bind-failed`.
- **No stop closes sessions.** `yin.repl.dht/close!` (`dht.cljc:438-445`) only unbinds. `yin.repl.serve` closes each session handle first (`serve.cljc:618-625`). With a held remote connection, Node's server close waits for it. Add a `head.ws/stop` following the serve pattern.
- **The lifecycle cursor can stick.** `serve-step` (`head/ws.cljc:273-284`) handles only `ok`. Every refused upgrade deposits a lifecycle fact, so more than 64 bad upgrades in one tick produce a gap that is never adopted, and `:stopped` (or `:bind-succeeded` on the async hosts) is never seen. Adopt the recovery cursor as `step-board` does (`dht.cljc:639`).
- **Dart is unverified on a real socket.** The real-socket test is excluded on ClojureDart (`head_ws_test.cljc:703`).
- **Path is not enforced by any listener.** Any path reaches the board's endpoint. This is harmless because the board has its own port and table.
- **Cosmetic:** lifecycle codes in `dao.stream.ws.jvm` and `dao.stream.ws.dart` are named `:yin.repl.endpoint/*`.

### Item 4: TCP at the UDP port number
**Low-medium | holds for explicit ports; one latent defect | `dht.cljc:602-604, 613`; `jvm.clj:114-117`; `node.cljs:137-142`; `dart.cljd:76-79`**

- **Port spaces are separate.** TCP and UDP ports are independent on Linux, macOS and Windows, so a node's own UDP bind never blocks its TCP bind at the same number. No `SO_REUSEADDR` setting is needed for that.
- **After an ephemeral UDP bind it is best effort.** The kernel picks the UDP number without looking at TCP, so that TCP port may already be held by another process. Failure is already data: the node runs with no board and no token. No code change is needed; I recommend an explicit port off loopback, where a forwarded port needs one anyway.
- **Dart may hide a UDP clash.** I believe `RawDatagramSocket.bind` defaults to address reuse (`dart.cljd:94`), which on Linux lets two nodes bind the same UDP port. The TCP bind failure is then the only signal. This is pre-existing and unchecked.
- **IPv6 dial is broken on all three hosts, loopback included.** `socket-url` builds `ws://` + host + `:` + port with no brackets, so an IPv6 literal gives an invalid URL. The UDP seam requires unbracketed literals, so the token host is unbracketed. Bracket in the three `socket-url` functions.
- **Dual-stack asymmetry.** The UDP seams are single-family by their own rule (`datagram/jvm.clj:88`), while a TCP listener on `::` is dual-stack by default. An IPv4 reader could reach the board but not fetch blobs. Set v6-only on Node and Dart for IPv6 binds and document it for the JVM.
- **Operationally:** two firewall rules and two NAT forwards, as 8.2 risk 3 says.

### Item 5: lost requests and half-open connections
**High | the design's description is wrong; liveness rule missing | `remote.cljc:377-390, 725-728`; `head/ws.cljc:315-319`; `head.cljc:552`; `dht.cljc:748-750`**

- **A clean loss is detected.** A close event closes the ring, the link sees `end`, the reflection answers `channel-gone`, the follower emits `:source-lost`, and `step-follow` drops the dial and redials (`dht.cljc:921-926`).
- **A half-open is not.** The reflection sends one `next`, registers it outstanding, and answers `blocked`. Every later poll finds it outstanding and only counts an ask. `head.ws/dial` passes no `resend-after`, so nothing is re-sent. The reader sees `blocked` indefinitely.
- **Section 8.3 says "retryable errors until TCP gives up"; that is not what happens.** The reader sees `blocked`, which looks exactly like "no new head". Only the kernel's retransmission limit on that one unacknowledged request ends it, which can take minutes.
- **`:answered` is falsely advanced.** `read-source` marks a locally produced `blocked` as answered. It affects reported status only.
- **Only `:resolving` has a bound** (`:yin.head/no-answer`). An attached dial has none.
- **The server side is the half-open leak of item 1.**

**Recommended contract (no clock, no `dao.stream.*` change, not a withholding timeout):** a live mirror answers every `next`, including with `blocked`, so a healthy channel shows inbound wire values every poll interval.
- In `step-link`, for an attached dial, watch `ws-project/reading-cursor` of the dial's projection.
- If it has not moved for the existing repair delay while the follower was polling, end the dial as `:yin.head/no-answer` and redial.
- A withholding source still answers and is not affected, so section 10's rule stands and `dao.lease` stays the owner of source liveness.
- Count `:answered` only from that inbound movement.

## 3. Recommended slicing and engineering boundary

**Order: S1 → S2 → S3 → S4.** S1 to S3 are safe on loopback and land before the gate is lifted.

| Slice | Where | What |
|---|---|---|
| S1 | `dao.stream.ws-project` | Optional `:max-sessions`, last-activity tick, idlest-first eviction, closed-handle reaping, per-session catch, `step!` event budget |
| S2 | `dao.stream.remote`, `dao.stream.ws` | Budget arity on `mirror-step`; cap on `:pending-frames` |
| S3 | `yin.repl.dht`, `yin.vm.linker.head.ws`, three `socket-url`s | Attached-dial no-answer rule; `head.ws/stop`; lifecycle gap adoption; IPv6 brackets |
| S4 | `head/ws.cljc:182`, `dht.cljc:267`, `main.cljc:136`, host listeners, design doc | Remove the loopback gates; advertised host per the owner's answer; banner line; listener limits; amend 5.1, 5.8, 8.2, 8.3, 10 |

**Boundary:**
- S1 and S2 are additive options with nil defaults, so `yin.repl.serve` behaves as today and no contract is amended beyond wording 2.3 already has.
- `yin.vm.linker.head` (the follower) changes only in how `:answered` is counted.
- The trace, `judge`, persistence and `dao.space.dht` are untouched.

**Completion criteria** (per slice `bb test:clj`; one full three-host `bb test` at each landing):
- Held connections beyond the cap evict the idlest and never grow `:sessions`.
- A throwing session does not stop the tick.
- More than 64 frames in one tick close the session.
- An attached dial over a silent channel is redialed.
- A real-socket crossing passes on Dart.
- An IPv6 loopback dial passes on all three hosts.

**Deferred, stated as limits:** per-address admission, outbound high-water on JVM and Dart, `wss`, and a retry for the ephemeral TCP bind.
