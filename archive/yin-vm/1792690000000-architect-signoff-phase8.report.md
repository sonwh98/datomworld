Completed-Local: 2026-10-15 +07
Coding-Agent: claude (claude-fable-5-1), Architect
# Architect sign-off: srvm-phase8 (8d + 8d fix) and srvm-phase8ab-r2

Read-only. Scope checked: `git diff 67b98907 origin/srvm-phase8` (31 files, +341/-8399, zero `src/`), `git diff 72592bd9 origin/srvm-phase8ab-r2` (16 files), the 8d/8ab/8ab-fix2 reports, both earlier Fable reviews, the GLM 8d-fix review, the unattended handoff, design §10 item 8 and derivations §8.1-8.2.

## Method limits
- I could not run tests: `cd` into the sibling worktrees (`datomworld-srvm-p8`, `-p8ab`) and `git merge-tree` need an approval this session cannot grant. JVM/Node/Dart numbers below are the delegates' and the orchestrator's, not rerun by me.
- The 8d-fix engineer report lives only in the `srvm-p8dfix` worktree collab; reading it was also blocked. Its Dart lane result is cited in the handoff as "queued/running" (`~/ci/.../hosts-8dfix/summary.txt` on shiva); I have not seen a green Dart line for 9b4b250a.

## Invariants and preconditions
- A the only code identity, H/R derived checksums: 8ab changes only the three hash sites plus docstrings; no A pin moved (`corpus.cljc`, `rows_test.cljc`, layout, harness, `rows-checkpoint-v3.txt`, `ledger-v2.txt` byte-identical per report and absent from the diff). Holds.
- BLAKE3 for surviving hashes: six sites on `jing/blake3`, both descriptors `:hash :blake3`, `:dim/lift-to` removed, no version bump (lowering 2, register 4). Matches §10 item 8 and the amended derivations row. Holds.
- Backends kept, AST retrievable: no kernel, lowerer or `src/` deletion in either branch; `stack.cljc`/`register.cljc` untouched. Holds.
- Descriptor-hash golden in a surviving namespace: `debruijn_code_test.cljc:360` now pins `7b7c6ae9…` (was `string?`); `debruijn_register_code_test` pins `aa757659…`. Met.
- No old lowerer deleted before replacement coverage: 8ab deletes nothing; 8d deletes tests/fixtures only. Met.

