# Independent fidelity review: final wasm specification tree

Date: 2026-10-19 (+07). Reviewer: gpt-6-astra, original r1–r5 author, independently checking the gemini-4-argon restructuring under the owner's stated waiver. This is a document-fidelity review, not implementation validation.

**Verdict: FAIL — content preservation passes, but the required single-home structure is not yet satisfied.** Two duplication findings require correction. One additional citation correction is listed below. No dropped runtime requirement or newly imposed execution semantics was identified in the audited moves.

## 1. Scope and method

Read both retired copies in full (417 and 495 lines), the entire engine extension, register §10 and yang.wasm, and compared their sections with the retired texts and frozen r5. Checked the existing engine/register body changes against Git, compared the 23 opcode rows directly against r5, scanned all 105 `docs/design/*.md` files for retired Markdown destinations and old ABI-name occurrences, and reconstructed the original Rust document from the captured r5 read outputs to check line positions independently of the footer's assertion.

The reviewed artifacts are the working-tree files. Git currently reports engine/register modified and yang.wasm/rust-kernel untracked; this review does not claim they are already committed. Nothing was staged, committed or changed outside this findings file.

Reference shorthand below: **E** = `docs/design/yin.vm.engine.md`; **R** = `docs/design/yin.vm.debruijn.register.md`; **Y** = `docs/design/yang.wasm.md`; **K** = `docs/design/yin.vm.rust-kernel.md`. **Retired runtime** and **retired kit** mean the two complete `collab/retired-2026-10-19-*` copies named in the task. Line ranges refer to this reviewed snapshot.

## 2. Findings and required corrections

### F1 — MAJOR: the target binding restates the engine protocol tables

**Evidence:** Y:406–409 says the section references the engine tables rather than duplicating them. Nevertheless:

- Y:415 repeats the entire control-block field/offset mapping, magic, versions, header size, initial buffer boundaries and allocation start. This is the E:641–682 / E.3.1 layout again, rendered as one long list item instead of a Markdown table.
- Y:416 repeats all five numeric transport-status assignments from E:684–714 and the response-size offset.
- Y:417 restates wire framing, the tag range, compression tag and exact NaN bytes from E:716–784.

There is one literal control-offset table and one literal TLV table for this ABI, but the protocol definitions do **not** have one textual home. Changing table formatting into prose does not satisfy the owner's “reference, not restate” requirement. The duplicated values currently agree; this is a specification-maintenance failure, not a discovered binary incompatibility.

**Correction:** retain Y's compiler-facing obligation to use `yin.vm.engine.host.v1`, its module import binding and its register-snapshot/codegen responsibilities. Replace Y §4 items 2–4's duplicated numeric/layout/codec definitions with references to E §E.3.1–E.3.3 and compiler obligations to obey those sections. Keep the NEED_SPACE handling obligation by reference to the authoritative retained-response protocol; do not duplicate offsets or status-number assignments. Do not move or change the engine definitions. The register-specific ten-region execution-memory table in Y §2.2 is a different table and should remain.

### F2 — MINOR, required: the conformance procedure is still copied into the fixture home

**Evidence:** R:1438–1463 correctly defines the register fixture schema and references the engine method. R:1466 then repeats the full corpus procedure found at E:1097–1105: project AST → A, assert vector/address, derive/validate R/live sets, compile/instantiate, supply bindings and parked targets, execute both arms, and refuse rather than skip unsupported cases. R:1467 repeats E:1107–1112's B0-versus-raw-boundary comparison instruction as well.

The required fixture names, bindings and observable register fields are appropriate in R §10.4. The repeated procedural instructions make the conformance method have two homes despite the requested “method → engine; fixtures → register” split. This is not a dropped test and does not change current acceptance results.

**Correction:** state the generic projection/admission/execution/comparison procedure once in E §E.5.1, referring to the guest fixture specification for concrete corpora, bindings and compared fields. In R §10.4 retain the register corpus paths, literal-golden meaning, required bindings/targets, fixture groups and complete raw observable-field list, and reference the engine procedure rather than repeat it. Preserve every acceptance obligation; do not remove the register-specific fields in the name of generalization. Register W0–W6 gates remain in §10.5.

