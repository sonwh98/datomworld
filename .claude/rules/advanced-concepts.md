---
description: Advanced concepts - parallel transport across DaoDB nodes, capability tokens, entanglement/consensus
---

# PARALLEL TRANSPORT

Parallel transport moves datoms from one DaoDB to another.
Entity-ref v values are local gauge; they must be resolved for transport.

AST parallel transport (content-hash based):
  Sender computes transitive closure of content hashes from the root.
  Sends {content-hash -> set of [a v] pairs} with ref v replaced by content hashes.
  Receiver walks bottom-up (leaves first):
    - Content hash already exists locally: reuse existing e (structural deduplication).
    - Content hash is new: assign fresh local e, assert [a v] pairs.
  Receiver asserts datoms with its own e, t, and m.
  No external mapping table needed. The content hash is the connection.

Continuation parallel transport (serialization based):
  Continuations are ephemeral runtime state. They do not have content hashes.
  A continuation references AST nodes (where in the code) and runtime values (computed state).
  AST references resolve via content hash (AST is content-addressed in DaoDB).
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
Prefer confinement over verification.

# ENTANGLEMENT

One leader establishes event ordering.
Failover must be explicit and observable.
No hidden consensus mechanisms.
Entanglement does not imply global truth: only shared causality.
