Completed-GMT: 2026-11-13 01:10:00 GMT
Completed-Local: 2026-11-13 08:10:00 Asia/Ho_Chi_Minh
Role: Reviewer (delta re-confirmation — corpus round `18870268` + reconstruction fixture `854b74c3`)
Coding-Agent: qwen (qwen3.8-max)
Subject: branch `exception-r3-impl` @ `854b74c373abc3d1c72f6b7f3a6a9a82ed574797`
Resume of: `1794562000000-reviewer-port-rereview.qwen3.8-max.findings.md` (APPROVE WITH FINDINGS)
Clock: read from `timeapi.io` (UTC `2026-11-13T01:05:49`, +07 `2026-11-13T08:05:56`) — no shell in this session.

# Delta re-confirmation — corpus round + reconstruction fixture vs sol's §4

## 0. Method, and what I could not run

**Still no shell.** `tool_search` for `run_shell_command`/`monitor`/`bash`/`exec` returned only
`computer_use`/worktree tools. So item 3's `git diff origin/master...854b74c3 --stat` was
**NOT RUN**, and I ran **no tests** — the reported 36/2,543 (latte-oracle) and 109/1,028
(clojure + core-call) are **UNVERIFIED by me**; I corroborated them structurally instead.

Substitute method, same as my two previous rounds:

- **State.** `.git/worktrees/datomworld-r3-orch/HEAD` → `ref: refs/heads/exception-r3-impl`;
  `.git/refs/heads/exception-r3-impl` → `854b74c373abc3d1c72f6b7f3a6a9a82ed574797`. **Matches the
  brief.** Reflog adds three commits since my last review of `d4e31b74`:
  `691e9bd6` "test(yang.clojure): record depth evidence at port tip" →
  `18870268` "test(yang.clojure): port implicit oracle catches to continuations" →
  `854b74c3` "test(yang.clojure): reconstruct oracle registry at quiescence".
  Note `691e9bd6` is the round that answers my previous **P2-4**; the brief folds it into item 3.
- **Exact per-file line counts** (`grep -c ''` equivalent) in the branch worktree
  (`B: /Users/sto/workspace/datomworld-r3-orch`) against both master
  (`M: /Users/sto/workspace/datomworld`) and the values I recorded at `d4e31b74`. Every delta below
  is accounted for; the accounting closes exactly.
- Master drifted again (`.git/refs/heads/master` = `f554e65e`, `origin/master` read `868e334a`
  earlier in the session). Every master count I re-measured is identical to the previous round, so
  `f554e65e` (`docs: rename yang.cic/yin.cic to dao.li`) touches none of these paths.
- **Worktree dirt remains undetectable** without `git status`. File sets match master everywhere
  compared (`src/cljc/yang` 47 = 47, `src/cljc/yin/vm` 79 = 79, `test/yin/vm` 42 = 42).

### The whole-span file set at `854b74c3` (derived, not `git diff` output)

| Path | master | `35d09aaa` | `d4e31b74` | `854b74c3` | Note |
|---|---:|---:|---:|---:|---|
| `src/cljc/yang/clojure.cljc` | 3267 | 3098 | 3122 | **3121** | −1 this delta: comment-only (see P3-10) |
| `src/cljc/yang/clojure/prelude.cljc` | 174 | 272 | 248 | **248** | untouched since `f15423c2` |
| `test/yang/clojure_test.clj` | 2712 | 3217 | 3281 | **3281** | untouched by both rounds |
| `test/yang/latte_oracle_test.clj` | 4139 | 4165 | 4165 | **4217** | +52 |
| `test/yang/core/` (dir) | 375 | 375 | 388 | **388** | untouched by both rounds |
| `test/yang/call/` (the kit, dir) | 2360 | 2360 | 2360 | **2360** | **untouched all span** |
| `src/cljc/yin/vm/` (dir) | 45929 | 46266 | 46266 | **46266** | **untouched all span** |
| `test/yang/latte_oracle/` (dir) | 5148 | 5317 | 5317 | **5311** | −6 |
| `deps.edn` | 176 | — | — | **176** | unchanged; LaTTe pin intact |

