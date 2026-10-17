Created-Local: 2026-10-14 04:30 +07
Author: claude-fable-5-1 (Architect, plan only)
Branch: srvm-phase8-plan at 627272d7 (= srvm-phase7)
Vocabulary: UCF §7.5.3 (cursor, cursor cell, cursor-ref, cell)

# Phase 8 (cutover) plan

## Summary for the owner (15 lines)

1. Phase 8 is not one rename. It is four deletions (old semantic kernel, old v3 lowering/stack table, three old de Bruijn lowerers, old transport) plus one rename plus one hash-algorithm move, and they are coupled through 56 files that still build the old kernel.
2. The rename is NOT drop-in: the old `yin.vm.semantic` exports `vm-load-program` (58 call sites) and `load-image` (11), which the new kernel does not have; the new kernel exports `load-ast`/`load-vector` with different argument shapes. Every one of the 56 requirers is edited by hand.
3. Ten of those 56 are C4-track files under `test/yang/python/antlr/` (the other orchestrator's). The cutover cannot land without editing them. Owner must rule who edits them and when (Q1).
4. The old transport (`handoff.cljc` 3783 lines, `checkpoint.cljc`, versions 0/1/2, 141 deftests) has no production requirer; only tests and two Python-track tests use it. It can die in one slice.
5. The surviving de Bruijn code is larger than the design implies: `debruijn-code`, `debruijn-register-code`, `debruijn-register-effects`, `debruijn.cljc` (projection + `resolve-name`) and both kernels stay. Only `-resolve`, `-linearize`, `-register-compile` die.
6. BLAKE3: 6 production sites move (two `descriptor-hash`, `image-hash`, `register-hash`, `dimension-hash`, `node-hash`); the descriptors also carry a literal `:hash :sha256` row, so the "differs only by the removed `:dim/lift-to` row" check in derivations §8.1 must be amended to allow that second row change (Q4).
7. Pinned constants that move: 11 in `derive_test`, 1 descriptor hash in `debruijn_register_code_test`, 2 in `linker_test`, 6 in `debruijn_test`, 6 in `stack_parity_test`, 2 C4 manifest goldens, plus every pinned address in `corpus.cljc` (32) and `rows_test` (20) only if A changes (it must not; that is a gate).
8. The descriptor-hash golden for H has NOT yet moved to a surviving namespace (`debruijn_code_test.cljc:368` still asserts only `string?`). That precondition of §10 item 8 is open and is slice 8a's first task.
9. Fixtures from 7a/7b (`rows-checkpoint-v3.txt`, `ledger-v2.txt`) carry A addresses and no H/R and no format keyword; they do not regenerate unless A moves. `checkpoint-v1.txt`, `handoff-v2.txt`, `scalars-v1.txt` die with the old transport.
10. Seven slices, 8a-8g. Estimated codex implementation 11-15 days plus 2-4 review rounds each; wall clock 4-5 weeks with two slices running concurrently where the dependency table allows.
11. Irreversible order: regenerate goldens and move the oracle (8a) before deleting the old lowerers (8e); switch the production pipeline (8c) before deleting; delete the old kernel (8f) last, after the C4 suite is green over new images.
12. Rollback is by branch: each slice is its own branch off `srvm-phase8`; nothing in `master` moves until the whole phase passes the slow tier and the C4 suite. Partial landing is refused.
13. The biggest risk is coordination, not code: a mass rename of 56 files and 131 textual sites collides with any uncommitted C4 branch that touches `linked_harness.cljc` or the e2e tests.
14. Documentation surface: 13 design docs cite `yin.vm.semantic-register` (127 occurrences), 25 files cite `yin.vm.semantic.md`, the old design doc itself (836 lines) needs a fate ruling (Q6).
15. Verdict at the end: NOT READY, blocked on six owner questions (Q1-Q6); everything else is decided by the rulings already in force.

---

## 1. Inventory

All paths are relative to the worktree root. Line numbers are at 627272d7.

### 1.1 Namespaces to delete

| Namespace | File | Lines | Production requirers (src/) | Test requirers | Verdict |
|---|---|---|---|---|---|
| `yin.vm.semantic` (old kernel) | `src/cljc/yin/vm/semantic.cljc` | 1058 | `yin.repl` (`repl.cljc:41`), `datomworld.demo.continuation-handoff` (`continuation_handoff.cljc:24`), `src/cljs/datomworld/demo/continuation_stream.cljs`, `src/cljd/yin/register_bench_cljd.cljd` | 52 files (list in §1.3) | delete; name taken by the new kernel |
| `yin.vm.linearize` (v3 lowering, `lower-rows`, `lower-ast`, `rows-loader`, `ast-loader`) | `src/cljc/yin/vm/linearize.cljc` | 502 | `yin.repl` (`:28`, `:224`), `yin.vm.ledger` (`ledger.cljc:204` `lower-rows` in the derivation check), `yin.vm.linker.publish` (`publish.cljc:14, :159`), the two demos, the Dart bench | 47 files | delete; `ledger.cljc:194-204` is rewritten to check `sr-linearize/project-rows` |
| `yin.vm.code` (v3 stack table) | `src/cljc/yin/vm/code.cljc` | 467 | `linker.cljc:45` (28 `code/` call sites, mostly `semantic-format` scanners), `ledger.cljc` (`code/well-formed-vector?` `:194`), `completion.cljc:206, :256` (`project-segment-qualified`), `semantic.cljc`, `ucf.cljc:32` (require only, docstring use), `publish.cljc:8` (`materialize-vector!`), `handoff.cljc:57`, `debruijn_code.cljc`, `debruijn_linearize.cljc` | 10 files | delete the table and `semantic-format`; `completion.cljc`'s two uses need a `sr-code` equivalent or the completion namespace moves to the v4 table (see Q5). `materialize-vector!` moves to `sr-code` or `yin.vm` |
| `yin.vm.debruijn-resolve` | `src/cljc/yin/vm/debruijn_resolve.cljc` | 624 | `linker.cljc:52` (`relowered-hashes` `:2460`), the two other old lowerers | `test_utils.cljc:24` (`resolved-of` `:200-210`), `debruijn_resolve_test`, `debruijn_register_contract_test`, `debruijn_register_benchmark_test`, `derive_test` | delete after 8a |
| `yin.vm.debruijn-linearize` | `src/cljc/yin/vm/debruijn_linearize.cljc` | 422 | `repl.cljc:28, :232`, `linker.cljc:49, :2171, :2462`, `publish.cljc:10, :161` | 37 files (every test that builds a stack kernel through `dl/adapt`; the 10 C4 files among them) | delete after 8c |
| `yin.vm.debruijn-register-compile` | `src/cljc/yin/vm/debruijn_register_compile.cljc` | 840 | `repl.cljc:30, :237`, `linker.cljc:51, :2175, :2464`, `publish.cljc:12, :162` | 36 files | delete after 8c |
| `yin.vm.ucf.handoff` (old transport, versions 0/1/2, `export-task` `:1392`, `resume-task` `:3778`, `codec-versions` `:3253`) | `src/cljc/yin/vm/ucf/handoff.cljc` | 3783 | none | 15 files: `handoff_test`, `handoff_v1_test`, `handoff_v2_{authority,census,custody,gates,holder,layout,test,validity,values,walker}`, `checkpoint_test`, `lift_v1_test`, `scalar_round_trip_test`, `ucf/safepoint_harness_test`, `lift_support`, `v2_support`; C4: `int_heap_test.cljc:241` (`export-task`), `int_contract_test.cljc:483` (`resume-task`) | delete (Q2) |
| `yin.vm.ucf.checkpoint` (old inspector, `inspect` `:510`, `v2-version` `:58`) | `src/cljc/yin/vm/ucf/checkpoint.cljc` | 541 | `handoff.cljc:69` only | `checkpoint_test`, `lift_v1_test`, `handoff_v2_authority_test` | delete with handoff; `yin.vm.ucf.rows.checkpoint` is the surviving inspector (required by holder, authority, export) |

Test-support namespaces that die with them: `test/yin/vm/ucf/lift_support.cljc` (454 lines; requires old `semantic`, `linearize`, `handoff`), `test/yin/vm/ucf/v2_support.cljc` (569 lines; requires all of the above plus `dl`/`rc`), `test/yin/vm/ucf/checkpoint_fixtures.cljc` (reads `checkpoint-v1.txt`). **Hazard:** three NEW tests borrow from them: `semantic_register/safepoint_harness_test`, `semantic_register/frame_test` (both require `lift-support` and `v2-support`), `semantic_register/layout_test` (`v2-support`), and `test/yin/vm/ucf/rows_support.cljc` requires `lift-support`. Slice 8d must first move the borrowed helpers (stream/ring construction, `new-machine`, program fixtures) into `rows_support.cljc` or a new `yin.vm.semantic-register.support`, then delete.

Fixtures that die: `test/resources/yin/vm/ucf/checkpoint-v1.txt` (524 hashes), `handoff-v2.txt` (163), `scalars-v1.txt` (96) and its minter `scalars-v1.mint.clj`. Fixtures that stay: `rows-checkpoint-v3.txt` (838 hashes, A addresses only, written 7a/7b), `ledger-v2.txt` (21). Neither contains the format keyword or an H/R (checked by hex grep for `semantic-register` and `semantic/code`: 0 hits in all five files).

### 1.2 Namespaces that survive (contrary to a naive reading of "delete the de Bruijn code")

| Namespace | Why it stays | Evidence |
|---|---|---|
| `yin.vm.debruijn-code` (`image-hash`, `encode-scalar`, `encode-image`, descriptor) | computes H; kernels, layout, derive, handoff-replacement all require it | required by `debruijn/stack.cljc`, `semantic_register/layout.cljc:35`, `semantic_register/derive.cljc`, `linker.cljc:48`, `publish.cljc:9`, `repl.cljc:27` |
| `yin.vm.debruijn-register-code` (`register-hash`, descriptor) | computes R | `debruijn/register.cljc`, `layout.cljc:36`, `derive.cljc`, `linker.cljc:50`, `publish.cljc:11` |
| `yin.vm.debruijn-register-effects` | kernel dependency | `debruijn/register.cljc`, `handoff.cljc:62` |
| `yin.vm.debruijn` (1641 lines; projection D5 and `resolve-name` `:617`) | `pipeline.cljc:39` uses `project-datoms`, `projected->datoms`, `frame-datoms`, `exception-diagnostic`; `derive.cljc:33` and `scan.cljc:37` use `resolve-name` | survives whole; its `dimension-hash` (`:957`) and `node-hash` (`:1020`) are SHA-256 and MOVE to BLAKE3 (owner: surviving hashes move) |
| `yin.vm.debruijn.stack`, `yin.vm.debruijn.register` | backends, owner ruling 2026-10-13 | 45 and 39 requirers |

The `encode-scalar` hazard: `debruijn_register_code.cljc:231, :234, :245, :258` call `debruijn-code/encode-scalar`, and its descriptor names it as data (`:201` `:scalar-encoder :yin.debruijn-code/encode-scalar`). Because `debruijn-code` survives, the hazard is not a dangling reference; it is that **`encode-scalar` is tested only in `debruijn_code_test.cljc` (`:120-276`), which also tests the `:dim/lift-to` row (`:364-367`)**. Slice 8a edits that test in place (drop the two lift-to assertions, pin the descriptor hash) and must not delete it.

### 1.3 Namespace to rename: `yin.vm.semantic-register` to `yin.vm.semantic`

Files: `src/cljc/yin/vm/semantic_register.cljc` (490 lines) plus `semantic_register/{analysis,code,derive,frame,layout,linearize,scan}.cljc`. Tests: `test/yin/vm/semantic_register/` (16 files) move to `test/yin/vm/semantic/`.

Counts (grep at 627272d7):

| Where | textual `yin.vm.semantic-register*` occurrences | files |
|---|---|---|
| src/ + test/ | 131 | 33 |
| docs/ | 127 | 13 (`universal-continuation-format.md` 20, `continuation.md` 24, `evaluator.md` 21, `derivations.md` 17, `ucf-transport-tuples.md` 10, `semantic-register-vm.md` 8, `code-as-tuples.md` 6, `linker.md` 4, `orchestrator-log.md` 4, `cursor-cell-numbering.md` 3, `debruijn.stack.md` 1, `debruijn.register.md` 1, `debruijn.targets.md` 1) |
| keyword `:yin.semantic-register/code` | 11 | `semantic_register.cljc:410, :446, :456`, `linker.cljc:719`, `rows.cljc:2118`, `repl/serve_test.cljc:450`, `linker_test.cljc:564`, `semantic_register/link_test.cljc:80, :127, :128, :157` |
| alias `:as sr` | 11 | tests |

Old-name requirers that must be rewritten to the new API (56 files; `semantic/create-vm` 92 sites, `semantic/vm-load-program` 58, `semantic/load-vector` 44, `semantic/load-image` 11, `semantic/attach-image` 5, `semantic/semantic-restore` 2):

- src (4): `yin/repl.cljc`, `datomworld/demo/continuation_handoff.cljc`, `src/cljs/datomworld/demo/continuation_stream.cljs`, `src/cljd/yin/register_bench_cljd.cljd`.
- yin.vm tests (34): `vm_test`, `attach_image_test`, `cell_test`, `completion_test`, `continuation_invoke_test`, `data_test`, `debruijn/register_test`, `debruijn/stack_effects_test`, `debruijn/stack_parity_test`, `effect_forgery_test`, `engine_gate_test`, `ffi_test`, `ffi/remote_serve/responder_test`, `heap_reclamation_test`, `host_typed_values_test`, `integer_test`, `linearize_test` (dies), `linker_manifest_test`, `linker_require_test`, `linker_step_test`, `linker_test`, `linker/dht_test`, `linker/publish_test`, `linker/remote_transfer_test`, `mint_test`, `rule_r_test`, `semantic_engine_test`, `semantic_ffi_test`, `semantic_stream_observer_test`, `semantic_test`, `stream_poll_test`, `telemetry_test`, `test_utils` (`:168` `rows-loader semantic/load-vector`), `ucf_test`, `ucf/handoff_test` (dies), `ucf/handoff_v1_test` (dies), `ucf/lift_support` (dies), `ucf/remote_test`, `ucf/v2_support` (dies).
- other (3): `bench/yin_vm_bench`, `datomworld/demo/continuation_handoff_test`, `yin/repl/dht_process_test.clj`.
- **C4 track (10, owner question Q1):** `yang/python/antlr/{e2e_c2_test.clj, e2e_test.clj, float_address_test, int_contract_test, int_heap_test, int_literal_test, int_ops_test, linked_harness, prelude_parity_test, safepoint_test}` and `linked_harness.cljc:105-120` which binds `:semantic` to `linearize/ast-loader semantic/vm-load-program` and `:stack`/`:register` to `dl/adapt`/`rc/adapt`.

API mapping the rewrite uses (new names at `semantic_register.cljc`): `create-vm` `:350` (same option map), `load-vector` `:303` (`[machine v stamp]`, v4 vector), `attach-image` `:314`, `load-ast` `:322` (`[machine datoms stamp]`, projects through `sr-linearize/project-datoms`). There is no `vm-load-program` (the old datom-batch loader) and no `load-image`; `semantic-restore` is `register-restore` `:101`. So the 58 `vm-load-program` sites become `load-ast` over `vm/ast->datoms`, or `load-vector` over `(:vector (sr-linearize/project ast))`.

Mechanics (recommendation in §2, Q3): `git mv` the eight source files and 16 test files in one commit with no content change except the `ns` forms and require sites, so the rename is reviewable as pure moves; the API rewrite of the 56 requirers is a separate commit in the same slice.

### 1.4 Every pinned H, R, descriptor hash and A address

| Constant | File:line | What it pins | Phase-8 action |
|---|---|---|---|
| 1 H + 1 R pair | `semantic_register/derive_test.cljc:240` | §6.3 row 6 image hashes of the new lowerer | regenerate (descriptor + BLAKE3) |
| 10 R hashes | `derive_test.cljc:419-428` | `register-goldens` | regenerate |
| 1 R descriptor hash `7eacba41…` | `debruijn_register_code_test.cljc:7` (already moved at 5b; duplicate at `debruijn_register_compile_test.cljc:446` dies) | `rcode/descriptor-hash` | regenerate |
| H descriptor hash | `debruijn_code_test.cljc:368` is still `(is (string? dc/descriptor-hash))` | NOT pinned | **pin at 8a before anything else** (§10 item 8 precondition) |
| H `52791d4a…`, R `c0aefe2f…` | `linker_test.cljc:522-531` (`pinned-identities-are-host-independent`), helpers `stack-image`/`register-image` `:253-260` use `dl/adapt`/`rc/adapt` | the one host-independence golden | helpers switch to `derive`, both strings regenerate |
| 12 R hashes/images | `debruijn_register_compile_test.cljc:449-620` | old lowerer | deleted, not regenerated (§8.1) |
| 6 node/dimension hashes | `debruijn_test.cljc:136, :933, :1300-1306` | projection `dimension-hash`, node hashes (SHA-256) | regenerate under BLAKE3 (surviving hashes move) |
| 6 image hashes + 2 hex images | `debruijn/stack_parity_test.cljc:321-347` | old `lower-stack` output and H of corpus rows (incl. `52791d4a…`) | the images are the old lowerer's; either regenerate from `derive/stack-image` (if bytes equal) or delete the byte pins and keep behavioural parity (recommend: regenerate, and the test's producer becomes `derive`) |
| `manifest-golden` `b9dbaeff…` | `yang/python/antlr/linked_prelude_test.cljc:223-235` | py manifest address (JVM golden) | regenerate (C4 file, Q1) |
| `pysp-manifest-golden` `0971cc1e…` | `linked_safepoint_test.cljc:240-247` | pysp manifest | regenerate (C4 file, Q1) |
| 5 + 1 segment addresses | `int_literal_test.cljc` (5), `float_address_test.cljc:227-269` (1 + 4 fragments) | A addresses of Python literals under the OLD v3 vector? To verify at 8f: if these are tree or v4 addresses they hold; if v3 vector addresses they die with v3 | verify, regenerate only if they are v3 (C4 files, Q1) |
| 32 A addresses | `semantic_register/corpus.cljc` | v4 vector addresses | must NOT change; a gate, not a regeneration |
| 20 addresses | `ucf/rows_test.cljc` | pack roots / A | must NOT change |
| 2 | `yang/safepoint_test.cljc` | to verify | as above |
| scanner goldens | `semantic_register/scanner_goldens.cljc` (7231 lines, v3 answers at 993d5411) | historical evidence | stays; the live comparisons in `scan_test.cljc:65-67, :188, :277-300` against `old-linearize`/`old-code` are deleted, `footprint-mnemonics-is-the-v3-table-minus-push` `:261-264` (`code/mnemonics`) is rewritten to compare against the pinned v3 mnemonic set as data |
| manifests' `:yin.module/index` | `publish.cljc:189` `{h h-address r r-address}` | | new shape `{:yin.debruijn.code h-addr :yin.debruijn.register r-addr}` (derivations §8.1) |

