# Cursor-cell numbering review r4 — base design only

Reviewed HEAD `1bb2638cfcf00e509f34bf463e3083e51d593697`, the uncommitted round-4 note, report and base probe. Phase 6a reference: `d77da9f04276fca34db6fe6c36bea38a576c09b2`. Foreground review, no agents. Only this findings file was edited; experimental mutations used temporary in-memory Var replacements, restored in `finally`. JVM commands ran from `collab/numbering-probe`, supplying scripts on stdin to `mise exec -- java -Xmx1g` or `-Xmx2g @java.args clojure.main -`.

**No accepted-state canonicality counterexample was found.** The exact corrected independent 5,000-state generator numbered 4,110 states and structurally refused 890, with zero root or refusal-path mismatches over two independently renamed/shuffled copies per state. Independent exhaustive bijection checks verified 463 member swaps in 427 passing root-run tie groups. The base tie rule has a sound argument; D6's former recursive-search proof problem does not apply to this design.

**Changes are still required for the resource gates and the probe's decoder ordering.** Comparison encoding now happens after a conservative byte charge, but the occurrence scan can overflow the host stack before the depth gate, and the representation estimator dropped 6a's early limit check. The probe also performs frame validation before discovery recomputation, so the unchanged 6a test run does not validate the claimed new decoder order.

References: `note` = `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`; `probe` = `collab/numbering-probe/src/probe/discovery.clj`; `rows` and `rows_test` = phase 6a's `src/cljc/yin/vm/ucf/rows.cljc` and `test/yin/vm/ucf/rows_test.cljc`.

| Severity | Location | Finding and required correction |
|---|---|---|
| **HIGH** | note:164–177, 249–256, 337–343; probe:121–150, 184–187, 392–403 | `occurrence-counts` runs the recursive `walk-cells` before the walk's depth check. With one reachable cell inside 3,000 nested vectors and `{:depth 3}`, numbering throws `StackOverflowError`, not `:yin.k/unsupported :resource-limit [:depth]`. The 4- and 300-level variants give the intended refusal. Enforce structural depth before recursive scans, memo-key hashing and spelling preparation can descend unchecked; carry depth through the scans or use a bounded iterative traversal. Add the deep adversarial case, not just `[[[[X]]]]`. The same discipline is needed for nested literal/descriptor representation sizing. |
| **MEDIUM** | note:168–178, 321–323; probe:94–110 | `rep-bound` is not the stated surviving 6a `size`: it removed the `remaining` argument and all intermediate limit checks. Under `{:work 2000}`, a set containing a 400,000-scalar literal invokes the estimator **400,012 times** before returning `[:work]`. CBOR allocation is correctly avoided, but the budget does not bound this preparation work or its recursion. Restore a remaining-budget-aware, early-aborting estimator, and qualify the accounting claim to distinguish encoded bytes, representation nodes and actual host heap bytes. A comparison byte vector and encoder intermediates consume more heap than the CBOR payload length; the representation bound is a conservative abstract charge, not a measured byte-for-byte heap ceiling. |
| **MEDIUM** | note:149–160, appendix A claim of exact implementation; probe:484–502 | The wrapper calls the entire old `rows/decode*`, including `validate-frames`, before doing discovery recomputation. The existing `numbering-precedes-frame-defect` witness, decoded with `p/decode*`, returns `:reserved-name` rather than `:noncanonical-cursor-cells`. Fix the probe or explicitly identify this second decoder limitation and add an actual discovery ordering test. The proposed production insertion around rows:570–575 is correctly placed before frame validation; preserve it rather than copying this wrapper literally. |
| **LOW** | note:270–274 | The asserted example of equal incomplete keys is stale after occurrence counts were added. A standalone `#{[A B] [B A]}` has counts 2,2; `#{[A B] [C D]}` has counts 1,1,1,1, and its disjoint members pass the local tie test. These are not the claimed equal residue keys. Replace the illustration with equal-size, equal-degree unresolved incidence structures, or remove that comparison. The incomplete-key clause itself remains necessary. |
| **LOW** | note:208–209, 249–256 | Make the termination measure precise for the queue as well as the current item. The prose explicitly allows a tie step that numbers no cell but replaces queued members with smaller sub-terms; that does not decrease the displayed pair when the walk's item remains the same. A multiset measure on pending subtree sizes, together with unnumbered-cell count and the explicit phase transitions, covers that case. Alternatively restrict that branch to invalid duplicate values and state the valid-input premise. This is a repairable proof presentation issue, not the former D6 recursion gap. |
| **LOW** | revise3 report, “What is refused, and why” | “The rest were harmless ties” is stronger than the measurements. The tables leave 60 refusals undecided (59 incidence-family states and one child state). Sampling equal roots is not proof of interchangeability. Preserve that distinction in the owner summary. |

