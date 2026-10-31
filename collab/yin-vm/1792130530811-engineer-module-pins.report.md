Completed-GMT: 2026-10-16 06:02:10 GMT
Completed-Local: 2026-10-16 13:02:10 +07
Coding-Agent: codex
Session-ID: unavailable (not exposed by the provider in this session)

# Module pins: partial implementation; R4 completed, carrier work stopped

## Scope and stop decision

Read the task prompt, master design, complete Architect fetch threat model,
and Architect gap-list report (including Q5). No agent delegation was used.

The prompt says: "if the spec in the report is ambiguous or you need a
decision (e.g. where the host-module profile is recorded), STOP that sub-part
and report". Threat-model section 4.2 requires nil module pins to compare
export profiles recorded at lift, but specifies no carrier field/schema for
those profiles. `:yin.k/modules {host.mod nil}` cannot encode those profiles.
An architectural question was sent to the user; no answer was received during
this work. I did not invent a field or silently omit the profile check.

Parts 1 and 2 STOPPED: module-pin carrier, encoder/decoder/validator changes,
registry re-discharge, linked-address mismatch outcomes and host-profile
comparison are not implemented. Their portable tests, malformed-slot fixture,
mutants, and checkpoint re-mint are also stopped. Q5 is answered C as a design
ruling; the implementation gap remains explicitly open in the ledger.

## Part 3: R4 implemented

- Every carried install child, throughout the task tree, must match the
  receiving composition's `opts {:yin.k/admitted {name manifest-address}}`.
  Default empty, missing name, nil address, or unequal address refuses with
  `:yin.k/unsatisfied` and `:yin.k/missing {:yin.k/modules {name carried-address}}`.
- The check occurs after existing structural/dependency/custody validation and
  before `attach-streams!`, preserving existing refusal precedence and zero
  attachments on admission refusal. Receiver registry values stay as given.
- Restore drops the sender's four `:yin.admit/*` decision fields from retained
  install responses before spawning/restoring children. Consequently
  `engine/link-install` cannot copy sender admission onto the receiving entry.
  The report explicitly permits dropping this record; no receiver-record
  schema was invented. Ordinary locally admitted linker responses are unchanged;
  no engine source change was needed.
- `holder.export/rehydrate-fenced` forwards only the caller's `:yin.k/admitted`
  to the fenced lower. It does not derive admissions from recovery data.
- Existing fixture compositions explicitly admit their fixed module manifests;
  no assertions were weakened. The independent admission test checks refusal
  before attach and then resumes an admitted child to result 42 and confirms
  that its installed registry entry has no sender `:admission`. Recovery tests
  independently check missing/wrong admission with zero attaches.

## Part 4: documentation

Updated continuation section 7.2 and Q3 for R4 and recovery forwarding, and Q5
for verdict C with implementation explicitly blocked. Added R4 admission text
beside the transport install grammar and installation-family exposure rules.
UCF section 7.11 records the remaining pin work and composition fetch-loop
restrictions (never carrier-seeded name-env, trusted derivation, carrier-named
fallbacks, or requires outside the reported modules), budgets and before-grant
ordering. No VM/lower fetch loop, clock, lease, or policy interpreter was added.
Known gaps distinguish the R4 implementation pending Architect review, blocked
Q5, and out-of-scope per-run lease. Added dated I-8 partial entry to revisions.
Continuation sections 2.5/7.1/7.2's current registry behavior and UCF 7.6.4/7.6.5
carrier-completion claims were not rewritten as implemented: that work stopped.

## Fixtures and identity audit

No fixture or golden changed. In particular, rows-checkpoint-v3.txt is unchanged;
its existing render-equality test passed in the focused lane. Old -> new
addresses: none (all unchanged). No pinned A, descriptor hash, or other golden
moved. No carrier re-mint was performed because no row schema changed in this
partial slice. I-8 explicitly records that the carrier re-mint remains blocked.

## Validation

