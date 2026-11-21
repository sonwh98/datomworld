Created-GMT: 2026-11-14 00:1x GMT
Created-Local: 2026-11-14 07:1x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1e611-f0df-7f62-a22c-ff9034ab408e)

# Task: D1 continuation r11 — finalize the declaration, integrate the bounded verifier, and execute the tagged run + the artifacts

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 07:1x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` + UNCOMMITTED r1-r10. r10 delivered the trace-memory and comparison pilots (155/155: the largest trace-ring reachable graph 311,144 B; the largest printed record 45 B; the trace-free and historical two-field comparisons each 155 passes) and the step-boundary checks (elapsed, used heap, trace counts, printed bytes; the final failure record preserving an exceeded value and ceiling) — tested (18/64/0), but NOT integrated into a complete bounded verifier. The bounds draft carries the measurements and non-binding candidate ceilings. Everything else from the findings chain stands.

## The work (in order)

1. **Finalize the bounds declaration**: the draft's non-binding candidate ceilings become the enforceable declaration (from the measured maxima with headroom, max-based pass/fail, laptop-scoped per ruling (b)); the trace-memory/byte capacity and the baseline/phase comparisons from the r10 pilot data; every ceiling justified by a measured number.
2. **Integrate the bounded verifier**: wire the step-boundary checks into the independent verifier's attribution query per fixture (the ceiling enforcement live during the run; an exceedance is a failed bounded run recorded honestly — never a raised ceiling; the failure record preserves the exceeded value and ceiling). The complete bounded verifier executes per fixture.
3. **The bounded tagged verification run UNDER the finalized declaration**: the independent verifier over both C lanes for the corpus, ceilings enforced.
4. **The verification-result publication + the companion**: `slice9_verification.edn` (or sibling) + `slice9_correspondence.edn` — the record's address, the verifier revision, inputs, per-fixture outcomes, the tagged-run evidence. Worktree artifacts; the retention-location OWNER DECISION noted as pending.
5. **The executable recipe**: in-turn if it completes; else the exact gap stated for r12.

## Bounds/invariants/deliverable

Same as r10: bounded files; forbidden set unchanged; the v1 bytes frozen; origins only from the r9e retained coordinates; the verifier rejects, never substitutes; red-first; kondo 0 errors; the full oracle suite is shiva-scale — do NOT run it. The turn-per-dispatch pattern: deliver the next coherent partial if the turn cannot complete all five (items 1-3 are the natural checkpoint).

Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794731000000-engineer-d1-r11-declaration-verifier-integration.gpt-6.1-terra.findings.md` (or worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
