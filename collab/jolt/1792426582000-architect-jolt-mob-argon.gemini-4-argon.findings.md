Created-GMT: 2026-10-19 16:16:28 GMT
Created-Local: 2026-10-19 23:16:28 +07
Coding-Agent: agy
Session-ID: 9f39e80a-850d-4c56-bfb8-2586c76ccbce
# Task: jolt mob - owner questions Q5/Q6/Q7 (argon)
Role: Architect (consultative position)
Implementers:
- Model: gemini-4-argon | Assigned: 2026-10-19 23:16:28 +07 | Status: completed | Rationale: owner-ordered mob across model families; independent position

## Recommendation Q5

- **Answer**: Pin `jolt` and its submodules by exact SHA in `jolt-toolchain.edn` (starting at `96d7b8662d7c491722445bf976b2e25e36ce5d63`, `collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md:10`). Advance on an owner/orchestrator-triggered **biweekly cadence** (or on-demand when an upstream fix retires a local workaround) solely through an explicit `bb bootstrap:jolt` + `bb test:all:jolt` promotion gate on both macOS arm64 and Linux x86_64 (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:52-55,201`). **Defer numeric speed/JVM-ratio budgets until J8 evidence** (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:258,318`), while enforcing structural runtime budgets immediately (collect-safe `:blocking` FFI calls, non-spinning poller waits, and bounded parser recursion, `collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:126,184-185`).
- **Reasons**:
  1. Single-maintainer rapid churn on `main` (e.g., `0.8.22`–`0.8.23` and `[Unreleased]` compiler, FFI, and poller fixes, `build/jolt-spike/jolt/CHANGELOG.md:8-145,251-367,11253-11284`) breaks unpinned reproducibility, yet multi-month freezes forfeit upstream fixes.
  2. Current timing data covers only trivial `-e` boot (~7s cold / ~0.2s warm, `collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md:12`) and obsolete `v0.8.3` numbers (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:22`). Setting JVM-ratio budgets before J1 exact-bigint arithmetic and J0c/J5 C++ CST marshalling exist (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:88-92,176-180`) violates `docs/design/datom.world.md:124` ("Do not optimize prematurely").
  3. Jolt's `{:direct-linking true}` and `jolt build` modes shift speed by 2–17x (`build/jolt-spike/jolt/CHANGELOG.md:60-72,96-103`), requiring J8 baselines to separate source, direct-linked, and AOT modes (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:258`).
- **Reverse-if**: Upstream `main` destabilizes across consecutive biweekly upgrades (switch to milestone-only bumps), or J1/J2 suites time out in CI (impose an immediate suite wall-clock ceiling).

## Recommendation Q6

- **Answer**: **Require an in-process WebSocket implementation over `jolt.socket` / `jolt.socket.native` and `jolt.io-poller` for J6/J8 parity; do not permit a JVM WebSocket sidecar.**
- **Reasons**:
  1. **Standalone native binary & P2P symmetry**: The spike's core case for `jolt` is a standalone native binary sharing source (`collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md:90`). With Q3 running the ANTLR4 C++ parser in-process (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:164`), a WebSocket sidecar would be the sole reason a parity node requires a JDK. In `datom.world`, client and server are symmetric interpreter roles (`docs/design/datom.world.md:97-98`); a sidecar forces a custom IPC protocol that must duplicate `dao.stream.ws` lifecycle and backpressure semantics (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:136`).
  2. **Required primitives already exist**: `jolt.socket` and `jolt.io-poller` provide non-blocking dual-stack TCP `Socket`/`ServerSocket` with fibers parking on `kqueue`/`epoll` (`build/jolt-spike/jolt/stdlib/jolt/socket.clj:15-23,333-366`; `build/jolt-spike/jolt/stdlib/jolt/io_poller.clj:1-12,921-931`), `dao.stream.base64` is portable (`src/cljc/dao/stream/datagram.cljc:46`), and Q4's `libcrypto` (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:120-122`) supplies `EVP_sha1()` for RFC 6455 handshakes.
  3. **Bounded RFC 6455 scope**: `dao.stream.ws` already owns subprotocols, frame/outbound byte bounds, and state transitions in `.cljc` (`src/cljc/dao/stream/ws.cljc:27-38,129-141,196-220`), leaving a narrow host seam (`src/cljc/yin/repl/host.cljc:20-37`; cf. `src/clj/dao/stream/ws/jvm.clj:48-121,251-360`). Implementing HTTP upgrade, 2–14 byte frame headers, client XOR masking, ping/pong, and close frames over `jolt.socket` costs roughly the same as a reliable cross-process sidecar bridge without two-process failure modes.
