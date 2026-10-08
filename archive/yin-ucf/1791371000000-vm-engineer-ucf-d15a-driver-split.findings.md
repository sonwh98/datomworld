Completed-GMT: 2026-10-07 14:14:10 GMT
Completed-Local: 2026-10-07 21:14:10 Asia/Ho_Chi_Minh
Coding-Agent: Codex

# D15a — holder control/program split and retained positional inboxes

## Disposition

The scoped implementation is complete and the focused driver suite passes on JVM, Node and Dart. The full JVM fast lane also passes. The earlier inbox blocker is resolved by the architect's amended assembly contract in `1791374000000-architect-d15a-inbox-ruling-astra.gpt-6-astra.findings.md`, read in full from the main tree.

**Shared-worktree scope caveat:** late validation detected four modified v2 handoff test files and a new v2 fixture that this session did not edit. They were absent from the initial tracked diff. I stopped further code edits, preserved those changes, and asked whether they are parallel work. Their provenance remains unconfirmed. Consequently, the current whole-worktree diff is not D15a-only. The full JVM count below is a mixed-worktree result, not proof of an isolated D15a patch. No unrelated files were reverted.

Worktree: `/Users/sto/workspace/datomworld-d10b`; branch `ucf-d15a-driver-split`; HEAD remained `49960017`. No git writes, commits or branch changes were performed by this session.

## Files changed by this session

- `src/cljc/yin/vm/ucf/holder/driver.cljc`: four public entries, shared split scheduling, deferred grant activation, positional inbox assembly, retention/dispatch, and receipt-aware initialization/reopen.
- `src/cljc/yin/vm/ucf/holder/inbox.cljc`: new small internal helper for descriptor/result validation, portable position bounds, canonical receipt equality, deduplication, gap/identity/conflict refusal, and source reconciliation.
- `test/yin/vm/ucf/holder/driver_test.cljc`: test-world upgrade and acceptance/regression rows; all legacy driver fixtures migrated off destructive readers.
- `docs/design/yin.vm.universal-continuation-format.md`: D15a scheduling, shutdown, cadence and receipt-recovery contract.
- This findings report, overwritten with the whole task's current state.

Unrelated changes detected and left untouched:
`test/yin/vm/ucf/handoff_v2_census_test.cljc`,
`test/yin/vm/ucf/handoff_v2_holder_test.cljc`,
`test/yin/vm/ucf/handoff_v2_layout_test.cljc`,
`test/yin/vm/ucf/handoff_v2_test.cljc`, and
`test/resources/yin/vm/ucf/handoff-v2.txt`.
Their observed modification times ranged from local 20:47:29 to 20:52:07. Inspection did not establish their author. They are not claimed as D15a work.

No engine, authority, lease, export, handoff codec, or handoff-wire source was edited.

## Implementation

### Public split

`control-step` handles receipt retention and authenticated control replies, proposals, grant acceptance, renewal, cleanup, and established exit brackets. It never calls lower, attaches program resources, applies program results, executes/replays/emits/observes guest work, or prepares a source/exit export.

An accepted grant enters `:activating` with no machine. Its original dao.lease observation basis is retained; control ticks can renew it while hydration omits the program tick. `program-step` independently establishes complete authenticated evidence, the same live occurrence/lease/epoch and fresh `lease/holding?` before lower, execution and each deferred result application. Lower receives fresh evidence and a bound respecting authenticated renewal and the maximum cap, without refreshing the original basis. Unavailable evidence suspends; contradiction or expiry follows the existing run-end cleanup. Per-IO guards remain, including a new recheck between computation and replay.

`stop` is an irreversible idempotent local latch, not a gate or export abort. Program steps return unchanged once stopped. Control authenticates a late grant and releases it without lower, suppresses fresh/replacement/recovery proposals, cleans up unfinished program work, and can finish an established exit bracket. Reopen never re-establishes execution from persisted clock readings; a shutdown composition must reapply the local stop latch.

`owed-control-write?` is pure, total and Boolean. It covers proposals, pending renewal, release, offer, report and required terminal cleanup. Authenticated carriage/admission, not inbound acceptance, discharges brackets. Future renewal deadlines, passive closure visibility, uncertain journal stalls and structurally refused inboxes do not advertise executable writes.

Convenience `step` uses the same machinery in combined mode; it does not compose two complete old active steps. The existing acceptance and recovery suite remains valid without duplicate program cycles or renewal.

### Positional receipt protocol

