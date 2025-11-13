# Yang Python Compiler

The Yang Python compiler transforms Python code into the Universal AST format that the Yin VM can execute.

## Overview

This is the **Python compiler** in the Yang compiler collection. It parses Python source code and compiles it to the same Universal AST format as the Clojure compiler, enabling:
- **Multi-language programs**: Mix Python and Clojure code
- **Cross-platform execution**: Python code runs on both JVM and Node.js via Yin VM
- **Language interoperability**: Call Python functions from Clojure and vice versa

## Supported Python Features

### ✅ Implemented

- **Literals**
  - Numbers: `42`, `3.14`, `-10`
  - Strings: `"hello"`, `'world'`
  - Booleans: `True`, `False`
  - None: `None`

- **Variables**
  - Identifiers: `x`, `foo`, `my_var`

- **Binary Operators**
  - Arithmetic: `+`, `-`, `*`, `/`
  - Comparison: `==`, `!=`, `<`, `>`, `<=`, `>=`
  - Logical: `and`, `or` (partial support)
  - Operator precedence: `2 + 3 * 4` → `2 + (3 * 4)`

- **Lambda Expressions**
  - Single parameter: `lambda x: x * 2`
  - Multiple parameters: `lambda x, y: x + y`
  - Complex body: `lambda x: x * 2 + 1`

- **Function Definitions**
  - Simple: `def double(x): return x * 2`
  - Multiple parameters: `def add(x, y): return x + y`
  - Note: `def` compiles to a lambda (no statements, expression-based)

- **Function Calls**
  - Simple: `f(5)`
  - Multiple arguments: `add(1, 2)`
  - Nested: `f(g(5))`
  - Lambda application: `(lambda x: x * 2)(21)`

- **If Expressions**
  - Ternary: `10 if x > 5 else 20`
  - With comparison: `100 if 3 < 5 else 200`
  - Nested: `(1 if x < 5 else 2) if True else 3`

### 🔄 Planned

- **Statements**
  - Multi-line functions
  - Assignments
  - For/while loops
  - Try/except

- **Data Structures**
  - Lists: `[1, 2, 3]`
  - Dictionaries: `{"a": 1, "b": 2}`
  - Tuples: `(1, 2, 3)`

- **Advanced Features**
  - List comprehensions
  - Generators
  - Decorators
  - Classes and objects
  - Import statements

## Usage Examples

### Basic Compilation

```clojure
(require '[yang.python :as py])

;; Compile a literal
(py/compile "42")
;; => {:type :literal, :value 42}

;; Compile an expression
(py/compile "1 + 2")
;; => {:type :application
;;     :operator {:type :variable, :name +}
;;     :operands [{:type :literal, :value 1}
;;                {:type :literal, :value 2}]}

;; Compile a lambda
(py/compile "lambda x: x * 2")
;; => {:type :lambda
;;     :params [x]
;;     :body {...}}
```

### End-to-End: Compile and Execute

```clojure
(require '[yang.python :as py])
(require '[yin.vm :as vm])

;; Compile Python code to AST
(def ast (py/compile "(lambda x, y: x + y)(10, 20)"))

;; Execute with Yin VM
(def result (vm/run {:control nil
                     :environment vm/primitives
                     :store {}
                     :continuation nil
                     :value nil}
                    ast))

(:value result)
;; => 30
```

### Multi-Language Composition

```clojure
(require '[yang.python :as py])
(require '[yang.clojure :as clj])
(require '[yin.vm :as vm])

;; Define a function in Python
(def py-double (py/compile "lambda x: x * 2"))

;; Define a function in Clojure
(def clj-add10 (clj/compile '(fn [x] (+ x 10))))

;; Compose them: add10(double(5))
(def composed
  {:type :application
   :operator clj-add10
   :operands [{:type :application
               :operator py-double
               :operands [{:type :literal :value 5}]}]})

(vm/run initial-state composed)
;; => {:value 20 ...}  ;; add10(double(5)) = add10(10) = 20
```

## Python to Universal AST Mapping

### Lambda Expressions

```python
# Python
lambda x, y: x + y
```

```clojure
;; Universal AST
{:type :lambda
 :params [x y]
 :body {:type :application
        :operator {:type :variable :name +}
        :operands [{:type :variable :name x}
                   {:type :variable :name y}]}}
```

### Function Definitions

```python
# Python
def add(x, y):
    return x + y
```

```clojure
;; Universal AST (same as lambda!)
{:type :lambda
 :params [x y]
 :body {:type :application
        :operator {:type :variable :name +}
        :operands [{:type :variable :name x}
                   {:type :variable :name y}]}}
```

### If Expressions

```python
# Python
10 if x > 5 else 20
```

```clojure
;; Universal AST
{:type :if
 :test {:type :application
        :operator {:type :variable :name >}
        :operands [{:type :variable :name x}
                   {:type :literal :value 5}]}
 :consequent {:type :literal :value 10}
 :alternate {:type :literal :value 20}}
```

### Binary Operations

```python
# Python
2 + 3 * 4
```

```clojure
;; Universal AST (respects operator precedence)
{:type :application
 :operator {:type :variable :name +}
 :operands [{:type :literal :value 2}
            {:type :application
             :operator {:type :variable :name *}
             :operands [{:type :literal :value 3}
                        {:type :literal :value 4}]}]}
```

