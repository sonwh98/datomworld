Created-Local: 2026-10-13 +07
# Slice 7a report: custody on rows, the rows inspector, the authority's fixtures (opus, engineer)

Worktree `srvm-phase7a`, from `23a8caef`. Nothing committed. JVM only, all runs in the foreground (one quick-tier run of every `yin.vm.ucf.*` namespace went past the 10-minute foreground limit, finished in the background and was read from its log).

## Verdict

7a is done as the plan's slice section and §3.1, §3.3 and §3.4 describe it, and its own acceptance holds: the two new suites, the ledger fixture, crash cuts, the eight authority tests and the holder tests that use fixtures all pass. `rows_test` and the safepoint harness pass with **no root id moved**.

**7a cannot land alone (design defect D1 below).** The plan's switch of the authority to the rows inspector makes the authority refuse every version-1/2 body. 141 deftests in six old-codec D/E suites now fail. Those suites are re-based in 7b/7c, and I proved the switch is the only cause. I did not work around it, because a fix would change semantics; the decision belongs to the orchestrator.

## Verification

- Lint: `clojure -M:kondo` needed approval in this session, so I used `clj -M:kondo --lint <files>`. Final run over all 22 changed `.clj[c]` files: 0 errors, 0 warnings.
- Quick tier, new and touched namespaces plus `rows_test`, the harness, `layout_test`, the old `checkpoint_test` and `lift_v1_test` (20 namespaces): **360 tests, 7188 assertions, 0 failures, 0 errors** (`target/7a-final.log`).
  - `rows_checkpoint_test`: 17 deftests.
  - `rows_custody_test`: 11 deftests, 107 assertions before the M3 fix added two more.
- Slow tier (`-i :slow`) on the same namespaces plus `rows_test` and the harness: 4 tests, 2724 assertions, 0 failures.
- Every `yin.vm.ucf.*` namespace, quick tier (`-r 'yin\.vm\.ucf\..*'`, `target/7a-ucf-quick.log`): 694 tests, 648 failures and 89 errors. They are all in six suites (see D1):

  | Namespace | Failing deftests | Failing assertions |
  |---|---|---|
  | `holder.driver-test` | 92 | 294 |
  | `compose-rows-test` | 35 | 335 |
  | `compose-test` | 6 | 35 |
  | `stage-e-rows-test` | 4 (the quick ones) | 35 |
  | `fencing-rows-test` | 3 | 30 |
  | `handoff-v2-authority-test` | 1 | 8 |

  No other `yin.vm.ucf` namespace fails.
- Counterfactual check: I pointed the three authority requires back at `yin.vm.ucf.checkpoint` and ran `fencing-rows-test`, `handoff-v2-authority-test` and `compose-test`: 21 tests, 332 assertions, 0 failures. I then restored the files (byte-compare verified).
- Node and Dart were not run, as instructed. The new code follows the portability rules: no `for`, no private var access, catches with `:cljd` first, start-from-`{}` assoc, no EDN strings. The `version-float` fixture relies on Node keeping a `Float64` carrier and on Dart's `integer?` rejecting a double, both checked in the source (`rows/decode` gates on `code/reg?`), but this is unverified on those hosts.

## Files

Created:

