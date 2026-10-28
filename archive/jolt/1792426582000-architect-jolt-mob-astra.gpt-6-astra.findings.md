Created-GMT: 2026-10-19 16:16:28 GMT
Created-Local: 2026-10-19 23:16:28 +07
Coding-Agent: codex
Session-ID: 01a14f30-ca56-7530-bb93-3a870eca7a7b
Role: Architect (consultative position)
Author: gpt-6-astra
Status: independent recommendation; no implementation commissioned

## Recommendation Q5

**Keep a SHA pin; review an upgrade candidate monthly, not on every upstream change.** The lane maintainer triggers a proposed `qualify:jolt-upgrade` task and submits the manifest change for review. Permit an out-of-cycle candidate for a blocking correctness/security fix. Never pull main during ordinary tests.

Reasons:
- A calendar should trigger evaluation, not force promotion. Keep the last passing revision if qualification fails.
- Gate each candidate on both platforms: clean tracked load; codec/hash/integer fixtures; applicable fast/all/slow suites; shared VM traces; native-library provenance; parser isolation/GC/lifetime tests; UDP probes; and cross-host peers once implemented. Record source/submodule SHAs and rollback coordinates.
- Set **no numerical JVM-relative performance promise now**. Begin measurements at J0a/J0c, retain cold/warm startup, p95 submission latency, throughput and memory reports, and compare against both JVM and the previous jolt pin on identical workloads. Establish workload-specific budgets after stable J8 evidence; no single runtime ratio. Timing noise must not become a correctness waiver. The existing plan distinguishes reporting from a later threshold gate (r6 plan:261).

**Reverse-if:** repeated upstream breakage warrants quarterly qualification; an urgent reproducible fix warrants immediate qualification. Neither changes the gate.

## Recommendation Q6

**Permit a JVM WebSocket sidecar at parity, with an explicit dependency label. Do not require a new WebSocket implementation to earn semantic parity.**

Reasons:
- A local adapter is not intrinsically a privileged network node. Require every peer to retain dial/listen capability, local custody and independent lifecycle; forbid a mandatory shared gateway. The doctrine prohibits privileged readers and extra contracts, not helper processes (`docs/design/datom.world.md:50–60,70`).
- There is already a JVM connect/bind/unbind implementation (`src/clj/yin/repl/host/jvm.clj:1–16`). Reusing it reduces protocol implementation risk, although subprocess composition still needs bounded buffering, failure and shutdown tests.
- **R, read not run:** jolt supplies nonblocking TCP/poller behavior, but documents receive-error-as-EOF (`stdlib/jolt/socket.clj:12–23`, under the spike checkout). TCP is groundwork, not WebSocket qualification. A direct implementation needs handshake, framing, masking, fragmentation, close and backpressure tests; SHA-1 through libcrypto solves only one small component. The repository already requires exhaustive WebSocket outcomes and terminal-event discipline (`docs/design/dao.stream.ws.md:63–64,152–161`).
- This sacrifices the strongest standalone-native packaging claim. Say “jolt host parity, JVM WebSocket sidecar required”; never advertise that deployment as JVM-free. Parser selection remains unchanged.

**Reverse-if:** measured sidecar deployment/resource costs are unacceptable, or the owner makes JVM-free network deployment a requirement. Then commission a separately gated direct adapter, preferably reusing a qualified protocol library rather than assuming socket availability makes RFC conformance cheap.

## Recommendation Q7

**Keep the landed contract. J0b verifies clean transport-error on would-block; do not add full.**

Reasons:
- The writer explicitly excludes full and admits only ok/transport-error/closed from the host (`src/cljc/dao/stream/datagram.cljc:25–33,294–300`). A jolt adapter cannot amend that unilaterally.
- Distinguish local nonacceptance from later UDP loss: EAGAIN means this attempt failed cleanly. Preserve its reason and the send-failed event; no hidden queue, production send wait or silent success. Observers must not count answer plus event twice (`docs/design/dao.stream.datagram.md:137–152`).
- Adding full requires a shared semantic definition and every host’s implementation/tests to distinguish nonacceptance consistently, including asynchronous error reporting. It is not a jolt-only improvement.

**Reverse-if:** cross-host evidence demonstrates useful, consistently observable retryable nonacceptance. Amend the shared contract first; then jolt returns full only for proven nonacceptance, with no implicit retry queue.

## Missed questions

None before the local-build probes. Redistribution licensing remains a later release gate, not something to waive to start feasibility (`antlr/python3/manifest.edn:127–144`).

## Confidence

Q5: high. Q6: medium. Q7: high.

## Unverified

No builds, benchmarks, UDP or WebSocket probes run. Sidecar cost, native WebSocket effort and platform backpressure behavior remain unmeasured. Recommendations are independent; other mob answers were not read.
