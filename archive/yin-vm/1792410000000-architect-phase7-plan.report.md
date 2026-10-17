Created-Local: 2026-10-13 16:10 +07
# Phase 7 plan: Stage D/E rerun over the row carrier (Architect, plan only)

Author: claude-fable-5-1. Worktree `srvm-phase7-plan` at `781a9ba8` (phases 1-6 merged). Read: the four design documents named in the brief, `rows.cljc`, `handoff.cljc` (export-task, the reader, resume-task*, the recovery paths), `holder/{export,driver,writer,reader}.cljc`, `checkpoint.cljc`, `custody.cljc`, `ledger.cljc`, `authority/{grant,completion,admission}.cljc`, `compose.cljc`, the Stage E world, scenarios, peer, process test and rows test, the checkpoint and ledger fixtures and tests, `lift_support.cljc`, `handoff_v2_holder_test.cljc`, the semantic-register safepoint harness, `dao.jing.{file,mem}` put contracts, `yin.repl`'s composition wiring. No code or doc was edited.

## Summary for the owner (15 lines)

1. Every Stage D/E test today runs on the **old** `yin.vm.semantic` kernel through `handoff.cljc` bodies (version 0 stream-CBOR, version 1 and 2 jing-CBOR); the row carrier (version 3) runs only on the semantic-register kernel and only with `custody nil`.
2. Phase 7 is therefore one move, done in four slices: teach the row carrier custody, re-base the holder (export record, recovery object, driver lower) and the authority's inspector on row packs, then rerun the composition and the Stage E crash matrix on the semantic-register kernel, three hosts.
3. The driver, writer, reader, compose, authority, ledger and front are **kernel-agnostic**: they read machine fields that `yin.vm.engine` owns (`:wait-set`, `:installs`, `:yin.k/gate`, `:yin.k/custody`). They change only where they call `handoff/*` or `checkpoint/inspect` (eight call sites).
4. Nothing of version 0/1/2 is rewritten; it dies at phase 8. Phase 7 deletes only what it replaces: the version-1 export-recovery object, the holder's use of `export-task`/`resume-task`, the authority's use of the version-1 inspector. The old codec and its direct tests stay green and dead until phase 8.
5. One hard fact drives the design: `dao.jing` content stores **verify bytes against the address on put**, and a row pack's bytes do not hash to its root id. So the checkpoint has two identities: the pack address (storage, the ledger's `:yin.k/id`) and the root id (semantic, inside the baseline). `emit` must sort its rows so one root gives one pack on every host.
6. Custody travels as the root's `custody` slot exactly as the draft's §5.5 table says (policy, occurrence, arbitration, next-op-seq, origin); lease, epoch and holder never enter a body (UCF 7.2.1 stands); op-ids already have their slots on `:put`, `:ffi-request` and `:link-request` pendings.
7. The recovery object loses its second "snapshot" body (rows already carry the whole store and every parked record) and keeps only holder-local engine state (gate, issues, issued, origins) beside the pack.
8. Crash cuts stay meaningful unchanged if the pack is stored as **one** blob; storing rows individually would add a torn-pack cut family and is not recommended.
9. `:resume-mode` stays out: the version-2 stack lane is frozen until phase 8, and rows carry `:act`/`:tail`, which subsume it. No work.
10. Three questions need the owner: fork spelling in the custody slot (nil as landed, or the draft's explicit `{:yin.k/policy :yin.k/fork}`); the REPL custody gap (the REPL composes the old kernel until phase 8, so its custody path is dead between 7 and 8 unless the kernel switch is pulled forward); whether the Q3 install checks (link-id = parent, image address = manifest) land in 7b.
11. Slices: 7a custody on rows and the rows inspector with regenerated fixtures; 7b holder export, recovery v2 and the driver's lower; 7c the composition and the compose/fencing row tests; 7d the Stage E world and matrix across processes; 7e docs. 7a → 7b → 7c → 7d, with 7e and the 7d world port overlapping 7c.
12. Estimate: 12 working days median (9 implementation, the rest 2-4 codex rounds per slice and the slow-tier runs), 17 at the worst; phase 6's three slices landed in three calendar days with the design pre-written, so calendar could be shorter.
13. Biggest risks: the identity split surprising the ledger's variant rules (mitigated by sorting rows); the fenced-wait rehydration of install children on the semantic-register kernel, exercised only by the harness today; Dart runs only the in-process Stage E form (as today).
14. Nothing found that forces a redesign of rows or the ledger; the de Bruijn lanes are not Stage E participants (the 61/350 matrix covers only the cross-engine laws).
15. Verdict at the end: **NOT READY** until the owner answers the three questions in §3.10; everything else is decided below and can start on 7a the moment the fork-spelling answer lands.

---

## 1. Inventory: every Stage D/E scenario and test, its transport today, what it needs on rows

Vocabulary (UCF 7.5.3): a *cursor* is the immutable `dao.stream` value; a *cursor cell* is the carrier's box (`:yin.k/cc-<n>` in rows, `:yin.k/c-<n>` in versions 0-2); a *cursor-ref* is the sealed reference a program holds; a *cell* is only the heap box.

Transport codes: **v0** = `handoff/export-task` with a nil header, stream CBOR, fork; **v1** = the same with a header, jing CBOR, exclusive; **v2** = `{:version 2}`, the four-profile body (frozen); **rec1** = the export-recovery object version 1 (`holder.export/freeze`, embeds a v0/v1/v2 body plus a full-census snapshot); **ckpt** = `checkpoint/inspect` over v1/v2 bytes; **ledger** = `yin.vm.ucf.ledger` records (no body); **rows** = `rows/lift-task`/`lower-task`, version 3.

### 1.1 Stage E (the acceptance matrix, `stage_e_*`)

The world (`stage_e_world.cljc`) builds both machines with `lift-support/new-machine`, which is `yin.vm.semantic/create-vm` (old kernel). Every life drives `compose` only; the export is v1 (`:export-version` nil), the recovery object rec1, the offer carries v1 bytes, the authority inspects with ckpt. Dart runs the in-process form only; JVM and Node also run the separate-process form over the four ordered pairs.

| Scenario (ids) | Count | Parking shape (continuation §5.2 row) | Transport today | Needs on rows |
|---|---|---|---|---|
| `[:fork]` | 1 | row 1 (blocked read, `:parked` world) | v0 lift, no bracket | rows lift with no header → root custody nil (or fork spelling, §3.1); the driver's `fork-step` answers pack bytes and address |
| `[:row-7 cut]` × 5 cuts | 5 | row 1 at the source; successor is the same shape | v1 export, rec1, ckpt on offer and report | rows export under header; recovery v2; rows inspector on offer and report; successor pack address as `:yin.k/result` |
| `[:row-7 :failure-lower]`, `[:row-7 :lost-authority]` | 2 | row 1 | v1, ckpt | as above; failure-lower is `attach!` returning not-found inside `lower-task` step 11 |
| `[:row-4 cut]` × 3 | 3 | row 1 then row 2 (`:rows` world: read, write to the enrolled target, write to the side log, halt) | v1 with an op-id on the `:put`, ckpt baseline, admission | rows lift with the enrolled set (new `:unprotected-pending`/unenrolled checks); op-id slot already exists; baseline from the rows inspector |
| `[:row-3 variant]` × 2 | 2 | rows 1, 2 | v1, admission | as row 4 |
| `[:row-5 variant]` × 2 | 2 | rows 1, 2; evicted kept cursor (gap) | v1; input records | unchanged protocol; the kept cursor position is a cursor-cell body `[stream position]` in rows |
| `[:row-2 cut]` × 14 cuts | 14 | row 1 | v1 export phases: mint, serve, store recovery (`store-put-*`), store body (`body-put-*`), fence, offer | the same phases over the pack; see §3.4 for why the cut set stays |
| `[:row-2-carrier outcome]` × 4 | 4 | row 1 | v1 offer appends, abort | unchanged (`export/abort` is machine-level) |
| `[:row-1]` | 1 | row 1; a second encoding minted by rewriting `:yin.k/id-counter` in the CBOR body | v1 variant bytes | the variant must be re-emitted: decode pack → change the root's id-counter slot → `emit` → new root id and new pack; equal baseline (§3.3) |
| `[:row-6 ...]` × 3 | 3 | row 1 | v1; partition and lost reply | unchanged |
| `[:clause-8 variant]` × 2, `[:clause-8 :closed]` | 3 | rows 1, 2; cross-target | v1, admission, inherited ids read the accepted checkpoint through ckpt | `accepted-baseline` must inspect the stored pack with the rows inspector |
| `[:clause-3 :regrant]`, `[:clause-3 :carried-put]` | 2 | row 2 with an id assigned before export | v1 carries the id | rows carry `:op-id` on the `:put` pending slot; the successor's counter from the custody slot |
| `[:journal cut]` × 7, `{:fail ...}` × 2 | 9 | row 1 | v1; inbox retention, journal brackets | unchanged except the fence record's two addresses are recovery v2 and the pack |

Total: 51 scenarios (`all-scenarios`), 12 `deftest`s in `stage_e_rows_test` (8 slow) and 12 in `stage_e_process_test` (all slow, JVM only, four host pairs, Node peer compiled by shadow with the peer as main).

**What the world itself needs:** `parked-reader-over` and `reading-writer` built with `yin.vm.semantic-register/create-vm` and `sr/load-vector` over `linearize/project` (as the harness's `machine`/`load-program` do), a `:source-index` supplied to the lift (the composition's `{A #{tree}}`, continuation §6.1), `holder-seams` unchanged, and `offer-variant!` re-emitting a pack instead of patching CBOR.

### 1.2 Stage D (the driver, the holder halves, the compose rows)

| Test file (deftests) | Transport today | What it tests | Needs on rows |
|---|---|---|---|
| `holder/driver_test.cljc` (118) | v0/v1/v2 through `handoff/resume-task`, `export/*`, rec1, ckpt | the whole driver: brackets, inbox, grant, lower, run, exit, reopen, abort, enrollment | machines from a semantic-register support namespace; the three driver call sites (`lower`, `step-validating`, `rebuilt-cell`/`reopen-holder`/`reopen-exit`) over rows; `a-version-two-fork-lift-is-the-composition-choice-test` deleted at 7 (its subject is the frozen v2 lane; nothing else exercises `:export-version 2` through the driver) |
| `holder/export_test.cljc` (26) | v0/v1/v2 enter/prepare/encode/freeze/rehydrate/abort | the exporting state and recovery | `enter`, `abort`, hold refusals: unchanged (machine-level); prepare/encode/freeze/rehydrate: rewritten over rows and recovery v2; the "snapshot extends body" and "transitive module store fixed point" tests disappear (rows carry everything once) |
| `holder/writer_test.cljc` (29) | machines with a hand-set `:yin.k/custody` map; no transport | assign, envelope, retention, discharge, closes | port the machine builders to the semantic-register kernel; logic unchanged (`writer.cljc` reads engine fields only) |
| `holder/reader_test.cljc` (24) | same | held observations, replay, frontier | same |
| `holder/evidence_test.cljc` (10) | ledger | binding evidence | none |
| `fencing_rows_test.cljc` (4) | v1 lift + writer + reader + authority, no compose | rows 2, 4, 5 fencing halves | rows lift under header; `lower-task` with the grant opts |
| `compose_test.cljc` (13) | v0/v1 through compose | open!, fork label, substrate checks, reopen, the plain composition | the `:receiver` and `:machine` seams become semantic-register machines; `the-plain-composition-loads-and-drives-with-no-yin-repl-test` reads the REPL's wiring (see §3.10 Q2) |
| `compose_rows_test.cljc` (41, 1 slow) | v1 through compose; rec1; ckpt | rows 1-8 same host, completion recovery, journal cuts around freeze/storage/fencing, the content-store cuts | as compose; the content-store cut tests must see two puts (recovery v2, pack) as today |
| `lift_v1_test.cljc` (29) | v1 `export-task` | header rules, enrolled refusals, canonical bytes, cursor cells | **kept until phase 8** (tests the old codec directly); its custody rules are re-pinned on rows in a new `rows_custody_test` (7a) |
| `handoff_v2_custody_test.cljc` (5), `handoff_v2_holder_test.cljc` (6), the other `handoff_v2_*` (8 files), `handoff_test`, `handoff_v1_test`, `scalar_round_trip_test` | v0/v1/v2 codec | the old codec and layouts | kept until phase 8, except `a-version-2-recovery-snapshot-freezes-and-rehydrates-fenced` (tests rec1 freeze, deleted at 7) and `a-poisoned-receiver-behaves-as-a-clean-one` (goes through `resume-task` only, keep) |

### 1.3 Stage C (the authority), where a body enters

| Test file (deftests) | Body use today | Needs on rows |
|---|---|---|
| `checkpoint_fixtures.cljc`, `checkpoint_test.cljc` (14), `test/resources/.../checkpoint-v1.txt` | five bases lifted by the old semantic kernel through v1, 48 mutations, 3 byte anchors | **kept as the old inspector's test** until phase 8; a new `rows_checkpoint_fixtures.cljc` + `rows-checkpoint-v3.txt` pins packs lifted by the semantic-register kernel (same five bases, the mutations re-expressed as row edits with rehash, §5.2's "raw row edit" rule) |
| `ledger_fixtures.cljc`, `ledger_fixture_test.cljc` (6), `ledger-v1.txt` | `park`/`halted` bodies derived from the checkpoint fixtures and offered/reported through `grant/offer!` and `completion/report!` | regenerate over packs (the projection digest and every frame change); file renamed `ledger-v2.txt` |
| `crash_cut_test.cljc` (2) | `lf/world`, `lf/park` bodies | follows the ledger fixtures |
| `authority/grant_test.cljc` (16), `completion_test.cljc` (21), `inherited_test.cljc` (18), `front_test.cljc` (29), `admission_test.cljc` (24), `input_test.cljc` (20), `reclaim_test.cljc` (21), `completion_fold_test.cljc` (8) | bodies through `checkpoint-fixtures`/`ledger-fixtures` where an offer, report or inherited id is admitted; otherwise ledger only | the fixture switch above; the inspector call in `grant/offer!`, `completion/report!`, `admission/accepted-baseline` becomes the rows inspector |
| `ledger_test` (14), `custody_test` (4), `durability_test` (10), `evidence_test` | ledger only | none |

