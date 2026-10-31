Created-Local: 2026-10-13 +07
# Slice 7b: holder export, recovery v2, and driver lower

Implemented and verified in `/Users/sto/workspace/datomworld-srvm-p7b`, branch `srvm-phase7b`. No commit. JVM only; every test process ran in the foreground, sequentially, including the mutation runs. No Node, Dart, remote host, network fetch, or escalation was needed.

## Result

7b's acceptance passes. The holder exports row packs under the composition's source index, freezes recovery format 2 with one pack and holder-local metadata, and rehydrates through fenced row lowering without program IO or child initialization. Exact counters, cursor aliases/distinct cells, all parked records, authentic receiver-owned values, operation IDs and issue stamps survive recovery. The recovered record reproduces the published pack byte for byte and cannot abort into execution. Q3's link/parent and manifest/image checks refuse before attachment. The driver's validation, grant lower and restart paths use rows; `:export-version` is removed from the driver/composition.

The existing partial edit was reviewed first with `git diff`, then by production-file review and tests. Its row export/driver/recovery implementation was retained. I removed its copied legacy holder export implementation from `lift_support`, moved the five preparation tests to the row holder suite, and added a two-record parked recovery test. The legacy codec is exercised directly through its existing `handoff/export-task`; no compatibility shim or old recovery reader was added.

This is integration-branch readiness for **7b**, not readiness to land the entire phase on master. The deferred red suites are listed below.

## Files and line counts

Counts are final file lines and added/deleted lines versus this worktree's HEAD, including the retained partial edit.

| File | Final lines | Added | Deleted |
|---|---:|---:|---:|
| `src/cljc/yin/vm/ucf/compose.cljc` | 1009 | 11 | 6 |
| `src/cljc/yin/vm/ucf/holder/driver.cljc` | 2943 | 82 | 56 |
| `src/cljc/yin/vm/ucf/holder/export.cljc` | 628 | 302 | 242 |
| `src/cljc/yin/vm/ucf/rows.cljc` | 2262 | 66 | 23 |
| `test/resources/yin/vm/ucf/rows-checkpoint-v3.txt` | 946 | 306 | 277 |
| `test/yin/vm/semantic_register/safepoint_harness_test.cljc` | 1939 | 11 | 2 |
| `test/yin/vm/ucf/handoff_v2_holder_test.cljc` | 217 | 5 | 156 |
| `test/yin/vm/ucf/holder/driver_test.cljc` | 4255 | 45 | 45 |
| `test/yin/vm/ucf/holder/export_test.cljc` | 974 | 625 | 563 |
| `test/yin/vm/ucf/holder/reader_test.cljc` | 1445 | 1 | 1 |
| `test/yin/vm/ucf/holder/writer_test.cljc` | 1127 | 6 | 5 |
| `test/yin/vm/ucf/lift_support.cljc` | 454 | 3 | 21 |
| `test/yin/vm/ucf/lift_v1_test.cljc` | 471 | 2 | 138 |
| `test/yin/vm/ucf/rows_custody_test.cljc` | 315 | 40 | 0 |
| `test/yin/vm/ucf/rows_support.cljc` | 297 | 64 | 5 |

`holder/evidence_test.cljc`, `rows_test.cljc`, and `layout_test.cljc` needed no edit. The harness change only supplies complete linker-style manifest/derivation data for Q3's install fixtures; its existing assertions and root pins were not edited. The rows checkpoint resource was regenerated for the extended install responses and is verified against its renderer. Production `handoff.cljc` and the old inspector/codec implementations remain untouched.

## Implementation details that matter to review

