Created-Local: 2026-10-13 +07
# The 6c doc pass (Architect, claude-fable-5-1, docs only)

Worktree `/Users/sto/workspace/datomworld-docs-6c`, branch `docs-6c-amend`. Edited: `docs/design/yin.vm.semantic-register-vm.continuation.md` only (521 lines changed: 512 added, 9 removed, the partial edit included). `docs/design/yin.vm.ucf-transport-tuples.md` is untouched: its only overlap (§10 answer 6) points at continuation §4 for the layouts and states none of the clauses 6c changes. No git add, no commit.

## Summary (12 lines)

1. The partial edit found in the worktree (revision-5 header, §4.1 R1 wording and API-as-landed, §4.2 D3 and D4) was correct against the merged code and is kept; only the header's "not yet merged" became the merge `781a9ba8`.
2. Every ruling text is applied verbatim by section: R1 §4.1; R2 §5.3 paragraph and §8.3 items 10-11 (61 cases, 350 transfers, 366/96 with the seam); R2's seam paragraph in §4.3 as 6c-post-D2, not a prerequisite; R3 §6.3 including nested values; R4 (D1) §5.5; R5 (D3) §4.2 and §8.3 item 12 permitting `:yin.k/non-portable :register-layout`; R6 D2, D4 (the "is no carrier value" phrase of §8.3 item 6 replaced, the §4.2/§7.2 text added), D5 §5.2 row 21, F2 identity amendment with R1's wording.
3. Each amended clause carries "**Amended after 6c (2026-10-13)**" with what changed and why, and §10.7 inventories fifteen of them in the §10.6 table style.
4. Each clause states what the merged `layout.cljc` and the 6c tests do, read from `origin/master`: `lower-defect`'s clause order (collision, installs, free-env procedure, register primitives), that it runs after `rows/decode` and before `rows/lower-task` and any attach, and the `canonical-task` / scratch-machine / `kernel-task` composition.
5. The refusal kinds and statuses stated are the code's: `:yin.k/unsupported :ambiguous-image` (lower, with image and segments), `:yin.k/non-portable :ambiguous-image` (lift, before serving), `:yin.k/unsupported :free-env-value` with a path and `:yin.k/non-portable :free-env-value` with a key, `:yin.k/unsupported :continuation-registers` with a path, `:yin.k/non-portable :register-layout` with pc and slot.
6. §5.3 also states the harness mechanics: `*lane*`, `kernel-load`, `lane-lift`/`lane-lower`, `link-rows`, `unlowerable`, and the `*projected*`/`scoped`/`file-wide` scoping of `kernel-space`.
7. §5.5 states `logical-positions`, `injective`, `logical-descriptors`, the three-lane assertions and the five controls of `harness/law-4-controls` as the code has them (the descriptor control is a stream only the register lane holds, not codex's `:review/extra` field; stated as the code does it).
8. §7.1 and §7.2 gain the lift and lower as `layout/lift-task` and `layout/lower-task` run them, with the store-write audit allowlist entries and their reason.
9. §8.3 gains items 14-17 (alpha-equivalent segments, free-env values and nested values, law-4 controls, audit and F6) and the quick/slow tier split of items 10-11 (16/92 bare).
10. §11 gains 6c Q1 "ACCEPTED by the owner on 2026-10-13" (61 law-4 cases and 350 law-5 transfers normative), 6c Q2 (the retained-FFI seam) open, 6c Q3 (free-env closures) open; numbered "6c Q1-Q3" so the 6b Q1-Q10 stand.
11. §8.5 and §9 are amended for honesty: 6c edited no kernel; counts are the tests' assertions; the three-host run is not recorded in any source read.
12. Vocabulary per UCF 7.5.3 (`:yin.k/status`, `:yin.k/kind`, `:yin.k/path`, `:yin.k/key`, `:yin.k/image`, `:yin.k/segments`).

## Edits by section

- **Header (revision 5):** merge sha `781a9ba8`; sections listed as §§4.1-4.3, 5.2, 5.3, 5.5, 5.6, 6.3, 7, 8.3, 8.5, 9; the test files named.
- **§4.1:** kept from the partial edit: R1 wording verbatim, what `space`/`image-segment`/`image-collision` do, the API as landed (deviation 1).
- **§4.2:** kept from the partial edit: D3 verbatim with `virt`'s behaviour; D4 verbatim with `state-prim`'s paths and the stack lane holding the same carriers.
- **§4.3:** new: 6c-pre landed (`296f75fd`) and the tail shapes `lower-state` emits for both kernels; the D2 seam text verbatim with R2's last sentence, what both kernels do today, `unlowerable`.
- **§5.2:** new: D5 verbatim, pinned by `env-reconstruction`'s row-21 clause and its 128 comparisons.
- **§5.3:** new: R2 paragraph verbatim, marked ACCEPTED by the owner (6c Q1); the harness lanes and scoping; the `lift-act`/`lift-tail` sentence superseded.
- **§5.5:** new: D1 verbatim, with what `canonical-under` erases, the harness's renaming and the five controls.
- **§5.6 item 8:** rows 6 and 20 on the native lanes; 9 and 12 reference-lane only.
- **§6.3:** new: R3 wording verbatim, `free-env-procedure` and `holds-procedure?`, the two tests, why refuse not convert.
- **§7.1:** new: the lift as `rows/lift-task` over `canonical-task`, its refusals in order, the layout refusals raised before serving.
- **§7.2:** new: decode, `lower-defect` (clauses in order), `rows/lower-task` into scratch, `kernel-task`; D4 sentence; the audit allowlist reason.
- **§8.3:** item 6 phrase replaced; items 10-12 replaced per R2/R5 with the tier split and the four `:continuation-registers` fixtures; items 14-17 added.
- **§8.5:** 6c edited no kernel; 6c-post-D2 is its own slice.
- **§9:** 6c-pre landed; counts as asserted; no three-host record; the limits and their shelf life.
- **§10.7:** the inventory table (R1, A1, R5, D4, S1, D2, D5, R2, D1, R3, L1, L2, T1, C1, V1).
- **§11:** 6c Q1 accepted, 6c Q2 open, 6c Q3 open.

## What I could not state truthfully, and why

- **A three-host run of 6c.** The engineer report and the codex review say Node and Dart were not run; the rulings list it as acceptance item 9 for the fix round; the merge commit and the two fix commits say nothing about hosts, and the fix-round reports are not in this worktree's `collab/` (listing the main checkout's `collab/` needed an approval this session could not get). §9 therefore says the sources read contain no three-host result and the document does not claim one. If the orchestrator has the fix-round report with three green lanes, §9 and §8.3 item 13 can cite it in one line.
- **Codex's exact descriptor control.** R4 asked for a descriptor given `:review/extra :different` on one lane; the code's control is a stream only the register lane holds, failing `:descriptors`. §5.5 states the code's control, not codex's.
- **Line citations inside the kernels** (`register.cljc:508-514`, `stack.cljc:564-570`, `semantic_register.cljc:105-111`, `rows.cljc:971-974`) are taken from the rulings report, which read those files; this pass read `layout.cljc` and the tests, not the kernels.
- Three of the fix-round fixtures count four `:continuation-registers` refusals where codex asked for three; the doc states four, as the test has.

READY
