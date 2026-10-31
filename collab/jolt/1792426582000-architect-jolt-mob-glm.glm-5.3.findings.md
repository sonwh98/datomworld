Created-GMT: 2026-10-19 16:19:48 GMT
Created-Local: 2026-10-19 23:19:48 +07
Coding-Agent: glm
Session-ID: bfa3b280-0891-4504-b4fb-24e4543b354a
# jolt mob — Q5/Q6/Q7 (glm-5.3, independent)

Basis: plan r6, Opus r5 review, datom.world.md, dao.stream.datagram.md/cljc, ws.cljc + adapters, jolt @96d7b866 (HEAD verified); nothing run; only this file written.

## Q5 — cadence and budgets

**Answer.** Pin by full SHA in `jolt-toolchain.edn`. Freeze through J0→J8, bumping only for a blocking upstream fix or security issue; after J8, quarterly review plus event-driven security bumps. Each bump passes a named gate: SHA delta, CHANGELOG read, clean-cache J0a load, `bb test:jolt` fast lane, J1 content corpus vs JVM, J0c parser packet parity, three lanes green. Trigger: orchestrator/owner only, via `bb bootstrap:jolt` update mode (plan §8), never silent. Budgets: thresholds only after J8 evidence, but fix the measurement protocol now (plan §9 J8) plus one tripwire: no shared-test weakening; a behavior regression (startup/latency far past the JVM baseline) forces investigation, not auto-pass.

**Reasons.** (1) Measured churn: 989 commits/30 days (`git log --since=2026-09-19`, HEAD `96d7b866`), 15 tagged releases (CHANGELOG.md:48,:3035), single maintainer, no coordinates — monthly tracking would eat the lane in re-verification; the milestone needs no fresh jolt. (2) Behavior changes land between tags (CHANGELOG.md:12-19,:23-27), and no benchmark of this SHA exists — thresholds set now would be unfalsifiable.

**Reverse-if.** Upstream stabilizes → monthly with the slimmer fast-lane+corpus gate; bumps repeatedly break shims → per-milestone only.

## Q6 — WebSocket at parity

**Answer.** jolt carries its own WebSocket over jolt.socket; no JVM sidecar in the J8 parity label. A sidecar is admissible only as disclosed throwaway J6 scaffolding, retired before qualification.

**Reasons.** (1) The adapter is the host's job: `dao.stream.ws` "deliberately knows no WebSocket library" (ws.cljc:4-8); every host ships its own (jvm.clj 471, node.cljs 339, dart.cljd 338 lines). Jolt would be the first to ship none: a permanent second process, crash domain and jolt+JVM coupling inside one peer, against the lane's standalone-binary point. (2) The sidecar still needs jolt.socket for local IPC, so that groundwork is built either way; only RFC 6455 is saved: handshake (SHA-1 via the vetted libcrypto wrapper, Q4 policy), masked framing, close codes, pressure→`:full` mapping the ws contract already defines. (3) P2P uniformity: a peer whose networking dies with a supervised sidecar is operationally a different node (datom.world.md:96-97); the label carries "requires JVM" forever. J6's cross-host fixtures retire front-loaded RFC 6455 risk — sidecar risk is permanent.

**Reverse-if.** Demonstrated infeasibility within J6 (none today: nonblocking sockets + poller parking, socket.clj:21-23, io_poller.clj:5-11), or an owner schedule ruling buying it with permanent disclosure.

## Q7 — UDP would-block

**Answer.** Keep the landed contract; do not amend. A jolt sendto returning EAGAIN/EWOULDBLOCK maps to clean `:dao.stream/transport-error` (nothing sent) plus the `send-failed` lifecycle event, per datagram.cljc:25-29; J0b tests only that.

**Reasons.** (1) `:full` is excluded on doctrine, not convenience: "a socket under pressure loses datagrams — the medium's declared nature, not a refusal" (datagram.cljc:30-33; dao.stream.datagram.md:141); writer admits only ok/transport-error/closed (:295-299). (2) It would be one implementation adding a contract (datom.world.md:70): the other seams cannot produce it (JVM send blocks; Node/Dart queue in user space), so jolt's errno would rewrite the shared contract alone. (3) `:full` carries queue/backpressure semantics this layer refuses to compose (datagram.cljc:4-6); backpressure belongs in composed middleware (dao.stream.datagram.md:143-145).

**Either way** jolt must: retry EINTR internally (native.clj:268-270), never retry or queue EAGAIN inside append!, never park the fiber on send; interpreters above treat it like any send failure. **Reverse-if:** a real composition shows interpreters cannot absorb local pressure as transport-error and every host can faithfully produce `:full` — neither holds today.

## Missed questions

- Which physical/CI machine owns the Linux x86_64 leg of the both-platform J0b/J0c gates?
- May local-build packages redistribute jolt (EPL-2.0, LICENSE/NOTICE at checkout root) or Chez artifacts, with what notices? Q4 covered libblake3/ANTLR.
- May a blocking jolt bug be fixed by a temporary local patch or upstream PR, or is a fork categorically out? No patch policy exists.
- `mise.toml:8` `chezscheme = "latest"` stays a moving selector while jolt is SHA-pinned — exact-pin Chez, or accept drift-and-record?

## Confidence

Q5 medium (judgment over verified churn); Q6 medium-high (RFC 6455 cost estimated); Q7 high.

## Unverified

Jolt runtime claims are read-not-run (WebSocket/SHA-1 absence from grep); no probes/builds run; counts and churn from reading/git. ANTLR inherited from the plan.
