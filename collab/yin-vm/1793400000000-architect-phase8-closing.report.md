Completed-GMT: 2026-10-16 11:45:00 GMT
Completed-Local: 2026-10-16 18:45:00 +07
Coding-Agent: claude (claude-fable-5-1), Architect
Session-ID: pending

# Architect closing sign-off: srvm-phase8 at c11f9299 (delta since the final review at 6dceede9)

Brief: ./collab/1793390000000-architect-phase8-closing.prompt.md
Worktree: datomworld-srvm-p8final = origin/srvm-phase8 @ c11f9299. Read-only; the only file written is this report. `git status` shows no tracked change.

## Delta

`git log --oneline 6dceede9..HEAD`: 8 commits, 3 first-parent merges, each with one slice commit beneath except the frame-NaN merge (two):

| merge | parents | slice commits |
|---|---|---|
| 65e1ec81 ledger docs | 6dceede9 + 4d727e0c | 4d727e0c (docs only) |
| 47249651 frame-NaN | 65e1ec81 + 51c1294a | 4accc7e9 (signed off), 51c1294a (cljs guard) |
| c11f9299 Node slow-C4 floats | 47249651 + 6af0ddd2 | 0ca2c0f9 (fixture + pin), 6af0ddd2 (pin replacement text) |

`git diff --stat 6dceede9..HEAD`: 8 files, +113/-13, and nothing else:

- `docs/agents/known-gaps.md` (+5/-2)
- `docs/design/yin.vm.ucf-revisions.md` (+8)
- `src/cljc/yin/vm/semantic/frame.cljc` (+21/-3)
- `src/cljc/yin/vm/ucf/rows.cljc` (+3/-1)
- `test/yang/python/antlr/prelude_parity_test.cljc` (+4/-7)
- `test/yin/vm/semantic/frame_test.cljc` (+35)
- `test/yin/vm/semantic/load_paths_test.cljc` (+23)
- `test/yin/vm/ucf/rows_test.cljc` (+14)

No `src/yang/**`, no fixture under `test/resources`, no collab or secret path. The only C4 file touched is `prelude_parity_test.cljc`, which is the one my Node slow-C4 ruling named under the 8f freeze rule.

## Piece by piece

### 1. Ledger-docs merge 65e1ec81 (my P2-1 / P2-2 / P3 rows)

**verified** against the final report §6:

- P2-1: `yin.vm.ucf-revisions.md` I-6 gains one dated paragraph (lines 647-654) saying `rows-checkpoint-v3.txt` re-minted at 8c because the installs child moved `"v3"` → `"v4"`, three child addresses moved, no format/profile/H/R/descriptor in any pack. That is my §4 item 1 wording. Met.
- P2-2: the native-binder-refusal row now reads "accepted as design for the phase 8 merge (the projection is the only producer … §3.4, defence in depth, not a hole); follow-up slice to be scheduled by the owner". That is the second of the two options I offered. Met.
- P3-2: the 8d partial-mappings row is added ("open: name two successor tests"). Met.
- P3-4: the "22 of 22" row records namespaces run by `~/ci/slow-tier.sh`, not deftests, 231 `^:slow` in tree. Met; the Node slow line it promises is now on file (piece 3).
- P3-1 (prose pass over seven docs) and P3-3 (`:refer` tidy, `malformed-vectors` deletion) were allowed to follow the merge and are not in the delta. Still open, still P3.

Docs only; no code in this merge. Matches.

### 2. Frame-NaN merge 47249651

**verified** that 4accc7e9 is exactly the signed-off shape: `frame.cljc` adds `bindings=` (slotwise `cbor/content=` with host `=` retained for fns, closures, continuations and `:stream-ref`/`:cursor-ref`/`:cell-ref` maps, the same opacity set `data-content=` uses) and the free-env check at the former `:141` now calls it; `rows.cljc` `decode*` compares decoded row order slotwise by `cbor/content=` instead of host `=`; `frame_test` adds `free-env-compares-values-by-content` over `:wait`/`:captured`/`:parked`; `rows_test` adds `nan-valued-row-slots-are-canonical` (bindings, set, map; emit → decode → emit root stable). The ledger row for `frame.cljc` is closed on the branch with the evidence link, and the new P2 row for the `decode*` host-`distinct` clause on map/set keys (0.0/-0.0 asymmetry, Dart-only 1/1.0 refusal) is the P2-1 of that review. The review report itself (`*architect-frame-nan-review.report.md`) is not in this worktree's `collab/` and the main checkout is outside this session's allowed directories, so the match is to the merge subject, the ledger row text and the code I signed; the code is unchanged from 4accc7e9 at HEAD (`git show 4accc7e9` diff of the three non-test files is byte-identical to the HEAD delta).

**verified** 51c1294a is the one later commit and does only what the brief says: the two signed-zero assertions (`0.0` vs `-0.0` free binding → `:free-env-mismatch`; `(not (cbor/content= 0.0 -0.0))`) are wrapped in `#?@(:cljs [] :default [...])` with a comment. Correct: JS has one zero, so the carrier cannot distinguish them there and the assertion would be a false red on Node, not a kernel divergence. No other line changed (+7/-4 in `frame_test.cljc`).

**Merged known-gaps.md**: 0 conflict markers in `known-gaps.md` and `ucf-revisions.md`; 25 table rows, 0 duplicate lines; exactly one row mentions `frame.cljc` (the closed one; the old open row is gone), exactly one native-binder row (the amended one). Row count reconciles: 22 at 6dceede9, +3 net from the ledger merge (binder amended in place, 8d, 22/22) = 25 at 65e1ec81 (my earlier number of 24 was wrong by the binder row being modified, not added), frame-NaN amended the frame row in place and added the `decode*` row; the conflict resolution kept both the ledger rows and the frame-NaN rows, which is the right union. No damage.

