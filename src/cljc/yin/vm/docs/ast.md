# Yin VM - Universal AST Documentation

This document incrementally describes the Universal AST (Abstract Syntax Tree)
used by the Yin Virtual Machine. The AST is a language-agnostic, map-based
structure that represents all executable code as immutable data.

> **Status (2026-09-17):** rewritten against the live v2 evaluator,
> `src/cljc/yin/vm/ast_walker.cljc`. The v1 evaluator this document
> originally described (`yin.vm`/`walker`) was deleted by
> `yin.vm.v1-retirement.implementation-plan.md`; every implementation
> snippet below is quoted from the current v2 code, and every place where
> v2's behavior differs from v1's is called out explicitly rather than
> silently updated. `docs/design/yin.vm.code-as-tuples.md` proposes a
> flat-row tuple grammar as a *second* representation of this same tree;
> that design is not implemented in the walker yet (it has its own
> implementation plan), so this document still describes the walker's
> only live input: the map AST below.

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

## Running examples against the live VM

Every "Evaluation" example below uses the v2 walker's real public surface,
exactly as its own tests do (`test/yin/vm/ast_walker_test.cljc`):

```clojure
(require '[yin.vm :as vm]
         '[yin.vm.test-utils :refer [create-vm]])

(let [result (vm/eval (create-vm) {:type :literal, :value 42})]
  (vm/value result)   ;; => 42
  (vm/halted? result)) ;; => true
```

`create-vm` (from `yin.vm.test-utils`) wraps
`yin.vm.ast-walker/create-vm` with the default primitive table; `vm/eval`,
`vm/value`, `vm/halted?`, and `vm/environment` are the `yin.vm/IVM` and
`IVMState` protocol methods every v2 evaluator implements.

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
(vm/value (vm/eval (create-vm) {:type :literal :value 42}))
;; => 42
```

#### String Literal
```clojure
;; Represents: "hello world"
{:type :literal
 :value "hello world"}
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

**Implementation (from `ast_walker.cljc`):**
```clojure
:literal (cesk-return state nil env k (:value node))
```

### Key Characteristics

- ✅ **Self-evaluating** - The value is the result
- ✅ **No dependencies** - Doesn't reference environment or store
- ✅ **Immediate** - Evaluates in a single step
- ✅ **Type-agnostic** - Can hold any Clojure value
- ✅ **Immutable** - The value never changes

### Usage in Larger Expressions

Literals are the building blocks of more complex expressions. They're commonly used as:
- **Function arguments**: `(+ 10 20)` - both `10` and `20` are literals
- **Return values**: `(lambda () 42)` - returns the literal `42`
- **Comparison operands**: `(= x 5)` - `5` is a literal

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

Variables represent named references to values in the lexical environment, the
global store, primitive operations, or the module registry.

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

When the Yin VM encounters a variable node, `engine/resolve-var` looks for the
name in this exact order — the first hit wins:

1. **Lexical Environment** (`env`) — the current scope (function parameters,
   let bindings).
2. **Store** (`store`) — the global heap, in v2 checked *before* primitives.
3. **Primitives** — built-in functions supplied to `create-vm`.
4. **Module registry** — only reached for a namespaced name (e.g. `stream/put!`);
   resolved by `yin.vm.module/resolve-module` against the registry value the
   composition supplied, never a global.

If none resolve, the lookup throws `ex-info` naming the unresolved symbol.

**Implementation (from `engine.cljc`):**
```clojure
(defn resolve-var
  [env store primitives registry name]
  (if-let [pair (find env name)]
    (val pair)
    (if-let [pair (find store name)]
      (val pair)
      (if-let [pair (find primitives name)]
        (val pair)
        (if-let [resolved (when (namespace name)
                            (module/resolve-module
                              registry
                              (symbol (str (namespace name) "." (clojure.core/name name)))))]
          resolved
          (fail (str "Unable to resolve symbol: " name " in this context")
                {:symbol name}))))))
```

Called from the walker as:
```clojure
:variable
(let [value (engine/resolve-var env store primitives modules (:name node))]
  (cesk-return state nil env k value))
```

