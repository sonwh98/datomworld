# UCF transported state as tuples: the row carrier (body version 3)

Status: **Draft, proposed, not landed.** Written 2026-10-10 from the owner
discussion recorded in
`public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog`
Part Three; revised the same day after the codex Architect review
`collab/1791500000000-architect-ucf-transport-tuples-review.gpt-6.1-sol.findings.md`
(round 1: thirteen findings; round 2: eight; round 3: seven; all
addressed below, and nine answers adopted in §10). §10 also lists which
parts of this document are design proposals rather than consequences of
the owner's rulings.
Subordinate to [`datom.world.md`](./datom.world.md), to UCF §7 in
[`yin.vm.universal-continuation-format.md`](./yin.vm.universal-continuation-format.md),
and to the version-2 amendment
[`yin.vm.universal-continuation-format.v2-amendment.md`](./yin.vm.universal-continuation-format.v2-amendment.md),
whose body is the fact list this carrier must reproduce. Follows the row
conventions of [`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md).
Nothing here is implemented. This document specifies body version 3, the
one carrier; older bodies are not read.

> **Amendment 2026-10-11.** This draft is amended by
> `yin.vm.semantic-register-vm.continuation.md` §10.3 (R1-R11, codex
> rulings of 2026-10-10) for the register-shaped canonical machine of
> `yin.vm.semantic-register-vm.md`, and the amended text below
> **supersedes** the draft's earlier §5.2 (the `:regs`/`:stack`/
> `stack-base` frame rows), its §8 step 7 (stack-base checks) and its
> "the `"v3"` stamp does not move" positioning: the carrier is body
> version 3 with one engine-less profile
> `{:yin.code/contract "v4" :yin.k/version 1}`, reads no other body
> version, carries canonical vectors by A, `:yin.k/trees` and the task's
> free environment, never a native image, and its frames are `:act` and
> `:tail` rows judged by the frame validator. Amended: the status
> paragraph (R1a), §2 (R1, R7), §5.1 `:reified` (R3), §5.2 (R2), §5.4
> `:parked-table` (R4), §5.5 (R5, R10), §8 steps 1 and 7 (R6a, R6), §9
> (R8), §10.3-10.8 (R7, R8, R9), §11 (R9); §3 kept verbatim (R11).
> Completed against continuation §3.3 and §7.1 on review (codex,
> 2026-10-11): §8 step 7 runs `frame/task-defect` once per body before
> its frames; §8 step 9's census roots and §9's lift walk include the
> initial free environment; §5.2 cites rule A2.

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

## 2. Positioning: body version 3, one profile

**Body version 3, one profile.** The carrier is body version 3 and reads
no other version (owner, 2026-10-11: no legacy): a body of version 0, 1
or 2 is `:yin.k/profile-mismatch` at the gate and is never normalised or
upgraded. The profile map is `{:yin.code/contract "v4" :yin.k/version 1}`;
there is no engine key, because the frame is the canonical
register-shaped machine's and the executing kernel is the receiving
composition's choice: the stack and register kernels lower the same rows
through their layouts (`yin.vm.semantic-register-vm.continuation.md`
§2.1, §4). Any other profile map is `:yin.k/profile-mismatch`.

## 3. Rows, id spaces, and the census

Every row is `[id kind & slots]`; the stored payload is the body
`[kind & slots]`; the id is an address envelope outside the hashed bytes,
as for code rows (code-as-tuples §2.1).

**The wire unit** is one closed map, so that the gate of §8 has
something to run on before any row is interpreted:

```clojure
{:yin.k/handoff true
 :yin.k/version 3
 :yin.k/root    B          ; the root :task row's id
 :yin.k/rows    [[id kind & slots] …]}   ; every row of the tree, any order
```

Unlisted keys refuse. Duplicate ids among `:yin.k/rows` refuse before
any traversal (`:yin.k/undecodable`), so every walk is over a map from
id to one row. The gate's walk over install children follows only
`:install` child crefs and keeps an **active recursion-path set**: a
cref that leaves the rows, or a child that is already on the active
path, stops the gate with `:yin.k/undecodable`. A child reached again
*off* the active path is not an error: two install entries whose
children have identical content (two identical halted children, say)
share one child row by Merkle construction, the structural graph is a
DAG, and each install entry still restores as its own native task
instance with its own task-local cursor cells and its own contextual custody
validation. Completed content validation is cached per row; instance
restoration never is. When a pack is addressed (§10.2) this map is what
is hashed, and a `:yin.k/root` that does not name a `:task` row in
`:rows` is `:yin.k/undecodable`.

**Content rows** have `id = (dao.jing/segment-key body)`: **the same
call, and therefore the same digest, as code addresses `A`**
(`ucf/code-address` is `(jing/segment-key v)`), which is Jing's default
algorithm, BLAKE3 at the time of writing (`dao.jing.cbor.md`,
`dao.jing.hash-registry.md`). The carrier mints no second addressing
scheme beside A. Uniqueness, which a canonical address scheme needs, is
supplied by the **carrier version**, not by naming an algorithm here:
**body version 3 uses BLAKE3 permanently**; this is an immutable
mapping from carrier version to digest, never the reader's current
default, and a later Jing default requires another carrier version,
exactly as it would for A. Version-3 verification and re-emission (§6,
§8) always use BLAKE3, whatever the reader's default is at the time;
this matters because root body bytes contain child-row addresses, so
the numbering procedure's leaf keys depend on the digest and are
canonical *within* a carrier version. Addresses are self-describing
(`:segment/blake3-…`, `:segment/sha256-…`); a reader verifies each id
under the algorithm its address names, as Jing always does, and refuses
an id whose algorithm is not the one version 3 mints under
(`:yin.k/undecodable`). Equal bodies are one row. The id is Merkle: it
commits to every row id in the body.

**Identity rows** are cursor cells (UCF §7.5.3, "Terminology: cursor,
cursor cell, cursor-ref, cell", which defines all four words; this document
uses them exactly as defined there). Before 2026-10-12 this document called
the cursor cell a "cell" and spelled its tags `:cells`, `:cell-body`,
`[:cell C]`, `:yin.k/c-<n>`; the version-2 handoff carrier keeps those
spellings. A cursor cell has a task-local id `C`
in the form `:yin.k/cc-<n>`, minted by the lift under §6's canonical
numbering (the n-th cursor cell met in §6's canonical walk), never content-addressed, and scoped to the task whose root
carries it: a cursor-cell id means nothing outside its root, in a pool or a
query, and two tasks' `:yin.k/cc-1` are unrelated.

**The census.** A cursor cell's *contents* (its stream marker and kept position)
are not reachable through its id, so a content row that references a
cursor cell commits to the cursor cell's identity but not to what the cursor cell holds. The
root therefore carries a **cursor-cell census**, a content row

```clojure
[X :cursor-cells  C₁ B₁  C₂ B₂  …]          ; sorted by n; Bᵢ is a cref
[Bᵢ :cursor-cell-body  stream-cref  position]
```

that maps every cursor-cell id to the content row of its body. The census is a
slot of the root, so the root id commits to every cursor cell's contents. A
change to any cursor cell's position or stream changes `Bᵢ`, the census, and
the root. Identity references preserve aliasing; the census
authenticates contents. The validation rules (§8) require the census to
list exactly the cursor cells the task reaches: none missing, none extra, per
root and per install child.

**Acyclicity.** Two edge families exist and are checked separately:

- *Structural value edges*: a cref in a slot, a cursor-cell iref in a value, a
  census entry. Merkle construction cannot build a cref cycle bottom-up,
  and a `:cursor-cell-body` references only a stream (content) and a position
  (data), so no cursor cell closes a cycle. The receiver still checks
  acyclicity over this family explicitly (§8 step 3), because a receiver
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
| `[:cursor-cell C]` | a reference to the identity cursor cell `C` |

The union is disjoint by the tag, not by the shape or spelling of `d`: a
literal keyword that happens to equal a row address is `[:lit k]` and
can never be read as a reference; a literal vector that looks like a
`[:row …]` pair is `[:lit [...]]`. No decision depends on content, which
is the UCF §7.5.1 property restated for rows.

**The portable literal domain** is UCF §7.5.1's scalar and collection
arms, closed under nesting, with exactly the numeric classes Jing and
`value-encoder` already admit: nil, booleans, exact integers of any width
(Jing major types 0/1, tags 2/3), float64 carriers, Jing's Decimal and
Rational carriers under Jing's normalization rules, strings, keywords,
symbols, and vectors, lists, sets and maps *all of whose elements, keys,
values and retained metadata are in the domain*. Carrier classes are
retained, never coerced (an integral float stays a float). Byte strings
are not admitted by this carrier and refuse as `:yin.k/non-portable`
kind `:byte-string`.

**Metadata** is walked like any other part of a value when deciding both
portability and whether a value reaches a reference. Reference-free
retained metadata on a *literal* travels as Jing preserves it and is
hashed as Jing hashes it. Collection rows have no metadata slot, so
**any collection that must become a row and carries retained metadata
refuses**, whether the metadata holds a reference (copying it as data
would carry source coordinates and seals unremapped) or is ordinary
reference-free data such as `{:doc "x"}`: `:yin.k/non-portable` kind
`:metadata` naming the path. This is a proposed limitation of this
carrier, not an inherited UCF requirement (§10). Metadata a reader strips
is not retained metadata and plays no part.

A guest map is always wrapped, `[:lit m]` or a `:map` row, and is open
data inside the wrapper; structural rows are closed.

A collection whose elements, keys, values or metadata include a
reference is not a literal; it becomes a row (§5.1) and the position
holds `[:row B]`. A collection that reaches no reference is a literal
and **must** be `[:lit d]`: a reference-free `:vec`, `:list`, `:set` or
`:map` row is a non-canonical spelling and refuses (§8). Empty
collections are therefore always literals. This is the only structural
decision the encoder makes and it is decided by one question, *does this
collection reach a reference?*, answered by walking it.

**Structural data** is a second, separate domain: values that UCF
§7.4.3 requires to be in the canonical-bytes domain but that are not
guest literals and contain no guest values, namely install responses,
link envelopes, operation ids, link ids and call ids. A structural-data
slot holds plain canonical-bytes data and is validated by the rule that
governs it (an install response by the linker's delivery checks, an
op-id by §7.4.3 r5), not by the literal domain. Table rows mark these
slots `D`. A retained **FFI request envelope is not structural data**:
`dao.stream.apply/request?` permits unknown envelope keys, and its
arguments are guest values. It therefore travels as the existing
encoder carries it (`handoff.cljc`): the **complete envelope,
recursively encoded as a `V`** (a `:map` row, or `[:lit m]` when it
reaches no reference), so that every key is preserved, closures stay
portable, and resource references go through the census. No closed
envelope row narrows the open request protocol.

Positions that hold structural data (addresses, pcs, symbols as binding
names, reasons, phases, ids) are typed by the row table and are not `V`;
their kinds are fixed per slot, as for code rows.

## 5. The row table

Kinds are closed. An unknown kind, a wrong arity, a slot whose content is
not of the tabled kind, or a `V` outside §4's union is `:yin.k/undecodable`
naming the row and slot. **One ordering rule** governs every emitted row
and every receiver check: "sorted" means ordered by the Jing canonical
bytes of the sort key *as spelled in the emitted row*, cursor-cell numbers
included, under the final numbering of §6. The spellings (keys) of §6
are used only inside the numbering procedure and never decide the order
of an emitted row. Ordered slots are part of the schema because
they determine the Merkle id.

`N` is an exact CBOR integer in `[0, 2^52−1]` (amendment §2). `A` is a
code address, `M` a manifest address, `P` a parked id, `C` a cursor-cell id,
`S` a symbol, `D` structural data (§4). Each `[:row B]` position admits
only the row kinds its table entry names; a `[:row B]` whose target is
of another kind refuses (§8). Keyed rows (`:bindings`, `:map`, `:store`,
`:parked-table`, `:cursor-cells`, the installs and module-stores slots) must
have unique keys, and `:set` rows unique elements, under canonical bytes
**and** under portable decoded equality, since Jing's own map and set
rules do not apply to a key or element list spelled as a vector.

### 5.1 Values (content rows)

| Kind | Slots | Decodes to |
|---|---|---|
| `:vec` | `V …` in order | vector |
| `:list` | `V …` in order | list |
| `:set` | `V …` sorted (§5 preamble) | set |
| `:map` | `k₁ V₁ k₂ V₂ …`, each `kᵢ` a `V`, sorted by key | map |
| `:bindings` | `S₁ V₁ S₂ V₂ …` sorted by symbol | `{S V}` |
| `:env` | `bindings-cref  store-of` | E: the named environment plus, when `store-of` is an `M`, `engine/store-of-key` (amendment §2) |
| `:closure` | `segment-A  entry-pc-N  params  bindings-cref  store-of` | semantic closure (amendment §6 semantic arm); `params` is a plain vector of symbols checked against the addressed lambda |
| `:prim` | `name-S` | the named primitive after the profile check (UCF §7.5.2); the profile lives in requires |
| `:stream` | `identity  descriptor` | a stream marker; both plain, authenticated at lift and re-sealed at lower |
| `:reified` | `act-cref` | a captured continuation: the decoded act state of frozen §4.2, no pending |
| `:parked-ref` | `parked-id-P` | the keyed `act` state (`yin.vm.semantic-register-vm.continuation.md` §1.2) reached through `P`: resolves in the task's parked table; never duplicates the state |

`:reified` and `:parked-ref` keep the two outer discriminants amendment
§6 requires and lower differently: a reified value becomes a
receiver-owned native continuation from its act; a parked reference
reconstructs the engine's parked value for `P`. A reified value's act is
validated in context `:captured` (continuation §3.2 P3): its `pc−1` is a
`:current-continuation` and it has no pending.

### 5.2 Frame rows: states, windows, waits, frames (content rows)

(Replaced 2026-10-11 by `yin.vm.semantic-register-vm.continuation.md`
§2.3, under frozen `yin.vm.semantic-register-vm.md` §4.2.)

| Kind | Slots | Notes |
|---|---|---|
| `:act` | `segment-A  pc-N  env-cref  window-cref  k-cref  rd-N` | the delivery record is `{:deliver :rd :rd rd-N}` by kind |
| `:tail` | `segment-A  site-N  k-cref` | delivery `{:deliver :return}` by kind; **no** env, window or pc slot |
| `:window` | `r₁ V₁  r₂ V₂  …` sorted by `rᵢ` ascending, unique | `rᵢ` is `N`; **a nil-valued register is present as `rᵢ [:lit nil]`**; the empty window is `[:window]` |
| `:k` | `kframe-cref …` outermost first | `[:k]` is the empty continuation |
| `:kframe` | `segment-A  pc-N  env-cref  window-cref  rd-N` | one `:return` frame |
| `:wait` | `state-cref  pending-cref` | `state-cref` is an `:act` or a `:tail` row |
| `:reified` | `act-cref` | a captured continuation value; its act's `pc−1` is a `:current-continuation` |
| `:parked-ref` | `parked-id-P` | unchanged |
| `:env` | `bindings-cref  store-of` | unchanged (§5.1) |
| `:bindings` | `S₁ V₁  S₂ V₂ …` sorted by symbol | unchanged |

`:window`, `:bindings`, `:store` and every other tabled keyed row are
structural rows, never a `V`; §4's "a reference-free collection must be
`[:lit d]`" rule is about guest collections. `rd` on `:act` is redundant
with the instruction at `pc−1` and is checked (continuation §3.2 rule
A2), as `pc` is redundant with the pending and checked. The frame rows
are in bijection with the keyed canonical frame of continuation §1.2
(`rows->frame`, `frame->rows`, continuation §2.7).

### 5.3 Pending (content rows)

One kind, `:pending`, whose first slot is the reason and whose remaining
slots are fixed per reason. The reasons are UCF §7.4.3's closed union as
the amendment r5 required-keys list fixes them; `:park` and
`:call-effect` are not reasons and refuse as `:yin.k/undecodable`.

| reason | slots after the reason |
|---|---|
| `:next` | `cursor-cell-C` |
| `:put` | `stream-cref  value-V  op-id-D` |
| `:ffi` | `cursor-cell-C  call-id-D  op` (op may be nil) |
| `:ffi-request` | `call-id-D  envelope-V  request-stream-cref  response-stream-cref  response-cursor-cell-C  op-id-D` |
| `:link-request` | `link-id-D  name-S  request-stream-cref  response-stream-cref  cursor-cell-C  envelope-D  op-id-D` |
| `:link-response` | `link-id-D  name-S  request-stream-cref  response-stream-cref  cursor-cell-C` |
| `:install` | `name-S` |

The retained FFI request's `envelope-V` is the complete envelope map,
recursively encoded under §4's ordinary rules: `[:lit m]` when it
reaches no reference (the common case, arguments such as `[1 2]`), a
`:map` row otherwise. Lower decodes it and retries it verbatim; it
never recomputes the arguments. Correlation must agree, not merely be present:
a cursor cell's `:cursor-cell-body` stream is the pending's response stream; an
envelope's link id or call id equals the pending's; a `:link-request`
envelope names the pending's module. `op-id` is `{:yin.k/occurrence P :yin.k/seq n}` as plain data or nil,
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
| `:store` | `k₁ V₁ k₂ V₂ …` sorted by key; the isolated slice of UCF §7.6.2 |
| `:module-store` | `manifest-M  store-cref` |
| `:parked-table` | `P₁ state-cref₁ P₂ state-cref₂ …` sorted by P (each state an `:act` or a `:tail`) |
| `:install` | `name-S  phase  parent-D  response-D  child-cref` |
| `:cursor-cells` | the census, §3 |
| `:cursor-cell-body` | `stream-cref  position` (position in the `:dao.stream.remote/v1` portable cursor domain) |

Validation of a parked state reads decoded task context, never an
engine: the table key `P` and the set of call ids the task's live
`:ffi`/`:ffi-request` pendings carry are the `task` input of
`frame/defect` (continuation §3.1), under which an `:act` at a `:park`
site is an explicit park and an `:act` at an `:ffi-call` site or a
`:tail` at a tail call under a live call id is FFI bookkeeping.

Resource entries never appear in `:store`; streams and cursor cells lower into
the receiver's private resource table beside the program store (linker
M4, `yin.vm.linker.md` 7.3). `:module-store` rows are the per-module
snapshots of §7.6.2's M4 paragraph. The parked table carries every
parked record (continuation §2.5). An `:install` row carries the complete
entry of
§7.4.3 r5: phase `:running` or `:parked`, non-nil parent link id, the
response verbatim as structural data, and the
child as a cref to a whole `:task` row in the child role. The intended "verified" contract
requires receiver verification, never trust merely because it decodes:
the response's manifest names the module the row is keyed by, its link
id equals `parent`, its image is well formed, hashes to the address it
is delivered under, is the image the manifest names, and its format,
contract and the child's profile agree (`yin.vm.linker.md` §5, §9;
amendment §2). A failure is `:yin.k/undecodable` naming the row.
**Amended after 7 (2026-10-14)** — Reason: 7b enforces install link and manifest/image agreement at lower.
The merged lower (`rows/check-install!`) verifies, before any attachment:
the phase is one of `#{:running :parked}` (`:install-phase`); the
response is a map with `:status :ok`, a `:manifest` whose
`:yin.module/name` is the row's module and whose `:yin.module/exports`
is a collection of symbols, and an `:image` map whose `:value` the
receiving kernel's loader admits (`:install-response`); and the carried
child task's kind is `:blocked` (`:install-child`), the only kind the
engine retains at a safepoint (a halted or parked child is linked in the
sweep it halts in, so phase `:running` is a wire phase the codec accepts
but the engine never exposes at a safepoint). Lower also requires
non-nil `parent` equal to response `:yin.link/id` (`:install-link-id`).
The response's `:derivation` record must hash to the manifest's
`[:yin.module/derivations :yin.semantic/code]` address; its
`:yin.ledger/input` must equal `:yin.module/tree` and its output must
hash-identify the image's `:value` vector (`:install-image`). These are
response self-consistency checks; the linker's source-index, fetch and
admission path binds code and trees to composition-known addresses.
A remains the only code identity and the Universal AST is never lost.
No new resolver or independent claimed-image-address check is added.

### 5.5 The root

```clojure
[T :task
   version          ; 3                       (gated first, §8)
   contract         ; {:yin.code/contract "v4" :yin.k/version 1}, the one profile map (§2)
   kind             ; :blocked | :parked | :halted
   role             ; :root | :child
   id-counter       ; N                       (UCF §7.6.3)
   free-env-cref    ; a :bindings row, the task's initial free environment (continuation §6.3)
   store-cref
   module-stores    ; crefs sorted by M
   parked-cref      ; :parked-table
   cursor-cells-cref       ; :cursor-cells census
   requires         ; plain: UCF §7.6.1's map plus :yin.k/trees (continuation §6.1)
   code             ; plain: {A canonical-vector …}, "v4" vectors verified to hash to their key
   installs         ; crefs to :install rows, sorted by module name
   frames           ; crefs to :wait rows, IN WAIT ORDER   (:blocked, :parked)
   parked-id        ; P or nil                              (:parked only)
   result           ; V or nil                              (:halted only)
   custody]         ; plain, see below
```

(Root replaced 2026-10-11 by continuation §2.6: `free-env-cref` added,
`contract` is the one profile map, `code` holds `"v4"` canonical vectors,
`requires` gains `:yin.k/trees`.) No `layout` slot and no native image:
those were the positional profiles' (v2 amendment §2, §4.1).

Kind rules are §7.2.1's: `:blocked` has nonempty frames and neither
parked-id nor result; `:parked` has frames (possibly empty), a parked-id
naming a key of the parked table, no result; `:halted` has a result and
neither frames nor parked-id. Frames restore in carried order and are
never collapsed to one; a task with an empty ready queue may hold several
ordered waits.

**Amended after 7 (2026-10-14)** — Reason: the owner chose nil forks and phase 7 restricts custody keys.
`custody` is the custody arm carried as plain data, under the rules of
§7.2.1 and amendment §3, by role, kind and policy:

| role | kind | policy | `custody` |
|---|---|---|---|
| root | blocked / parked | exclusive | `{:yin.k/policy :yin.k/exclusive :yin.k/occurrence :yin.k/arbitration :yin.k/next-op-seq}` plus `:yin.k/origin` on every successor, absent on a first export |
| root | blocked / parked | fork | nil |
| root | halted | exclusive | `{:yin.k/origin …}` only: a result is not a lease subject |
| root | halted | fork | nil: a fork halt has no custody data and a first fork halt has no origin to carry |
| child | any | — | nil; validated with its root's occurrence, origin and counter passed down |

Fork custody is nil in every row; it proposes nothing and is never
upgraded to custody. An exclusive-only receiver refuses a fork as
`:yin.k/profile-mismatch`. The slot admits only `:yin.k/policy`,
`:yin.k/occurrence`, `:yin.k/arbitration`, `:yin.k/next-op-seq` and
`:yin.k/origin`. No current lease, epoch or holder enters a body;
origin's predecessor lease is lineage evidence.

The root is a content row and **its id is the transported state's
semantic identity**, the `B` of UCF §7.3.4 for this carrier. Occurrence and origin
are inside it, as the governing rule keeps them (§10.1). The code map is
plain data in the root rather than a row kind: each instruction vector
keeps its own address `A` computed exactly as UCF §7.3.2 computes it, and
the carrier verifies, never recomputes, that address.

## 6. Canonical cursor-cell numbering

Cursor-cell ids must be a function of the state, or two lifts of one state give
two root ids. Local rules are not enough: a cursor cell's number must depend on
everything that distinguishes it, including references to it from
elsewhere in the task (the review's counterexample: `'a` bound to a set
holding cursor cells X and Y with equal bodies, `'b` bound to X; any rule that
looks only at the set and the bodies cannot tell X from Y, but `'b`
can). The numbering is therefore defined over the **whole alias
topology** of the task, and the definition is the reference against
which any algorithm is checked.

**What is defined, and what is not claimed.** The canonical numbering is
defined as the output of the discovery-order procedure below, a function
of the state up to renaming of cursor cells and host enumeration of guest
sets, maps and the census (proof in
`yin.vm.semantic-register-vm.cursor-cell-numbering.md` §2). It is not the
minimum of the root bytes over all bijections. The one choice the walk
cannot make by itself, between tied members of one guest set or map that
the tie test cannot prove interchangeable, is refused
(`:unresolved-cursor-cells`); the refusal certifies nothing about
interchangeability and can be narrowed later without changing any
numbering assigned today.

**The rule in one paragraph.** A cursor cell is named by the order in
which a fixed walk over the task meets it: the n-th cursor cell met is
`:yin.k/cc-n`, as a de Bruijn index names a binder by position. The walk
is pre-order in the table order of §5, so every position is ordered
except the members of a guest set and the entries of a guest map. Those
the carrier *transforms* into ordered vectors before numbering: each
member is given a key, the spelling of the member in which cursor cells
already numbered appear by number and the others by body, occurrence
count and the order in which they first appear inside the member; the
members are sorted by key. Members whose keys are equal are *tied*; they
are postponed until the rest of the walk has numbered what it can, and
re-keyed. A tie that survives is entered in any order if a cheap test
proves its members interchangeable (swapping them leaves the whole state
unchanged); otherwise the lift refuses. Guest programs are unrestricted:
sets and maps may hold cursor-refs anywhere, including as map keys.

**Vocabulary.** An **item** is a row or a value. The **walk** is the
procedure below over one item from a numbering; the **root walk** is over
the `:task` row from the empty numbering and its numbers are the output;
a **key walk** is the same procedure over one member of a set or map from
the enclosing walk's numbering, run only to produce the member's **key**.
A **member** is a `:set` element or a `:map` entry taken as the synthetic
ordered row `[:entry key value]`. A **tie group** is a run of members of
one container with equal keys. A cursor cell's **body rank** is the index
of its census body label `[:body [:stream identity descriptor] position]`
among the census's distinct labels in bytewise order; its **occurrence
count** is the number of positions of the task (census and install
children excluded) that hold it. Install children are numbered as their
own tasks by the same rule, in any order.

**The transformation and the key.** When the walk reaches a `:set` or
`:map` it replaces the container, for its own purposes, by the vector of
its members in key order. The key of a member is the **spelling** of that
member produced by a key walk under the numbering at that moment (every
member of one container is keyed from that one numbering):

- a cursor cell numbered by the enclosing walk spells `[:cursor-cell
  [:n k]]` (root numbers) or as the enclosing key walk spelled it;
- a cursor cell the key walk meets unnumbered receives a **local index**
  `j` (the order of first meeting, counted from the enclosing walk's
  local counter so a nested key walk never reuses an index of its
  enclosing one) and spells `[:cursor-cell [:u j r m]]`, `r` its body
  rank and `m` its occurrence count, all exact CBOR integers;
- `[:lit d]` as itself, structural data as itself (by reference, never
  copied), `[:row B]` expanded in place, the census as `[:cursor-cells]`,
  an install child as `[:child]`, keyed structural rows in canonical key
  order, `:window` in register order, and a nested `:set` or `:map` as
  the bytewise sorted vector of its members' spellings in which a tie
  group the key walk could not certify (the tie test below) is spelled in
  place as `[:tie K … K]`, its common key once per member.

Keys are compared bytewise on canonical CBOR; the spelling is hash-free.
`[X X]` keys `[:vec [u0 r m] [u0 r m]]` and `[Y Z]` keys
`[:vec [u0 r m] [u1 r m']]`: the alias pattern inside a member is part
of its key, and so is each cell's occurrence count, which separates
`#{[A B] [B C]}` and `[#{X Y} #{Y Z}]` without a search.

**The walk.**

- *W1, order.* Depth-first, pre-order, from the walk's item. A row's
  slots in table order (§5); ordered repeated slots (`frames`, `:vec`,
  `:list`, `:k`) in order; keyed rows with structural keys (`:bindings`,
  `:store`, `:parked-table`, the root's `installs` and `module-stores`)
  in the Jing canonical byte order of the key; `:window` in ascending
  register order. The census is not entered; an install child's `:task`
  row is not entered. A `:set` or `:map` is entered as the transformation
  orders it: members with a unique key in key order, an entry's key
  before its value; each tie group is **postponed** to the walk's queue
  (queue order is canonical: containers in walk order, groups within one
  container in key order).
- *W2, meeting.* When the walk reaches a `[:cursor-cell C]` value or a
  `C` slot of a `:pending` row whose cursor cell has no number, the cell
  receives the next number (the root walk counts from 1). A numbered cell
  is passed over. Every occurrence emits `[:cursor-cell :yin.k/cc-n]`;
  the census row stays the contents table.
- *W3, postponed ties.* After the pre-order walk, the queue is taken in
  order: each group's members are re-keyed under the current numbering
  and treated as in W1 (unique keys entered in key order, surviving ties
  re-queued in order; a tie met inside an entered member joins the same
  queue). Repeat while a pass numbers at least one cursor cell and groups
  remain. When a pass numbers nothing, the surviving groups go to the tie
  test.

**The tie test.** A surviving group **passes** when all of (i) its common
key is complete (contains no `[:tie …]`), (ii) the unnumbered cursor
cells of its members are pairwise disjoint, and (iii) none of them occurs
in a member of any other surviving group of the same walk. (At this point
every unnumbered cell occurs only in postponed members, so (ii) and (iii)
say: the cells of each member are confined to that member.) If every
surviving group passes, all of them are entered, each group's members in
the order the host lists them (the numbering note's §2 shows the rows do
not depend on it), and the walk returns to W3. If any group fails, the
**root walk refuses** and a **key walk stops**: its surviving groups are
spelled as `[:tie …]` residue and its key is incomplete. The test inside
a key walk reads only the member being keyed; it never reads the
enclosing state. The test is sound and cheap: (i)–(iii) are exactly what
the proof needs for the index-matched swap of two members to be an
automorphism of the state; whatever it refuses may still be
interchangeable (numbering note §3 says what), but nothing it accepts can
be host-order dependent.

**Refusal.** The root walk refuses `:yin.k/non-portable` kind
`:unresolved-cursor-cells`, `:yin.k/path` the row path of the container
holding the first failing group in queue order (a container nested under
a member of a set or map has the enclosing container's path followed by
`:member`, since no index into an unordered container is canonical; for
an install child the path is prefixed `[12 <install name> …]`), and
`:yin.k/cursor-cells` the sorted unnumbered cursor cells of the failing
groups' members. The verdict and the path are functions of the state up
to renaming and host enumeration; the cell list names local source ids.
This is not a resource refusal and never `:noncanonical-cursor-cells`.
The receiver's recomputation (§8 step 6) translates only this refusal,
for the root or any install child, to `:yin.k/undecodable`
`:unresolved-cursor-cells` with the path prefixed by the carried root id
(a child's path reads `[<root> 12 <name> …]`); a resource refusal
propagates as itself. Numbering is checked after step 5 and before frame
validation (step 7), so a wrong numbering is reported as
`:noncanonical-cursor-cells` and an unresolved one as
`:unresolved-cursor-cells` even when the task also has a frame defect.

**Budgets and gates.**

- `:cursor-cells`: the census's pair count is charged first, before the
  census map, ranks, counts or any walk is built.
- `:depth`: enforced second, **before any recursive traversal of the
  task**, by one bounded iterative scan (an explicit stack, never the
  host stack) over rows, members, structural and literal data and the
  census, install children excluded (each is gated by its own run). The
  depth of a position is its slot steps from the task (a member step
  counts one, a map entry's key or value two) plus, inside data, its
  collection nesting; a position deeper than `:depth` refuses
  `[:depth]`. After the scan every recursive read of the task (cell
  scans, occurrence counts, memo-key hashing, spelling, encoding) nests
  at most `:depth` levels, so no input can overflow the host stack
  before the gate fires. The walk checks its own depth as well. Default
  256.
- `:work`: one unit per position scanned (the depth scan), visited
  (walk, key walk, cell scans, count and rank preparation), keyed (one
  per member per keying, so the number of key walks and memo lookups is
  charged) or spelled, one unit per cell in a key walk's memo key, and,
  **before every CBOR encoding made for a comparison** (keys, rank
  labels, structural keys), the representation bound of the value
  encoded: 128 per node or scalar, 4 per character, 16 plus the length
  per byte payload, an upper bound of the encoding's bytes. The
  estimator is iterative and budget-aware: it runs against the budget
  remaining at the call and stops as soon as its running total exceeds
  it, so it never reads to the end of a value it cannot afford.
  Spellings hold input literals by reference, so their own size is their
  spelled positions. Every comparison encoding is thus charged before it
  is made, by a conservative abstract bound of its encoded bytes and of
  the encoder's intermediate nodes; the budget bounds allocation up to a
  constant factor of the host's representation, not byte for byte, and
  the input itself is not counted. No polynomial bound is claimed; the
  budget is the bound. Default `4194304`. A literal of more than about
  30000 scalars inside a guest set or map exceeds it; in an ordered
  position a literal is never spelled or encoded and costs one unit per
  position of its data in the depth scan.
- `:rows`, `:bytes`: the decoder's, unchanged.

Exceeding any budget is the resource refusal of §10.10
(`:yin.k/unsupported :resource-limit [<key>]`), never a canonicality
verdict, at lift and at decode alike.

**Totality.** The root walk ends with an empty queue or a refusal. Before
emission the implementation asserts that the numbering is a bijection
from the census's cursor cells onto `1 … n`; a violation (a census cell
no walk reaches) is a defect, an exception without `:yin.k/status`
carrying the missing and extra cells, never a refusal and never a
partially numbered carrier.

**Validation.** Canonicality is a property of the emitted rows, not of
a source-cursor-cell-to-number bijection, so the receiver does not compare
numberings. It decodes the rows to the abstract task, runs the
procedure, re-emits rows, and compares the resulting root id with the
carried root id: unequal is `:yin.k/undecodable` kind
`:noncanonical-cursor-cells`. Every labeling that yields the selected
commitment is thereby accepted. The emitted commitment and the acceptance
decision are invariant under the host's enumeration order of every set,
map and census (numbering note §2); the procedure's one use of host
order, entering the members of a tie group proved interchangeable,
cannot change the rows, and a receiver never decides validity by its own
enumeration order. This is a lower-side structural refusal, not a lift
kind. A receiver whose work bound (§10.10) is exceeded before the
procedure completes refuses with the structured resource refusal before
any attachment, never labels the state `:noncanonical-cursor-cells`, and
never accepts an unfinished check; a receiver that reaches the tie
refusal refuses `:yin.k/undecodable` kind `:unresolved-cursor-cells`.

Under this rule two lifts of one state are byte-identical. Two lifts of
different states sharing a closure over the same cursor cell may number it
differently and so not share that closure's row across lifts; that
cross-lift dedup is knowingly given up for identity-bearing values and
never affected rows below a cursor cell. On lower, each `C` becomes one fresh
private resource entry seeded at its carried position; every `[:cursor-cell C]`
maps to that entry; two references to one cursor cell share it and one
reference per cursor cell keeps its own (UCF §7.5.3).

## 7. Equality and the content address

**Amended after 7 (2026-10-14)** — Reason: content stores verify the whole pack's bytes, not the root row's hash.
The transported state is a DAG rooted at the `:task` row. The root id
commits to reachable content and cursor-cell contents through the census;
equal root ids express semantic equality under §6's canonical numbering.
The **pack address** is BLAKE3 of the canonical CBOR bytes of the whole
pack. It is the content-store key, ledger `:yin.k/id`, grant checkpoint,
offer variant id and report `:yin.k/result`; it differs from the root id.
The baseline records the root as `:yin.k/root`.

`emit` sorts `:yin.k/rows` by the string of each row id, so canonical
emission yields one pack for one root on each host. `decode` still
accepts other row orders. Reversing rows therefore gives another pack
address with the same root and baseline; re-emitting an id-counter change
changes the root and is `:variant-conflict` (7a D2). Occurrence and origin
remain inside the root's hash. The holder stores the whole pack as one
object, after recovery format 2 and before journaling the fence; it does
not store rows separately.

## 8. Validation on lower

Validation is pure until the last step and checks the whole tree before
one attachment. The order follows amendment §8 and is binding:

1. **Gate.** Decode the wire map's canonical bytes retaining numeric
   classes; the tag is present and the outer version is the exact
   integer 3, else `:yin.k/profile-mismatch` (version found, supported
   set). Reject duplicate row ids and a `:yin.k/root` that names no
   `:task` row (`:yin.k/undecodable`). Walk install children from the
   root in canonical module-name order with an **active recursion-path
   set** (§3), following only `:install` child crefs; a cref outside the
   rows, a child already on the active path, or a non-`:task` child
   stops the gate as `:yin.k/undecodable`; a child reached again off
   the path is gated once and its result cached. Gate every
   child's version, then every profile map against the one profile map
   of §2, then require all versions 3
   and all profiles equal to the root's. Malformed task structure is
   `:yin.k/undecodable`; an unsupported version or profile is
   `:yin.k/profile-mismatch`; the two are never conflated.
2. **Structure, non-recursive.** Every row's kind is tabled with the
   right arity; every structural slot holds its tabled kind by local
   inspection; every `V` has one of the three tags; every `D` is in the
   canonical bytes domain; exactly one row has role `:root`; kind rules
   of §5.5 hold; the custody slot matches the role, kind and policy
   table. Nothing in this step follows a reference.
3. **Graph.** Every cref and every `[:row B]` names a present row of a
   kind admissible in that position; the structural edge family is
   acyclic (checked with an active-path walk, bounded by the row count);
   every row is reachable from the root through structural edges (an
   unreachable row is `:yin.k/undecodable`); every `[:cursor-cell C]` names a
   census entry and every census entry is reached **through the task's
   own roots, the census's declaration edges excluded**, per task and
   per child; every `:parked-ref` and `parked-id` resolves in the parked
   table; every `store-of` resolves to a carried module store; every
   `:install` pending has its entry and every entry's child is a
   `:child` task. After this step every traversal below is finite and
   safe.
4. **Hashes.** Every content row's id equals `(jing/segment-key body)`
   under the algorithm version 3 mints with, and its address names that
   algorithm; every code payload hashes to its key `A` and is well formed
   under the stamp; the requested body address, if one was given, equals
   the root id. Mismatch is `:yin.k/hash-mismatch` naming the row or
   code key.
5. **Canonical form, recursive.** Every literal is in the portable
   domain; every sorted slot is in the §5 order; every keyed row has
   unique keys, and every `:set` row unique elements, under canonical
   bytes and under portable decoded equality; optional absence has its
   one spelling (nil in the tabled slot); a reference-free collection is
   spelled `[:lit d]`, never a row; empty collections are literals;
   metadata obeys §4. A breach is `:yin.k/undecodable` naming the row
   and slot. Without this step two different valid encodings could lower
   to one state, or a `:bindings` row could carry one symbol twice and
   lose a binding on lower.
6. **Canonical numbering.** Recompute §6 under the work bound, for the
   root and every install child, and compare (`:yin.k/undecodable`, kind
   `:noncanonical-cursor-cells`; kind `:unresolved-cursor-cells` if the
   tie refusal is reached in the root or in a child, translated from the
   recomputation's lift-side refusal of that kind only, its path prefixed
   by the carried root id; or the resource refusal of §10.10 if the bound
   is exceeded, which propagates untranslated).
7. **Frames.** For each body (the root and every install child), first
   decode its task context (parked key, live FFI call ids, the root's
   free environment from `free-env-cref`) and judge it once by
   `yin.vm.semantic-register.frame/task-defect` (continuation §3.2 rule
   X1: the free environment is a sorted, unique symbol-keyed map with no
   reserved name); this runs for every body, a `:halted` body with no
   frames included, since no frame rule establishes X1. Then every
   `:act`, `:tail`, `:kframe`, `:reified` and parked-table state is
   decoded to its keyed frame and judged by
   `yin.vm.semantic-register.frame/defect` with its context (`:wait`,
   `:captured`, `:parked`) and that task context; a defect of either
   function is `:yin.k/undecodable` with the rule's kind and the row path
   (continuation §3.3). This step runs after canonical numbering (step 6)
   and before pending validation (step 8).
8. **Pending and operations.** Reason-specific slot rules; correlation
   agreement of §5.3 (cursor-cell stream, envelope ids, module names); every
   op-id below `next-op-seq`, unique across the tree, absent without
   origin; every install response verified as §5.4 requires.
9. **Dependency closure.** Compute the census of amendment §6 from the
   rows (waits, pendings and envelopes, parked records, the initial free
   environment `free-env`, store, module stores, result, children; the
   free environment is a root because a value reachable only through it
   can carry closures, streams and primitives, continuation §7.1) and
   require the carried `requires` and `code` map to agree with it
   exactly: a declared segment not reached or a reached segment not
   declared is `:yin.k/undecodable`, and every reached segment has a
   non-empty `:yin.k/trees` entry (UCF §7.6.1a). Then
   evaluate `requires` per UCF §7.6.5: missing segments, primitives,
   modules, streams, cursor profiles are `:yin.k/unsatisfied`; a body
   with cursor cells claims `:dao.stream.remote/v1`; module-store coverage is
   complete for every `store-of` reached.
10. **Custody.** For an exclusive root, the occurrence, baseline and
    operation inspector and admission checks of §7.7.8; an authority
    never adds missing header keys.

**Amended after 7 (2026-10-14)** — Reason: phase 7 runs custody once after three per-tree stages.
`rows/lower-task` runs stores, pendings/install records and dependencies
**each over the whole task tree before the next**, then custody once for
the tree, all before the first attachment (step 11): (a) stores: no reserved name keys the root store or a module store
(`:yin.k/undecodable :reserved-name`); (b) pendings and install
records: role, every `:install` pending names a carried install, kind
consistency, parked id, every install record as §5.4 as amended
(`:role :incomplete-install :kind :parked-id :install-phase
:install-response :install-link-id :install-image :install-child`); (c) dependencies: `requires`'
`:yin.k/segments` equals `code`'s key set (`:requires`), every segment
has a non-empty `:yin.k/trees` entry, every `store-of` of this task's
closures and environments names one of **this task's** module-store
rows (`:yin.k/unsatisfied`, `:yin.k/missing {:yin.k/stores …}`), every
vector attaches in the receiving kernel under its A (`:segment`), every
closure row names a closure tuple of its vector with the same binders
(`:closure-origin`, `:closure-binders`), every `:prim` is the receiver's
(`:yin.k/unsatisfied`); (d) custody: `custody-baseline` validates the
slot and op-ids across the tree, refusing fork ids, missing origin,
self-occurrence, out-of-range sequences and duplicates. A fork under
`:exclusive` refuses; halted exclusive needs no grant; blocked/parked
exclusive checks grant evidence, the pack checkpoint, binding, tenure,
input prefix and protection/enrollment in both directions before attach.
`:fenced` skips grant/protection and restores exporting state (§9).
The broader correlation agreement remains unimplemented
(continuation §11 Q4); of step 9's, the census is not recomputed from the
rows (continuation §11 Q2), and `requires` beyond segments, trees and
primitives (modules, streams, cursor profiles) is not evaluated on
lower; free names are discharged at lift only (continuation §7.1).
Refusals are thrown `ex-info` with `:yin.k/status` and a `:yin.k/kind`.
11. **Composition.** Protection, resource, primitive and grant inputs of
    the receiving composition.
12. **Restore.** Reconstruct isolated code spaces, fresh values, the
    isolated store, module-store instances (first link wins), one
    private resource entry per cursor cell, the parked table, children first
    (one native task instance **per install entry**, even where two
    entries share a child row), then waits in order; adopt `max(local,
    carried)` for the id counter; publish the machine only after every
    child succeeds; run, poll, mint, append, close or initialize nothing
    during lower.

Diagnostics use the established families and no new top-level outcome:
`:yin.k/profile-mismatch`, `:yin.k/undecodable` with `:yin.k/path` as
`[row-id slot-index …]` and a `:yin.k/kind` where one is named above
(`:reason-mismatch`, `:noncanonical-cursor-cells`,
`:unresolved-cursor-cells`), `:yin.k/hash-mismatch`,
`:yin.k/unsatisfied`, and on the lift side `:yin.k/non-portable` with
the three kinds this carrier adds to UCF §7.5.4's set: `:byte-string`,
`:metadata` and `:unresolved-cursor-cells`.

## 9. Lift, lower, and the laws

**Lift** (reference machine): refuse unless quiescent; number cursor cells (§6);
walk waits, parked records, the slice, module stores, the initial free
environment (continuation §6.3, §7.1), the result and each install
child, emitting rows and the census; refuse by path as UCF
§7.5.4 and §7.2.1 specify. **Amended after 6b (2026-10-13; continuation
§7.1 B4):** the merged order is quiescence and safepoint, kind, free
environment, the walk (which fixes the segments, contexts and module
stores), code, the per-context dependency closure over live code, tree
coverage from the composition's `:source-index`, frame validation of
the assembled task, then canonical numbering and emit; the slice is the
whole root store and every parked record.

**Amended after 7 (2026-10-14)** — Reason: phase 7 adds the header and custody checks after frame validation.
`:header` nil means fork; otherwise enrollment remains local and the root
slot is written by kind after exhaustion/protection checks. The lift then
runs `custody-baseline` for the tree and emits sorted rows (§7). It can
serve streams during the walk before a custody refusal; the holder
retains successful answers for retries. The nested carrier's value table
(`:yin.k/values`) has no counterpart, sharing being Merkle; its cursor cell
table becomes the census.

**Lower**: §8 in order, then restore.

**Amended after 7 (2026-10-14)** — Reason: format-2 recovery uses an administrative fenced lower.
`rows/lower-task {:fenced true}` runs the carrier checks and custody
grammar before attach, skips grant/protection, then gates every task
`:exporting`. Waits and all parked records move to
`:yin.k/recovery-waits` and `:yin.k/recovery-parked`, outside the scheduler;
the id counter is the carried value exactly and
`:yin.k/recovery-cell-ids` retains resource-key/cursor-cell associations.
Child spawning disables stream construction and initializes nothing.
The recovery reader checks its local header and `:yin.k/state` against
the pack before lower, extracts the export record, and requires identical
re-encoded pack bytes. It restores no tenure or runnable gate and refuses
abort after restart. Ordinary resume still adopts `max(local, carried)`.

The laws, with the domains the Architect's finding 12 requires:

- **Canonical round trip.** For every valid, canonical (§6-numbered)
  version-3 state `S` under the one profile of §2 **for which `lower(S)`
  succeeds** (adequate composition, resource and admission inputs; a
  refusal is not a counterexample), and with no execution between lower
  and lift, `lift(lower(S)) = S` by root id under the **fresh-receiver
  reconstruction context**: the receiver's id counter is 0 so
  `max(local, carried)` is the carried value; occurrence, origin and the
  custody arm are supplied to the lift from the restored task's own
  record, not minted; cursor-cell bodies and op-ids are carried verbatim; the
  lift's §6 numbering undoes resource remapping. Outside that context
  (a receiver with a larger counter, or any step taken) the law does not
  apply and native equivalence is the claim.
- **Native observational equivalence.** `lower(lift(m))` need not equal
  `m` as native state (fresh ids, seals, private keys); under equivalent
  external inputs it must preserve the next observable step, the wait
  set and its order, the result, and the effect trace.
- **Cross-engine.** Running the corpus to each safepoint on an engine
  that implements canonical semantic-VM execution yields, under
  controlled composition identities (occurrence, origin, resource
  descriptors fixed by the harness), the same version-3 row set the
  reference machine yields.

The UCF §7.11 harness gains one assertion per law. The Stage D/E corpus,
re-encoded as version-3 rows, is the oracle for the first law.

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
   canonical encoding, distinct from the semantic root id; cursor-cell ids
   remain root-scoped inside it. Same answer as code-as-tuples §2.1
   should receive; the loader interface is resolved explicitly, not by
   layout.
3. **The nested grammar.** Rows are the carrier authority.
4. **Contract stamp.** The code contract carried is `"v4"`, the
   register-shaped machine's; the carrier version is 3; the two are
   independent, and neither names a legacy value.
5. **Reified and parked.** Sharing immutable `:act` rows is acceptable;
   the two keep distinct outer kinds (§5.1) and distinct lowering. A
   reified capture has no pending; a parked reference resolves in task
   context.
6. **Foreign-engine value profiles.** Required before an implementation
   brief claims execution conformance for an engine **outside the three
   repository lanes** (the semantic-register kernel and the stack and
   register kernels, whose reconstruction is the layouts of
   `yin.vm.semantic-register-vm.continuation.md` §4 and needs no value
   profile), and not drafted here: admitted values, numeric behaviour,
   key equality, metadata, resource and profile requirements, refusal
   sets, and safepoint reconstruction obligations. Slot losslessness
   alone does not establish execution parity; version 3 carries the
   canonical frame; a stack or register kernel reconstructs it through
   its layout (continuation §4), which is heterogeneous reconstruction,
   not translation between native formats; foreign-engine *value*
   profiles for engines outside this repository remain a separate
   document.

Answers 7–9 are the Architect's (round 2) and are adopted (7 and 8 are
deleted 2026-10-11: there are no other profile tables and no legacy
reader):

9. **Skeleton placeholder.** Safe only if the full value-union tag is
   retained in the skeleton, which §6 now requires (`[:cursor-cell <class>]`,
   never a bare class); and a placeholder does not canonicalize alias
   topology, which is why §6 is now defined as the output of the
   discovery-order procedure, which is invariant under renaming and host
   enumeration.

10. **Receiver work bound** (Architect, round 3; adopted as a
    requirement, with the limits left to implementation and
    composition). Before an implementation brief, the receiver must have
    a bound on decoded bytes, rows, cursor cells, traversal depth and
    traversal-and-spelling work for §6 (its `:work` unit counts positions
    and representation bytes, charged before any spelling is encoded),
    with elapsed time as an optional
    operational limit on top. Exceeding a local budget produces a
    structured resource or capability refusal before any attachment, in
    the qualified-unsupported family `datom.world.md` already provides;
    it never labels a valid state `:noncanonical-cursor-cells`, never
    substitutes host enumeration order for the canonical walk, and never
    accepts an unfinished check. The
    exact limits and their refusal mapping are implementation and
    composition decisions and must not alter canonical bytes.

**Proposals, not consequences.** The following are design choices this
document makes; they follow neither uniquely from the owner's three
rulings nor from the six invariants, and an implementation brief must
name them as choices being accepted: a new body version (3) rather than
another carrier identification; the canonical representative of §6 as
defined by discovery order, and the `:unresolved-cursor-cells` refusal;
the wire unit and pack framing of §3; the
explicit fork-policy spelling of §5.5; one engine-less profile
(continuation §2.1); the explicit root `free-env` (continuation §6.3);
the byte-string and metadata restrictions of §4; binding the row digest to
the carrier version rather than to a named algorithm (§3; the owner
ruled 2026-10-10 that rows use the same digest as code addresses, which
is Jing's default, rather than a separate SHA-256 pin); and positional
rows over EAV (§1). Preserving the complete FFI
envelope (§5.3) is *not* on this list: it is the inherited behaviour,
and narrowing it would have been the new protocol decision.

Still open after this revision: none beyond the work-bound limits
(§10.10).

## 11. What this does not change

Code identity (A), safepoints,
pending-wait semantics and the r5 required keys, install-child rules,
the dependency closure and satisfaction check, the isolated store, the
private resource table, module-store instancing, scheduler state, the
custody header and admission rules, fencing and operation ids, the
outcome families, and the existing refusal kinds (three lift kinds and three
`:yin.k/undecodable` kinds are added, §8). The carrier is a new body
version for the same facts. The
Architect's round-1 statement is the acceptance bar: tuple transport
violates none of the six invariants once polymorphic references and
context edges are specified and validated, and §4, §3 and §8 are where
they are.
