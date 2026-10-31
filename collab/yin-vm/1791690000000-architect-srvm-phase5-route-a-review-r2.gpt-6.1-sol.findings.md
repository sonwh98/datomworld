I’ll check the revision against each finding, recount all H targets, and re-derive the new tail-call golden. I’ll also verify whether the descriptor regeneration check uses the existing hash functions.
The corrected H targets and new tail-call golden check out. The SHA-256 descriptor check also matches the existing checksum functions; it introduces no new executable identity hash. One new C4 gate needs correction: `prelude/defined-keys` includes definitions inside function bodies, so it cannot be the expected store-key set after merely loading a module.
The module fixtures do provide top-level placeholders for their nested runtime keys, so the expected key sets can hold for this corpus. The remaining issue is the observable: it lists only store keys while also promising to compare normalized values, and “every definition runs” overstates what loading a module executes.
Completed-GMT: 2026-10-10 18:02:08 GMT
Completed-Local: 2026-10-11 01:02:08 Asia/Bangkok

The golden, allocator, liveness and descriptor corrections pass. Three specification inconsistencies remain, plus one stale count. No files edited or tests run.

| Severity | file:line | evidence | correction |
|---|---|---|---|
| Medium | docs/design/yin.vm.semantic-register-vm.derivations.md:629 | The module observable contains only `defined-keys`, but the following text promises normalization of stored values. Keys alone cannot check those values. “Every definition” also includes definitions inside closures that loading does not execute. | Compare a normalized store map alongside halting/blocking and assert its key set separately. Describe coverage as top-level execution and closure creation; retain phase-8 coverage for invoked module bodies. |
| Medium | docs/design/yin.vm.semantic-register-vm.derivations.md:1308 | “The record’s input is that tree” contradicts §7.6: native derivation records have input A, while the semantic record has input tree. | Qualify this as the **semantic derivation record**; native records refer to A and reach the source tree through the semantic record/association. |
| Medium | docs/design/yin.vm.semantic-register-vm.derivations.md:1092 | “Both storage addresses change” is not guaranteed. Removing a descriptor row changes checksums, but descriptors are outside the stored image payload. Payload addresses change only when payloads change. | Recompute both storage addresses from their final images without requiring inequality to previous addresses. Keep descriptor-dependent checksum regeneration separate. |
| Low | docs/design/yin.vm.semantic-register-vm.derivations.md:533 | Corpus table still says six linked programs; §6.1.4 now specifies ten root programs. | Change the count to ten. |

1. **H goldens — fixed; every pc recounted.** Instruction counts for rows 1–9 are respectively **8, 12, 6, 6, 12, 14, 3/9, 9, 12**. Row 7 contains two programs. The only targets are:
   - Row 4: alternate 4, join 5.
   - Row 5: alternate 6, join 7.
   - Row 6: closure body 6.

   All agree with the specified emitter. No old-byte comparison is required or used.

2. **Actual-successor liveness and new tail golden — fixed.** Independently derived row 9:

   `f→T0, g→T1, x→T2, inner-result→T3, y→T1, outer-result→T2`; peak four registers.

   The inner tail call has no successors, hence `live []`. Its following layout component has `L(4)={1,2}`, which correctly does **not** determine that tail call’s live-out. The listed R image, intervals and H tail flag agree.

   Two further independent derivations agree:
   - Row 5: conditional result reuses T1; `y→T2`, outer result T3.
   - Definition-call row: call result T3; definition result reuses T0 after operand intervals expire.

3. **Lift declarations and regeneration — fixed.** Phase 8 removes both hashed lift rows, preserves version numbers under the owner ruling, and regenerates descriptor hashes and H/R checksums. Descriptor coverage survives deletion of compiler tests. The final format-keyed manifest index is coherent because A is supplied by the semantic record. Correct the storage-address assertion above.

   **Hash ruling:** `jing/sha256(dc/encode-scalar descriptor)` is exactly the existing descriptor-hash computation: stack at `debruijn_code.cljc:542-548`, register at `debruijn_register_code.cljc:257-258`. It introduces no new hash use or executable identity. A continues using Jing’s default address mechanism.

4. **Import/C4 gates — largely fixed.** The ten root programs and pinned outcomes match the import tests. Eight module-root runs are separately identified, and newly published install-child coverage is explicitly gated at phase 8. Correct the module observable and coverage wording above. The module fixtures’ placeholders support their expected definition-key sets; `defined-keys` remains an any-depth syntactic scanner, not an execution oracle.

5. **19+1 execution rows — fixed.** Nineteen throw comparisons and the explicit zero-argument `:op/ping` bridge yielding `[true false :pong]` are consistently specified.

6. **Retrievability/source association — largely fixed.** Set-valued associations, independent vector bytes, explicit carriers, no mandatory trusted-policy fetch, and the host carve-out match the rulings. Fix the derivation-record input qualification. Completeness should reject empty tree sets as well as absent entries, consistently with the non-empty rule already stated.

7. **Allocator loop — fixed.** Expiry and assignment have explicit total ordering; `next` advances only for a never-used slot. Allocation no longer relies on the single-start assertion.

8. **Evidence anchors — fixed.** The register linearizer reads the tail flag at line 116; my earlier 120–121 reference concerned the stack linearizer. The qualified references now distinguish them correctly.

   I checked at least eight revised references: packet declarations at `import_programs.cljc:410, 473, 533, 698, 813, 890, 997, 1045, 1085`; both descriptor-hash definitions; the surviving descriptor test; and the qualified register-linearizer anchors. No new private-var access found.

9. **F12 and F8–F14 — amendments substantially match the rulings.** F12 now contains the complete scanner replacement. F8/F9/F11/F12/F13’s required changes are addressed. F10’s module observable needs the correction above; F14’s migration check must allow recomputed payload addresses to remain unchanged. Settled architecture and owner choices remain intact.

VERDICT: CHANGES_REQUESTED
