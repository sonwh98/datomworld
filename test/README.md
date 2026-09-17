## ClojureDart Test Suite

ClojureDart tests are cross-compiled to Dart and executed using the Dart VM.

### Running ClojureDart Tests
```bash
clj -M:cljd test            # all namespaces (sweeps the classpath)
clj -M:cljd test some.ns    # a subset
```
The native `cljd.build` test command compiles every `.cljc`/`.cljd` namespace on the classpath, generates a `*_test.dart` entrypoint under `test/cljd-out/` for each namespace containing deftests, and runs them via `flutter test`. There is no allowlist: JVM-only namespaces are excluded at the reader level with `#?(:cljd nil :clj ...)` guards (a plain `#?(:clj ...)` does NOT exclude code from the ClojureDart build, because the host pass also matches `:clj`).

---

## ClojureScript Test Suite

ClojureScript tests are cross-compiled to JavaScript and executed using Node.js.

### Running ClojureScript Tests
```bash
./bin/run-cljs-tests.sh
```
This script uses `shadow-cljs` to compile the `:test` build and runs the resulting script with `node`.

### Supported Test Build
The configuration for the test build is defined in `shadow-cljs.edn` under the `:test` key.

---

## Yin VM Formal Test Suite

## Overview

Formal test suite using `clojure.test` for the Yin Virtual Machine implementation.

## Test Files

### [yin/vm_basic_test.clj](yin/vm_basic_test.clj)
Core functionality tests covering:
- ✅ **Literal evaluation** - Numbers and strings
- ✅ **Variable lookup** - Environment binding
- ✅ **Lambda/Closure creation** - Captures environment
- ✅ **Primitive addition** - Direct function application
- ✅ **Lambda addition** - User-defined functions
- ✅ **Increment with continuations** - Step-by-step execution
- ✅ **All arithmetic primitives** - `+`, `-`, `*`, `/`
- ✅ **All comparison operations** - `=`, `<`, `>`

**Stats:** 8 tests, 16 assertions

## Running Tests

### Quick Run
```bash
./run_formal_tests.sh
```

### Manual Run
```bash
clj -M:test -e "(require 'yin.vm-basic-test) (clojure.test/run-tests 'yin.vm-basic-test)"
```

### From REPL
```clojure
(require 'yin.vm-basic-test)
(clojure.test/run-tests 'yin.vm-basic-test)
```

### Run Specific Test
```clojure
(require 'yin.vm-basic-test)
(clojure.test/test-var #'yin.vm-basic-test/test-lambda-addition)
```

## Test Structure

Each test follows the standard `clojure.test` pattern:

```clojure
(deftest test-name
  (testing "Description"
    (let [ast {...}
          result (walker/run (walker/make-state env) ast)]
      (is (= expected (:value result))
          "Assertion message"))))
```

## Test Coverage

### Covered Features
- ✅ CESK machine state transitions
- ✅ Literal value evaluation
- ✅ Variable environment lookup
- ✅ Closure creation and application
- ✅ Primitive function application
- ✅ Lambda function application
- ✅ Continuation-based evaluation
- ✅ All arithmetic operations
- ✅ All comparison operations

### Known Limitations
- ⚠️ Nested lambda applications (requires continuation improvements)
- ⚠️ Nested arithmetic expressions within lambda bodies
- ⚠️ Conditional (if/then/else) - not yet tested

## Configuration

Tests use the `:test` alias from `deps.edn`:

```clojure
:test {:extra-paths ["test"]
       :extra-deps {io.github.cognitect-labs/test-runner
                    {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}
```

## Test Output

Successful run shows:
```
Testing yin.vm-basic-test

Ran 8 tests containing 16 assertions.
0 failures, 0 errors.
```

## Integration with CI/CD

These tests can be integrated into CI/CD pipelines:

```bash
# Exit with error code on test failure
clj -M:test -e "(require 'yin.vm-basic-test) (let [result (clojure.test/run-tests 'yin.vm-basic-test)] (System/exit (if (clojure.test/successful? result) 0 1)))"
```

## Adding New Tests

1. Create test file in `test/yin/`
2. Use namespace: `(ns yin.your-test (:require [clojure.test :refer [deftest is testing]] [yin.vm :as vm]))`
3. Add tests using `deftest`, `testing`, and `is`
4. Run with: `clj -M:test -e "(require 'yin.your-test) (clojure.test/run-tests 'yin.your-test)"`

## Related Documentation

- **Implementation:** [src/cljc/yin/vm.cljc](../src/cljc/yin/vm.cljc)
- **Informal Tests:** [test_add.clj](../test_add.clj), [test_simple_continuation.clj](../test_simple_continuation.clj)
- **Full Documentation:** [YIN_VM_TESTS.md](../YIN_VM_TESTS.md)
