# Slow discovery metadata fix

Base `origin/master`: `74ef28f3c2c9f40b42dc649856059a332ee61e65`. Implementation commit: `78203be8` (the findings commit follows it). Branch `fix/slow-discovery-metadata`.

The JVM inventory uses `clojure.tools.namespace.find/find-namespaces-in-dir`, the Cognitect runner's discovery source, loads those namespaces, and counts public vars with truthy `:test` and `:slow` metadata. It writes the sorted full EDN manifest to `jvm-slow-manifest.edn`; the lane summary prints the subset actually scheduled. Discovery errors fail the lane. The existing runner still filters vars with `-i :slow`. Node/Dart use a different guard-based scheme. No test file or quick runner was changed.

## Discovery

`bb gen:antlr`: rc=0. Inventory, no test execution: rc=0, 61 namespaces. The actual nightly `slow-manifest` invocation: rc=0, output `61 9 8 false` (count, exception-contract count, exception-call count, dao.test-slow present). Full namespace set diff against the old literal grep (60 names):

```diff
- dao.test-slow
+ yang.call.exception-contract-test 9
+ yang.python.antlr.exception-call-test 8
```

## Quick comparison

Two independent metadata inventories over the same unchanged base test tree, filtering public test vars with falsey `:slow`, yielded 5,210 vars each; `diff -u` rc=0, empty. This is not two independently checked-out revisions: the test tree, deps, `bb.edn`, `affected.clj`, and quick planner are identical to the base, and only the slow discovery caller changed. `bb test:quick-sharded --list --workers 12`: rc=0, selected=369, covered=369, uncovered=0, unexpected=0, duplicates=0. Restricting both var inventories to those selected namespaces also yields 5,210 vars and an empty diff (rc=0).

## Lint

`clojure -M:kondo --lint src/dev/nightly.clj src/dev/slow_inventory.clj`: rc=0, errors=0, warnings=0. An initial lint reported the pre-existing unused `lane-rc` binding (rc=2); it was removed.

## Invocation correction

The first attempted inventory command used the `:test` alias without overriding its main options, inadvertently starting the default test runner. It was interrupted during namespace loading before a `Testing` banner or `Ran` summary (no terminal rc was captured); it did not complete a test run. The corrected inventory explicitly overrides the main entry point.

## Focused fresh-JVM slow tests

- `clojure -M:test -i :slow -n yang.call.exception-contract-test -v yang.call.exception-contract-test/clojure-provenance-test`: rc=0; `Ran 1 tests containing 33 assertions.` `0 failures, 0 errors.`
- `clojure -M:test -i :slow -n yang.python.antlr.exception-call-test -v yang.python.antlr.exception-call-test/clojure-to-python-test`: rc=0; `Ran 1 tests containing 24 assertions.` `0 failures, 0 errors.`

The full `yang.call.exception-contract-test` namespace was never run.
