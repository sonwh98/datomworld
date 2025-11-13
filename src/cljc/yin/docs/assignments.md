# Handling Assignments in Yin VM Universal AST

## The Challenge

The Yin VM is based on the CESK machine model with **immutable state**. Assignments like `x = x + 1` require **mutable state**, creating a fundamental tension between:

- **Imperative languages** (Python, JavaScript, Java) - Use mutable variables
- **Yin VM architecture** - Pure functional, immutable continuations

This document explores several approaches to handle assignments while preserving Yin's immutable semantics.

---

## Approach 1: Let-Based Rebinding (Functional Transform)

### Concept

Transform assignments into nested `let` expressions that create new scopes with updated bindings.

### Python Code
```python
x = 5
x = x + 1
print(x)  # 6
```

### Universal AST Transformation

Desugar to nested let expressions:
```clojure
;; Conceptual transformation
(let [x 5]
  (let [x (+ x 1)]
    (print x)))
```

### AST Structure
```clojure
{:type :let
 :bindings [{:name 'x :value {:type :literal :value 5}}]
 :body {:type :let
        :bindings [{:name 'x
                    :value {:type :application
                            :operator {:type :variable :name '+}
                            :operands [{:type :variable :name 'x}
                                      {:type :literal :value 1}]}}]
        :body {:type :application
               :operator {:type :variable :name 'print}
               :operands [{:type :variable :name 'x}]}}}
```

### Pros
- ✅ Purely functional
- ✅ No VM changes needed (can desugar to existing lambda applications)
- ✅ Preserves immutability
- ✅ Clear semantics

### Cons
- ❌ Deep nesting for many assignments
- ❌ Doesn't match imperative semantics exactly
- ❌ Can't express loops with mutable counters naturally

### Implementation
```clojure
(defn compile-assignment
  "Transform assignment to let expression."
  [var-name value-expr body]
  {:type :let
   :bindings [{:name var-name :value value-expr}]
   :body body})
```

---

## Approach 2: Explicit Store Operations (Stateful Store)

### Concept

Use the `:store` field in the CESK state as a mutable heap. Add `store-set` and `store-get` operations.

### New AST Nodes

#### Store Set
```clojure
{:type :store-set
 :address <symbol>     ; Variable name as address
 :value <ast-node>     ; Value to store
 :body <ast-node>}     ; Continue with body
```

#### Store Get
```clojure
{:type :store-get
 :address <symbol>}    ; Variable name as address
```

### Python Code
```python
x = 5
x = x + 1
```

### Universal AST
```clojure
{:type :store-set
 :address 'x
 :value {:type :literal :value 5}
 :body {:type :store-set
        :address 'x
        :value {:type :application
                :operator {:type :variable :name '+}
                :operands [{:type :store-get :address 'x}
                          {:type :literal :value 1}]}
        :body {:type :store-get :address 'x}}}
```

### VM Implementation
```clojure
(defn eval [state ast]
  (case (:type ast)
    :store-set
    (let [{:keys [address value body]} ast
          ;; Evaluate the value
          value-state (run state value)
          computed-value (:value value-state)
          ;; Update store
          new-store (assoc (:store state) address computed-value)]
      ;; Continue with body in updated state
      (assoc state
             :store new-store
             :control body))

    :store-get
    (let [address (:address ast)
          value (get (:store state) address)]
      (assoc state
             :control nil
             :value value))))
```

### Pros
- ✅ Matches imperative semantics closely
- ✅ Natural for loops and counters
- ✅ Efficient (no deep nesting)
- ✅ Store already exists in CESK state

### Cons
- ❌ Breaks pure functional model
- ❌ Harder to reason about
- ❌ Complicates continuation migration (need to serialize store)
- ❌ Race conditions if store is shared

---

## Approach 3: State Monad (Functional State Threading)

### Concept

Thread state explicitly through computations using monadic composition. Each statement returns `(state, value)` pair.

### New AST Node
```clojure
{:type :sequence
 :statements [<ast-node> ...]  ; Sequence of statements
 :result <ast-node>}            ; Final result expression
```

### Python Code
```python
x = 5
y = x + 1
z = y * 2
```

### Universal AST
```clojure
{:type :sequence
 :statements [{:type :bind :name 'x :value {:type :literal :value 5}}
              {:type :bind :name 'y :value {:type :application
                                             :operator {:type :variable :name '+}
                                             :operands [{:type :variable :name 'x}
                                                       {:type :literal :value 1}]}}
              {:type :bind :name 'z :value {:type :application
                                             :operator {:type :variable :name '*}
                                             :operands [{:type :variable :name 'y}
                                                       {:type :literal :value 2}]}}]
 :result {:type :variable :name 'z}}
```

