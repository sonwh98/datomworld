I’ll read the report and design, then check the frame rules, layout examples, and amendments against the referenced code and carrier draft.
The layout checks have exposed two areas that need closer scrutiny: reconstructing positional environments from a merged `E`, and computing tail-call live sets from the actual CFG. I’ll check those against the kernels and worked examples before ruling on the amendments.
The two stack examples and the register saved-window example check out. I found concrete gaps in the stack K slices, register return destinations, tail-state reconstruction, and free-environment recovery. The amendments also leave several contradictory clauses in the carrier draft; I’ll identify those precisely in the findings.
Completed-GMT: 2026-10-10 18:47:32 GMT
Completed-Local: 2026-10-11 01:47:32 Asia/Bangkok

The canonical row shape is a sound direction, but the reconstruction algorithms and several acceptance laws need correction before this design is accepted. No files were edited; no tests were run.

All continuation-design references below are to `docs/design/yin.vm.semantic-register-vm.continuation.md`.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High — defect | continuation.md:782 | Recovering `free-env` as `E` minus lexical names loses free bindings hidden by lexical shadowing. Those bindings can still be required by another closure. | Preserve the task’s free environment explicitly, or specify and prove an equally lossless representation. Amend §4.4, §6.3 and the root schema together. |
| High — defect | continuation.md:531 | K windows use `[stack-base_i, stack-base_{i+1})`. The kernel records `stack-base_i` **after** the caller’s residual operands, so that interval selects the next activation’s slice. | Frame `i` uses `[previous-base, stack-base_i)`, with initial previous-base zero. The current activation uses `[last-base, stack-count)`. |
| High — defect | continuation.md:534 | The assertion that a tail-call wait holds only enclosing-frame slices is false for accepted tail calls inside operands. The current stack kernel also resumes host effects at `pc+1`; it does not deliver them through K. | Specify the stack kernel’s tail-completion support and gate it explicitly. A layout alone cannot assume this transition already exists. Add the necessary file/dependency to 6c. |
| Medium — defect | continuation.md:469 | Register K reconstruction assigns physical `dest` directly to canonical `rd`. In the worked example, physical destination 3 corresponds to virtual destination 2. | Recover canonical `rd` from the addressed A call. Resolve each K frame’s segment and relative return pc independently, including frames crossing images. |
| Medium — defect | continuation.md:475 | The lower algorithm uses `pc`, `rd`, `env` and window unconditionally, although canonical `tail` has none of them. Stack lowering at line 539 has the same problem. | Give separate complete `act` and `tail` algorithms. For register tail entries use `site`, `dest nil`, `live []` and return delivery; state explicitly how unused native fields are populated. |
| Medium — defect | continuation.md:719 | The static laws compare every boundary against `saved(p+1, rd)`, contradicting phase-5’s actual-successor law for tail calls. Inverse uniqueness over every pc also fails when multiple definition-less ids share the structural slot in unreachable syntax. | Use actual-successor live-out; tail live is empty. Restrict inverse reconstruction to the registers required at reachable canonical safepoints, and separately validate unreachable structural operands. |
| Medium — specification gap | continuation.md:324 | The published validator takes only frame/code, but rules 13–14 require context, parked-table key and the task’s live FFI pendings. Rules 4–8 also lack explicit `act` guards; malformed K/window handling is underspecified. | Publish the complete pure input contract, including contextual data. Specify guarded applicability, structural checks and refusal kinds for malformed K and return windows before dependent reads. |
| Medium — defect | continuation.md:375 | §3.3 places frame validation before canonical numbering; §7.2 and the inherited carrier order place it after numbering. | Select one order and use it everywhere, including first-defect tests. Retaining carrier step 7 avoids changing the established ordering unnecessarily. |
| Medium — specification gap | continuation.md:687 | The cited `stream-blind` rewrites nested maps; it does not recompute Merkle row ids or the root. Blinding an already hashed row set cannot establish the stated root-id equality. | Specify a test-only transformation of decoded tasks followed by canonical numbering and complete re-emission, or use genuinely identical resource descriptors before hashing. |
| Medium — defect | continuation.md:1077 | U1 replaces the carried-vector sentence but leaves UCF lines 513–527 requiring vector→datoms loading. R8 deletes legacy §9 but leaves carrier §10.8 requiring legacy readers; §10.6 still says version 3 does not translate between kernels. | Extend the amendment inventory to every operative contradiction, including carrier §8 step 1’s registry gate, §10.5–10.8, proposal/open-item summaries and §11. |
| Medium — dependency gap | continuation.md:848 | 6a exposes full task lift/lower, but §7.1 requires the new phase-5 scanners. Its dependency list names only phase 3, 4a and 4b stage 1. | Gate full task transport on the scanner slice, or make 6a only the independent frame/row codec and defer complete task transport. |
| Medium — test gap | continuation.md:871 | In `(f (stream-next c) nil-reg)`, the final operand is evaluated after the read. It cannot supply the claimed earlier nil-valued live register. Line 965 also claims changing a live value causes `:window-extra`; membership validation cannot detect that. | Use an earlier nil operand, such as `(f nil (stream-next c))`. Tamper membership for membership refusals; test changed values through execution parity. |
| Low — grounding | continuation.md:1230 | The committed stage-1 `gc-children` method is at line 386, not 391. The module-context template at line 649 points to a fixture parked at explicit `:park`, not a blocked read inside a module closure. | Correct the anchor and describe the fixture’s actual coverage; provide a separate program for the proposed blocked module-closure row. |

