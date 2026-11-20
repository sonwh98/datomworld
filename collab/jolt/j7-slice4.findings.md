# J7 slice 4 — process lifetime, native receive, and acceptance limits

Date: 2026-11-20. Agent: codex. Role: engineer.
Worktree: `/home/sto/ci/dw-j7`, branch `fix/j7-udp-dht`.
Base: `13e7a496` (the slice-3 parser and stepper regression).

**Status: regression coverage delivered; live acceptance incomplete.** No
runtime cookie/receive change was made. The retained trace does not show
an initial UDP ping: it shows a TCP head-board handshake. Its reader also
received EOF and quit while require was pending. These are defects in the
old acceptance probe's evidence and lifetime, not evidence that a cookie
reply died. The new process test keeps stdin open. Whether another defect
remains with that corrected lifetime requires a socket-enabled run.

## Deliverable 1: real JVM publisher and jolt reader

Added `yin.repl.dht-process-test/jolt-reader-requires-a-jvm-publication-by-name`
beside the existing JVM/Node process scenarios, reusing their anchor,
process control, publication fixture and bounded output waits:

1. A storing JVM anchor binds an ephemeral UDP port.
2. A separate JVM shell publishes `my.lib` with `f(x) = x + 4200`, using a
   fresh key and durable store. The fixture also publishes `my.store`;
   the test waits for that last publication before starting the reader.
3. A separate jolt shell starts from this worktree's pinned launcher and
   generated `build/tools/jolt-env.sh`. It joins the publisher's token,
   trusts that principal, follows its WebSocket head, and requires
   `my.lib` by name. No manifest or module address is supplied to it.
4. Stdin remains open with no further input until require answers. Then
   `(my.lib/f 1)` must print `4201`. The qualified export spelling matches
   the existing process tests. Finally the test types `(quit)`, waits,
   forcibly terminates only its own child if necessary, and cleans up.

Every shell is launched with `--no-state`. Stores and key paths are unique
per run, and both ports are ephemeral. No saved home-directory state,
human keystroke, pre-existing publisher, or tick-feeding process is used.
Transcripts are written to `target/jolt-test-publisher-jvm.log` and
`target/reader-jolt.log` when those processes were started.

Run from the worktree after its normal ANTLR/jolt prerequisites exist:

```sh
clj -M:test -n yin.repl.dht-process-test \
  -v yin.repl.dht-process-test/jolt-reader-requires-a-jvm-publication-by-name
```

Optional integrated strace acceptance:

```sh
JOLT_DHT_STRACE=/tmp/j7-slice4-live.trace \
  clj -M:test -n yin.repl.dht-process-test \
  -v yin.repl.dht-process-test/jolt-reader-requires-a-jvm-publication-by-name
```

That mode traces `sendto,recvfrom` and additionally asserts more than one
successful `sendto` carrying an IPv4 destination sockaddr. A TCP send with
a null destination cannot satisfy it. The call result and UDP evidence
must pass in the same run. No claim that this process test reached the
post-ping condition or passed here: this session denies the anchor bind.

## Deliverable 2 investigation: the presumed ping was TCP, followed by EOF

Read the retained `/tmp/jolt-accept-strace.log` and
`/tmp/jolt-accept2-strace.log` rather than counting all `sendto` lines.
Each has exactly this kind of single send (different PID/fd):

```text
1919372 sendto(13, "GET /head HTTP/1.1\r\nHost: 127.0."..., 199, MSG_NOSIGNAL, NULL, 0) = 199
```

This is the WebSocket client's HTTP upgrade request over TCP, with no UDP
destination. It is **not** a CBOR padded DHT ping. These are pre-existing
host artifacts read during this task, not fresh captures from this
sandbox. They do not support the dispatch's claim that a UDP ping was
sent, or that a UDP cookie reply arrived and was lost.

`/tmp/j7-accept2.sh` launches the reader as:

```sh
printf '%s\n' '(println :REQ (require (quote my.lib)))' \
  '(println :CALL-RESULT (f 1))' | timeout 300 strace ... jolt ...
```

It closes stdin immediately. The retained `/tmp/jx-joltB.log` contains the
pending `link [:t0 1]` followed by `Bye`, then the process exits normally
in the trace. This was not a node left alive waiting for a cookie.

The shipped jolt main's stdin callback in `yin.repl.main/main!` explicitly
submits `(quit)` on EOF. `quit` is a local engine command, including while
a require is parked. `run-owner!` then drains the board/endpoint and
custody work; that shutdown contract does not wait for a pending require
to complete. The new portable regression
`piped-eof-quits-before-a-pending-require-can-fetch` submits the old
probe's require, call and EOF-equivalent quit together through the actual
driver/composition. It verifies:

- The shell stops with a parked require and prints `Bye`.
- No UDP ping was sent before quit.
- The shutdown tick reports stopped while the require is still pending;
  at most an initial ping is emitted in that final drain tick.

The fix to this reproduction is the process test's input lifetime: hold
stdin open until require and call finish. Changing the native receive
loop, adding a background receiver, or making quit wait indefinitely
would change runtime behavior without a demonstrated runtime defect.
**Deviation from the requested implementation:** no protocol/host fix is
claimed, and no guarantee is made that the new test fails on the original
checkout for the alleged cookie stall. Its live result remains unknown.

