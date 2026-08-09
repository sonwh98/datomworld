---
description: Moduli space of tuples; meta-protocol over open dimensions; N-dimensional tuples, entity IDs, content addressing, namespaces; datom defined as [e a v t m] locally, [e a v t m ns] when folded across streams
---

# TUPLES AND DATOMS

Tuples are elements in a moduli space, graded by dimension n.
A tuple can be any dimension/size; each dimension dn is a distinct kind of fact, fit for a distinct role.
A datom is the canonical fact tuple: [e a v t m] locally, [e a v t m ns] once a namespace is attached.
The namespace slot is last, so [e a v t m] is a literal prefix of [e a v t m ns]: a stream stores the
short form and a cross-stream fold materializes the sixth slot. Eliding ns is truncation, not a convention.
The moduli space is open: applications declare new dimensions as needed. No dimension is canonical.

Tuples (including datoms) are immutable facts, not objects.
Tuples are the universal format for persistent facts in dao.jing: AST, schema, provenance.
Streams carry whatever values the consumer needs (datoms or other tuples for persistent layers, entities or scalars for ephemeral layers).

Dimensions in use:
  d1: (v). Pure values; identity = hash(v). Blob storage, primitive interning.
  d3: (s, a, v). Bare facts (RDF-style triples). The semantic floor for fact-shaped dimensions.
  d5: (e, a, v, t, m). Provenanced temporal facts, scoped to one stream. The **datom**. Documented in detail below.
  d6: (e, a, v, t, m, ns). A datom carrying its authoring stream's namespace. What a cross-stream fold
      produces; never what a stream stores. d5 is its literal prefix, so every d5 accessor works unchanged.
  Higher / domain-specific: signatures, capabilities, sensor frames, vector clocks, named-graph quads.

Two universal floors:
  d1 (content-addressing floor): every tuple of any dimension reduces to its hash, itself a d1 tuple. Universal addressability.
  d3 (semantic floor): every fact-shaped dimension projects to (subject, attribute, value). Universal interpretability for the fact-shaped subset.

Not every dimension is fact-shaped. Some are tuple-shaped without subject (capabilities, signatures, sensor frames).
For those, d1 is still a floor; d3 is not.

Universal principles (apply to all dimensions):
  Do not embed behavior inside tuples.
  Content hashes are derived by interpreters, not intrinsic to the tuple.
  Interpretation is local: agents decide meaning, not global ontologies.
  Graphs are constructed from tuples, not assumed.
  Restrictions are a feature: dimension choice constrains shape, enabling efficient per-shape indexing.

Intuition (physics metaphor):
  The tuple stream is the unitary wave function: it contains the complete state of the universe.
  Each dimension is a different chart on that universe.
  A tuple at dimension n is a tuple-shaped event in n coordinates.
  A datom (the canonical d5 5-tuple [e a v t m]) is like a space-time event:
  Space (structural): [e a v] defines what exists (entity, attribute, value).
  Time (causal): [t m] defines when and why (transaction, metadata).
  Interpreters observe parts of the stream and construct higher-dimensional structures.
  Like quantum measurement, each interpreter projects the stream differently: same data, different meanings.
  Higher-dimensional tuples (7-tuple, 8-tuple, etc.) can be used for specialized streams (e.g., spatial coordinates, confidence scores, or signatures). The moduli space grows by appending slots, as d6 does.

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

Per-tuple hash:
  hash(tuple) = hash(dimension-hash || canonical-encoded-slots-in-order).
  Different dimensions never collide on the same slot values.
  A d3 fact and a d5 fact (datom) with the same (s, a, v) hash to different values: they are different things in the moduli space.

Morphisms:
  Projections (dn -> dm where m < n): drop slots; lossy.
  Lifts (dm -> dn where n > m): add slots, with defaults from the ingesting segment.
  Each new dn publishes morphisms to/from anchor dimensions (typically d3 for fact-shaped, d1 universally).
  Composite morphisms are computed transitively.

Equivalence:
  Two tuples are equal iff their canonical hashes match within the same dimension.
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

Per-tuple hash (universal):
  hash(tuple) = hash(dimension-hash || canonical-encoded-slots-in-order).

Cross-dimension addressing:
  d1 floor: every tuple reduces to its hash, a d1 tuple. Universal.
  d3 floor: every fact-shaped tuple projects to (s, a, v). Universal for the fact-shaped subset.

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