### 1.4 Phase 6 assets phase 7 builds on (no change expected)

`rows_test.cljc` (31), `semantic_register/safepoint_harness_test.cljc` (27; rows 1-24 of §5.2 on three lanes, laws 1-6), `frame_test`, `layout_test`. The harness's `machine`, `load-program`, `project`/`source-index` and `canonical-under` are the templates for the new support namespace.

---

## 2. Old transport paths: deleted at phase 7 versus kept until phase 8

**Deleted or replaced at phase 7** (their subject is replaced, so keeping them would test a dead path):

- `holder.export/prepare`, `encode`, `freeze`, `rehydrate-fenced` as written over `handoff/export-task`, `inspect-recovery-body` and `rehydrate-fenced-body`: rewritten over rows. `enter` and `abort` survive unchanged.
- The export-recovery object version 1 (`:yin.k/export-recovery`, `:yin.k/version 1`, `:yin.k/snapshot-*`): replaced by version 2 (§3.2). No reader of version 1 remains.
- The driver's calls: `handoff/resume-task` in `lower` and `step-validating`; `checkpoint/inspect` in `step-validating`, `rebuilt-cell`, `reopen-holder`; `export/rehydrate-fenced` in `rebuilt-cell`; `:export-version` as a seam (compose's `holder-config-keys` and `driver-config` drop it). The `fork-step` keeps its shape over the rows lift.
- The authority's three inspector calls (`grant/offer!`, `completion/report!`, `admission/accepted-baseline`) switch to the rows inspector; the ledger's `:yin.k/baseline` shape gains `:yin.k/root` (§3.3).
- `lift-support`'s role as the D/E machine source: superseded by a semantic-register support namespace; `lift-support` itself stays for the old codec's tests.
- Tests deleted at 7: `driver_test/a-version-two-fork-lift-is-the-composition-choice-test`, `handoff_v2_holder_test/a-version-2-recovery-snapshot-freezes-and-rehydrates-fenced`, `export_test`'s rec1-specific rows (`complete-export-recovery-is-fenced-and-reproducible`, `module-store-dependencies-are-censused-to-a-fixed-point`, `version-one-recovery-retains-operation-ids-issues-and-local-enrollment`, `snapshot`-shaped assertions) are replaced by their rows equivalents in the same file.