Fixtures that would regenerate only if A moves: `rows-checkpoint-v3.txt`, `ledger-v2.txt` (both 7a/7b). Gate: `(= (:vector (sr-linearize/project ast)) <before>)` is unaffected by phase 8 because the v4 vector, `sr-code/contract "v4"` and `jing/segment-key` are untouched; the rename changes no hashed content (the keyword `:yin.semantic-register/code` is not in any pack: hex grep 0 hits).

### 1.5 The old transport paths and who exercises them

| Path | Location | Exercised by | Phase 8 |
|---|---|---|---|
| version 0/1/2 codec, `codec-versions` | `handoff.cljc:3253-3310`, `v2-body?` `:1993` | `handoff_test` (24), `handoff_v1_test` (27), `handoff_v2_*` (44 across 10 files), `lift_v1_test` (21), `scalar_round_trip_test` (2) | delete (Q2) |
| `export-task`/`resume-task` | `handoff.cljc:1392, :3778` | the above + `int_heap_test.cljc:241`, `int_contract_test.cljc:483` (C4) | delete; the two C4 calls move to the holder path (`yin.vm.ucf.holder.export`/`driver`) |
| old inspector `checkpoint/inspect` | `checkpoint.cljc:510`, `inspect-body` `:476` | `checkpoint_test` (15), `handoff_v2_authority_test`, `lift_v1_test` | delete; `rows/checkpoint.cljc` is the inspector |
| old harness | `test/yin/vm/ucf/safepoint_harness_test.cljc` (8 deftests, over `handoff`) | | delete; `semantic_register/safepoint_harness_test.cljc` (26 deftests) is the harness |
| `ucf/contract-stamp` `{"v3", version 0}` and `ucf/profiles` (version-2 registry) | `ucf.cljc:47-68` | `ucf_test.cljc:164-354` (14 sites over `vm/semantic-contract`) | `contract-stamp` becomes the rows profile (`rows.cljc:36`), `profiles` deleted with version 2; `ucf_test` rewritten to v4 vectors |
| `vm/semantic-contract "v3"` | `vm.cljc:266-269`; users `linker.cljc:706`, `ledger.cljc:49`, `completion.cljc:714`, `ucf.cljc:47, :59, :266`, `publish.cljc:174`, `scan.cljc:76` (footprint-table key), `vm.cljc:1755` (footprint table) | | becomes `"v4"` (= `sr-code/contract`); the footprint table is re-keyed; the manifest contracts map moves `"v3"` to `"v4"` (§8.1) |

