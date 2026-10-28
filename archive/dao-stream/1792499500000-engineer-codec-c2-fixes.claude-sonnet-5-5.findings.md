# C2 fixes (R1, R2, N1, N6) — engineer report

- R1: `:ws/subprotocol seam` added to the four raw sockets in `test/dao/stream/ws_test.cljc`; `dart_test.cljd` socket gets `:ws/subprotocol (codec/codec-name transit/profile)` (added the `dao.stream.codec` require). Dart file not compiled here.
- R2: `docs/design/dao.stream.codec.md` §6.4, §7.1 (C2 and C5 rows), §10 OQ1 (resolved 2026-10-20), §11 updated.
- N1: `ws_project_peer.cljc` rejects an unknown `--codec` (prints error, exit 2); `exit!` now takes an optional code.
- N6: `yin.repl.md` documents `--codec` and `(connect url :codec ...)`.
- Tests: `bb test:sub:clj dao.stream`: 35 namespaces, 499 tests, 0 failures. JVM lane only; cljs/cljd lanes not run.
- Not verified: fixture namespace explicitly named in the output (see fixture grep); the docs describe the owner decision, I did not check that `yin.repl` code implements `--codec`/`:codec` (the working tree already has uncommitted yin.repl changes).
- Nothing committed.