## 1. Frame rows

The proposed `:act`, `:tail`, `:window`, `:k`, `:kframe` and `:wait` rows are sufficiently explicit. Encoding a present nil register as `r [:lit nil]` is correct. Using the state kind as the delivery discriminant removes a redundant delivery slot without losing information.

K ordering, retained-request values, module-store lookup edges and the content/cell distinction are consistent with the carrier draft. Merkle sharing and the census remain intact.

The missing piece is task context: a merged named E does not preserve the task-wide free environment. For example, with initial `x = 99`:

```clojure
(let [f (fn [] x)]
  ((fn [x] (f (stream-next c))) 1))
```

At the read, current E binds `x` to 1. The specified subtraction removes `x` from the receiver’s free environment. The lowered positional closure `f`, whose body uses `:load-free x`, then loses the binding 99.

**Choices:** dropping `:yin.k/engine` is **ACCEPTED**; carrying the free environment solely through merged E is **REJECTED as specified**. Keeping canonical frames engine-independent does not require discarding task context.

## 2. Fourteen validator rules

The essential obligations are present: A membership, bounds, site/destination agreement, saved-window membership, return sites, Rule R and pending compatibility.

They are not yet an implementation-ready ordered validator:

- Publish context and task bookkeeping as explicit pure inputs.
- Apply current-window rules only to `act`; validate return windows separately.
- Define malformed K/frame/window refusals and safe dependent reads.
- Require the appropriate pending presence/absence for each context.
- Align validation order with the carrier.

Rule 14 need not read an engine. It can read decoded canonical task context, including the parked key and corresponding pending. The distinction must be stated.

The fourteen-row tamper gate should remain, supplemented by rows covering the separate refusal variants.

## 3. Layouts

Independent derivations confirm the ordinary worked cases:

| Case | Canonical result |
|---|---|
| `(f (g x) y)`, inner call at A pc 3 | Resume pc 4; `L(4)={1,2}`; `saved(4,2)={1}`; stack slice `[f]`. |
| `(f (if c (g) 2) y)`, `(g)` at A pc 4 | Resume pc 5; shared conditional destination 2; saved set `{1}`; stack slice `[f]`. |
| Register image of `(f (g x) y)` | Saved virtual register 1 is physical slot 0. Inner result virtual 2 is physical slot 3. |

The non-push H↔A correspondence follows the phase-5 emitter: each A instruction has one corresponding H instruction, and the additional instructions are pushes. It does not depend on old-byte equality.

The operand-slice argument works for ordinary continuing paths. Its universal formulation fails for terminal paths and must distinguish `act` from `tail`.

The environment forward merge is plausible and agrees with evaluator closure entry. The stated inverse is not lossless: shadowed positional values cannot be recovered literally from a merged map, and hidden free bindings can remain semantically necessary. Tests over duplicate parameters do not prove otherwise.

## 4. Harness

The three lanes and six ordered cross-lane directions are appropriate. The canonical-equality law is valuable once its blinding/re-hashing procedure is specified.

The fifteen rows are templates, rather than fifteen fully specified executions: link phases and install modes introduce additional cases, while nested capture and capture invocation lack concrete programs and expected outcomes. Before the 6b brief, publish the expanded matrix, including which tail FFI/bookkeeping shapes are executable and which require synthetic validation fixtures.

Required stage-2 facilities include FFI retained→sent restoration, linker request/response/install preservation, install children and the evaluator’s linking methods on `IModuleKernel`. The stage-1 commit alone does not supply them.

The stack tail-completion seam is an additional prerequisite, separate from evaluator stage 2.

## 5. Code and carrier context

A-only canonical vectors, receiver-local native derivation and the phase-5 tree association are correct. Native images must remain outside this carrier.

Apply phase-5’s complete association rule: absent **or empty** tree sets are incomplete; a claimed tree address alone does not establish retrievability.

Free bindings require explicit preservation. Merkle sharing reduces duplication of equal maps; it does not recover bindings overwritten by a merge.

## 6. Amendment rulings

These rulings concern the proposed text; they do not authorize changes to frozen documents before the defects above are resolved.