- **Reverse-if**: `jolt.socket`'s recv-error-as-EOF divergence (`build/jolt-spike/jolt/stdlib/jolt/socket.clj:12-13`) or `jolt.io-poller` full-duplex fiber wakeups (`build/jolt-spike/jolt/stdlib/jolt/io_poller.clj:255-284`) fail under J6 stress even when calling `jolt.socket.native` directly (`build/jolt-spike/jolt/stdlib/jolt/socket/native.clj:17-33`).

## Recommendation Q7

- **Answer**: **Keep the landed `dao.stream.datagram` contract unchanged (no `:dao.stream/full`; `EAGAIN`/`EWOULDBLOCK` returns `:dao.stream/transport-error` and deposits `:dao.stream.datagram/send-failed`).**
- **Reasons**:
  1. **Contract & boundary doctrine**: `docs/design/datom.world.md:70` and `docs/design/datom.world.abi.md:89-91` state that implementations do not add contracts. Both `src/cljc/dao/stream/datagram.cljc:25-33,296-299` and `docs/design/dao.stream.datagram.md:139-145` exclude `:full` because this layer holds no outbound queue and drops under pressure by nature; pacing/retry belongs to interpreters above (`docs/design/dao.stream.datagram.md:18-21,30-32`).
  2. **Cross-host asymmetry**: Amending `src/cljc/dao/stream/datagram.cljc:296-299` touches all hosts, but JVM `DatagramSocket.send` blocks or throws `IOException` (`src/clj/dao/stream/datagram/jvm.clj:94-103`), Node `dgram.send` queues in libuv and reports errors asynchronously (`src/cljs/dao/stream/datagram/node.cljs:10-14,100-106`), and Dart `RawDatagramSocket.send` returns `0` without distinguishing `EAGAIN` (`src/cljd/dao/stream/datagram/dart.cljd:57-61,152-160`).
  3. **Jolt implementation requirements either way**: Jolt's seam must set `O_NONBLOCK` (`build/jolt-spike/jolt/stdlib/jolt/io_poller.clj:120-126`), park receive loops via `poller/wait-ready` (`build/jolt-spike/jolt/stdlib/jolt/io_poller.clj:921-931`), and never wait in `send!` (`docs/design/dao.stream.md:85,171`). Under the **landed** contract, `sendto` returning `native/eagain` (`build/jolt-spike/jolt/stdlib/jolt/socket/native.clj:103,263-266`) deposits `send-failed-event` and returns `{:dao.stream/outcome :dao.stream/transport-error}` (`src/cljc/dao/stream/datagram.cljc:208-215,293-299`). If **amended**, `DatagramWriter/append!` (`src/cljc/dao/stream/datagram.cljc:296-298`) must admit `:dao.stream/full` and jolt's `:send!` must return `{:dao.stream/outcome :dao.stream/full}` on `eagain?` without depositing `send-failed`.
- **Reverse-if**: J7 DHT burst tests show non-blocking `EAGAIN` floods the traffic ringbuffer with `:send-failed` events and evicts inbound datagrams (`docs/design/dao.stream.datagram.md:152-153`).

## Missed questions

- May J0b and J0c report macOS arm64 evidence first if a Linux x86_64 runner is not immediately reachable, or must commissioning wait until both platforms are online (`collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md:156,262-264,274` permits single-platform partial reporting only for J0a)?

## Confidence

- **Q5**: High
- **Q6**: High
- **Q7**: High

## Unverified

- Executed no runtime commands, builds, FFI probes, or benchmarks (`jolt`, `sysctl`, CMake, ANTLR4 C++).
- Could not verify network-dependent claims: upstream `antlr/grammars-v4@8af0d4c` C++ helper existence (`antlr/python3/manifest.edn:1-22`) or ANTLR 4.13.2 C++ runtime internals beyond cited review text (`collab/1792425688000-architect-jolt-lane-plan-review-r5.claude-opus-5-5.findings.md:30,50-53`).
