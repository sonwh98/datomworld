# Sharing Without Losing Control

## The Founding Question and the Tautology

datom.world's founding question is "how to share data without losing control?"

This question contains a near-tautology: sharing the bits — or sharing the key to decrypt them — is inherently losing control of those bits. If you share data, you no longer control it; the recipient can copy it, alter it, or share it further. Encryption does not solve this problem; it merely relocates it to key-sharing.

## The Seam

The seam in the tautology is that a counterparty benefits if and only if it comes to hold `f(X)` for some function `f` that you authorized. You keep control of `X` by never emitting `X` itself — only the authorized, attenuated `f(X)`.

Therefore, **the unit of sharing is not the datom; it is the governed interpreter `f` and its audited result.**

## Grounding in Existing Primitives

This model builds naturally upon datom.world's existing primitives:
- **`m`-slot Access Control:** The `m` slot in the d5 datom (see `docs/agents/datom-spec.md`) references a reified metadata entity (where `dao.datom/reserved` governs low IDs). This provides the natural per-datom policy handle.
- **Confinement:** The architectural stance to "prefer confinement over verification" (see `docs/agents/advanced-concepts.md`). Trust must be contextual, revocable, and stream-scoped.
- **Immutable Provenance:** An accountability floor based on an immutable log that records derivation and access.

## Two Access Modes

This security model establishes two distinct access modes:

1. **Public — pull-to-reader (current `dao.space`):** You ship the datoms to the reader. No control is needed or expected. This is the embedded-peer topology where the reader pulls streams and indexes them locally.
2. **Controlled — push-interpreter-to-data / confined:** The answer is returned, but datoms never leave. The reader pushes a confined interpreter to the data. This inverts the embedded-peer topology for controlled streams.

Controlled mode is honestly a form of **mediation**, not an escape from it: in the default topology the data *owner* is the mediator for their own data. The contribution is not "no mediator" — it is that the mediator is a **generic, introspectable, capability-scoped, accountable** evaluator (a `yin.vm` AST) run by the owner, instead of a hand-written API run by a third party. The one topology with *no* single mediator is MPC (see Deployment Topologies), at a high cost.

## Capabilities Govern Interpreters

Capabilities govern the interpreters. Using a Shibi (Macaroon-style) design (the `:yin/capability` attribute), capabilities contain attenuatable, offline-verifiable, revocable caveats that bound the function `f`, its scope (the `m`-policy class), and its time-to-live.

For example: *"compute COUNT/SUM over m=policy-X, not individual v, until T, logged."*

The caveat acts as a content predicate. Be precise about what enforces what: the **capability token** is *cryptographically authenticated* (the macaroon's caveat chain), while the **predicate itself** is enforced by the *evaluation substrate* (which confines the continuation's Environment/Store scope, and restricts its expressivity/output-shape, as detailed below). That enforcement is *operational* in a CESK interpreter (owner-host / TEE) and *cryptographic* in an MPC/FHE circuit — either way it is the substrate, not the token's signature, that bounds what `f` can touch and express. This unifies the earlier "predicate-over-content scoping" with capabilities: the caveat is the predicate, the evaluator is the enforcer.

### Why `f` Is a `yin.vm` AST

Keep two things apart: **what `f` *is*** (the unit of sharing) and **how `f` is *evaluated*** (the deployment topology, below). This section is the first: `f` is committed to be a **`yin.vm` program / continuation** — a Universal AST, which is itself datoms.

Safety has **two parts**, and runtime confinement gives only the first:

- **Execution safety — runtime confinement.** The evaluator gives the continuation an Environment (`E`) and Store (`S`) scoped to *only* the datoms the capability authorizes, exposes *no* I/O or exfiltration primitives, and bounds execution by steps/gas — operationally in a CESK interpreter (owner-host / TEE), or as bounds baked into the circuit topology (MPC/FHE). This closes the explicit I/O channel — the only deliberate way out is the return value. In the controlled mode that means a capability authorizing no effects: handlers present but refused (an empty allow-set is equivalent to stripping), not absent by construction.
- **Data safety — expressivity confinement.** Runtime confinement does **not** stop `f` from computing `f(X) = X` and returning the raw datoms through the *legitimate* return channel — which would collapse control back into raw sharing. Data safety comes from restricting `f` to a **bounded-output / aggregation sub-language** in which "return the rows verbatim" is simply *not expressible*. This is **not** verifying an arbitrary `f` is benign (that is undecidable per Rice's theorem, which is precisely *why* arbitrary `f` is rejected). Rather, it is a decidable, *syntactic* check that `f` is a member of the restricted language. (Language restriction stops *wholesale* copy; residual per-answer leakage and the inference risk of composing many narrow `f`'s is the *budget*, below.)

