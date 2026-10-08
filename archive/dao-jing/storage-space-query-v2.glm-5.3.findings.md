Completed-GMT: 2026-09-08 11:43:33 GMT
Completed-Local: 2026-09-08 18:43:33 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 98b2c597-f191-4430-aa68-56a7536f500d

# dao.space.query on DaoStream v2 — P1 implementation report

Branch `dao.stream-redesign-v2`. Everything below is in the working tree,
nothing staged, nothing committed.

## What was built

`src/cljc/dao/space/query.cljc` rewritten on values per Decisions 1-4. The
namespace now requires only `dao.datom`, `dao.jing`, `dao.jing.coordinate`,
`dao.space.index`, `dao.stream`, `dao.stream.observe` — **no
`dao.stream`, no `dao.stream.relation`**, on every host. Form-level
comparison against `78b5262` (top-level forms, byte-for-byte): **80 forms
identical** (the whole evaluator — planner, rules, aggregates, `pull`,
`:in` expansion, find specs), 11 deleted, 11 added, 10 changed.

- Relation value `{:dao.stream/type :dao.space.query/relation
  :dao.space.query/relation tuples}` from `relation`/`entity-map-relation`;
  `fact-relation` adds `:fact? true` so the fact index is built without a
  d5 pass (schema builds these directly).
- View value `{:dao.stream/type :dao.space/current|:history
  :dao.space.query/view :current|:history :source src :as-of?}` from
  `current`/`history` — "the shape the descriptor path already returned".
- `rows`: view or relation value → resolved row vector.
- `open-published!` / `close-published!` over `jing-coordinate/open!`,
  `index/read-manifest`, `index/restored-indexes`; the opened value is
  `{:dao.space.query/published coord :indexes … :rows (delay
  index/read-datoms …) :store h :close-guard atom}`. `close-published!` is
  itself a no-op the second time (compare-and-set on the guard), before
  the backend is even asked.
- `realize-db-value!` is a cond over the value tags returning
  `{::relation ::fact-index ::indexes}` with no `::owned`; a current view
  with no as-of over an opened published index routes through the restored
  covered sets (L1), everything else resolves rows (L2). Raw vectors, raw
  maps, and loose `:dao.stream/type` maps throw the I1 message.
