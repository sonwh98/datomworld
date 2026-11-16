Reviewed-GMT: 2026-11-16 03:58:16 UTC
Reviewed-Local: 2026-11-15 22:58:16 EST
Coding-Agent: codex
Role: reviewer
Branch: `fix/j7-udp-dht`
Reviewed-HEAD: `fd195c0cf0c7a62cd21915b8bcde3cb3e2231509`
Reviewed-base: `origin/master` = `d972c2c4349f807d1ddd67d7a6741d09ee69d611`
Verdict: request changes; J7 acceptance incomplete. Four actionable findings; no implementation fixes made.

# Findings

P1 | src/cljc/yin/shell/dht.cljc:252 | Jolt DHT activation lands without the J7 production-seam and composition acceptance required by §8.4 and the committed design §§6–7. The diff adds no tests, no jolt process roles, and no applicability reconciliation. The retained J0b probe exercises its own syscalls, while the claimed solo smoke explicitly opens no socket. | Add and run production IPv4/IPv6, configurable-size, failure/cleanup and DHT/signed-board process tests; reconcile the J7 exclusions and supply the required host/interoperability evidence before accepting J7 as complete.

P2 | src/jolt/dao/stream/udp.jolt:155 | Socket setup ignores the boolean result of `native/close-on-exec!` and the `[result errno]` from `IPV6_V6ONLY` at line 162. Injecting either failure still deposits `bound` and retains the socket. Failed close-on-exec can leak a duplicate into children and keep a port alive after owner close; failed IPv6-only setup can admit mapped IPv4 ingress where the design requires a deterministic single-family seam. | Check both results, close the acquired descriptor, and deposit `bind-failed` with the actual failure instead of exposing a partially configured seam.

P2 | src/jolt/dao/stream/udp.jolt:151 | Descriptor ownership is not protected against exceptions after `socket()` succeeds. Injecting a throw from `native/local-address` propagates the exception, deposits no `bind-failed`, and calls close zero times. Earlier setup helpers can also throw; the per-buffer send/receive `try` blocks likewise start after a second native acquisition at lines 199 and 235. | Establish cleanup immediately after each acquisition; on failed bind release the descriptor and native allocations and report `bind-failed`, preserving the original reason. Apply the same ownership discipline to send/receive temporary buffers.

P2 | src/jolt/dao/stream/udp.jolt:244 | Every negative receive result loses its errno and diagnostic; only `EBADF` closes the seam. With Linux `ENOTSOCK` (88) injected, three owner turns issue three receives, each answering only `{:progressed? false}`, with no closure or error observation. A permanently broken seam therefore looks idle and is retried indefinitely by the owner. | Retain explicit instance-owned receive diagnostics, including errno, and close fatal descriptor errors once; leave interruption recoverable and would-block silent, without adding a public lifecycle kind or retrying inside a turn.

## Evidence and scope

The review inspected every changed source file and the complete committed
[architect findings](1794773141000-architect-j7-design.findings.md),
[engineer report](1794775739000-engineer-j7-impl.findings.md),
[§8.4 and J7 gate](../../docs/design/yin.repl-jolt-implementation.md), and
[datagram contract](../../docs/design/dao.stream.datagram.md).
Implementation commits: `16d4d3ed` and `fd195c0c`. The engineer identifies
the work as slices 1 and 2; this review does not reinterpret that as evidence
that the remaining design gates passed.

For finding 1, `git diff --name-only origin/master..HEAD` contains seven source
files and three collaboration documents, with no test or runner changes.
`src/dev/jolt_j2_lane.clj:182–218` still excludes UDP, DHT, head-board and
head-follow namespaces. `test/resources/yin/repl/jolt-applicability.edn:6–29`
retains J7 per-test exclusions whose reasons cite the now-removed refusal.
Codec-neutral board fixtures still require Transit
(`test/yin/vm/linker/head_board_test.cljc:27`). There is no native UDP test
addition, jolt role in `test/yin/repl/dht_process_test.clj`, or macOS and
JVM/Node/Dart interoperation result in the engineer report. Passing portable
tests below cannot fill these gaps.

