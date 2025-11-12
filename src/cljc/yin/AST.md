# Yin VM - Universal AST Documentation

This document incrementally describes the Universal AST (Abstract Syntax Tree) used by the Yin Virtual Machine. The AST is a language-agnostic, map-based structure that represents all executable code as immutable data.

## Design Principles

1. **Universal** - Can represent code from multiple languages (Clojure, Python, JavaScript, etc.)
2. **Map-based** - Every node is a Clojure map with a `:type` field
3. **Immutable** - AST nodes never change; transformations create new nodes
4. **Self-describing** - Contains syntax, semantics, and metadata
5. **Executable** - The Yin VM evaluates the AST directly

## AST Node Structure

Every AST node is a map with at minimum:
```clojure
{:type <node-type>  ; Required: identifies the kind of expression
 ...}               ; Additional fields specific to the node type
```

---

## Part 1: Simple Values (Literals)

Literals are self-evaluating values - they evaluate to themselves.

### Literal Node

**Type:** `:literal`

**Purpose:** Represents a constant value (number, string, boolean, nil, etc.)

**Structure:**
```clojure
{:type :literal
 :value <any-clojure-value>}
```

**Fields:**
- `:type` - Always `:literal`
- `:value` - The actual constant value

### Examples

#### Integer Literal
```clojure
;; Represents: 42
{:type :literal
 :value 42}
```

**Evaluation:**
```clojure
(def ast {:type :literal :value 42})
(vm/run (make-state {}) ast)
;; => {:value 42, :control nil, ...}
```

#### String Literal
```clojure
;; Represents: "hello world"
{:type :literal
 :value "hello world"}
```

**Evaluation:**
```clojure
(def ast {:type :literal :value "hello world"})
(vm/run (make-state {}) ast)
;; => {:value "hello world", :control nil, ...}
```

#### Boolean Literal
```clojure
;; Represents: true
{:type :literal
 :value true}

;; Represents: false
{:type :literal
 :value false}
```

#### Nil Literal
```clojure
;; Represents: nil
{:type :literal
 :value nil}
```

#### Floating Point Literal
```clojure
;; Represents: 3.14159
{:type :literal
 :value 3.14159}
```

#### Negative Number Literal
```clojure
;; Represents: -42
{:type :literal
 :value -42}
```

### Evaluation Semantics

When the Yin VM encounters a literal node:

1. **Extract the value** - Get the `:value` field
2. **Return immediately** - Literals don't require further computation
3. **Set control to nil** - Evaluation is complete
4. **Store value in state** - Result is placed in the `:value` field of the state

**Implementation (from vm.cljc):**
```clojure
:literal
(let [{:keys [value]} node]
  (assoc state :value value :control nil))
```

### Key Characteristics

- ✅ **Self-evaluating** - The value is the result
- ✅ **No dependencies** - Doesn't reference environment or store
- ✅ **Immediate** - Evaluates in a single step
- ✅ **Type-agnostic** - Can hold any Clojure value
- ✅ **Immutable** - The value never changes

### Test Examples

From [test/yin/vm_basic_test.clj](test/yin/vm_basic_test.clj):

```clojure
(deftest test-literal-evaluation
  (testing "Literal values evaluate to themselves"
    ;; Integer literal
    (let [ast {:type :literal :value 42}
          result (vm/run (make-state {}) ast)]
      (is (= 42 (:value result))))

    ;; String literal
    (let [ast {:type :literal :value "hello"}
          result (vm/run (make-state {}) ast)]
      (is (= "hello" (:value result))))))
```

### Usage in Larger Expressions

Literals are the building blocks of more complex expressions. They're commonly used as:
- **Function arguments**: `(+ 10 20)` - both `10` and `20` are literals
- **Return values**: `(lambda () 42)` - returns the literal `42`
- **Comparison operands**: `(= x 5)` - `5` is a literal

### Interactive Examples

Try these in a REPL:

```clojure
;; Load the VM
(require '[yin.vm :as vm])

;; Helper function
(defn eval-literal [value]
  (vm/run {:control nil :environment {} :store {} :continuation nil :value nil}
          {:type :literal :value value}))

;; Evaluate different literals
(:value (eval-literal 42))           ;; => 42
(:value (eval-literal "test"))       ;; => "test"
(:value (eval-literal true))         ;; => true
(:value (eval-literal [1 2 3]))      ;; => [1 2 3]
(:value (eval-literal {:a 1 :b 2}))  ;; => {:a 1, :b 2}
```

### Notes

- Literals can contain **any Clojure value**, including:
  - Numbers (integers, floats, ratios)
  - Strings
  - Booleans
  - Keywords
  - Symbols (as values, not variables)
  - Collections (vectors, maps, sets, lists)
  - Even functions (though typically functions are closures)

- In the Universal AST, literals from different source languages all compile to this same structure:
  ```
  Python:    42  →  {:type :literal, :value 42}
  JavaScript: 42 →  {:type :literal, :value 42}
  Clojure:   42  →  {:type :literal, :value 42}
  ```

---

**Next:** [Part 2: Variables](AST.md#part-2-variables) (to be added)

## Summary

Literal nodes are the simplest form of AST node:
- Single `:type` field set to `:literal`
- Single `:value` field containing the constant
- Self-evaluating with no side effects
- Foundation for all other expressions

The simplicity of literals demonstrates the power of the Universal AST - even the most basic values are represented uniformly as data.
