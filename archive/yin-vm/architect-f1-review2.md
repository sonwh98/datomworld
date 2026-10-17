Completed-Local: 2026-10-15 +07
Coding-Agent: claude (claude-fable-5-1), Architect, second pass
Brief: collab/1792730000000-architect-f1-review2.prompt.md
Target: worktree datomworld-srvm-p8f1 @ f9d15d36 + uncommitted rows_test.cljc (`lowering-isolates-task-state-from-a-populated-receiver`, 88 assertions)

## Part 1: the test, read against the deleted tests
- Not vacuous: every "absent" assertion is paired with `(= clean restored)` on the same field, and every field is first shown to differ between the populated receiver and the clean lower (`not=`), so an empty-collection pass is impossible. This is stronger than the deleted `a-poisoned-receiver-behaves-as-a-clean-one`, which compared code only and used `not (contains?)` elsewhere.
- Install child: the whole child VM is compared (`:installs` equality), plus the seven fields at the child's path; the resume to 42 proves the child's stream is the task's. Covers the deleted `v1-child-state-and-receiver-isolation` child half.
- Resource contract: the collision case pre-places the receiver's stream at exactly the key the clean task mints and asserts it is neither overwritten nor reused. This is the `:yin.k/k-1`-style collision my first pass (R4) asked for; the author had it already.
- No masking: `:make-stream nil` is the fenced-spawn discipline rows.cljc itself uses, so no constructor noise is hidden; no relift, no normalization, no `#?`, no private access, no `for` over a long seq. Portable by inspection; Node/Dart lanes not run by me (JVM only, per the brief).
- What it does NOT claim, correctly: the author report scopes out "other receiver composition keys". Part 2 shows that scope hides a defect.

## Part 2: R2 with evidence (temporary probe, deleted; src untouched)
Receiver poisoned with `:yin.k/closes [{:stream-id :yin.k/k-9 :yin.k/issue 5}]`, `:yin.k/issued 99`, `:yin.k/gate :running`, `:yin.k/custody {:poison true}`, `:yin.k/recovery-waits/-parked/-cell-ids`, and two registry entries (`receiver.mod` addressed, `receiver.local` address-less). Survivors after `rows/lower-task`:

| lower | closes | issued | gate | custody | recovery-* | registry (both entries) |
|---|---|---|---|---|---|---|
| fork (reader, install root) | KEPT | KEPT 99 | KEPT :running | KEPT | KEPT (stale) | resolvable |
| exclusive (grant) | KEPT | reset 0 | :running (overwritten) | overwritten | KEPT (stale) | resolvable |
| fenced | KEPT | reset 0 | :exporting | KEPT {:poison true} | overwritten | resolvable |
| fork install child | absent | absent | absent | absent | absent | resolvable (spawn-child copies the registry) |

Ruling:
- `:yin.k/closes`: LEAK. Consumers key closes by `:stream-id` (holder/reader.cljc:224 defers reads, writer.cljc:225/255 emits closes, engine.cljc:602 resolves them); `fresh-key!` avoids only `(keys (:resources base))`, so a receiver close on `:yin.k/k-9` names whatever task stream later mints k-9. A lowered task would defer reads and emit closes for streams it never closed.
- `:yin.k/gate` and `:yin.k/issued` on the fork path: LEAK. A fresh receiver's fork lowers with gate nil (rows_custody_test:257); into a receiver gated `:running` the same task queues its closes instead of performing them (engine.cljc:752), and into one gated `:ended` every close throws "Gate is closed". Task behaviour depends on the prior receiver.
- `:yin.k/custody` (fork, fenced) and stale `:yin.k/recovery-*` (non-fenced): LEAK, lower impact (export.cljc:472 and the driver read these; a stale custody record on a fork is a false provenance claim).
- Module registry: INTENDED composition-owned state (primitives, stream module, effect handlers; `check-dependencies!` resolves primitives against it; `spawn-child` propagates it on purpose). The old `isolated-receiver` dropped addressed (linked) entries and kept address-less ones; whether a linked `receiver.mod` should stay visible to a lowered task is an owner/linker question (linker owns modules). Not a blocker; document it.
- `:resources` merge: intended (R3 stands; collision case proves disjointness).
This is a regression of a pinned contract: the deleted v1 test asserted "Receiver-local close/issue state is removed" and the old `isolated-receiver` dissoc'd exactly these keys; phase 8d deleted the test and rows.cljc never carried the dissoc. Production impact today is nil only because driver.cljc:1565 lowers into the config-supplied receiver, never a run one.