# d5: DATOMS (PROVENANCED TEMPORAL FACTS)

A datom is the canonical 5-tuple shape [e a v t m] like in Datomic, except the m position is a metadata entity ID. It packages fact + transaction + provenance + entity handle + value in one row, suitable for column-store layouts and EAVT/AEVT indexing. It is the dimension most of dao.jing currently uses.

The remainder of this document defines the datom (d5) in detail: components, sizing, value constraints, reserved entities, namespaces.
Other dimensions (d1, d3, etc.) are documented separately when introduced.

Tuple shape:
  [e a v t m]        within a stream (what a stream stores, what a local query sees)
  [e a v t m ns]     d6, once several streams are folded together

Entity ids are stream-local, so two streams independently assign e 16 to different entities. Merging
their datoms without ns lets a join unify those, fabricating an entity whose attributes came from both.
The ns slot is what keeps them apart; see d5: NAMESPACES below for how it enters a query.

Components (ns is d6-only; the rest are every datom):
  e: Entity ID. Local handle for evolving identity. Relative offset from zero basis.
     Negative IDs are temporary local IDs (tempids), used during compilation and before commitment.
     Positive IDs are permanent IDs assigned by the authoring stream's writer after a successful transaction.
     Reserved range: 0-15 for system entities (always positive) — markers only, see
     Reserved Entities below for why it is this small.
     User entities start at 16 (positive). While local/uncommitted, they are represented as negative counterparts (e.g., -16).
     Entity ID is a local gauge: a coordinate choice for a specific stream, never a
     portable reference. The stream is the namespace — a stream's identity (its
     kickoff hash, see CONTENT ADDRESSING > Streams) is its namespace. The
     gauge-invariant identity is the content hash; e is only a local cache index, so
     different streams assign different e to the same logical fact.
     e is always the bare local offset. It is never widened, never fused with a
     namespace, and never rewritten. When several streams are combined (the fold
     behind a query), the fold appends the ns slot rather than touching e — see the
     ns component below. A writer never stamps: to reference another stream's entity
     it copies the stamped id it observed from such a read, and that lands in v, not
     in e. Migration relocates a stream (its log) to another node; its namespace
     (kickoff hash) and offsets travel with it unchanged, so nothing is re-stamped.
     Semantic identity uses unique attributes (e.g., :person/email). Uniqueness is
     namespace-scoped: :db/unique enforces uniqueness within a stream, and within the
     assigned namespace once stamped. Cross-namespace correlation uses queries.
  a: Attribute. Namespaced keyword.
  v: Value. Inline canonical primitive (<= 32 bytes) or 32-byte content hash.
     Entity references and compound values appear here as hashes.
     See CANONICAL ENCODING for per-type rules.
     v is the one slot that may hold a *foreign* reference. A reference to an entity in
     another stream is written as a stamped id — the two-element vector [namespace offset]
     (namespace 64-bit, offset 64-bit), mirroring IPv6's network-prefix + interface-id
     split, and written "namespace:offset" by that analogy. It fits well under the 32-byte
     inline threshold. A writer never mints one: it copies a stamped id it observed from a
     read. Note this is a value, not a coordinate on the datom itself — the datom's own
     namespace is the ns slot, and the two are independent (a datom in stream A may
     reference an entity in stream B).
  t: Transaction ID. Monotonic integer, intrinsic and stream-local.
  m: Metadata entity reference (always an integer, never language-level nil).
     Establishes causality across streams (since t is local).
     Used for validity (assert/retract), provenance, access control, and cross-stream references.
     This is a strict superset of Datomic's 5th slot: where Datomic stores a boolean
     `added` (assert vs retract), d5 stores an entity reference whose low reserved ids
     mirror that boolean (0 = retract, 1 = assert) and whose high ids (16+) point at
     reified metadata entities carrying :db/op plus provenance as their own datoms.
     The single source of truth for the reserved ids is dao.datom/reserved; code must
     never compare m against a bare integer literal.
     m is always stream-local, like e. Cross-stream causality is established *through*
     m, not by m being foreign: m names a local reified metadata entity whose own
     datoms carry stamped cross-stream references in their v slots. Every entity-position
     slot (e, m) is stream-local; v is the only slot that may hold a foreign reference.
  ns: Namespace (d6 only). The authoring stream's identity — its kickoff hash, see
     CONTENT ADDRESSING > Streams. Globally meaningful with no coordination: two
     instances that have never communicated agree on what a namespace denotes.
     Present only in a cross-stream fold. A stream stores [e a v t m]; segments are
     per-stream, so where a namespace must be recorded at rest it belongs to the
     segment, not repeated per datom. Absence means "this stream," and because ns is
     the last slot, absence is plain truncation — a 5-tuple is a 6-tuple with ns
     elided, the same value, not a different encoding.
     ns is a coordinate, not an identity claim. It keeps two streams' offsets from
     colliding; it does not assert that two entities are the same thing. Correlating
     entities across streams goes through unique attributes or a derived content hash,
     never through e.
     Sort position: ns is the last tiebreaker in every index order, never a leading
     component. The indexes are sets, so without it two datoms agreeing on [e a v t m]
     compare equal and one is silently dropped. Trailing, it leaves 5-tuple ordering
     identical. Slot order is not index order — a fold wanting per-stream locality
     builds its own comparator.

