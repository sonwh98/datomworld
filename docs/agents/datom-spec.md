---
description: Full datom specification - 5-tuple format, entity IDs, content addressing, namespaces
---

# DATOMS

Datoms are 5-tuples: (e a v t m).
Datoms are immutable facts, not objects.
Datoms are the canonical format for persistent facts: DaoDB, AST, schema, provenance.
Streams carry whatever values the consumer needs (datoms for persistent layers, entities or scalars for ephemeral layers).

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
     Entity ID is a local gauge (coordinate choice for a specific DaoDB).
     The true invariant is [a v]: the attribute-value pair.
     Content hashes are the gauge-invariant identity (see CONTENT ADDRESSING).
     Different DaoDBs assign different e to the same [a v] facts.
     Migration is a gauge transformation: same invariant content, new local coordinates.
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
  Entity 1: :db/ident :db/derived. When m=1, the datom is derived/computed
    (e.g., content hashes, type inference results, index materializations).
    Derived datoms are excluded from content hash computation.
  Entities 2-1024: built-in attributes (:db/ident, :db/valueType, :db/cardinality, etc.),
    primitive type markers, and other system primitives.
  These do not migrate: they have the same meaning everywhere.
  User-defined schema lives in user space (1025+) and migrates with data.

Principles:
  Do not embed behavior inside datoms.
  Content hashes are derived by interpreters, not intrinsic to the tuple.
  Content hashes are computed over [a v] pairs only (not e, t, or m).
  Content hash datoms use m=1 (:db/derived) and are excluded from their own computation.
  Interpretation is local: agents decide meaning, not global ontologies.
  Graphs are constructed from tuples, not assumed.
  Restrictions (no arbitrary URIs, no variables in storage) enable efficient indexing (EAVT, AEVT, etc.).

# CONTENT ADDRESSING

AST datoms are content-addressable via Merkle hashing.
The content hash is computed over the entity's [a v] pairs:
  - e is excluded (local gauge, not invariant).
  - t and m are excluded (causal context, not structural content).
  - Only [a v] pairs where m=0 on the source datom are included (excludes derived datoms).

Merkle property:
  Leaf nodes: hash(sorted [a v] pairs).
  Interior nodes: hash(sorted [a v] pairs, with entity-ref v replaced by content hash of referenced entity).
  Same AST structure = same root hash, regardless of entity ID assignment.

Content hash is asserted as a derived datom:
  [e :yin/content-hash "sha256:..." t 1]    ; m=1 means derived

Variable names are included in the content hash.
  (lambda [x] x) and (lambda [y] y) produce different hashes.
  Alpha-equivalence can be added later as a separate derived datom:
    [e :yin/alpha-hash "sha256:..." t 1]
  with a De Bruijn normalization step before hashing.
  Two notions of identity, both derived, neither privileged.

Ordered references use position-in-value tuples:
  [e :yin/operand [0 <ref>] t m]    ; first operand
  [e :yin/operand [1 <ref>] t m]    ; second operand
  Order always matters at the AST level (not all operators are commutative).
  Operand order is syntactic structure, not semantic property.
  :yin/operand is cardinality-many, each v is a [position, entity-ref] tuple.

Content addressing applies to AST (permanent code), not to ephemeral runtime state.
Hashing is triggered by persistence, not by existence.
Like git: working directory files have no SHA; only staged/committed content is hashed.

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