## Part 3: mutants over restore-task (each applied alone, src restored byte-exact, `git diff --quiet -- src` after every one; test diff re-verified identical to collab/f1-uncommitted.diff; baseline rerun 88/0/0)
| mutant | result | killing assertions |
|---|---|---|
| keep `(:parked recv)` | killed: 5 F | `:parked equals a fresh receiver's lower` (x2), `(not (contains? (:parked restored) :receiver-only))`, child field |
| keep `(:store recv)` | killed: 5 F | same shape on `:store` |
| keep `(:installs recv)` | killed: 12 F 2 E | `:installs` equality, child's stream/cursor keys missing, resume cannot append |
| conj receiver waits onto `entries` | killed: 8 F 2 E | `:wait-set` equality, `not-any? :receiver-only`, stream-key, resume wakes with a diagnostic |
| keep receiver `:code`/`:code-aliases` | killed: 6 F | `:code` equality root+child (and `:wait-set`, segment aliases shift) |
| seed `used` with fixed keys only (reuse receiver key) | killed: 2 F | collision case: `(= receiver-stream (get (:resources collision) stream-key))`, `collision-stream-key` not in receiver |

## Findings
| id | sev | file:line | finding | fix |
|---|---|---|---|---|
| R2 | P2 | src/cljc/yin/vm/ucf/rows.cljc:2090-2091, 2222-2237 | `restore-task` starts from `recv` with its `:yin.k/closes`, `:yin.k/issued`, `:yin.k/gate`, `:yin.k/custody`, `:yin.k/recovery-waits/-parked/-cell-ids` intact; `with-custody` only overwrites some of them on the exclusive/fenced paths (table above). Pinned contract lost at 8d. | Own src slice F1b: `base` starts from `(dissoc recv :yin.k/closes :yin.k/issued :yin.k/gate :yin.k/custody :yin.k/recovery-waits :yin.k/recovery-parked :yin.k/recovery-cell-ids)` (same for the spawned child), then `with-custody` sets what the lower decides. Add to this test: poison those seven keys on `populated`; assert `(not (contains? restored k))` for each on the fork lower, `(= (select-keys clean ks) (select-keys restored ks))` for the same keys; one exclusive lower (rows_custody_test's grant helpers) asserting `(empty? (:yin.k/closes m))` and `(= 0 (:yin.k/issued m))`. Document the registry: `(is (some? (module/resolve-module (:modules restored) 'receiver.mod)) "the composition's registry is the receiver's")` until the owner rules on linked entries. |
| R3 | P3 | rows.cljc:2240 docstring | unchanged from first pass: "recv holds no link wait of its own". | one sentence |
| R6 | P3 | collab engineer report line 26 | "No receiver-isolation defect found" is a statement about the seven fields only; the custody keys were scoped out, not checked. | Ledger entry pointing at R2. |

## Verdict
SIGN-OFF, scoped: this test closes F1 as specified (seven fields, resources, child, resume) and is landable as is; it is not vacuous and kills all six mutants. R2 is a src defect outside this test's claim, fixed in its own slice F1b with the assertions listed above; F1b joins F2 as a gate before `srvm-phase8` merges to master. Node/Dart lanes for this namespace remain to be run at that landing.
SIGN-OFF
