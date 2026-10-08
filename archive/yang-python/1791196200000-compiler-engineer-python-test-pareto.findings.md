Created-GMT: 2026-10-05 17:15:50 GMT
Coding-Agent: claude (opus-5-5)
# Findings: Pareto set of slow Python tests, tagged on all three lanes

Scope: every namespace under test/yang/python/ (11) plus yin.vm.integer-test.
No test assertion or input was changed. No product code was changed. No git
writes. Every run was foreground and per namespace (no `bb test`).

## Result (fast form, Python namespaces, before -> after)

| lane | before run 1 | before run 2 | after run 1 | after run 2 | tests before -> after |
|---|---|---|---|---|---|
| JVM  | 550 s | 494 s | 117 s | 124 s wall (*) | 215 -> 152 |
| Node | 441 s | 430 s | 27 s | 27 s | 83 -> 83 (34 bodies SKIP) |
| Dart | 1030 s | 965 s | 103 s | 96 s | 83 -> 83 (34 bodies SKIP) |

(*) JVM after run 2 is the real runner, one process, wall time including
JVM start and loading: `clojure -M:test -e :slow -n <each of the 11 ns>`:
"Ran 152 tests containing 1212 assertions. 0 failures, 0 errors", 2:03.5.
The other JVM numbers are the sum of per-namespace run time from a timing
harness (target/prof/jvm_prof.clj, which excludes ^:slow like `-e :slow`).
Node: each namespace alone, `node target/prof/node-*.js --test=<ns>`
(shadow :node-test build with a :begin/:end-test-var timing reporter),
DATOM_SLOW_TESTS unset. Dart: each namespace alone, a one-file shard over
the generated test/cljd-out file (as cljd_agg does), `flutter test
--reporter json`; Dart numbers are flutter wall per namespace, about 4 s of
load each, so the aggregated lane will be a little lower.
All after-runs: 0 failures, 0 errors on every lane.

Target "under about 2 minutes per lane": met on Node (27 s) and Dart
(96-103 s). JVM is at 117-124 s, right at the 2-minute mark.

Per-namespace (before run 1 / run 2 -> after run 1 / run 2, seconds):

| ns | JVM | Node | Dart |
|---|---|---|---|
| safepoint-test | 170 / 150 -> 9.6 | 273 / 268 -> 8.1 / 8.0 | 513 / 505 -> 17.7 / 15.6 |
| e2e-test | 146 / 136 -> 23.4 | - | - |
| prelude-parity-test | 90 / 84 -> 23.2 | 144 / 138 -> 14.3 / 14.2 | 423 / 378 -> 59.4 / 62.2 |
| e2e-c1-test | 78 / 67 -> 6.0 | - | - |
| e2e-c2-test | 52 / 45 -> 48.5 | - | - |
| float-address-test | 14 / 11 -> 5.8 | 23 / 22 -> 3.8 / 3.8 | 75 / 71 -> 12.7 / 9.6 |
| lower-test, cst-export, scope | 0.3 / 0.4 -> 0.3 | - | - |
| lower-portable-test | 0.0 -> 0.0 | 0.7 / 0.5 -> 0.4 | 14.4 / 5.7 -> 5.3 / 4.3 |
| yin.vm.integer-test | 0.1 -> 0.0 | 0.6 / 0.5 -> 0.5 | 4.3 / 4.5 -> 7.4 / 3.9 |

## Tag policy actually applied (decisions to review)

1. ^:slow when the test is over 5 s on the JVM or Node (max of two runs),
   as the convention says.
2. ^:slow also for every JVM test with a mean of 3.0 s or more. The 5 s rule
   alone left the JVM fast form at about 243 s (most e2e tests sit on a
   3 s floor, see below). The JVM 80% Pareto set is ranks 1-67, down to
   2.9 s; cutting at 3.0 s is the same set minus three 2.9 s ties and
   reaches the 2-minute target. This tier (the e2e/e2e-c1 tests at 3.0-5 s)
   goes beyond the doc's "about 5 s" rule; it is the part to revert if you
   prefer the threshold to the target.
3. A .cljc test over 5 s on Dart only (JVM < 3 s, Node < 5 s): guard only,
   no ^:slow, so the JVM fast lane still runs it (15 tests). Note that this
   also removes them from the Node fast lane, where they take about 3 s.
4. Smoke exemptions (kept in the fast lane on every lane although over 5 s
   on Dart or 3 s on the JVM). No cross-host .cljc generator, dict or
   exception test runs in under 5 s on Dart: every prelude-on-every-VM test
   costs about 11 s there. So the cheapest representative ones stay:
   - generators: prelude-parity yield-from-and-iter-on-every-host-test,
     generator-throw-and-close-on-every-host-test (Dart 11.9, 12.6 s)
   - dict keys/hash: prelude-parity numeric-dict-keys-on-every-host-test,
     nan-keys-on-every-host-test (Dart 10.9, 10.9 s)
   - exceptions/safepoint: prelude-parity escapes-on-every-host-test
     (Dart 10.8 s), safepoint insertion-is-deterministic-test (Dart 5.2 s)
   - JVM e2e-c1 (otherwise entirely tagged): raise-in-finally-and-handler-
     stack-test (3.4 s), tuples-are-values-and-unhashable-lists-test (3.1 s)
   - integers/floats and lowering need no exemption: yin.vm.integer-test
     (19), float-address program-addresses-test and the 8 sub-second
     float-address tests, lower-portable-test (4), plus the JVM-only
     lower-test, cst-export-test and scope-test, are all well under 5 s
     on every lane.
   The 5 Dart smoke tests in prelude-parity cost about 55 s of Dart's 96 s.
   Dropping them would put Dart near 40 s but leave Node/Dart no cross-host
   Python-execution signal for generators, dict keys or exceptions.
   On the JVM, generators keep all of e2e-c2 (24 tests, 48 s) and the
   prelude-parity generator tests. Dict keys, exceptions and floats also
   keep their e2e and parity tests under 3 s.

Mechanism. .clj tests (e2e-test, e2e-c1-test): `^:slow` only.
.cljc tests: the guarded body is moved, verbatim and at the same
indentation, into a private no-arg `defn-` named after the test without
`-test`. The deftest becomes `(deftest ^:slow x-test (slow/guard "x-test"
x))`. This deviates from the literal `(fn [] ...)` wrapper because wrapping
in place re-indents each body by 4 to 14 columns, which added 48 net lines
over 80 columns in safepoint-test and prelude-parity-test. The guard gets
the same thunk either way. Diff: 5 test files, +296/-78 lines. kondo:
0 errors, 0 warnings. **cljstyle was blocked** (the call needs approval I
do not have here): neither `cljstyle fix` nor `check` was run. The new forms
follow the existing aligned guard layout (yin.repl.query-test and others),
so please run `cljstyle check` on the five files.

