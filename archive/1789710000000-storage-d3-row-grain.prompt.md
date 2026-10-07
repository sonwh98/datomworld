Created-GMT: 2026-09-18T05:00:00Z
Created-Local: 2026-09-18 12:00:00 +0700 (Asia/Ho_Chi_Minh)

Session-ID: 5d909236-d9cb-4587-808a-cadb5771494b

# Task: Resolve D3 of yin.vm.code-as-tuples.implementation-plan.md — dao.jing's row storage grain

Role: Storage & Indexing Specialist

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-18 12:00:00 +07 | Status: active | Rationale: storage/indexing/content-addressing is this model's stated strength; the owner asked the orchestrator to route this to the team rather than reason it through directly, since it turns on CBOR/storage specifics neither the owner nor the orchestrator has deep expertise in

## Context (fresh session, no prior history — read everything below)

`docs/design/yin.vm.code-as-tuples.md` proposes a flat-row tuple grammar
for code/AST content. Its own implementation plan,
`docs/design/yin.vm.code-as-tuples.implementation-plan.md`, names six
owner decisions (D1-D6); D1 and D2 are already resolved (see that
document's revision history). **D3 is open and is your task:**

> ### D3 — how `dao.jing` stores a tree's rows
>
> Individual rows (git-style: one address per node, shared subtrees
> shared across trees, N fetches per load, `:root-reachable` checked
> against what was fetched) or a pack per tree (one fetch, a pack address
> distinct from the root id, the validator's reachability rule is over
> the pack). It decides the loader's interface and it decides what
> `:yin.k/carried` carries.

Read that section in full (it names a real prerequisite: `dao.jing.md`'s
metadata-carry gap, addressed by a separate plan — see below). Read
`docs/design/dao.jing.md` (the current Jing design) and
`docs/design/dao.jing.cbor.md` (a CBOR storage migration plan for Jing,
not yet implemented, but fully reviewed as of tonight — four rounds, two
independent model families, zero remaining defects; read its Revision/
Status lines and its Objective, Backend changes, and Addressing sections
in particular). Read the current implementation:
`src/cljc/dao/jing.cljc` and `src/cljc/dao/jing/{mem,file,remote,dht}.cljc`.

## What the orchestrator and owner already worked through (verify, don't just trust)

Reasoning so far, in conversation, landed on: individual-row storage as
the canonical form, with a pack-per-tree allowed only as an *optional
transport envelope* (fetch many rows in one round-trip, verified row-by-
row on receipt) — never a replacement for row-level content addressing.
The argument: two trees sharing an identical subtree get that subtree
under **the same address** with individual-row storage (real dedup, one
truth per `datom.world.md`'s "Interpretation Creates Semantics" axiom);
pack-per-tree hashes the whole pack's bytes as one unit, so a shared
subtree's identical bytes get stored twice under two different pack
addresses — dedup lost, not merely nominal.

A separate argument (graphs must be constructed explicitly from tuples,
one of `datom.world.md`'s six non-negotiable invariants) was raised and
then partly walked back: a pack is just one ordinary CBOR-encodable
value, and refs inside it still resolve explicitly, so a pack doesn't
obviously reintroduce an *implicit* graph — it just changes the
addressing granularity at which explicit refs are resolved. This
invariant may or may not actually discriminate between the two options;
judge this yourself rather than accepting either side's framing.

**Also verify architecturally, since it changed the shape of this
question:** an earlier Architect review of `dao.jing.cbor.md` found that
"a pack needs no new write primitive — it's just one ordinary
CBOR-encodable value... materialized through the existing single-value
contract." Confirm or correct this against the actual CBOR plan and the
current `dao.jing.cljc` API (`materialize!`, `get`, `segment-key`).

## Task

1. Confirm or correct every claim above against the actual documents and
   code — this conversation's reasoning is a starting point, not settled
   fact.
2. Resolve D3 with a concrete recommendation: individual rows, pack per
   tree, or the hybrid (individual rows canonical + pack as optional
   transport) already sketched above — or something better if you find
   one. Ground the recommendation in the actual CBOR encoding contract
   (byte overhead, fetch cost, hash-verification cost of each option
   under Boring's canonical CBOR profile), not just architectural
   principle — this is exactly the kind of storage-mechanics judgment the
   owner asked to route to someone with real expertise here.
3. Name what `:yin.k/carried` should carry under your recommendation, and
   what the loader's interface should look like (this is D3's own stated
   scope).
4. Confirm the metadata-carry prerequisite `yin.vm.code-as-tuples.
   implementation-plan.md`'s D3 section names (`dao.jing.md:447-464`) is
   correctly understood: `dao.jing.cbor.md`'s design fixes the storage/
   transport half but the intake-stream half (rows arriving via
   `dao.stream` Transit, which carries no metadata) is still open —
   confirm this is accurate and say what it means for your recommendation
   (e.g., does your chosen grain interact with which ingestion path a
   metadata-bearing row must use?).

## Deliverable

A recommendation with reasoning, addressed to the owner (who is not a
CBOR/storage expert and asked for exactly this — a grounded answer, not
a menu of tradeoffs to weigh themselves). State plainly what you'd tell
them to approve. If you genuinely can't resolve it without an owner
judgment call irreducible to storage mechanics (e.g., a values-based
tradeoff between fetch latency and storage efficiency the team has no
existing precedent for), say so explicitly and name exactly what that
residual choice is — don't manufacture false certainty.

Do not implement anything. Do not touch any file except to read them.
