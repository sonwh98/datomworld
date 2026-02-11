# ===========================
# datom.world – Development Rules
# ===========================

# CORE PHILOSOPHY

Everything is data.
Streams are data. Code is data. Runtime state is data.
Everything is a stream.
State is represented as immutable datoms.
Interpretation > abstraction.
Explicit causality > implicit assumptions.
Restrictions are a feature.

# NON-NEGOTIABLE INVARIANTS

Do not introduce hidden global state.
Do not introduce implicit control flow.
Do not introduce callbacks without explicit stream representation.
Do not introduce shared mutable state.
Do not collapse interpretation and execution into the same layer.
Do not assume graphs: graphs must be constructed explicitly from tuples.

# DATOMS

Datoms are 5-tuples: (e a v t m).
Datoms are immutable facts, not objects.
Datoms are the canonical serialization format (no parsing step).

Intuition (physics metaphor):
  The datom stream is the unitary wave function: it contains the complete state of the universe.
  A datom [e a v t m] is like a space-time event.
  Space (structural): [e a v] defines what exists (entity, attribute, value).
  Time (causal): [t m] defines when and why (transaction, metadata).
  Interpreters observe parts of the stream and construct higher-dimensional structures.
  Like quantum measurement, each interpreter projects the stream differently: same data, different meanings.

Components:
  e: Entity ID. Relative offset from zero basis.
     Negative IDs are temporary local IDs (tempids), used during compilation and before commitment.
     Positive IDs are permanent IDs assigned by DaoDB after a successful transaction.
     Reserved range: 0-1024 for system entities (always positive).
     User entities start at 1025 (positive). While local/uncommitted, they are represented as negative counterparts (e.g., -1025).
     On migration, the zero basis changes but entity IDs remain unchanged (relative offsets).
     Global form: 128-bit [namespace:offset] where namespace is 64-bit, offset is 64-bit.
     This mirrors IPv6's design: 64-bit network prefix + 64-bit interface ID.
     Namespace 0 is local. Migrated datoms receive a non-zero 64-bit namespace.
     The 64-bit namespace space supports billions of nodes, each with 64-bit entity space.
     Semantic identity uses unique attributes (e.g., :person/email).
     Uniqueness is namespace-scoped: :db/unique enforces uniqueness locally, and within
     the assigned namespace after migration. Cross-namespace correlation uses queries.
  a: Attribute. Namespaced keyword.
  v: Value. Ground value (primitive: integer, string, boolean, etc.) or entity reference.
  t: Transaction ID. Monotonic integer, intrinsic and stream-local.
  m: Metadata entity reference (always an integer, never language-level nil).
     Establishes causality across streams (since t is local).
     Used for provenance, access control, and cross-stream references.

Sizing:
  Datoms can be variable-size (general case) or fixed-size (typed streams).
  A stream can declare a type that constrains the size of each datom position.
  Fixed-size streams enable: cache-efficient layouts, SIMD operations, O(1) indexing.
  Variable-size streams provide flexibility at the cost of offset-table overhead.
  Example typed stream: {:e :int64, :a :keyword, :v :float64, :t :int64, :m :int64}

Value Constraints:
  v is a ground value or an entity reference.
  v can be a blob, but size must be bounded by stream type.
  Large values are represented as separate streams, referenced by entity ID.

Reserved Entities (0-1024):
  System entities with universal meaning across all namespaces.
  Entity 0: nil metadata. When m=0, means "no metadata". Self-referential (fixed point).
  Entities 1-1024: built-in attributes (:db/ident, :db/valueType, :db/cardinality, etc.),
    primitive type markers, and other system primitives.
  These do not migrate: they have the same meaning everywhere.
  User-defined schema lives in user space (1025+) and migrates with data.

Principles:
  Do not embed behavior inside datoms.
  Content hashes (if used) are derived by interpreters, not intrinsic to the tuple.
  Interpretation is local: agents decide meaning, not global ontologies.
  Graphs are constructed from tuples, not assumed.
  Restrictions (no arbitrary URIs, no variables in storage) enable efficient indexing (EAVT, AEVT, etc.).

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

# NAMESPACES

Namespaces scope entity IDs and uniqueness constraints.

Cross-Namespace Queries:
  Namespaces are treated as separate databases in queries (explicit inputs).
  Like Datomic's multiple database pattern:
    [:find ?e1 ?e2
     :in $local $remote
     :where [$local ?e1 :person/email ?email]
            [$remote ?e2 :person/email ?email]]
  Joins across namespaces happen on shared values, not entity IDs.
  Cross-namespace identity correlation is a query-time concern, not storage-time.

# AGENTS

Agents are continuations.
Agents may migrate across nodes.
Agents do not own state: they read/write streams.
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

# CAPABILITIES & TRUST

Possession of a capability is necessary but not sufficient for trust.
Trust must be contextual, revocable, and stream-scoped.
Never assume a valid signature implies safe execution.
Prefer confinement over verification.