Assembly requires `:reply-inbox` and `:outcome-inbox`, each version 1, with a stable canonical identity and non-destructive `read-at!`. Old reader-only assembly is rejected; there is no compatibility spool after destructive consumption.

Results are the closed `:record/:empty/:unavailable` union. Missing history is never converted to empty, positions must be exact portable integers, and exhaustion refuses advancement rather than wrapping.

Selection is reply-first. The driver journals the complete source lane/identity/position/author/record and local occurrence/lease/epoch association before advancing or dispatching. Journal order is the merge order; there is no second persisted ordering counter. Control dispatch may pass deferred program observations. Deferred program observations preserve selection order and are consumed once by the surviving authorized machine.

Uncertain retention appends stall both entries. Reconciliation rereads unchanged positions, deduplicates the same observation, rejects changed identity/content or gaps, and reconstructs positions and queues from the journal. Canonical byte comparison avoids host container identity determining receipt equality. Malformed later observations preserve earlier confirmed receipts and their local positions; refusal also survives post-cycle result normalization.

No VM-applied bit is persisted. Receipt recovery is not machine recovery: restarted holders release and re-enter the existing checkpoint/regrant/replay procedure. Old-binding receipts are not blindly applied to a new run, and writer/reader authentication and incarnation/request checks remain in force.

The test world now supplies complete-retention reply journals rather than reply rings. Positional adapters are reconstructed over preserved backing frames in recovery tests. This is **simulated process recovery over memory-backed journals**, not proof of durable storage hardware. D15 still owns production REPL adapters and their upstream retention guarantees.

Custody-free fork sources ignore custody inbox noise and remain unjournaled; halted custody-free machines do not acquire inbox activity from control ticks. No v0/v1 body bytes or codecs were changed.

## Acceptance coverage

All listed rows execute in the same portable driver namespace on all three hosts.

| Ruling row | Evidence |
|---|---|
| 1. Control isolation | Ready guest computation plus pending read/write remains unchanged under renewal/control ticks; no writes, observations or attachments. A real regrant prefix is not replay-applied by control. |
| 2. Mixed inbox | Real input acknowledgment is deferred, then applied once; reply/outcome selection order and deferred queues survive fresh adapter/driver reconstruction. Deferred program records do not block renewal. |
| 3. Grant while paused | No machine/attachment on control grant acceptance; activation before expiry succeeds, after expiry releases without lower; renewal preserves the original observation basis. |
| 4. Between-step tenure | Expiry, unavailable complete history and replacement/lapsed binding prevent unauthorized program progression; existing epoch and per-effect bound rows remain. |
| 5. Shutdown race | Stop before grant delivery authenticates/releases it without lower; no fresh candidacy or program call follows. |
| 6. Full-stream drain | Five-tick simulated bounded drain preserves release identity and intent-before-send; eventual acceptance finishes, continuous fullness leaves a recoverable release. |
| 7. Journal uncertainty | Before/after-visible retention cuts in both lanes and grant/renewal/release brackets stall both APIs without speculative send or activation. |
| 8. Exit continuity | Split ticks preserve offer/report/release/closure/admission order without control-side serving; terminal report answers diagnose/release, and existing suspended/quarantine/exit-cut rows remain valid. |
| 9. Cadence | Each request family, passive closure waits, terminal cleanup, authenticated renewal discharge, stopped and stalled states, and nil input are pinned. |
| 10. Compatibility | Existing driver acceptance/recovery rows pass through convenience step; split and combined schedules agree on result, input requests, attachment and observation counts. |

Additional inbox rows cover both lanes' result families, source read before retention, confirmed retention before local advancement, uncertain persisted/absent receipts, same-position deduplication, conflicting contents, changed source identity, missing history, position overflow, old-run receipt isolation, and legacy assembly refusal. Recovery adapters are reconstructed over preserved journal frames rather than wrapping old destructive readers.

## Exact validation results

### Final green runs

