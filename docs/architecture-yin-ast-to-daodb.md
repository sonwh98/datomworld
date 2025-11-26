# Architecture: Yin.vm AST as Datom Streams in DaoDB

**Goal:** Store Yin.vm Universal AST as a stream of datoms `[e a v t m]` in DaoDB, enabling:
- Queryable program structure via Datalog
- Temporal evolution tracking (AST changes over time)
- Cross-language transformation lineage
- Execution state as datom streams (CESK machine state)
- Mobile code continuations

---

## 1. Current State Analysis

### Yin.vm AST Structure (Map-Based)
```clojure
;; Example: ((lambda (x) (+ x 1)) 5)
{:type :application
 :operator {:type :lambda
            :params ['x]
            :body {:type :application
                   :operator {:type :variable :name '+}
                   :operands [{:type :variable :name 'x}
                              {:type :literal :value 1}]}}
 :operands [{:type :literal :value 5}]}
```

### CESK Machine State
```clojure
{:control      <AST-node>      ; What we're evaluating
 :environment  {x 5, y 10}     ; Variable bindings
 :store        {}              ; Memory graph
 :continuation <cont-frame>    ; Where to return
 :value        42}             ; Current result
```

---

## 2. Datom Schema Design

### 2.1 Core Principle: Each AST Node = Entity

Every AST node becomes an **entity** with datoms describing its properties and relationships.

### 2.2 The Five-Element Datom: [e a v t m]

- **e (entity)**: AST node ID (e.g., `node-42`, `node-100`)
- **a (attribute)**: Property name (e.g., `:ast/type`, `:ast/value`)
- **v (value)**: The actual data (e.g., `:literal`, `42`)
- **t (time)**: Transaction ID (when this fact was asserted)
- **m (metadata)**: Context including:
  - Causal relationships
  - Source language info
  - Certainty levels
  - Execution provenance

### 2.3 AST Node Attributes

#### Structural Attributes
```clojure
:ast/type          ; Node type (:literal, :variable, :lambda, :application, :if)
:ast/parent        ; Parent node reference (entity ID)
:ast/children      ; Child nodes (vector of entity IDs)
:ast/order         ; Position among siblings (for operands, etc.)
```

#### Type-Specific Attributes

**Literal:**
```clojure
:ast/value         ; The literal value (42, "hello", true, etc.)
:ast/value-type    ; Optional: :number, :string, :boolean, :nil
```

**Variable:**
```clojure
:ast/name          ; Variable name (symbol or string)
:ast/binding-ref   ; Reference to binding site (for analysis)
```

**Lambda:**
```clojure
:ast/params        ; Parameter list (vector of symbols)
:ast/body          ; Body expression (entity ID)
:ast/closure-env   ; Captured environment (entity ID or inline data)
```

**Application:**
```clojure
:ast/operator      ; Function expression (entity ID)
:ast/operands      ; Argument expressions (vector of entity IDs)
```

**Conditional (if):**
```clojure
:ast/test          ; Test expression (entity ID)
:ast/consequent    ; Then branch (entity ID)
:ast/alternate     ; Else branch (entity ID)
```

#### Metadata Attributes
```clojure
:ast/source-lang       ; "Clojure", "Python", "JavaScript"
:ast/source-location   ; {:line 10, :column 5, :file "foo.clj"}
:ast/transformed-from  ; Previous AST node ID (for cross-language tracking)
:type/certainty        ; :static, :inferred, :dynamic, :unknown
:type/declared         ; Declared type (if any)
:type/inferred         ; Inferred type (from analysis)
```

---

## 3. Decomposition: Map AST → Datom Stream

### 3.1 Example: Literal Node

**Input (Map):**
```clojure
{:type :literal
 :value 42}
```

**Output (Datoms):**
```clojure
[node-1 :ast/type :literal tx-100 {}]
[node-1 :ast/value 42 tx-100 {}]
```

### 3.2 Example: Variable Node

