I’ll check the nine corrections and independently derive the wrapper’s return frames, including how `with-cat` establishes its bindings.
The fully specified fixture has five return frames with bases `0, 0, 0, 2, 4`: the `with-cat`, stream and cursor bindings contribute three empty slices before the two operand slices. The explicit free-environment argument also lets phase 6 proceed without changing the committed 4b stage 2. I’m checking the remaining amendment scope and reference claims.

Completed-GMT: 2026-10-11 03:55:12 GMT
Completed-Local: 2026-10-11 10:55:12 Asia/Bangkok

The nine previous findings are addressed. Two inconsistencies remain in the revised acceptance text. No files were edited or tests run.

References below use `continuation.md` for `docs/design/yin.vm.semantic-register-vm.continuation.md`.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| Medium — acceptance inconsistency | continuation.md:1095 | 6a item 2 still requires rows 18–22, including row 20, over all four segments. Revised row 20 must run outside the wrappers to preserve empty K; 6b correctly makes this exception. | Exclude row 20 from the four-segment group and require its single, unwrapped run separately. |
| Low — incorrect explanation | continuation.md:1224 | The text groups a free-env cursor with a nonportable host primitive. Authentic cursor references are machine data and have an explicit carrier representation; `engine.cljc:899–905` translates them to cursor markers. The cursor operand also need not remain live after the read consumes it. | Retain the host-primitive restriction. Describe in-program cursor construction as the fixture’s chosen setup, rather than a carrier restriction. |

1. **Stack lowering bases — fixed; dispute resolved in Fable’s favour for the newly specified fixture.**  
   The expression alone contributes bases **2,4**: entering the x closure leaves `[cat [1]]`; entering y leaves `[cat [1] cat [2]]`. At the read, the current slice is `[cat [3]]`, giving stack length six. The explicit `with-cat`, `let1 s`, and `let1 c` bindings contribute three preceding empty frames. Therefore:
   
   - bases: **0,0,0,2,4**;
   - saved slices: `[0,0)`, `[0,0)`, `[0,0)`, `[0,2)`, `[2,4)`;
   - current slice: `[4,6)`.

   My earlier **0,2,4** count covered `with-cat` without the two resource-binding wrappers. The revised cumulative-through-slice formula is the correct inverse, for both act and tail lowering.

2. **Live-set formula — fixed.**  
   Subtraction now occurs on virtual ids before physical mapping. For the inner call, `{1,2} − {2} = {1}`, mapping to `[0]`. Tail live-out remains empty under actual-successor liveness.

3. **Free-env acquisition — fixed.**  
   The explicit composition-owned initial map supplies the missing information without recovering it from current E. Deferring automatic retention to **4b stage 3 is acceptable**; committed stage 2 need not be amended for this design to proceed. Stage 3 must retain the checked initial map and include it in GC roots. Until then, compositions using lift must preserve and pass that map explicitly.

4. **E2, X1 and integer guards — fixed.**  
   E2 now requires lexical-key presence, including nil, and compares complete off-lexical bindings. X1 validates the root environment before frame validation. C2 guards kinds and bounds before indexing. The rule inventory correctly totals **24 rules and 25 tamper rows**.

5. **Continuation-invocation oracle — fixed.**  
   Row 15 branches on the delivered `k` before attempting another read. Invocation with `:second` re-enters the capture successor and reaches `:done` without repeating the read.

6. **Refusal oracles — fixed.**  
   Changing unshadowed y fails E2; changing lexical x does not; removing x fails presence. A non-tail replacement site produces `:site`; an otherwise valid tail with an unmatched bookkeeping key reaches `:parked-site`.

7. **Placeholder renaming — fixed.**  
   Explicit fixture correspondences replace traversal-derived assignment. Unknown markers fail the test. Substitution, canonical cell numbering and complete Merkle re-emission now support the equality claim; the unordered-container permutation gate exercises it.

8. **Amendment residue — fixed.**  
   R9 qualifies the value-profile prerequisite for engines outside the three repository lanes. U7 scopes historical body readers while preserving custody, fencing and operation-id semantics, with the carrier’s custody table governing the current representation. Export-recovery rebasing is explicitly a phase-7 prerequisite.

9. **Nil fixture, harness counts and grounding — fixed except for the two findings above.**  
   Row 22 produces `[nil "B"]` and keeps the nil register present across the wait. The revised matrix has **22 wrapped executable rows × four segments + one unwrapped row = 89 runs**, plus synthetic row 23.

   I spot-checked more than ten anchors, including stack `716–733`, `766–772`, `838–839`; harness `69–106`; AST helpers `56–104`; module-store fixture `310–333`; analysis tests `131–139`; engine `55–60`, `312–325`, `899–905`; CBOR `842–853`; and committed 4b stage-2 evaluator `205–217`, `349–375`, `485–488`. The cursor explanation is the remaining grounding mismatch.

The amendment rulings remain:

| Amendments | Ruling and target |
|---|---|
| F1, F4 | **ACCEPT** — frozen design |
| F2, F3 | **ACCEPT WITH CHANGES** — frozen design; required changes are now incorporated |
| U1, U2, U4, U5 | **ACCEPT** — UCF |
| U3, U6 | **ACCEPT WITH CHANGES** — UCF; required changes are incorporated |
| U7 | **ACCEPT** — UCF historical-reader scope |
| R1, R2, R3, R4, R7, R8 | **ACCEPT** — carrier draft |
| R5, R6, R9 | **ACCEPT WITH CHANGES** — carrier draft; required changes are incorporated |
| R1a, R6a, R10, R11 | **ACCEPT** — carrier draft |
| C1 | **ACCEPT** — code-as-tuples |

Before merge, align 6a’s row-20 exception with the matrix, then remove the incorrect cursor portability explanation.

VERDICT: CHANGES_REQUESTED
