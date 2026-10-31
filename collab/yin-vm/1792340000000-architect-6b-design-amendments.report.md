Created-Local: 2026-10-13 +07
# Report: continuation design amended after slice 6b (Architect, docs only)

Architect: claude-fable-5-1. Worktree `/Users/sto/workspace/datomworld-docs-6b-amend`, branch `docs-6b-amend`
(from master `1db08f7d`). Nothing staged or committed. Files edited: `docs/design/yin.vm.semantic-register-vm.continuation.md`
(revision 4), `docs/design/yin.vm.ucf-transport-tuples.md` (three "Amended after 6b" notes), this report. No code touched.

## Summary

1. The continuation design now states what the merged `rows.cljc` does for lift, lower, discharge and install children.
2. Every amended clause is marked "**Amended after 6b (2026-10-13)**" with what changed and why; §10.6 inventories them (B1-B4, D1-D10).
3. B1: rows 10-12 rewritten to the engine's real states; row 10 is child `:blocked` at phase `:parked`, row 11 is the `:running` wire phase set by hand.
4. B1: the lower's install contract is stated: phase in `#{:running :parked}`, response status/manifest/exports/image, child must be `:blocked`.
5. B2: the wrapper rule has one stated exception: row 21 under `:depth-3` answers `[3 [2 [1 1]]]` because `nest`'s parameter `x` captures.
6. B3: the `:tail` fourth-slot refusal is `:row-shape` at structure step 2; emit drops extra abstract slots, so the tamper is a raw row edit with rehash.
7. B4: §7.1 lists the lift's real order; the dependency closure runs after the walk and before frame validation and emit.
8. §7.2 gives the lower's four stages (stores, pendings and install records, dependencies, custody), each across the whole tree, all before any attach, with a refusal-kind table.
9. Per-context discharge over live code (`live-pcs`, both branch edges, reachable closure bodies) and its answering order are documented; backward-jump reachability is tested directly because the parser admits none.
10. `:source-index` as a lift input, thrown refusals, custody nil at the root, registry entries not travelling, the whole store travelling, and the retention seam having landed are all recorded.
11. The `handoff.cljc` claim is recorded as **withdrawn**: the code is `(symbol (str (name m) "/" (name x)))`, which one-argument `symbol` splits into a qualified symbol on all three hosts; codex verified on the JVM. No code edit.
12. §11 now lists ten open questions the owner has not ruled on (install phase narrowing, census agreement, install verification depth, op-ids, re-discharge on lower, live-code conservatism, shadowed host primitives, preflight attach cost, Law 2 outside a fresh receiver, row 11's future).
13. The tuples draft is amended only where it stated the same things: §5.4 install verification, §8 steps 8-10, §9 lift order.
14. §9 honesty note updated: 4b stage 2 landed, 89 labelled runs (85 distinct setups), JVM only; the three-host gate is still open.
15. Q6 was checked against the harness: the dead-constructor case transports because the resume pc is past the branch, not by constant folding.

## Edits by section

**`yin.vm.semantic-register-vm.continuation.md`**

- Status header: revision 4 paragraph naming the merged commits, the sources and the marker convention.
- §2.5: new block "what travels and what does not": whole root store and every parked record; module stores by reachability; linked registry entries do not travel (composition prerequisite, receiver registry kept as given); custody nil at the root.
- §2.6: custody table note: only fork rows reachable; nil written, non-nil refused `:yin.k/unsupported :custody`.
- §5.2 wrapper paragraph: B2 exception for row 21 under `:depth-3`, with the harness branch cited.
- §5.2 rows 10, 11: rewritten (10: `:blocked`/`:parked`, executable; 11: `:running` wire phase, synthetic phase on row 10's machine).
- §5.2 rows 17, 18: row 17 names the registry prerequisite; row 18 labelled as a symbol-keyed hand-built store.
- §5.2 new paragraph before the refusal rows: B1 engine analysis (`advance-install` arms), the three install refusal kinds on lower, the two on lift.
- §5.2 refusal rows: `:frame-shape` replaced by `:row-shape` with the emit-drops-extra-slots note (B3).
- §6.1: `:source-index` as a lift input, absent-or-empty refusal on lift and lower, no persisted slot.
- §6.3: the retention seam landed; default read of the kernel field, explicit override, E2 catches a disagreeing override, children drop the option.
- §7.1: ten-step lift order as the code runs it (B4), the per-context live-code discharge with answering order and refusal, trees, frames before emit; thrown refusals.
- §7.2: four-stage table with checks and refusal kinds; attachment and restore described; no re-discharge on lower; the kinds named by the engineer listed.
- §8.2: depends-on mentions the landed seam and `:source-index`; acceptance 3 restated for the amended rows; acceptance 7 notes `:row-shape` and the attach-count law.
- §9: 4b stage 2 landed; run counts; three-host gate open.
- §10.6: new inventory table B1-B4, D1-D10; the `handoff.cljc` note.
- §11: ten open questions Q1-Q10.

**`yin.vm.ucf-transport-tuples.md`**

- §5.4 install paragraph: what `check-install!` verifies; link-id and image-address checks marked not implemented (points to Q3).
- §8 after step 10: the four lower stages and their kinds; what of steps 8-9 is not implemented (points to Q2, Q4).
- §9 lift: the merged order, whole-store slice, custody nil.

## Open questions (for the owner; not decided here; full text in continuation §11)

- Q1 Narrow the install phase set and child kind to the one reachable state, or change the engine to make a parked install child reachable and rewrite row 11?
- Q2 Lower checks `requires` segments = `code` keys only, not a census recomputed from the rows (draft §8 step 9). Accept or require?
- Q3 Install verification omits link-id = parent and image address = manifest (draft §5.4). Accept for phase 6 or extend before phase 7?
- Q4 Op-id rules and correlation agreement of draft §8 step 8 are not implemented; phase 7's with the custody arm, or a gap now?
- Q5 Lower does not re-discharge registry-answered names; a receiver lacking the registry entry fails at run time. Accept as composition prerequisite?
- Q6 `live-pcs` follows both branch edges with no constant propagation; constant-dead arms refuse when lifted before the branch. Intended precision?
- Q7 A host-class primitive shadowed by `E` or the active store lifts. Intended?
- Q8 Preflight `attaches?` attaches every vector into a throwaway receiver and restore attaches again. Accept or add a validate-only kernel entry?
- Q9 Law 2 holds only for a fresh receiver; no non-fresh case exists. Document as a non-law or add a case?
- Q10 If Q1 narrows, row 11 leaves the matrix; otherwise it stays synthetic. Which?

Also for the owner's awareness, not a question: the `handoff.cljc` symbol claim is withdrawn (verified by reading `handoff.cljc:1480-1488`; one-argument `symbol` splits at `/`).

READY for codex's doc review.
