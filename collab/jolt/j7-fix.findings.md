Created-GMT: 2026-11-16 04:20:00 UTC
Coding-Agent: agy
Role: engineer
Branch: `fix/j7-udp-dht`
Base: `fd195c0cf0c7a62cd21915b8bcde3cb3e2231509`
Task: J7 fix round — the review findings P1-P2x3

# J7 Fix Findings and Acceptance Report

## Overview

Addresses and resolves all four review findings from [`collab/jolt/j7-review.findings.md`](j7-review.findings.md) (verdict: request changes), maintaining full integrity of landed Slices 1 and 2, adding regression failure-injection coverage, and reconciling test applicability without falsely claiming full J7 process gate completion.

---

## Addressed Findings

### 1. P2 (udp.jolt:155,162): Check close-on-exec! boolean and IPV6_V6ONLY [result errno]
- **Issue**: Socket setup ignored the boolean return of `native/close-on-exec!` and the `[result errno]` return from `IPV6_V6ONLY` `native/set-int-option!`. Injecting failures deposited `bound` and retained the socket.
- **Fix**:
  - `(if-not (native/close-on-exec! fd) ...)`: on `false`, closes acquired descriptor and deposits `:dao.stream.datagram/bind-failed` with `"close-on-exec! failed"`, returning `failed-seam`.
  - For IPv6 binds, checks `[r-v6 err-v6] (native/set-int-option! fd native/ipproto-ipv6 native/ipv6-v6only 1)`: on `(neg? r-v6)`, closes descriptor and deposits `:dao.stream.datagram/bind-failed` with `(str "IPV6_V6ONLY failed: errno " err-v6)`, returning `failed-seam`.

### 2. P2 (udp.jolt:151 + 199,235): Immediate descriptor & buffer cleanup on native acquisition
- **Issue**: Descriptor ownership was unprotected against throws after `c-socket` succeeded (e.g. `native/local-address` throw leaked descriptor and propagated unhandled exception). Send and receive temporary buffer allocations were also vulnerable to leaks if secondary allocations threw before `try` began.
- **Fix**:
  - Immediately following non-negative `fd` acquisition from `native/c-socket`, establishes `close-fd!` and executes all subsequent setup within `try ... (catch Throwable t (close-fd!) (failed-seam deposit identity (or (ex-message t) (str t))))`. On any setup throw, `fd` is closed, native storage freed, and `bind-failed` deposited preserving the original reason.
  - In `send-fn`, `(ffi/alloc (max 1 len))` is immediately protected by `try ... (finally (ffi/free buf))` before `(native/make-sockaddr ...)` is called.
  - In `step-fn`, `(ffi/alloc cap)` is immediately protected by `try ... (finally (ffi/free buf))` before `(native/alloc-sockaddr)` is called.

### 3. P2 (udp.jolt:244): Retain receive diagnostics; close seam once on fatal descriptor errors
- **Issue**: Negative receive results lost their errno and diagnostics; only `EBADF` closed the seam. Injecting `ENOTSOCK` (88) caused owner turns to retry indefinitely without error observation.
- **Fix**:
  - Added instance-owned `diagnostics` atom on every seam instance, accessible via `:diagnostics`, `:diagnostic`, and helper `(dao.stream.udp/diagnostics seam)`.
  - **Fatal descriptor errors** (any negative receive result other than would-block and EINTR, e.g. `ENOTSOCK`, `EBADF`, `EINVAL`): records `{:kind :fatal-descriptor-error :errno e :reason (str "recvfrom fatal error: errno " e)}` in diagnostics, calls `close-fn` once (canceling/forgetting poller, closing fd, depositing `closed` event, and setting `@closed? true`), and finishes turn immediately without retrying. Subsequent owner calls to `step!` return `{:progressed? false}` immediately without issuing syscalls. Subsequent `close!` calls are idempotent.
  - **Interruption** (`EINTR` 4): stays recoverable; records `{:kind :interrupted :errno 4 :reason "recvfrom interrupted: errno 4"}` in diagnostics, does not close socket, does not retry inside the turn, and leaves the descriptor open and usable for subsequent turns.
  - **Would-block** (`EAGAIN` 11, `ENOBUFS` 105): stays silent; finishes turn immediately without closing descriptor or recording error diagnostics.

