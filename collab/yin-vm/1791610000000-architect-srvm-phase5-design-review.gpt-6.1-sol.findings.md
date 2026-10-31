I’ll read the report and full derivations design, then check the allocator comparison, adapter contract, scanners, and migration gates against the frozen design and current code. I’ll keep the review read-only.
The two sample allocations match the author’s numbers, and reusing the existing lowerers is a viable route. I found gate definitions that need closer checking: the tree scanner and vector scanner use different occurrence orders, and the proposed lexical-address walk may count the synthesized definition operator that neither native image emits.
The adapter recommendation still holds, but several acceptance rows would fail as written. The worked-example R golden needs live set `[1]` on the inner call. The C4 declaration check also compares declarations with raw free names, although module specs first remove names defined by the module. I’m separating these gate defects from the allocation strategy decision.
Completed-GMT: 2026-10-10 15:49:53 GMT
Completed-Local: 2026-10-10 22:49:53 Asia/Bangkok

The adapter route is architecturally sound and preferable to a new allocator here. The design needs corrections to its address law, scanner comparisons, and acceptance gates before acceptance. No files were edited and no tests were run.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High — gate defect | docs/design/yin.vm.semantic-register-vm.derivations.md:677 | The proposed resolved-address walk visits every application’s operator. A synthesized definition therefore contributes `[:free yin/def]`, while A, H and R contain no corresponding variable load. The existing `resolved-addresses-of` helper has this limitation too. | Make the address oracle mirror definition lowering: visit only the value operand for a definition. Do not reuse the existing helper unchanged on the expanded definition corpus. |
| High — gate defect | docs/design/yin.vm.semantic-register-vm.derivations.md:811 | Tree occurrences are sorted by structural path; vector occurrences put main first and bodies FIFO. For `:define-then-call`, the tree encounters body `list` before main `inc`/`n`; vector order is the reverse. Tree application sites also count definition applications and omit FFI nodes, unlike vector sites. | Specify an independent tree oracle normalized to the same emission order and site semantics, or separate unordered occurrence agreement from old/new sequence agreement. Explicitly exclude definition applications and include FFI sites in the normalized site count. |
| Medium — gate defect | docs/design/yin.vm.semantic-register-vm.derivations.md:816 | Comparing only retained names discards the join’s `:use-before-definition` defect. Two empty obligation vectors can agree while refusal behavior differs. | Compare successful/refused outcomes, normalized defect kind/name, and retained records. Add a use-before-definition case. Apply the join agreement gate to C4 as promised, not only corpora 1–2. |
| Medium — gate defect | docs/design/yin.vm.semantic-register-vm.derivations.md:831 | C4 declarations do not equal raw lexical free names. Module specs remove names defined by the module and then omit requirement-covered names. The cited prelude test does not assert the claimed raw-set equality. | Compare the appropriately discharged external-name set with primitive declarations plus requirement coverage. Preserve separate raw scanner agreement. |
| Medium — incorrect golden | docs/design/yin.vm.semantic-register-vm.derivations.md:1069 | The inner `(g x)` call must preserve `f` in register 1. `fill-live` therefore emits `[1]`, not `[]`. | Pin `[:call 2 3 [4] false [1]]`; the outer call’s live operand remains `[]`. |
| Medium — incorrect scanner expectation | docs/design/yin.vm.semantic-register-vm.derivations.md:1119 | In the listed fixtures, `n` is a lexically free store name, not a parameter. The listed fixtures contain no free `f`. | State exact facts per fixture. For `:define-then-call`, free occurrences are main `inc`, main `n`, then body `list`; parameter occurrences `a` and `b` are bound. |
| Medium — amendment contradiction | docs/design/yin.vm.semantic-register-vm.derivations.md:1244 | F9’s replacement says body order **and absolute pcs** coincide with A. Stack pushes and register parameter banks/layouts prevent general pc equality. | Say body discovery order agrees; each emitter computes its own instruction positions. Remove the now-redundant second parse from frozen §8.3 and update §8.4’s obsolete composition formula. |
| Medium — implementation-brief gap | docs/design/yin.vm.semantic-register-vm.derivations.md:783 | `project-segment-qualified` belongs to the old `yin.vm.code`, not the required semantic-register code namespace. Several named scanner helpers and `undischarged` are private. | Pin imports and portable public access. Use functions exposed by format records for scanner oracles and public `linker/verify` for join outcomes, or explicitly specify a justified public seam. Define the new segment-row projection without a hidden dependency on the retired stack table. |
| Medium — runtime-gate gap | docs/design/yin.vm.semantic-register-vm.derivations.md:1062 | “Every B0 program” under existing normalization includes closure-valued results whose native representation lacks the pinned named body. The cited stack execution test runs five selected fixtures; it supplies no general exclusion policy. | Specify runtime corpus membership, composition options, and a common observable normalization for closure results. Keep byte comparison over the full corpus. |
| Low — grounding/editorial | docs/design/yin.vm.semantic-register-vm.derivations.md:606 | The stack corpus has **15**, not 14 entries. §3.2 also excludes store-put and gensym from its agreeing atomic B0 cases and overstates the sample evidence as a universal theorem. | Correct the count and atomic cases; narrow allocation claims to demonstrated cases unless a general proof is supplied. |