## 1. Canonicality and the independent generator

I reused the corrected r3 script's definitions through `gen`, with seed `179221`, unchanged. Each iteration constructs the same two independent renamed/shuffled copies as r3, including all sets, maps and censuses. The removed D5/D6 comparisons consumed no randomness, so this preserves the original generator/copy sequence. Refusal classification uses a **separate** RNG (`9123`) and does not perturb that sequence.

For each state I compared the base `p/do-root` result across the three copies. Successful results must be the same root. Structural refusals were normalized to kind and row path, excluding source-name diagnostics. There were no resource refusals in this corpus.

| Check | Result |
|---|---:|
| States / install-child states | 5,000 / 250 |
| Numbered | 4,110 |
| Structurally refused | 890 |
| Root or acceptance/refusal/path mismatches | **0** |
| Refused: exhaustively interchangeable | 470 |
| Refused: demonstrated non-interchangeable | 100 |
| Refused: sampled equal, undecided | 320 |
| Decode successes among first 100 states | 60 |
| Decode resource refusals in that subset | 14 |
| Other decode failures in that subset | **0** |

The first-100 decode check is a separate rerun preserving the same generator/copy sequence. The other 26 states were structurally refused. Resource refusals are the retained 6a recomputation limitation; successful valid carriers were checked through the base wrapper.

My refusal classifier does not call `p/classify-refused`, `p/reorder`, the author's permutations or renamer. It traverses the schema independently, transforms children before their parent, and records each guest set/map's member count in that post-order. It then takes the Cartesian product of independently generated permutations when the product is at most 720. Reordering each original task in the same post-order makes the container ordinals stable even when parents are shuffled. Above 720, it uses 48 independent shuffled arrangements. Permissive emission is used only to measure roots. Two different roots prove non-interchangeability; one root from exhaustive enumeration certifies the tested host-order invariance; one root from sampling stays undecided.

To attack positive tie-test results independently, I temporarily wrapped `group-failure`. On each passing **root-run** group, I collected the unnumbered cell IDs directly from its member data, enumerated bijections between the first member's cells and each other member's cells, extended each candidate to a two-way swap, and tested the entire task under that swap. My equality projection treats guest sets as mathematical sets, maps as maps, census as an ID-to-body map, and ordered rows as vectors. It retains bodies, aliasing, fixed numbered cells and all ordered occurrences. All **463 pair checks in 427 passing groups** found a whole-task automorphism. Some passing groups occur in a root run which eventually refuses because of another group; the check covers those too. This supplements, rather than substitutes for, the accepted-root comparison.

An earlier exploratory run sampled 16 permissive copies using the generator's own RNG and obtained 4,100/900. That altered later generated states. It is not the exact r3-sequence rerun and is not counted in the table above.

### Assessment of section 2

The root tie step is sound: equal **complete** spellings provide an index-matched bijection preserving ordered structure, unordered membership, literal data, body rank, occurrence count and already-numbered cells. Clauses (ii) and (iii), after the fixpoint, ensure that every swapped unnumbered cell is confined to its one member. Extending that bijection to a block permutation therefore preserves the whole state. Swapping blocks changes private source-to-number maps but preserves emitted rows. This is precisely the confinement obligation that the r1 orbit argument lacked.

