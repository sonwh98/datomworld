Created-GMT: 2026-09-09 13:01:29 GMT
Created-Local: 2026-09-09 20:01:29 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: confirm the failure-scope split (r3)
Role: Routine Review

**Read-only. Print to stdout; write nothing.**
Review `git diff -- docs/design/dao.space.md`. **No code changed this round.**

Your P1 is fixed. The section is now split explicitly by failure scope:

- **A writer task stops, process alive** — handles stay owned and readable, a
  reader reaches the tail and is told `:dao.stream/blocked`, and publication
  retries are idempotent by content addressing while the memory-log is
  available.
- **The process fails** — the local stream is a process-lifetime memory-log
  and the intake streams are in-memory handles, so un-published local
  contents and unmaterialized intake payloads may be lost; **nothing tails a
  dead handle**. Recovery has only what DaoJing has fully materialized.

And the checkpoint is demoted to what it actually is: "a performance
optimisation that would reduce the O(history) causality replay `create!` pays
on open, and it adds no durability — un-published history would remain
un-recoverable, checkpoint or not."

All four lanes were green before this round (clj 1434/165356, cljs
1344/34923 + 1 pre-existing, cljd 1297, demo 0 warnings); I am re-running
`bb test:clj` and the demo compile as a docs-only smoke check.

## Judge

1. Does the split close the finding, and is either scope now wrong?
2. Is the checkpoint's demotion accurate against
   `dao.space.transactor.md`'s *Open items*?
3. Anything else in this section — or elsewhere in the phase's docs — still
   promising what the implementation cannot do.

This is the third round on this one section. If it is right, say so plainly;
if a fourth conflation survives, say that equally plainly and name it.
State whether Phase 2 is ready to commit.
