Created-Local: 2026-10-14 +07
# Slice 7d: Stage E over semantic-register row packs

Worktree `/Users/sto/workspace/datomworld-srvm-p7d`, branch `srvm-phase7d`. No commit. JVM verification only; commands ran serially in the foreground, with logs under `target/7d-*`. No production code, REPL, old codec, row grammar, layout, or harness change remains.

## Result

Stage E's source and receiver machines now come from `rows-support`: changing the world’s support require ports both `parked-reader-over` and `reading-writer` to the semantic-register kernel without duplicating their AST builders. Both composition opens supply the function `:source-index (fn [] @s/source-index)`, including the exclusive-refusal control in the fork scenario. Code and trees take the existing composition/linker seam.

`offer-variant!` reverses the pack’s row vector, retains its root and custody baseline, computes its BLAKE3 pack address, stores it and offers it through the holder’s attributed inbound. Occurrence discovery uses the rows inspector. Row 1 now explicitly compares the two encodings’ complete inspected baselines and distinct pack addresses, as well as asserting two admitted variants and exactly one running holder. A missing winner reaches assertion failures instead of throwing from `val nil`.

Snapshot `:fences` already reads the addressed stored bytes; those objects are now packs, so its existing BLAKE3 digests retain their meaning. New `:packs` observations inspect the stored pack, check sorted row ids, and decode/re-emit it on the current host. Every reported final or named snapshot compares that host’s re-emitted canonical byte digest to the actual fenced pack digest. This is a portable Row 10 assertion: on the process matrix, receiver hosts re-emit bytes produced by source hosts. It excludes the intentionally unsorted alternative encoding, which is offered directly rather than fenced.

Every life with a requested cut must report killed and exactly one event naming that cut. This common runner assertion covers both isolated runtimes and the unchanged process transport. Existing checks for unfenced exports, successor eligibility, input replay/divergence, operation-id assignment, reclaim, lost authority, and fork labels remain.

7c-review F1 is added beside the journal-substrate test: absent and non-function (`{}`) source indexes refuse at open in both fork and exclusive modes. F2's version-2 authority docstring now names `authority.grant-test`, `authority.completion-test`, and `compose-rows-test`.

## Files and line counts

Final line counts and additions/deletions against this worktree’s HEAD:

| File | Lines | Added | Deleted |
|---|---:|---:|---:|
| `test/yin/vm/ucf/stage_e_world.cljc` | 1410 | 26 | 8 |
| `test/yin/vm/ucf/stage_e_scenarios.cljc` | 1326 | 20 | 1 |
| `test/yin/vm/ucf/compose_test.cljc` | 1139 | 18 | 0 |
| `test/yin/vm/ucf/handoff_v2_authority_test.cljc` | 66 | 2 | 1 |
| This report | 88 | — | — |

The Stage E rows suite has 13 deftests (5 quick, 8 slow); the process suite has 13 slow deftests. `stage_e_peer`, `stage_e_rows_test`, and `stage_e_process_test` need no lasting edit. The Node peer build sets `yin.vm.ucf.stage-e-peer/main` on the `:vm-bench` node-script target; it has no old fixture namespace allowlist or regexp to update. Its transitive world require picks up `rows-support`, rows, and the rows inspector.

## Verification

| Gate | Tests | Assertions | Failures / errors | Log |
|---|---:|---:|---:|---|
| Initial touched quick run, Stage E rows + compose | 21 | 237 | 0 / 0 | `target/7d-quick.log` |
| Stage E rows slow (`DATOM_SLOW_TESTS=1`, `-i :slow`) | 8 | 632 | 0 / 0 | `target/7d-stage-e-slow.log` |
| Every `yin.vm.ucf.*` quick (`-e :slow -r 'yin\.vm\.ucf\..*'`) | 701 | 9452 | 0 / 0 | `target/7d-ucf-quick.log` |
| Every UCF slow except the process matrix | 13 | 2926 | 0 / 0 | `target/7d-ucf-slow.log` |
| Final touched quick + unchanged rows, semantic-register harness and layout | 88 | 4383 | 0 / 0 | `target/7d-final-quick.log` |
| Unchanged semantic-register harness/layout slow | 3 | 2716 | 0 / 0 | `target/7d-harness-slow.log` |
| Named process handoff gate, restricted to JVM/JVM | 1 | 26 | 0 / 0 | `target/7d-process-gate.log` |

Lint over all four changed source files: 0 errors, 0 warnings (`target/7d-lint.log`). Final `git diff --check` and `git diff --quiet -- src` pass. No red test observed in the tested UCF quick/slow namespaces. The process matrix is unverified, not claimed green.

The process gate ran `the-cross-host-handoff-across-processes-test` with its pair vector temporarily changed to `[[:jvm :jvm]]`, exactly as the brief permits. `target/7d-process-gate.py` backs up the file, runs the named test, restores it in `finally`, byte-compares the backup and verifies `git diff --quiet` on that file. The durability record contains `[:row-7 nil]`, pair `[:jvm :jvm]`, two distinct peer pids. This clean-handoff gate requests no crash cut; it is not evidence of a real SIGKILL run. The unchanged process harness blocks the peer on a cut and calls `destroyForcibly`; the orchestrator must run the killed lives on its full matrix.

## Mutants

