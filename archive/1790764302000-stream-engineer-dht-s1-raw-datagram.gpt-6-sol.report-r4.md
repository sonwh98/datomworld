Completed-GMT: 2026-09-30 15:10:10 GMT
Completed-Local: 2026-09-30 22:10:10 +07 (+0700)
Coding-Agent: gpt-6-sol
# DHT S1 fix round 3 report

Completed F1 and the requested Low and Info edits in the `dht-s1` worktree. Nothing was staged or committed.

## Changes

- `src/clj/dao/stream/datagram/jvm.clj`: before a send, compares the destination IP family with `bind-host`. A mismatch deposits `send-failed` with reason `IP family mismatch` and answers `transport-error`; it does not call the host send. This covers wildcard binds, whose JVM socket may otherwise send across families.
- `test/dao/stream/datagram/jvm_test.clj`: added a `0.0.0.0` wildcard-bind mismatch test with a `send-failed` assertion and surviving IPv4 canary. Renamed its bad-port test to `live-socket-send-failure-preserves-canary`; made the same rename in the Node test.
- `src/cljd/dao/stream/datagram/dart.cljd`: documented that the asynchronous `onError` path is untested in S1.
- `docs/design/dao.stream.datagram.md`: removed the duplicate “dropped”. `src/cljc/dao/stream/datagram.cljc`: removed the trailing whitespace at the writer outcome clamp.

## Test-first evidence

Before the JVM fix, the new wildcard-bind test failed in two assertions: the `::1` send answered `ok` and no `send-failed` event was deposited. The focused suite reported 10 tests, 55 assertions, 2 failures. After the fix, the same focused suite reported 10 tests, 55 assertions, 0 failures, 0 errors.

## Final checks observed

- Kondo on the changed source and test files: 0 errors, 0 warnings. Cljstyle check: clean after formatting the new test. `git diff --check`: clean; a direct trailing-whitespace search of the untracked `datagram.cljc` found none.
- Full `clj -M:test` (`target/r4-clj.log`): **2,440 tests, 185,174 assertions, 0 failures, 0 errors**.
- `bb test:cljs` (`target/r4-cljs.log`): **2,344 tests, 51,582 assertions, 0 failures, 0 errors**; shadow-cljs reported 0 warnings.
- `bb test:cljd` (`target/r4-cljd.log`): **2,303 passed; all tests passed**. Babashka printed a process-cleanup `sysctl failed` exception after the visible green verdict; the command exited 0.

The Dart `onError` behavior remains without a direct host-error test, as the Architect explicitly accepted for S1. The existing S1 throughput measurements were not repeated in this fix round.
