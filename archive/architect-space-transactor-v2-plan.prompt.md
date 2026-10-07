Created-GMT: 2026-09-08 13:16:18 GMT
Created-Local: 2026-09-08 20:16:18 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828
# Task: plan the dao.space.transactor migration to dao.stream
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-08 20:16:18 +0700 | Status: active | Rationale: Architect primary; wrote the query and index plans, and this ordering is its own D1 recommendation

The owner took your D1 ordering: **transactor first, then index closes.**
Your index plan is accepted and parked — its Phase 1 (splitting
`datoms-from-elements` from `snapshot-datoms`) should be folded into *this*
plan wherever it fits, since index's close now follows immediately.

Same method as before. Same repo, branch `dao.stream-redesign-v2`, clean at
`80181d5`.

## Two design updates since your index plan, both committed

- `dao.stream.md`'s *Explicitly Absent* now records **where taking went**:
  taking is a write; removing a tuple from an append-only log is a retraction
  datom, so Linda's `in` is observe-then-retract; the hard part is exclusion,
  which is a lease over datoms, not a stream promise.
- `dao.space.query.md` no longer says a shared `snapshot` would be named
  `drain`. **There is no `observe/drain` and there should not be one** — the
  name imports the vocabulary of the destructive read the contract removed,
  into the namespace named for observation. If the mechanism is ever shared it
  keeps the name `snapshot`; the gap policy stays with each caller. Your D2
  reasoning survives intact, only the name changes.

## Measured facts (mine, verified — correct me, as you rightly did last time)

`transactor.cljc` is 246 lines, `transactor_test.cljc` 735. It requires both
`[dao.stream :as ds]` (44) and `[dao.stream :as stream]` (45).

**The asymmetry that defines the problem:** the transactor is already the seam
between the two versions. Its `defopen` validates intake-pool members with
**v2** `stream/writer?` (:200), while requiring the local stream to satisfy
**v1** `ds/IDaoStreamReader`+`IDaoStreamWriter` (:188-189). It reads and
writes v1 locally and writes v2 to intake.

- `DaoStreamLog` (deftype, :140-172) implements all three v1 protocols:
  `ds/IDaoStreamWriter` `append!`; `ds/IDaoStreamReader` `next`, which just
  delegates to the local stream; `ds/IDaoStreamBound` `close!`/`closed?`.
- `ds/defopen :transactor` (:178) — the v1 registry, which v2 lists as
  Explicitly Absent.
- `append-packet!` (:110-122) appends through `ds/append!` and checks the v1
  outcome shape `{:result :ok}`; v2 answers `:dao.stream/ok`. The watermark
  advances only after a successful append, and the docstring makes that a
  retry contract.
- `derive-next-t` (:125-139) over `index/snapshot-datoms local-stream` — the
  causality boundary, O(history) at every open.
- `ds/closed?` at :239 guards `publish!`.
- Consumers: `schema.cljc:965` opens a `:transactor` descriptor and wraps it;
  `SchemaWrapper` delegates `close!`/`closed?` to that inner handle
  (schema.cljc:949, :954, :1069, :1127); `stigmergy_test.clj:106`; and about
  thirty `ds/open! {:dao.stream/type :transactor …}` sites in
  `transactor_test.cljc`.

## The questions this plan must settle

1. **What replaces `ds/defopen :transactor`?** v2 has no registry. Query's
   precedent is a plain constructor the caller calls (`open-published!`). Is
   the transactor opened by `transactor/create!` over a v2 local stream, and
   what happens to the descriptor as a *serializable* thing — was it ever
   one, given `:local-stream` holds a live handle?
2. **Is a transactor a stream in v2 at all?** `DaoStreamLog`'s `next` merely
   delegates. v2 has no `closed?` (Explicitly Absent) yet schema calls it at
   four sites and `publish!` guards on it. Does the wrapper become a v2
   writer-shaped value with explicit operations, and what replaces the
   closedness questions — an outcome on the next operation, per the contract's
   "operation results are authoritative"?
3. **The outcome vocabulary.** `{:result :ok}` → `:dao.stream/ok`, and what
   the retry contract in `append-packet!` becomes when the append can answer
   `full` or `closed` as data rather than throwing.
4. **`derive-next-t`'s O(history) replay.** Keep as-is (the stream is the only
   durable truth, so the counter must be recovered from it) or note it as a
   deferred cost? Do not invent a checkpoint in this plan; say which and why.
5. **Where index's close lands.** Fold your index Phase 1 in, and say exactly
   what `snapshot-datoms` becomes once the local stream is v2 — including
   whether `query/snapshot`'s mechanism is shared at that point, under the
   naming rule above.
6. **Ordering against `schema`.** `SchemaWrapper` wraps the transactor and
   delegates closedness to it. Does schema have to move in the same phase, or
   can the transactor land with schema still on v1?

## Constraints

- `public/demo.html` must keep working; verify by compiling `:demo`.
- All three lanes green at the end of every phase.
- Do not plan `dao.space.schema`'s own migration beyond what this change
  forces; it keeps its own plan.
- The v1 `:transactor` `defopen` and `DaoStreamLog` are deleted in the phase
  that replaces them, not left standing.

## Deliverable

Write the plan to stdout, starting with the Completed-GMT/Local,
Coding-Agent, Session-ID header. I will promote and route it for review.
