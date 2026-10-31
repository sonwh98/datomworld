Created-GMT: 2026-09-08 13:35:17 GMT
Created-Local: 2026-09-08 20:35:17 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: review the transactor+index v2 migration plan before any code is written
Role: Architect review (Architect primary is the plan's author, so this goes
to the Architect fallback; you also reviewed the query migration this follows)

**Read-only workspace. Write nothing; print your review to stdout.**

Repository `/Users/sto/workspace/datomworld`, branch
`dao.stream-redesign-v2`, clean at `80181d5`. **No code has been written.**
This reviews the plan itself, before an implementer is briefed — a defect
found here is far cheaper than one found after five phases land.

## What to read

- `collab/1788874487255-architect-space-transactor-v2-plan-r2.claude-fable-5-1.findings.md`
  — the plan under review (652 lines, five phases)
- `collab/architect-space-index-v2-plan.claude-fable-5-1.findings.md` — the
  earlier index plan it absorbs; its P-table is the reference for Phase 5's
  test accounting
- `docs/design/dao.stream.md` — the v2 contract (*Explicitly Absent*, and the
  new paragraph recording where taking went)
- `docs/design/dao.space.query.md` — the sibling that already migrated; you
  reviewed it. Its *Snapshots* section carries the naming rule the plan follows.
- `docs/design/dao.space.index.md`, and the three source files:
  `transactor.cljc` (246), `index.cljc` (604), `schema.cljc` (1168)

## Context you should have

The owner ordered: **transactor first, then index closes in the same sweep.**
That is why the plan absorbs the index plan and relaxes the schema constraint
exactly far enough to move `:dao.space.schema/published` onto
`index/read-datoms`. `dao.space.schema`'s own migration stays a separate plan.

## What I have already verified myself, so spend your budget elsewhere

- `index.cljc` has five `ds/` occurrences; `:444` is prose; the two real
  sites are `snapshot-datoms` and the published adapter. Confirmed.
- `jing-coordinate/open!` appears once in index (`:390`), inside the adapter
  Phase 5 deletes — so the `[dao.jing.coordinate]` require does leave with it.
- `schema.cljc` `ds/close!` is 3 code sites (`:367 :373 :949`); `:1164` is a
  comment.
- The capacity asymmetry: v1 `ringbuffer.cljc:82` never evicts without a
  capacity; v2 `v2/ringbuffer.cljc:15-20` requires a positive integer and
  evicts. 28 `ds/open!` unbounded sites, all in `dao.space` tests.
- `SchemaWrapper` implements only `ds/IDaoStreamBound` (`schema.cljc:945`).

## What I want judged

1. **Is the plan executable as written?** Every phase must leave three lanes
   green. Phase 3 is "the swap" and cannot be partial — check that its edit
   set is actually closed under compilation, especially the ~30
   `ds/open! {:dao.stream/type :transactor …}` sites in `transactor_test` and
   schema's six forced edits.
2. **Phase 5's test accounting.** It claims three of the ten index_test sites
   are *moves* because deleting them would drop coverage (coordinate
   rejection, open-time fetch count, and two rewrites). Verify each of the ten
   against its named survivor. A property with no covering test after the
   phase is a defect the plan must fix before it runs.
3. **D1's central claim — a transactor is not a stream in v2.** It rests on
   `next` having only test callers. Verify that independently; if any
   non-test consumer reads through the wrapper, D1 collapses.
4. **The closedness replacement.** v2 has no `closed?`. Schema calls it at
   four sites and `publish!` guards on it. Check that the plan's replacement
   is sound under the contract's "operation results are authoritative", and
   that it does not reintroduce a stale predicate under a new name.
5. **The capacity hazard subsection.** Is the stated rule sufficient, and does
   the new `retained-history-survives-reopen-at-capacity` test actually catch
   an undersized capacity, or can it pass by luck?
6. **Anything the plan leaves owed that it does not name.** It is transient
   and will be deleted when consumed, so an unnamed remainder is lost.

State plainly whether this plan is ready to implement, or what must change
first. Rank findings by severity and say for each whether it blocks.
