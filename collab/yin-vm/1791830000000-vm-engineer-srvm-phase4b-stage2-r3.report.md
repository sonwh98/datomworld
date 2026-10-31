Completed-GMT: 2026-10-11 03:45:12 GMT
Completed-Local: 2026-10-11 10:45:12 Asia/Bangkok

# Stage 2 round 3

## Changed files (test-only)

- `test/yin/vm/semantic_register/effects_test.cljc`: new `ffi-cell-ref-stays-pinned` passes `(ffi :op/echo (cell/new :keep))` with the cell module registered. It asserts blocked state, a real cell id, membership in `:gc :pinned`, and the cell's `:keep` value after collection with empty window and environment.
- `test/yin/vm/semantic_register/link_test.cljc`: `image-and-store-context` gives the spawning parent window `{0 :parent-only}` and asserts the child's window is exactly `{}`. The non-empty parent is necessary to make the isolation assertion distinguish a shared window.

No other tracked files changed. Optional binding-mismatch ex-data alignment skipped to preserve test-only scope. No staging or commits.

## Mutation evidence

| Mutant | Run | Result |
| --- | --- | --- |
| R17: replace `(engine/pin-refs machine args)` with `machine` in `:ffi-call` | `clojure -M:test -n yin.vm.semantic-register.effects-test` | KILLED by `ffi-cell-ref-stays-pinned`: 42 tests / 236 assertions, 2 failures, 0 errors, exit 1 |
| R12b: `spawn-module` associates parent's window into loaded child | `clojure -M:test -n yin.vm.semantic-register.link-test` | KILLED by `image-and-store-context`: 6 tests / 66 assertions, 1 failure, 0 errors, exit 1 |

R17 failed assertions:
```
(contains? (get-in blocked [:gc :pinned]) cell-id)
;; actual: (not (contains? #{} :cell-0))
(= :keep (get-in collected [:heap cell-id :value]))
;; actual: (not (= :keep nil))
```
R12b failed assertion:
```
(= {} (:window child))
;; actual: (not (= {} {0 :parent-only}))
```

Mutants applied separately and restored from a byte snapshot after each run. Final `git diff --quiet HEAD -- src/` exits 0. Source bytes also equal the pre-mutation snapshot; SHA256: 7fe2bb0c0984d44c4f1efc6bf3d1c365a023b07dfd59efc5bf91f04e35ba28a2.

## Verification

G0: `clojure -M:test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test`: exit 0, 48 tests / 302 assertions, zero failures/errors.

Full JVM regression, foreground with literal flags:
```
clojure -M:test -n yin.vm.continuation-invoke-test -n yin.vm.ffi-test -n yin.vm.rule-r-test -n yin.vm.linker-require-test -n yin.vm.semantic-test -n yin.vm.semantic-engine-test -n yin.vm.semantic-ffi-test -n yin.vm.parity-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test
```
Exit 0: **232 tests / 3318 assertions**, zero failures/errors. All 17 requested `Testing <ns>` lines confirmed. Log: `/private/tmp/srvm-r3-jvm.log`.

Node:
```
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(code-test|linearize-test|analysis-test|load-paths-test|vm-test|walker-trace-test|parity-test|effects-test|link-test)$" :output-to "out/test.js" :autorun false}'
node out/test.js
```
Compile exit 0: 155 files, 4 compiled, 0 warnings. Runner exit 0: **115 tests / 2011 assertions**, zero failures/errors. All nine requested register namespaces confirmed; dependency `yin.vm.parity-test` also runs, as in round 2. Logs: `/private/tmp/srvm-r3-node-compile.log`, `/private/tmp/srvm-r3-node.log`.

Dart: **NOT RUN**, explicitly delegated to the orchestrator by this brief; no command attempted this round.

Checks on both changed test paths:
```
clj -M:kondo --lint test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/link_test.cljc
cljstyle check test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/link_test.cljc
```
Kondo exit 0, 0 errors / 0 warnings. Initial style check exit 2 found indentation only; `cljstyle fix` corrected both files, final check exit 0. Full runtime lanes ran before the whitespace-only formatting correction. Final kondo rerun passed. Trailing-whitespace scan: zero hits. `git diff --check`: exit 0.

## Carried review notes and incomplete work

No source defect found or changed. Composite-id coverage retains the dedicated FFI assertion and existing fifth-backend composite-id test. FFI blocks in step traces, so the completion branch is unreachable there; run/bridge tests cover delivery. Binding-mismatch ex-data divergence remains optional and unchanged. The bridge template has 13 deftests despite the design's wording of fourteen; design unchanged. The decoder/table-removal local-binding swap is equivalent: decoding takes no machine, removal produces an immutable local value, and no machine returns on a throw; no additional gate required. The authorized scanner exclusion remains exactly one site pending phase 5c.

Both requested gaps closed; no remaining round-3 implementation work. Dart verification belongs to the orchestrator.
