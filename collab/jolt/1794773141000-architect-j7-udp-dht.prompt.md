Created-GMT: 2026-11-15 20:05:41 UTC
Created-Local: 2026-11-16 03:05:41 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: J7 architect round — the UDP + DHT composition on jolt
Role: architect
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-16 03:05 +0700 | Status: active | Rationale: design-before-implement on a new transport slice

Produce the J7 slice DESIGN (not the implementation). Read first: docs/design/yin.repl-jolt-implementation.md (§8.3, §8.4, §9, §10.1 J0b/J7 rows), src/cljc/yin/shell/dht.cljc (esp. the :jolt refusal at :255), src/jolt/dao/stream/ws/{tcp,pump}.jolt and the J0b probe remnants, docs/design/dao.jing.dht.md section 10, docs/design/yin.repl.dht-composition.md.

Deliver, as findings in collab/jolt/<ts>-architect-j7-design.findings.md (committed on this branch):

1. The exact surface yin.shell.dht needs from a jolt host (transport step/send/recv datagrams, time, crypto signing for the signed HEAD, file persistence, announce), mapped against what src/jolt already provides (TCP substrate J6d, poller, crypto) — a gap table.
2. The datagram contract for jolt per §8.4 (would-block/ENOBUFS = transport-error + send-failed; no full; no hidden retries; nonblocking recv) and where it lands (new src/jolt/dao/stream/udp.jolt over jolt.socket.native, reusing the J0b probe evidence).
3. The composition plan for the :jolt arm of yin.shell.dht:store-spec->store — what composes, what stays refused, in what order (slices with falsifiable acceptance each).
4. The J0b regression set to retain (§8.3 legs) and the J7 acceptance gate mapping to §10.1's J7 row.
5. What is explicitly OUT of scope (multi-peer testnet topologies, NAT traversal).

Constraints: no top-level conditional requires (a6c25106); the shared cljc stays portable; jolt arms live in src/jolt/*.jolt whole-file overrides or reader arms ONLY where the whole-file override pattern doesn't apply. Commit findings + any design-diagram files on this branch.