Both are *confinement* — of the runtime, and of what `f` can express — not *verification* of an arbitrary program: "prefer confinement over verification" taken literally, at two levels.

`f`-as-AST is what makes the expressivity confinement enforceable, and adds what an opaque binary (a WASM module, a raw enclave payload) cannot:
- **Structural enforcement of the sub-language — the data-safety mechanism, not just audit.** Because `f` is datoms, the host runs Datalog over `f`'s AST to enforce membership in the allowed language: whitelisted operators only, bounded output arity, aggregation-only. This constitutes a decidable syntactic check — not cryptographic, and not a semantic-safety oracle. An opaque binary cannot be checked this way, so it cannot be admitted.
- **Native `m`-provenance.** `yin.vm` maintains the causality graph natively, stamping each output datom with the capability that authorized the continuation. An opaque runtime breaks this chain; an AST evaluator preserves it.
- **Trivial transport.** `f` is pushed to where the data lives as datoms (AST / serialized continuation), directly realizing "push-interpreter-to-data" — this is continuation parallel transport (`docs/agents/advanced-concepts.md`).

## Accountability Floor

An always-on accountability floor guarantees transparency. The immutable log, combined with `m`-provenance, attributes every authorized computation and every derivation to a specific actor. We rely on **control-as-accountability** in scenarios where control-as-prevention is impossible.

## The Residual Budget

In this model, control is defined as the governed set of authorized `f`'s plus the audit log, rather than a wall around bits. The primary risk shifts to composition and inference risk (e.g., the residual budget), where a series of many narrow `f`'s could be used to reconstruct the original `X`.

## Deployment Topologies

`f` is a `yin.vm` AST regardless of topology; *where* and *how* the AST is evaluated is a separate, deferred choice — the model stays mechanism-agnostic here. The topology fixes the trust assumptions, and in particular **who introspects `f`, and when**:

1. **Owner-hosted (default):** the owner evaluates the AST locally and returns only `f(X)`; the data never leaves the owner. Introspection is *dynamic* — the owner inspects and confines the submitted AST at evaluation time. The owner is the mediator.
2. **Recipient-side TEE:** the only literal "ship the data, keep control" path. The recipient runs `yin.vm` in an attested enclave; the owner releases the decryption key only against an attestation of the **exact AST hash** to be run. So introspection here is *static and ahead-of-time* — the owner pre-approves a specific `f`-hash (whitelisting), not arbitrary dynamic submission. Strongest trust assumption (the recipient's hardware vendor).
3. **MPC / FHE:** the only topology with **no single mediator** — no party sees `X` in the clear. The `yin.vm` AST is still the unit (introspected before compilation) and outputs can still be `m`-stamped; MPC/FHE is an *evaluation strategy* for that AST, not an opaque alternative to it. Cost: heavy, and limited to the function classes those schemes support.

The line that matters is *opaque vs. introspectable `f`*, not *which runtime*. An **opaque** `f` — a raw WASM module or enclave payload with no recoverable AST — is rejected: it forecloses structural policy and breaks `m`-provenance. Running an **introspectable `yin.vm` AST** *via* a TEE, MPC, or FHE is not opaque and is fine; the AST is inspected before it is sealed or compiled.

## Honest Limits

This model accepts several honest limits:
- **Implementation status:** Full runtime confinement is a target, not yet realized. Current `yin.vm` configuration leaves the surface open (e.g., ungated host module functions). Effect handlers that securely honor capability tokens are not yet built.
- **Side-channel leakage:** Closing the explicit I/O channel does not close side channels. Store access patterns, step counts, and communication patterns leak data unless execution is data-oblivious — a stronger condition than we currently assume.
- **Sub-language design:** Designing a bounded-output/aggregation sub-language that is both syntactically decidable for membership and usefully expressive is a genuine open problem.
- **Post-disclosure irrevocability:** Once an answer `f(X)` is disclosed, it cannot be revoked (honoring the tautology).
- **Answer leakage:** The result `f(X)` inherently leaks some information about `X`.
- **Metadata/shape leakage:** The frequency, size, and shape of queries can leak metadata.
- **Management cost:** There is an inherent cost to managing capabilities, keys, and policies.

## References

- [ADR 0002: Share Governed Computation, Not Data](adr/0002-share-governed-computation-not-data.md) — the decision this design elaborates
- [`dao.space.md`](dao.space.md) — the tuple space; its "Security and Access Modes" subsection summarizes this doc
- [`docs/agents/datom-spec.md`](../agents/datom-spec.md) — the `m` slot
- [`docs/agents/advanced-concepts.md`](../agents/advanced-concepts.md) — confinement; continuation parallel transport
- [`docs/agents/architecture.md`](../agents/architecture.md) — the `yin.vm` CESK machine and Universal-AST substrate