### 4. P1 (acceptance reconciliation): Applicability updates and failure-injection probe
- **Reconciliation**:
  - Updated 11 stale test exclusions in `test/resources/yin/repl/jolt-applicability.edn` that cited the removed J7 refusal. Each now cites the landed state: `J7 slice 1+2 landed; networked DHT peer/follow acceptance open per design §§6-7`.
  - Updated 14 stale test namespace exclusions in `src/dev/jolt_j2_lane.clj` to cite landed Slices 1 and 2, and explicitly document why each remains excluded per design §§6–7 (multi-process DHT roles, real socket fixtures, Transit requirements on board fixtures, cross-host interop).
  - Explicitly did **NOT** claim full J7 gate completion: design §§6–7 process/interop acceptance remains open and recorded.
- **Failure-Injection Regression Probe (`bb probe:jolt-udp-failures`)**:
  - Added `probe:jolt-udp-failures` in `bb.edn` backed by `src/dev/jolt_udp_failures_probe.sh` and `src/dev/jolt_udp_failures_probe.clj`.
  - Exercises 68 assertions across setup failures, throw cleanup, buffer ownership, fatal error seam closure, recoverable EINTR, silent would-block, and complete production writer/seam contracts.

---

## Independently Executed Acceptance Evidence

All commands run on Linux x86_64 Ubuntu 24.04.5 LTS, clean jolt `b389f1a7fb553f63a3494d71f8fe3251790bf4d3`, Chez 10.4.1.