**Kept until phase 8, untouched** (the brief's rule: no new work on the old kernels and compilers):

- `handoff.cljc` whole (version 0/1/2 lift, reader, `validate-body`, `rehydrate-fenced-body`, `inspect-recovery-body`; the last two become dead code with no caller).
- `checkpoint.cljc` (the version-1/2 inspector) and `checkpoint_test`/`checkpoint_fixtures`/`checkpoint-v1.txt`.
- `remote.cljc`'s version 0-2 pending lift/lower (`lift-frame`, `lower-frame`, `lift-pending`, `lower-pending`); rows uses only `lift-marker`/`lower-marker`/`cursor-profiles`.
- `lift_v1_test`, `handoff_test`, `handoff_v1_test`, every `handoff_v2_*` test but the one deftest above, `scalar_round_trip_test`, `v2_support`, `lift_support`, `handoff-v2.txt`, `scalars-v1.*`.
- `custody.cljc`, `ledger.cljc`, `authority.cljc`, `authority/*`, `holder/{driver,writer,reader,inbox,evidence}.cljc`, `compose.cljc`: not old transport; they change only at the call sites listed above.

**Owner rulings honoured:** A is the only code identity (rows carry `{A vector}` and `:yin.k/trees`; the inspector names code by A only); the AST is never lost (`:source-index` is a lift input in every slice; the Stage E composition must supply it); cursor-cell sharing survives transport (the census, unchanged); BLAKE3 (row ids, pack address, intent digest, recovery address all under Jing's default); no backward compatibility (no version dispatch in the driver, fixtures regenerated, files renamed); native lanes excluded from Stage E.

---

## 3. Design questions phase 7 forces, with recommendation, cost, and who decides

### 3.1 Exclusive and holder custody on rows: which rows carry lease, fence and holder state

**Finding.** The root row's slot 16 `custody` is plain data (draft §5.5 table). UCF 7.2.1 is operative for version 3: "no body carries an epoch; the epoch is learned only from the grant binding". The holder state the driver needs at run time (`:yin.k/custody` on the machine: occurrence, lease, holder, epoch, arbitration, next-op-seq, `:protection`, `:input {:next :prefix}`, `:tenure`) is built by the lower from the **grant evidence**, never from the body (`accept-grant!` today). The fence (op-ids) already has slots: `:put [stream V op-id]`, `:ffi-request [... op-id]`, `:link-request [... envelope op-id]`.

**Recommendation (Architect decides).**
- The root `custody` slot carries exactly the draft's table for `:blocked`/`:parked` exclusive roots: `{:yin.k/policy :yin.k/exclusive :yin.k/occurrence O :yin.k/arbitration {...} :yin.k/next-op-seq n}` plus `:yin.k/origin` on a successor; a halted exclusive root carries `{:yin.k/origin ...}` only; children nil (as now). Lease, epoch, holder, enrolled set, protection classes: **never** in a row.
- `lift-task` gains a `:header` option (the same header `holder.export/prepare` takes today). With a header it writes the slot by kind, refuses `:op-seq-exhausted` at 2^52-1 on a blocked or parked root, `:unprotected-pending` for a retained `:put`/`:ffi-request`/`:link-request` on an enrolled target without an op-id (root or child), and `:yin.k/unsatisfied` naming the stream for an op-id on an unenrolled target; a halted root with a header but no origin is refused (not downgraded). These are the version-1 rules, moved (`handoff.cljc:623-657`, `:1513-1523`).
- `lower-task` replaces `check-custody!` (nil only) by the version-1 custody stage: fork root under `:exclusive` → `:yin.k/profile-mismatch`; exclusive root of kind `:blocked`/`:parked` requires `:grant` + `:protection` opts and runs the seven grant checks of `accept-grant!` (`handoff.cljc:3343-3420`, ported verbatim, keyed off the slot instead of body keys); every marker's protection class must match enrollment in both directions; op-id presence must equal enrollment per retained write; the restored machine gets `:yin.k/custody` from the evidence and `:yin.k/next-op-seq` from the slot **exactly** (no max); a halted exclusive root lowers with no grant, gated `:ended`. A `:fenced` option (recovery, §3.2) skips the grant and gates `:exporting`.
- The custody stage keeps its place: all four stages over the whole tree before the first `attach!` (continuation §7.2, codex F1/F2/F4/F7).

**Fork spelling (OWNER decides, §3.10 Q1).** Phase 6 writes `custody nil` for a fork root; the draft's table spells `{:yin.k/policy :yin.k/fork}` on blocked/parked fork roots and nil on halted ones. Both decide the policy from the root alone. Nil is landed and every pinned root id in `rows_test` and the harness depends on it; the explicit spelling costs about half a day of pin regeneration and makes the slot's reader one `case` on `:yin.k/policy`. I recommend **keeping nil** and amending the draft's table (fork = nil in every row), on the minimal-diff principle; the owner may prefer the draft's spelling.

**Cost.** 1.5 days in 7a (lift option, three refusals, the ported grant checks, the protection stage, tests on three hosts).

### 3.2 The export-recovery object re-based on rows

**Finding.** Version 1 (`holder.export/freeze`) stores two bodies: the published body and a "snapshot" lifted with `::recovery true` (full census), because version-0 bodies omitted transitive module stores and the recovery must rehydrate everything. It also stores `:yin.k/header`, `:yin.k/descriptors` (the served set) and `:yin.k/state` (`gate`, `origins`, `issues` per wait, `issued`, children recursively). `rehydrate-fenced` restores the snapshot with `rehydrate-fenced-body` (gate `:exporting`, waits in `:yin.k/recovery-waits`, parked table emptied into the record, `:yin.k/recovery-cell-ids` mapping resource ids back to carrier cursor cells), re-prepares from the retained served table and proves the published bytes are reproduced.

**Recommendation (Architect decides).** Version 2:

```clojure
{:yin.k/export-recovery true
 :yin.k/version         2
 :yin.k/pack-address    a        ; the pack's own address (§3.3)
 :yin.k/root            B        ; the root id inside it
 :yin.k/pack-bytes      base64
 :yin.k/header          h        ; nil for a fork
 :yin.k/descriptors     #{...}   ; the served set, as today
 :yin.k/state           {:gate g :origins n :issued n :issues [..] :children {m state}}}
```

One body: the pack already carries the whole root store, every parked record and the module stores by reachability (continuation §2.5), so the "snapshot extends body" check and the fixed-point repair disappear. `rehydrate-fenced` = decode, verify `pack-address` and `root`, `lower-task` with `{:fenced true :header h}` (no grant, gate `:exporting`, waits moved to `:yin.k/recovery-waits`, parked records moved into the record, `:yin.k/recovery-cell-ids` from the census), then `prepare` from the retained served table and `encode` must reproduce `pack-bytes` exactly. Engine-local state that is not canonical content (`issues`, `issued`, `origins`, `gate`) stays in `:yin.k/state` as today; `:yin.k/issue` on lowered `:put` waits is assigned sequentially in carried order (what `resume-task*` does, `handoff.cljc:3643-3650`) and overwritten from `:issues` on rehydration. The recovery address stays the BLAKE3 of the recovery bytes.

**Cost.** 1.5 days in 7b (export.cljc rewrite, `:fenced` lower mode, export_test re-based).

### 3.3 Checkpoint and ledger embedding of a row body root

**Finding.** The authority stores the body bytes under `:yin.k/id` with `(:put-bytes-fn store)`, and both `dao.jing.mem` and `dao.jing.file` **refuse a put whose bytes do not hash to the address** (`mem.cljc:38`, `file.cljc:351`). The pack `{:yin.k/handoff true :yin.k/version 3 :yin.k/root B :yin.k/rows [...]}` does not hash to `B`. Further, `emit` builds `:yin.k/rows` as `(vec (vals rows))` of a hash map, so the pack's byte order is host-dependent today; `B` is not.

**Recommendation (Architect decides; the draft already allows it, §10 answer 2: "when a pack is addressed it gets its own address, distinct from the semantic root id").**
- `emit` sorts `:yin.k/rows` by row id (bytewise). One root → one pack on every host. Root ids do not change, so no pin moves.
- Two identities, each used where it belongs: the **pack address** `a = BLAKE3(pack bytes)` is the storage key and the ledger's `:yin.k/id` (a snapshot variant is an encoding, as today); the **root id** `B` is the semantic identity and enters the baseline as `:yin.k/root`. Two packs with one root are variants of equal baseline (impossible after sorting, but harmless). The offer request, the fence record and the report's `:yin.k/result` carry `a`; `offer-decision`'s `(jing/segment-address? (:yin.k/id fact))` holds for `a`.
- The **rows inspector** (`yin.vm.ucf.rows.checkpoint/inspect address bytes`): address matches bytes (`jing/segment-bytes-match?`), bytes decode through `rows/decode` (every row hash, canonical numbering, frames), the root's custody slot by kind (the version-1 header rules of `checkpoint.cljc:183-232` on the slot's map; children nil), every op-id on `:put`/`:ffi-request`/`:link-request` pendings in root and children against the root's occurrence, origin and counter (`check-op-id!` verbatim), duplicate ids refused, and the baseline `{:yin.k/kind :yin.k/root :yin.k/occurrence :yin.k/origin :yin.k/arbitration :yin.k/next-op-seq :yin.k/ops {op-id digest}}` where the intent digest is `BLAKE3(cbor [:yin.k/append target-identity payload-slot])` with the payload **as the wire slot holds it** (`[:lit x]` or `[:row id]`, both canonical). A fork pack answers `:yin.k/fork true` as `inspect-v2` does. Every refusal is data; nothing throws (as today).
- `admission/accepted-baseline` and `completion/successor-defect` read `:yin.k/next-op-seq` and `:yin.k/origin` from the baseline, unchanged. `ledger/offer-defect` is unchanged (`:yin.k/kind` ∈ `#{:blocked :parked}`, the baseline compared whole).
- Halted exit: the report's `:yin.k/result` is the halted pack's address; the terminal edge names it.

**Cost.** 1.5 days in 7a (inspector, sorted emit, fixtures regenerated, the three authority call sites, `ledger-v2.txt`).

### 3.4 Crash cuts: which cuts are meaningful when the body is a Merkle row set

**Finding.** The named cuts (`stage_e_world/cuts`) cut the progress journal around mint, serve, store intent/ack, two content-store puts (recovery first, body second), fence, offer; the authority's commit; the reply journal; and composition ticks. They are meaningful at the granularity of **one store put per object**.

**Recommendation (Architect decides).** Store the pack as **one** content-store object. Then every cut keeps its meaning: `:export/store-put-*` cuts the recovery v2 put, `:export/body-put-*` the pack put, and a pack is either whole or absent; a torn pack is impossible by the store's atomic insert. Storing rows individually (one object per row, Merkle-addressed) would make partial checkpoints possible and require a new cut family (`:pack/missing-row-before-fence`) and a new refusal path (`rows/decode` `:missing-row` at the authority), with no benefit in phase 7. Row-level storage is a phase-8+ option for deduplication across checkpoints and is out of scope. One new cut is worth adding cheaply: `:export/pack-put-after-recovery-missing` is already covered by `store-put-before` (recovery absent, nothing fenced); no addition needed.

**Cost.** None beyond keeping the two-put order in `export-fence-step`.

### 3.5 Publication of successors

**Finding.** The protocol (`exit-mint` → prepare → freeze → fence → report → release → closure; the successor offer refused `:awaiting-completion` then admitted) is body-agnostic except for three values: the successor header (built from the machine's `:yin.k/custody`), the report's result address and the stored bytes.

**Recommendation (decided).** No protocol change. The successor's header takes `:yin.k/next-op-seq` from the machine's custody map as today; the lift writes it into the slot; the inspector's `:counter-regression` check compares slot against baseline. The report carries the successor's pack address; `reopen-exit` rebuilds from the journaled mint and the fence as today. The one wrinkle: `reopen-holder` and `rebuilt-cell` read the kind and occurrence from the stored bytes through the inspector; the rows inspector answers the same keys.

**Cost.** Included in 7b.

### 3.6 Authority and capability tokens versus sealed refs on rows

**Finding.** Rows re-seal every stream and cursor-ref under the receiver's owner on lower (`engine/issue-ref`), authenticate refs on lift, and carry no credential (UCF 7.7.8: "the envelope carries no credential; the holder is authenticated by the attribution the composition supplies"). The owner ruled (2026-10-12) that ShiBi tokens are a configurable policy at the `:admit` seam, and (2026-09-27) that ShiBi is a tuple space emerging from index and query interpreters, with `dao.stream.remote` offering only a capability-agnostic seam.

**Recommendation (Architect decides; owner to confirm scope).** Phase 7 carries **no** capability token in any row and adds no row kind for one. Authority is ledger facts, attribution is the composition's resolver, tenure is lease evidence; sealed refs are the receiver's and are minted on lower. A token-bearing admission is ShiBi work at the admission seam, after phase 8.

**Cost.** None.

### 3.7 `:resume-mode` under version 2

**Finding.** The owner's ruling (orchestrator log, 2026-10-11) is that the version-2 stack lane stays frozen and does not carry `:resume-mode`. Rows carry `:act` and `:tail` rows; a `:tail` is the register kernel's `:return-result` delivery and an `:act` the `:write-result` one (continuation §4.2, §4.3), so the information `:resume-mode` encodes is structural in version 3.

**Recommendation (decided).** No work. Phase 7 does not touch version 2; version 3 needs no such key.

### 3.8 Two gaps the brief did not list but phase 7 meets

- **Continuation §11 Q4 (op-id rules on lower).** Closed by the rows inspector running inside `lower-task`'s custody stage for exclusive roots (below `next-op-seq`, unique across the tree, absent without origin, never the body's own occurrence). Fork roots refuse any op-id (`:fork-op-id`), as `inspect-v2` does. Decided, in 7a.
- **Continuation §11 Q3 (install verification).** `check-install!` verifies status, manifest name, exports and that the image loads, not link-id = parent nor image address = the manifest's. Adding both is about a quarter day in 7b. **OWNER decides** (§3.10 Q3); recommend yes, because the recovery object now trusts the pack for install children.
- **Continuation §11 Q1/Q10 (the `:running` wire phase).** Unchanged by phase 7; Stage E's installer scenarios use phase `:parked` only. Leave open.

### 3.9 Which kernels take part

Stage E runs the **semantic-register kernel only** (the reference lane). The de Bruijn kernels are covered by the cross-engine laws of the harness (61/350, accepted) and are not Stage E holders or sources; the holder driver never lowers through a layout. This follows the owner's matrix ruling and avoids three times the process matrix.

### 3.10 The owner's questions (blocking)

- **Q1, fork spelling in the custody slot.** nil (landed; recommended) or the draft's `{:yin.k/policy :yin.k/fork}` (half a day of pin regeneration; the draft is amended either way).
- **Q2, the REPL custody gap.** `yin.repl` composes the **old** `yin.vm.semantic` kernel (`repl.cljc:144`) and wires `compose` (`repl/main.cljc:979-1011`). After phase 7 the driver reads only packs, so the REPL's custody path cannot lift its machine until phase 8 renames the semantic-register kernel into `yin.vm.semantic`. Options: (a) accept the gap until 8 (recommended: `the-plain-composition-loads-and-drives-with-no-yin-repl-test` is re-based on a semantic-register receiver, and the REPL's `:custody` branch is marked inert until 8); (b) pull the REPL's kernel switch into phase 7 (an extra slice, two to three days, touching `yin.repl` and its tests, which 4b/5 did not plan).
- **Q3, the install checks of continuation §11 Q3** in 7b (recommended yes, a quarter day).

