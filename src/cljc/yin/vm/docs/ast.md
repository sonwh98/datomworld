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
(walker/run (walker/make-state {}) ast)
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
(walker/run (walker/make-state {}) ast)
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

From [test/yin/vm_basic_test.clj](../../../../../test/yin/vm_basic_test.clj):

```clojure
(deftest test-literal-evaluation
  (testing "Literal values evaluate to themselves"
    ;; Integer literal
    (let [ast {:type :literal :value 42}
          result (walker/run (walker/make-state {}) ast)]
      (is (= 42 (:value result))))

    ;; String literal
    (let [ast {:type :literal :value "hello"}
          result (walker/run (walker/make-state {}) ast)]
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
  (walker/run {:control nil :environment {} :store {} :continuation nil :value nil}
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

## Part 2: Variables

Variables represent named references to values in the lexical environment, primitive operations, or the global store.

### Variable Node

**Type:** `:variable`

**Purpose:** To retrieve a value by its symbol name.

**Structure:**
```clojure
{:type :variable
 :name <symbol>}
```

**Fields:**
- `:type` - Always `:variable`
- `:name` - The symbol representing the variable name (e.g., `x`, `+`, `my-var`)

### Examples

#### Local Variable
```clojure
;; Represents: x
{:type :variable
 :name 'x}
```

#### Primitive Operation
```clojure
;; Represents: +
{:type :variable
 :name '+}
```

### Evaluation Semantics

When the Yin VM encounters a variable node:

1. **Resolve the name** - The VM looks for the name in the following order:
   - **Lexical Environment**: The current scope (e.g., function parameters, let bindings)
   - **Primitives**: Built-in functions defined in the VM (e.g., `+`, `-`, `assoc`)
   - **Store**: The global heap/store (if applicable)
2. **Return the value** - If found, the value is placed in the `:value` field of the state.
3. **Handle missing variables** - If the name cannot be resolved, an error is typically thrown.

**Implementation (from ast_walker.cljc):**
```clojure
:variable
(let [value (engine/resolve-var env store primitives (:name node))]
  (cesk-return state nil env k value))
```

### Key Characteristics

- 🔍 **Lookup-based** - Requires searching an environment or store
- 📦 **Context-dependent** - The same variable can evaluate to different values in different scopes
- 🛠️ **Primitive Access** - Primitives are treated as variables with special pre-bound values

### Usage Examples

Variables are used whenever you need to reference a value that isn't a literal:
- **Function parameters**: Referencing arguments passed to a lambda
- **Mathematical operations**: Referencing `+`, `-`, etc.
- **Global state**: Accessing shared values in the store

---

## Part 3: Lambdas and Closures

Lambdas are the primary mechanism for defining functions. When evaluated, a lambda node produces a **closure**—a value that combines the function's code with the environment in which it was created.

### Lambda Node

**Type:** `:lambda`

**Purpose:** To define an anonymous function or a macro.

**Structure:**
```clojure
{:type :lambda
 :params [<symbol> ...]  ; Vector of parameter names
 :body <ast-node>        ; The expression to evaluate when called
 :macro? <boolean>       ; Optional: true if this is a macro
 :phase-policy <keyword> ; Optional: :compile, :runtime, or :both
 }
```

**Fields:**
- `:type` - Always `:lambda`
- `:params` - A vector of symbols representing the arguments the function accepts.
- `:body` - An AST node representing the function's implementation.
- `:macro?` - (Optional) If `true`, the function is treated as a macro.
- `:phase-policy` - (Optional) Specifies when the macro should run.

### Examples

#### Simple Identity Function
```clojure
;; Represents: (lambda (x) x)
{:type :lambda
 :params ['x]
 :body {:type :variable :name 'x}}
```

#### Constant Function
```clojure
;; Represents: (lambda () 42)
{:type :lambda
 :params []
 :body {:type :literal :value 42}}
```

### Evaluation Semantics (Closure Creation)

When the Yin VM encounters a lambda node, it doesn't execute the body. Instead, it creates a **closure**:

1. **Capture Environment** - The VM takes the current lexical environment (`env`).
2. **Create Closure Value** - It returns a map containing the params, body, and the captured environment.

**Implementation (from ast_walker.cljc):**
```clojure
:lambda 
(let [{:keys [params body]} node]
  (cesk-return state nil env k 
               {:type :closure, :params params, :body body, :env env}))
```

### Closure Structure (Runtime Value)

The resulting closure is a runtime value:
```clojure
{:type :closure
 :params [...]
 :body <ast-node>
 :env {...}  ; The captured lexical scope
 }
```

### Key Characteristics

- 📦 **Encapsulation** - Lambdas bundle code with state (the environment).
- 🔗 **Lexical Scoping** - Closures "remember" the variables available when they were defined.
- 🏗️ **Deferred Execution** - The body is only evaluated when the closure is applied (see Part 4).
- 🪄 **Macro Capability** - Lambdas can be marked as macros for source-to-source transformation.

---

## Part 4: Function Application

Function application (or "calling" a function) is how code is executed. It involves evaluating an operator and its operands, then applying the resulting function to the resulting values.

### Application Node

**Type:** `:application`

**Purpose:** To invoke a function or primitive.

**Structure:**
```clojure
{:type :application
 :operator <ast-node>    ; The expression that produces a function
 :operands [<ast-node> ...] ; The expressions that produce arguments
 :tail? <boolean>        ; Optional: true if this is a tail call
 }
```

**Fields:**
- `:type` - Always `:application`
- `:operator` - An AST node that, when evaluated, must yield a function (primitive or closure).
- `:operands` - A vector of AST nodes to be evaluated as arguments.
- `:tail?` - (Optional) If `true`, indicates this call is in tail position, allowing the VM to perform tail-call optimization (TCO).

### Examples

#### Calling a Primitive
```clojure
;; Represents: (+ 1 2)
{:type :application
 :operator {:type :variable :name '+}
 :operands [{:type :literal :value 1}
            {:type :literal :value 2}]}
```

#### Calling a Lambda (Immediately Invoked)
```clojure
;; Represents: ((lambda (x) x) 42)
{:type :application
 :operator {:type :lambda :params ['x] :body {:type :variable :name 'x}}
 :operands [{:type :literal :value 42}]}
```

### Evaluation Semantics

The evaluation of an application node follows several steps:

1. **Evaluate Operator** - The VM first evaluates the `:operator` node to get a function value.
2. **Evaluate Operands** - The VM then evaluates each node in `:operands` to get a list of argument values.
3. **Apply Function**:
   - **If Primitive**: The VM calls the host-language function (e.g., Clojure's `+`) with the evaluated arguments.
   - **If Closure**: The VM extends the closure's captured environment by binding the `:params` to the argument values, then evaluates the closure's `:body` in this new environment.

**Implementation (from ast_walker.cljc):**
```clojure
;; Start evaluating operator
:application (cesk-return state (:operator node) env 
                          {:frame node, :next k, :env env, :type :eval-operator}
                          (:value state))

;; Apply logic
(defn- apply-function [state fn-value evaluated-operands k env]
  (cond
    (fn? fn-value) ;; Primitive
    (handle-primitive-result state (apply fn-value evaluated-operands) k env)
    
    (= :closure (:type fn-value)) ;; User-defined
    (let [{:keys [params body], closure-env :env} fn-value
          extended-env (merge closure-env (zipmap params evaluated-operands))]
      (cesk-return state body extended-env k (:value state)))))
```

### Key Characteristics

- 🔄 **Iterative Evaluation** - Multiple nodes are evaluated before the final application occurs.
- 🧵 **Continuations** - The VM uses continuations (`:eval-operator`, `:eval-operand`) to track progress through the application steps.
- 🚀 **Tail-Call Optimization** - When `:tail?` is true, the VM can reuse the current stack frame or continuation, enabling deep recursion.

---

## Part 5: Conditionals (If)

Conditionals allow the VM to choose between different execution paths based on a test condition.

### If Node

**Type:** `:if`

**Purpose:** To perform branching logic.

**Structure:**
```clojure
{:type :if
 :test <ast-node>        ; The condition to evaluate
 :consequent <ast-node>  ; Evaluated if test is truthy
 :alternate <ast-node>   ; Evaluated if test is falsy
 }
```

**Fields:**
- `:type` - Always `:if`
- `:test` - An AST node representing the condition.
- `:consequent` - The AST node to evaluate if the test yields a truthy value (anything except `false` or `nil`).
- `:alternate` - The AST node to evaluate if the test yields a falsy value (`false` or `nil`).

### Example

#### Simple Check
```clojure
;; Represents: (if true 1 0)
{:type :if
 :test {:type :literal :value true}
 :consequent {:type :literal :value 1}
 :alternate {:type :literal :value 0}}
```

### Evaluation Semantics

The evaluation of an `if` node happens in two phases:

1. **Evaluate Test** - The VM evaluates the `:test` node. It sets up a continuation (`:eval-test`) to remember the `consequent` and `alternate` branches.
2. **Branch** - Once the test value is known:
   - If truthy, the VM begins evaluating the `:consequent` node.
   - If falsy, the VM begins evaluating the `:alternate` node.

**Implementation (from ast_walker.cljc):**
```clojure
;; Start evaluating test
:if (cesk-return state (:test node) env 
                 {:frame node, :next k, :env env, :type :eval-test}
                 (:value state))

;; Handle test result in continuation
:eval-test
(let [frame (:frame k)
      test-value (:value state)
      branch (if test-value (:consequent frame) (:alternate frame))]
  (cesk-return state branch env (:next k) test-value))
```

### Key Characteristics

- 🛣️ **Branching** - Only one of the two branches is ever evaluated.
- 📉 **Lazy Evaluation** - Unlike function arguments, the branches are not evaluated until the test result is determined.
- ☯️ **Truthiness** - Follows Clojure's rules: everything is true except `false` and `nil`.

---

## Part 6: Store Operations (VM Primitives)

Store operations are VM-level primitives that interact directly with the global heap or "store". They provide mechanisms for state management and unique ID generation.

### 1. Unique ID Generation (Gensym)

**Type:** `:vm/gensym`

**Purpose:** To generate a unique identifier (symbol or string).

**Structure:**
```clojure
{:type :vm/gensym
 :prefix <string>}  ; Optional: prefix for the generated ID
```

**Evaluation:** Increments an internal counter and returns a unique symbol starting with the prefix.

### 2. Read from Store (Store-Get)

**Type:** `:vm/store-get`

**Purpose:** To retrieve a value from the global store by its key.

**Structure:**
```clojure
{:type :vm/store-get
 :key <any>}  ; The key to look up (typically a keyword or symbol)
```

**Evaluation:** Returns the value associated with `:key` in the VM's store.

### 3. Write to Store (Store-Put)

**Type:** `:vm/store-put`

**Purpose:** To associate a value with a key in the global store.

**Structure:**
```clojure
{:type :vm/store-put
 :key <any>   ; The key to set
 :val <any>   ; The value to store
 }
```

**Evaluation:** Updates the store such that `:key` maps to `:val`, and returns `:val`.

### 4. Update Store (Store-Update)

**Type:** `:vm/store-update`

**Purpose:** To update a value in the store by applying a function to its current value.

**Structure:**
```clojure
{:type :vm/store-update
 :key <any>       ; The key to update
 :fn <clojure-fn> ; The function to apply
 :args [<any> ...] ; Additional arguments for the function
 }
```

**Evaluation:** Retrieves current value at `:key`, applies `:fn` to it (plus any `:args`), and stores the result back at `:key`.

### Key Characteristics

- 💾 **Stateful** - These operations modify or read from the VM's persistent store.
- 🌍 **Global** - The store is shared across all lexical scopes in the VM.
- ⚡ **Side Effects** - `:vm/store-put` and `:vm/store-update` are primarily used for their side effects.

---

## Part 7: Continuation Control

Continuations represent "the rest of the computation." Yin VM provides first-class access to continuations, allowing for advanced control flow like coroutines, exceptions, and cooperative multitasking.

### 1. Capture Continuation (Current-Continuation)

**Type:** `:vm/current-continuation`

**Purpose:** To reify the current execution state as a value.

**Structure:**
```clojure
{:type :vm/current-continuation}
```

**Evaluation:** Returns a `:reified-continuation` object containing the current stack/continuation and lexical environment.

### 2. Suspend Execution (Park)

**Type:** `:vm/park`

**Purpose:** To stop the current execution and save it for later.

**Structure:**
```clojure
{:type :vm/park}
```

**Evaluation:** Moves the current continuation to the `:parked` map in the store and halts the VM (setting `:control` and `:k` to `nil`). Returns a reference to the parked continuation.

### 3. Resume Execution (Resume)

**Type:** `:vm/resume`

**Purpose:** To restart a previously parked continuation with a specific value.

**Structure:**
```clojure
{:type :vm/resume
 :parked-id <keyword> ; ID of the parked continuation
 :val <ast-node>      ; The node that provides the value to "send" to the resumed process
 }
```

**Evaluation:**
1. Evaluates the `:val` node.
2. Retrieves the parked continuation by its `:parked-id`.
3. Restores the VM state to that continuation, providing the result of `:val` as the next value.

### Key Characteristics

- ⏳ **First-Class Time** - Allows capturing a point in time and returning to it.
- 🚦 **Concurrency** - Foundation for the Yin VM's cooperative multitasking and stream-based IO.
- 🧬 **State Capture** - Captures both control flow (`:k`) and lexical scope (`:env`).

---

## Part 8: Stream Operations

Stream operations model all IO as data streams. These nodes interact with `dao.stream` implementations and can cause the VM to block until data is available.

### 1. Create Stream (Stream-Make)

**Type:** `:stream/make`

**Purpose:** To initialize a new DaoStream.

**Structure:**
```clojure
{:type :stream/make
 :buffer <long>}  ; Optional: capacity of the stream buffer
```

### 2. Emit to Stream (Stream-Put)

**Type:** `:stream/put`

**Purpose:** To send a value into a stream.

**Structure:**
```clojure
{:type :stream/put
 :target <ast-node> ; The stream to write to
 :val <ast-node>    ; The value to write
 }
```

**Evaluation:** Evaluates target and value, then performs a `put!`. May block if the stream is full.

### 3. Create Cursor (Stream-Cursor)

**Type:** `:stream/cursor`

**Purpose:** To create a reading position (cursor) on a stream.

**Structure:**
```clojure
{:type :stream/cursor
 :source <ast-node> ; The stream to read from
 }
```

### 4. Read Next (Stream-Next)

**Type:** `:stream/next`

**Purpose:** To read the next value from a stream cursor.

**Structure:**
```clojure
{:type :stream/next
 :source <ast-node> ; The cursor-ref to read from
 }
```

**Evaluation:** Blocks the current execution until a new value is available on the stream, then returns that value.

### 5. Close Stream (Stream-Close)

**Type:** `:stream/close`

**Purpose:** To signal that no more values will be sent.

**Structure:**
```clojure
{:type :stream/close
 :source <ast-node> ; The stream to close
 }
```

### Key Characteristics

- 🌊 **Async by Design** - Stream operations naturally handle asynchronous data flow.
- 🧱 **Blocking** - `:stream/put` and `:stream/next` can suspend the VM's current continuation.
- 🔗 **Decoupling** - Producers and consumers communicate through streams, not direct calls.

---

## Part 9: Macro Expansion

Macro expansion is a compile-time (or evaluation-time) transformation where a macro function is called with **unevaluated** AST nodes to produce a new AST node.

### Macro-Expand Node

**Type:** `:yin/macro-expand`

**Purpose:** To trigger the expansion of a macro.

**Structure:**
```clojure
{:type :yin/macro-expand
 :operator <ast-node>    ; The macro lambda/closure
 :operands [<ast-node> ...] ; Unevaluated AST nodes as arguments
 }
```

**Fields:**
- `:type` - Always `:yin/macro-expand`
- `:operator` - A lambda or closure that has `:macro? true`.
- `:operands` - A vector of AST nodes that are passed to the macro function without being evaluated first.

### Evaluation Semantics

1. **Invoke Macro** - The VM calls the macro function, passing the raw operand AST nodes as arguments.
2. **Obtain Expansion** - The macro returns a new AST node.
3. **Continue** - The VM then evaluates the resulting "expanded" AST node in place of the original macro-expand node.

### Key Characteristics

- 🏗️ **Structural Transformation** - Macros manipulate code structure, not just values.
- 📂 **Unevaluated Arguments** - Unlike `:application`, the operands are treated as data, not as expressions to be computed.
- ⚡ **Phase Control** - Expansion typically happens during a compilation phase before final execution.

---

## Part 10: Special Application (dao.stream.apply)

The `dao.stream.apply/call` node represents a high-level asynchronous call that communicates over streams. It's used for cross-language FFI or distributed service calls.

### Stream-Apply Call Node

**Type:** `:dao.stream.apply/call`

**Purpose:** To perform an asynchronous, stream-based function call.

**Structure:**
```clojure
{:type :dao.stream.apply/call
 :op <keyword>           ; The operation name
 :operands [<ast-node> ...] ; Arguments to evaluate
 }
```

### Evaluation Semantics

1. **Evaluate Operands** - The VM evaluates all `:operands` to get argument values.
2. **Park Continuation** - The VM creates a callback continuation and parks itself.
3. **Emit Request** - A request containing the `:op` and argument values is sent to the `:yin/call-in` stream.
4. **Await Response** - The VM stays blocked until a response appears on the `:yin/call-out` stream, at which point the parked continuation is resumed with the result.

### Key Characteristics

- 🌐 **Inter-Process/Language** - Designed for calling code outside the current VM instance.
- 📡 **Message-Based** - Operates by sending and receiving discrete messages over streams.
- 🧱 **Explicit Blocking** - The calling process is suspended until the external system responds.

---

## Metadata and Identity

In addition to the fields described above, any AST node may contain:

- `:eid` - A unique entity ID (used when the AST is stored as datoms in DaoDB).
- `:tail?` - A boolean indicating the node is in a tail-call position.
- `:metadata` - A map containing source locations, documentation, or other non-executable data.

---

## Conclusion

The Yin VM Universal AST is a robust, data-oriented representation of code. By using maps and keywords, it remains language-neutral and highly malleable, allowing for complex transformations and efficient execution across diverse environments.

## Summary

Literal nodes are the simplest form of AST node:
- Single `:type` field set to `:literal`
- Single `:value` field containing the constant
- Self-evaluating with no side effects
- Foundation for all other expressions

The simplicity of literals demonstrates the power of the Universal AST - even the most basic values are represented uniformly as data.