## Slow side verified

- JVM, `clojure -M:test -i :slow -v <each newly tagged var>` (63 vars, two
  processes, each under 600 s): e2e-test + e2e-c1-test 42 tests, 507
  assertions, 0 failures, 0 errors, 192 s wall. float-address +
  prelude-parity + safepoint 21 tests, 226 assertions, 0 failures,
  0 errors, 237 s wall. Selected with -v rather than plain `-i :slow -n ns`,
  because `-i :slow -n yang.python.antlr.e2e-test` also runs the
  pre-existing long-loops-test (about 14 min), which is over the 600 s cap.
  A full `-i :slow` per namespace, for you to run, takes about 160 s
  for safepoint (plus tail-preservation-test), 140 s plus 14 min for e2e,
  70 s for c1 (plus gate-round3-test), 65 s for parity and 8 s for float.
- Node, `DATOM_SLOW_TESTS=1 clj -M:cljs -m shadow.cljs.devtools.cli compile
  test --config-merge '{:ns-regexp "^yang\\.python\\.antlr\\.float-address-test$"}'`:
  16 tests (with lower-portable-test, which shadow pulls in), 119 assertions,
  0 failures, no SKIP line. Fast form of the same namespace: 75 assertions.
  Before tagging: 105, and 105 + 14 (lower-portable) = 119, so all three
  guarded bodies ran.
- Guard-only tests run on the JVM fast lane: they are in the 152-test
  `-e :slow` run above (0 failures).
- Node/Dart fast runs print one SKIP per guarded test: 3 in float-address,
  19 in prelude-parity, 15 in safepoint (14 new + tail-preservation).

## Test-side slowness (for your decision; nothing changed)

No single avoidable inefficiency in a test helper. The time is a per-test
floor from the end-to-end design. Each test lowers its program together with
the whole base prelude (26,644 datoms), then runs it on all four VMs. The
e2e helper `every-vm=` does that twice, naive and under no-op hooks. Measured
on the JVM (target/prof/floor.clj, warm, 3 rounds):
- `every-vm=` on `print(1)\n`: 2.6-3.0 s; that is the e2e floor.
- one `run-python` of `print(1)`: 1.2 s naive, 1.4 s under no-op hooks.
- inside that, recompiling the prelude per run: register `rc/adapt`
  0.31-0.43 s, stack `dl/adapt` 0.14-0.26 s.
- prelude-parity `run-with-prelude` floor: about 1.4 s on the JVM, 3 s on
  Node, 11 s on Dart per test (four VMs, prelude each time).
Caching a compiled prelude image across tests would change what a test
exercises: every program is lowered with its prelude and then compiled. It
would also need product support (a separately compiled prelude), so I left
it alone. Dart-specific outliers that look like candidates for a product
speed investigation, not test fixes: safepoint set-recursion-limit-test
(Dart 130 s, Node 39 s, JVM 17 s), prelude-parity int-defects-stay-host-
failures-on-every-host-test (Dart 42 s, JVM 5.6 s), safepoint
admission-in-every-mode-test (Dart 39 s, JVM 5.6 s).

## What was not measured

- The whole fast lane was not re-measured, so the docs "Measured
  fast-lane times" comment is unchanged. One line naming the Python slow
  set and the landing command was added to docs/agents/build-n-test.md.
- Dart was measured per namespace in its own flutter process, not in the
  aggregated `bb src/dev/cljd_agg.clj` shards.
- Pre-existing ^:slow tests (long-loops-test, recursion-test,
  keyboard-interrupt-test, gate-round3-test, long-generator-with-break-test,
  yield-from-long-range-test, resume-continuation-length-is-stable-test,
  reachable-heap-is-stable-under-delegation-test, tail-preservation-test)
  were excluded from every measurement, before and after.
- Outside the stated scope and not profiled: yang.python-test
  (test/yang/python_test.clj) and yang.safepoint-test.
- Machine load was high and uneven (other work ran concurrently). The two
  runs differ by up to about 25% on single tests (e.g. Dart
  int-limits-are-catchable 29.0 / 18.7 s). Both runs are in the tables.
- Profiling tooling and raw EDN are under target/prof/ (not committed):
  run.clj (driver), jvm_prof.clj, prof/timer_test.cljs, pareto.clj,
  decide.clj, apply.clj, floor.clj; `<lane>-pre.{1,2}.edn` and
  `<lane>-post.{1,2}.edn`.

## Tag list

Columns: action | ns | test | JVM mean/max s | Node max s | Dart max s | file kind.
Times are from the two before-runs.

