Created-GMT: 2026-11-13 22:1x GMT
Created-Local: 2026-11-14 05:1x +0700
Coding-Agent: codex
Session-ID: (fresh thread on the laptop; the by-state brief carries the context)

# Task: D1 continuation r16 — wire the dependency closure into the recipe; the publishable side records; the image-derivation checks; the two-lane trace completeness

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 05:1x +0700 | Status: active | Rationale: continuity

## State resume (by state — everything is in the worktree)

Worktree: `/Users/sto/workspace/datomworld-d1` (branch d1-correspondence @ `e4ac2114` + the UNCOMMITTED r9b/r9c/r14/r15 work). Read first: the findings chain r1-r15 (`collab/yang-clojure/1794422563000-engineer-d1-correspondence-r{1..8}...findings.md`, the r9b continuation + continuation-4 findings, the r14 findings `1794790000000-engineer-d1-r14-companion-subset-retention.gpt-6.1-terra.findings.md`, the r15 findings `1794795000000-engineer-d1-r15-upstream-construction.gpt-6.1-terra.findings.md`), the ORIGINAL dispatch brief (`1794422563000-engineer-d1-durable-correspondence.prompt.md` + the POST-CC3/PORT/REFACTOR amendment), the two astra rulings (`1794524600000-...findings.md` RULING (A); `1794422563000-architect-d1-profile-amendment-r2...findings.md` RULING (b)), the bounds-declaration DRAFT (`1794671000001-d1-bounds-declaration-draft.md`), and the cc3 v2-declaration format precedent (`1794390804000-cc3-bounded-run-declaration-v2.md`).

r15 landed `correspondence_dependency.cljc` (byte-preserving, content-addressed dependency bundles; 9/76/0) — the honest limit: it stores/retrieves bundles but does NOT establish the compiler dependency closure. The retained corpus (`target/d1-r9e-retained-store`, intact) holds the closure CONTENT.

## The work (in order; deliver the next coherent partial if the turn cannot finish all)

1. **Wire the dependency closure into the recipe**: from the retained corpus (`target/d1-r9e-retained-store`), extract the actual compiler dependency closure (the compiler/frontend revision, the grammar/options, the expansion environment, the recorded origins/attempts, the rebind mechanics) and store it through the r15 bundle store. The recipe record references the closure by address.
2. **The publishable provenance side records**: the C1 metadata + instruction-provenance side records in their publishable form (cc3's landed publish machinery), covering the corpus fixtures — the form a future verifier fetches.
3. **The image-derivation checks**: semantic, stack, register — each under its own execution contract and verifying derivation chain (`yin.vm.linker/verify` per the r2 inventory), run per fixture, evidence recorded.
4. **The two-lane trace completeness**: the lane runner retains its instruction count (r9c's finding: it discards it — fix), and the complete two-lane trace evidence is produced and retained per fixture.

If the turn cannot complete all four: deliver the next coherent partial (items 1-2 the natural checkpoint) with the same honesty standard.

## Bounds/invariants/deliverable

Bounded files: the correspondence machinery + the new recipe/profile/side-record files + tests + the jolt lane (only if the J2 manifest needs the update). Forbidden set unchanged (macro.cljc, semantic.cljc, ast_walker.cljc, yang/clojure.cljc, core/prelude.cljc, dht.cljc). The v1 bytes frozen. Origins only from retained coordinates. kondo 0 errors. The full oracle suite is shiva-scale — do NOT run it.

Deliverable: `collab/yang-clojure/1794800000000-engineer-d1-r16-recipe-closure.gpt-6.1-terra.findings.md` (worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
