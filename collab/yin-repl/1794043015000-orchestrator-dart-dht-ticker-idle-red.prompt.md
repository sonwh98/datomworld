Created-GMT: 2026-11-07 09:56:55 GMT
Created-Local: 2026-11-07 16:56:55 +07
Coding-Agent: qwen
Session-ID: dcdea7bd-3fab-4a64-b823-7e8f5b124f8f

# Task: fix the dart-side DHT ticker-idle red (yin.repl, master-green critical path)

Role: Engineer (implementation + verification)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-07 16:56:55 +07 | Status: active | Rationale: implementation round on the qwen lane (Token Plan, available per routing-status 2026-11-07 board)

## The failure (identified 2026-11-07, reproduction evidence retained)

Dart lane, branch `nightly-reds-fix` @ `4f772d61`, jupiter clone
`~/ci/datomworld-nightly-dart`. Scoped run with `DATOM_SLOW_TESTS=1
DATOM_FAIL_FAST=1 bb src/dev/cljd_agg.clj --only …` (log
`/tmp/nrf-dart-scoped.log` on jupiter; the run may still be finishing —
read its final tally). Exactly ONE failure out of the six scoped namespaces:

- Test: `yin.repl.dht-test/with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles`
  (source `test/yin/repl/dht_test.cljc:2304`, ^:slow via dao.test-slow/guard)
- Assertion at the generated Dart `test/cljd-out/yin/repl/dht-test_test.dart`
  (cljd line 31613):
  `Expected: (some false? (drop-while false? (:moved idle)))` — after the
  leading `false`s, the idle curve must eventually report `false` again
  ("between repair cycles the ticker returns to its idle cadence").
  Actual: EVERY entry `true` — the ticker never returns to its idle cadence;
  something keeps reporting "moved" on every tick for the whole window.

## Your job

1. DIAGNOSE FIRST, and the first question is REGRESSION OR PRE-EXISTING:
   - `git checkout --detach 8d0a4ffb` in the jupiter dart clone
     (`~/ci/datomworld-nightly-dart`; fetch from origin first — its origin is
     GitHub, and branch nightly-reds-fix is pushed), then run the single
     namespace: `DATOM_SLOW_TESTS=1 bb src/dev/cljd_agg.clj --only yin.repl.dht-test`
     (login-shell mise env first, like the failing run). If it FAILS there too,
     the red is pre-existing (likely one of the nightly's 7 uncaptured dart
     failures) — fix it on the branch as a forward fix all the same.
   - If it PASSES there, the red is a regression of fix 5 (`4f772d61`,
     `:publish-opts {:branching-factor 64}` at `src/cljc/yin/repl/dht.cljc:349-358`)
     and your fix must respect that fix's intent (REPL index pages below the
     64 KiB DHT store-message bound) while restoring the ticker's idle cadence.
2. Diagnose why `:moved` stays true forever with peers that never accept:
   read `src/cljc/yin/repl/dht.cljc` (the ticker/idle-cadence machinery and the
   `:moved` flag producers) and the test body at `test/yin/repl/dht_test.cljc:2306`
   onward. Hypotheses to check in order: (a) fix 5's smaller branching factor
   makes every round "move" something where 512-key pages moved nothing; (b) the
   64-key cap interacts with the repair-cycle length so the window in the test
   no longer contains an idle cycle.
3. FIX it on branch `nightly-reds-fix` (this is a follow-up on the master-green
   critical path; the branch is checked out on the laptop working tree — check
   `git status` first and do not touch unrelated untracked files). Preserve
   fix 5's message bound. Do not weaken the test: if the test's expectation
   itself needs adjusting to the new page geometry, explain why in the report
   and change ONLY what the invariant requires.
4. VERIFY per the scoped ruling (owner 2026-11-07): run ONLY the failing test
   until green — dart: `DATOM_SLOW_TESTS=1 bb src/dev/cljd_agg.clj --only
   yin.repl.dht-test` on the jupiter clone (NOT the laptop; owner rule: no slow
   tests locally). Also run `yin.repl.dht-test/rounds-larger-than-the-pending-write-bound-are-acknowledged-never-busy`
   green (it pins fix 5; it ran green on node at 4f772d61). Commit nothing —
   the orchestrator handles staging/commit authority; leave the diff in the
   working tree and report `git diff --stat`.

Constraints: work only in `src/cljc/yin/repl/dht.cljc` and
`test/yin/repl/dht_test.cljc` (plus scratch state on jupiter). Do not touch
`collab/`, do not run suite-scale tests anywhere, do not weaken tests, do not
push. If the fix demands an interface change beyond these two files, STOP and
report instead of expanding scope.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: regression-or-preexisting verdict with the evidence command and tally,
the root cause, the changed files and diff --stat, exact verification commands
with outcomes, and any incomplete work. Do not claim runs that did not occur.

## SUPPLEMENT (2026-11-07 17:2x +07, before first execution reached depth) — the regression verdict is SETTLED

The orchestrator reproduced locally on the laptop (owner authorized the slow test):
master @ 3d2f20db (NO fix 5), dart `--only yin.repl.dht-test`:
- `with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles` PASSES
- `rounds-larger-than-the-pending-write-bound-are-acknowledged-never-busy` FAILS
  (the pre-fix 512-key-page bug, as expected without fix 5)
On the branch @ 4f772d61 (jupiter): ticker-idle FAILS, write-bound PASSES.
Therefore the ticker-idle red IS a regression of fix 5
(`:publish-opts {:branching-factor 64}`). Skip step 1's 8d0a4ffb check entirely;
go straight to diagnosing why the 64-key geometry keeps `:moved` true forever
(check hypotheses (a) and (b) in the original brief). A supplementary QA brief
from the owner exists at
collab/general/1794033000000-qa-orchestrator-nightly-reds-20261106.brief.md —
reconcile with it; its JVM-lane dht red (dht_test.cljc:2105 ack timings) is a
DIFFERENT seat's item, not yours; your surface is the dart ticker-idle only.

- Status-Event: 2026-11-07 17:25 +07 | Model: qwen3.8-max | Status: superseded | Rationale: dispatch stopped pre-execution to inject the settled regression verdict; fresh session re-dispatched with this addendum