| action | ns | test | JVM mean/max | Node | Dart | kind |
|---|---|---|---|---|---|---|
| ^:slow | e2e-c1-test | arithmetic-operators-test | 3.7/3.9 | - | - | clj |
| ^:slow | e2e-c1-test | comprehension-scope-test | 3.6/3.7 | - | - | clj |
| ^:slow | e2e-c1-test | comprehensions-test | 4.1/4.2 | - | - | clj |
| ^:slow | e2e-c1-test | finally-nesting-and-override-test | 3.8/4.4 | - | - | clj |
| ^:slow | e2e-c1-test | finally-runs-on-every-exit-test | 6.2/7.2 | - | - | clj |
| ^:slow | e2e-c1-test | float-zero-and-infinity-test | 3.5/3.7 | - | - | clj |
| ^:slow | e2e-c1-test | gate-p3-round2-test | 5.8/5.9 | - | - | clj |
| ^:slow | e2e-c1-test | gate-p3-runtime-test | 3.3/3.5 | - | - | clj |
| ^:slow | e2e-c1-test | generator-consumer-rebound-test | 3.1/3.3 | - | - | clj |
| ^:slow | e2e-c1-test | integer-bound-test | 5.0/5.3 | - | - | clj |
| ^:slow | e2e-c1-test | keyword-arguments-test | 4.1/4.3 | - | - | clj |
| ^:slow | e2e-c1-test | membership-and-augmented-test | 3.6/3.7 | - | - | clj |
| smoke (kept) | e2e-c1-test | raise-in-finally-and-handler-stack-test | 3.4/3.9 | - | - | clj |
| ^:slow | e2e-c1-test | slices-test | 3.3/3.6 | - | - | clj |
| ^:slow | e2e-c1-test | tuple-of-classes-test | 4.3/4.5 | - | - | clj |
| ^:slow | e2e-c1-test | tuples-and-unpacking-test | 4.0/4.5 | - | - | clj |
| smoke (kept) | e2e-c1-test | tuples-are-values-and-unhashable-lists-test | 3.1/3.3 | - | - | clj |
| ^:slow | e2e-c1-test | with-statement-test | 4.7/5.4 | - | - | clj |
| ^:slow | e2e-test | arithmetic-and-print-test | 4.5/4.6 | - | - | clj |
| ^:slow | e2e-test | builtin-fallback-before-module-binding-test | 3.3/3.4 | - | - | clj |
| ^:slow | e2e-test | class-body-reads-class-namespace-then-globals-test | 3.0/3.0 | - | - | clj |
| ^:slow | e2e-test | classes-test | 3.2/3.5 | - | - | clj |
| ^:slow | e2e-test | closures-and-nonlocal-test | 3.4/3.5 | - | - | clj |
| ^:slow | e2e-test | escape-restores-handlers-test | 3.3/3.5 | - | - | clj |
| ^:slow | e2e-test | exact-numeric-keys-test | 3.4/3.6 | - | - | clj |
| ^:slow | e2e-test | floats-are-tagged-test | 3.3/3.4 | - | - | clj |
| ^:slow | e2e-test | for-range-list-else-test | 3.3/3.6 | - | - | clj |
| ^:slow | e2e-test | forward-capture-test | 3.2/3.3 | - | - | clj |
| ^:slow | e2e-test | function-objects-test | 3.5/3.6 | - | - | clj |
| ^:slow | e2e-test | global-declaration-test | 3.2/3.3 | - | - | clj |
| ^:slow | e2e-test | integer-is-test | 3.2/3.4 | - | - | clj |
| ^:slow | e2e-test | integer-limits-are-guest-exceptions-test | 17.0/17.6 | - | - | clj |
| ^:slow | e2e-test | keyboard-interrupt-is-a-builtin-test | 5.7/6.1 | - | - | clj |
| ^:slow | e2e-test | lambda-test | 3.3/3.5 | - | - | clj |
| ^:slow | e2e-test | module-namespace-is-a-dict-test | 3.1/3.2 | - | - | clj |
| ^:slow | e2e-test | numeric-hash-test | 6.3/6.6 | - | - | clj |
| ^:slow | e2e-test | recursion-under-real-hooks-test | 15.7/16.5 | - | - | clj |
| ^:slow | e2e-test | too-many-arguments-test | 3.2/3.2 | - | - | clj |
| ^:slow | e2e-test | try-else-and-reraise-test | 3.0/3.2 | - | - | clj |
| ^:slow | e2e-test | try-except-raise-test | 3.4/3.7 | - | - | clj |
| ^:slow | e2e-test | unbound-local-test | 3.1/3.3 | - | - | clj |
| ^:slow | e2e-test | undefined-global-is-name-error-test | 3.1/3.2 | - | - | clj |
| ^:slow | e2e-test | unhashable-test | 3.3/3.5 | - | - | clj |
| ^:slow | e2e-test | while-break-continue-test | 3.4/3.5 | - | - | clj |
| guard only | float-address-test | dict-keys-test | 2.5/2.7 | 4.5 | 16.4 | cljc |
| ^:slow + guard | float-address-test | nan-test | 2.6/2.9 | 6.2 | 23.0 | cljc |
| ^:slow + guard | float-address-test | runs-on-every-vm-test | 4.7/5.3 | 7.9 | 28.1 | cljc |
| ^:slow + guard | prelude-parity-test | big-integer-keys-past-the-digit-limit-on-every-host-test | 2.9/3.1 | 6.0 | 22.8 | cljc |
| guard only | prelude-parity-test | delegation-chain-callers-and-unwinding-on-every-host-test | 1.7/1.7 | 3.5 | 12.8 | cljc |
| guard only | prelude-parity-test | delegation-stop-and-sticky-dict-iterator-on-every-host-test | 1.5/1.6 | 3.1 | 11.0 | cljc |
| smoke (kept) | prelude-parity-test | escapes-on-every-host-test | 1.4/1.4 | 3.1 | 11.0 | cljc |
| ^:slow + guard | prelude-parity-test | failed-key-normalization-leaves-containers-unchanged-test | 10.5/11.6 | 14.1 | 18.8 | cljc |
| ^:slow + guard | prelude-parity-test | float-is-on-every-host-test | 3.5/3.7 | 7.5 | 29.9 | cljc |
| guard only | prelude-parity-test | generator-expression-admission-on-every-host-test | 1.4/1.5 | 3.2 | 11.2 | cljc |
| guard only | prelude-parity-test | generator-expression-on-every-host-test | 1.6/1.6 | 3.4 | 13.9 | cljc |
| guard only | prelude-parity-test | generator-switch-on-every-host-test | 1.4/1.4 | 3.0 | 17.3 | cljc |
| smoke (kept) | prelude-parity-test | generator-throw-and-close-on-every-host-test | 1.7/1.7 | 3.1 | 13.7 | cljc |
| guard only | prelude-parity-test | generator-throw-non-exception-class-on-every-host-test | 1.3/1.4 | 3.0 | 11.4 | cljc |
| ^:slow + guard | prelude-parity-test | int-defects-stay-host-failures-on-every-host-test | 5.6/5.9 | 14.7 | 42.9 | cljc |
| ^:slow + guard | prelude-parity-test | int-limits-are-catchable-on-every-host-test | 4.5/4.7 | 6.7 | 29.0 | cljc |
| guard only | prelude-parity-test | integer-bound-on-every-host-test | 1.9/1.9 | 3.9 | 16.5 | cljc |
| smoke (kept) | prelude-parity-test | nan-keys-on-every-host-test | 1.5/1.5 | 3.0 | 11.2 | cljc |
| smoke (kept) | prelude-parity-test | numeric-dict-keys-on-every-host-test | 1.5/1.6 | 4.4 | 10.9 | cljc |
| ^:slow + guard | prelude-parity-test | numeric-keys-hash-and-is-on-every-host-test | 22.3/23.9 | 28.5 | 36.9 | cljc |
| ^:slow + guard | prelude-parity-test | prelude-semantics-on-every-vm-test | 7.8/8.4 | 12.7 | 23.8 | cljc |
| guard only | prelude-parity-test | preserved-key-arms-on-every-host-test | 1.5/1.5 | 3.1 | 17.0 | cljc |
| guard only | prelude-parity-test | printed-floats-on-every-host-test | 1.4/1.5 | 3.0 | 12.4 | cljc |
| ^:slow + guard | prelude-parity-test | range-fast-path-on-every-host-test | 5.7/5.9 | 7.2 | 13.2 | cljc |
| guard only | prelude-parity-test | signed-zero-floats-on-every-host-test | 1.2/1.3 | 2.7 | 12.2 | cljc |
| guard only | prelude-parity-test | unhashable-on-every-host-test | 1.4/1.4 | 2.9 | 11.3 | cljc |
| smoke (kept) | prelude-parity-test | yield-from-and-iter-on-every-host-test | 1.5/1.6 | 3.3 | 12.2 | cljc |
| ^:slow + guard | safepoint-test | admission-in-every-mode-test | 5.6/5.8 | 11.5 | 39.9 | cljc |
| ^:slow + guard | safepoint-test | delegation-admission-test | 17.0/17.7 | 27.4 | 33.6 | cljc |
| ^:slow + guard | safepoint-test | escape-restores-depth-test | 11.5/12.0 | 18.5 | 35.7 | cljc |
| guard only | safepoint-test | fail-closed-test | 1.7/1.7 | 4.0 | 12.6 | cljc |
| ^:slow + guard | safepoint-test | generator-admission-test | 7.8/8.3 | 12.7 | 21.9 | cljc |
| ^:slow + guard | safepoint-test | generator-depth-test | 14.3/15.0 | 22.4 | 30.1 | cljc |
| ^:slow + guard | safepoint-test | generator-rebase-test | 14.3/14.6 | 23.4 | 32.8 | cljc |
| ^:slow + guard | safepoint-test | generator-throw-close-depth-test | 9.5/9.7 | 16.0 | 22.9 | cljc |
| smoke (kept) | safepoint-test | insertion-is-deterministic-test | 1.6/1.6 | 3.8 | 5.4 | cljc |
| ^:slow + guard | safepoint-test | interrupt-test | 3.4/3.4 | 8.1 | 26.5 | cljc |
| ^:slow + guard | safepoint-test | no-op-hooks-are-transparent-test | 3.5/3.7 | 6.8 | 24.4 | cljc |
| guard only | safepoint-test | no-park-test | 1.9/2.0 | 4.1 | 13.0 | cljc |
| ^:slow + guard | safepoint-test | recursion-error-test | 47.8/53.8 | 68.0 | 67.6 | cljc |
| guard only | safepoint-test | registered-handler-runs-test | 1.9/1.9 | 4.1 | 13.0 | cljc |
| ^:slow + guard | safepoint-test | set-recursion-limit-test | 16.8/17.7 | 40.0 | 131.6 | cljc |

