Created-GMT: 2026-09-08 14:45:12 GMT
Created-Local: 2026-09-08 21:45:12 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 0dae45ea-4204-49d9-bf35-c56e24cce14e
# Task: generalize the retention law (r3) — last correction before implementation
Role: Lead System Architect

All six earlier findings are confirmed closed. **One correction remains**, and
it is the same species as the P0 that started this: a law generalized from one
instance, quietly encoding that instance's properties.
Review: `collab/1788878406883-review-v2-memory-log-plan-r2.gpt-5.6-sol.findings.md`

## The finding

`run-retention-laws` appends 256 values and compares the replay against all
256. The contract permits a complete-history writer with **declared finite
capacity** to return permanent `full` (you argued this yourself in §3's
revised rationale). Such a transport — a conforming capacity-2 complete log —
would fail your law after its first two appends. The law would reject a valid
transport.

Two related gaps: a transport may restrict its value domain, so arbitrary
conformance tokens could draw `invalid-value` — completeness promises
retention of *accepted* values, not acceptance of arbitrary ones. And the
reader-only `:retention-fixture` shape `{:handle h :expected [...]}` does not
say whether a populated immutable reader terminates with `blocked` or `end`;
both are contract-valid.

## Required changes to `run-retention-laws` only

- Collect only values whose append answered `ok`; that collection, not the
  attempted 256, is `expected`.
- Stop population on `full` and do **not** treat permanent `full` as a
  retention failure.
- Take population values from the manifest or a fixture rather than assuming
  every transport accepts arbitrary keywords.
- Require at least one accepted value, so the law cannot pass vacuously.
- Reader-only fixtures declare their terminal outcome, e.g.
  `{:handle h :expected [...] :terminal :dao.stream/end}`.
- Keep requiring post-close `end` where the law itself closes an owner handle.
- Confirm the ring-buffer falsification still bites: it accepts every append,
  so the kept origin cursor still observes `gap` after capacity two.

## Two rulings to fold in as prose, not as new machinery

1. **`pos > tail` is legitimate.** Cursor validity is transport-owned and no
   cursor memory-log minted can hold such a position; the ring buffer's
   `blocked` creates no cross-transport law. Record the caveat: if
   fabricated-cursor histories are ever added to the oracle, the abstract
   model needs a transport option or a stricter range rule.
2. **Exclusions as unchecked assertions** belongs in `conformance.cljc`'s
   **namespace documentation**, not as an executable law. State that induction
   proves declared outcomes inducible but cannot prove an excluded outcome
   impossible, so each exclusion is a proof obligation discharged by a
   structural argument, a property law such as complete retention, or a
   transport-specific falsification test — and that fixtures must never be
   required for excluded outcomes, since that would contradict the exclusion.
   Put it where it survives this plan's deletion.

Change nothing else. Emit the whole revised plan to stdout.
