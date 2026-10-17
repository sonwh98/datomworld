I’ll check the five corrections, re-derive `((f))` and two rows covered by the replacement lemma, and verify the new references.
Completed-GMT: 2026-10-10 16:12:17 GMT
Completed-Local: 2026-10-10 23:12:17 Asia/Bangkok

Four findings are fixed. The replacement allocation lemma needs one further qualification.

| Severity | file:line | evidence | correction |
|---|---|---|---|
| Medium | docs/design/yin.vm.semantic-register-vm.derivations.md:435 | “The destinations differ” does not follow for every call with an atomic operator at pc 0. For `(if (f) 1 2)`, R1 reserves root T0, test result T1 and operator T2. Frozen scanning assigns operator T0 and test result T1. Both call destinations are T1, despite satisfying the stated premise. | Restrict destination inequality to body-root calls. For the B0 conditional tests, state their separately derived destinations, or use the operator-slot difference to establish unequal images. |

1. **Raw C4 oracle — fixed.** Both §6.3 and the acceptance item remove `vm/definition-operator` from `prelude/free-names`. This matches the scanner’s treatment of definition syntax.

2. **Allocation claim — partially fixed.** The `((f))` derivation is correct:

   | Value | Frozen interval | Frozen slot | R1 slot |
   |---|---|---|---|
   | `f` | `[0,1]` | T0 | T2 |
   | Inner result | `[1,2]` | T1 | T1 |
   | Outer result | `[2,3]` | T0 | T0 |

   At pc 2, the scan expires `f` and reuses T0.

   Two covered rows independently check out:
   - `:zero-arity-call`: R1 operator/result = T1/T0; frozen scan = T0/T1.
   - `:lambda-application`: main closure, argument, result = T1/T2/T0 under R1, and T0/T1/T2 under frozen scanning.

   The listed seventeen register-corpus rows have a body-root call with an atomic operator at pc 0. Their coverage is sound under the corrected root qualification. B0’s fourteen call roots also satisfy it. Its two conditional tests differ too: R1 assigns test result T1; frozen scanning assigns it T3 after `<` and two literals. The sixteen-row conclusion survives, but the general lemma currently overstates its proof.

3. **Export gate — fixed.** Both address-sequence readers are permitted and their result shape is checked. Neither is mistaken for an executable identity function.

4. **F11 amendment — fixed.** It now states unordered structural-oracle agreement, ordered emission-oracle agreement, and complete normalized join outcomes, including refusals. The read-only C4 declaration qualification is preserved.

5. **Runtime selection — fixed.** The twelve selected rows include both closure-valued programs. `:body-queue-order` returns its nested `[q]` closure through the `[r]` identity closure; `:closure-in-arm-in-body` returns the selected `[y]` closure. Both normalize to `:closure`. The twenty exclusions now distinguish unbound reads, unknown continuation ids and missing FFI composition.

I checked the reference targets supporting the revised claims: `debruijn_register_compile.cljc:188-197`, its conditional arm at `:211-224`, root allocation at `:380-388`, `parity_test.cljc:39-115`, `corpus.cljc:54-118`, `prelude.cljc:3395-3414`, and `linker.cljc:1363-1420`. No reference mismatch or new private-var access found. No files edited or tests run.

VERDICT: CHANGES_REQUESTED