### F3 — MINOR: wrong r5 section attribution on the return row

**Evidence:** Y:326, the `:return rs` row, correctly links to R §10.2.2 but labels the origin “r5 §3.3.” The frozen r5 row points to **§3.2**, “Restore algorithm and failure ordering”; r5 §3.3 is host transport/different layouts. All 23 row semantics otherwise match r5; this cross-reference substitution is the sole changed opcode row in a direct row comparison.

**Correction:** change the parenthetical to “r5 §3.2,” retaining the correct R §10.2.2 target. No semantic edit is necessary.

## 3. Section-to-home completeness: retired runtime

“Preserved” means the normative obligations remain, including obligations reached through the surviving cross-references. It does not excuse F1/F2 duplication.

| Retired section / lines | Surviving home | Result / checked content |
|---|---|---|
| Status, provenance, decomposition, authorities (1–47) | R:1211–1258; E:363–403; Y:1–46 | Preserved as dated planning/provenance and corrected companion pointers. |
| §1 register state overview (48–61) | R §10.1, 1260–1272 | r2/version 4, 23 opcodes, host ABI and shared Rust services retained. |
| §1.1 eight fields/barrier (62–92) | R §10.1.1, 1274–1303; E §E.1 | Exact eight fields, supplied-map authority, unconditional W1 import, old snapshots, map roots before GC, returned-host authority and absolute seam pc retained. |
| §1.2 dense/sparse/GC (93–118) | R §10.1.2, 1305–1327; E §E.2.3 | Dense write-through, sparse saved data, current parked-register distinction, root inventory, wrapper traversal and immutable snapshots retained. |
| §2 continuation heading; §2.1 materialization (119–143) | R §10.2.1, 1331–1349; Y §3.5, 364–402 | All six live-set sites, payload keys, sparse pair/live order, optional fields, no code space on return frames, capture-time image data and no leaked handles retained. Historical precedence moved to R:1241–1250. |
| §2.2 restore ordering (144–168) | R §10.2.2, 1351–1374; Y §3.5 | Format/image, defect and resume-value checks; request-sent conversion; exact empty/nonempty-K return-result updates; blocked flag discipline and distinct internal continuation call retained. |
| §2.3 layout transport (169–189) | R §10.2.3, 1376–1395 | Same-layout round-trip, linker non-portable refusal, all recursively rebased coordinates, matching-prefix gate and rebasing/UCF non-goals retained. |
| §2.4 wait-entry scope (190–204) | R §10.2.4, 1397–1408; E §E.4.4 | Four-shape oracle scope, owning validators for link/install/observe and stale-ready-entry exclusion retained. |
| §3 attach/spawn/cache (205–234) | R §10.3, 1410–1436; Y §5, 426–483; E §E.4.4 | Staged attachment outside exchange, code-space preservation, fresh task isolation, closure lifting/lowering, A/R cache provenance and interpreter fallback retained. |
| §4 / §4.1 harness/corpora (235–249) | E §E.5.1, 1054–1135; R §10.4, 1438–1467 | Same kit/lanes, identical binaries, both corpora and raw register observations retained; duplication is F2. |
| §4.2 W0 (250–261) | R §10.5.1, 1479–1493; E §E.5.2–E.5.3 | Admission/R/live sets, matching layout, eight-field snapshot gates, transport/codec probes retained. |
| §4.3 W1 (262–286) | R §10.5.2, 1495–1520 | Full pure cases, Rust parity, wide registers, million-tail, nil-fill/drop, aliasing, frame truthiness, snapshot and halt/return retention checks retained. |
| §4.4 W2 (287–310) | R §10.5.3, 1522–1547 | All capture/restore cases, qualified refusals and precedence assertions retained. |
| §4.5 W3 (311–334) | R §10.5.4, 1549–1597 | Immediate/blocked × tail/non-tail matrix, site/entry pc difference, cell GC roots, custody, waits, seals and once-only retries retained. |
| §4.6 W4 (335–357) | R §10.5.5, 1599–1636 | Wasmer W1–W4, bidirectional slices, attach/capture layout cases, empty rows, private stores, GC/old snapshots retained. |
| §4.7 W5 (358–366) | R §10.5.6, 1638–1665 | Three-runtime release, frozen ABI, all corpora, named refusals and no silent narrowing retained. |
| §4.8 W6/non-goals (367–384) | R §10.5.7, 1667–1695; Y §6; E §E.5.4 | Measure before optimizing, mandatory parity, single-port service migration and register non-goals retained. |
| §5 risks/end condition (385–401) | R §10.6, 1712–1727; E §E.5.4; Y §6.2 | Transport limitation and absolute-layout risk, host/lowering risks by reference, full-trace completion retained. |
| Reference conventions (402–410) | R:1729–1735; E §E.5.5; Y reference conventions | Paths/symbol aliases retained through references. |
| Open items (411–417) | R:1729–1735; E:1291–1296; Y:545–556 | Both original tensions remain explicitly unresolved. |