Sizing:
  Datoms can be variable-size (general case) or fixed-size (typed streams).
  A stream can declare a type that constrains the size of each slot.
  Fixed-size streams enable: cache-efficient layouts, SIMD operations, O(1) indexing.
  Variable-size streams provide flexibility at the cost of offset-table overhead.
  Example typed stream: {:e :int64, :a :keyword, :v :hash, :t :int64, :m :int64}
  Folded (d6) adds {:ns :hash}. Within a segment ns is constant, so it costs one
  run-length run, not a value per datom.

Value Constraints:
  v is always small: an inline canonical primitive or a fixed-size content hash.
  Compound values and large blobs are referenced by hash; underlying bytes live in a content-addressed store, fetched lazily.
  Stream payloads remain compact regardless of underlying value size.

Reserved Entities (0-15):
  System entities with universal meaning across all namespaces. This is the one place a
  numeric id is global rather than a local gauge, so it is kept as small as possible.
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
  Entities 3-15: unallocated, held for further validity/provenance markers.
  These do not migrate: they have the same meaning everywhere.
  User entities, including all schema, live at 16+ and migrate with data.

  Why only markers are reserved. Built-in attributes (:db/ident, :db/valueType,
  :db/cardinality), value-type markers, and cardinality/uniqueness values are not in this
  range: they occupy the a and v slots, where they are already namespaced keywords and so
  globally meaningful without any number. A schema entity appearing in e position takes an
  ordinary stream-local id like anything else and is named across streams by its :db/ident.
  Only the m slot needs fixed numbers, because m is an integer read on every validity fold
  and a keyword lookup per datom would be a real cost. Reserving more than the markers would
  put global coordinates in a slot the spec declares a local gauge (see e, above) — the size
  is not the objection, the kind is.

  What the small range buys. The reserved ceiling sets the floor for e's width. At 0-1024 a
  signed int8 stream is arithmetically impossible; at 0-15 it holds 112 user entities
  (16-127) with tempids in -16..-128, and an int16 stream holds ~32.7k. See Sizing.

Validity fold (deferred):
  Assert/retract is resolved at the index layer by folding m, exactly as Datomic folds
  `added`: storage stays append-only and immutable; "current vs history" is an
  interpretation. The fold itself is not yet implemented. When it lands, any datom that
  predates this convention and carries the old m=0 ("nil metadata") meaning must be
  rewritten m:0->1 first, or it will read as a retraction. (There is currently no such
  data: AST datoms are regenerated at runtime via ast->datoms.)

Datom-specific principles (d5):
  Content hashes for datoms are computed over [a v] pairs only (not e, t, or m).
  Content hash input is the assert(1) datoms; retract(0) and derived(2) are excluded.
  Content hash datoms themselves use m=2 (:db/derived) and so are excluded from their
  own computation.
  No arbitrary URIs and no variables in storage, enabling efficient EAVT/AEVT indexing.

