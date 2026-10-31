Completed-GMT: 2026-09-19 16:25:56 GMT
Completed-Local: 2026-09-19 23:25:56 +07

# r2 confirmation: dao.lease Phase 1+2

**Not ready to commit.** Fourteen of the 18 findings are confirmed fixed. One P1 fix introduces a new P1, one P2 is relocated rather than fixed, and P2-5 and part of the P3 assembly finding are only partly fixed. I edited nothing and ran no suites, as the brief directed.

## New or still-open defects

**N1 (P1, regression from the P1-4 fix) | `lease.cljc:39-46, 59-71, 760-766` | The 10⁶ magnitude bound gives the judge a finite lifetime, and it ends silently.**
- `duration?` also gates `:dao.lease/reading`, and readings never decrease. A millisecond tick stream therefore becomes structurally defective after 1000 seconds. A second-resolution stream lasts about 11.6 days.
- After that, `apply-tick-value` ignores every tick and `now` stops advancing. No `:abort` is set and tick-cursor values are not counted in `:dropped`. No lease lapses again for `:cap` or `:silence`.
- The plan's §6 row (line 548) records the bound as a constraint on compositions but says nothing about this consequence.
- `r2-hostile-reading-cannot-wedge-the-drain-test` pins `{:s 1000000}` as the maximum legal reading. No test goes past the bound.
- The bound also caps any duration or `:dao.lease/max` given in ms at about 16.7 minutes, and rejects a plain `:h 3600000` unit-table entry.
- Remaining action:
  - Bound the base product, not the raw magnitude: in `duration?`, require n ≤ 2^52.
  - When a unit table is supplied, also require `n ≤ (quot 2^52 unit-magnitude)` as a structural defect. That check divides, so it cannot overflow.
  - Products and sums then stay exact on all three hosts, and ms readings last about 140,000 years.
  - Add a test for a tick past the old bound, and make an over-bound tick on a tick cursor visible through a counter or signal.

**N2 (P2-4 not fixed, only relocated) | `lease.cljc:712-715, 848-852, 1068-1070` | One holder can still block another holder's proposal id.**
- Answers are recorded under `[self pid]`. The test asserts exactly that at `test:1177-1181` (`[:grantor :p1]`).
- Scenario: holder B proposes `:p1` and is refused. Holder A proposes `:p1`, and that proposal now reaches the hook. The grant echoing `:p1` then hits `[:grantor :p1]` in `deliver-authored` and is rejected as `:proposal-already-answered`. A can never be answered under that id.
- The orchestrator's note that this "achieves the smaller fix structurally" is accurate. My smaller fix was itself incomplete: it let A's proposal through but did not protect the answer.
- No test has two holders sharing a proposal id.
- Remaining action:
  - Key answers by the proposer. For a grant that is `[holder pid]`.
  - A refusal carries no proposer. Have the `:answer` hook return the proposer alongside each refusal, or record this as a contract question.
  - Add the two-holder collision test.

**N3 (P2, new, caused by the "or a self-authored grant" clause of my P1-3 fix) | `lease.cljc:889-895, 1005-1007` | A grant read from a fact cursor registers the lease on the grantor's medium, which switches off both fallbacks for unregistered leases.**
- `on-any-medium` then returns true for that lease.
- If the holder's own medium truncates or gaps before its first renewal is observed, the lease is neither suppressed (`silence-suppressed`, 1168-1171) nor marked unknown (`mark-medium-unknown`, 956-957). That is a false lapse over a window the judge never observed.
- The tests never reach this path because they seed grants through `author-grant`.
- Remaining action: register a lease only from holder-authored facts (renewal or release), never from the grant. Add a test that drains a grant from a cursor, truncates the holder's medium, and expects no silence lapse.