---

## 4. Slicing

Each slice: files, dependencies, acceptance tests, hosts, days (opus implementation + review rounds at the observed 2-4 codex rounds per slice, about half a day per round including fixes).

### 7a: custody on rows, the rows inspector, the authority's fixtures

**Create.** `src/cljc/yin/vm/ucf/rows/checkpoint.cljc` (the inspector of §3.3); `test/yin/vm/ucf/rows_support.cljc` (semantic-register machine builders mirroring `lift_support`: `new-machine` over `sr/create-vm` with `tu/make-stream`, deterministic one-slot streams, `parked-reader`, `parked-writer`, `parked-explicit`, `parked-installer`, `parked-linker`, `halted-machine`, `halted-with-cursors`, `parked-module-store-cursors`, `with-op-id`, `serve-table`, `header`, `source-index` capture through `linearize/project`); `test/yin/vm/ucf/rows_checkpoint_fixtures.cljc` + `test/resources/yin/vm/ucf/rows-checkpoint-v3.txt` (the five bases lifted by `rows/lift-task` under a header, the mutations as raw row edits with rehash where they touch a row and as root-slot edits otherwise, three anchors with full pack bytes); `test/yin/vm/ucf/rows_checkpoint_test.cljc`; `test/yin/vm/ucf/rows_custody_test.cljc` (the lift and lower custody rules of §3.1: header by kind, the three lift refusals, the seven grant checks with zero attachments, protection in both directions, exact counter restore, halted exclusive lowers ended, fork under `:exclusive` refused).