Within a key walk, confinement applies to the item being keyed, not to the whole root. That is sufficient for a canonical key: the item also reads the global occurrence counts and body ranks as invariant cell colors. Equal complete spellings preserve those colors. The local first-occurrence index preserves the alias relation; omitting it is unsound, as the targeted mutant below demonstrates.

Counts are structure-derived because the scan counts schema positions, not distinct host objects or source IDs; it excludes the census and separately scoped child tasks. Shuffling unordered containers cannot change those counts. Body ranks derive from sorted distinct structural labels. The first-occurrence index derives from the key walk's canonical decisions, with nested walks starting at the enclosing local counter. Its validity follows from the smaller-subterm key induction plus the interchangeable-tie argument, not from an assumed host-independent raw encounter order.

Unlike D6, a nested key request is over an actual smaller member subterm. There are no synthetic component or forced-trial transitions to justify. Fixpoint passes that repeat number a cell; passing nonempty cell-bearing tie groups also number cells. A finite queue/subterm argument covers the remaining no-new-cell branch. The displayed termination measure needs the clarification listed above, but I found no infinite loop or base canonicality defect.

Do not describe this test as uniquely “the cheapest sound test” without a cost model. Nor does the seven-cell example prove that **every** sound rule needs search: richer deterministic structural analysis can distinguish a triangle from a square. It proves that arbitrary tied-edge entry is unsafe and that this chosen cheap test correctly refuses it. That is enough for this base design.

## 2. Mutants

All mutants ran without source edits. The first four were rerun over the exact saved 5,000-state corpus and its two copies. The byte-charge mutant used the targeted large-literal budget fixture.

| Mutation | Rerun result | What catches it |
|---|---|---|
| Drop occurrence count using `:count-spelling false` | 2,649 numbered, 2,351 refused; **0 invariance mismatches**; 2,073 changed roots among states numbered by both versions | Count-specific fixture pins and coverage expectations. This is a legitimate alternative rule with worse coverage/different roots, not inherently a canonicality failure. |
| Enter failing ties in host order using `:permissive true` | 5,000 numbered; **86 root mismatches** | Renaming/shuffling invariance. This switch affects key walks as well as the root. |
| Skip confinement by suppressing only `:shared-across-groups` failures | 4,496 numbered, 504 refused; **42 mismatches** | Renaming/shuffling invariance. Keep the complete-key and within-group checks intact for this mutation. |
| Drop local first-occurrence index from `[:u j rank count]` | 3,998 numbered, 1,002 refused; **0 invariance mismatches in this generator**; 388 changed roots among states numbered by both versions | Corpus pins catch a change, but this generator alone misses the unsoundness. The targeted balanced-alias witness below gives two accepted roots. |
| Skip representation-byte charge in `key-bytes!` | Huge-literal fixture numbers under `{:work 2000}`, charged work **44**, whereas base refuses `[:work]` | The large-literal budget assertion. A canonicality-only rerun does not test accounting. |

The balanced-alias witness is

```clojure
#{[X X Y Y] [Z W Z W]}
```

All four cells have equal bodies and occurrence count 2. The base gives one root under reversal, `5376f133…`. Dropping the index makes both complete keys equal and the disjoint/confinement clauses pass, although the alias patterns differ. Reversing the set's enumeration then changes the accepted root from `5376f133…` to `09115434…`. Add this witness to the alias-pattern acceptance test; pair-edge-only examples do not adequately test the index component when count spelling is present.

## 3. Byte/work accounting and gates

