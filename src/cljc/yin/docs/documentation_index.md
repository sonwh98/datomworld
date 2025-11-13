# Yin VM - Documentation Index

Complete guide to all Yin VM documentation.

## 🎯 Quick Start

New to Yin VM? Start here:
1. **[summary.md](summary.md)** - Project overview and what was built
2. **[state.md](state.md)** - Understand the CESK machine state
3. **[ast.md](ast.md)** - Learn the Universal AST (start with Part 1: Literals)
4. **[readme_tests.md](readme_tests.md)** - Run tests to see it in action

## 📚 Core Concepts

### The CESK Machine

**[state.md](state.md)** - Complete state documentation (503 lines)

Learn about:
- The five state fields (`:control`, `:environment`, `:store`, `:continuation`, `:value`)
- State lifecycle and transitions
- Immutability and its benefits
- Helper functions and best practices
- Complete examples and demos

**Demo:** `clj test_state_demo.clj`

### The Universal AST

**[ast.md](ast.md)** - Incremental AST documentation (206+ lines)

Currently documented:
- ✅ **Part 1: Literals** - Simple values (numbers, strings, booleans, collections)

Coming soon:
- 🔄 Part 2: Variables
- 📋 Part 3: Lambdas
- 📋 Part 4: Application
- 📋 Part 5: Conditionals

**Quick Reference:** [ast_quickref.md](ast_quickref.md)
**Demo:** `clj test_literal_demo.clj`

### Documentation Roadmap

**[ast_documentation_plan.md](ast_documentation_plan.md)**

Complete plan for documenting all AST node types with progress tracking.

## 🧪 Testing

### Informal Tests (Quick Demos)

Located in project root:

- **[test_add.clj](../../../test_add.clj)** - Lambda addition tests
  ```bash
  clj test_add.clj
  ```

- **[test_simple_continuation.clj](../../../test_simple_continuation.clj)** - Continuation stepping
  ```bash
  clj test_simple_continuation.clj
  ```

- **[test_literal_demo.clj](../../../test_literal_demo.clj)** - Literal evaluation examples
  ```bash
  clj test_literal_demo.clj
  ```

- **[test_state_demo.clj](../../../test_state_demo.clj)** - State transitions demo
  ```bash
  clj test_state_demo.clj
  ```

**Run all:** `./run_tests.sh`

### Formal Test Suite

Located in `test/` directory:

- **[test/README.md](../../../test/README.md)** - Test suite documentation
- **[test/yin/vm_basic_test.clj](../../../test/yin/vm_basic_test.clj)** - 8 tests, 16 assertions

**Run:** `./run_formal_tests.sh`

**Documentation:**
- **[yin_vm_tests.md](yin_vm_tests.md)** - Comprehensive test guide
- **[formal_tests_summary.md](formal_tests_summary.md)** - Formal test details

## 📖 Implementation

### Source Code

- **[src/cljc/yin/vm.cljc](vm.cljc)** - The Yin VM implementation
  - `eval` function - Steps the CESK machine
  - `run` function - Runs evaluation to completion
  - `primitives` - Built-in operations (`+`, `-`, `*`, `/`, `=`, `<`, `>`)

### Configuration

- **[deps.edn](deps.edn)** - Clojure CLI configuration
- **[project.clj](../../../project.clj)** - Leiningen configuration (alternative)

## 📊 Project Documentation

### Overview

- **[summary.md](summary.md)** - Complete project summary
  - What was implemented
  - Test results
  - Architecture highlights
  - Future enhancements

### Test Guides

- **[readme_tests.md](readme_tests.md)** - Quick test guide
  - How to run tests
  - What gets tested
  - Requirements

- **[yin_vm_tests.md](yin_vm_tests.md)** - Comprehensive test documentation
  - All test cases
  - Example execution flows
  - Test coverage

- **[formal_tests_summary.md](formal_tests_summary.md)** - Formal test suite details
  - Test structure
  - Coverage metrics
  - CI/CD integration

## 🎓 Learning Path

### Beginner Path

