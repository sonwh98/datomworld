Created-GMT: 2026-10-19 17:12:47 GMT
Created-Local: 2026-10-20 00:12:47 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J0b UDP probe, round 2 (review fixes R1-R3, notes R4-R6)
Role: Stream & Network Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 00:12:47 +07 | Status: done (macOS arm64 record) | Rationale: resume the author to fix accepted review findings
- Status-Event: none

This report supersedes the evidence citations of the round-1 report
(`collab/1792429249000-engineer-jolt-j0b-udp-probe.claude-opus-5-5.findings.md`, left unedited).
Every number below is cited as `run-N.log:line`. The logs are under `build/jolt-spike/j0b/`.

## Review findings -> what changed

| finding | change |
|---|---|
| R1: two checks passed a literal `true` | Those two are now `RECORD` lines that cannot count as PASS, plus a third record for restoring SO_SNDBUF. The summary now separates the two: `SUMMARY 36 assertions: 36 pass, 0 fail; 3 records` (run-4/5/6.log:46). |
| R2: round-1 report cited ports, a trace and a latency found in no log | Cause: I took them from an exploratory run before I started saving logs. This report cites only run-4..6.log by file and line. The round-1 numbers (57132/52555, trace 6761..6886, "0.04 ms") are withdrawn. |
| R2 (MSG_TRUNC line) | Rechecked: in this SDK MSG_TRUNC is `sys/socket.h:574` (`grep -n MSG_TRUNC $(xcrun --show-sdk-path)/usr/include/sys/socket.h` gives `574:#define MSG_TRUNC 0x10`). The round-1 cite of 574 was correct; the reviewer's 576 does not match this SDK. MSG_TRUNC is still unused. |
| R3: SO_SNDBUF never read back | Both setsockopt results are now recorded and read back with `get-int-option`. Requesting 2048 gives `:setsockopt [0 35], :so-sndbuf-read-back 2048` (run-4.log:31). Restoring gives `[0 35]` and a read-back of 9216 (run-4.log:33). The errno slot (35) is meaningless when the result is 0. Effect on item 3: see below. |
| R4 (info): drain loops could hang on a persistent `:error` | New `drain!` stops on `:error` and emits a FAIL. It is used by the EMSGSIZE bisection drain and the item-3 drain. |
| R5 (info): larger N was hardcoded | New assertion `(< 1200 big max-ok)`: `{:N 1200, :larger-N 8192, :max-sendable 9216}` PASS (run-4.log:8). |
| R6 (info): jolt `c-bind` declares socklen_t as `:int` | Not changed: it lives in the jolt checkout, which is out of scope. Same width and ABI here, so benign. Carried to J7. |
| Reviewer could not verify SHA, exit code or sysctls from the logs | `run.sh` now writes them into each log: jolt SHA plus a dirty-file count (line 1), Chez version (2), uname/macOS (3), sysctls (4), exit code (47). |

## Verdict (unchanged in substance)

**macOS arm64: PASS on all five §8.3 items.** Item 3's send-side would-block is covered by injection only. **The Linux x86_64 record (shiva) is NOT RUN, so the J0b gate stays incomplete.**

| §8.3 item | macOS arm64 | Linux x86_64 |
|---|---|---|
| 1 | PASS (short send: injected only) | NOT RUN |
| 2 | PASS | NOT RUN |
| 3 | PASS by injection. Natural send would-block NOT observed (recorded, not asserted) | NOT RUN |
| 4 | PASS | NOT RUN |
| 5 | PASS (records in the logs) | NOT RUN |

## Runs

- Command, foreground from the repo root: `build/jolt-spike/j0b/run.sh > build/jolt-spike/j0b/run-N.log 2>&1`, for N = 4, 5, 6.
- All three runs: exit 0 and `SUMMARY 36 assertions: 36 pass, 0 fail; 3 records` (run-4/5/6.log:46-47).
- run-1..3.log are kept. They come from the round-1 probe, which had 37 rows including the two vacuous ones.
- **Environment, identical in all three logs (run-4.log:1-5):**
  - jolt `96d7b8662d7c491722445bf976b2e25e36ce5d63` with dirty=0
  - Chez 10.4.1 (mise path)
  - Darwin arm64, macOS 26.6.2
  - sysctl maxdgram 9216, recvspace 786896, maxsockbuf 8388608
  - `os.arch= aarch64`

## Evidence (run-4.log unless noted)

**Item 1.**
- **Bind and readback:** ephemeral ports a=63735 and b=52188 were read back (run-4.log:9). The other runs got 51232/55849 (run-5.log:9) and 57318/63271 (run-6.log:9).
- **Empty socket:** `:would-block` errno 35 (:10).
- **N=1200 round trips** (`:from {:ip "127.0.0.1" :port 63735}` on each):
  - zero-length (:11)
  - embedded-NUL, 8 bytes (:12)
  - 1200 bytes (:13)