| Amendment | Ruling | Target and qualification |
|---|---|---|
| F1 | **ACCEPT** | Frozen §4.2: state row names, delivery discriminant and sparse window encoding. |
| F2 | **ACCEPT WITH CHANGES** | Frozen §4.2: reference the corrected validator input contract and ordering. |
| F3 | **ACCEPT WITH CHANGES** | Frozen §4.3: correct slices, tail reconstruction, register destinations and environment obligations before inserting the layout summary. |
| F4 | **ACCEPT** | Frozen §10 phase 6: one canonical profile, A vectors and tree association; walker excluded. |
| U1 | **ACCEPT WITH CHANGES** | UCF §7.3.4: also replace lines 513–527’s retired projection obligations. |
| U2 | **ACCEPT** | UCF §7.4.1: explicit supersession and canonical frame example. |
| U3 | **ACCEPT WITH CHANGES** | UCF §7.4.2: update the remaining “observed deltas,” activation-base and stack-height explanatory text as well as the table. |
| U4 | **ACCEPT** | UCF §7.4.3: pending paired with either delivery state. |
| U5 | **ACCEPT** | UCF §7.6: adopt the complete phase-5 association text. |
| U6 | **ACCEPT WITH CHANGES** | UCF §7.11: use the corrected, mechanically specified equality law and complete engine prerequisites. |
| R1 | **ACCEPT** | Carrier §2: single profile and single reader version. |
| R2 | **ACCEPT** | Carrier §5.2: new state/window/K rows. |
| R3 | **ACCEPT WITH CHANGES** | Carrier §5.1: update the table’s decoded value and surrounding `registers R` descriptions, not only its cref spelling. |
| R4 | **ACCEPT WITH CHANGES** | Carrier §5.4: publish the pure task-context inputs needed for bookkeeping validation. |
| R5 | **ACCEPT WITH CHANGES** | Carrier §5.5: retain A-only code, but replace the insufficient free-environment treatment. |
| R6 | **ACCEPT WITH CHANGES** | Carrier §8 step 7: corrected validator API, contextual checks and consistent ordering. |
| R7 | **ACCEPT** | Carrier stamp statements: remove the obsolete unchanged-v3 claim. |
| R8 | **ACCEPT WITH CHANGES** | Carrier §9/§10.3: also remove §10.8 and other operative legacy-reader requirements. |
| R9 | **ACCEPT WITH CHANGES** | Carrier §10.7: reconcile §10.6, gate text, summaries and §11 with heterogeneous reconstruction. |
| C1 | **ACCEPT** | Code-as-tuples §7.2: direct-vector loading and the two AST projection lanes feeding one validator. |

The proposed amendments are not yet exhaustive. The carrier grep found additional surviving claims about legacy readers, semantic-only support, unchanged profiles and no kernel translation.

## 7. Slices and grounding

The file separation from 4b and phase 5 is sensible. The dependency graph needs correction: complete task transport requires the new scanners, and 6c needs an explicitly supported stack tail-completion seam.

Phase-7 entry conditions are otherwise appropriate: all reconstructing lanes green on three hosts, a transport seam for holder/checkpoint/ledger/authority, and Stage D/E shapes enumerated before re-encoding.

I spot-checked more than fifteen references, including:

- `ucf.cljc:51–74`, `77–91`, `247–251`, `434–442`;
- `analysis.cljc:15–28`, `92–97`;
- `analysis_test.cljc:131–139`;
- master evaluator `semantic_register.cljc:141–143`, `261`;
- committed stage-1 evaluator `61–84`, `97–113`, `229–234`;
- `handoff.cljc:491–504`, `588`, `674–749`, `897–980`, `1455–1457`;
- harness `92–106`, `251–304`, `368–401`, `591–603`, `675–692`;
- `v2_support.cljc:127–141`, `453`, `531–543`;
- `module.cljc:395–461`;
- `debruijn/register.cljc:326–337`.

The mismatches are reported above. I used the committed stage-1 code rather than the sibling worktree’s newer working-file contents.

## 8. Invariants and portability

No hidden global state, callback mechanism, shared mutable state or transport machinery is proposed. Explicit rows and contextual lookup edges satisfy the requirement to construct graphs from data. Validation remains distinct from execution.

The reconstruction defects threaten explicit control flow and information preservation: tail completion cannot silently become continued execution, and free bindings cannot disappear during lowering.

The BLAKE3/default-Jing identity rule is respected. Keep the carrier draft’s explicit version-3 BLAKE3 verification requirement when adopting its unchanged hashing section.

The portability notes correctly cover nil membership, large-sequence construction, reader conditionals, assoc-on-nil and private-var access. Correct the nil canary and protocol line anchor. Temporary coexistence with the untouched old VM is implementation sequencing, not permission for the new reader to accept legacy bodies.

VERDICT: CHANGES_REQUESTED