## Pareto tables (fast form, before tagging)

Rows with a mean of 0.5 s or less are omitted but counted in totals and percentages.

### jvm: 215 tests, 522 s (mean of runs)

| # | ns | test | run1 s | run2 s | mean s | cum % |
|---|---|---|---|---|---|---|
| 1 | safepoint-test | recursion-error-test | 53.8 | 41.8 | 47.8 | 9 |
| 2 | prelude-parity-test | numeric-keys-hash-and-is-on-every-host-test | 23.9 | 20.8 | 22.3 | 13 |
| 3 | e2e-test | integer-limits-are-guest-exceptions-test | 17.6 | 16.3 | 17.0 | 17 |
| 4 | safepoint-test | delegation-admission-test | 17.7 | 16.2 | 17.0 | 20 |
| 5 | safepoint-test | set-recursion-limit-test | 17.7 | 15.9 | 16.8 | 23 |
| 6 | e2e-test | recursion-under-real-hooks-test | 14.9 | 16.5 | 15.7 | 26 |
| 7 | safepoint-test | generator-depth-test | 15.0 | 13.5 | 14.3 | 29 |
| 8 | safepoint-test | generator-rebase-test | 14.6 | 13.9 | 14.3 | 32 |
| 9 | safepoint-test | escape-restores-depth-test | 12.0 | 10.9 | 11.5 | 34 |
| 10 | prelude-parity-test | failed-key-normalization-leaves-containers-unchanged-test | 11.6 | 9.3 | 10.5 | 36 |
| 11 | safepoint-test | generator-throw-close-depth-test | 9.7 | 9.3 | 9.5 | 38 |
| 12 | prelude-parity-test | prelude-semantics-on-every-vm-test | 7.3 | 8.4 | 7.8 | 39 |
| 13 | safepoint-test | generator-admission-test | 8.3 | 7.4 | 7.8 | 41 |
| 14 | e2e-test | numeric-hash-test | 6.6 | 5.9 | 6.3 | 42 |
| 15 | e2e-c1-test | finally-runs-on-every-exit-test | 7.2 | 5.1 | 6.2 | 43 |
| 16 | e2e-c1-test | gate-p3-round2-test | 5.9 | 5.7 | 5.8 | 44 |
| 17 | e2e-test | keyboard-interrupt-is-a-builtin-test | 6.1 | 5.3 | 5.7 | 45 |
| 18 | prelude-parity-test | range-fast-path-on-every-host-test | 5.9 | 5.6 | 5.7 | 46 |
| 19 | prelude-parity-test | int-defects-stay-host-failures-on-every-host-test | 5.3 | 5.9 | 5.6 | 47 |
| 20 | safepoint-test | admission-in-every-mode-test | 5.8 | 5.3 | 5.6 | 48 |
| 21 | e2e-c1-test | integer-bound-test | 5.3 | 4.8 | 5.0 | 49 |
| 22 | float-address-test | runs-on-every-vm-test | 5.3 | 4.1 | 4.7 | 50 |
| 23 | e2e-c1-test | with-statement-test | 5.4 | 4.0 | 4.7 | 51 |
| 24 | prelude-parity-test | int-limits-are-catchable-on-every-host-test | 4.7 | 4.4 | 4.5 | 52 |
| 25 | e2e-test | arithmetic-and-print-test | 4.4 | 4.6 | 4.5 | 53 |
| 26 | e2e-c1-test | tuple-of-classes-test | 4.5 | 4.1 | 4.3 | 54 |
| 27 | e2e-c1-test | comprehensions-test | 4.2 | 4.1 | 4.1 | 55 |
| 28 | e2e-c1-test | keyword-arguments-test | 4.3 | 3.9 | 4.1 | 55 |
| 29 | e2e-c1-test | tuples-and-unpacking-test | 4.5 | 3.5 | 4.0 | 56 |
| 30 | e2e-c1-test | finally-nesting-and-override-test | 4.4 | 3.2 | 3.8 | 57 |
| 31 | e2e-c1-test | arithmetic-operators-test | 3.9 | 3.5 | 3.7 | 58 |
| 32 | e2e-c1-test | membership-and-augmented-test | 3.7 | 3.5 | 3.6 | 58 |
| 33 | e2e-c1-test | comprehension-scope-test | 3.5 | 3.7 | 3.6 | 59 |
| 34 | e2e-c1-test | float-zero-and-infinity-test | 3.7 | 3.4 | 3.5 | 60 |
| 35 | e2e-test | function-objects-test | 3.6 | 3.4 | 3.5 | 60 |
| 36 | prelude-parity-test | float-is-on-every-host-test | 3.7 | 3.2 | 3.5 | 61 |
| 37 | safepoint-test | no-op-hooks-are-transparent-test | 3.3 | 3.7 | 3.5 | 62 |
| 38 | e2e-c1-test | raise-in-finally-and-handler-stack-test | 3.9 | 3.0 | 3.4 | 62 |
| 39 | e2e-test | while-break-continue-test | 3.5 | 3.3 | 3.4 | 63 |
| 40 | e2e-test | try-except-raise-test | 3.7 | 3.1 | 3.4 | 64 |
| 41 | safepoint-test | interrupt-test | 3.4 | 3.3 | 3.4 | 64 |
| 42 | e2e-test | exact-numeric-keys-test | 3.6 | 3.1 | 3.4 | 65 |
| 43 | e2e-test | closures-and-nonlocal-test | 3.5 | 3.2 | 3.4 | 66 |
| 44 | e2e-test | builtin-fallback-before-module-binding-test | 3.3 | 3.4 | 3.3 | 66 |
| 45 | e2e-test | for-range-list-else-test | 3.6 | 3.0 | 3.3 | 67 |
| 46 | e2e-test | floats-are-tagged-test | 3.4 | 3.2 | 3.3 | 67 |
| 47 | e2e-test | lambda-test | 3.5 | 3.1 | 3.3 | 68 |
| 48 | e2e-c1-test | slices-test | 3.6 | 3.0 | 3.3 | 69 |
| 49 | e2e-c1-test | gate-p3-runtime-test | 3.5 | 3.1 | 3.3 | 69 |
| 50 | e2e-test | unhashable-test | 3.5 | 3.0 | 3.3 | 70 |
| 51 | e2e-test | escape-restores-handlers-test | 3.5 | 3.0 | 3.3 | 71 |
| 52 | e2e-test | classes-test | 3.5 | 3.0 | 3.2 | 71 |
| 53 | e2e-test | forward-capture-test | 3.3 | 3.1 | 3.2 | 72 |
| 54 | e2e-test | global-declaration-test | 3.3 | 3.1 | 3.2 | 72 |
| 55 | e2e-test | integer-is-test | 3.4 | 2.9 | 3.2 | 73 |
| 56 | e2e-test | too-many-arguments-test | 3.2 | 3.1 | 3.2 | 74 |
| 57 | e2e-test | module-namespace-is-a-dict-test | 3.2 | 3.0 | 3.1 | 74 |
| 58 | e2e-c1-test | generator-consumer-rebound-test | 3.3 | 2.9 | 3.1 | 75 |
| 59 | e2e-test | unbound-local-test | 3.3 | 2.8 | 3.1 | 75 |
| 60 | e2e-c1-test | tuples-are-values-and-unhashable-lists-test | 3.3 | 2.9 | 3.1 | 76 |
| 61 | e2e-test | undefined-global-is-name-error-test | 3.0 | 3.2 | 3.1 | 77 |
| 62 | e2e-test | try-else-and-reraise-test | 3.2 | 2.8 | 3.0 | 77 |
| 63 | e2e-test | class-body-reads-class-namespace-then-globals-test | 3.0 | 3.0 | 3.0 | 78 |
| 64 | e2e-test | class-attributes-and-identity-test | 3.3 | 2.7 | 3.0 | 78 |
| 65 | prelude-parity-test | big-integer-keys-past-the-digit-limit-on-every-host-test | 3.1 | 2.7 | 2.9 | 79 |
| 66 | e2e-test | lists-alias-and-index-test | 3.1 | 2.7 | 2.9 | 79 |
| 67 | e2e-test | boolean-operators-test | 3.0 | 2.8 | 2.9 | 80 |
| 68 | e2e-test | strings-test | 3.0 | 2.8 | 2.9 | 81 |
| 69 | e2e-test | recursion-error-is-a-builtin-test | 3.0 | 2.7 | 2.9 | 81 |
| 70 | e2e-test | zero-division-test | 3.1 | 2.6 | 2.9 | 82 |
| 71 | e2e-test | dict-order-and-key-normalization-test | 3.1 | 2.7 | 2.9 | 82 |
| 72 | e2e-test | uncaught-exception-test | 3.0 | 2.6 | 2.8 | 83 |
| 73 | float-address-test | program-addresses-test | 3.0 | 2.6 | 2.8 | 83 |
| 74 | float-address-test | nan-test | 2.9 | 2.3 | 2.6 | 84 |
| 75 | e2e-c2-test | genexp-nesting-and-call-arguments-test | 2.6 | 2.5 | 2.5 | 84 |
| 76 | float-address-test | dict-keys-test | 2.7 | 2.2 | 2.5 | 85 |
| 77 | e2e-c2-test | genexp-laziness-test | 2.3 | 2.1 | 2.2 | 85 |
| 78 | e2e-c2-test | genexp-generator-protocol-test | 2.1 | 2.1 | 2.1 | 86 |
| 79 | e2e-c2-test | generator-while-loop-test | 2.6 | 1.4 | 2.0 | 86 |
| 80 | prelude-parity-test | integer-bound-on-every-host-test | 1.9 | 1.9 | 1.9 | 86 |
| 81 | e2e-c2-test | yield-from-thrown-stop-iteration-test | 2.0 | 1.8 | 1.9 | 87 |
| 82 | safepoint-test | registered-handler-runs-test | 1.9 | 1.9 | 1.9 | 87 |
| 83 | e2e-c2-test | iter-protocol-test | 1.9 | 1.8 | 1.9 | 87 |
| 84 | e2e-c2-test | with-around-yield-test | 2.0 | 1.7 | 1.9 | 88 |
| 85 | safepoint-test | no-park-test | 2.0 | 1.8 | 1.9 | 88 |
| 86 | e2e-c2-test | yield-from-chain-with-changing-callers-test | 1.9 | 1.8 | 1.8 | 88 |
| 87 | e2e-c2-test | suspension-during-unwinding-test | 1.9 | 1.8 | 1.8 | 89 |
| 88 | e2e-c2-test | throw-caught-at-yield-site-test | 2.0 | 1.7 | 1.8 | 89 |
| 89 | e2e-c2-test | yield-from-throw-through-test | 1.9 | 1.7 | 1.8 | 90 |
| 90 | e2e-c2-test | genexp-scopes-test | 1.8 | 1.7 | 1.8 | 90 |
| 91 | e2e-c2-test | close-outcomes-test | 1.9 | 1.6 | 1.7 | 90 |
| 92 | safepoint-test | fail-closed-test | 1.7 | 1.7 | 1.7 | 91 |
| 93 | e2e-c2-test | pep-479-test | 1.9 | 1.6 | 1.7 | 91 |
| 94 | e2e-c2-test | genexp-outermost-iterable-at-creation-test | 1.8 | 1.6 | 1.7 | 91 |
| 95 | e2e-c2-test | yield-from-suspension-during-unwinding-test | 1.7 | 1.6 | 1.7 | 92 |
| 96 | e2e-c2-test | genexp-pep-479-test | 1.7 | 1.6 | 1.7 | 92 |
| 97 | prelude-parity-test | generator-throw-and-close-on-every-host-test | 1.6 | 1.7 | 1.7 | 92 |
| 98 | prelude-parity-test | delegation-chain-callers-and-unwinding-on-every-host-test | 1.7 | 1.6 | 1.7 | 92 |
| 99 | prelude-parity-test | generator-expression-on-every-host-test | 1.6 | 1.6 | 1.6 | 93 |
| 100 | e2e-c2-test | generator-already-executing-test | 1.7 | 1.4 | 1.6 | 93 |
| 101 | safepoint-test | insertion-is-deterministic-test | 1.6 | 1.5 | 1.6 | 93 |
| 102 | e2e-c2-test | yield-from-close-order-test | 1.6 | 1.5 | 1.6 | 94 |
| 103 | e2e-c2-test | throw-uncaught-and-unstarted-test | 1.7 | 1.4 | 1.6 | 94 |
| 104 | prelude-parity-test | numeric-dict-keys-on-every-host-test | 1.5 | 1.6 | 1.5 | 94 |
| 105 | prelude-parity-test | delegation-stop-and-sticky-dict-iterator-on-every-host-test | 1.6 | 1.5 | 1.5 | 95 |
| 106 | e2e-c2-test | generator-body-is-lazy-test | 1.7 | 1.4 | 1.5 | 95 |
| 107 | prelude-parity-test | yield-from-and-iter-on-every-host-test | 1.6 | 1.5 | 1.5 | 95 |
| 108 | e2e-c2-test | send-and-iterator-methods-test | 1.6 | 1.4 | 1.5 | 95 |
| 109 | e2e-c2-test | generator-return-value-test | 1.7 | 1.3 | 1.5 | 96 |
| 110 | prelude-parity-test | preserved-key-arms-on-every-host-test | 1.5 | 1.5 | 1.5 | 96 |
| 111 | e2e-c2-test | close-runs-finally-test | 1.6 | 1.4 | 1.5 | 96 |
| 112 | e2e-c2-test | yield-from-send-through-test | 1.5 | 1.4 | 1.5 | 97 |
| 113 | e2e-c2-test | yield-expression-send-test | 1.6 | 1.3 | 1.5 | 97 |
| 114 | prelude-parity-test | nan-keys-on-every-host-test | 1.4 | 1.5 | 1.5 | 97 |
| 115 | e2e-c2-test | throw-non-exception-class-test | 1.6 | 1.3 | 1.4 | 97 |
| 116 | prelude-parity-test | generator-expression-admission-on-every-host-test | 1.4 | 1.5 | 1.4 | 98 |
| 117 | prelude-parity-test | printed-floats-on-every-host-test | 1.5 | 1.3 | 1.4 | 98 |
| 118 | prelude-parity-test | unhashable-on-every-host-test | 1.4 | 1.4 | 1.4 | 98 |
| 119 | e2e-c2-test | yield-in-nested-default-test | 1.5 | 1.3 | 1.4 | 98 |
| 120 | prelude-parity-test | escapes-on-every-host-test | 1.4 | 1.3 | 1.4 | 99 |
| 121 | prelude-parity-test | generator-switch-on-every-host-test | 1.4 | 1.4 | 1.4 | 99 |
| 122 | prelude-parity-test | generator-throw-non-exception-class-on-every-host-test | 1.4 | 1.3 | 1.3 | 99 |
| 123 | prelude-parity-test | signed-zero-floats-on-every-host-test | 1.3 | 1.2 | 1.2 | 100 |
| 124 | safepoint-test | marks-reach-the-side-table-only-test | 0.7 | 0.8 | 0.7 | 100 |

