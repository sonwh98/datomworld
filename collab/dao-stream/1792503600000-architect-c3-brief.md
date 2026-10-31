# Slice C3 Architectural & Implementation Brief

## 1. Goal
Execute Slice C3 of `docs/design/dao.stream.codec.md`:
"Contract: `dao.stream.ws` drops the Transit require, `checked-codec`'s default, the one-arity gates, the `make-endpoint` default and the no-offer default (refused). The four adapters drop `default-codecs`, the Transit require and the `ws/subprotocol` fallback. `remote-channel` refuses `::no-codec`."

## 2. Worktree & Environment
- Working tree: branch `codec-c2` in `../datomworld-codec-c2` (stacked on C2).
- Baseline: C2 verified state.

## 3. Scope of Changes
1. `src/cljc/dao/stream/ws.cljc`:
   - Drop require `[dao.stream.transit :as transit]`.
   - `checked-codec`: drop Transit default; require valid profile or throw.
   - Delete 1-arity `(defn descriptor? [x])` and `(defn servable-descriptor? [x])` (keep only 2-arity `[x codec]`).
   - `make-endpoint`: drop `:codecs` default. Absence or empty vector throws/refuses.
   - No-offer case: when client offers no subprotocol or unknown subprotocol, connection is refused (no fallback to Transit).
2. Host Adapters (`src/clj/dao/stream/ws/jvm.clj`, `src/cljs/dao/stream/ws/node.cljs`, `src/cljd/dao/stream/ws/dart.cljd`, `src/cljs/dao/stream/ws/browser.cljs`):
   - Drop require on `dao.stream.transit`.
   - Drop `default-codecs`.
   - Drop fallback to `ws/subprotocol`.
3. `src/cljc/dao/stream/remote_channel.cljc`:
   - Refuse with `::no-codec` when `:codecs` or `:codec` is absent/empty.
4. Acceptance & Tests (§7.1 C3):
   - Codec-less `make-attacher`, `make-endpoint`, `listen!`/`handle-request!` throw (one test per host).
   - Seam without a name is refused.
   - `remote-channel` returns `::no-codec`.
   - Update `remote_channel_test.cljc:244` (`an-explicit-empty-codecs-is-refused-and-omitted-defaults-to-transit` -> test both explicit empty and omitted return `::no-codec`).
   - Documentation: `docs/design/dao.stream.ws.md` "absent name" sentence amended.
   - Grep gate: `grep -rn "dao.stream.transit" src/` hits only `transit*.clj*` and `yin/repl/{serve,connect}`.
   - Frozen fixtures pass 100%.
