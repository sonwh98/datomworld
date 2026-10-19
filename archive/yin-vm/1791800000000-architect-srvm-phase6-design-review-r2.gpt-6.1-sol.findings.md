I’ll check the revision against the thirteen findings, independently recount the stack example, and verify the new rules and amendment coverage.
The multi-frame example’s pushed operators give the stated six stack values and bases 2 and 4 for the two call frames. The lowering formula still uses the length before each slice, though, which would emit bases 0 and 2. I also found mismatches in the virtual/physical live-set subtraction and several new harness expectations.
Completed-GMT: 2026-10-10 19:05:34 GMT
Completed-Local: 2026-10-11 02:05:34 Asia/Bangkok

The revision fixes most of the architecture, but several formulas and new test oracles remain incorrect. No files were edited or tests run.

References abbreviated as `continuation.md` below mean `docs/design/yin.vm.semantic-register-vm.continuation.md`.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High — defect | continuation.md:624 | Stack lowering sets each base to the length **before** its slice. The corrected lift uses the base as that slice’s end. The worked case would lower to bases 0,2 rather than 2,4. | Use cumulative length **including** `slice(fᵢ)`, in both act and tail lowering. |
| Medium — defect | continuation.md:504 | `live-out*` contains virtual ids, but the formula subtracts `phys(rd)`. The same error occurs at line 858. For the worked example, `{1,2} − {3}` incorrectly retains destination 2. | Compute `sort(map phys (disj live-out* rd))`; alternatively subtract the physical destination after mapping the whole set. |
| Medium — implementation gap | continuation.md:904 | The root now preserves the initial free environment, but its source on semantic-register export is unspecified. The evaluator retains mutable-current `:env`, not a separate initial map. | Specify an explicit construction/export context or coordinated retention seam. Do not recover the initial map from current E. Name its ownership and dependency without silently editing 4b’s file. |
| Medium — validator gap | continuation.md:401 | E2 checks only names outside the lexical set. It permits missing lexical bindings, which lowering fills with nil, breaking exact canonical round trip. Root free bindings also need their own reserved-name check. | Require all lexical names to be present, including nil bindings; validate the root free environment’s shape and reserved names. Keep exact integer guards explicit in C2 before indexing. |
| Medium — incorrect oracle | continuation.md:773 | Invoking the captured continuation with `:second` changes `k`, then re-executes `(next-of c)`. It does not make that read return `:second`; with only `:first` appended, the program blocks again. | Supply a second stream value, or restructure the program to branch on the delivered `k` before repeating the read. |
| Medium — incorrect refusal oracle | continuation.md:796 | Current E’s `x` is lexical and excluded by E2; changing it to 98 does **not** produce `:free-env-mismatch`. At line 1042, changing a tail site to a non-tail call fails T1 with `:site`, before P4. | Tamper an unshadowed free binding for E2. Expect `:site` for the stated tail-site tamper; use an otherwise valid tail with an unmatched bookkeeping key to exercise `:parked-site`. |
| Medium — specification gap | continuation.md:843 | First-occurrence placeholder assignment is not established as renaming-invariant. Traversal of maps/sets and ordering “cells by body” can depend on the stream identities being erased. Subsequent cell numbering does not rename those placeholders. | Specify an identity-independent traversal/canonicalization, or provide the harness’s explicit logical-stream correspondence. Include an unordered-container permutation test. |
| Medium — amendment residue | continuation.md:1393 | R9 replaces the end of carrier §10.6, but leaves its opening requirement that a separate, undrafted value-profile document precede **any** heterogeneous implementation brief. | Qualify that requirement for engines outside the three repository lanes, consistently throughout §10.6. Explicitly scope remaining UCF legacy-body clauses as historical for the new carrier. |
| Low — fixture/count errors | continuation.md:780 | `f = cat` means `(conj nil "B")`, yielding a one-element sequence, not `[nil "B"]`. The rule groups at line 987 total 23, not 24. The standard `with-cat` wrapper adds an empty return frame to line 1087’s two-frame example. | Correct the nil result or use a vector-building f; correct the rule count; specify the multi-frame fixture’s binding setup or include the wrapper’s zero-base frame. |

1. **Free environment and E2 — partially fixed.**  
   The root slot is the correct correction, and row 21 returns 99 under the prescribed preservation. The source-side acquisition seam remains unspecified. E2’s off-lexical comparison is correct, but needs lexical-key presence and root-binding validation. Its claimed lexical-x tamper refusal is wrong.

2. **Stack slices — lift fixed; lower still wrong.**  
   Independently deriving the displayed expression gives:
   - after entering the x closure: `[cat [1]]`, base 2;
   - after entering the y closure: `[cat [1] cat [2]]`, base 4;
   - at the read: `[cat [1] cat [2] cat [3]]`, length 6.

   Thus the slices are `[0,2)`, `[2,4)`, `[4,6)`. Each includes the pushed operator. With the specified ordinary `with-cat` binding wrapper, K additionally contains its empty frame, giving bases `0,2,4`.