### Key Characteristics

- 🔍 **Lookup-based** - Requires searching an environment, store, primitive table, or module registry
- 📦 **Context-dependent** - The same variable can evaluate to different values in different scopes
- 🛠️ **Primitive Access** - Primitives are treated as variables with special pre-bound values
- 🧩 **Module-backed** - A namespaced name that misses env/store/primitives resolves through a composition-supplied registry value, not a global

### Usage Examples

Variables are used whenever you need to reference a value that isn't a literal:
- **Function parameters**: Referencing arguments passed to a lambda
- **Mathematical operations**: Referencing `+`, `-`, etc.
- **Global state**: Accessing shared values in the store
- **Module functions**: Referencing an effect handler through a namespaced name

---

## Part 3: Lambdas and Closures

Lambdas are the primary mechanism for defining functions. When evaluated, a lambda node produces a **closure**—a value that combines the function's code with the environment in which it was created.

### Lambda Node

**Type:** `:lambda`

**Purpose:** To define an anonymous function.

**Structure:**
```clojure
{:type :lambda
 :params [<symbol> ...]  ; Vector of parameter names
 :body <ast-node>        ; The expression to evaluate when called
 }
```

**Fields:**
- `:type` - Always `:lambda`
- `:params` - A vector of symbols representing the arguments the function accepts.
- `:body` - An AST node representing the function's implementation.

> A `:lambda` node may still carry `:macro?`/`:phase-policy` as data — the
> walker's `:lambda` arm reads and ignores both. Macro expansion is not a
> runtime concern of any v2 evaluator (see Part 9); a lambda arriving here
> with `:macro? true` is not a macro the walker will treat specially, it is
> an already-expanded ordinary closure whose source happened to be a macro
> definition.

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

**Implementation (from `ast_walker.cljc`):**
```clojure
:lambda (let [{:keys [params body]} node]
          (cesk-return
            state
            nil
            env
            k
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
- `:tail?` - (Optional) read by the lowering profile that flattens this tree into an instruction vector (`yin.vm.code-as-tuples.md` §5.2.1); the walker's own `:application` arm does not branch on it — a CESK machine's continuation stack already reuses the frame for a tail call.

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
2. **Evaluate Operands** - The VM then evaluates each node in `:operands` in order to get a list of argument values.
3. **Apply Function**:
   - **If Primitive**: The VM calls the host-language function with the evaluated arguments.
   - **If Closure**: The VM extends the closure's captured environment by binding `:params` to the argument values, then evaluates the closure's `:body` in this new environment.

**Implementation (from `ast_walker.cljc`):**

```clojure
;; Start evaluating operator
:application (cesk-return
              state
              (:operator node)
              env
              {:frame node, :next k, :env env, :type :eval-operator}
              (:value state))

;; Once operator and every operand are evaluated:
(defn- apply-function
  [state fn-value evaluated-operands k env]
  (cond (fn? fn-value)
        (handle-primitive-result state
                                 (apply fn-value evaluated-operands)
                                 k
                                 env)
        (= :closure (:type fn-value))
        (let [{:keys [params body], closure-env :env} fn-value
              extended-env (merge closure-env
                                  (engine/bind-params params evaluated-operands))]
          (cesk-return state body extended-env k (:value state)))
        :else (throw (ex-info "Cannot apply non-function" {:fn fn-value}))))
```

**Under-arity calls do not silently drop parameters.** `engine/bind-params`
binds each positional operand to its parameter name and **nil-fills** any
parameter left over when there are fewer operands than params — it does not
use `zipmap`, which would silently omit the unbound parameter names from the
extended environment entirely. This is `yin.vm.code-as-tuples.md` §7.7.2's
named-parameter binding rule, landed in v2:

```clojure
;; engine.cljc
(defn bind-params
  [params args]
  (into {} (map vector params (concat args (repeat nil)))))