## Spot-check of 12 audit mappings (bodies read at 67b98907 vs origin/srvm-phase8)
| Deleted | Survivor | Verdict |
|---|---|---|
| handoff_v2_test/a-blocked-write-retries-its-retained-value-in-every-profile | H/bare-rows row 2 `writer` (law 1 result+trace, law 2 relift) | holds |
| handoff_v2_test/link-waits-cross-in-every-profile | H/bare-rows rows 7-9 `linking` | holds |
| handoff_v2_test/an-exclusive-blocked-reader-needs-its-grant-and-then-runs | C/every-grant-check-refuses… (refusal half); the "then runs" half lives in fencing-rows `a-carried-id-crosses-park-bytes-and-lower-and-retries-fenced` | holds, audit names only half |
| handoff_test/a-halt-crosses-as-a-result-with-no-frame | H/bare-rows row 19 (halted 42) | holds |
| handoff_test/a-park-beside-ordered-waits-keeps-the-waits | H/bare-rows row 13 `parking` (park only; no reader wait beside it) | partial, see F4 |
| handoff_test/interleaved-responses-find-the-correlated-one-once | effects-test/ffi-response-validation is a `register-restore` unit test; the carrier-level scenario survives only via rows 3/24 `sent-ffi` | partial, see F4 |
| handoff_v2_values_test/a-closure-in-a-blocked-environment-round-trips | H/bare-rows rows 14, 21, 22 | holds |
| handoff_v2_holder_test/a-poisoned-receiver-behaves-as-a-clean-one | H/bare-rows lowers into a FRESH `(machine …)`; no surviving test lowers into a receiver holding its own code/store/parked/installs/wait-set | LOST, see F1 |
| handoff_v1_test/d9-blocked-and-parked-fixtures-restore-with-explicit-grants | H/bare-rows + C custody rows | holds |
| handoff_v1_test/v1-ffi-and-link-ids-and-kept-cursors | fencing-rows `retained-writes` [:put :ffi-request :link-request] with op ids | holds (better than the audit's H/bare-rows) |
| handoff_v2_layout_test/a-captured-continuation-returns-across-images-as-the-source-does | H/cross-engine-lowering-bare: `lane-rows` = rows minus link rows, so rows 14-16 (captures) run on all 6 ordered pairs | holds |
| lift_v1_test/the-version-0-wire-is-still-emitted-for-a-halted-first-export-test | R/legacy-version-headers-refuse-before-attachment (retired fact, refusal pinned) | holds |

## Integration plan
- `srvm-phase8` is based on `67b98907` and does NOT contain master `2c70bec1..72592bd9` (9 commits: L-d-1/2/3, I4-a, the edn regen). Merging `srvm-phase8ab-r2` (on `72592bd9`) therefore also merges those into the integration branch. File-level check: master-range files ∩ 8d files = ∅; 8ab files ∩ 8d files = `test/resources/yin/vm/ucf/handoff-v2.txt` only. So the plan's "one expected conflict, resolve by delete" is correct; nothing on the surviving tree references that fixture or `handoff_v2_census_test`.
- The merged tree (8d tests over I4-a's `vm.cljc`/`engine.cljc`/linker/module changes, and `int_contract_test` over the new prelude) has never been built or run. See F2.
- `dht-import-test` expected red on the integration branch until the yang.python seat regenerates `dht-import-v1.edn` at landing: acceptable and already proven green with a local regen (fix2 report). Put it in the known-gaps ledger so the phase landing gate cannot forget it.

## Findings
| id | sev | file:line | finding | fix |
|---|---|---|---|---|
| F1 | P2 | test/yin/vm/ucf/rows_test.cljc (missing test); src/cljc/yin/vm/ucf/rows.cljc:2083 | Receiver isolation has no surviving test. `restore-task` replaces :code/:store/:module-stores/:parked/:installs/:wait-set/:ready-queue but MERGES `:resources` with the receiver's and keeps any other receiver key; `lower-task`'s docstring assumes a fresh receiver, yet `holder/driver.cljc:1565` lowers into `(:receiver state)`. The audit maps the two deleted isolation tests (`a-poisoned-receiver…`, `v1-child-state-and-receiver-isolation`) to fresh-receiver tests. | One rows-test deftest: lower a pack into a receiver pre-loaded with code, store, parked, installs, wait-set and a resource; assert the task-owned fields equal a clean lower and the receiver's resource keys are not reused. Required before `srvm-phase8` merges to master (8f at latest); does not block the 8ab merge. |
| F2 | P2 | srvm-phase8 (branch) | The 8ab-r2 merge is also a master sync (I4-a and friends); 8d's tests were only ever run on `67b98907`. | Post-merge gate before dispatching 8c: jupiter quick tier on the merged `srvm-phase8`, Node + Dart on the 8d namespaces (rows, harness, frame, layout, fencing, compose, custody, checkpoint, int-contract) and the 8ab nine; `int-heap-test/cell-refusal-test` slow once on the JVM. |
| F3 | P3 | test/yin/vm/ucf/rows_test.cljc:1065 (8d fix) | `#?(:clj payload :default …)` is the two-branch form the repo rule forbids (cljd host pass has `:clj`; precedent `lift_v1_test` used `#?(:cljd m :default …)`). It only works if Dart's emit pass took `:default`; the Dart lane for 9b4b250a is the proof and I have not seen it. | Confirm the shiva summary shows Dart green for 9b4b250a and copy it to main `collab/`; or respell as `#?(:cljd (select-keys …) :cljs (select-keys …) :clj payload)`. |
| F4 | P3 | collab/1792620000000-engineer-8d.report.md audit | Two mappings are coarser than claimed: interleaved responses after lowering (noise before the correlated one, request not reissued) and a park beside a live reader wait rest on rows 3/24 and 13 by law-1 equality, not on the named successors. | Note in the known-gaps ledger; optional: a row with a park beside a pending read, and a sent-ffi row that appends noise before the correlated response. |
| F5 | P3 | srvm-phase8ab-r2 tip d3530037; docs/design/dao.jing.call-site-classification.md:41 | The tip commit edits two C4-owned test files (py/pysp pins), which the freeze note must list; the classification doc now says the DHT node id "remains SHA-256" with no pointer to the owner's BLAKE3-everywhere survey. | Add the two files to the C4 freeze note; one clause in the doc: "pending the BLAKE3 survey". |

Nothing in F1-F5 is code the slices should not have written; no revert. The earlier Fable reviews missed F1 (both sampled six mappings and took bare-rows as sufficient) and F2 (the phase8 base predates I4-a).

## Verdicts
(a) srvm-phase8 as it stands (8d + 8d fix): SIGN-OFF, scoped: F1 must be closed and F2's post-merge lanes green before `srvm-phase8` merges to master; F3's Dart evidence must be on file before 8c starts.
(b) srvm-phase8ab-r2 for merge into srvm-phase8: SIGN-OFF. Resolve the one modify/delete conflict by deleting `handoff-v2.txt`; carry `dht-import-test` as expected-red in the ledger until the landing regen; run F2's lanes on the merged tree before dispatching 8c.
