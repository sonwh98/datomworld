Created-Local: 2026-10-13 +07
# Report: "native kernels die at phase 8" claims corrected (Architect, docs only)

Worktree `/Users/sto/workspace/datomworld-docs-backends`, branch `docs-backends`. Nothing staged or committed. No code touched.

## Summary (10 lines)

1. Grepped every file under `docs/design/` for phase-8 deletion, "removed with their kernels", "dies at phase 8", "until phase 8", "shelf life", "bounded by phase 8" and kernel/native-lane wording near delete/retire words.
2. Only `yin.vm.semantic-register-vm.continuation.md` claimed the de Bruijn kernels, the native lanes or their refusals are short-lived; every such clause is rewritten and marked "**Amended 2026-10-13 (backends)**" with a one-line reason.
3. The frozen design's §10 phase 8 already deletes only `yin.vm.semantic`, `debruijn-resolve`, `debruijn-linearize`, `debruijn-register-compile`, `:dim/lift-to` and the old request paths; it never deletes the kernels, so it needed no edit.
4. The derivations (§8.3 "old lowerers alive until phase 8"), evaluator, hash-registry, call-site-classification, UCF (§7.2.1, §7.3, §7.4.1) and ucf-transport-tuples hits all refer to the old stack-shaped `yin.vm.semantic` or the old lowerers leaving at phase 8, which stays true; unchanged.
5. New §4.6 "The de Bruijn kernels are backends" added to the continuation design: the model (lowerings A→H and A→R of the semantic-register bytecode, not peers, kept after phase 8, what phase 8 really deletes), the fragment rule (identical on what it accepts, refusal before attach, not widened), the five documented limits with their refusal kinds and sections, the accepted counts, and cross-references.
6. §0.1 gains ruling 8 (the backends ruling, DECIDED); the header gains a Revision 6 sentence.
7. 6c Q1's rationale restated as "the documented supported fragment of two backends" in §5.3, §8.3 item 11, §10.7 R2 and §11; the counts 61/350 (16/92 quick) are unchanged.
8. 6c Q2 closed as REJECTED by the owner in §4.3, §5.3, §8.3 item 11, §8.5, §10.7 D2 and C1, and §11; the "366/96 when it lands" projections are withdrawn, `unlowerable` and 350/92 stand; the seam specification in §4.3 is kept as a record marked not-to-be-built.
9. §9's "limits with their shelf life" bullet and §10.7 V1 restated as the backends' documented fragment; a new §10.7 row B1 inventories this revision.
10. Remaining occurrences of the old phrases in the file are all inside quoted "was …" text in the amendment markers; verified by grep.

## Edits by file and line (post-edit line numbers)

`docs/design/yin.vm.semantic-register-vm.continuation.md` (1 file, +166/−49):

