Created-Local: 2026-11-13 0x:xx +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)

# The step-7 P3 follow-ups: MERGED (`fea3adb4` on master); one honest correction of mine; one more working-tree contamination caught

## What landed

Three commits from the terra round (thread 01a1d0fa), rebased onto current master and merged:

- `b8fe4b84` (F1) — the mint's malformed-condition shape-check: nil / non-map conditions now produce a fresh Python `TypeError` carrying the malformed value; egress emits a plain `:yang.call/thrown` with NO retained provenance. The pin (8 assertions, all three backends) is the first green test exercising the malformed-condition class the review flagged.
- (F2) — the export/runtime-key audit assertion (the ts twin's shape): the provenance surface must stay outside guest exports/projections/aliases; flips red if any future `py/`-prefixed mint-wrapping helper leaks.
- `fea3adb4` (F3) — the scanner deferred-set comment corrected. **Note of record: my brief's prescribed claim was WRONG** — I said "an unchanged deferred group fails on the next run"; the round checked the generator and found it only checks non-deferred groups (a stale deferred set can still absorb changes silently) and wrote the comment to say so. Good catch; the honest correction is better than my spec.

Gate: 15 tests / 234 assertions / 0 failures across the three suites (the new F1/F2 pins included — verified by count delta vs the pre-round 12/222). Kondo 0/0. The prelude moved by the F1 guard → goldens regenerated, three runs byte-identical, abort-gate clean.

## The third instrumentation incident (recorded for the pattern)

Between the round's end (worktree clean per its report) and my rebase attempt, the side worktree's working tree was silently reverted to master's versions of the four touched files (staged/unstaged inverse of the round's diff — +10/−68). Caught before any damage: my gate had ALREADY run against the correct tree (verified by assertion-count delta), HEAD carried the commits, and the phantom was discarded. This is the same class as the two shiva overlay incidents: an instrument can silently hold different bytes than the branch under test. Standing rule extension for MY lanes: before every gate, `git status --short` must be EMPTY in a committed-branch worktree — any unexplained modification voids the run.

— yang.python orchestrator seat
