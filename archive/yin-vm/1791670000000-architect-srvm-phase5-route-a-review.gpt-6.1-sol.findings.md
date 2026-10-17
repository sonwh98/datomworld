I’ll review the rewritten route directly, derive the allocation and stack goldens independently, and check the verification and unitarity amendments against the code and owner rulings.
The allocation goldens need closer checking against static liveness, particularly after terminal instructions. I’m also checking whether the proposed unitarity text requires retrieval evidence beyond the owner’s ruling that the tree remain retrievable.
I found two incorrect H targets: the conditional operand’s branch/jump pcs and the tail-call closure’s body pc. The R goldens I’ve re-derived agree with the scan. The liveness gate also needs to use actual CFG successors for tail calls rather than always comparing against `L(pc+1)`.
Completed-GMT: 2026-10-10 17:46:44 GMT
Completed-Local: 2026-10-11 00:46:44 Asia/Bangkok

The direct derivations are viable. The R goldens check out, but two H goldens are wrong, and verification, descriptor deletion and unitarity wording need correction before acceptance. Reviewed HEAD `cdb128b2`; no files edited or tests run.

| Severity | file:line | evidence | correction |
|---|---|---|---|
| High — defect | docs/design/yin.vm.semantic-register-vm.derivations.md:665 | In the H image, the alternate starts at pc **6**, and the join is pc **7**. Targets 5 and 6 are wrong. | Use `[:branch-false 6]` and `[:jump 7]`. |
| High — defect | docs/design/yin.vm.semantic-register-vm.derivations.md:685 | The H main body contains six instructions. Closure body pc 4 points at the main call, not the lambda body. | Use `[:closure 1 6]`. |
| High — defect | docs/design/yin.vm.semantic-register-vm.derivations.md:320 | The unconditional `L(p+1) − {rd}` agreement law contradicts successor-less tail calls. In `(f (tail-call g x) y)`, layout syntax after the inner tail call keeps `f` live at `p+1`, while physical `body-liveness` correctly returns `[]`. This shape is not prohibited by the validator. | Compare against virtual **live-out over actual successors**, minus destination. State tail-call `live = []` explicitly; update §6.2, acceptance tests and F8. |
| High — defect | docs/design/yin.vm.semantic-register-vm.derivations.md:975 | Both descriptors declare a lift morphism through hashed `:dim/lift-to`. Deleting that morphism while retaining its declaration is inconsistent with “no legacy.” | Remove or replace the lift declaration at cutover, keep version numbers under the owner ruling, and mechanically regenerate descriptor hashes and all affected H/R checksums. |
| Medium — gap | docs/design/yin.vm.semantic-register-vm.derivations.md:567 | The cited import tests do not identify six root programs `o t m r e n`. Several are module packets; the tests use root packets including `q`, `d`, `c`, `r`, `x`, `nn`, `s`, `tt`. | Specify exact root packet vars, expected outcomes and count. Separate module ASTs from import programs. |
| Medium — gap | docs/design/yin.vm.semantic-register-vm.derivations.md:564 | Linked execution changes only root lowering; served module bodies remain old images. This does not exercise new lowering of the module bodies the gate claims to cover. | Add meaningful execution coverage for newly lowered module bodies, using test-local composition without editing C4 files, or explicitly narrow the claimed coverage and supply the missing gate. |
| Medium — contradiction | docs/design/yin.vm.semantic-register-vm.derivations.md:542 | All twenty rows are required to throw, but `:ffi-call-no-args` is supplied a bridge “so it completes.” The cited parity fixture implements `:op/echo`, not `:op/ping`. | Specify nineteen throw comparisons plus one successful FFI comparison, with an explicit zero-argument `:op/ping` bridge. Update slice acceptance wording. |
| Medium — gap | docs/design/yin.vm.semantic-register-vm.derivations.md:970 | The manifest-diff whitelist permits only the R index entry to change. Cutover also changes executable indexing under A; correcting deleted lift declarations can change H as well. | Enumerate the final index shape and every affected entry/address, including descriptor consequences. Preserve genuinely unchanged manifest fields. |
| Medium — defect | docs/design/yin.vm.semantic-register-vm.derivations.md:1101 | A mandatory `jing/get` existence check fetches the tree. The owner requires retrievability under `:trusted`, not mandatory fetching. | State the composition’s retrievability obligation without imposing a fetch; distinguish absence from transient retrieval failure. |
| Medium — gap | docs/design/yin.vm.semantic-register-vm.derivations.md:1103 | “Resolve a tree the same three ways” is undefined: current code indexes and carried values contain vectors, not tree row sets. One address also needs a rule for multiple source trees projecting to the same A. | Specify the segment-to-tree association, tree carrier/index shape, completeness checks and handling of multiple source trees. Keep canonical vector bytes independent of that association. |
| Low — evidence | docs/design/yin.vm.semantic-register-vm.derivations.md:430 | Several references resolve to the wrong namespace or lines: stack `linearize.cljc:103-105` is definition handling; tail reading is at 120–121. The cited projection tests at 83 and 107 are in `semantic_register/linearize_test.cljc`. | Qualify paths and correct anchors. The substantive omitted-facts argument remains sound. |

