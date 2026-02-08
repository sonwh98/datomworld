# Yin VM - State Documentation

This document describes the **state** parameter passed to the `eval` function in the Yin Virtual Machine. The state represents the complete computational context of the CESK machine at any point in execution.

## Overview

The state is an immutable map that contains all information needed to continue execution. It implements the **CESK machine** model:

- **C**ontrol - What to evaluate next
- **E**nvironment - Variable bindings
- **S**tore - Heap memory (future use)
- **K**ontinuation - Where to return

## State Structure

```clojure
{:control      <ast-node-or-nil>     ; Current expression being evaluated
 :environment  <map>                 ; Variable bindings
 :store        <map>                 ; Heap memory (reserved for future)
 :continuation <continuation-or-nil> ; Where to return after evaluation
 :value        <any>}                ; Current computed value
```

## Field Descriptions

### `:control` - Current Expression

**Type:** AST node (map) or `nil`

**Purpose:** The expression currently being evaluated. When `nil`, no evaluation is in progress.

**Values:**
- AST node - An expression to evaluate (e.g., `{:type :literal :value 42}`)
- `nil` - Evaluation is complete or pending continuation

**Example:**
```clojure
;; Evaluating a literal
{:control {:type :literal :value 42}
 :environment {}
 :store {}
 :continuation nil
 :value nil}

;; Evaluation complete
{:control nil
 :environment {}
 :store {}
 :continuation nil
 :value 42}
```

### `:environment` - Variable Bindings

**Type:** Map (symbol → value)

**Purpose:** Stores lexical variable bindings. Maps variable names (symbols) to their values.

**Characteristics:**
- **Immutable** - Never modified, only extended
- **Lexical** - Captures scope at definition time
- **Persistent** - Clojure persistent map for structural sharing

**Example:**
```clojure
;; Empty environment
{:environment {}}

;; Environment with bindings
{:environment {'x 10
               'y 20
               '+ #<primitive-add-function>}}

;; Nested scope (lambda captures environment)
{:environment {'x 5           ; outer scope
               'y 10          ; outer scope
               'z 15}}        ; inner scope
```

**Usage:**
```clojure
;; Variable lookup
(get environment 'x)  ;; => 10

;; Extending environment (for lambda application)
(merge environment {'param1 value1 'param2 value2})
```

### `:store` - Heap Memory

**Type:** Map

**Purpose:** Reserved for heap-allocated memory (mutable references, boxes, etc.)

**Current Status:** Not yet implemented, reserved for future use

**Planned Use:**
- Mutable references
- Shared memory locations
- Garbage collection roots
- Store locations (addresses → values)

**Example (future):**
```clojure
;; Store with allocated memory
{:store {0 42           ; address 0 contains 42
         1 "hello"      ; address 1 contains "hello"
         2 [1 2 3]}}    ; address 2 contains vector
```

### `:continuation` - Return Context

**Type:** Continuation map or `nil`

**Purpose:** Where to return after completing the current evaluation. Enables pausable, resumable computation.

**Structure:**
```clojure
{:frame  <ast-node>          ; The expression that's waiting for a value
 :parent <continuation>      ; The outer continuation (or nil)
 :type   <keyword>}          ; Type of continuation
```

**Continuation Types:**
- `:eval-operator` - Evaluating function/operator
- `:eval-operand` - Evaluating function argument
- `:eval-test` - Evaluating conditional test

**Example:**
```clojure
;; No continuation (top-level)
{:continuation nil}

;; Waiting to evaluate operands after operator
{:continuation {:frame {:type :application
                        :operator {...}
                        :operands [...]}
                :parent nil
                :type :eval-operator}}

;; Nested continuation
{:continuation {:frame {...}
                :parent {:frame {...}
                         :parent nil
                         :type :eval-operator}
                :type :eval-operand}}
```

### `:value` - Current Result

**Type:** Any Clojure value