No dropped normative element found in the retired runtime sections.

## 4. Section-to-home completeness: retired conformance kit

| Retired section / lines | Surviving home | Result / checked content |
|---|---|---|
| Status/provenance and §1 scope (1–51) | E:363–403 and §E.5.1, 1054–1076; R §10 opening | Single K0/W0 deliverable, R4 oracle, planning status, lane naming and K3/K4 authority retained. |
| §2 exported fixtures (52–77) | R §10.4, 1438–1467; E:1056–1076 | Image, R, B0 result/error, boundary/K/saved pairs, fourteen validator rules, descriptor/scalars/messages, refusal atomicity, complete tail-site matrix, >65,535 registers, host-limit and million-tail fixtures retained. |
| §2 execution/comparison method (78–128) | E §E.5.1, 1078–1135; R §10.4–§10.5 | One manifest, native Rust participation, no replacement oracle, same inputs/secrets/profiles, full ABI/boundary traces, exact identities, restricted private normalization and LLVM/fallback checks retained. F2 concerns duplicate placement, not absence. |
| §3 encodings/hashes (129–199) | E §E.2.2, 526–595; §E.3.3, 716–784; §E.5.2, 1137–1172 | All three domains, scalar framing/hash preimage, Jing specifics, exact owner/seal/profile/address formulas, shared crate assignment, corpus reuse and NaN/carrier/order/framing checks retained. |
| §4 traversal (200–252) | E §E.4.3, 942–999; §E.5.2; R §10.5.4–§10.5.5 | Every listed traversal site and preserved explicit order retained, including values-only markers, direct-before-cursor streams, slice-before-fresh-store, first-link wins, FIFO and first invalid update key. Versioned shared-reference prerequisite remains explicit. |
| §5 W0 pins/probes (253–317) | E §E.5.3, 1174–1254; R §10.5.1 | Exact six toolchain selections, dependency lock/checksum/target recording, all feature/codec/status tests, retained responses, snapshot replacement and W/K dependency map retained. |
| §6 W1 (318–337) | R §10.5.2, 1495–1520 | Same pure, atomicity/fault/wide/tail and public-state checks retained. |
| §6 W2 (338–357) | R §10.5.3, 1522–1547 | Native payload validation and full restore matrix retained, including no hash trust or ABI-hash substitution. |
| §6 W3 (358–393) | R §10.5.4, 1549–1597 | Full-writer/stale keys, ready-after-halt, terminal outcomes, call-pair ordering, effect permission, resource validation, GC/custody/FFI and actual-effect NEED_SPACE replay tests retained. |
| §6 W4 (394–423) | R §10.5.5, 1599–1636 | Full Wasmer W1–W4 gate, all attachment/lifetime cases, bidirectional slices and differing-secret receiver oracles retained. |
| §6 W5 (424–446) | R §10.5.6, 1638–1665 | Every opcode, IVM/IVMState/eval refusals, exact test invocations, native tasks, full trace, no JS/WASI dependencies and Dart/shared-change gates retained. |
| §6 W6 (447–465) | R §10.5.7, 1667–1695; Y §6.1 | All measurements, same-workload variant comparison, no attribution of host-language speed to wasm, shared Rust migration, optional W6 and K3/K4 distinction retained. |
| §7 Rust/LLVM (466–489) | R §10.5.8, 1697–1710; E §E.5.1 | K1–K5 acceptance, `:live` byte parity, fault isolation, deterministic LLVM output, artifact-drop/no-toolchain and JVM blocked-state round-trips retained. |
| §8 open items (490–495) | E §E.5.5; R §10.6; Y Open items | Both unresolved items retained without redesign. |