**Change.** `rows.cljc` (`emit` sorts rows by id; `lift-task` `:header`; `lower-task` custody stage with `:grant`/`:protection`/`:exclusive`/`:fenced` opts; `check-custody!` replaced); `authority/grant.cljc`, `authority/completion.cljc`, `authority/admission.cljc` (inspector call); `ledger.cljc` (none expected; `:yin.k/root` in the baseline is opaque to the fold); `ledger_fixtures.cljc` (`park`/`halted` from rows packs; `path` → `ledger-v2.txt`), `ledger_fixture_test`, `crash_cut_test`, `grant_test`, `completion_test`, `inherited_test`, `front_test` (fixture bodies only).

**Depends on.** Nothing; owner's Q1.

**Acceptance.** `rows_checkpoint_test` (every base accepted with its baseline; every mutation refused at its path; the file equals `render` on three hosts), `rows_custody_test`, `ledger_fixture_test` (every host rebuilds every frame byte for byte; the projection digest pinned), `crash_cut_test`, the eight authority tests green, `rows_test` and the safepoint harness unchanged (no root id moves), `store_write_audit_test` allowlist updated if the row table changes.

**Hosts.** JVM, Node, Dart.

**Days.** 2.5 implementation + 1.5 review (3 rounds) = **4**.

