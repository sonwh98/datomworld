I’ll compare each applied amendment with the approved design text, then check the five documents for stale claims and conflicting cross-references.

The main amendments are applied, including the free-env root, A-only code maps and the execution-oracle gates. I’ve found residual vector-projection requirements and a frame-validator reference that need checking against the source designs.

Completed-GMT: 2026-10-11 06:22:06 GMT
Completed-Local: 2026-10-11 13:22:06 Asia/Bangkok

Reviewed commit `695c3ec0`. The amendment application is substantially faithful, but several operative passages remain inconsistent. No files were edited or tests run.

All paths below are relative to `docs/design/`.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High — contradictory identity specification | yin.vm.semantic-register-vm.md:790 | §7 expressly preserves “two load paths” and vector projection to datoms. UCF §7.3.2 still specifies the stack-shaped vector, old opcode table and batch-loader normalization as canonical. These conflict with the amended direct-vector, register-shaped contract. | Remove the frozen §7 projection claim. Add explicit precedence at UCF §7.3.2: for `"v4"`, the canonical vector and validator are frozen §§2.3/3.3/3.4; distinguish the old stack specification as historical. |
| Medium — incomplete root validation | yin.vm.ucf-transport-tuples.md:671 | Step 7 invokes `frame/defect` for frames but omits the once-per-body `frame/task-defect` check required by continuation §3.3. A halted body can have no frames, so frame validation cannot establish X1. | Invoke `frame/task-defect` on each body’s decoded task context before validating its frames, including halted bodies. |
| High — incomplete dependency roots | yin.vm.ucf-transport-tuples.md:683 | The census root list omits the newly carried free environment. UCF’s fixed-point roots at line 1459 also omit it. Values reachable only through `free-env` can carry closures, resources and dependencies. | Include the initial free environment in both root lists, consistently with continuation §7.1. Preserve exact census agreement and the complete tree-association check. |
| Medium — retired-path residue | yin.vm.code-as-tuples.md:1095 | §7.1 still loads `:yin.code/*` datoms through UCF’s now-deleted projection rule. The §10 impact table at lines 2008–2011 retains the old validator/path arrangement. UCF line 2956 still requires direct/projected loads. | Preserve the AST datom lane, but remove the retired instruction-vector projection path and its acceptance requirements. Update the impact table and UCF matrix to the two AST lanes plus direct vector load. |
| Low — broken references | yin.vm.ucf-transport-tuples.md:313 | Rule A7 does not exist; destination validation is A2. The typo was copied from the continuation design. Frozen §8.4 also leaves its §6.1.3/§6.1.4 references unqualified. | Refer to continuation §3.2 A2; correct the companion’s matching typo. Qualify those frozen references as derivations-design sections. |

1. **Fidelity — substantially passed.**  
   I checked every named application:

   | Amendments | Result |
   |---|---|
   | F8–F14 | Applied in the intended frozen sections; allocation, execution coverage, scanner obligations, regeneration and cutover qualifications are present. |
   | Derivations §9.2 | All five proposed changes are applied; the “One tree” passage correctly remains unchanged. |
   | F1–F4 | Applied with canonical rows, explicit free environment, pure validator context and both reconstruction lanes. |
   | U1–U7 | Applied; the direct-vector obligation, source association, three-engine equality and historical-reader scope are preserved. |
   | R1–R11, R1a/R6a | Applied; engine-less profile, new rows/root, reader removal and BLAKE3 checks are present. |
   | C1 | Applied in §7.2 with the intended lane/direct agreement and malformed-vector obligation. |

   The context adjustments reported by Fable are acceptable. The root-validation and census findings require completing the application against continuation §§3.3/7.1; literal application of the shorter amendment inventory is insufficient there.

   I also consulted the accessible review records. The two latest phase-6 corrections—row 20’s unwrapped exception and the cursor explanation—are incorporated in the companion design now. Fable’s stated reliance on the designs’ ruling records is consistent with its report; no contradictory application was found.

2. **Reconciliations — passed.**  
   U1 and the §9.2 source-association text preserve resolution order, association merging, nonempty completeness and transient-fetch distinctions. U6’s replacement of the projection/direct clause is correct. The merged phase-6 parenthetical, obsolete byte-version wording repairs and carrier numbering adjustments preserve their obligations.

3. **Residue — incomplete.**  
   The findings identify operative residue requiring correction. Historical body clauses covered by U7, native stack-kernel `stack-base`, and instruction operand-kind `:regs` are legitimate remaining occurrences.

   Carrier §3’s SHA-256 example describes self-describing addresses followed by mandatory rejection of non-BLAKE3 row ids; it introduces no new hash use. Code-as-tuples’ SHA-256 row-id examples at lines 55/175 should preferably use BLAKE3 for consistency.

4. **Cross-references and records — mostly passed.**  
   The cited derivations and continuation sections exist and support the applied claims, except the references listed above. All five documents have amendment records. The runtime-shape qualification in the frozen header is compatible with changing carrier row shapes.

5. **Scope — passed.**  
   `git diff master...HEAD` changes exactly the five requested documents. No code, companion design or foundational document changed.

Apply corrections in order: canonical-vector/load-path precedence; root validation and dependency census; residual load-path gates; reference fixes.

VERDICT: CHANGES_REQUESTED
