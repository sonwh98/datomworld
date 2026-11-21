Created-GMT: 2026-11-13 22:1x GMT
Created-Local: 2026-11-14 05:1x +0700
Coding-Agent: codex
Session-ID: (fresh thread on the laptop; the by-state brief carries the context)

# Task: D1 continuation r18 — complete the reconstruction package (the ruling's steps 1-3)

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 05:1x +0700 | Status: active | Rationale: continuity

## State resume (by state — everything is in the worktree)

Worktree: `/Users/sto/workspace/datomworld-d1`, branch d1-correspondence @ `e4ac2114` + the UNCOMMITTED r9b-r17 work (4 files: correspondence_text.clj documentation fix, latte_oracle_test.clj capture expansion, correspondence_verification.clj, correspondence_verifier.cljc, clojure_callsite_test.cljc). Read first: the findings chain r1-r17 (`collab/yang-clojure/1794422563000-engineer-d1-correspondence-r{1..8}...findings.md`, the r9b continuation + continuation-4 findings, the r14 findings, the r15 findings, the r17 findings `1794810000000-engineer-d1-r17-reconstruction-package.gpt-6.1-terra.findings.md` — the worktree mirror), the THIRD astra ruling (`1794805000000-architect-d1-executable-closure-adjudication.gpt-6-astra.findings.md` — RULING (other): the reconstruction-package contract, the §4.1 replacement text, the implementation sequence, the five defects), the TWO earlier rulings that bind the design (`1794524600000-...findings.md` RULING (A); `1794422563000-architect-d1-profile-amendment-r2...findings.md` RULING (b)), the bounds-declaration DRAFT (`1794671000001-d1-bounds-declaration-draft.md`), and the cc3 v2-declaration format precedent (`1794390804000-cc3-bounded-run-declaration-v2.md`).

The reconstruction package construction (the ruling's steps 1-3) is IN PROGRESS in the uncommitted work: the capture boundary implemented (forms/call before hashing, program/options before compile), the text profile implemented, the admission-time fidelity + refusal gates implemented, the live-capture pilot run. The remaining steps 1-3: (1) the resolved classpath and required sources/resources inventory → their bytes retained; (2) the runtime distribution retained or its addressed manifest; (3) the launch configuration (entrypoint, classpath order, JVM options, working-directory mapping, properties, environment, locale/encoding, initialization rules) — the execution manifest per the ruling's component table.

## The work (in order; deliver the next coherent partial if the turn cannot finish all)

1. **The execution manifest**: build it per the ruling's component table — sources and resources (the exact files + transitive dependencies + uncommitted/generated files the corpus compilation actually needs), binary dependencies (the resolved jars with content identities, classpath order), runtime (the addressed JDK distribution or its file manifest), build/load plan, launch configuration, pipeline and contracts, expansion context (the substantiated bypass or the captured events).
2. **The reconstruction entrypoint**: fetch and verify the package, start a fresh process, reconstruct assembly and compilation, compare all claimed outputs — resolving compiler code from the PACKAGE, not the invoking checkout.
3. **The expansion-applicability resolution**: record the substantiated bypass for the direct pipeline (per ruling §4: the direct compile/stamp path's inapplicability is investigated and documented), or capture the actual expander context.
4. **The corpus evaluation under the new package**: record it as later reconstruction; reuse retained admission coordinates only where the required facts exist.

If the turn cannot complete all four: deliver the next coherent partial (items 1-2 the natural checkpoint) with the same honesty standard.

## Bounds/invariants/deliverable

Bounded files: the correspondence machinery + the NEW manifest/package/entrypoint files + tests + latte_oracle_test.clj. Forbidden set unchanged (macro.cljc, semantic.cljc, ast_walker.cljc, yang/clojure.cljc, core/prelude.cljc, dht.cljc). The v1 bytes frozen. Origins only from retained coordinates. kondo 0 errors. The full oracle suite is shiva-scale — do NOT run it.

Deliverable: `collab/yang-clojure/1794815000000-engineer-d1-r18-package-continuation.gpt-6.1-terra.findings.md` (worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
