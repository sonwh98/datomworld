Created-GMT: 2026-10-16 16:14:13 GMT
Created-Local: 2026-10-16 23:14:13 +07
Coding-Agent: agy
Session-ID: 0a64aec9-237e-42c5-afae-a949ee4be967
# Task: Architectural Review & Effort Re-Estimation of Any-PC Snapshots (Slice 6d / 6e)
Role: Lead System Architect
Implementers:
- Model: gemini-4-argon | Assigned: 2026-10-16 23:14:13 +07 | Status: active | Rationale: Independent Architect second opinion on the 9-day estimate for any-PC snapshots on the post-Phase-8 register-shaped semantic VM

Completed-GMT: 2026-10-16 16:15:00 GMT
Completed-Local: 2026-10-16 23:15:00 +07

# Executive Summary for the Orchestrator (`glm-5.3-flash`)

The **9-day estimate** from the pre-Phase-7 assessment (`collab/archive/register-vm-2026-10/1792430000000-architect-any-pc-snapshot.report.md`) is **2x-3x too high** in human-equivalent effort and **~4x-6x too high** in actual multi-agent wall-clock execution on this repository.

1. **Slice 6d (Canonical Semantic VM + UCF Row Carrier) is ~120-140 LOC across two files (`frame.cljc` and `rows.cljc`) and 0 LOC in the evaluator or analyzer.** Realistic effort is **2-2.5 human-equivalent days** (**~6-12 hours of wall-clock time** across 1 implementation round, 1-2 review rounds, and a `shiva`/`jupiter` slow-lane gate).
2. **Slice 6e (Cross-Engine de Bruijn Stack/Register Any-PC Layouts, 4.0 days of the old estimate) should be dropped unless explicitly requested by the owner.** Under Owner Ruling 8 (`docs/design/yin.vm.semantic.continuation.md:128-138`, §0.1 & §4.6), `debruijn/stack` and `debruijn/register` are retained **backends over a documented fragment that is not widened**. Furthermore, the register backend refuses any saved state holding a `:prim` in a live register (`:continuation-registers`, `continuation.md:748-768`), which disqualifies every PC between loading a primitive operator (`:var rd +`) and invoking it (`:call`).
3. **Even if Slice 6e were included**, its static prerequisites (`stack-layouts`, `correspondence`, `phys-maps`) are already computed for every PC in `src/cljc/yin/vm/semantic/layout.cljc:85-131`, making 6e ~150 LOC (~1.5-2 days human-equivalent), bringing the combined 6d+6e total to **4-4.5 days**, not 9.

---

## Findings (Severity | file:line | Invariant / Evidence | Recommended Correction)

