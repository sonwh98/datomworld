Created-GMT: 2026-11-12 21:35:00 GMT
Created-Local: 2026-11-13 04:35:00 +07 (+0700)
Coding-Agent: codex
Session-ID: fd4ddc16-b4a3-4c11-88f3-d94e153a2fa1
# Task: engineer — PORT Phase 3r-b: correct the baseline experiment (the right pre-port state)
Role: Engineer (port continuation; Phase 3r landed 8456c2e9 with post-port depths but the wrong baseline target)
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 04:35 +0700 | Status: active | Rationale: owner routing (deepseek low → codex; sol reserved for review; terra for moderate implementation). The baseline experiment is measurement plumbing, well-scoped.

## What happened before you (verified corrections)

1. Phase 3r (deepseek) attempted baselines at `f37bc62b`/`9c10d2ca` — my brief MISPECIFIED those
   as "pre-port outcome model." `f37bc62b` is the seam-REMOVAL transition commit: VM doors gone,
   compiler references to them still present — a known-broken mid-refactor state. Baselines there
   are meaningless, and the delegate correctly refused to fabricate.
2. VERIFIED FACT: `fbd8da0f` DOES contain the seam in `yin/vm/engine.cljc` (+52) and
   `module.cljc` (+30), and `clojure.cljc` references them consistently — it was shiva-verified
   GREEN (xtrack-r3A4: 103/993 0F/0E, all 11 clojure rows green). **That is the valid pre-port
   outcome-model baseline state.** (The seam's removal came later per the owner's NO; for
   MEASUREMENT purposes fbd8da0f is the outcome model at its working best.)

## The task

1. Reuse the scratch worktree `/Users/sto/workspace/datomworld-portpre`: check out **`fbd8da0f`**
   (detached; clean first). Apply ONLY the harness instrumentation from the port's `8456c2e9`
   (depth collection in `latte_oracle_test.clj` / `latte_oracle_tables.clj` — cherry-pick the
   harness hunks or apply as a patch; if the corpus sizes differ, adapt minimally and say so).
2. Run the depth collection on fbd8da0f. Record the **baseline EDN**: per workload → max depth
   for the workloads that exist in BOTH states (the overlap set).
3. On the port branch tip (`8456c2e9` — already measured: 155 workloads, max 28 frames), produce
   the **comparison table**: workload | baseline (fbd8da0f) | post-port (8456c2e9) | ratio.
   Workloads with no pre-port equivalent: mark `no-baseline` explicitly, post-port number only.
4. Commit the baseline EDN + comparison on `exception-r3-impl`. Report the table. **No judgment
   about thresholds** — the owner's rule: any workload >10× baseline or >10k frames goes to the
   owner before merge. Compute ratios; flag which (if any) exceed 10×; do not evaluate further.

## Notes

- The ratio can exceed 10× at trivially small absolute numbers (e.g. 2 → 28 frames); that is the
  threshold working as designed — present both numbers, let the owner see it.
- fbd8da0f era corpus is smaller (pre-cc3 expansion) — the overlap set is what compares; do not
  invent pre-port numbers for workloads that did not exist.

## Verification

Measurement runs on the portpre worktree + port branch are the task itself; no gates. kondo on
committed files. Commit + push `exception-r3-impl`.

End with Completed-GMT/Completed-Local and: **BASELINES RECORDED: <overlap count>/<155>** or
**BLOCKED: <what>**.
