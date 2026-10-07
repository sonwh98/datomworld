Created-GMT: 2026-09-08 12:56:03 GMT
Created-Local: 2026-09-08 19:56:03 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828
# Task: plan the dao.space.index migration to dao.stream
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-08 19:56:03 +0700 | Status: active | Rationale: Architect primary per team.md; wrote the dao.space.query plan whose method this follows

**Planning task, no write authority beyond `collab/`.** Repository
`/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, clean at
`acb28f0`.

## Method

Follow the method you used for `dao.space.query`: an explicit invariants list
is the contract, marked `[D]` stated in the design, `[T]` pinned only by a
test, `[T→D]` test-pinned and worth promoting, `[T✗]` an implementation
accident dropped with its reason. The existing implementation and tests have
no authority beyond the invariants they pin. Old code is deleted in the phase
that replaces it. Nothing is in production; tests are re-writable where an
invariant is preserved. The plan is transient and is deleted when consumed —
which means it must not leave anything owed.

## Read first

- `docs/design/dao.space.index.md` — the design this answers to
- `docs/design/dao.stream.md` — the v2 contract; *Explicitly Absent* and
  *The v2 namespace is transient*
- `docs/design/dao.space.query.md` — the sibling that just migrated,
  especially *Snapshots* and the two new *Decisions*. Its plan is deleted
  (consumed); `git show 1e5f0ad` has it if you want the shape.
- `src/cljc/dao/stream/observe.cljc` — `step`, and the cursor law in its
  docstring
- `src/cljc/dao/space/index.cljc` (604 lines) and
  `test/dao/space/index_test.cljc` (1037 lines)

## Measured facts (mine, verified against the tree — correct me if wrong)

`index.cljc` requires **both** `[dao.stream :as ds]` (line 40) and
`[dao.stream :as stream]` (line 41). It is genuinely dual. The v1 surface
splits in two:

**Dead, as of `96ec78f`:**
- `PublishedIndexStream` (344-362), a `defrecord` implementing
  `ds/IDaoStreamReader` + `ds/IDaoStreamBound`, whose `next` reads a forced
  row vector by `:position` and whose `close!` closes the store.
- `ds/defopen :dao.space.index/published` (383-400).
- Nothing anywhere in `src` or `test` calls `ds/open!` with that type — I
  grepped every `ds/open!` call site. `dao.space.query` used to; it now has
  its own `open-published!`.

**Live and load-bearing:**
- `snapshot-datoms` (441-468) walks an agent-local stream with `ds/next` from
  `{:position 0}`, flattening `{:dao.space/transaction …}` records into
  datoms, finishing on `:blocked` or `:end`, throwing on `:daostream/gap` and
  on malformed results. Consumers: `schema.cljc:926` and `:969`,
  `transactor.cljc:205`, `index_test:188`, `schema_test:670`.

**Already v2:** `select-intake-stream!` (504) validates the intake pool as
writable v2 values; `append-ok!` (527) appends through `stream/append!` and
requires `:dao.stream/ok`.

**The coordinate:** `published-index` (330-341) mints
`{:dao.stream/type :dao.space.index/published :dao.stream/bound {…}
:dao.stream/comparator … :content-store … :manifest-address …}`.
`dao.space.query/open-published!` validates that **exact** map
(query.cljc:236-241) before opening anything. So those keys are load-bearing
as a *shape*, not as dispatch — nothing dispatches on them any more.

## The questions the plan must settle

1. **`snapshot-datoms` reads the transactor's v1 local stream.** That is the
   crux: index cannot drop `ds` while its input is a v1 reader. Either the
   transactor's local stream migrates first, or `snapshot-datoms` moves to
   whoever owns that stream, or index stays dual for one more phase. Which,
   and why — and what that implies for ordering `index` against
   `transactor` and `schema`.
2. **`snapshot-datoms` is the second consumer of the drain shape.** The query
   plan's Decision 3 recorded that when a second consumer appears, `snapshot`
   moves to `dao.stream.observe` as `drain`. It has appeared. Is
   `snapshot-datoms` `drain` plus datom/transaction flattening, and does
   `query/snapshot` become a caller of it? Note the two disagree on policy
   today: query returns `:gap` as data, `snapshot-datoms` throws.
3. **The coordinate's `:dao.stream/*` keys.** Keep them (a serializable name
   that already travels, and query validates the exact shape), or rename to
   `:dao.space.index/*` now that nothing dispatches on them? A rename is a
   breaking change to a portable coordinate and touches query.
4. **The dead reader.** Confirm `PublishedIndexStream` and the `defopen` are
   unreachable, and delete them in this plan rather than leaving them for a
   later one.
5. **Migration shape** — does index end v1-free in this plan, or dual with a
   named remainder? Say which, and what each remaining namespace owes.

## Constraints

- `public/demo.html` must keep working. Verify by compiling the `:demo`
  build, not by inspection.
- Do not plan work inside `dao.space.schema` or `dao.space.transactor` beyond
  the call-site edits index's own change forces; each has its own plan coming.
- `bb test:clj`, `bb test:cljs`, `bb test:cljd` must all be green at the end
  of every phase.

## Deliverable

Write the plan to stdout. I will promote it to
`collab/architect-space-index-v2-plan.claude-fable-5-1.findings.md` and, after
review, to `docs/design/`. Start with the Completed-GMT/Local, Coding-Agent,
Session-ID header.
