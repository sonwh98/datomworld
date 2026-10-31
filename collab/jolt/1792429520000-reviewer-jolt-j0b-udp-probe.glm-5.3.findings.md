Created-GMT: 2026-10-19 17:11:54 GMT
Created-Local: 2026-10-20 00:11:54 +07
Coding-Agent: glm
Session-ID: 73bafbc6-3a49-4146-b006-f3121e58efc9
# Review: J0b UDP-over-FFI probe (macOS arm64 record)
Role: Reviewer (adversarial, read-only)
Implementers:
- Model: glm-5.3 | Assigned: 2026-10-20 00:05:20 +07 | Status: done | Rationale: probe author is claude-opus-5-5; reviewer must be a different family; glm available again per owner

## Verdict: SOUND WITH CHANGES

Nothing found lets a broken transport pass as fixed: every §8.3 item is backed by a falsifiable check, injected evidence is seam-scoped and labelled, and the bindings/constants are target-correct. The defects are two vacuous PASS rows inflating the 37, an unverified SO_SNDBUF label, and report citations that match none of the three logs.

Independently verified (not re-run): all constants against MacOSX.sdk (SOCK_DGRAM 2 socket.h:112; SO_SNDBUF/RCVBUF 0x1001/0x1002 :155-156; MSG_TRUNC 0x10 :576; IPPROTO_UDP 17 in.h:121; EAGAIN 35/EMSGSIZE 40/ENOBUFS 55/EBADF 9 errno.h:96,129,137,170; socklen_t = __uint32_t). sendto/recvfrom signatures and :size_t/:ssize_t/:uint widths match the prototypes (udp_probe.clj:26-29). Errno capture is atomic per-thread on the foreign-call return path (`(__errno)` convention, jolt rt.ss:348-356; ffi.clj:1549-1551). read-array/write-array copy (ffi.clj:1256-1294); every alloc freed in `finally`; no use-after-free. Truncation: buffer is limit+1, N+1 → :oversize, N+100 reports :received N+1 (kernel-truncated), no :bytes, queue-empty check supports consumed-whole. Item 4's park is genuine: `:parked` state plus wait-fiber's `fiber-to-scheduler!` (io_poller.clj:794-813, 921-925); cancel→forget→close ordering follows the poller's contract (io_poller.clj:616-658). Injection is `binding`-scoped around send only; item 2 and the post-injection natural round trip double as leak detectors. jolt checkout HEAD 96d7b866… matches report §1 and is clean.

## Findings

| id | severity | file:line | evidence | correction |
|---|---|---|---|---|
| R1 | low | udp_probe.clj:212-215 | Two checks pass a literal `true` ("outcome histogram", "drained receiver"); they cannot fail yet count in "37 pass" | Emit as records without PASS, or assert something falsifiable; report 35 assertions + 2 records |
| R2 | low | report :78,102-103 vs run-{1,2,3}.log:4,32,34 | §3 claims "run-1.log" evidence but cites ports 57132/52555, trace [6761…6886], "about 0.04 ms" — none appears in any log (actual: 54721/65239, 51394/62421, 62519/61591; 7025/7153…; 1/40 and 4/125 ms). MSG_TRUNC is socket.h:576 in this SDK, not 574 | Re-cite from an actual log or label the source run; all qualitative claims are corroborated, only the citations are wrong |
| R3 | low | udp_probe.clj:207,216 | Both SO_SNDBUF setsockopt results ([r e]) are discarded and never read back; the "[natural] SO_SNDBUF=2048" label is unverified | Read back via get-int-option into the record detail |
| R4 | info | udp_probe.clj:195,214 | Drain loops recur on any non-:would-block result, so a persistent :error hangs instead of failing | Stop on :error with a FAIL |
| R5 | info | udp_probe.clj:280 | big N=8192 is hardcoded, not derived from max-ok; below-max holds here (9216) and a violation fails loudly | Derive from max-ok or assert (< big max-ok) |
| R6 | info | jolt native.clj:191 (reused) | c-bind declares socklen_t as :int, not :uint; benign (same width/ABI, len 16) | Fix when J7 lands the datagram layer |

## What I could not verify

- Exit code 0 of the three logged runs (SUMMARY lines only; the orchestrator's re-run reports 37/0/exit 0).
- sysctl/ifconfig values (approval-gated here); indirectly corroborated — logs carry so-sndbuf 9216, so-rcvbuf 786896, max-sendable 9216, and 745×1024 < 786896.
- That runs 2-3 came from the same binary (no SHA in logs; checkout matches the report).
- The Linux x86_64 row: not run, as the report states — the §8.3.5 gate stays incomplete, and the report says so.
