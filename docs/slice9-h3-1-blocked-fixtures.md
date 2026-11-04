# Slice 9 Hardening H3-1 — Blocked Kernel-Gate Fixture Diagnostic Packet

## Status & Governance Notice

- **Slice**: `H3-1` (blocked-fixture diagnosis under the approved Hardening-3 plan)
- **Branch / Base**: `hardening3-h3-1` based on `0805a25354fb04f90477db6f001bc49e3b84f26c` (`master`)
- **Production & Gate Source Changes**: **None** (`src/cljc/` untouched; `test/yang/latte_oracle_test.clj`, `kernel_gate.edn`, `slice9_evidence.edn`, `slice9_verification.edn`, and `:instruction-budget-per-lane 2000000` untouched). One untracked H3-0 resource file omitted from commit `1fd7236be` (`test/yang/latte_oracle/slice9_declarations.edn`) is restored so `yang.latte-oracle-test` loads cleanly in fresh worktrees (see §2.4).
- **Coverage & Gate Accounting**: Partial and extended-budget traces in this packet are **diagnostic evidence only** and contribute **zero** acceptance or arity coverage credit. The checked-in gate remains honestly `:blocked` (`100` `:pass`, `2` `:recorded-deviation`, `2` `:recorded-upstream-failure`, `2` `:blocked`; `107 / 165` credited arities, `58` `:unexercised`).

---

## Executive Summary & Ranked Root-Cause Hypotheses

Neither blocked fixture suffers from non-termination, unbounded recursion, a shared-runtime (`yin.vm` / `yang.call`) bug, or trace-instrumentation blowup. Both `:check-accepted` and `:check-assume-have-discharge` are deterministic, finite computations whose C lanes execute the exact same UAST and kernel arity sequences and agree with host Clojure (`A`) and the portable source reference (`B`) once given sufficient steps:

- **`:check-accepted`** (`720` traced kernel calls, `2,292` total §8.10 guest calls):
  - `:c-semantic` **completes at `1,207,270` steps** (within the `2,000,000` budget) and agrees with `A` and `B`.
  - `:c-walker` reaches **`693 / 720` kernel calls (`96.25%`)** at `2,000,000` steps (already inside the final NbE quotation of `gate-norm-beta-eq?`) and **completes at `2,086,120` steps** (`+86,120` steps / `+4.31%` past `2,000,000`), agreeing with `A`, `B`, and `:c-semantic`.
- **`:check-assume-have-discharge`** (`2,360` traced kernel calls, `9,598` total §8.10 guest calls):
  - `:c-semantic` progresses monotonically (`927` calls at `2M` -> `1,924` calls at `4M`) and **completes at `5,030,283` steps** under the `8M` trial, agreeing with `A` and `B`.
  - `:c-walker` progresses monotonically through the identical call sequence (`459` calls at `2M` -> `1,120` calls at `4M` -> **`2,219 / 2,360` calls [`94.03%`]** at `8M`, already inside the 4th and final `gate-norm-beta-eq?` with only `141` traced calls left). Applying the invariant `1.7280x` walker-to-semantic step multiplier measured across all completing proof fixtures places `:c-walker` completion at **`~8,692,000` steps** (`~692k` steps above the authorized `8M` diagnostic ceiling).

### Root-Cause Hypotheses Ranked by Quantitative Evidence

| Rank | Hypothesis | Share of Measured Cost | Verdict & Mechanism |
| :--- | :--- | :--- | :--- |
| **H1 (Primary)** | **Clojure §8.10 Common-Call + Outcome Per-Call Lowering Amplification (`src/cljc/yang/clojure.cljc`)** | **`95.1% – 95.7%`** of all execution steps (`4,764,636 / 5,009,459` semantic insts on `:check-assume-have-discharge`) | **CONFIRMED PRIMARY CAUSE.** Adopting outcome-wrapped §8.10 common-call descriptors + invoker routing (`ea18c8e47`) raised per-call cost from **`36 -> 538` semantic steps (`14.94x`)** and **`65 -> 934` walker steps (`14.37x`)** for non-tail calls (`37 -> 499` / `69 -> 860` for tail calls). Every call — including intra-unit calls to known `defn`s and `yang/library` sequence helpers — re-mints a fresh admission witness closure and runs structural closure equality (`dsg__admitted?`), builds a 4-key invocation map via nested `conj` closures (`dsg__assoc`), re-validates that compiler-built map (`yang.call/valid-invocation?`), captures a continuation (`u/capture`) on non-tail calls, and unwraps/re-wraps `[:yin.k/ok v]` twice. Actual LaTTe kernel bodies execute in **`32,700` semantic instructions (`0.65%`)**. |
| **H2 (Secondary)** | **Tree-Walker vs Register-VM Step Granularity (`1.7280x` Invariant Ratio)** | **`+72.8%`** walker steps over semantic steps on identical UAST | **CONFIRMED CAUSE OF `:check-accepted` LANE SPLIT.** `yin.vm.semantic` executes flat register instructions (`1` `:call` instruction evaluates operator + register args; `1` `:branch-false` tests condition). `yin.vm.ast-walker` counts separate CESK steps for `:application` (`:eval-operator`), each `:eval-operand` transition, `:variable` lookups, and `:if` (`:eval-test`). Across all fixtures, `:c-walker` deterministically consumes **`1.7277x – 1.7280x`** steps per `:c-semantic` instruction (`1,207,270 × 1.7280 = 2,086,120`), pushing `:check-accepted` just `+4.31%` over `2,000,000` on `:c-walker` while `:c-semantic` finishes with `792,730` steps to spare. |
| **H3 (Minor / Observability)** | **Single-Batch Setup Accounting & Missing `:consumer` on Exhaustion (`test/yang/clojure_test.clj`)** | **`1.04% – 1.79%`** of `2M` budget (`20.7k` semantic / `35.9k` walker setup steps) | **CONFIRMED MINOR CONTRIBUTOR + HARNESS DEFECT.** `run-bounded` resets `n = 0` per `(run consumer)` invocation, but `queue-ast!` / `queue-rows!` appends the whole compiled unit in one batch (`:run-calls = 1`), charging setup (`~35.8k` walker / `~20.8k` semantic steps) against the `2M` lane budget. Separately, `run-bounded` throws budget exhaustion without `:consumer v`, causing `observer/consumer-left-by-run` to report the pre-run `loaded` VM (`step 0, :k nil`) instead of the exhausted VM state. |
| **H4 (Falsified)** | **Infinite Loop / Shared-Runtime Bug / Trace-Stream Overhead** | **`0.28% – 0.38%`** trace overhead (`6` semantic / `11` walker steps per traced call) | **FALSIFIED.** Both fixtures halt with exact `A = B = C` agreement; continuation depth remains bounded (`23 – 48` frames); uninstrumented runs save only `4,330 – 14,170` semantic steps (`7,936` walker steps) and leave every `2M / 4M / 8M` terminal disposition unchanged. |

**Recommended Next Round**: **`compiler fix`** (paired with a 1-line **`harness fix`** in `test/yang/clojure_test.clj:1946` to preserve `:consumer v` on exhaustion, plus tracking `test/yang/latte_oracle/slice9_declarations.edn`). Reducing per-call overhead for intra-unit / statically admitted Clojure definitions brings `:check-assume-have-discharge` well under `1,000,000` steps on both lanes without inflating the `2,000,000` gate budget or masking a `14.9x` call-bridge overhead. Full contingent **`budget proposal`** measurements are also provided in §6.2 for architect review.

---

## 1. Pinned Identities, Four-Cell Comparison, and Budget Trial Matrix

### 1.1 Pinned Source, Compiler, and Runtime Identities