The former principal encoding defect is fixed: every comparison encoding in the numbering procedure goes through `key-bytes!`, which calls `spend!` on the representation bound before calling `bytes-key`. Sorting caches encoded keys rather than repeatedly encoding from the comparator. Synthetic cell/tag/tie overhead is included in the bound of the eventual encoded spelling; spelling positions are charged as they are constructed. Input literals are retained by reference in spellings.

Independent instrumentation and gate checks:

| Case | Observation |
|---|---|
| 400,000-scalar literal inside a set, default budget | `[:work]`; only the 135-byte body-label encoding occurred before refusal, **no huge-literal encoding** |
| Same set under `{:work 2000}` | `[:work]`, but estimator entered 400,012 times before refusal |
| Same set with byte charge removed | Accepted; work 44 |
| Two cells, descriptor length 128 | Work 3,689; charged comparison encodings total 367 bytes |
| Two cells, descriptor length 65,536 | Work 265,321; comparison encodings total 65,778 bytes; accepted |
| 2, 65 and 10,000 cells under `{:cursor-cells 1}` | All refuse `[:cursor-cells]` |
| One cell, 3,000 or 10,000 nested vectors, `{:depth 3}` | `StackOverflowError` before the depth gate |
| Same vector construction at depth 4 or 300 | Intended `[:depth]` refusal |
| Nested singleton sets at depths 40, 80, 120, 180 and 240 | All stop at `[:work]` under default budgets |
| Empty census, singleton set holding the huge literal | `[:work]`, rather than the old numbering procedure's immediate `{}` |

The descriptor measurement is my two-cell/single-body fixture, not the report's four-cell/two-body fixture; their absolute work totals need not match. It confirms that descriptor bytes are now charged and that local rank spellings do not repeat the large descriptor.

The census gate is before ranks/counts/walking, as promised, but **after** building the `bodies` map (probe:392–393). If “first” is intended to prevent oversized census preparation allocation too, check the raw pair count before that materialization. This is also a residual in 6a, whose label preparation precedes its gate; retaining the old line is not by itself evidence of preallocation protection.

Hashing memo keys containing member data can also scan large input literals before a byte charge. The implementation should make the accounting promise about bounded, charged preparation explicit, rather than calling every read/allocation paid. In particular, the estimator's docstring “allocating nothing” is literally false for `str` and lazy `mapcat` scaffolding, though those are not the old giant-CBOR allocation defect.

The nested-singleton result is another practical cost to report: fairly shallow identity-bearing structure can exhaust the new work budget even with one cell. It is a resource refusal, not a structural tie refusal or canonicality failure. No polynomial claim is needed, but a production performance assessment should include this shape.

## 4. Refusal predicate and decode

The operational predicate in §3 matches `resolve!`: re-key queued groups to a no-progress pass; refuse if **any** group is incomplete, overlaps internally, or shares an unnumbered cell with another remaining group. If every group passes, enter them and return to the fixpoint. Key walks leave residue rather than refusing. `group-failure` implements the three clauses in the listed priority; root refusal reports the first failing group's path and all failing groups' unnumbered cells. The corrected mixed-body and anchored examples no longer overstate what an ordered anchor certifies.

The incomplete-key illustration needs the count-aware correction above. The operational iff should also be read subject to resource limits and valid input: a budget can stop the procedure before it reaches a structural decision, and an unreachable census cell raises a totality defect after a successful walk.

Install translation is fixed and independently verified. A parent with install `'a` holding the 2-cycle gives lift refusal `:yin.k/non-portable :unresolved-cursor-cells`, path `[12 a 15]`. A phase-6a-emitted carrier for that parent decoded through discovery gives `:yin.k/undecodable :unresolved-cursor-cells`, path `[<root> 12 a 15]`, carried cells `[:yin.k/cc-1 :yin.k/cc-2]`. Work-limit refusals at lift and decode under `{:work 1}` remain `:yin.k/unsupported :resource-limit [:work]`, untranslated.