```

An extra argument beyond the params list length is simply dropped, since
`map`/`into` stop at the shorter sequence — `params`.

### Key Characteristics

- 🔄 **Iterative Evaluation** - Multiple nodes are evaluated before the final application occurs.
- 🧵 **Continuations** - The VM uses continuations (`:eval-operator`, `:eval-operand`) to track progress through the application steps.
- 🚀 **Tail-Call Reuse** - A CESK machine's continuation is already reused across a tail call; `:tail?` is metadata a lowering profile reads, not a runtime branch.
- 🪄 **Nil-fill, never zipmap** - An under-arity call binds every declared parameter name, missing ones to `nil`, rather than silently omitting them.

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

**Implementation (from `ast_walker.cljc`):**
```clojure
;; Start evaluating test
:if (cesk-return state
                 (:test node)
                 env
                 {:frame node, :next k, :env env, :type :eval-test}
                 (:value state))

;; Handle test result in continuation
:eval-test
(let [frame (:frame k)
      test-value (:value state)
      saved-env (or (:env k) env)
      branch (if test-value (:consequent frame) (:alternate frame))]
  (cesk-return state branch saved-env (:next k) test-value))
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

**Evaluation:** Increments an internal counter and returns a unique string starting with the prefix (default `"id"`).

**Implementation (from `ast_walker.cljc`):**
```clojure
:vm/gensym (let [prefix (or (:prefix node) "id")
                 [id s'] (engine/gensym state prefix)]
             (assoc s' :value id :control nil :halted? (nil? k)))
```

### 2. Read from Store (Store-Get)

**Type:** `:vm/store-get`

**Purpose:** To retrieve a value from the global store by its key.

**Structure:**
```clojure
{:type :vm/store-get
 :key <any>}  ; The key to look up (typically a keyword or symbol)
```

**Evaluation:** Returns the value associated with `:key` in the VM's store.

**Implementation (from `ast_walker.cljc`):**
```clojure
:vm/store-get (cesk-return state nil env k (get store (:key node)))
```

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

**Implementation (from `ast_walker.cljc`):**
```clojure
:vm/store-put (let [key (:key node)
                    value (:val node)
                    new-store (assoc store key value)]
                (assoc state
                       :store new-store
                       :value value
                       :control nil
                       :halted? (and (not (:blocked? state)) (nil? k))))
```

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

**Implementation (from `ast_walker.cljc`):**
```clojure
:vm/store-update (let [key (:key node)
                       f (:fn node)
                       args (:args node)
                       current (get store key)
                       new-value (apply f current args)
                       new-store (assoc store key new-value)]
                   (assoc state
                          :store new-store
                          :value new-value
                          :control nil
                          :halted? (and (not (:blocked? state)) (nil? k))))
```

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

**Evaluation:** Returns a `:reified-continuation` value carrying the current continuation and lexical environment.

**Implementation (from `ast_walker.cljc`):**
```clojure
:vm/current-continuation
(cesk-return state nil env k {:type :reified-continuation, :k k, :env env})
```

### 2. Suspend Execution (Park)

**Type:** `:vm/park`

**Purpose:** To stop the current execution and save it for later.

**Structure:**
```clojure
{:type :vm/park}
```

**Evaluation:** Moves the current continuation into the VM's `:parked` map via `engine/park-continuation` and halts (`:control`/`:k` both `nil`). Returns a reference to the parked continuation.

**Implementation (from `ast_walker.cljc`):**
```clojure
:vm/park (-> (engine/park-continuation state {:k k, :env env})
             (assoc :control nil :k nil))
```

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
2. Retrieves the parked continuation by its `:parked-id` via `engine/resume-continuation`.
3. Restores the VM state to that continuation, providing the result of `:val` as the next value.

**Implementation (from `ast_walker.cljc`):**
```clojure
:vm/resume (cesk-return state
                        (:val node)
                        env
                        {:type :eval-resume-val, :parked-id (:parked-id node), :next k, :env env}
                        (:value state))

;; once :val is evaluated:
:eval-resume-val
(let [resume-val (:value state)
      parked-id (:parked-id k)]
  (engine/resume-continuation
    state
    parked-id
    resume-val
    (fn [new-state parked rv]
      (cesk-return new-state nil (:env parked) (:k parked) rv))))
```