**N4 (P2, new) | `lease.cljc:622, 1140-1142` | `:tolerance` is never validated.**
- `tolerance?` is defined at line 74 and never called.
- A tolerance in a unit outside the table throws from `add-duration` inside `classify-entry`. Step 4 has no try/catch, so the throw escapes `judge-step` after step 3 has already appended grants.
- The threaded state is lost, and the next pass appends the queued grants again.
- This was half of my P3 assembly finding, and `initial-judge-rejects-misconfiguration-test` omits it.
- Remaining action: in `initial-judge`, check `tolerance?`, check that its unit is in the table, and add the test.

**N5 (P2/P3, new) | `lease.cljc:833-837, 1051-1055` | Catching throws turns an exception wedge into a silent permanent abort.**
- The catch returns the judge and cursor entry as they were before the drain. An exception raised while processing a value, such as a resolver that throws on a hostile envelope, therefore re-reads the same element every pass. Reclamation halts for every lease.
- The docstring claims "no cursor is left wedged", which is false in this case.
- The exception itself is discarded, and on the JVM `Throwable` also swallows interrupts and out-of-memory errors.
- `r2-thrown-read-folds-into-abort-test` covers only a throwing `next`, where aborting is the right response.
- Remaining action:
  - Wrap value processing separately from the read. On a throw, advance past the element and count it in `:dropped`.
  - Keep the abort for a throwing read.
  - Catch `Exception` on clj and store the message under an `:abort-error` key.

**N6 (P3, residual, acceptable if documented) | Any truncated or gapped medium affects every lease that has never renewed.**
- This includes an attacker's own per-author medium. A truncated medium suppresses `:silence` for every never-renewed lease, and a gapped one re-marks them `unknown` with a fresh resumed reading.
- The leak is bounded, since `:cap` still applies, and it errs toward the holder.
- Remaining action: record it in §6 as owed to "the grant declares its medium" in Phase 4.

## Dispositions of the original findings

| Finding | Disposition | Evidence | Remaining action |
|---|---|---|---|
| P1-1 forged release poisons the holder's release | **Fixed** | 903-915 returns `[judge nil]`; r2 test at 1400 | none |
| P1-2 truncated drain classifies silence | **Fixed for the intended path** | `:truncated?` at 1011/1029, suppression at 1145-1172, tests 1426-1495 | see N3, N6 |
| P1-3 registration by any author | **Fixed, apart from the grant clause** | `counted` at 994-1007, test 1498 | see N3 |
| P1-4 overflow wedge | **Overflow closed; the fix is wrong** | stale rule moved to 760-766, bound at 39-71 | see N1, N5 |
| P1-5 resolver bound to source | **Fixed** | 742-751, `wire-facts` source, test 643 | none |
| P2-1 pending first, throwing reclaim | **Fixed** | 1244-1250, test 1009 | none |
| P2-2 three-way delivery | **Fixed** | 1083-1118 | none |
| P2-3 only accepted/rejected deliverable | **Fixed** | 1094 | none |
| P2-4 proposal-id collision | **Not fixed** | see N2 | see N2 |
| P2-5 gap before first renewal | **Partly fixed** | the fallback at 954-966 and the test at 866 exist, but N3 switches the fallback off on the grant-drained path | see N3, N6 |
| P2-6 restart | **Fixed** | 1351-1384 keeps `:seen`, reports unreclaimed items, clears the stale queue | none |
| P2-7 `:seen`/`:answered` growth | **Accepted as owed** | attacker-driven paths closed; legitimate growth documented at 607-611 | none |
| P2-8 tick abort starves fact cursors | **Fixed** | 1050-1057, test 1289 | none |
| P3 unknown-state tolerance | **Routed to contract owner** | §6 | none |
| P3 universal shape checks | **Fixed** | 255-267, 352-358 | none |
| P3 assembly validation | **Partly fixed** | budget and units validated at 615-620; tolerance is not | see N4 |
| P3 vacuous purity test | **Removal is justified** | comment after test 1612 | none |
| P3 surviving-renewal test | **Accepted as restated** | the test name and comments (912-958) match; I did not read the body | none |

To become ready for commit, fix N1 through N4, each with the named failing test added first, and either fix N5 or narrow its docstring claim. N6 needs only the §6 row.