For findings 2–4, the review-only script in ignored
`build/j7-review/native-injection.clj` invokes the actual production binder,
closures, public datagram writer, address encoding and traffic rings. Socket
creation/bind/flags/local-address/close and poller cleanup are replaced with
controlled functions; the fake fd is 123. This avoids the sandbox's socket
restriction and does not claim a real socket was opened. Observations:

```text
close-on-exec! -> false:
  events = [bound at 127.0.0.1:40001]; closes-before-close = []
IPv6 set-int-option! -> [-1 1]:
  events = [bound at ::1:40001]; closes-before-close = []
local-address -> throws "injected getsockname failure":
  result = propagated exception; events = []; closes = []
recvfrom -> [-1 88], on three successive turns:
  turns = [{:progressed? false} {:progressed? false} {:progressed? false}]
  calls = 3; events = [bound]; closes-before-close = []
```

The exception reproducer tests setup ownership, not whether the C
`getsockname` syscall itself throws. `native/local-address` allocates native
sockaddr storage; exceptions from this or other fallible helper work must
still release the descriptor. In send/receive, failure of the second
allocation occurs before the first buffer's release has been protected.
Those additional allocation sites were inspected, not separately injected.

# Independently executed acceptance

Host: Linux x86_64, Ubuntu 24.04.5 LTS. Runtime: clean jolt
`b389f1a7fb553f63a3494d71f8fe3251790bf4d3`; Chez 10.4.1, matching the declared
pin. The existing bootstrap environment was used; bootstrap was not rerun.
Every result below is from a fresh review command; actual output was read.
An earlier untracked review was present at the requested output path. Its
source claims and review-only injection script were inspected and all eight
checks below were independently rerun in this turn; the old report and logs
were not used as acceptance results. The inspected injection script remains
in ignored `build/j7-review/`; fresh command outputs were read directly from
the execution tool.

Shell setup for `bb`/JVM commands:

```sh
export MISE_STATE_DIR="$PWD/build/j7-review/mise-state"
export MISE_CACHE_DIR="$PWD/build/j7-review/mise-cache"
eval "$(mise env -s bash)"
```

Jolt commands first source `. build/tools/jolt-env.sh`. Timeout wrappers
bounded the namespace load at 60 seconds and other checks at 120 seconds;
none of the reported successful checks timed out.

| Check | Command | Actual result |
|---|---|---|
| Jolt namespace loads | `"$JOLT_HOME/bin/jolt" -Srepro -M:jolt -e "(require 'dao.stream.udp 'dao.stream.udp.common 'dao.posix.random 'dao.space.dht 'yin.shell.dht 'yin.repl) (println :j7-all-namespaces-loaded-ok)"` | Exit 0; `:j7-all-namespaces-loaded-ok`. |
| J0b retained legs | `bb probe:jolt-udp` | Exit 1 before the first assertion: `Unhandled exception: socket failed`, `ex-data: {:errno 1}`, at `jolt-udp-probe/udp-open` line 76. Header confirms the clean pinned SHA. No leg passed here. |
| Solo DHT smoke | `printf '(+ 1 2)\n' \| "$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-yin-repl --no-state --index-store dht:/tmp/j7-review4-solo-20261115` | Exit 0; `yin> 3`, nine durable local blobs, explicit solo/no-socket/no-ack message, `Bye`. |
| JVM load | `clojure -M -e "(require 'yin.shell.dht 'yin.repl) (println :jvm-load-ok)"` | Exit 0; `:jvm-load-ok`. |
| Claimed JVM UDP/datagram suites | `clojure -M:test -n dao.stream.udp-test -n dao.stream.udp-raw-test -n dao.stream.datagram-test` | Exit 0; 37 tests, 185 assertions, 0 failures/errors. |
| Same portable suites on jolt | `"$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj dao.stream.udp-test dao.stream.udp-raw-test dao.stream.datagram-test` | Exit 0; 37 tests, 185 assertions, 0 failures/errors/load failures. |
| Signed-head and mesh follower on jolt | `"$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj yin.vm.linker.head-test yin.vm.linker.head-follow-test` | Exit 0; 32 tests, 920 assertions, 0 failures/errors/load failures. Portable test mesh, not native UDP/WS processes. |
| Production seam injection | `"$JOLT_HOME/bin/jolt" -Srepro -M:jolt build/j7-review/native-injection.clj` | Exit 0; 35 contract assertions passed, plus the adverse setup/receive observations quoted above. |