**Input (Map):**
```clojure
{:type :variable
 :name 'x}
```

**Output (Datoms):**
```clojure
[node-2 :ast/type :variable tx-100 {}]
[node-2 :ast/name x tx-100 {}]
```

### 3.3 Example: Lambda Node

**Input (Map):**
```clojure
{:type :lambda
 :params ['x 'y]
 :body {:type :application
        :operator {:type :variable :name '+}
        :operands [{:type :variable :name 'x}
                   {:type :variable :name 'y}]}}
```

**Output (Datoms):**
```clojure
;; Lambda node
[node-3 :ast/type :lambda tx-100 {}]
[node-3 :ast/params [x y] tx-100 {}]
[node-3 :ast/body node-4 tx-100 {}]
[node-3 :ast/children [node-4] tx-100 {}]

;; Body: application node
[node-4 :ast/type :application tx-100 {}]
[node-4 :ast/parent node-3 tx-100 {}]
[node-4 :ast/operator node-5 tx-100 {}]
[node-4 :ast/operands [node-6 node-7] tx-100 {}]
[node-4 :ast/children [node-5 node-6 node-7] tx-100 {}]

;; Operator: + variable
[node-5 :ast/type :variable tx-100 {}]
[node-5 :ast/name + tx-100 {}]
[node-5 :ast/parent node-4 tx-100 {}]
[node-5 :ast/order 0 tx-100 {}]

;; First operand: x variable
[node-6 :ast/type :variable tx-100 {}]
[node-6 :ast/name x tx-100 {}]
[node-6 :ast/parent node-4 tx-100 {}]
[node-6 :ast/order 1 tx-100 {}]

;; Second operand: y variable
[node-7 :ast/type :variable tx-100 {}]
[node-7 :ast/name y tx-100 {}]
[node-7 :ast/parent node-4 tx-100 {}]
[node-7 :ast/order 2 tx-100 {}]
```

### 3.4 Complete Example: `((lambda (x) (+ x 1)) 5)`

**Input (Map):**
```clojure
{:type :application
 :operator {:type :lambda
            :params ['x]
            :body {:type :application
                   :operator {:type :variable :name '+}
                   :operands [{:type :variable :name 'x}
                              {:type :literal :value 1}]}}
 :operands [{:type :literal :value 5}]}
```

**Output (Datoms):**
```clojure
;; Root: application node
[node-10 :ast/type :application tx-100 {}]
[node-10 :ast/operator node-11 tx-100 {}]
[node-10 :ast/operands [node-16] tx-100 {}]
[node-10 :ast/children [node-11 node-16] tx-100 {}]

;; Operator: lambda
[node-11 :ast/type :lambda tx-100 {}]
[node-11 :ast/parent node-10 tx-100 {}]
[node-11 :ast/params [x] tx-100 {}]
[node-11 :ast/body node-12 tx-100 {}]
[node-11 :ast/children [node-12] tx-100 {}]

;; Lambda body: application (+ x 1)
[node-12 :ast/type :application tx-100 {}]
[node-12 :ast/parent node-11 tx-100 {}]
[node-12 :ast/operator node-13 tx-100 {}]
[node-12 :ast/operands [node-14 node-15] tx-100 {}]
[node-12 :ast/children [node-13 node-14 node-15] tx-100 {}]

;; + operator
[node-13 :ast/type :variable tx-100 {}]
[node-13 :ast/name + tx-100 {}]
[node-13 :ast/parent node-12 tx-100 {}]
[node-13 :ast/order 0 tx-100 {}]

;; x operand
[node-14 :ast/type :variable tx-100 {}]
[node-14 :ast/name x tx-100 {}]
[node-14 :ast/parent node-12 tx-100 {}]
[node-14 :ast/order 1 tx-100 {}]

;; 1 literal
[node-15 :ast/type :literal tx-100 {}]
[node-15 :ast/value 1 tx-100 {}]
[node-15 :ast/parent node-12 tx-100 {}]
[node-15 :ast/order 2 tx-100 {}]

;; Argument: 5 literal
[node-16 :ast/type :literal tx-100 {}]
[node-16 :ast/value 5 tx-100 {}]
[node-16 :ast/parent node-10 tx-100 {}]
[node-16 :ast/order 0 tx-100 {}]
```