| Artifact / Property | Pinned Value |
| :--- | :--- |
| **Git Base Commit (`master` / `hardening3-h3-1`)** | `0805a25354fb04f90477db6f001bc49e3b84f26c` |
| **Upstream `latte-kernel` Revision** | `86fca0d1d054af005cb37395eae04500d45e6279` |
| **Gate Adaptation Version** | `{:context 2, :kernel-assembly 4}` |
| **Configured Gate Budget** | `:instruction-budget-per-lane 2000000` (`test/yang/latte_oracle_test.clj:1895`) |
| **Compiler Convention** | `{:outcomes true, :invoker true}` (`arg-vector-opts` + `stream/*` native profiles when instrumented) |
| **Host Runtime** | Clojure `1.12.4`, Java `17.0.18` (`OpenJDK 64-Bit Server VM`), `Mac OS X` (`aarch64`) |
| `src/cljc/yang/clojure.cljc` (SHA-256) | `19328ce01228a800284d7d2cd52d7073f1d7923c7482d46fe9c7df6cb08728a3` |
| `src/cljc/yang/core/call.cljc` (SHA-256) | `51b2f6ecc2b184e7869ddbb8d87dde00d78e6d239258e4e607ddcbdc7bf982d9` |
| `src/cljc/yin/vm/ast_walker.cljc` (SHA-256) | `7635ea06e2e1f46c96acc5dae31bd2b4bf63b612ec11e65b4c70c5a37ab754d5` |
| `src/cljc/yin/vm/semantic.cljc` (SHA-256) | `32976cf08f911f1189eddcd632ea09bbc9680c8cdcbc129902efe1c0255a7e9e` |
| `src/cljc/yin/vm/semantic/linearize.cljc` (SHA-256) | `ac2a862684a96b30cc5e53daa61b18bf58af0a0561c6ba3b3c019ae37c1e53b7` |
| `src/cljc/dao/stream/observer.cljc` (SHA-256) | `43020d893eca069fdd10da392474b1c0266a97093bcf8b0135d9d32e0ca14d26` |
| `test/yang/latte_oracle_test.clj` (SHA-256) | `d4655872d46defd3a99de52c11e2fa194ca19b02b1b500e702c106b5cf02bad4` |
| `test/yang/clojure_test.clj` (SHA-256) | `7443cd4dbdbf574d76297a28bcc2fa2a4de5556d1ed552231971c829eba7405d` |
| `test/yang/latte_oracle/kernel_gate.edn` (SHA-256) | `f8ea00c01080359e471bbf66fbfbf095dff76c905ec0ecb4ed38c800d15748a4` |
| `test/yang/latte_oracle/slice9_declarations.edn` (SHA-256) | `7fa05e988b4ce6248fefaf68c0dac6d6490b4b279e574b0b93c32b176530d53a` |

### 1.2 Four-Cell Comparison at the Configured 2,000,000 Gate Budget (Instrumented Gate Mode)

Both fixtures compile `48` `yang/library` definitions plus their static kernel transitive closure (`143` kernel defs / `215,882` AST nodes for `:check-accepted`; `144` kernel defs / `216,917` AST nodes for `:check-assume-have-discharge`). `A` and `B` complete in `< 25 ms` on both fixtures (`[:ok [(λ [A ✳] (λ [x A] x)) (Π [A ✳] (Π [x A] A))]]` for `:check-accepted`; `[:ok [(<a>) (Π [A ✳] (Π [B ✳] (Π [f (Π [⇧ A] B)] (Π [x A] (Π [⇧ A] B)))))]]` for `:check-assume-have-discharge`).

| Cell (`Fixture × Evaluator`) | Mode | Configured Budget | Actual Steps Consumed | Setup / Exec Split | Traced Calls (`Distinct Arities`) | Terminal Reason | Elapsed (`compile / queue+load / run / total` ms) |
| :--- | :--- | ---: | ---: | ---: | ---: | :--- | ---: |
| **`:check-accepted` × `:c-walker`** | Instrumented | `2,000,000` | **`2,000,000`** | `35,707` / `1,964,293` | `693 / 720` (`46`) | `:budget-exhausted` (`Guest instruction budget exhausted`) | `99.3 / 438.8 / 8,625.1 / 9,164.5` |
| **`:check-accepted` × `:c-semantic`** | Instrumented | `2,000,000` | **`1,207,270`** | `20,715` / `1,186,555` | `720 / 720` (`49`) | **`:completed`** (`halted? true`, `A=B=C` exact match) | `99.3 / 12,415.2 / 2,785.1 / 19,814.3`* |
| **`:check-assume-have-discharge` × `:c-walker`** | Instrumented | `2,000,000` | **`2,000,000`** | `35,895` / `1,964,105` | `459 / 2,360` (`57`) | `:budget-exhausted` (`Guest instruction budget exhausted`) | `88.2 / 477.8 / 8,636.1 / 9,203.1` |
| **`:check-assume-have-discharge` × `:c-semantic`** | Instrumented | `2,000,000` | **`2,000,000`** | `20,824` / `1,979,176` | `927 / 2,360` (`87`) | `:budget-exhausted` (`Guest instruction budget exhausted`) | `88.2 / 12,752.7 / 4,638.2 / 24,672.0`* |

*\*Note on `:c-semantic` `total` ms in diagnostic profiling runs: `total` includes post-run PC-owner table construction (`~4.5–7.1 s`); raw VM execution (`run`) is `2.79 s` (`2.31 µs/step`) vs `:c-walker`'s `8.63 s` (`4.31 µs/step`). `queue+load` on `:c-semantic` (`~12.4 s`) reflects AST-to-datom row projection (`524k–527k` datoms) and linearization (`230,449` / `231,547` instructions).*

### 1.3 Authorized Finite Diagnostic Budget Trials (`2M`, `4M`, `8M`) — Instrumented & Uninstrumented

Trials stopped per cell as soon as completion was observed (`4M` for `:check-accepted` `:c-walker`; `2M` for `:check-accepted` `:c-semantic`; `8M` for both `:check-assume-have-discharge` lanes).

| Fixture | Lane | Mode | Trial Budget | Actual Steps | Setup Steps | Exec Steps | Traced Calls | Progress (`%` of Calls) | Terminal Status | Run Time (`ms`) |
| :--- | :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | :--- | ---: |
| `:check-accepted` | `:c-walker` | Inst | `2,000,000` | `2,000,000` | `35,707` | `1,964,293` | `693` | `96.25%` | `:budget-exhausted` | `8,625.1` |
| `:check-accepted` | `:c-walker` | Inst | `4,000,000` | **`2,086,120`** | `35,707` | `2,050,413` | **`720`** | **`100.00%`** | **`:completed` (`A=B=C`)** | `9,056.2` |
| `:check-accepted` | `:c-walker` | Uninst | `2,000,000` | `2,000,000` | `35,691` | `1,964,309` | — | `~96.62%` | `:budget-exhausted` | `8,467.6` |
| `:check-accepted` | `:c-walker` | Uninst | `4,000,000` | **`2,078,184`** | `35,691` | `2,042,493` | — | **`100.00%`** | **`:completed` (`A=B=C`)** | `8,634.5` |
| `:check-accepted` | `:c-semantic` | Inst | `2,000,000` | **`1,207,270`** | `20,715` | `1,186,555` | **`720`** | **`100.00%`** | **`:completed` (`A=B=C`)** | `2,785.1` |
| `:check-accepted` | `:c-semantic` | Uninst | `2,000,000` | **`1,202,940`** | `20,705` | `1,182,235` | — | **`100.00%`** | **`:completed` (`A=B=C`)** | `2,690.1` |
| `:check-assume-have-discharge` | `:c-walker` | Inst | `2,000,000` | `2,000,000` | `35,895` | `1,964,105` | `459` | `19.45%` | `:budget-exhausted` | `8,636.1` |
| `:check-assume-have-discharge` | `:c-walker` | Inst | `4,000,000` | `4,000,000` | `35,895` | `3,964,105` | `1,120` | `47.46%` | `:budget-exhausted` | `17,598.7` |
| `:check-assume-have-discharge` | `:c-walker` | Inst | `8,000,000` | `8,000,000` | `35,895` | `7,964,105` | `2,219` | **`94.03%`** | `:budget-exhausted` | `34,566.8` |
| `:check-assume-have-discharge` | `:c-walker` | Uninst | `2,000,000` | `2,000,000` | `35,879` | `1,964,121` | — | `~19.5%` | `:budget-exhausted` | `8,691.6` |
| `:check-assume-have-discharge` | `:c-walker` | Uninst | `4,000,000` | `4,000,000` | `35,879` | `3,964,121` | — | `~47.6%` | `:budget-exhausted` | `17,015.5` |
| `:check-assume-have-discharge` | `:c-walker` | Uninst | `8,000,000` | `8,000,000` | `35,879` | `7,964,121` | — | **`~94.3%`** | `:budget-exhausted` | `33,754.2` |
| `:check-assume-have-discharge` | `:c-semantic` | Inst | `2,000,000` | `2,000,000` | `20,824` | `1,979,176` | `927` | `39.28%` | `:budget-exhausted` | `4,638.2` |
| `:check-assume-have-discharge` | `:c-semantic` | Inst | `4,000,000` | `4,000,000` | `20,824` | `3,979,176` | `1,924` | `81.53%` | `:budget-exhausted` | `9,123.8` |
| `:check-assume-have-discharge` | `:c-semantic` | Inst | `8,000,000` | **`5,030,283`** | `20,824` | `5,009,459` | **`2,360`** | **`100.00%`** | **`:completed` (`A=B=C`)** | `11,465.0` |
| `:check-assume-have-discharge` | `:c-semantic` | Uninst | `2,000,000` | `2,000,000` | `20,814` | `1,979,186` | — | `~39.4%` | `:budget-exhausted` | `4,613.2` |
| `:check-assume-have-discharge` | `:c-semantic` | Uninst | `4,000,000` | `4,000,000` | `20,814` | `3,979,186` | — | `~81.8%` | `:budget-exhausted` | `8,564.7` |
| `:check-assume-have-discharge` | `:c-semantic` | Uninst | `8,000,000` | **`5,016,113`** | `20,814` | `4,995,299` | — | **`100.00%`** | **`:completed` (`A=B=C`)** | `11,089.9` |