The smoke adds `--no-state` and uses a fresh directory so it does not write
the user's saved `~/.yin/node/state.edn` or reuse the implementer's store.
It preserves the requested evaluation and DHT store behavior. Its output:

```text
dht: solo, no --dht-peer, so no socket is opened; publications are durable in /tmp/j7-review4-solo-20261115 and acknowledged by no one
yin> 3
yin> dht: published :segment/blake3-3ecb092cc91ce58deabf9d9c3dd3f129b7371de9c6396926400f173022dfd09b (9 blobs), NOT acknowledged: solo: no --dht-peer is configured, so nothing was sent; the index is in /tmp/j7-review4-solo-20261115 only; 9 of 9 blobs not sent; not retrying (every remaining failure is final); the local copy is durable
Bye
```

The J0b failure is an environment limitation, not an implementation defect
or evidence against the implementer's historical 37-pass run. This review
cannot independently confirm that run: `socket()` is denied with EPERM here.
The wrapper also reports absent `/proc/sys/net/ipv4/udp_mem`; that diagnostic
is separate from the socket failure. No sandbox bypass or alternate runtime
was used. Real native IPv4/IPv6, readiness/fiber legs and macOS acceptance
remain unverified.

## Contract and portability disposition

The injected production writer cases EAGAIN, ENOBUFS, EMSGSIZE, EINTR and
short-send each returned `transport-error` and exactly one `send-failed`,
made one send call, and made no later send during two receive turns. None
returned `full`. Closed sends returned `closed`; closed stepping was inert.
An oversize sentinel consumed the full 64-call receive budget without
depositing a packet; would-block returned immediately; repeated close closed
once. Inspection confirms no outbound queue, send-side wait, EINTR retry or
POLLOUT replay. Actual nonblocking flags and packet/source fidelity still
need real socket acceptance. Receive diagnostic handling fails as reported.

The shared UDP body moved without wire-key or algorithm changes; its public
functions/arities and `dao.stream.udp.UdpHandle` facade remain available.
Both JVM and jolt portable suites pass. No top-level conditional `require`
was introduced. Jolt imports remain within `.jolt` files and the existing
namespace reader-conditional seam in `dao.space.dht`; shared
`udp/common.cljc` remains portable. Entropy lowering retains the qualified
`dao.host.hash` provenance gate; `dao.space` does not depend on `yin.*`.
The optional native receive turn precedes DHT interpretation as designed.

JVM binder/entropy selection retains its original reader branch. Shared
facade factoring and shell-board CBOR dialing do affect JVM-loaded source;
the latter is explicitly required by design §4, with CBOR first and Transit
retained on supporting listeners. JVM load and affected UDP tests passed;
that does not qualify board negotiation, Node/Dart compilation or process
interoperability. Those remain open, rather than establishing that the JVM
lane is wholly unchanged or broken.

Only this findings artifact is intended for the requested commit. No source,
test, applicability file or supplied execution log was changed or staged.
The existing review script, backups and logs are ignored build output.
`git diff --check origin/master..HEAD` passed.

## Commit status

Findings whitespace validation passed. The findings-only commit command was
attempted:

```sh
git add -- collab/jolt/j7-review.findings.md &&
git commit -m "docs(collab): independent J7 implementation review findings" -- collab/jolt/j7-review.findings.md
```

`git add` exited 128 before staging, so the commit did not run:

```text
fatal: Unable to create '/home/sto/ci/datomworld-j6e-linux/.git/worktrees/dw-j7/index.lock': Read-only file system
```

The linked worktree's Git metadata is outside the session's writable roots.
The completed report remains untracked at the requested path; no commit was
created. Completing the commit requires an environment permitted to write
that metadata. No Git metadata or permissions were changed to bypass the
restriction. The earlier untracked report was preserved in ignored
`build/j7-review/review-before-refresh-20261116.findings.md`.
