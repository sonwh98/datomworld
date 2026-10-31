Completed-GMT: 2026-09-08 12:11:33 GMT
Completed-Local: 2026-09-08 19:11:33 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 98b2c597-f191-4430-aa68-56a7536f500d

# P1 fix round: both v1 bridges removed from dao.space.query

Branch `dao.stream-redesign-v2`, on top of the uncommitted P1 tree. Nothing
staged, nothing committed. The owner's ruling — "there should be no
legacy" — applied to both of `gpt-5.6-sol`'s P1 findings; the cleared
items were left alone.

## Fix 1 — the legacy v1 realization path is gone

- `legacy-v1-realization?` deleted; `current` and `history` lost their
  realization branches and route **every** source through `db-source`
  (query values and snapshots pass through; everything else — raw maps,
  raw vectors, and now *any* unrecognized host object — gets the same I1
  rejection). `index/snapshot-datoms` is no longer reachable from
  `dao.space.query`; the namespace has no v1 traversal behind any indirection.
- `test/dao/space/schema_test.cljc`:
  - `borrowed-and-descriptor-paths-agree` (was 523-531): the borrowed leg
    now passes the closed realization **directly** to `schema/current`,
    whose `interpret-view` drains it where `dao.stream` lives. I took the
    reviewer's first alternative ("pass the closed realization directly to
    schema/current") over the prompt's test-side
    `(query/relation (ds/strict-vec …))` drain because it keeps the
    borrowed *path* exercised — schema's realization branch plus
    interpret-view's drain — instead of pre-digesting the stream in the
    test; what the test pins is unchanged (borrowed interpretation ≡ value
    interpretation of the same rows).
  - `borrowed-path-does-not-close-again` (was 599-610): same shape —
    `(schema/current stream)` directly. **Not vacuous**: the operation
    under test is exactly the one that could close (schema/current's
    defopen route historically closed inner streams), and the count
    staying at 1 proves neither interpret-view's `ds/strict-vec` drain nor
    the interpretation closed the borrowed source.

## Fix 2 — no v1 transport typing on query values

- `relation` returns exactly `{:dao.space.query/relation tuples}`;
  `view-value` returns `{:dao.space.query/view kind :source src :as-of?}`.
  No `:dao.stream/type` anywhere on query values, so v1 descriptor helpers
  (`ds/descriptor?` etc.) no longer classify them as transports. The
  published **coordinate** keeps its own `:dao.stream/type` — that is
  `index/published-index`'s shape, not a query value's, and is untouched.
- `schema/current`'s map branch accepts `(query/value? d)` **or** a legacy
  descriptor carrying a keyword `:dao.stream/type`; query values interpret
  eagerly, legacy descriptors keep the defopen route. The rejection
  message for everything else is unchanged.
- `validate-not-nested-view!` now inspects `:dao.space.query/view` ∈
  `#{:current :history}` for query view values and keeps the
  `:dao.stream.schema/current`-descriptor check; W12's three rejections
  still throw the same "not a nested view" message.
- `bound-inherited-from-source` (was 538-541) — the reviewer was right
  that it compared two nils. Deleted the v1 property and replaced the site
  with `schema-current-returns-a-fact-relation-value`, asserting the new
  contract at the same place: `(query/value? v)` and `(true? (:fact? v))`
  for `v = (schema/current rel)`. (Revised rather than deleted because the
  result's fact-relation shape was previously pinned nowhere directly.)
- `query_test.cljc`: the two view-value tests and the relation test now
  assert `(nil? (:dao.stream/type v))`; added
  `unrecognized-host-objects-are-rejected` pinning Fix 1's blast-radius
  removal — an atom, a delay, and (on clj) a `java.util.Date` all receive
  the I1 rejection instead of being drained as ersatz v1 readers.

## Cleared items — untouched

The interpret-view drain hoist, the eager/defopen dispatch in
`schema/current` and its `:as-of`/`:schema-as-of` behavior, `snapshot`
against `observe/step`, `open-published!`/`close-published!` ownership,
every dropped `[T✗]` invariant, the `dao.stream.relation` deletion, the
evaluator, and the reader conditionals are exactly as P1 left them.

## Verification (full, unfiltered)

| command | result |
| --- | --- |
| `bb test:clj` (`clojure -M:test`) | **1424 tests, 165259 assertions, 0 failures, 0 errors** (P1: 1423/165253; +1 deftest from the new rejection test, +assertions from the revised W11) |
| `bb test:cljs` | **1335 tests, 34844 assertions, 0 failures, 1 error** — the same pre-existing `wasm-eval-emits-telemetry-test` ReferenceError. `Testing dao.space.query-test` confirmed in the Node output. |
| `bb test:cljd` | **All tests passed — 1288 tests** (P1: 1287; `dao.space.query-test` and `dao.space.schema-test` ran and passed on Dart) |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **Build completed. (212 files, 0 warnings, 3.09s)** |
| `clj -M:kondo --lint` on query.cljc, schema.cljc, query_test.cljc, schema_test.cljc | **errors: 0**. query.cljc and query_test.cljc: 0 warnings. schema.cljc carries one pre-existing warning ("Unresolved protocol method: close!") and three "Redundant ignore" infos, byte-identical at `78b5262` (verified by linting the HEAD extract); schema_test.cljc's one "Redundant ignore" info is likewise pre-existing. |

## Notes

- No new deviations. Both fixes stay inside the prescribed file set plus
  `schema_test.cljc` as granted; the only judgment call is the direct
  realization pass in the two borrowed tests, argued above.
- `dao.space.query` now reaches v1 nowhere: its requires are `dao.datom`,
  `dao.jing`, `dao.jing.coordinate`, `dao.space.index`,
  `dao.stream`, `dao.stream.observe`; `snapshot` remains the only
  v2 interpreter, per Decision 3.