**Purpose:** Holds the most recently computed value. Used when returning from sub-expressions.

**Values:**
- Computed value from evaluation
- `nil` when no value yet computed
- Any Clojure data type

**Example:**
```clojure
;; No value yet
{:value nil}

;; Literal evaluated to 42
{:value 42}

;; Function evaluated
{:value #<closure>}

;; Expression result
{:value [1 2 3]}
```

## State Lifecycle

### 1. Initial State

When starting evaluation:
```clojure
{:control      <initial-ast>  ; The expression to evaluate
 :environment  <env-map>      ; Initial bindings (often includes primitives)
 :store        {}             ; Empty store
 :continuation nil            ; No outer continuation
 :value        nil}           ; No value yet
```

### 2. During Evaluation

State transitions through many intermediate forms:
```clojure
;; Step 1: Evaluating operator
{:control      {:type :variable :name '+}
 :environment  {'+ <add-fn>}
 :store        {}
 :continuation {:frame {:type :application ...} :type :eval-operator}
 :value        nil}

;; Step 2: Operator evaluated, now evaluate operands
{:control      {:type :literal :value 10}
 :environment  {'+ <add-fn>}
 :store        {}
 :continuation {:frame {...} :type :eval-operand}
 :value        <add-fn>}
```

### 3. Final State

When evaluation is complete:
```clojure
{:control      nil            ; No more to evaluate
 :environment  <final-env>   ; Final environment
 :store        {}             ; Store state
 :continuation nil            ; No pending returns
 :value        <result>}      ; The final result
```

## State Immutability

**Key Property:** Every state is immutable. Each step creates a new state.

```clojure
;; State before
(def state-0 {:control ast, :value nil, ...})

;; Evaluate one step
(def state-1 (walker/eval state-0 nil))

;; state-0 is unchanged!
(= state-0 {:control ast, :value nil, ...})  ;; => true

;; state-1 is new
(not= state-0 state-1)  ;; => true
```

**Benefits:**
- ✅ Time-travel debugging
- ✅ Snapshots at any point
- ✅ Undo/redo
- ✅ Parallelization
- ✅ Serialization

## Helper Functions

### Creating Initial State

```clojure
;; Usage
(walker/make-state {})                    ; Empty environment
(walker/make-state {'x 10 'y 20})        ; With variables
(walker/make-state {'+ (get vm/primitives '+)})  ; With primitives
```

### Starting Evaluation

```clojure
;; Set control and run
(walker/run (assoc (walker/make-state env) :control ast))

;; Or use walker/run directly
(walker/run (walker/make-state env) ast)
```

## State Transitions

### Example: Evaluating `(+ 10 20)`

```clojure
;; Step 0: Initial
{:control {:type :application
           :operator {:type :literal :value <add-fn>}
           :operands [{:type :literal :value 10}
                      {:type :literal :value 20}]}
 :environment {}
 :value nil
 :continuation nil}

;; Step 1: Evaluate operator
{:control {:type :literal :value <add-fn>}
 :environment {}
 :value nil
 :continuation {:frame <application-node> :type :eval-operator}}

;; Step 2: Operator done, return to application
{:control <application-node-with-fn>
 :environment {}
 :value <add-fn>
 :continuation nil}

;; Step 3: Evaluate first operand
{:control {:type :literal :value 10}
 :environment {}
 :value nil
 :continuation {:frame <application-node> :type :eval-operand}}

;; ... (more steps)

;; Final: Result computed
{:control nil
 :environment {}
 :value 30
 :continuation nil}
```

## State Inspection

### Checking State

```clojure
;; Is evaluation done?
(defn done? [state]
  (and (nil? (:control state))
       (nil? (:continuation state))))

;; What's being evaluated?
(:control state)

;; What's the current value?
(:value state)

;; Is there a pending continuation?
(:continuation state)

;; What variables are bound?
(keys (:environment state))
```

