Completed-GMT: 2026-09-09 13:00 GMT
Completed-Local: 2026-09-09 20:00 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 6a2bae02-ee7f-47b6-ae80-e905b0ca657f

# Phase 2 r3 — the two failure scopes separated

Docs only; no `.clj*` file was touched, so no cljstyle run was needed.
Nothing staged, nothing committed.

## The P1, closed

`docs/design/dao.space.md`, *Fault Tolerance (Crash-Only Semantics)*, is now
organized by failure scope, with the scopes named explicitly:

- **Atomicity, in both scopes** — one atomic transaction record per local
  append; manifest last, so a failure can leave a prefix, never a torn
  transaction or a partial manifest. ("A crash can leave" became "a failure
  can leave" so the word crash no longer appears where a scope is implied.)
- **A writer task stops; the process lives.** Its handles remain owned and
  readable: a reader reaching the stopped writer's local-stream tail is
  told `:dao.stream/blocked` and waits rather than failing — the same for a
  DaoJing observer over an intake stream. Retrying an interrupted
  publication is idempotent **while the memory-log is still available**:
  content addressing deduplicates a re-emitted prefix, the manifest is
  appended last. The old "crashed writer" phrasing is gone.
- **The process fails.** The local stream is a process-lifetime memory-log
  and the intake streams are in-memory handles, so un-published local
  contents **and intake payloads a DaoJing observer has not yet
  materialized** may be lost; **nothing tails a dead handle**. Recovery has
  only what DaoJing has fully materialized: a restarted process sees a new,
  empty local logical stream with a new identity and continues from the
  manifest addresses it (or its configuration) names. Durability begins at
  publication, never before.

The checkpoint sentence is no longer "the future relief for un-published
history." It is now a closing paragraph stating plainly that the
causality-carrying checkpoint in `dao.space.transactor.md`'s *Open items* is
a performance optimisation — it would reduce the O(history) causality
replay `create!` pays on open — and **adds no durability**: un-published
history would remain un-recoverable, checkpoint or not. (Chosen over
deleting the sentence: the pointer keeps a future implementer from
mistaking the checkpoint's purpose in the other direction — as a durability
mechanism someone might ask it to become.) No new mechanism introduced.

## One follow-through beyond the cited lines

`docs/dao.space.stigmergy.md`'s *"Agents fail mid-action"* bullet carried
the same conflation — "A crashed agent's log simply stops; a reader tailing
it blocks rather than erroring" — and cites the very section this pass
re-scoped, so it would have contradicted the correction. It now reads: an
agent that stops while its process lives leaves its log readable (reader at
the tail blocks, publication retries idempotent); a failed process's data
survives only as published. Same scoping correction, applied to the
sentence that pointed at the fixed section — not a new finding.

Verified by grep: `crashed writer|crashed agent` returns nothing across the
phase's five design docs.

## Verification (smoke, per the brief)

| check | command | result |
|---|---|---|
| clj | `bb test:clj` | **Ran 1434 tests containing 165356 assertions. 0 failures, 0 errors.** |
| demo | `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **Build completed. (212 files, 1 compiled, 0 warnings, 2.44s)** |

cljs/cljd not re-run — no code touched since their green r2 runs
(1344/34923, 0 failures + 1 pre-existing error; cljd all passed +1297).