### 7b: the holder's export record, recovery v2, the driver's lower

**Change.** `holder/export.cljc` (`prepare`/`encode` over `rows/lift-task` with `:header` and a `:serve-keyed` adaptor; `freeze` → recovery v2; `rehydrate-fenced` over `lower-task {:fenced true}`); `holder/driver.cljc` (`lower`, `step-validating`, `rebuilt-cell`, `reopen-holder`, `reopen-exit`, `source-mint`/`exit-mint` unchanged in shape; `:export-version` removed); `compose.cljc` (`holder-config-keys`/`driver-config` drop `:export-version`); `holder/export_test`, `holder/driver_test`, `holder/writer_test`, `holder/reader_test` (machines from `rows_support`; rec1 rows replaced; the v2 fork-lift deftest deleted); `handoff_v2_holder_test` (one deftest deleted).

**Depends on.** 7a; owner's Q3 for the install checks (a quarter day inside this slice).

**Acceptance.** `export_test` (recovery v2 freezes and rehydrates fenced with zero program IO, the published pack reproduced byte for byte, aliases and distinct cursor cells preserved, install children rehydrated without initialization or IO, a restarted abort refused), `driver_test` whole (117 deftests), `writer_test`, `reader_test`, `evidence_test`; the law "zero attachments before the custody stage refuses" pinned by attach counts.