1. **Allocator.** Expiry, lowest-free assignment, parameter offset, interval reservation and structural-slot placement are deterministic. Widening intervals to include static live points is appropriate.

   Keep the frozen scan’s explicit “for each starting interval, increasing virtual id” loop in the pseudocode. The asserted single-start property is unnecessary to determinism; the implementation should not depend on it. Increment `next` explicitly only when allocating a never-used slot.

   Independent results:

   | Golden | Derived temporary allocation | Physical register count |
   |---|---|---:|
   | `(+ 1 2)` | operator T0, literals T1/T2, result T3 | 4 |
   | `(f (g x) y)` | f T0, g T1, x T2, inner T3, y T1, result T2 | 4 |
   | `(f (if c 1 2) y)` | f T0, c T1, conditional T1, y T2, result T3 | 4 |
   | Tail-call closure | main T0/T1/T2; body temporaries T0–T3 plus one local | 3 / 5 |
   | `(yin/def x 5)` | value T0, result T1 | 2 |
   | `(yin/def y (+ 1 2))` | call result T3, definition result reuses T0 | 4 |
   | `(f (resume :p x) y)` | f T0, x T1, y T1, result T2, structural T3 | 4 |

   These agree with the R images. Tail boundaries need the corrected live-out gate.

2. **H emitter.** Its traversal fixes emission order, pushes, labels and FIFO bodies. I independently checked `(+ 1 2)`, `(f (g x) y)` and both definition forms against today’s `lower-stack`; their listed vectors agree. The conditional-operand and closure-body targets require the corrections above. H equality remains an observation, not a compatibility gate.

3. **Facts omitted by A.** Sound: a lowering receiving only A cannot inspect omitted AST facts. Application tail flags remain encoded; non-application tail annotations are omitted. Macro flags, sharing identifiers and provenance do not enter either new derivation. The old lowerers do not consult the carried macro flag. Correct the evidence anchors, and compare vectors/addresses explicitly when testing projection invariance rather than relying on an entire provenance-bearing result map.

4. **Verification.** Execution equivalence, independent goldens, validators, liveness checks and determinism can replace a byte-equivalence oracle. The present specification overstates execution coverage: some corpus rows are designated only for bytes/validation, the C4 program names are wrong, and served modules use old images. State precisely which programs execute, which compare refusals, and how effect-bearing rows receive a controlled composition. Closure normalization is suitable for common results, but closure application tests remain necessary.

5. **Regeneration.** The old production callers and execution-oracle dependency correctly explain retention until phase 8. The list is not complete until descriptor/lift deletion and final A-based manifest indexing are included. Move descriptor-hash coverage out of a test namespace scheduled for deletion. “Descriptors unchanged” is defensible during coexistence, not after deleting their declared morphisms.

6. **Unitarity.** The four cases need distinct treatment:
   - Linker 225–232 is consistent; reconstruction is unnecessary.
   - `:trusted` must preserve retrievability, without requiring a fetch.
   - Host modules need a guest-code carve-out. Keeping the existing absent tree field is sufficient; introducing explicit nil is not necessary.
   - UCF needs a retrievable source association from every referenced segment/A, with a defined carrier and satisfaction rule.

   Do not imply that A uniquely identifies all original AST facts: §4 itself establishes otherwise.

7. **Amendment rulings.**
   - **F8 — ACCEPT WITH CHANGES**, frozen §8.2: retain total scan ordering; correct the liveness comparison.
   - **F9 — ACCEPT**, frozen §8.3: direct stack emitter, no adapter.
   - **F10 — ACCEPT WITH CHANGES**, frozen §8.4: make executable corpus coverage precise.
   - **F11 — ACCEPT WITH CHANGES**, frozen §6: retain names/versions, but regenerate descriptors affected by deleted declarations.
   - **F12 — ACCEPT WITH CHANGES**, frozen §10: incorporate corrected gates and paste the complete scanner replacement text rather than referring to a previous revision.
   - **F13 — ACCEPT WITH CHANGES**, frozen §9: include descriptor-dependent checksum changes; qualify expected H equality accordingly.
   - **F14 — ACCEPT WITH CHANGES**, frozen §10: include descriptor cleanup, final index/record migration and replacement coverage before deleting old tests.

8. **Slices and grounding.** Additive slices remain independent of 4b, apart from the already identified obligations-fixture follow-up. The acceptance lists become mechanical once the findings above are fixed.

   I checked more than fifteen reference ranges: phase-3 `code` validation/parse/load, body partition and analysis; lexical resolution; physical `body-liveness` and tail validation; old stack emission; resolver macro handling; row grammar; both descriptors; R golden; parity FFI fixture; linked-harness backend/composition/drive; import programs; publisher manifest construction; linker re-lowering; and UCF carrier/resolution rules. Mismatches are reported above.

9. **Portability and invariants.** The pure A-derived lowering architecture preserves the six invariants: allocation state is local, CFG edges and ownership are derived explicitly, and no callbacks or execution are introduced into interpretation. Existing portability guidance is appropriate. Unitarity requires the explicit retrieval association described above; a claimed address alone does not establish retrievability. No owner decision is needed to reopen the chosen route.

VERDICT: CHANGES_REQUESTED