### 1.4 Neighboring Completing Proof Fixtures (Context for the `2,000,000` Cliff)

| Neighbor Fixture | Proof / Theorem Difference vs `:check-accepted` | `:c-walker` Steps | `:c-semantic` Steps | Traced Calls | `Walker / Semantic` Ratio |
| :--- | :--- | ---: | ---: | ---: | ---: |
| `:elab-accepted` | Same proof `((λ [A :type] (λ [x A] x)))`, calls `elab-proof` only (skips theorem type parse + final `beta-eq?`) | `1,007,668` | `583,252` | `403` | **`1.7277x`** |
| `:check-rejected` | Same proof, `check-proof` against 1-binder theorem `'(Π [A ✳] A)` instead of 2-binder `'(Π [A ✳] (Π [x A] A))` | `1,728,338` | `1,000,259` | `616` | **`1.7279x`** |
| **`:check-accepted`** | Same proof, `check-proof` against 2-binder theorem `'(Π [A ✳] (Π [x A] A))` (`+104` kernel calls in second `Π` binder typing & NbE `beta-eq?`) | **`2,086,120`** | **`1,207,270`** | **`720`** | **`1.7280x`** |

`:check-rejected` already consumes `86.4%` of the `2,000,000` walker budget; checking a second `Π` binder in `:check-accepted` adds `104` kernel calls (`+357,782` walker steps) and crosses `2,000,000` by `86,120` steps.

---

## 2. Aggregate Cost, Phase Boundaries, and Observer Step-Counter Audit

### 2.1 Observer Runner Step-Counter Audit (`dao.stream.observer` + `run-bounded`)

We inspected `src/cljc/dao/stream/observer.cljc` (`run-on-stream`, lines `199–247`) and `test/yang/clojure_test.clj` (`run-guest-machine`, lines `1929–1963`) and ran an empirical multi-batch probe:

1. **Does `run-bounded` reset its step counter across consumer `(run ...)` invocations?**
   - **Yes in `run-bounded` (`clojure_test.clj:1942–1948`)**: `(loop [v machine, n 0] ...)` binds `n` locally to `0` on each invocation of `run`. In `observer/run-on-stream`, each time `ready?` returns true and `stream/next` yields `{:ok batch}`, `observer` calls `(load consumer batch)` and `(run loaded)`. Our two-batch probe queued two 10-step programs across separate ingress turns under `step-limit 12`: `steps-per-run` recorded `[10 10]` (`total = 20`) and **completed without budget exhaustion** because `n` reset to `0` on the second batch.
   - **However, in `kernel-c`, `:run-calls` is always `1`**: `tu/queue-ast!` (`walker`) and `tu/queue-rows!` (`semantic`) deposit the entire compiled program (`prelude + yang.call + yang/library + kernel-defs + fixture-call`) as **one** ingress batch (`:load-calls 1`, `:run-calls 1`). Therefore, for every kernel-gate fixture, the reported `2,000,000` count **is** the exact single-invocation whole-fixture total (`setup + execution`).
2. **Harness Observability Defect on Exhaustion (`clojure_test.clj:1946` vs `observer.cljc:192–196`)**:
   - `run-bounded` throws `(ex-info "Guest instruction budget exhausted" {:lane lane :steps n})` **without** `:consumer v` in `ex-data`.
   - `observer/consumer-left-by-run` checks `(let [data (ex-data e)] (if (and (map? data) (contains? data :consumer)) (:consumer data) fallback))`, where `fallback` is `loaded` (the VM *before* `(run loaded)` started, at step `0` with `:k nil`!).
   - Consequently, inspecting `(get-in (ex-data e) [:session :consumer])` on a stock `run-guest-machine` timeout returns the **initial unexecuted VM** (`:k nil`, `:control {:type :application}`) rather than the VM state at step `2,000,000`. Passing `{:lane lane :steps n :consumer v}` fixes this cleanly without affecting gate semantics.

### 2.2 Exact Setup vs Execution Phase Boundaries (Step Numbers)

Phase boundaries were recorded by monitoring VM `:store` population and the `gate-execution-trace` stream at every step:

| Phase Milestone | `:check-accepted` (`:c-walker`) | `:check-accepted` (`:c-semantic`) | `:check-assume-have-discharge` (`:c-walker`) | `:check-assume-have-discharge` (`:c-semantic`) |
| :--- | ---: | ---: | ---: | ---: |
| **1. `dsg__*` + `yang.call/*` prelude loaded** (`yang.call/thrown` in `:store`) | `108` | `66` | `108` | `66` |
| **2. `yang/library` (`48` defs, `dsg__assoc` + witness minting per def) loaded** | `9,159` | `5,320` | `9,159` | `5,320` |
| **3. `gate-execution-trace` stream created** | `9,175` | `5,330` | `9,175` | `5,330` |
| **4. All `143 / 144` kernel `gate-*` descriptors minted & stored (Setup Complete)** | **`35,707`** (`1.79%` of 2M) | **`20,715`** (`1.04%` of 2M) | **`35,895`** (`1.79%` of 2M) | **`20,824`** (`1.04%` of 2M) |
| **5. `kernel-context` (`env` construction) entered** | `36,536` | `21,196` | `36,724` | `21,305` |
| **6. Theorem `presyntax/parse-term` entered** (only in `have-discharge`; quoted AST in `accepted`) | — | — | `37,655` | `21,841` |
| **7. `gate-proof-check-proof` entered** | `37,529` | `21,769` | `388,994` | `224,911` |
| **8. `gate-proof-compile-proof` entered** | `58,195` | `33,716` | `431,425` | `249,431` |
| **9. `gate-proof-elab-proof` entered** | `110,725` | `64,079` | `968,309` | `559,035` |
| **10. `[gate-proof-elab-have 8]` `#1` (`<a>`) entered** | — | — | `1,968,853` (call `#445`) | `1,137,620` (call `#445`) |
| **11. `[gate-proof-elab-have 8]` `#2` (`<b>`) entered** | — | — | `3,424,782` (call `#913`) | `1,979,658` (call `#913`) |
| **12. `[gate-proof-elab-discharge 6]` (`×4`: `x`, `f`, `B`, `A`) entered** | — | — | `4,549,964 .. 5,134,105` (`#1295..#1439`) | `2,630,606 .. 2,968,457` (`#1295..#1439`) |
| **13. `[gate-proof-elab-qed 4]` entered** | `290,654` (`#47`) | `168,116` (`#47`) | `5,455,318` (`#1511`) | `3,154,202` (`#1511`) |
| **14. Theorem `[gate-typing-rebuild-type 3]` entered** | `1,072,220` (`#415`) | `620,567` (`#415`) | `5,763,120` (`#1590`, 7th call) | `3,332,211` (`#1590`, 7th call) |
| **15. Final `[gate-norm-beta-eq? 4]` entered** | `1,606,415` (`#608`) | `929,720` (`#608`) | `7,331,479` (`#2096`, 4th call) | `4,239,549` (`#2096`, 4th call) |
| **16. Halted (`[:yin.k/ok [:ok ...]]`)** | **`2,086,120`** (`#719`) | **`1,207,270`** (`#719`) | *(proj. `~8,692,000`)* | **`5,030,283`** (`#2359`) |

