Created-GMT: 2026-09-16 09:41:00 GMT
Created-Local: 2026-09-16 16:41:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 2ac005e6-e114-4b86-933a-c6ea2633c706

# Task: Give `measure-ms` a ClojureDart implementation in the semantic-VM bench

Role: Scoped / Subagent

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-09-16 16:41:00 +07 | Status: active | Rationale: single well-defined bug, narrow scope, same model family that cleanly fixed an adjacent bug in this exact file earlier tonight

## Context

`test/bench/yin_vm_bench.cljc` (untracked, never committed) is a
three-host micro-benchmark comparing the AST-walker and semantic-VM
evaluators. It has two known bugs, found separately tonight:

1. A reader-conditional splicing bug in its `:require` form (line 20) —
   **already fixed and applied on disk** by an earlier unit tonight
   (`archive/1789525378000-scoped-fix-cljd-bench-require.*` has the full
   history if you want it, not required reading). Do not touch line 20's
   `:require` form; it's already correct.

2. **The bug this task fixes**: `measure-ms` is defined across two separate
   `#?()` reader-conditional top-level forms, and *both* put `nil` in the
   `:cljd` branch:

   ```clojure
   #?(:cljd nil
      :clj
      (defn- measure-ms
        "Criterium quick-bench mean, in milliseconds."
        [loaded]
        (let [result (criterium/quick-benchmark (vm/run loaded) {})]
          (criterium/report-result result)
          (* 1e3 (first (:mean result))))))


   #?(:cljd nil
      :clj nil
      :cljs
      (defn- measure-ms
        "Mean of 9 samples after 4 warmups, each sample 20 runs, in milliseconds
         per run — the sampling discipline of `yin.register-bench-cljd`."
        [loaded]
        (let [now #(.now js/performance)
              sample (fn []
                       (let [start (now)]
                         (dotimes [_ 20] (vm/run loaded))
                         (/ (- (now) start) 20)))]
          (dotimes [_ 4] (sample))
          (let [samples (vec (repeatedly 9 sample))]
            (println "  samples ms/run:" samples)
            (/ (reduce + samples) (count samples))))))
   ```

   So under ClojureDart, `measure-ms` is never defined at all, and
   `run-bench` (which calls `(measure-ms loaded)` unconditionally around
   line 113) fails to compile with `Unknown symbol: measure-ms`.

The `:cljs` branch's own docstring already names the reference to mirror:
"the sampling discipline of `yin.register-bench-cljd`" — that file is
real, read it: `src/cljd/yin/register_bench_cljd.cljd`. Its actual
timing primitive (verify this yourself, don't just trust this summary):

```clojure
(:import ["dart:core" DateTime])
...
(defn- now-ms
  []
  (/ (.-microsecondsSinceEpoch (DateTime/now)) 1000.0))
```

Note it divides `microsecondsSinceEpoch` by `1000.0`, not
`millisecondsSinceEpoch` directly — mirror the real house idiom exactly,
don't invent your own timing primitive.

**Cross-host reader-conditional trap** (this project's own established
convention, verify against the two existing forms above before touching
anything): a `#?(:clj ...)`-only conditional with no `:cljd` branch does
NOT reliably exclude on this project's ClojureDart build — the safe
pattern is always an explicit `:cljd` branch, in FIRST position within
the form (a `:cljd` branch in tail position has silently failed to take
effect before in this codebase). **Keep the existing two-form,
`:cljd`-branch-first shape exactly as it is** — do not restructure into
one form or reorder branches. Only replace the first form's `:cljd nil`
with a real implementation.

## Task

1. Read `test/bench/yin_vm_bench.cljc` in full, and
   `src/cljd/yin/register_bench_cljd.cljd` in full, before changing
   anything.
2. Replace the **first** `#?()` form's `:cljd nil` branch with a real
   ClojureDart `measure-ms` implementation, matching the sampling
   discipline already described in the `:cljs` branch's docstring (4
   warmup samples, then 9 samples, each sample = 20 runs of `(vm/run
   loaded)`, mean ms/run) and using the real `now-ms` timing idiom from
   `register_bench_cljd.cljd` (import `DateTime` from `dart:core` in
   this file's own `ns` form — check whether it's already imported before
   adding a duplicate import).
3. Leave the second form's `:cljd nil` alone — after step 2, there must be
   exactly ONE real `:cljd` definition of `measure-ms` (in the first
   form), matching how `:clj` is real in the first form and `:cljs` is
   real in the second. Do not create a duplicate-def risk.
4. Do not touch `:clj` or `:cljs` branches, `check!`, `run-bench`,
   `tail-countdown-ast`, `load-walker`, `load-semantic`, or anything in
   the `ns` form other than adding `DateTime` if it isn't already
   imported. Do not touch line 20's already-fixed `:require` form.
5. Verify:
   - `clj -M:kondo --lint test/bench/yin_vm_bench.cljc`
   - `clj -M:cljd compile bench.yin-vm-bench` (scoped compile of just
     this namespace)
   - `bb test:cljd` (the full suite — this specific fix is expected to be
     the last thing blocking it; report the exact pass/fail count either
     way, don't just report your own namespace's result)
6. Do not stage or commit. Do not touch any file other than
   `test/bench/yin_vm_bench.cljc`.

## Deliverable

Report back: the exact diff, the exact verification commands and their
output (including the full `bb test:cljd` result, pass or fail), and
confirmation nothing else was touched.
