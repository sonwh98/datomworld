Created-GMT: 2026-09-09 12:35:43 GMT
Created-Local: 2026-09-09 19:35:43 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 6a2bae02-ee7f-47b6-ae80-e905b0ca657f
# Task: Phase 2 corrections from two reviews (r2)
Role: Storage & Indexing Engineer

Two independent reviews. **The code is cleared by both** — do not rewrite it.

- Routine, `gpt-5.6-sol`:
  `collab/1788956738719-review-space-transactor-phase2.gpt-5.6-sol.findings.md`
  One **P1, blocking, documentation only**.
- Adversarial, `deepseek-v4-pro`:
  `collab/1788956763164-adversarial-space-transactor-phase2.deepseek-v4-pro.findings.md`
  **"Sound; ready to commit. No blocker."** Three non-blocking findings.

It re-enumerated every public schema surface and confirmed D10 is fully
applied — no fifth forwarding surface — and confirmed the conforming `gap`
and `cursor-mismatch` doubles pin S10. Your `ThrowingAppendStream` `val` fix
was checked against every other migrated double: none pass vacuously.

## P1 (blocks) — the docs you edited still describe a system that does not exist

`docs/design/dao.space.md:422-439`, the **Fault Tolerance** section, says the
write path uses "persistent append-only `dao.stream` files", that "datoms
flushed before a crash are safe", that "a restarted writer reopens its file in
append mode", and that a lagging cursor "returns `:daostream/gap`".

None of that is true of `memory-log`, and the last is worse than stale: it
tells a reader to handle an outcome the transport **excludes by declaration**,
which is the exact confusion `conformance.cljc`'s exclusion principle exists
to prevent. It contradicts the new text you added at `:390-405`.

Rewrite the section around the actual two-stage model:

- un-published memory-log contents **do not survive process failure**;
- durability begins at publication, in `dao.jing`;
- recovery after restart starts from published state, or from a future
  causality-carrying checkpoint (named as future work, not designed);
- delete the `ds/next`, file-reopen and `:daostream/gap` claims.

Also stale, same phase, same files:

- `docs/design/dao.space.md:182` — "q returns a closed bounded result
  DaoStream". Query returns a bounded **value**; `collect` materializes it.
- `docs/design/dao.space.md:476-477` — worker loop still writes via
  `ds/append!` while the example below uses `transactor/append!`.
- `docs/design/dao.space.schema.md:246-248` — still calls the inner object a
  registered `:transactor` **stream wrapper**.
- `docs/dao.space.stigmergy.md:3-6` — still *defines* stigmergy as writing
  via `ds/append!`.

## Finding 1 (non-blocking, but fix it) — the plan is now factually false

You correctly deferred `stream-values` and the `:777` carrier to Phase 3:
their only callers are the four published-adapter deftests Phase 3 moves, and
migrating them now would have pointed a v2 read loop at a v1
`PublishedIndexStream` that has no `cursor`. **You were right.** But the
plan's §5.5 still says Phase 2 migrates them, and §4.5 counts the carrier
among "the other 27 migrate", and your correction lives only in a findings
file that gets archived.

Correct §4.5 and §5.5 in
`collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md`
— this is the one case where editing the plan is right, because it is still
the live specification for Phase 3 and a successor will read it as truth.

## Finding 2 (non-blocking) — `create!` throws opaquely on a non-map spec

`transactor.cljc:173` runs `(contains? spec :next-t)` before any map guard,
so `(transactor/create! 42)` throws a raw
`IllegalArgumentException: contains? not supported` rather than D2's clean
`ex-info`. Add the guard before the `contains?`, with a test.

## Finding 3 (non-blocking) — the one untested branch, which you named yourself

`transactor.cljc:146-148`'s non-outcome → `transport-error` fold. Both
reviewers confirm it is the **only** untested branch in `append-packet!`. Add
a double whose `append!` answers `:boom` and whose retry delegates to the
memory-log; assert the exact folded result including `:dao.stream/answer`, and
that the retry commits at the original `t`.

## Verification

Re-run all four lanes and report counts. Format with
`mise exec -- cljstyle fix` before reporting. Do not stage or commit.

Report to `collab/1788957343459-storage-space-transactor-phase2-r2.glm-5.3.findings.md`.
