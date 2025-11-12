# Yin VM Test Suite

## Overview

This document describes the test suite for the Yin Virtual Machine implementation, which is a CESK (Control, Environment, Store, Continuation) machine based on the specification at [https://datom.world/yin.chp](https://datom.world/yin.chp).

## Implementation Files

- **[src/cljc/yin/vm.cljc](src/cljc/yin/vm.cljc)** - The Yin VM implementation
- **[src/cljc/yin/vm_test.cljc](src/cljc/yin/vm_test.cljc)** - Comprehensive test suite

## Quick Test Files

### 1. Simple Addition Test
**File:** [test_add.clj](test_add.clj)

Tests basic primitive operations and lambda-based addition:
- ✅ `(+ 10 20)` → `30`
- ✅ `((lambda (x y) (+ x y)) 3 5)` → `8`

**Run:** `lein run -m clojure.main test_add.clj`

### 2. Continuation Stepping Test
**File:** [test_simple_continuation.clj](test_simple_continuation.clj)

Demonstrates step-by-step execution with continuations:
- Shows each evaluation step
- Displays control, continuation, and value at each step
- Expression: `((lambda (x) (+ x 1)) 5)` → `6`
- Takes 17 execution steps

**Run:** `lein run -m clojure.main test_simple_continuation.clj`

**Sample Output:**
```
Step  0: ctrl=:application cont=nil             val=nil
Step  1: ctrl=:lambda      cont=:eval-operator  val=nil
Step  2: ctrl=nil          cont=:eval-operator  val=<closure>
Step  3: ctrl=:application cont=nil             val=nil
...
Step 16: ctrl=:application cont=nil             val=nil

Final value: 6
```

## Test Suite

The comprehensive test suite in [src/cljc/yin/vm_test.cljc](src/cljc/yin/vm_test.cljc) includes:

### Test 1: Literal Evaluation
- Evaluates `42` to itself
- Tests basic value production

### Test 2: Variable Lookup
- Looks up variable `x` in environment `{x 100}`
- Tests environment binding

### Test 3: Lambda/Closure Creation
- Creates closure `(lambda (x) x)`
- Tests closure structure with captured environment

### Test 4: Conditional (True Branch)
- Tests if-then-else evaluation
- Verifies test expression is evaluated first

### Test 5: String Literal
- Evaluates string `"hello"`
- Tests non-numeric literals

### Test 6: Multiple Variables in Environment
- Environment: `{x 10, y 20, z 30}`
- Looks up `z` → `30`

### Test 7: Lambda that Adds Two Numbers ⭐
- Expression: `((lambda (x y) (+ x y)) 3 5)`
- Result: `8`
- Tests function application with user-defined lambda

### Test 8: Simple Primitive Addition
- Expression: `(+ 10 20)`
- Result: `30`
- Tests direct primitive function application

### Test 9: Continuation Stepping ⭐
- Expression: `((lambda (x) (+ x 1)) 5)`
- Result: `6`
- Demonstrates step-by-step execution
- Counts total evaluation steps (17)

## Running Tests

### Using `clj` (Recommended)

**Run all tests:**
```bash
./run_tests.sh
```

**Run individual tests:**
```bash
# Addition tests
clj test_add.clj

# Continuation stepping test
clj test_simple_continuation.clj
```

### Using Leiningen (Alternative)

**Run individual tests:**
```bash
lein run -m clojure.main test_add.clj
lein run -m clojure.main test_simple_continuation.clj
```

### From REPL
```clojure
(require '[yin.vm-test :as t])
(t/run-all-tests)

;; Or run individual tests
(t/test-add-lambda)
(t/test-continuation-stepping)
```

## Key Features Demonstrated

### 1. **CESK Machine Architecture**
- **Control**: Current AST node being evaluated
- **Environment**: Persistent lexical scope
- **Store**: Immutable memory graph
- **Continuation**: Reified control context

### 2. **First-Class Continuations**
- Continuations are data structures
- Can be inspected at each step
- Enable pausable, resumable computation
- Support for mobile agents (future use)

### 3. **Universal AST**
- Map-based structure
- Language-agnostic
- Supports: `:literal`, `:variable`, `:lambda`, `:application`, `:if`

### 4. **Primitive Operations**
Built-in operations in `vm/primitives`:
- `+`, `-`, `*`, `/` (arithmetic)
- `=`, `<`, `>` (comparison)

### 5. **Closure Support**
- Lambdas capture their environment
- Support nested scopes
- Enable higher-order functions

## Example: Step-by-Step Execution

For the expression `((lambda (x) (+ x 1)) 5)`:

1. **Step 0-2**: Evaluate operator (lambda) → creates closure
2. **Step 3-6**: Evaluate operand (5) → literal value
3. **Step 7**: Apply closure to argument → extend environment with `{x 5}`
4. **Step 8-16**: Evaluate body `(+ x 1)`:
   - Evaluate operator `+` → primitive function
   - Evaluate operand `x` → lookup in environment → `5`
   - Evaluate operand `1` → literal value
   - Apply primitive `+` to `[5, 1]` → `6`

Total: **17 steps** with continuations managing control flow throughout.

## Design Principles

Based on [datom.world/yin.chp](https://datom.world/yin.chp):

✅ **Universal AST** - Programs as immutable, map-based data structures
✅ **Persistent CESK** - Structural sharing for fast snapshots
✅ **First-class continuations** - Portable computation state
✅ **Eval as VM** - No hidden interpreter, eval steps the machine
✅ **Runtime macros** - Code can transform itself (foundation laid)

## Future Enhancements

The current implementation provides a foundation for:
- Serializable continuations for network migration
- Distributed AST transformation
- Mobile agents moving across nodes
- Time-travel debugging with state snapshots
- Collaborative code evolution

## Notes

- The VM properly handles tail calls through continuation-passing style
- All state transitions are explicit and visible
- Persistent data structures enable efficient state snapshots
- Continuations can be saved at any step for later resumption
