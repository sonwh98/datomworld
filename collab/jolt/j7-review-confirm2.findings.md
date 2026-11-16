Reviewed-GMT: 2026-11-16 05:00:26 UTC
Coding-Agent: codex
Role: reviewer
Branch: `fix/j7-udp-dht`
Reviewed-HEAD: `a7c82518dd32bdff2893ccb20994f1a2bdac7a6a`
Reviewed-fixes: `8668198f207d4af8a390338e9b7725e183e3449c`, `ebbd019963c385bd71014a172e4fffc17866f6db`
Verdict: both reopened P2 findings CONFIRMED-FIXED for the requested injections. Real-socket J0b acceptance remains unverified in this sandbox.

# Per-finding verdicts

- **P2 | CONFIRMED-FIXED | Helper acquisition exception safety** — `src/jolt/dao/stream/udp.jolt:126–164`. Independently injected failure of the receive helper's 4-byte length-cell allocation after acquisition of the 1201-byte receive buffer and 128-byte sockaddr storage: **0 unreleased bytes**, replacing the prior 128-byte leak. Independently injected address-string allocation failure during bind and send: **0 unreleased bytes** in both cases, replacing the prior sockaddr leak and covering the send payload buffer. Bind failure closes fd 123 exactly once and deposits only `bind-failed` with the original injected reason. Send/receive allocation exceptions preserve descriptor ownership until explicit close; repeated close calls close exactly once. The committed helpers and their installation work on the clean pinned runtime without the engineer's uncommitted stdlib edit.
- **P2 | CONFIRMED-FIXED | Descriptor versus asynchronous receive-error classification** — `src/jolt/dao/stream/udp.jolt:351–366`. Injected Linux `ECONNREFUSED` (111) returns `{:progressed? false}` after one syscall, retains an observable instance-owned `{:kind :receive-error :errno 111 ...}` diagnostic, emits no `closed` event, and leaves the seam open. A subsequent turn issues another receive, and an injected successful send returns `:dao.stream/ok`. The committed probe additionally verifies subsequent packet delivery. Injected `EBADF` (9) records `:fatal-descriptor-error`, closes fd 123 **exactly once**, emits one `closed` event, and makes subsequent turns inert without another syscall; repeated explicit closes remain idempotent. This distinction matches the documented possibility of receiving an asynchronous error for an earlier packet, including ECONNREFUSED, on an unconnected UDP socket. [Linux udp(7), Error handling and ERRORS](https://man7.org/linux/man-pages/man7/udp.7.html).

# Independently executed validation

Every result below comes from a fresh command in this review, not a prior report. Host: Linux x86_64, Ubuntu 24.04.5 LTS; Chez 10.4.1; Jolt pin `b389f1a7fb553f63a3494d71f8fe3251790bf4d3`.

The existing `build/tools/jolt` checkout has one pre-existing uncommitted change to `stdlib/jolt/socket/native.clj`, duplicating helper cleanup. To exclude that as the reason for success, I made a separate local clone of the exact pin and its locally available pinned submodules at `build/j7-review-confirm2/clean-jolt`. Its `git status --porcelain` was empty before and after the runs. The existing runtime and its modifications were left intact; no toolchain pin or implementation source was changed.

| Check | Fresh result |
|---|---|
| `bb probe:jolt-udp-failures` | Exit 0; **83 assertions, 83 pass, 0 fail**. Includes partial helper allocations, ECONNREFUSED recovery, EBADF close-once, setup-option failures, EINTR, would-block, send errors/no replay, and receive budget. |
| Same committed failure probe on clean pinned Jolt | Exit 0; **83 assertions, 83 pass, 0 fail**. |
| Original review injections plus independent reopened-case assertions on clean pinned Jolt | Exit 0; **65 assertions passed**, comprising the original 35 contract assertions plus 30 new assertions for the reopened cases. |
| `bb probe:jolt-udp` | Exit 1 before any assertion: `socket failed`, `{:errno 1}` (EPERM). **37/37 cannot be independently confirmed here.** Header identifies the existing runtime as `dirty=1`. The missing `/proc/sys/net/ipv4/udp_mem` diagnostic is separate from the socket failure. |
| Jolt `dht:` solo smoke | Exit 0; boots, evaluates `(+ 1 2)` to **3**, reports a durable local publication of **9 blobs**, then `Bye`. Fresh store: `/tmp/j7-confirm2-dht-yPUUJe`; uses `--no-state`. Output explicitly states solo mode opens no socket and has no peer acknowledgement. |
| JVM UDP/datagram suites | Exit 0; **37 tests, 185 assertions, 0 failures, 0 errors**, including `dao.stream.udp-test`, `dao.stream.udp-raw-test`, and `dao.stream.datagram-test`. |

Commands ran from the worktree root with 120-second timeout bounds. Babashka/JVM setup used worktree-local `MISE_STATE_DIR` and `MISE_CACHE_DIR`, with `MISE_TRUSTED_CONFIG_PATHS="$PWD"`, then `eval "$(mise env -s bash)"`. Jolt commands sourced `build/tools/jolt-env.sh`.

```sh
bb probe:jolt-udp-failures
build/j7-review-confirm2/clean-jolt/bin/jolt -Srepro -M:jolt src/dev/jolt_udp_failures_probe.clj
build/j7-review-confirm2/clean-jolt/bin/jolt -Srepro -M:jolt build/j7-review-confirm2/reopened-injections.clj
bb probe:jolt-udp
printf '(+ 1 2)\n' | "$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-yin-repl --no-state --index-store dht:/tmp/j7-confirm2-dht-yPUUJe
clojure -M:test -n dao.stream.udp-test -n dao.stream.udp-raw-test -n dao.stream.datagram-test
```

Full fresh outputs and the independent injection script are in ignored `build/j7-review-confirm2/`: `failures.log`, `failures-clean.log`, `injections-clean.log`, `j0b.log`, `solo.log`, `jvm-udp.log`, and `reopened-injections.clj`. The injections exercise production binder/send/receive closures and real native allocations with fake socket syscalls and fd 123; they do not establish real-network behavior. The earlier P1 process/interoperability acceptance remains open; this targeted confirmation does not close it.

Only this findings document is intended for the requested commit. No implementation, committed test, applicability file, or supplied execution log was changed.

# Commit status

Findings whitespace validation passed using `git diff --no-index --check /dev/null collab/jolt/j7-review-confirm2.findings.md`. Staging the requested document was attempted with `git add -- collab/jolt/j7-review-confirm2.findings.md` and failed with exit 128:

```text
fatal: Unable to create '/home/sto/ci/datomworld-j6e-linux/.git/worktrees/dw-j7/index.lock': Read-only file system
```

The worktree's `.git` points to metadata outside this session's writable roots. No commit was created; the completed findings document remains untracked at the requested path. Completing the commit requires an environment allowed to write that Git metadata. No metadata, permissions, or existing files were altered to bypass the restriction.
