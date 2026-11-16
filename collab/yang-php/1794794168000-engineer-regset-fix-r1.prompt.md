Created-GMT: 2026-11-16 (dispatch time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2)
Session-ID: pending

# Task: regset-fix r1 — three yang.php JVM-lane regressions on master 8a44b87a

Worktree: ~/ci/dw-regfix (branch fix/yangphp-regset, cut from master 8a44b87a). One writer per
branch; do not touch other trees; push YOUR branch only, never master.

## The pinned red set (fresh gen:antlr, bb test:sub:clj yang.php, 29 namespaces, 5034 assertions)
1. yang.php.antlr.class-model-test/declare-and-inherit-test (class_model_test.cljc:102):
   expects "Cannot declare class a, because the name is already in use", receives nil.
2. yang.php.antlr.e2e-test/permutation-corpus-quick-test (e2e_test.clj:446): php-src oracle
   mismatches — quick selection 271 entries: pass 119, unsupported 57, FAIL 95.
3. yang.php.antlr.finalization-test/shutdown-test (finalization_test.clj:43): destructor order
   "x21", expected "x12" (creation order).

These pass at ancestor a717da28 and fail at 8a44b87a — the suspect window is
a717da28..8a44b87a (git log that range; the P7-b ticks round and the eval-bridge landing
(733fcd5b / 8cc092d1 lineage) are inside it). Everything else in the namespace set is green,
including include-test — do not disturb it.

## Method
- FIRST attribute: for each failure, bisect the window (git bisect or targeted reverts in a
  scratch branch) to find the introducing commit(s). Check whether the three share a root cause
  (they all involve module/publication/finalization semantics that the ticks + eval-bridge
  landing touched).
- Then fix minimally per failure, or revert the introducing change if that is cleaner — a
  design conflict with the ticks/eval-bridge invariants = STOP and record (do not weaken the
  §8.10.7 boundary or the astra ticks-SCC ruling, collab/yang-php/1794772328000).
- The oracle for semantics is real PHP (shiva php:8.3 docker, the established oracle pattern);
  the e2e corpus failures must be validated against php-src expectations, not intuition.
- Gates: bb gen:antlr first (fresh checkout has no parsers); bb test:sub:clj yang.php must be
  0 failures; keep log custody per-run (copy target/profile/sub-*.log before any rerun).
- Dart/Node lanes are not required this round; JVM green is the bar. Note if a fix is
  JVM-only-verified so the other lanes get a follow-up check.

## Deliverables
- Commits on fix/yangphp-regset + push the branch.
- collab/yang-php/<ts>-engineer-regset-fix-r1.gpt-6.1-terra.findings.md: per-failure
  attribution (commit hash + mechanism), the fix or the stop reason, gate numbers, log paths.
- If sandbox blocks writing collab/ from the worktree, stage the findings at the worktree root
  and say so.
- Stop rule: an attribution that requires a design decision (e.g. an invariant conflict between
  the landing and an older behavior) stops the round for that failure; record it and move to the
  next failure if independent.