### VM Implementation
```clojure
(defn eval [state ast]
  (case (:type ast)
    :sequence
    (let [{:keys [statements result]} ast]
      (loop [s state
             stmts statements]
        (if (empty? stmts)
          ;; Evaluate result in final state
          (eval s result)
          ;; Process next statement
          (let [stmt (first stmts)
                new-state (eval s stmt)]
            (recur new-state (rest stmts))))))

    :bind
    (let [{:keys [name value]} ast
          value-state (eval state value)
          computed-value (:value value-state)]
      ;; Extend environment (immutably)
      (assoc state
             :environment (assoc (:environment state) name computed-value)
             :control nil))))
```

### Pros
- ✅ Preserves immutability (environment extended, not mutated)
- ✅ Explicit sequencing
- ✅ Clean composition
- ✅ Easy to add to existing VM

### Cons
- ❌ Shadowing semantics (each bind creates new scope)
- ❌ Doesn't truly mutate (more like sequential lets)
- ❌ Verbose AST

---

## Approach 4: Reference Cells (Explicit Mutability)

### Concept

Introduce explicit reference cells (like ML's `ref` or Clojure's `atom`). Variables are immutable, but can hold references to mutable cells.

### New AST Nodes

#### Create Reference
```clojure
{:type :ref-new
 :value <ast-node>}  ; Initial value
```

#### Read Reference
```clojure
{:type :ref-get
 :ref <ast-node>}    ; Reference to read
```

#### Write Reference
```clojure
{:type :ref-set
 :ref <ast-node>     ; Reference to write
 :value <ast-node>}  ; New value
```

### Python Code
```python
x = 5
x = x + 1
```

### Universal AST (with explicit refs)
```clojure
;; Create a reference cell for x
{:type :let
 :bindings [{:name 'x :value {:type :ref-new
                              :value {:type :literal :value 5}}}]
 :body {:type :sequence
        :statements [{:type :ref-set
                     :ref {:type :variable :name 'x}
                     :value {:type :application
                             :operator {:type :variable :name '+}
                             :operands [{:type :ref-get
                                        :ref {:type :variable :name 'x}}
                                       {:type :literal :value 1}]}}]
        :result {:type :ref-get
                :ref {:type :variable :name 'x}}}}
```

### Pros
- ✅ Explicit mutability boundaries
- ✅ Preserves immutable environment
- ✅ Clear semantics (variables are immutable, cells are mutable)
- ✅ Enables true mutation

### Cons
- ❌ Verbose (explicit ref operations)
- ❌ Doesn't match source language syntax
- ❌ Requires ref implementation in VM
- ❌ More complex continuations

---

## Approach 5: SSA Form (Static Single Assignment)

### Concept

Transform code to SSA form where each variable is assigned exactly once. Reassignments create new variable names.

### Python Code
```python
x = 5
x = x + 1
x = x * 2
```

### SSA Transformation
```python
x_0 = 5
x_1 = x_0 + 1
x_2 = x_1 * 2
```

### Universal AST
```clojure
{:type :let
 :bindings [{:name 'x_0 :value {:type :literal :value 5}}]
 :body {:type :let
        :bindings [{:name 'x_1
                    :value {:type :application
                            :operator {:type :variable :name '+}
                            :operands [{:type :variable :name 'x_0}
                                      {:type :literal :value 1}]}}]
        :body {:type :let
               :bindings [{:name 'x_2
                          :value {:type :application
                                  :operator {:type :variable :name '*}
                                  :operands [{:type :variable :name 'x_1}
                                            {:type :literal :value 2}]}}]
               :body {:type :variable :name 'x_2}}}}
```

### Pros
- ✅ Purely functional
- ✅ Standard compiler technique
- ✅ Easy to analyze and optimize
- ✅ No VM changes needed

### Cons
- ❌ Name explosion
- ❌ Complicated with control flow (need φ-functions)
- ❌ Breaks semantic equivalence for debugging

---

## Comparison Table

