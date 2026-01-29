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
  e: Entity ID. Relative offset from zero basis, sized by stream type (e.g., 16-bit, 64-bit).
     Like Datomic tempids, entity IDs are local until committed by a transactor.
     Reserved range: 0-1024 for system entities (universal, same meaning everywhere).
     User entities start at 1025, which is the "zero basis" for user data.
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

# End of rules