**Hosts.** JVM, Node, Dart.

**Days.** 3 implementation + 2 review (4 rounds; the driver is 2.9k lines and its tests 4.3k) = **5**.

### 7c: the composition and the compose and fencing rows

**Change.** `fencing_rows_test` (rows lift under header, `lower-task` with grant opts), `compose_test` (semantic-register `:receiver`/`:machine`; the plain-composition test per Q2), `compose_rows_test` (41 deftests; `offer-variant`-style helpers re-emit packs; content-store cuts see recovery v2 then the pack); `yin.repl/main.cljc` only if Q2 answers (b).

**Depends on.** 7b; owner's Q2.

**Acceptance.** All three files green on three hosts; the slow deftest `completion-recovery-unknown-policy-transaction-is-reconciled-once-test` under `DATOM_SLOW_TESTS=1` on each lane.

**Days.** 1.5 implementation + 1.5 review (3 rounds) = **3**.

### 7d: Stage E on the semantic-register kernel, three hosts, four process pairs

**Change.** `stage_e_world.cljc` (`parked-reader-over`, `reading-writer` over `rows_support`; `holder-seams` supplies `:source-index`; `offer-variant!` re-emits; `snapshot`'s `:fences` reads pack digests), `stage_e_scenarios.cljc` (checks that name `:yin.k/id-counter` or body keys adjusted; row 1's "two encodings" assertion keeps its meaning), `stage_e_peer.cljc` (none expected), `stage_e_process_test.clj` (none expected; the Node peer build picks up the new namespaces), `stage_e_rows_test.cljc` (none expected).

**Depends on.** 7c (the world port can begin on 7b and run in-process while 7c is in review).

**Acceptance.** `stage_e_rows_test` on JVM, Node and Dart (slow tier, `DATOM_SLOW_TESTS=1`); `stage_e_process_test` on the JVM over `[[:jvm :jvm] [:jvm :node] [:node :jvm] [:node :node]]`, every life its own process, every cut a SIGKILL, `target/stage-e-durability.edn` appended; the fast-lane gate `the-cross-host-handoff-across-processes-test` first. Row 10's canonical-byte agreement across hosts now holds for packs as well (sorted rows).

**Hosts.** JVM, Node, Dart (in-process); JVM↔Node (processes).

**Days.** 2 implementation + 1.5 review (3 rounds) + 0.5 for the slow matrix runs (the process matrix is about an hour per full pass on the remote hosts, per build-n-test) = **4**.

### 7e: documentation

