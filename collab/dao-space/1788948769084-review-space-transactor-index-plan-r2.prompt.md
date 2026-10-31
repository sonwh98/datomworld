Created-GMT: 2026-09-09 10:12:49 GMT
Created-Local: 2026-09-09 17:12:49 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: review the transactor+index plan, revised against the built transport
Role: Architect review

**Read-only. Print to stdout; write nothing. No code written yet.**

You reviewed this plan before the transport existed and found six defects
including the P0. `dao.stream.memory-log` is now built, reviewed by you,
and committed (`ba90b3a`), with the contract at `2e25b5c`, `ed30d7b`,
`ff44107`. The plan is revised against it.

Plan: `collab/1788948293243-architect-space-transactor-v2-plan-r3.claude-fable-5-1.findings.md`
(805 lines) — **five phases became three.**

## What it claims to have done

- **§6 capacity hazard deleted entirely**, including
  `retained-history-survives-reopen-at-capacity`, which asserted a gap it
  could not produce and would have pinned a defect as a feature. Accounting
  corrected to your numbers: 28 unbounded opens, `query_test:97` a literal,
  **27 migrate including the Phase 5 carrier** — the self-contradiction you
  caught.
- **r2's Phase 2 deleted** — no `observe/snapshot` promotion. Its argument:
  `query/snapshot` classifies every stopping outcome as data because its
  input's nature is *unknown*, while `index/snapshot-datoms` now reads a
  handle whose nature is *declared* with three reachable outcomes, so sharing
  one loop would make index carry query's vocabulary for conditions its
  transport excludes — `ff44107`'s exclusion principle. Index's read is ten
  lines. **Judge this argument specifically.**
- **The gap throw is deleted**; one totality branch survives. It states
  plainly that the surviving branch **cannot catch a wrongly wired ring
  buffer either**, since a ring buffer also answers `ok`/`blocked`/`end` from
  a fresh `:oldest` — nothing inside index can detect that defect by the
  contract's design, and the protection is the declaration plus the
  composition. Is that residual risk correctly stated and correctly placed?
- **Your five other findings**, each at its point of bite: schema installs
  `next-state` only on `:dao.stream/ok` (new T19, tested with a writer double
  since the real transport excludes `full`); the test inventory's closure
  criterion is a residue grep — `ds/` in `transactor_test` **135 → 0**, which
  I verified is 135 today; `SchemaWrapper.close!` keeps `{:woke []}` and
  `publish!`'s guard moves under the wrapper lock, with the cost stated (the
  lock is held across an index build); P5's missing failure-path test added
  and r2's stigmergy overclaim softened.
- **ADR 0003 amended, not superseded** — I verified :50-54 names `full`/`gap`
  as the loss vocabulary, which the local log now excludes.

## Judge

1. **Is it executable?** Three phases, each leaving three lanes green. Phase
   1 (the swap) still cannot be partial.
2. **The Phase 2 deletion** — sound, or does it leave duplicated logic that
   will diverge?
3. **The residual risk.** Nothing detects a wrongly wired transport. Is the
   plan's placement of that note sufficient, or does it need somewhere more
   durable than a plan that gets deleted when consumed?
4. **Deletions**: what goes with S5 and T4b. It argues deleting a test needs
   the same standard as keeping one, discharged by the exclusion principle
   rather than a survivor. Check each.
5. **Anything owed and unnamed**, given the plan is transient.

State plainly whether this is ready to implement.