| Approach | Purity | Complexity | Performance | Semantics Match | Migration |
|----------|--------|------------|-------------|-----------------|-----------|
| **Let-Based** | ✅ Pure | ⚠️ Medium | ✅ Good | ⚠️ Different | ✅ Easy |
| **Store Ops** | ❌ Impure | ⚠️ Medium | ✅ Good | ✅ Exact | ⚠️ Harder |
| **State Monad** | ✅ Pure | ⚠️ Medium | ✅ Good | ⚠️ Similar | ✅ Easy |
| **Ref Cells** | ⚠️ Mixed | ❌ High | ⚠️ Overhead | ⚠️ Explicit | ⚠️ Harder |
| **SSA** | ✅ Pure | ❌ High | ✅ Best | ❌ Different | ✅ Easy |

---

## Recommended Approach: Hybrid Strategy

### For Python Compiler

Use **different strategies for different contexts**:

#### 1. Simple Assignments → Let-Based (Approach 1)
```python
x = 5
y = x + 1
return y
```
Compile to nested lets (or equivalent lambda applications).

#### 2. Loop Counters → Store Operations (Approach 2)
```python
for i in range(10):
    # use i
```
Use store operations for loop-local mutable state.

#### 3. Function-Local State → State Monad (Approach 3)
```python
def counter():
    x = 0
    x = x + 1
    x = x + 1
    return x
```
Use sequential bindings with environment extension.

---

## Practical Implementation: Approach 2 (Store Operations)

### Why This Is Best for Yin VM

1. **Store field is unused** - Currently reserved in CESK state
2. **Natural fit** - Matches imperative semantics
3. **Efficient** - No deep nesting
4. **Flexible** - Can handle loops, closures, etc.

### Implementation Plan

#### 1. Add New AST Nodes

```clojure
;; In yin/vm.cljc, add these cases to eval:

:store-set
(let [{:keys [name value body]} node
      value-state (eval state value)
      computed-value (:value value-state)
      new-store (assoc store name computed-value)]
  (assoc state
         :store new-store
         :control body
         :value computed-value))

:store-get
(let [name (:name node)
      value (get store name)]
  (assoc state
         :control nil
         :value value))
```

#### 2. Update Python Compiler

```clojure
;; In yang/python.cljc

(defn compile-assignment
  "Compile Python assignment to store operations."
  [var-name value-expr next-statement]
  {:type :store-set
   :name var-name
   :value (compile-py-expr value-expr)
   :body (compile-py-statement next-statement)})

(defn compile-variable-reference
  "Check if variable is in store or environment."
  [var-name]
  ;; Try environment first (lexical), then store (assigned)
  {:type :variable-or-store
   :name var-name})
```

#### 3. Handle Sequential Statements

```clojure
(defn compile-statements
  "Compile multiple statements with assignments."
  [statements]
  (if (= 1 (count statements))
    (compile-py-statement (first statements))
    (let [[stmt & rest-stmts] statements]
      (if (assignment? stmt)
        {:type :store-set
         :name (:var stmt)
         :value (compile-py-expr (:value stmt))
         :body (compile-statements rest-stmts)}
        ;; Non-assignment statement
        {:type :sequence
         :first (compile-py-statement stmt)
         :rest (compile-statements rest-stmts)}))))
```

---

## Example: Complete Assignment Flow

### Python Source
```python
def counter():
    x = 0
    x = x + 1
    x = x + 1
    return x

result = counter()
```

### Compiled Universal AST
```clojure
{:type :store-set
 :name 'counter
 :value {:type :lambda
         :params []
         :body {:type :store-set
                :name 'x
                :value {:type :literal :value 0}
                :body {:type :store-set
                       :name 'x
                       :value {:type :application
                               :operator {:type :variable :name '+}
                               :operands [{:type :store-get :name 'x}
                                         {:type :literal :value 1}]}
                       :body {:type :store-set
                              :name 'x
                              :value {:type :application
                                      :operator {:type :variable :name '+}
                                      :operands [{:type :store-get :name 'x}
                                                {:type :literal :value 1}]}
                              :body {:type :store-get :name 'x}}}}}
 :body {:type :store-set
        :name 'result
        :value {:type :application
                :operator {:type :store-get :name 'counter}
                :operands []}
        :body {:type :store-get :name 'result}}}
```

---

## Conclusion

**Recommended:** Implement **Approach 2 (Store Operations)** for pragmatic assignment support.

**Trade-off:** Sacrifices pure functional semantics for practical imperative language support.

**Alternative:** Use **Approach 1 (Let-Based)** to stay purely functional but with different semantics.

**Long-term:** Consider **Approach 5 (SSA)** for optimization passes after initial compilation.
