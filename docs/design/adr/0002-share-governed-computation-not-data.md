# ADR 0002: Share Governed Computation, Not Data

**Status:** Accepted (2026-06-26)

**Supersedes:** the security framing in `dao.space.md` that treated fine-grained control as
plaintext result-filtering requiring a centralized trusted mediator.

**Amends:** ADR 0001 ruling 4 (refining its mediator framing while leaving the storage boundary, now named `dao.jing`, untouched).

## Context

datom.world's founding question is "how to share data without losing control?" This question poses a near-tautology: sharing bits is losing control of those bits. Encryption does not solve this problem, as it merely relocates the issue to key-sharing. Earlier framing in `dao.space.md` considered fine-grained control to be row-level filtering of plaintext, concluding it was impossible without a centralized trusted mediator.

However, a counterparty benefits only if it comes to hold `f(X)` for an authorized function `f`. We can keep control of `X` by never emitting `X`, but instead only emitting `f(X)`.

## Decision

We adopt the model: **share governed computation, not data**.
- The unit of sharing is the governed interpreter, not the datom. In datom.world, this interpreter `f` is realized natively as a **`yin.vm` program or continuation**.
- Capabilities govern interpreters (using a Shibi / `:yin/capability` Macaroon-style design with attenuatable, revocable caveats bounding the `yin.vm` execution).
- The `m` slot of the d5 datom carries the access policy.
- An always-on accountability floor guarantees immutable provenance of every derivation.

## Consequences

- **Two Access Modes:** We establish a Public mode (pull-to-reader, the canonical `dao.space` default) and a Controlled mode (push-interpreter-to-data, confined, answer-only).
- **Topology Inversion:** The Controlled mode inverts the embedded-peer topology, sending the query to the data instead of shipping the data to the query.
- **`f` is an introspectable `yin.vm` AST; evaluation stays mechanism-agnostic.** The *unit* `f` must be a `yin.vm` AST/continuation, not an opaque binary. Safety requires both *runtime confinement* (closing explicit I/O channels) and *expressivity confinement* (restricting `f` to a bounded-output sub-language via a structural check). This is why arbitrary `f` must be rejected per Rice's theorem. *How* the AST is evaluated — owner-hosted, TEE, MPC, or FHE — is a deferred choice; only opaque-`f` runtimes are rejected.
- **Controlled mode is mediation made generic.** The owner-hosted default *is* a trusted mediator (the owner, for their own data); the contribution is a generic, capability-scoped, accountable evaluator instead of a bespoke API. MPC is the only mediator-free topology.

## Open Questions

- Concrete capability/caveat wire format.
- The `m`-policy entity schema.
- Key-derivation hierarchy.
- Revocation epoch mechanism.
- Designing the syntactically decidable, bounded-output sub-language for `f` (expressivity confinement).

## References

- `docs/design/dao.space.security.md` (detailed architectural breakdown)
- `docs/agents/advanced-concepts.md` (confinement, parallel transport)
- [ADR 0001](0001-dao-space-as-storage-boundary.md) (storage boundary; previous security framing)
