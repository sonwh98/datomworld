Created-GMT: 2026-11-08 14:43:29 GMT
Created-Local: 2026-11-08 21:43:29 +07
Coding-Agent: muse
Session-ID: a1b7a676-f833-4542-9251-5e6a9344d872

# Task: Step 1 Invariant Pins Remediation per Architect Review

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-08 21:43:29 +07 | Status: active | Rationale: Remediate 3 specific Step 1 findings from Lead System Architect gpt-6-astra

Read first:
- `collab/dao-stream/1794148750000-architect-step1-review.gpt-6-astra.findings.md`
- `docs/design/yin.repl.dht-composition.md` (§8 Invariants to pin, §9 Step 1 exit criteria)
- Existing uncommitted work in `/Users/sto/workspace/datomworld-yin-repl-dht`

## Exact Remediation Tasks (address all 3 findings)

1. **High — Publication-Result Protocol Expectations (`index_test.cljc:926`, `fixture.cljc:160`):**
   - Explicitly specify expected outcomes for the full publication-result protocol: matching success, refusal, delayed completion, wrong ID/round/manifest, and duplicate/stale results.
   - Distinguish these protocol assertions from existing synchronous pins; connect them to the real seam so Step 2 has an unambiguous oracle.
2. **High — Invariant Pin Evidence & Classification:**
   - Supply revision-specific commands, executed test identifiers, complete outcomes, and evidence paths for the narrow pre-relocation pins across §8 (restore-Settings, repair-cadence, cross-host parity, and composition-lifecycle baseline).
   - Classify known integration reds by signature under §11.
3. **Medium — Retain Shell-Level Acceptance Reproduction for Publication Retry Defect (`index_test.cljc:967`):**
   - Retain a failing shell-level acceptance reproduction pin and record its exact baseline revision, signature, and command.
   - Keep the narrower green indexer oracle; defer the engine fix to Step 2.

## Constraints
- Work strictly in `/Users/sto/workspace/datomworld-yin-repl-dht` on branch `yin-repl-dht-composition`.
- Keep changes in `test/yin/repl/`; do NOT edit `src/`.
- Run focused individual tests (G0).
- Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