### Debugging State

```clojure
;; Print state nicely
(defn print-state [state]
  (println "Control:" (:type (:control state)))
  (println "Value:" (:value state))
  (println "Continuation:" (:type (:continuation state)))
  (println "Environment:" (keys (:environment state))))

;; Step-by-step execution
(loop [state initial-state
       step 0]
  (when (or (:control state) (:continuation state))
    (println "Step" step)
    (print-state state)
    (recur (walker/eval state nil) (inc step))))
```

## State Serialization

States are pure data and can be serialized:

```clojure
;; To EDN
(pr-str state)

;; From EDN
(read-string serialized-state)

;; To JSON (with transit)
(transit/write writer state)

;; From JSON (with transit)
(transit/read reader)
```

**Use Cases:**
- Save/restore execution state
- Send computation to another machine
- Time-travel debugging
- Distributed execution

## CESK Machine Correspondence

| CESK Component | State Field | Purpose |
|---------------|-------------|---------|
| **C**ontrol | `:control` | Current expression |
| **E**nvironment | `:environment` | Variable bindings |
| **S**tore | `:store` | Heap memory |
| **K**ontinuation | `:continuation` | Return context |
| (Extra) | `:value` | Current result |

The `:value` field is an implementation detail that holds temporary results between steps.

## Best Practices

### Creating States

✅ **Do:**
```clojure
;; Use helper function
(make-state {'x 10})

;; Set control explicitly
(assoc (walker/make-state env) :control ast)
```

❌ **Don't:**
```clojure
;; Manually construct - easy to forget fields
{:control ast :environment env}  ; Missing :store, :continuation, :value
```

### Modifying States

✅ **Do:**
```clojure
;; Create new state
(assoc state :control new-ast)

;; Extend environment
(update state :environment merge new-bindings)
```

❌ **Don't:**
```clojure
;; Never mutate!
(assoc! state :control new-ast)  ; NO!
```

### Checking Completion

✅ **Do:**
```clojure
(and (nil? (:control state))
     (nil? (:continuation state)))
```

❌ **Don't:**
```clojure
(nil? (:control state))  ; Not enough - continuation might be active
```

## Examples

### Simple Evaluation

```clojure
(require '[yin.vm :as vm]
         '[yin.vm.ast-walker :as walker])

;; Evaluate a literal
(def state (walker/make-state {}))
(def ast {:type :literal :value 42})
(def result (walker/run state ast))

(:value result)  ;; => 42
(:control result)  ;; => nil (done)
```

### With Environment

```clojure
;; Evaluate variable lookup
(def state (walker/make-state {'x 100}))
(def ast {:type :variable :name 'x})
(def result (walker/run state ast))

(:value result)  ;; => 100
```

### Capturing Intermediate States

```clojure
(defn capture-states [initial-state ast]
  (loop [state (assoc initial-state :control ast)
         states []]
    (if (or (:control state) (:continuation state))
      (recur (walker/eval state nil) (conj states state))
      (conj states state))))

;; Get all states during evaluation
(def states (capture-states (walker/make-state {}) ast))

;; Inspect any state
(nth states 5)
```

## Summary

The state parameter is:
- ✅ **Immutable** - Never changes, always creates new states
- ✅ **Complete** - Contains everything needed for evaluation
- ✅ **Inspectable** - Can examine at any point
- ✅ **Serializable** - Pure data, can be saved/restored
- ✅ **Portable** - Can move between machines
- ✅ **Debuggable** - Step-by-step inspection possible

The state is the heart of the Yin VM - it represents the entire computational context and enables all the powerful features like continuations, time-travel debugging, and distributed execution.

---

See also:
- [ast.md](ast.md) - AST node documentation
- [src/cljc/yin/vm.cljc](vm.cljc) - Implementation
- [test/yin/vm_basic_test.clj](../../../test/yin/vm_basic_test.clj) - Test examples
