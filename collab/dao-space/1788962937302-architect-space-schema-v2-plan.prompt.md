Created-GMT: 2026-09-09 14:08:57 GMT
Created-Local: 2026-09-09 21:08:57 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 7762fd3e-1c6b-4e95-9e2d-2032a2209d5f
# Task: plan the migration of dao.space.schema to dao.stream
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-09 21:08:57 +0700 | Status: active | Rationale: Architect primary per team.md; authored the dao.space.query and dao.space.transactor/index plans this one continues

**Planning task, no write authority except the one output file named below.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`,
clean at `4b9f0e7`.

## The goal this serves

Every consumer moves off v1 `dao.stream` onto `dao.stream`, after which v2
is renamed to `dao.stream` (`docs/design/dao.stream.md`, *The v2 namespace is
transient*). `dao.space.query`, `dao.space.transactor` and `dao.space.index`
are done. **`dao.space.schema` is the last `dao.space.*` namespace on v1.**

## Read first

- `docs/design/dao.space.schema.md` — the design this plans against, in
  particular §3.1's ownership paragraph and the **D10 rule**.
- `docs/design/dao.stream.md` — the v2 contract; *Explicitly Absent* decides
  several of the questions below outright.
- `docs/design/dao.space.transactor.md` — the inner value schema wraps, already
  on v2. Its **T20** hands the D10 collapse to this plan by name.
- `git show 96ec78f` and `docs/design/dao.space.query.md` — how the adjacent
  read side settled the same questions. Its Decisions 1-3 (inputs are values;
  bounded realizations stop pretending to be streams; one `snapshot` over
  `observe/step`) are precedent you should either follow or explicitly depart
  from with a reason.
- `src/cljc/dao/space/schema.cljc` (1215 lines),
  `test/dao/space/schema_test.cljc` (1967 lines),
  `test/dao/space/schema_fixtures.cljc` (66 lines).

## Method

Same as the plans you wrote for query and the transactor: an explicit
**invariants list** is the contract, grouped by the test namespace that
exercises each, every invariant marked **[D]** (stated in the design), **[T]**
(pinned only by a test), **[T→D]** (test-pinned, promote to the design), or
**[T✗]** (an accident of the v1 stream dressing, dropped with its reason).
The existing implementation and tests have no authority beyond the invariants
they pin. Nothing is in production; tests are re-writable where an invariant
is preserved. The plan is transient and is deleted when nothing in it is owed.

## Verified measurements (mine, on `4b9f0e7` — correct me if wrong, in a
## "corrections to the brief" section, as you did for query)

`schema.cljc` touches **8 v1 APIs across 16 lines**. Four of the eight are
listed *Explicitly Absent* in v2:

| site | API | note |
| --- | --- | --- |
| 260, 319 | `ds/realization?` | the borrowed-realization branch |
| 261 | `ds/strict-vec` | drains a v1 realization inside `interpret-view` |
| 320, 365 | `satisfies? ds/IDaoStreamBound` / `IDaoStreamReader` | borrowed-input validation |
| 324, 1079 | `ds/closed?` | **Explicitly Absent** |
| 352, 364 | `ds/open!` | **Explicitly Absent** (registry) |
| 358, 1205 | `ds/defopen` | **Explicitly Absent** (registry) |
| 368, 374 | `ds/close!` | on the opened inner realization |
| 946, 1184, 1194 | `ds/IDaoStreamBound` / `IDaoStreamReader` | protocol implementations, below |

Two structural v1 citizens:

- **`SchemaWrapper`** (line ~943) — a `deftype` whose *only* protocol is
  `ds/IDaoStreamBound`, implementing `close!` (returns `{:woke []}`, closes
  the inner v2 transactor via `tx/close!`) and `closed?` (reads its own state
  atom). It already owns its closedness flag precisely because the inner v2
  transactor has no `closed?` to delegate to.
- **`PublishedSchemaRows`** (line ~1181) — a `defrecord` implementing
  `IDaoStreamReader` + `IDaoStreamBound` over a fully-forced row vector,
  reached only through `ds/defopen :dao.space.schema/published`. Added by the
  index Phase 3 work. This is the exact shape query's Decision 2 retired.

Tests: `schema_test.cljc` already requires `dao.stream`,
`dao.stream.memory-log` and `dao.stream.ringbuffer` alongside v1.
Its v1 use is **63 `ds/close!`** (overwhelmingly on the wrapper), 2
`ds/open!`, 1 each of `ds/append!`, `ds/strict-vec`, `ds/closed?`.
`schema_fixtures.cljc` exists **only** to hold a `ds/defopen` and its
`RecordingStream` deftype; its docstring says why (a ClojureDart constraint:
a deftype emitted by a test namespace cannot be referenced from another,
because the `dao.stream/open!` contribution table points at the canonical
`lib/cljd-out` path).

Consumers of `dao.space.schema` in the tree: only its own tests and fixtures.
`query.cljc` mentions schema in prose only. This namespace has no in-tree
caller to keep compiling — a freedom the query migration did not have.

## What the plan must settle

1. **The D10 collapse.** `dao.space.schema.md` §3.1 and
   `dao.space.transactor.md` T20 both defer to "schema's own plan": schema
   re-wraps three results into v1 shapes — `transact!` to
   `{:result :ok :t t :datoms datoms}`, `SchemaWrapper.close!` to
   `{:woke []}`, `publish!` unchanged — while a conforming non-ok inner
   outcome passes through and is told apart by `:dao.stream/outcome` versus
   `:result`. Does this plan collapse all of it to the v2 receipt? What
   becomes of the two-shaped outcome space, and what do the tests that pin
   `{:result :ok …}` become?
2. **What `SchemaWrapper` is.** It implements a v1 protocol to expose two
   operations, one of which (`closed?`) v2 declares Explicitly Absent. Does it
   stop being a stream type and become a value with named operations, the way
   `dao.space.transactor` did? If so, the 63 `ds/close!` sites become
   `schema/close!` — say so, and say whether `closed?` survives at all given
   that the wrapper's flag is real per-wrapper state rather than a stale
   predicate over a remote handle.
3. **The `:dao.space.schema/current` descriptor route.** `current` is dual
   today: a query value interprets directly, a v1 `open!`-dispatchable
   descriptor goes through `ds/defopen :dao.space.schema/current`, which opens
   the inner source, closes it, and interprets. The registry is retired. Does
   the descriptor route die entirely — callers pass values, as query's
   Decision 1 settled — and is anything actually lost when it does?
4. **`interpret-view`'s v1 drain** (260-261). It converts a v1 realization to
   a relation value because query takes values only. With the descriptor route
   gone, does it vanish, or does it become `query/snapshot` for a genuine v2
   handle? Is a live stream a legitimate schema source at all?
5. **`PublishedSchemaRows` and `:dao.space.schema/published`.** Query's
   Decision 2 and its `open-published!` / `close-published!` are the
   precedent. What replaces the `defopen`, and does the opener belong in
   schema, beside query's, or in `index`?
6. **The borrowed-realization contract** (319-324: must satisfy
   `IDaoStreamBound`, must answer `closed?` true). Query dropped its
   equivalent (I6) as an accident of the stream dressing. Same ruling here, or
   is schema's different?
7. **`schema_fixtures`.** The namespace exists for a `defopen`. Does it
   survive at all, and does its ClojureDart deftype constraint still bind when
   nothing registers into an ambient table?
8. **The uncovered `finally`.** The transactor Phase 3 record notes that
   schema's published opener has its own `finally` and that no test covers
   removing it — it was correctly out of that phase's scope and is owed here.
9. **Migration shape.** One piece, as query was, or dual, as index and the
   transactor were? Schema has no in-tree consumer to keep compiling; weigh
   that.
10. **Whether anything is left owing afterward.** Name it by namespace. After
    schema lands, `dao.space.*` should be entirely v2; say explicitly whether
    that is true and what the rest of the repo still holds (the `yin.vm.*`
    family and the v1 transports) before `dao.stream` can take the name.

## Constraints

- Do not plan work for `yin.vm.*`, the v1 transports, or `dao.jing`.
- `public/demo.html` must keep working; schema is not on its path today
  (`compilation_pipeline.cljs` reaches `query` through `semantic/find-by-type`),
  but confirm that rather than assume it.
- Phases must each leave the suite green on clj, cljs (Node) and cljd, and
  delete what they replace in the phase that replaces it.
- `#?(:clj ...)` alone does **not** exclude code from the cljd build; use
  `#?(:cljd nil :clj ...)` with `:cljd` first.

## Output

Write the plan to `collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md`,
starting with Completed-GMT, Completed-Local, Coding-Agent: claude,
Session-ID: 7762fd3e-1c6b-4e95-9e2d-2032a2209d5f. Structure it as your query and transactor plans were: any
corrections to this brief first, then what schema is in one paragraph, the
invariants list, the decisions with their reasons, the phases with explicit
build/delete/prove lists, a host matrix, the boundary (built here vs left
owing by namespace), and an end condition. Change no other file.