- JVM focused: `clojure -M:test -n yin.vm.ucf.holder.driver-test` — **104 tests, 1584 assertions, 0 failures, 0 errors**. Log: `/private/tmp/d15a-jvm-focused-final.log`.
- Node focused: shadow `compile test` with the anchored driver namespace regex — **104 tests, 1584 assertions, 0 failures, 0 errors**; **159 files, 4 compiled, 0 warnings**. Log: `/private/tmp/d15a-node-focused-final.log`. `NODE_PATH` supplied dependencies from the main tree's existing `node_modules`; that tree was read, not edited.
- Dart focused: `bb src/dev/cljd_agg.clj --only yin.vm.ucf.holder.driver-test` — **104 tests, 0 failing tests**, `All tests passed!`. Log: `/private/tmp/d15a-dart-focused-final.log`. The runner does not print assertion totals; none are claimed. Flutter cache access used approved escalation.
- Full JVM fast lane: `bb test:clj` — **3654 tests, 238191 assertions, 0 failures, 0 errors**. Log: `/private/tmp/d15a-jvm-fast-final.log`. Node REPL build and pinned Python parser generation were included. This run included unrelated v2 test changes present in the shared worktree.
- Final kondo over the three changed code/test files — **0 errors, 0 warnings**. Log: `/private/tmp/d15a-kondo-final.log`.
- Final cljstyle check over those three files — passed; **0 files requiring correction**, empty check output. Formatting fixed those three files beforehand. Logs: `/private/tmp/d15a-style-final-fix.log`, `/private/tmp/d15a-style-final-check.log`.
- `git diff --check` — passed, including the currently visible shared diff.

Fast-lane qualifications: `^:slow` tests were excluded by the existing task. The JVM log also reports host-inapplicable codec skips and skips `a-dart-dialer-reads-a-jvm-server` because `build/ws-project-peer` is absent. That optional cross-host executable was not built. No full Node or full Dart project-wide fast lane was run; the full D15a driver namespace ran on both.

Earlier green snapshots: driver **97/1456**, then **100/1561**; Node **100/1561** and Dart **100 tests**; an earlier full JVM snapshot **3647/237783**, all with zero failures/errors. These are not substituted for the final counts.

### Red evidence and iteration

- API-first compilation refused `driver/stop` before implementation; no tests executed. Log: `/private/tmp/d15a-split-api-red.log`.
- First substantive suite iteration: **90 tests, 1380 assertions, 19 failures, 3 errors**. It exposed exit release/closure sequencing and receipt integration, plus fixture migration issues (including an invalid lapse cause and old journal frame offsets). Log: `/private/tmp/d15a-driver-iteration3.log`.
- Renewed paused activation: **1 test, 4 assertions, 2 failures, 0 errors**, fixed by using renewed/capped tenure without refreshing the observation basis. Log: `/private/tmp/d15a-renewed-activation-red.log`.
- Passive-closure cadence: **1 test, 19 assertions, 1 failure, 0 errors**. Log: `/private/tmp/d15a-cadence-red.log`.
- Terminal-report cleanup cadence: **1 test, 21 assertions, 1 failure, 0 errors**. Log: `/private/tmp/d15a-terminal-cadence-red.log`.
- Partial drain retention: **1 test, 8 assertions, 4 failures, 0 errors**, fixed by retaining the latest confirmed state across subsequent source refusal. Log: `/private/tmp/d15a-partial-drain-red.log`.
- Fork inbox isolation: **1 test, 3 assertions, 2 failures, 0 errors**. Log: `/private/tmp/d15a-fork-isolation-red.log`.
- Post-cycle refusal normalization: **1 test, 4 assertions, 1 failure, 0 errors**. Log: `/private/tmp/d15a-post-cycle-refusal-red.log`.

The API and primary activation/isolation/tenure/shutdown/receipt/recovery/cadence rows were written before the split implementation. Full-stream, exit-continuity and compatibility refinements were added after the shared machinery existed; there is not an isolated preimplementation red run for every acceptance row. The focused green results cover all final rows. Intermediate syntax/lint failures were corrected; they are not presented as behavioral red evidence. Command wrappers commonly printed log tails after running tools; counts above come from the actual summaries, not from a masked shell wrapper exit code.

## Remaining concerns / incomplete work

- No unfinished D15a implementation item was identified after final focused validation. No broader D15 composition, production inbox adapter, hydration integration, bounded REPL shutdown loop, or real durable-media crash proof is claimed.
- The strict test-first-per-row process qualification above remains; it cannot be retroactively claimed away.
- The whole worktree is not scope-pure because of the unrelated v2 edits. Owner confirmation/separation is needed before treating its entire diff as this slice. Further code edits stopped at detection; the required report and already-running validation were completed without modifying those files.
- Complete inbox retention is an assembly guarantee supplied by the adapter implementation, not something descriptor validation can prove. D15 must establish it and must not silently wrap a destructive ring reader.
- No commits or git writes were made. No sandbox-blocked kondo or cljstyle result is claimed; both final checks actually ran.

## D15a gate r1 — passive candidate cadence fix

