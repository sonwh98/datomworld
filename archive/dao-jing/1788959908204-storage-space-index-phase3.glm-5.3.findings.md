Completed-GMT: 2026-09-09 14:07 GMT
Completed-Local: 2026-09-09 21:07 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 09a11e16-5b39-47cd-9534-318b8b20d0bd

# Phase 3 — index closes

Implementation, bounded to §5 of the r5 plan. Nothing staged, nothing
committed. Eight files touched (5 code, 3 docs):

```
 src/cljc/dao/space/index.cljc       | 55 +-------
 src/cljc/dao/space/schema.cljc      | 41 ++++--
 test/dao/space/index_test.cljc      | 273 +++-----------------------------------
 test/dao/space/query_test.cljc      | 184 ++++++++++++++++++++++++++
 test/dao/space/stigmergy_test.clj   |  13 +-
 docs/design/dao.space.index.md      |  55 ++++----
 docs/design/dao.space.schema.md     |  10 +-
 docs/design/dao.stream.md           |   2 +-
```

## Src

**`index.cljc` is v1-free.** Deleted: `PublishedIndexStream` (the record),
`ds/defopen :dao.space.index/published`, the `[dao.stream :as ds]` and
`[dao.jing.coordinate :as jing-coordinate]` requires (jing-coordinate was
used only by the deleted defopen — confirmed by grep, not assumed). There
was no `#?(:cljs (:require-macros [dao.stream]))` in this file to remove —
confirmed absent both before and after. Final require list, exactly the
five the plan names:

```
(:require [dao.data.btree :as bt]
          [dao.data.btree.storage :as bts]
          [dao.datom :as datom]
          [dao.jing :as jing]
          [dao.stream :as stream])
```

`published-index` (the constructor) **stays**: it is the transportable read
coordinate `query/open-published!` validates through (query.cljc:241) and
`stigmergy_test`'s `published-source-pool` builds. Its docstring and the
section header were rewritten from "bounded logical d5 stream / exact stream
bound" to the read-coordinate vocabulary. `dao.stream.observe` was never
added (D5).