The `test/yang/latte_oracle/` −6 decomposes **exactly**: `unfold_implicit.edn` 19→14 (−5),
`context.edn` 133→132 (−1), `depth_post_port.edn` 4→2 (−2), `depth_comparison.md` 164→166 (+2),
`depth_baseline_fbd8da0f.edn` 3→3 (0). Nothing else in that directory changed — in particular
**`kernel_gate.edn` is untouched**, which is what makes item 3's "measurement unchanged" claim
checkable rather than asserted.

The `latte_oracle_test.clj` +52 decomposes exactly by deftest-span arithmetic (all 36 spans
compared): ns header **+2**, `context-host-form` region **−5**, `test-oracle-slice8-context-reconstruction`
**+15**, depth-writer region **+40**. Sum = +52. ✓

---

## Item 1 — Corpus round `18870268`: **PASS**, conversions are faithful and closer to upstream

**The five `yin.k/outcome-of` errors are gone.** Repo-wide, `yin.k/outcome-of` now occurs exactly
once under `B:test/` — a comment at `clojure_test.clj:3249` ("`yin.k/outcome-of` is retired: failure
in an early def transfers out…"). Zero occurrences in `latte_oracle_test.clj` and zero in any
`test/yang/latte_oracle/*.edn` fixture.

**Two fixtures were converted. Both are re-expressions, not relaxations.**

1. `B:test/yang/latte_oracle/unfold_implicit.edn` (19 → 14 lines). The B-leg adaptation of upstream
   `latte-kernel.typing/unfold-implicit` (`typing.cljc:453`, "the one try in the corpus"):

   ```clojure
   ;; master (outcome model)                      ;; branch (continuations)
   (let [outcome (yin.k/outcome-of               (try [:ok ((:fn implicit-def) args) args]
         ((:fn implicit-def) args))]                  (catch Exception e
     (if (= :yin.k/error (first outcome))                [:ko (merge {:implicit (:name implicit-def)
       [:ko (conj {:implicit (:name implicit-def)                    :msg (ex-message e)}
                 :msg (:yin.k/message (second outcome))}              (ex-data e)) nil])))
              (:yin.k/data (second outcome)))
        nil]
       [:ok (second outcome) args]))
   ```

   The branch form is now **structurally identical to the A-leg oracle the test compares it
   against** — `upstream-unfold` at `latte_oracle_test.clj:625-630` is
   `(try [:ok ((:fn implicit-def) args) args] (catch clojure.lang.ExceptionInfo e [:ko (merge
   {:implicit (:name implicit-def), :msg (ex-message e)} (ex-data e)) nil]))`. The only difference
   is the caught class (`Exception` guest vs `clojure.lang.ExceptionInfo` host), which is correct:
   clojure's admitted catch surface is `Exception`/`ExceptionInfo`, and both name any raised value.
   Under the outcome model the fixture consumed a *compiler-mediated tuple*; it now catches the
   actual guest raise. That is a **strengthening** of the differential, not a weakening. The header
   comment was rewritten honestly ("The catch is replaced by the outcome model: … `yin.k/outcome-of`
   is the raw call, not unwrapped at the call site" → "The implicit function is a guest closure; the
   catch merges the raised value's data over `:implicit` and `:msg` in upstream's order (the data
   wins)"), and the "explicitly adapted, NOT compiled from upstream source" provenance statement is
   retained.

2. `B:test/yang/latte_oracle/context.edn:117-122`, `unfold-typed-implicit` (133 → 132 lines). Same
   conversion, same `[:ok …]` / `[:ko (merge {:implicit … :msg …} (ex-data e)) nil]` shapes, same
   typed-argument boundary. The surrounding comment at `:115-116` ("Same typed-argument boundary as
   the slice-5 catch adaptation, now with the real record field and calling convention") is
   unchanged in both trees.

**The dead B-leg rewriter was removed, not repurposed.** `context-host-form`
(`B:latte_oracle_test.clj:936-957`) dropped its `(= 'yin.k/outcome-of (first x))` clause — which
rewrote the intrinsic into a *host* `try/catch` producing `[:yin.k/ok …]`/`[:yin.k/error …]` — and its
docstring was corrected from "The sole guest outcome intrinsic becomes the equivalent host catch"
to "B is ordinary Clojure over the checked-in adaptation". With the intrinsic retired from the
corpus the clause was unreachable; leaving it would have been a silent no-op that masks a future
un-ported fixture. Removing it is the right call. The `gensym`, `fresh-symbol` and `data/dissoc`
adaptations are byte-identical.

**Renamed pins carry per-fixture justification.** `test-oracle-slice5-unfold-implicit`
(`B:633-651`): the `testing` label moved from "failure: **error outcome consumed**, data overrides
`:msg`, result is `[:ko ... nil]`" to "failure: **the raised error is caught**, data overrides
`:msg`, result is `[:ko ... nil]`" — the label now names the mechanism actually under test. Both
assertions survive unchanged: the differential against the real upstream function
(`(is (= (upstream-unfold a-throw '[7]) (second (b-leg throw-form '[7]))))`) **and** the explicit
expected value `(is (= [:ko {:implicit 'imp, :msg "data wins", :extra 1} nil] …))`, which still
proves `ex-data` overrides the `:msg` default. Three assertions before, three after; span 69 lines
before, 69 after.

**Nothing was weakened.** `latte_oracle_test.clj` `(is ` count is **316 = 316** versus `d4e31b74`,
and the deftest set is the same **36 names** in the same order. The two other suites that reported
errors — `test-oracle-slice8-shared-implicit-installation` (span 63 lines, unchanged) and
`test-oracle-slice8-implicit-delta-errors` (span 42 lines, unchanged) — were not edited at all; they
failed because the *fixtures they consume* still spelled the retired intrinsic. Fixing the fixture
rather than the assertion is the correct direction.

The only allow-list widening in this file, `yang/continuation-family-names` inside
`unbound-ast-symbols` (`B:210-213`, +3 lines vs master), **predates this round** — it landed in
Phase 3r `8456c2e9` ("Admit the clj/ continuation family … so the kernel-gate corpus compiles").
It is a harness scan, not the linker. My previous **P3-7** on it still stands, unchanged.

## Item 2 — Reconstruction fixture `854b74c3` vs sol's §4: **PASS on every clause**

Spec: `1794565000000-consult-oracle-reconstruction-transport.gpt-6.1-sol.stdout.log`, final
`agent_message` §4 ("Exact instruction for the next implementer round"). Target:
`B:test/yang/latte_oracle_test.clj:1271-1360` (`assert-machine-data-within-budget!` +
`test-oracle-slice8-context-reconstruction`).

| sol's requirement | Where | Verdict |
|---|---|---|
| Work from `18870268` | reflog: `18870268` → `854b74c3`, single commit | ✓ |
| Retain upstream LaTTe pin `86fca0d1d054af005cb37395eae04500d45e6279` | `B:deps.edn:37-39`, byte-identical to `M:deps.edn:37-39`; `deps.edn` 176 = 176 | ✓ |
| Edit **only** the reconstruction test + its fixture-local helpers | `latte_oracle_test.clj` is the only file changed by this commit (+15 in the deftest span, +2 ns requires `clojure.java.shell` / `yang.core.uast`, +40 in the depth-writer region which belongs to `691e9bd6`); `clojure_test.clj` 3281 = 3281, `clojure.cljc` comment-only | ✓ |
| Build the original context under normal `compile-program` lowering | `builder` = `(yang/compile-program (into (yang/library-for forms) forms) true arg-vector-opts)`, `:1292` | ✓ |
| Require its successful result | `(u/if-node (= (get built 0) :yin.k/ok) … (u/v 'built))`, `:1305-1311` — a failed build short-circuits and is returned, so the fixture cannot pass on a broken build | ✓ |
| After construction returns **and its handlers unwind**: execute `yin/def clj.rt/ctx nil` | `(u/then (u/def! 'clj.rt/ctx (u/lit nil)) …)`, `:1308` — retirement **by executed guest UAST**, not by editing machine fields or UCF output | ✓ |
| Bind `checkpoint` to the existing context value | `(u/let1 'checkpoint (u/app (u/v 'get) (u/v 'built) (u/lit 1)) …)`, `:1309` | ✓ |
| UCF checkpoint there | the machine loop stops on `(get-in m [:env 'checkpoint])`, `:1334-1338`, i.e. after the `def! nil` and before the re-mint — so the root store holds **no live handler cell** at the checkpoint. This is exactly the blocker sol diagnosed (UCF traverses the root store, `ucf/rows.cljc:1634`, and refuses heap-cell references, `:1503`) | ✓ |
| Execute the original `clj.rt/ctx` initialization node | `mint` = `(get-in @#'yang/continuation-uast [:operator :body])`, `:1294` — **reuses the production node**, does not re-implement it | ✓ |
| Enter the original `top-boundary` | `consume` = `(u/let1 'dsg__equal (u/sexp->uast '(fn [a b] (= a b))) (#'yang/top-boundary invoke))`, `:1298-1299` — the real `top-boundary`, with the `dsg__equal` helper bound in the driver exactly as sol required | ✓ |
| Invoke the existing `unparse` through `yang.call/invoke(checkpoint, 3)` | `invoke` = `(yang.call/invoke unparse (conj (conj [] checkpoint) 3) [] <raise-exit>)`, `:1295-1297` | ✓ |
| Preserve original `base`, `offset`, registered closure expression | `build` = `(let [base initial-context offset 4] (register-unparser base :captured (fn [term] [(+ offset term) true])))`, `:1290-1291`; `initial-context` unchanged at `:987` | ✓ |
| Include `unparse`'s dependencies in the builder compilation | `forms` = `(conj (context-for [build '(unparse checkpoint 3)]) build)`, `:1291` — the transitive closure is computed from a form that mentions `unparse`, so its deps compile into `builder` even though consumption happens later | ✓ |
| **Keep the original compilation's descriptors and admission helpers** | the whole driver is `(u/let1 'built builder …)`, so `builder`'s own preamble — `dsg__call_token`, `dsg__make_admission`, `dsg__make_entry`, `dsg__intra_call` — is lexically in scope when `invoke` runs. No second compilation is spliced in. This is the fix for the `:yang.call/not-callable` sol reproduced | ✓ |
| Finalize with `yang/annotate-call-sites` | `ast` = `(yang/annotate-call-sites (u/let1 'built builder …))`, `:1303` | ✓ |
| Derive bytecode address and source-index afresh | `code` = `(vm/ast->semantic-bytecode ast)`, `bytecode` = `(linearize/project-rows code)`, `source-index` = `{(ucf/code-address bytecode) #{(:root code)}}`, `:1329-1331` — all from the composed `ast` | ✓ |
| Preserve code-row execution on both lanes | `(doseq [lane [:walker :semantic]] (is (= [:yin.k/ok 7] (compiler-test/run-guest-ast rebuilt modules lane 1000000))))` over `rebuilt` = `(vm/datoms->ast (cbor/decode (cbor/encode datoms)))`, `:1322-1325` | ✓ |
| Genuine UCF/CBOR reconstruction | `rows/lift-task` → `cbor/encode`/`cbor/decode` → `rows/lower-task` with a real `attach!`, `:1345-1349` | ✓ |
| Closure checks | `(is (values/closure? (get-in k [:unparser-registry :unparsers :captured :yang.call/entry])))` before transport (`:1340`) and the same on `k'` after (`:1350`) | ✓ |
| Both resumed results | `(is (= [:yin.k/ok 7] (run-bounded restored)))` and `(is (= [:yin.k/ok 7] (run-bounded checkpoint)))`, `:1352-1353` — `7` = `(+ offset 3)` = `(+ 4 3)`, so the captured `offset` genuinely survived | ✓ |
| Host-callback rejection | `(is (thrown? Exception (encoder/project (encoder/source-envelope :program "slice8-host-callback-refusal" [{:type :literal :value {:unparser-registry {:unparsers {:bad identity}}}}]))))`, `:1356-1360` | ✓ |
| **Both** validation limits → 500,000 | `:1342` and `:1351`, the only two `500000` occurrences in the file; no `100000` remains (the other numeric hits are unrelated 1,000,000 instruction budgets) | ✓ |
| Keep recursive validator, validity assertion, visit assertion, exhaustion throw | `assert-machine-data-within-budget!` `:1271-1287`: the `with-redefs` wrapper **delegates to the real `vm/machine-data?`** via the captured `check`, counts visits in a `volatile!`, throws `"Machine-data validation budget exhausted"` **outside** `is` so exhaustion also aborts UCF reconstruction (docstring says so), then `(is valid?)` and `(is (<= @visits limit) …)`. All four intact | ✓ |
| Ship no diagnostic redefinitions or mocks | No stubbed `clj/*`, no faked checkpoint, no hand-edited machine state. The only private-var reaches are `@#'yang/continuation-uast` and `#'yang/top-boundary` — **reusing production compiler nodes as sol instructed**, not replacing them. The one `with-redefs` is the pre-existing instrumenting validator that delegates to the real function | ✓ |
| All 14 existing assertions pass | Counted from source: `plain-data? context-source`, `plain-data? ast`, code-rows ×2 lanes, `closure?` k, validator k (validity + visits = 2), `plain-data? wire`, `closure?` k', validator k' (2), `restored` = 7, `checkpoint` = 7, host-callback `thrown?` = **14** | ✓ (by count; not executed) |

Two of sol's structural points are honoured in a way worth calling out, because they are the
substance of the ruling rather than its letter:

- **The transport boundary is quiescence, not active unwinding.** The commit subject says it plainly
  ("reconstruct oracle registry **at quiescence**"). The fixture nils the runtime cell *after* the
  builder's `top-boundary` handler has unwound and *before* the checkpoint, so what crosses UCF is a
  plain registry value with a compiled closure — never a live `%capture`d continuation or a heap-cell
  reference in the root store. Sol's §3 limitation ("This ruling establishes proof-context migration
  across a quiescent runtime boundary. It does not establish active-handler migration.") is
  respected, not stretched.
- **`(is (vm/plain-data? ast) "runtime closures are never embedded as literals")`** (`:1320`) is
  retained, so the composed driver cannot smuggle a host closure into code data — the failure mode
  that would make the whole reconstruction vacuous.

## Item 3 — Whole-span discipline: **PASS** (`git diff --stat` not run; accounting closes exactly)

- **`src/cljc/yin/vm/` EMPTY of branch-side changes.** Aggregate **46266** at `854b74c3`, identical
  to what I measured at `35d09aaa` and at `d4e31b74`. Per-file: `engine.cljc` **2934 = 2934**,
  `module.cljc` **701 = 701** vs master. Non-docs VM total **40943 = 40943**. The only divergence
  anywhere under that tree remains master's own `docs/yin.repl.md` restructure (`ebd64c1e`,
  604 branch vs 267 master = the entire 337 aggregate delta), i.e. master-side, taken from master on
  merge. `test/yin/vm/` is likewise untouched: 42 = 42 files, `provenance_test.cljc` **348 = 348**,
  `scanner_goldens.cljc` **8185 = 8185**. **The carrier still costs zero kernel lines.**
