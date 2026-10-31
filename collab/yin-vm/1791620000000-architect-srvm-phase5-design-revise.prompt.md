Created-GMT: 2026-10-10 16:00:00 GMT
Created-Local: 2026-10-10 23:00:00 Asia/Bangkok

# Task: revise the phase-5 derivations design after codex review (continuing your session)

Same worktree `/Users/sto/workspace/datomworld-srvm-p5-design`, branch `srvm-phase5-design`.
Edit ONLY `docs/design/yin.vm.semantic-register-vm.derivations.md` (and your report). Do not edit the frozen design; amendments F8-F12 stay as proposed text inside the derivations doc. No git add/commit.

Codex (gpt-6.1-sol) returned CHANGES_REQUESTED. Read its full findings:
`collab/1791610000000-architect-srvm-phase5-design-review.gpt-6.1-sol.findings.md`.
Verified by it: F8 allocations match yours; the adapter route is architecturally sound; F12 ACCEPT; F8-F11 ACCEPT WITH CHANGES.

Fix, in order:
1. Address oracle (line ~677): mirror definition lowering, visiting only the value operand of a definition (no `[:free yin/def]`); do not reuse `resolved-addresses-of` unchanged.
2. Scanner agreement oracle (~811): specify an independent tree oracle normalized to the vector emission order and site semantics (exclude definition applications, include FFI sites), or separate unordered occurrence agreement from sequence agreement.
3. Join agreement (~816): compare success/refusal, normalized defect kind and name, and retained records; add a use-before-definition case; apply to C4 as promised.
4. C4 declarations (~831): compare the discharged external-name set (module-defined names removed, requirement coverage applied) with declarations; keep raw scanner agreement separate; C4 gate stays read-only.
5. Worked-example golden (~1069): inner call is `[:call 2 3 [4] false [1]]`; outer live stays `[]`.
6. Fixture facts (~1119): per-fixture exact facts; `:define-then-call` free occurrences are main `inc`, main `n`, then body `list`; `a`/`b` are bound.
7. F9 text (~1244): body discovery order agrees; each emitter computes its own pcs; remove the redundant second parse from frozen §8.3 and update §8.4's obsolete composition formula.
8. Brief gaps (~783): pin imports and public access (no `project-segment-qualified` from old `yin.vm.code`; no private vars; use `linker/verify` for join outcomes or specify a justified public seam); define the segment-row projection with no dependency on the retired stack table.
9. Runtime gate (~1062): specify runtime corpus membership, composition options and a common closure-result normalization; keep byte comparison over the full corpus.
10. Editorial: stack corpus has 15 entries; narrow §3.2's allocation claims to demonstrated cases (store-put and gensym are excluded from the agreeing atomic cases).
11. §4.4: replace the generic "else -> re-version" with: a discrepancy blocks the slice and triggers investigation; re-versioning needs an explicit changed-contract decision, recorded separately for 5a and 5b.
12. Resolve-on-vector: compare closure side-table params per closure pc excluding provenance; require `:source` to equal the complete record set including synthesized records.
13. F10: phase 5 leaves lifts and descriptors unchanged; phase 8 needs an explicit lift-contract decision. Record codex's rulings on your delegated decisions (pc-derived ids, corpus, blocking gate, derived footprint map with drift test, byte-neutral 5c, named-datom resolver kept through phase 7, obligations-fixture follow-up).
14. Owner questions: keep three, reworded per codex (route-(b) tradeoff: no simpler native allocator from the register shape; phase-8 lift contract; v4 profile string, reuse `"ast-to-bytecode"` with explicit `"v4"` acceptable).

Report to `collab/1791620000000-architect-srvm-phase5-design-revise.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then: per-item what changed (section), anything you disagree with and why, anything unverified.