Driver: `target/7d-mutants.py`. Artifacts: `target/7d-mutants/<name>/` (byte backups, actual diff, test log), plus summaries. Each mutant ran alone, in the foreground, against one named JVM deftest. Backups are restored in `finally`; each restore runs `git diff --no-index --quiet` against its backup and `git diff --quiet -- src`. All seven requested mutants were caught; an additional mutant checks F1.

| Mutant | Change | Catching test | Failures / errors |
|---|---|---|---:|
| M1 | Omit the driver’s durable fence record while advancing the world’s export step | Stage E `row-1-candidates-and-encodings-test` | 0 / 1 |
| M2 | Offer a re-emitted task with id-counter slot 4 changed to 9, and make grant variant comparison ignore `:yin.k/root` | Stage E `row-1-candidates-and-encodings-test` | 8 / 0 |
| M3 | Call the successor’s offer step where its fence step must run | Stage E `row-7-failure-lower-and-lost-authority-test` | 0 / 1 |
| M4 | Skip destruction of the permanently lost authority | Stage E `row-7-failure-lower-and-lost-authority-test` | 3 / 0 |
| M5 | Make `:row7/while-running`’s cut predicate never match | Stage E `row-7-failure-lower-and-lost-authority-test` | 3 / 0 |
| M6 | Skip the origin check, accepting an unfenced result report | `compose-rows-test/a-composed-authority-refuses-an-unfenced-result-report-test` | 2 / 0 |
| M7 | Report an exclusive status-policy for the per-host fork | Stage E `fork-per-host-test` | 1 / 0 |
| M8 (extra) | Remove compose’s open-time function check for `:source-index` | `compose-test/a-source-index-is-checked-at-open-test` | 4 / 0 |

M1 and M3 fail with scenario errors because the required durable checkpoint is absent or an offer is attempted without valid bytes. M2 initially failed with `val nil` when no winner ran; the row-1 check was made nil-safe and the mutant rerun, so it now exposes the failed custody assertions directly. The M2 mutation changes both the world encoding and the authority admission comparison; it does not change the ledger fold, which independently enforces baseline consistency. M6 uses the existing composition authority control from 7c, not a newly invented Stage E scenario. M7 injects a divergent fork observation into the common per-host check; it is a JVM execution of that portable check, not cross-host runtime evidence.

## Not run

- Node or Dart in-process Stage E, including their slow bodies and compiler checks.
- Node peer compilation/execution, `npm ci`, or the four-pair process matrix on shiva/jupiter. The brief delegates these to the orchestrator; no remote checkout or push was attempted, and no commit was made.
- The other 12 process deftests, including real SIGKILL cuts. The local process check above is the restricted clean JVM/JVM gate only.

Portability was checked by reading: the additions are ordinary `.cljc` data/sequence operations, no `for`, no cross-namespace private vars, no added host-specific types, no new catches, no unary float minus or EDN string traps. Runtime portability remains unverified until the orchestrator’s Node/Dart runs.

## Deviations and design defects

1. **Source index at composition, not holder.** The brief/plan says `holder-seams` supplies `:source-index`, but current `compose/check-holder-config-keys!` explicitly rejects that key in a holder config. The code wins: both `compose/open!` configs supply it, and compose forwards it to the driver. No holder seam, resolver, or compatibility workaround was added.
2. **Inherited 7a D2 / 7c F3 remains a design-text defect.** An id-counter change changes the root; the baseline includes that root, so this is `:variant-conflict`. Row 1 uses the owner-ruled equal-root reversed-row encoding. `rows/decode` accepts an unsorted pack while `emit` produces sorted rows. No semantic rule was changed to make the stale plan true.
3. **The plan’s suite inventory is stale.** Section 1.1 says 12 deftests in each Stage E suite; the current files have 13 each (rows: 5 quick, 8 slow). No suite was removed to match the inventory.
4. **No new production defect found.** Recovery v2 first, pack second, fence before publication, pack address as storage/ledger id, root as semantic identity, and custody state outside body lease/epoch/holder remain as implemented. Row 5’s pinned gap-marker commitment remains unchanged pending the existing Architect ruling. The accepted REPL custody gap remains untouched.

## What 7e needs to state

- Stage E sources/receivers use only semantic-register machines; de Bruijn kernels remain cross-engine-law backends, not Stage E participants.
- Code is identified by A, with trees supplied by the composition’s function source-index seam; holder configs cannot override it.
- Row-1 equal-baseline variants have the same root with another row order. Counter changes conflict. Sorted canonical emission gives one pack for a root; readers still accept the unsorted alternative.
- Recovery version 2 is stored before the whole pack object; the journal fences both addresses before any source/successor publication. Existing store/body cut names refer to those two object puts.
- Pack BLAKE3 address is the store key, offer variant id, grant checkpoint and report result; `:yin.k/root` is the baseline’s structural identity. Fork custody slot is nil; lease/epoch/holder remain outside the body.
- Update UCF/continuation citations to the rows inspector and current custody/fenced lower stages, plus F2’s actual covering suites.
- Separate local evidence from tri-host acceptance: all local JVM rows tests and the clean JVM/JVM process gate pass, but Node/Dart and the killed four-pair matrix must still be reported before phase 7 integration goes to master. The REPL’s accepted custody gap is not closed by this slice.