Deftest counts at stake: old-kernel direct tests 91 (`semantic_test`, `semantic_engine_test`, `semantic_ffi_test`, `semantic_stream_observer_test`, `linearize_test`, `code_test`); old lowerer tests 85 (`debruijn_resolve_test`, `debruijn_linearize_test`, `debruijn_register_compile_test`, `debruijn_vm_contract_test`, `debruijn_register_contract_test`, `debruijn_register_benchmark_test`); old transport tests 141. Total suite today 4574 deftests. Of the 91, `semantic_engine_test`, `semantic_ffi_test`, `semantic_stream_observer_test` are engine-level tests parameterised over the old kernel and should be **ported** to the new kernel, not deleted (coverage audit in 8f). `debruijn_vm_contract_test` (B0 normaliser) and `debruijn_register_contract_test` (R0 descriptor/address law) pin contract facts, not lowerer behaviour: port the descriptor and normaliser assertions to `debruijn_code_test`/`debruijn_register_code_test`, delete the lowerer legs.

### 1.6 SHA-256 sites that move to BLAKE3

Production (all in `src/cljc/yin/vm/`, found by `grep -rn -iE 'sha-?256' src`):

| Site | Survives? | Moves? |
|---|---|---|
| `debruijn_code.cljc:548` `descriptor-hash`, `:564` `image-hash` (H) | yes | yes |
| `debruijn_register_code.cljc:258` `descriptor-hash`, `:269` `register-hash` (R) | yes | yes |
| `debruijn.cljc:966` `dimension-hash`, `:1028` `node-hash` (projection) | yes (pipeline D5) | yes |
| descriptor rows `:hash :sha256` at `debruijn_code.cljc:138`, `debruijn_register_code.cljc:200`, `debruijn.cljc:139-140` (and the `:node-hash` prose string) | yes | yes: becomes `:hash :blake3`; this is a second descriptor row change beside `:dim/lift-to` (Q4) |
| docstrings `debruijn_code.cljc:552-560`, `debruijn_register_code.cljc:262`, `debruijn.cljc:1021` | | rewrite |
| `dao/jing.cljc` `sha256`, `sha256-bytes`, registry `:sha256` | yes, explicitly mintable registry member (owner) | no |
| `dao/jing/content/step.cljc:288` `{:algorithm :sha256}` option | yes | no |

