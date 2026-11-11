Created-GMT: 2026-11-11 04:3x GMT
Created-Local: 2026-11-11 11:3x +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Architect sign-off request — cc2 (transform & session C1 preservation)

Role: Lead System Architect (commit sign-off per orchestrator.md's coordination contract)

Implementers:
- Status-Event: 2026-11-11 12:2x +0700 | Model: gemini-4-argon-high | Status: failed | Rationale: 429 RESOURCE_EXHAUSTED at turn start, resets in ~126h (also recorded by the ts seat); zero output
- Status-Event: 2026-11-11 12:5x +0700 | Model: gpt-6.1-sol | Status: failed | Rationale: codex shared-pool usage limit at turn start (resets ~hourly; drained by the same-session astra confirms); zero output
- Model: deepseek-v4-pro | Assigned: 2026-11-11 12:5x +0700 | Status: active | Rationale: third lane, different family and model from both the astra review thread and the failed attempts; architect-rated for invariant work; off-peak metered window

Perform a read-only architecture sign-off review of the completed cc2 round. Do NOT edit files. This is the sign-off that authorizes the orchestrator to merge `cc2-transform-session-c1` into master.

## The work

Branch `cc2-transform-session-c1`, two commits on top of master `4cfa609d`:
- `2d170926` — cc2 round 1: safepoint derive-envelope C1 recomputation + explicit surviving/injected/unmapped correspondence; encoder projected-member metadata preservation + full claim validation; index/REPL publication recovery bound to the expander's retained tables; repl link forwarding.
- `a1133cbe` — fix rounds r3+r4: admission pairing from commit evidence only (`index-round-succeeded?` gate; no counter inference; both-sides uniqueness; rehydration claim-free), wrapper provenance validated against retained assembly evidence (`:yang.link.publish/wrapper-provenance` refusal), exact coverage refusal, four adversarial regression cases.

Diff: `git diff 4cfa609d..a1133cbe` in `/Users/sto/workspace/datomworld-cc2` (six files: yang/safepoint.cljc, yin/repl.cljc, yin/repl/query.cljc, yin/vm/encoder.cljc, yin/vm/linker/publish.cljc, test/yang/clojure_callsite_test.cljc; +1523/−126 total). Forbidden surfaces untouched (vm/macro.cljc, vm/semantic/*, yang/clojure.cljc, core/prelude.cljc, vm/linker/dht.cljc, vm.cljc).

## The evidence chain (all commands re-run by the orchestrator, not delegated summaries)

- Independent review (astra gpt-6-astra, codex thread 01a1c759): round-1 REQUEST CHANGES (5 findings: 2×P0, 2×P1, 1×P2) → r2 → confirm REQUEST CHANGES (F2 residue + new P0 + F5) → r3 → confirm REQUEST CHANGES (narrower residues) → r4 → **confirm APPROVE** (F2/F6/F5 CONFIRMED-FIXED, no new blockers). Findings: collab/yang-clojure/1794345832000*, 1794347200000*, 1794352400000*, 1794353400000*.
- Author: muse-wonder-1.0 (4 sessions, by-state resume chain). Every fix round reproduced its findings red-first before going green.
- Laptop G0 (orchestrator's own runs): callsite battery 28/285/0 → 29/334/0 → 31/348/0 at each round tip.
- Shiva (scratch clone ~/ci/datomworld-cc2, GitHub origin, merge-base verified at each run):
  - focused set at a1133cbe (callsite+safepoint+publish+repl.query+repl+index): 152 tests / 1311 assertions / 0 failures.
  - dao.stream merge precondition: tri-lane green (clj 589/0, cljs 579/0 after npm repair, cljd 509/0) at 2d170926; re-run at a1133cbe in flight at brief-writing time — final numbers in the orchestrator's sign-off record; treat this brief as provisional until they land green.
  - kondo: 0 errors / 1 pre-existing warning (encoder `load` refers, present on base).
  - kit contract test (`yang.call.exception-contract-test`): red on BOTH the branch and pristine master with IDENTICAL counts (4/141/87F/21E) — the documented intentionally-red baseline pending exception rounds; attributed pre-existing, not cc2. Does not complete on the laptop on any tree (three measured non-completions); completes on shiva in ~30 min.

## Known accepted limitations (from the reviewer's own APPROVE, for the record)

1. The wrapper check verifies consistency with the retained assembly witness; it does not authenticate against a caller fabricating both witness and claim — the composition remains the provenance authority (the design's own policy; owner ruling U: origins are supplied by the admitting composition).
2. The recorder's fail-closed round gate can suppress legitimate claims when a retained `:failure` was not cleared by the current round — claim-cost only, never a wrong origin.
3. The dht publication custody gap (`dht/publish-name!` discards the `:call-sites` handle) is unchanged and explicitly deferred to cc3 (recorded in r2 findings).
4. Index-recovery for rehydrated sessions publishes claim-free by policy.
5. The kit-gate laptop line is N/A-with-evidence; shiva is the kit gate.

## Sign-off questions

1. Do you sign off on merging `a1133cbe` into master as-is (known limitations recorded)?
2. Are any of the five recorded limitations landing blockers in your judgment, or correctly deferred (1, 4 = design policy; 3 = cc3's named item; 2, 5 = fail-closed/attribution evidence)?
3. Any invariant violation you can name from the diff (origins minted, canonical bytes changed, legacy broken, metadata reaching hashes)?

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: `SIGN-OFF: APPROVED | DENIED` (+ one-paragraph rationale), per-question answers, and any conditions attached to the approval. Read-only; cite file:line for anything you flag.
