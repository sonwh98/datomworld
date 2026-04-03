---
description: System architecture - streams, agents/continuations, YIN.VM CESK machine, compilation pipeline
---

# STREAMS

All IO is modeled as a stream.
Functions consume streams and produce streams.
No direct function-to-function coupling without a stream boundary.
Side effects must appear as stream emissions.
Ordering guarantees must be explicit.
Streams are values that can be sent through streams.

Bounded vs Unbounded:
  Open stream: unbounded, still receiving datoms.
  Bounded stream: closed at some t, finite, stable for reasoning.
  A bounded stream IS the database value (no separate "value" type).
  Stability comes from bounding, not from a different abstraction.

Stream Iteration:
  Streams iterate in t-order (transaction/append order).
  This is the canonical order regardless of any indexes.

Streams and Indexes:
  Streams are the universal abstraction (datoms in t-order).
  Indexes are optional optimizations built by interpreters (e.g., DaoDB).
  d/q works on any bounded stream:
    - Raw stream: no indexes, O(n) scan
    - DaoDB-managed stream: EAVT/AEVT/AVET/VAET indexes, O(log n) lookup
  Same interface, same semantics, different performance.
  Indexes are a performance optimization, not a semantic requirement.

# AGENTS

Agents, functions, closures, and continuations are a unified concept in Yin.VM.
Agents may migrate across nodes.
Agents own state through their continuation structure: call stack, environment, and stored values.
Agents communicate with the outside world through DaoStream.
Agent behavior is specified entirely through stream effects.
Agent identity must not imply trust.
Capability tokens define authority, not identity.

Continuation Migration:
  When a continuation migrates, the AST datoms are the canonical payload.
  Bytecode is a projection of the datoms for a specific execution model.
  Migrating bytecode alone would force the destination to use the same VM.
  Migrating datoms lets the destination project into whatever model it prefers.
  The AST datoms can travel alone, giving the destination freedom to compile as needed.
  Bytecode may accompany the datoms as a cache, but never travels without them.
  If the destination runs the same VM model, it can use the bytecode directly.
  If it doesn't, it recompiles from the datoms.
  Closure fields like :body-bytes and :body-addr are ephemeral, local to the VM instance.
  What survives migration: the datom stream, the captured environment bindings,
  and the continuation structure (where execution was suspended).

Runtime State:
  Runtime state (continuations, environments, stack frames) is ephemeral.
  Runtime state lives in memory as native VM data structures, not in DaoDB.
  DaoDB is for persistent facts: AST datoms, schema, parked continuations.
  Runtime state is queryable via Datalog on demand:
    vm/state->datom-stream projects in-memory VM state into a datom stream.
    Zero cost during execution; projection computed only when queried.
    Joins across $code (DaoDB) and $runtime (in-memory stream) in a single d/q:
      [:find ?node-type ?frame-type
       :in $code $runtime
       :where [$runtime ?frame :cont/pending-arg [0 ?node-hash]]
              [$code ?node :yin/content-hash ?node-hash]
              [$code ?node :yin/type ?node-type]
              [$runtime ?frame :cont/type ?frame-type]]
  Persistence boundary: park/migrate promotes ephemeral state to persistent datoms in DaoDB.

# YIN.VM & UNIVERSAL AST

Yin.vm is a CESK continuation machine (Control, Environment, Store, Continuation).
The Universal AST is canonical code: syntax is a rendering.
AST nodes are represented in two equivalent forms:
  - AST maps: raw in-memory maps with :type, :operator, :operands, :body, etc.
  - Datoms: [e a v t m] tuples stored in DaoDB and queryable via Datalog.
The AST is not compiled away: it persists alongside execution state.

ASTs are materialized views over five orthogonal dimensions:
  1. Structure (spatial): parent-child relationships, tree topology
  2. Time (temporal): evolution through transactions
  3. Type (certainty): static to dynamic as a continuum, not a binary
  4. Language (transformations): cross-language semantic preservation
  5. Execution (runtime): continuation state, stack frames, instruction pointers

Two independent interpreters prove the same semantics on different representations:
  - ASTWalkerVM interprets AST maps via tree traversal (in-memory graphs).
  - SemanticVM interprets AST datoms via graph traversal (DaoDB queries).
