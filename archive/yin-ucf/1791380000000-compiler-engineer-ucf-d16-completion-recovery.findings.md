Completed-GMT: 2026-10-08 20:44:52 GMT
Completed-Local: 2026-10-09 03:44:52 Asia/Ho_Chi_Minh
Coding-Agent: Codex

# D16-completion-recovery

## Disposition

Implemented the prospective production repair specified by the architect's
1791380000000 ruling. The requested focused suites pass on JVM, Node and Dart.
The full JVM fast lane completed with **0 failures and 1 error**, caused by an
unrelated golden fixture absent from both this worktree and HEAD. This is not
a claim that the wider D16 gate is ready. See coverage qualifications below.

## Production changes

- `src/cljc/yin/vm/ucf/authority/completion.cljc`: an eligible accepted report
  now completes on either release or policy lapse. The existing live binding,
  report, quarantine, epoch and successor-uniqueness checks remain; explicitly
  reject an already-closed occurrence. The common grant lapse transaction is
  `[lapse, occurrence-completed, exact successor-or-terminal edge, occurrence-reclaimed]`.
- `src/cljc/yin/vm/ucf/ledger.cljc`: closure validation accepts release or
  policy but still requires the matching lapse in this same transaction's
  `::unepoched` set. Edge validation is unchanged. The fold keeps the actual
  policy cause, accepted report, exact closing lease and edge, epoch advancement
  and removal of the live binding. Standalone historical repairs remain invalid.
- `src/cljc/yin/vm/ucf/authority/grant.cljc`: document the extended common
  lapse/reopen contract. No separate policy transaction implementation was
  necessary: the existing lapse builder already inserts `completion/closure`
  between lapse and reclaim. Repeated reopen does not reclaim a closed lease again.
- `src/cljc/yin/vm/ucf/holder/driver.cljc`: recovered exits inspect and validate
  the authoritative closure before dispatching release replies or retrying
  release. Extract, without loosening, the exact lease/body/successor-or-result
  predicate. An exact closure finishes a terminal exit or drives the same
  successor offer until admitted. Old release observations remain journal history;
  recovery does not newly mark them carried after finding policy completion.
  Missing evidence suspends. Quarantine, exhaustion and reported historical
  reclaim without closure end diagnostically. A reclaimed origin without an
  accepted report returns to checkpoint candidacy, not speculative successor execution.
- `docs/design/yin.vm.universal-continuation-format.md` and
  `docs/design/yin.vm.linker.dht.md`: specify policy completion, prospective-only
  recovery, exact closure matching, and the corrected offer-before-report order
  with admission allowed to answer awaiting-completion.

No Jing, handoff codec, kernel, authority ownership or successor-admission
contract was changed. No policy history was rewritten as release; no second
lapse or synthetic release acknowledgment was introduced.

## Tests and the eight ruling rows

The file-backed composition fixtures exercise simulated process recovery,
not physical power-loss guarantees. The following rows ran on all three hosts:

1. **Accepted report, before release:** strengthen the existing report-crash
   row with the exact policy transaction, preserved cause/report/closing lease,
   one epoch advancement, successor admission and no recovery release. Refine
   its cut to the authority's accepted report, before any inbound release;
   a progress-journal report acknowledgment was already too late for that cut.
2. **Release appended, before authoritative lapse:** the other original red
   crash row now completes on policy reopen with the same exit and successor
   assertions, and without newly fabricating a release acknowledgment.
3. **Closure/unknown transaction acceptance:** retain the closure-crash row;
   add file-backed cuts before and after actual policy-transaction storage.
   Refused open exposes no usable composition. Fresh inspection sees all four
   facts or none; eventual reopen and another reopen leave one edge and epoch 1.
4. **Terminal report:** add a real composition recovery row pinning the exact
   result edge, exit without successor offer, and no release requirement.
5. **Abandonment:** add real composition cases for no report, a durably stored
   intent interrupted before send, and a queued but uncommitted report. None
   closes the origin or admits the speculative successor; the original
   checkpoint re-enters candidacy and can obtain a new grant.
6. **Validation negatives:** add real composition mismatches for origin lease,
   body address and successor, plus quarantine and old reported policy history
   without closure. Preserve/extend fold negatives for standalone closure,
   duplicate/wrong edges and exhaustion. The supported lower authority epoch
   bound tests policy exhaustion for both continuation and terminal reports.
   **Qualification:** exhaustion uses the production authority's bounded test
   option, not a file-backed `compose/open!` boundary run; standalone/duplicate
   edge rejection is pinned at the fold. Thus exhaustive composition-only
   coverage of every negative subcase in row 6 remains additional coverage.
7. **Ordinary release:** existing release completion tests remain passing.
   Former policy-as-abandonment fixtures now use silence to retain their true
   orphan/ancestry negative purpose; eligible policy reports have new positive pins.
8. **Isolation:** recovered exits retain no runnable machine; a program-observer
   spy remains at zero through unavailable-ledger suspension and eventual exit.
   A transport-error proxy is installed before source assembly, rather than
   assuming closing a transport erases already-retained ledger evidence.

