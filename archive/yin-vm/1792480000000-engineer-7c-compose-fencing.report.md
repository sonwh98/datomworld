Created-Local: 2026-10-13 +07
# Slice 7c: composition and fencing on row packs

Implemented in `/Users/sto/workspace/datomworld-srvm-p7c`, branch `srvm-phase7c`. No commit. All test lanes ran serially in the foreground, JVM only. No Node, Dart, remote host, or production-code change.

## Result

F1 and F2 were the first edits: the driver's source fence test now asserts the journal's store intents name recovery first and the pack second; Q3's install test changes only the derivation record's profile while retaining its honest input/output. Both gaps are protected by actual killed mutants.

Composition fixtures now build semantic-register receivers and source machines through `rows-support`, and every composition supplies its required `:source-index` seam. F3's smuggled holder key is `:source-index (fn [] {})`. The fresh-process plain-composition test still asserts `PHASE lifted` and `REPL-NAMESPACES []`, using a semantic-register source/receiver.

Fencing fixtures lift with a custody header and source index, and lower row packs with exclusive, protection, and grant options. Put, retained FFI, and retained link requests preserve their operation ids through park, pack, lower, and fenced retry. The original remote toy transport remains the attachment/observation seam; there is no new code resolver.

The composition crash tests now observe recovery v2 before the pack. They assert the cut object's identity and format, its presence/absence for after/before cuts, no fence or offer over incomplete storage, and stalled recovery with no attachments or second occurrence mint. Successor mutations decode and re-emit the abstract task/custody through the existing fixture helpers, preserving valid row hashes.

A second admitted encoding reverses pack row order: it has a different pack address, the same root id, and the same baseline. A counter change modifies the root and is correctly a conflicting variant. Pack addresses remain store keys and ledger variant ids; the structural root stays inside the baseline.

Two focused controls were added: the composition must consult its supplied source index and refuse an empty index; a live composed authority must refuse an unfenced fork result report without changing its projection.

## Files and line counts

Final lines and additions/deletions versus this worktree's HEAD:

| File | Lines | Added | Deleted | Tests (quick / slow) |
|---|---:|---:|---:|---:|
| `test/yin/vm/ucf/compose_test.cljc` | 1121 | 39 | 12 | 15 / 0 |
| `test/yin/vm/ucf/compose_rows_test.cljc` | 2597 | 50 | 11 | 40 / 1 |
| `test/yin/vm/ucf/fencing_rows_test.cljc` | 629 | 105 | 76 | 4 / 0 |
| `test/yin/vm/ucf/handoff_v2_authority_test.cljc` | 65 | 24 | 60 | 3 / 0 |
| `test/yin/vm/ucf/holder/driver_test.cljc` | 4261 | 6 | 0 | 116 / 1 |
| `test/yin/vm/ucf/rows_custody_test.cljc` | 318 | 3 | 0 | 12 / 0 |

This report is the only additional deliverable. The six touched suites contain 192 tests: 190 quick and 2 slow. `compose_rows_test` has 41 tests including its guarded slow case. No pins, resources, rows implementation, layout implementation, kernels, or REPL files were edited.

## Old-codec authority disposition (7a D1)

Deleted only the version-2 authority calls and their assertions from the two mixed tests. Kept all direct inspector/codec coverage, on all four existing engines: fork versus exclusive baselines, fork operation-id refusal, and halted result origin. The two mixed tests were renamed to describe their retained inspector assertions. The old inspector and codec stay untouched until phase 8; no version dispatch or compatibility shim was added. Current row authority behavior is covered by rows-custody/authority suites and the new composed unfenced-report control.

## Verification

- `clj -M:kondo --lint` all six changed `.cljc` files: **0 errors, 0 warnings** (`target/7c-lint-final.log`). `git diff --check` clean.
- Every `yin.vm.ucf.*` namespace, quick tier: `clojure -M:test -e :slow -r 'yin\.vm\.ucf\..*'`: **700 tests, 9340 assertions, 0 failures, 5 errors** (`target/7c-ucf-quick.log`). All five errors are in `yin.vm.ucf.stage-e-rows-test`; no other namespace fails. The five cases are `row-1-candidates-and-encodings-test`, `row-7-failure-lower-and-lost-authority-test`, `row-3-reclaim-test`, `fork-per-host-test`, and `row-5-replay-divergence-test`. Each currently refuses assembly because `stage_e_world` supplies no source index. Stage E process cases are tagged slow and excluded; no Node process/build ran.
- Every touched namespace was selected explicitly with `-n` in both tiers. The slow run with `DATOM_SLOW_TESTS=1` passed **2 tests, 435 assertions**, including `completion-recovery-unknown-policy-transaction-is-reconciled-once-test` (46 assertions) and the driver's existing slow journal-cut case (389 assertions), zero failures/errors (`target/7c-slow.log`).
- Final post-mutation focused quick verification, selecting the six touched suites plus rows, both safepoint harnesses, and semantic-register layout explicitly with `-n`: **260 tests, 6942 assertions, 0 failures, 0 errors** (`target/7c-final-quick.log`).
- Final post-mutation slow verification with the same explicit namespace selection and `DATOM_SLOW_TESTS=1`: **8 tests, 5010 assertions, 0 failures, 0 errors** (`target/7c-final-slow.log`). This includes the untouched rows and both harnesses as well as both touched slow cases.
- Rows, the UCF safepoint harness, semantic-register safepoint harness, and semantic-register layout are verified without edits. No root-id pin moved.

