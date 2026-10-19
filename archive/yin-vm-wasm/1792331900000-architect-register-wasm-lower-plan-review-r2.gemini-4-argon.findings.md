# Round-2 Independent Second-Architect Review: Register-VM Wasm Lowering Plan (R2)

- **Reviewed Plan**: `collab/1792329904305-architect-register-wasm-lower-plan-r2.gpt-6-astra.findings.md`
- **Round-1 Review**: `collab/1792330570048-architect-register-wasm-lower-plan-review.gemini-4-argon.findings.md`
- **Reviewer**: `gemini-4-argon` (Architect)

---

## 1. Round-1 Finding Resolution Audit (Verified Against Body & Source Code)

Each of the seven Round-1 findings (`MAJOR-1`..`MAJOR-3`, `MINOR-1`..`MINOR-4`) was audited against both the body of `collab/1792329904305-architect-register-wasm-lower-plan-r2.gpt-6-astra.findings.md` (not merely the §0 revision log) and the current implementation in `src/cljc/yin/vm/`.

### MAJOR-1 — Empty-`K` `:return` and `:return-result` continuation restore `:pc` preservation
- **Status**: **RESOLVED**
- **Plan Body Locations**: §2.3 (`:return`, line 109), §2.4 (line 132), §3.2 step 5 (line 170), §6 W2 (line 265), §6 W3 (line 271).
- **Verification**:
  - `src/cljc/yin/vm/debruijn/register.cljc:428-436` (`return-value`): When `(:continuation state)` is `nil`, the transition produces `(assoc state :value retval :halted? true :parked? false)` while leaving `:pc` unchanged (`(:pc state)`, or `(:pc entry)` when driven from a `:return-result` restore).
  - `src/cljc/yin/vm/debruijn/register.cljc:455-468` (`restore-continuation-record`): The `:return-result` branch restores `:pc (:pc entry)`, `:frames (:frames entry)`, `:continuation (:continuation entry)`, `:store-of (:store-of entry)`, and `:halted? false` onto `base` before calling `(return-value ... value)`.
  - §2.3, §2.4, and §3.2 step 5 in the revised plan now specify this exact sequence and explicitly forbid clobbering `:pc` with `0` or advancing it on empty-`K` `:return`.

### MAJOR-2 — Bidirectional synchronization between Clojure VM map fields and wasm linear memory across engine/public seams
- **Status**: **RESOLVED**
- **Plan Body Locations**: §1.2.1 ("Mandatory map ↔ memory synchronization", lines 52–61), §1.3 (line 67), §4.1 (line 186), §6 W0 (line 254), §6 W1 (line 259), §6 W3 (line 273).
- **Verification**:
  - `src/cljc/yin/vm/debruijn/register.cljc:944-949` (`module/gc-roots`) and `src/cljc/yin/vm/engine.cljc:2587-2614, 2720` (`collect` during `:cell/new`): `engine/handle-effect` on `:cell/new` collects live heap cells by walking Clojure keys (`:registers`, `:frames`, `:continuation`, `:value`, `:store`, `:wait-set`, `:run-queue`, `:yin.k/puts`) on the `state` map passed to it.
  - `src/cljc/yin/vm/engine.cljc:2746` (`handle-effect`): Writes `:value` on `state'` and may update `:store`, `:id-counter`, `:wait-set`, `:run-queue`, `:parked?`, `:halted?`, and `:yin.k/*`.
  - `test/yin/vm/debruijn/register_test.cljc:444-445`: Callers mutate Clojure map keys (e.g., `(assoc vm3 :store {...})`) between public steps.
  - §1.2.1 now mandates the three-point synchronization contract: (1) export hot wasm state (`:pc`, `:registers`, `:frames`, `:continuation`, `:value`, `:halted?`, `:parked?`, `:store-of`) to the Clojure map before any `engine/*` call, (2) re-import any mutated Clojure map fields back into wasm state after `engine/*` returns, and (3) import caller-visible Clojure map keys on public entry (`vm/step`, `vm/run`, `vm/eval`, `restore-continuation`) and export before returning.