**`schema.cljc`: the one rewritten defopen body.** Added
`[dao.jing.coordinate :as jing-coordinate]`; added private
`PublishedSchemaRows` (`ds/IDaoStreamReader` `next` by position over the
forced vector, `ds/IDaoStreamBound` `close!` → `{:woke []}` per D10,
`closed?` → true); the `:dao.space.schema/published` defopen keeps its form
and validation verbatim (same "invalid schema/published descriptor" throw —
W40 depends on it) and its body is now `jing-coordinate/open!` →
`index/read-datoms` inside `try`/`finally (jing/close! store)` →
`(->PublishedSchemaRows rows)`. The store closes before the value is
returned, so P5's ownership rule holds structurally on this path too.
Nothing else in §5.3's do-not-touch list was touched: `:dao.space.schema/
current` and its opener, `SchemaWrapper`, `ds/strict-vec` :260,
`ds/realization?` :259/:318, `schema_test:304`'s fixture, and the
closedness model are byte-identical to HEAD.

## The eleven read paths (§5.4), and what pins each now

| # | property | now pinned by |
|---|---|---|
| 1 | coordinate rejection (P1) | **moved** → query_test `open-published-rejects-unresolvable-and-malformed-coordinates` (message modernized to query's own `"invalid published-index coordinate"`; the unsupported-type case still greps `#"unsupported DaoJing content-store coordinate"` from `jing-coordinate/open!`) |
| 2 | missing/invalid manifest at open | deleted; covered by index_test `read-manifest-guards-missing-and-invalid` on the shared `read-manifest`/`read-datoms` |
| 3 | P6 protocol vocab, P7 two cursors, idempotent close, EAVT order | deleted per plan; EAVT order → row #7's move; idempotent close → query_test `close-published-closes-once-and-is-idempotent`; P6/P7 die with the adapter |
| 4 | empty index reads as no datoms | deleted + **added** query_test `open-published-of-an-empty-manifest-yields-an-empty-relation` (`rows` = `[]`, q over current = `#{}`); `publish-index-of-empty-input-is-readable` keeps the write side |
| 5 | open-time fetch count (P3) | **moved** → query_test `open-published-fetches-only-the-manifest`: exactly 1 get after open and before any read, zero nodes faulted (cljd branch skips — with-redefs) |
| 6 | `covered-indexes` structural (P8) | **split**: the structural cases stay verbatim in index_test `covered-indexes-returns-the-four-covered-sets`, now fixture-free; the opened-value assertion moved onto `query/open-published!`'s value as query_test `open-published-carries-the-four-covered-sets` (that the structural check accepts the query value *is* P8's proof) |
| 7 | rows = eager EAVT walk (P4) | **moved** → query_test `open-published-rows-match-the-eager-walk` (non-EAVT insertion order; `query/rows` vs `index/read-datoms`) |
| 8 | plain EDN + stream round-trip (P2) | **moved and modernized** → query_test `published-index-is-transportable-plain-data`: `pr-str`/`edn/read-string` round-trip, carrier now a **v2 ringbuffer** (`ring-handle`, append + oldest-cursor `next`), the transported coordinate opened with `query/open-published!`, and the **complete exact coordinate map** asserted in full (replacing v1's `ds/exact-bound?`) |
| 9 | `stigmergy_test` `sources` (the production use) | **migrated**: `query/open-published!` → `query/rows` → `query/close-published!`, `current-state-seq` kept over the rows, `try`/`finally` kept; all five scenarios re-ran green |
| P5 | failed open closes its store | **new** query_test `failed-open-closes-the-store-it-opened`: a close-counting store whose `:get-content-fn` throws after `jing-coordinate/open!` succeeds; asserts the *identical* error object propagates and the store closed **exactly once** (cljd branch skips — with-redefs) |

With the seven deftests gone and #6 stripped, `publish-into-file`,
`stream-values`, `temp-content-path`, `cleanup-file` and the
`clojure.edn`/`dao.jing.file`/cljd-`dart:io` requires lost their last
callers in index_test and were deleted. `counting-content-store` stays
(its remaining users are the restored-indexes laziness tests) with its
docstring reworded off the deleted open path. `jing-coordinate` moved into
the existing `#?@(:clj ...)` require gate beside `jing-remote` — its only
remaining use is the `:clj`-only `remote-coordinate…` deftest, which
kondo then correctly stops flagging.

## Closure criteria (greps, run against the final tree)

```
$ grep -c "ds/" test/dao/space/index_test.cljc      # 35 → 0
0
$ grep -c "ds/" test/dao/space/stigmergy_test.clj   # 3 → 0
0
$ grep -n "jing-coordinate/\|ds/\|observe/" src/cljc/dao/space/index.cljc
(no matches)
```

`dao.space.schema` is the only `dao.space` namespace still requiring
`[dao.stream :as ds]` (grep-verified). Its v1 surface is the §6 table's
15 prior sites plus the record's two protocol impls — the relocation the
plan names as intended.

## Verification — commands and counts

- `.clj-kondo/.cache` deleted before linting.
- `mise exec -- cljstyle fix` on the five code files: after one
  paren-count fix in `sources` (cljstyle caught my short `finally` line —
  the same closing-paren trap as prior rounds), `cljstyle check` returns
  clean.
- `clj -M:kondo --lint` on all five: **0 errors, 0 warnings**, four
  info-level "Redundant ignore" notes in schema.cljc — all four sit on
  `#_{:clj-kondo/ignore …}` forms that are unchanged from HEAD (three in
  regions I never touched; the fourth still precedes the same
  `(ds/defopen :dao.space.schema/published …)` form); they surfaced with
  the cleared cache, not from this diff. Not acted on: removing them risks
  breaking the cljd/cljs profiles the ignores were written for.
- `timeout 900 bb test:clj` → **exit 0**. 1434 tests, 165341 assertions,
  0 failures, 0 errors. All seven `dao.space.*` namespaces present.
  Focused re-runs confirmed each moved/new deftest actually executes:
  the seven new query_test deftests (7 tests / 21 assertions), and
  W38–W41 + the split covered-indexes + constructor-validation +
  remote-coordinate + all five stigmergy scenarios (12 tests /
  40 assertions), all 0 failures.
- `bb test:cljs` → exit 0. 1344 tests, 34908 assertions, 0 failures,
  **1 error: `yin.vm.telemetry-test/wasm-eval-emits-telemetry-test`
  (`ReferenceError: wasm is not defined`)** — pre-existing, documented in
  Phase 1's findings as baked into base history `78b5262`, in a file this
  phase does not touch. Node output contains `Testing dao.space.index-test`
  and `Testing dao.space.schema-test` as required.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` → exit 0,
  "Build completed. (212 files, 12 compiled, 0 warnings)".
- `bb test:cljd` → **exit 0**, "All tests passed!" — 1297 Dart tests
  (peer builds + full tree). `dao.space.index-test`, `…query-test`,
  `…schema-test`, `…transactor-test`, `…transact-test` and
  `…positional-query-test` all ran; `stigmergy-test` is JVM-only by
  design.

## Docs (§5.7)

- `dao.space.index.md` — the laziness bullet rewritten on
  `query/open-published!` + `query/rows`; the adapter removed from *What
  the library owns* (the read-coordinate bullet now names query's opener)
  and from *Why a separate library*; the public-surface `published-index`
  line kept (still public) with its comment re-anchored on
  `open-published!`; the dependency picture and *Platform status* no
  longer name a DaoStream adapter; the K-way-merge open item reworded.
- `dao.space.schema.md` — the ":dao.space.schema/published delegates to
  the published-index realization / open! dispatches on :dao.stream/type"
  paragraph rewritten on `index/read-datoms` + `PublishedSchemaRows`.
- `dao.stream.md` — the remaining-consumer list now reads
  "`dao.space` (`schema` only — `query`, `index` and `transactor`
  migrated …)".
- Beyond the named lines, `stigmergy_test`'s own ns docstring said readers
  reach the space "through explicit bounded DaoStream descriptors" — the
  path this phase migrated — so two prose lines there now say coordinates
  opened with `query/open-published!`.

## Not honoured / deviations

- None in scope. Two judgment calls, both named above: deftest #1 kept its
  v1 name's shape but says "coordinates" (query's own vocabulary) instead
  of "descriptors"; and the moved #6 opened-value assertion lives in a
  query_test deftest of its own (`open-published-carries-the-four-covered-sets`)
  rather than being folded into another test, so the move is greppable.
- cljd skips the with-redefs harnesses (#5-moved, P5) with an `is true`
  note — the file's established convention for every counting/redef test;
  both properties stay pinned on JVM and Node.
