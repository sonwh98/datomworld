# Agent Tzu: ingest text → build a persisted knowledge graph

## Context

Agent Tzu's goal is to **ingest text and generate a knowledge graph using datoms**.
Today it does the extraction half well — `text->datoms` (src/cljc/agent/tzu.cljc:236)
turns prose into `[e a v]` triples with negative-int tempids, using Schema.org
properties (`:schema/...`) as attributes and reusing entity tempids across chunks
within one document. But the output is a **flat vector that is never stored**:
`datoms->tx-data` (tzu.cljc:273) has no live caller, and `-main` just `pprint`s the
triples. There is no graph, no persistence, no cross-document entity reuse.

This design adds the missing glue so Tzu transacts its triples into a `dao.db` store
and yields an actual queryable graph. All new code goes **directly into
`agent.tzu`**; scope is the full tier: foundation + cross-document dedup +
query/CLI wiring.

## Two store constraints the design must respect (verified)

1. **Edges require a ref schema.** A negative-int *value* is only resolved into an
   entity reference (tempid → permanent eid, indexed in VAET for reverse traversal)
   when its attribute is declared `:db/valueType :db.type/ref`. See `collect-tempids`
   (src/cljc/dao/db/in_memory.cljc:415 — `(and (contains? ref-attrs a) (tempid? v))`),
   `ref-attrs` derived from schema at in_memory.cljc:282, `add-datom-to-indexes`
   at in_memory.cljc:331. **Without a derived schema, `[e :schema/creator -2]` never
   becomes an edge.**
2. **`:db/unique` rejects, never upserts.** A second entity claiming an existing
   unique `[a v]` *throws* "Unique constraint violated" (in_memory.cljc:664–678).
   So cross-document dedup must be **explicit** (look up existing eid by
   `:schema/name`, rewrite the tempid before transacting), not via `:db/unique`.

## Store API to reuse (verified, file:line)

- `dao.db/transact` (src/cljc/dao/db.cljc:182) → `{:db-after :db-before :tx-data
  :tempids :db}`; accepts `[:db/add e a v]` ops; `:tempids` is `{neg-tempid → eid}`;
  permanent eids start at 1025.
- `dao.db.in-memory/empty-db` (in_memory.cljc:1198) — fresh db.
- Schema is data: `[:db/add attr-tid :db/ident :schema/creator]` +
  `[:db/add attr-tid :db/valueType :db.type/ref]` + `[:db/add attr-tid
  :db/cardinality :db.cardinality/many]`. Template: `schema->tx-data`
  (in_memory.cljc:1177); cache builder `build-schema-cache` (in_memory.cljc:265);
  default cardinality is **one** unless declared.
- Query/traversal: `dao.db/find-eids-by-av` (db.cljc:151), `datoms` (db.cljc:171,
  supports `:eavt`/`:vaet`), `pull`/`entity` (db.cljc:121–129).
- `dao.db` and `dao.db.in-memory` are plain `.cljc` with no blocking deps → DB ops
  are cross-platform; only `prompt`/`ds/take!!` is JVM-bound.

## New functions in `agent.tzu` (dependency order, proposed signatures)

1. `derive-schema [datoms] → {attr-kw {props}}` (private). Classify attributes:
   an attr is a **ref** if it *ever* has a negative-int value
   (`(and (integer? v) (neg? v))`). Emit, for each distinct attr (skip system
   `:db/*` attrs): `{a (cond-> {:db/cardinality :db.cardinality/many}
   (ref-attr? a) (assoc :db/valueType :db.type/ref))}`. All extracted attrs are
   **cardinality/many** (facts repeat: multiple authors/examples). Never assign
   `:db/unique`. `:schema/type` is string-valued → naturally classified scalar.
2. `schema-tx-data [schema] → ops` (private). Mirror in_memory.cljc:1177: per attr
   allocate a fresh tempid via a local counter, emit `[:db/add tid :db/ident a]` +
   valueType/cardinality ops. (Schema is its own prior transaction, so its tempids
   are independent of the data tempids — no collision handling needed.)
3. `resolve-dups [db datoms] → {tempid → existing-eid}` (private). Reusing the
   `entities-from` idea (tzu.cljc:201, keyed on `:schema/name`), for each
   `[tempid name]` call `(db/find-eids-by-av db :schema/name name)`; if non-empty,
   map `tempid → (apply min eids)` (deterministic). Tempids with no match are
   omitted (left for `transact` to allocate fresh).
4. `rewrite-tempids [datoms tempid→eid] → datoms` (private). Replace `e` and `v`
   with `(get m x x)`. Strings/keywords are untouched (only integer positions hit).