### Key Characteristics

- ⏳ **First-Class Time** - Allows capturing a point in time and returning to it.
- 🚦 **Concurrency** - Foundation for the Yin VM's cooperative multitasking and stream-based IO.
- 🧬 **State Capture** - Captures both control flow (`:k`) and lexical scope (`:env`).

---

## Part 8: Stream Operations

Stream operations model all IO as data over `dao.stream`. Every stream op
here goes through `engine/handle-effect`, which either completes immediately
or parks the calling continuation in the VM's wait set until the transport's
outcome is ready — there is no ambient waiter registration, and no operation
polls; the scheduler drains the wait set on its own pass (see
`docs/design/dao.stream.md`).

### 1. Create Stream (Stream-Make)

**Type:** `:stream/make`

**Purpose:** To initialize a new DaoStream.

**Structure:**
```clojure
{:type :stream/make
 :buffer <long>}  ; Optional: capacity of the stream buffer
```

**Implementation (from `ast_walker.cljc`):**
```clojure
:stream/make (let [capacity (or (:buffer node) vm/default-stream-capacity)
                   effect {:effect :stream/make, :capacity capacity}
                   {:keys [state value]} (engine/handle-effect state effect
                                          {:restore-fn ast-walker-restore})]
               (cesk-return state nil env k value))
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

**Evaluation:** Evaluates `:target`, then `:val`, then appends. A transport
that answers `full` parks the continuation in the wait set rather than
blocking a host thread; the append is retried when the transport signals
room.

**Implementation (from `ast_walker.cljc`):**
```clojure
:stream/put (cesk-return state (:target node) env
                         {:frame node, :next k, :env env, :type :eval-stream-put-target}
                         (:value state))

;; target evaluated, now evaluate val:
:eval-stream-put-target
(let [frame (:frame k)
      stream-ref (:value state)
      val-node (:val frame)]
  (cesk-return state val-node env
              (assoc k :type :eval-stream-put-val :stream-ref stream-ref)
              stream-ref))

;; val evaluated, now append:
:eval-stream-put-val
(let [val (:value state)
      stream-ref (:stream-ref k)
      effect {:effect :stream/put, :stream stream-ref, :val val}
      {:keys [state value blocked?]}
      (engine/handle-effect state effect
        {:restore-fn ast-walker-restore,
         :park-entry-fns {:stream/put (fn [_s _e r]
                                        {:k (:next k), :env env, :reason :put,
                                         :stream-id (:stream-id r), :datom val})}})]
  (if blocked?
    (assoc state :control nil :k nil :halted? false)
    (cesk-return state nil env (:next k) value)))
```

### 3. Create Cursor (Stream-Cursor)

**Type:** `:stream/cursor`

**Purpose:** To mint a reading position (opaque cursor) on a stream.

**Structure:**
```clojure
{:type :stream/cursor
 :source <ast-node> ; The stream to read from
 }
```

**Implementation (from `ast_walker.cljc`):**
```clojure
:stream/cursor (cesk-return state (:source node) env
                            {:frame node, :next k, :env env, :type :eval-stream-cursor-source}
                            (:value state))

:eval-stream-cursor-source
(let [stream-ref (:value state)
      effect {:effect :stream/cursor, :stream stream-ref}
      {:keys [state value]} (engine/handle-effect state effect
                             {:restore-fn ast-walker-restore})]
  (cesk-return state nil env (:next k) value))
```

### 4. Read Next (Stream-Next)

**Type:** `:stream/next`

**Purpose:** To read the next value at a stream cursor.

**Structure:**
```clojure
{:type :stream/next
 :source <ast-node> ; The cursor-ref to read from
 }
```

**Evaluation:** Parks the calling continuation in the wait set until a value
is available on the stream at that cursor, then returns that value. The
cursor is opaque — it is never a fabricated position — and advances only as
the transport's own outcome carries it forward.

**Implementation (from `ast_walker.cljc`):**
```clojure
:stream/next (cesk-return state (:source node) env
                          {:frame node, :next k, :env env, :type :eval-stream-next-cursor}
                          (:value state))