Tests mentioning SHA-256 inside yin.vm (counts): `linker_test` 8, `engine_test` 4, `completion_test` 3, `debruijn_test` 2, `malformed_rows` 2, and 1 each in `vm_test`, `debruijn_register_benchmark_test` (dies), `debruijn_register_compile_test` (dies), `semantic_test` (dies), `ucf/checkpoint_test` (dies), `ucf/rows_checkpoint_test`, `linker/head_follow_test`. Each must be read: most mint a `sha256` address deliberately to test the registry seam and stay; those that compute H/R by hand move. `dao.*` tests (jing, btree, space) are out of scope.

No `jing/digest-string :sha256` call exists in yin.vm; the move is six `jing/sha256` to `jing/blake3` edits plus descriptor rows plus prose.

### 1.7 Switch points

| Switch | Today | Phase 8 |
|---|---|---|
| `linker/relowered` | `linker.cljc:2159-2177`: `vm/semantic-bytecode->ast` then `linearize/adapt` / `register-compile/adapt` | `(derive/stack-image (:vector (sr-linearize/project-rows tree)))`, `(derive/register-image …)`; `verified-policy-outcome` `:2233` `(= :yin.semantic/code format-kw)` keeps its branch |
| `relowered-hashes`, `verify-same-root-pairing`, `fallback-fetch` | `linker.cljc:2457-2565` (old H/R request paths: `resolve/resolve` then `lower-stack`/`lower-register` from source datoms) | these are the "old H/R request paths" of §10 item 8: delete `relowered-hashes` and the `resolve` require; `verify-same-root-pairing` recomputes from A: `(derive/* (:vector (sr-linearize/project-datoms source-datoms)))`. Tests: `linker_test.cljc:1969-1980`, `linker_manifest_test.cljc:740-743` |
| `checked-derivation` | `linker.cljc:2252-2262`: record input must be the tree | de Bruijn records: input is A (the semantic derivation's output); semantic record unchanged (§7.6) |
| `implemented-profiles` | `linker.cljc:952-958`; profile maps `:930-948` keep `"stack-lowering"`/`"register-lowering"` (ruling 2) | docstrings only |
| `semantic-format` / `semantic-register-format` | `linker.cljc:695-731`; `interpreter.cljc:84-93` holds 7 records; `serve_test.cljc:446-452` asserts 7 | one record `semantic-format` with `:format :yin.semantic/code :contract "v4"` and the v4 scanners; `interpreter/formats` holds 6; `serve_test` asserts 6 |
| `publish-closure!` | `publish.cljc:115-213`: `linearize/lower-rows` for the semantic vector, `dl/adapt`/`rc/adapt` on named datoms for H/R, `derive-record tree-address output` for all three, index `{h h-addr r r-addr}` `:189` | `sem-vector` from `sr-linearize/project-rows tree`; `h-image`/`r-image` from `derive` over that vector; records `A -> H`, `A -> R` (`:yin.ledger/input` = `sem`); index `{:yin.debruijn.code h-addr :yin.debruijn.register r-addr}`; contracts `"v4"` |
| `address-attribute`/`index-from-datoms` | `linker.cljc:965-990` | re-minted; keyed by format |
| REPL kernel table | `repl.cljc:143-146` `vm-constructors` (`semantic/create-vm`, `stack/create-vm`, `register/create-vm`), `:224-240` `program-loaders` (`linearize/rows-loader semantic/load-vector`; `debruijn-linearize/adapt`, `register-compile/adapt` over `packet->named-datoms`) | `:semantic` to the new `create-vm` and `load-vector` over `(:vector (sr-linearize/project-rows (macro/packet->row-set packet)))`; `:stack`/`:register` to `derive/stack-image`/`register-image` over the same vector; `packet->named-datoms` `:150-156` deleted; closes the REPL custody gap (UCF §7.11 note at `universal-continuation-format.md:3347`) |
| `ledger/lowering-profile` and the derivation check | `ledger.cljc:40-50` (`"ast-to-bytecode"`, contract `"v3"`), `:194-204` (`code/well-formed-vector?`, `linearize/lower-rows`) | contract `"v4"`; check uses `sr-code/well-formed?` and `sr-linearize/project-rows` |
| the `raise` dependency | the phrase comes from the frozen design (§10 item 8, F14 insertion point at `derivations.md:1267`); no `raise` symbol exists in `semantic_register*.cljc` or `linker.cljc` today | I read it as the old kernel's thrown-`ex-info` error path that `linker/relowered` swallows (`:2177` `catch … nil`) and `relowered-hashes` swallows (`:2466`); it leaves with those functions. Noted so the implementer does not hunt for a function named `raise` |

### 1.8 Build tooling, Dart and docs touched by the rename

- `src/dev/subsystems.edn` matches by prefix `yin.vm` and `yin.vm.ucf`; no change needed. `shadow-cljs.edn` auto-discovers `*-test`; no allowlist.
- ClojureDart writes `lib/cljd-out/yin/vm/semantic_register.dart` and `semantic_register/*.dart`; after the rename, stale `.dart` files for the deleted namespaces remain in `lib/cljd-out/` on every machine that built before (laptop, shiva, jupiter). The Dart peer build (`bb.edn:18-25`, `dart compile exe lib/cljd-out/yin/repl/slice-peer.dart`) imports by path, so a stale `semantic.dart` from the OLD kernel colliding with the NEW `semantic.dart` is the concrete Dart risk (§4).
- Docs that must change: the 13 files above; `docs/design/yin.vm.semantic.md` (836 lines, describes the v3 machine; 25 citers including `src/cljc/yin/vm.cljc`, `repl.cljc`, `ucf.cljc`, `docs/agents/format.md`); `yin.vm.ucf-revisions.md` (new revision record for `"v4"` as the one semantic contract); `yin.vm.universal-continuation-format.md:190, :390, :651, :3225, :3347` (phase-8 forward references); `yin.vm.linker.md:222-224` (§3 amendment); `yin.vm.debruijn.stack.md`, `yin.vm.debruijn.register.md`, `yin.vm.debruijn.targets.md` (SHA-256 prose, lowerer names); `semantic-register-vm.md` status header `:1-11` ("working name… takes the name `yin.vm.semantic`"); `derivations.md` §7.4-§8.3 PROPOSED to DECIDED/DONE; `continuation.md` §8.4; `docs/agents/build-n-test.md` if any lane names change (none expected).

---

## 2. Design questions phase 8 forces

Each with recommendation and cost. OWNER marks a question only the owner can close.

**Q1 (OWNER). Who edits the 10 C4 files, and when.** The cutover cannot compile without rewriting `linked_harness.cljc:105-120` and the ten Python tests that require the old kernel, `dl`, `rc` and `handoff`, and without regenerating `manifest-golden`, `pysp-manifest-golden` and verifying the literal-address pins. Options: (a) this track edits them in slice 8f under a frozen C4 branch state the owner declares (the other orchestrator pauses commits to those files for the slice's duration); (b) the other orchestrator ports them against a published 8c/8e API on a C4 branch, and 8f merges both. Recommendation: (a), because the change is mechanical (the harness's three backend lambdas and the loader) and a two-party edit of one file is the worst conflict shape; cost half a day plus the C4 slow lane (hours on a laptop; shiva/jupiter preferred). The owner must also say whether the C4 goldens are "ours to move" (derivations §8.1 says publish twice and diff; the review must show the diff).

**Q2 (OWNER). Do versions 0/1/2, `handoff.cljc` and `checkpoint.cljc` die in this phase.** The brief says yes ("leave at phase 8"). The inventory confirms zero production requirers and 141 tests. Recommendation: yes, in one slice (8d), because keeping a 3783-line codec nobody produces is the legacy the no-compat rule forbids, and because its tests pin the old kernel and so block 8f. Cost: 1.5 days (mostly porting the helpers the new tests borrow from `lift_support`/`v2_support`). The one thing lost is the only existing test of the holder's acceptance of a version-2 body as a refusal; add one row to `rows_test` that a version-2 or version-0 header is `:yin.k/undecodable` (the holder path never dispatched on version; confirm, do not invent).

**Q3. Rename mechanics for a name that other tracks and docs cite.** Recommendation: two commits inside slice 8f. Commit 1: `git mv` of 8 source and 16 test files plus `ns` forms, the `:yin.semantic-register/code` keyword to `:yin.semantic/code`, alias `sr` kept where it reads well; no logic change. Commit 2: the 56 requirers' API rewrite. Reviewers diff commit 1 with `--find-renames` and read only commit 2. Docs are a third commit (8g). Keep `yin.vm.semantic-register.*` nowhere, no alias namespace (no compat shim). Cost: 2 days codex, mostly the 58 `vm-load-program` sites.

**Q4. The descriptor change is two rows, not one.** Derivations §8.1-8.2 say the printed descriptor "differs from the previous one only by the removed `:dim/lift-to` row", but the BLAKE3 ruling also flips the `:hash :sha256` row (`debruijn_code.cljc:138`, `debruijn_register_code.cljc:200`, `debruijn.cljc:139-140`). Recommendation: amend §8.1's mechanical check to "differs by exactly the removed `:dim/lift-to` row and the `:hash` value", and compute descriptor hashes with `jing/blake3`. No version bump (ruling 2 covers both as regeneration). Cost: zero beyond the doc line; flagged because a reviewer applying §8.1 literally would refuse the slice.

**Q5. What replaces `yin.vm.code` for `completion.cljc` and `materialize-vector!`.** `completion.cljc:206, :256` project a v3 vector to qualified rows for the completion fold; `publish.cljc` materialises the semantic vector through `code/materialize-vector!`. Recommendation: `materialize-vector!` is one `jing/materialize!` of the vector and moves into `sr-code`; completion gets `sr-code`'s row projection (or `sr-linearize`'s) under the same name. Decide at 8c review whether `completion.cljc` needs a v4 projection at all or whether `completion_test` (3 SHA-256 sites) is testing a dead path. Cost: half a day.