**Key Takeaway**: Loading and descriptor minting for all `191–192` guest functions consumes only **`35,707 – 35,895` walker steps (`1.79%`)** and **`20,715 – 20,824` semantic steps (`1.04%`)**. Over **`98.2%`** of all steps occur during fixture execution after every definition is already in `:store`.

### 2.3 Where Inter-Call Steps Go (`Top Inter-Call Gaps`)

The largest step gaps between consecutive traced `gate-*` calls occur when kernel functions loop over guest collections via `yang/library` (`map`, `reduce`, `reverse`, `seq`, `rest`, `cons`, `into`, `contains?`):

- `[gate-proof-compile-proof 2] -> [gate-proof-compile-proof 2]` (call `#97 -> #98` in `:check-assume-have-discharge`): **`134,768` walker steps / `77,726` semantic steps** without a single kernel call — spent entirely in `yang/library` sequence manipulation (`seq`, `first`, `rest`, `map`, `reverse`, `into`) compiling the 8-step proof script (`4` `:assume`, `2` `:have`, `1` `:qed` across nested subproofs).
- `[gate-presyntax-reserved-symbol? 1] -> [gate-proof-check-proof 5]` (call `#68 -> #69`): **`92,079` walker / `53,170` semantic steps** returning unwound outcome tuples up the parsed theorem `(forall [A :type] (forall [B :type] (forall [f (==> A B)] (forall [x A] (==> A B)))))`.

### 2.4 Incidental H3-0 Packaging Finding (`slice9_declarations.edn`)

When running `clojure -M:test -n yang.latte-oracle-test -v yang.latte-oracle-test/test-kernel-differential-gate` on a clean worktree from `0805a2535`, namespace load initially failed at `test/yang/latte_oracle_test.clj:1939` (`(io/resource "yang/latte_oracle/slice9_declarations.edn")` returned `nil`). Inspection of commit `1fd7236be` (`c2f8c907..e4d33ce0`) showed that H3-0 staged the 4 modified tracked files (`known-gaps.md`, `slice9_evidence.edn`, `slice9_gate.md`, `latte_oracle_test.clj`) while leaving the newly created `test/yang/latte_oracle/slice9_declarations.edn` untracked in the author's worktree. Reconstructing `test/yang/latte_oracle/slice9_declarations.edn` verbatim from the load-time contract (`latte_oracle_test.clj:1939–2186`) and `slice9_evidence.edn` passed all `32` assertions in `test-kernel-acceptance-counting` and `test-kernel-gate-evidence-writer-round-trip` (exact replay match against checked-in `slice9_evidence.edn`).

*(Note on CLI invocation: `clojure -M:test -n yang.latte-oracle-test -v yang.latte-oracle-test/test-kernel-differential-gate` requires `-n yang.latte-oracle-test` in fresh worktrees where `build/antlr/*/classes` has not been pre-compiled, per `docs/agents/build-n-test.md:269`.)*

---

## 3. Bounded Snapshots at Budget Exhaustion

All snapshots were captured via bounded VM accessors (`vm/control`, `vm/continuation`, local `engine/without-store-of` key sets, and the `gate-execution-trace` ringbuffer stream) with zero host-session or closure-environment serialization.

### 3.1 Cell 1: `:check-accepted` × `:c-walker` at `2,000,000` Steps (`96.25%` Complete)

- **State & Progress**: `halted? false`, `blocked? false`, `:store` defs = `205`, traced calls = **`693 / 720`** (`46` distinct arities). Already entered final `[gate-norm-beta-eq? 4]` at step `1,606,415` (call `#608`); only `27` NbE quotation calls (`86,120` walker steps) remain before halting.
- **Control Position**: `:evaluated-value` (`nil`), local `:env` keys `[k m]` (returning from `dsg__get` inside `yang.call/entry` -> `yang.call/eligible?` -> `yang.call/invoke` while invoking a `yang/library` helper inside `gate-nbe-quotation-`).
- **Continuation Stack**: Depth **`28`** (`{:eval-operand 24, :eval-operator 1, :eval-test 3}`).
  - Frames `0..6`: `dsg__get` (`[k m]`) -> `yang.call/_descriptor` -> `yang.call/entry` -> `yang.call/eligible?` (`[callable descriptor ...]`) -> `yang.call/invoke` (`[callable named positional raise ...]`).
  - Frames `7..9`: `compile-guest-call` escape bridge (`[... coll f res s val dsg__loop0 dsg__raise_escape]`) inside `yang/library` `reduce`/`map` -> `call-entry` (`[dsg__body dsg__invocation ...]`).
  - Frames `10..11`: `gate-nbe-quotation-` (`[binder f level t tx x x' ...]`).
- **Top-15 Executed Kernel Call Frequencies at Exhaustion**:
  `[[gate-syntax-binder? 1] 79]`, `[[gate-syntax-app? 1] 60]`, `[[gate-syntax-kind? 1] 60]`, `[[gate-syntax-variable? 1] 47]`, `[[gate-syntax-type? 1] 40]`, `[[gate-nbe-evaluation 4] 36]`, `[[gate-nbe-quotation- 2] 36]`, `[[gate-syntax-ascription? 1] 30]`, `[[gate-syntax-ref? 1] 30]`, `[[gate-typing-type-of-term 3] 28]`, `[[gate-nbe-evaluation 3] 24]`, `[[gate-nbe-norm 3] 24]`, `[[gate-nbe-quotation 1] 24]`, `[[gate-norm-beta-delta-normalize 3] 24]`, `[[gate-norm-normalize 3] 24]`.
- **Ordered Tail of Executed-Call Stream (Last 25 of 693)**:
  ```edn
  [{:idx 668, :step 1868327, :call [gate-syntax-app? 1]}
   {:idx 669, :step 1870100, :call [gate-syntax-variable? 1]}
   {:idx 670, :step 1873125, :call [gate-utils-map-with 3]}
   {:idx 671, :step 1891163, :call [gate-nbe-quotation- 2]}
   {:idx 672, :step 1891964, :call [gate-syntax-binder? 1]}
   {:idx 673, :step 1896283, :call [gate-syntax-app? 1]}
   {:idx 674, :step 1898059, :call [gate-syntax-ref? 1]}
   {:idx 675, :step 1901554, :call [gate-syntax-ascription? 1]}
   {:idx 676, :step 1914165, :call [gate-nbe-quotation- 2]}
   {:idx 677, :step 1914966, :call [gate-syntax-binder? 1]}
   {:idx 678, :step 1933776, :call [gate-nbe-evaluation 4]}
   {:idx 679, :step 1935688, :call [gate-syntax-binder? 1]}
   {:idx 680, :step 1940007, :call [gate-syntax-app? 1]}
   {:idx 681, :step 1941780, :call [gate-syntax-variable? 1]}
   {:idx 682, :step 1943640, :call [gate-utils-map-with 3]}
   {:idx 683, :step 1961678, :call [gate-nbe-quotation- 2]}
   {:idx 684, :step 1962479, :call [gate-syntax-binder? 1]}
   {:idx 685, :step 1966798, :call [gate-syntax-app? 1]}
   {:idx 686, :step 1968574, :call [gate-syntax-ref? 1]}
   {:idx 687, :step 1972069, :call [gate-syntax-ascription? 1]}
   {:idx 688, :step 1984680, :call [gate-nbe-quotation- 2]}
   {:idx 689, :step 1985481, :call [gate-syntax-binder? 1]}
   {:idx 690, :step 1989800, :call [gate-syntax-app? 1]}
   {:idx 691, :step 1991576, :call [gate-syntax-ref? 1]}
   {:idx 692, :step 1995071, :call [gate-syntax-ascription? 1]}]
  ```

### 3.2 Cell 2: `:check-accepted` × `:c-semantic` at `2,000,000` Budget (**Completes at `1,207,270`**)

