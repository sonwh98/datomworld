Completed-GMT: 2026-11-01 19:13:49 GMT
Completed-Local: 2026-11-02 02:13:49 Asia/Bangkok (UTC+07:00)

**Verdict: (a), a platform-dependent test assumption.** On this Linux host, the second UDP bind **succeeds** with Dart’s default options. The seam correctly deposits `bound`; there is no bind failure to report. A deliberately exclusive blocker causes the unchanged seam to deposit `bind-failed` promptly. This distinguishes the failure from a missing error handler or a five-second timing race.

No repository source files were edited. Final `git status --porcelain=v1` was empty.

**Measured reproduction**

Host: `shiva`, Linux `6.8.0-147-generic`, x86_64. Repository revision: `21649fd80abe60a60bb14a919e489ad714ae347c`. Selected toolchain: Flutter 3.47.8, Dart 3.13.7.

I compiled only `dao.stream.datagram.dart-test`, then ran its generated `package:test` test directly with Dart. Another Flutter lane was already running; this avoided starting a second Flutter lane.

| Operation | Wall time | Result |
|---|---:|---|
| Compile this namespace | 33.13 s | Exit 0 |
| Run only `refusals-and-lifecycle-events` | 40.37 s | Exit 1 |
| Run this entire namespace | 32.35 s | 4 passed, 1 failed |
| Native socket probe with `strace` | 6.73 s | Exit 0 |
| Unchanged compiled-seam probe | 11.39 s | Exit 0 |

The exact test reported:

```text
00:00 +0: loading test/cljd-out/dao/stream/datagram/dart-test_test.dart
00:00 +0: null dao.stream.datagram.dart-test/refusals-and-lifecycle-events
00:05 +0 -1: null dao.stream.datagram.dart-test/refusals-and-lifecycle-events [E]
  Expected: (some? failed)
    Actual: Result:<(not (some? nil))>
     Which: no bind-failed event deposited
            occured in:
            a bind on a taken port deposits bind-failed
            dao.stream.datagram.dart-test/refusals-and-lifecycle-events
```

The runner subsequently reported a 30-second test timeout and exited 1. That is secondary: the relevant assertion already failed at five seconds. Full, unabridged output is retained in [test.log](/tmp/datagram-linux-diagnosis/test.log), with the namespace run in [namespace.log](/tmp/datagram-linux-diagnosis/namespace.log).

The lifecycle and send-failed assertions preceding the duplicate-bind assertion passed. The other four tests in the namespace also passed.

**Seam and test behavior**

One correction to the brief: `dest` is **seam-b’s** port, established at [dart_test.cljd:235](/home2/sto/ci/datomworld-datagram/test/dao/stream/datagram/dart_test.cljd:235). Seam-b remains open. Seam-a has already been closed before the duplicate-bind subtest.

The test helper at [dart_test.cljd:67](/home2/sto/ci/datomworld-datagram/test/dao/stream/datagram/dart_test.cljd:67) passes `127.0.0.1`, the requested port, and the traffic ring into the production seam.

The seam:

- Calls `RawDatagramSocket.bind(bind-host, bind-port)` without reuse arguments at [dart.cljd:94](/home2/sto/ci/datomworld-datagram/src/cljd/dao/stream/datagram/dart.cljd:94).
- Attaches the socket and deposits `bound` when the Future succeeds.
- Handles Future rejection through `.catchError`, marks the seam failed, and deposits `bind-failed` at [dart.cljd:110](/home2/sto/ci/datomworld-datagram/src/cljd/dao/stream/datagram/dart.cljd:110).
- Uses the established socket’s listener `onError` for `send-failed` and closure, not initial bind failure.

**Native probe: exact output**

The standalone [udp_probe.dart](/tmp/datagram-linux-diagnosis/udp_probe.dart) binds `127.0.0.1:0`, obtains its assigned port, and attempts a second bind while keeping the first socket open. It distinguishes synchronous exceptions, Future rejection, and socket-stream errors.