**Q6 (OWNER). Fate of `docs/design/yin.vm.semantic.md`.** It describes the v3 machine that is deleted. Options: (a) delete it and move the §1 stance and the `"v2"`/`"v3"` revision history into `yin.vm.ucf-revisions.md` §3-5; (b) rewrite its header to "historical, superseded by `yin.vm.semantic-register-vm.md`", keep body; (c) rename `yin.vm.semantic-register-vm.md` to `yin.vm.semantic.md` and delete the old. The frozen design's own header says it "takes the name `yin.vm.semantic`". Recommendation: (c) plus a one-paragraph history block, because a doc named after a namespace that no longer exists is the same legacy as a shim. Cost: 1 day of doc work in 8g including the 25 citers.

**Q7. The old harness and stage tests of the old kernel.** `engine_gate_test`, `semantic_engine_test`, `semantic_ffi_test`, `semantic_stream_observer_test`, `stream_poll_test`, `telemetry_test`, `heap_reclamation_test` etc. exercise the engine through the old kernel. Recommendation: port, do not delete; the new kernel is the same engine's client. Slice 8f carries a coverage audit: list every deftest in deleted files, and for each name a surviving deftest or write one. Reviewer gate: the suite's deftest count after 8f is at least today's minus the 85 + 141 + (91 - ported) that are tests of deleted code, documented in the slice report with the list. Cost: in 8f's 2 days.

