Created-GMT: 2026-11-13 20:4x GMT
Created-Local: 2026-11-14 03:4x +0700
Coding-Agent: codex
Session-ID: pending (provider-generated — fresh thread on the LAPTOP; the prior jupiter thread 01a1d916 cannot resume here (no rollout on this host), so this is a by-state continuation: the worktree + the findings chain are the state)

# Task: D1 continuation r9d — complete the measurements, the verifier's missing components, and the tagged run + publication

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 03:4x +0700 | Status: active | Rationale: continuity — the corpus rebuild (155/155) and the verifier pilot are your predecessor's completed state

## State resume (by state)

Worktree: `/Users/sto/workspace/datomworld-d1` (branch d1-correspondence @ `747e80fc` + the UNCOMMITTED r9b/r9c work — this is YOUR working tree on the laptop). Read first: the findings chain r1-r8 + the r9b continuation findings (`1794700000000-engineer-d1-r9b-continuation*.findings.md` — the corpus rebuild 155/155 with retained addresses; the scratch store + outcomes exist) and the r9c findings (`1794705000000-...findings.md` — what remains and why). The two astra rulings bind the design (§4.1 amendment + the numeric-fidelity relation). The cc3 v2-declaration format governs the bounds.

## The work (in order; deliver the next coherent partial if the turn cannot finish all)

1. **Complete the interrupted measurements** (r9c's gap): the exploratory heap and serialization measurement was interrupted before producing results — re-run it to completion and record peak-heap (named method + comparability limits), lane quantities (guest instructions, trace records/bytes), serialized sizes, and per-phase elapsed on jupiter. These are the declaration's inputs.
2. **Build the verifier's missing components** (r9c's finding): the verifier needs (a) the complete D1 record + recipe built from the corpus-rebuild machinery (the r9b pattern — 155 fixtures with retained addresses; the record/recipe construction code exists in `yang.correspondence` — wire it to the corpus data), (b) image derivation checks (`yin.vm.linker/verify` per the r2 inventory's image contracts), (c) lane-trace completeness evidence (the lane runner currently discards its successful instruction count — retain it), and (d) the companion record construction.
3. **Finalize the jupiter bounds declaration** from the completed measurements (cc3 v2 format; max-based pass/fail; declared BEFORE the tagged run).
4. **The tagged verification run UNDER the declaration** + the verification-result publication (`slice9_verification.edn` or sibling) + the companion (`slice9_correspondence.edn`, with the retention-location note — the OWNER DECISION is pending; the store stays worktree-scratch, NOT committed).

If the turn cannot complete all four: deliver the next coherent partial — the measurements + the record/recipe construction is the minimum checkpoint; the tagged run + publication follow in r9e.

## Bounds/invariants/deliverable

Same as r9c: bounded files; forbidden set unchanged; the v1 bytes frozen; origins only from retained coordinates; the verifier rejects, never substitutes; red-first; the standing nets stay green (57/700/0 baseline); kondo 0 errors. Deliverable: `collab/yang-clojure/1794710000000-engineer-d1-r9d-completion.gpt-6.1-terra.findings.md` (worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