## Architecture

### Three-Stage Compilation

```
Python Source → Python AST → Universal AST → Yin VM
     |              |              |             |
  "x + 1"   {:py-type :binop}  {:type :app}  Execution
```

1. **Tokenizer** - Breaks source into tokens
2. **Parser** - Builds Python-specific AST
3. **Compiler** - Transforms to Universal AST

### Tokenizer

Converts Python source into token stream:

```clojure
(tokenize "lambda x: x * 2")
;; => [[:keyword "lambda"]
;;     [:identifier "x"]
;;     [:colon ":"]
;;     [:identifier "x"]
;;     [:operator "*"]
;;     [:number "2"]]
```

### Parser

Builds Python AST with operator precedence:

```clojure
(parse-python "2 + 3 * 4")
;; => {:py-type :binop
;;     :op +
;;     :left {:py-type :literal :value 2}
;;     :right {:py-type :binop
;;             :op *
;;             :left {:py-type :literal :value 3}
;;             :right {:py-type :literal :value 4}}}
```

### Compiler

Transforms Python AST to Universal AST:

```clojure
(compile-py-expr python-ast)
;; => {:type :application
;;     :operator {:type :variable :name +}
;;     :operands [...]}
```

## Implementation Details

### Written in CLJC

The Python compiler is written in `.cljc` format, making it portable:
- **JVM**: Full Clojure environment
- **Node.js**: ClojureScript compilation
- **Browser**: (Future) In-browser Python compilation

### No External Dependencies

The compiler is self-contained:
- Custom tokenizer
- Recursive descent parser
- No Python runtime required
- Pure data transformation

### Operator Precedence

Implements proper Python operator precedence:

| Precedence | Operators |
|------------|-----------|
| 3 (highest)| `*`, `/`  |
| 2          | `+`, `-`  |
| 1          | `==`, `!=`, `<`, `>`, `<=`, `>=` |
| 0 (lowest) | `and`, `or` |

### Expression-Based

The current implementation focuses on expressions:
- All Python code must be expressible as a single expression
- `def` statements are transformed to lambdas
- `return` statements extract the returned value
- Statements like `print()` are not yet supported

## Testing

Comprehensive test suite with 70+ assertions:

```bash
# Run Python compiler tests
clj -M:test -n yang.python-test
```

Test coverage:
- ✅ Tokenization
- ✅ Literals (all types)
- ✅ Variables
- ✅ Binary operations with precedence
- ✅ Lambda expressions
- ✅ Function definitions
- ✅ Function calls
- ✅ If expressions
- ✅ End-to-end compilation and execution
- ✅ Multi-language composition with Clojure
- ✅ Error handling

## Demo

Run the demo to see the Python compiler in action:

```bash
clj demo_python.clj
```

Examples include:
1. Simple literals
2. Arithmetic expressions
3. Lambda expressions
4. Function definitions
5. Operator precedence
6. If expressions
7. Higher-order functions
8. **Multi-language composition** (Python + Clojure!)

## Comparison with Clojure Compiler

Both compilers produce equivalent Universal AST:

```clojure
;; Python
(py/compile "lambda x: x * 2")

;; Clojure
(clj/compile '(fn [x] (* x 2)))

;; Both produce:
{:type :lambda
 :params [x]
 :body {:type :application
        :operator {:type :variable :name *}
        :operands [{:type :variable :name x}
                   {:type :literal :value 2}]}}
```

This equivalence enables seamless interoperability!

## Limitations

### Current Limitations

1. **Expression-based only**: No multi-line statements
2. **No imports**: Cannot import Python modules
3. **No classes**: Object-oriented features not supported
4. **No assignments**: Variables are immutable
5. **Limited standard library**: Only basic operators

### Design Limitations

Since Python and Yin VM have different execution models:
- Python is statement-based, Yin is expression-based
- Python has mutable state, Yin is immutable
- Python has exceptions, Yin has continuations

These differences mean some Python features may not map directly to Universal AST.

## Future Enhancements

1. **Statement support**: Multi-line function bodies
2. **Data structures**: Lists, dictionaries, tuples
3. **List comprehensions**: `[x * 2 for x in range(10)]`
4. **Full standard library**: Map Python builtins to Yin primitives
5. **Class support**: Object-oriented programming
6. **Async/await**: Asynchronous operations via continuations
7. **Import system**: Module management

## Project Structure

```
src/cljc/yang/
├── python.cljc        # Python compiler (450+ lines)
└── docs/
    └── python.md      # This file

test/yang/
└── python_test.clj    # Comprehensive tests (250+ lines)

demo_python.clj        # Demo script (100+ lines)
```

## See Also

- [Yang Architecture](architecture.md) - Multi-language compiler design
- [Yang Clojure Compiler](README.md) - Sister compiler
- [Yin VM Documentation](../../yin/docs/documentation_index.md) - Execution engine
- [Universal AST Specification](../../yin/docs/ast.md) - AST format details

## Contributing

To extend the Python compiler:

1. Add new token types to the tokenizer
2. Extend the parser for new syntax
3. Map new Python constructs to Universal AST
4. Add tests for new features
5. Update documentation

The compiler is designed to be easily extensible!
