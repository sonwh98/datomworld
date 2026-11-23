# Findings: ClojureDart nil-key map literal

- Host: shiva; branch: `fix/cljd-nil-key-map-literal`.
- Base: GitHub `origin/master` at `1c9e50d9087fc6aa3eaa48b64adbd3c0ef1f823b`; tested and pushed head: `6d2571f1fd0f07107c7f128ce36d824f1a988820`.
- The worktree's configured origin points to a local checkout whose local master was stale (`746b2c34`); fetched actual GitHub master directly and pushed the branch directly to GitHub.
- Nightly evidence identified `cljd.core/-map-lit` failing to expand `{nil :nil 0 :zero}`. Replaced the literal with `(zipmap [nil 0] [:nil :zero])`; the expected `[[0 :zero] [nil :nil]]` remains unchanged. No other nil-key literal was identified.
- An initial attempt using `(array-map nil :nil 0 :zero)` failed: `clojure -M:clojuredart:cljd compile yin.vm.data-test` rc=1, `Unknown symbol: array-map`. It was replaced with `zipmap`; no expectation changed.
- Final `clojure -M:clojuredart:cljd compile yin.vm.data-test` run 1: rc=0, `Compilation succeeded`.
- Final `clojure -M:clojuredart:cljd compile yin.vm.data-test` run 2: rc=0, `Compilation succeeded`.
- Both compiles printed dynamic-member warnings in other code; neither produced a compilation error.
- `clojure -M:test -n yin.vm.data-test`, fresh JVM run 1: rc=0; `Ran 29 tests containing 536 assertions. 0 failures, 0 errors.`
- `clojure -M:test -n yin.vm.data-test`, fresh JVM run 2: rc=0; `Ran 29 tests containing 536 assertions. 0 failures, 0 errors.`
- `clojure -M:kondo --lint test/yin/vm/data_test.cljc`: rc=0; `errors: 0, warnings: 0`.
- `git diff --check`: rc=0. Full Dart shard was not run.