Completed-GMT: 2026-10-07 15:20:12 GMT
Completed-Local: 2026-10-07 22:20:12 Asia/Ho_Chi_Minh
Coding-Agent: Codex

### Changes in this round

- `src/cljc/yin/vm/ucf/holder/driver.cljc`: the nil-proposal arm of `owed-control-write?` excludes observed `:yin.k/not-holder` and unavailable-ledger `:no-arbitration` waits. Existing outstanding-proposal, renewal and release arms are unchanged; the query remains pure.
- `test/yin/vm/ucf/holder/driver_test.cljc`: three portable regression tests drive actual control paths: another holder owns the occurrence; a real result report and release close the occurrence permanently; ledger evidence is unavailable. Each pins proposing/nil-proposal, no proposal request, and false cadence. The held-candidate test also pins true before observing the passive wait.
- This findings file: appended this round. No other files were edited in this round. The concurrently ruled second item was not touched. Existing unrelated v2 changes and the earlier D15a diff were preserved; full-lane counts describe this mixed worktree.

### Test-first evidence and checks

- Before the implementation change: the three new tests plus existing `owed-control-write-families-test` ran **4 tests / 42 assertions / 3 failures / 0 errors**, exit 1. Only the three new false-cadence assertions failed. Log: `/private/tmp/d15a-r1-red.log`.
- After the fix: the same selection ran **4 tests / 42 assertions / 0 failures / 0 errors**, exit 0. Existing sendable-proposal, renewal and release true cases remain true. Log: `/private/tmp/d15a-r1-green.log`.
- Focused JVM driver suite: `clojure -M:test -n yin.vm.ucf.holder.driver-test` — **107 tests / 1605 assertions / 0 failures / 0 errors**, exit 0. Log: `/private/tmp/d15a-r1-focused.log`.
- Full JVM fast lane: `NODE_PATH=/Users/sto/workspace/datomworld/node_modules bb test:clj` — **3657 tests / 238212 assertions / 0 failures / 0 errors**, exit 0. Log: `/private/tmp/d15a-r1-fast.log`. Slow tests remain excluded; five host-inapplicable CBOR conformance cases were skipped, and the Dart-dialer/JVM-server row was skipped because `build/ws-project-peer` is absent.
- Kondo on the two changed code files: **0 errors / 0 warnings**. Cljstyle initially found one new indentation mismatch; it was corrected with apply_patch, and the final two-file check exited 0. `git diff --check` passed. No sandbox-blocked check is claimed.
- Node and Dart focused lanes were not rerun in this narrow JVM-requested round; the preceding whole-task results above remain historical, not new evidence.

### Remaining scope

The requested mechanical gate fix is complete. The second item remains explicitly outside this round; no conclusion about it is claimed. Prior whole-task limitations and production-adapter prerequisites still apply. No git writes were made.

## D15a fix round 2 — unavailable inboxes suspend program, not custody

Completed-GMT: 2026-10-07 15:45:58 GMT
Completed-Local: 2026-10-07 22:45:58 Asia/Ho_Chi_Minh
Coding-Agent: Codex

The governing `1791379000000-architect-d15a-inbox-unavailable-ruling-astra.gpt-6-astra.findings.md` was read in full from the main tree. Its ruled behavior is implemented; the round-1 cadence correction remains covered.

### Changed files and behavior

- `src/cljc/yin/vm/ucf/holder/driver.cljc`: active holders now validate binding/tenure and schedule due or pending renewals despite either inbox's availability obstruction. Outcome obstruction still permits authenticated renewal replies. Reply obstruction preserves the identical pending request and its original pre-send reading; inbound acceptance does not change the renewal counter or holder/bound. Unavailability returns before program application, execution, activation or export preparation; established prepared exit control brackets remain eligible, and their ordinary OK answers do not replace the unavailable diagnostic.
- The combined entry scans and progresses custody while awaiting activation, without lowering while obstructed. Successful scans clear the obstruction. Existing lease/holding? checks, including the before-and-after renewal acknowledgment checks, prevent late carriage from reviving an expired run. Expiry uses the existing one-diagnostic, ended-gate, durable cleanup-release flow; cleanup retries do not require continued tenure. Malformed/conflicting evidence and uncertain journal acceptance remain hard progression barriers.
- Reopening a journaled holder may recover cleanup when the retained inbox source is unavailable. The canonical receipts remain retained, with runtime-only pending reconciliation; they are not dispatched until source identity/content reconciliation succeeds. A contradictory reread refuses progression. This exception is limited to journaled-holder recovery, not candidate/source recovery, and never reconstructs execution or tenure. Journal format and authority/lease semantics are unchanged.
- `owed-control-write?` excludes an unavailable nil-proposal wait; eligible pending renewal/release still answers true. No clock or IO was added to this query.
- `test/yin/vm/ucf/holder/driver_test.cljc`: nine new portable tests cover the four ruled regressions under split and combined scheduling, plus paused activation, missing ledger/malformed-inbox exclusion, conflicting receipt-source recovery, uncertain cleanup-intent append, and cadence. Tests use real judge/front carriage and bounded lease media; reconstruction over memory-backed journal frames is explicitly simulated process recovery, not a durable-media claim.
- `docs/design/yin.vm.universal-continuation-format.md`: records the ruled availability/renewal/cleanup/recovery semantics and cadence distinction.
- This findings file: appended this round. These four files are the only files edited in this round; earlier D15a changes and unrelated v2 changes were preserved. No Jing, authority, lease, kernel, front or handoff-wire changes were made.

