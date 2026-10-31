Completed-GMT: 2026-09-27 15:46:25 GMT
Completed-Local: 2026-09-27 22:46:25 ICT

# ShiBi decision brief

**Decision:** Define ShiBi as an attenuatable capability system. Treat metered use as a policy on a grant. Defer transferable, fungible currency as a separate design decision.

## What the choice means in this architecture

A **capability** gives a peer a verifiable right: for example, “peer P may append to meeting peer M’s pair-proposal medium until T.” P can delegate a narrower right, never a broader one. Grant, caveat, delegation, and revocation facts live on ordinary streams. ShiBi’s index interpreter publishes a local query snapshot; its query interpreter checks the presented credential, request, and context against that snapshot.

A **currency** gives a peer an allowance it *spends*, potentially transferring value to another peer. Issuance, transfers, and spends would likewise be stream facts, but the index must establish an authoritative balance and reject competing spends of the same value. If ShiBi is fungible across independent peers, a mirror’s local snapshot cannot settle that conflict by itself. An issuer-local, nontransferable counter can meter use; calling that currency would obscure its narrower guarantee.

## First consumers

| Consumer | Capability | Currency |
| --- | --- | --- |
| Relay-pair lease | The meeting peer grants a named peer the right to claim or renew a particular pair, with scope and expiry. Its gate verifies that right. The existing lease judge still owns duration and reclaim. | Each claim or renewal spends units. This adds pricing, balance, and spend ordering to a short-lived resource whose attribution already comes from per-author media. It helps only if scarce relay capacity must be allocated by payment. |
| `dao.stream.remote` credential slot | `present` supplies an opaque grant; the mirror gate’s pure `verify` checks it against the published snapshot and returns a reason or `nil`. Revocations take effect when indexed. | The slot can carry a payment proof, but pure verification alone cannot atomically consume a transferable balance across mirrors. Metered decisions can be emitted as facts; charging requires a defined spend authority and conflict rule. |
| REPL session authority | A shell grants a principal bounded rights such as evaluate in session S, with optional limits on operations, time, or delegated scope. The shell enforces them at its request boundary. | A shell charges per evaluation or resource unit. This may price shared compute, but it does not establish *whose* request the shell should honor; identity and authority remain necessary. |

## Reversibility

The **remote seam can host both**: its credential is opaque, and its gate consumes an index-published decision. Starting with capabilities does not require changing `dao.stream.remote` or the capability-free `:dao.stream/refused` outcome.

The **ShiBi fact model cannot silently treat both as one token**. A grant with caveats and revocations expresses authority; a transferable balance needs issuance, ownership, spend identity, ordering, and double-spend resolution. Reserve room for a later spend-policy relation and metering facts, but specify capability tuple shapes now. Avoid putting balances or payment amounts into the canonical grant shape. A later currency interpreter could publish additional facts for a gate to query, once its settlement model is decided.

**Rationale:** Capabilities directly answer all three consumers’ first question—*may this principal perform this operation?*—and fit the existing pure, local verification contract. Metering can limit use under an issuer’s authority. Fungible currency buys transferable economic value, but requires a settlement mechanism that the current stream and index seam does not provide. ShiBi is [not yet implemented](/Users/sto/workspace/datomworld/docs/bootstrap.md:65); making currency foundational now would make these authorization paths depend on that unresolved mechanism. This follows the open precondition in [discovery](/Users/sto/workspace/datomworld/docs/design/dao.stream.discovery.md:181) and preserves the [ShiBi seam](/Users/sto/workspace/datomworld/docs/design/dao.shibi.md:20).

Status: COMPLETE