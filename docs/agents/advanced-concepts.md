---
description: Advanced concepts - parallel transport across dao.jing instances, capability tokens, entanglement/consensus
---

# PARALLEL TRANSPORT

Parallel transport moves datoms from one dao.jing to another.
Entity-ref v values are local gauge; they must be resolved for transport.

Transport is not the cross-stream query fold. Both cross a stream boundary; they are
different operations and use different mechanisms:
  Fold (dao.space.query): no ingestion. Datoms stay where they are; sources remain
    separate database inputs and each clause resolves against its named source, so
    equal stream-local ids never collide or merge (docs/agents/datom-spec.md,
    d5: SOURCE SCOPE). e is never rewritten, nothing is deduplicated, nothing is
    hashed. A coordinate operation.
  Transport (this section): ingestion. The receiver ends up owning the datoms, mints
    its own e for them, and deduplicates structurally. An identity operation.
Neither subsumes the other. Scope does not travel: transported datoms land in the
receiver's stream and carry the receiver's scope. Migration is a third thing —
it relocates a stream whole, so its scope and offsets travel unchanged.

Transport is interpreter-level, and unimplemented.
  The content hash below is the *semantic* hash of docs/agents/datom-spec.md: an
  interpreter's projection of an entity, over (a v) or (a v m). It is specified,
  not built, and no attribute is reserved for it.
  It is NOT dao.jing/content-hash. That one is the *syntactic* hash — it addresses
  opaque blobs at the storage boundary and knows nothing of entities. Asking it to
  deduplicate entities would require teaching the well what an entity is, which is
  precisely the structure-awareness that layer refuses.
  Sender and receiver must use the SAME interpreter. Equality is interpreter-defined,
  and hashes from different interpreters never compose — so the interpreter's own
  identity is part of the hash, and a mismatch must not silently look like a miss.
  Choice of projection is load-bearing: (a v) quotients away m, so an assertion and
  its retraction hash alike. (a v m) keeps them apart but needs m resolved to the
  metadata entity's own hash when m >= 16, since a bare m is stream-local.
  It dedupes *structures*, not evolving entities: an entity's (a v) set grows as facts
  accumulate, so the hash identifies a version. Right for AST and other write-once
  data; wrong as identity-over-time for, say, a person. Those correlate by unique
  attribute instead.

AST parallel transport (content-hash based):
  Sender computes transitive closure of content hashes from the root.
  Sends {content-hash -> set of [a v] pairs} with ref v replaced by content hashes.
  Receiver walks bottom-up (leaves first):
    - Content hash already exists locally: reuse existing e (structural deduplication).
    - Content hash is new: assign fresh local e, assert [a v] pairs.
  Receiver asserts datoms with its own e, t, and m.
  This is why e never travels: it is a local gauge on both sides, and the receiver's
  assignment is the only one meaningful in the receiver's stream.
  No external mapping table needed. The content hash is the connection.

Continuation parallel transport (serialization based):
  Continuations are ephemeral runtime state. They do not have content hashes.
  A continuation references AST nodes (where in the code) and runtime values (computed state).
  AST references resolve via the same semantic hash as above — not dao.jing's blob
  hash, which addresses segments rather than AST nodes.
  Runtime values serialize and travel as-is (no hashing, no deduplication).
  Transport protocol:
    1. Serialize runtime state (environment, partial results, frames).
    2. Replace AST entity-ref v with content hash of the AST node.
    3. Runtime values travel directly.
    4. Receiver resolves AST content hashes to local e, reconstructs runtime state, resumes.
  Content addressing and parallel transport are orthogonal mechanisms.
  They intersect at one point: AST references inside continuations use content hashes.

# CAPABILITIES & TRUST

Possession of a capability is necessary but not sufficient for trust.
Trust must be contextual, revocable, and stream-scoped.
Never assume a valid signature implies safe execution.
Prefer confinement over verification (see `docs/design/dao.space.security.md` for the elaboration of this model: sharing governed computation instead of data).

# ENTANGLEMENT

One leader establishes event ordering.
Failover must be explicit and observable.
No hidden consensus mechanisms.
Entanglement does not imply global truth: only shared causality.