### 1. J0b UDP Probe (retained baseline)
```sh
bb probe:jolt-udp
```
**Output**:
```text
jolt: build/tools/jolt/bin/jolt
jolt-sha: b389f1a7fb553f63a3494d71f8fe3251790bf4d3 dirty=0
chez: /home/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme 10.4.1
uname: Linux x86_64 Ubuntu 24.04.5 LTS
sysctl: net.core.wmem_default=212992 net.core.wmem_max=4194304 net.core.rmem_default=212992 net.core.rmem_max=4194304 net.ipv4.udp_wmem_min=4096 net.ipv4.udp_rmem_min=4096 net.ipv4.udp_mem=1533171 2044230 3066342
lo mtu: 65536
jolt: source mode; run 'make devboot' to precompile
jolt UDP probe, platform= :linux os.name= Linux os.arch= amd64 constants= {:sock-dgram 2, :ipproto-udp 17, :so-sndbuf 7, :so-rcvbuf 8, :msg-trunc 32, :eagain 11, :emsgsize 90, :enobufs 105, :ebadf 9}
PASS 2 above platform max: sendto fails EMSGSIZE (an error, nothing sent) {:so-sndbuf 212992, :so-rcvbuf 212992, :max-sendable 65507, :send-max+1 {:result :emsgsize, :errno 90}}
platform max sendable = 65507 configured larger N = 8192
PASS 2 configured larger N is below the observed platform send maximum {:N 1200, :larger-N 8192, :max-sendable 65507}
PASS 1 bound ports read back {:a {:family 2, :ip "127.0.0.1", :port 57993, :scope-id 0}, :b {:family 2, :ip "127.0.0.1", :port 34950, :scope-id 0}}
PASS 1 empty socket answers :would-block {:result :would-block, :errno 11}
PASS 1 zero-length round-trip {:len 0, :send {:result :sent, :n 0}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}, :recv-len 0}
PASS 1 embedded-NUL round-trip {:len 8, :send {:result :sent, :n 8}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}, :recv-len 8}
PASS 1 limit-sized N round-trip {:len 1200, :send {:result :sent, :n 1200}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}, :recv-len 1200}
PASS 1 5 consecutive packets, one per receive, in order, then empty {:lens [10 11 12 13 14], :recv-lens [10 11 12 13 14], :after {:result :would-block, :errno 11}}
PASS 1 [injected] short send surfaces :short-send, nothing completes it {:send {:result :short-send, :n 99, :len 100}, :recv-after {:result :would-block, :errno 11}}
PASS 2 N=1200 send N (1200 bytes) -> :packet {:send {:result :sent, :n 1200}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}}
PASS 2 N=1200 send N+1 (1201 bytes) -> :oversize {:send {:result :sent, :n 1201}, :recv {:result :oversize, :received 1201, :limit 1200, :from {:ip "127.0.0.1", :port 57993}, :waits 0}}
PASS 2 N=1200 send N+100 (> recv buffer N+1) (1300 bytes) -> :oversize {:send {:result :sent, :n 1300}, :recv {:result :oversize, :received 1201, :limit 1200, :from {:ip "127.0.0.1", :port 57993}, :waits 0}}
PASS 2 N=1200 oversize consumed whole, queue empty {:result :would-block, :errno 11}
PASS 1 bound ports read back {:a {:family 2, :ip "127.0.0.1", :port 57993, :scope-id 0}, :b {:family 2, :ip "127.0.0.1", :port 34950, :scope-id 0}}
PASS 1 empty socket answers :would-block {:result :would-block, :errno 11}
PASS 1 zero-length round-trip {:len 0, :send {:result :sent, :n 0}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}, :recv-len 0}
PASS 1 embedded-NUL round-trip {:len 8, :send {:result :sent, :n 8}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}, :recv-len 8}
PASS 1 limit-sized N round-trip {:len 8192, :send {:result :sent, :n 8192}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}, :recv-len 8192}
PASS 1 5 consecutive packets, one per receive, in order, then empty {:lens [10 11 12 13 14], :recv-lens [10 11 12 13 14], :after {:result :would-block, :errno 11}}
PASS 1 [injected] short send surfaces :short-send, nothing completes it {:send {:result :short-send, :n 99, :len 100}, :recv-after {:result :would-block, :errno 11}}
PASS 2 N=8192 send N (8192 bytes) -> :packet {:send {:result :sent, :n 8192}, :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 0}}
PASS 2 N=8192 send N+1 (8193 bytes) -> :oversize {:send {:result :sent, :n 8193}, :recv {:result :oversize, :received 8193, :limit 8192, :from {:ip "127.0.0.1", :port 57993}, :waits 0}}
PASS 2 N=8192 send N+100 (> recv buffer N+1) (8292 bytes) -> :oversize {:send {:result :sent, :n 8292}, :recv {:result :oversize, :received 8193, :limit 8192, :from {:ip "127.0.0.1", :port 57993}, :waits 0}}
PASS 2 N=8192 oversize consumed whole, queue empty {:result :would-block, :errno 11}
RECORD 3 [natural] 20000 x 1024B sends, SO_SNDBUF requested 2048, no reader: outcome histogram {:setsockopt [0 11], :so-sndbuf-read-back 4608, :outcomes {{:result :sent} 20000}, :would-block-observed? false}
RECORD 3 [natural] drained receiver {:packets-delivered 92}
RECORD 3 SO_SNDBUF restored {:setsockopt [0 11], :so-sndbuf-read-back 18432}
PASS 3 [linux] SO_SNDBUF read-back follows socket(7): >= 2 x requested {:requested [2048 9216], :read-back [4608 18432]}
PASS 3 [injected] sendto -1/errno 11 -> :would-block, no packet accepted {:send {:result :would-block, :errno 11}}
PASS 3 [injected] sendto -1/errno 105 -> :would-block, no packet accepted {:send {:result :would-block, :errno 105}}
PASS 3 [natural] real sendto/recvfrom succeed after injection {:send {:result :sent, :n 1024}, :recv-len 1024}
PASS 3 [natural] recvfrom on empty socket -> :would-block errno 11 {:result :would-block, :errno 11}
PASS 4 empty receive parks the fiber; another fiber progresses; packet wakes the waiter {:trace [[9403 :waiter-start] [9512 [:worker-ticked 50 :waiter-state :parked]] [9512 [:worker-sends {:result :sent, :n 77}]] [9512 [:waiter-got :packet 1]]], :recv {:result :packet, :from {:ip "127.0.0.1", :port 57993}, :waits 1}}
PASS 4 cancel! releases a parked receive; retry is :would-block (no packet) {:state-before-cancel :parked, :join :released, :out {:after-wake {:result :would-block, :errno 11}}}
PASS 4 wait on cancelled fd returns immediately {:ms 2689/50000}
PASS 4 close after forget! {:close [0 11]}
PASS 4 recvfrom on closed fd is an error (EBADF), not a packet/would-block {:result :error, :errno 9}
PASS 4 poller table holds no entry for the closed fd {:debug {:pending {}, :waits 4, :ev-errors 0, :stale-consumes 0, :fds {6 {:read {:ready true, :waiters 0}}}}}
PASS 5 cleanup: both sockets closed {:a [0 9], :b [0 9]}
PASS 5 cleanup: poller table has no fd, pending or waiter left {:debug {:pending {}, :waits 4, :ev-errors 0, :stale-consumes 0, :fds {}}}
SUMMARY 37 assertions: 37 pass, 0 fail; 3 records
exit: 0
```