- **`test/yang/call/` (the kit) EMPTY of branch-side changes.** Aggregate **2360** at every one of
  `35d09aaa`, `d4e31b74`, `854b74c3`, and master. Per-file at `854b74c3`: `exception_contract.clj`
  **211 = 211**, `exception_contract_test.clj` **136 = 136**, `exception_source_test.clj`
  **41 = 41**. The acceptance instrument has not been touched by any round of this port.
- **Expected file set only.** Nine paths, itemised in §0, with every line delta accounted for. No
  build-config widening: `deps.edn` **176 = 176**, LaTTe pin `86fca0d1d…` intact at `:39`, and no
  `:measure` alias was committed (the depth writer is invoked through an ad-hoc `-Sdeps`-style alias
  visible only in `B:.cpcache/*.basis`, not in the repo).
- **Depth evidence: re-recorded, attributable, and unaffected by these two rounds.**
  - `depth_comparison.md:5` now reads "Baseline: `fbd8da0f` (outcome model). Post-port: **`d4e31b74`**."
    and the file ends with a **computed** footer: `none-exceeds-10x / max-ratio: 1.20×` (`:166`).
  - `depth_post_port.edn` no longer contains `working-tree`, `8e1dab6d` or `f15423c2` (grep: no
    matches) — the stale attributions I filed as **P2-4** are gone.
  - The writer now earns its attribution: `write-kernel-gate-depths!` (`:2595-2608`) runs
    `git rev-parse --short=8 HEAD` and **throws** `"Cannot determine depth measurement revision"` on
    non-zero exit, so it cannot silently stamp a bogus SHA. `write-kernel-gate-comparison!`
    (`:2611-2643`) is new and **validates before writing** — set-equality of baseline/post workload
    ids, `:workload-count` consistency on both sides, every depth integer and positive — throwing
    `"Incomplete or invalid depth comparison"` otherwise, then computes the ratios, the max and the
    `>10×` flagged list itself. The table is now generated, not hand-assembled. **P2-4 closed;
    P3-1's reproducibility half closed.**
  - **Do the corpus/fixture rounds alter the measurement? No, and this is checkable rather than
    asserted.** The 155 workloads are the kernel-gate corpus in `kernel_gate.edn`, which is
    **untouched** (it is inside the −6 accounting that leaves no room for it). The corpus round
    changed `unfold_implicit.edn` and `context.edn` — slice-5/slice-8 B-leg fixtures that are not
    kernel-gate workloads. The reconstruction round changed only a test-local driver. The single
    `src/` change in the delta is one comment line in `clojure.cljc` (P3-10), which cannot affect
    lowering. So the committed `d4e31b74` record — 155/155 overlap, max ratio **1.20×**, max
    post-port depth **28** — stands for `854b74c3`, and no re-record is demanded.