---

## 4. CESK Machine State as Datom Stream

### 4.1 Execution State Attributes

```clojure
;; Execution step
:exec/step-id          ; Unique step identifier
:exec/order            ; Sequential order (0, 1, 2, ...)
:exec/control          ; Current AST node being evaluated
:exec/value            ; Current computed value
:exec/environment      ; Environment snapshot (entity ID)
:exec/continuation     ; Continuation frame (entity ID)

;; Environment
:env/bindings          ; Map of variable → value
:env/parent            ; Parent environment (lexical scope chain)

;; Continuation
:cont/type             ; :eval-operator, :eval-operand, :eval-test
:cont/frame            ; Original AST node (entity ID)
:cont/parent           ; Outer continuation (entity ID)
:cont/environment      ; Saved environment (entity ID)
```

### 4.2 Example: Execution Trace as Datoms

For `((lambda (x) (+ x 1)) 5)`:

```clojure
;; Step 0: Start evaluation
[step-0 :exec/order 0 tx-200 {}]
[step-0 :exec/control node-10 tx-200 {}]
[step-0 :exec/value nil tx-200 {}]
[step-0 :exec/continuation nil tx-200 {}]
[step-0 :exec/environment env-0 tx-200 {}]

;; Step 1: Evaluate operator (lambda)
[step-1 :exec/order 1 tx-201 {}]
[step-1 :exec/control node-11 tx-201 {}]
[step-1 :exec/continuation cont-1 tx-201 {}]
[cont-1 :cont/type :eval-operator tx-201 {}]
[cont-1 :cont/frame node-10 tx-201 {}]

;; Step 2: Lambda evaluated, return closure
[step-2 :exec/order 2 tx-202 {}]
[step-2 :exec/control nil tx-202 {}]
[step-2 :exec/value closure-1 tx-202 {}]
[closure-1 :closure/type :closure tx-202 {}]
[closure-1 :closure/params [x] tx-202 {}]
[closure-1 :closure/body node-12 tx-202 {}]

;; ... (continue for all 17 steps)

;; Final step: Result
[step-16 :exec/order 16 tx-216 {}]
[step-16 :exec/control nil tx-216 {}]
[step-16 :exec/value 6 tx-216 {}]
[step-16 :exec/continuation nil tx-216 {}]
```

---

## 5. Datalog Queries

### 5.1 Query: Find All Lambda Nodes

```clojure
[:find ?node ?params
 :where
 [?node :ast/type :lambda]
 [?node :ast/params ?params]]
```

### 5.2 Query: Find All Variable References Named 'x'

```clojure
[:find ?node ?parent
 :where
 [?node :ast/type :variable]
 [?node :ast/name x]
 [?node :ast/parent ?parent]]
```

### 5.3 Query: Trace Execution Steps

```clojure
[:find ?step ?control-type ?value
 :in $ ?start-step
 :where
 [?step :exec/order ?order]
 [(>= ?order ?start-step)]
 [?step :exec/control ?control]
 [?control :ast/type ?control-type]
 [?step :exec/value ?value]
 :order-by (asc ?order)]
```

### 5.4 Query: Find AST Nodes That Changed Between Transactions

```clojure
[:find ?node ?attr ?old-value ?new-value
 :in $ ?tx1 ?tx2
 :where
 [?node ?attr ?old-value ?tx1]
 [?node ?attr ?new-value ?tx2]
 [(!= ?old-value ?new-value)]]
```

### 5.5 Query: Find Cross-Language Transformations