- `holder.export/prepare` retains served answers by `[task-path resource-id]`, resolves handle aliases from the retained record, and passes `:header`, `:source-index` and `:serve-keyed` to `rows/lift-task`. `encode` reads only those answers; it never serves externally.
- Recovery format 2 contains exactly the tag, version, pack address, structural root, base64 pack, local header, and `:yin.k/state` (gate, origins, issued, ordered issues, child metadata). There is no second snapshot body, duplicate descriptor set, current lease, epoch or holder in the pack. The origin's predecessor lease remains as the specified lineage evidence.
- Rehydration validates the recovery address, pack address/grammar, root, header/custody agreement and state/tree shape before attachment. Fenced lower preserves the exact carried id counter, records resource-key/cursor-cell associations, detaches waits and every parked record, and gates every task exporting. Child construction disables `:make-stream` during administrative spawning, restoring the composition's constructor afterward.
- Q3 checks the response's `:yin.link/id` against the install parent and checks its image through the manifest-named semantic derivation record: record address, tree input and image output. It adds no fetcher, resolver or initialization path.
- The driver still mints and journals occurrences before prepare, stores recovery before the pack, and preserves the existing fence/offer/report/release brackets and crash-cut names. The source index is a required composition seam, supplied as `(fn [] {A #{tree-address}})`.
- 7a D4 is preserved: lift serves during its walk before deciding exhaustion/protection refusals. The record retains those answers, and the retry test proves no successful serve is repeated.

## Verification

All changed Clojure files were linted with `clj -M:kondo --lint <changed files>`: **0 errors, 0 warnings**. `git diff --check` is clean.

Unique successful namespace coverage (excluding duplicate focused/post-mutation runs): **514 quick tests / 8,941 assertions**, plus **5 slow tests / 3,113 assertions**, all with zero failures and errors. Each touched namespace was run using `clojure -M:test -e :slow -n <ns>` and `-i :slow`; the two support namespaces correctly select zero tests. All five holder namespaces and all eight authority namespaces were included. The rows checkpoint suite was also run in both tiers.

| Namespace | Quick tests / assertions | Slow tests / assertions |
|---|---:|---:|
| `yin.vm.semantic-register.layout-test` | 12 / 2396 | 0 / 0 |
| `yin.vm.semantic-register.safepoint-harness-test` | 23 / 1213 | 3 / 2716 |
| `yin.vm.store-write-audit-test` | 3 / 129 | 0 / 0 |
| `yin.vm.ucf.authority.admission-test` | 23 / 240 | 0 / 0 |
| `yin.vm.ucf.authority.completion-fold-test` | 8 / 50 | 0 / 0 |
| `yin.vm.ucf.authority.completion-test` | 25 / 303 | 0 / 0 |
| `yin.vm.ucf.authority.front-test` | 30 / 217 | 0 / 0 |
| `yin.vm.ucf.authority.grant-test` | 16 / 107 | 0 / 0 |
| `yin.vm.ucf.authority.inherited-test` | 18 / 112 | 0 / 0 |
| `yin.vm.ucf.authority.input-test` | 20 / 125 | 0 / 0 |
| `yin.vm.ucf.authority.reclaim-test` | 21 / 108 | 0 / 0 |
| `yin.vm.ucf.checkpoint-test` | 15 / 247 | 0 / 0 |
| `yin.vm.ucf.handoff-v2-holder-test` | 5 / 338 | 0 / 0 |
| `yin.vm.ucf.holder.driver-test` | 116 / 1524 | 1 / 389 |
| `yin.vm.ucf.holder.evidence-test` | 9 / 38 | 0 / 0 |
| `yin.vm.ucf.holder.export-test` | 36 / 325 | 0 / 0 |
| `yin.vm.ucf.holder.reader-test` | 24 / 247 | 0 / 0 |
| `yin.vm.ucf.holder.writer-test` | 25 / 202 | 0 / 0 |
| `yin.vm.ucf.ledger-fixture-test` | 6 / 53 | 0 / 0 |
| `yin.vm.ucf.lift-support` | 0 / 0 | 0 / 0 |
| `yin.vm.ucf.lift-v1-test` | 21 / 208 | 0 / 0 |
| `yin.vm.ucf.rows-checkpoint-test` | 17 / 179 | 0 / 0 |
| `yin.vm.ucf.rows-custody-test` | 12 / 127 | 0 / 0 |
| `yin.vm.ucf.rows-support` | 0 / 0 | 0 / 0 |
| `yin.vm.ucf.rows-test` | 29 / 453 | 1 / 8 |