3. **Stack tail seam — fixed in architectural direction.**  
   `:resume-mode :return-result` is sound: return delivery uses the saved K, truncates at the popped frame’s base, and skips terminal-path layout syntax. Preserve the existing retained-FFI re-park and response-decoding order before final delivery.

   **6c-pre is honestly scoped:** it explicitly changes `debruijn/stack.cljc`, which is absent from the other slices’ file lists. It is a prerequisite, not existing support. Its correctness still depends on fixing the lowering bases.

4. **Register K destination and image coordinates — fixed.**  
   Every K frame now resolves its own image and relative return pc, and obtains canonical rd from A’s addressed call. The worked distinction remains virtual rd 2 versus physical dest 3.

5. **Separate act/tail algorithms — fixed.**  
   The shape distinction is explicit. Register tail lowering correctly specifies empty regs/live, nil dest and return delivery. Stack tail lowering correctly drops the current dead operand slice. The cumulative-base correction applies to both stack algorithms.

6. **Actual-successor liveness — partially fixed.**  
   Tail live-out is correctly empty, and inverse reconstruction is restricted away from unreachable structural operands. The virtual/physical subtraction error must be corrected in §4.2 and §5.6.

7. **Pure validator contract and guards — substantially fixed.**  
   Context and decoded task inputs are explicit; current-window rules are act-only; K structure and return windows have separate checks. The remaining E2 and exact-type obligations are listed above. The published groups contain **23 rules**.

8. **Validation order — fixed.**  
   Frame validation consistently follows canonical numbering at carrier step 7. The combined-defect test correctly prioritizes `:noncanonical-cells`.

9. **Blinding — partially fixed.**  
   Decode, renumber and complete Merkle re-emission correctly replace post-hash mutation. The new placeholder traversal still needs a precise renaming-invariance argument or explicit fixture correspondence.

10. **Amendment inventory — substantially fixed.**  
    U1 now removes UCF’s vector→datoms load obligation. R1/R6a/R8/R9 cover the principal semantic-only gate, unchanged-profile and legacy-reader clauses previously missed. R10 and R11 are appropriate additions.

    Grep still finds historical legacy-body obligations in UCF, including version-0 admission and recovery descriptions. They need an explicit scope/precedence statement for the new single-version carrier, rather than being implicitly inherited. Carrier §10.6 retains the operative value-profile prerequisite described in the findings.

11. **6a dependency — fixed.**  
    Independent codec/validator work remains in 6a. Full task transport moves to 6b with dependencies on stage 2 and phase-5 scanners. The free-environment acquisition seam must also be named.

12. **Nil canary and value tampering — partially fixed.**  
    Evaluating nil before the read correctly makes it a saved-window member. Changing a saved value is correctly tested through execution parity rather than membership refusal. The nil result and new refusal expectations require correction.

13. **Expanded matrix and grounding — substantially fixed.**  
    The matrix distinguishes executable and synthetic cases, splits linker/install phases, and adds module, shadowing and nil coverage. Rows 15, 22 and 23 have the oracle defects above. Expected results should also explicitly distinguish the bare expression from the four segment wrappers.

    I spot-checked more than ten revised anchors: stack `418`, `555–568`, `683`, `716–733`, `748–752`, `766–772`, `355`; register `461–468`, `472–480`, `690–704`; de Bruijn `617–637`; AST helpers `56–104`; harness cat `69–81`; module-store fixture `310–333`; and committed stage-1 `gc-children:386`. These anchors support their stated code facts. The numerical and fixture discrepancies are reported above.

The amendment rulings remain:

| Amendments | Reconfirmed ruling |
|---|---|
| F1, F4 | **ACCEPT**, frozen design |
| F2, F3 | **ACCEPT WITH CHANGES**, frozen design; apply the corrected validator and layout text |
| U1, U2, U4, U5 | **ACCEPT**, UCF |
| U3, U6 | **ACCEPT WITH CHANGES**, UCF; align the corrected laws and equality procedure |
| R1, R2, R3, R4, R7, R8 | **ACCEPT**, carrier draft |
| R5, R6, R9 | **ACCEPT WITH CHANGES**, carrier draft; complete free-environment validation and the remaining scope qualification |
| C1 | **ACCEPT**, code-as-tuples |
| R1a, R6a, R10, R11 | **ACCEPT**, carrier draft |

Apply corrections in this order: stack bases; live-set formula; free-environment acquisition and validation; harness oracles/counts; identity-independent re-emission; amendment scope.

VERDICT: CHANGES_REQUESTED