80% set: 67 tests of 215; rest (<= 0.5 s) not listed

Per-namespace totals (mean):

| ns | tests | s |
|---|---|---|
| safepoint-test | 21 | 160.3 |
| e2e-test | 36 | 140.6 |
| prelude-parity-test | 26 | 86.8 |
| e2e-c1-test | 18 | 72.7 |
| e2e-c2-test | 28 | 48.5 |
| float-address-test | 12 | 12.8 |
| lower-test | 28 | 0.2 |
| yin.vm.integer-test | 19 | 0.1 |
| cst-export-test | 12 | 0.1 |
| scope-test | 11 | 0.0 |
| lower-portable-test | 4 | 0.0 |

### node: 83 tests, 433 s (mean of runs)

| # | ns | test | run1 s | run2 s | mean s | cum % |
|---|---|---|---|---|---|---|
| 1 | safepoint-test | recursion-error-test | 66.9 | 68.0 | 67.5 | 16 |
| 2 | safepoint-test | set-recursion-limit-test | 40.0 | 37.2 | 38.6 | 24 |
| 3 | prelude-parity-test | numeric-keys-hash-and-is-on-every-host-test | 28.5 | 26.5 | 27.5 | 31 |
| 4 | safepoint-test | delegation-admission-test | 27.4 | 26.0 | 26.7 | 37 |
| 5 | safepoint-test | generator-rebase-test | 23.4 | 22.4 | 22.9 | 42 |
| 6 | safepoint-test | generator-depth-test | 22.4 | 22.4 | 22.4 | 47 |
| 7 | safepoint-test | escape-restores-depth-test | 18.1 | 18.5 | 18.3 | 52 |
| 8 | safepoint-test | generator-throw-close-depth-test | 16.0 | 15.4 | 15.7 | 55 |
| 9 | prelude-parity-test | failed-key-normalization-leaves-containers-unchanged-test | 14.1 | 13.8 | 14.0 | 58 |
| 10 | prelude-parity-test | int-defects-stay-host-failures-on-every-host-test | 11.1 | 14.7 | 12.9 | 61 |
| 11 | safepoint-test | generator-admission-test | 12.7 | 12.5 | 12.6 | 64 |
| 12 | prelude-parity-test | prelude-semantics-on-every-vm-test | 12.7 | 11.6 | 12.1 | 67 |
| 13 | safepoint-test | admission-in-every-mode-test | 11.5 | 11.3 | 11.4 | 70 |
| 14 | float-address-test | runs-on-every-vm-test | 7.9 | 7.9 | 7.9 | 72 |
| 15 | safepoint-test | interrupt-test | 7.3 | 8.1 | 7.7 | 73 |
| 16 | prelude-parity-test | float-is-on-every-host-test | 7.5 | 6.4 | 7.0 | 75 |
| 17 | prelude-parity-test | range-fast-path-on-every-host-test | 7.2 | 6.6 | 6.9 | 77 |
| 18 | safepoint-test | no-op-hooks-are-transparent-test | 6.7 | 6.8 | 6.7 | 78 |
| 19 | prelude-parity-test | int-limits-are-catchable-on-every-host-test | 6.7 | 6.3 | 6.5 | 80 |
| 20 | float-address-test | nan-test | 6.2 | 6.0 | 6.1 | 81 |
| 21 | prelude-parity-test | big-integer-keys-past-the-digit-limit-on-every-host-test | 6.0 | 5.3 | 5.6 | 82 |
| 22 | float-address-test | dict-keys-test | 4.5 | 4.2 | 4.4 | 83 |
| 23 | safepoint-test | registered-handler-runs-test | 4.1 | 3.9 | 4.0 | 84 |
| 24 | safepoint-test | no-park-test | 3.8 | 4.1 | 3.9 | 85 |
| 25 | safepoint-test | fail-closed-test | 3.8 | 4.0 | 3.9 | 86 |
| 26 | safepoint-test | insertion-is-deterministic-test | 3.8 | 3.7 | 3.8 | 87 |
| 27 | prelude-parity-test | numeric-dict-keys-on-every-host-test | 3.0 | 4.4 | 3.7 | 88 |
| 28 | prelude-parity-test | integer-bound-on-every-host-test | 3.9 | 3.2 | 3.6 | 89 |
| 29 | prelude-parity-test | delegation-chain-callers-and-unwinding-on-every-host-test | 3.5 | 3.1 | 3.3 | 89 |
| 30 | float-address-test | program-addresses-test | 3.4 | 3.1 | 3.3 | 90 |
| 31 | prelude-parity-test | generator-expression-on-every-host-test | 3.4 | 3.0 | 3.2 | 91 |
| 32 | prelude-parity-test | yield-from-and-iter-on-every-host-test | 3.3 | 2.9 | 3.1 | 92 |
| 33 | prelude-parity-test | generator-expression-admission-on-every-host-test | 3.2 | 2.9 | 3.0 | 92 |
| 34 | prelude-parity-test | preserved-key-arms-on-every-host-test | 3.1 | 2.9 | 3.0 | 93 |
| 35 | prelude-parity-test | delegation-stop-and-sticky-dict-iterator-on-every-host-test | 3.1 | 2.9 | 3.0 | 94 |
| 36 | prelude-parity-test | escapes-on-every-host-test | 3.1 | 2.7 | 2.9 | 94 |
| 37 | prelude-parity-test | generator-throw-and-close-on-every-host-test | 3.1 | 2.7 | 2.9 | 95 |
| 38 | prelude-parity-test | printed-floats-on-every-host-test | 3.0 | 2.7 | 2.9 | 96 |
| 39 | prelude-parity-test | nan-keys-on-every-host-test | 3.0 | 2.8 | 2.9 | 96 |
| 40 | prelude-parity-test | generator-switch-on-every-host-test | 3.0 | 2.7 | 2.8 | 97 |
| 41 | prelude-parity-test | generator-throw-non-exception-class-on-every-host-test | 3.0 | 2.7 | 2.8 | 98 |
| 42 | prelude-parity-test | unhashable-on-every-host-test | 2.9 | 2.7 | 2.8 | 98 |
| 43 | prelude-parity-test | signed-zero-floats-on-every-host-test | 2.7 | 2.5 | 2.6 | 99 |
| 44 | safepoint-test | marks-reach-the-side-table-only-test | 1.2 | 1.0 | 1.1 | 99 |
| 45 | safepoint-test | canonical-untouched-test | 1.1 | 1.1 | 1.1 | 99 |
| 46 | safepoint-test | no-hook-under-a-prelude-definition-test | 1.0 | 1.0 | 1.0 | 100 |
| 47 | safepoint-test | empty-profile-is-the-identity-test | 0.8 | 0.7 | 0.7 | 100 |

