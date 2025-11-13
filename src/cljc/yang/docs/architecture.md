# Yang Compiler Architecture

## Overview

Yang is a **collection of compilers** that transform source code from different programming languages into the Universal AST format that the Yin VM can execute.

## Compiler Collection

```
yang/
├── clojure.cljc       ✅ Clojure → Universal AST (implemented)
├── python.cljc        ✅ Python → Universal AST (implemented)
├── javascript.cljc    🔄 JavaScript → Universal AST (planned)
├── java.cljc          🔄 Java → Universal AST (planned)
└── ...                   More languages as needed
```

## Current Implementation: `yang.clojure`

The first compiler in the Yang collection. Transforms Clojure s-expressions into Universal AST.

### Usage Pattern

```clojure
(require '[yang.clojure :as clj-compiler])

;; Compile Clojure code
(def ast (clj-compiler/compile '(+ 1 2)))

;; Execute with Yin VM
(require '[yin.vm :as vm])
(vm/run initial-state ast)
```

## Design Philosophy

### 1. Language-Specific Compilers
Each language gets its own namespace:
- `yang.clojure` - Understands Clojure syntax and semantics
- `yang.python` - (Future) Understands Python syntax and semantics
- `yang.javascript` - (Future) Understands JavaScript syntax and semantics

### 2. Unified Output
All compilers produce the same Universal AST format:
- `:literal` - Self-evaluating values
- `:variable` - Symbol references
- `:lambda` - Function definitions
- `:application` - Function calls
- `:if` - Conditionals

### 3. Cross-Platform
Each compiler is written in `.cljc` format to run on both JVM and Node.js.

## Future Compilers

### Python Compiler (`yang.python`)
```clojure
(require '[yang.python :as py-compiler])

;; Compile Python code
(def ast (py-compiler/compile "def add(x, y): return x + y"))
```

### JavaScript Compiler (`yang.javascript`)
```clojure
(require '[yang.javascript :as js-compiler])

;; Compile JavaScript code
(def ast (js-compiler/compile "function add(x, y) { return x + y; }"))
```

### Java Compiler (`yang.java`)
```clojure
(require '[yang.java :as java-compiler])

;; Compile Java code
(def ast (java-compiler/compile "int add(int x, int y) { return x + y; }"))
```

## Benefits of This Architecture

### 1. Extensibility
Adding a new language is straightforward:
1. Create `yang.<language>.cljc`
2. Implement parser for that language's syntax
3. Transform to Universal AST
4. Done! The Yin VM can now execute code from that language

### 2. Language Interoperability
Since all languages compile to the same Universal AST:
- Call Python functions from Clojure code
- Mix JavaScript and Java code
- Share data structures across languages
- Serialize continuations and migrate between languages

### 3. Unified Runtime
One execution engine (Yin VM) for all languages:
- Consistent performance characteristics
- Shared optimizations benefit all languages
- Single debugging/profiling infrastructure
- Portable bytecode (Universal AST)

### 4. Code Reusability
Common compiler infrastructure can be shared:
- AST validation
- Optimization passes
- Source maps
- Error reporting

## Implementation Status

### ✅ Completed: Clojure Compiler
- **File**: `src/cljc/yang/clojure.cljc`
- **Lines**: 226 lines of code
- **Features**: Literals, variables, lambdas, applications, conditionals, let, do, quote
- **Tests**: 12 test suites, 61 assertions, 100% passing
- **Platforms**: JVM and Node.js

### ✅ Completed: Python Compiler
- **File**: `src/cljc/yang/python.cljc`
- **Lines**: 450+ lines of code
- **Features**: Literals, variables, lambdas, def statements, binary ops, if expressions, function calls
- **Tests**: 12 test suites, 70 assertions, 100% passing
- **Platforms**: JVM and Node.js
- **Includes**: Custom tokenizer and recursive descent parser

### 🔄 Planned: JavaScript Compiler
- Parse JavaScript AST
- Transform JavaScript semantics to Universal AST
- Handle JavaScript-specific features (async/await, prototypes, etc.)

### 🔄 Planned: Java Compiler
- Parse Java AST
- Transform Java semantics to Universal AST
- Handle Java-specific features (classes, interfaces, generics, etc.)

## Universal AST as Lingua Franca

The Universal AST serves as the **lingua franca** (common language) for all source languages:

```
┌─────────────┐
│   Clojure   │──┐
└─────────────┘  │
                 │
┌─────────────┐  │    ┌──────────────────┐    ┌──────────┐
│   Python    │──┼───→│  Universal AST   │───→│  Yin VM  │
└─────────────┘  │    └──────────────────┘    └──────────┘
                 │
┌─────────────┐  │
│ JavaScript  │──┘
└─────────────┘
```

## Example: Multi-Language Program

```clojure
;; Define a function in Clojure
(def clj-double (yang.clojure/compile '(fn [x] (* x 2))))

;; Define a function in Python (future)
(def py-triple (yang.python/compile "lambda x: x * 3"))

;; Define a function in JavaScript (future)
(def js-add (yang.javascript/compile "(x, y) => x + y"))

;; Compose them in the Yin VM
(def composed-ast
  {:type :application
   :operator clj-double
   :operands [{:type :application
               :operator py-triple
               :operands [{:type :literal :value 5}]}]})

;; Execute: double(triple(5)) = double(15) = 30
(vm/run initial-state composed-ast)
;; => {:value 30 ...}
```

## See Also

- [Yang Clojure Compiler](README.md) - Current implementation details
- [Yin VM Documentation](../../yin/docs/documentation_index.md) - Execution engine
- [Universal AST Specification](../../yin/docs/ast.md) - AST format details
