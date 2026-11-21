Created-GMT: 2026-11-13 21:4x GMT
Created-Local: 2026-11-14 08:4x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1e611-f0df-7f62-a22c-ff9034ab408e)

# Task: D1 continuation r12 — complete the declaration, then the bounded corpus verification + the result/companion publication

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 08:4x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` + UNCOMMITTED r1-r11. r11 landed the retained-input fixture gate (integrated and tested: 33/114/0) and the pre-declaration pilot through two retained fixtures (replay, structural checks, both guarded lanes, scoped attribution queries). The larger fixture measured **69,936 ms** including fetch and replay — EXCEEDING the draft's non-binding 60,000 ms candidate (never frozen; the exceedance is recorded honestly as a pre-declaration pilot datum). Your r11 findings' own list: the complete declaration needs the full claim set, phase and baseline measurements, and the remaining enforcement work.

Everything else from the findings chain stands (the corpus 155/155 with retained addresses; the independent verifier; the (A)/(b) rulings; the v1 bytes frozen).

## The work (in order)

1. **Complete the bounds declaration**: the full claim set (every quantity the cc3 v2 format names), the phase and baseline measurements (B/S/T per design §6.2), and the enforcement integration completed (the r11 gate machinery wired to the finalized declaration). The 69,936 ms pilot datum is the measured basis for the per-fixture elapsed ceiling (headroom per the cc3 v2 format; max-based pass/fail; the exceedance RECORDED as the pilot datum that set the ceiling, never hidden).
2. **The bounded corpus verification run UNDER the finalized declaration**: all 155 fixtures through the complete gate (the independent verifier + the enforcement), ceilings enforced live. An exceedance is a failed bounded run recorded honestly — never a raised ceiling. If a fixture exceeds: record it per-fixture with the evidence; do not stop the corpus (the per-fixture independence rule) — the exceedance is that fixture's failed bounded run.
3. **The verification-result publication + the companion**: `slice9_verification.edn` (or sibling) + `slice9_correspondence.edn` — the record's address, the verifier revision, inputs, per-fixture outcomes, the tagged-run evidence. Worktree artifacts; the retention-location OWNER DECISION noted as pending (the 700 MB-class store stays uncommitted).
4. **The executable recipe**: in-turn if it completes; else the exact gap stated for r13.

## Bounds/invariants/deliverable

Same as r11: bounded files; forbidden set unchanged; the v1 bytes frozen; origins only from retained coordinates; the verifier rejects, never substitutes; red-first; kondo 0 errors; the full oracle suite is shiva-scale — do NOT run it. The turn-per-dispatch pattern: deliver the next coherent partial if the turn cannot complete all four (items 1-2 are the natural checkpoint).

Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794735000000-engineer-d1-r12-declaration-completion.gpt-6.1-terra.findings.md` (or worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
