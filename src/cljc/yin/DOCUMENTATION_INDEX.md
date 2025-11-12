# Yin VM - Documentation Index

Complete guide to all Yin VM documentation.

## 🎯 Quick Start

New to Yin VM? Start here:
1. **[SUMMARY.md](SUMMARY.md)** - Project overview and what was built
2. **[STATE.md](STATE.md)** - Understand the CESK machine state
3. **[AST.md](AST.md)** - Learn the Universal AST (start with Part 1: Literals)
4. **[README_TESTS.md](README_TESTS.md)** - Run tests to see it in action

## 📚 Core Concepts

### The CESK Machine

**[STATE.md](STATE.md)** - Complete state documentation (503 lines)

Learn about:
- The five state fields (`:control`, `:environment`, `:store`, `:continuation`, `:value`)
- State lifecycle and transitions
- Immutability and its benefits
- Helper functions and best practices
- Complete examples and demos

**Demo:** `clj test_state_demo.clj`

### The Universal AST

**[AST.md](AST.md)** - Incremental AST documentation (206+ lines)

Currently documented:
- ✅ **Part 1: Literals** - Simple values (numbers, strings, booleans, collections)

Coming soon:
- 🔄 Part 2: Variables
- 📋 Part 3: Lambdas
- 📋 Part 4: Application
- 📋 Part 5: Conditionals

**Quick Reference:** [AST_QUICKREF.md](AST_QUICKREF.md)
**Demo:** `clj test_literal_demo.clj`

### Documentation Roadmap

**[AST_DOCUMENTATION_PLAN.md](AST_DOCUMENTATION_PLAN.md)**

Complete plan for documenting all AST node types with progress tracking.

## 🧪 Testing

### Informal Tests (Quick Demos)

Located in project root:

- **[test_add.clj](test_add.clj)** - Lambda addition tests
  ```bash
  clj test_add.clj
  ```

- **[test_simple_continuation.clj](test_simple_continuation.clj)** - Continuation stepping
  ```bash
  clj test_simple_continuation.clj
  ```

- **[test_literal_demo.clj](test_literal_demo.clj)** - Literal evaluation examples
  ```bash
  clj test_literal_demo.clj
  ```

- **[test_state_demo.clj](test_state_demo.clj)** - State transitions demo
  ```bash
  clj test_state_demo.clj
  ```

**Run all:** `./run_tests.sh`

### Formal Test Suite

Located in `test/` directory:

- **[test/README.md](test/README.md)** - Test suite documentation
- **[test/yin/vm_basic_test.clj](test/yin/vm_basic_test.clj)** - 8 tests, 16 assertions

**Run:** `./run_formal_tests.sh`

**Documentation:**
- **[YIN_VM_TESTS.md](YIN_VM_TESTS.md)** - Comprehensive test guide
- **[FORMAL_TESTS_SUMMARY.md](FORMAL_TESTS_SUMMARY.md)** - Formal test details

## 📖 Implementation

### Source Code

- **[src/cljc/yin/vm.cljc](src/cljc/yin/vm.cljc)** - The Yin VM implementation
  - `eval` function - Steps the CESK machine
  - `run` function - Runs evaluation to completion
  - `primitives` - Built-in operations (`+`, `-`, `*`, `/`, `=`, `<`, `>`)

### Configuration

- **[deps.edn](deps.edn)** - Clojure CLI configuration
- **[project.clj](project.clj)** - Leiningen configuration (alternative)

## 📊 Project Documentation

### Overview

- **[SUMMARY.md](SUMMARY.md)** - Complete project summary
  - What was implemented
  - Test results
  - Architecture highlights
  - Future enhancements

### Test Guides

- **[README_TESTS.md](README_TESTS.md)** - Quick test guide
  - How to run tests
  - What gets tested
  - Requirements

- **[YIN_VM_TESTS.md](YIN_VM_TESTS.md)** - Comprehensive test documentation
  - All test cases
  - Example execution flows
  - Test coverage

- **[FORMAL_TESTS_SUMMARY.md](FORMAL_TESTS_SUMMARY.md)** - Formal test suite details
  - Test structure
  - Coverage metrics
  - CI/CD integration

## 🎓 Learning Path

### Beginner Path

