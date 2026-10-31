Created-GMT: 2026-09-09 10:26:08 GMT
Created-Local: 2026-09-09 17:26:08 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 8bc96ab3-be06-4493-82d4-355a52e8dcb2
# Task: adversarial review of the transactor+index migration plan
Role: Adversarial Review
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-09 17:26:08 +0700 | Status: active | Rationale: Adversarial Review primary per team.md; a third family, independent of both the Claude architect who wrote the plan and the GPT reviewer who has done every review round so far

**Read-only. Print your findings to stdout; write nothing. No code exists
yet** — this attacks the plan before an implementer is briefed.

Plan (857 lines, three phases):
`collab/1788949213827-architect-space-transactor-v2-plan-r4.claude-fable-5-1.findings.md`

## Why you specifically

Eleven review rounds on this migration have all been the same model
(`gpt-5.6-sol`, one resumed thread). It has found a great deal — including a
P0 that would have silently corrupted transaction time. **Nothing has checked
it.** Your value is being a different mind, not a more thorough one. Assume
the plan is wrong and find where; assume the previous reviewer developed blind
spots and find them.

## What this migration is

`dao.space.transactor` and `dao.space.index` move from v1 `dao.stream` to
`dao.stream`, ending with both v1-free and `dao.space.schema` the last
`dao.space` namespace on v1. Read `docs/design/dao.stream.md` (the contract),
`dao.space.index.md`, and the three sources: `transactor.cljc` (246 lines),
`index.cljc` (604), `schema.cljc` (1168).

## History you should distrust rather than trust

- A **P0**: a freshly minted `:dao.stream/oldest` is the earliest *retained*
  position, not the origin, so `derive-next-t` could derive transaction time
  from a truncated suffix with nothing observable. Fixed by building
  `dao.stream.memory-log` (committed `ba90b3a`), a transport declaring
  complete retention.
- Three of the findings across this chain were **a rule generalized from one
  instance**, each looking obviously right. Look for a fourth.
- The plan's own `snapshot-datoms` loop would have **hung** rather than
  thrown on a malformed result, because it interpreted outcomes before
  validating result shape. Fixed in r4. Look for the same shape elsewhere:
  where else does this plan trust a value before checking it?

## Attack these specifically

1. **The three phases.** Phase 1 claims to be behaviour-neutral. Phase 2 is
   "the swap" and cannot be partial. Is each edit set actually closed under
   compilation, and does each phase truly leave three lanes green? A phase
   that only compiles because a later phase lands is a defect.
2. **The residual risk the plan admits**: nothing inside `index` can detect a
   wrongly wired evicting transport, because a ring buffer answers
   `ok`/`blocked`/`end` from a fresh `:oldest` exactly as the memory-log
   does. The plan's answer is documentation in four places. **Is that
   actually sufficient, or is it a defect being written down instead of
   fixed?** If it can be detected, say how.
3. **The deletions.** Seven `index_test` deftests, the v1 `defopen`s,
   `DaoStreamLog`, S5/T4b. Every deleted test must have its property covered
   or explicitly dropped with a reason. Find one that is neither.
4. **Concurrency.** `SchemaWrapper`'s lock now spans an index build. The
   transactor's watermark advances only on `ok`. Find an interleaving that
   breaks a stated invariant.
5. **What the plan does not mention at all.** It is transient and gets
   deleted when consumed, so anything owed and unnamed is lost. That is where
   I would look first.

Do not restate what previous reviews found; `collab/1788948769084-review-space-transactor-index-plan-r2.gpt-5.6-sol.findings.md`
has those. Rank findings by severity, name file and line, and say for each
whether it blocks implementation.
