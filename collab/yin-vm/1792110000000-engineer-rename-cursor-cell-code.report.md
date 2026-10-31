Completed-GMT: 2026-10-11 18:49:59 GMT
Completed-Local: 2026-10-12 01:49:59 Asia/Bangkok

(Both stamps are verbatim `date` output. The system clock is behind the brief's Created-GMT 2026-10-12 09:05:00.)

# Second rename: the carrier's box is a "cursor cell" (engineer report, claude-opus-5-5)

Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD 83fd51d8. Nothing staged or committed (`git diff --cached` is empty; `git diff --check` is clean). No file outside the three below was touched; the v2 production code (`engine.cljc`, `ucf/handoff.cljc`, `ucf/remote.cljc`) is untouched.

## Files

| File | Change |
|---|---|
| `src/cljc/yin/vm/ucf/rows.cljc` | renamed (same 53 lines the first rename touched) |
| `test/yin/vm/ucf/rows_test.cljc` | renamed, 2 pinned roots regenerated, +1 assertion |
| `test/yin/vm/semantic_register/frame_test.cljc` | 2 pending-key sites renamed |
| `src/cljc/yin/vm/semantic_register/frame.cljc` | **unchanged**: it has no "cursor" or "cell" site |

Backups were taken before any edit, and `cmp` showed all three byte-identical to the HEAD sources (`ALL-IDENTICAL`):
`collab/1792110000000-backup-{rows.cljc,rows_test.cljc,frame_test.cljc}.orig`.

## Method

`git show 83fd51d8` was the site map. Every hunk of that commit turned "cell" into "cursor". In the pre-rename originals (`collab/1792090000000-backup-*.orig`, which I checked are byte-identical to `83fd51d8^`), every "cell" means the carrier's box: these files have no heap-cell sites. Every "cursor" in those originals means something else and was left alone by the first rename (see the bare-cursor list below).

So **every hunk of 83fd51d8 is box-sense**. I decided per hunk, and none of them keeps "cursor". To avoid having to tell "cursor (renamed)" apart from "cursor (original)" in the current files, I regenerated the files from the pre-rename originals:
1. Ran the ordered substitution `collab/1792110000000-rename-cursor-cell.py`: `:yin.k/c-` -> `:yin.k/cc-`, `"c-"` -> `"cc-"`, `#"c-(` -> `#"cc-(`, `c-N` -> `cc-N`, then `(?<!\w)cell` -> `cursor-cell`. Because of the single pass, `response-cell` becomes `response-cursor-cell`, `cell-body` becomes `cursor-cell-body`, and `cells` becomes `cursor-cells`.
2. Re-applied the 83fd51d8 manual edits: the cljstyle re-indent in `check-abstract!` (done by `cljstyle fix`) and the malformed-key assertion.
3. Hand-fixed the prose sites where "cursor cell" is a noun (rows_test comments: "(streams and cursor cells)", "equal-body cursor cells ... all three cursor cells", "the census cursor cell reachable"). Compound adjectives keep the hyphen per the table: "cursor-cell numbering", "cursor-cell scopes", "row/cursor-cell markers", "source-cursor-cell -> :yin.k/cc-N".

The result's `git diff --stat` against `83fd51d8^` is the same 119/119 lines as 83fd51d8. The diff against HEAD touches exactly the lines 83fd51d8 touched.

## Spellings applied (box sense)

- Tags: `:cursors` -> `:cursor-cells`, `:cursor-body` -> `:cursor-cell-body`, including the task schema's `[:ref :cursor-cells]` and the `:pairs` schema entry.
- Ids: `:yin.k/cur-N` -> `:yin.k/cc-N`. `census-key` is now `#"cc-([1-9][0-9]*)"` and the minter uses `(str "cc-" ...)`. Numeric order, the no-leading-zero rule and `census-order` are unchanged.
- Value union `[:lit|:row|:cursor]` -> `[:lit|:row|:cursor-cell]`; wire `[:cursor id]` -> `[:cursor-cell id]`; marker `{:yin.k/cursor id}` -> `{:yin.k/cursor-cell id}`, in `marker-shaped?`, `decoded-value` and the docstrings.
- Refusal kinds: `:extra-cursor-cell`, `:missing-cursor-cell`, `:noncanonical-cursor-cells`.
- Pending-row keys: `:yin.k/cursor` -> `:yin.k/cursor-cell` (for `:next`, `:ffi`, `:link-request`, `:link-response`) and `:yin.k/response-cursor` -> `:yin.k/response-cursor-cell` (for `:ffi-request`), in rows.cljc `pending-keys` and the two frame_test pending maps.
- Identifiers: `cursors` locals and args -> `cursor-cells`; budget key `:cursors 64` -> `:cursor-cells 64` (also the `spend!` category); test helper `cursor-body` -> `cursor-cell-body`.
- Test names (count unchanged): `refinement-search-on-symmetric-cursor-cells-is-bounded`, `census-is-numeric-from-ten-cursor-cells`, `cursor-cell-and-identity-shaped-literal-are-distinct`, and the `defn` `seven-cursor-cell-triangle-square`.
- The UCF envelope key `:yin.k/cursors` does not occur in these files.

## Ambiguous sites and decisions

1. **Pending-row keys** (`pending-keys`, frame_test lines 165/234, rows_test lines 91/258). **Renamed to cursor-cell.** The `:C` slot holds a census id, which names a box. These keys are not hashed, so no id changes.
2. **Budget key and `spend!` category `:cursor-cells`.** **Renamed.** It counts census entries, which are boxes. No caller passes the key.
3. **Internal refinement labels** in `canonical-numbering` (`[:cursor-cell L]`, `[:cursor-cells]`) and the `check-abstract!` dispatch. **Renamed.** They are compared bytewise but never hashed. The probe shows numbering is identical on every fixture.
4. **`:test/cursor`** (frame_test 165/234; rows_test 91/266). **Kept.** This is a pre-existing arbitrary test keyword that predates both renames; neither rename touched it.
   - In rows_test it is used as a box's source identity; in frame_test it is the pending `:C` value. Strictly that is box-sense.
   - It is a test datum, not a vocabulary term from the table.
   - Renaming it would change the pinned worked-frame root (`c4a7c7e6…`), because the `:C` value goes onto the wire as-is.
   - If the owner wants it to read `:test/cursor-cell`, that is a one-line follow-up plus one more re-pin.
5. **Malformed-key assertion** in `schema-and-literal-canonicality`. The list is now `[:x :yin.k/cc-01 :yin.k/cc-0 :yin.k/cc- :yin.k/c-1 :yin.k/cur-1]`.
   - `:yin.k/c-1` is kept from round 1 (the original spelling).
   - `:yin.k/cur-1` is added (the intermediate spelling). It must refuse with `:slot-kind`, and it does.
   - This is the only reason the counts moved from 553 to 554 (and from 323 to 324 for rows alone). No test was weakened or deleted.
6. **Prose noun vs adjective.** Nouns read "cursor cell(s)" with a space; compound modifiers read "cursor-cell" with a hyphen (as in the table's "cursor-cell census"). The sites are listed under Method.

### Every remaining bare "cursor" (none denotes the carrier's box, except item 4 above)

All of these were in the pre-rename originals:

| Site | Token | Why kept |
|---|---|---|
| frame_test:97 | `(engine/gensym m "cursor")` | engine's gensym prefix for the sealed ref; engine code is out of scope |
| frame_test:98 | `engine/handle-cursor` | engine API name (v2 production, not renamed) |
| frame_test:114,121 | `second-cursor` | local naming the `:cursor-ref` that `attach-reader` returns (the guest handle) |
| frame_test:165,234; rows_test:91,266 | `:test/cursor` | ambiguous item 4 |
| rows_test:35 | `'cursors` | guest-program symbol literal (guest data) |
| rows_test:69 | `:cursor-ref` | the sealed ref type; unchanged per vocabulary |
| rows_test:411,419 | `'cursor` | guest binding symbol in a `:bindings` row (guest data) |

`grep -i cell` finds no bare "cell": every occurrence is "cursor cell" or "cursor-cell" (rows.cljc 48 lines, rows_test 57, frame_test 2). No `cur-`, `:cursors`, `cursor-body` or `:yin.k/cursor` remain, except the intentional `:yin.k/cur-1` refusal probe.

## Pinned ids: old -> new

| Pin | Original (cell, c-N) | Round 1 (cursor, cur-N), HEAD | **New (cursor-cell, cc-N)** |
|---|---|---|---|
| `(rows/emit (fixture :s1 :s2 :x :y))` root | `be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937` | `beef94ed002ce432ba00874b12ec585069e82856cec367b86007d657138902b7` | `:segment/blake3-fab223d9b3540fdce0ccf2132f8a4041917a158a32295470a4936754742d3ac4` |
| loop + 2-cycle (three-cell) root | `8899f95d330a174ee098e10f16ecf2d017d5aae67d4f50d8369222342e6d36e9` | `b140ac405963a115665c26ac8ff3435d984ed70c8a44cec3be86544ae66abad9` | `:segment/blake3-55ff168e6b5a37028fb5ecd99ab21dc58f6a4e2b9c95dbe570ccec65b15a63c0` |
| `(rows/frame->rows ft/worked-frame)` root | `c4a7c7e6…3da7d` | unchanged | **unchanged** `:segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d` |

The new values are the failing-assertion actuals from the public `rows/emit`, confirmed by the probe. The worked frame stays the same because its wire is frame-rooted, with no census, no cell-body and no cell values, and pending keys are not hashed; respelling its wire is the identity. No other hash, checksum or golden in these files contains the renamed tags or ids. The non-hashed literals were respelled: the numbering maps, `:yin.k/cc-3`, `cc-9`/`cc-8`, the malformed keys, `[:identity :yin.k/cc-1]`, the `(str "cc-" %)` census expectation, and the expected decode in `input-row-bodies-do-not-spend-label-bytes`.

## Proof that only the spelling changed

Probe: `collab/1792110000000-rename-equivalence.clj` (the round-1 probe respelled by `collab/1792110000000-adapt-probe.py`). It ran directly this time:

```
clojure -Sdeps '{:aliases {:probe {:extra-paths ["test"]}}}' -M:probe collab/1792110000000-rename-equivalence.clj
```

Log: `collab/1792110000000-rename-equivalence.log`, exit 0. It compares against the **original** codec (`collab/1792090000000-backup-rows.cljc.orig`: `c-N`, `:cells`, `:cell-body`), so it checks the whole chain c -> cc in one step.

| Fixture | old (original) root | new root | numbering identical (cc-N <-> c-N) | respelled new wire = old wire (root and full row set) |
|---|---|---|---|---|
| fixture :s1 :s2 :x :y | be82d50b… (= original pin) | fab223d9… | true `{:x cc-1 :y cc-2}` | true |
| fixture lane-a | 8245d25a… | b307e36c… | true | true |
| fixture reversed | f23036e5… | 38d4e92a… | true | true |
| loop + 2-cycle | 8899f95d… (= original pin) | 55ff168e… | true `{:b cc-1 :a cc-2 :l cc-3}` | true |
| ten cursor cells | 8714f893… | 6e7c7a6b… | true `x0..x9 -> cc-1..cc-10` | true |
| child scope / shared child DAG | b46120be… | a31c0530… | true | true |
| worked frame | c4a7c7e6… | c4a7c7e6… | n/a | respelled = itself: true |

Old decode of the old wire and new decode of the new wire both succeed for every fixture. The old roots are the same values round 1 recorded, and the original codec reproduces both original pins. So the numbering, the ordering and the wire structure are unchanged; only the spelling differs.

## Mutants re-checked

Harness: `collab/1792110000000-rename-mutants.py`, which is the round-1 harness with only the renamed tokens respelled (`collab/1792110000000-adapt-mutants.py`). For each mutant it applies one unique site, runs `clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test`, and restores from a verified snapshot. Final `restored-identical True`. Logs: `collab/1792110000000-mutant-<name>.log`; results: `collab/1792110000000-rename-mutants.json`.

| Mutant | Result | Killing test(s) |
|---|---|---|
| guest (drop guest/explicit marker distinction; uses `:yin.k/cursor-cell`) | **KILLED** 2 failures | `well-formed-guest-markers-are-refused` |
| bytes (remove one byte-bound preflight) | **KILLED** 2 failures | `intermediate-label-bytes-are-bounded` |
| equality (collapse equality tags) | **KILLED** 1 failure, 1 error | `cursor-cell-and-identity-shaped-literal-are-distinct` |
| numbering-order (frame check before numbering; `:noncanonical-cursor-cells`) | **KILLED** 1 failure | `numbering-precedes-frame-defect` |
| census-reversed | **KILLED** 3 failures | `census-is-numeric-from-ten-cursor-cells`, `canonical-roots-are-pinned` (x2) |
| extra: equality-leaf-only | survived (as in round 1) | none; argued equivalent in the round-1 report (a scalar never equals a vector-tagged form) |

## Lanes (all foreground)

| Lane | Command | Result |
|---|---|---|
| JVM pair | `clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test` | **31 tests, 554 assertions, 0 failures, 0 errors** (+1 from ambiguous item 5) |
| JVM rows, bounded heap | `clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test` | **21 tests, 324 assertions, 0 failures, 0 errors**, exit 0 (`collab/1792110000000-rows-256m.log`, via the `python3 -I collab/1792110000000-run.py` wrapper) |
| semantic-register namespaces | the 10 `-n yin.vm.semantic-register.*-test` namespaces (analysis, code, effects, frame, linearize, link, load-paths, parity, vm, walker-trace) | **120 tests, 2181 assertions, 0 failures, 0 errors** |
| Node | `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.(ucf\\.rows\|semantic-register\\.frame)-test$"}'` | **43 tests, 820 assertions, 0 failures, 0 errors**; 157 files, 5 compiled, 0 warnings. Ran `yin.vm.parity-test`, `yin.vm.semantic-register.frame-test`, `yin.vm.semantic-register.parity-test`, `yin.vm.ucf.rows-test` (`collab/1792110000000-node.log`) |
| kondo | `clj -M:kondo --lint` on the three files | **0 errors, 0 warnings** |
| cljstyle | `mise exec -- cljstyle check` on the three files | **clean** (after `cljstyle fix` re-indented the one `check-abstract!` continuation line, the same re-indent as round 1) |
| Dart | not run | orchestrator; `test/cljd-out/yin/vm/ucf/rows-test_test.dart` is stale |

## Scope notes

- The v2 handoff carrier (`engine.cljc`, `ucf/handoff.cljc`, `ucf/remote.cljc`, and readers listed in the round-1 report) still spells `:yin.k/c-<n>`, `:yin.k/cell`, `:yin.k/response-cell`, `:yin.k/cells`, `:extra-cell`. It is unchanged, as instructed.
- The v2 vocabulary and the v3 codec in rows.cljc now differ on purpose: `c-N` vs `cc-N`, `:yin.k/cell` vs `:yin.k/cursor-cell`.
- This brief covered code only. The doc reversions (UCF 7.2.1/7.4.3/7.5.3, the v2 amendment, the linker v2 tables, ucf-revisions) were not in my file list and were not touched.

Scratch artifacts in `collab/` (never committed):
- `1792110000000-rename-cursor-cell.py`
- `1792110000000-gen-{rows,rows_test,frame_test}.cljc` (generated, before the hand edits)
- `1792110000000-adapt-probe.py`, `1792110000000-rename-equivalence.{clj,log}`
- `1792110000000-adapt-mutants.py`, `1792110000000-rename-mutants.{py,json}`, `1792110000000-mutant-*.log`, `1792110000000-renamed-rows.cljc`
- `1792110000000-run.py`, `1792110000000-rows-256m.log`, `1792110000000-node.log`
- the three `.orig` backups
