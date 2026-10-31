Completed-GMT: 2026-10-25 05:24:35 GMT
Completed-Local: 2026-10-25 12:24:35 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

**NOT SIGNED OFF: bounded execution still permits unaccounted candidate expansion and host-stack overflow during source admission.**

I reviewed the working-tree implementation, both GLM reports and the amended contract, and ran targeted JVM probes. No files were edited. The observed HEAD was `46de095b`, rather than the dispatched `57207b36`. I did not reproduce the full three-host or downstream suites; those remain supplied gate evidence.

## Blocking findings

**P1 — Function-result binding bypasses work accounting and demand.**

At [query.cljc:1405](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:1405), collection/relation function results are exhaustively unified into vectors without execution state. `emit-fn` charges once before this expansion; subsequent checked enumeration sees only the surviving bindings.

Consequently, rejected candidates consume no individual allowance. This is query-controlled interpretation **after** the host function returns, so the opaque-host-function exception does not apply.

Reproduced with fully materialized inputs:

```clojure
(def query
  '[:find ?x
    :in $ ?xs
    :where
    [(identity ?xs) [0 ...]]
    [(ground 1) ?x]])
```

With `?xs` containing integers `1..10000` and `:max-steps 100`:

- Core `q-with-state` completed successfully at **9 steps**.
- The adapter returned `[]`.
- Ten candidates and ten thousand candidates consumed the same nine steps.

A separate lazy-input probe realized all 10,000 values before eventually reporting exhaustion at 100 steps.

**Required correction:** make collection/relation result binding an incremental state-passing producer. Charge each attempted binding, including rejected ones, and honor terminal/reduced outcomes before processing further elements. Do not first build the complete output vector.

Also remove the analogous pre-accounting enumeration at [query.cljc:1858](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:1858): `(count val)` can exhaust a non-counted collection input before the first corresponding budget check.

Add deterministic tests for rejected outputs, collection/relation inputs, and first-witness termination during function-result expansion.

**P1 — Source-chain admission and realization remain host-recursive.**

At [datomic.cljc:548](/Users/sto/workspace/datomworld/src/cljc/dao/space/query/datomic.cljc:548), `source-admissible?` recursively descends view descriptors outside the trampoline and without progress accounting. Realization independently recurses through [query.cljc:518](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:518).

Reproduced:

```clojure
(let [source (reduce (fn [s _] (query/current s))
                     (query/relation [])
                     (range 10000))]
  (datomic/q
    {:query '[:find ?e :where [?e :a ?v]]
     :args [source]
     :dao.space.query.datomic/max-steps 100}))
```

**Observed:** raw `java.lang.StackOverflowError`, not structured exhaustion or refusal.

This source chain is constructed through the public API and terminates in a materialized relation. It therefore falls within Phase 4a’s stated admission domain.

**Required correction:** traverse descriptors iteratively with invocation accounting and realize nested views through explicit frames or an equivalent checked iterative path. Preserve zero-read refusal of published/deferred sources. Fixing admission alone leaves realization vulnerable.

Add three-host deep-source tests demonstrating structured exhaustion and safe refusal when the terminal source is published.

## Conformance to r3 and r4

The principal architectural choices are correctly implemented:

- **One evaluator:** [query.cljc:2187](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:2187) delegates legacy `q` to `q-with-state` with the unbounded policy.
- **Scope versus progress:** rule bodies receive extended immutable context while returned execution state continues through their callers.
- **Rule/continuation trampoline:** `run-comp`, `bounce`, `bind` and `walk` genuinely move pending continuation work onto heap frames. This is more than trampolining rule entry.
- **Deadline semantics:** `>=`, absorbing terminal states and discarded post-read values match the rulings.
- **Published-source boundary:** ordinary published/deferred source cases are refused structurally; Phase 4b remains explicitly unqualified.
- **Layer separation:** I found no hidden mutable invocation state or Client option leakage into core semantics.

However, **r3/r4 are not fully satisfied**. The two reproduced paths above escape the execution discipline around the otherwise-correct trampoline. Passing recursive-rule tests does not establish stack safety or accounting for the entire invocation.

## Disclosed residuals

- **Frame-allocation cost:** acceptable for Phase 4a landing, with performance qualification still outstanding.
- **Dart wall-clock fallback:** acceptable under the documented limitation.
- **Examples outside the test classpath:** acceptable if not presented as executed evidence.
- **Deferred published-index bounds and E13:** acceptable as explicitly scoped.
- **“Syntax recursion is bounded by query-text depth”:** insufficient as a general justification. The scanner bounds string queries only; native map/vector queries and source descriptors do not receive that bound. Recursive syntax validation and pull parsing need an explicit applicability check before this residual can be accepted generally.

The existing green gates remain useful evidence, but they omit the reproduced cases. Fix the two blocking paths, add the focused portability regressions, and rerun the affected gates before requesting sign-off.