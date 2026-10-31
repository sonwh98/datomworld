Completed-GMT: 2026-10-16 14:00:37 GMT
Completed-Local: 2026-10-16 21:00:37 +07
Coding-Agent: claude
Session-ID: dc3e4b88-02d8-428d-b938-541541f0fbe1
# Slice 6d-a fix round: four review nits plus Dart-lane gating

Role: VM Runtime. Branch `srvm-6d-a`, worktree `/Users/sto/workspace/datomworld-srvm-6d-a`, still uncommitted. No `src/` file was touched in this round, and nothing under `src/yang`, `test/yang` or `collab/` was staged or committed.

## Per-nit changes

### Nit 1: layout_test `executed-ordered-regions`

**STOPPED as a question; a stronger measured pin is applied instead of `pos?`.**

`(is (pos? @repeated))` cannot pass. I measured the counter for each of the four ASTs, and it is **0 for all four**, including `(+ x x)`. The `repeated` atom is per-AST, inside the `doseq`.

A throwaway probe of the projected vectors shows why. Linearization gives every operand a fresh register:
- `(fn [x] (+ x x))` compiles to `[:var 1 +] [:var 2 x] [:var 3 x] [:call 0 1 [2 3]]`.
- `(+ 1 1)`, `(+ + +)` and the nested `(+ x (+ x x))` behave the same way.

So `layout(p) ++ uses(p)` never repeats a register in any projected program. The executed check cannot exercise the repeated-register agreement clause, and the review's premise that `(+ x x)` produces a group is false.

What I applied:
- `(is (<= 0 @repeated))` became `(is (zero? @repeated))`, with a comment giving the reason. This is strictly stronger than before: a future linearizer that reuses registers fails loudly here.
- The per-group agreement assertion is unchanged.

**Question for the Architect/Fable:** is "repeated-register agreement" (plan v2.1 §3.5, item 9) meant for vectors that are not projected (for example, hand-built ones)? If so, its executed check needs a stack-lane fixture from a non-projected vector, which this test cannot build without new seams. If not, the clause is vacuous for 6d-a and the zero-pin is the honest gate.

### Nit 2: harness `boundary-description` registry clause

**Verified live and correctly keyed; no code change, comment added.**

For real links, `:module-stores` is keyed by **manifest address**, not by name:
- `engine/lift-slice`'s `own` is the manifest address, and `active-store` reads `(get (:module-stores state) store-of)`.
- `module/linked` sets entry `:address (jing/segment-key manifest)`.
- Name-keyed maps such as `{'scoped {}}` and `{'host.mod {...}}` occur only in hand-built fixtures.

A throwaway probe on row 17's linked machine confirmed it:

| Probe | Value |
|---|---|
| `:module-stores` keys | `(:segment/blake3-c9a70abe…)` |
| registry entry `host.mod` `:address` | the same key |
| registry clause | `#{host.mod/f}` |
| `:store-keys` | `#{host.mod/f}` |

So the clause carries the post-link answer, and the install-manifest clause carries the in-flight answer. Neither is dead. I added a one-line comment naming the address keying. The row-17 pin passes.

### Nit 3: `yin.vm.semantic.continuation.md` §8.6 item 7

Applied:
- "the leading boundary is excluded" → "the leading **boundaries** are excluded".
- Item 7 had no separate obligations clause, so the ruled wording is attached to "recorded per case with its obligations". It now reads: "…recorded per case with its obligations, each of which names the case's own execution later answers — store or environment names its own later definitions execute, or module exports its own `require` installs — and zero serves, …".

### Nit 4 (Dart gate): `with-redefs` site inventory

Every site follows the repo pattern: `:cljd` first, `:default` second (per `pipeline_test.cljc:334` and `query_test.cljc:962`). The three private var-quotes `@#'sr/vm-hot*` moved inside the `:default` branches; CLJD rejects private var-quote at runtime.

Line numbers are post-cljstyle.

