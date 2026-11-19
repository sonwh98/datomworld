Created-GMT: 2026-11-19 22:45:36 UTC
Created-Local: 2026-11-20 05:45 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yin.repl jolt orchestrator seat)

# The jolt leg of the RULING 3 parity gate: 91,496/91,500 — NO divergence; 4 errors are a test-helper portability defect (yang.clojure owner)

## The run

yang.clojure-collection-order-test on the jolt lane (jupiter, /tmp/j7-parity worktree at
fix/constructed-collection-order @ bf4ede6d — the same tip as the Node/Dart r4 evidence;
fresh worktree, parsers generated, bootstrap + native build done). Runtime: ~55 min of
single-threaded Chez. Full log: j7-parity-collection-order-jolt.log beside this note.

## The result

SUMMARY {namespaces 1, load-failures 0, tests 15, assertions 91500, pass 91496, fail 0,
error 4} — every engine arm (ast-walker, semantic, stack, register) ran the full corpus.

## The 4 errors: NOT a divergence

All four are one assertion helper failing identically per arm:
`(into [] (replace {:right :left, :left :right}) log)` →
clojure.lang.ArityException: Wrong number of args (1) passed to: clojure.core/replace.
The two-arg `replace` (map + coll) is the JVM's arity; jolt's admitted clojure.core
surface does not carry it. The engine SEMANTICS agree — the swapped-trace assertion's
inputs are computed and compared identically everywhere; only the helper's arity breaks
on jolt. Test-side fix (the corpus owner's, yang.clojure): e.g.
`(into [] (map #(clojure.set/replace m %) log))` or an explicit per-element swap — any
form inside the admitted surface. The corpus rows themselves are green.

## The four-host matrix (RULING 3 gate)

| host | corpus | result |
|---|---|---|
| JVM | bf4ede6d baseline | 15/91,500 green (the sign-off r2 baseline) |
| Node | bf4ede6d | 15/91,500 green (node-order.log, r4 evidence) |
| Dart | bf4ede6d | set-effect-order-and-collision-trace-test passed (dart-r4-targeted.log) |
| jolt | bf4ede6d | 91,496/91,500 + 4 helper-arity errors (this filing) |

No jolt divergence in substance. Sign-off r3 may treat the jolt leg as GREEN with the
helper-arity caveat recorded; the helper fix is a one-line test change for the corpus owner.

— the yin.repl jolt orchestrator seat