| Severity | file:line | Invariant / Evidence | Recommended Correction |
| :--- | :--- | :--- | :--- |
| **P1 (Scope / Estimate)** | `collab/archive/register-vm-2026-10/1792430000000-architect-any-pc-snapshot.report.md:177-182` | **Ruling 8 (`yin.vm.semantic.continuation.md:128-138`):** 4.0 of the 8.5-9 estimated days belong to Slice 6e (dynamic any-PC lift/lower on the de Bruijn stack and register backends). On 2026-10-13 the owner ruled that the de Bruijn kernels are backends over a fixed fragment that is **not widened**, and `debruijn/register` structurally refuses `:prim` values in live registers (`:continuation-registers`), which occurs on every non-safepoint PC between `:var` of a primitive and `:call`. | Scope the implementation to **Slice 6d only** (canonical `yin.vm.semantic` + UCF version-3 row carrier + static every-PC checks A.3/A.4 in `layout_test.cljc`). Do not schedule Slice 6e unless the owner explicitly reverses Ruling 8's non-widening clause. |
| **P2 (Code Delta Sizing)** | `src/cljc/yin/vm/semantic/frame.cljc:49-186` & `src/cljc/yin/vm/ucf/rows.cljc:49-69,1650-1698` | **Codebase Grounding:** The evaluator (`yin.vm.semantic/vm-hot`) already materializes `{:control {:segment seg :pc pc} :window W :env E :k K}` on fuel exhaustion (`vm/step` = fuel 1), and `yin.vm.semantic.analysis/live` already computes `L(pc)` at every PC. Only the frame validator (`frame.cljc`, 187 lines total) and row codec (`rows.cljc`) reject non-parked PCs. | Budget Slice 6d as a single focused VM Engineer round (~35 LOC in `frame.cljc`, ~90 LOC in `rows.cljc`, ~5 LOC in `layout.cljc` `state-prim`, plus tests in `frame_test.cljc`, `rows_test.cljc`, `layout_test.cljc`, and `safepoint_harness_test.cljc`). |
| **P2 (Custody Boundary)** | `src/cljc/yin/vm/ucf/rows.cljc:1704-1716,1765-1773` | **Explicit Causality / Custody Admission:** `header-custody`, `custody-baseline`, and `ledger/offer-defect` currently partition runnable roots into `:blocked` and `:parked`. Phase 7 landed after the Oct 13 report, so Q2 of that report is now the sole architectural decision before dispatching 6d. | Ask the owner one binary question before briefing the engineer: **(a)** admit `:running` roots in exclusive custody (treating `:running` identically to `:blocked`/`:parked` in `header-custody`, `custody-baseline`, and `ledger/offer-defect`, ~10 LOC), or **(b)** restrict `:running` roots to fork-only (`custody nil`) and refuse exclusive headers on `:running`. |
| **P3 (Test Tiering)** | `test/yin/vm/semantic/safepoint_harness_test.cljc` | **Verification Discipline (`docs/agents/build-n-test.md`):** Stepping all 89 reference cases across ~3,000-6,000 dynamic PCs and running `lift -> lower -> run` at each PC will take hours on Node and Dart if placed in the quick tier. | Gate the exhaustive every-PC sweep behind `^:slow` and `dao.test-slow/guard` (run on `shiva`/`jupiter`), and put the 5-structural-PCs-per-bare-row sample (~150 points) in the quick tier. |

---

## Confirmed Architectural Properties (Passed Review)

1. **Every instruction boundary of `yin.vm.semantic` is a canonical CESK state (`yin.vm.semantic.md` §2.1, §8.1):**
   - At any instruction `pc` before execution, the state is completely determined by `⟨A, pc, W↾L(pc), E, K⟩` plus the task context (`free-env`, `store`, `module-stores`, `parked`, `cursor-cells`, `id-counter`).
   - Trimming `W` to `L(pc)` (`analysis/live`) is sound and canonical: no register outside `L(pc)` can be read on any path from `pc` before being overwritten, and `select-keys` preserves nil-valued live registers as `[:lit nil]`.
2. **Step atomicity preserves stream and effect invariants (`datom.world.md` Axioms 1-4):**
   - In both ungated execution and custody-gated execution, stream and FFI effects are observed atomically within a single step or scheduler sweep. No instruction boundary holds a half-issued effect.
   - Retaining the `:yin.k/not-quiescent` refusal when `:ready-queue` is non-empty (`rows.cljc:1651-1652`) is architecturally required because a ready-queue entry holds an already-performed stream effect outcome (`:value`, `:resource-updates`) outside both the pending table and the register window; a caller crosses it with a single `vm/step`.
3. **`live-pcs` and free-name discharge (`rows.cljc:1579-1630`) require zero algorithmic changes:**
   - Recording `(context! ctx a pc (:env m))` for a `:run` state feeds `live-pcs` from `pc` identically to an `:act` state's resume `pc`.
4. **Canonical cursor-cell numbering (`yin.vm.semantic.cursor-cell-numbering.md`) requires zero changes:**
   - Discovery-order traversal walks `:task -> frames` regardless of whether the frame slot holds `:wait` or `:run`.