Merkle property (d5):
  Entity hash = hash(sorted (a, encode(v)) byte pairs).
  In storage a ref's v is the bare stream-local offset (gauge-dependent); the hasher
  first resolves it to the referent's content hash, so the hash stays gauge-invariant.
  With that resolution v is a content hash
  whenever it is a ref or compound value (see CANONICAL ENCODING).
  Hashing never crosses a stream boundary. Resolution applies only when the referent
  is in the hashing context (the same content-addressed unit's hash-cache).
  A reference whose referent is outside that
  unit — including an already-stamped cross-stream [namespace offset] — is hashed as
  its literal value, never by reading another stream. So content hashes are
  gauge-invariant within a content-addressed unit (AST), while a cross-unit reference
  is pinned by the stamped id rather than the referent's content hash. This is
  consistent with content addressing applying to self-contained persisted AST units,
  not to ephemeral cross-stream coordination state.
  No recursion at the entity layer; recursion lives at the value layer.
  Same structure -> same root hash, regardless of entity ID assignment.

Content hash would be asserted as a derived datom (m=2, :db/derived).
  Status: specified, not implemented, and no attribute is reserved for it.
  Nothing computes entity-level Merkle hashes today; dao.jing/content-hash
  addresses opaque blobs at the storage boundary, which is a different level
  (docs/design/dao.jing.md, Encoding).

Variable names are included in the content hash.
  (lambda [x] x) and (lambda [y] y) produce different hashes.
  Alpha-equivalence can be added later as its own derived datom, with a
  De Bruijn normalization step before hashing.
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

Each stream is a namespace. A stream's identity (its kickoff hash) names its namespace, so the bare offsets a stream stores are scoped by it. Folding several streams (e.g. a DaoSpace query) appends the ns slot to each datom, so stream-local offsets never collide.

Local queries are d5: ns is constant across a single stream, so dropping it loses nothing and
a local query is exactly a Datomic query — [e a v t m], e a plain integer, four indexes.
Cross-stream queries are d6. Clauses are positional prefix patterns, so ns is reached with
wildcards for the slots between, the same shape Datomic uses to reach `added`:

  ; scoped — a repeated ?ns keeps the join inside one stream
  [:find ?ns ?e ?name
   :where [?e :person/email "alice@example.com" _ _ ?ns]
          [?e :person/name ?name _ _ ?ns]]

  ; deliberate crossing — distinct ?ns, joined on a shared value
  [:find ?ns1 ?e1 ?ns2 ?e2
   :where [?e1 :person/email ?email _ _ ?ns1]
          [?e2 :person/email ?email _ _ ?ns2]
          [(not= ?ns1 ?ns2)]]

  Joins across namespaces happen on shared values, not entity IDs.
  Cross-namespace identity correlation is a query-time concern, not storage-time.

Because clauses are prefix patterns, an unqualified clause does not fail against a folded
relation — it leaves ns unconstrained and unifies e across streams, which is the collision
the slot exists to prevent. The safety property is therefore a stated rule, not an accident
of arity:

  Against a multi-source relation, every clause binding an entity variable must also
  bind its namespace.

Likewise a result: any e appearing in :find must carry its ns, inside aggregates too, or it
is a gauge-dependent integer meaning nothing outside its stream. (count ?e) is ill-formed
across streams; (count ?h) over a derived identity is well-formed.

A source is arity-homogeneous: every datom in one relation carries ns, or none does. A clause
slot past a datom's own arity is a non-match, never a nil binding — binding nil is what would
let two streams' entity ids unify. That rule is right for a uniform source and silently wrong
for a mixed one, where a namespaced negation ignores un-namespaced datoms: (not [?e :claim _ _ _ ?ns])
finds no claim for an entity whose claim datom is a 5-tuple, and the negation passes. Folds
produce uniform relations by construction; nothing enforces it for a hand-built source.

  Status. Three separate gaps, none of them blocking the others:
    1. Stamping. dao.space.query folds an explicit pool of root sources without attaching a
       namespace, because a stream's kickoff hash is not yet derived (see
       docs/design/dao.jing.md, "Namespace stamping"). Until it lands, multi-root folds
       carry the collision described above.
    2. The per-clause rule above is stated, not enforced. Nothing in dao.space.query rejects
       an unqualified clause over a multi-source relation; it simply unifies across streams,
       exactly as it did before the slot existed. Independent of (1): stamping could land and
       this would still be unchecked.
    3. Arity homogeneity is assumed, not validated.
  What is built: the query engine matches and unifies the ns slot (dao.space.query), and the
  index comparators order by it as a trailing tiebreaker (dao.space.index).