- **State & Terminal Outcome**: `halted? true`, `blocked? false` at step **`1,207,270`** (`20,715` setup + `1,186,555` exec), `:store` defs = `205`, traced calls = **`720 / 720`** (`49` distinct arities).
- **Return Value**: `[:yin.k/ok [:ok [(λ [A ✳] (λ [x A] x)) (Π [A ✳] (Π [x A] A))]]]` — exact value and `result-shape` agreement with `A`, `B`, and `4M` `:c-walker`.

### 3.3 Cell 3: `:check-assume-have-discharge` × `:c-walker` at `2,000,000` (Plus `4M` & `8M` Progression)

- **At `2,000,000` Steps (`19.45%` Complete)**:
  - **State**: `halted? false`, `blocked? false`, `:store` defs = `206`, traced calls = **`459 / 2,360`** (`57` distinct arities). Entered first `:have <a>` (`[gate-proof-elab-have 8]`) at step `1,968,853` (call `#445`), currently normalizing `<a>`'s term type via `gate-nbe-evaluation`.
  - **Control Position**: AST node `{:type :variable, :name conj, :tail? false}` inside `dsg__assoc` (`:env` keys `[k k2 k3 m v v2 v3]`) while building a §8.10 invocation / outcome record.
  - **Continuation Stack**: Depth **`32`** (`{:eval-operand 31, :eval-operator 1}`). Frames alternate between `dsg__assoc` (`[k k2 k3 m v v2 v3]`), `call-entry` (`[dsg__body dsg__invocation ...]`), `compile-guest-call` escape bridges (`[... dsg__callee dsg__arguments dsg__raise_escape]`), and `gate-nbe-evaluation` / `gate-typing-type-of-term` (`[ctx def-env fn-env t]`, `[ctx def-env ty x]`).
  - **Top-15 Call Frequencies (`2M`)**:
    `[[gate-syntax-kind? 1] 30]`, `[[gate-syntax-binder? 1] 23]`, `[[gate-presyntax-kind? 1] 20]`, `[[gate-presyntax-parse-term 3] 20]`, `[[gate-presyntax-type? 1] 20]`, `[[gate-syntax-app? 1] 20]`, `[[gate-syntax-variable? 1] 19]`, `[[gate-syntax-type? 1] 18]`, `[[gate-typing-ctx-fetch 2] 16]`, `[[gate-typing-type-of-term 3] 14]`, `[[gate-presyntax-reserved-symbol? 1] 13]`, `[[gate-nbe-evaluation 4] 12]`, `[[gate-nbe-quotation- 2] 11]`, `[[gate-presyntax-parse-symbol-term 3] 11]`, `[[gate-nbe-evaluation 3] 10]`.
  - **Ordered Tail of Executed-Call Stream at `2M` (Last 25 of 459)**:
    ```edn
    [{:idx 434, :step 1920179, :call [fetch-definition 3]}
     {:idx 435, :step 1952826, :call [gate-presyntax-parse-term 2]}
     {:idx 436, :step 1953702, :call [gate-presyntax-parse-term 3]}
     {:idx 437, :step 1954503, :call [gate-presyntax-kind? 1]}
     {:idx 438, :step 1956227, :call [gate-presyntax-type? 1]}
     {:idx 439, :step 1960634, :call [gate-presyntax-parse-symbol-term 3]}
     {:idx 440, :step 1961435, :call [gate-presyntax-reserved-symbol? 1]}
     {:idx 441, :step 1964072, :call [gate-defenv-registered-definition? 2]}
     {:idx 442, :step 1964922, :call [registered-definition? 2]}
     {:idx 443, :step 1965798, :call [registered-definition? 3]}
     {:idx 444, :step 1966641, :call [fetch-definition 3]}
     {:idx 445, :step 1968853, :call [gate-proof-elab-have 8]}
     {:idx 446, :step 1969745, :call [gate-proof-elab-have-impl 8]}
     {:idx 447, :step 1970572, :call [gate-typing-type-of-term 3]}
     {:idx 448, :step 1971376, :call [gate-syntax-kind? 1]}
     {:idx 449, :step 1972261, :call [gate-syntax-type? 1]}
     {:idx 450, :step 1973146, :call [gate-syntax-variable? 1]}
     {:idx 451, :step 1974878, :call [gate-typing-type-of-var 3]}
     {:idx 452, :step 1975706, :call [gate-typing-ctx-fetch 2]}
     {:idx 453, :step 1984768, :call [gate-typing-ctx-fetch 2]}
     {:idx 454, :step 1995516, :call [gate-norm-normalize 3]}
     {:idx 455, :step 1996379, :call [gate-norm-beta-delta-normalize 3]}
     {:idx 456, :step 1997269, :call [gate-nbe-norm 3]}
     {:idx 457, :step 1998109, :call [gate-nbe-evaluation 3]}
     {:idx 458, :step 1999029, :call [gate-nbe-evaluation 4]}]
    ```
- **Progression at `4,000,000` & `8,000,000` Steps**:
  - **At `4M`**: `1,120 / 2,360` calls (`47.46%`), continuation depth `30` (`{:eval-operand 30}`), control `{:type :if}` inside `call-entry` (`[dsg__body dsg__invocation ...]`), inside second `:have <b>`'s `[gate-norm-beta-eq? 4]` (call `#1106` at step `3,974,712`).
  - **At `8M`**: **`2,219 / 2,360` calls (`94.03%`)**, continuation depth **`26`** (`{:eval-operand 24, :eval-test 2}`), control `{:type :application, :operator {:type :variable, :name data/map?}}` inside `dsg__admitted?` (`[dsg__candidate ...]`). Has already completed both `:have` steps, all four `elab-discharge` steps (`#1295..#1439`), `elab-qed` (`#1511`), all seven `rebuild-type` calls (`#1590`), and entered the 4th/final `[gate-norm-beta-eq? 4]` at step `7,331,479` (`#2096`); last traced call is `{:idx 2218, :step 7999167, :call [gate-syntax-ref? 1]}`.

### 3.4 Cell 4: `:check-assume-have-discharge` × `:c-semantic` at `2,000,000` (Plus `4M` & `8M` Completion)

- **At `2,000,000` Steps (`39.28%` Complete)**:
  - **State**: `halted? false`, `blocked? false`, `:store` defs = `206`, traced calls = **`927 / 2,360`** (`87` distinct arities). Completed `:have <a>` (`#445` at step `1,137,620`) and entered second `:have <b>` (`[gate-proof-elab-have 8]`, `#913` at step `1,979,658`), currently inside `[gate-typing-type-of-app 4]` -> `[gate-typing-type-of-term 3]` -> `[gate-syntax-kind? 1]`.
  - **Control Position**: `segment -1, pc 185517` (`code-length 231,547`), instruction `[:call 1 2 [3 7] false]` (`data/content=` outcome tag check inside `let [dsg__o1 ...]` continuation `anon@185511` of `gate-typing-type-of-term`).
  - **Continuation Stack**: Depth **`23`** (`{:return 23}`). Active call chain alternates cleanly between kernel functions (`gate-typing-type-of-term`, `gate-typing-type-of-app`, `gate-proof-elab-have-impl`, `gate-proof-elab-have`, `gate-proof-elab-proof-step`, `gate-proof-elab-proof`, `gate-proof-check-proof`) and the shared descriptor wrapper `call-entry` (`entry = 454`, return `pc = 511`).
  - **Top-15 Call Frequencies (`2M`)**:
    `[[gate-syntax-binder? 1] 70]`, `[[gate-syntax-kind? 1] 63]`, `[[gate-syntax-variable? 1] 57]`, `[[gate-syntax-app? 1] 53]`, `[[gate-syntax-type? 1] 40]`, `[[gate-typing-ctx-fetch 2] 35]`, `[[gate-nbe-evaluation 4] 31]`, `[[gate-nbe-quotation- 2] 31]`, `[[gate-syntax-ascription? 1] 27]`, `[[gate-syntax-ref? 1] 27]`, `[[gate-typing-type-of-term 3] 27]`, `[[gate-presyntax-kind? 1] 24]`, `[[gate-presyntax-parse-term 3] 24]`, `[[gate-presyntax-type? 1] 24]`, `[[gate-nbe-evaluation 3] 21]`.
  - **Ordered Tail of Executed-Call Stream at `2M` (Last 25 of 927)**:
    ```edn
    [{:idx 902, :step 1955868, :call [context-list-star 2]}
     {:idx 903, :step 1961708, :call [gate-presyntax-parse-term 3]}
     {:idx 904, :step 1962172, :call [gate-presyntax-kind? 1]}
     {:idx 905, :step 1963172, :call [gate-presyntax-type? 1]}
     {:idx 906, :step 1965728, :call [gate-presyntax-parse-symbol-term 3]}
     {:idx 907, :step 1966192, :call [gate-presyntax-reserved-symbol? 1]}
     {:idx 908, :step 1967719, :call [gate-defenv-registered-definition? 2]}
     {:idx 909, :step 1968213, :call [registered-definition? 2]}
     {:idx 910, :step 1968721, :call [registered-definition? 3]}
     {:idx 911, :step 1969207, :call [fetch-definition 3]}
     {:idx 912, :step 1973714, :call [gate-presyntax-left-binarize 1]}
     {:idx 913, :step 1979658, :call [gate-proof-elab-have 8]}
     {:idx 914, :step 1980171, :call [gate-proof-elab-have-impl 8]}
     {:idx 915, :step 1980649, :call [gate-typing-type-of-term 3]}
     {:idx 916, :step 1981114, :call [gate-syntax-kind? 1]}
     {:idx 917, :step 1981626, :call [gate-syntax-type? 1]}
     {:idx 918, :step 1982138, :call [gate-syntax-variable? 1]}
     {:idx 919, :step 1983130, :call [gate-syntax-binder? 1]}
     {:idx 920, :step 1987185, :call [gate-syntax-ref? 1]}
     {:idx 921, :step 1990759, :call [gate-syntax-ascription? 1]}
     {:idx 922, :step 1994333, :call [gate-syntax-host-constant? 1]}
     {:idx 923, :step 1995889, :call [gate-syntax-app? 1]}
     {:idx 924, :step 1999015, :call [gate-typing-type-of-app 4]}
     {:idx 925, :step 1999493, :call [gate-typing-type-of-term 3]}
     {:idx 926, :step 1999958, :call [gate-syntax-kind? 1]}]
    ```
