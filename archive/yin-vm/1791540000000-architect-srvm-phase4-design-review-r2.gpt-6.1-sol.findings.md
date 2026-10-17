I’ll check both revisions against the eight findings, recount the corpora and trace example, and compare the frozen-design amendments with the qualified F1–F7 rulings.
The eight original findings are addressed, and the applied F1–F7 text matches the rulings. I found one remaining trace-oracle issue: `:none` is both a valid Yin value and the “no event” sentinel. The trace also identifies parking by result shape, which can mistake a literal parked-shaped map for an actual park.
Completed-GMT: 2026-10-10 12:58:30 GMT
Completed-Local: 2026-10-10 19:58:30 Asia/Bangkok

The eight original findings are fixed. Two remaining trace-oracle defects need small corrections; neither requires changing the frozen machine design.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| Medium — trace defect | docs/design/yin.vm.semantic-register-vm.evaluator.md:760 | `extract` returns `:none` for no event, but `:none` is also a valid expression result. The algorithm drops that result’s event. | Return a tagged result such as `{:event? true :value v}` or `{:event? false}`. Add a literal-`:none` trace case. |
| Medium — trace defect | docs/design/yin.vm.semantic-register-vm.evaluator.md:734 | Parking is detected from the result’s shape. A legal literal map shaped like a parked continuation is therefore recorded as `[:park …]`; the register algorithm similarly misclassifies its eventual halt. | Detect actual parking from the executed walker node/register instruction, not the returned value. Add a parked-shaped literal-map case. |
| Low — editorial | docs/design/yin.vm.semantic-register-vm.evaluator.md:623 | §4 introduces the frozen correspondence using the superseded “value-producing steps with register writes” quotation. | Refer to the amended production/delivery correspondence. Historical quotations explicitly identified as replaced text in §6 may remain. |

1. **Eight findings.** All addressed as recommended: role-based events; explicit destinations; shared slice eligibility; corrected counts and expected-value normalization; qualified FFI exception behavior; separated stamp/vector refusals; effect materialization and builder arguments; restore-seam and delivery-record corrections.

   Independent recount confirms **26 B0 rows**, **32 register-corpus rows**, and **25 B0 rows in 4a**. The cited lambda produces six value events: closure, `10`, `+`, `x`, `1`, `11`.

   Items 5–6 correctly use register 1 for `f`, destination 2 at pc 3, and saved window `{1 <f>}`. Item 12’s intermediate sequence **5, 7, 8, 9, 6** matches the `:if-in-tail` golden.

   The static predicate excludes all vectors containing `:park` or `:resume`, including out-of-line bodies. Excluding `:all-terminal-arms` and `:resume-operand` is correct. It conservatively excludes unsupported syntax even when an earlier error would prevent execution; the stated **21 eligible / 11 deferred** split is consistent.

2. **Events and extraction.** Production/delivery roles now correctly cover define, repeated literals, existing-value primitive results, tail primitive completion, and continuation invocation. `:return r` and `:halt r` remain relocation. Captured and parked destinations are read from pre-state records, avoiding window-difference inference.

   Resumed effects use the same delivery semantics; blocking cases remain covered by completion parity rather than the non-blocking step trace. Fuel is explicitly **100000 steps**, and matching fuel exhaustion still fails. The two value-classification defects above remain.

3. **Applied amendments.** F1–F7 match the rulings and are correctly placed:

   - F1 and §11 item 4 identify both restore paths and qualify terminal checking and active explicit resume.
   - F2 distinguishes deferred `base.env` from immediate E.
   - F3 specifies one K frame kind.
   - F4 distinguishes wire reasons from in-VM markers.
   - F5 uses semantic roles rather than value novelty.
   - F6 records the approved interim format and retirement phase.
   - F7 names module-aware `put-active`.

   The header accurately records the amendments. No opcode, canonical layout, state shape, or transition equation changed; the edits clarify engine behavior and the comparison procedure. No unauthorized amendment found.

4. **Cross-document consistency.** Grepping both documents found no operative recurrence of the old restore seam, FFI K-frame list, or value-novelty criterion. Remaining old formulations are identified historical quotations, apart from the stale §4 introduction noted above. Wire `:ffi` terminology remains appropriately qualified.

5. **Readiness for the 4a brief.** The evaluator decisions are sufficient. Fix the trace sentinel and parking classification before accepting the design, so the implementation brief carries a valid oracle. No further frozen-design amendment is needed.

VERDICT: CHANGES_REQUESTED