- Focused command from the prompt: 242 tests, 6311 assertions, 0 failures/errors.
- Recovery follow-up: 37 tests, 331 assertions, 0 failures/errors.
- Jing `-r '^dao\.jing\..*'`, unrestricted: 194 tests, 9771 assertions,
  0 failures/errors. Initial sandbox run had 1 loopback socket error; the
  unrestricted rerun passed without code changes.
- Kondo on every changed Clojure file: 0 errors, 0 warnings.
- `git diff --check`: clean.
- Whole JVM quick tier: 4023 tests, 249892 assertions, 0 failures/errors (unrestricted final rerun).
- The first whole quick run had 4022 tests / 249886 assertions, 9 failures,
  0 errors, all in two install-child recovery tests. These exposed the missing
  admission forwarding at the recovery API; the fix and focused follow-up above
  address them. Existing assertions were retained.
- The Python lexer was absent. `bb gen:python-antlr` initially failed under the
  sandbox's network restriction, then succeeded unrestricted using the pinned
  generator. Only build outputs were generated; no src/yang or test/yang edits.
- Node/Dart lanes were not run. The quick tier reports missing Dart-peer binary
  skips for the WS-project peer and REPL peer; no peer builds were added.

## Mutants

Executed and restored:

1. Install admission accepts name presence instead of equal address: 1 test,
   21 assertions, 6 failures, 0 errors (killed).
2. Retain sender admission fields during restore: 1 test, 21 assertions,
   2 failures, 0 errors (killed; installed-entry assertion also fails).
3. Bypass the carried-install admission gate entirely: 1 test, 21 assertions,
   12 failures, 0 errors (killed). Restored source then passed the same test:
   1 test, 21 assertions, 0 failures/errors.

Two initial mutant invocations without a namespace restriction failed during
test discovery due to the absent lexer; those are not counted as mutant kills.
The successful kill counts above are from the namespace-restricted reruns.
No carrier/receiver-module mutants are claimed for the stopped parts.

Logs: `/tmp/module-pins-focused.log`, `/tmp/module-pins-recovery.log`,
`/tmp/module-pins-jing-unrestricted.log`, `/tmp/module-pins-kondo.log`,
`/tmp/module-pins-quick-unrestricted.log` (initial failure),
`/tmp/module-pins-quick-unrestricted-final.log` (passing rerun),
`/tmp/module-pins-mutant-name-only-admission.log`,
`/tmp/module-pins-mutant-sender-admission-copy.log`,
`/tmp/module-pins-mutant-no-admission-gate.log`,
`/tmp/module-pins-restored-gate.log`, and
`/tmp/module-pins-antlr-unrestricted.log`.

## File-to-commit mapping (proposed only; nothing staged or committed)

Code:
- src/cljc/yin/vm/ucf/rows.cljc
- src/cljc/yin/vm/ucf/holder/export.cljc

Tests:
- test/yin/vm/ucf/rows_test.cljc
- test/yin/vm/ucf/rows_support.cljc
- test/yin/vm/ucf/rows_custody_test.cljc
- test/yin/vm/ucf/holder/export_test.cljc
- test/yin/vm/semantic/safepoint_harness_test.cljc

Docs:
- docs/design/yin.vm.semantic.continuation.md
- docs/design/yin.vm.ucf-transport-tuples.md
- docs/design/yin.vm.universal-continuation-format.md
- docs/design/yin.vm.linker.dht.md
- docs/design/yin.vm.ucf-revisions.md
- docs/agents/known-gaps.md

The collab report is a local review artifact, not a proposed commit file.
No staging, commits, --yolo, or changes to src/yang/test/yang were performed.
Architect review remains required before any commit.

## Remaining decision

Specify the canonical field and schema carrying host-module export profiles
for nil pins, including whether it contains all exports or the used exports.
Then implement parts 1/2, their portable tests/mutants, malformed-slot collection,
fixture re-mint and old/new addresses, and finish the corresponding design text.

BLOCKED