### 2. Failure-Injection Regression Probe (New)
```sh
bb probe:jolt-udp-failures
```
**Output**:
```text
jolt: source mode; run 'make devboot' to precompile
PASS 1 close-on-exec! failure closes acquired fd {:closes [123]}
PASS 1 close-on-exec! failure deposits bind-failed {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bind-failed, :reason close-on-exec! failed}]}
PASS 1 close-on-exec! returns failed-seam (inert close and step) {}
PASS 1 IPV6_V6ONLY failure closes acquired fd {:closes [123]}
PASS 1 IPV6_V6ONLY failure deposits bind-failed with errno {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bind-failed, :reason IPV6_V6ONLY failed: errno 1}]}
PASS 1 IPV6_V6ONLY failure returns failed-seam {}
PASS 2 Setup exception closes acquired fd {:closes [123]}
PASS 2 Setup exception deposits bind-failed preserving original reason {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bind-failed, :reason injected getsockname failure}]}
PASS 2 Setup exception returns failed-seam {}
PASS 2 make-sockaddr bind failure closes acquired fd {:closes [123]}
PASS 2 make-sockaddr bind failure deposits bind-failed {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bind-failed, :reason injected make-sockaddr failure}]}
PASS 2 send-fn frees payload buffer when destination sockaddr fails {:freed-count 3, :res injected destination make-sockaddr error}
PASS 2 step-fn frees receive buffer when alloc-sockaddr throws {:freed-count 1, :res injected alloc-sockaddr error}
PASS 3 Fatal receive error returns not progressed {:turns [{:progressed? false} {:progressed? false} {:progressed? false}]}
PASS 3 Fatal receive error closes fd once {:closes [123]}
PASS 3 Fatal receive error deposits bound then closed {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bound, :local #:dao.stream.datagram{:host 127.0.0.1, :port 40001}} #:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/closed}]}
PASS 3 Fatal receive error stops turn and ceases retrying {:calls 1}
PASS 3 Fatal receive error retains diagnostic with errno {:diagnostics [{:kind :fatal-descriptor-error, :errno 88, :reason recvfrom fatal error: errno 88}]}
PASS 3 Subsequent close! is idempotent {:closes [123]}
PASS 3 EBADF fatal error closes fd once {:closes [123]}
PASS 3 EBADF deposits closed event {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bound, :local #:dao.stream.datagram{:host 127.0.0.1, :port 40001}} #:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/closed}]}
PASS 3 EBADF retains diagnostic with errno 9 {:diagnostics [{:kind :fatal-descriptor-error, :errno 9, :reason recvfrom fatal error: errno 9}]}
PASS 3 EINTR does not progress turn {:turn1 {:progressed? false}}
PASS 3 EINTR does not close fd {:closes []}
PASS 3 EINTR retains recoverable diagnostic with errno 4 {:diagnostics [{:kind :interrupted, :errno 4, :reason recvfrom interrupted: errno 4}]}
PASS 3 Turn after EINTR recovers and receives packets {:turn2 {:progressed? true}, :calls 3}
PASS 3 Datagram event deposited after recovery {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bound, :local #:dao.stream.datagram{:host 127.0.0.1, :port 40001}} #:dao.stream.datagram{:socket probe-test, :source #:dao.stream.datagram{:host 127.0.0.1, :port 40002}, :bytes AAAAAAAAAAAAAA==}]}
PASS 3 EAGAIN returns {:progressed? false} {:turn {:progressed? false}}
PASS 3 EAGAIN does not close fd {:closes []}
PASS 3 EAGAIN stays silent with no error diagnostics {:diagnostics []}
PASS 3 ENOBUFS returns {:progressed? false} {:turn {:progressed? false}}
PASS 3 ENOBUFS does not close fd {:closes []}
PASS 3 ENOBUFS stays silent with no error diagnostics {:diagnostics []}
PASS 4 :eagain outcome is transport-error {}
PASS 4 :eagain syscall count is 1 {:calls 1}
PASS 4 :eagain failure count is 1 {}
PASS 4 :eagain no replay on step {:calls 1}
PASS 4 :eagain writer append after close returns :dao.stream/closed {:outcome :dao.stream/closed}
PASS 4 :eagain step after close answers {:progressed? false} {}
PASS 4 :enobufs outcome is transport-error {}
PASS 4 :enobufs syscall count is 1 {:calls 1}
PASS 4 :enobufs failure count is 1 {}
PASS 4 :enobufs no replay on step {:calls 1}
PASS 4 :enobufs writer append after close returns :dao.stream/closed {:outcome :dao.stream/closed}
PASS 4 :enobufs step after close answers {:progressed? false} {}
PASS 4 :emsgsize outcome is transport-error {}
PASS 4 :emsgsize syscall count is 1 {:calls 1}
PASS 4 :emsgsize failure count is 1 {}
PASS 4 :emsgsize no replay on step {:calls 1}
PASS 4 :emsgsize writer append after close returns :dao.stream/closed {:outcome :dao.stream/closed}
PASS 4 :emsgsize step after close answers {:progressed? false} {}
PASS 4 :eintr outcome is transport-error {}
PASS 4 :eintr syscall count is 1 {:calls 1}
PASS 4 :eintr failure count is 1 {}
PASS 4 :eintr no replay on step {:calls 1}
PASS 4 :eintr writer append after close returns :dao.stream/closed {:outcome :dao.stream/closed}
PASS 4 :eintr step after close answers {:progressed? false} {}
PASS 4 :short outcome is transport-error {}
PASS 4 :short syscall count is 1 {:calls 1}
PASS 4 :short failure count is 1 {}
PASS 4 :short no replay on step {:calls 1}
PASS 4 :short writer append after close returns :dao.stream/closed {:outcome :dao.stream/closed}
PASS 4 :short step after close answers {:progressed? false} {}
PASS 4 oversize progress is true {}
PASS 4 bounded receives max 64 {:calls 64}
PASS 4 oversize dropped without packet delivery {:events [#:dao.stream.datagram{:socket probe-test, :event :dao.stream.datagram/bound, :local #:dao.stream.datagram{:host 127.0.0.1, :port 40001}}]}
PASS 4 empty turn answers {:progressed? false} {}
PASS 4 close! closes fd once {:closes [123]}
SUMMARY 68 assertions: 68 pass, 0 fail
exit: 0
```

