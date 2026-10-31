Created-GMT: 2026-10-21 02:25:00 GMT
Created-Local: 2026-10-21 09:25:00 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Address Codex Re-Review Findings (H0-R5 & Design Reconciliation) on Slice H0
Role: Implementation Engineer
Model: claude-opus-5-5

Working Directory: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0
Branch: host-h0
Governing Document: /Users/sto/workspace/datomworld/collab/1792529000000-reviewer-host-boundary-h0-r2.gpt-6-astra.findings.md

Codex Findings to Address:

## 1. H0-R5 (High): `specify`/`specify!` target expressions must be walked
- In `test/dao/host/gate_test.clj` around lines 198-199, 411-418:
  Currently `specify`/`specify!` uses `(drop 2 form)` in the shared `deftype-heads` handling, which skips the target expression.
  The target expression is executable code and must be scanned with the enclosing scope.
  Separate the target expression: walk the target expression with the enclosing lexical scope, separate from method declarations.
  Add regression test verifying that an effect call (e.g. `(random-uuid)`) inside the target expression of `specify!` is caught as a violation.

## 2. Design Reconciliation (§8.1 & §14 Vocabulary / Categories):
- Reconcile vocabulary: in `gate_test.clj:584`, use `:pending` for `yin/vm.cljc` (or reconcile with §8.1/§14 while retaining OQ11 intent).
- Reconcile `list-1-categories` (lines 493-516) with the §8.1 category-to-class table: ensure mapping aligns cleanly with §8.1.

## 3. Verification:
- Run `clojure -Sdeps '{:aliases {:review {:extra-paths ["test"]}}}' -M:review target/h0-review/re-review.clj`
- Run `bb test:sub:clj dao.host`

Write report to:
/Users/sto/workspace/datomworld/collab/1792529500000-engineer-host-boundary-h0-r5-fix.claude-opus-5-5.findings.md
