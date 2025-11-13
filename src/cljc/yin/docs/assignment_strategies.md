# Assignment Strategies for Yin VM - Quick Reference

## The Problem

Python/JavaScript: `x = x + 1` (mutable)
Yin VM: Immutable CESK machine (functional)

**How do we bridge this gap?**

---

## 5 Approaches

### 1️⃣ Let-Based Rebinding (Pure Functional)

**Idea:** Transform assignments into nested `let` expressions

```python
# Python
x = 5
x = x + 1
```

```clojure
;; Becomes
(let [x 5]
  (let [x (+ x 1)]
    x))
```

**Pros:** Pure, no VM changes
**Cons:** Deep nesting, doesn't match imperative semantics

---

### 2️⃣ Store Operations (Pragmatic)

**Idea:** Use `:store` field in CESK state as mutable heap

```clojure
;; New AST nodes
{:type :store-set :name 'x :value ... :body ...}
{:type :store-get :name 'x}
```

**Pros:** Matches imperative semantics, efficient
**Cons:** Breaks purity, harder to migrate continuations

**⭐ RECOMMENDED FOR PYTHON COMPILER**

---

### 3️⃣ State Monad (Functional State Threading)

**Idea:** Thread state through computations explicitly

```clojure
{:type :sequence
 :statements [{:type :bind :name 'x :value ...}
              {:type :bind :name 'y :value ...}]
 :result ...}
```

**Pros:** Preserves immutability, clean composition
**Cons:** Verbose, shadowing semantics

---

### 4️⃣ Reference Cells (Explicit Mutability)

**Idea:** Variables hold references to mutable cells

```clojure
{:type :ref-new :value ...}   ;; Create cell
{:type :ref-get :ref ...}     ;; Read cell
{:type :ref-set :ref ... :value ...}  ;; Write cell
```

**Pros:** Explicit mutability boundaries
**Cons:** Very verbose, doesn't match source syntax

---

### 5️⃣ SSA Form (Static Single Assignment)

**Idea:** Each variable assigned exactly once

```python
# Python
x = 5
x = x + 1
x = x * 2
```

```python
# SSA
x_0 = 5
x_1 = x_0 + 1
x_2 = x_1 * 2
```

**Pros:** Pure functional, standard compiler technique
**Cons:** Name explosion, complex with control flow

---

## Comparison

| Approach | Purity | Complexity | Performance | Match Semantics |
|----------|--------|------------|-------------|-----------------|
| Let-Based | ✅ | ⚠️ | ✅ | ❌ |
| **Store Ops** | ❌ | ⚠️ | ✅ | **✅** |
| State Monad | ✅ | ⚠️ | ✅ | ⚠️ |
| Ref Cells | ⚠️ | ❌ | ⚠️ | ⚠️ |
| SSA | ✅ | ❌ | ✅ | ❌ |

---

## Recommended: Store Operations (Approach 2)

### Why?

1. `:store` field already exists in CESK state (currently unused)
2. Matches imperative language semantics exactly
3. Efficient - no deep nesting
4. Flexible - handles loops, closures, multiple assignments

### Implementation

```clojure
;; In yin/vm.cljc - Add to eval function:

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

```clojure
;; In yang/python.cljc - Compile assignments:

(defn compile-assignment [var-name value-expr body]
  {:type :store-set
   :name var-name
   :value (compile-py-expr value-expr)
   :body body})
```

### Example

```python
# Python
x = 5
x = x + 1
```

```clojure
;; Universal AST
{:type :store-set
 :name 'x
 :value {:type :literal :value 5}
 :body {:type :store-set
        :name 'x
        :value {:type :application
                :operator {:type :variable :name '+}
                :operands [{:type :store-get :name 'x}
                          {:type :literal :value 1}]}
        :body {:type :store-get :name 'x}}}
```

---

## Trade-offs

**Pure Functional (Approach 1, 3, 5):**
- ✅ Easier to reason about
- ✅ Continuation migration is simpler
- ❌ Doesn't match source language semantics
- ❌ Performance overhead or code bloat

**Pragmatic Mutable (Approach 2, 4):**
- ✅ Matches source language exactly
- ✅ More efficient
- ❌ Harder to reason about
- ❌ Continuation migration needs store serialization

---

## Hybrid Strategy

Use different approaches for different contexts:

- **Simple sequential assignments** → Store Operations
- **Pure functional code** → Keep as lambdas/let
- **Optimization passes** → Convert to SSA later

---

## Next Steps

1. Implement `:store-set` and `:store-get` in `yin/vm.cljc`
2. Update Python compiler to recognize assignment statements
3. Add tests for assignment compilation and execution
4. Document store semantics in state.md
5. Consider scoping rules (local vs global store)

---

See [assignments.md](src/cljc/yin/docs/assignments.md) for detailed analysis and implementation examples.