No dropped normative element found in the retired kit sections.

## 5. Other required checks

### Rename and links — PASS

All seven live occurrences of `yin.register.host.v1` are explicitly historical rename notes: E:17, 379, 624; Y:26, 236, 407; R:1231. Live protocol bindings use `yin.vm.engine.host.v1`.

Scanned inline Markdown links and reference-definition destinations in all 105 `docs/design/*.md` files: **zero destinations to either retired file**. Both retired paths are absent under `docs/design/`. Remaining names are plain-text provenance, not live links. Rust footer pointers target the three surviving homes.

### Rust line coordinates and pointer-only amendments — PASS, with precise meaning

Reconstructed the original 1,333-line Rust authority from `collab/1792340000000-architect-register-wasm-lower-plan-r5.gpt-6-astra.stdout.log`: raw read outputs for lines 1–180, 181–460, 461–790, 791–1090 and the numbered 1091–1333 tail. Compared it directly with the present file, not with a reviewer's citation claims.

All original lines are byte-identical at the same positions **except line 1300**, whose sole change is the authorized supersession/pointer text. No original line moved. Original status/revision are unchanged; §12 remains at line 1304. Line 1334 is the blank separator, followed by related-spec pointers and dated notes at 1335–1338. Thus the footer's **coordinate** claim is true; it must not be read as a claim that every byte through 1334 is unchanged. The new destinations correctly identify engine method/protocol, yang lowering and register instance/fixtures/gates.

### Generalization seams and semantics — PASS

R:1283–1291 retains exactly `:pc :registers :frames :continuation :store-of :value :halted? :blocked?`, plus the separate code layout/engine state. R §10.1 retains the complete concrete barriers and GC roots rather than relying only on the engine's generic wording. R §10.2 and Y §3.5 retain the register payload, live sets, optional fields, absolute layout and check-order facts. R §10.4 and §10.5 retain concrete fixtures and every-opcode acceptance.

Y §3.3 has exactly 23 opcode rows. Direct comparison against r5 found all row behavior unchanged; only the return row's destination citation was rewritten (with the minor source-section typo in F3). Trampoline, non-native guest calls, live spill maps, empty-stack branch discipline, no `return_call` baseline, 64-bit wire pcs with Rust u32 limits, code growth, combined-segment payload reads, empty-base rows and disposable artifact rules survive.

E §E.3 retains the request/response ownership and once-only semantics, not an async-stack or trap-based blocking scheme. E §E.4 preserves shared Rust crates, safe handle facade, custody distinctions and FFI writer→reader conversion. Engine original-body changes are limited to the dated resource-update correction and extension pointers; register §1–§9 is unchanged, with a dated precedence note in §10.

### Frozen-content check — PASS for normative behavior

No new opcode, guest state schema, scalar/hash algorithm, transport status, engine transition, runtime feature requirement, acceptance exemption or implementation authorization was found. The new document homes, ABI rename, generalization wording and dated precedence notes implement the owner's explicit amendments. The engine extension's general pattern remains concretely instantiated for register execution. Restating protocol data is a violation of the requested organization (F1), not a new protocol; repeating the harness procedure is F2, not a relaxed gate.

