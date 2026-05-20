# Yin VM - AST Documentation Plan

## Overview

This document outlines the incremental plan for documenting the Universal AST used by the Yin Virtual Machine.

## Documentation Structure

### Main Documentation: [ast.md](ast.md)
Comprehensive, incremental documentation with:
- Detailed explanations
- Code examples
- Evaluation semantics
- Test references
- Interactive REPL examples

### Quick Reference: [ast_quickref.md](ast_quickref.md)
Single-page quick reference for all node types

### Demo Scripts
- [test_literal_demo.clj](../../../../../test_literal_demo.clj) - Interactive literal evaluation

---

## Documentation Plan

### ✅ Part 1: Literals (Simple Values) - COMPLETE

**Status:** Documented in [ast.md Part 1](ast.md#part-1-simple-values-literals)

**Coverage:**
- ✅ Node structure: `{:type :literal :value <value>}`
- ✅ All value types (numbers, strings, booleans, collections)
- ✅ Evaluation semantics
- ✅ Implementation details
- ✅ Test examples
- ✅ Interactive REPL examples
- ✅ Demo script

**Files:**
- [ast.md](ast.md) - Lines 23-206
- [test_literal_demo.clj](../../../../../test_literal_demo.clj)
- [ast_quickref.md](ast_quickref.md)

---

### 🔄 Part 2: Variables (Next)

**Node Type:** `:variable`

**To Document:**
- [ ] Node structure: `{:type :variable :name 'x}`
- [ ] Environment lookup semantics
- [ ] Lexical scoping
- [ ] Variable binding
- [ ] Free vs bound variables
- [ ] Test examples
- [ ] Interactive examples

**Implementation Reference:** [src/cljc/yin/vm.cljc](vm.cljc) lines 40-44

**Test Reference:** [test/yin/vm_basic_test.clj](../../../../../test/yin/vm_basic_test.clj) - `test-variable-lookup`

---

### 🔄 Part 3: Lambdas (Closures)

**Node Type:** `:lambda`

**To Document:**
- [ ] Node structure: `{:type :lambda :params [...] :body <ast>}`
- [ ] Closure creation
- [ ] Environment capture
- [ ] Parameter binding
- [ ] Lexical scope
- [ ] Test examples
- [ ] Interactive examples

**Implementation Reference:** [src/cljc/yin/vm.cljc](vm.cljc) lines 47-54

**Test Reference:** [test/yin/vm_basic_test.clj](../../../../../test/yin/vm_basic_test.clj) - `test-lambda-closure`

---

### 🔄 Part 4: Application (Function Calls)

**Node Type:** `:application`

**To Document:**
- [ ] Node structure: `{:type :application :operator <ast> :operands [<ast> ...]}`
- [ ] Function application semantics
- [ ] Argument evaluation (left-to-right)
- [ ] Continuation-based evaluation
- [ ] Primitive vs closure application
- [ ] Test examples
- [ ] Interactive examples

**Implementation Reference:** [src/cljc/yin/vm.cljc](vm.cljc) lines 57-116

**Test Reference:** [test/yin/vm_basic_test.clj](../../../../../test/yin/vm_basic_test.clj) - `test-primitive-addition`, `test-lambda-addition`

---

### 🔄 Part 5: Conditionals

**Node Type:** `:if`

**To Document:**
- [ ] Node structure: `{:type :if :test <ast> :consequent <ast> :alternate <ast>}`
- [ ] Conditional evaluation
- [ ] Branch selection
- [ ] Test evaluation semantics
- [ ] Test examples
- [ ] Interactive examples

**Implementation Reference:** [src/cljc/yin/vm.cljc](vm.cljc) lines 118-134

**Test Reference:** [test/yin/vm_test.cljc](../../../../../test/yin/vm_test.cljc) - `test-if-true`

---

## Documentation Style Guidelines

Each part should include:

### 1. Introduction
- Brief description
- Purpose and use cases
- When to use this node type

### 2. Structure
- Full node structure
- Field descriptions
- Required vs optional fields

### 3. Examples
- Simple example
- Complex example
- Real-world usage

### 4. Evaluation Semantics
- Step-by-step evaluation
- State transitions
- CESK machine behavior

### 5. Implementation
- Code snippet from vm.cljc
- Explanation of implementation

### 6. Test Cases
- Reference to formal tests
- Expected behavior
- Edge cases

### 7. Interactive Examples
- REPL-ready code
- Helper functions
- Multiple examples

### 8. Visual Diagrams (when helpful)
- Evaluation flow
- State transitions
- Memory/environment layout

---

## Supporting Materials

### Demo Scripts (per node type)
- `test_literal_demo.clj` ✅
- `test_variable_demo.clj` 🔄
- `test_lambda_demo.clj` 🔄
- `test_application_demo.clj` 🔄
- `test_conditional_demo.clj` 🔄

### Visual Diagrams
- Evaluation flow diagrams
- State transition diagrams
- Environment/closure diagrams

### Cross-References
- Link to implementation
- Link to tests
- Link to related node types

---

## Progress Tracking

| Part | Node Type | Status | Lines | Demo |
|------|-----------|--------|-------|------|
| 1 | Literal | ✅ Complete | 183 | ✅ |
| 2 | Variable | 🔄 Next | - | - |
| 3 | Lambda | 📋 Planned | - | - |
| 4 | Application | 📋 Planned | - | - |
| 5 | Conditional | 📋 Planned | - | - |

---

## Success Criteria

Each part is complete when:
- ✅ Documented in ast.md with all sections
- ✅ Added to ast_quickref.md
- ✅ Demo script created and tested
- ✅ All examples verified in REPL
- ✅ Cross-references validated
- ✅ Reviewed for clarity and accuracy

---

## Notes

- Keep incremental - one part at a time
- Test all examples before documenting
- Use real code from vm.cljc
- Link to actual tests
- Provide runnable examples
- Clear, concise explanations
- Progressive complexity

---

## Current Status

**Last Updated:** Part 1 (Literals) completed

**Next Step:** Part 2 (Variables)

**Estimated Completion:** 5 parts total, ~1000 lines of documentation

---

See [ast.md](ast.md) for the main documentation.
