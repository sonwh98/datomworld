Created-GMT: 2026-11-12 21:00:00 GMT
Created-Local: 2026-11-13 04:00:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: fb63d109-d846-4ee7-b5c6-9bcfa975044d
# Task: engineer — PORT Phase 3r: depth harness + baselines via a pre-port checkout (your blocker, resolved)
Role: Engineer (port continuation; your Phase 2b+3 Part 1 landed clean at 8e1dab6d)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-13 04:00 +0700 | Status: active | Rationale: continuity; your blocker diagnosis was correct and the resolution keeps your harness work.

## Your blocker, resolved

You were right: the port is already on the branch, so no pre-port state exists THERE. But the
pre-port state exists at **`f37bc62b`** (post-cc3 rebase, outcome model intact — the commit
right before your Phase 1). The experiment design:

1. **Land the harness on the port branch** (`exception-r3-impl` @ `8e1dab6d`): the depth
   collection instrumentation (max-live-K per workload) in the latte test runner, as you were
   building. The KERNEL-GATE corpus port (admitting `clj/*`, moving `arg-vector-opts`/
   `oracle-registry` off the outcome model) is ALSO yours — it is Phase 3's other half and it
   unblocks the corpus on the port branch. Scope: `test/yang/latte_oracle_test.clj`,
   `src/dev/latte_oracle_tables.clj`, and whatever kernel-gate admission/runtime pieces the
   corpus needs in `clojure.cljc`/prelude.
2. **Measure baselines on a PRE-PORT checkout**: create a scratch worktree at `f37bc62b`
   (`git worktree add /Users/sto/workspace/datomworld-portpre f37bc62b`), apply the SAME
   harness instrumentation there (cherry-pick the harness commit, or apply it as a patch — the
   harness is test-side instrumentation and should apply to the outcome-model tree with the
   outcome-model admission), run the corpus, record baselines EDN (workload → max depth).
3. **Measure post-port on the port branch** with the identical harness → the comparison table.
4. **Commit the baselines EDN + the comparison** on `exception-r3-impl`. Both numbers travel
   to the owner's threshold check at merge time.

## Acceptance

- Harness on the port branch; kernel-gate corpus runs on the port branch (admission + runtime
  fixed for the continuation encoding).
- Baselines EDN committed: per-workload max depth at `f37bc62b` (outcome model).
- Post-port numbers committed: per-workload max depth at the port tip.
- The comparison table in your report: workload | baseline depth | post-port depth | ratio.
  **No judgment about whether thresholds fire — the numbers only.** The orchestrator takes the
  comparison to the owner if any workload exceeds >10× or >10k frames.

## Verification

Focused: `yang.latte-oracle-test` (or the kernel-gate namespace's post-port name) on the port
branch; the baseline worktree runs are measurement, not gates. kondo where runnable. Contract
test remote-only. Commit + push `exception-r3-impl` (harness + corpus port + EDN). The
baseline worktree is scratch — remove it after the baselines are committed, or leave it for
the orchestrator and say which.

End with Completed-GMT/Completed-Local and: **BASELINES RECORDED: <n> workloads** or
**BLOCKED: <what>**.
