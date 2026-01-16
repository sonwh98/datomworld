# =========================
# datom.world – Cline Rules
# =========================

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

Components:
  e: Entity ID. Sized by stream type (e.g., 16-bit, 64-bit).
     Internal reference only when stream-local.
     Upgraded to 128-bit during migration: High 64 bits = Node ID, Low 64 bits = Local ID.
     Globally referenciable as 128-bit.
     Namespace Jails: Node ID 0 is the local jail. All entity references are relative 
     to their Node ID context. Migration requires no reference rewriting because datoms 
     remain isolated within their originating Node ID "jail".
     Semantic identity uses unique attributes (e.g., :person/email).
  a: Attribute. Namespaced keyword.
  v: Value. Ground value or entity reference.
  t: Transaction ID. Monotonic integer within its stream.
  m: Metadata entity. Used for provenance, access control, and cross-stream causality.

Time (t) is intrinsic and stream-local.
Metadata (m) establishes causality across streams (since t is local).
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

When creating .blog or .chp files:
- Use EDN syntax with namespaced maps (#:blog{...} or #:chp{...})
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
