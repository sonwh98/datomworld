Created-GMT: 2026-11-13 18:5x GMT
Created-Local: 2026-11-14 01:5x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5bd-11ef-7513-9672-97c79d26f3bc)

# Task: D1 continuation r8 — the corpus run: all 155 fixtures through the per-fixture gates

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 01:5x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` @ `56cc5ca3` + UNCOMMITTED r1-r7. r7 landed `check-fixture!` (the per-fixture static gate: independent source-hash + site declarations + binding reconstruction + C1 marker coverage; refusals for missing/extra/mutated/malformed/duplicate), `fetch-tree!` (addressed row closure with limits), the 128-nesting honest workaround (per-row materialization), and per-fixture outcomes for 2/155 (`:beta-eq-false`, `:minimal-last` — pass + mutation-refused). The outcomes enumeration is `1794422563000-engineer-d1-correspondence-r7.outcomes.edn`. Retention location PROPOSED (`test/yang/latte_oracle/slice9_correspondence_store/content.jing`, scratch-copy-before-open) — needs size measurement + owner agreement. 56/694/0 standing battery; kondo 0/0.

## The items

1. **The corpus run**: extend the per-fixture gate to ALL 155 fixtures — each under a fresh recorded admission, the full static gate (independent hash + declarations + bindings + C1 coverage), the mutation-refusal spot check per the r7 pattern (at least one per distinct refusal class, not necessarily all 155), outcomes recorded per-fixture in the outcomes file (`:pass`/`:refused <stage>`/`:not-run` — honest enumeration, `:not-run` is never a pass). This is mechanical but long: run it in BATCHES and record progress so an interruption loses nothing (the outcomes file IS the progress record). If specific fixtures fail their gates, record the failures per-fixture with evidence — they may be real defects in the port's compiled shapes or in the gate; attribute, don't paper over.
2. **The retention-location measurement**: measure the proposed `slice9_correspondence_store` size for the full corpus (evidence + recipes + records + row closures) and record the number in findings. The retention/distribution owner agreement stays an OWNER decision — present the number and the proposal, do not implement the committed artifact this round.
3. **Bounds-declaration preparation** (not the tagged run): draft the D1 bounds declaration per the cc3 v2 format from the measured corpus numbers (the per-fixture gates are STATIC — no C lanes; the declaration prepares r9's tagged run). Ceilings declared from measured data with headroom, max-based pass/fail, the format precedent followed.

## Bounds/invariants/deliverable

Same as r7. The per-fixture gate stays independent (no producer-helper calls). Red-first for any new machinery. Verification: the full-corpus outcomes recorded; the 2 known-good fixtures stay green; any newly failing fixtures get per-fixture evidence; battery + nets green; kondo 0 errors. Deliverable: findings r8 + the complete outcomes file (mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
