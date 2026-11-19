# J7 slice 3 — DHT owner stepping and subcommand expansion

Date: 2026-11-19. Coding agent: codex. Role: engineer.
Base: `5251e70568b3632055a138d505b73ce2741feabf`, branch
`fix/j7-udp-dht`, workspace `/home/sto/ci/dw-j7`.

Status: the parser defect is fixed and the existing bounded stepper is
covered by a regression. Live acceptance is incomplete because this
session denies sockets and ptrace; see the acceptance table below.

## 1. The polled node seam is already wired on this base

The dispatch's missing-stepper premise does not match this checkout.
No additional transport owner, receive loop, or runtime step was added.
The shipped integration is:

```text
yin.shell.main/composition
  :step = yin.shell.dht/step
yin.repl.main/step-all
  -> composition-step (passes and retains the composition session)
  -> yin.shell.dht/step
  -> dao.space.dht/step
     -> (:step! (:seam node)), once, before bind-step
     -> dao.jing.dht/step, budget 64 per stage
```

Jolt `main!` calls `run-owner!`, which calls that same `step-all` used by
Node's tick function. Pending requires are served/rechecked by the
composition around this round; they do not acquire a second transport
step owner. `dao.space.dht/default-bind` selects `dao.stream.udp/bind!`
on jolt. That binder returns `:step!`; its receive loop has a 64-attempt
budget and stops on would-block/interruption. The call at
`dao.space.dht/step` was already committed in `3ab1a5ccf`.
The dispatch's line 1432 is in `dao.space.dht`, not `dao.jing.dht`.

`port-step!` belongs to the UDP value-channel port. This DHT composition
instead reads the raw datagram traffic stream through its own cursor,
as the datagram design specifies. Adding a value-port reader would not
repair a missing name or a solo configuration.

Added `test/yin/repl/dht_step_test.cljc` to protect the actual engine-round
integration. Its seam deposits `bound` only when pumped, records sends,
and never uses a host socket. The shipped shell composition processes a
real `(require (quote my.lib))` with a composition-provided name address.
The test verifies binding, a parked require, a DHT send to the configured
peer, retained loading state, and exactly one seam call per owner round
(three calls for three rounds). A jolt-only assertion verifies that the
default binder is the UDP binder. The direct name entry deliberately
does not claim signed-head following or live network delivery.

Verification on the unchanged runtime stepping code:

```text
jolt: yin.repl.dht-step-test
  2 tests, 10 assertions, 10 pass, 0 fail, 0 error
JVM: yin.repl.dht-step-test
  1 test, 9 assertions, 0 failures, 0 errors
```

## 2. Fix: global flags preceding `dht` bypassed subcommand expansion

The supplied `/tmp/j7-reader.sh` launches this argument shape:

```text
--reset --index-store dht:/tmp/jx-jolt dht join <token>
```

Startup removes `--reset`, then calls `expand-args`. Previously that
function examined only the first argument, now `--index-store`, and
fell back to `plain-args`. Consequently `dht join <token>` reached
`parse-args` as unused extra arguments. The explicit store opened with
no peer, principal, or follow source. This bug is in shared parsing,
not a missing jolt entry-point hook. A command-first `dht join <token>`
already expanded correctly on jolt before this change (checked directly
through `yin.shell.main/expand-args`).

`yin.shell.cli/expand-args` now skips complete global flags using the
existing flag table, finds the DHT command, and hands the prefix to the
same `dht-args` expansion used by command-first invocations. `dht-args`
also preserves valued global flags as pairs, so their values cannot be
mistaken for the join token. Explicit flags retain their precedence over
subcommand defaults, including the explicitly supplied store directory.
No host-specific parser or duplicated join mapping was introduced.

Added `test/yin/repl/dht_args_test.cljc`, runnable on jolt without
sockets. It covers all three DHT verbs, flags before the command and
before/after the token, principal/peer/follow derivation, the explicit
store, saved publish/key clearing, flag values equal to command words,
and the supplied reader argument order through `startup` with only
store opening substituted. Initial JVM red run: 4 tests, 14 assertions,
8 failures, 0 errors. Final runs:

```text
JVM focused: 5 tests, 35 assertions, 0 failures, 0 errors
jolt focused: 6 tests, 36 assertions, 36 pass, 0 fail, 0 error,
              0 load failures
JVM dao.stream.udp-test: 12 tests, 36 assertions, 0 failures, 0 errors
bb probe:jolt-udp-failures: 83 assertions, 83 pass, 0 fail, exit 0
```

Commands (run from the workspace, logs under `/tmp/j7-slice3-*`):