| file:line | Redefined var(s) | How it is gated | What cljd still asserts |
|---|---|---|---|
| `safepoint_harness_test.cljc:2345` | `vm/run` (driver observe) in `check-returned-boundaries` | `#?(:cljd (throw …) :default (with-redefs …))`; unreachable on cljd because all callers are gated | — |
| `safepoint_harness_test.cljc:2375` | `vm/run` (isolated law-1 replay), same helper | same | — |
| `:2452` `product-returned-boundaries` | (calls the helper) | whole body `#?(:cljd nil …)`, comment names the replacement | constructed products: `task-products-and-ready-validation`, `running-plus-ready-fenced-recovery` |
| `:2465` `sampled-returned-boundaries` | (calls the helper) | whole body gated | `bare-rows-match-the-reference`, `conservative-prefixes-refuse-before-serving` |
| `:2488` `every-returned-boundary` (slow) | (calls the helper) | body inside `slow/guard` gated | slow `every-row-every-segment-matches-the-reference` |
| `:2694`–`2704` `running-plus-ready-fenced-recovery` | `sr/vm-hot*`, `engine/scheduler-round-fuel`, `ffi/bridge-step` | `result` is `#?(:cljd (error #(entry closed)) :default (let [hot @#'…] (with-redefs …)))`; the counters assertion is gated | `:gate-closed`, unchanged control/window/env/K, byte-equal relift, for all four entries × both gates |
| `:2787`–`2795` `completed-link-request-and-active-completions` | `stream/append!` | `b` is gated; the zero-appends assertion is gated | ready reason, active control, empty waits/installs, source = receiver outcome |
| `:2906`–`2916` `long-and-nested-initializer-transfers` | `sr/vm-hot*` (per-task instruction counter) | `next` is `#?(:cljd (sr/run-fuel m 5) …)`; the two budget assertions are gated | transfer, both halt, `host.mod`/`child.mod` linked, `'host.mod` value |
| `:2977`–`2984` `completed-poll-observation-is-an-ordinary-read-outcome` | `stream/next` | `got` is gated; the zero-reads assertion is gated | observe reason, completed shape, blocked outcome, empty waits |
| `vm_test.cljc:428` `fuel-dispatch-and-diagnostic-bridge-are-bounded` | `engine/scheduler-round-fuel`, `engine/scheduler-round` | first block `#?(:cljd nil …)`, comment names the replacement | the diagnostic-bridge block, plus `fuel-and-closed-gates` |
| `vm_test.cljc:462`–`476` `fuel-counts-instruction-transitions` | `sr/vm-hot*` | `got` is `#?(:cljd (sr/run-fuel m n) …)`; the three counter assertions are spliced out with `#?@(:cljd [] …)` | state equality of `run-fuel n` with n single steps |

No `src` seam was needed. JVM behavior is byte-identical: the focused run is still 218 tests / 24,342 assertions, and the quick tier counts are unchanged (below).

**Node risk, not tested by me.** Commit 51117e93 records that Node silently ignored a `with-redefs`. The driver redefines `vm/run`, a protocol method, and `sr/vm-hot*`, which is called intra-namespace. If cljs static dispatch bypasses either var on Node, these tests would observe no boundaries or count nothing. That would show up as pin failures (for example 0 measured), not as silent passes, because the pins and counters assert exact non-zero values. **Watch the Node log for these tests.** A cljd compile check was not run either; both are the orchestrator's lanes.

## Verification

| Check | Result | Log |
|---|---|---|
| cljstyle | applied to `safepoint_harness_test.cljc`, `vm_test.cljc`, `layout_test.cljc` | — |
| kondo, three changed test files | 0 errors, 0 warnings (an intermediate "redundant do" was fixed with `#?@`) | — |
| Focused namespaces (engine, frame, layout, safepoint-harness, vm, rows, rows-custody, ledger) | 218 tests, 24,342 assertions, 0 failures, 0 errors | `target/6da/r4-focused.log` |
| `yin.vm.semantic.vm-test` after the final edit | 30 tests, 352 assertions, 0 failures | — |
| **Whole JVM quick tier `clojure -M:test -e :slow`** | **4,079 tests, 269,020 assertions, 0 failures, 0 errors** (identical to the previous baseline) | `target/6da/r4-quick.log` |

The slow tier was not re-run. Its body changed only by the `#?(:cljd nil :default …)` wrapper and reformatting, and the JVM branch is the same code.

## STOPPED on

- **Nit 1's literal `pos?`.** It is unattainable for projected programs; this is a design question about the scope of the repeated-register clause. A stronger measured `zero?` pin is in place in the meantime.

Nothing else.

READY