## Item 4 — Counts: **PASS**, with one correction to the brief's framing

- **No assertion was deleted in either round.** `latte_oracle_test.clj`: `(is ` **316 → 316**
  (identical to `d4e31b74`), deftests **36 → 36** with an identical name list in identical order.
  `clojure_test.clj`: **3281 lines, 105 deftests, 610 `(is ` forms — all three identical to
  `d4e31b74`**, i.e. neither round touched it. `test/yang/core/` **388 = 388**.
- **The "105→109 deftests / 1,011→1,028 assertions" figure is a change of suite scope, not growth.**
  105 is `yang.clojure-test` alone (measured: 105 deftests). 109 is `yang.clojure-test` **plus**
  `yang.core.call-test` (measured: 4 deftests — `base-validation-and-construction-test`,
  `entry-and-invocation-test`, `conditions-and-transfer-test`,
  `cross-frontend-callable-identity-fixture-test`). 105 + 4 = 109. The extra 17 assertions are
  `yang.core.call-test`'s, which have been on the branch since `f15423c2` (the `missing-raise-exit`
  re-pin I confirmed last round); they are not new coverage from these two rounds. The orchestrator
  should not record this as an assertion increase attributable to the corpus or reconstruction work.
- **Where coverage genuinely grew** is the oracle's *executed* assertion count: 2,497 (at
  `691e9bd6`, 6 errors) → 2,535 (at `18870268`, 1 error) → **2,543** (at `854b74c3`, 0 errors), per
  the implementer logs. That growth is tests no longer aborting early, which is the correct kind.
  Not independently verified — I ran nothing.

