Completed-Local: 2026-10-15 +07
Coding-Agent: claude (claude-fable-5-1), Architect
Brief: collab/1792750000000-architect-f1b-review.prompt.md
Target: worktree datomworld-srvm-p8f1b @ 3d626385 + UNCOMMITTED rows.cljc / rows_test.cljc (git diff verified identical to collab/f1b-uncommitted.diff before and after review; src restored byte-for-byte via cp backup + cmp; temp files deleted)

## (1) Placement of the two dissoc sites
- restore-task `base` (rows.cljc:2093-2095) dissocs the seven keys from recv BEFORE attach-module and before everything that derives from base; `with-custody` (2024-2046) runs last over `machine` (2236-2241), and `:yin.k/recovery-cell-ids` is assoc'd at 2235 under fenced. So the lower sets exactly what the R2 probe table had on the exclusive path (issued 0, gate :running / :ended for halted root, custody = accepted state on the root only) and the fenced path (gate :exporting, recovery-waits, recovery-parked, recovery-cell-ids, issued absent). The only change vs the table: closes/custody/stale recovery-* are now ABSENT instead of KEPT. `:make-stream` handling (spawn-child 2074/2081) is untouched: dissoc is applied to `child` first, then the fenced `assoc :make-stream`, so the fenced child still regains the constructor.
- Nothing the lowered task needs is removed. Read myself: driver.cljc:1565 lowers into `(assoc (:receiver state) :attach-stream attach!)`, the config receiver, never a run machine; driver 2085-2107 (guard-writes/restore-handles) touch only `:resources`; export.cljc:457-475 reads `:yin.k/recovery-waits/-parked` set by this lower's with-custody and itself dissocs custody/closes/issued; export 604-628 reads the gate the lower set and `(contains? machine :yin.k/custody)` for tenure checks, which a fenced lower now answers false (before the fix it answered whatever the receiver carried: a false provenance). reader/writer custody reads (reader 567-814, writer 186-507) are all on the exclusive root's accepted custody, set at 2041. engine.cljc:752 queues closes under a :running gate with `:yin.k/issued`; a fork lower now runs with gate nil and no issued, as rows_custody_test:257 pins.
- Child dissoc (2079-2080): correct but DEAD. All three kernels' spawn-module (semantic_register.cljc:411, semantic.cljc:976, ast_walker.cljc:1208) build the child with create-vm from composition values only, per the IModuleKernel contract ("holding nothing of vm's state"). See mutant 8 and R1 below.

## (2) The assertions
- Fork: each of the seven keys is first shown present on `populated`, then absent on `restored` and on `restored-task` (root for the :reader/:install-root rows, child for the install row), plus `(= (select-keys clean ks) (select-keys restored ks))`: non-vacuous (the poison makes `populated` differ from `clean` on every key, and an all-absent/all-absent equality is backed by the explicit `not contains?`). Registry line asserts the composition's `receiver.mod` stays resolvable: documents the R2 ruling without claiming more.
- Exclusive: grant built through public rows API (`pack-address`, decoded occurrence/arbitration), same shape as rows_custody_test's `grant-for`; asserts closes absent AND empty, issued 0, gate :running, custody equal to an exact literal (occurrence, lease, holder, epoch 0, arbitration, next-op-seq 0, protection, input, tenure {:bound 10}), the three recovery keys absent. Correctly scoped (blocked reader root, so :running not :ended).
- Fenced: root and install child compared to a clean fenced lower on the seven keys, gate :exporting, no closes, no custody, recovery-waits non-empty, recovery-parked/-cell-ids maps, scheduler empty. Non-vacuous (`seq`/`map?` guards).
- Portability: no `#?`, no private vars, no `for` over long seqs, dynamic-key maps built with `(assoc {} ...)` on a map not nil, no EDN strings. Fine by inspection.

## (3) Mutants (each alone; src restored and cmp'd after each; two regression vars, baseline 2 tests/160 assertions/0 F)
| # | mutant | result |
|---|---|---|
| 1 | root site keeps `:yin.k/closes` | killed 9 F: fork absence x3, select-keys equality x3, exclusive `empty?`/`not contains?`, fenced `not contains?` |
| 2 | root keeps `:yin.k/issued` | killed 6 F: fork absence + equality, fenced equality (exclusive overwrites it, as expected) |
| 3 | root keeps `:yin.k/gate` | killed 12 F: fork absence/equality + 7 behavioural (resume no longer halts / value wrong / append refused under the inherited :ended gate) |
| 4 | root keeps `:yin.k/custody` | killed 7 F: fork absence + equality, fenced equality + `not contains?` |
| 5-7 | root keeps each `:yin.k/recovery-*` | killed 6 F each: fork absence + equality, exclusive `not contains?` |
| 8 | child site: whole dissoc removed | SURVIVES (0 F): spawn-module never carries these keys, so the child dissoc is unobservable |
Each key is proven at the root site; the child site is unprovable by construction (see R1).

## (4) Existing callers
kondo 0/0 on both files. `clojure -M:test -e :slow -n yin.vm.ucf.rows-test`: 36 tests/672 assertions/0 F. Slow: `clojure -M:test -i :slow -n yin.vm.ucf.stage-e-rows-test -n yin.vm.ucf.compose-rows-test`: 9 tests/678 assertions/0 F (DATOM_SLOW_TESTS is a no-op on the JVM: dao.test-slow/enabled? is always true there). Orchestrator's 570/6808/0 over all yin.vm.ucf.* stands. No fixture, root id or carrier change.

## (5) Node/Dart
Not run (JVM only per brief). No construct in the diff hits a known cljd/cljs trap; `(nth (:slots ...) 16)` and `into #{} (keep ...)` are portable. Run the Node/Dart lanes for yin.vm.ucf.rows-test at the srvm-phase8 landing as already planned.

## Findings
| id | sev | file:line | finding | fix |
|---|---|---|---|---|
| R1 | P3 | src/cljc/yin/vm/ucf/rows.cljc:2079-2080 | child-site dissoc is dead code under every kernel (mutant 8 survives); it restates the IModuleKernel spawn-module contract | optional: drop it for a minimal diff, or keep as a guard against a future kernel; either way not a blocker |
| R2 | P3 | rows.cljc:2240 docstring | unchanged from F1 review (recv holds no link wait of its own) | one sentence, F2 or later |

## Verdict
The fix is in the right place (before with-custody, independent of the fenced :make-stream swap), removes only receiver-local task state, leaves exclusive/fenced lowers setting exactly what they did, and every root-site key is killed by a non-vacuous, portable assertion. Commit as is.
SIGN-OFF