## Mutants

Each mutation was applied alone, with a `cp`-equivalent byte backup (`shutil.copy2`), saved diff, foreground JVM run, and restoration in `finally`. After each restore, `git diff --no-index --quiet <backup> <file>` and `git diff --quiet -- src` both passed. Logs, diffs, backups, and summary: `target/7c-mutants/`; reproducible driver: `target/7c-mutants.py`. Each scoped run used `-n <namespace> -v <namespace/test>` and excluded slow tests.

| Mutant | Change | Catching test | Failures / errors |
|---|---|---|---:|
| M1 | Store pack before recovery | driver `the-source-half-mints-fences-and-offers-before-candidacy-test` (F1) | 1 / 0 |
| M2 | Attach before deciding custody | rows-custody `every-grant-check-refuses-before-any-attachment` | 18 / 0 |
| M3 | Skip `accept-grant!` in row lower, through compose | compose-rows `row-2-the-source-emits-nothing-until-its-own-grant-test` | 9 / 0 |
| M4 | Fenced lower restores gate `:running` | rows-custody `an-exclusive-blocked-root-under-fenced-skips-the-grant` | 1 / 0 |
| M5 | Offer request carries wrong pack address | compose `a-whole-exclusive-handoff-runs-on-a-file-backed-authority-test` | 12 / 0 |
| M6 | Swap before/after meaning in content-store cut seam | compose-rows `the-wired-compositions-content-store-cuts-test` | 1 / 1 |
| M7 | Compose driver config ignores source index | compose `fork-runs-when-selected-and-is-labelled-fork-test` and `the-source-index-is-read-from-the-composition-test` | 6 / 0 |
| M8 | Report origin check bypassed, admitting an unfenced halted baseline | compose-rows `a-composed-authority-refuses-an-unfenced-result-report-test` | 2 / 0 |
| M9 | Q3 record-address conjunct removed | rows-custody `the-install-response-is-verified-before-any-attachment` (F2) | 2 / 0 |

All eight requested mutants and the extra F2 mutant were killed. M6 is a mutation of the test substrate's cut interpreter, not production code: its identity assertion fails before the swapped absence also causes decode to error. The first mutation attempt used `-v` without `-n`, which made the runner load unrelated Python suites and fail on missing generated lexer classes; those runs were discarded, stored under `target/7c-mutants-unscoped/`, and are not counted above.

## Deviations

1. No production seam change was necessary: existing source-index, header, grant, fenced recovery, and authority contracts support meaningful composition tests as landed.
2. Equal-baseline variant generation varies pack row order rather than task counter, because the root id is part of the baseline. Successor mismatch cases still re-emit changed tasks/custody. This follows code semantics rather than weakening authority comparisons.
3. Added the source-index and unfenced-report controls to make those mutant obligations explicit. Preserved the existing plain-composition subprocess contract.
4. Per the owner ruling, `yin.repl/main.cljc` is untouched. Its old semantic kernel means its `:custody` branch remains inert until phase 8; this is an accepted gap, not a live custody proof. The no-REPL test proves the standalone semantic-register composition.
5. No commit, including for the initial F1/F2 step, per the brief's explicit instruction.

## Design defects (separate list)

- **No new production/design mismatch found.** The root-inclusive baseline and recovery-first publication order are honored by the tests.
- **Inherited D1 disposition:** authority accepts row packs only; the old-codec authority assertions no longer describe a supported path and were removed while preserving direct old-codec coverage. Authority was not redirected to the old inspector.
- **Accepted Q2 gap:** REPL custody stays inert until its phase-8 kernel switch.
- **Existing Q3 boundary (7b F5):** install verification checks response self-consistency (parent/link id and manifest/derivation/image), while binding content to composition-known addresses remains linker's fetch/admission responsibility. No widened admission or resolver was introduced.

## What 7d needs

Port `stage_e_world` source and receiver builders to semantic-register/rows-support; supply `:source-index` to each composition; replace old body-key and pending accesses with row task/custody decoding. Use pack addresses for store and offers, root ids only inside baselines. Re-emit changed task/custody variants; vary pack row order when an equal-root alternative encoding is required. Keep recovery-v2-first crash-cut meanings and fenced lower's exporting gate.

Then run the Stage E in-process slow cases on the specified three hosts and its separate JVM/Node process matrix after building peers. None of that Stage E host/process acceptance is claimed here. Only 7d's Stage E suite remains red in the full UCF quick lane.
