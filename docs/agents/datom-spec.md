---
description: Datom moduli space; meta-protocol over open dimensions; N-dimensional tuples, entity IDs, content addressing, namespaces; d5 documented as the original 5-tuple instance
---

# DATOMS

Datoms are tuples in a moduli space, graded by dimension n.
The canonical format for persistent facts is the 5-tuple: (e a v t m).
Each dimension dn is a distinct kind of fact, fit for a distinct role.
The moduli space is open: applications declare new dimensions as needed. No dimension is canonical.

Datoms are immutable facts, not objects.
Datoms are the universal format for persistent facts in dao.jing: AST, schema, provenance.
Streams carry whatever values the consumer needs (datoms for persistent layers, entities or scalars for ephemeral layers).

Dimensions in use:
  d1: (v). Pure values; identity = hash(v). Blob storage, primitive interning.
  d3: (s, a, v). Bare facts (RDF-style triples). The semantic floor for fact-shaped dimensions.
  d5: (e, a, v, t, m). Provenanced temporal facts. The original 5-tuple. Documented in detail below.
  Higher / domain-specific: signatures, capabilities, sensor frames, vector clocks, named-graph quads.

Two universal floors:
  d1 (content-addressing floor): every datom of any dimension reduces to its hash, itself a d1 datom. Universal addressability.
  d3 (semantic floor): every fact-shaped dimension projects to (subject, attribute, value). Universal interpretability for the fact-shaped subset.

Not every dimension is fact-shaped. Some are tuple-shaped without subject (capabilities, signatures, sensor frames).
For those, d1 is still a floor; d3 is not.

Universal principles (apply to all dimensions):
  Do not embed behavior inside datoms.
  Content hashes are derived by interpreters, not intrinsic to the tuple.
  Interpretation is local: agents decide meaning, not global ontologies.
  Graphs are constructed from tuples, not assumed.
  Restrictions are a feature: dimension choice constrains shape, enabling efficient per-shape indexing.

Intuition (physics metaphor):
  The datom stream is the unitary wave function: it contains the complete state of the universe.
  Each dimension is a different chart on that universe.
  A datom at dimension n is a tuple-shaped event in n coordinates.
  A canonical datom [e a v t m] is like a space-time event:
  Space (structural): [e a v] defines what exists (entity, attribute, value).
  Time (causal): [t m] defines when and why (transaction, metadata).
  Interpreters observe parts of the stream and construct higher-dimensional structures.
  Like quantum measurement, each interpreter projects the stream differently: same data, different meanings.
  Higher-dimensional datoms (6-tuple, 7-tuple, etc.) can be used for specialized streams (e.g., spatial coordinates, confidence scores, or parallel transport context).

# META-PROTOCOL

The meta-protocol is the only universal piece. The vocabulary of dimensions is open.

A dimension dn is declared as a small d3 subgraph:
  :dim/arity         n (the slot count).
  :dim/slots         ordered list of (slot-name, slot-type, slot-role).
  :dim/encoding      canonical-encoding rule (parameterized by slot types).
  :dim/projection-to declared morphism dn -> dm (m < n).
  :dim/lift-from     declared morphism dm -> dn (with default-supplying function or pointer).

The content hash of this subgraph IS the dimension's identity.
New dimensions are introduced by publishing such a bundle. No external schema authority is needed.

Per-datom hash:
  hash(datom) = hash(dimension-hash || canonical-encoded-slots-in-order).
  Different dimensions never collide on the same slot values.
  A d3 fact and a d5 fact with the same (s, a, v) hash to different values: they are different things in the moduli space.

Morphisms:
  Projections (dn -> dm where m < n): drop slots; lossy.
  Lifts (dm -> dn where n > m): add slots, with defaults from the ingesting segment.
  Each new dn publishes morphisms to/from anchor dimensions (typically d3 for fact-shaped, d1 universally).
  Composite morphisms are computed transitively.

