# Yin VM - Quick Test Guide

## Run All Tests (Easiest)

```bash
./run_tests.sh
```

## Run Individual Tests with `clj`

### Test 1: Lambda Addition
```bash
clj test_add.clj
```
Tests:
- ✅ `(+ 10 20)` → `30`
- ✅ `((lambda (x y) (+ x y)) 3 5)` → `8`

### Test 2: Continuation Stepping
```bash
clj test_simple_continuation.clj
```
Tests:
- ✅ `((lambda (x) (+ x 1)) 5)` → `6`
- Shows all 17 execution steps
- Displays Control, Continuation, and Value at each step

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

## Requirements

- Clojure CLI tools (`clj`)
- `deps.edn` is already configured

## Implementation

- **VM:** [src/cljc/yin/vm.cljc](vm.cljc)
- **Full Test Suite:** [test/yin/vm_test.cljc](../../../test/yin/vm_test.cljc)
- **Documentation:** [yin_vm_tests.md](yin_vm_tests.md)

## Specification

Based on [https://datom.world/yin.chp](https://datom.world/yin.chp)
