Created-GMT: 2026-11-13 20:5x GMT
Created-Local: 2026-11-14 03:5x +0700
Coding-Agent: codex
Session-ID: pending (provider-generated — fresh thread on the laptop)

# Task: D1 continuation r9e — fresh admissions per ruling (A) migration step 4; the completion round on the laptop

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 03:5x +0700 | Status: active | Rationale: POOL 1 probe-verified live; the ruling (A) migration path authorizes exactly this

## State resume (by state — read the findings chain first)

Worktree: `/Users/sto/workspace/datomworld-d1`, branch d1-correspondence, NOW AT `ccd3648d` (the state-preservation tip: the r1-r8 WIP + the jupiter r9b/r9c corpus-rebuild machinery + the 155-fixture outcomes file + the findings chain r1-r9d). Read the findings chain r1-r9d (in `collab/yang-clojure/`) — especially r3's replay-hazard finding, r5's amended-fidelity state, r6's independent-verifier pilot, r7's per-fixture gates (2/155 verified + 153 not-run), r8's corpus run (155/155 on the LAPTOP with the outcomes file — BUT its scratch store was deleted after recording, per r8's own findings), and r9d's BLOCKED report (the laptop worktree was clean because the jupiter-side corpus state never existed here).

**The ruling (A) migration step 4 authorizes EXACTLY this round**: "Produce new durable admissions where the pilot's retained closure no longer exists. Never reconstruct historical admission identity from equal source text." The r8 laptop outcomes' historical admissions are not reconstructable (their store was deleted) — so this round produces FRESH admissions for the whole corpus on THIS host, and the D1 record references THESE.

## The work (in order)

1. **The fresh-corpus admission run on the laptop**: all 155 fixtures through the per-fixture static gate (check-fixture! machinery from r7) under FRESH recorded admissions (scope + ordinal), with the r8 corpus-run pattern: batched, the outcomes file as the progress record, per-fixture measurement capture (the r8 pattern), the mutation-refusal checks per the r7 pattern. The captured admission texts (per ruling (A): forms/call/program/options captured at the input boundary) must be RETAINED this round — not deleted after recording (that was r8's gap): the r4 profile's file-backed store pattern with close/reopen proof.
2. **The laptop bounds measurement** from the fresh corpus data: peak heap (named method + comparability limits), C-lane quantities, serialized sizes, per-phase elapsed — the declaration's inputs (the cc3 v2 format; the r8 draft `1794671000001-d1-bounds-declaration-draft.md` is the shape to finalize).
3. **The laptop bounds declaration finalized** from the measured data: finite ceilings with headroom, max-based pass/fail, declared BEFORE the tagged run.
4. **The tagged verification run UNDER the laptop declaration**: the independent verifier (r6's machinery) executes the scoped attribution query over both C lanes, ceilings enforced (max-based; an exceedance is a failed bounded run recorded honestly).
5. **The verification-result publication + the companion record**: `slice9_verification.edn` (or sibling) + `slice9_correspondence.edn` — worktree artifacts; the retention-location OWNER DECISION remains pending (the scratch store stays uncommitted; note it).

The verifier is r6's independent machinery — producer-vs-verifier agreement per fixture, the mutated-table rejection per fixture. No tagged run before item 3's declaration exists. Red-first per item.

## Bounds/invariants/deliverable

Bounded files: correspondence*.cljc/clj, their tests, latte_oracle_test.clj, the new companion/verification artifacts. Forbidden set unchanged (macro.cljc, semantic.cljc, ast_walker.cljc, yang/clojure.cljc, core/prelude.cljc, dht.cljc). The v1 bytes frozen (SHA e435ac40...). Origins only from fresh retained coordinates. kondo 0 errors. The full oracle suite is shiva-scale — do NOT run it.

This is a LONG round (155 fixtures × compile+gates, the r8 run took hours). Work in batches; the outcomes file is the progress record; if the turn ends mid-run, the outcomes file + retained admissions are the honest stopping state for the next continuation.

Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794720000000-engineer-d1-r9e-fresh-admissions.gpt-6.1-terra.findings.md` (or worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
