Created-GMT: 2026-10-19 17:00:49 GMT
Created-Local: 2026-10-20 00:00:49 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J0b - UDP over FFI feasibility probe on jolt (macOS arm64)
Role: Stream & Network Engineer (probe implementation)
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 00:00:49 +07 | Status: done (macOS arm64 record) | Rationale: spike session that knows the jolt checkout

## Verdict

**macOS arm64: raw nonblocking UDP over jolt.ffi is feasible. All five §8.3 items PASS on this platform, with one stated limit on item 3.**
The probe ran 3 times in the foreground, and each run gave 37 pass / 0 fail with exit 0.

The limit: loopback never produced EAGAIN or ENOBUFS on send naturally. That path is covered only by labelled native-seam injection, together with real successful syscalls.

**The Linux x86_64 record (shiva) is NOT RUN, so the J0b gate as a whole is incomplete, not passed (§8.3.5).**

| §8.3 item | macOS arm64 | Linux x86_64 |
|---|---|---|
| 1 bind/readback, payload + source address, whole packets, short send = error | PASS (short send: injected only) | NOT RUN |
| 2 N=1200 and N=8192, N+1 / over-buffer = error, platform limits, EMSGSIZE separately | PASS | NOT RUN |
| 3 EAGAIN/ENOBUFS as raw `:would-block` + errno, no accepted packet | PASS by injection; natural would-block on send NOT observed (see 3) | NOT RUN |
| 4 empty receive parks the fiber via jolt.io-poller, other fiber progresses, packet wakes, cancel/close/cleanup | PASS | NOT RUN |
| 5 records | PASS (this file + logs) | NOT RUN |

## 1. Environment (verified)

- **Machine:** arm64 (`uname -m`), macOS 26.6.2 (25G83), Apple clang 21.0.0. jolt reports `os.name="Mac OS X"`, `os.arch=aarch64`.
- **jolt:** `build/jolt-spike/jolt` at `96d7b8662d7c491722445bf976b2e25e36ce5d63` (`git rev-parse HEAD`). The working tree is clean; I did not edit or re-pin it.
- **Chez:** `~/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme --version` gives `10.4.1` (mise).
- **Commands, from the repo root, foreground:**
  - `build/jolt-spike/j0b/run.sh`. This sets `JOLT_CHEZ` and runs `build/jolt-spike/jolt/bin/jolt build/jolt-spike/j0b/udp_probe.clj`.
  - Logs: `build/jolt-spike/j0b/run-{1,2,3}.log` (each `SUMMARY 37 pass, 0 fail`, exit 0).
- **Platform limits** (`sysctl`, `ifconfig lo0`):
  - `net.inet.udp.maxdgram: 9216`
  - `net.inet.udp.recvspace: 786896`
  - `kern.ipc.maxsockbuf: 8388608`
  - `lo0` MTU 16384
- **Socket options read by the probe:** default `SO_SNDBUF` 9216 and `SO_RCVBUF` 786896.

## 2. Bindings and constants (verified against MacOSX.sdk headers)

| name | value | header |
|---|---|---|
| SOCK_DGRAM | 2 | sys/socket.h:112 |
| SO_SNDBUF / SO_RCVBUF | 0x1001 / 0x1002 | sys/socket.h:155-156 |
| MSG_TRUNC (not used) | 0x10 | sys/socket.h:574 |
| IPPROTO_UDP | 17 | netinet/in.h:121 |
| EAGAIN = EWOULDBLOCK | 35 | sys/errno.h:129-130 |
| EMSGSIZE | 40 | sys/errno.h:137 |
| ENOBUFS | 55 | sys/errno.h:170 |
| EBADF | 9 | sys/errno.h:96 |
| socklen_t | uint32 | arm/_types.h:117 |

This confirms the review-reported values for macOS: SOCK_DGRAM 2 and EMSGSIZE 40.

New bindings, defined in the probe only (`udp_probe.clj`), through `ffi/defcfn` with `{:capture-native-error true}`. Both answer `[result errno]`.
- `sendto [:int :pointer :size_t :int :pointer :uint] :ssize_t`
- `recvfrom [:int :pointer :size_t :int :pointer :pointer] :ssize_t`

Neither binding is `:blocking`, because the fd is O_NONBLOCK, so neither call can pin the carrier.

Reused from jolt, none of which hardcodes SOCK_STREAM:
- `jolt.socket.native`: `c-socket`, `c-bind`, `c-close`, `close-on-exec!`, `set-blocking!`, `make-sockaddr`, `alloc-sockaddr`, `sockaddr-ip`/`-port`, `local-address`, `get-int-option`/`set-int-option!`
- `jolt.io-poller`: `wait-ready`, `cancel!`, `forget!`, `debug-state`, `clock-ms`
- `jolt.fibers`: `spawn`, `join`, `state`

Truncation is detected with an **N+1 receive buffer sentinel**, not recvmsg/MSG_TRUNC, so recvmsg is not bound. macOS `recvfrom` silently truncates a packet larger than the buffer, which is why the sentinel is needed.

Raw result vocabulary:
- `send!`: `:sent`, `:would-block` (errno 35 or 55), `:emsgsize`, `:error`, `:short-send`
- `recv!`: `:packet`, `:oversize`, `:would-block`, `:error`

