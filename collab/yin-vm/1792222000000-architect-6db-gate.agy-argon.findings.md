Created-GMT: 2026-10-16 21:40:00 GMT
Created-Local: 2026-10-17 04:40:00 +07
Coding-Agent: agy
Session-ID: 6ca9e8c6-86b3-420e-8420-08f3c610a69d
# Task: slice 6d-b architectural gate — agy (Gemini 4 Argon) verdict
Role: Architect
Implementers:
- Model: gemini-4-argon (agy, effort high via the model's tiers) | Assigned: 2026-10-17 03:5x +07 | Status: completed | Rationale: owner routing (architectural review → agy/argon); gate ran across two turns (the 5-minute print timeout cut turn 1; resumed on the same conversation and delivered); final message promoted verbatim below (JSON response field); stdout at collab/1792222000000-architect-6db-gate.agy-argon.stdout{,2,3,4}.log — first attempts failed on model-id/effort flag syntax (argon = gemini-4-argon; no --effort flag for the bare name)

6DB-GATE: SIGNED-OFF (head fbb49cf75)

# Architectural Gate Review — Slice 6d-b (`srvm-6d-b`, `73c28036..fbb49cf75`)

Read completely in the prescribed order:
1. [1792222000000-6db-final.diff](file:///Users/sto/workspace/datomworld-srvm-6d-b/review/1792222000000-6db-final.diff)
2. [1792172314000-engineer-6d-b.report.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/review/1792172314000-engineer-6d-b.report.md)
3. [1792174323000-reviewer-6db-diff-glm.glm-5-3.findings.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/review/1792174323000-reviewer-6db-diff-glm.glm-5-3.findings.md)
4. [1792175000000-engineer-6db-nits.report.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/review/1792175000000-engineer-6db-nits.report.md) and [1792176000000-engineer-6db-lanefix.report.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/review/1792176000000-engineer-6db-lanefix.report.md)
5. [1792145566000-architect-6d-plan-v21.report.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/review/1792145566000-architect-6d-plan-v21.report.md)

Independently verified against the working tree (`src/`, `test/`, `docs/`, and `target/6db/`).

---

## Per-Question Rulings

### (a) Four Outcomes & Stage-Local Semantics — **PASS**

- **Outcome 1 (Live-tenure preemption & `:fuel` contract):**
  - [`check-common!`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L195-L197) enforces `nil` or positive integer `:fuel`; [`common`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L227) retains it; [`holder-config-keys`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/compose.cljc#L673-L674) admits `:fuel` and `:yin.k/admitted-modules`.
  - Stage 1 of [`run-cycle`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2158-L2164) executes `(if fuel (sr/run-fuel m0 fuel) (vm/run m0))` under the existing `(= :running (vm/gate-mode m0))` and `(not (:halted? m0))` guards, then increments `:completed-slices`.
  - [`cycle-result`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2227-L2229) reports `:yin.k/at-boundary true` in `:detail` when `(:control (:machine base))` is non-nil while keeping `:phase :running`. No automatic export policy is triggered (`at-safepoint?` still requires `:halted?` with empty wait/ready queues across the task tree); [`hand-off`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L1128-L1144) remains explicit.
- **Outcome 2 (Stage-local failure transport & `:yin.k/retained-boundary`):**
  - [`stage-result`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2129-L2139) checks `(require-tenure! state)` against its input `state`, executes `(operation state)`, rethrows `::journal-uncertain` and `::inbox/refusal`, and on any other failure returns `{:status :failed :state state :failure failure}` closing over that stage's input `state` (the previous stage's completed state).
  - [`run-cycle`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2149-L2180) reduces over the four stages (execution slice, `reader/replay`, `writer/emit` with `restore-handles`, `reader/step`) short-circuiting via `reduced` on `:failed`, and [`step-active-held`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2281-L2286) wraps the post-cycle `#(drain % mode)` in `stage-result` as well.
  - Because each stage's external seam callbacks (`request!`, `diagnostic!`, `observe!`, and `guarded-handle`) check `require-tenure!` and throw before `operation` returns a machine, a mid-stage throw returns the stage's input state untouched—never an invented partial machine and never reverting across earlier completed stages to `step-active`'s initial argument.
  - [`failed-stage`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2182-L2187) passes the carried `:state` to [`end-run`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L1468-L1490), which records `:yin.k/retained-boundary` (`:yin.k/pc`, `:yin.k/segment`, `:yin.k/steps-since-checkpoint`, `:yin.k/checkpoint`) and gates the retained machine and all install children `:ended`.
- **Outcome 3 (Expiry & recovery from last durable checkpoint):**
  - `end-run` leaves `:bytes` and `:address` unchanged on the holder state; [`reopen-holder`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2562-L2610) and competing candidates recover exclusively from the durable checkpoint bytes (`:bytes` / `:address` or journaled fence), losing unexported local progress.
- **Outcome 4 (Fenced reopen & execution-entry refusal):**
  - Reuses the 6d-a kernel entry guard on `vm-hot`, `vm/step`, `vm/run`, and `sr/run-fuel`. [`rehydrate-fenced`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L475-L554) lowers with `{:fenced true}`, extracts `:yin.k/recovery-ready` into the export record alongside waits and parked records while keeping the machine's live `:ready-queue []` and `:yin.k/gate :exporting`, and verifies byte-for-byte pack reproduction.

### (b) Custody & Security Posture — **PASS**

- **Zero effects past the bound at every cut:**
  - Verified in [`clock-cuts-retain-the-exact-completed-stage-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L1008-L1083) across all 9 cuts (`:before`, `:after-slice`, `:replay`, `:after-replay`, `:emit`, `:after-emit`, `:read`, `:after-read`, `:between`), [`running-export-tenure-cuts-keep-the-exact-fence-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L1139-L1188) across `:mint`, `:prepare`, `:offer` (asserting zero `past-serves`, empty `past-requests` other than `:yin.k/release`, zero attaches/observations, and no `:yin.k/resumed`), and Stage E's [`running-expiry`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/stage_e_scenarios.cljc#L1440-L1466).
- **Export record queue retention without execution under the gate:**
  - [`export/enter`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L111-L140) removes `first-refusal`, sets `:yin.k/gate :exporting`, clears `:wait-set []` and `:ready-queue []` on the root machine, and moves `:ready-queue` into `:record`. [`reinstated`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L142-L151) restores `:ready-queue` for `lift` and `abort`. [`recovery-metadata`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L315-L335), [`valid-state?`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L401-L421), and [`extract-recovered`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L435-L473) carry, validate (`(= (:ready-count state) (count (nth ss 18)))`), and extract `:ready-queue` without executing or polling it.
- **Ledger & grant admit `:running` while `:halted` refusal holds:**
  - [`grant/offer-decision`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/authority/grant.cljc#L74-L75) and [`ledger/offer-defect`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/ledger.cljc#L277) widen the offerable kind set from `#{:blocked :parked}` to `#{:running :blocked :parked}`, keeping `:halted` refused.
- **Expiry recovers ONLY the last durable checkpoint; `:yin.k/retained-boundary` inspection-only:**
  - `:yin.k/retained-boundary` is written only by [`end-run`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L1476-L1481) and read only by test assertions and [`holder-summary`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/stage_e_world.cljc#L960-L966). Nothing in `src/` reads `:yin.k/retained-boundary` or adopts the ended `:machine`.

### (c) Exact-Machine-Equality Assertions & 13-Mutant Campaign — **PASS**

- **Strongest-form equality assertions:**
  - [`clock-cuts-retain-the-exact-completed-stage-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L1067) asserts `(is (= ended (:machine result)))` across all 9 cuts, where `ended` is the full machine map of the latest completed stage with `:yin.k/gate :ended` and `:resources` restored.
  - [`running-export-tenure-cuts-keep-the-exact-fence-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L1178) asserts `(is (= (assoc exact :yin.k/gate :ended) (:machine result)))`.
  - [`tenure-is-rechecked-between-bare-effects-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L2751-L2759) asserts `(is (= expected (:machine st)))` against the complete post-replay machine gated `:ended`.
  - [`completed-queues-are-retained-without-execution-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/export_test.cljc#L1221) asserts `(is (= source (:machine (export/abort (:machine r) (:record r) [] nil))))`.
  - [`hold-resolution-needs-acknowledgement-and-backpressure-can-persist-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/reader_test.cljc#L1327) asserts `(is (= one ten))` across 10 cycles of backpressure.
  - Stage E's [`running-expiry`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/stage_e_scenarios.cljc#L1456) asserts `(is (= (assoc before :yin.k/gate :ended) (:machine ended)))` and `(is (= 0 (get-in recovered [:machine :control :pc])))` against `(pos? (get-in before [:control :pc]))`.
- **13-mutant campaign:**
  - Verified [`target/6db/mutants.clj`](file:///Users/sto/workspace/datomworld-srvm-6d-b/target/6db/mutants.clj#L28-L67) and [`target/6db/mutation-results.edn`](file:///Users/sto/workspace/datomworld-srvm-6d-b/target/6db/mutation-results.edn#L1). Each of the 13 mutants targets a distinct production rule (`sr/run-fuel`, `driver/require-tenure!`, `driver/run-cycle` failure state reversion, `driver/completed-stage`, `driver/check-common!`, `export/kind-of`, `export/reinstated`, `export/valid-state?`, `sr/check-gate!`, `lease/holding?`, `export/abort`, `ledger/offer-defect`, `grant/offer-decision`) and is genuinely killed with non-zero failure/error counts.

### (d) Documentation Hunks vs. Plan v2.1 — **PASS**

- [yin.vm.universal-continuation-format.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md):
  - Child-quiescence paragraph replaced at [L1042](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L1042) with the `:running`/`:running` and `:parked`/`:blocked` task-product rule.
  - §7.7.4 step 1 Quiesce replaced verbatim at [L1890](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L1890); step 3 updated at [L1896](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L1896) attributing the kernel guard to 6d-a; `:yin.k/gate` and hold resolution updated at [L1918-L1929](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L1918-L1929) with no fixed cycle count promised; kind/header agreement updated at [L2018-L2023](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L2018-L2023).
  - §7.7.6 ([L2151](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L2151)), §7.8 ([L2605-L2662](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L2605-L2662)), §7.10 table ([L2824-L2828](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L2824-L2828)), and §7.11 / §7.11.1 ([L2918-L2925](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L2918-L2925), [L3088-L3116](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L3088-L3116)) match plan v2/v2.1.
- [yin.vm.semantic.continuation.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.semantic.continuation.md):
  - §7.3 ([L2005-L2013](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.semantic.continuation.md#L2005-L2013)) states all four outcomes, the explicit stage-local `{:status :ok ...}` / `{:status :failed ...}` contract, the completed-slice unit for `:yin.k/steps-since-checkpoint`, last-durable-checkpoint recovery, and 6d-a ownership of the closed-gate guard.
- [yin.vm.linker.dht.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.linker.dht.md):
  - §14.1.1, §14.1.2, §14.1.3, and §14.2.3 ([L3779-L3901](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.linker.dht.md#L3779-L3901), [L4118-L4148](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.linker.dht.md#L4118-L4148)) match the plan's owned replacements.
- Inventory search (`rg -n 'not-quiescent|Quiesce|resolve in its next cycle' docs src test`) confirms no active normative clause in UCF, continuation, or linker-DHT promises one-cycle hold resolution or assigns the kernel guard to 6d-b.

### (e) Judgment Calls & Lane-Fix Root Cause — **PASS**

1. **Judgment call 1 (`:yin.k/steps-since-checkpoint` counts completed holder execution slices):** Sound and necessary. `sr/run-fuel` does not expose a guest-instruction counter, a slice can park or halt before exhausting `fuel`, and scheduler sweeps distribute fuel across install children. Counting completed holder execution slices (`:completed-slices`) is exact, deterministic, and explicitly documented in [yin.vm.semantic.continuation.md](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.semantic.continuation.md#L2009).
2. **Judgment call 2 (Strengthened [`tenure-is-rechecked-between-bare-effects-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L2751-L2759)):** Sound and required by P2-8. Under stage-local retention, losing tenure during `writer/emit` retains the completed `reader/replay` stage's machine (which followed `vm/run`, where the sweep already retired the halted install child), whereas the pre-6d-b test asserted the pre-cycle machine bug. Replacing the stale child-gate check with whole-machine equality against `replay(vm/run machine)` gated `:ended` strengthens the test.
3. **Nit fixes (`82d48e1e`) and Lane-fix (`fbb49cf75`):**
   - Both glm P3 code comments were cleanly applied ([driver.cljc:2163](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/driver.cljc#L2163) and [driver_test.cljc:1026](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L1026)), with the `:resources` normalization verified live by the engineer's in-memory probe.
   - The Node failure in `clock-cuts-retain-the-exact-completed-stage-test` was purely a ClojureScript multi-arity `with-redefs` artifact (`reader/replay` has 1- and 2-arity entry points; `writer/emit` has 2- and 3-arity entry points; replacing them with variadic `(fn [& args] ...)` omitted `.cljs$core$IFn$_invoke$arity$2` and `$3`). Providing matching multi-arity `fn replay-cut` and `fn emit-cut` in [driver_test.cljc:1038-1055](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/driver_test.cljc#L1038-L1055) is the exact right test-only fix.
   - The Dart failure in [compose_rows_test.cljc:906-911](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/compose_rows_test.cljc#L906-L911) was ClojureDart's `void` return from `is`/`expect` bound to `_` in a `let`; wrapping the assertions in `(do ... nil)` preserves every assertion without gating.

---

## Findings by Priority

No P0, P1, or P2 findings. Two non-blocking P3 observations for record hygiene:

- **P3-1 — `export/freeze` docstring omits `:ready-count` in its illustrative `:yin.k/state` map ([src/cljc/yin/vm/ucf/holder/export.cljc:347](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L347)) and UCF §7.7.4 ([docs/design/yin.vm.universal-continuation-format.md:1967](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.universal-continuation-format.md#L1967)).**
  - *Evidence:* [`recovery-metadata`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L323) and [`valid-state?`](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/holder/export.cljc#L412) include `:ready-count` (pinned by [`export_test.cljc:455`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/holder/export_test.cljc#L455)), while the docstring schematic at `export.cljc:347` and UCF line 1967 still list `{:gate :origins :issued :issues :children}`.
  - *Minimal fix (optional in 6e doc pass):* Add `:ready-count` to the schematic map in both doc blocks.
- **P3-2 — Pre-existing historical `:yin.k/not-quiescent` cross-reference in [docs/design/yin.vm.dependency-completion.md:292](file:///Users/sto/workspace/datomworld-srvm-6d-b/docs/design/yin.vm.dependency-completion.md#L292).**
  - *Evidence:* Line 292 of `yin.vm.dependency-completion.md` (outside 6d-b's owned doc files) parenthetically mentions `(:yin.k/not-quiescent otherwise, UCF §7.4.1)`.
  - *Minimal fix (optional in 6e doc pass):* Annotate or update alongside any future `dependency-completion.md` touch.

---

## Items for Fable at Next Gate

1. **Confirm the two ratified 6d-b judgment calls** (already aligned with plan v2.1 and confirmed by glm and this gate):
   - `:yin.k/steps-since-checkpoint` measures completed holder slices (`:completed-slices`), not guest instructions.
   - `tenure-is-rechecked-between-bare-effects-test` now asserts full post-replay machine equality (with the halted install child already retired by the completed sweep) rather than the pre-P2-8 unretired child.
2. **Compose-level `:yin.k/admitted-modules` forwarding ([src/cljc/yin/vm/ucf/compose.cljc:673](file:///Users/sto/workspace/datomworld-srvm-6d-b/src/cljc/yin/vm/ucf/compose.cljc#L673)):** `compose` now forwards `:yin.k/admitted-modules` alongside `:fuel` so composed holders can hand off and lower running install children ([`exclusive-fuel-boundary-and-running-install-child-handoff-test`](file:///Users/sto/workspace/datomworld-srvm-6d-b/test/yin/vm/ucf/compose_rows_test.cljc#L884-L923)).
3. **2× lift preflight cost in holder export (`export/prepare` + `export/encode`):** As noted in 6d-a, `rows/lift-task` performs a preflight lift; `export/prepare` and `export/encode` each invoke `lift-task`. Acceptable for correctness gates, worth keeping in mind if export latency is profiled in 6e or later.

