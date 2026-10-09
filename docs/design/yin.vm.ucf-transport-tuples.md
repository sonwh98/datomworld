# UCF transported state as tuples: the row carrier (body version 3)

Status: **Draft, proposed, not landed.** Written 2026-10-10 from the owner
discussion recorded in
`public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog`
Part Three; revised the same day after the codex Architect review
`collab/1791500000000-architect-ucf-transport-tuples-review.gpt-6.1-sol.findings.md`
(thirteen findings, all addressed below, and six answers adopted in §10).
Subordinate to [`datom.world.md`](./datom.world.md), to UCF §7 in
[`yin.vm.universal-continuation-format.md`](./yin.vm.universal-continuation-format.md),
and to the version-2 amendment
[`yin.vm.universal-continuation-format.v2-amendment.md`](./yin.vm.universal-continuation-format.v2-amendment.md),
whose body is the fact list this carrier must reproduce. Follows the row
conventions of [`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md).
Nothing here is implemented. Body versions 0, 1 and 2 keep their grammar,
readers and bytes; this document proposes a fourth body version whose
carrier is rows, and says what it must preserve.

## 1. Stance

Three owner rulings fix the shape of this document.

1. *Cross-VM safepoints are semantic-VM safepoints.* The Universal AST is
   the canonical code; the instruction vector is its deterministic
   lowering; a safepoint is `(segment-address, pc)` into that vector
   (UCF §7.3.2, §7.4.1). This carrier changes nothing about code
   identity, safepoints, or execution profiles.
2. *The transported state is a tuple set.* Sharing and the literal/marker
   distinction stop being special cases: a reference is a row id in a
   typed position, sharing is Merkle, and a program's data is wrapped
   where it can be confused with a reference. What does not collapse is
   the per-entity decision between content and identity, which this
   carrier makes a property of the row kind and then commits to the root
   through a census.
3. *Tuples are the transport form only.* They exist between a lift and a
   lower. At rest they are rows like any other and queryable by the
   ordinary interpreter. Nothing executes them; the destination lowers
   them into its native representation.

This proposal chooses positional rows: a row kind fixes a slot list, one
table serves encoder and decoder, and a row's Merkle id commits to its
children inline, as code rows already do. EAV is expressive enough and
nothing in the invariants rules it out; positional rows are chosen for
compactness, closedness, and consistency with the existing row
conventions, not because EAV was rejected by review.

## 2. Positioning: body version 3, same profiles

Body versions 0 and 1 (UCF §7.2.1) and 2 (the amendment) are nested
canonical-CBOR maps. **This carrier is body version 3.** The outer
`:yin.k/version` gate of §7.2.1 and amendment §8 step 1 runs first and
unchanged: a reader that does not speak 3 refuses with
`:yin.k/profile-mismatch` carrying the version found and the set it
speaks, before any row is read. A version-3 reader keeps versions 0, 1
and 2 exactly as their contracts state and never upgrades a body across
versions.

The execution profile is untouched. `:yin.k/contract` carries exactly the
amendment §1 profile map `{:yin.k/engine e :yin.code/contract c
:yin.k/version 1}`, from the same registry, under the same whole-tree
gate (one profile per task and install tree). Code stamps `"v3"`, `"b2"`,
`"r2"` and the inner version 1 do not move: this is a change of carrier,
not of instruction, scheduler, or value semantics, exactly the
distinction the amendment draws between its versions and executable
code.

This document tables the **semantic** profile's registers, frames and
closures in full. The stack, register and walker profiles use the same
carrier with their own `R`, `F`, layout and free-env rows, to be tabled
in a follow-up against amendment §5.2–5.4; the carrier admits them by
profile but this draft does not yet specify their rows. Like version 2,
version 3 transports native suspended state within a profile and does not
translate between kernels. The foreign-engine case (an engine that
implements canonical semantic-VM execution in its own layout, UCF §7.4.2)
is the semantic profile lowered through that engine's value profile, and
value profiles are a separate document (§10.6).

## 3. Rows, id spaces, and the census

Every row is `[id kind & slots]`; the stored payload is the body
`[kind & slots]`; the id is an address envelope outside the hashed bytes,
as for code rows (code-as-tuples §2.1).

**Content rows** have `id = (dao.jing/segment-key body)`, the canonical
CBOR of the body under the established digest. Equal bodies are one row.
The id is Merkle: it commits to every row id in the body.

**Identity rows** are cells (UCF §7.5.3). A cell has a task-local id `C`
in the form `:yin.k/c-<n>`, minted by the lift under §6's canonical
numbering, never content-addressed, and scoped to the task whose root
carries it: a cell id means nothing outside its root, in a pool or a
query, and two tasks' `:yin.k/c-1` are unrelated.

**The census.** A cell's *contents* (its stream marker and kept position)
are not reachable through its id, so a content row that references a
cell commits to the cell's identity but not to what the cell holds. The
root therefore carries a **cell census**, a content row

```clojure
[X :cells  C₁ B₁  C₂ B₂  …]          ; sorted by n; Bᵢ is a cref
[Bᵢ :cell-body  stream-cref  position]
```

that maps every cell id to the content row of its body. The census is a
slot of the root, so the root id commits to every cell's contents. A
change to any cell's position or stream changes `Bᵢ`, the census, and
the root. Identity references preserve aliasing; the census
authenticates contents. The validation rules (§8) require the census to
list exactly the cells the task reaches: none missing, none extra, per
root and per install child.

**Acyclicity.** Two edge families exist and are checked separately:

- *Structural value edges*: a cref in a slot, a cell iref in a value, a
  census entry. Merkle construction cannot build a cref cycle bottom-up,
  and a `:cell-body` references only a stream (content) and a position
  (data), so no cell closes a cycle. The receiver still checks
  acyclicity over this family explicitly (§8 step 4), because a receiver
  validates, it does not trust construction.
- *Context lookup edges*: a `:parked-ref` row names a parked id `P`
  resolved through the task's parked table; a closure's `store-of` names
  a manifest address `M` resolved through the module stores; an
  `:install` pending names a module whose entry holds a child body.
  These may close cycles and that is legal: a module store that holds a
  closure whose `store-of` is that same module is the ordinary shape of
  a linked module, and self- or mutually-referencing parked records
  resolve through the explicit `P` table exactly as amendment §6 states.
  A guest value that would need a cycle through *structural* edges is
  refused at lift as `:yin.k/non-portable` kind `:cyclic` naming the
  path, unchanged from UCF §7.5.3.

## 4. The value union

Every position that holds a guest value holds one **encoded value** `V`,
a two-element vector whose first element is a fixed tag:

| `V` | Meaning |
|---|---|
| `[:lit d]` | a literal: `d` is in the portable literal domain below, carried by Jing as data |
| `[:row B]` | a reference to the content row `B` (a closure, a collection row, a frame value, a primitive, a stream marker) |
| `[:cell C]` | a reference to the identity cell `C` |

The union is disjoint by the tag, not by the shape or spelling of `d`: a
literal keyword that happens to equal a row address is `[:lit k]` and
can never be read as a reference; a literal vector that looks like a
`[:row …]` pair is `[:lit [...]]`. No decision depends on content, which
is the UCF §7.5.1 property restated for rows.

**The portable literal domain** is UCF §7.5.1's scalar and collection
arms, closed under nesting: nil, booleans, exact integers of any width
(Jing major types 0/1, tags 2/3), float64 carriers, strings, keywords,
symbols, and vectors, lists, sets and maps *all of whose elements, keys
and values are in the domain*. Scalar and collection carrier classes are
retained, never coerced (an integral float stays a float). Byte strings
are not admitted by this carrier and refuse as `:yin.k/non-portable`
kind `:byte-string`. Metadata: a literal travels with whatever metadata
Jing's canonical encoding preserves for it, hashed as Jing hashes it; a
collection that must become a row (because it reaches a reference) has
no metadata slot, and one that carries metadata refuses as
`:yin.k/non-portable` kind `:metadata` naming the path. A guest map is
always wrapped, `[:lit m]` or a `:map` row, and is open data inside the
wrapper; structural rows are closed.

A collection whose elements include a reference is not a literal; it
becomes a row (§5.1) and the position holds `[:row B]`. This is the only
structural decision the encoder makes and it is decided by one question,
*does this collection reach a reference?*, answered by walking it.

Positions that hold structural data (addresses, pcs, symbols as binding
names, reasons, phases, ids) are typed by the row table and are not `V`;
their kinds are fixed per slot, as for code rows.

## 5. The row table

Kinds are closed. An unknown kind, a wrong arity, a slot whose content is
not of the tabled kind, or a `V` outside §4's union is `:yin.k/undecodable`
naming the row and slot. "Sorted" means ordered by Jing canonical-byte
order of the sort key, and the sort key never contains a cell reference
(§6). Ordered slots are part of the schema because they determine the
Merkle id.

`N` is an exact CBOR integer in `[0, 2^52−1]` (amendment §2). `A` is a
code address, `M` a manifest address, `P` a parked id, `C` a cell id,
`S` a symbol.

### 5.1 Values (content rows)

| Kind | Slots | Decodes to |
|---|---|---|
| `:vec` | `V …` in order | vector |
| `:list` | `V …` in order | list |
| `:set` | `V …` sorted by the element's cell-free skeleton (§6) | set |
| `:map` | `k₁ V₁ k₂ V₂ …`, each `kᵢ` a `V`, sorted by the key's cell-free skeleton | map |
| `:bindings` | `S₁ V₁ S₂ V₂ …` sorted by symbol | `{S V}` |
| `:env` | `bindings-cref  store-of` | E: the named environment plus, when `store-of` is an `M`, `engine/store-of-key` (amendment §2) |
| `:closure` | `segment-A  entry-pc-N  params  bindings-cref  store-of` | semantic closure (amendment §6 semantic arm); `params` is a plain vector of symbols checked against the addressed lambda |
| `:prim` | `name-S` | the named primitive after the profile check (UCF §7.5.2); the profile lives in requires |
| `:stream` | `identity  descriptor` | a stream marker; both plain, authenticated at lift and re-sealed at lower |
| `:reified` | `regs-cref` | `{:yin.k/tag :yin.k/frame :yin.k/registers R}`: a captured continuation, no wait |
| `:parked-ref` | `parked-id-P` | `{:yin.k/tag :yin.k/frame :yin.k/parked-id P}`: resolves in the task's parked table; never duplicates `R` |

`:reified` and `:parked-ref` keep the two outer discriminants amendment
§6 requires and lower differently: a reified value becomes a
receiver-owned native continuation from `R`; a parked reference
reconstructs the engine's parked value for `P`.

### 5.2 Semantic registers, waits, frames (content rows)

| Kind | Slots |
|---|---|
| `:regs` | `segment-A  pc-N  env-cref  stack-cref  k-cref` |
| `:stack` | `V …` bottom first |
| `:k` | `kframe-cref …` bottom to top |
| `:kframe` | `segment-A  pc-N  env-cref  stack-base-N` |
| `:wait` | `regs-cref  pending-cref` |

`:regs` is amendment §5.1's `R` for the semantic profile, and nothing
else: no reason, no pending. A wait is a `:wait` row pairing registers
with a pending; a reified value and a parked record reference `:regs`
directly. `:kframe` is exactly the semantic return frame `{:type :return
segment pc env stack-base}`; the semantic profile has no other frame
type on the wire (walker effect frames belong to the walker profile's
own table). Resume pc and return pc must lie inside their segment, the
return pc immediately after a non-tail call, stack bases nondecreasing
from outermost to innermost and each at most the enclosing stack length
(amendment §5.1), all checked in §8.

### 5.3 Pending (content rows)

One kind, `:pending`, whose first slot is the reason and whose remaining
slots are fixed per reason. The reasons are UCF §7.4.3's closed union as
the amendment r5 required-keys list fixes them; `:park` and
`:call-effect` are not reasons and refuse as `:yin.k/undecodable`.

| reason | slots after the reason |
|---|---|
| `:next` | `cell-C` |
| `:put` | `stream-cref  value-V  op-id` |
| `:ffi` | `cell-C  call-id  op` (op may be nil) |
| `:ffi-request` | `call-id  request-envelope-V  request-stream-cref  response-stream-cref  response-cell-C  op-id` |
| `:link-request` | `link-id  name-S  request-stream-cref  response-stream-cref  cell-C  envelope-V  op-id` |
| `:link-response` | `link-id  name-S  request-stream-cref  response-stream-cref  cell-C` |
| `:install` | `name-S` |

`op-id` is `{:yin.k/occurrence P :yin.k/seq n}` as plain data or nil,
present exactly when the write was first attempted through a fenced
writer, carried verbatim, never minted or renumbered by lift or lower,
and subject to every rule of §7.4.3 r5 (below the root's
`:yin.k/next-op-seq`, unique across the task and its children, absent
when the body has no origin). `request-envelope` and `envelope` are the
retained requests, retried verbatim. `call-id` and `link-id` are plain.
An `:install` pending is never sufficient alone: the root's installs
slot must hold the entry for `name`, else lift refuses
`:yin.k/non-portable` kind `:incomplete-install` and a body carrying it
is `:yin.k/undecodable`.

### 5.4 Task context (content rows)

| Kind | Slots |
|---|---|
| `:store` | `k₁ V₁ k₂ V₂ …` sorted by the key's cell-free skeleton; the isolated slice of UCF §7.6.2 |
| `:module-store` | `manifest-M  store-cref` |
| `:parked-table` | `P₁ regs-cref₁ P₂ regs-cref₂ …` sorted by P |
| `:install` | `name-S  phase  parent  response-V  child-cref` |
| `:cells` | the census, §3 |
| `:cell-body` | `stream-cref  position` (position in the `:dao.stream.remote/v1` portable cursor domain) |

Resource entries never appear in `:store`; streams and cells lower into
the receiver's private resource table beside the program store (linker
M4, `yin.vm.linker.md` 7.3). `:module-store` rows are the per-module
snapshots of §7.6.2's M4 paragraph. The parked table holds exactly the
reachable records. An `:install` row carries the complete entry of
§7.4.3 r5: phase `:running` or `:parked`, parent link id (nil where the
contract permits), the verified response verbatim as a `V`, and the
child as a cref to a whole `:task` row in the child role.

### 5.5 The root

```clojure
[T :task
   version          ; 3                       (gated first, §8)
   contract         ; the profile map, plain  (amendment §1)
   kind             ; :blocked | :parked | :halted
   role             ; :root | :child
   id-counter       ; N                       (UCF §7.6.3)
   store-cref
   module-stores    ; crefs sorted by M
   parked-cref      ; :parked-table
   cells-cref       ; :cells census
   requires         ; plain: UCF §7.6.1's map, unchanged
   code             ; plain: {A code-payload …}, each payload verified to hash to its key (§7.2.1)
   installs         ; crefs to :install rows, sorted by module name
   frames           ; crefs to :wait rows, IN WAIT ORDER   (:blocked, :parked)
   parked-id        ; P or nil                              (:parked only)
   result           ; V or nil                              (:halted only)
   custody]         ; plain, see below
```

Kind rules are §7.2.1's: `:blocked` has nonempty frames and neither
parked-id nor result; `:parked` has frames (possibly empty), a parked-id
naming a key of the parked table, no result; `:halted` has a result and
neither frames nor parked-id. Frames restore in carried order and are
never collapsed to one; a task with an empty ready queue may hold several
ordered waits.

`custody` is the version-1 header carried as plain data, under the same
rules (§7.2.1, amendment §3): on a `:root` of kind `:blocked` or
`:parked` under `:yin.k/exclusive`, exactly `{:yin.k/policy
:yin.k/occurrence :yin.k/arbitration :yin.k/origin :yin.k/next-op-seq}`
with origin absent on a first export; a fork root carries `{:yin.k/policy
:yin.k/fork}` and nothing else; a `:halted` root carries origin only; a
`:child` carries nil and is validated with its root's occurrence, origin
and counter passed down. No body carries an epoch.

The root is a content row and **its id is the transported state's
address**, the `B` of UCF §7.3.4 for this carrier. Occurrence and origin
are inside it, as the governing rule keeps them (§10.1). The code map is
plain data in the root rather than a row kind: each instruction vector
keeps its own address `A` computed exactly as UCF §7.3.2 computes it, and
the carrier verifies, never recomputes, that address.

## 6. Canonical cell numbering

Cell ids must be a function of the state, or two lifts of one state give
two root ids. The numbering is computed **before** any row is hashed and
without reference to any hash, in one pass:

1. **Traversal order.** Walk the task in this fixed order, recursing
   into every reachable structure: frames in wait order, and within a
   wait its registers then its pending; within registers, env (bindings
   by sorted symbol, then store-of's module store if not yet walked),
   stack bottom-up, k bottom-to-top with each frame's env; the parked
   table by sorted `P`; the store by sorted key skeleton; module stores
   by sorted `M`; the result; install entries by sorted module name,
   each child recursively as its own task with its own numbering.
   Within a value: vectors and lists in order; sets and maps by the
   element's or key's **cell-free skeleton**, the value's canonical bytes
   with every cell reference replaced by the constant `[:cell nil]`;
   closures by segment, entry, then bindings; pendings in slot order.
2. **Assignment.** The first time a cell is reached it receives the next
   `n`; a cell reached again keeps its number. Distinct cells at equal
   streams and positions are distinct and receive distinct numbers.
3. **Ties.** Two elements of a set, or two keys of a map, with equal
   skeletons are ordered by the canonical bytes of their referenced
   cells' *bodies* (stream marker, then position), walked in the
   element's own traversal order. Two elements that are equal in
   skeleton and in every referenced cell body are symmetric: either
   assignment produces the same row set and the same root id, so the
   choice is immaterial and the encoder takes them in encounter order.

Because skeletons omit cell numbers and ties are broken by cell bodies,
the ordering never depends on a number the pass has yet to assign. Rows
are then built and hashed with the numbers fixed. The receiver
**recomputes the numbering from the decoded rows and refuses**
(`:yin.k/undecodable`, kind `:noncanonical-cells`) if the carried ids
differ from the recomputed ones; contiguity alone is not canonicality.

Under this rule two lifts of one state are byte-identical. Two lifts of
different states sharing a closure over the same cell may number it
differently and so not share that closure's row across lifts; that
cross-lift dedup is knowingly given up for identity-bearing values and
never affected rows below a cell. On lower, each `C` becomes one fresh
private resource entry seeded at its carried position; every `[:cell C]`
maps to that entry; two references to one cell share it and one
reference per cell keeps its own (UCF §7.5.3).

## 7. Equality and the content address

The transported state is a DAG rooted at the `:task` row. Its address is
the root id, which commits to every reachable content row, to every cell
id through the references, and to every cell's contents through the
census. Two transported states are equal iff their root ids are equal,
and under §6 that holds iff they were lifted from states equal up to the
renaming of cell ids that §6 makes canonical. No physical enumeration of
the row set needs an order, because the root commits to everything and
the pack, if one is addressed, has its own canonical encoding (§10.2).
Jing supplies canonical bytes and the digest of each body; this document
supplies the order within each body and the numbering of cells. Jing is
asked to sort nothing it does not already sort.

Occurrence and origin are inside the hash, as in versions 1 and 2 where
the body address is the hash of the whole body. Snapshot variants of one
occurrence may therefore have distinct addresses while remaining one
occurrence, exactly as UCF §7.3.4 intends.

## 8. Validation on lower

Validation is pure until the last step and checks the whole tree before
one attachment. The order follows amendment §8 and is binding:

1. **Gate.** Decode canonical bytes retaining numeric classes. The root
   row's kind is `:task` and its version is the exact integer 3, else
   `:yin.k/profile-mismatch` (version found, supported set). Traverse
   install children in canonical module-name order; gate every child's
   version, then every profile map against the registry, then require
   all versions 3 and all profiles equal to the root's. A malformed
   install container is `:yin.k/undecodable`; nothing claims to have
   gated a non-task.
2. **Structure.** Every row's kind is tabled with the right arity; every
   structural slot holds its tabled kind; every `V` is in §4's union and
   every literal in the portable domain; no two rows share an id; exactly
   one row has role `:root`; kind rules of §5.5 hold; the custody slot
   matches the role, kind and policy.
3. **Hashes.** Every content row's id equals the Jing address of its
   body; every code payload hashes to its key `A` and is well formed
   under the stamp; the requested body address, if one was given, equals
   the root id. Mismatch is `:yin.k/hash-mismatch` naming the row or
   code key.
4. **Graph.** Every cref names a present row of a kind admissible in that
   slot; the structural edge family is acyclic; every row is reachable
   from the root (an unreachable row is `:yin.k/undecodable`); every
   `[:cell C]` names a census entry and every census entry is reached,
   per task and per child; every `:parked-ref` and `parked-id` resolves
   in the parked table; every `store-of` resolves to a carried module
   store; every `:install` pending has its entry and every entry's child
   is a `:child` task.
5. **Canonical numbering.** Recompute §6 and compare (kind
   `:noncanonical-cells` on mismatch).
6. **Code and frames.** Each resume pc is a static safepoint whose kinds
   admit the wait's reason (`:yin.k/reason-mismatch`); a parked record's
   pc is an explicit-park safepoint; return pcs follow a non-tail call;
   stack bases are bounded and nondecreasing; closure params agree with
   the addressed lambda; integer slots are in range.
7. **Pending and operations.** Reason-specific slot rules; every op-id
   below `next-op-seq`, unique across the tree, absent without origin;
   link and FFI correlation fields present.
8. **Dependency closure.** `requires` evaluated per UCF §7.6.5: missing
   segments, primitives, modules, streams, cursor profiles are
   `:yin.k/unsatisfied`; a body with cells claims
   `:dao.stream.remote/v1`; module-store coverage is complete for every
   `store-of` reached.
9. **Custody.** For an exclusive root, the occurrence, baseline and
   operation inspector and admission checks of §7.7.8; an authority
   never adds missing header keys.
10. **Composition.** Protection, resource, primitive and grant inputs of
    the receiving composition.
11. **Restore.** Reconstruct isolated code spaces, fresh values, the
    isolated store, module-store instances (first link wins), one
    private resource entry per cell, the parked table, children first,
    then waits in order; adopt `max(local, carried)` for the id counter;
    publish the machine only after every child succeeds; run, poll,
    mint, append, close or initialize nothing during lower.

Diagnostics use the established families and no new top-level outcome:
`:yin.k/profile-mismatch`, `:yin.k/undecodable` with `:yin.k/path` as
`[row-id slot-index …]`, `:yin.k/hash-mismatch`, `:yin.k/unsatisfied`,
and the lift-side `:yin.k/non-portable` kinds, including the three this
carrier adds to UCF §7.5.4's set: `:byte-string`, `:metadata`,
`:noncanonical-cells`.

## 9. Lift, lower, and the laws

**Lift** (reference machine): refuse unless quiescent; number cells (§6);
walk waits, parked records, the slice, module stores, the result and
each install child, emitting rows and the census; refuse by path as UCF
§7.5.4 and §7.2.1 specify. The nested carrier's value table
(`:yin.k/values`) has no counterpart, sharing being Merkle; its cell
table becomes the census.

**Lower**: §8 in order, then restore.

The laws, with the domains the Architect's finding 12 requires:

- **Canonical round trip.** For every valid, canonical (§6-numbered)
  version-3 state `S` within a supported profile,
  `lift(lower(S)) = S` by root id, under a reconstruction context that
  preserves every carried canonical field: the id counter is compared as
  carried, not after `max`; occurrence, origin, cell bodies and op-ids
  are carried verbatim; resource remapping is undone by the lift's own
  numbering.
- **Native observational equivalence.** `lower(lift(m))` need not equal
  `m` as native state (fresh ids, seals, private keys); under equivalent
  external inputs it must preserve the next observable step, the wait
  set and its order, the result, and the effect trace.
- **Cross-engine.** Running the corpus to each safepoint on an engine
  that implements canonical semantic-VM execution yields, under
  controlled composition identities (occurrence, origin, resource
  descriptors fixed by the harness), the same version-3 row set the
  reference machine yields.
- **Legacy correspondence.** A version-0/1/2 body normalizes into
  version 3 with semantic preservation (same lowered machine under
  native equivalence); exact mutual inversion is required only between
  version 3 and its one canonical nested projection (§10.3), never
  against historical nested encodings with arbitrary sharing thresholds
  and cell names.

The UCF §7.11 harness gains one assertion per law. During transition the
Stage D/E corpus, re-encoded through the canonical projection, is the
oracle for the first law.

## 10. Questions the review answered, and what remains

Answers 1–6 are the codex Architect's (round 1); where the Architect
marked a point as an owner decision it is labelled so.

1. **Occurrence inside the hash.** Kept inside, following the governing
   rule and binding custody context to the published checkpoint. An
   occurrence-independent execution-state address is an owner-level
   protocol decision; if ever pursued it must sit beside, not replace, a
   fully authenticated checkpoint address, and it does not substitute
   for the census.
2. **Packing.** Individual immutable content rows plus a self-contained
   transport pack. When a pack is addressed it gets its own address and
   canonical encoding, distinct from the semantic root id; cell ids
   remain root-scoped inside it. Same answer as code-as-tuples §2.1
   should receive; the loader interface is resolved explicitly, not by
   layout.
3. **The nested grammar.** Versions 0, 1 and 2 remain compatibility
   inputs. One canonical nested projection of version 3 is kept for
   single-message channels and must stay exactly mutually inverse with
   the rows. Rows are the carrier authority once specified. Retiring old
   readers is an owner version-support decision.
4. **Contract stamp.** The profile map and code contracts do not move; a
   carrier change is a new body version with its own gate. Any change to
   instruction or scheduler semantics would separately revise the
   execution contract and is not proposed here.
5. **Reified and parked.** Sharing immutable `:regs` rows is acceptable;
   the two keep distinct outer kinds (§5.1) and distinct lowering. A
   reified capture has no pending; a parked reference resolves in task
   context.
6. **Foreign-engine value profiles.** Required before any implementation
   brief claims heterogeneous execution conformance, and not drafted
   here: admitted values, numeric behaviour, key equality, metadata,
   resource and profile requirements, refusal sets, and safepoint
   reconstruction obligations. Slot losslessness alone does not
   establish execution parity, and version 3, like version 2, does not
   translate between kernels.

Still open after this revision:

7. The stack, register and walker profile row tables (§2).
8. Whether a version-3 reader should accept version-2 bodies for lower
   only, or whether normalization (law 4) is a separate tool.
9. The exact cell-free skeleton encoding (§6): `[:cell nil]` as the
   placeholder is a proposal; the Architect should confirm it cannot
   collide with a literal.

## 11. What this does not change

Code identity, the contract stamp and profile registry, safepoints,
pending-wait semantics and the r5 required keys, install-child rules,
the dependency closure and satisfaction check, the isolated store, the
private resource table, module-store instancing, scheduler state, the
custody header and admission rules, fencing and operation ids, the
outcome families, and the existing refusal kinds (three lift kinds are
added, §8). The carrier is a new body version for the same facts. The
Architect's round-1 statement is the acceptance bar: tuple transport
violates none of the six invariants once polymorphic references and
context edges are specified and validated, and §4, §3 and §8 are where
they are.