1. **F8 independently. RULING: ACCEPT WITH CHANGES — frozen §6, §8.2–8.4, §9 and §10 phase 5.**

   Both sample allocations match:

   | Program | R1 | Frozen interval scan |
   |---|---|---|
   | `(+ 1 2)` | result 0; operator 1; operands 2, 3; four registers | operator 0; operands 1, 2; result 3; four registers |
   | `(f (g x) y)` | `f=1, g=3, x=4, inner=2, y=3, result=0`; five registers | `f=0, g=1, x=2, inner=3, y=1, result=2`; four registers |

   The second scan follows the stated expiry rule: at pc 4, `g` and `x` expire; `y` takes slot 1; at pc 5, result takes the remaining free slot 2.

   The adapter’s byte-equivalence argument is credible. The existing lowerers traverse structural references, not record-id order; occurrence expansion changes sharing without changing emitted visits. Synthesized definition records satisfy the lowerers’ special definition branch. Defaults match: `"id"` and `1024`. Tail flags affecting ordinary calls are preserved; definition tail metadata is irrelevant to that special lowering branch.

   Retain “lowerer-equivalent,” rather than claiming literal record equality or a full AST bijection. Per-program structure/bytes/checksum gates are the right implementation evidence. Correct the gates before strengthening the frozen text to an unconditional preservation claim.

2. **Consequence for A.** A remains the canonical projection and derivation source: parsing its register operands reconstructs the expression relationships used by the lowerers. Its virtual numbering does not drive physical allocation.

   This satisfies the owner’s identity and virtual-register rulings. The owner-level observation is that route (b) buys **no simpler native allocator or local stackification** from the register shape. Its direct benefits are the evaluator state, UCF windows, and reconstruction maps. Native derivation gains a validated, canonical source grammar and reuses existing emitters. That tradeoff should remain explicit.

3. **F9–F12.**

   - **F9: ACCEPT WITH CHANGES — frozen §8.3.** Emitters own FIFO discovery and label resolution. Correct the absolute-pc claim and eliminate redundant parsing.
   - **F10: ACCEPT WITH CHANGES — frozen §8.2, §9 and phase-8 planning.** The lift target is hashed into both descriptors; phase 5 should leave lifts and descriptors unchanged. Phase 8 needs an explicit lift-contract decision. Keeping a historical label must not disguise a normative contract change.
   - **F11: ACCEPT WITH CHANGES — frozen §10 phase 5.** Replace “discharged bodies” with concrete scanner/join obligations, but use corrected oracle ordering, application semantics, C4 declaration treatment, and refusal comparison.
   - **F12: ACCEPT — retirement through frozen §8.2’s replacement.** Remove interval-specific structural-slot allocation. Preserve canonical definition-less IDs and §8.1 analysis unchanged.

