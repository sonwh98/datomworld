# Slice C2 Architectural & Implementation Brief

## 1. Goal
Execute Slice C2 of `docs/design/dao.stream.codec.md`:
"Migrate callers to pass explicit codec profiles. Every test in §7.2 names its profile; one parameterized fixture runs codec-neutral suites over both profiles; one-arity descriptor gates replaced by two-arity calls at every site. Defaults still exist but nothing reaches them."

In addition, per user direction on OQ1:
- `yin.repl` wire default is **CBOR by default** (`dao.stream.cbor/profile`).
- Configurable via CLI param (e.g. `--codec cbor|transit`) and dynamically at the REPL prompt (e.g. `(connect url :codec ...)` or `(connect "daostream:ws://..." :codec :cbor)` / command-line `--codec`).

## 2. Worktree & Environment
- Isolated worktree: `../datomworld-codec-c2` on branch `codec-c2`.
- Baseline commit: `eacaed902` (Slice C1 landed on master).
- Old peer tagged commit: `f0-old-peer` (`47f43405c`).

## 3. Scope of Changes
1. **`yin.repl`**:
   - `yin.repl.main`: parse `--codec <cbor|transit>` (defaulting to `:cbor`). Thread `:codec` profile into `boot-server` / `serve!`.
   - `yin.repl.serve`: accept `:codec` / `:codecs` in `serve!`, passing to `remote-channel/serve`. Default to `cbor/profile`.
   - `yin.repl.connect`: accept `:codec` in `open` / `dial` / `reattach`. Default to `cbor/profile`.
   - `yin.repl.driver`: update `(connect ...)` to support optional codec argument e.g. `(connect url)` or `(connect url :codec :transit)`.
2. **One-arity descriptor gates replaced**:
   - Replace one-arity calls to `ws/descriptor?` and `ws/servable-descriptor?` with explicit two-arity calls across `src/` and `test/` (e.g., `remote_channel_test.cljc:174`, `ws_test.cljc:567-570`).
3. **Tests in §7.2 migrated**:
   - `test/dao/stream/ws_test.cljc`
   - `test/dao/stream/ws_codec_test.cljc` & `.cljd`
   - `test/dao/stream/ws/jvm_test.clj`, `node_test.cljs`, `dart_test.cljd`, `browser_test.cljs`
   - `test/dao/stream/ws_project_test.cljc`, `ws_project_jvm_test.clj`, `ws_project_cross_jvm_test.clj`, `ws_project_cross_node_test.cljs`, `ws_project_peer.cljc`
   - `test/dao/stream/remote_channel_test.cljc`
   - `test/dao/stream/loopback_net.cljc`
   - `test/yin/repl/host/jvm_test.clj`
   - Callers supply explicit codec profile; run parameterized fixture for codec-neutral suites over both profiles.
4. **Wire Proof & Bidirectional Verification (§7.3)**:
   - Run bidirectional old/new peer checks against `f0-old-peer` (`47f43405c`).
   - Clean corpora and baseline exceptions tests pass.
