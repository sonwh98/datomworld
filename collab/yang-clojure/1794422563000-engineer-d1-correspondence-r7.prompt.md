Created-GMT: 2026-11-13 18:3x GMT
Created-Local: 2026-11-14 01:3x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5bd-11ef-7513-9672-97c79d26f3bc)

# Task: D1 continuation r7 — the persisted corpus record + per-fixture verifier gates

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 01:3x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` @ `56cc5ca3` + UNCOMMITTED r1-r6. r6 landed the independent verifier pilot (`yang.correspondence-verifier`: own site-declaration derivation, own source-hash, own paired-tree traversal + binding reconstruction; producer-vs-verifier agreement on the `:beta-eq-false` fixture; the mutated-declaration rejection test; red-first; 53/674/0 standing battery; kondo 0/0). Its "Remaining before verification publication" list is this round's target. Findings chain r1-r6 complete (canonical copies in main collab). The original brief + r2/r3 amendments govern.

## The two items

1. **The persisted corpus record**: produce the complete D1 record for the corpus (the fixtures the oracle covers, per-fixture admissions with retained coordinates) — evidence address (the frozen v1 file's Jing materialization per r2), recipe address (r2's recipe, versioned), roots (the live per-fixture compiled trees' canonical addresses), origin-bindings (r6's verifier-reconstructed bindings per fixture), the C1 metadata and instruction-provenance side records (cc3's landed publish machinery), stored durably (r3's file-backed pattern, but a REPRODUCIBLE location — the r3 finding that a `target` path is not an available closure stands; propose and justify the location in findings). The record's construction must run the r6 verifier's checks per fixture at construction time.
2. **Per-fixture verifier gates**: extend the verifier from the pilot fixture to the corpus — each fixture's admission is constructed, stored, fetched, and independently verified (the r6 machinery), with the per-fixture independence rule kept (one pass licenses nothing; each fixture's gates run on its own record). The mutated-table rejection test extends to per-fixture mutations. Report per-fixture outcomes; a fixture that cannot complete its gate records the refusal at its stage (no silent corpus-wide claim).

The verifier RESULT publication, bounds declaration, and tagged run stay OUT of this round (r8+). If the corpus record's construction reveals a gap (e.g. a fixture whose admission coordinates can't be retained, or an expansion record that doesn't exist), record it as an inventory item — do not invent.

## Bounds/invariants/deliverable

Same as r6: bounded files + the new record-construction machinery; forbidden set unchanged; v1 bytes frozen; origins only from retained coordinates; red-first; per-fixture independence. Verification: the corpus-record construction test, per-fixture verifier gates green (or honestly refused), the mutation rejection extended, the standing nets green, kondo 0 errors. Deliverable: findings r7 (mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