### MAJOR-3 — `:call` `:host-fn` effect completion contract (`payload-of`, `park-entry-fns`, blocked `:pc` advance, post-effect `state` preservation)
- **Status**: **RESOLVED**
- **Plan Body Locations**: §2.3 (`:call`, line 106), §2.4 ("Exact host-function effect completion", lines 125–135), §6 W3 (line 271).
- **Verification**:
  - `src/cljc/yin/vm/debruijn/register.cljc:533-545, 564-580, 709-719` and `src/cljc/yin/vm/module.cljc:579-584`:
    - `payload-of` is constructed at the call site with `:pc (inc site-pc)` and `:reg dst`.
    - `park-entry-fns` supplies `:stream/put`, `:stream/next`, and `:module/require` park entries, with `handle-host-effect` falling back to `(or (get park-entry-fns eff-type) (when (= :module/try-require eff-type) (:module/require park-entry-fns)) (when (= :stream/poll eff-type) (:stream/next park-entry-fns)))`.
    - On `:blocked? true`, `handle-host-effect` returns `(assoc res-state :pc (inc site-pc))` without calling `return-result`.
    - On `:parked? true`, `handle-host-effect` returns `res-state` directly.
    - On `:halted? true`, `handle-host-effect` returns `(assoc res-state :value value)` directly.
    - Otherwise, `return-result` writes `dst` or calls `return-value` on **`res-state`** (preserving all post-effect mutations to `:store`, `:id-counter`, `:wait-set`, `:run-queue`, `:yin.k/*`, etc.).
  - §2.4 captures every branch and fallback rule faithfully.

### MINOR-1 — Continuation invocation check order and foreign-continuation error keyword
- **Status**: **RESOLVED (with code-verified correction from Astra)**
- **Plan Body Locations**: §2.3 (`:call`, line 106), §3.2 (line 174), §6 W2 (line 265).
- **Verification**:
  - Astra's refutation of the error keyword in Round 1 is verified against `src/cljc/yin/vm/engine.cljc:78-95`: `engine/operator-kind` checks `(and (map? f) (= :continuation (:type f)))` and, when `(not= (:machine f) (:type state))`, throws `(ex-info "Cannot invoke foreign continuation" {:type :yin/uncaught-exception, :exception {:reason :foreign-value, :kind :continuation, ...}})`.
  - Next, `src/cljc/yin/vm/debruijn/register.cljc:724` calls `(engine/continuation-argument arg-vals)` (`src/cljc/yin/vm/engine.cljc:103-112`), which checks `(= 1 (count args))` and throws `:reason :continuation-arity`.
  - Finally, `src/cljc/yin/vm/debruijn/register.cljc:730-732` calls `(check-format! state (:format f))`, which throws `"Continuation format mismatch"` with `{:expected fmt, :actual format}` on mismatch.
  - §2.3, §3.2, and §6 W2 now state this exact three-step check order and exact error payloads.

### MINOR-2 — `:halt` and empty-`K` `:return` must preserve existing `:registers`, `:frames`, and `:continuation`
- **Status**: **RESOLVED**
- **Plan Body Locations**: §2.3 (`:return` line 109, `:halt` line 110), §2.4 (line 132), §6 W1 (line 259).
- **Verification**:
  - `src/cljc/yin/vm/debruijn/register.cljc:432` and `745-747` preserve `:registers`, `:frames`, `:continuation`, and `:pc` unchanged on empty-`K` `:return` and `:halt`, only updating `:value`, `:halted? true`, and `:parked? false`.
  - §2.3, §2.4, and §6 W1 now state this invariant explicitly.

### MINOR-3 — `effects/wait-entry-defect` scope and custody gate (`:yin.k/gate`) preservation
- **Status**: **RESOLVED (with two code-verified corrections from Astra)**
- **Plan Body Locations**: §1.2 (line 45), §4.2 (line 198), §4.2.1 ("Custody gates are preserved engine semantics", lines 200–210), §4.3 (lines 215–216), §6 W3 (line 273), §6 W4 (line 279).
- **Verification**:
  - `src/cljc/yin/vm/debruijn_register_effects.cljc:345-384`: `wait-entry-defect` validates queued `:wait-set` entries only (`:stream/put`, `:stream/next`, `:module/require`).
  - Astra's two refutations of Round-1 wording details are verified against `src/cljc/yin/vm/engine.cljc`:
    1. `engine/issue-number` (`engine.cljc:554-559`) increments `:yin.k/issued` (not `:yin.k/next-issue`) on `state` and stamps `:yin.k/issue n` on gated records.
    2. `engine/handle-effect` on `:stream/close` (`engine.cljc:2713-2716`) calls `(gate-refuse! state effect)` (`engine.cljc:484-492`, which refuses `:exporting` and `:ended`, returning `:running` when mode is `:running`) and then delegates to `handle-close` (`engine.cljc:744-761`), which under `:running` appends `{:stream-id stream-id, :yin.k/issue n}` to `:yin.k/closes` and returns `nil` without calling `stream/close!`.
  - §4.2.1 accurately documents all four `:yin.k/*` state keys (`:yin.k/gate`, `:yin.k/puts`, `:yin.k/closes`, `:yin.k/issued`) and exact gate behavior across `:running`, `:exporting`, and `:ended`.