The known compiled-closure-call/shared-transition tension and COMPLETE→NEED_SPACE test notation remain open. They were not silently resolved. This review does not reopen the frozen architecture or treat those inherited open items as new restructuring defects.

## 6. Reviewed snapshot and disposition

SHA-256 digests:

| File | SHA-256 |
|---|---|
| retired register-wasm | `800a1c605a26623c9028e43a99375b5e91746314476667cf196b251ad01b2828` |
| retired conformance-kit | `14f66c0dba2e5d5053fe8ae766eec6ca4dfaa067d671de58053906557de96c66` |
| E | `3334657969bea328a8ce624103aeb1ec42dfc69273972013ff3b8007a034578a` |
| R | `6d8901c8c7bcbd5cc3cf6708827f07667c2873da4e7b6f33199f149d17c799e1` |
| Y | `afab172bb5205606a833ffe176bdfd88c40e6f77238ace5c9cc6dca619a0ca64` |
| K | `c9b87be54b3bef13950aaf42e8250fcd3642aa6dfd7575255c3114f8562cac01` |

Required corrections are organizational and referential: replace Y's copied protocol definitions with engine references (F1), make the fixture/method boundary single-home without dropping register facts (F2), and fix the return row's r5 section citation (F3). No source/test changes or redesign are requested. Recheck these focused edits and the link/coverage invariants before committing.

FAIL


## Recheck round 2

Date: 2026-10-19 (+07). **PASS — the focused corrections resolve F1–F3.** This disposition supersedes the first-round FAIL above. Scope is limited to the requested corrections, their coverage/link invariants and the unchanged Rust authority; no architectural redesign or broader rereview was performed.

### Corrections verified

- **F1 resolved.** Y:415–417 now binds compiler behavior by reference to E §E.3.1–E.3.3. The duplicated offset/magic/version/buffer map, numeric transport-status assignments and literal TLV/NaN byte definition are gone. The retained NEED_SPACE obligation describes compiler response handling while referencing the authoritative protocol. A reference to handle compression is not a second tag table. Compared the full previous Y snapshot recovered from the first-round read log: the only changes are line 326 and lines 415–417. Y §4 items 1 and 5 and the complete §2.2 ten-region execution-memory table are unchanged.
- **F2 resolved.** E:1097–1108 now gives the generic per-fixture procedure and refers to R §10.4 for concrete inputs/observations. R:1465–1470 retains the concrete register corpus and fixture facts and references that procedure. The old repeated project/derive/instantiate/execute/comparison recipe is no longer defined in R §10.4. Comparing the captured first-round E extension and R §10 text confirms that these paragraph substitutions are their only changes within those reviewed sections. The slice gates remain intact.
- **F3 resolved.** Y:326 now cites **r5 §3.2**, with the correct R §10.2.2 destination unchanged. No opcode behavior changed.

### F2 sentence-by-sentence obligation mapping

The removed source paragraphs were E:1097–1112 in round 1 and their copies at R:1466–1467. Current locations below use the round-2 coordinates. “Generic procedure” means E:1097–1108; “register corpus facts” means R:1465–1470. All later resource/identity/normalization comparison requirements in E remain unchanged.

