# C3 fixes (Sonnet 5.5) — R1..R4

- R1: `node_test.cljs` `server-fixture` now defaults `:codecs` to `[transit/profile]` and passes it to both `make-endpoint` and `node/listen!`. Also fixed `make-client` (same removed default; passes `:codec (or codec transit/profile)`), which surfaced when running the lane.
- R2: `ws/make-endpoint` requires `(vector? codecs)`; new test `make-endpoint-requires-a-vector-of-codec-profiles` (list, set rejected; vector accepted) in `ws_test.cljc`.
- R3: `dart/bind!` validates a non-empty vector of codec profiles before `HttpServer.bind`; throws, no listener, no deposit.
- R4: added `a-codec-less-endpoint-or-listener-throws` (node_test.cljs) and `a-codec-less-bind-throws-before-allocating-a-listener` (dart_test.cljd).

Verification: `bb test:sub:clj dao.stream` 503 tests, 0 failures; `bb test:sub:cljs dao.stream` 506 tests, 0 failures.
NOT run: Dart/cljd lane (change to `dart.cljd` and `dart_test.cljd` is uncompiled/untested). Nothing committed.
