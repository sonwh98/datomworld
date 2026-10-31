I’ll check the revision against each finding, re-derive the corrected golden and scanner fixture facts, and spot-check the new code references.
The address and scanner oracles now separate the comparisons that previously conflicted. I’m checking the new allocation lemmas and runtime gate closely; these add claims beyond the requested corrections.
The corrected `(f (g x) y)` golden and `:define-then-call` facts check out. I found remaining issues in the raw C4 oracle, the new allocation lemmas, and the proposed F11 wording. The new public address helper also conflicts with the namespace-export acceptance test.
Completed-GMT: 2026-10-10 16:07:08 GMT
Completed-Local: 2026-10-10 23:07:08 Asia/Bangkok>

Most corrections are sound. Four specification issues remain before merge; the runtime selection also needs a wording correction. Only the derivations document changed. No files edited or tests run.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High | docs/design/yin.vm.semantic-register-vm.derivations.md:968 | `prelude/free-names` includes the definition operator: its generic walk visits a definition’s `yin/def` variable. `scan/free-names`, `vm/free-names` and linker obligations exclude it. The new raw C4 equality therefore fails on modules containing definitions. | Compare against `(disj (set (prelude/free-names ast)) vm/definition-operator)`. Apply that qualification to the acceptance list too. |
| Medium | docs/design/yin.vm.semantic-register-vm.derivations.md:430 | The new allocation lemmas confuse a child expression’s result with its first emitted instruction. For `((f))`, frozen scanning emits `f` into T0, the inner result into T1, then reuses T0 for the outer result. R1 also gives the outer result T0. The stated root-result lemma is false; the call-destination lemma has the same unsupported operator assumption. | Restrict the claims to cases whose relevant child/result interval demonstrably occupies T0 through the consuming instruction, or retain only the worked examples and individually established corpus cases. |
| Medium | docs/design/yin.vm.semantic-register-vm.derivations.md:1144 | The export gate permits only `addresses`, but §5 now requires public `resolved-addresses`, whose name also contains `address`. | Permit both address-sequence readers; distinguish them from functions assigning an executable identity. |
| Medium | docs/design/yin.vm.semantic-register-vm.derivations.md:1557 | F11’s proposed frozen amendment still describes an unspecified ordered tree oracle and only the join’s retained obligations. §6.3 now correctly distinguishes structural-order multisets, an emission-order oracle, and complete success/refusal outcomes. | Bring F11’s replacement text into agreement with those three obligations, explicitly including refusal comparison. |
| Low | docs/design/yin.vm.semantic-register-vm.derivations.md:1172 | “The other 22 reference unbound names” is false: `:closure-in-arm-in-body` has no free names and returns a closure; terminal/resumption rows have other exclusion reasons. | Describe these ten rows as the selected runtime corpus, or enumerate the actual exclusion reasons. |

1. **Address oracle — fixed.** The corrected resolved walk skips a definition’s synthesized operator and key, visits its value, and preserves FIFO body traversal. The helper is public and avoids private-var reflection. The export gate conflict above is new.

2. **Scanner order/site oracle — fixed.** Unordered structural-tree comparison and ordered emission-tree comparison are now separate. The independent emission oracle excludes definition applications from sites and includes FFI sites. Its implementation brief should preserve the specified main-then-FIFO traversal.

3. **Join agreement — fixed in §6.3 and acceptance tests.** Public `linker/verify` exercises validation and the join. Normalized outcomes retain refusal reason/name and successful obligation name/flag sequences. `(list n (yin/def n 1))` does refuse for `n`: its main-sequence occurrence precedes its only definition. F11 still needs the same obligation stated explicitly.

4. **C4 declarations — partially fixed.** Removing defined keys and requirement-covered namespaces matches `lower/module-spec`; the read-only boundary remains intact. The added raw oracle needs the reserved-operator exclusion above.

5. **Worked golden — fixed, independently re-derived.** R1 reserves result 0, operator `f` 1, inner result 2, `g` 3 and `x` 4. After the inner call, slot 3 is reused for `y`. The inner saved live operand is `[1]`; the outer one is `[]`. Seven instructions give inclusive body end 6 and five registers, exactly as revised.

6. **Fixture facts — fixed, independently re-derived.** For `:define-then-call`:
   - Free occurrences: `[inc false]` at pc 3, `[n false]` at pc 4, `[list true]` at pc 8.
   - Definition: `[n false]` at pc 2.
   - Application sites: pcs 5, 6 and 11.
   - `a` and `b` are bound at depth 0, positions 0 and 1.

   The other listed fixture facts, including `:if-in-tail`’s four sites, match their goldens.

7. **F9/body positions — fixed.** Discovery order agrees; instruction positions belong to each emitter. The duplicate parse and obsolete composition formula are removed.

8. **Public APIs/imports — fixed.** Format-record functions and `linker/verify` provide public access to private scanner/join implementations. Qualified rows are constructed locally in `scan`; the old code namespace is test-only. No new private-var access is required.

9. **Runtime gate — fixed substantively.** Composition, halting assertions and common closure normalization are specified. Full byte comparison remains independent of runtime selection. Correct the exclusion explanation noted above.

10. **Counts/allocation wording — partially fixed.** The stack corpus count is correctly 15. B0’s ten atomic rows and sixteen differing rows are consistent with its actual forms, but the new generalized lemmas do not establish every call-rooted register-corpus case.

11. **§4.4 re-version procedure — fixed.** A discrepancy blocks the slice and requires investigation. Re-versioning requires an explicit reviewed changed-contract decision. Independent b2/r2 evidence rows are appropriate.

12. **Resolve side tables — fixed.** Closure pc sets and per-entry parameter vectors are compared; provenance is excluded. The source table must cover synthesized records too.

13. **Amendments and owner questions.**
   - **F8: ACCEPT WITH CHANGES.** Adapter route and replacement formulas match the ruling; correct its supporting allocation claims.
   - **F9: ACCEPT.**
   - **F10: ACCEPT.** Phase 5 leaves lifts/descriptors unchanged. Phase 8 must preserve a genuine legacy contract or openly change/version it.
   - **F11: ACCEPT WITH CHANGES.** Align its frozen replacement with revised §6.3.
   - **F12: ACCEPT.**

   All three owner questions now reflect the recommended tradeoff, genuine lift-contract decision, and optional profile renaming.

I spot-checked more than eight revised references, including `linker.cljc:692-731`, `:1090-1160`, `:1363-1420`; `completion.cljc:195`; `prelude.cljc:3395-3466`; `lower.cljc:1827-1843`, `:1864-1877`; native stack construction at `stack.cljc:369-394`; and `linked_harness.cljc:118-120`. The significant reference problems are semantic qualifications identified above, not missing APIs.

VERDICT: CHANGES_REQUESTED
