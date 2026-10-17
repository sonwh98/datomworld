Completed-GMT: 2026-10-15 07:52:00 GMT
Completed-Local: 2026-10-15 14:52:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (claude -p; the harness does not expose it)

# 8f-1 Architect ruling: who owns what `yin.vm.code` still provides once it is deleted

Tree: srvm-phase8f1 @ d43afbe3. Read-only; this report is the only artifact.

## 0. What I verified (grep, not the engineer's word)

Requirers on this base (`rg -l` over `src test`, require forms only):

| doomed namespace | production requirers | test requirers |
|---|---|---|
| `yin.vm.code` | `debruijn_code.cljc`, `ucf.cljc`, `completion.cljc`, `semantic.cljc`, `ucf/handoff.cljc` | `code_test`, `debruijn_code_test`, `linearize_test`, `rule_r_test`, `dao/space/query_test` |
| `yin.vm.semantic` | demos only (`datomworld/demo/continuation_handoff.cljc`, `demo/continuation_stream.cljs`, `yin/register_bench_cljd.cljd`) | 38 files (the plan's "56 requirers" set, incl. 9 C4 files, `test_utils`, `ucf_test`, `ucf/remote_test`) |
| `yin.vm.linearize` | the same three demos | 36 files (largely the same set) |
| `yin.vm.ucf.handoff` | nothing in src | `yang/python/antlr/int_heap_test.cljc:25` (one call, `handoff/export-task`, :240) |
| `yin.vm.ucf.checkpoint` | `ucf/handoff.cljc` only | nothing |
| `yin.vm.completion` | `ucf/handoff.cljc` only | `completion_test`, `cell_test`, `linker/publish_test`, `semantic_register/scan_test`, `debruijn_code_test` |
| `yin.vm.ucf` | `semantic_register/scan.cljc:83`, `semantic_register/linearize.cljc:211,301` (both **`code-address` only**), plus `semantic.cljc:669`, `handoff.cljc`, `checkpoint.cljc` | `ucf_test` (24), `rule_r_test` (1 deftest), `semantic_register/{code,scan,linearize}_test` (`code-address` only) |

Facts that change the ruling:

1. **8d did not delete the old transport's sources.** Commit 249f8799 removed 8399 lines of tests and fixtures; `src/cljc/yin/vm/ucf/handoff.cljc` (3783 lines) and `checkpoint.cljc` are still on disk, untested, with one C4 test caller. They are what keeps `yin.vm.completion` and the §7.3/§7.4 half of `yin.vm.ucf` "live". Their last commits are ae3fe21e / 49960017 (pre-phase-8). Plan Q2 said they die in 8d; they die in 8f-1 instead.
2. **`yin.vm.completion` has no production reader except `handoff.cljc`.** `linker.cljc` never required it; the register-era lift is `engine/lift-slice` (`engine.cljc:1054`) and the rows carrier. So completion is dead production code the moment handoff goes.
3. **Every production reader of `yin.vm.ucf` after (1) and (2) is `code-address`** (`jing/segment-key`). The §7.3 canonicalizer canonicalizes a v3 `:yin.code/*` batch, which nothing produces since 8e; the §7.4 safepoint half is stack-shaped (`:push` stack effects, positional `[:var name]`/`[:closure params body]`).
4. **The descriptor is already pinned twice.** `debruijn_code_test/descriptor-declares-the-contract` pins `dc/descriptor-hash` = `7b7c6ae9…f3489`; `golden-corpus-bytes-and-hash` pins image bytes and H for four images; `debruijn_register_code_test/golden-descriptor-hash-test` pins `rcode/descriptor-hash` = `aa757659…7e74c` (routes through `debruijn-code/encode-scalar`, not through the stack opcode table). `encode-scalar` orders map entries by their encoded bytes, so a literal map with the same entries hashes identically to the derived one.
5. **The native register backend's only dependency on `debruijn_code` is `encode-scalar`** (`debruijn_register_code.cljc:232-259`); `debruijn/stack.cljc:163` reads `dcode/opcode-table`. Neither touches `yin.vm.code`.
6. **`vm/segment-requirements` + `segment-extraction-queries` (`vm.cljc:1839-1894`) are v3-positional** (`[:store-put ?key _]`, `[:ffi-call ?op _]`) and read only by `completion.cljc:258` and `dao/space/query_test.cljc:1865,1874`. `scan/requirements` (`scan.cljc:88-100`) is the v4 twin, with `scan/segment-rows` the v4 `project-segment-qualified` (as `derivations.md:901` already records).
7. **`vm/opcode-table` (`vm.cljc:619`, the old stack opcode integers) is read only by `semantic.cljc` and `completion.cljc`**; the `opcase` macro carries its own literal copy and is used only by `semantic.cljc`. The optimisation note `src/cljc/yin/vm/docs/bytecode-vm-optimization.md` mentions it (doc).
8. **The v4 footprint `:mnemonics` table (`vm.cljc:1781-1797`) still has a `:push #{}` row.** Readers: `scan.cljc:76` (`dissoc :push`), `scan_test:254` (the same dissoc), `query_test:1838` (`(= code/mnemonics …)` against the old 22-mnemonic set).
9. **"v4 on v3" acceptance exists at two sites, not one:** `semantic.cljc:795-796` (`load-vector` checks `vm/semantic-contract` then validates with the v3 `code/well-formed-vector?`) and `ucf.cljc:47,59,267-273` (`contract-stamp`/`profiles :semantic` stamp a v3 vector with `{:yin.code/contract "v4"}`; `canonicalize` checks the v4 stamp and emits a v3 vector).
10. **Old `code/materialize-vector!` has no caller.** `linker_test`, `mint_test`, `linker_step_test`, `linker/publish.cljc:158` all call `yin.vm.semantic-register.code/materialize-vector!` (alias `code` there is the new ns; none of those files require `yin.vm.code`). `mint_test:256` tests the refusal-before-write. The only reference left is the docstring at `linker.cljc:2056-2057` and `dao/jing/content/driver.clj:8`.

## 1. Rulings (DECIDED)

### R1. The retained native stack grammar is owned by `yin.vm.debruijn-code`, as literal data, in place.

Inline the table: `opcode-table` becomes a literal map of the 23 mnemonics with their `[:yin.debruijn.code/<attr> kind]` slots, exactly the entries the derivation produces today (20 carried from the old table with the namespace moved, plus `:load-bound`, `:load-free`, `:closure`). Delete `renamespace-slots` and `carried-mnemonics`. Add `(def ^:private terminators #{:jump :return :halt})` beside it and point `terminator-rule` (:722) and `successors` (:749) at it. Drop the `[yin.vm.code :as code]` require. Rewrite the ns docstring lines 9-14 and the banner at :54-56 and :684 to say the table is this dimension's own data, frozen under `lowering-contract-version` 2.

No new namespace (`yin.vm.debruijn.stack-grammar` rejected: it would be a third place that spells the same 23 rows, and the descriptor already is the authoritative spelling, `:dim/slots opcode-table`). This is not "ownership by invention": the owner ruled the native kernels are retained backends with a documented fragment; a backend owns its own instruction grammar. Nothing is widened: same 23 mnemonics, same slots, same kinds.

**Proof the bytes do not change** (codex runs before and after step 1, same JVM):

- `clojure -M:test -n yin.vm.debruijn-code-test` — `descriptor-declares-the-contract` must still pass with the literal `7b7c6ae92e5fe53577718b615a3ed0bdd81796bf8074efe8c07d7005c45f3489` **unchanged in the test file** (codex may not edit that literal); `golden-corpus-bytes-and-hash` must pass with its four `[bytes H]` pairs unchanged. Because `descriptor` embeds `opcode-table` and `mnemonic-tag` is derived from `(keys opcode-table)`, any slot, kind, attribute-name, or mnemonic drift flips both.
- `clojure -M:test -n yin.vm.debruijn-register-code-test` — `golden-descriptor-hash-test` literal `aa757659a28f9787e44cd04d02d9f4412eba1088d46a1b07adf9fa820d07e74c` unchanged.
- At landing, the three-host lane (`pinned-identities-are-host-independent`, `register-determinism`), as every phase-8 slice.
- Test rewrite: `opcode-table-derives-from-the-named-table` loses its first `testing` block (it compares to the deleted table). Replace it with: `(is (= 23 (count dc/opcode-table)))`, `(is (not (contains? dc/opcode-table :var)))`, every attribute namespaced `"yin.debruijn.code"`, every kind in `#{:data :sym :kw :str :bool :buffer :pc :uint}`; keep the second `testing` block verbatim. Rename it `opcode-table-is-this-dimensions-own-data`. Drop the `yin.vm.code` require from that test file.

### R2. `yin.vm.ucf` shrinks to `code-address`. Its v3 halves die, un-ported.

Keep `(defn code-address [v] (jing/segment-key v))` and a one-paragraph ns docstring ("the A of a canonical vector; UCF mints no second addressing scheme"). Delete everything else: `contract-stamp`, `profiles`, `supported-profiles`, `profile-engine`, the batch readers (`segment-attrs` … `check-segment-entity`, `canonical-tuple`, `batch->canonical-instruction-vector`, `canonicalize`, `refusal`), and the safepoint half (`transitions`, `parking-kinds`, `stack-effect`, `lexically-required`, `safepoints`, `safepoint-table`, `safepoint-at`, `activation-state`, `unsatisfied-names`). Requires drop to `dao.jing` only.

Why not port the validator/operand-table dependency to `semantic-register.code`: the §7.3 half reads a **v3 segment batch** (`:yin.code/op`, `:yin.code/pc`, saturation defaults for `[:call :yin.code/tail?]`); no producer of that batch survives 8e, and the register kernel's batch path is `semantic-register/linearize/project-datoms` + `code/well-formed?`. Porting would invent a v4 batch grammar nobody emits (derive-don't-persist). The safepoint half is stack-shaped; the register kernel's safepoint facts are already owned on the `semantic-register` side (`frame.cljc`, `semantic_register/safepoint_harness_test`, `frame_test`) and the rows carrier. Nothing is lost that is not stack-v3-specific.