4. **Resolve-on-vector.** The record table covers the supported instruction vocabulary. The two definition records are necessary and correctly shaped. Duplicate parameters and shadowing match `resolve-name`: rightmost within the nearest frame wins. Pc-derived record IDs are distinct and absent from native bytes.

   Compare closure side-table parameters **per closure pc**, excluding provenance; do not compare top-level `:params` lookups on pc-keyed side-table maps. Require `:source` to equal the complete record set, including synthesized records.

5. **Scanners.** Owner-tree scope resolution and the shifted operand positions are correct. The facts are sufficient inputs to the existing linker analysis, but the proposed agreement oracle is not currently executable as specified.

   Pinning old answers before deletion is sound once the fixture includes normalized join refusals and its corpus is explicit. It is historical evidence; the independent tree oracle remains necessary.

   The C4 agreement gate is appropriately **read-only**: construct its module specs, scan their ASTs, publish nothing, and change no C4 code or declarations. A disagreement is reported to that track’s owner, not repaired by weakening its declarations.

6. **Byte/contract decision procedure.** Structure, encoded bytes, checksums, unchanged descriptors, validators, allocation, execution and live rules cover frozen §6. Under the selected adapter route, a discrepancy first blocks the slice and triggers investigation; it does not authorize automatic re-versioning.

   Rewrite §4.4’s generic “else → re-version” branch accordingly. Re-versioning requires an explicit changed-contract decision after the discrepancy is understood. Record the decision independently for 5a and 5b.

7. **Slices.** The proposed dependencies are sound: 5a supplies resolution/H; 5b depends on it; scanner development depends only on phase 3; production wiring depends on proven H/R equivalence. Their principal files do not overlap 4b.

   The follow-up to the 4b obligations fixture is correctly assigned to whichever lands second. Revise the numbered gates identified above before issuing briefs. The slow C4 rows must actually run for sign-off; ordinary subsystem success alone is insufficient.

8. **Decisions and owner questions.**

   My rulings:
   - Approve pc-derived record IDs.
   - Approve corpus membership, correcting the stack count to 15.
   - Approve equivalence as a blocking gate, not discretionary checksum acceptance.
   - Approve the temporary derived footprint map with a drift test and explicit phase-8 relocation.
   - Approve the byte-neutral 5c production switch after both equivalence gates pass.
   - Retain named-datom resolution through phase 7. At cutover, remove production callers; retain a portable test oracle without depending on deleted stack machinery.
   - Approve the obligations-fixture follow-up.

   Owner decisions:
   - Accept the route-(b) tradeoff described in item 2; I recommend it.
   - Decide whether phase 8 preserves a genuine legacy lift contract or changes it with the required contract/version consequences.
   - Choose the v4 profile string. Reusing `"ast-to-bytecode"` with an explicit `"v4"` contract is architecturally acceptable; a new name is optional.

9. **Grounding.** More than fifteen references were checked. Supporting locations include `resolve-name:617–637`, resolver emission `127–265`, validation `454–492`, stack emission `103–168`, side tables `192–209`, stack entry `212–235`, R1 allocation `88–116`, node lowering `152–306`, register side tables `323–338`, assembly `358–428`, register encoding/hash `248–269`, liveness `342–385`, linker formats `655–731`, join `1090–1160`, publishing `165–189`, ledger records `40–65`, defaults `vm.cljc:841/850`, and phase-3 projection `188–220`.

   The material mismatches are reported in the table: address-helper behavior, scanner ordering/site semantics and visibility, C4 declaration evidence, execution-test coverage, corpus count, and the worked-example live golden.

10. **Invariants and portability.** No violation in the adapter architecture: stage data has no identity; all outputs derive from A; lexical ownership and CFGs are constructed from validated tuples; lowering remains separate from execution; no callbacks or shared mutable runtime state are introduced.

   The host precautions are appropriate. Add explicit protection against private-var access and unspecified oracle ordering. The copied footprint table is derived engine data, not canonical content.

VERDICT: CHANGES_REQUESTED — correct address and scanner oracles; preserve join refusals and fix C4 declaration comparison; repair goldens and runtime gates; complete public API/import specifications; revise F8–F11 amendment text and record the owner decisions.