The whole-discovery-emission catch correctly includes children and catches only the specified lift-side refusal. The separate frame-order defect is real: the prototype's retained decoder already validates frames before the catch/recomputation. Run the rewritten ordering test against the discovery decoder, not merely the unchanged copied rows namespace.

## 5. Section 4 audit and portability

The probe's rows and rows_test files match the phase-6a worktree by SHA-1:

- rows: `e02350337c1b48a8d02901e46499d4cd0ab90f19`
- rows_test: `d8aeed70b39a1f4770ba99fb699d06e6561a4128`

The named deletion/rewrite spans are accurate: label-bound helper 212–230, checked wrappers 245–251, label seed 252–255, encoding 257–284, positions/depth 285–325, classes/refinement/search 326–354, emission 181–197 and 358–372, decoder recomputation 570–575 followed by frame validation 576–578. The rewritten test locations 171–178, 307–328, 400–403 and 406–421 are also correct. The unreachable `base` assertion is now explicitly rewritten as a totality defect; reachable/custody literal cases stay valid.

The cut list correctly retains the census gate and relocates the depth gate, adds the synthetic entry descriptor, threads child paths, and distinguishes the two `bytes-key` signatures. Add the depth/preparation and early-bound corrections to that brief; the listed four-level depth fixture cannot detect the current failure.

Portable `.cljc` concerns are accurately identified: replace the probe's `for` in `group-failure` for potentially long sequences, keep 6a's byte-array-taking `bytes-key` with an explicit charged encode call, and remove reflection used only by the scratch emitter/decoder. The JVM `^bytes`/`alength` representation branch also needs the existing host-conditional implementation from 6a. Recursion depth must protect **all** preparatory traversals; guarding only the main walk does not establish safe bounded recursion on any of the three hosts.

Phase 6a's unchanged tests passed independently: **21 tests, 324 assertions, zero failures/errors**. This validates the copied reference, not a production integration or a discovery decoder test suite. No Node or Dart execution was performed.

## 6. Size, difficulty and refusal policy

The procedure range 79–416 has **305 nonblank lines**, or **296 after excluding comment-only lines**. The comparable 6a helpers have approximately 136 nonblank/noncomment lines. Thus the report's underlying size comparison is credible, although “non-blank” is an imprecise label for its counting rule. Roughly 200 added/replacement rows lines versus 135 deleted is a reasonable planning estimate, not an implemented diff. Correct scan/depth/accounting and decode validation add some work beyond that estimate.

The base is conceptually simpler than D6 and avoids 6a's label growth and individualization search. It still requires recursive local walks, provisional and root numbering, memoization sensitive to numbering/counter, faithful alias spellings, deferred worklists, confinement scans, resource accounting and decoder integration. It is **larger code and a substantial implementation**, not merely sorting a vector once. The proof burden is better contained than D6's, but “one page” does not make the boundary/accounting obligations disappear. I would not promise a shorter implementation/review schedule from these measurements.

The measured random refusal rate is **0.95%** (19/2000); frame-bearing is **2%** (4/200). These are generator rates, not production evidence. The independent tie-heavy corpus refuses **17.8%**, including 470 exhaustively harmless states. Valid small shapes that 6a can carry now deterministically refuse. That is an explicit behavioral tradeoff of the adopted base rule, not a defect that this review recommends repairing with D6.

I cannot certify those rates as acceptable for unrestricted production suspension/migration. They may be acceptable for an explicitly refusal-capable carrier if callers preserve the live task and handle the structured refusal, but the samples cannot establish operational reliability. Treat this as an owner-accepted capability limit pending a real snapshot corpus; do not describe the random/frame samples as demonstrating practical coverage. Retain the occurrence count: it materially improves coverage, and dropping it saves little implementation complexity.

The owner-selected base remains a defensible implementation direction. Before implementation approval, fix the preparatory depth gate and early budget estimator, reconcile the probe's validator order with the production brief, and correct the small proof/example/report overclaims. No search extension is needed to address these findings.

VERDICT: CHANGES_REQUESTED