5. `ingest [db text] / [db text chunk-size] → {:db ... :tempids ...}` (public).
   Cross-document entry point:
   - `(text->datoms text chunk-size)` → datoms.
   - `(derive-schema datoms)`, then **filter to attrs not already in `(:schema db)`**
     (re-asserting `:db/ident` for an existing attr would create a duplicate
     attr-entity), transact `schema-tx-data` of the new attrs first.
   - `(resolve-dups db' datoms)` → rewrite map; `(rewrite-tempids datoms m)`.
   - `(db/transact db' (datoms->tx-data rewritten))` (reuse `datoms->tx-data`
     tzu.cljc:273). Return `{:db (:db-after res) :tempids (:tempids res)}`.
6. `text->graph [text] / [text chunk-size] → {:db ... :tempids ...}` (public).
   Foundation: `(ingest (in-memory/empty-db) text ...)`.
7. `entity-graph [db name] → {:entity eid :attrs {...} :out [[a eid]...] :in
   [[eid a]...]}` (public). `eid` via `find-eids-by-av :schema/name`; outgoing from
   `(db/datoms db :eavt eid)` filtered to ref attrs; incoming via the reverse index
   `(db/datoms db :vaet eid)`; attrs via `(db/pull db '[*] eid)`.
8. `graph-stats [db] → {:entities n :datoms n :edges n}` (public). `:datoms` =
   count `(db/datoms db :eavt)`; `:edges` = count `(db/datoms db :vaet)`;
   `:entities` = distinct `:e` with eid ≥ 1025.
9. `-main` (tzu.cljc:332, `:clj` only) — replace the final `pprint` of triples with
   `(text->graph input)` then `(pprint (graph-stats db))`. Stays JVM-only.

### `:require` additions (tzu.cljc:1–17)
Add `[dao.db :as db]` and `[dao.db.in-memory :as in-memory]` to the shared
`:require` (NOT under a `:clj` conditional — both are cross-platform). `-main`
remains the only `:clj`-gated form.

## TDD test plan (add to test/agent/tzu_test.cljc)

Follow the existing `with-redefs [tzu/prompt (fn [_] ...canned EDN...)]` pattern
(tzu_test.cljc:47–79). Add requires `[dao.db :as db]` and
`[dao.db.in-memory :as in-memory]`. Keep `#?(:clj ...)` gating to match existing
canned-prompt tests.

- `derive-schema-classifies-refs-test`: on `[[-1 :schema/name "Linda"]
  [-1 :schema/creator -2] [-2 :schema/name "Bob"]]` → `:schema/creator` has
  `:db/valueType :db.type/ref`; `:schema/name` does not; both cardinality/many.
- `ingest-resolves-refs-to-edges-test`: redef `prompt` to return datoms containing
  `[... :schema/creator -2]`; after `text->graph`, assert the creator datom's `:v`
  is a permanent eid (≥1025 = Bob's eid) and Bob is reachable via
  `(db/datoms db :vaet bob-eid)`. Proves the derived schema made the ref resolve.
- `ingest-dedups-by-name-test`: `ingest` a db with "Linda"; `ingest` a second text
  that re-introduces "Linda" (new tempid) plus a new fact; assert
  `(count (db/find-eids-by-av db :schema/name "Linda")) == 1` and the new fact
  attaches to the same eid (no duplicate node, no unique violation).
- `entity-graph-neighbors-test`: assert outgoing (`:schema/creator` → Bob) and
  incoming neighbors via VAET.
- `graph-stats-test`: assert counts on a small canned ingest.

## Risks / edge cases

- **Sometimes-ref/sometimes-scalar attr**: if an attr has both tempid and scalar
  values, ref classification wins; scalar datoms on that attr are stored but not
  VAET-indexed (acceptable).
- **Fallback non-`schema/` predicates** (`:influenced-by`, `:implements`): handled
  uniformly — ref only if they ever carry a tempid value.
- **Name collisions**: exact-string `:schema/name` dedup will wrongly merge two
  distinct real entities sharing a name. Known limitation; deterministic (min eid)
  so behavior is stable. Future work: disambiguate by `:schema/type` + name.
- **Re-declaring schema**: filtering to attrs absent from `(:schema db)` prevents
  duplicate `:db/ident` attr-entities on repeat ingests.

## Verification

- `clj -M:kondo --lint src/cljc/agent/tzu.cljc test/agent/tzu_test.cljc` — clean.
- `clj -M:test` — all existing + new tests pass (tests mock `prompt`, no network).
- REPL smoke test (no API key needed if `prompt` is stubbed; with a key, end-to-end):
  `(let [{:keys [db]} (tzu/text->graph "Linda was created by David Gelernter at
  Yale in 1986.")] [(tzu/graph-stats db) (tzu/entity-graph db "Linda")])` — expect
  a non-zero edge count and Linda's `:schema/creator` edge pointing at the Gelernter
  entity, with Yale reachable.
- CLJS sanity (DB ops are cross-platform): `clj -M:cljs -m shadow.cljs.devtools.cli
  compile test && node target/node-tests.js`.
