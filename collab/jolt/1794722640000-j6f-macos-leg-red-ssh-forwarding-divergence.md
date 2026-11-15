Created-GMT: 2026-11-15 06:04:00 UTC
Created-Local: 2026-11-15 13:04:00 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yin.repl jolt orchestrator seat)

# J6f macOS arm64 leg: RED over the SSH-forwarding route — escalated to the owner

## The runs (two rounds, same unit, same signature → terminal-state escalation)

Both server-role gate runs (gate-proposal-server, gate-proposal-server2) against the native
darwin-arm64 testee through the README-sanctioned SSH-forwarding route (pinned Docker suite
on jupiter, native testee on the Mac, `ssh -R` loopback forward) FAILED with the same shape:

- run1 (tunnel mistimed, truncated at case 9.3.2): 16 FAILED + 63 UNCLEAN + 41 missing
- run2 (clean timing, full): 300/301 received, **16 FAILED + 63 OK/UNCLEAN + 10 FAILED-close,
  accepted=false**, 2036 s (vs 202 s on Linux loopback — the tunnel is ~10x slower)
- Retained: summary-macos-server.json (run2) in j6f-evidence-jupiter/ beside the Linux records.

## The failure signature

The suite's per-case reports for the FAILED block (1.1.1-1.1.8, 1.2.1-1.2.8, contiguous)
read: **"The WebSocket opening handshake was never completed"** — the suite sent its 243-byte
upgrade, received zero octets back (`rxOctetStats: {}`), waited ~30 min, then the peer dropped
TCP. The 6.x UNCLEAN block (63 cases) is close-handshake incompleteness on the same path.

## What is ruled OUT (the adapter is clean on every shape tested directly)

Local darwin-arm64 probes against the IDENTICAL testee process (bootstrapped jolt b389f1a7,
adapter dao.stream.ws.jolt), direct loopback: pipelined masked fragments (coalesced single
write) echo correctly; split fragments echo; byte-dripped upgrade handshake completes;
invalid UTF-8 answers close 1007 with reason; fragment-then-close answers clean 1000; close
echoes 1000. Even through the tunnel interactively (probe from jupiter over the forward),
handshake + pipelined masked fragments work. The 1.1.x fragmentation logic and 6.x close
logic are demonstrably correct on darwin — the Linux runs' 298/3 clean verdict used the same
source.

## The open suspects (for the diagnosis round)

1. ssh -R channel churn under the suite's connection pattern (301 sequential connections;
   MaxSessions/channel teardown interacting with the early fast cases) — a transport-layer
   artifact of the forwarding route, not the adapter.
2. The testee's accept/owner-pump behavior on darwin under that connection pattern (J6d's
   listener cap 64 vs the tunnel's connection delivery timing).
3. An sshd-side loopback-forward quirk on jupiter (the suite's Docker bridge → sshd).

Note the evidence bias: every probe I could run interactively passes; the failing cases die
with ZERO response bytes, which points at connection delivery, not WebSocket framing.

## Owner disposition requested (per the terminal-state rule)

(a) Authorize an engineer diagnosis round (codex) — packet-capture on the Mac side during a
gate run to split transport vs testee;
(b) Install Docker Desktop on the Mac and run the pinned suite locally (no tunnel variable);
(c) Rule the Linux-only Docker evidence sufficient for J6 closure and record the macOS leg
as an accepted gap.

— the yin.repl jolt orchestrator seat
