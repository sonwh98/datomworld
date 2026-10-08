Completed-GMT: 2026-09-30 14:17:42 GMT
Completed-Local: 2026-09-30 21:17:42 +07 (+0700)
Coding-Agent: gpt-6-sol
# DHT S1 fix round 1 report

Implemented the binding sign-off fixes in the `dht-s1` worktree. Nothing was staged or committed.

## Changes

- `datagram.cljc`: IPv6 compression accepts only a truly empty piece, so whitespace cannot become an IP literal. The writer clamps any seam outcome outside `ok`, `transport-error`, and `closed` to `transport-error`.
- Host seams: removed the unsupported claim that oversize drops are counted. The JVM bind-failed reason now uses `(str e)`. The Node docstring records the close-before-listening behavior. The Dart seam handles subscription errors as `send-failed` events and marks a socket closed once on `RawSocketEvent.closed`; its empty-send wording now reflects the ambiguous zero return.
- Tests: added the five whitespace IPv6 cases, a scripted `:full` seam case, a budget case with skipped lifecycle and DHT events, and live-socket failure plus canary checks on JVM, Node, and Dart. The Node and JVM live-socket checks use a host-rejected port. The Dart check uses an IPv6 destination from an IPv4-bound socket and accepts either a surviving canary path or an observed close after send failure.
- Docs: amended `dao.stream.datagram.md` §§2 and 4 for scoped link-local addresses, synchronous `send-failed`, lack of correlation, traffic-ring cost, Dart empty-send behavior, and oversize dropping. Updated its status and the retired seam names in `dao.stream.remote.implementation-plan.md`.

## Test-first evidence and checks

- Before the portable fixes, full `clj -M:test` showed the five whitespace cases and the scripted `:full` case failing: 2,437 tests, 185,162 assertions, six failures. The new skipped-event budget case passed without a code change.
- The first `bb test:cljs` run showed the new Node IPv6-to-IPv4 send expectation was wrong on this host: `send!` answered `ok` and emitted no `send-failed`; the canary path continued. I changed that test to a host-rejected port. The first run's process exit was 0 despite the test failure text, so I did not count it as green.
- `clj -M:kondo --lint` on the nine changed source/test files: 0 errors, 0 warnings, including after the final Dart test edit.
- Full `clj -M:test`: 2,438 tests, 185,166 assertions, 0 failures, 0 errors. A later focused JVM host-suite run after its test adjustment: 8 tests, 47 assertions, 0 failures, 0 errors.
- Final `bb test:cljs`: 2,343 tests, 51,578 assertions, 0 failures, 0 errors; shadow-cljs build completed with 0 warnings. Babashka printed a post-run `sysctl failed` process-tree cleanup exception despite exit 0 and the observed green test verdict.
- `git diff --check`: clean.

## Dart lane and remaining verification

`bb test:cljd` could not reach compilation or the Dart probe. Flutter's `update_engine_version.sh` failed writing `/Users/sto/.local/share/mise/installs/flutter/3.47.4-stable/bin/cache/engine.stamp.tmp.*` and `engine.realm` with `Operation not permitted`; cljd then reported `EOF while reading` in `mk-live-analyzer-info`. Babashka also reported `Operation not permitted (sysctl failed)` during process cleanup. The orchestrator needs to run the Dart lane and evaluate the IPv4-bound-to-`::1` probe. I have not seen a Dart compile or test result for this round.

The final Dart test edit occurred after the blocked lane and was only linted, so its runtime behavior remains unverified. All prior S1 files and throughput measurements remain as stated in the GLM report; this round did not remeasure throughput.
