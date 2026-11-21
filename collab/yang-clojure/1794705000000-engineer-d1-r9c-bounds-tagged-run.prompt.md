Created-GMT: 2026-11-13 20:2x GMT
Created-Local: 2026-11-14 03:2x +0700
Coding-Agent: codex
Session-ID: (fresh thread on jupiter; the by-state brief carries the context)

# Task: D1 continuation r9c — the jupiter bounds declaration, the tagged verification run under it, and the verification-result publication

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 03:2x +0700 | Status: active | Rationale: continuity — the corpus rebuild (155/155, retained addresses) is your predecessor's completed state

## State resume (by state — everything is in the worktree)

Worktree: `/Users/sto/workspace/datomworld-d1` (branch d1-correspondence @ `747e80fc` + the uncommitted r9b continuation work: the corpus rebuild is COMPLETE — **155/155 fixtures pass, 0 refusals**, per-fixture outcomes with admission origins and six segment addresses each in `target/d1-r9b-outcomes.edn` (128,453 B) and the scratch store `target/d1-r9b-store/content.jing`. A fresh process verified all 930 addresses against their recorded admissions.)

Read first: the findings chain r1-r8 (`collab/yang-clojure/1794422563000-engineer-d1-correspondence-r{1..8}...findings.md` + the r9b continuation findings `1794700000000-engineer-d1-r9b-continuation*.findings.md`), the original brief + amendment (`1794422563000-engineer-d1-durable-correspondence.prompt.md`), the two astra rulings (`1794524600000-...findings.md` RULING (A) with the normative §4.1 amendment; `1794422563000-architect-d1-profile-amendment-r2...findings.md` RULING (b) the numeric-fidelity relation), the bounds-declaration DRAFT (`1794671000001-d1-bounds-declaration-draft.md` — its storage/rows/sites/elapsed ceilings came from the LAPTOP corpus run; the peak-heap and lane quantities are explicitly NOT declared — this round measures them on jupiter and declares them), and the cc3 v2-declaration format precedent (`1794390804000-cc3-bounded-run-declaration-v2.md`).

## The three deliverables (in order)

1. **The jupiter bounds declaration**: measure peak heap (named method + comparability limits — the cc3 v2 format), C-lane quantities (guest instructions, trace records/bytes per the cc3 baselines), serialized sizes, and per-phase elapsed on THIS host from the completed corpus data; declare the finite ceilings with headroom, max-based pass/fail. The declaration is jupiter-scoped (the laptop's r2-declared 8 MB ceiling history is context, not the bound; the draft's storage/rows/sites/elapsed ceilings reconcile against the corpus-run data already recorded).
2. **The tagged verification run UNDER the declaration**: the independent verifier (`yang.correspondence-verifier`) executes the scoped attribution query over BOTH C lanes per design §4.2 step 6, with the declaration's ceilings enforced (max-based; an exceedance is a failed bounded run recorded honestly — never a raised ceiling). The run consumes the persisted corpus record/inputs from the scratch store.
3. **The verification-result publication + the companion**: `test/yang/latte_oracle/slice9_verification.edn` (or the design-named sibling) — the record's address, the verifier revision, inputs, per-fixture outcomes, the tagged-run evidence; and the companion `slice9_correspondence.edn` referencing the frozen v1 evidence address. Both remain worktree artifacts (the retention-location OWNER DECISION is pending; do NOT commit the 700 MiB store — note the pending decision in the findings).

## Bounds/invariants/deliverable

Same as r8: bounded files; forbidden set unchanged; the v1 bytes frozen; origins only from retained coordinates; the verifier rejects, never substitutes; red-first for any gate machinery; the standing nets stay green; kondo 0 errors. The full oracle suite is shiva-scale — do NOT run it. Deliverable: `collab/yang-clojure/1794705000000-engineer-d1-r9c-bounds-tagged-run.gpt-6.1-terra.findings.md` (worktree mirror + say-so). Commit NOTHING.

If a ceiling is exceeded: record the exceedance honestly (a failed bounded run) and deliver the partial — never raise a ceiling silently.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