The driver has **117 deftests total** (116 quick, one slow), as required. Rows, layout, and the safepoint harness retain their assertions; no existing pinned root was changed. The store-write audit, old checkpoint suite, old direct v1 lift tests and remaining v2 holder tests also pass. After restoring every mutant, the focused export/custody/checkpoint gate passed again: **65 tests / 631 assertions**.

Logs and machine-readable results: `target/7b-verification/`, `results.json`, `final-checks.json`, `final-lint.log`, and `mutants/results.json`. These are local ignored verification artifacts.

### Deferred red suites, observed unchanged

These quick-tier probes were run to identify the integration boundary; they are excluded from the green acceptance totals. No fixes were made in these suites.

| Namespace | Tests / assertions | Failures / errors | Follow-up |
|---|---:|---:|---|
| `yin.vm.ucf.compose-test` | 14 / 23 | 3 / 20 | 7c: row machines and source-index wiring |
| `yin.vm.ucf.compose-rows-test` | 39 / 39 | 0 / 39 | 7c: row machines, source-index and recovery-v2 assertions |
| `yin.vm.ucf.fencing-rows-test` | 4 / 61 | 29 / 2 | 7c: row lift/lower and rows authority inspector |
| `yin.vm.ucf.stage-e-rows-test` | 5 / 5 | 0 / 5 | 7d: port the world and its composition seams |
| `yin.vm.ucf.handoff-v2-authority-test` | 3 / 68 | 8 / 0 | Known 7a D1: old v2 bodies are refused by the rows-only authority |

The Stage E process suite was not run: it requires the JVM/Node peer matrix, prohibited by this brief's JVM-only/no-Node instruction. Deferred compose/Stage E slow tiers belong to 7c/7d and were not run here. `yin.repl` was not touched, per owner Q2.

## Mutations

Each mutant ran in a fresh foreground JVM against `holder.export-test` or `rows-custody-test`. Sources were copied to backups first; each mutant's diff and test log were saved. After each run, restoration was checked byte for byte, with `git diff --quiet --no-index <backup> <source>`, and by comparing the entire binary worktree diff with its pre-mutation diff. Plain `git diff --quiet` against HEAD cannot be clean in this intentionally uncommitted worktree; no-index verifies restoration without erasing the intended edit. All nine behavior-changing mutants were killed; none survived.

| Mutant | Failures / errors | Representative catching test |
|---|---:|---|
| `snapshot-body` | 1 / 0 | `the-recovery-object-is-the-pack-and-holder-local-state-test` |
| `program-execution` | 6 / 0 | `complete-export-recovery-is-fenced-and-reproducible-test` |
| `counter-off-by-one` | 64 / 4 | `the-id-counter-is-carried-exactly-test` |
| `parked-record-dropped` | 14 / 0 | `recovery-retains-every-parked-record-test` |
| `restart-abort-accepted` | 1 / 0 | `a-restarted-abort-is-refused-test` |
| `install-link-check-removed` | 4 / 0 | `the-install-response-is-verified-before-any-attachment` |
| `install-image-check-removed` | 10 / 0 | `the-install-response-is-verified-before-any-attachment` |
| `source-index-omitted` | 81 / 10 | `the-lift-takes-trees-from-the-composition-source-index-test` |
| `attach-before-custody` | 30 / 0 | `every-grant-check-refuses-before-any-attachment` |

The program-execution mutant reinstated recovery waits, removed the exporting gate and called `vm/run` during rehydration; the zero-IO assertions caught the resulting operations. The attachment-order mutant attached first and then ran custody; 30 zero-attachment assertions failed. Snapshot duplication was detected by the recovery object's exact-key assertion. The Q3 checks were removed separately, not together.

