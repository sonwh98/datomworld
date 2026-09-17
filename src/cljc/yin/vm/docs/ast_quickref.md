# Yin VM - AST Quick Reference

Quick reference for the Universal AST node types. See [ast.md](ast.md) for detailed documentation.

## Node Types

### ✅ Part 1: Literals (Simple Values)

```clojure
;; Integer
{:type :literal :value 42}

;; String
{:type :literal :value "hello"}

;; Boolean
{:type :literal :value true}

;; Collections
{:type :literal :value [1 2 3]}
{:type :literal :value {:key "value"}}
```

**Evaluation:** Self-evaluating, returns value immediately.

---

### 🔄 Part 2: Variables (Coming Next)

```clojure
;; Variable reference
{:type :variable :name 'x}
```

---

### 🔄 Part 3: Lambdas (Coming Next)

```clojure
;; Lambda expression
{:type :lambda
 :params ['x 'y]
 :body <ast-node>}
```

---

### 🔄 Part 4: Application (Coming Next)

```clojure
;; Function call
{:type :application
 :operator <ast-node>
 :operands [<ast-node> ...]}
```

---

### 🔄 Part 5: Conditionals (Coming Next)

```clojure
;; If expression
{:type :if
 :test <ast-node>
 :consequent <ast-node>
 :alternate <ast-node>}
```

---

## Quick Examples

> **Status (2026-09-16):** these examples use the v1 `yin.vm` / `yin.vm.ast-walker` API, deleted by `yin.vm.v1-retirement.implementation-plan.md`; the live evaluator is `yin.vm` (see `test/yin/vm/test_utils.cljc` for how to run an AST).

### Evaluate a Literal
```clojure
(require '[yin.vm :as vm]
         '[yin.vm.ast-walker :as walker])

(walker/run (walker/make-state {}) {:type :literal :value 42})
;; => {:value 42, ...}
```

### Test in REPL
```bash
clj -e "(require '[yin.vm :as vm] '[yin.vm.ast-walker :as walker]) (defn eval-ast [ast] (:value (walker/run (walker/make-state {}) ast))) (eval-ast {:type :literal :value 42})"
```

---

## Status

- ✅ **Literals** - Documented in [ast.md Part 1](ast.md#part-1-simple-values-literals)
- 🔄 **Variables** - Coming next
- 🔄 **Lambdas** - Coming soon
- 🔄 **Application** - Coming soon
- 🔄 **Conditionals** - Coming soon

---

See [ast.md](ast.md) for full documentation with examples, semantics, and test cases.
