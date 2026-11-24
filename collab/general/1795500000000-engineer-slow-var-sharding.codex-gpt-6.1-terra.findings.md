# Slow var sharding — engineering findings

Implementation commit: ec99baf8 (base 0d378482933fa8cf73a2d12f144a04052176ccb8). Opt in with `NIGHTLY_SLOW_VAR_SHARDS=1`; unset is whole namespace. A comma-separated custom set is also supported. The exception-contract namespace is hard-excluded. `bb src/dev/nightly.clj --list-slow .` prints the commands. The discovery subprocess uses the same `:test` classpath and slow metadata as the landed inventory; it fails closed if counts differ. The runner retains the `-i` and `-v` filtering.

## Independence evidence

`yang.php.antlr.e2e-test` declares no test once-fixture. `run-phpt-stress-case` invokes `engine-result` for each entry; the e2e harness constructs service atoms and streams within each run. The two GC stress vars were executed on the real selection and pinned gap assertions in fresh JVMs and together in namespace order:

- `phpt-gc-013-stress-test`: rc=0, wall=599s, Ran 1 tests containing 3 assertions, 0 failures, 0 errors. Payload: `{:out "", :diag [], :uncaught {:class "Error", :message "Call to undefined function gc_collect_cycles()"}}`.
- `phpt-gc-023-stress-test`: rc=0, wall=1262s, Ran 1 tests containing 3 assertions, 0 failures, 0 errors. Same payload.
- Both with repeated `-v`: rc=0, wall=1846s, Ran 2 tests containing 6 assertions, 0 failures, 0 errors. Each payload matched its separate run. Commands used `clojure -M:test -i :slow -n yang.php.antlr.e2e-test` plus the corresponding fully qualified `-v` selectors.
- Additional comparison: GC 012 alone rc=0 wall=142s, Ran 1 tests containing 3 assertions, 0 failures, 0 errors; GC 012 and 013 together rc=0 wall=724s, Ran 2 tests containing 6 assertions, 0 failures, 0 errors. Each emitted the same recorded undefined-function payload.

The test files of the other four configured namespaces contain no `use-fixtures` or `defonce` declarations. This evidence is scoped to the checked rows and fixture inspection; it is not a proof of all possible cross-var interactions.

## Verification

- `clojure -M:kondo --lint src/dev/nightly.clj`: rc=0, 0 errors, 0 warnings.
- Unset list rc=0: 61 whole-namespace invocations. Configured list rc=0: 76 invocations. All namespace sets match; shard selectors are unique; group sizes: PHP e2e 3/3/3/3, PHP include 2/2/2/1, Python e2e 12/12/12/12, TypeScript corpus 2/2/1/1, safepoint harness 2/1/1/1. `yang.call.exception-contract-test` remains a single whole-namespace command.
- `bb test:quick-sharded --list` rc=0 before and after. Diff is empty after normalizing the generated run-directory name; raw diff contains only that name.
- Actual configured split group `clojure -M:test -i :slow -n yang.php.antlr.include-test -v yang.php.antlr.include-test/linked-profile-matches-bundled-test`: rc=0 wall=330s, Ran 1 tests containing 3 assertions, 0 failures, 0 errors.
- Tonight's `slow-yang.php.antlr.include-test.log` reports Ran 7 tests containing 566 assertions, 0 failures, 0 errors; it has no per-var timing, so no historical share can be calculated.

## Execution notes

The exploratory PHP bug39438/bug40261 single and paired runs were deliberately stopped after 78s each, rc=143, before any Ran line; they are not equivalence evidence. An inventory attempt mistakenly used `-M:test -e`, invoking the test runner instead of the expression. It was interrupted in `dao.space.dht-test` before reaching the exception-contract namespace. Before interruption it reported three `dao.host.gate-test/independent-traversal-agrees-with-the-committed-closure` failures (walked files missing from the committed closure); no overall Ran line or final verdict exists for that accidental run. The later inventory used an overriding main alias and only loaded namespaces.

## Unsplit listing