Equivalence:
  Two datoms are equal iff their canonical hashes match within the same dimension.
  Cross-dimensional equality goes through projection: a in dn matches b in dm iff pi(a) = b.

# CANONICAL ENCODING

Every slot type has a canonical encoding rule that all implementations must conform to.
The encoding is what makes hashes byte-identical across languages and platforms.

Per-type rules:
  Integers: little-endian fixed-width (int8, int16, int32, int64).
  Floats:   IEEE 754; NaN normalized; +0 and -0 distinct or unified per declaration; denormals as-is.
  Strings:  UTF-8 NFC, length-prefixed.
  Keywords: namespaced; canonical UTF-8 NFC bytes; namespace and name separated by a fixed delimiter.
  Booleans: 1 byte (0x00 = false, 0x01 = true).
  Bytes:    raw, length-prefixed.
  Hashes:   32-byte SHA-256.

Inline-or-hash threshold:
  encode(v) = canonical(v)        if |canonical(v)| <= hash-size (32 bytes).
  encode(v) = hash(canonical(v))  otherwise.

Compound values (maps, lists, records, large blobs) appear as hashes; underlying bytes live in a content-addressed store, fetched lazily.
Primitives inline.
A slot value is always small: an inline canonical primitive or a fixed-size content hash.

# CONTENT ADDRESSING

Every dimension carries content-addressing for free.

Per-datom hash (universal):
  hash(datom) = hash(dimension-hash || canonical-encoded-slots-in-order).

Cross-dimension addressing:
  d1 floor: every datom reduces to its hash, a d1 datom. Universal.
  d3 floor: every fact-shaped datom projects to (s, a, v). Universal for the fact-shaped subset.

Cycles:
  Pure content addressing forbids value-level cycles (the hash would have to be known to compute itself).
  Cycles live at the entity layer (in dimensions like d5 where e is a stable local handle): two entities can reference each other via attributes whose v is the other's content hash.
  Values stay acyclic. Entities can cycle.

Streams:
  Streams are temporal and unbounded; their content cannot be hashed in finite time.
  A stream is identified by the hash of its kickoff metadata (creator, schema, dimension, t-zero), not by its content.
  Values are atemporal. Streams are temporal. They are different kinds of thing.

Hashing is triggered by persistence, not by existence.
Like git: working directory has no SHA; only committed content is hashed.

# d5: PROVENANCED TEMPORAL FACTS

d5 is the original datom shape: the 5-tuple (e a v t m). It packages fact + transaction + provenance + entity handle + value in one row, suitable for column-store layouts and EAVT/AEVT indexing. It is the dimension most of dao.jing currently uses.

The remainder of this document defines d5 in detail: components, sizing, value constraints, reserved entities, namespaces.
Other dimensions (d1, d3, etc.) are documented separately when introduced.

Tuple shape:
  (e a v t m)