1. Read [summary.md](summary.md) - Get the big picture
2. Run `clj test_literal_demo.clj` - See literals in action
3. Read [ast.md Part 1](ast.md#part-1-simple-values-literals) - Understand literals
4. Run `clj test_state_demo.clj` - See state transitions
5. Read [state.md](state.md) - Understand the CESK machine

### Intermediate Path

1. Run `./run_tests.sh` - See all informal tests
2. Read [yin_vm_tests.md](yin_vm_tests.md) - Understand test coverage
3. Run `./run_formal_tests.sh` - See formal tests
4. Read [test/README.md](../../../test/README.md) - Learn formal testing
5. Study [src/cljc/yin/vm.cljc](vm.cljc) - Understand implementation

### Advanced Path

1. Read [ast_documentation_plan.md](ast_documentation_plan.md) - See the roadmap
2. Study continuation handling in [state.md](state.md)
3. Implement new AST node types (Variables, Lambdas, etc.)
4. Add new tests to [test/yin/vm_basic_test.clj](../../../test/yin/vm_basic_test.clj)
5. Contribute to AST documentation

## 🔍 By Topic

### Understanding State

- [state.md](state.md) - Complete guide
- [test_state_demo.clj](../../../test_state_demo.clj) - Interactive demo
- [src/cljc/yin/vm.cljc](vm.cljc) - Implementation

### Understanding AST

- [ast.md](ast.md) - Main documentation
- [ast_quickref.md](ast_quickref.md) - Quick reference
- [test_literal_demo.clj](../../../test_literal_demo.clj) - Literal examples
- [ast_documentation_plan.md](ast_documentation_plan.md) - Roadmap

### Understanding Tests

- [readme_tests.md](readme_tests.md) - Quick start
- [yin_vm_tests.md](yin_vm_tests.md) - Comprehensive guide
- [test/README.md](../../../test/README.md) - Formal tests
- [formal_tests_summary.md](formal_tests_summary.md) - Test details

### Understanding Continuations

- [state.md](state.md#continuation---return-context) - Continuation field
- [test_simple_continuation.clj](../../../test_simple_continuation.clj) - Demo
- [test_state_demo.clj](../../../test_state_demo.clj) - Step-by-step transitions

### Understanding Primitives

- [src/cljc/yin/vm.cljc](vm.cljc) - `primitives` definition
- [test_add.clj](../../../test_add.clj) - Addition examples
- [test/yin/vm_basic_test.clj](../../../test/yin/vm_basic_test.clj) - All primitives tested

## 📁 File Organization

```
datomworld/
├── Documentation
│   ├── summary.md                    # Project summary
│   ├── documentation_index.md        # This file
│   ├── state.md                      # State documentation ⭐
│   ├── ast.md                        # AST documentation ⭐
│   ├── ast_quickref.md              # AST quick reference
│   ├── ast_documentation_plan.md    # AST roadmap
│   ├── readme_tests.md              # Test quick guide
│   ├── yin_vm_tests.md              # Comprehensive tests
│   └── formal_tests_summary.md      # Formal test details
│
├── Demo Scripts
│   ├── test_add.clj                  # Addition tests
│   ├── test_simple_continuation.clj  # Continuation stepping
│   ├── test_literal_demo.clj         # Literal examples ⭐
│   ├── test_state_demo.clj           # State transitions ⭐
│   ├── run_tests.sh                  # Run all informal tests
│   └── run_formal_tests.sh          # Run formal tests
│
├── Implementation
│   └── src/cljc/yin/vm.cljc         # Yin VM implementation ⭐
│
├── Formal Tests
│   ├── test/README.md               # Test documentation
│   └── test/yin/
│       ├── vm_basic_test.clj        # Core tests (8 tests) ⭐
│       ├── vm_addition_test.clj     # Addition tests
│       └── vm_continuation_test.clj # Continuation tests
│
└── Configuration
    ├── deps.edn                      # Clojure CLI config
    └── project.clj                   # Leiningen config

⭐ = Most important files
```

## 🚀 Quick Commands

```bash
# Run informal tests
./run_tests.sh

# Run formal tests
./run_formal_tests.sh

# Run specific demo
clj test_literal_demo.clj
clj test_state_demo.clj
clj test_add.clj
clj test_simple_continuation.clj

# Run formal tests directly
clj -M:test -e "(require 'yin.vm-basic-test) (clojure.test/run-tests 'yin.vm-basic-test)"

# Read documentation
cat state.md
cat ast.md
cat summary.md
```

## 🎯 Common Tasks

### I want to understand how the VM works
1. Read [state.md](state.md)
2. Run `clj test_state_demo.clj`
3. Read [src/cljc/yin/vm.cljc](vm.cljc)

### I want to understand the AST
1. Read [ast.md](ast.md)
2. Run `clj test_literal_demo.clj`
3. Check [ast_quickref.md](ast_quickref.md)

### I want to run tests
1. Quick: `./run_tests.sh`
2. Formal: `./run_formal_tests.sh`
3. Read [readme_tests.md](readme_tests.md)

### I want to add new features
1. Read [ast_documentation_plan.md](ast_documentation_plan.md)
2. Study [src/cljc/yin/vm.cljc](vm.cljc)
3. Add tests to [test/yin/vm_basic_test.clj](../../../test/yin/vm_basic_test.clj)

### I want to understand continuations
1. Read [state.md](state.md) continuation section
2. Run `clj test_simple_continuation.clj`
3. Run `clj test_state_demo.clj`

---

**Last Updated:** state.md and ast.md Part 1 complete

**Total Documentation:** 1000+ lines across all files

**Next:** Document AST Part 2 (Variables)
