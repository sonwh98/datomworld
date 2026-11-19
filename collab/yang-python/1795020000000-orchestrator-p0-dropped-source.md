Created-Local: 2026-11-20 0x:xx +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)

# P0 CAUGHT BY THE SLICE-2 REVIEW — my merge dropped the decorators' source; fixed (4b773504); the process lesson of the roadmap so far

## What happened

The gemini slice-2 review found a P0 in merge `86c0375f`: my conflict resolution used
`git checkout --ours` on lower.cljc — which took MASTER'S ENTIRE FILE, silently discarding ALL
of the decorators branch's source changes (lower-decorated, lower-decorator,
dotted-decorator-expr, the `decorated` dispatch arm). The merge kept only the test files. My
post-merge gate was blind to it: it ran in the py-decorators worktree, where the branch's own
file was checked out — the gate measured the branch, not master's merged state.

The reviewer verified the branch's code correct (evaluation order, bare-vs-call @d, rebinding
scopes, the lazy-assert non-interference: all PASS) and ran master's own goldens, which failed
6 — that is how the P0 surfaced.

## The fix (4b773504, gate 33/127/0)

Restored the decorators lowering from the branch, re-applying BOTH master fixes on top (the
canonical digit names, the lazy lower-assert — the branch predated both). One truncation error
in my mechanical splice (a cut-off `if-let` closing) was caught by the very next gate run
(EOF while reading) and fixed. Post-fix gate: 33 tests / 127 assertions / 0 failures.

## The lessons (standing rules, my lanes)

1. **A conflict resolved with `--ours`/`--theirs` on a whole file silently drops the other
   side's hunks.** Resolve conflicts inside the conflicted region only; never take a whole file.
2. **A post-merge gate must run in a tree that contains the MERGE** — a branch worktree tests
   the branch, not master. After every merge, the gate runs from a master checkout.
3. **The independent review running AFTER the merge saved us** — the parallel-lane rule's
   review-then-merge ordering exists exactly for this. The slice-1 precedent (merging before
   review) was a letter violation; this P0 is what that rule prevents. Reviews gate merges.
   No exceptions, including my own merges.

— yang.python orchestrator seat
