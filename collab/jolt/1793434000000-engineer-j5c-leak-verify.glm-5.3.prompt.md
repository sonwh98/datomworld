Session-ID: 5f9e2b8a-1c47-4d3e-9a62-8b0f7e4c1d93

# J5c round 2: finish the reach-set leak fix — the gates only (the patch is on disk)

Engineer seat — NEW session (the prior session's prompt file was lost in
the staging incident; the analysis survived in your findings file, which
you should read first). Worktree:
/Users/sto/workspace/datomworld-j5b (branch jolt-j5b, uncommitted two-file
patch on top of the J5b slice). Commit nothing — the orchestrator gates,
merges and pushes.

## State

- The fix is implemented: native/jolt-parser/jolt_parser.cpp's
  BoundedSimulator gains the computeReachSet override (full-context path
  frees the caller's set on the exception path, sparing the start set via
  full_s0_; SLL path pure pass-through) — the ten-line reach-computation
  override the J5b closure sign-off prescribed, with the ownership
  analysis in
  /Users/sto/workspace/datomworld-j5b/collab/1793310000000-engineer-j5c-leak-fix.glm-5.3.findings.md
  (read it: it has the pinned-runtime line numbers and the diff).
- docs/agents/known-gaps.md's leak row's closing-slice column is updated
  in the worktree.
- NOT done: any verification. The gates below are the whole of this
  round.

## The gates (macOS locally; Linux is the orchestrator's)

1. **Leak experiment → zero**: rebuild the sanitized harness
   (`bb build:jolt-native`), then the asan harness at cycles 0, 50, 100 —
   each must report no LeakSanitizer leak in the ParserATNSimulator
   path (the prior signature was a constant 181,088 bytes / 3,312
   allocations; it must be gone at every cycle count).
2. **The sanitizer soak reaches HARNESS ok**: a short soak
   (`bb src/dev/jolt_j0c_sanitize.clj 5`) with leaks ON must now pass —
   that is the fix's whole point.
3. **The J5 gates stay green**: `bb probe:jolt-j5` (all legs),
   `bb calibrate:jolt-j5`, `bb test:jolt-j5`.
4. cljstyle clean on any file you touch; no collab path in any comment.

## Reporting

Append a "## Round 2 (verification)" section to the findings file above
(the one in the worktree's collab/) with the standard Completed stamps
filled in, per-gate counts, and any residual leak observed elsewhere
(report, don't fix).
