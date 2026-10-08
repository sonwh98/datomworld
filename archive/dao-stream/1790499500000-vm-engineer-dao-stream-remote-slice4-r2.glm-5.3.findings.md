# dao.stream.remote Slice 4, Round 2 — COMPLETE

## Audit of the partial work (defects found and fixed)

The dead implementer's tree had real holes beyond the unfinished port:

- `content.cljc` `serve-one!`'s `cond` had an **odd form count** (never compiled — serve-step was untested, as suspected).
- `content/step.cljc` `accepted-value` returned `nil` for **both** a refused reply and a stored `nil` payload — a found nil answered `:error integrity-failure`. Ported the old `refused` host-object sentinel (cljs keyword-literal hazard documented in its docstring) into both the get decode and the `:present` verify.
- `content/step.cljc` `step` **never cleared `:diagnostics`** — the take-exactly-once contract was broken; every diagnostic re-reported forever.
- `content/step.cljc` `submit` had **no terminal check** — the docstring promised `:terminal` outcomes after the reader ends; requests were still appended onto a dead pair.
- Verify reads were ordered by `sort-by first` over put-ids — **lexical order over random UUIDs**, not issue order. Records now carry `:seq` (minted from `:next-seq`) and the sort is by issue order.
- `driver.cljc` `resolve-get` treated a found `nil` value as absent (`if-some`) — a stored nil read back as `::absent`. Fixed alongside the sentinel work.
- `coordinate.cljc` `open-remote`'s `#?(:cljd X :clj Y)` had **no default** — on cljs `open!` returned `nil`; now `(:clj driver … :default client-state)`.
- The linker was **half-migrated and did not compile**: `link-state` took `:content` but `issue-request`/`step`/`abandon` still called the un-required `rpc/*` API.

## What was built

- **Linker M3 core** rewritten onto the content vocabulary: `:content {:requests :answers :cursor :unsent :lost :terminal}` — the linker speaks `{:jing/request r :jing/get a}` itself (it does **not** embed `dao.jing.content.step`: its `:max-bytes` cap must fire on the raw Base64 text *before* any decode, and content.step publishes already-decoded values; plan section 5's "the linker's stepped core is unchanged" names exactly this). `jing/accept-bytes!` is the ingress check, its reason keys mapped `:base64 → :absent`, hash/non-canonical `→ :address-mismatch`. Refused appends file losses under `[:content :lost]`, routed next step (old rpc timing); gap loses claims without ending the binding; a ended/erring reader ends it.
- **driver moved to `src/clj/dao/jing/content/driver.clj`** (JVM-only namespace, plain Clojure): a cljc file with every def `#?(:cljd nil :clj …)` trips kondo's cljs pass for the ungated requires, and cljd rejects the resulting empty spliced require.
- `jing-coordinate/open!` is now **single-arity `[coordinate opts]`** (callers pass nil): a multi-arity defn + cljs `with-redefs` single-arity stub crashes on the `$$IFn$_invoke$arity$N` field (`query/open-published!` threads opts to it; query_test's four stubs take `[_ _opts]` — that arity change was forced by the seam, and `query.cljc` is the brief's named consumer).
- Consumers migrated: `yin.repl.link` (serve-step on a daemon-ticker cursor held on the linker state), `btree/storage` (docstring names `dao.jing.content.async`; the facade API is unchanged), stigmergy (one shared served pair behind **nonclosable** wrapped attach handles, so one opened index's driver-close never ends the shared medium; transport transparency now runs through the two-descriptor coordinate).
- Old module deleted: `dao/jing/remote.cljc`, `remote/step.cljc`, `remote/async.cljc`, `remote_test.cljc`, `remote/step_test.cljc` (+ `rm -rf test/cljd-out` before the Dart lane, per build doc).

## Tests

Ported to `test/dao/jing/content_test.cljc` (13) and `test/dao/jing/content/step_test.cljc` (17): round trips incl. nil/opaque/absent, duplicate `:present`, the ingress matrix (non-keyword, hash mismatch, non-strict Base64, hash-valid non-canonical), malformed-answer decode totality, the verify hop (three-step order, mismatch, present-but-absent), busy/full-writer retry, unsent-at-detach, non-terminal gap, abandon, diagnostics-taken-once, payload discipline, driver timeout/interrupt/timing-gate/close. Assertions kept in outcome; ids are now minted UUIDs, so id-equality assertions compare against the minted `:id` and the traffic-parity test strips `:jing/request`. btree_async, index, query, stigmergy, linker_test/step/manifest, require_test ported at their fixtures (`local-runtime` over a store + `serve-all`; scripted raw-answer servers where a hostile reply is the subject — the vocabulary has **no error answer**, so a hostile `{:jing/result :refused}`/malformed found shape is the failure signal).

Deleted with the module (no successor behavior exists until slice 5): the `network-*` connect/serve machinery tests, the linker's `images-transfer-over-the-websocket-transport` + `ws-runtime`, the rpc-layer unit tests (`call-step`, `retire-call`, `await-established-step`, `content-descriptor`, `drain-outboxes` — they tested the deleted rpc wrapper, whose equivalents live where the behavior now lives), the allocator-exhaustion test (no integer allocator under UUID ids), and the two ws wire tests.

## Proofs

- **grep**: `grep -rn "\[dao.jing.remote" src test` → empty (remaining prose mentions: `boring.cljc:23` cites the deleted file as the gating-pattern example and `lease_composition_test.cljc:556` cites its old line numbers — both files outside slice 4's ownership, comments only).
- **JVM**: 2,254 tests / 183,116 assertions / 0 failures. **Node**: 2,163 / 49,781 / 0. **Dart**: `+2123: All tests passed!` (after `rm -rf test/cljd-out`; peers built first, exactly `bb test:cljd`'s order). Sequential, solo.
- **Baseline** measured at HEAD `83cc8bcd` (git-archive under `target/`): 2,272 / 183,316 / 0 — the brief's 2,272 matches; its 183,324 does not reproduce at this HEAD. Exact ledger: −35 (remote_test) −18 (remote/step_test) −1 (linker ws) +13 +17 = **slice 4 net −24 tests**; the tree also carries +5 ucf_test deftests and +1 untracked ws-project cross-JVM deftest from parallel tasks (not mine), closing 2,272 → 2,254 exactly.
- **kondo**: clean on every touched file (remaining repo findings are pre-existing in files I never touched: `btree_transients_test`, `dht_test`, `transact_test`). **cljstyle**: `check` exit 0 on all 20 touched files (fix applied first). ASCII and ≤80 cols on every line I added or edited. No commit/stage/checkout/reset/stash performed; `git status` shows only this slice's edits/deletions plus the parallel tasks' files, untouched.

## Note for slice 5

Deleting `dao.jing.remote` did **not** newly unblock any slice-3 ws retirement: `dao.stream.serving` and `ws/accept-connection!` still have `yin.repl.serve` as a live consumer, so the `:ws/accept`/disclaim/served-path retirements stay gated on slice 5's `yin.repl.serve` deletion exactly as planned. Slice 5 also inherits: the REPL-side content serving composition over a real WebSocket (the linker's ws-transport test and the driver's network tests are owed their successors there), and `yin.repl.cljc`'s stale `:content-client` docstring (rpc wording; the file was out of this slice's scope).

Status: COMPLETE