**Q8. Who regenerates the C4 goldens and the literal-address pins.** Covered by Q1; the mechanical check is derivations §8.1 row 6 (publish twice, diff the manifest map). If the five `int_literal_test` and `float_address_test` pins are v3 vector addresses they die; if tree or v4 addresses they hold. Decided at 8f by printing them.

**Q9. Deletion order (irreversible).** Fixed by this plan: 8a pins and regenerates goldens and moves the H descriptor golden; 8b moves BLAKE3; 8c switches production callers; 8d deletes the old transport; 8e deletes the three old lowerers; 8f deletes the old kernel and renames; 8g documents. Nothing is deleted before its replacement is green on three hosts in the same slice or earlier.

**Q10. `derive_test`'s `:old` oracle legs** (`:150-160`, `:438-447`, `:612-630` `c4-backends :stack-old/:register-old`). These compare new lowerings against the old lowerers and die with them (8e). The execution oracle then compares `:stack-new`/`:register-new` against the walker and the semantic kernel only (§6.1 already names the walker as oracle). Recommendation: accept; the old lowerers' answers are already pinned as the §6.3 goldens.

---

## 3. Slicing

Estimates: codex implementation days; review rounds 2-4 per slice (Fable/opus, non-author). Hosts: every slice runs the quick tier on JVM per iteration (`bb test:sub:clj yin.vm` or `clojure -M:test -n <ns>`), `bb test:changed` on three hosts at landing (G2). Slow tier (`bb test:all`), C4 suite with `--slow`, and three hosts once at the phase boundary (8f/8g).

