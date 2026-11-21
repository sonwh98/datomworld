# J7 slices 3+4 review

Reviewer: codex. Date: 2026-11-21. Branch: `fix/j7-udp-dht`.
Reviewed checkout: `533216bec2354d9263a8a17a146a3e8172b39a64`.

**Verdict: no actionable P0–P3 code findings. Live acceptance remains
unverified in this review session.** The passing injected and host-free
tests do not establish a successful JVM-to-jolt network exchange.

| Severity | Code findings |
| --- | --- |
| P0 | None |
| P1 | None |
| P2 | None |
| P3 | None |

## Scope and comparison

At review time `origin/master` and `HEAD` both resolve to the reviewed
checkout above; `git diff origin/master..HEAD` is empty. To avoid a vacuous
review, I inspected the requested changes as introduced by `ea5226f4`
(shared parser and regressions) and `f85b68cd` (process, native receive and
EOF regressions), against their respective parents, and read their current
versions and surrounding startup/process/DHT code. The five scoped source
and test files have the same cumulative changes in `ea5226f4^..HEAD`.

Contract references: `docs/design/datom.world.md`,
`docs/design/dao.stream.datagram.md`, and the existing shared state/CLI and
DHT compositions. This review makes no assessment of unrelated J7 changes.

## Review conclusions

- `src/cljc/yin/shell/cli.cljc:374–467`: expansion uses the existing flag
  table to skip switches or complete valued flags before looking for
  `dht`. It retains the prefix in order and passes it through the same
  `dht-args` implementation as command-first input. Within that parser,
  recognized valued flags remain pairs, so their values cannot become the
  join token. Explicit flags follow generated defaults, preserving the
  existing scalar override and repeated-flag behavior. A command word
  used as a recognized flag's value remains a value. Plain invocations
  still fall through to `plain-args`.
- The parser change contains no new reader conditional, socket operation,
  host parser hook or duplicated host implementation. The same CLJC code
  remains the path for JVM, Node, Dart and jolt. No Node/Dart regression was
  identified by inspection; those two runtimes were not executed here.
- `dht_args_test.cljc` exercises all three DHT verbs, value/command
  ambiguity, explicit-store precedence and startup expansion. The
  `dht_step_test.cljc` fake seam deposits `bound` through the real traffic
  stream and checks the shipped owner's pump before DHT processing, one
  turn per round, including a parked require. The EOF case documents and
  exercises shutdown with a pending require; it is an engine integration
  test using an explicitly submitted quit, not a native stdin test.
- `dht_process_test.clj:1029–1098` uses a fresh JVM publisher, anchor,
  stores and key, and launches the shipped jolt shell. The reader gets a
  token and explicit trust, rather than a manifest/module address. Its
  writer remains open while the test waits for require, and the call is
  submitted only after require succeeds. Both shells use `spawn!`, which
  adds `--no-state`; saved home-node configuration cannot affect the run.
  The strace mode requires multiple successful sends with IPv4 destination
  sockaddrs, excluding the TCP head-board handshake with a null destination.
  The same test also requires the by-name result and `4201`.
- `udp_dht_test.jolt` substitutes kernel calls while retaining native
  buffers/sockaddrs, the production binder and datagram writer, Base64,
  traffic cursor, CBOR and DHT stepping. Both payloads traverse a cookie
  challenge; the larger one verifies chunk reassembly and cache content.
  Its bounded, owner-driven stepping agrees with the datagram composition
  contract. This is injected receive-path evidence, not live wire evidence.

## Acceptance executed in this session

All logs below were produced afresh in this checkout; historical retained
acceptance logs were not treated as this review's test results.