- **Progression at `4,000,000` & Completion at `5,030,283` (`8M` Trial)**:
  - **At `4M`**: `1,924 / 2,360` calls (`81.53%`), `pc = 180` (`[:return 0]` inside `yang.call/_descriptor`), continuation depth `48` (`{:return 48}`), inside 7th `gate-typing-rebuild-type`.
  - **At `8M`**: **Halts cleanly at step `5,030,283`** (`2,360` calls, `112` distinct arities) returning `[:yin.k/ok [:ok [(<a>) (Π [A ✳] (Π [B ✳] (Π [f (Π [⇧ A] B)] (Π [x A] (Π [⇧ A] B)))))]]]` (`A = B = C` exact match).

---

## 4. Instrumented vs Uninstrumented Results & Semantic-vs-Walker Divergence

### 4.1 Exact Instrumentation Cost (`kernel-trace-form`)

`kernel-trace-form` (`test/yang/latte_oracle_test.clj:1839–1860`) injects `(def gate-execution-trace (stream/make 1000000))` once in setup and prepends a native `(stream/put! gate-execution-trace ['fn-sym arity])` side effect at the entry of each kernel arity body (native call — **not** a §8.10 guest call). Comparing completing runs with and without instrumentation yields exact integer step deltas:

| Fixture & Lane | Instrumented Steps (`Setup + Exec`) | Uninstrumented Steps (`Setup + Exec`) | Total Delta (`%`) | Exact Marginal Cost per Traced Call |
| :--- | ---: | ---: | ---: | :--- |
| `:check-accepted` × `:c-semantic` (`720` calls) | `1,207,270` (`20,715 + 1,186,555`) | `1,202,940` (`20,705 + 1,182,235`) | `+4,330` (`+0.36%`) | `+10` setup + **`6.000` steps/call** (`4,320 / 720`) |
| `:check-accepted` × `:c-walker` (`720` calls) | `2,086,120` (`35,707 + 2,050,413`) | `2,078,184` (`35,691 + 2,042,493`) | `+7,936` (`+0.38%`) | `+16` setup + **`11.000` steps/call** (`7,920 / 720`) |
| `:check-assume-have-discharge` × `:c-semantic` (`2,360` calls) | `5,030,283` (`20,824 + 5,009,459`) | `5,016,113` (`20,814 + 4,995,299`) | `+14,170` (`+0.28%`) | `+10` setup + **`6.000` steps/call** (`14,160 / 2,360`) |

*(In the PC opcode profile of `:check-assume-have-discharge`, the `14,160` instrumentation instructions decompose into exactly `2,360 × 6`: `+4,720` `:var` [`2/call`], `+4,720` `:call` [`2/call`: `stream/put!` + `let [_ ...]` discard lambda], `+2,360` `:const` [`1/call`], and `+2,360` `:closure` [`1/call`].)*

### 4.2 What `:c-semantic` Sees on `:check-accepted` That `:c-walker` Does Not

Both evaluators ingest the **exact same compiled UAST** (`215,882` AST nodes) and emit the **exact same 720-element `:executed-calls` sequence** (`#0..#719`). Neither evaluator takes a different branch or extra retry.

The difference is strictly **step granularity per UAST construct**:
- **`yin.vm.semantic/vm-hot*` (`src/cljc/yin/vm/semantic.cljc`)**: Linearizes variable/literal operands into virtual registers. A function call `[:call rd rf [r1 ... rk] tail?]` resolves `rf` and all `k` argument registers and jumps to the target PC in **1 step**; an `if` branch `[:branch-false rtest target]` executes in **1 step**.
- **`yin.vm.ast-walker/cesk-transition` (`src/cljc/yin/vm/ast_walker.cljc`)**: Walks the AST as a fine-grained CESK state machine:
  1. Entering `{:type :application}` pushes an `:eval-operator` frame (**1 step**), evaluates the operator (**≥1 step**), and pops `:eval-operator` to push `:eval-operand` (**1 step**).
  2. Each operand evaluates (**≥1 step**) and transitions `:eval-operand` to the next operand or final application (**1 step per operand**).
  3. Entering `{:type :if}` pushes `:eval-test` (**1 step**) before evaluating the test and branching (**≥2 steps**).

Because `:var` (`47.7%` of all semantic instructions) and `:call` (`25.4%`) dominate the program, `:c-walker` deterministically executes **`1.7280x`** transitions per `:c-semantic` instruction:
- At call `#692` (`[gate-syntax-ascription? 1]`), `:c-semantic` is at step `1,157,550` and finishes calls `#693..#719` (`27` NbE quotation calls returning `true` from `gate-norm-beta-eq?` and constructing `[:ok [term thm-type]]`) in `49,720` more instructions (`1,207,270` total).
- At that exact same call `#692`, `:c-walker` is at step `1,995,071`, exhausts `2,000,000` `4,929` steps later inside a `yang/library` call of `#693`, and never sees calls `#693..#719` (`[gate-nbe-quotation- 2]`, `[gate-syntax-binder? 1]`, `[gate-syntax-variable? 1]`, `[gate-utils-decomposer 3]`, etc.) unless given `86,120` additional steps (`2,086,120`).

### 4.3 Bytecode PC Hotspot Profile (`:c-semantic`) — Proving the `95%+` Call-Protocol Share

Profiling every executed PC on `:check-assume-have-discharge` (`5,009,459` execution instructions) and `:check-accepted` (`1,186,555` execution instructions) and decoding segment-0 closure entry points reveals exact integer multiples of the total §8.10 guest invocation count (**`9,598` §8.10 calls** = `8,259` non-tail `invoke` + `1,339` tail `invoke-with` in `:check-assume-have-discharge`; **`2,292` §8.10 calls** = `1,919` non-tail + `373` tail in `:check-accepted`):

