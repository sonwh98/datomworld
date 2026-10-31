Created-GMT: 2026-10-20 08:39:47 GMT
Created-Local: 2026-10-20 15:39:47 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J0b UDP probe made platform-aware (macOS arm64 and Linux x86_64)
Role: Stream & Network Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 15:39:47 +07 | Status: done (macOS verified; Linux NOT RUN by me) | Rationale: owner: "push the jolt-j0a branch and run J0b on shiva"

**macOS behaviour is unchanged:** `SUMMARY 36 assertions: 36 pass, 0 fail; 3 records`, exit 0, on 5 foreground runs. The PASS/RECORD labels are identical to `run-6.log`.

**The Linux expectations below are written but UNTESTED.** I cannot run on shiva.

Files touched: only `build/jolt-spike/j0b/udp_probe.clj`, `build/jolt-spike/j0b/run.sh`, and new logs `run-7..11.log`. `run-1..6.log` are kept. Nothing was staged.

## What changed

### `udp_probe.clj`

1. **Platform selection.** The platform is `jolt.socket.native/os`, the same value jolt's socket layer and sockaddr helpers branch on (`:macos`, `:linux`, `:windows`, derived inside `native.clj`). The probe never reads `os.name` itself. The constants are one table per platform:

| constant | `:macos` (MacOSX.sdk, as before) | `:linux` (shiva headers, orchestrator-verified) |
|---|---|---|
| SOCK_DGRAM / IPPROTO_UDP | 2 / 17 | 2 / 17 |
| SO_SNDBUF / SO_RCVBUF | 0x1001 / 0x1002 | 7 / 8 |
| MSG_TRUNC (unused) | 0x10 | 0x20 |
| EAGAIN = EWOULDBLOCK | 35 | 11 |
| EMSGSIZE | 40 | 90 |
| ENOBUFS | 55 | 105 |
| EBADF | 9 | 9 |

   Any other platform throws at load. The header line prints `platform=`, the `os.name`/`os.arch` the shim gives (possibly nil on Linux) and the whole constant table. The sockaddr layout (macOS `sin_len` byte versus Linux 16-bit family) is already handled per platform by `native/make-sockaddr` and `sockaddr-*`, so nothing in the probe changed for it.
2. **Constants flow through everywhere:**
   - send-error classification: EAGAIN/ENOBUFS give `:would-block`, EMSGSIZE gives `:emsgsize`;
   - recv `:would-block`;
   - the injected-errno cases use the platform's EAGAIN and ENOBUFS (11 and 105 on Linux), and stay labelled `[injected]`;
   - the EBADF check after close;
   - the label of the empty-receive check now prints the platform's errno (still reads "errno 35" on macOS).
3. **The platform maximum is measured, not assumed.** The bisection over [1, 65507] was already measured, and it is unchanged:
   - macOS stops at 9216 (`maxdgram` = SO_SNDBUF);
   - on Linux it should reach 65507, the IPv4 UDP payload limit.

   The EMSGSIZE-above-max check uses the platform's EMSGSIZE. The "configured larger N (8192) below the observed max" check asserts `(< 1200 8192 max-ok)` against the measured value on both platforms.
4. **SO_SNDBUF read-back.**
   - **macOS (unchanged):** the requested values 2048 and 9216 are read back as RECORDs only, to keep the 36/3 counts.
   - **Linux (one extra assertion):** `[linux] SO_SNDBUF read-back follows socket(7): >= 2 x requested`. It checks that both setsockopt calls answer 0 and that the read-back values are at least 4096 and at least 18432. Linux doubles the request for bookkeeping, clamps it at `net.core.wmem_max` (212992 on shiva, above both requests) and floors it at `SOCK_MIN_SNDBUF`. I chose "≥ 2×" so a floor above 2×2048 still passes, never equality.
   - The RECORD lines still show the raw values on both platforms.
5. **Natural would-block stays a RECORD** on both platforms (20000 × 1024-byte sends with no reader, histogram plus delivered count). Whatever Linux does is recorded, not asserted.
6. **Comments:** sendto all-or-nothing now says (macOS, Linux); socklen_t is 32-bit unsigned on both.

**Expected Linux totals: 37 assertions, 3 records** (the one extra Linux check).

### `run.sh` (POSIX sh; checked with `sh -n` and run under `/bin/dash`)

- **jolt binary:** `$JOLT`, else `build/tools/jolt/bin/jolt` (J0a layout), else `build/jolt-spike/jolt/bin/jolt` (spike layout). The probe path comes from the script's own directory, so it can be run from any checkout root.
- **`JOLT_CHEZ`:**
  1. env;
  2. else source `build/tools/jolt-env.sh` when present (J0a layout);
  3. else the mise 10.4.1 path if it exists;
  4. else unset, and jolt searches PATH (a line says so).