Test files changed this round:

- `test/yin/vm/ucf/authority/completion_test.cljc`
- `test/yin/vm/ucf/authority/completion_fold_test.cljc`
- `test/yin/vm/ucf/authority/inherited_test.cljc`
- `test/yin/vm/ucf/compose_rows_test.cljc`

The worktree already contained the untracked 20-test composition-row file and
the shared-fixture exposure diff in `test/yin/vm/ucf/compose_test.cljc`.
This round extends the former to 27 tests and does not edit the latter.
The inherited collab plan/audit files were also left unchanged.

## Red and green evidence

Before the production patch:

| Run | Tests | Assertions | Failures | Errors | Log |
| --- | ---: | ---: | ---: | ---: | --- |
| Policy authority/fold and the two existing crash rows | 4 | 47 | 16 | 0 | `/private/tmp/d16-completion-red1.log` |
| Corrected six new recovery rows | 6 | 120 | 21 | 0 | `/private/tmp/d16-completion-red-final.log` |

The abandonment cases already passed before the patch; they are not claimed
as new red behavior. Supplementary exact-mismatch and bounded safety tests were
added after the main implementation: their isolated green was 2 tests,
63 assertions, 0 failures/errors, not an isolated preimplementation red.
The refined pre-release report cut separately passed 1 test/18 assertions.

Intermediate fixture/selector/local-renaming mistakes were corrected, not
classified as intended behavioral reds. The first Dart attempt executed
0 tests and had 8 shard-load errors: a conditional test assertion compiled as
a void-valued let binding. An explicit nil fixture return fixed it; the final
Dart run below executed all 346 tests. No production semantics were changed
to accommodate that host compile issue.

## Final validation

The focused selection contains driver, compose, compose-rows, ledger,
ledger-fixture and all authority namespaces: 14 namespaces in each host lane.
Lanes ran sequentially.

| Check | Exact final outcome | Evidence |
| --- | --- | --- |
| Focused JVM | **350 tests, 4,014 assertions; 0 failures, 0 errors; exit 0** | `/private/tmp/d16-completion-focused-final2.log` |
| Focused Node | **346 tests, 3,978 assertions; 0 failures, 0 errors; exit 0** | `/private/tmp/d16-completion-node-final2.log` |
| Focused Dart | **346 tests, all passed; exit 0**; runner does not print assertion totals | `/private/tmp/d16-completion-dart-final2.log` |
| Full JVM fast | **3,874 tests, 242,652 assertions; 0 failures, 1 error; exit 1** | `/private/tmp/d16-completion-jvm-fast-final.log` |
| clj-kondo | Eight edited code/test files; **0 errors, 0 warnings; exit 0** | `/private/tmp/d16-completion-kondo-final2.log` |
| cljstyle | Eight edited code/test files; **exit 0**, no differences | `/private/tmp/d16-completion-style-final2.log` |
| `git diff --check` | **exit 0** | Rechecked after final validation |

Commands:

- JVM focused: `clojure -M:test -r '^yin\.vm\.ucf\.(holder\.driver|compose|compose-rows|ledger|ledger-fixture|authority(\..*)?)-test$'`
- Node focused: `NODE_PATH=/Users/sto/workspace/datomworld/node_modules clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.ucf\\.(holder\\.driver|compose|compose-rows|ledger|ledger-fixture|authority(\\..*)?)-test$"}'`
- Dart focused: `bb src/dev/cljd_agg.clj --only` with the same 14 namespaces explicitly listed; the full invocation/output is identified by the Dart log above.
- JVM fast: `NODE_PATH=/Users/sto/workspace/datomworld/node_modules bb test:clj`.

The final Node build reported 183 files, 3 recompiled, 0 warnings. Dart emitted
14 dynamic-warning diagnostics during compilation, then passed all tests.
The full JVM lane logged seven explicit skips: five host-inapplicable CBOR
cases and two missing optional Dart peer executables. Its normal fast selector
also excludes slow-tagged tests; no full slow lane was claimed.

## Unresolved concerns and incomplete work

- Full JVM error: `yin.vm.ucf.handoff-v2-census-test/a-version-2-body-is-the-same-bytes-on-every-host`
  throws `FileNotFoundException` for `test/resources/yin/vm/ucf/handoff-v2.txt`.
  A read-only `git cat-file -e HEAD:test/resources/yin/vm/ucf/handoff-v2.txt`
  also failed with exit 128. No fixture was invented/copied and no out-of-scope
  v2 test was weakened. Consequently a green full JVM lane is not established.
- Row 6's strict composition-only negative coverage qualification is explicit
  above; no literal traversal of the production maximum epoch was attempted.
- Historical reported policy-reclaim ledgers without closure intentionally end
  diagnostically. A migration/retroactive closure repair remains unauthorized.
- This named repair does not discharge other incomplete D16 gate audit rows.

No git writes, commits or branch changes were performed. Apart from this
requested findings report, edits stayed inside the ruled production/test/doc
scope; inherited worktree changes were preserved.