### 3. Node slow-C4 fix merge c11f9299

**verified** 0ca2c0f9 applies F1 of my ruling verbatim: the three `py/float-mod` rows become `(py/mod {:py/float …} {:py/float …})` with `{:py/float …}` expectations, and the reducer loses the `py/float-mod` special case so every row is `(ops/exact-literals (with-float64 f))`. No `src/` change, `int_ops_test` untouched (the optional docstring trim was not taken; fine).

**verified** the pin at HEAD in `load_paths_test.cljc` is the F2 shape with the replacement text the brief says I prescribed in the node-pin diagnosis (that report is also outside this worktree): the portable half is unchanged from F2 (float64 `1.0E20` through both lanes, `(= rows datoms)`, address `blake3-d034b2a4…46b6`); the cljs half now keeps `linearize/project` and `project-datoms` inside the `thrown-with-msg?` form and asserts both lanes refuse with "unsafe integral JavaScript number". That is what 6af0ddd2's subject says ("keeps projection inside the assertion; the row lane refuses at projection on JS") and it is the correct tightening of my F2, whose cljs half assumed projection accepts the bare Number and only load refuses; the Node run showed the row lane refuses earlier, and the pin now covers both lanes regardless of which stage refuses. The address assertion held on the JVM here (below), so no re-pin was needed.

**Gate evidence**:

| lane | source | status |
|---|---|---|
| JVM, `frame-test` + `load-paths-test` + `rows-test` at c11f9299 | rerun here | **verified**: 52 tests, 1076 assertions, 0 failures, 0 errors; counterexample searches 0 failures |
| JVM, `prelude-parity-test` (`^:slow`) | not runnable here (`DATOM_SLOW_TESTS=1` needs an approval this session does not have) | **reported** green by the 0ca2c0f9 step 4 (my ruling's JVM probe of the three rows also passed before the edit) |
| Node slow (jupiter) after the fix | brief | **reported**: 55 tests, 658 assertions, 0 failures, 0 errors |
| Node slow-C4 full run before the fix | brief + `collab/node-slow-c4-prelude-failure.txt` | **reported**: 257 tests, 69 failures = 66 `dht-import-test` + 3 prelude rows; the failure text on file is exactly the cbor refusal my ruling diagnosed, with the `:ast-walker` side passing |
| Dart pin | brief / merge subject | **reported**: +38 green |

The Node and Dart numbers are the orchestrator's; I could not build either lane here (no `node_modules`, no `lib/cljd-out`). The 55/658 line is for the lane the fix was aimed at and leaves `dht-import-test` as the only red in the full run (69 − 3 = 66, all `dht-import-test`).

## Conditions of the final review

| condition | owner | status |
|---|---|---|
| (a) jupiter Node slow-C4 line on file with `dht-import-test` the only red | orchestrator | **met**: full run 257/69 with 66 = `dht-import-test` and the other 3 fixed by c11f9299; post-fix prelude lane 55/658/0 |
| (b) owner ruling on who regenerates `dht-import-v1.edn` and in which commit | **owner** | **not in the delta, not required to be**: `test/resources/yang/python/dht-import-v1.edn` is still untouched on this branch; the ledger carries the red. This remains the owner's call and does not block the merge |
| P2-1 doc line | orchestrator | met (65e1ec81) |
| P2-2 row | orchestrator + owner (slice to schedule) | met as "accepted as design"; the follow-up slice is the owner's to schedule |
| §7 item 4, lift the C4 freeze and name the moved `test/yang` files | **owner** | post-merge; now 13 files (the 12 plus `prelude_parity_test.cljc` again), the yang.python seat must be told |
| §7 item 5 follow-ups | owner | `frame.cljc` NaN: **closed** on this branch; binder-refusal defence, §2 prose pass, `malformed-vectors` deletion: open P2/P3, post-merge |

## Findings

| id | sev | where | finding | ask |
|---|---|---|---|---|
| P3-1 | P3 | `docs/agents/known-gaps.md`, `decode*` row | new P2 from the frame-NaN review (host-`distinct` on 0.0/-0.0 and 1/1.0 keys) has no slice assigned | assign with the binder-refusal slice; not a merge blocker, it is a pre-existing asymmetry the content-equality change made visible |
| P3-2 | P3 | `test/yang/python/antlr/int_ops_test.cljc` `exact-literals` docstring | "callers skip the float-only rows" is now stale (no caller skips) | one-line trim in a later C4 edit, the yang.python seat's file |
| P3-3 | P3 | record | the frame-NaN review report and the node-pin diagnosis are not in this worktree's `collab/`; this closing review matched code to the merge subjects, the ledger text and my own rulings | copy them into this worktree's `collab/` (cp -n) before the worktree is removed, per the standing collab rule |

No P1, no P2. Nothing in the delta is outside what I approved or prescribed; the two post-sign-off commits (51c1294a, 6af0ddd2) are test-only host guards whose reasoning I confirmed.

## Verdict

**SIGN-OFF at c11f9299.** The delta is the three merges the brief lists and nothing else; each matches the approved or prescribed shape; the merged ledger has no marker or duplicate damage and carries the right union of rows; condition (a) of the final review is met by the Node slow line on file, and condition (b) remains the owner's ruling on `dht-import-v1.edn`, which the ledger already carries and which does not gate the merge. Merge `srvm-phase8` to master at c11f9299.
