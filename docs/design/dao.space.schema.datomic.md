# dao.space.datomic — A Datomic-Compatible Schema Interpreter over dao.space

Status: proposal. This document describes what would be required to offer a
Datomic-compatible schema and transaction boundary over dao.space. It does not
change the contract of `dao.space.schema`, which remains the existing optional,
Datomic-inspired schema dialect.

Compatibility here means observable behavior at the transaction and database
value boundaries. It does not mean physical file compatibility with Datomic.
dao.space persists canonical d5 tuples `[e a v t m]`, where `m` is a metadata
entity reference; Datomic exposes `[e a v tx added?]`, where the fifth component
is a boolean. A compatibility interpreter must translate between those models.

Before implementation, pin an exact Datomic product and version as the
conformance target. Datomic Cloud and Datomic Pro differ in areas such as AVET
index membership, deployment, and some APIs. Unqualified claims of "Datomic
compatible" are not testable.

## Related documents

- `docs/design/dao.space.schema.md` — the existing open schema dialect
- `docs/design/dao.space.md` — the tuple-space storage boundary
- `docs/design/dao.space.query.md` — query db-values and source polymorphism
- `docs/design/dao.space.index.md` — covered index representation
- `docs/agents/datom-spec.md` — canonical d5 tuples and metadata references
- `docs/agents/architecture.md` — agents and DaoStream effect boundaries
- `docs/design/adr/0002-share-governed-computation-not-data.md` — capability
  boundaries for governed writes
- `docs/datomic.md` — local Datomic architecture notes

Primary Datomic references:

- [Datomic overview](https://docs.datomic.com/datomic-overview.html)
- [Schema data reference](https://docs.datomic.com/schema/schema-reference.html)
- [Changing schema](https://docs.datomic.com/schema/schema-change.html)
- [Transaction data](https://docs.datomic.com/transactions/transaction-data-reference.html)
- [Transaction model](https://docs.datomic.com/transactions/model.html)
- [Transaction functions](https://docs.datomic.com/transactions/transaction-functions.html)

## 1. Decision: a separate interpreter

Do not turn `dao.space.schema` into this design.

The existing interpreter intentionally permits unschematized attributes,
offers lax and strict modes, time-travels schema with the data, represents
uniqueness as a boolean, rejects some card-one list-form updates, and adds an
assertion-time ref-existence rule. Those are coherent decisions for an open
tuple space, but they are not Datomic semantics.

The compatible boundary should therefore be a separate namespace and stream
interpreter, provisionally:

```clojure
dao.space.datomic
```

Both interpreters may consume the same physical d5 machinery, but they make
different promises. Naming the boundary separately prevents a compatibility
mode from weakening the existing dialect or silently changing stored meaning.

## 2. Compatibility levels

Compatibility must be stated as a finite contract. Three levels are useful.

### 2.1 Core schema compatibility

The minimum credible target:

- mandatory attribute declaration before use;
- Datomic value-type and cardinality semantics;
- `:db.unique/identity` and `:db.unique/value`;
- tempids, idents, lookup refs, and identity upsert;
- declarative, order-independent transaction planning;
- card-one implicit retraction;
- schema-change validation against current data;
- Datomic current/history behavior under the current schema;
- transaction atomicity through one authoritative writer;
- cardinality-aware entity and pull results.

### 2.2 Extended schema compatibility

Adds:

- `:db/isComponent` and recursive `:db/retractEntity`;
- `:db/noHistory` at the observable database-value boundary;
- `:db/index` and the target edition's AVET rules;
- tuple attributes and tuple derivation;
- attribute predicates;
- transaction entities and `:db/txInstant`.

### 2.3 Full declared compatibility

Adds every feature named by the pinned target, including fulltext, entity
specs, `:db/ensure`, built-in and user transaction functions, all value types,
and target-specific API behavior. Full compatibility is likely JVM-first
because several Datomic values and predicate/function facilities are native to
the JVM.

The first implementation should claim only Core schema compatibility.

## 3. The authority boundary: exactly one transactor

Datomic schema enforcement follows from one fact: every transaction is
serialized by one authoritative transactor against the latest database value.
A per-wrapper lock is insufficient because two wrappers over one stream can
both validate stale state, allocate the same logical time, or accept conflicting
unique values.

The dao.space realization must make the authority an explicit stream topology:

```text
client transaction streams
           |
           v
  one Datomic transactor agent
  - reads current db-before
  - plans the entire transaction
  - validates schema and uniqueness
  - allocates tempids, t, and metadata
  - emits one accepted record or one rejection
           |
           v
      canonical d5 log
           |
           v
 result and novelty streams
```

The transactor is an agent consuming transaction-request tuples and emitting
result tuples plus durable d5 records. No callback or hidden singleton is
needed. Its authority comes from a write capability: for a database claiming
compatibility, no other writer may append to the governed log.

Required invariants:

1. Exactly one live writer capability exists per compatible database.
2. Every accepted transaction is evaluated against the immediately preceding
   committed database value.
3. Validation and transaction expansion finish before durable append.
4. One append publishes either the complete transaction or nothing.
5. A rejected transaction consumes neither `t` nor entity ids.
6. Restart reconstructs the same transactor state from durable history and
   persisted allocation metadata.

Multi-stream federation remains a dao.space query feature, not one Datomic
database. Cross-stream uniqueness cannot be called Datomic-compatible without
a single authority spanning those streams.

## 4. The datom adapter

The physical tuple remains:

```clojure
[e a v t m]
```

The compatibility db-value exposes the Datomic interpretation:

```clojure
[e a v tx added?]
```

The adapter rules must be explicit:

- `e`, `a`, and `v` retain their database-local meaning.
- `t` must name or deterministically map to the Datomic transaction entity
  exposed as `tx`.
- reserved assertion and retraction metadata markers map to `added?` true and
  false.
- when `m` names a reified metadata entity, the interpreter resolves its live
  `:db/op` rather than treating every nonzero `m` as assertion.
- provenance carried through `m` remains queryable as an extension, but it
  cannot change Datomic-visible assertion semantics.
- conflicting or structurally incomplete metadata operations reject database
  construction rather than receiving an arrival-order interpretation.

This metadata-op interpreter is a prerequisite. Preserving `m >= 16` rows as
live without resolving their `:db/op` is sufficient for the current dialect,
not for a compatibility claim.

## 5. System schema and attribute declarations

The compatible database starts with a queryable system schema. Attribute
definitions are ordinary data but their meaning is fixed by the interpreter.

Core schema attributes include at least:

```clojure
:db/ident
:db/valueType
:db/cardinality
:db/unique
:db/index
:db/doc
```

Extended levels add the system attributes required by the target, including
component, no-history, fulltext, tuple, predicate, and entity-spec properties.

Unlike `dao.space.schema`, Core compatibility is closed at the attribute
boundary:

- a data attribute must be installed before a data transaction can use it;
- its required schema properties must be present and legal;
- an undeclared attribute is a transaction error;
- an attribute with no cardinality does not silently become card-many;
- an attribute with no value type does not become untyped pass-through;
- the `:db` and `:db.*` namespaces follow the target's reservation rules;
- schema entities may carry ordinary user annotations where Datomic permits
  them.

The interpreter must distinguish an attribute entity from an arbitrary entity
that merely carries one schema-looking property. Partial declarations that do
not denote an installable attribute either remain inert ordinary data or reject
according to the pinned target; they must not be accepted as half-active
schema.

## 6. Value types

Core compatibility must pin exact host representations and comparison rules
for every claimed Datomic type. The target schema grammar includes more than
the current string, keyword, boolean, long, double, and ref subset.

Likely types include:

```clojure
:db.type/string
:db.type/boolean
:db.type/keyword
:db.type/symbol
:db.type/long
:db.type/bigint
:db.type/float
:db.type/double
:db.type/bigdec
:db.type/instant
:db.type/uuid
:db.type/uri
:db.type/bytes
:db.type/ref
:db.type/tuple
```

For each supported type, specify:

- accepted transaction input representations;
- canonical d5 encoding;
- equality and total comparison;
- query literal representation;
- transit/serialization behavior;
- behavior on Clojure, ClojureScript, and ClojureDart.

If native parity cannot be made exact, begin with a JVM compatibility target.
Do not claim a type on a host whose equality, precision, range, or ordering is
observably different.

## 7. Uniqueness and identity

Boolean `:db/unique` is not compatible. The schema value must distinguish:

```clojure
:db.unique/identity
:db.unique/value
```

Both require card-one and enforce one current entity per value. Identity
uniqueness additionally participates in upsert.

The transactor must plan uniqueness over the complete transaction and current
database value:

1. Resolve all explicit entity identifiers and tempids.
2. Collect every unique assertion in the transaction.
3. Resolve identity assertions against current AVET state.
4. Unify tempids that name the same existing identity.
5. Detect incompatible identity assertions that would unify one proposed
   entity with multiple existing entities.
6. Validate unique-value assertions without upserting.
7. Apply retractions and assertions as one declarative set.

Ambiguity is not retained as lax data in a compatible database. It rejects the
transaction. The set-valued claimant index remains useful internally for
detecting corrupt imported history, but a governed writer never commits more
than one current claimant.

## 8. Transaction data and whole-transaction planning

Compatibility requires Datomic transaction data, not merely similar vector
shapes. Core forms include:

- entity maps;
- `[:db/add e a v]`;
- `[:db/retract e a v]` and supported convenience retractions;
- tempids;
- entity ids, idents, and lookup refs in entity positions;
- nested entity maps where the schema permits them;
- the current transaction entity.

Planning must be declarative. Input vector order and host map iteration order
must not change the accepted datoms. The current sequential same-record lookup
rule therefore cannot be used in compatibility mode.

A suitable pipeline is:

```text
tx-data
  -> parse all forms
  -> collect symbolic entities and identity claims
  -> solve tempid/upsert equivalence classes
  -> expand maps, reverse references, and transaction functions
  -> calculate db-after candidate
  -> validate schema and transaction invariants
  -> eliminate redundant datoms
  -> canonicalize emitted datoms
  -> append one record
```

This pipeline is a pure interpretation until the final append. Its intermediate
forms should be immutable tuples or maps so every causal decision is
inspectable.

### 8.1 Cardinality one

Asserting a new value for a card-one attribute automatically retracts the old
current value, regardless of whether the assertion came from an entity map or
list form. A list-form collision is not a special strict-mode rejection.

Multiple incompatible card-one assertions for one entity and attribute in one
transaction reject. Redundant assertions and retractions are removed according
to the target's transaction semantics.

### 8.2 References

`:db.type/ref` validates the representation as an entity reference and resolves
tempids, idents, and lookup refs. It does not impose the current dialect's
assertion-time referential-integrity rule. A ref may name an entity without
requiring that entity to retain some unrelated live fact.

### 8.3 Transaction functions

Core compatibility should include at least:

- `:db/cas` for guarded card-one updates;
- `:db/retractEntity` once component semantics are included.

User transaction functions, if claimed, run at the transactor against the
current `db-before` and return transaction data that re-enters the same planner.
Their effects must be explicit in the resulting transaction stream.

## 9. Schema changes

Schema transactions are ordinary serialized transactions, but they have
additional validation. The candidate schema must be checked against both the
old schema and all current affected data before append.

Required cases include:

- many-to-one cardinality changes require at most one current value per entity;
- adding uniqueness requires current values already to be unique;
- unique attributes require card-one;
- target-defined immutable properties, including value type and tuple shape,
  cannot be altered;
- removing uniqueness changes enforcement only for subsequent transactions;
- index changes follow the target edition's availability/synchronization rules;
- component and no-history changes follow their forward-looking target
  semantics;
- ident renaming preserves the target's old-ident alias behavior rather than
  simply making the old name disappear.

The current dialect's `:schema-as-of` behavior is not compatible. Datomic uses
the schema associated with the current basis when presenting historical
database values, because physical and semantic index interpretation follows
the working schema. The compatible db-value must reproduce that behavior.

If historical-schema interpretation remains useful, expose it as a clearly
named dao.space extension outside the compatible API.

## 10. Components, tuples, predicates, and history

### 10.1 Components

`:db/isComponent` affects nested-map ownership, pull, and recursive entity
retraction. Implementing it requires a transaction expansion for
`:db/retractEntity` that retracts the entity's current facts, inbound refs as
required by the target, and recursively owned components. The expansion is a
write-side transaction function, not a read-side callback.

### 10.2 Tuples

Tuple attributes require schema for tuple type or constituent attributes,
canonical tuple values, automatic derivation when constituents change, and
matching retractions. Callers do not directly maintain derived composite
tuples where the target says Datomic owns them.

### 10.3 Attribute predicates and entity specs

Attribute predicates run automatically for asserted values. Entity specs run
when transaction data requests `:db/ensure`. Both execute at the single
transactor against the candidate database value and can reject the complete
transaction.

Portable predicate execution is a separate problem from storing predicate
symbols as data. A JVM-first target may require predicates on the transactor's
classpath. A cross-host target needs an explicitly portable predicate
language; silently substituting host functions is not compatible.

### 10.4 No-history

dao.space's raw log is immutable and may retain facts even when Datomic would
eventually omit them from historical indexes. Compatibility can still be
observable at the db-value boundary if compatible history queries hide facts
according to `:db/noHistory`. Physical-storage equivalence must not be claimed.

## 11. Index and query obligations

Schema compatibility becomes observable through reads. The compatible
database value must provide:

- cardinality-one values as scalars and cardinality-many values as sets;
- lookup refs over unique attributes;
- AVET behavior required by `:db/index`, uniqueness, and the pinned edition;
- VAET behavior for ref attributes;
- current and history datoms with Datomic-compatible assertion flags;
- entity and pull behavior driven by the active schema;
- tuple and fulltext access paths if those features are claimed.

The current complete four-tree covered set may remain the physical substrate,
but `:db/index` cannot remain semantically deferred. A compatibility layer may
use a complete index where Datomic would use a partial one, provided answers and
availability behavior remain compatible. Performance internals need not match;
observable results must.

Pull requires special attention. The current count-based convention cannot
distinguish a card-many attribute containing one value from a card-one
attribute. A compatible db-value must carry schema into pull/entity projection
so cardinality determines result shape.

## 12. Atomicity, durability, and recovery

The transactor's state is a projection of durable history, never the source of
truth. The append protocol must nevertheless prevent two accepted transactions
from publishing the same basis or allocating overlapping entity ranges.

At minimum:

- one writer agent owns the next transaction id and entity-id allocator;
- append and durable head publication have one atomic success condition;
- accepted results are emitted only after durable publication;
- crash after durable append but before reply is recoverable and idempotently
  discoverable by transaction identity;
- restart reconstructs schema, identities, uniqueness, and allocation
  watermarks before admitting new requests;
- clients can distinguish rejection, retryable uncertainty, and committed
  success.

A process-local lock around one wrapper does not satisfy this boundary across
processes or multiple wrappers. The capability and durable head must identify
one database writer explicitly.

## 13. Relationship to `dao.space.schema`

The two interpreters should share only stable, semantics-neutral machinery:

- d5 tuple accessors and comparison;
- source realization and bounded snapshots;
- covered index storage;
- canonical value codecs where representations agree;
- pure helpers whose contracts are identical.

They should not share policy by conditionals scattered through one transactor.
In particular, strict/lax branching, ref-existence checks, boolean uniqueness,
schema time travel, sequential map interpretation, and no-dedup behavior belong
only to the existing dialect.

A one-time migration tool may translate the compatible subset of an existing
schema:

```text
dao.space.schema declarations
  -> compatibility analysis
  -> Datomic-compatible schema tx-data
  -> governed import transaction stream
```

Migration must reject or require a ruling for:

- undeclared data attributes;
- boolean unique declarations lacking identity/value choice;
- duplicate live unique values;
- card-many data changing to card-one;
- unsupported values or schema properties;
- refs that depend on the existing dialect's extra existence rule;
- histories whose `m` operation cannot be resolved.

## 14. Implementation sequence

### Phase 0: pin the target

1. Choose Datomic Cloud or Pro and a version.
2. Publish a feature matrix with Core, Extended, Deferred, and Declined rows.
3. Define canonical host representations and a JVM-first or cross-host target.
4. Define the exact API surface whose behavior is claimed compatible.

### Phase 1: pure planner

1. Parse Datomic transaction data into immutable intermediate tuples.
2. Implement tempid and identity-upsert equivalence solving.
3. Implement card-one expansion and redundancy elimination.
4. Produce a candidate db-after without IO.
5. Validate types, cardinality, and uniqueness against the candidate.

### Phase 2: governed writer stream

1. Define transaction request and result tuple shapes.
2. Give one transactor agent the append capability.
3. Persist transaction/entity allocation state or derive it safely.
4. Publish one atomic d5 record and a corresponding result emission.
5. Implement recovery and duplicate-request handling.

### Phase 3: schema installation and evolution

1. Seed the pinned system schema.
2. Require installed attributes for data writes.
3. Implement schema-change invariants against current data.
4. Implement ident aliases and unique/index transitions.
5. Apply the active schema to historical db-values.

### Phase 4: compatible db-values

1. Implement the d5-to-Datomic datom adapter.
2. Implement cardinality-aware entity and pull projections.
3. Implement lookup refs and required index selection.
4. Implement transaction entities and compatible history.

### Phase 5: extended features

Add components, retract-entity, CAS, tuples, predicates/specs, no-history,
fulltext, and user transaction functions only as the conformance matrix grows.

## 15. Differential conformance suite

Compatibility is a test result, not a resemblance. Run the same transaction
corpus against the pinned Datomic target and `dao.space.datomic`, then compare
observable results.

The suite must cover:

- installing and querying attribute schema;
- every claimed value type, including invalid host values;
- card-one implicit retraction from map and list forms;
- card-many accumulation and retraction;
- redundant assertion and unmatched-retraction elimination;
- tempid allocation and reports;
- lookup refs in entity and value positions;
- identity upsert, unique value, and conflicting upserts;
- transaction-order and entity-map-order permutations;
- schema changes over conforming and nonconforming current data;
- ident rename and alias lookup;
- current, history, as-of, and since behavior under changed schema;
- transaction entities and metadata;
- CAS and retract-entity when claimed;
- component recursion and tuple derivation when claimed;
- restart, retry, and uncertain-commit behavior;
- concurrent clients submitting conflicting transactions.

For each case compare:

1. acceptance or rejection category;
2. resolved entity ids and tempid report relationships;
3. current EAV facts;
4. transaction/history datoms and assertion flags;
5. entity and pull result shapes;
6. lookup-ref results;
7. schema visible to subsequent and historical reads.

Tests should target public contracts. Internal d5 rows may be inspected to pin
the adapter and recovery invariants, but implementation maps and caches are not
the compatibility surface.

## 16. Acceptance criteria

Core compatibility is complete only when all of the following are true:

- the product/version and feature matrix are explicit;
- every data attribute is governed by installed schema;
- transaction results are independent of item and map iteration order;
- card-one, tempid, lookup-ref, upsert, and uniqueness behavior match the
  target;
- incompatible schema changes reject before append;
- one durable writer authority governs every append to the database;
- historical db-values use the target's active-schema semantics;
- entity and pull results respect declared cardinality;
- the differential Core suite passes against the pinned Datomic target;
- unsupported features fail explicitly rather than approximating silently.

Until those criteria hold, describe the implementation as Datomic-inspired or
as a named compatibility subset, never simply Datomic-compatible.

## 17. Open decisions

1. Which Datomic product and version is the target?
2. Is Core compatibility JVM-only initially?
3. Which Datomic API surfaces are in scope beyond transactions and db-values?
4. How is the single writer capability durably leased and recovered?
5. How are entity-id and transaction-id allocation ranges persisted?
6. How are reified `m` metadata operations resolved and exposed as extensions?
7. Is `:db/noHistory` observable compatibility sufficient while raw d5 remains
   immutable?
8. Which extended features are required before the word "compatible" may be
   used without a qualifier?