### Red and green evidence

- The four main regression tests were added before the implementation patch. JVM red: **4 tests / 71 assertions / 24 failures / 1 error**, exit 1. Renewal sends/credits were absent; reopening cleanup threw `:inbox-unavailable`. Log: `/private/tmp/d15a-r2-red.log`.
- After the custody/recovery patch, those same four tests: **4 tests / 134 assertions / 0 failures / 0 errors**, exit 0. More assertions execute because cleanup reconstruction no longer throws. Log: `/private/tmp/d15a-r2-green1.log`.
- The added cadence row preceded its predicate patch: **1 test / 4 assertions / 1 failure / 0 errors**, exit 1. The unavailable nil-proposal candidate incorrectly answered true. Log: `/private/tmp/d15a-r2-cadence-red.log`. The final focused suites cover its green result.
- The additional activation/evidence/corruption/uncertainty safety tests were added after the main patch; no isolated preimplementation red is claimed for those supplementary rows. An intermediate focused green was **115 tests / 1808 assertions**, before the final cadence and attachment-count assertions.

### Final validation

- JVM driver: `clojure -M:test -n yin.vm.ucf.holder.driver-test` — **116 tests / 1816 assertions / 0 failures / 0 errors**, exit 0. Log: `/private/tmp/d15a-r2-focused-final.log`.
- Node driver: shadow `compile test` with anchored driver namespace selection and the main tree's existing node_modules supplied via NODE_PATH — **116 tests / 1816 assertions / 0 failures / 0 errors**, exit 0; **159 files / 4 compiled / 0 warnings**. Log: `/private/tmp/d15a-r2-node-final.log`.
- Dart driver: `bb src/dev/cljd_agg.clj --only yin.vm.ucf.holder.driver-test` — **116 tests, all passed**, exit 0. No assertion total is printed. Compilation printed **14 dynamic-member warnings** in dependency namespaces, not a clean-warning result. Log: `/private/tmp/d15a-r2-dart-final.log`.
- Full JVM fast lane: `NODE_PATH=/Users/sto/workspace/datomworld/node_modules bb test:clj` — **3666 tests / 238423 assertions / 0 failures / 0 errors**, exit 0. Log: `/private/tmp/d15a-r2-fast-final.log`. This includes the mixed worktree's pre-existing v2 tests. Slow tests remain excluded; five host-inapplicable CBOR conformance cases and the Dart-dialer/JVM-server row with missing `build/ws-project-peer` were skipped as reported by the runner.
- Kondo on the two changed code files: **0 errors / 0 warnings**, exit 0. Log: `/private/tmp/d15a-r2-kondo-final.log`. Final cljstyle check on both files exited 0 after correcting new formatting mismatches; log: `/private/tmp/d15a-r2-style-final.log`. `git diff --check` passed. No sandbox-blocked check is claimed; approved escalation enabled full-lane loopback tests and Dart SDK cache access.

### Remaining concerns / incomplete work

No unfinished implementation item from this round's ruling was identified. Full project-wide Node/Dart fast lanes were not run; the complete driver namespace ran on both hosts. Production positional adapters and real durable-media crash proof remain D15/composition work, as previously qualified. Prior whole-task test-first qualifications remain; this round's precise red evidence is recorded above. No git writes were made.

## D15a gate r2 fix — candidates with an unavailable inbox, and late grants after stop

Completed-GMT: 2026-10-07 17:11:15 GMT
Completed-Local: 2026-10-08 00:11:15 Asia/Ho_Chi_Minh
Coding-Agent: Claude Opus 5.5