```clojure
["clojure" "-M:test" "-i" :slow "-n" "yang.clojure.stream-eval-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.generators-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.derive-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.siteset-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.dht-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-conv-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.stage-e-process-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.linked-prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.prelude-parity-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.publication-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.holder.driver-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.float-address-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.lower-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.dht-head-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.corpus-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.float-text-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.string-forms-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.c3-gate-parser-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.exception-call-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.import-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.ast-query-e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-c2-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.exception-contract-test"]
["clojure" "-M:test" "-i" :slow "-n" "dao.space.dht-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.source-call-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "dao.star.driver-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.scan-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.c3-gate-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.dht-import-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.object-e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.stage-e-rows-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.php-exception-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.include-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.linker-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-ops-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.main-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.safepoint-harness-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.safepoint-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.linked-safepoint-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-contract-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.prelude-parity-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.dht-process-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.exception-source-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-literal-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-heap-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-ops-parser-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.linker.cross-host-transfer-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.python-exception-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.rows-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.safepoint-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.linked-prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.python-antlr-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.class-prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.compose-rows-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.query-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-c1-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.linker.dht-end-to-end-test"]

```

## Split listing

```clojure
["clojure" "-M:test" "-i" :slow "-n" "yang.clojure.stream-eval-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.generators-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.derive-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.siteset-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.dht-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-conv-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.stage-e-process-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.linked-prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.prelude-parity-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.publication-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.holder.driver-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.float-address-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.lower-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.dht-head-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.corpus-test" "-v" "yang.typescript.antlr.corpus-test/t1-bundled-matches-node-test" "-v" "yang.typescript.antlr.corpus-test/t3-bundled-matches-node-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.corpus-test" "-v" "yang.typescript.antlr.corpus-test/t1-linked-matches-node-test" "-v" "yang.typescript.antlr.corpus-test/t3-linked-matches-node-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.corpus-test" "-v" "yang.typescript.antlr.corpus-test/t2-bundled-matches-node-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.corpus-test" "-v" "yang.typescript.antlr.corpus-test/t2-linked-matches-node-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.float-text-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.string-forms-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.c3-gate-parser-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.exception-call-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.import-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.ast-query-e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-c2-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.exception-contract-test"]
["clojure" "-M:test" "-i" :slow "-n" "dao.space.dht-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.source-call-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.e2e-test" "-v" "yang.php.antlr.e2e-test/fixtures-on-every-vm-test" "-v" "yang.php.antlr.e2e-test/phpt-bug40809-stress-test" "-v" "yang.php.antlr.e2e-test/phpt-gc-012-stress-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.e2e-test" "-v" "yang.php.antlr.e2e-test/permutation-corpus-full-test" "-v" "yang.php.antlr.e2e-test/phpt-bug44069-stress-test" "-v" "yang.php.antlr.e2e-test/phpt-gc-013-stress-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.e2e-test" "-v" "yang.php.antlr.e2e-test/phpt-bug39438-stress-test" "-v" "yang.php.antlr.e2e-test/phpt-bug63055-stress-test" "-v" "yang.php.antlr.e2e-test/phpt-gc-023-stress-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.e2e-test" "-v" "yang.php.antlr.e2e-test/phpt-bug40261-stress-test" "-v" "yang.php.antlr.e2e-test/phpt-bug64660-stress-test" "-v" "yang.php.antlr.e2e-test/stage0-enabled-fixtures-on-every-vm-test"]
["clojure" "-M:test" "-i" :slow "-n" "dao.star.driver-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-test" "-v" "yang.python.antlr.e2e-test/annotated-assignment-test" "-v" "yang.python.antlr.e2e-test/builtin-fallback-before-module-binding-test" "-v" "yang.python.antlr.e2e-test/classes-test" "-v" "yang.python.antlr.e2e-test/escape-restores-handlers-test" "-v" "yang.python.antlr.e2e-test/floats-are-tagged-test" "-v" "yang.python.antlr.e2e-test/fstring-test" "-v" "yang.python.antlr.e2e-test/integer-limits-are-guest-exceptions-test" "-v" "yang.python.antlr.e2e-test/lists-alias-and-index-test" "-v" "yang.python.antlr.e2e-test/numeric-hash-test" "-v" "yang.python.antlr.e2e-test/shadow-then-delete-builtin-test" "-v" "yang.python.antlr.e2e-test/try-else-and-reraise-test" "-v" "yang.python.antlr.e2e-test/undefined-global-is-name-error-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-test" "-v" "yang.python.antlr.e2e-test/arithmetic-and-print-test" "-v" "yang.python.antlr.e2e-test/builtin-lookup-order-test" "-v" "yang.python.antlr.e2e-test/closures-and-nonlocal-test" "-v" "yang.python.antlr.e2e-test/exact-numeric-keys-test" "-v" "yang.python.antlr.e2e-test/for-range-list-else-test" "-v" "yang.python.antlr.e2e-test/function-objects-test" "-v" "yang.python.antlr.e2e-test/keyboard-interrupt-is-a-builtin-test" "-v" "yang.python.antlr.e2e-test/long-loops-test" "-v" "yang.python.antlr.e2e-test/recursion-error-is-a-builtin-test" "-v" "yang.python.antlr.e2e-test/strings-test" "-v" "yang.python.antlr.e2e-test/try-except-raise-test" "-v" "yang.python.antlr.e2e-test/unhashable-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-test" "-v" "yang.python.antlr.e2e-test/assert-statement-test" "-v" "yang.python.antlr.e2e-test/class-attributes-and-identity-test" "-v" "yang.python.antlr.e2e-test/decorator-test" "-v" "yang.python.antlr.e2e-test/filtered-range-comprehension-repl-test" "-v" "yang.python.antlr.e2e-test/forward-capture-test" "-v" "yang.python.antlr.e2e-test/global-declaration-test" "-v" "yang.python.antlr.e2e-test/keyboard-interrupt-test" "-v" "yang.python.antlr.e2e-test/module-namespace-is-a-dict-test" "-v" "yang.python.antlr.e2e-test/recursion-test" "-v" "yang.python.antlr.e2e-test/sys-is-refused-test" "-v" "yang.python.antlr.e2e-test/unbound-local-test" "-v" "yang.python.antlr.e2e-test/while-break-continue-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-test" "-v" "yang.python.antlr.e2e-test/boolean-operators-test" "-v" "yang.python.antlr.e2e-test/class-body-reads-class-namespace-then-globals-test" "-v" "yang.python.antlr.e2e-test/dict-order-and-key-normalization-test" "-v" "yang.python.antlr.e2e-test/filtered-range-comprehension-test" "-v" "yang.python.antlr.e2e-test/fstring-spec-test" "-v" "yang.python.antlr.e2e-test/integer-is-test" "-v" "yang.python.antlr.e2e-test/lambda-test" "-v" "yang.python.antlr.e2e-test/named-unicode-escape-test" "-v" "yang.python.antlr.e2e-test/recursion-under-real-hooks-test" "-v" "yang.python.antlr.e2e-test/too-many-arguments-test" "-v" "yang.python.antlr.e2e-test/uncaught-exception-test" "-v" "yang.python.antlr.e2e-test/zero-division-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.scan-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.c3-gate-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.dht-import-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.object-e2e-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.stage-e-rows-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.php-exception-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.include-test" "-v" "yang.php.antlr.include-test/constant-admission-linked-test" "-v" "yang.php.antlr.include-test/missing-stage-refusals-are-data-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.include-test" "-v" "yang.php.antlr.include-test/cyclic-init-with-registered-tick-handler-test" "-v" "yang.php.antlr.include-test/mutual-include-ticks-on-every-vm-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.include-test" "-v" "yang.php.antlr.include-test/cyclic-module-initialization-does-not-run-bodies-test" "-v" "yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.php.antlr.include-test" "-v" "yang.php.antlr.include-test/linked-profile-matches-bundled-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.linker-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-ops-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.main-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.safepoint-harness-test" "-v" "yin.vm.semantic.safepoint-harness-test/aligned-boundaries-canonical-equality" "-v" "yin.vm.semantic.safepoint-harness-test/every-row-every-segment-matches-the-reference"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.safepoint-harness-test" "-v" "yin.vm.semantic.safepoint-harness-test/cross-engine-canonical-equality"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.safepoint-harness-test" "-v" "yin.vm.semantic.safepoint-harness-test/cross-engine-lowering"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.semantic.safepoint-harness-test" "-v" "yin.vm.semantic.safepoint-harness-test/every-returned-boundary"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.safepoint-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.linked-safepoint-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-contract-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.prelude-parity-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.dht-process-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.exception-source-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-literal-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-heap-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.int-ops-parser-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.linker.cross-host-transfer-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.call.python-exception-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.rows-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.safepoint-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.linked-prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.python-antlr-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.typescript.antlr.class-prelude-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.ucf.compose-rows-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.repl.query-test"]
["clojure" "-M:test" "-i" :slow "-n" "yang.python.antlr.e2e-c1-test"]
["clojure" "-M:test" "-i" :slow "-n" "yin.vm.linker.dht-end-to-end-test"]

```
