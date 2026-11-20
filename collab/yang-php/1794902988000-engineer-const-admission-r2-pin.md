Created-GMT: 2026-11-17 (resume time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a1e3ef)

# const-admission r2 — the manifest re-pin (one root, mechanical, disclosed)

Your r1 is accepted substantively: the ruling implemented exactly, the slow linked test 90/0,
all focused suites green. The full-gate run (184 tests) shows exactly 3 failures, ALL in
e2e-test/phpt-selection-runs-test, ALL one root:

- php-src quick selection: unsupported 57 -> 48 (your fix ADMITTED 9 previously-refused
  programs — they contain bare constant reads).
- Those 9 programs now EXECUTE and mismatch on their OTHER known gaps (foreach-by-ref, gc,
  named-params families), moving the run-fail count 95 -> 104 (95+9=104 — consistent).
- The manifest stability guards (counts + ID sets) correctly caught the drift. The recorded
  manifest is now stale against the improved admission.

## The task
1. Compute the EXACT 9-program diff (actual mismatch set minus the previously recorded set —
   the two sets in the failure output at target/profile/sub-clj.log / the round's own saved logs).
2. Re-pin the manifest: the new unsupported count (48), the new run-fail ID set, and any
   family counts (:other 62 -> 71). Keep the manifest's format and its known-gap semantics:
   these 9 entries migrate from "unsupported (refused)" to "executes, mismatch on <recorded
   other gap>" — annotate the migration in the manifest entry or the ledger per the file's
   existing convention (do not silently reclassify).
3. Preserve every existing guard: the counts test, the ID-set test, the refusal-count test —
   re-pinned, not weakened or deleted.
4. Re-run: the phpt-selection-runs-test green; then bb test:sub:clj yang.php once (expect
   0 failures). Per-run log custody in target/const-r2/.
5. Findings: the 9 migrated IDs, before/after counts, the annotation form used. No commits.

Note: do NOT "fix" any of the 9 programs' underlying mismatches (foreach-by-ref etc.) — those
are separate pre-existing known gaps, out of scope; the re-pin just makes the manifest honest
about the new boundary.
