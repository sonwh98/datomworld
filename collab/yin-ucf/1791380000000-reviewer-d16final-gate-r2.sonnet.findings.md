Completed-GMT: 2026-10-08 22:40:00 GMT
Coding-Agent: Claude (claude-sonnet-5-5), adversarial reviewer

# D16-final adversarial review, Round 2

**Verdict: READY_TO_LAND for the test content, conditional on the three-lane run being done at landing.** The code and test changes I asked for are in and correct. Required item 1 (Node, Dart, full JVM lane) is still not done. It is a landing step, not a defect in the diff, and I list it below as a hard precondition.

## Items from Round 1

| # | Requirement | Status |
|---|---|---|
| 1 | Full JVM fast lane, Node, Dart, counts recorded | **Not done.** The implementer reports the owner waived the JVM rerun. Node and Dart were never run. |
| 2 | kondo and cljstyle on the two test files | **kondo: done.** I ran `clj -M:kondo --lint` on both files: 0 errors, 0 warnings. **cljstyle: not run by me.** `git diff --check` is clean per the implementer. |
| 3a | Row 5 quarantine assertion tightened | **Done.** `compose_rows_test.cljc:2198` asserts `(nil? (get-in occurrences [o :yin.k/quarantined]))`. It is paired with a `contains?` check that the occurrence exists, so a wrong path can no longer pass. `nil` is the right expectation, since the fold only ever writes `true`. |
| 3b | Row 2 duplicate-evidence / no second grant | **Done.** Lines ~1936-1958 cover both the `transport-error` and `ok` branches: one admitted variant, one lease ("duplicate evidence creates no second grant"), one grant acknowledgment in the journal (`journal-acks`), one attach. The attempt-count expectations are asymmetric on purpose: `transport-error` retries, `ok` sends once. That is correct. |
| 4 | Dispositions and Stage E carry-overs | **Recorded** in the implementer findings. Freeze liveness is the safe terminal state for Stage D, deferred to Stage E. Row 5 write-ahead is flagged as pinned behavior. The Stage E list is named: row 1 variants, row 2 intent-to-attempt cut, row 6 partition, row 8 forged/overflow/attach/store-isolation, cross-host durability. |

## Reproduction

`clojure -M:test -n yin.vm.ucf.compose-test -n yin.vm.ucf.compose-rows-test` gives 54 tests, 949 assertions, 0 failures, 0 errors. This is +15 over my Round 1 run (934). That matches the implementer's account of 1 added assertion for row 5 and 14 for row 2. Production is untouched: the diff is confined to `test/yin/vm/ucf/`.

## Residual notes (non-blocking)

- **Architect rulings are still not on file.** Two dispositions are owner-relayed only: the freeze-liveness stall and the row 5 write-ahead reading. The implementer says so honestly. Please file a one-paragraph ruling or tracked open question for each before Stage E. That way a later liveness fix visibly changes the pinned rows on purpose.
- **Carrier contract.** Row 2 treats `full` and `refused` as proof nothing landed. It rests on the `export/not-appended?` rule, and the implementer names it. A one-line docstring or spec note next to the row would make the dependency discoverable.
- **Cross-host hygiene.** These are `.cljc` rows, and Node and Dart behavior is unproven. The known CLJD traps are not visibly present, but only a run settles that.

## Conditions before the commit

1. Run Node (`npm ci` first in this worktree) and Dart for the slice, plus the one full JVM `bb test` that `build-n-test.md` calls for. If either host lane fails, treat that as new `CHANGES_REQUESTED` input.
2. Run cljstyle on the two changed files and check that it makes no diff.
3. Do not stage `collab/` paths.
