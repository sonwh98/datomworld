Created-GMT: 2026-11-13 22:0x GMT
Created-Local: 2026-11-14 05:0x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1e611-f0df-7f62-a22c-ff9034ab408e)

# Task: D1 continuation r10 — complete the bounds declaration and execute the bounded tagged verification run + the result and companion publication

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 05:0x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` + UNCOMMITTED r1-r9g. r9g completed the C-LANE PILOT: all 155 fixtures measured on both lanes, no trace loss, every outcome labeled `:pre-declaration true`; the observed maxima (2,544,482 walker steps; 1,398,649 semantic steps; 2,345 trace records / 67,500 printed-EDN bytes per lane) and the static profile (occurrence counts, path sizes, serialized sizes for all 155) are recorded in the bounds draft (`1794671000001-d1-bounds-declaration-draft.md`) and the pilot/static-profile artifacts (`1794422563000-engineer-d1-r9f-measurements.edn`, `1794728000000-engineer-d1-r9g-static-profile.edn`). The sequencing resolution (r9g's brief) is final: the pilot set the ceilings; the bounded tagged verification run under the declaration is what counts.

Findings chain r1-r9g complete; the two astra rulings bind the design; the cc3 v2-declaration format governs. Your r9g findings' own open list: trace-buffer memory/byte capacity, the required baseline and phase comparisons, enforceable time and memory ceilings, the executable recipe, the independent derivation/provenance checks, the bounded verification run, the D1 companion, the verification result.

## The work (in order)

1. **Complete the bounds declaration**: from the measured maxima + the static profile — the trace-buffer memory and byte capacity, the enforceable time and memory ceilings, the baseline and phase comparisons (B/S/T per design §6.2), all finite with headroom, max-based pass/fail, laptop-scoped per ruling (b). Every ceiling justified by a measured number; no invented values.
2. **The bounded tagged verification run UNDER the declaration**: the independent verifier (`yang.correspondence-verifier`) executes the scoped attribution query over both C lanes for the corpus, ceilings enforced (max-based; an exceedance is a failed bounded run recorded honestly — never a raised ceiling; the run PRESERVES the exceedance evidence).
3. **The verification-result publication + the companion**: `slice9_verification.edn` (or the design-named sibling) + `slice9_correspondence.edn` — the record's address, the verifier revision, inputs, per-fixture outcomes, the tagged-run evidence. Worktree artifacts; the retention-location OWNER DECISION pending (the store stays uncommitted; note it).
4. **The executable recipe** (your r9g open item): if it completes within the turn, include it; if not, record it as the r11 item with the exact gap stated.

## Bounds/invariants/deliverable

Same as r9g: bounded files; forbidden set unchanged; the v1 bytes frozen; origins only from the r9e retained coordinates; the verifier rejects, never substitutes; red-first; kondo 0 errors; the full oracle suite is shiva-scale — do NOT run it. The turn-per-dispatch pattern: deliver the next coherent partial if the turn cannot complete all four (the declaration + the tagged run is the natural checkpoint).

Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794729000000-engineer-d1-r10-declaration-tagged-run.gpt-6.1-terra.findings.md` (or worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