| Slice | Files | Depends on | Acceptance (named tests) | Hosts | Codex | Reviews |
|---|---|---|---|---|---|---|
| **8a Descriptors and goldens** | `debruijn_code.cljc:145`, `debruijn_register_code.cljc:204` (drop `:dim/lift-to`), `debruijn_code_test.cljc:364-368` (drop two lift assertions, pin descriptor hash), `derive_test.cljc:240, :419-428` (regenerate), `linker_test.cljc:253-260, :522-531` (helpers to `derive`, regenerate), `debruijn_register_code_test.cljc:7`, `debruijn_register_compile_test.cljc:446` (dup golden deleted), `stack_parity_test.cljc:321-347` (producer to `derive`, regenerate) | none | `debruijn-code-test/descriptor-declares-the-contract` (now pins hash), `derive-test/{stack-goldens,register-goldens,stack-determinism,register-determinism,lexical-address-law,lexical-address-law-stack}`, `linker-test/pinned-identities-are-host-independent`, `stack-parity-test/*`; reviewer recomputes every new constant from the printed image per §8.1 | 3 | 1 d | 2 |
| **8b BLAKE3** | `debruijn_code.cljc:138, :548, :564`, `debruijn_register_code.cljc:200, :258, :269`, `debruijn.cljc:139-140, :966, :1028` + docstrings; `debruijn_test.cljc:136, :933, :1300-1306`; re-pin 8a's goldens once more (or merge 8a+8b so goldens move once: **recommend merging 8a and 8b into one slice "8ab"** so every H/R constant regenerates exactly once) | 8a | same as 8a plus `debruijn-test/*`, `pipeline-test/*`; descriptor-hash recomputed by `(jing/blake3 (dc/encode-scalar <printed descriptor>))` | 3 | 0.5 d | 2 |
| **8c Production pipeline to A** | `linker.cljc:46-52` requires, `:695-731` one `semantic-format` (v4 scanners), `:2159-2177` `relowered`, `:2252-2320` `checked-derivation` (de Bruijn input = A), `:2457-2565` pairing paths; `publish.cljc:5-17, :159-213`; `ledger.cljc:40-50, :194-204`; `vm.cljc:266-269` `"v4"`, `:1755` footprint key; `ucf.cljc:47-68`; `completion.cljc:206, :256` (Q5); `interpreter.cljc:84-93`; `repl.cljc:27-41, :143-156, :224-240`; `rows.cljc:2118`, `semantic_register.cljc:410-456` keyword to `:yin.semantic/code` (so the keyword moves once, here, not in 8f); tests `linker_test`, `linker_manifest_test:740`, `linker_require_test`, `linker/publish_test`, `repl/serve_test:446-452`, `semantic_register/link_test`, `repl_test`, `ucf_test` (v4 vectors), `ledger_test` | 8ab | `linker-test/*` incl. `pinned-identities-are-host-independent`, `verify-same-root-pairing` tests `:1969-1980`, `linker-manifest-test/*`, `linker-require-test/*`, `publish-test/*` (manifest shape: contracts `"v4"`, three derivation records with de Bruijn input = A, index keyed by format), `serve-test/the-composition-registers-the-v4-format-record` (6 records), `repl-test/*`, `dht_process_test`, `remote_transfer_test`, `cross_host_transfer_test`; `derive_test/c4-execution-oracle` still green | 3 (JVM + Node REPL peer + Dart peer are all involved) | 3 d | 3-4 |
| **8d Old transport out** | delete `handoff.cljc`, `checkpoint.cljc`, 15 test files, `lift_support.cljc`, `v2_support.cljc`, `checkpoint_fixtures.cljc`, 3 fixtures + minter; first move borrowed helpers into `rows_support.cljc` (new `frame_test`, `layout_test`, `semantic_register/safepoint_harness_test` requires rewired); `int_heap_test.cljc:241`, `int_contract_test.cljc:483` to the holder path (C4 files: Q1) | none (can run concurrently with 8ab/8c) | `rows-test/*`, `rows-checkpoint-test/*`, `compose-rows-test/*` (41), `fencing-rows-test`, `stage-e-rows-test`, `stage_e_process_test`, `holder/*`, `authority/*`, `semantic-register/safepoint-harness-test/*` (26), `frame-test/*` (10), `layout-test/*` (12); one new refusal row for a version-0/2 header | 3 | 1.5 d | 2-3 |
| **8e Old lowerers out** | delete `debruijn_resolve.cljc`, `debruijn_linearize.cljc`, `debruijn_register_compile.cljc`, their 3 tests, `debruijn_register_benchmark_test`; port the contract facts of `debruijn_vm_contract_test`/`debruijn_register_contract_test` to `debruijn_code_test`/`debruijn_register_code_test`, delete the rest; `test_utils.cljc:24, :200-210` (`resolved-of` becomes `derive` over the projected vector); `derive_test` `:old` legs out (Q10); every test that builds a kernel through `dl/adapt`/`rc/adapt` (37 files, the 10 C4 files among them: Q1) switches to `derive/stack-image`/`register-image` over `(:vector (sr-linearize/project ast))` | 8c (production no longer calls them) | the full `yin.vm` subsystem quick tier; `derive-test/*` incl. `c4-execution-oracle` (`--slow`); `debruijn/register_test`, `debruijn/stack_*_test`; kondo clean (`clj -M:kondo`) with no unresolved namespace | 3 | 2 d | 2-3 |
| **8f Old kernel out, rename, C4 gate** | delete `semantic.cljc`, `linearize.cljc`, `code.cljc` (what remains after 8c) and `semantic_test`, `linearize_test`, `code_test`; port `semantic_engine_test`, `semantic_ffi_test`, `semantic_stream_observer_test` to the new kernel; commit 1 `git mv` `semantic_register*` to `semantic*` and `test/yin/vm/semantic_register/` to `test/yin/vm/semantic/`; commit 2 rewrite the 56 requirers (`vm-load-program` to `load-ast`/`load-vector`); demos (`continuation_handoff.cljc`, `continuation_stream.cljs`, `register_bench_cljd.cljd`, `yin_vm_bench`) ported; `linked_harness.cljc:105-120`; C4 goldens regenerated and literal pins verified (Q1, Q8); coverage audit list | 8c, 8d, 8e | **phase gate**: `bb test:all` three hosts (slow tier), `bb test:sub yang.python --slow` (C4 suite: `import_test`, `linked_prelude_test`, `linked_safepoint_test`, `safepoint_test`, `dht_import_test`, `e2e_test`, `e2e_c2_test`, `prelude_parity_test`) over newly published images = the install-child gate of derivations §6.1.4 (c); `repl-test/*`, `dht_process_test`, `store_write_audit_test` (allowlist touches); deftest-count audit per Q7; kondo clean; `lib/cljd-out` cleaned before the Dart lane | 3 + shiva/jupiter for the slow tier | 3 d | 3-4 |
| **8g Documentation** | the 13 design docs, `yin.vm.semantic.md` fate (Q6), `ucf-revisions.md` record, `linker.md` §3, `build-n-test.md` if needed, the frozen design's §10 item 8 marked complete, `derivations.md` §7.4-8.3 and `continuation.md` §8.4 statuses, `debruijn.*.md` SHA-256 prose | 8f | Architect doc review; `grep -r 'yin.vm.semantic-register' docs src test` returns only historical/dated statements; `grep -rn 'sha256' src/cljc/yin/vm` returns none | n/a | 1.5 d | 2 |

Ordering and concurrency:

