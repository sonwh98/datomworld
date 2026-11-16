Created-GMT: 2026-11-16 14:40:24 UTC
Created-Local: 2026-11-16 21:40 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yin.repl jolt orchestrator seat)

# The cross-host DHT interop probe: JVM publishes, jolt fetches — NOT YET working; the recorded-open gate is exactly the gap

Owner question: can yin.repl on the JVM compile code and jolt call it by attaching to the same
DHT? Probed live on loopback (macOS, master head + the J7 slices). Answer: the architecture
supports it and every layer EXISTS on both sides, but the end-to-end path does NOT close yet —
and the failure is precisely the sections 6-7 gate recorded open in §10.1.

## What works (verified live)

- JVM: the canonical publish walkthrough green — publisher A (dht init, auto-key, principal
  ed25519:bc5f...778b), storing peer B, `(yin.link/publish 'my.lib '[f])` returns
  `{:module 'my.lib, :address :segment/blake3-e613c45d..., :links {:yin.ast/code :ok, ...}}`,
  join token printed.
- jolt: the dht: composition boots, binds its UDP socket over the new dao.stream.udp seam,
  registers its peer, sends fetches, and reports honest statuses (pending requires, exhausted
  fetch, join tokens).

## What does not close (two concrete gaps, in order)

1. **Signed-name resolution on jolt is absent**: `(require 'my.lib)` on jolt goes pending, then
   `Module link refused: absent`. Name resolution follows the publisher's SIGNED HEAD BOARD —
   and the head-board/head-follow namespaces are among those still excluded on the jolt lane
   (jolt_j2_lane.clj:182-218, flagged in the J7 review as the P1 reconciliation). Without a
   jolt-side head follower, the reader cannot learn the name->manifest mapping.
2. **Fetch-by-address does not complete cross-host**: bypassing names, jolt's
   `--dht-manifest :segment/blake3-e613c45d...` engages the transport (asks its peer) but the
   JVM node's log shows NO incoming fetch activity — jolt's request either never parses on the
   JVM wire or is silently dropped. "no peer produced :segment/... (exhausted)". The JVM<->JVM
   walkthrough works; the jolt<->JVM wire leg does not.

## Disposition

This probe is the first cross-host interop evidence for the sections 6-7 gate, and it is RED.
The gate is real: the remaining J7 work is (a) the jolt head-board follower (or an agreed
address-only fetch contract), and (b) wire-level debugging of the jolt fetch request against
the JVM node's serve path (packet capture on both sides). Until then, the use case "JVM
compiles, jolt calls via the same DHT" is NOT possible end-to-end — on JVM/JVM it is (the
walkthrough), and jolt-side every ingredient exists in isolation.

Suggested next commissioning: a targeted J7 slices 3-4 round (wire interop + the jolt head
follower), with this probe as its baseline.

— the yin.repl jolt orchestrator seat
