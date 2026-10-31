Created-GMT: 2026-10-19 16:16:28 GMT
Created-Local: 2026-10-19 23:16:28 +07
Coding-Agent: claude
Session-ID: 3527f14c-bcb0-4f8b-9ccf-cece0542e1ad
# Jolt mob: Q5/Q6/Q7, independent position (claude-opus-5-5, Architect)

## Recommendation Q5: pin by SHA, upgrade through a named gate, defer budgets

**Answer.** Record the full jolt SHA and the resolved Chez build in `jolt-toolchain.edn`. Upgrades go only through `bb bootstrap:jolt --update`, which produces a candidate SHA. The candidate is promoted only after it passes the **jolt upgrade gate**: `test:all:jolt`, `test:slow:jolt`, the J0b/J0c probes and the J1 byte/hash fixtures, on both platforms. The orchestrator triggers the gate when an upstream fix we need lands, when the resolved Chez version changes, or when the pin is older than about 30 days. No silent pulls. Prefer release-tagged SHAs. Set ratio budgets against the JVM only after J8; until then, every gate run reports benchmarks.

**Reasons.**
- Jolt releases every 1-4 days (`CHANGELOG.md:48,251,842,965,1098,1182`: 0.8.18 to 0.8.23 between 10-06 and 10-17). Following main continuously means chasing noise. A pin plus a gate keeps results reproducible (plan §8).
- No benchmark exists for `96d7b866`; the only numbers are for v0.8.3 (`docs/jolt-experiment.md:11-21`). A ratio chosen now would be a guess, and would end up ignored or used to weaken tests.
- Comparing each candidate with the previous pin needs no JVM baseline, and it catches upstream regressions, which are the real churn risk.

**Reverse if:** the owner needs a speed promise before J8; then set interim ceilings from J2 measurements.

## Recommendation Q6: native WebSocket over jolt.socket, no JVM sidecar at parity

**Answer.** Jolt implements only the host WebSocket **seam** (`{:send! :close! :queued-bytes}`) on top of `jolt.socket`. The portable `dao.stream.ws` (`ws.cljc:170-215`) keeps all of the semantics: bounds, `full`, teardown and outcomes.

**Reasons.**
- A sidecar makes a JVM the real network endpoint, with the jolt node reaching peers through a privileged co-process. That conflicts with the P2P / no-privileged-node invariant, and its helper protocol is close to an implementation-added contract (`datom.world.md:70`).
- The cost is bounded. The whole JVM host (client and server) is 471 lines (`src/clj/dao/stream/ws/jvm.clj`) and has no TLS code to port. Handshake SHA-1 comes from the libcrypto already qualified in J0a. Framing, masking, ping/pong and close are small and covered by test vectors.
- Natively, fibers park on the poller (`socket.clj:21-23`) and `:queued-bytes` is exact, whereas http-kit's server only approximates it (`ws/jvm.clj:9-10`).
- Extra testing is modest. The existing cross-host peer fixtures exercise any seam; add RFC 6455 handshake, masking and fragmentation vectors and a malformed-frame suite.

**Reverse if:** J6 shows jolt's nonblocking TCP cannot keep correct park/backpressure behavior under load, unfixed upstream within one upgrade-gate cycle. Even then, label the sidecar temporary, never "parity".

## Recommendation Q7: keep the landed contract; no `:full`

**Answer.** Keep the contract as it is. On jolt, EAGAIN/EWOULDBLOCK (and ENOBUFS, which is what macOS returns under pressure) map to `{:dao.stream/outcome :dao.stream/transport-error :dao.stream.datagram/reason "would-block"}` plus a `send-failed` event. `append!` never retries, queues or parks.

**Reasons.**
- `:full` means "try again later", which needs a queue or a readiness wake. This layer has neither by design (`datagram.cljc:29-33`, `dao.stream.datagram.md:141-145`).
- No other host would produce `:full`. The JVM send blocks, and its failures already become transport-error (`datagram/jvm.clj:94-104`); Dart's zero return is already transport-error (`dao.stream.datagram.md:159-162`). A jolt-only `:full` would be an implementation adding a contract (`datom.world.md:70`).
- The shared writer turns any other outcome into transport-error (`datagram.cljc:296-301`). An amendment would touch shared code, `udp.cljc` consumers, the DHT and all four hosts, for a signal UDP semantics make meaningless.

**Reverse if:** DHT workload measurements show frequent would-block failures and a consumer shows it needs a transient signal. Then make it a dao.stream.datagram design change on all hosts at once, with a wake mechanism, not jolt work.

## Missed questions

- Who provides the Linux x86_64 machine or CI for J0b and J0c? Both are two-platform gates.
- May the repo carry local patches on top of the pinned jolt SHA, or must every fix go upstream first?
- Does parity include `wss://`? The JVM host has no TLS, so I assume not.

## Confidence

Q5 medium. Q6 high. Q7 high.

## Unverified

- Jolt commit churn: git in the checkout was blocked, so the cadence comes from changelog headers only.
- macOS UDP returning ENOBUFS, not EAGAIN: from memory.
- Native RFC 6455 size: estimated by analogy to the JVM host, not counted.
- SHA-1 coverage in `jolt-crypto`: not checked.