## Deviations

1. **Five old-suite preparation tests moved to `holder.export-test`.** They exercised the retired holder wrapper, not merely the old codec. The initial partial edit copied that wrapper into test support. I removed the copy and moved the tests onto real semantic-register machines; direct v0/v1 coverage remains on the old codec. `lift_support/lift` now calls the existing codec directly, and the child hold/path assertion calls the kernel-agnostic `export/enter`.
2. **Q3 required fixture completion beyond holder files.** `rows_support` and the harness's module-response builder now carry the manifest tree, derivation address and record. The row checkpoint resource therefore changes for install-containing cases. Existing row/layout root pins remain unchanged.
3. **No duplicate recovery descriptor set.** The pack already carries every stream descriptor. Rehydration derives the internal retained served table from those descriptors and administrative attachments. This follows the owner's “only holder-local engine state beside the pack” instruction and avoids persisting the same facts twice.
4. **Header validation belongs to `rehydrate-fenced`.** It compares the local recovery header with the inspected pack custody and validates `:yin.k/state` against the task tree before calling `rows/lower-task {:fenced true}`. Raw row lowering does not take a recovery envelope or implement an independent recovery-header reader.
5. **An additional all-parked-record test** covers an inactive record alongside the selected explicit park; both must survive with no IO and an identical re-encoded pack.

## Design defects / discrepancies (separate list)

- **D1, inherited from 7a:** the rows-only authority rejects old bodies. Compose/fencing/Stage E still need their planned ports, and the old v2 authority test is not repaired by those ports. The latter needs an explicit owner disposition before final phase acceptance; no version dispatch was introduced.
- **D2, inherited from 7a:** the plan's different-root/equal-baseline variant conflicts with the actual baseline, which includes the root. Driver equal variants use a different row order with the same root; different roots remain conflicts. Recovery must reproduce canonical emitted bytes, so a merely reordered pack is valid carrier syntax but inconsistent recovery. The existing semantics were preserved and tested.
- **D3, scope discrepancy:** the plan describes `lift_v1_test` as direct old-codec coverage to keep untouched, but five tests invoked the replaced holder preparation path. Keeping them unchanged would require the forbidden legacy wrapper/shim. Moving those five laws onto rows preserves their subjects without keeping the retired implementation alive.
- **D4, recovery shape discrepancy:** plan §3.2's sketch includes a separate descriptor set; the owner brief says the pack plus only holder-local state. The implemented format omits the redundant set. Document this final shape in 7e.
- **D5, known 7a lift ordering fact:** streams may already be served when a custody/protection lift refusal is returned. Accounting and retries preserve that fact; no zero-serve law was invented.

No additional unresolved production defect was found in the reviewed 7b implementation. Cross-host execution remains unverified here by instruction, not implied by the JVM results.

## What 7c needs

- Supply `:source-index` to `compose/open!` and driver configs as a function returning the composition/linker's current `{A #{tree-address}}`. Do not add a fallback resolver or synthetic index in production.
- Build source and receiver machines on semantic-register, and use rows for fork/exclusive bodies. The holder answers pack bytes and their BLAKE3 **pack address**; the structural root is distinct and stays in the baseline.
- Rebase content-store/restart assertions on recovery format 2: `:yin.k/pack-bytes`, `:yin.k/pack-address`, `:yin.k/root`, header and local state; no snapshot or duplicate descriptor set. Keep recovery-first/pack-second puts and the existing journal cut names.
- Pass grant/protection/exclusive options to row lower. Preserve the no-attachment-before-custody law. Q3 install fixtures must carry a parent-correlated link ID and the manifest-named semantic derivation/image chain.
- Use same-root/reordered-row packs for equal variants; use different-root packs only as conflicts. Preserve the accepted REPL gap until phase 8.
- Run the deferred compose/fencing suites and their slow tiers, then hand the world/source-index/recovery changes to 7d for the Stage E matrix. No files in those suites or in `yin.repl` were edited here.
