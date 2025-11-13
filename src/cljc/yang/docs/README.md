# Yang Clojure Compiler

The Yang Clojure compiler transforms Clojure code into the Universal AST format that the Yin VM can execute.

## Overview

Yang is a collection of compilers that generate Universal AST for the Yin VM. This is the **Clojure compiler**, the first in the Yang compiler collection.

Together, Yin and Yang form a dual system:
- **Yin** - The continuation execution engine (passive, runtime)
- **Yang** - The continuation generation engine (active, compile-time)
  - `yang.clojure` - Compiles Clojure to Universal AST
  - `yang.python` - (Future) Compiles Python to Universal AST
  - `yang.javascript` - (Future) Compiles JavaScript to Universal AST

The Clojure compiler transforms Clojure s-expressions into the Universal AST (U-AST), a language-agnostic, map-based structure that Yin can execute.

## Architecture

The Yang compiler consists of three main components:

### 1. Core Compiler (`yang.clojure`)

Platform-agnostic Clojure compiler written in `.cljc` format that runs on both JVM and Node.js.

**Key Functions:**
- `compile` - Main entry point, compiles a Clojure form to Universal AST
- `compile-form` - Internal dispatcher that handles different form types
- `compile-literal` - Handles self-evaluating values
- `compile-variable` - Handles symbol lookup
- `compile-lambda` - Compiles `fn` expressions
- `compile-application` - Compiles function calls
- `compile-if` - Compiles conditionals
- `compile-let` - Compiles let bindings (desugars to lambda applications)
- `compile-do` - Compiles sequential expressions

### 2. JVM I/O (`yang.io` - Clojure)

Platform-specific file I/O for the JVM.

**Functions:**
- `read-source` - Read Clojure source from file
- `compile-file` - Compile a source file to AST
- `compile-string` - Compile a string to AST
- `write-ast` - Save AST to file
- `compile-and-save` - Compile and save in one step

### 3. Node.js I/O (`yang.io` - ClojureScript)

Platform-specific file I/O for Node.js using the `fs` module.

Same API as JVM version for cross-platform compatibility.

## Universal AST Format

The Yang compiler produces Universal AST nodes that conform to the Yin VM specification. See the [Yin VM AST documentation](../../yin/docs/ast.md) for details.

### Supported AST Node Types

#### Literal
```clojure
;; Input: 42
;; Output:
{:type :literal
 :value 42}
```

#### Variable
```clojure
;; Input: x
;; Output:
{:type :variable
 :name x}
```

#### Lambda
```clojure
;; Input: (fn [x y] (+ x y))
;; Output:
{:type :lambda
 :params [x y]
 :body {:type :application
        :operator {:type :variable :name +}
        :operands [{:type :variable :name x}
                   {:type :variable :name y}]}}
```

#### Application
```clojure
;; Input: (+ 1 2)
;; Output:
{:type :application
 :operator {:type :variable :name +}
 :operands [{:type :literal :value 1}
            {:type :literal :value 2}]}
```

#### Conditional
```clojure
;; Input: (if true 1 2)
;; Output:
{:type :if
 :test {:type :literal :value true}
 :consequent {:type :literal :value 1}
 :alternate {:type :literal :value 2}}
```

#### Let Bindings (Desugared)
```clojure
;; Input: (let [x 1] (+ x 2))
;; Output: Desugared to lambda application
{:type :application
 :operator {:type :lambda
            :params [x]
            :body {:type :application
                   :operator {:type :variable :name +}
                   :operands [{:type :variable :name x}
                              {:type :literal :value 2}]}}
 :operands [{:type :literal :value 1}]}
```

## Usage Examples

### Basic Compilation

```clojure
(require '[yang.clojure :as yang])

;; Compile a literal
(yang/compile 42)
;; => {:type :literal, :value 42}

;; Compile a simple expression
(yang/compile '(+ 1 2))
;; => {:type :application
;;     :operator {:type :variable, :name +}
;;     :operands [{:type :literal, :value 1}
;;                {:type :literal, :value 2}]}

;; Compile a lambda
(yang/compile '(fn [x] (* x 2)))
;; => {:type :lambda
;;     :params [x]
;;     :body {...}}
```

### End-to-End: Compile and Execute

