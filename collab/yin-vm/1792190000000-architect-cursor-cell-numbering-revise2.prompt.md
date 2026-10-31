Created-GMT: 2026-10-12 00:00:00 GMT
Created-Local: 2026-10-12 07:00:00 Asia/Bangkok

# Task: revise the discovery-order numbering note, round 3 (fresh Architect session)

You are claude-fable-5-1, a NEW session. Worktree `/Users/sto/workspace/datomworld-srvm-numbering`, branch `numbering-note`. Edit ONLY `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`, the prototype under `collab/numbering-probe`, and your report `collab/1792190000000-architect-cursor-cell-numbering-revise2.report.md`. No git add/commit. Use the vocabulary of UCF section 7.5.3 exactly (cursor, cursor cell, cursor-ref, cell).

Owner decision 2026-10-12 (final): ADOPT discovery-order numbering instead of phase 6a's refinement+search numbering, because it is simpler and easier to explain and matches the de Bruijn style already used in the register and stack VMs. Your job is to make the note correct enough to replace 6a's numbering.

Read first: the note; `collab/1792170000000-architect-cursor-cell-numbering-review-r2.gpt-6.1-sol.findings.md` (3 High, 2 Medium, with exact reproductions); your previous report `collab/1792150000000-architect-cursor-cell-numbering-revise.report.md`; 6a's numbering in `src/cljc/yin/vm/ucf/rows.cljc` (branch srvm-phase6a) for what must be replaced.

Fix, in order:
1. HIGH D5 semantics: key-building local runs swallow conflicts (witness `[#{#{[A B] [B A]}} A]` numbers and decodes). State precisely which local conflicts must refuse the lift (speculative key runs versus runs whose completeness is required), fix the spec, F4, lemma D, amendments, prototype.
2. HIGH install child refusal: a D5 inside an install child escapes decode as `:yin.k/non-portable` path `[:child 15]`. Wrap the whole discovery recomputation including child emission in the narrow translation to `:yin.k/undecodable`, prefix the receiver path with the carried root id.
3. HIGH work bound: P counts positions but not bytes (stream descriptor 128 to 65536 chars grew key bytes 3509 to 592208); memoization ignores counter dependency and differing simulated numbering contexts. Include expanded byte volume in the bound, bound distinct memo contexts, or drop the polynomial claim and use an explicit budget with `:resource-limit` refusal like 6a's `:label-bytes`.
4. MEDIUM A2 contradicts D4 on host enumeration order; reword. MEDIUM size and review-cost estimates: replace with an implementation-based estimate (codex: 100-195 net lines, not 230).
5. IMPORTANT coverage regression: codex says discovery order refuses simple 2-cycles and 3-cycles that 6a numbers. State exactly which structures that is and whether realistic suspended tasks hit them; if they are common, say so and propose the repair (or the minimum extra rule) rather than leaving a regression.
6. Add a section "Replacing 6a" listing exactly what in rows.cljc and rows_test.cljc is deleted, rewritten and kept, so an implementation brief can be cut from it. Keep every counterexample as a fixture.

Rerun the random renaming and permutation search including nested sets, `#{[X X] [Y Z]}`, tied elements, parked states, K frames, install children; report counts. Be honest where a case stays refused. Foreground only: run tests and searches in the foreground; do not background anything. End the report with a clear READY or NOT READY for implementation and the remaining risks.