## 3. Evidence per item (run-1.log; runs 2 and 3 identical except ports and timestamps)

**Item 1.**
- **Bind and readback:** `udp-open` binds nonblocking 127.0.0.1:0, and `getsockname` reads back distinct ephemeral ports (for example 57132 / 52555).
- **Round trips, N=1200 and again N=8192:** I compared payload bytes and source address `{:ip "127.0.0.1" :port <sender>}`.
  - 0 bytes: sent n=0, received a 0-byte `:packet`.
  - 8 bytes with embedded NULs `[0 1 0 0 255 0 65 0]`: exact match.
  - Limit-sized 1200 and 8192: exact match.
- **Consecutive packets:** 5 distinct packets (10 to 14 bytes) arrived one per receive, in order, followed by `:would-block` errno 35. So one receive consumes exactly one packet.
- **Short send [INJECTED]:** the seam answered `[99 0]` for a 100-byte send. The result was `{:result :short-send :n 99 :len 100}` with nothing sent after it (receiver `:would-block`). A natural short UDP send cannot be produced on macOS, where sendto is all-or-nothing.

**Item 2.**
- **Platform maximum:** bisection over [1, 65507] found the largest sendable size is 9216 (= `net.inet.udp.maxdgram` = default SO_SNDBUF). 9217 gave `{:result :emsgsize :errno 40}` with nothing delivered. That is an error, not a truncation. Tested separately from the oversize cases.
- **N=1200:**
  - 1200 bytes gave `:packet`.
  - 1201 gave `:oversize :received 1201`.
  - 1300 (larger than the N+1 buffer) gave `:oversize :received 1201`.
  - No prefix was returned, and the queue was empty afterwards (the oversize packet was consumed whole).
- **N=8192** (the configured larger N, below 9216): same pattern with 8192, 8193 and 8292.

**Item 3.**
- **[NATURAL]** 20000 x 1024-byte sends with SO_SNDBUF set to 2048 and no reader gave `{:sent 20000}` and **no would-block**. Only 745 packets were delivered (about 763 KB, which is under recvspace 786896). The other 19255 were dropped silently by the kernel. **Loopback receiver overflow on macOS is silent loss, not EAGAIN/ENOBUFS.** That fits the datagram contract (no reliability beneath it), but it means natural send would-block was never observed here.
- **[INJECTED]** The seam returned `[-1 35]` and `[-1 55]`. The results were `{:result :would-block :errno 35}` and `{:result :would-block :errno 55}`, and no packet was accepted.
- **[NATURAL]** A real sendto/recvfrom round trip (1024 bytes) succeeded right after the injection. The receive-side would-block is natural (`recvfrom` on an empty socket gives errno 35).
- The datagram-writer mapping to transport-error plus send-failed (§8.1) is a J7 surface and is not in this probe.

**Item 4.**
- **Parking and wakeup:** the waiter fiber's `recv!` returned `:would-block`, then it parked in `poller/wait-ready fd :read` (the fiber path, kqueue). Meanwhile a worker fiber ran 50 ticks of `Thread/sleep 2` and observed `(fibers/state waiter) = :parked`, then sent 77 bytes. The waiter woke in the same millisecond and got the `:packet` after 1 wait. Trace from run-1: `[6761 :waiter-start] [6886 [:worker-ticked 50 :waiter-state :parked]] [6886 [:worker-sends …]] [6886 [:waiter-got :packet 1]]`.
- **Cancellation:** a fiber parked on a fresh empty socket was in state `:parked`. `poller/cancel!` released it, its retry was `:would-block` (no packet), and `join` returned `:released`. A further wait on the cancelled fd returned in about 0.04 ms.
- **Close:** `forget!` then `close` returned 0. A later `recvfrom` gave `{:result :error :errno 9}` (EBADF), never a packet or would-block. The poller table had no entry for that fd.
- **Cleanup (item 5 rows):** both probe sockets closed (close 0). The final `debug-state` was `{:pending {} :fds {} :ev-errors 0 :stale-consumes 0}`.
- **Not tested:** the receives in items 1 to 3 run on the main thread, so they use `wait-thread`, the per-call kqueue path. The fiber path is exercised in item 4. Send-side readiness waits were not tested, and no harness retries were used.

## 4. What this does not show

- Linux x86_64 (shiva): NOT RUN. Its constants (EMSGSIZE 90, EAGAIN 11, ENOBUFS 105) are unverified, and its epoll path is unexercised.
- IPv6 UDP was not probed (out of §8.3's list).
- No natural send-side EAGAIN/ENOBUFS observation on macOS loopback. A non-loopback interface might produce ENOBUFS, but I didn't try that.
- No datagram/DHT conformance and no J7 integration, as scoped.

## Files created
- `build/jolt-spike/j0b/udp_probe.clj` (probe + bindings + harness)
- `build/jolt-spike/j0b/run.sh` (foreground runner)
- `build/jolt-spike/j0b/run-1.log`, `run-2.log`, `run-3.log`
- this report

Nothing in src/, test/, docs/, mise.toml, deps.edn or the jolt checkout was touched. Nothing was staged or committed.
