I’ll read the amendment, implementation report, and eight new files, then check the parser, validation order, liveness reference, and phase-3 gates against the frozen design. I’ll keep both worktrees unchanged.
Completed-GMT: 2026-10-10 09:23:40 GMT
Completed-Local: 2026-10-10 16:23:40 Asia/Bangkok

The implementation substantially follows the frozen design, and the whole-vector item-13 amendment is correct. One specification/gate mismatch needs resolution before merge. I inspected the code and tests; I did not rerun the reported checks or edit either worktree.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| Medium — merge-blocking specification gap | `docs/design/yin.vm.semantic-register-vm.md:567`; `docs/design/yin.vm.code-as-tuples.md:1123` | The design still requires direct-vector versus vector-to-datoms loading, including equal refusals for malformed vectors. `test/yin/vm/semantic_register/load_paths_test.cljc:18` instead compares valid AST projections that all call `load-vector`. This is useful, but a different obligation. | Explicitly amend the phase-3 gate to the owner-authorized direct-vector architecture: one vector validator, both AST projection lanes feeding it, and direct malformed-vector refusals. Mark vector-to-datoms equivalence retired. If that path remains required, implement and test it instead. |

1. **Fidelity to the frozen design**

   Operand positions, supported opcodes, body-local pre-order numbering, conditional destination reuse, resume’s conceptual destination, occurrence expansion and FIFO body emission match the design.

   The shift-reduce parser recovers the stipulated expression structure. `leaves`, `minted`, exclusive-definition validation and the forward assignment walk cover definition-less expressions, nested conditionals, terminal children and continuing-arm joins.

   Validation runs in the declared rule order. Analysis implements the specified CFG, syntactic def/use sets, whole-CFG liveness and explicit destination subtraction.

   The material divergence is the load-path interpretation above. No additional implementation defect found in the inspected paths.

2. **Seven ambiguity readings**

   - **Rules 10/12:** accept structural layout validation through rule 6’s parse, with later kind checks. Deferred malformed operands can still trigger an earlier structural/register defect; this should be understood as first-defect behavior, not a promise that every bad argument reaches rule 10.
   - **Rule names:** accept.
   - **Minting reconstructed from operands:** accept for valid vectors. Parent references recover conceptual destinations; rule 13 independently checks canonical numbering. The malformed-input fallback is validation machinery, not authority to choose a canonical ID.
   - **Tail-call syntactic def:** accept. Its empty successor set prevents destination def from affecting live-out.
   - **Fixed-point liveness:** accept. Reverse sweeps are safer than relying on the prose’s pass-count claim.
   - **Load paths:** accept the architectural choice only after the normative gate is amended. The report alone cannot retire the frozen obligation.
   - **Image shape:** accept for phase 3; executable decoding belongs to phase 4.

3. **Item-13 amendment**

   Comparing the entire re-projected segment is the right rule. The implementation first checks numbering at existing body positions, then re-emits the segment in FIFO body order. Its register/layout diagnostic distinction is reasonable and tested.

   The label-placement note holds within this parser’s accepted grammar: arm boundaries determine the recovered tree, and re-emitting that tree reproduces its internal branch layout. Moving a label can change the parsed tree or fail earlier validation; it need not produce a separate layout refusal. Body ordering remains a genuine segment-level canonicality check.

4. **Tests as gates**

   Passed on inspection:

   - Shared inline vectors and **literal pinned addresses**, exercised by both projection lanes.
   - Opcode coverage and refusal coverage for every applicable rule.
   - Terminal, definition-less and nested-body cases.
   - Independent liveness comparison.

   The reference is genuinely independent in implementation: it has its own operand positions and edge construction and uses a predecessor worklist rather than production reverse sweeps. It calls neither production analysis nor code helpers.

   The supplied three-host results support portability and determinism; I did not independently rerun them. The load-path gate remains the sole unresolved specification claim.

5. **Portability**

   No additional Node/Dart-specific blocker found. The new code avoids the listed problematic constructs, uses the repository’s established exception reader conditionals, and pins ordering through vectors, sorted body starts and explicit scans. The reported three-host execution provides meaningful coverage.

   Very deep expressions still use recursive parsing/projection, as the existing linearizer does. That is an implementation limit to assess with workload evidence, not a new architecture defect.

6. **Readiness for phase 4**

   The image contains enough: vector, verified address, body boundaries, owner PCs and register counts. Public `parse` and `analysis/analyze` expose the remaining derived facts.

   Phase 4 should compute analysis once per admitted image and retain it as engine-local derived data. Parsed trees or saved sets need not enter canonical content or the persisted image contract. Document that body `:end` is exclusive.

   The evaluator brief should preserve the already frozen act/tail delivery shapes, pending-phase transitions and exactly-once restore rules. Phase-3 acceptance establishes syntax and static analysis, not evaluator or UCF parity.

VERDICT: CHANGES_REQUESTED.