### MINOR-4 — Module attachment base offsets, combined-segment payload reads, and empty-base rows
- **Status**: **RESOLVED**
- **Plan Body Locations**: §5 (lines 229, 232–234), §6 W4 (line 279).
- **Verification**:
  - `src/cljc/yin/vm/debruijn/register.cljc:135-146, 184-227, 525-530` and `src/cljc/yin/vm/debruijn_register_effects.cljc:97-120`:
    - `attach-module-code` records `:base-code (count cur-code)` and `:base-pool (count cur-pool)` (so attaching to an empty base yields `{:base-code 0, :base-pool 0}`), shifts module pool `:code` (`+ base-code`) and `:consts` (`+ base-pool`) only when `(or (pos? base-code) (pos? base-pool))`, and increments `:program-version`.
    - `fetch-pool` and `fetch-instr` index the combined `:pool` and `:bytecode` vectors at `(:pc state)`.
  - §5 and §6 W4 now match this exact attachment and segment-indexing contract.

---

## 2. Full-Document Consistency & Regression Check

A complete pass over §1–§7 of `collab/1792329904305-architect-register-wasm-lower-plan-r2.gpt-6-astra.findings.md` confirmed:
- No internal contradictions between the §0 revision log and the normative sections (§1–§7).
- The W0–W6 phase gates (§6) incorporate all new synchronization, restore, effect-completion, custody-gate, and module-attachment invariants into concrete parity assertions.
- No architectural drift or regressions were introduced during the Round-2 edits.

---

## Verdict

APPROVE

### Findings Remaining
None. All 3 MAJOR and 4 MINOR findings from Round 1 have been completely and accurately resolved in the plan body.

### Properties Passing Review
1. **Execution Boundary & Dual-Tier Dispatch**: `engine/run-loop` (`src/cljc/yin/vm/engine.cljc:2772-2797`) and `step-on-stream` (`src/cljc/yin/vm/debruijn/register.cljc:751-775`) cleanly accommodate a wasm inner hot-loop without forking scheduler, stream, store, or module state.
2. **Bidirectional Clojure-Map ↔ Linear-Memory Synchronization**: Export-before-engine-call, re-import-after-engine-call, and public-boundary import/export (§1.2.1) preserve `module/gc-roots` during `:cell/new` GC collection (`engine.cljc:2587-2614, 2720`), preserve `:value` and store/scheduler updates after `engine/handle-effect`, and honor caller `assoc` mutations between steps.
3. **Value Representation & Two-Level Memory Management**: The 64-bit NaN-boxed / tagged-handle value encoding and split between transient wasm arena allocations and Yin logical heap cells (`:heap`, `:cells`, `engine/collect`) preserve Clojure value equality and deterministic logical GC semantics.
4. **Bytecode & Instruction Semantics**: All 18 opcodes (`src/cljc/yin/vm/debruijn/register.cljc:622-749`), `resolve-Access` (`register.cljc:477-513`), `call-WithArgs` (`register.cljc:582-620`), `return-value` (`register.cljc:428-436`), and `handle-host-effect` (`register.cljc:533-580`) match the canonical interpreter transition-for-transition, including empty-`K` `:pc` and `:registers`/`:frames`/`:continuation` preservation and post-effect `res-state` propagation.
5. **Reified Continuations & Portable Format**: Continuation capture (`register.cljc:241-294`) and `restore-continuation-record` (`register.cljc:455-468`) preserve `{:type :continuation, :machine :yin.vm/debruijn-register, :format :debruijn-register-v1, :value payload}` bit-for-bit across `:call`, `:park`, and `:return-result` entries, including the exact continuation-invocation validation order (`:foreign-value` -> `:continuation-arity` -> `"Continuation format mismatch"`).
6. **FFI, Effect Handling, and Custody Gates**: Two-step `:ffi/call` (`register.cljc:691-705`, `ffi.cljc:307-351`), `debruijn_register_effects` validation (`wait-entry-defect` on `:wait-set`), and `:yin.k/gate` custody semantics (`:yin.k/puts`, `:yin.k/closes`, `:yin.k/issued` across `:running`, `:exporting`, and `:ended` in `engine.cljc:484-761, 2673-2716`) are preserved unchanged.
7. **Dynamic Module Attachment**: `attach-module-code` (`register.cljc:135-146, 184-227`) base-offset shifting, `:module-Table` recording (including `{:base-code 0, :base-pool 0}` on empty bases), combined-segment `:bytecode`/`:pool` indexing, and `:program-version` cache invalidation are preserved.
8. **Phased Delivery & Parity Gates (W0–W6)**: Each phase maintains green `clj -M:test` and `bb test:cljs` suites and enforces state-equivalence against the canonical interpreter.