- `snapshot` (Decision 3, the namespace's only v2 use): mints
  `:dao.stream/oldest`, loops `observe/step` with the total conj-effect
  until it stops, returns `{:relation … :status :ended|:blocked|:gap|:defect
  :cursor … :recovery … :read …}`. Never closes the handle; cursor advances
  only on retained values (the step's effect-before-commit).
- `q` returns `{:dao.space.query/result rows :spec … :return-map-key …
  :return-map-keys …}`; `collect` materializes; `match`, `pull`,
  `pull-many`, `entity-attrs` keep their bodies, minus the owned/finally
  plumbing. `datoms` and `current-state-seq` untouched.

Deleted from `query.cljc`: `ViewStream`, `QueryResultStream`,
`make-query-result-stream`, `bounded-next`, `quiet-close!`, `close-owned!`,
`validate-borrowed!`, `validate-descriptor!`, `open-db-inputs!`,
`realization?`, `fact-view-realization?`, the `dao.stream` /
`dao.stream.relation` requires and the `:require-macros` entry — exactly
the plan's delete list.

`test/dao/space/query_test.cljc` rewritten: 40 deftests / 116 assertions
(was 41/112) organized as I (inputs), V (views), R (results), O
(ownership), S (snapshot), E (evaluator), L (lazy published). R5 pins S1-S3
over the v2 ringbuffer (blocked/ended/defect/never-closes/relation-feeds-q)
and a scripted reader for gap-with-values-and-recovery; the counting
harness redefines `jing-coordinate/open!` exactly as before and opens the
published value inside the `with-redefs` so the counted store is the one
the restored trees read; `lazy-published-current-closes-owned-store-once`
is deleted (O2 moved to the caller, covered by the two O tests).

## What was deleted elsewhere

- `src/cljc/dao/stream/relation.cljc` and its `defopen`, plus the stale
  generated `lib/cljd-out/dao/stream/relation.dart`.
- `test/dao/stream_test.cljc`: the whole `relation-descriptor-contract-test`
  deftest and the `[dao.stream.relation :as relation]` require at line 5.
  Nothing else in that file moved.

## Consumer call sites

- `schema.cljc:255-256` → `query/rows`; `294` → `query/fact-relation`; see
  the deviation below for the rest of that file's edit.
- `stigmergy_test.clj`: the six `(query/current (index/published-index …))`
  sites (three in `publication-republication`, three in
  `transport-transparency`) became `(query/current (query/open-published!
  (index/published-index …)))` inside try/finally closing through
  `close-published!`. Line 177's `(query/current-state-seq (ds/strict-vec
  stream))` over the v1 transactor is untouched, as are the `sources`
  helper's own v1 `ds/open!`/`ds/strict-vec`.
- `transact.cljc`, `semantic.cljc`, `positional_query_test.cljc`,
  `compilation_pipeline.cljs`: no edit; confirmed by the green suites and
  the `:demo` compile, not by inspection.

## The one deviation: schema.cljc needed three sites, not two

The plan's C2 ("two sites only") is not satisfiable as written, and the
conflict is mechanical, not stylistic:

1. `schema/current`'s map-input branch **validates** its source with
   `(keyword? (:dao.stream/type d))` and rejects nested views by the same
   key. A relation value that carries no `:dao.stream/type` is therefore
   rejected before `q` is ever involved, so T15 and every
   `(qq … (schema/current rel))` test fails. Conversely W12 requires
   `query/current`/`history` *outputs* to carry `:dao.space/current|:history`
   under `:dao.stream/type` so the nested-view check fires. I therefore
   kept a vestigial `:dao.stream/type` key on relation and view values
   (documented in `relation`'s docstring).
2. Even with that, the old descriptor branch *returns an unresolved
   `:dao.space.schema/current` view map*, and `q` — which now opens
   nothing — must refuse it (R1: a loose map with `:dao.stream/type`
   throws). The schema interpretation (extract-schema + card-one collapse)
   lives in schema.cljc and is unreachable from query without either a
   `dao.stream` require (forbidden by the end condition) or a circular
   query↔schema require (this repo has no require cycle among its 160 src
   namespaces; cljs rejects them). W13 additionally pins the defopen route
   (recording stream closed exactly once), W10/W14/as-of pin the collapse.

   So `schema/current`'s map branch now interprets **eagerly**: a query
   value source goes straight to `interpret-view`; a v1
   open!-dispatchable descriptor keeps the existing defopen route
   (`ds/open!` of the built view — W13's open/close/interpret chain is
   unchanged). The defopen itself, the realization branch, and every other
   v1 surface in schema.cljc are untouched.
3. `interpret-view` receives v1 realizations from both of its callers, and
   the plan's `query/rows (query/history source …)` edit alone cannot drain
   one without a `dao.stream` require in query. The site edit therefore
   hoists the drain schema-side: `(if (ds/realization? source) (query/relation
   (ds/strict-vec source)) source)` — `dao.stream` already lives in
   schema.cljc, and the published defopen pre-forces its rows so the drain
   never touches the closed store (W39).

Total schema.cljc delta: 45 lines across `interpret-view` (docstring,
drain hoist, `query/rows`), `current` (docstring + the eager dispatch),
and the `query/fact-relation` site. Both named sites are there; the third
region is the price of keeping `dao.space.query` v1-free, which the end
condition names first. Everything else in the plan's schema expectation
holds: schema's own v1 require, transactor, and remaining `strict-vec`
sites (including `:dao.space.schema/published`'s) are as they were.

Related consequence, confined to the test surface: `query/current` /
`query/history` accept a *legacy v1 realization* (an opaque host object —
only schema_test's W10/`borrowed-path-does-not-close-again` still hand
these over) by draining it eagerly through `index/snapshot-datoms` —
index already requires v1; query still does not — returning a resolved
fact/d5 relation value. This is behavior-loosening in one respect the old
`validate-borrowed!` covered: an *open* v1 stream now drains to its
current tail instead of throwing "must be closed", and rows that are not
local datoms throw `snapshot-datoms`'s "malformed local datom" instead of
being accepted as opaque 5-vectors. No test pins either old behavior on
this path (the old R5 throw tests are replaced by S1-S3 by the plan
itself), but the loosening is real and recorded here.

## Verification (all commands run in full, unfiltered)

| command | result |
| --- | --- |
| `bb test:clj` (`clojure -M:test`) | **1423 tests, 165253 assertions, 0 failures, 0 errors** (baseline 1425/165264: −1 deftest deleted from `stream_test`, −1 net deftest in the rewritten `query_test`) |
| `bb test:cljs` (`clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test`) | **1334 tests, 34839 assertions, 0 failures, 1 error** — the same pre-existing `wasm-eval-emits-telemetry-test` ReferenceError (`wasm is not defined`) present in the baseline before any edit. `Testing dao.space.query-test` appears in the Node output. |
| `bb test:cljd` (incl. `build:slice-peer`, `build:yin-repl-peer`, regenerating `test/cljd-out/`) | **All tests passed — 1287 tests** (baseline 1289: −1 deftest deleted from `stream_test`, −1 net deftest in the rewritten `query_test`; `dao.space.query-test` ran and passed on Dart) |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **Build completed. (212 files, 0 warnings)** (final recompile of the finished tree: 11 files compiled, 3.02s); `public/js/main.js` rebuilt (the page loads `/js/main.js?v=…`) |
| `clj -M:kondo --lint src/cljc/dao/space/query.cljc` | **errors: 0, warnings: 0** |

The browser page itself was not driven (no browser harness in this
session); the demo's own namespaces (`datomworld.demo.*`, including the
artifact/compilation-pipeline tests) are green in both the clj and cljs
lanes, and the `:demo` build — the chain the prompt names as the hard
constraint — compiles clean.

## Invariants I could not honor exactly

- **C2's "two sites only"** — see the deviation above; three regions of
  schema.cljc were edited, the third forced by the no-`dao.stream` end
  condition plus W10/W12/W13/W14.
- **R5's gap scenario as worded** ("a capacity-1 buffer with two appends
  gives `:gap` with the values read before it"): `snapshot` mints at
  `:dao.stream/oldest`, and a quiescent ringbuffer's oldest cursor never
  trails its first retained position, so a fresh snapshot over any
  quiescent ringbuffer cannot observe a gap — eviction must race the read.
  The gap-with-values-and-`:recovery` shape is pinned instead with a
  scripted reader returning `ok` then `gap`; the ringbuffer covers
  `:blocked`, `:ended`, never-closes, and feeds-`q`.

## Left owing (beyond the plan's own Left-owing list)

- The `:dao.stream/type` keys riding on query values are load-bearing only
  for `schema.cljc`'s fixed validation; when schema's own v2 plan rewrites
  that validation, both the keys and `legacy-v1-realization?`/its drain in
  `current`/`history` can go.
- `snapshot` still lives in query awaiting its second consumer
  (`dao.stream.observe/drain`), per Decision 3.
- P2 (the `dao.space.query.md` rewrite) was not mine this phase.