```text
OS=linux Dart=3.13.7 (stable) (Thu Oct 22 14:28:57 2026 -0700) on "linux_x64"
defaults first=127.0.0.1:57591
defaults second call returned Future (no synchronous throw)
defaults FUTURE SUCCESS port=57591 elapsedUs=2232
defaults observedMs=5500 streamEvents=1 streamErrors=0
addr-only-both first=127.0.0.1:45852
addr-only-both second call returned Future (no synchronous throw)
addr-only-both FUTURE SUCCESS port=45852 elapsedUs=1416
addr-only-both observedMs=100 streamEvents=1 streamErrors=0
exclusive-first first=127.0.0.1:53093
exclusive-first second call returned Future (no synchronous throw)
exclusive-first FUTURE ERROR SocketException: SocketException: Failed to create datagram socket (OS Error: Address already in use, errno = 98), address = 127.0.0.1, port = 53093
exclusive-first errno=98 elapsedUs=7699
exclusive-first observedMs=100 streamEvents=0 streamErrors=0
exclusive-second first=127.0.0.1:37164
exclusive-second second call returned Future (no synchronous throw)
exclusive-second FUTURE ERROR SocketException: SocketException: Failed to create datagram socket (OS Error: Address already in use, errno = 98), address = 127.0.0.1, port = 37164
exclusive-second errno=98 elapsedUs=1243
exclusive-second observedMs=100 streamEvents=0 streamErrors=0
exclusive-both first=127.0.0.1:48586
exclusive-both second call returned Future (no synchronous throw)
exclusive-both FUTURE ERROR SocketException: SocketException: Failed to create datagram socket (OS Error: Address already in use, errno = 98), address = 127.0.0.1, port = 48586
exclusive-both errno=98 elapsedUs=1335
exclusive-both observedMs=100 streamEvents=0 streamErrors=0
port-only-both first=127.0.0.1:36629
port-only-both second call returned Future (no synchronous throw)
port-only-both FUTURE SUCCESS port=36629 elapsedUs=1070
port-only-both observedMs=100 streamEvents=1 streamErrors=0
both-flags-both first=127.0.0.1:43636
both-flags-both second call returned Future (no synchronous throw)
both-flags-both FUTURE SUCCESS port=43636 elapsedUs=1306
both-flags-both observedMs=100 streamEvents=1 streamErrors=0
```

Here, “exclusive” means `reuseAddress: false, reusePort: false`; “addr-only” means `true, false`; “port-only” means `false, true`.

The [syscall trace](/tmp/datagram-linux-diagnosis/probe.strace) confirms the default case sets `SO_REUSEADDR=1` on both UDP sockets, does **not** set `SO_REUSEPORT`, and receives success from both kernel `bind()` calls. Disabling address reuse on either socket makes the second kernel bind return `EADDRINUSE`.

Therefore, `reusePort: false` alone does **not** ensure exclusive UDP binding on this Linux host.

**Production seam: exact probe output**

A second [probe](/tmp/datagram-linux-diagnosis/seam_probe.dart) imports the unchanged compiled repository seam and test helpers. It reproduces the two-seam setup, closes seam-a, and binds “loser” to seam-b’s port. It then tests the same seam against a native exclusive blocker:

```text
default-seams elapsedUs=11387 events=[{:dao.stream.datagram/socket "loser", :dao.stream.datagram/local {:dao.stream.datagram/host "127.0.0.1", :dao.stream.datagram/port 43541}, :dao.stream.datagram/event :dao.stream.datagram/bound}]
default-seams after5500ms events=[{:dao.stream.datagram/socket "loser", :dao.stream.datagram/local {:dao.stream.datagram/host "127.0.0.1", :dao.stream.datagram/port 43541}, :dao.stream.datagram/event :dao.stream.datagram/bound}]
exclusive-blocker elapsedUs=10700 events=[{:dao.stream.datagram/socket "exclusive-loser", :dao.stream.datagram/reason "SocketException: Failed to create datagram socket (OS Error: Address already in use, errno = 98), address = 127.0.0.1, port = 54631", :dao.stream.datagram/event :dao.stream.datagram/bind-failed}]
exclusive-blocker send={:dao.stream.datagram/reason "not bound", :dao.stream/outcome :dao.stream/transport-error}
```

This directly proves the Linux bind-failure deposit path is reachable and works.

**Platform facts and limits**