```clojure
[:find ?source-node ?target-node ?source-lang ?target-lang
 :where
 [?target-node :ast/transformed-from ?source-node]
 [?source-node :ast/source-lang ?source-lang]
 [?target-node :ast/source-lang ?target-lang]]
```

---

## 6. Implementation Architecture

### 6.1 Components

```
┌─────────────────────────────────────────────────────────┐
│                    Yin.vm AST (Maps)                    │
│  {:type :application :operator ... :operands [...]}     │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│              AST Decomposer (NEW)                       │
│  - Walk AST tree recursively                            │
│  - Generate unique node IDs                             │
│  - Emit datoms for each node                            │
│  - Preserve parent-child relationships                  │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼ [e a v t m] stream
┌─────────────────────────────────────────────────────────┐
│                      DaoDB                              │
│  - Consume datom stream                                 │
│  - Materialize covered indexes                          │
│  - Support Datalog queries                              │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼ Datalog queries
┌─────────────────────────────────────────────────────────┐
│              Applications                               │
│  - Query AST structure                                  │
│  - Trace execution history                              │
│  - Cross-language translation                           │
│  - Mobile code continuations                            │
└─────────────────────────────────────────────────────────┘
```

### 6.2 Key Modules to Build

#### Module 1: `yin.datom.decompose`
**Purpose:** Convert map-based AST → datom stream

```clojure
(ns yin.datom.decompose)

(defn ast->datoms
  "Decomposes an AST map into a stream of datoms.
   Returns: {:datoms [...], :root-id node-id}"
  [ast]
  ...)

(defn node->datoms
  "Converts a single AST node to datoms.
   Returns: {:datoms [...], :node-id id, :children-ids [...]}"
  [node parent-id]
  ...)
```

#### Module 2: `yin.datom.recompose`
**Purpose:** Reconstruct map-based AST from datoms

```clojure
(ns yin.datom.recompose)

(defn datoms->ast
  "Reconstructs an AST map from datoms.
   Input: datom stream, root node-id
   Returns: AST map"
  [datoms root-id]
  ...)
```

#### Module 3: `yin.datom.exec`
**Purpose:** Capture CESK execution as datom stream

```clojure
(ns yin.datom.exec
  (:require [yin.vm :as vm]))

(defn trace->datoms
  "Converts VM execution trace to datom stream."
  [initial-state ast]
  ...)

(defn step->datoms
  "Converts a single CESK step to datoms."
  [step-num state]
  ...)
```

#### Module 4: `yin.datom.query`
**Purpose:** Helper functions for common Datalog queries

```clojure
(ns yin.datom.query)

(defn find-all-nodes-of-type [db node-type])
(defn find-node-by-id [db node-id])
(defn find-children [db node-id])
(defn find-execution-trace [db root-node-id])
```

---

## 7. Two Execution Modes

### Mode 1: Direct Interpretation (Current)
```
Map AST → yin.vm/eval → Result
```
- ✅ Already implemented
- ✅ Simple, no overhead
- ❌ Not queryable
- ❌ No history

### Mode 2: Datom Stream Execution (Future)
```
Map AST → Decompose → Datoms → Store in DaoDB
                                    ↓
Query for Execution Plan → Linear Execution Stream
                                    ↓
                            Sequential Execution
```
- ✅ Fully queryable
- ✅ Complete history
- ✅ Mobile continuations
- ⚠️ Compilation overhead (one-time)

### Hybrid Mode: Best of Both
```
Initial: Map AST → Direct interpretation
         Record each step as datoms in background
         ↓
Result:  Fast execution + Complete trace
```

---

## 8. Metadata (m) Examples

The `m` field in `[e a v t m]` stores contextual information:

### 8.1 Causal Relationships
```clojure
{:caused-by [node-5 node-7]    ; This node depends on these
 :causes [node-10 node-11]}    ; This node influences these
```

### 8.2 Source Location
```clojure
{:source-file "app.clj"
 :line 42
 :column 10
 :end-line 42
 :end-column 25}
```