| | needs | concurrent with |
|---|---|---|
| 8ab | nothing | 8d |
| 8c | 8ab | 8d |
| 8d | nothing | 8ab, 8c |
| 8e | 8c | nothing (touches 37 test files; serialize) |
| 8f | 8c, 8d, 8e | nothing |
| 8g | 8f | nothing (docs only; may start drafting during 8f review) |

Gate list: quick tier (`bb test:sub:clj yin.vm` per iteration, `bb test:changed` three hosts at landing) for every slice; `bb test:sub yin.vm yin.vm.ucf` at 8c and 8e checkpoints; the slow tier once at 8f on shiva/jupiter plus the C4 suite with `--slow`; three hosts at every landing from 8ab up because the regenerated constants must agree on JVM, Node and Dart (that is what `pinned-identities-are-host-independent` and `register-determinism` exist for).

Branching: `srvm-phase8` off `srvm-phase7` (after phase 7 lands on master, rebase once); each slice a branch `srvm-phase8a` etc. merged into `srvm-phase8`; `srvm-phase8` lands on master only after 8g.

---

## 4. Risks and mitigations

1. **Mass rename collides with the other orchestrator.** 56 files, 10 of them C4's, and `linked_harness.cljc` is the shared hinge. Mitigation: Q1 ruling; freeze window announced via collab/owner relay before 8e starts; 8e and 8f edit C4 files only as mechanical API substitution with no behavioural change; the C4 suite over new images is the proof. Never touch `datomworld-c4-*` worktrees (none exist in `git worktree list` today; the C4 work seems to live in the main checkout's branches).
2. **Accidental coverage loss when deleting 317 deftests.** Mitigation: Q7 audit list in the 8f report (deleted test name, reason: tests deleted code / ported to X / replaced by Y); port the engine-level tests; reviewer refuses 8f without the list. Count today: 4574.
3. **Hash regeneration drift.** Three regeneration moments collapse into one (8ab merged) so each H/R constant moves once; §8.1's mechanical check is applied by the reviewer on printed intermediates (image, descriptor, manifest map); A addresses in `corpus.cljc` (32) and `rows_test` (20) and the 7a/7b fixtures are asserted unchanged as a negative gate. Descriptor diff is exactly two rows (Q4).
4. **Dart name resolution on rename.** The new `yin.vm.semantic` compiles to `lib/cljd-out/yin/vm/semantic.dart`, the same path the old kernel occupied; a stale build cache or a stale `semantic_register.dart` referenced by a stale `semantic_register/*.dart` can shadow or double-define. Mitigation: 8f's Dart lane starts with a clean `lib/cljd-out` (and the same on shiva/jupiter before their lanes); the known cljd traps (reader-conditional order, `[_ _]` protocol params, chunked `for`, unary minus) are re-checked by the three-host gate, not by inspection. Also the file rename changes the cljd build's import graph; the Dart peer binary must be rebuilt (`bb build:yin-repl-peer`) before `dht_process_test`.
5. **The REPL custody gap closes by rename, but the REPL also loses `packet->named-datoms`.** `repl.cljc:150-156` feeds the old lowerers from named datoms; after 8c the stack/register evaluators derive from A. A REPL session that `def`s across inputs relies on `append-stack-image`/`append-register-image` (`:159-204`) keeping earlier images; `derive` produces images with the same `:segment`/`:bodies` shape, but `absolute-pc` lookups use `dcode/image-hash`, which moves under BLAKE3 in the same window. Mitigation: `repl_test` and `dht_process_test` in 8c's acceptance; run the REPL smoke on Node (`target/yin-repl.js`) in 8c.
6. **`completion.cljc` and the v3 table (Q5)** may hide a dependency on v3 row shapes in the completion fold; discovered at 8c. Mitigation: `completion_test` in 8c acceptance; if the fold needs a v4 projection that does not exist, 8c's report says so and 8e waits.
7. **Review-round inflation.** Phases 5-7 averaged 2-4 rounds; 8c and 8f are the two slices with the most files and the most hosts. Mitigation: split 8f into two commits (Q3) so the reviewer reads the rename as moves; keep `--find-renames` diffs in the report.
8. **Credits.** Codex implements (gpt-6.1-sol, workspace-write); Fable/opus review only. If codex is out, implementation waits (owner rule); do not move implementation to opus without a ruling.

**Rollback story.** Every slice is a branch; `srvm-phase8` is not merged to master until 8g. Rolling back a slice is deleting its branch and re-running the predecessor's quick tier. Rolling back after master lands is a `git revert` of the one merge commit; nothing is persisted outside git (dev-only repo, no deployed stores, owner rule). The regenerated goldens and C4 manifests are recomputable from the printed intermediates in each slice report, so no golden is ever "lost": the old values are in git history and the new ones in the report.

---

## 5. Honest total estimate

| | Codex days | Review rounds | Calendar |
|---|---|---|---|
| 8ab | 1.5 | 2-3 | 2-3 days |
| 8c | 3 | 3-4 | 5-6 days |
| 8d (concurrent) | 1.5 | 2-3 | within 8ab+8c |
| 8e | 2 | 2-3 | 3-4 days |
| 8f | 3 | 3-4 | 5-6 days + slow tier (JVM 20+ min, Node/Dart ~5 min each, C4 slow sequence hours on a laptop; use shiva/jupiter) |
| 8g | 1.5 | 2 | 2-3 days |
| **Total** | **12.5 (range 11-15)** | **14-19 rounds** | **4-5 weeks** with 8d overlapped; 6 weeks if every slice hits 4 rounds or codex credits lapse |

Not counted: the owner's own time on Q1-Q6, and any C4-side rebase the other orchestrator needs after 8f.

---

## Verdict

**NOT READY.** Blocking owner questions:

- **Q1** who edits the 10 C4 files and the two C4 manifest goldens, and the freeze window.
- **Q2** confirm versions 0/1/2, `handoff.cljc` and `checkpoint.cljc` die in 8d (the brief implies yes; the inventory found no production requirer, so a yes costs nothing).
- **Q4** accept the two-row descriptor diff (lift-to removed, `:hash :blake3`) as the amended §8.1 check.
- **Q6** fate of `docs/design/yin.vm.semantic.md` (recommend: the frozen design takes the name, old doc deleted, history block kept).

Q3, Q5, Q7-Q10 are recommendations the orchestrator can adopt without an owner ruling. On Q1, Q2, Q4, Q6 answered, slice 8ab can be briefed to codex the same day; 8d can be briefed in parallel on Q2 alone.