:eval-stream-next-cursor
(let [cursor-ref (:value state)
      effect {:effect :stream/next, :cursor cursor-ref}
      {:keys [state value blocked?]}
      (engine/handle-effect state effect
        {:restore-fn ast-walker-restore,
         :park-entry-fns {:stream/next (fn [_s _e r]
                                         {:k (:next k), :env env, :reason :next,
                                          :cursor-ref (:cursor-ref r), :stream-id (:stream-id r)})}})]
  (if blocked?
    (assoc state :control nil :k nil :halted? false)
    (cesk-return state nil env (:next k) value)))
```

### 5. Close Stream (Stream-Close)

**Type:** `:stream/close`

**Purpose:** To signal that no more values will be sent.

**Structure:**
```clojure
{:type :stream/close
 :source <ast-node> ; The stream to close
 }
```

> **Status (2026-09-17): not implemented in `yin.vm.ast-walker` yet.**
> The walker's `case` has no `:stream/close` arm — evaluating this node
> throws `"Unknown AST node type"`. `docs/design/yin.vm.code-as-tuples.md`
> §3.2 specifies the frame this arm needs (shaped like
> `:eval-stream-cursor-source` above, raising `{:effect :stream/close
> :stream ref}` through `engine/handle-effect`), and its own
> implementation plan schedules it as unit U1 — an afternoon of work, no
> decision pending. Do not write code against this node type until that
> unit lands; it will throw today.

### Key Characteristics

- 🌊 **Async by Design** - Stream operations naturally handle asynchronous data flow.
- 🧱 **Parking, not blocking** - `:stream/put` and `:stream/next` park the calling continuation in the VM's wait set; no host thread blocks and nothing registers a waiter with the transport.
- 🔗 **Decoupling** - Producers and consumers communicate through streams, not direct calls.
- ⚠️ **`:stream/close` is speculative** - documented in the vocabulary, absent from the evaluator, until U1 of the tuples plan lands.

---

## Part 9: Macro Expansion

> **Status (2026-09-17): this is not a runtime node type in any v2
> evaluator, and never will be.** `yin.vm.ast-walker`'s own docstring
> states the rule directly: "evaluators know nothing about macros
> (decision 1 of `yin.vm.macro.md`)... Expansion is a process on the
> syntax side of a medium boundary, so programs arrive here already
> expanded." The walker's `case` has no `:yin/macro-expand` arm; nothing
> in `ast_walker.cljc` reads `:macro?` except to ignore it on a `:lambda`
> node (Part 3). This section describes *where* expansion happens, not a
> node the VM steps through.

Macro expansion is a compile-time (or load-time) stream transformation, completely external to the evaluator, that consumes unexpanded AST and produces the canonical tuples the walker receives. `docs/design/yin.vm.macro.md` specifies the expander itself, and `src/cljc/yin/vm/macro.cljc` contains the implementation. No macro node or macro-expand tag ever reaches the tuple grammar.
deliverable, not a shipped namespace (`yin.vm.code-as-tuples.md` §10 item 4).
When it is built, it will run upstream of the walker, on a syntax medium the
walker never observes, and hand the walker only fully-expanded `:lambda`/
`:application`/... nodes like every other tree in this document.

### Key Characteristics

- 🏗️ **Structural Transformation** - Macros manipulate code structure, not just values, before any evaluator sees the result.
- 🚧 **Not built** - the expander itself is unimplemented; this section is a placeholder for where its output lands, not a description of running code.
- 📖 **Specified elsewhere** - `yin.vm.macro.md` (the expander contract) and `yin.vm.code-as-tuples.md` §8.4-8.5 (the batch/provenance shape a future expander's events carry).

---

## Part 10: Special Application (dao.stream.apply)

The `:dao.stream.apply/call` node represents a cross-process FFI call: it
sends a request over an outbound stream to a host-side bridge and parks the
calling continuation until a correlated response arrives, rather than
calling a Clojure function directly in-process.

### Stream-Apply Call Node

**Type:** `:dao.stream.apply/call`

**Purpose:** To perform an asynchronous, stream-based function call across
the FFI bridge.

**Structure:**
```clojure
{:type :dao.stream.apply/call
 :op <keyword>           ; The operation name
 :operands [<ast-node> ...] ; Arguments to evaluate
 }
