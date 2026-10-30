Created-GMT: 2026-10-19 16:25 GMT
Created-Local: 2026-10-19 23:25 +07
Coding-Agent: glm (orchestrator seat; synthesis only, no new judgment)
Session-ID: not-applicable (interactive seat)
# Task: jolt mob synthesis - owner questions Q5/Q6/Q7
Role: Lead Engineering Orchestrator (secretary: reports positions, does not adjudicate)

Positions (independent, each family did not see the others):
- gpt-6-astra (GPT, plan author): collab/1792426582000-architect-jolt-mob-astra.gpt-6-astra.findings.md
- claude-opus-5-5 (Claude): ...-opus.claude-opus-5-5.findings.md
- glm-5.3 (GLM): ...-glm.glm-5.3.findings.md
- gemini-4-argon (Gemini): ...-argon.gemini-4-argon.findings.md (agy conversation 9f39e80a-850d-4c56-bfb8-2586c76ccbce)
Brief: collab/1792426582000-architect-jolt-mob-questions.brief.md

## Q7 UDP would-block: UNANIMOUS 4/4 - keep the landed contract, no :full
EAGAIN/EWOULDBLOCK (opus adds ENOBUFS on macOS) -> :dao.stream/transport-error + send-failed event; append! never retries, queues or parks; J0b tests only that. Reason shared by all: :full is excluded by design (datagram.cljc:25-33), no other host can produce it, amending it is an implementation adding a contract (datom.world.md:70). Reverse only on DHT burst evidence plus a consumer that needs a transient signal, then as an all-hosts dao.stream.datagram change.

## Q5 cadence and budgets: UNANIMOUS on the shape, differs on the interval
All four: pin by full SHA in jolt-toolchain.edn; upgrade only through a named gate on both platforms (clean tracked load, J1 hash/CBOR corpus, applicable suites, J0b/J0c probes); owner/orchestrator-triggered, never silent; NO numeric JVM-ratio budget until J8 evidence (measure and report from J0 on). Interval: astra monthly candidate review (out-of-cycle for blocking fixes); opus on need / Chez change / pin older than ~30 days; glm freeze through J8, then quarterly + security bumps; argon biweekly. Verified by orchestrator: jolt has 989 commits in the last 30 days (git log --since=2026-09-19 in the checkout), 122 tags, single maintainer.

## Q6 WebSocket sidecar at parity: SPLIT 3-1
- NATIVE WebSocket over jolt.socket, no JVM sidecar (opus, glm, argon). Reasons: the ws adapter is each host's job (ws.cljc:4-8; JVM host 471 lines); portable dao.stream.ws already owns bounds and outcomes so only the host seam + RFC 6455 handshake/framing is new; SHA-1 via the already-qualified libcrypto; a sidecar contradicts the P2P no-privileged-node invariant and the standalone-binary argument; with the parser in-process the sidecar would be the only reason a parity node needs a JDK. glm: a sidecar is admissible only as disclosed throwaway J6 scaffolding. Reverse-if: jolt.socket cannot keep correct park/backpressure under J6 stress.
- PERMIT a disclosed JVM sidecar (astra, the plan author): reuses the existing JVM adapter (host/jvm.clj), lowers protocol risk; label "jolt host parity, JVM WebSocket sidecar required"; must not be a mandatory shared gateway. Reverse-if: sidecar cost unacceptable or JVM-free becomes a requirement. Confidence medium (others: opus high, glm medium-high, argon high).
Orchestrator note (not a ruling): the split follows the plan author vs the other three families. This is the owner's call.

## Questions the mob says the plan missed (de-duplicated; each needs an owner answer)
1. Linux x86_64 machine/CI for the two-platform J0b/J0c gates: who provides it? (opus, glm; argon asks the related question: may macOS arm64 report first, with Linux later, for J0b/J0c as the plan allows only for J0a?)
2. Local patches on the pinned jolt SHA: allowed, or must every fix go upstream first? (opus, glm)
3. Chez pin: mise.toml:8 is chezscheme = "latest" while jolt is SHA-pinned: exact-pin Chez, or accept drift and record it? (glm)
4. Licensing/redistribution of jolt (EPL-2.0) and Chez artifacts in local-build packages; Q4 covered only libblake3/ANTLR. (glm)
5. wss:// in scope for parity? Assumed no: the JVM host has no TLS. (opus)

## Unverified across the mob
No builds, probes or benchmarks run. macOS ENOBUFS-vs-EAGAIN, native RFC 6455 size, and SHA-1 coverage in jolt-crypto are estimates. Argon's and astra's line citations were not individually re-checked by the orchestrator beyond the churn count.
