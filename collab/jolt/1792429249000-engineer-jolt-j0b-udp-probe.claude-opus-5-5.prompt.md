Created-GMT: 2026-10-19 17:00:49 GMT
Created-Local: 2026-10-20 00:00:49 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Task: J0b - UDP over FFI feasibility probe on jolt (macOS arm64)
Role: Stream & Network Engineer (probe implementation)
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 00:00:49 +07 | Status: active | Rationale: spike session that knows the jolt checkout; implementation routes to claude/codex; codex out until 03:00 +07

Owner commissioned J0b (verbatim): "confirm defaults, licensing local only, ws only. commission J0b". Owner earlier: "yes, add the UDP probe to J0"; Q7: "keep landed contract". Scope is EXACTLY the J0b probe in section 8 (8.1-8.3) of /Users/sto/workspace/datomworld/docs/design/yin.repl-jolt-implementation.md (read sections 8, 10.1 J0b row and 10.2 first). It is raw transport feasibility only: not datagram/DHT conformance, not J7.

Where: jolt checkout /Users/sto/workspace/datomworld/build/jolt-spike/jolt (pinned @96d7b866; do NOT edit it, do NOT re-pin). Run jolt as in your spike (JOLT_CHEZ=~/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme, bin/jolt, see build/jolt-spike/*.sh). Put ALL new files under /Users/sto/workspace/datomworld/build/jolt-spike/j0b/ (untracked scratch). Define the extra libc bindings (sendto, recvfrom, SOCK_DGRAM, EMSGSIZE, ENOBUFS, MSG_* as needed) in your probe via jolt.ffi/defcfn on top of jolt.socket.native and jolt.io-poller helpers; verify constants from the macOS SDK headers, do not assume. Do not touch src/, test/, docs/, mise.toml, deps.edn or the jolt checkout. Do not stage or commit anything.

Acceptance (all from 8.3): (1) loopback nonblocking SOCK_DGRAM sendto/recvfrom with payload and source-address comparison for zero-length, embedded-NUL, consecutive and limit-sized packets, whole-packet semantics, short send = error; (2) N=1200 and a larger configured N, receive buffer at least N+1, record platform limits/sysctls, EMSGSIZE tested separately, oversize/truncation surfaced as an error never a successful prefix; (3) EAGAIN/EWOULDBLOCK/ENOBUFS as a distinct raw :would-block result with errno and no accepted packet; where loopback cannot induce it use deterministic native-seam injection labelled separately from natural observations; also exercise real successful syscalls; (4) an empty receive waits through jolt.io-poller yielding the fiber (another fiber progresses; a packet wakes the waiter); test cancellation, close and cleanup; (5) record toolchain/runtime SHA, architecture, exact commands, packet lengths/counts, error traces, poller wakeup evidence, cleanup.

Rules: run every command in the FOREGROUND (never background a test run). Mark what you ran as verified and what you did not; Linux x86_64 on shiva is NOT in this task (macOS arm64 only; the Linux record stays open). If jolt cannot do something, report the exact failure; do not paper over it with a stub. Put an honest verdict per acceptance item (PASS / FAIL / NOT RUN) in the report.

Write the report to /Users/sto/workspace/datomworld/collab/1792429249000-engineer-jolt-j0b-udp-probe.claude-opus-5-5.findings.md with the same header fields, then end your final response with the report path and the list of files you created.