| Check | Observed result | Log under `/tmp/` |
| --- | --- | --- |
| JVM `yin.repl.dht-step-test`, `yin.repl.dht-args-test`, `dao.stream.udp-test` | PASS: 18 tests, 78 assertions, 0 failures/errors; exit 0 | `j7-s34-focused-jvm.log` |
| Jolt same two focused REPL namespaces plus `dao.stream.udp-dht-test` | PASS: 8 tests, 58 assertions, 0 failures/errors/load failures; exit 0 | `j7-s34-focused-jolt.log` |
| Jolt `dao.jing.dht-test` | PASS: 44 tests, 257 assertions, 0 failures/errors/load failures; exit 0 | `j7-s34-dht-core-jolt.log` |
| Jolt `dao.stream.udp-test` | PASS: 12 tests, 36 assertions, 0 failures/errors/load failures; exit 0 | `j7-s34-udp-jolt.log` |
| Existing JVM state/subcommand precedence regression | PASS: 1 test, 9 assertions, 0 failures/errors; exit 0 | `j7-s34-state-precedence.log` |
| Focused jolt process scenario with `JOLT_DHT_STRACE=/tmp/j7-s34-live.trace` | BLOCKED: 1 test, 1 failed anchor-bind assertion, 0 errors; exit 1. Reader never launched; no UDP trace acceptance obtained. | `j7-s34-process-strace.log` |
| Process namespace, excluding unrelated `:prompt-hang` test | BLOCKED: 4 tests, 5 failed anchor-bind assertions, 0 errors; exit 1 | `j7-s34-process-all.log` |
| `bb probe:jolt-udp` | BLOCKED at first socket creation, errno 1; exit 1. Not a 37/37 pass. | `j7-s34-udp-probe.log` |
| `bb probe:jolt-udp-failures` | PASS: 83/83 assertions; exit 0 | `j7-s34-udp-failures.log` |
| Independent `strace ... true` capability check | BLOCKED: `PTRACE_TRACEME: Operation not permitted`; exit 1 | `j7-s34-ptrace.log` |

The process failures all report
`bind-failed: java.net.SocketException: Operation not permitted` before
publisher/reader startup. The raw jolt probe independently reports
`socket failed`, `{:errno 1}`. Although the dispatch says sockets work on
this host, this tool session denies both socket creation and ptrace.
These are execution restrictions, not demonstrated defects in the scoped
changes. The requested live `4201` and multiple-UDP-send acceptance must
still run in a session that permits those operations. The Node REPL build
is absent here; the blocked process run did not exercise a Node child.

Commands used (Babashka was added to PATH from its installed mise path):

```sh
clj -M:test -n yin.repl.dht-step-test -n yin.repl.dht-args-test -n dao.stream.udp-test
. build/tools/jolt-env.sh
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj yin.repl.dht-step-test yin.repl.dht-args-test dao.stream.udp-dht-test
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj dao.jing.dht-test
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj dao.stream.udp-test
JOLT_DHT_STRACE=/tmp/j7-s34-live.trace clj -M:test -n yin.repl.dht-process-test -v yin.repl.dht-process-test/jolt-reader-requires-a-jvm-publication-by-name
clj -M:test -n yin.repl.dht-process-test -e :prompt-hang
clj -M:test -n yin.repl.state-test -v yin.repl.state-test/a-dht-subcommand-resumes-and-serve-stops-publishing
bb probe:jolt-udp
bb probe:jolt-udp-failures
strace -f -e trace=sendto,recvfrom -o /tmp/j7-s34-ptrace.trace true
```

Host/runtime: Linux x86_64, Ubuntu 24.04.5 LTS; configured Chez 10.4.1;
jolt `b389f1a7fb553f63a3494d71f8fe3251790bf4d3`. Its checkout already has a
modified `stdlib/jolt/socket/native.clj`; I did not change it. Results are
for that existing runtime, not a clean-runtime qualification. Whitespace
checks passed for both scoped introduction diffs. No fixes were made.

## Findings commit delivery

Staging this findings file in the source worktree failed:

```text
fatal: Unable to create '/home/sto/ci/datomworld-j6e-linux/.git/worktrees/dw-j7/index.lock': Read-only file system
```

The session cannot write that metadata or request escalation. The findings
are therefore committed in the isolated local Git copy
`target/j7-s34-review-delivery`, based on the reviewed checkout, and exported
as `target/j7-s34-review.bundle`. This does not advance the source branch.
The bundle contains one findings-only commit; its parent is
`533216bec2354d9263a8a17a146a3e8172b39a64`.

From a writable checkout, the commit can be applied with:

```sh
git fetch /home/sto/ci/dw-j7/target/j7-s34-review.bundle refs/heads/j7-s34-review-findings
git cherry-pick FETCH_HEAD
```