5. **Static every-PC correspondence holds in `layout.cljc` without touching runtime backend lowering:**
   - Linear-scan register allocation (`derive.cljc:161-189`) assigns distinct physical slots to all virtual registers in `L(p) ∪ def(p)` at every reachable PC `p`.
   - `stack-layouts` (`layout.cljc:118-131`) already computes pending pushed virtuals at every PC `p`, satisfying `(set (stack-layout p)) ∪ (set (uses v[p])) = L(p)` at every reachable non-tail PC and `(set (uses v[p])) = L(p)` at every reachable tail-call PC.

---

## Exact Implementation Scope for Slice 6d (for the Engineer Brief)

1. **`src/cljc/yin/vm/semantic/frame.cljc` (~35 LOC):**
   - Extend `defect` S1 check to accept `:yin.k/state :run` with required keys `[:yin.k/state :yin.k/segment :yin.k/pc :yin.k/env :yin.k/window :yin.k/k]` and no optional keys (R3: no `:yin.k/deliver`, no `:yin.k/pending`).
   - In C2 (`:118-123`), allow `0 <= pc < (count v)` when `(= :run (:yin.k/state frame))` (body-start PC `0` or `body-pc` is valid for `:run`; `pos? pc` remains required for `:act` and `:return` frames).
   - Skip C3 (`body(pc-1) = body(pc)`) for the top-level frame when `:run` (still enforce C3 on all `returns` in `:yin.k/k`).
   - Parameterize `window-defect` (`:49-61`) to take `wanted` (`(analysis/live (analysis/analyze v) pc)` for `:run` with no `rd` self-check; `(analysis/saved (analysis/analyze v) pc rd)` with `(contains? w rd)` check for `:act` and `:return`).
   - In P1 (`:162-164`), admit context `:running`: requires `(= :run (:yin.k/state frame))` and `(not (contains? frame :yin.k/pending))`; forbid `:run` in `:wait`, `:captured`, and `:parked`.
2. **`src/cljc/yin/vm/ucf/rows.cljc` (~90 LOC):**
   - `row-table`: add `:run [:A :N [:ref :env] [:ref :window] [:ref :k]]` and update `:task` slot 13 (`frames`) to `[:refs :wait :run]`.
   - `rows->frame` / `frame->rows`: add `:run` bijection (no `:yin.k/deliver` synthesized).
   - `lift-task*`: when `(some? (:control m))`, if `(and (not (:blocked? m)) (empty? (:wait-set m)))`, set `kind` to `:running` (else refuse `:yin.k/not-at-safepoint`); build the single `:run` node from `(:control m)`, `(:env m)`, `(select-keys (:window m) (analysis/live a pc))`, and `(:k m)`, recording `(context! ctx a pc (:env m))`.
   - `check-pendings!` / `validate-frames` / `restore-task`: enforce that `:running` has `role :root`, `frames` count = 1 of kind `:run`, `parked-id nil`, `result nil`; validate the `:run` frame under context `:running`; on restore, populate `:control`, `:window`, `:env`, `:k` with `:halted? false` and `:blocked? false`.
3. **`src/cljc/yin/vm/semantic/layout.cljc` (~5 LOC):**
   - Add `:run` arm to `state-prim` (`:863-874`) alongside `:act`.
4. **Tests & Docs:**
   - `test/yin/vm/semantic/frame_test.cljc`: `run-state-rules` (every PC of `:worked-example`, body-start PC, `act` vs `run` at `p+1`, tampers for C2', R1, R2, R3, P1).
   - `test/yin/vm/ucf/rows_test.cljc`: `run-row-bijection` and `running-kind` round-trip.
   - `test/yin/vm/semantic/layout_test.cljc`: `static-checks-at-every-pc` (A.3 register injectivity over `L(p)` and A.4 `stack-layout(p) ∪ uses(p) = L(p)` across `layout-corpus`).
   - `test/yin/vm/semantic/safepoint_harness_test.cljc`: quick-tier 5-PC structural sample (`sampled-pcs-match-the-reference`), `^:slow` full every-PC sweep (`every-pc-matches-the-reference`), and `run-refusals`.
   - Docs (`yin.vm.semantic.continuation.md` §1.3, `yin.vm.semantic.md` §4.1, `yin.vm.universal-continuation-format.md` §7.4.2).