Dart defaults to `reuseAddress: true, reusePort: false`, confirmed both in this installed SDK and the [official API](https://api.dart.dev/dart-io/RawDatagramSocket/bind.html).

The Linux behavior is measured here. The macOS explanation is independently corroborated by [Dart SDK issue #50172](https://github.com/dart-lang/sdk/issues/50172), which reports the same loopback duplicate-bind scenario failing on macOS with `SocketException`/errno 48 while succeeding on Linux. Apple documents `SO_REUSEPORT` as permitting completely duplicate bindings. [Apple socket-option documentation](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/setsockopt.2.html)

I did not execute anything on macOS. Thus, the Linux cause is experimentally confirmed; the macOS side is supported by upstream evidence rather than a fresh laptop measurement.

**Proposed minimal correct fix**

Prefer a **test-only exclusive blocker fixture** at [dart_test.cljd:289](/home2/sto/ci/datomworld-datagram/test/dao/stream/datagram/dart_test.cljd:289):

1. Add a `dart:io` require.
2. Await a native `RawDatagramSocket.bind("127.0.0.1", 0, reuseAddress: false, reusePort: false)`.
3. Bind the existing production seam helper to that blocker’s actual port.
4. Retain the `bind-failed` assertion.
5. Close both the seam and blocker in `finally`.

This preserves meaningful failure-path coverage on both platforms and requires no production seam change. Its essential mechanism was validated by the compiled-seam probe above.

This recommendation follows the repository’s contract: lifecycle events report host facts, and an actually failed bind deposits `bind-failed`; the contract does not promise exclusive binding. See [dao.stream.datagram.md:98](/home2/sto/ci/datomworld-datagram/docs/design/dao.stream.datagram.md:98) and the rule that host implementations do not add contracts at [datom.world.md:70](/home2/sto/ci/datomworld-datagram/docs/design/datom.world.md:70).

A one-line production change adding `.reuseAddress false` at [dart.cljd:94](/home2/sto/ci/datomworld-datagram/src/cljd/dao/stream/datagram/dart.cljd:94) would also make this collision fail, but changes production binding policy. It is appropriate only if exclusive binding is intended as part of the contract.

A Linux-only assertion split or skip is a fallback, but loses genuine bind-failure coverage unless another fixture supplies it. The known-failure note at [build-n-test.md:436](/home2/sto/ci/datomworld-datagram/docs/agents/build-n-test.md:436) should replace “inferred, not confirmed” with this measured explanation; documentation alone does not fix the test.

**Exact execution commands**

Run from `/home2/sto/ci/datomworld-datagram`:

```bash
/usr/bin/time -f 'elapsed=%e exit=%x' -o /tmp/datagram-linux-diagnosis/compile.time mise exec -- clojure -M:clojuredart:cljd compile dao.stream.datagram.dart-test > /tmp/datagram-linux-diagnosis/compile.log 2>&1

/usr/bin/time -f 'elapsed=%e exit=%x' -o /tmp/datagram-linux-diagnosis/test.time mise exec -- dart run test --reporter expanded --concurrency=1 test/cljd-out/dao/stream/datagram/dart-test_test.dart --plain-name dao.stream.datagram.dart-test/refusals-and-lifecycle-events > /tmp/datagram-linux-diagnosis/test.log 2>&1

/usr/bin/time -f 'elapsed=%e exit=%x' -o /tmp/datagram-linux-diagnosis/namespace.time mise exec -- dart run test --reporter expanded --concurrency=1 test/cljd-out/dao/stream/datagram/dart-test_test.dart > /tmp/datagram-linux-diagnosis/namespace.log 2>&1

/usr/bin/time -f 'elapsed=%e exit=%x' -o /tmp/datagram-linux-diagnosis/probe.time strace -f -tt -e trace=socket,setsockopt,bind,getsockname -o /tmp/datagram-linux-diagnosis/probe.strace /home/sto/.local/share/mise/installs/flutter/3.47.8-stable/bin/cache/dart-sdk/bin/dart /tmp/datagram-linux-diagnosis/udp_probe.dart > /tmp/datagram-linux-diagnosis/probe.log 2>&1

/usr/bin/time -f 'elapsed=%e exit=%x' -o /tmp/datagram-linux-diagnosis/seam.time mise exec -- dart --packages=.dart_tool/package_config.json /tmp/datagram-linux-diagnosis/seam_probe.dart > /tmp/datagram-linux-diagnosis/seam.log 2>&1
```

Host-identification and core inspection commands are retained in [commands.txt](/tmp/datagram-linux-diagnosis/commands.txt).

The initial sandboxed inspection could not execute because `bwrap` failed with `loopback: Failed RTM_NEWADDR: Operation not permitted`. Approved escalated execution succeeded. No diagnostic remained blocked. No macOS, full-repository, or Flutter test lane was run; native Dart executed the original generated namespace directly.
