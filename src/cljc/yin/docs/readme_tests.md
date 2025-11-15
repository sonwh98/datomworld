# Yin VM - Quick Test Guide

## Requirements

- [Clojure CLI tools](https://clojure.org/guides/install_clojure) (`clj`)

## Run All Tests

```bash
clj -M:test
```

Or from the REPL:

```clojure
(require '[clojure.test :refer [run-tests]])
(require 'yin.vm-test)
(run-tests 'yin.vm-test)
```

## Run Individual Test Namespaces from shell

### Run specific test namespace
```bash
clj -M:test -n yin.vm-literal-test
clj -M:test -n yin.vm-continuation-test
```

### Run specific test function
```bash
clj -M:test -v yin.vm-addition-test/test-lambda-with-multiple-operations
```

### Run multiple specific namespaces
```bash
clj -X:test :nses '[yin.vm-literal-test yin.vm-test]'
```

## What Gets Tested

### Core Features
- ✅ **Literals** - Self-evaluating values
- ✅ **Variables** - Environment lookup
- ✅ **Lambdas** - Closure creation with captured environment
- ✅ **Application** - Function calls (both primitive and user-defined)
- ✅ **Primitives** - Built-in operations (`+`, `-`, `*`, `/`, `=`, `<`, `>`)
- ✅ **Continuations** - First-class, reified control flow

### CESK Machine
All tests validate the CESK (Control, Environment, Store, Continuation) architecture:
- **Control** - Current AST node
- **Environment** - Lexical scope bindings
- **Store** - Immutable memory (for future use)
- **Continuation** - Where to return after evaluation

## Implementation

- **VM:** [src/cljc/yin/vm.cljc](vm.cljc)
- **Full Test Suite:** [test/yin/vm_test.cljc](../../../test/yin/vm_test.cljc)
- **Documentation:** [yin_vm_tests.md](yin_vm_tests.md)

## Specification

Based on [https://datom.world/yin.chp](https://datom.world/yin.chp)