| Decoded Bytecode Owner (`PC` & Source Site) | `:check-accepted` Insts (`%` of `1.187M`) | `:check-assume-have-discharge` Insts (`%` of `5.009M`) | Exact Call-Count Formula (`N = 9,598` in `have-discharge`) |
| :--- | ---: | ---: | :--- |
| **`dsg__assoc` + `[one]`/`[two]` continuations** (`PC 21, 52`, `clojure.cljc:2147–2154`) | `215,642` (`18.17%`) | **`898,744` (`17.94%`)** | Multi-closure `(conj m (conj [] k v))` + `data/content=` `nil` checks on every invocation map, witness strip, option map, & error map |
| **`dsg__get`** (`PC 11`, `clojure.cljc:2144`) | `120,660` (`10.17%`) | **`504,436` (`10.07%`)** | `~52.6` insts per §8.10 call (`5` insts × `~10.5` `dsg__get` calls per invocation!) |
| **`yang.call/_descriptor`** (`PC 161`, `call.cljc:52–55`) | `103,140` (`8.69%`) | **`431,910` (`8.62%`)** | **`9,598 × 45`** (`15` insts × `3` calls per invocation: `1` in `eligible?`, `2` via `entry`) |
| **`yang.call/valid-invocation?` + `_named-pairs?`** (`call.cljc:41–84`) | `126,060` (`10.62%`) | **`527,890` (`10.54%`)** | **`9,598 × 55`** (`45` in `valid-invocation?` + `10` in `_named-pairs?` re-validating compiler-built maps) |
| **`call-entry` (`PC 454` + `[dsg__entry_outcome]` `PC 668`)** (`clojure.cljc:1055–1097`) | `76,917` (`6.48%`) | **`325,443` (`6.50%`)** | Shared descriptor entry wrapper checking `:clj/outcome-token`, `:yang.call/named`, and unwrapping `[:yin.k/ok v]` |
| **`dsg__admitted?` (`PC 441, 661, 825`) + `dsg__make_admission` (`PC 123`)** (`clojure.cljc:2095–2116`) | `73,368` (`6.18%`) | **`307,428` (`6.14%`)** | **`9,596 × 32`** (`2` calls are data-invoker lookups; every descriptor call strips `:clj/admission`, mints a fresh witness closure, and compares closures) |
| **`yang.call/invoke` + `[target]`/`[record]` (`PC 227, 544, 723`)** (`call.cljc:109–119`) | `65,246` (`5.50%`) | **`280,806` (`5.61%`)** | **`8,259` non-tail calls** (`8,259 × 34` insts) |
| **`dsg__equal`** (`PC 16`, `clojure.cljc:2145`) | `62,676` (`5.28%`) | **`263,380` (`5.26%`)** | `5` insts × `~5.5` structural `data/content=` checks per call (including closure witness comparison) |
| **`yang.call/eligible?` + `[descriptor]` (`PC 405, 576`)** (`call.cljc:60–64`) | `41,256` (`3.48%`) | **`172,764` (`3.45%`)** | **`9,598 × 18`** insts |
| **`yang.call/invocation` + `invocation-with`** (`call.cljc:86–95`) | `38,964` (`3.28%`) | **`163,166` (`3.26%`)** | Allocates 4-key / 5-key invocation record via `dsg__assoc` on every call |
| **`yang.call/entry`** (`PC 411`, `call.cljc:57–58`) | `27,504` (`2.32%`) | **`115,176` (`2.30%`)** | **`9,598 × 12`** (`6` insts × `2` calls per invocation) |
| **`dsg__empty`** (`PC 32`, `clojure.cljc:2155`) | `13,752` (`1.16%`) | **`57,588` (`1.15%`)** | **`9,598 × 6`** insts |
| **`yang.call/invoke-with` + `[record]` (`PC 198, 693`)** (`call.cljc:97–107`) | `9,698` (`0.82%`) | **`~34,814` (`0.69%`)** | **`1,339` tail calls** |
| **Subtotal — Static Prelude (`PC 0..850`) Alone** | **`974,883` (`82.16%`)** | **`4,083,545` (`81.52%`)** | Before counting per-call-site `compile-guest-call` bridges (`u/capture`, `raise` closure, arg vector, `bind-outcome`)! |
| **Per-Call-Site `compile-guest-call` + `bind-outcome` Bridges** | `~160,781` (`13.55%`) | **`~681,091` (`13.60%`)** | `8,259` `:current-continuation` ops, `raise` closures, `[:yin.k/ok v]` re-wrap & `bind-outcome` unwrap |
| **Actual `yang/library` + LaTTe Kernel Computation (Residual)** | **`~50,891` (`4.29%`)** | **`~244,823` (`4.89%`)** | Direct entry bodies of all `144` kernel `defn`s take only **`32,700` insts (`0.65%`)**; `48` library `defn` entry bodies take **`98,984` insts (`1.98%`)** |

---

## 5. Minimal Self-Contained Reproduction (Per-Call Cost Amplification)

The following minimal 2-function guest program (`0` host dependencies, `0` kernel code) reproduces the exact `14.4x – 14.9x` per-call step amplification when compiled across `yang.clojure`'s four lowering modes (`N = 0, 100, 1000` iterations to isolate exact marginal cost `(steps(1000) - steps(100)) / 900` free of setup cost):

```clojure
;; Non-tail call probe (1 guest call to `callee` + 1 tail self-call per iteration;
;; difference vs `callee` inlined isolates 1 non-tail guest call, or see direct 1-call loop):
[(defn callee [x] (+ x 1))
 (defn driver [n acc]
   (if (= n 0)
     acc
     (let [y (callee acc)]   ;; non-tail call (or `(callee acc)` in tail position for tail probe)
       (recur (- n 1) y))))
 (driver N 0)]
```

*(Note: `recur` compiles to a direct local loop closure call `dsg__loop0`, so `(let [y (callee acc)] (recur (- n 1) y))` executes **exactly `1` guest `defn` call per iteration**! For the tail-call row, `(defn step-fn [n acc] (if (= n 0) acc (step-fn (- n 1) (+ acc 1))))` executes **exactly `1` tail guest `defn` call per iteration**.)*

### 5.1 Measured Marginal Steps per Single Guest Function Call

| Compiler Mode (`yang/compile-program` opts) | Call Kind | `:c-semantic` Steps (`N=0 / N=100 / N=1000`) | `:c-semantic` **Steps / Call** | `:c-walker` Steps (`N=0 / N=100 / N=1000`) | `:c-walker` **Steps / Call** | Amplification vs `:arg-vector` |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| `nil` (raw closure, native arity) | Non-tail | `26 / 2,026 / 20,026` | **`20.0`** | `41 / 3,541 / 35,041` | **`35.0`** | `0.56x / 0.54x` |
| `nil` (raw closure, native arity) | Tail | `16 / 1,516 / 15,016` | **`15.0`** | `24 / 2,824 / 28,024` | **`28.0`** | `0.41x / 0.41x` |
| `{:calling-convention :arg-vector}` (pre-outcome) | Non-tail | `26 / 3,626 / 36,026` | **`36.0`** | `41 / 6,541 / 65,041` | **`65.0`** | **`1.00x` (baseline)** |
| `{:calling-convention :arg-vector}` (pre-outcome) | Tail | `34 / 3,734 / 37,034` | **`37.0`** | `57 / 6,957 / 69,057` | **`69.0`** | **`1.00x` (baseline)** |
| `{:outcomes true}` (§8.10 common-call, no invoker) | Non-tail | `226 / 53,026 / 528,226` | **`528.0`** | `368 / 92,168 / 918,368` | **`918.0`** | **`14.67x / 14.12x`** |
| `{:outcomes true}` (§8.10 common-call, no invoker) | Tail | `686 / 49,586 / 489,686` | **`489.0`** | `1,159 / 85,559 / 845,159` | **`844.0`** | **`13.22x / 12.23x`** |
| **`{:outcomes true, :invoker true}` (Gate Mode)** | **Non-tail** | `1,534 / 55,334 / 539,534` | **`538.0`** | `2,624 / 96,024 / 936,624` | **`934.0`** | **`14.94x / 14.37x`** |
| **`{:outcomes true, :invoker true}` (Gate Mode)** | **Tail** | `2,004 / 51,904 / 501,004` | **`499.0`** | `3,431 / 89,431 / 863,431` | **`860.0`** | **`13.49x / 12.46x`** |

Under the current gate convention (`934` walker steps/call), **a simple loop of just `2,142` non-tail guest function calls exhausts the entire `2,000,000` walker instruction budget** (`2,142 × 934 = 2,000,628`). `:check-accepted` makes `2,292` guest calls (`2.09M` walker steps), and `:check-assume-have-discharge` makes `9,598` guest calls (`~8.69M` walker steps).

