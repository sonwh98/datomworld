Created-GMT: 2026-11-14 04:2x GMT
Created-Local: 2026-11-13 23:2x EST
Coding-Agent: codex
Session-ID: (resume of thread 01a1d916-5204-7cc0-8c44-b6a552dd37d7 — jupiter)

# Task: D1 r9 continuation — rebuild the corpus with persisted inputs on jupiter, then the bounds + tagged run

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 04:2x UTC | Status: active | Rationale: continuity on the jupiter thread; the owner directs POOL 2 be drawn down

## State resume

Your r9 returned incomplete with the exact reasons: the r8 measurement store was deleted (its outcomes file retained outcomes, not input/snapshot addresses), and jupiter's heap/trace bounds are unmeasured. Your static pilot passed (1/4), the standing battery passed (57/700/0), the v1 table hash unchanged, nothing committed.

The laptop corpus run's findings chain (r1-r8 + the two astra rulings + the rebind note) is in the worktree's `collab/yang-clojure/` — read the r8 findings (`1794422563000-engineer-d1-correspondence-r8.glm-5.3.findings.md`) for the corpus-run pattern you are replicating: fresh recorded admissions (scope + ordinal per fixture), the full static gate per fixture, per-fixture outcomes with input/snapshot addresses RETAINED THIS TIME, batched with the outcomes file as the progress record.

## The work (in order; this supersedes your r9 brief's sequence)

1. **The corpus rebuild on jupiter**: all 155 fixtures through the full static gate under fresh recorded admissions (the r8 pattern), WITH the retained input/snapshot addresses persisted per-fixture this time (that was r8's gap — the outcomes file must carry what reconstruction needs). Batched; the outcomes file is the progress record; a killed batch loses nothing.
2. **The jupiter bounds measurement**: from the rebuilt corpus's actual data — peak heap (named method + comparability limits), lane quantities, serialized sizes, per-phase elapsed — declared as finite ceilings with headroom, max-based pass/fail (the cc3 v2 format; the draft `1794671000001-d1-bounds-declaration-draft.md` supplies the shape).
3. **The tagged verification run UNDER the jupiter declaration**: the independent verifier executes the scoped attribution query over both C lanes with the ceilings enforced. A ceiling exceedance is a failed bounded run, recorded honestly — never a raised ceiling.
4. **The verification-result publication** + the companion record: `slice9_verification.edn` (or sibling) + `slice9_correspondence.edn` per the original brief — with the retention-location note (the OWNER DECISION is pending; construct and store in the worktree's scratch store; do NOT commit the large store).

If the turn cannot complete all four: deliver the next coherent partial with the same honesty standard (the corpus rebuild with retained addresses is the priority — it unblocks everything else).

## Bounds/invariants/deliverable

Same as r9's brief: bounded files; forbidden set unchanged; the v1 bytes frozen (SHA e435ac40...); origins only from retained coordinates; the verifier rejects, never substitutes; red-first per item; kondo 0 errors. Deliverable: findings (worktree mirror; `1794700000000-engineer-d1-r9b-corpus-rebuild.gpt-6.1-terra.findings.md` or your naming), updating the r9 findings by reference. Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