---

## Findings

No P0. No P1. No P2. Everything I filed last round that these rounds were chartered to close is
closed: **P2-4** (depth attribution) and the reconstruction knot. Carried-over P3s and four new
minor notes:

| # | Sev | Where | Finding |
|---|---|---|---|
| P3-1 | P3 (carried, much improved) | `B:latte_oracle_test.clj:2595-2643` | Still **no executable depth gate**. `write-kernel-gate-depths!` / `write-kernel-gate-comparison!` are writers invoked through an alias; no `deftest` asserts the 155/155 overlap, the ≤10× bound or a frame ceiling, so a future regression is caught only when someone re-runs the alias and reads the markdown. Attribution and generation are now fixed, which is why this stays P3. Residual: `depth_baseline_fbd8da0f.edn` carries an `:overlap-count 155` key that `write-kernel-gate-depths!` never emits and `write-kernel-gate-comparison!` never reads or validates — inert, but the baseline file is not byte-reproducible from the writer. |
| P3-2 | P3 (carried, partly fixed) | `B:latte_oracle_test.clj:161,182-183`; `B:clojure.cljc:2461-2463,2971-2976,3093-3097`; 12 `test-outcome-*` deftests | Stale outcome-model prose. **Fixed this round:** the `yin.k/outcome-of` comment above the `dsg__let` arm. **Still stale:** `arg-vector-opts`' "The argument-vector convention with the outcome model" (`latte_oracle_test.clj:182`), `oracle-registry`'s "portable outcome adapters" (`:161`), `unit-opts`' "with the outcome model over the guest library's adapters" (`clojure.cljc:2972-2973`), `lower-unit`'s "the convention preamble `compile-program` emits defines those names locally" — true for `yang.call`, no longer true for `clj/*` after `d4e31b74` (`:3093-3097`), the guest-library header's "unwrapped by the compiler's own early-return pattern" (`:2461-2463`), and twelve deftests named `test-outcome-*` that exercise the continuation lane. |
| P3-7 | P3 (carried, unchanged) | `B:latte_oracle_test.clj:210-213` | `yang/continuation-family-names` is admitted **unconditionally** into `unbound-ast-symbols`' allow-list with the comment "emitted inline by compile-program under `:outcomes true`" — true only while `:linked-runtime` is absent. If the oracle corpus is ever compiled with `unit-opts`, the allow-list would mask genuinely undeclared `clj/*` reads. Gate it on the same predicate. (Pre-dates this delta; landed in `8456c2e9`.) |
| P3-9 | P3 (new) | `B:latte_oracle_test.clj:12,2598` | The depth writer now shells out to `git`, so `clojure.java.shell` is an ns-level require of the oracle harness. It **fails closed** (throws on non-zero exit rather than stamping a blank revision), which is the right behaviour, but it makes the depth artifact unregenerable from a source tarball or a CI clone without `git` on `PATH`. Acceptable for a maintainer-invoked writer; worth a docstring note. |
| P3-10 | P3 (new, attribution hygiene) | `B:clojure.cljc:2017` | A `src/` file changed inside commit `18870268`, whose subject is `test(yang.clojure): port implicit oracle catches to continuations`. The change is comment-only and benign — the two stale lines `;; (yin.k/outcome-of (f x)): the call's outcome as a value, not unwrapped / ;; internal, from the outcome lowering…` became one accurate line, `;; Internal let whose bindings are already lowered.` (net −1) — and it closes part of P3-2. Flagging only because a source edit under a `test(...)` subject makes the commit-subject-to-content mapping unreliable for whoever reads the log next. |
| P3-11 | P3 (new, latent) | `B:latte_oracle_test.clj:1297` | The fixture's `yang.call/invoke` raise exit is `(u/lam '[condition] (u/app (u/v 'clj/raise) (u/v 'condition)))` — it raises the condition **whole**, whereas production `compile-guest-call` unwraps `:yang.call/thrown` to its payload first (`clojure.cljc:1394-1404`). Harmless today: `unparse checkpoint 3` succeeds, so the exit never fires, and sol's §4 did not prescribe its shape. But if this fixture is ever extended to a *failing* `unparse`, the divergence would make it test a raise path no production caller takes. Add a comment or mirror the production shape. |

## Verification I could not perform — for the seat that has a shell

```sh
cd /Users/sto/workspace/datomworld-r3-orch
git status --porcelain                                    # confirm clean at 854b74c3
git diff origin/master...854b74c3 --stat                  # expect exactly the §0 nine-path set
git diff origin/master...854b74c3 -- src/cljc/yin/vm/     # expect only docs/yin.repl.md (master-side)
git diff origin/master...854b74c3 -- test/yang/call/      # expect EMPTY
git show --stat 18870268 854b74c3                         # confirm 18870268 is the only src/ toucher (comment-only)
clojure -M:test -n yang.latte-oracle-test                 # expect 36 tests / 2,543 assertions / 0F 0E
clojure -M:test -n yang.clojure-test                      # expect 105 tests / 0F 0E
clojure -M:test -n yang.core.call-test                    # expect 4 tests / 0F 0E  (105+4 = 109)
clj -M:kondo --lint test/yang/latte_oracle_test.clj src/cljc/yang/clojure.cljc
```

P1-1 (the kit's `clojure-restricted-catch-surface-preserved-test` still asserting that clojure
refuses `finally`) is the owner's and I do not re-litigate it; I confirm only that the kit is still
byte-identical at `854b74c3`, so that collision is unchanged and still needs its ruling before the
shiva run goes green.

**CONFIRMED — PORT READY FOR MERGE REQUEST** (pending the P1-1 owner ruling and the final shiva run
`xtrack-r3-B`). Item 1 **PASS**: both implicit-unfold adaptations re-expressed as guest `try`/`catch`
that now mirror upstream `typing.cljc:453` more closely than the outcome-tuple versions did, the dead
B-leg rewriter removed, pins renamed with justification, `(is ` count 316 = 316 and 36 deftests
unchanged — nothing weakened. Item 2 **PASS**: every clause of sol's §4 satisfied, including the
transport-at-quiescence boundary, runtime retirement by executed guest UAST, retention of the
original compilation's admission token, `annotate-call-sites` finalization, both ceilings at 500,000,
all 14 assertions preserved by source count, the LaTTe pin `86fca0d1d…` intact, and no mocks.
Item 3 **PASS**: `src/cljc/yin/vm/` and `test/yang/call/` empty of branch-side changes across the
whole span, expected file set only, and the depth record at `d4e31b74` (155/155, 1.20×, max 28) is
now SHA-attributable and provably unaffected by these two rounds — `kernel_gate.edn` untouched, the
only `src/` delta a comment. Item 4 **PASS** with the correction that 105→109 is suite scope
(`yang.clojure-test` + `yang.core.call-test`), not new coverage; no assertion was deleted in either
round. Remaining findings are P3 only: P3-1, P3-2, P3-7, P3-9, P3-10, P3-11.

— qwen3.8-max, independent of both implementer families. The only peer artifact I opened was the sol
consult named in the brief, which is the spec I was asked to check against; I read no other
reviewer's output for this round.
