Created-GMT: 2026-11-13 21:3x GMT
Created-Local: 2026-11-14 04:3x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1e611-f0df-7f62-a22c-ff9034ab408e)

# Task: D1 r9g — the DEFINITIVE sequencing resolution, then the C-lane pilot + the declaration + the bounded tagged run

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 04:3x +0700 | Status: active | Rationale: continuity

## THE SEQUENCING RESOLUTION (definitive — this supersedes the ambiguity; no further asking on this point)

The two rules govern DIFFERENT runs, and both are satisfied by this exact sequence:

1. **The C-lane pilot measurements run FIRST, before the declaration.** They are PILOT runs (design §6.2's T-configuration measurements), explicitly labeled `:pre-declaration true` in the outcomes, and they are NOT verification evidence. Their purpose is to SET the ceilings: without measured C-lane quantities, the declaration's C-lane ceilings would be invented. This is the same structure the cc3 v2 declaration used (pilot measurements → ceilings declared from them).
2. **The bounds declaration is finalized FROM the pilot data** — finite ceilings with headroom, max-based pass/fail, declared before anything counts as evidence.
3. **The bounded tagged verification run executes UNDER the declared ceilings** — THIS is the run §6.3's "bounds precede any tagged run" rule governs. Its results are the verification evidence. A ceiling exceedance here is a failed bounded run.

So: pilot (unbounded, labeled) → declare → bounded verification run (governed). The design §6.3 sentence "before any tagged verification run" applies to step 3; step 1 is the measurement phase §6.3 itself requires to set the ceilings ("The implementation preflight record must contain actual finite numbers... derive from the pinned corpus"). The one pre-declaration pilot already recorded (r9f's `:beta-eq-false` both-lanes run) is exactly this and stays labeled as such.

**Do not ask this question again — this resolution is final for the round.**

## The work (the complete remaining sequence, in order)

1. **The C-lane pilot measurements for the remaining 154 fixtures** (the `:beta-eq-false` pilot exists): per fixture, both C lanes, the quantities recorded (guest instructions per lane, trace records/bytes, per-phase elapsed), labeled `:pre-declaration true`. Batched; the outcomes/measurements file is the progress record.
2. **The bounds declaration finalized FROM the pilot data** (the draft `1794671000001-d1-bounds-declaration-draft.md` + the pilot measurements): finite ceilings with headroom, max-based pass/fail, laptop-scoped per ruling (b)'s scoping clause.
3. **The bounded tagged verification run UNDER the declaration**: the independent verifier (`yang.correspondence-verifier`) executes the scoped attribution query over both C lanes for the corpus, ceilings enforced. An exceedance is a failed bounded run recorded honestly.
4. **The verification-result publication + the companion**: `slice9_verification.edn` (or sibling) + `slice9_correspondence.edn`; the second row-closure traversal (155/155 already PASSED — keep that record); the retention-location OWNER DECISION noted as pending (the store stays uncommitted scratch).

## Bounds/invariants/deliverable

Same as r9f: bounded files; forbidden set unchanged; the v1 bytes frozen; origins only from the r9e retained coordinates; the verifier rejects, never substitutes; red-first; kondo 0 errors; the full oracle suite is shiva-scale — do NOT run it. If the turn cannot complete all four: deliver the next coherent partial (items 1-2 are the natural checkpoint) with the same honesty standard.

Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794728000000-engineer-d1-r9g-sequencing-resolution.gpt-6.1-terra.findings.md` (or worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