- **Prints:** the jolt path, jolt SHA and dirty count (git -C the jolt home), and the Chez version. Then, per OS:
  - **Darwin:** `uname -sm`, `sw_vers`, sysctls `net.inet.udp.maxdgram`, `net.inet.udp.recvspace`, `kern.ipc.maxsockbuf`, and the lo0 MTU.
  - **Linux:** `uname -sm`, the `/etc/os-release` PRETTY_NAME, `/proc/sys/net/core/{wmem_default,wmem_max,rmem_default,rmem_max}`, `/proc/sys/net/ipv4/{udp_wmem_min,udp_rmem_min,udp_mem}`, and `/sys/class/net/lo/mtu`.

  No macOS-only command runs on Linux, and every probe of the environment tolerates a missing file.
- **Ends with** `exit: N`.

## macOS evidence (foreground, this Mac, jolt 96d7b866, Chez 10.4.1)

| run | how | result |
|---|---|---|
| `run-7.log`, `run-8.log`, `run-9.log` | `build/jolt-spike/j0b/run.sh` from the repo root (spike layout default) | exit 0, `SUMMARY 36 assertions: 36 pass, 0 fail; 3 records` |
| `run-10-dash-j0a.log` | `JOLT=…/jolt-j0a/build/tools/jolt/bin/jolt dash build/jolt-spike/j0b/run.sh` | exit 0, 36/0/3 |
| `run-11-j0a-default.log` | from the jolt-j0a worktree root with `JOLT`/`JOLT_CHEZ` unset (exercises the `build/tools/jolt` + `jolt-env.sh` default; that worktree stayed clean) | exit 0, 36/0/3 |

- **Header** (`run-7.log`):
  - `platform= :macos` with constants `{:so-sndbuf 4097 … :eagain 35 :emsgsize 40 :enobufs 55 :ebadf 9}` (0x1001 = 4097);
  - sysctls `9216 786896 8388608`, lo0 MTU 16384;
  - max sendable 9216, EMSGSIZE errno 40.
- **Labels:** the PASS/FAIL/RECORD label sequence of `run-7.log` is identical to `run-6.log` (diff empty).

## Linux x86_64 expectations, all UNTESTED (for the shiva run)

1. **Platform and constants:**
   - `native/os` answers `:linux` and the header shows the Linux constant table. If the shim's nil `os.name` made `native/os` fall to `:linux` on shiva, that is the right answer there.
   - Bind, readback and sockaddr decoding work through the Linux sockaddr layout. This is jolt's code path and is unexercised by me.
2. **Packet semantics (item 1):** the same assertions as macOS, with EAGAIN 11 on the empty receive.
3. **Platform maximum (item 2):**
   - The bisection should find **65507**, and 65508 should give EMSGSIZE errno **90** with nothing delivered.
   - Big sends land in a 212992-byte receive buffer, which may drop some of the bisection's packets. The drain counts them; that is not asserted.
   - Item 2 for N=1200 and N=8192 should pass unchanged (8192 < 65507).
4. **SO_SNDBUF:** the read-back for 2048 is expected around 4608 (the doubled request, possibly floored at SOCK_MIN_SNDBUF), and 18432 for 9216. The `[linux]` assertion requires only ≥ 2× the request.
5. **Natural would-block (RECORD):** Linux may or may not produce EAGAIN/ENOBUFS when blasting with a small SO_SNDBUF. Loopback frees the send buffer on delivery, so I expect `{:sent 20000}` again with silent receiver drops, but it is unknown.
6. **Injected cases:** EAGAIN 11 and ENOBUFS 105 should give `:would-block` with no packet accepted.
7. **Poller and fibers (item 4):** this runs on jolt's epoll backend, not kqueue. The parked waiter, wakeup, `cancel!`, `forget!`, close then EBADF 9, and the empty poller table are all unexercised on Linux by me.
8. **`run.sh` on dash/Ubuntu:** the `/proc` and `/sys` reads and `/etc/os-release` parsing are untested on Linux. They were only shown not to run on Darwin. The script did run under `/bin/dash` on macOS.
9. **Toolchain on shiva:** the J0a provisioning of Chez 10.4.1 and the jolt checkout is a precondition. `run.sh` uses `build/tools/jolt-env.sh` if bootstrap has run there.

**Expected Linux total: 37 assertions + 3 records.** Any FAIL there is a real finding to report, not something to adjust the probe to.

Report: `/Users/sto/workspace/datomworld/collab/1792485587000-engineer-jolt-j0b-linux-port.claude-opus-5-5.findings.md`