# YIN.VM & UNIVERSAL AST

Yin.vm is a CESK continuation machine (Control, Environment, Store, Continuation).
The Universal AST is canonical code: syntax is a rendering.
AST nodes are datoms stored in DaoDB and queryable via Datalog.
The AST is not compiled away: it persists alongside execution state.

ASTs are materialized views over five orthogonal dimensions:
  1. Structure (spatial): parent-child relationships, tree topology
  2. Time (temporal): evolution through transactions
  3. Type (certainty): static to dynamic as a continuum, not a binary
  4. Language (transformations): cross-language semantic preservation
  5. Execution (runtime): continuation state, stack frames, instruction pointers

The canonical nature of the AST as datoms enables multiple execution models via semantic projections:
- A stack-based VM for traditional sequential execution and legacy compatibility.
- A register-based VM for modern, efficient execution with explicit register allocation.
- Compilation to native code for specific hardware targets (e.g., IoT devices, specialized chips).
Since the AST preserves semantics as immutable data, any execution backend can project the same underlying facts into the optimal form for its target environment.

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

Visual reference: src/cljs/datomworld/core.cljs renders this pipeline as draggable cards
connected by bezier curves. Each card is a stream stage. The connection-line buttons
(AST ->, Asm ->, Reg ->, Stack ->) are the transducers. The UI makes the stream
topology explicit and observable.

# ENTANGLEMENT

One leader establishes event ordering.
Failover must be explicit and observable.
No hidden consensus mechanisms.
Entanglement does not imply global truth: only shared causality.

# LANGUAGE & STYLE

Prefer simple data over rich types.
Prefer total functions over partial ones.
Prefer declarative pipelines over imperative logic.
Avoid cleverness.
Name things after what they do to streams.

# FILE FORMATS

.chp and .blog files are EDN files with Hiccup markup.

Structure for .blog files:
#:blog{:title "Title Here"
       :date #inst "YYYY-MM-DDTHH:MM:SS.000-00:00"
       :abstract [:p "Abstract with hiccup markup..."]
       :content [:section.blog-article
                 [:div.section-inner
                  [:article
                   :$blog-title
                   :$blog-date
                   ;; Hiccup content here (vectors for HTML elements)
                   ]]]}

When creating .blog :
- Use EDN syntax with namespaced maps (#:blog{...} )
- Use Hiccup vectors for all markup (e.g., [:p "text"], [:strong "bold"], [:a {:href "..."} "link"])
- Never use markdown syntax
- Include special keywords like :$blog-title and :$blog-date where appropriate
- Never use em dashes (—). Use alternatives:
  - Parenthetical asides: commas or parentheses
  - Lists or examples: colons
  - Dramatic pauses: periods (new sentences)
  - Clarifications: parentheses

# AI BEHAVIOR CONSTRAINTS

Do not commit changes until explicitly asked by the user.
When asked to commit, only commit staged changes.
Do not invent abstractions that hide streams.
Do not suggest mainstream frameworks unless explicitly asked.
Do not optimize prematurely.
When uncertain, ask at the architectural level, not the implementation level.
Explanations should align with Plan 9 / Datomic / Lisp lineage.

# WHAT TO DO INSTEAD

If a problem appears complex:
  Decompose it into streams.
  Identify invariants.
  Restrict degrees of freedom.
  Make causality explicit.
  Let structure emerge from constraints.

# DEBUGGING MALFORMED CLJ / EDN / CLJS

Clojure and EDN files use three bracket types: ( ) [ ] { }.
A single missing or extra bracket can silently shift the meaning of an entire file.
The ClojureScript compiler may still succeed even when brackets are wrong,
because the resulting forms are syntactically valid but semantically broken
(e.g., a defn swallowing the next five defns as body forms).

When brackets are suspected to be wrong:

1. Run clj-kondo first:
     clj-kondo --lint path/to/file.cljs
   clj-kondo reports mismatched brackets with line numbers.
   Zero errors means brackets are balanced. Warnings are separate.

2. If clj-kondo reports a mismatch, write a bracket-tracing script.
   Do NOT try to eyeball-count brackets in Clojure. The nesting is too deep.
   Use a script that tracks a stack of (char, line, col) for every opener,
   pops on every closer, and reports the first mismatch or any unclosed openers.
   The script must handle: string literals (skip contents), escaped characters
   inside strings, and ;; line comments (skip to newline).

3. Fix one bracket at a time, re-running the checker after each fix.
   A single bracket fix can reveal a second independent issue elsewhere.
   Common patterns:
   - A Reagent hiccup vector [component arg1 arg2] missing its closing ]
     causes subsequent sibling vectors to become extra arguments.
   - A defn or let missing its closing ) causes subsequent top-level
     forms to nest inside it.
   - Both can coexist: the total bracket count may still be zero-sum
     while individual forms are wrong.

4. After all brackets balance, run clj-kondo again to confirm zero errors,
   then rebuild with shadow-cljs to confirm zero warnings.

# End of rules
