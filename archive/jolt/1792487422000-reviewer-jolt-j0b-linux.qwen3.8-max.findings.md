Created-GMT: 2026-10-20 09:10:22 GMT
Created-Local: 2026-10-20 16:10:22 +07
Coding-Agent: cmd
Session-ID: 61966f9d-4875-4b42-9545-b0dc8a2c9891
# Review: J0b platform-aware probe + Linux x86_64 evidence
Role: Reviewer (read-only) | Model: qwen/qwen3.8-max

## Verdict: SIGNED OFF

## Findings

| id | sev | file:line | evidence | correction |
|---|---|---|---|---|
| F1 | info | build/jolt-spike/j0b/run.sh:12-24 | `JOLT` default and the `jolt-env.sh` source are CWD-relative (probe path is `$0`-relative); must run from the repo root. Header says so; run-11 exercised the J0a layout. | None required; optionally resolve both from `$dir`. |
| F2 | info (record) | udp_probe.clj:249-255, run-7.log/shiva-1.log RECORD lines | Natural send-side would-block never observed on either platform (histogram `{:sent 20000}` both); the `:would-block` send path and short-send rest on `[injected]` seams. §8.3:954-956 explicitly permits this when loopback cannot induce it, labelled separately, with real successful syscalls also exercised - all three conditions met ("[natural] real sendto/recvfrom succeed after injection"; natural receive-side EAGAIN asserted, errno 35/11). | Keep the RECORD in the J0b gate record; a real full-send-buffer induction remains unobserved on both platforms. |

## Checks

- (a) Constants all correct: Linux EAGAIN=EWOULDBLOCK 11, EMSGSIZE 90, ENOBUFS 105, EBADF 9, SO_SNDBUF 7, SO_RCVBUF 8, MSG_TRUNC 0x20, SOCK_DGRAM 2, IPPROTO_UDP 17; macOS 35/40/55/9, 0x1001/0x1002, 0x10, 2, 17. One table per platform via `native/os` (udp_probe.clj:21-29); any other platform throws at load. No cross-contamination: labels, injection errnos and classification all flow from `consts` (shiva log shows errno 11/105 labels, macOS 35/55).
- (b) The `[linux]` check (udp_probe.clj:265-269) is not a tautology: it requires both setsockopt rc 0 and real read-backs ≥ 2× request. Observed 4608/18432: 4608 is the SOCK_MIN_SNDBUF floor (above 2×2048=4096), 18432 exactly 2×9216 - matching socket(7) doubling/clamp/floor semantics; a non-doubling kernel would read 2048 and fail. `>=` (never equality) is the right tolerance for the floor; honestly labelled and Linux-only, keeping macOS at 36/3.
- (c) macOS 36 labels are an exact subset of Linux's 37 (only the `[linux]` check added); errno numerals in labels track platform constants. Nothing weakened or skipped on Linux: items 1, 2, 4, 5 fully natural on both (bisection measured 9216 vs 65507, EMSGSIZE 40/90 asserted with nothing delivered, oversize never yields a prefix and is consumed whole, MSG_TRUNC declared-unused per the N+1 sentinel, N=1200/8192 below both maxima, fiber park/wake/cancel/close/EBADF/poller-empty on epoll, cleanup counts). Item 3 is the injection-dependent one - see F2.
- (d) Report claims match the logs: it was written pre-shiva with Linux expectations flagged UNTESTED and predicted 37/3; shiva-1..3 each show exactly `37 assertions: 37 pass, 0 fail; 3 records`, exit 0, identical headers (jolt 96d7b866 dirty=0, Chez 10.4.1 threaded, Ubuntu 24.04.5). J0a precondition on shiva evidenced (bootstrap: pins verified, cmake 4.4.4 linux-x86_64; smoke `SUMMARY ok`). Item 5 records complete: SHAs, arch, sysctls/proc values, MTU, lengths/counts, error traces, wakeup traces, cleanup.
- (e) run.sh is POSIX sh; macOS-only commands (`sw_vers`, `sysctl`, `ifconfig`) sit strictly in the Darwin case arm; the Linux arm reads `/proc/sys`, `/sys/class/net/lo/mtu`, `/etc/os-release` with missing-file tolerance. Proven by execution: dash on macOS and three real Ubuntu runs.

## Could not verify

- Probe byte-identity local↔shiva (orchestrator's sha256, trusted); shiva-2/3 full label diffs (headers/summaries grepped, identical).
- Linux constant values against shiva's actual headers (matched against my knowledge of asm-generic/x86_64 values, not read from the machine).
- jolt epoll-poller internals beyond the logged traces; the run-6↔run-7 label diff claim.