**Change.** `yin.vm.semantic-register-vm.continuation.md` §2.5, §2.6 (custody slot by kind), §7.1-7.2 (the `:header` lift, the custody and fenced lower stages), §8.4 (phase 7 met), §10 (a 10.7 inventory), §11 (Q3, Q4 closed; Q1/Q10 left); `yin.vm.ucf-transport-tuples.md` §5.5 (the custody table as ruled), §7 (pack address versus root id, sorted rows), §9 (the fenced lower); `yin.vm.universal-continuation-format.md` 7.7.4 (recovery v2 paragraph replacing the version-1 one), 7.11.1 clauses 1-5 and 10 (stage letters updated); `docs/orchestrator-log.md` entry.

**Depends on.** The owner's answers; concurrent with 7c/7d.

**Days.** 1 + 1 review round = **1.5**.

---

## 5. Risks and what could force a redesign

1. **The identity split (pack address versus root id).** If the owner or a reviewer insists the ledger's `:yin.k/id` be the root id, the content store contract must change (a verifier-taking put) or the authority must store under the pack address and record both; the second is what §3.3 recommends anyway, so the redesign cost is in naming, not mechanics. Sorting rows removes the host-order variance that would otherwise make JVM and Node packs distinct variants.
2. **Fenced rehydration on the semantic-register kernel.** `rehydrate-fenced-body` relies on the version-0/1/2 lower's `:fenced?` arm (`handoff.cljc:3688-3699`, `:3722-3725`): gate `:exporting`, waits parked in `:yin.k/recovery-waits`, `:yin.k/recovery-cell-ids`, the FFI pair resources for `:ffi-request` pendings, `id-counter` carried exactly. `lower-task` has none of this; porting it is routine but the install-child case (children exporting, their parked records moved to the child's record) is exercised only by `export_test`. Medium.
3. **Install children on the semantic-register kernel under the holder.** The harness proves rows 7-12 (4b stage 2) for lift/lower; the holder's reader (held observations, replay through child call ids) over semantic-register install children has no test today. If `reader.cljc`'s walk assumes a field the new kernel spells differently, 7b finds it. Low-medium: both kernels share `engine.cljc` for `:installs`, `:wait-set`, `:yin.k/held`.
4. **The REPL gap (Q2).** If the owner wants the REPL's custody demo live between 7 and 8, phase 7 grows by a slice and touches the 4b/5 "no REPL edit" boundary.
5. **Stage E's pinned row-5 behaviour** ("the exposed gap marker is the first and only commit", pending an Architect ruling) is inherited unchanged; a ruling during phase 7 would change `row5`'s check, not the transport.
6. **Dart.** Stage E runs in-process only on Dart (as today); the numbering budget (`default-budget`) is far above Stage E's task sizes. The known Dart traps (reader whitespace, `for` over chunked seqs, multi-key `assoc` on nil, duplicate protocol params) apply to the new support namespace; the harness already avoids them.
7. **Review load.** Phase 6b took three codex rounds on a 1.8k-line file; 7b touches the 2.9k-line driver through eight call sites plus `export.cljc` whole. Budgeted at four rounds.
8. **What would force a redesign of rows:** a requirement that the body's identity be a byte address (contradicts Merkle rows; §3.3 resolves it with two identities); a requirement to carry lease or epoch in the body (contradicts UCF 7.2.1, not proposed); row-level storage with partial checkpoints (deferred, §3.4). None is live.

---

## 6. Order of work and concurrency

```
7a (custody, inspector, fixtures)        ──────────┐
                                                   ▼
7b (export record, recovery v2, driver)  ──────────┐
                                                   ▼
7c (compose, compose/fencing rows)       ──────┐   ├── 7d world port may start here
                                               ▼   ▼
7d (Stage E, in-process three hosts, then the process matrix)
7e (docs) ─ starts after the owner's answers, lands beside 7c/7d
```

- 7a is the only slice that needs an owner answer before it starts (Q1). Q2 gates one deftest in 7c and possibly an extra slice; Q3 gates a quarter day in 7b.
- Concurrent work: 7e with 7c/7d; the 7d world port (`stage_e_world` over `rows_support`) with 7c's review, since it depends on 7b's `export`/`driver` only. The old-codec tests are never touched, so no conflict with other worktrees; `rows.cljc` is the one file 7a and the harness share, and 7a keeps every pinned root id.
- Landing cadence (owner's tiers): quick tier at every checkpoint; the slow tier (Stage E, the completion-recovery slow deftest, the process matrix) before each merge of 7c and 7d and once at the phase boundary; one lane set per machine, Dart in its own clone on the remote host.

---

## 7. Honest total

| Slice | Implementation (opus) | Review rounds | Days |
|---|---|---|---|
| 7a | 2.5 | 3 | 4 |
| 7b | 3 | 4 | 5 |
| 7c | 1.5 | 3 | 3 |
| 7d | 2 | 3 (+0.5 matrix runs) | 4 |
| 7e | 1 | 1 | 1.5 |
| **Total** | **10** | | **17.5 sequential** |

With 7e concurrent and the 7d port overlapping 7c, the critical path is 7a → 7b → 7c → 7d: about **14 days** of work; **12 days median** if reviews average the lower end (two rounds on 7a/7c), **17 at the worst** (four rounds everywhere, one redesign loop on the identity split). Phase 6's three slices went from first commit to merge in three calendar days with the design already written and reviews back-to-back; phase 7 has four code slices over twice the surface, so calendar time of one to two weeks is realistic if codex capacity holds.

---

## Verdict

**NOT READY.** Blocking questions for the owner: **Q1** fork spelling in the custody slot (nil, recommended, or the draft's explicit map); **Q2** the REPL custody gap until phase 8 (accept, recommended, or pull the REPL kernel switch into phase 7); **Q3** the install link-id and image-address checks in 7b (recommended yes). Every other design point above is decided by the Architect within the owner's rulings and is ready for an implementation brief for 7a on receipt of Q1.