```sh
# Completed before booting either JVM shell or JVM tests.
clj -M:antlr-gen > /tmp/j7-slice3-antlr.log 2>&1
clj -M:test -n yin.repl.dht-step-test -n yin.repl.dht-args-test \
  > /tmp/j7-slice3-focused-jvm.log 2>&1
clj -M:test -n dao.stream.udp-test > /tmp/j7-slice3-udp-jvm.log 2>&1
. build/tools/jolt-env.sh
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-test \
  src/dev/jolt_test_runner.clj yin.repl.dht-step-test yin.repl.dht-args-test \
  > /tmp/j7-slice3-jolt.log 2>&1
PATH=/home/sto/.local/share/mise/installs/babashka/1.13.225:$PATH \
  bb probe:jolt-udp > /tmp/j7-slice3-udp.log 2>&1
PATH=/home/sto/.local/share/mise/installs/babashka/1.13.225:$PATH \
  bb probe:jolt-udp-failures > /tmp/j7-slice3-udp-failures.log 2>&1
```

The host reports Linux x86_64, Ubuntu 24.04.5 LTS, Chez 10.4.1, jolt
`b389f1a7fb553f63a3494d71f8fe3251790bf4d3`, with one pre-existing dirty
runtime file (`stdlib/jolt/socket/native.clj`). That runtime checkout was
not modified here. No clean-runtime or Node/Dart qualification is claimed.

## Acceptance evidence and remaining work

| Requirement | Observed result |
| --- | --- |
| a. strace, pending jolt require, `sendto > 0` | **Blocked.** `strace -f -e trace=socket,sendto` fails before launching jolt: `PTRACE_TRACEME: Operation not permitted`. The empty trace is not evidence of a silent node. Log: `/tmp/j7-slice3-reader.log`. |
| b. spy shows both directions | **Blocked.** Running the supplied `/tmp/udp-spy.py` fails at `socket.socket(AF_INET, SOCK_DGRAM)`: `PermissionError: [Errno 1] Operation not permitted`. Log: `/tmp/j7-slice3-spy-attempt.log`. No packets captured. |
| c. live JVM publisher → jolt name require → `4201` | **Blocked.** Both publisher and reader reach their UDP bind and receive `EPERM`. No live name resolution, head following, or evaluation result is claimed. |
| d. raw UDP probe 37/37 | **Blocked.** The first `udp-open` throws `socket failed`, `{:errno 1}`; exit 1, not 37/37. `/tmp/j7-slice3-udp.log`. |
| d. UDP failure probe 83/83 | **PASS**, 83/83, exit 0. `/tmp/j7-slice3-udp-failures.log`. Its native calls are explicitly substituted by the existing fault-injection fixture. |
| e. JVM UDP lane unchanged | **PASS**, 12 tests, 36 assertions, 0 failures/errors. `/tmp/j7-slice3-udp-jvm.log`. No UDP implementation files changed. |

For (c), attempted a fresh JVM publisher using the probe's A/B peer
addresses, without its broad process-killing or home-directory cleanup:

```sh
timeout 90 clj -M:clj-yin-repl dht init --no-state \
  --dir /tmp/j7-slice3-publisher --key /tmp/j7-slice3-publisher.key \
  --listen 19999 --peer localhost:19998 --peer localhost:19997 < /dev/null \
  > /tmp/j7-slice3-publisher.log 2>&1
```

It reports `the socket could not bind: java.net.SocketException:
Operation not permitted`. Also attempted the jolt reader without strace,
using the existing public token from `/tmp/j7-token.txt`:

```sh
timeout 240 build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-yin-repl \
  --no-state --index-store dht:/tmp/j7-slice3-reader \
  dht join "$(cat /tmp/j7-token.txt)" <<'EOF'
(println :REQ (require (quote my.lib)))
(println :CALL-RESULT (f 1))
EOF
```

`/tmp/j7-slice3-reader-untraced.log` now reports:

```text
dht: binding 127.0.0.1:0 (ephemeral); peers 127.0.0.1:19999
dht: fetch-only, nothing this node holds is shared (--dht-publish shares it)
...
yin> dht: refused: the socket could not bind: socket failed: errno 1
```

This confirms the native entry point no longer composes solo for the
supplied argument order. The furthest verified link progression is the
host-free parked-require/send regression, not live packet exchange.
The live checks must be rerun in a session with UDP and ptrace available;
whether any signed-head-following gap remains cannot be determined here.

## Execution limits and commit delivery

This session's sandbox permits writes only in the workspace and `/tmp`.
The worktree's Git metadata is elsewhere and read-only. Staging was
attempted, with this result:

```text
fatal: Unable to create '/home/sto/ci/datomworld-j6e-linux/.git/worktrees/dw-j7/index.lock': Read-only file system
```

The source branch cannot be advanced in this session. Changes and this
evidence are committed separately in an isolated local copy of the
same branch, with an incremental bundle under `target/j7-slice3.bundle`
for application from a writable session. This is not a claim that the
source worktree branch was updated. The first isolated commit is
`58ba62e7` (stepper regression plus its evidence); the second carries the
parser fix, its regression, and the completed acceptance record.

The bundle requires the base commit named above. From a clean, writable
worktree of this branch still at that base:

```sh
git fetch /home/sto/ci/dw-j7/target/j7-slice3.bundle fix/j7-udp-dht
git merge --ff-only FETCH_HEAD
```