### 5.2 Exact Lowering Sites Responsible (`src/cljc/yang/clojure.cljc` & `src/cljc/yang/core/call.cljc`)

1. **Per-call witness closure re-minting & structural closure comparison (`src/cljc/yang/clojure.cljc:1267, 2095–2116`)**:
   Every call site executes `(dsg__admitted? callee)`, which reads `:yang.call/entry` and `:clj/admission`, allocates a new map `(dsg__assoc candidate :clj/admission nil)`, allocates a brand-new closure `(dsg__make_admission ...)`, and runs `(dsg__equal witness fresh-witness)` (`data/content=` recursively comparing closure payloads and captured descriptor maps). Even direct calls to top-level `defn`s defined in the same compilation unit (`gate-*` and `yang/library` helpers) pay this full dynamic authenticity check on every invocation.
2. **Multi-closure `dsg__assoc` implementation (`src/cljc/yang/clojure.cljc:2146–2154`)**:
   `dsg__assoc` is a 7-arg guest lambda `(fn [m k v k2 v2 k3 v3] (let [one (conj m (conj [] k v))] (if (data/content= k2 nil) one (let [two ...] ...))))` rather than calling native `assoc` (or fixed-arity helpers), costing `~22–45` instructions per map update (`898,744` semantic instructions — `17.94%` of the entire run).
3. **Redundant descriptor lookup, 4-key invocation map allocation, and self-validation (`src/cljc/yang/core/call.cljc:52–119`)**:
   `yang.call/invoke` calls `eligible?` (which calls `_descriptor` and `entry`, which calls `_descriptor` a second time), then calls `entry` again (`_descriptor` a third time), builds a 4-key map via 4 nested `assoc` calls (`yang.call/invocation`), and immediately runs `valid-invocation?` (`4` `contains?` + `2` `vector?` + `_named-pairs?` + `empty?`) on the map `yang.call/invocation` just constructed (`959,800` semantic instructions across `_descriptor` + `valid-invocation?` + `_named-pairs?`).
4. **Non-tail continuation capture + double outcome wrap/unwrap (`src/cljc/yang/clojure.cljc:1072–1084, 1222–1256`)**:
   On every non-tail call (`8,259` calls in `:check-assume-have-discharge`), `compile-guest-call` captures the current continuation (`u/capture`) and allocates a `raise` escape closure so `yang.call/invoke` can return a bare value; inside `call-entry` (`lines 1077–1080`), the callee's `[:yin.k/ok v]` outcome is unwrapped to `v`, returned through `yang.call/invoke`, and **immediately re-wrapped** into `[:yin.k/ok v]` at `line 1232` (`(outcome-node ok-tag (u/v result))`), only for the caller's `bind-outcome` to unwrap `[:yin.k/ok v]` a second time! (Tail calls already pass `:clj/outcome-token` to forward outcomes without unwrapping; non-tail calls do not.)

---

## 6. Recommended Next Round

### 6.1 Primary Recommendation: `compiler fix` Round (+ 1-Line `harness fix`)

We recommend dispatching a **`compiler fix` round** on `src/cljc/yang/clojure.cljc` (paired with a tiny **`harness fix`** on `test/yang/clojure_test.clj:1946`), **not** raising the `2,000,000` acceptance budget:

1. **Compiler Lowering Optimization (`src/cljc/yang/clojure.cljc`)**:
   - **Direct / pre-admitted intra-unit call fast path or token-forwarded non-tail outcome lane**: Top-level `defn` / `defn-` bindings compiled within the same `compile-program` unit (when not shadowed by locals) have statically known descriptors/private bodies, or non-tail admitted calls can pass `{:clj/outcome-token dsg__call_token}` (or invoke the private `av` closure directly for unshadowed intra-unit `defn`s while preserving full §8.10 descriptor publication for first-class values and cross-boundary calls).
   - **Streamline `dsg__assoc` / `dsg__admitted?`** for dynamic first-class descriptor calls (avoiding 7-arg nested `[k v]` vector-conj chains when native `assoc` is unshadowed, and avoiding redundant map allocations).
   - **Expected Impact**: Cutting intra-unit per-call cost even halfway back toward `:arg-vector` (`538 -> ~60–100` semantic steps, `934 -> ~110–180` walker steps) reduces `:check-accepted` from `2.09M -> ~0.25M–0.40M` walker steps and `:check-assume-have-discharge` from `~8.69M -> ~0.95M–1.65M` walker steps (`~0.55M–0.95M` semantic steps) — **passing all 106 fixtures on both lanes inside the existing `2,000,000` budget** and speeding up the full gate suite ~5x.
2. **Harness Observability Fix (`test/yang/clojure_test.clj:1946`)**:
   - Include `:consumer v` in `run-bounded`'s exhaustion `ex-info` (`(throw (ex-info "Guest instruction budget exhausted" {:lane lane :steps n :consumer v}))`) so `observer/consumer-left-by-run` preserves the exhausted VM state rather than falling back to step `0`.
   - Keep `test/yang/latte_oracle/slice9_declarations.edn` tracked in git (included in this branch).

### 6.2 Contingent `budget proposal` Data (If Architect Elects a Budget Change Before/Instead of Compiler Optimization)

Per `BRIEF-h3-1.md`, if the architect elects to unblock the gate via an acceptance-budget raise prior to compiler optimization, the required measurements are:

- **Measured Completion Costs (Instrumented Gate Mode)**:
  - `:check-accepted`: **`1,207,270`** (`:c-semantic`), **`2,086,120`** (`:c-walker`).
  - `:check-assume-have-discharge`: **`5,030,283`** (`:c-semantic`), **`~8,692,000`** projected (`:c-walker`, bounded to **`8,682,000 – 8,705,000`** by the `1.7277x – 1.7280x` walker/semantic invariant and `94.03%` call completion at `8,000,000` steps).
- **Measured Instrumentation Overhead**: **`+0.28%` to `+0.38%`** (`+6.00` semantic / `+11.00` walker steps per traced kernel call; `+4,330` to `+14,170` total steps).
- **Monotonic Progress Evidence**: Across `2M -> 4M -> 8M`, `:check-assume-have-discharge` advances strictly forward through the finite proof script (`459 -> 1,120 -> 2,219` of `2,360` calls on `:c-walker`; `927 -> 1,924 -> 2,360` halting on `:c-semantic`) with bounded continuation depth (`23 – 48` frames) and exact `A = B = C` output agreement.
- **Proposed Finite Bound & Headroom (Contingent Only)**:
  - A uniform per-lane budget would require **`12,000,000` steps** (`1.38x` headroom above `:check-assume-have-discharge` `:c-walker`'s `~8.69M` completion cost; `2.39x` above `:c-semantic`'s `5.03M`).
  - **Why `compiler fix` is preferred over `12,000,000`**: Raising `:instruction-budget-per-lane` to `12,000,000` leaves `95.1%–95.7%` of VM execution in avoidable per-call protocol/witness boilerplate and increases worst-case walker timeout cost `6x` (`~8.6 s -> ~52 s` per runaway lane).

---

## 7. Verification Record

1. **Pre-check (`test-kernel-differential-gate`)**:
   - Command: `clojure -M:test -n yang.latte-oracle-test -v yang.latte-oracle-test/test-kernel-differential-gate`
   - Result: `Ran 1 tests containing 661 assertions. 1 failures, 0 errors.` (`{:pass 100, :recorded-upstream-failure 2, :recorded-deviation 2, :blocked 2}`, `58` unexercised arities — only the expected `blocked` assertion fails).
2. **H3-0 Declarations & Evidence Round-Trip Check**:
   - Command: `clojure -M:test -n yang.latte-oracle-test -v yang.latte-oracle-test/test-kernel-acceptance-counting -v yang.latte-oracle-test/test-kernel-gate-evidence-writer-round-trip`
   - Result: `Ran 2 tests containing 32 assertions. 0 failures, 0 errors.`
3. **Post-check (`test-kernel-differential-gate` & `clj-kondo`)**:
   - Zero changes to `src/cljc/` or `test/yang/latte_oracle_test.clj`; post-check matches pre-check (`Ran 1 tests containing 661 assertions. 1 failures, 0 errors.`; `clojure -M:kondo --lint test/yang/latte_oracle/slice9_declarations.edn test/yang/latte_oracle_test.clj`: `0 errors, 0 warnings`).
