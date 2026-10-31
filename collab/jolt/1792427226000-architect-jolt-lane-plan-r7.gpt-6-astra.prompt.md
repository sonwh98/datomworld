Created-GMT: 2026-10-19 16:27:06 GMT
Created-Local: 2026-10-19 23:27:06 +07
Coding-Agent: codex
Session-ID: 01a14f30-ca56-7530-bb93-3a870eca7a7b
# Task: jolt host-lane plan, revision r7 (fold Q5/Q6/Q7 answers; proposed defaults for three mob questions)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-19 23:27:06 +07 | Status: active | Rationale: plan author; resumed to fold owner decisions

Base: your r6 (collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md). Mob synthesis: collab/1792427000000-architect-jolt-mob-synthesis.orchestrator.findings.md.

OWNER DECISIONS to record as settled, verbatim quotes:
- Q5: "record the mob's answer", then for the interval: "opus when needed". => pin jolt by full SHA in jolt-toolchain.edn; upgrade only through a named two-platform gate, triggered by owner/orchestrator, never silent; upgrade "when needed" = an upstream fix we need lands, the resolved Chez version changes, or the pin is older than ~30 days; prefer release-tagged SHAs; NO numeric JVM-ratio budgets until J8 evidence, but measure and report from J0.
- Q6: "native websocket, no sidecar". => jolt carries its own WebSocket over jolt.socket for J6/J8; no JVM sidecar in the parity label. NOTE this overrides your own r6 recommendation (you recommended a disclosed sidecar); record it as the owner's ruling and rewrite J6/J8, labels and risks accordingly (RFC 6455 handshake with SHA-1 via the qualified libcrypto, framing, masking, fragmentation, ping/pong, close, backpressure via the host seam {:send! :close! :queued-bytes}; dao.stream.ws keeps the semantics).
- Q7: "keep landed contract". => no :full; would-block (EAGAIN/EWOULDBLOCK, plus ENOBUFS on macOS) is :dao.stream/transport-error plus the send-failed event; J0b tests only that.

PROPOSED DEFAULTS awaiting owner veto tomorrow. These are the ORCHESTRATOR's proposals, NOT owner decisions and NOT mob consensus (the mob only raised the questions). Write them in the plan as "proposed default, owner may veto", and keep them out of any "settled" list:
1. Platforms for J0b/J0c: macOS arm64 may report first; Linux x86_64 evidence is required before the slice is closed (the plan already allows single-platform partial reporting only for J0a; this extends it as an explicit interim state).
2. Local patches on the pinned jolt SHA: allowed only when recorded in the toolchain file with rationale and upstreamed in parallel; no silent fork.
3. Chez: pin the exact version in mise.toml and the toolchain file (not "latest"); a Chez change is an upgrade-gate trigger.

STILL OPEN (list at the end as the only remaining owner questions): jolt/Chez licensing and redistribution in local-build packages (EPL-2.0 for jolt; note the existing local-build-only limit); whether wss:// is in scope for parity (assume not; the JVM host has no TLS).

Write a NEW file, the full plan (not a diff), same header fields:
/Users/sto/workspace/datomworld/collab/1792427226000-architect-jolt-lane-plan-r7.gpt-6-astra.findings.md
Do not edit earlier files. Write only that file. Keep it internally consistent for a cold reader; remove the superseded sidecar text except a short decision-history note; update milestone labels (the parity label no longer mentions a JVM sidecar); add an r7 change note. Mark unverified claims R. No src/ edits; nothing is commissioned.