- **Consecutive packets:** lens `[10 11 12 13 14]` were received as `[10 11 12 13 14]`, then `:would-block` (:14).
- **Short send [injected]:** `{:result :short-send, :n 99, :len 100}`, and the receiver got nothing (:15).
- **N=8192:** the same rows again at :21-26, with an 8192-byte packet at :24.

**Item 2.**
- **Platform maximum:** the largest sendable packet is 9216. 9217 gave `{:result :emsgsize, :errno 40}` (:6), with `:so-sndbuf 9216, :so-rcvbuf 786896`.
- **Larger N below the maximum:** asserted (:8).
- **N=1200:**
  - 1200 bytes gave `:packet` (:16).
  - 1201 gave `:oversize :received 1201` (:17).
  - 1300 gave `:oversize :received 1201` (:18).
  - Queue empty afterwards (:19).
- **N=8192:** same pattern at :27-30.
- Note: run-6.log:29 shows `:waits 0`, meaning the packet was already queued, so no wait was needed.

**Item 3.**
- **RECORD [natural]:** SO_SNDBUF was requested at 2048 and read back as 2048 (macOS did not round it). 20000 sends of 1024 bytes gave `{{:result :sent} 20000}` and `:would-block-observed? false` (:31). The receiver then drained 745 packets (:32). The same 745 appears in run-5.log:32 and run-6.log:32.
- **How SO_SNDBUF affects the observation:**
  - Verified: shrinking SO_SNDBUF to 2048, confirmed by read-back, did not induce would-block, and 1024-byte sends still succeeded.
  - Inference, not proven by this probe: on macOS a UDP socket keeps no send queue against SO_SNDBUF on loopback. SO_SNDBUF acts as the per-datagram send limit (the default equals maxdgram 9216, and EMSGSIZE fires above it, :6). The packet goes straight to the receiver, where the overflow is dropped silently. 745 x 1024 = 762880 bytes, which is under recvspace 786896, consistent with receive-buffer drops.
  - So a smaller SO_SNDBUF cannot make loopback produce EAGAIN/ENOBUFS here. Natural send would-block stays unobserved, and the requirement rests on injection.
- **[injected]:**
  - `[-1 35]` gave `{:result :would-block, :errno 35}` (:34).
  - `[-1 55]` gave `{:result :would-block, :errno 55}` (:35).
  - No packet was accepted in either case.
- **[natural]:** a real 1024-byte send and receive succeeded after the injections (:36). A receive on an empty socket gave `:would-block` errno 35 (:37).

**Item 4.**
- **Parking and wakeup:** trace `[[6769 :waiter-start] [6917 [:worker-ticked 50 :waiter-state :parked]] [6917 [:worker-sends {:result :sent, :n 77}]] [6917 [:waiter-got :packet 1]]]` (:38). The waiter was `:parked` while the worker fiber ran 50 ticks, and the 77-byte packet woke it within the same millisecond. Timestamps are `poller/clock-ms`.
  - run-5.log:38 trace: 6642/6789.
  - run-6.log:38 trace: 6684/6830.
- **Cancellation:**
  - `cancel!` released a `:parked` waiter, the join returned `:released`, and the retry was `:would-block` (:39).
  - A wait on the cancelled fd returned in `11/250` ms = 0.044 ms (:40). Other runs: `3/125` = 0.024 ms (run-5.log:40) and `29/500` = 0.058 ms (run-6.log:40).
- **Close:**
  - Close after `forget!` returned 0 (:41).
  - A `recvfrom` on the closed fd gave `{:result :error, :errno 9}` (:42).
  - The closed fd is absent from the poller table (:43). The `fds {6 …}` entry shown there is the still-open probe socket b's ready tombstone, not the closed fd.

**Item 5, cleanup.**
- Both sockets closed with result 0 (:44).
- The final `debug-state` was `{:pending {}, … :fds {}}` (:45).

## Not covered (unchanged)
- Linux x86_64 on shiva: NOT RUN.
- IPv6.
- Natural send-side EAGAIN/ENOBUFS.
- recvmsg/MSG_TRUNC: not bound; truncation is detected with the N+1 sentinel.
- Datagram/DHT conformance and the J7 writer mapping.

## Files (round 2)
- Modified: `build/jolt-spike/j0b/udp_probe.clj` (record/drain!/SO_SNDBUF read-back/N assertion/summary) and `build/jolt-spike/j0b/run.sh` (environment header and exit line).
- Created: `build/jolt-spike/j0b/run-4.log`, `run-5.log`, `run-6.log`, and this report.

Nothing outside `build/jolt-spike/j0b/` and this report was touched. Nothing was staged or committed.