```clojure
(require '[yang.clojure :as yang])
(require '[yin.vm :as vm])

;; Compile Clojure code to AST
(def ast (yang/compile '((fn [x y] (+ x y)) 10 20)))

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

### File Compilation (JVM)

```clojure
(require '[yang.io :as io])

;; Compile a file
(def ast (io/compile-file "src/my_program.clj"))

;; Compile and save
(io/compile-and-save "src/my_program.clj" "out/program.ast")

;; Compile string
(def ast (io/compile-string "(+ 1 2 3)"))
```

### File Compilation (Node.js)

```clojure
(require '[yang.io :as io])

;; Same API as JVM version
(def ast (io/compile-file "src/my_program.clj"))
(io/compile-and-save "src/my_program.clj" "out/program.ast")
```

## Supported Clojure Features

### ✅ Implemented

- **Literals**: Numbers, strings, booleans, nil, keywords, vectors, maps
- **Variables**: Symbol lookup
- **Functions**: `fn` expressions with multiple parameters
- **Application**: Function calls with multiple arguments
- **Conditionals**: `if` expressions with optional alternate
- **Let bindings**: `let` with multiple bindings
- **Sequential execution**: `do` blocks
- **Quote**: `quote` for literal data

### 🔄 Planned

- Recursion via `loop`/`recur`
- Destructuring in lambda parameters
- Variadic functions (`&` rest args)
- Multiple arities
- Macros
- `def` for top-level bindings
- Special forms: `try`/`catch`, `throw`

## Technical Details

### Let Desugaring

The compiler transforms `let` bindings into nested lambda applications:

```clojure
;; Source
(let [x 1
      y 2]
  (+ x y))

;; Desugared to
((fn [x]
   ((fn [y]
      (+ x y))
    2))
 1)
```

This transformation ensures that:
1. Bindings are evaluated in order
2. Each binding is in scope for subsequent bindings
3. Closures capture the lexical environment correctly

### Do Block Desugaring

The `do` form is transformed into nested lambda applications that evaluate and discard intermediate results:

```clojure
;; Source
(do expr1 expr2 expr3)

;; Desugared to
((fn [_] ((fn [_] expr3) expr2)) expr1)
```

### Environment Restoration

The Yin VM was enhanced to properly restore lexical environments when popping continuations. This ensures that:
1. Nested function applications work correctly
2. Let-bound closures can reference outer bindings
3. Multiple operand evaluation preserves the calling environment

## Cross-Platform Compatibility

The Yang compiler is designed to run on both JVM and Node.js:

### Core Compiler (`yang.core`)
- Written in `.cljc` (Clojure Common)
- Uses only portable Clojure/ClojureScript features
- No platform-specific dependencies
- 100% code sharing between platforms

### I/O Layer
- JVM: Uses `clojure.java.io` and `clojure.edn`
- Node.js: Uses `fs` module and `cljs.reader`
- Identical API for both platforms
- Easy to add browser support later

## Testing

Comprehensive test suite covering:
- All AST node types
- Edge cases (empty do, if without alternate, etc.)
- End-to-end compilation and execution
- Integration with Yin VM

Run tests:
```bash
# Yang Clojure compiler tests
clj -M:test -n yang.clojure-test

# Yin VM tests (to verify VM changes)
clj -M:test -n yin.vm-basic-test
```

## Project Structure

```
src/
├── cljc/yang/
│   ├── clojure.cljc       # Clojure compiler (portable)
│   └── docs/
│       └── README.md       # This file
├── clj/yang/
│   └── io.clj             # JVM file I/O
└── cljs/yang/
    └── io.cljs            # Node.js file I/O

test/yang/
└── clojure_test.clj       # Comprehensive tests
```

## Future Enhancements

1. **Source Maps**: Preserve line/column information for debugging
2. **Optimization Passes**: Constant folding, dead code elimination
3. **Type Inference**: Optional static typing
4. **Macro Expansion**: Support for Clojure macros
5. **Multi-language Support**: Python, JavaScript, Java to U-AST
6. **Incremental Compilation**: Compile only changed code
7. **REPL Integration**: Interactive compilation
8. **AST Visualization**: Tools to inspect compiled output

## See Also

- [Yin VM Documentation](../../yin/docs/documentation_index.md) - Execution engine
- [Yin VM AST Spec](../../yin/docs/ast.md) - Universal AST format
- [Yin VM State](../../yin/docs/state.md) - CESK machine state

## License

Same as parent project.