1. Read [SUMMARY.md](SUMMARY.md) - Get the big picture
2. Run `clj test_literal_demo.clj` - See literals in action
3. Read [AST.md Part 1](AST.md#part-1-simple-values-literals) - Understand literals
4. Run `clj test_state_demo.clj` - See state transitions
5. Read [STATE.md](STATE.md) - Understand the CESK machine

### Intermediate Path

1. Run `./run_tests.sh` - See all informal tests
2. Read [YIN_VM_TESTS.md](YIN_VM_TESTS.md) - Understand test coverage
3. Run `./run_formal_tests.sh` - See formal tests
4. Read [test/README.md](test/README.md) - Learn formal testing
5. Study [src/cljc/yin/vm.cljc](src/cljc/yin/vm.cljc) - Understand implementation

### Advanced Path

1. Read [AST_DOCUMENTATION_PLAN.md](AST_DOCUMENTATION_PLAN.md) - See the roadmap
2. Study continuation handling in [STATE.md](STATE.md)
3. Implement new AST node types (Variables, Lambdas, etc.)
4. Add new tests to [test/yin/vm_basic_test.clj](test/yin/vm_basic_test.clj)
5. Contribute to AST documentation

## 🔍 By Topic

### Understanding State

- [STATE.md](STATE.md) - Complete guide
- [test_state_demo.clj](test_state_demo.clj) - Interactive demo
- [src/cljc/yin/vm.cljc](src/cljc/yin/vm.cljc) - Implementation

### Understanding AST

- [AST.md](AST.md) - Main documentation
- [AST_QUICKREF.md](AST_QUICKREF.md) - Quick reference
- [test_literal_demo.clj](test_literal_demo.clj) - Literal examples
- [AST_DOCUMENTATION_PLAN.md](AST_DOCUMENTATION_PLAN.md) - Roadmap

### Understanding Tests

- [README_TESTS.md](README_TESTS.md) - Quick start
- [YIN_VM_TESTS.md](YIN_VM_TESTS.md) - Comprehensive guide
- [test/README.md](test/README.md) - Formal tests
- [FORMAL_TESTS_SUMMARY.md](FORMAL_TESTS_SUMMARY.md) - Test details

### Understanding Continuations

- [STATE.md](STATE.md#continuation---return-context) - Continuation field
- [test_simple_continuation.clj](test_simple_continuation.clj) - Demo
- [test_state_demo.clj](test_state_demo.clj) - Step-by-step transitions

### Understanding Primitives

- [src/cljc/yin/vm.cljc](src/cljc/yin/vm.cljc) - `primitives` definition
- [test_add.clj](test_add.clj) - Addition examples
- [test/yin/vm_basic_test.clj](test/yin/vm_basic_test.clj) - All primitives tested

## 📁 File Organization

```
datomworld/
├── Documentation
│   ├── SUMMARY.md                    # Project summary
│   ├── DOCUMENTATION_INDEX.md        # This file
│   ├── STATE.md                      # State documentation ⭐
│   ├── AST.md                        # AST documentation ⭐
│   ├── AST_QUICKREF.md              # AST quick reference
│   ├── AST_DOCUMENTATION_PLAN.md    # AST roadmap
│   ├── README_TESTS.md              # Test quick guide
│   ├── YIN_VM_TESTS.md              # Comprehensive tests
│   └── FORMAL_TESTS_SUMMARY.md      # Formal test details
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
cat STATE.md
cat AST.md
cat SUMMARY.md
```

## 🎯 Common Tasks

### I want to understand how the VM works
1. Read [STATE.md](STATE.md)
2. Run `clj test_state_demo.clj`
3. Read [src/cljc/yin/vm.cljc](src/cljc/yin/vm.cljc)

### I want to understand the AST
1. Read [AST.md](AST.md)
2. Run `clj test_literal_demo.clj`
3. Check [AST_QUICKREF.md](AST_QUICKREF.md)

### I want to run tests
1. Quick: `./run_tests.sh`
2. Formal: `./run_formal_tests.sh`
3. Read [README_TESTS.md](README_TESTS.md)

### I want to add new features
1. Read [AST_DOCUMENTATION_PLAN.md](AST_DOCUMENTATION_PLAN.md)
2. Study [src/cljc/yin/vm.cljc](src/cljc/yin/vm.cljc)
3. Add tests to [test/yin/vm_basic_test.clj](test/yin/vm_basic_test.clj)

### I want to understand continuations
1. Read [STATE.md](STATE.md) continuation section
2. Run `clj test_simple_continuation.clj`
3. Run `clj test_state_demo.clj`

---

**Last Updated:** STATE.md and AST.md Part 1 complete

**Total Documentation:** 1000+ lines across all files

**Next:** Document AST Part 2 (Variables)