## Native binder-to-DHT receive coverage

Added `test/dao/stream/udp_dht_test.jolt`, namespace
`dao.stream.udp-dht-test`. It uses the production `udp/bind!`, datagram
writer, native buffer copies, sockaddr construction/parsing, Base64 event
encoding, raw traffic cursor, CBOR and `dao.jing.dht/step`. Only socket
acquisition/setup/close and the kernel send/receive operations are
substituted. Both simulated DHT peers execute on jolt; this is not
cross-host or live-network evidence.

The peer sends a real cookie challenge through the injected recvfrom
buffer and source sockaddr. The client must send precisely
`[:ping :ping :fetch]`, propagate the received cookie, prove the observed
source, complete its query, and verify/cache the payload. The second
payload forces chunked replies and reassembly. The fixture makes at most
64 rounds and calls the seam once per round, before the DHT core. No
background stepper or unbounded receive loop was added. Both payloads
passed against the unchanged runtime.

## Executed checks and acceptance

All commands ran locally in this worktree. Logs are under
`/tmp/j7-slice4-*`, with copies in `target/j7-slice4-evidence/` (the table
uses the suffix filenames). Copies of the historical captures there have
a `retained-` prefix. The results below are fresh unless explicitly marked
as retained artifacts above.

| Check | Result |
| --- | --- |
| JVM `dao.stream.udp-test` + `yin.repl.dht-step-test` | **PASS**: 14 tests, 52 assertions, 0 failures/errors. `jvm-final.log`. |
| Jolt native receive + owner/EOF regressions | **PASS**: 4 tests, 32 assertions, 0 failures/errors/load failures. `jolt-final.log`. |
| Jolt `dao.jing.dht-test` | **PASS**: 44 tests, 257 assertions, 0 failures/errors/load failures. `core-jolt.log`. |
| Failure probe (`sh src/dev/jolt_udp_failures_probe.sh`) | **PASS**: 83/83, exit 0. `udp-failures.log`. |
| Raw probe (`sh src/dev/jolt_udp_probe.sh`) | **BLOCKED**: socket creation raises errno 1; exit 1, not 37/37. `udp-probe.log`. |
| All DHT process scenarios, excluding unrelated `:prompt-hang` | **BLOCKED**: 4 tests, 5 failed bind assertions, 0 errors. Existing JVM/Node scenarios and the new jolt scenario fail at their anchor binds with `SocketException: Operation not permitted`. `process-all.log`. |
| New jolt process scenario alone | **BLOCKED** at the same anchor bind, before reader startup. `process-focused.log`. |
| Fresh strace of the jolt entry point | **BLOCKED** before exec: `PTRACE_TRACEME: Operation not permitted`; exit 1. `reader-strace.log`; empty `reader.trace` is not packet evidence. |
| Lint of the three changed test sources | **PASS**: 0 errors, 0 warnings. `lint-final.log`. |

Acceptance (a) live UDP sends > 1 and (b) live JVM-to-jolt call `4201`
are **not established**. Acceptance (c)'s live JVM process and raw 37/37
legs are **not green in this session**; the portable UDP tests and
injected 83/83 leg are green. No Node/Dart runtime qualification is
claimed; no shared runtime source or jolt runtime source changed.

Commands for the successful focused checks:

```sh
clj -M:test -n yin.repl.dht-step-test -n dao.stream.udp-test
. build/tools/jolt-env.sh
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-test \
  src/dev/jolt_test_runner.clj yin.repl.dht-step-test dao.stream.udp-dht-test
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-test \
  src/dev/jolt_test_runner.clj dao.jing.dht-test
sh src/dev/jolt_udp_failures_probe.sh
```

Existing process legs were attempted with:

```sh
clj -M:test -n yin.repl.dht-process-test -e :prompt-hang
```

Host: Linux x86_64, Ubuntu 24.04.5 LTS, Chez 10.4.1, pinned jolt
`b389f1a7fb553f63a3494d71f8fe3251790bf4d3`. Its checkout has the
pre-existing modified `stdlib/jolt/socket/native.clj`, not changed here.
This is not clean-runtime qualification.

## Commit delivery

The source worktree's Git metadata is outside its writable root. `git add`
fails creating
`/home/sto/ci/datomworld-j6e-linux/.git/worktrees/dw-j7/index.lock` with
`Read-only file system`. Approval policy forbids escalation.

Separate commits were prepared on the same branch in
`target/j7-slice4-delivery`, an isolated local Git copy, and exported as
`target/j7-slice4.bundle`. This does **not** advance the source worktree's
branch.

- `4e597db9`: real JVM-to-jolt process regression, optional UDP strace assertion.
- `695c860d`: native receive/chunk regression and portable EOF reproduction.
- The bundle branch tip is the separate findings commit; use
  `git bundle list-heads target/j7-slice4.bundle` to inspect its full hash.

The incremental bundle requires base `13e7a496`. From a **clean, writable**
checkout of this branch at that base:

```sh
git fetch /home/sto/ci/dw-j7/target/j7-slice4.bundle fix/j7-udp-dht
git merge --ff-only FETCH_HEAD
```

`git bundle verify target/j7-slice4.bundle` is the delivery check. The
bundle includes only the process test, the two receive/lifetime test
sources, and this findings file; unrelated untracked session files were
left untouched.