Both are executable; neither is compiled away. They are equivalent views of the same computation.

The AST also enables multiple compilation backends via semantic projections:
  - A stack-based VM that compiles AST datoms to stack bytecode.
  - A register-based VM that compiles AST datoms to register bytecode.
  - Compilation to WebAssembly binaries for execution in WASM runtimes.
  - Compilation to native code for specific hardware targets (e.g., IoT devices, specialized chips).
Since the AST preserves semantics as immutable data, any execution backend can project the same underlying facts into the optimal form for its target environment.

The semantic VM's direct interpretation of AST datoms enables runtime macros: code can transform itself by manipulating the same datom structures the VM executes.

Types are metadata on AST nodes, not categories of languages.
Static and dynamic typing are unified as certainty levels (:static, :dynamic, :unknown).
Eval is the VM: no hidden interpreter, AST execution is explicit.
Continuations are first-class, serializable, and mobile across nodes.
Functions, closures, and continuations are unified as reified execution contexts.
Syntax rendering is non-deterministic: one AST maps to many valid syntaxes.
Syntax preferences are user/context-specific: the AST remains canonical.
Runtime macros exist: code can transform itself using the same structures the VM executes.
Cross-language interop works through the Universal AST as a shared semantic layer.
Semantic drift must be prevented at all compositional levels, not just atomic nodes.
The Universal AST is lower-level than Lisp: it makes evaluation, mutation, and types explicit.

Execution must be sandboxed.
Assume hostile or malformed inputs.
VM boundaries are security boundaries.
Kernel-space and user-space semantics must remain consistent.
No VM escape assumptions.

# COMPILATION AS STREAM PROCESSING

Compilation is a stream processor. Each stage consumes a datom stream and emits a new one.
The compilation pipeline does not destroy or consume its input: all intermediate streams persist.

Pipeline topology:

  Source text
    -> yang/compile: text -> Universal AST (maps with :type, :operator, :operands, etc.)
    -> vm/ast->datoms: AST -> datom stream [e a v t m] with :yin/ attributes and negative tempids
    -> Three independent projections from the same datom stream:
       1. asm/compile: datoms -> semantic bytecode (:op/ triples, queryable via Datalog)
       2. stack/ast-datoms->asm: datoms -> stack assembly ([:push v] [:load x] [:call n])
       3. register/ast-datoms->asm: datoms -> register assembly ([:loadk rd v] [:call rd rf args])
    -> Assembly -> bytecode: numeric encoding with constant pools
    -> VM execution: bytecode stream -> value + effect descriptors
    -> AST execution: universal AST -> walker/run -> value + effect descriptors

Same datoms, multiple interpreters.
The datom stream from ast->datoms is the shared substrate.
Each backend (semantic, stack, register) projects the same facts into a different execution model.
This is the physics metaphor in practice: same wave function, different measurements.

Datom stream at the AST boundary (concrete example, (+ 1 2)):
  [-1 :yin/type :application 0 0]
  [-1 :yin/operator -2 0 0]
  [-1 :yin/operands [-3 -4] 0 0]
  [-2 :yin/type :variable 0 0]
  [-2 :yin/name + 0 0]
  [-3 :yin/type :literal 0 0]
  [-3 :yin/value 1 0 0]
  [-4 :yin/type :literal 0 0]
  [-4 :yin/value 2 0 0]

Semantic projection of the same expression:
  [-1028 :op/type :apply]
  [-1028 :op/operator-node -1025]
  [-1028 :op/operand-nodes [-1026 -1027]]
  [-1025 :op/type :load-var]
  [-1025 :op/var-name +]
  [-1026 :op/type :literal]
  [-1026 :op/value 1]
  [-1027 :op/type :literal]
  [-1027 :op/value 2]

Invariant: every stage is a pure function from stream to stream.
No stage mutates its input. Intermediate representations coexist.
The AST persists alongside bytecode, alongside execution state.

Visual reference: src/cljs/datomworld/demo.cljs renders this pipeline as draggable cards
connected by bezier curves. Each card is a stream stage. The connection-line buttons
(AST ->, Asm ->, Reg ->, Stack ->) are the transducers. The UI makes the stream
topology explicit and observable.