Moving `code-address` into `semantic-register.code` and deleting `ucf.cljc` was considered and rejected: `derivations.md:906` pins scan's imports to `yin.vm.ucf (code-address)`, three production sites and four test files spell `ucf/code-address`; the minimal diff keeps the namespace.

Tests: delete `test/yin/vm/ucf_test.cljc` whole (24 deftests; every fixture is a hand-assembled v3 batch run on `yin.vm.semantic`). Delete `rule_r_test/canonicalize-verifies-the-batch-stamp` (1 deftest). The stamp-verification fact survives in `rule_r_test/every-persistent-code-loader-requires-and-compares-a-stamp` once its "semantic datoms"/"semantic vector" legs are rewritten to the register kernel's loaders (8f-3, plan commit 2).

### R3. `yin.vm.completion` is deleted with `handoff.cljc`. Nothing is re-derived.

`completion.cljc` (757 lines) and `completion_test.cljc` (19 deftests) go. `vm.cljc` loses `segment-extraction-queries` and `segment-requirements` (:1839-1894) and `opcode-table` + `opcase` (:612-680; the `opcase` macro is only `semantic.cljc`'s). The docstring at `vm.cljc:1875-1886` (`ast-requirements`) drops its sentence about the segment twin.

Facts that survive, and where:

| fact completion_test / siblings asserted | survives as |
|---|---|
| tree and segment free names agree | `scan_test/assert-agreement` `(is (= (:free-names new) tree-names))` + `goldens-match` over `scanner_goldens.cljc` (historical v3 answers, master 993d5411) |
| tree and segment requirement sets agree | `scan_test/assert-agreement` `(is (= (:requirements new) (vm/ast-requirements rows)))` |
| lift refuses a cell | `cell_test/lift-of-a-bare-cell-ref-is-refused-test` (`engine/lift-slice`, on every VM); `int_heap_test/cell-refusal` blocks 2-3 (`engine/lift-slice`) |
| image → vector round trip hashes to A | `semantic_register/load_paths_test`, `mint_test` (new kernel) |
| FFI pair keys never in a slice | `debruijn_code_test:638` keeps the assertion with the set inlined |
| module footprint discovery completes | **dropped**: `publish_test/published-footprint-completes-module-discovery` is a test of `completion/complete`; keep the test's first half (the published manifest carries `[:yin.module/footprint :effects]`) as an `is` on `manifest`, delete the completion half |
| CLJS address-reuse fast path of the old query | **dropped** (`scan_test/completion-address-reuse-preserves-answers`, perf of deleted code) |

Edits this implies (all compile-independent of the old kernel's deletion):

- `semantic_register/scan_test.cljc`: drop requires `yin.vm.completion`, `yin.vm.linearize :as old-linearize`, and the `#?@(:cljd [] :cljs [[dao.jing] [dao.jing.cbor]])` block; delete `completion-free-names` (:131-176) and `completion-address-reuse-preserves-answers` (:266-270); in `assert-agreement` delete the `old-vector` binding and the `(completion-free-names old-vector)` term so the assertion reads `(is (= (:free-names new) tree-names))`.
- `dao/space/query_test.cljc`: delete `segment-db` (:1826-1830) and its two uses: the second `is` of `definition-keys-are-store-slice-requirements` and the whole deftest `requirement-sets-from-tree-and-segment-are-equal` (the fact lives in `scan_test`, fact row 2). Drop the `yin.vm.code` and `yin.vm.linearize` requires; add `yin.vm.semantic-register.code :as sr-code` for R5.
- `cell_test.cljc`: delete `completion-over-a-cell-is-not-complete-test` and the require; its `linearize`/`semantic` uses at :38 are 8f-3's.
- `linker/publish_test.cljc`: as in the table.
- `debruijn_code_test.cljc:638`: replace `completion/ffi-pair-keys` with `#{vm/call-in-stream-key vm/call-out-stream-key vm/call-out-cursor-key}`; drop the require.
- `yang/python/antlr/int_heap_test.cljc`: delete the first `run-all` block of `cell-refusal` (:237-243) and the `yin.vm.ucf.handoff` require. C4 file: edited by this track under the phase-8 freeze (owner ruling 2026-10-14).
- `store_write_audit_test.clj:339-350`: remove the `handoff.cljc` allowlist entry and its comment.

### R4. `materialize-vector!`: the old one dies uncalled; the new one is the only one.

`code/materialize-vector!` has zero callers; `semantic-register.code/materialize-vector!` (:881) is called by `linker/publish.cljc:158` and tested by `mint_test:117,138,256,274`, `linker_test:321,1153,1786`, `linker_step_test:453`. In 8f-1 fix the two stale prose references: `linker.cljc:2057` → `yin.vm.semantic-register.code/materialize-vector!` (becomes `yin.vm.semantic.code/…` in the 8f-2 rename; write the post-rename name only if 8f-2 lands first, else fix twice) and `dao/jing/content/driver.clj:8`. No test is added: the refusal-before-write fact is already `mint_test:256`.

### R5. The "v4 on v3" acceptance dies at both sites; the footprint `:push` residue dies with it.

Site 1, `semantic.cljc:795`, dies with the file (8f-3). Site 2, `ucf.cljc` stamps, dies in R2 (8f-1). Confirmation for the ledger: after 8f-1, `rg -n '"v4"|semantic-contract' src/cljc/yin/vm/ucf.cljc` returns nothing; after 8f-3, `rg -ln 'well-formed-vector\?' src` returns only `semantic_register/code.cljc`.

`:push`: delete the `:push #{}` row from `vm/footprint-table` (:1796), remove the `(dissoc … :push)` at `scan.cljc:76` and `scan_test:254` (rename that deftest `footprint-mnemonics-is-the-v4-table`), and make `query_test/footprint-table-has-a-row-for-every-tag-and-mnemonic` assert `(= sr-code/mnemonics (set (keys (:mnemonics table))))`. Readers of the `:push` row are exactly those three sites; the stack backend's `:push` is its own opcode, not a footprint fact.

### R6. Docs do not move in 8f-1.

The statements that become historical (`debruijn.stack.md:211,510,644`; `derivations.md:119,901-914,1011`; `semantic-register-vm.md:153,981`; `linker.md:771-772,2332`; `universal-continuation-format.md:244`; `ucf-revisions.md` needs a dated entry that handoff/checkpoint sources died in 8f-1, not 8d; `dependency-completion.md` marked superseded by scan + lift-slice) are 8g's. Code docstrings that name a deleted var are fixed in the step that deletes it (kondo won't catch them; 8g's grep gate will).

## 2. 8f-1 restated as ordered file-level steps (each step compiles and runs its lane)

Base: srvm-phase8f1 @ d43afbe3. JVM per step (`clojure -M:test -n …`); `bb test:clj` quick tier at the end of step 5; three hosts at landing.

**Step 0 — record the pins.** Run `clojure -M:test -n yin.vm.debruijn-code-test -n yin.vm.debruijn-register-code-test`; both green; note the two descriptor-hash literals and the four golden H values in the report. No edits.

**Step 1 — R1.** `src/cljc/yin/vm/debruijn_code.cljc` (literal table, private terminators, drop require, docstrings); `test/yin/vm/debruijn_code_test.cljc` (rewrite one deftest, drop require). Lane: step 0's two namespaces plus `yin.vm.debruijn.stack-effects-test`, `yin.vm.debruijn.stack-parity-test`, `yin.vm.debruijn.register-test`, `yin.vm.semantic-register.derive-test` (quick, no `--slow`). Gate: the three pinned literals unchanged.

**Step 2 — old transport sources out.** `git rm src/cljc/yin/vm/ucf/handoff.cljc src/cljc/yin/vm/ucf/checkpoint.cljc`; `int_heap_test.cljc` (require + first `run-all` block); `store_write_audit_test.clj` (allowlist entry). Lane: `yin.vm.store-write-audit-test`, `yang.python.antlr.int-heap-test` (quick part), `bb test:sub:clj yin.vm.ucf`. Compiles: nothing else requires either file (table in §0).

**Step 3 — R3.** `git rm src/cljc/yin/vm/completion.cljc test/yin/vm/completion_test.cljc`; `vm.cljc` (:612-680 `opcode-table`+`opcase`, :1839-1894 segment queries, docstring :1875); `scan_test`, `query_test`, `cell_test`, `linker/publish_test`, `debruijn_code_test` as listed in R3; `linker.cljc:2057` and `driver.clj:8` docstrings (R4). Lane: `dao.space.query-test`, `yin.vm.semantic-register.scan-test`, `yin.vm.cell-test`, `yin.vm.linker.publish-test`, `yin.vm.debruijn-code-test`, `yin.vm.mint-test`. (`cell_test`/`publish_test`/`query_test` still require `yin.vm.semantic`/`linearize` elsewhere; they compile because those files still exist until 8f-3.)

**Step 4 — R2 + R5.** `ucf.cljc` to `code-address`; `git rm test/yin/vm/ucf_test.cljc`; `rule_r_test` (one deftest); `vm.cljc:1796`, `scan.cljc:76`, `scan_test:254`, `query_test` footprint assertion. **`semantic.cljc:669` calls `ucf/batch->canonical-instruction-vector`**: in this step replace that call site's function with a 3-line private copy? No — rule: `semantic.cljc` is deleted in 8f-3, so step 4 would break it. Therefore step 4 is split: **4a** (R5 `:push` edits and the `rule_r_test` deftest removal) lands in 8f-1; **4b** (shrinking `ucf.cljc`, deleting `ucf_test.cljc`) moves to 8f-3 and is the first `git rm`/edit of that slice, in the same commit as `semantic.cljc`'s deletion. Lane for 4a: `yin.vm.rule-r-test`, `yin.vm.semantic-register.scan-test`, `dao.space.query-test`.

**Step 5 — gate for 8f-1.** `clj -M:kondo` (no new warnings; the deleted files' warnings vanish), `bb test:clj` quick tier, then Node and Dart lanes per `build-n-test.md` (clean `lib/cljd-out` first). Report with: the deleted-test ledger (handoff/checkpoint: 0 deftests, already gone in 8d; `completion_test` 19 deleted, table in R3 as the replacement column; `rule_r_test` 1 deleted; `scan_test` 1 deleted; `cell_test` 1 deleted; `query_test` 1 deleted; `publish_test` 0 deleted, 1 narrowed), the pinned literals before/after, deftest count before/after.

Commit shape for 8f-1: one commit, `refactor(yin.vm): the stack grammar is debruijn-code's own data; old transport sources, dependency completion and the v3 footprint residue are out (8f-1)`. Architect sign-off before commit, as standing.

## 3. What must wait

- **8f-2** (plan Q3 commit 1): `git mv` `semantic_register*` → `semantic*`, tests likewise. Unchanged by this ruling.
- **8f-3** (plan Q3 commit 2 plus the deletions): `git rm semantic.cljc linearize.cljc code.cljc`, `semantic_test` (25) `linearize_test` (23) `code_test` (13), **plus step 4b** (`ucf.cljc` shrink, `ucf_test` 24), the three demos, `test_utils.cljc` (`create-vm` is already the walker; `run-encoder-session:167` and the `linearize`/`semantic` requires go), the ~38 test requirers incl. the 9 C4 files, `ucf/remote_test`, the ports of `semantic_engine_test`/`semantic_ffi_test`/`semantic_stream_observer_test`, `linked_harness` is already ported (8c). Gate: the phase gate of plan row 8f.
- **8g**: every doc line in R6.

## 4. Invariants checked

A is the only code identity: H stays a derived checksum (`image-hash` docstring already says so); R1 moves data, not identity. Tree always available: nothing here lowers from A alone; completion's tree-less segment scoping is what dies. Derive, don't persist: no new table, no v4 batch grammar, no safepoint port; the one new literal replaces a derivation whose source dies. Minimal diff: zero new namespaces; the only additions are a private set and one rewritten deftest. No backend widening: 23 mnemonics in, 23 out, `lowering-contract-version` stays 2. Codex implements, Fable reviews.

RULING-READY