```

### Evaluation Semantics

1. **Evaluate Operands** - The VM evaluates every node in `:operands`, left to right, exactly as `:application` does.
2. **Park and Request** - `park-and-call` parks the continuation (`engine/park-continuation`) *before* touching the transport — deliberately, so an error raised later cannot strand a continuation with a consumed id — then encodes and appends a request naming `:op` and the evaluated arguments to the bridge's inbound (`call-in`) stream.
3. **Retry-safe on `full`** - A `full` outcome on the append leaves the identical request in the wait set to retry; the call only starts waiting on a response once the append itself succeeds. `closed`, `invalid-value`, and `transport-error` fail the call at this point instead.
4. **Await Response** - The continuation stays parked until a correlated reply lands on the bridge's outbound (`call-out`) stream (`:dao.stream.apply/eval-call`), at which point `ffi/call-result` extracts the return value and the continuation resumes with it.

**Implementation (from `ast_walker.cljc`):**
```clojure
:dao.stream.apply/call
(let [operands (or (:operands node) [])
      op (:op node)]
  (if (empty? operands)
    (park-and-call state op [] k env)
    (cesk-return state (first operands) env
                {:frame {:op op, :operands operands, :evaluated []},
                 :next k, :env env, :type :dao.stream.apply/eval-operand}
                (:value state))))

(defn- park-and-call
  [state op args k env]
  (let [{:keys [call-in]} (ffi/require-call-pair! (:store state) op)
        response-cont {:type :dao.stream.apply/eval-call, :next k, :env env}
        parked (engine/park-continuation state {:k response-cont, :env env})
        parked-id (get-in parked [:value :id])
        request (apply2/request parked-id op (vec args))
        result (apply2/put-request! call-in request)]
    (case (:dao.stream/outcome result)
      :dao.stream/ok
      (-> parked
          (update :wait-set (fnil conj [])
                  (ffi/call-response-wait-entry parked-id k env))
          (assoc :control nil :k nil :value :yin/blocked :blocked? true :halted? false))
      :dao.stream/full
      (-> parked
          (update :wait-set (fnil conj [])
                  {:k {:type :dao.stream.apply/request-sent, :parked-id parked-id,
                       :next k, :env env, :op op}
                   :env env, :reason :put, :stream-id vm/call-in-stream-key, :datom request})
          (assoc :control nil :k nil :value :yin/blocked :blocked? true :halted? false))
      (throw (ex-info "FFI request could not be appended"
                      {:op op, :outcome (:dao.stream/outcome result)})))))
```

### Key Characteristics

- 🌐 **Inter-Process/Language** - Designed for calling code outside the current VM instance, through an explicit host-supplied FFI bridge (`create-vm`'s `:bridge` option).
- 📡 **Message-Based, Retry-Safe** - Operates by sending and receiving discrete requests/responses over streams; a `full` append is retried by the wait set, never silently dropped.
- 🧱 **Parking, not blocking** - The calling continuation parks like any other stream operation; nothing invokes a callback from inside the transport, and no host thread blocks.

---

## Metadata and Identity

In addition to the fields described above, any AST node may contain:

- `:eid` - An entity id, present when the AST arrives as a batch of `:yin/*`
  datoms and is converted by `vm/datoms->ast` — the walker's only live
  loading path today (`vm-load-program`). `docs/design/yin.vm.code-as-tuples.md`
  proposes a second, content-addressed row representation of this same tree
  that does not use `:eid`; that representation is not consumed by any
  evaluator yet (its own implementation plan schedules the row loader as a
  separate unit).
- `:tail?` - A boolean a lowering profile reads when flattening a tree to an
  instruction vector (Part 4); the walker's own evaluation does not branch on it.
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