80% set: 20 tests of 83; rest (<= 0.5 s) not listed

Per-namespace totals (mean):

| ns | tests | s |
|---|---|---|
| safepoint-test | 22 | 270.1 |
| prelude-parity-test | 26 | 141.0 |
| float-address-test | 12 | 22.0 |
| yin.vm.integer-test | 19 | 0.2 |
| lower-portable-test | 4 | 0.1 |

### dart: 83 tests, 972 s (mean of runs)

| # | ns | test | run1 s | run2 s | mean s | cum % |
|---|---|---|---|---|---|---|
| 1 | safepoint-test | set-recursion-limit-test | 128.4 | 131.6 | 130.0 | 13 |
| 2 | safepoint-test | recursion-error-test | 66.0 | 67.6 | 66.8 | 20 |
| 3 | prelude-parity-test | int-defects-stay-host-failures-on-every-host-test | 41.8 | 42.9 | 42.3 | 25 |
| 4 | safepoint-test | admission-in-every-mode-test | 39.9 | 38.8 | 39.4 | 29 |
| 5 | prelude-parity-test | numeric-keys-hash-and-is-on-every-host-test | 36.9 | 30.6 | 33.8 | 32 |
| 6 | safepoint-test | delegation-admission-test | 33.6 | 32.9 | 33.2 | 36 |
| 7 | safepoint-test | escape-restores-depth-test | 35.7 | 28.7 | 32.2 | 39 |
| 8 | safepoint-test | generator-rebase-test | 32.8 | 29.3 | 31.1 | 42 |
| 9 | safepoint-test | generator-depth-test | 29.5 | 30.1 | 29.8 | 45 |
| 10 | prelude-parity-test | float-is-on-every-host-test | 29.9 | 28.5 | 29.2 | 48 |
| 11 | float-address-test | runs-on-every-vm-test | 27.8 | 28.1 | 28.0 | 51 |
| 12 | safepoint-test | interrupt-test | 25.4 | 26.5 | 26.0 | 54 |
| 13 | safepoint-test | no-op-hooks-are-transparent-test | 24.4 | 23.6 | 24.0 | 56 |
| 14 | prelude-parity-test | int-limits-are-catchable-on-every-host-test | 29.0 | 18.7 | 23.9 | 59 |
| 15 | float-address-test | nan-test | 23.0 | 22.8 | 22.9 | 61 |
| 16 | safepoint-test | generator-throw-close-depth-test | 22.9 | 22.8 | 22.8 | 63 |
| 17 | prelude-parity-test | big-integer-keys-past-the-digit-limit-on-every-host-test | 22.8 | 22.0 | 22.4 | 66 |
| 18 | prelude-parity-test | prelude-semantics-on-every-vm-test | 18.9 | 23.8 | 21.3 | 68 |
| 19 | safepoint-test | generator-admission-test | 21.9 | 20.7 | 21.3 | 70 |
| 20 | prelude-parity-test | failed-key-normalization-leaves-containers-unchanged-test | 18.8 | 18.5 | 18.6 | 72 |
| 21 | prelude-parity-test | preserved-key-arms-on-every-host-test | 17.0 | 12.0 | 14.5 | 73 |
| 22 | float-address-test | dict-keys-test | 16.4 | 12.3 | 14.3 | 75 |
| 23 | prelude-parity-test | generator-switch-on-every-host-test | 17.3 | 10.6 | 13.9 | 76 |
| 24 | prelude-parity-test | integer-bound-on-every-host-test | 16.5 | 11.1 | 13.8 | 78 |
| 25 | safepoint-test | registered-handler-runs-test | 12.9 | 13.0 | 13.0 | 79 |
| 26 | prelude-parity-test | range-fast-path-on-every-host-test | 13.2 | 12.5 | 12.9 | 80 |
| 27 | safepoint-test | no-park-test | 12.5 | 13.0 | 12.8 | 82 |
| 28 | prelude-parity-test | generator-expression-on-every-host-test | 13.9 | 11.4 | 12.7 | 83 |
| 29 | prelude-parity-test | generator-throw-and-close-on-every-host-test | 13.7 | 11.5 | 12.6 | 84 |
| 30 | safepoint-test | fail-closed-test | 12.6 | 12.3 | 12.5 | 86 |
| 31 | prelude-parity-test | delegation-chain-callers-and-unwinding-on-every-host-test | 12.8 | 11.6 | 12.2 | 87 |
| 32 | prelude-parity-test | yield-from-and-iter-on-every-host-test | 11.6 | 12.2 | 11.9 | 88 |
| 33 | prelude-parity-test | printed-floats-on-every-host-test | 12.4 | 10.6 | 11.5 | 89 |
| 34 | prelude-parity-test | generator-throw-non-exception-class-on-every-host-test | 11.4 | 11.2 | 11.3 | 90 |
| 35 | prelude-parity-test | generator-expression-admission-on-every-host-test | 11.2 | 10.9 | 11.0 | 92 |
| 36 | prelude-parity-test | delegation-stop-and-sticky-dict-iterator-on-every-host-test | 11.0 | 11.0 | 11.0 | 93 |
| 37 | prelude-parity-test | unhashable-on-every-host-test | 11.3 | 10.7 | 11.0 | 94 |
| 38 | prelude-parity-test | nan-keys-on-every-host-test | 11.2 | 10.6 | 10.9 | 95 |
| 39 | prelude-parity-test | signed-zero-floats-on-every-host-test | 12.2 | 9.7 | 10.9 | 96 |
| 40 | prelude-parity-test | numeric-dict-keys-on-every-host-test | 10.9 | 10.9 | 10.9 | 97 |
| 41 | prelude-parity-test | escapes-on-every-host-test | 11.0 | 10.5 | 10.8 | 98 |
| 42 | safepoint-test | insertion-is-deterministic-test | 5.4 | 5.1 | 5.2 | 99 |
| 43 | float-address-test | program-addresses-test | 3.9 | 4.1 | 4.0 | 99 |
| 44 | lower-portable-test | transform-routes-programs-and-diagnostics-test | 1.5 | 1.4 | 1.5 | 99 |
| 45 | safepoint-test | no-hook-under-a-prelude-definition-test | 1.4 | 1.4 | 1.4 | 100 |
| 46 | safepoint-test | canonical-untouched-test | 1.4 | 1.3 | 1.4 | 100 |
| 47 | safepoint-test | marks-reach-the-side-table-only-test | 1.1 | 1.1 | 1.1 | 100 |
| 48 | safepoint-test | empty-profile-is-the-identity-test | 0.6 | 0.7 | 0.7 | 100 |

80% set: 26 tests of 83; rest (<= 0.5 s) not listed

Per-namespace totals (mean):

| ns | tests | s |
|---|---|---|
| safepoint-test | 22 | 504.5 |
| prelude-parity-test | 26 | 395.3 |
| float-address-test | 12 | 69.7 |
| lower-portable-test | 4 | 1.5 |
| yin.vm.integer-test | 19 | 0.7 |