Components (for canonical 5-tuples):
  e: Entity ID. Local handle for evolving identity. Relative offset from zero basis.
     Negative IDs are temporary local IDs (tempids), used during compilation and before commitment.
     Positive IDs are permanent IDs assigned by the authoring stream's writer after a successful transaction.
     Reserved range: 0-1024 for system entities (always positive).
     User entities start at 1025 (positive). While local/uncommitted, they are represented as negative counterparts (e.g., -1025).
     Entity ID is a local gauge: a coordinate choice for a specific stream, never a
     portable reference. The stream is the namespace — a stream's identity (its
     kickoff hash, see CONTENT ADDRESSING > Streams) is its namespace. The
     gauge-invariant identity is the content hash; e is only a local cache index, so
     different streams assign different e to the same logical fact.
     Global form: a 128-bit two-element vector [namespace offset] (namespace 64-bit,
     offset 64-bit), mirroring IPv6's network-prefix + interface-id split. (Written
     "namespace:offset" by analogy to IPv6; that shorthand denotes the vector, not
     EDN keyword syntax.) The namespace
     value is the authoring stream's identity (derived from its kickoff hash):
     globally unique and self-describing, so the same entity carries the same
     stamped id in every reader's frame and cross-stream value-joins by equality
     hold. It addresses streams, not nodes — a node hosts many. The bare-offset
     form, with the namespace elided to 0 ("this stream"), is the intra-stream
     storage compaction of that global form: inside its authoring stream a stream's
     own namespace is implied, so only the offset is stored. "Relative to self" is
     an encoding convention, not the nature of the namespace field.
     Stamp on crossing: within its authoring stream an e is the bare offset. When
     datoms from several streams are combined (the fold behind a query), the fold
     stamps each e to [namespace offset] using its authoring stream's namespace. A
     writer never stamps: to reference another stream's entity it copies the stamped
     id it observed from such a read. Bare offsets exist only inside their authoring
     stream's own log; an e appearing as a value in another stream is always stamped.
     Migration relocates a stream (its log) to another node; its namespace (kickoff
     hash) and offsets travel with it unchanged, so nothing is re-stamped. The only
     crossing of the namespace boundary is a cross-stream reference, resolved by the
     fold above, not by migration.
     Semantic identity uses unique attributes (e.g., :person/email). Uniqueness is
     namespace-scoped: :db/unique enforces uniqueness within a stream, and within the
     assigned namespace once stamped. Cross-namespace correlation uses queries.
  a: Attribute. Namespaced keyword.
  v: Value. Inline canonical primitive (<= 32 bytes) or 32-byte content hash.
     Entity references and compound values appear here as hashes.
     See CANONICAL ENCODING for per-type rules.
  t: Transaction ID. Monotonic integer, intrinsic and stream-local.
  m: Metadata entity reference (always an integer, never language-level nil).
     Establishes causality across streams (since t is local).
     Used for validity (assert/retract), provenance, access control, and cross-stream references.
     This is a strict superset of Datomic's 5th slot: where Datomic stores a boolean
     `added` (assert vs retract), d5 stores an entity reference whose low reserved ids
     mirror that boolean (0 = retract, 1 = assert) and whose high ids (1025+) point at
     reified metadata entities carrying :db/op plus provenance as their own datoms.
     The single source of truth for the reserved ids is dao.datom/reserved; code must
     never compare m against a bare integer literal.

Sizing:
  d5 datoms can be variable-size (general case) or fixed-size (typed streams).
  A stream can declare a type that constrains the size of each slot.
  Fixed-size streams enable: cache-efficient layouts, SIMD operations, O(1) indexing.
  Variable-size streams provide flexibility at the cost of offset-table overhead.
  Example typed stream: {:e :int64, :a :keyword, :v :hash, :t :int64, :m :int64}

Value Constraints:
  v is always small: an inline canonical primitive or a fixed-size content hash.
  Compound values and large blobs are referenced by hash; underlying bytes live in a content-addressed store, fetched lazily.
  Stream payloads remain compact regardless of underlying value size.

Reserved Entities (0-1024):
  System entities with universal meaning across all namespaces.
  The validity ids (0,1) mirror Datomic's `added` boolean so any Datomic datom maps
  to a d5 datom by identity; the remaining ids extend beyond what Datomic can express.
  Entity 0: :db/retract. When m=0, the datom is a retraction (Datomic added=false).
    Written explicitly only; never the emit default, so a zeroed/uninitialized slot
    can never silently read as a deletion.
  Entity 1: :db/assert. When m=1, the datom is an assertion (Datomic added=true).
    This is the emit default for produced datoms (see dao.datom/default-op).
  Entity 2: :db/derived. When m=2, the datom is derived/computed
    (e.g., content hashes, type inference results, index materializations).
    Derived datoms are excluded from content hash computation.
  Entities 3-1024: built-in attributes (:db/ident, :db/valueType, :db/cardinality, etc.),
    primitive type markers, and other system primitives.
  These do not migrate: they have the same meaning everywhere.
  User-defined schema lives in user space (1025+) and migrates with data.