### 8.3 Type Certainty
```clojure
{:type/certainty :static        ; High confidence
 :type/inferred-by :flow-analysis
 :type/confidence 0.95}
```

### 8.4 Cross-Language Provenance
```clojure
{:original-lang "Python"
 :transformation "python->universal-ast"
 :timestamp #inst "2025-11-25T10:30:00Z"}
```

### 8.5 Execution Context
```clojure
{:execution-id "exec-42"
 :host "node-5.datom.world"
 :step-number 17}
```

---

## 9. Benefits of This Architecture

### 9.1 Queryability
- **Find all variables named 'count'** across the entire codebase
- **Trace value provenance** from source to sink
- **Detect patterns** like "unvalidated input to sensitive function"

### 9.2 Time-Travel
- **Query any historical state** by filtering on transaction ID
- **Compare two versions** of the same AST
- **Undo/redo** by retracting/asserting datoms

### 9.3 Mobile Code
- **Serialize continuations** as datom transactions
- **Migrate computation** between nodes
- **Resume execution** on different machines/languages

### 9.4 Cross-Language
- **Preserve transformation lineage** (Python → AST → C++)
- **Track semantic drift** (detect "false friends")
- **Unified queries** across polyglot codebases

### 9.5 Performance
- **Compile once** (AST → execution stream via Datalog)
- **Execute fast** (sequential iteration, cache-friendly)
- **Query anytime** (AST remains queryable during execution)

---

## 10. Next Steps

### Phase 1: Basic Decomposition ✅ Ready to implement
- [ ] Implement `ast->datoms` for all node types
- [ ] Test with existing yin.vm examples
- [ ] Verify round-trip (map → datoms → map)

### Phase 2: DaoDB Integration
- [ ] Define datom schema in DaoDB
- [ ] Create indexes for common query patterns
- [ ] Test Datalog queries

### Phase 3: Execution Tracing
- [ ] Capture CESK steps as datoms
- [ ] Query execution history
- [ ] Visualize execution flow

### Phase 4: Advanced Features
- [ ] Cross-language transformations
- [ ] Mobile continuations
- [ ] Semantic firewalls
- [ ] Time-travel debugging

---

## 11. Open Design Questions

### Q1: Node ID Generation
- **Option A:** Sequential integers (`node-1`, `node-2`, ...)
- **Option B:** UUIDs (globally unique, distributed-friendly)
- **Option C:** Content-based hashes (deterministic, deduplication)

**Recommendation:** Start with sequential integers for simplicity, migrate to UUIDs for distribution.

### Q2: Environment Representation
Should environments be:
- **Option A:** Inline maps in datoms (simple)
- **Option B:** Separate entities with their own datoms (queryable)
- **Option C:** Hybrid (inline for small, entity for large)

**Recommendation:** Option B for full queryability.

### Q3: Datom Metadata Extent
How much metadata should we store in `m`?
- **Option A:** Minimal (only transaction ID)
- **Option B:** Rich (source location, causality, types)
- **Option C:** Pluggable (let applications decide)

**Recommendation:** Option B with Option C as future extension.

### Q4: Performance vs Queryability Trade-off
For execution traces:
- **Option A:** Store every CESK step (complete history, slow)
- **Option B:** Store only "interesting" steps (fast, incomplete)
- **Option C:** Configurable retention policy (flexible)

**Recommendation:** Option C with sane defaults.

---

## 12. References

- **DaoDB Spec:** `/Users/sto/workspace/datomworld/public/chp/dao-db.chp`
- **Yin.vm Implementation:** `/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc`
- **Blog: AST as Datom Streams:** `public/chp/blog/ast-datom-streams-bytecode-performance.blog`
- **Blog: Higher Dimensional AST:** `public/chp/blog/ast-higher-dimensional-datom-streams.blog`

---

**Status:** Architecture design complete. Ready for implementation.

**Author:** Claude
**Date:** 2025-11-25
**Version:** 1.0