| Line | Clause | Edit |
|---|---|---|
| 63-72 | header | Revision 6 sentence: ruling, markers, Q1/Q2 |
| 123-131 | §0.1 ruling 8 | new ruling: kernels are backends, not deleted at phase 8, documented fragment, refuse before attach, not widened |
| 566-576 | §4.1 R1 paragraph | "Until phase 8 removes those kernels" removed; refusal stated as a kept fragment limit |
| 588-593 | §4.1 R1 *Why* | "offset tables for a phase-8 deletion was not worth a slice" replaced by the fragment/not-widened reason |
| 731-734 | §4.2 virt fallback | "lives only until phase 8" replaced by "stays as such with the register backend's layout" |
| 747-750 | §4.2 D4 primitives | "separate kernel seam" kept; added "none is planned", limit of the register backend's fragment |
| 904-909 | §4.3 D2 heading | seam marked REJECTED by the owner, specification kept as record |
| 917-924 | §4.3 D2 body | 6c-post-D2 past tense; rejection; `unlowerable` and 350/92 stand; "366 and 96" withdrawn |
| 932-937 | §4.3 D2 *Why* | "dies at phase 8 with the kernels" replaced: would widen a backend's fragment, owner rejected |
| 1017-1075 | **new §4.6** | "The de Bruijn kernels are backends": model, fragment, five limits (ambiguous images §4.1; free-environment closures §6.3; register-kernel primitives §4.2; link/install rows §5.3; retained FFI §4.3), counts, cross-references |
| 1255-1263 | §5.3 R2 paragraph | "until the retained-FFI seam lands" and "removed with their kernels at phase 8" replaced |
| 1281-1287 | §5.3 R2 *Why* | "2-3 days … for kernels deleted at phase 8" replaced by outside-the-fragment, counts unchanged |
| 1514-1522 | §6.3 R3 | "Until phase 8" removed; kept fragment limit |
| 2048-2058 | §8.3 item 11 | "366 once 6c-post-D2 lands" and "96 with the seam" withdrawn |
| 2132-2137 | §8.5 | 6c-post-D2 rejected; no kernel edit beyond 6c-pre is named |
| 2201-2209 | §9 limits bullet | "with their shelf life" / "removed with them at phase 8" replaced |
| 2584 | §10.7 R1 | why-column: fragment limit, not an offset-table edit |
| 2589 | §10.7 D2 | rejected; counts stand; why-column corrected |
| 2591 | §10.7 R2 | why-column: outside the fragment, not "kernels deleted at phase 8" |
| 2597 | §10.7 C1 | 6c-post-D2 rejected |
| 2598 | §10.7 V1 | "shelf life" replaced |
| 2599 | §10.7 B1 (new row) | inventory of this revision |
| 2685-2691 | §11 6c Q1 | rationale restated; ACCEPTED and counts unchanged |
| 2692-2701 | §11 6c Q2 | Open → REJECTED by the owner 2026-10-13 |

No other file under `docs/design/` edited.

## Checked and left unchanged (true as written)

- `yin.vm.semantic-register-vm.md` §10 phase 8 (lines 1102-1117): deletes the old evaluator and old lowerers only; §10 phase 6 gate names "the stack and register kernels through their layouts" as engines that must pass.
- `yin.vm.semantic-register-vm.derivations.md` lines 54, 420, 915, 1010-1127 (incl. §8.3 "What keeps the old lowerers alive until phase 8"): about `lower-stack`, `debruijn-resolve`, `yin.vm.linearize`, the `"v3"` scanners.
- `yin.vm.semantic-register-vm.evaluator.md` 35, 1498-1552; `yin.vm.semantic-register-vm.md` 10, 929, 966, 1022, 1050: the old `yin.vm.semantic` / interim format name.
- `yin.vm.universal-continuation-format.md` 190, 390, 651: the stack-shaped machine's handoff/vector/table leaving at phase 8.
- `yin.vm.ucf-transport-tuples.md` 919-920: names the stack and register kernels as repository lanes with no shelf-life claim.
- `dao.jing.hash-registry.md` 357, 455, 466 and `dao.jing.call-site-classification.md` 50: BLAKE3 at the phase-8 cutover for H/R, consistent with the ruling.
- `yin.vm.semantic-register-vm.continuation.md` line 234 (`yin.vm.ucf.handoff` reader leaves at phase 8): the old handoff reader, not a kernel.

## Open questions

1. **Transport-tuples doc, §10 item 6 wording.** It says foreign-engine value profiles are needed for engines "outside the three repository lanes" and that the stack and register kernels need no value profile. Under the backend model that is still true, but it does not say the two backends admit only a documented fragment. I left it; if the owner wants the fragment pointer in that doc too, it is a one-sentence cross-reference to continuation §4.6.
2. **§4.3's rejected seam specification.** I kept the D2 seam text (marked rejected, not to be built) rather than deleting it, because §8.3, §10.7 and §11 cite it by content. Delete it instead if the owner prefers no record of rejected seams in the design.
3. **Memory/handoff text.** The owner's ruling says the old `yin.vm.semantic` *is* deleted at phase 8 and `yin.vm.semantic-register` is renamed; several docs still call `yin.vm.semantic-register` a "working name" (frozen line 10). Consistent with the ruling; nothing to decide, noted for completeness.

READY