Validity fold (deferred):
  Assert/retract is resolved at the index layer by folding m, exactly as Datomic folds
  `added`: storage stays append-only and immutable; "current vs history" is an
  interpretation. The fold itself is not yet implemented. When it lands, any datom that
  predates this convention and carries the old m=0 ("nil metadata") meaning must be
  rewritten m:0->1 first, or it will read as a retraction. (There is currently no such
  data: AST datoms are regenerated at runtime via ast->datoms.)

d5-specific principles:
  Content hashes for d5 are computed over [a v] pairs only (not e, t, or m).
  Content hash input is the assert(1) datoms; retract(0) and derived(2) are excluded.
  Content hash datoms themselves use m=2 (:db/derived) and so are excluded from their
  own computation.
  No arbitrary URIs and no variables in storage, enabling efficient EAVT/AEVT indexing.

Merkle property (d5):
  Entity hash = hash(sorted (a, encode(v)) byte pairs).
  In storage a ref's v is the bare stream-local offset (gauge-dependent); the hasher
  first resolves it to the referent's content hash, so the hash stays gauge-invariant
  (yin.content/resolve-value does this). With that resolution v is a content hash
  whenever it is a ref or compound value (see CANONICAL ENCODING).
  Hashing never crosses a stream boundary. Resolution applies only when the referent
  is in the hashing context (the same content-addressed unit's hash-cache; see
  yin.content/compute-content-hashes). A reference whose referent is outside that
  unit — including an already-stamped cross-stream [namespace offset] — is hashed as
  its literal value, never by reading another stream. So content hashes are
  gauge-invariant within a content-addressed unit (AST), while a cross-unit reference
  is pinned by the stamped id rather than the referent's content hash. This is
  consistent with content addressing applying to self-contained persisted AST units,
  not to ephemeral cross-stream coordination state.
  No recursion at the entity layer; recursion lives at the value layer.
  Same structure -> same root hash, regardless of entity ID assignment.

Content hash is asserted as a derived datom:
  [e :yin/content-hash "sha256:..." t 2]    ; m=2 means :db/derived

Variable names are included in the content hash.
  (lambda [x] x) and (lambda [y] y) produce different hashes.
  Alpha-equivalence can be added later as a separate derived datom:
    [e :yin/alpha-hash "sha256:..." t 2]
  with a De Bruijn normalization step before hashing.
  Two notions of identity, both derived, neither privileged.

Ordered references use position-in-value tuples:
  [e :yin/operand [0 <ref>] t m]    ; first operand
  [e :yin/operand [1 <ref>] t m]    ; second operand
  Order always matters at the AST level (not all operators are commutative).
  Operand order is syntactic structure, not semantic property.
  :yin/operand is cardinality-many, each v is a [position, entity-ref] tuple.

Content addressing applies to AST (permanent code), not to ephemeral runtime state.

# d5: NAMESPACES

Namespaces scope entity IDs and uniqueness constraints. This is a d5-specific concern (d3 has no e; the question does not arise there).

Each stream is a namespace. A stream's identity (its kickoff hash) names its namespace, so the bare offsets a stream stores are scoped by it. Folding several streams (e.g. a DaoSpace query) stamps each datom with its stream's namespace before indexing, so stream-local offsets never collide.

Cross-Namespace Queries:
  Namespaces are treated as separate databases in queries (explicit inputs).
  Like Datomic's multiple database pattern:
    [:find ?e1 ?e2
     :in $local $remote
     :where [$local ?e1 :person/email ?email]
            [$remote ?e2 :person/email ?email]]
  Joins across namespaces happen on shared values, not entity IDs.
  Cross-namespace identity correlation is a query-time concern, not storage-time.