| File | Lines | What |
|---|---|---|
| `src/cljc/yin/vm/ucf/rows/checkpoint.cljc` | 102 | The rows inspector: `inspect address bytes`, `inspect-pack wire`. Address check, canonical CBOR, `rows/decode`, a `:task` root, then `rows/custody-baseline`; adds `:yin.k/root` |
| `test/yin/vm/ucf/rows_support.cljc` | 238 | Semantic-register machine builders mirroring `lift_support` (it reuses that namespace's AST helpers, one-slot streams, `serve-table`, header parts and `with-op-id`): `new-machine`, `project` with `source-index`, `parked-reader`, `parked-writer`, `parked-explicit`, `parked-linker`, `parked-installer`, `halted-machine`, `halted-with-cursors`, `parked-module-store-cursors`, `lift`, `attacher` (counting), `receiver` |
| `test/yin/vm/ucf/rows_checkpoint_fixtures.cljc` | 452 | Five bases lifted under a header; 50 mutations (task edits re-emitted, raw pack edits where the defect is the carrier's own); `render`/`read-file`/`fixture`; editing helpers the authority tests use (`base`, `pack-of`, `edit-task`, `edit-custody`, `waits`, `update-put`, `set-op-id`, `update-child`, `enc`) |
| `test/resources/yin/vm/ucf/rows-checkpoint-v3.txt` | 917 | 55 pins, 3 anchors with full bytes |
| `test/yin/vm/ucf/rows_checkpoint_test.cljc` | 203 | 17 deftests |
| `test/yin/vm/ucf/rows_custody_test.cljc` | 275 | 11 deftests |
| `test/resources/yin/vm/ucf/ledger-v2.txt` | 22 | Replaces `ledger-v1.txt` (deleted with `git rm`, so the deletion is staged) |

Changed:

- `src/cljc/yin/vm/ucf/rows.cljc` (411 lines changed per `git diff --stat`, now 2219 lines):
  - `pack`: rows sorted by id, used by `emit`, `emit-numbered` and `frame->rows`.
  - The custody section: `max-exact`, `write-slots`, `custody-baseline`, `pack-address`.
  - `lift-task :header`: `header-custody` and `protect!`, `:op-seq-exhausted`, and the grammar run before emitting.
  - `check-custody!` rewritten as the custody stage: grammar, then fork under `:exclusive`, then `accept-grant!` ported verbatim. It runs once after the three per-tree stages and before `attach-streams!`.
  - `with-custody` in restore: issue numbering, gate, custody state, and the fenced gate.
- `authority/{grant,completion,admission}.cljc`: the require switched to `yin.vm.ucf.rows.checkpoint` under the same alias, so each call site is unchanged; docstrings updated.
- Fixture bodies only:
  - Named in the plan: `ledger_fixtures`, `ledger_fixture_test` (docstring), `grant_test`, `completion_test`, `inherited_test`, `front_test`. `crash_cut_test` needed no edit; it follows `lf/park`.
  - Also needed (deviation 1): `admission_test`, `input_test`, `reclaim_test`, `holder/evidence_test`, `holder/reader_test`, `holder/writer_test`.
- `test/yin/vm/semantic_register/safepoint_harness_test.cljc`: one expected value (deviation 2).

## What the code does (for review)

- **Custody slot (root slot 16):**
  - nil for a fork of any kind (owner Q1).
  - `{:yin.k/policy :yin.k/exclusive :yin.k/occurrence :yin.k/arbitration :yin.k/next-op-seq}` plus `:yin.k/origin` for blocked or parked exclusive roots.
  - `{:yin.k/origin}` alone for a halted exclusive root.
  - nil on every child.
- **`custody-baseline`** is the version-1 header and op-id grammar of `checkpoint.cljc`, keyed off the slot. On top of that it:
  - refuses any key outside the five custody keys (`:custody-key`), so lease, epoch and holder cannot enter a body;
  - refuses a slot that is not a map (`:malformed-custody`);
  - checks each task's kind and role, that each `:install` pending names an install row, and that every child slot is nil;
  - refuses any op-id in a fork tree (`:fork-op-id`, closing continuation §11 Q4 for 7a).

  Paths are relative to the root task: `[:custody k]`, `[:frames i :op-id …]`, and `[:installs <name> …]` inside children. The intent digest is BLAKE3 of the canonical `[:yin.k/append target-identity payload-slot]`, where `[:row n]` becomes `[:row id]`.
- **The lift** runs the grammar over the abstract task before emitting, the same contract as the version-1 lift running its inspector over its own bytes. It does not decode its own output, which keeps continuation §7.1 step 9 true. A fork lift of a machine that carries op-ids therefore refuses `:fork-op-id`; before 7a the op-ids travelled verbatim.
- **The lower's custody stage**, in order: grammar; then a fork under `:exclusive` is `:yin.k/profile-mismatch`; a halted exclusive root needs no grant and is gated `:ended`; `:fenced` skips the grant and gates `:exporting`, with waits moved to `:yin.k/recovery-waits`; otherwise `accept-grant!` runs. The grant's `:checkpoint` must equal `rows/pack-address` of the wire, not the root id; a test pins that the root id is refused as `:not-holder`.
- **Restore:**
  - `:yin.k/custody` comes from the evidence, with `:yin.k/next-op-seq` equal to the slot's value exactly.
  - In an exclusive tree every task numbers its `:put` waits (`:yin.k/issue`, `:yin.k/issued`) and is gated `:running`, as `resume-task*` does.
- **The two identities:**
  - The pack address is the ledger's `:yin.k/id`, the content-store key, the grant's checkpoint and the report's result.
  - The root id is the baseline's `:yin.k/root`.
  - Tested in `the-pack-address-is-storage-and-the-root-id-is-semantic`: address = BLAKE3 of the bytes; root ≠ address; root = the root row's own hash; rows sorted; re-emitting a decoded pack gives the same bytes. Also tested in `a-pack-variant-of-one-root-shares-its-baseline`.

## Mutation checks

Driver: `target/7a-mut/{mut,run}.py`. Each mutant was applied to a backup-guarded copy, run against `rows-custody-test`, `rows-checkpoint-test`, `authority.grant-test` and `ledger-fixture-test`, then restored. Each restore was byte-compared with a clean copy; all were clean.

| # | Mutant | Caught? | By |
|---|---|---|---|
| M1 | Custody header by kind swapped (`header-custody` branches) | yes | `the-lift-writes-the-custody-slot-by-kind`, every base (they no longer lift), 46 deftests |
| M2 | A grant check skipped (holder vs binding) | yes | `every-grant-check-refuses-before-any-attachment` ("another holder") |
| M3 | Protection, one direction dropped (an enrolled stream classed otherwise accepted) | **survived at first**; yes after the fix | The op-id presence check that follows masked it on the successor. I added the isolating case (a reader pack with no retained write) to `protection-is-checked-in-both-directions` |
| M4 | Counter restore off by one | yes | `the-counter-is-restored-exactly`, `an-exclusive-root-lowers-under-its-grant`, `install-children-lower-…` |
| M5 | Fork under `:exclusive` accepted | yes | `a-fork-is-refused-under-exclusive` |
| M6 | Custody stage after attach | yes | The zero-attach assertions in 4 deftests (30 assertions) |
| M7 | Unsorted `emit` | yes | `the-file-holds-exactly-the-declared-bytes` (anchors), `the-pack-address-…`, the ledger frames, 14 deftests |
| M8 | Inspector accepting a mutated row (decode's row-hash check off) | yes | `each-mutation-is-refused-as-it-names` (`row-tampered`) |
| M9 | Baseline root ignored | yes | 11 deftests, including `a-conflicting-variant-is-refused` (`variant-other-root`) and the ledger projection digest |
| M10 (extra) | The lift skips the grammar | yes | `the-lift-refuses-what-custody-forbids` |

## Deviations

1. **Six more test files rebased.** `authority/{admission,input,reclaim}_test` and `holder/{evidence,reader,writer}_test` offer fixture bodies through `grant/offer!` and break without the switch. Each change is a three-to-five-line `body` helper.
2. **One harness assertion changed.** `carrier-steps-run-in-order-over-the-tree` expected the phase-6 refusal `[:yin.k/unsupported :custody]` for slot `{:custody :exclusive}`. The plan replaces `check-custody!`, so it now expects `[:yin.k/undecodable :custody-key]`. Nothing else in the harness changed, and no root id moved.
3. **`:fenced` is minimal.** It skips the grant and protection, gates `:exporting` on every task and moves the waits to `:yin.k/recovery-waits`. `:yin.k/recovery-cell-ids`, the exact id counter, the recovery header check and moving parked records into the record are left to 7b (plan §3.2 costs the fenced mode there).
4. **Refusal kinds and paths are mine:**
   - New kinds: `:custody-key`, `:malformed-custody`, `:policy` (the old inspector gave a policy mismatch no kind), `:kind`, `:role`, `:child-header`, `:fork-op-id`, `:incomplete-install`.
   - Paths: `[:custody …]`, `[:frames i …]`, `[:installs <name> …]`.
   - Carrier-level refusals keep `rows/decode`'s own: a missing tag is `:yin.k/profile-mismatch :version` on rows, not `:undecodable :body` as on version 1.
5. **No op-id-on-variant fixture.** It is structurally impossible on rows: the pending table gives `:next` no op-id slot. I added fixtures the old set lacked: `frame-root`, `row-tampered`, `root-role`, `custody-epoch`, `custody-lease`, `custody-not-map`, `fork-op-id`, `variant-other-root`.
6. **The grammar lives in `rows.cljc`** (`custody-baseline`). `rows.checkpoint` is a thin wrapper around it, because `lower-task` must run the grammar too and `rows.checkpoint` requires `rows` (a cycle otherwise). Behaviour is what plan §3.3 assigns to the inspector.
7. **Fixture shape.** `rows_checkpoint_fixtures` takes the `refusal` map a mutation must contain, instead of the old `expected` and `place` pair.
8. **Accessor alias kept.** The authority still calls the inspector through the alias `checkpoint`.

## Design defects found (separate list)

- **D1. 7a cannot land alone.** The authority switch (plan §2, §4 7a "Change") refuses version-1/2 bodies at `offer!`, `report!` and `accepted-baseline`. The holder, compose and Stage E paths still offer such bodies until 7b/7c, so 141 deftests in six suites fail (table above; proven by the counterfactual). The plan's 7a acceptance never mentions them.
  - Options for the orchestrator:
    1. Merge 7a only together with 7b and 7c.
    2. Move the three-line authority switch out of 7a into 7c.
    3. A temporary version dispatch in the authority. This contradicts "no version dispatch" and is not recommended.
  - `handoff_v2_authority_test` (an old-codec test the plan keeps until phase 8) also breaks permanently under any option. It needs deleting or re-basing, which the plan does not list.
- **D2. Plan §1.1 row-1 contradicts §3.3.** Row-1 wants a re-emitted id-counter variant with "a new root id and new pack; equal baseline". With `:yin.k/root` in the baseline, which this brief requires, such a variant's baseline differs, and the ledger refuses it as `:variant-conflict` (pinned by `grant_test` `variant-other-root`). Under sorted `emit`, the only equal-baseline variant is the same root in another row order. I used that for `variant-equal`, `completion_test` and `inherited_test`.
  - Stage E row-1 (7d) needs a ruling: either the variant is a conflict, or the root leaves the baseline.
  - Related: `rows/decode` accepts rows in any order, so "one root, one pack" holds for what `emit` makes, not for what a reader accepts. Should decode refuse unsorted packs? I left decode unchanged.
- **D3. Draft amendments to list (no doc edited):**
  - transport-tuples §5.5 table: fork is nil in every row (owner Q1), and the slot admits only the five custody keys.
  - continuation §2.5, §2.6, §7.1 step 8 and §7.2's stage table: custody is not phase-6 nil-only any more; the step-10 refusal `:yin.k/unsupported :custody` is replaced by the grammar's kinds and the grant refusals; the stage runs once for the tree after the three per-tree stages.
  - continuation §11 Q4: closed for the op-id rules (correlation agreement is still not implemented).
  - UCF 7.11.1: the inspector citation moves from `yin.vm.ucf.checkpoint` to `yin.vm.ucf.rows.checkpoint`.
- **D4. The lift serves streams before custody refusals.** v1 refused `:op-seq-exhausted` before lifting; rows refuses it after the walk has called `serve!`. Harmless to the lower's zero-attach law, but a holder that counts serves will see them (7b's export record).

## What 7b needs from me

- `rows/lift-task` with `{:header h :source-index …}` is `holder.export/prepare` and `encode`'s new path; `h` is the same header shape (`:yin.k/enrolled` stays local).
- `rows/pack-address wire` is the storage key to store and fence. The grant's `:checkpoint` must be that address.
- `rows/lower-task recv wire attach! {:grant g :protection p}` is the driver's `lower`; `{:exclusive true}` refuses a fork; `{:fenced true}` is the rehydration entry. Finishing fenced is 7b's: recovery-cell-ids, exact id counter, parked records into the record, the header check against `:yin.k/state`.
- `rows.checkpoint/inspect` answers the old inspector's keys plus `:yin.k/root`; `reopen-holder` and `rebuilt-cell` can read `:yin.k/kind`, `:yin.k/occurrence` and `:yin.k/next-op-seq` from it.
- `rows_support` builders for the driver, writer and reader tests, and `rows_checkpoint_fixtures`' editing helpers for packs.
- D1 decides whether 7b lands together with 7a.
