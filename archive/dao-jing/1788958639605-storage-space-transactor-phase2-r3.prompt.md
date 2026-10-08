Created-GMT: 2026-09-09 12:57:19 GMT
Created-Local: 2026-09-09 19:57:19 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 6a2bae02-ee7f-47b6-ae80-e905b0ca657f
# Task: separate the two failure scopes (r3)
Role: Storage & Indexing Engineer

Everything else is **confirmed closed**: the four stale doc sites, the plan
correction, `create!`'s map guard, and `NonOutcomeAppendStream` — the
reviewer verified each pins what it claims, and that the new double repeats
none of the `ThrowingAppendStream` mistake. All four lanes are green on your
tree (cljd: all passed, 1297).
Review: `collab/1788958438753-review-space-transactor-phase2-r2.gpt-5.6-sol.findings.md`

**One P1 remains, in `docs/design/dao.space.md:439-451`.** It is a scope
conflation, and "crashed writer" is the word doing the damage.

## The defect

- `:439-442` — "a reader tailing a **crashed writer's** local stream reaches
  the tail and is told `:dao.stream/blocked`". After **process** failure the
  in-memory handle is gone; there is nothing to tail. That sentence is true
  only when a writer **task** stops while its host process and stream remain
  alive.
- `:446-451` — resuming an interrupted publication, and the checkpoint, are
  filed under **restart** recovery. After process failure the un-published
  history may not exist to re-emit, so idempotent resumption is also a
  live-process property. And the checkpoint is proposed in
  `dao.space.transactor.md` *Open items* to reduce **O(history) replay on
  open** — a performance optimisation. Calling it "the future relief for
  un-published history" recasts it as a durability mechanism it was never
  designed to be, and would mislead whoever builds it.

## Required

Split the section by **failure scope**, explicitly:

1. **A writer task stops, process alive.** Its handles remain owned and
   readable; a reader reaches the tail and is told `:dao.stream/blocked`;
   publication retries are idempotent by content addressing while the
   memory-log is still available.
2. **The process fails.** Local and queued-but-unmaterialized data may be
   lost. Recovery has only what DaoJing has fully materialized. Nothing
   tails a dead handle.

Then either delete the checkpoint sentence, or state plainly that it
optimises causality replay on open and **adds no durability**. Do not
introduce any new mechanism; this is a scoping correction to prose.

## Verification

Docs only. Re-run `bb test:clj` and the `:demo` compile as a smoke check —
the other two lanes need not repeat unless you touch code. Format with
`mise exec -- cljstyle fix` if you touch any `.clj*`. Do not stage or commit.

Report to `collab/1788958639605-storage-space-transactor-phase2-r3.glm-5.3.findings.md`.
