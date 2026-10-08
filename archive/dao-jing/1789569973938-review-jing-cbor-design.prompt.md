Created-GMT: 2026-09-16 14:46:13 GMT
Created-Local: 2026-09-16 21:46:13 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: fcfd1a00-5beb-4333-a17c-0128b463c211

# Task: Review the dao.jing.cbor design for implementation readiness

Role: Adversarial Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 21:46:13 +07 | Status: active | Rationale: storage/DHT/indexing review specialist; independent of the implementer family that will revise the design afterwards

Review docs/design/dao.jing.cbor.md as an architecture proposal. Do not edit
any file. The remediation space for this review's findings is design
documentation only — no code changes are in scope for this effort.

Read first (exact list):

- AGENTS.md and docs/design/datom.world.md — foundations and invariants
- docs/agents/architecture.md — stream/agent/VM architecture
- docs/design/dao.jing.cbor.md — the proposal under review
- docs/design/dao.jing.md — the current Jing design, especially "Canonical
  encoding" and "Open items and current limitations"
- docs/design/dao.jing.dht.md — DHT transport semantics
- docs/design/adr/0001-dao-space-as-storage-boundary.md — the recorded
  storage-boundary decision
- src/cljc/dao/jing*.cljc — the current implementation (read-only)
- docs/design/dao.jing.remote.implementation-plan.md — prior remote/transport
  implementation precedent

Treat historical sections as context; flag contradictions between documents
rather than silently choosing one.

The central invariant is non-negotiable: dao.jing is backend-independent
storage, like Datomic storage. Memory, files, PostgreSQL, S3, remote services,
and DHTs must be interchangeable storage mechanisms. CBOR specifies the stored
representation; it must not couple Jing to a particular backend or introduce
database semantics into storage.

Evaluate:

1. Boundaries: Does the value-facing API over an opaque byte-store contract
   preserve storage ignorance, explicit composition, and the existing stream
   architecture? Include whether the proposed byte-store boundary forecloses
   the deferred effect-stream write path (dao.jing.md, Open items: "The
   content write path as an effect stream"). Are codec, integrity, durability,
   and backend responsibilities assigned correctly?

2. Community reuse: Verify the proposed Boring version and capabilities
   against upstream source. Distinguish what Datahike/Konserve actually
   provide from our assumptions. Are Jing's custom wrappers necessary, or are
   we recreating an ad hoc format inside CBOR?

3. Identity and portability: Can JVM, JavaScript, and Dart produce identical
   bytes and addresses? Examine numeric kinds, decimal scale, rational
   normalization, signed zero, NaNs, metadata, identifiers, lists versus
   vectors, and map/set keys. Give concrete collision or failed-round-trip
   examples.

4. Feasibility: Can Boring support the proposed profile without private API
   dependencies or extensive reimplementation? Assess the Dart work and
   whether numeric carriers remain usable by existing consumers.

5. Storage correctness: Examine immutable byte snapshots, concurrent
   insert-if-absent, :present verification, corruption detection, durability,
   file recovery, and nondestructive rejection of old stores. Reason
   concretely about memory, PostgreSQL, and S3 without requiring new backend
   implementations.

6. Transport and migration: Assess Base64 over existing Transit envelopes,
   size limits, integrity checks, and the clean break for address-bearing
   indexes, ASTs, and continuations. Identify hidden compatibility
   requirements.

7. Scope and simplicity: Identify unnecessary abstractions, duplicated
   validation, excessive copying or encoding, and decisions that should remain
   backend-specific. Preserve the requested rich numeric support and
   distinction between integer and floating-point content.

8. Completion criteria: Is the proposal's own "Implementation sequence and
   validation" section — the required test scenarios and acceptance paragraph
   — sufficient to prove cross-host byte identity and backend
   interchangeability before implementation begins? Name any missing scenario
   or acceptance criterion.

Library claims: cite primary sources. Your seat has no network access; if an
upstream fact cannot be verified from the repository or the local dependency
cache, label it explicitly as an unverified assumption rather than asserting
it. The orchestrator will verify those separately.

Begin the final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07 (Asia/Ho_Chi_Minh)>

Report actionable findings first, each as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate. Fix means the smallest design
correction, not a rewrite.

Then: a verdict — ready, ready with corrections, or needs redesign; unresolved
decisions; unverified upstream assumptions (separate list). Do not rewrite the
entire plan or broaden the project into a storage/VM redesign.