### 3. Solo DHT Smoke on Jolt
```sh
printf '(+ 1 2)\n' | "$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-yin-repl --no-state --index-store dht:/tmp/j7-fix-smoke-20261115
```
**Output**:
```text
jolt: source mode; run 'make devboot' to precompile
dht: solo, no --dht-peer, so no socket is opened; publications are durable in /tmp/j7-fix-smoke-20261115 and acknowledged by no one
dht: a (require ...) of a module this node does not hold may fetch its code from peers; it stays pending until the load ends, and (abandon) gives it up
dht: no --dht-key: (yin.link/publish ...) is refused; names are still read, resolved, loaded and linked
Host: jolt vnightly-17-gb389f1a7 / Chez 10.4.1
python: the native C++ ANTLR4 parser (native/jolt-parser), in process; no process crash isolation
yin> 3
yin> dht: published :segment/blake3-885640e41f3f089563cb8de0d34b624f6da83699d139065f22e71d3933ab4899 (9 blobs), NOT acknowledged: solo: no --dht-peer is configured, so nothing was sent; the index is in /tmp/j7-fix-smoke-20261115 only; 9 of 9 blobs not sent; not retrying (every remaining failure is final); the local copy is durable
Bye
```
Exit: 0.

### 4. Claimed JVM Test Suites & JVM Load
```sh
clojure -M:test -n dao.stream.udp-test -n dao.stream.udp-raw-test -n dao.stream.datagram-test
```
**Output**:
```text
Ran 37 tests containing 185 assertions.
0 failures, 0 errors.
```
Exit: 0.

```sh
clojure -M -e "(require 'yin.shell.dht 'yin.repl) (println :jvm-load-ok)"
```
**Output**:
`:jvm-load-ok` (Exit: 0).