The blocker from gate r2: a candidate in `:proposing` whose inbox is unavailable sent nothing, but `owed-control-write?` still answered true for its uncarried proposal, because only the `:not-holder` and `:no-arbitration` passive waits were excluded. D15's `moved?` would busy-loop on it. Two related gaps: `step-proposing` returned before the ledger read while the inbox was unavailable, so a stopped candidate never saw a late grant and never released it; and `propose` still resent an uncarried proposal after `stop`. The fix applies the ruling's rule to candidates: unavailability suspends program progress and new activation, and passive waits owe no write.

### Changes (driver.cljc and driver_test.cljc only)

- `propose`: a stopped candidate or an unavailable inbox sends nothing. A stopped candidate with nil or refused proposal still answers `:yin.k/ended {:cause :stopped}`; otherwise the outstanding proposal waits passively, its status unchanged, until a scan succeeds (unavailable) or a late grant arrives (stopped).
- `step-proposing`: returns early only on `:inbox-refused?`, as the holder phases do. An unavailable inbox no longer stops the ledger read, so grant observation (control progress that needs no inbox) goes on. A late grant to a stopped candidate goes through `accept` as before: the binding and evidence are checked, the grant ack is journaled, then `releasing` with `{:cause :stopped}`. There is no lower and no attach.
- `accept` stale-own-grant arm: a stopped candidate answers `:ended {:cause :stopped}` and does not propose again (before this, it kept the answered proposal and `propose` resent it).
- `advance` `:proposing` under `:combined`: does not `activate` an `:activating` result while the inbox is unavailable. The grant is held, and only lowering waits, as in the holder's paused-activation path.
- `owed-control-write?`: in the `:proposing` arm, `:stopped?` and `:inbox-unavailable?` now exclude both the nil-proposal and the outstanding-proposal branches. Renewal and release arms are unchanged, so a stopped candidate's release, once opened, still answers true until carried.

### Tests (written first)

- `unavailable-inbox-candidate-waits-to-propose-test` (split and combined × reply and outcome lane): a fresh candidate with an unavailable inbox mints no proposal and owes nothing. A candidate with a pending uncarried proposal owes nothing over two obstructed ticks, sends no second request, and keeps the same proposal and the `:inbox-unavailable` detail. Once the inbox is restored it resends the identical request (2 requests, 1 distinct, proposals stays 1) and owes again. Zero attaches.
- `stopped-candidate-releases-a-late-grant-test` (inbox available, reply-unavailable, outcome-unavailable): stop after the proposal is sent and before carriage. The stopped tick owes nothing and sends no second proposal. Then the front carries and the judge grants. The next control step releases with `{:cause :stopped}` and owes until carriage, and the cleanup ends `:failed` with `:dao.lease/released true` (in the unavailable rows, once the inbox is restored). Exactly 1 proposal and 1 release request; no machine, zero attaches, zero observations.
- `unavailable-inbox-control-cadence-test`: two pure rows were added. An unavailable inbox and a stopped candidate, each with an uncarried proposal, answer false.

### Evidence

- Red (new tests before the fix): `clojure -M:test -n yin.vm.ucf.holder.driver-test -e :slow`: **118 tests / 1912 assertions / 28 failures / 0 errors**, all in the new rows (owed true on the pending and stopped states, no release on the late grant, re-proposal).
- Green, focused JVM driver suite (same command): **118 tests / 1912 assertions / 0 failures / 0 errors**.
- Full JVM fast lane: `clojure -M:test -e :slow` (the `bb test:clj` task body; `bb` is not on this shell's PATH): **3668 tests / 238519 assertions / 0 failures / 0 errors**, exit 0. Log: `collab/d15a-fix-r1-jvm.log` in this worktree. Skips as before: five host-inapplicable CBOR conformance cases and the Dart-dialer/JVM-server row (`build/ws-project-peer` absent).
- Kondo on the two files: **0 errors / 0 warnings**. `git diff --check` on the two files: clean. cljstyle was **not run** (the command needed an approval this session did not have). Node and Dart lanes were not run this round.

### Remaining concerns

- The gate r2 log in the main tree could not be read from this session (permission). The fix follows the brief's statement of the blocker.
- A non-stopped candidate with an unavailable inbox now accepts a grant into `:activating`, whereas before it waited. That is custody progress, and renewals then go on as in the holder's paused-activation row. Lowering still waits for a successful scan.
- No files other than driver.cljc, driver_test.cljc and this report were edited. No git writes were made.