| Removed sentence or distinct obligation | Surviving home and check |
|---|---|
| Use `test/yin/vm/semantic/corpus.cljc` programs and literal goldens. | R:1469 retains the exact path, programs and literal-golden requirement. E applies the procedure to each declared fixture. |
| Project AST to A and assert the literal vector/address. | E:1097–1100 requires projection to the guest's canonical form and assertion of literal goldens. R:1469 identifies the register corpus's syntax/address goldens; R §10.5.1 explicitly preserves every literal vector/address. This is a generic method parameterized by unchanged register facts, not permission to omit address checks. |
| Derive R, validate R/live sets, compile and instantiate wasm. | E:1099–1100 retains derivation, validation, compilation and instantiation per lane. Register admission and exact R/live-set gates remain explicit in R §10.5.1; Y §3.2 still requires register validation and liveness before lowering. The generalization does not remove register-specific validation. |
| Goldens specify syntax/address, not a runnable environment or an expected final value for every entry. | R:1469 retains that distinction explicitly. |
| Supply deterministic f/g/h/k/x/y/c/loop bindings, stream fixtures and genuine parked targets for resume cases; otherwise compare the same qualified refusal. | R:1469 retains the complete binding list, stream fixtures, genuine parked targets and qualified-refusal alternative. E:1100–1103 applies execution with those declared bindings/targets and the same refusal rule. |
| Execute both conditional arms where applicable. | E:1102–1103 requires every conditional arm where applicable. |
| Do not silently skip resume/terminal/closure cases or relabel unsupported cases as successes. | E:1103–1105 retains both prohibitions and all three named case kinds. |
| Use all register_test fixtures/assertions, including hand images, B0/B2 parity, stream scripts, continuation corruption and IVM behavior. | R:1470 retains the exact corpus, “all fixtures and assertions,” and each named category, alongside the scalar/image test corpora already present before the correction. |
| B0 is insufficient; also compare raw payload shapes, stores/module-stores, pc, frame order, K, counter, flags, wait/ready/parked entries and emitted request traces. | E:1105–1108 owns the B0-plus-raw-observables rule. R:1470 enumerates every concrete register observation from the removed list. R:1451 still exports the B0-normalized result/error. |
| Resource identity/cursor progress and stream emissions, not object addresses; exact hashes/seals/profiles/ids; restricted private normalization; equal compositions and diagnostic comparison. | The remainder of E §E.5.1, starting at current line 1108, is unchanged. Its generic procedure still leads directly into these requirements; none was removed by the paragraph replacement. |

No acceptance obligation was dropped. Named concrete facts are now the register fixture specification's responsibility; procedural execution/comparison rules are the engine specification's responsibility. References and test-specific assertions in W0–W6 instantiate that method rather than create a competing general method.

### Single-home, links and stability checks

- **ABI definitions:** the engine is the only home of this ABI's control-offset table and TLV tag table. A search of the surviving specifications finds the control magic only in E's own prose/table, no longer in Y. Y references E for the wire format/status assignments. R does not define these protocol tables. The separate register execution-memory table remains in Y as required.
- **Conformance placement:** the generic corpus procedure lives in E §E.5.1. R §10.4 owns the concrete corpus paths, literal-golden meaning, binding/target requirements, fixture groups and register observation list, with a procedure reference. No second corpus procedure remains in that section. Existing summaries and per-slice acceptance tests are not alternative definitions.
- **Links:** rescanned all 105 `docs/design/*.md` files for inline/reference-definition links to the retired files: none. All local Markdown file targets in the four surviving specs exist. The focused references to E §E.3.1–E.3.3 / §E.5.1 and R §10.4 / §10.2.2 name existing sections. Both retired paths remain absent under `docs/design/`.
- **Coverage:** all 23 opcode rows and W0–W6 remain. The focused-diff comparison shows no other Y change and no other change within E's extension or R §10; their first-round completeness audit remains applicable.
- **Rust untouched:** K's SHA-256 is exactly the first-round digest, `c9b87be54b3bef13950aaf42e8250fcd3642aa6dfd7575255c3114f8562cac01`. The entire 1,338-line file is byte-identical, so the verified coordinates, pointers and status are unchanged.

Round-2 reviewed SHA-256 digests:

| File | SHA-256 |
|---|---|
| E | `8f2c3572d2004eac7117038b6369f316bfc5bf4b59473044bbbc231b45513934` |
| R | `b30a3a5d42c7df975a7d999b9b4bfa274ed81e685194286b68bf074c9854422c` |
| Y | `f646350c50bc1921d57c6a586b1e8a78325993eef02e7b1d1d9d9d1be6111673` |
| K | `c9b87be54b3bef13950aaf42e8250fcd3642aa6dfd7575255c3114f8562cac01` |

All three review findings are closed. The tree is ready to commit within the reviewed documentation scope. Only this findings file was appended; no design/source/test file or Git state was changed by this recheck.

PASS
