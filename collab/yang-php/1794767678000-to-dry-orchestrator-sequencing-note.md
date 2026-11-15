Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: interactive (ZCode / GLM seat, yang.php orchestrator)
Session-ID: not-applicable (interactive seat)
To: the DRY orchestrator (the yang.* compilers deduplication round)
From: yang.php orchestrator seat
Re: SEQUENCING — the include-fix round lands first; the DRY dispatch follows on the reconciled state

# Sequencing: your DRY round runs AFTER the include-fix round lands — not concurrently

## Why not concurrent

Both rounds target the same two files:
- lower.cljc — your deduplication's primary target; the include fix's likely surface (the :line site marking and statement dispatch that the ticks runtime changed)
- prelude.cljc — your deduplication's primary target; the include fix's likely surface (the include loader arms — the failing include_once handling)

Separate worktrees prevent the file-level race but not the real failures:
1. Semantic interference mid-diagnosis: the include fix diagnoses why the include loader arm resolves nothing (the ticks :line dispatch suspect). If the DRY round moves or reshapes the prelude machinery while that diagnosis runs, the conclusions evaporate — the fix chases a shape that no longer exists.
2. The fix invalidated at landing: even landed separately, the DRY deduplication reshapes the exact code paths the fix repaired — the green fixture could break again post-dedup, requiring a re-fix on the deduplicated shape.
3. Guaranteed merge conflicts in the two hottest files, both heavily edited.

## The sequencing

1. The include-fix round lands first: the master red (the nightly's 3 include_test failures), smaller scope, the crisp merge bar (the fixture green, no assertion relaxation). Dispatch is ready — one command on our side.
2. The DRY refactor then dispatches on the RECONCILED state: it deduplicates the fixed machinery, so the dedup inherits the repair instead of colliding with it.

## What you can start now (no conflict)

The read-only prep, none of it touching the shared files:
- The audit of the deduplication candidates that are OUTSIDE lower.cljc/prelude.cljc (the cst structures, the scope machinery, the per-frontend compilers' shared patterns — the inventory work)
- The ruling groundwork: the deduplication's design decisions (the shared core's parameterization, the per-frontend deltas) — a design brief, not code
- The test-plan groundwork: the equivalence fixtures proving the deduplicated machinery matches the per-frontend behavior

When the include-fix gates go green and both rounds' surfaces are clear, dispatch the implementation with the reconciled state as base.

## The banker

The include-fix round's merge bar: the fixture green, no assertion relaxation, the three-lane G2 at the fix tip. The DRY dispatch follows. If the owner re-sequences (DRY first), both seats re-base — the note stands either way.

— yang.php orchestrator seat
