Completed-GMT: 2026-09-30 14:51:31 GMT
Completed-Local: 2026-09-30 21:51:31 +07 (+0700)
Coding-Agent: gpt-6-sol
# DHT S1 fix round 2 report

Fixed the Dart IPv4-socket failure confirmed by the orchestrator's probe. Nothing was staged or committed.

## Changes

- `src/cljd/dao/stream/datagram/dart.cljd`: a destination with a different IP family from the bound socket is refused before `RawDatagramSocket.send`, answering `transport-error` and depositing one `send-failed`. The socket remains usable. An asynchronous socket error now deposits `send-failed`, marks the socket closed once, and closes the host socket.
- `src/cljs/dao/stream/datagram/node.cljs`: applies the same family-mismatch refusal so the host seams agree. The JVM seam already refused this mismatch synchronously through its host API.
- `test/dao/stream/datagram/dart_test.cljd`: the probe now requires exactly `transport-error`, a `send-failed` event, no `closed` event, and a successful IPv4 canary round trip.
- `test/dao/stream/datagram/{jvm,node}_test`: added explicit family-mismatch, failure-event, and canary coverage.
- `docs/design/dao.stream.datagram.md`: records the address-family rule and continued socket usability.

## Test-first observations

- The orchestrator's pre-fix Dart probe (`target/orch/r2-cljd.log`) showed a mismatched `::1` send leaving the IPv4 socket unable to send a canary, without a `closed` event. Its second compiled occurrence timed out.
- The new JVM family-mismatch test passed before the fix: 9 focused JVM host tests, 51 assertions, no failures. Its host API already refused the send.
- The new Node test failed before the fix in precisely two assertions: the mismatched send answered `ok` and no `send-failed` event appeared (`target/r3-cljs-red.log`, 2,344 tests, 51,582 assertions, 2 failures). The canary continued to work.

## Final checks seen in this worktree

- Focused JVM host suite: 9 tests, 51 assertions, 0 failures, 0 errors. No full JVM lane was run in this fix round.
- `bb test:cljd`: **2,303 passed, all tests passed**, including the tightened Dart probe (`target/r3-cljd.log`). The command required a sandbox override because Dart telemetry writes `/Users/sto/.dart-tool/dart-flutter-telemetry-session.json`, outside the writable roots. Earlier default-sandbox attempts failed there or at `/Users/sto/.config/flutter/tool_state`; neither reached a test verdict.
- Final `bb test:cljs`: **2,344 tests, 51,582 assertions, 0 failures, 0 errors**, shadow-cljs build with 0 warnings (`target/r3-cljs.log`). Babashka again printed a `sysctl failed` process-cleanup exception after the green test verdict.
- `cljstyle check` on the changed source and tests: clean after formatting fixes. `clj -M:kondo --lint` on those files: 0 errors, 0 warnings. `git diff --check`: clean.

The final formatting pass changed only whitespace after the green host runs. Prior S1 throughput measurements remain in the GLM report; this fix round did not remeasure throughput.
