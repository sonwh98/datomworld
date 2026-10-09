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
closures in full, and **version 3 declares support for the semantic
profile only**: a version-3 body whose profile map names `:stack`,
`:register` or `:walker` fails closed at the profile gate
(`:yin.k/profile-mismatch`) until those profiles' row tables and
validation rules are published against amendment §5.2–5.4, with their
code addresses and profile maps unchanged. Like version 2,
version 3 transports native suspended state within a profile and does not
translate between kernels. The foreign-engine case (an engine that
implements canonical semantic-VM execution in its own layout, UCF §7.4.2)
is the semantic profile lowered through that engine's value profile, and
value profiles are a separate document (§10.6).

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
instance with its own task-local cells and its own contextual custody
validation. Completed content validation is cached per row; instance
restoration never is. When a pack is addressed (§10.2) this map is what
is hashed, and a `:yin.k/root` that does not name a `:task` row in
`:rows` is `:yin.k/undecodable`.

**Content rows** have `id = (dao.jing/segment-key body {:algorithm
:sha256})`: this carrier pins SHA-256 explicitly, because
`segment-key`'s one-argument default is Jing's own default algorithm
(BLAKE3 at the time of writing) and a canonical address scheme must be
unique to be canonical. An id carrying any other multihash is
`:yin.k/undecodable`. Code addresses `A` are not touched by this pin:
they keep their governing address function (`ucf/code-address`), and
the carrier verifies them under it. The digest choice is a proposal
(§10). Equal bodies are one row. The id is Merkle: it commits to every
row id in the body.

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
| `[:cell C]` | a reference to the identity cell `C` |

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
bytes of the sort key *as spelled in the emitted row*, cell numbers
included, under the final numbering of §6. The class-erased skeletons of
§6 are used only inside the numbering procedure and never decide the
order of an emitted row. Ordered slots are part of the schema because
they determine the Merkle id.

`N` is an exact CBOR integer in `[0, 2^52−1]` (amendment §2). `A` is a
code address, `M` a manifest address, `P` a parked id, `C` a cell id,
`S` a symbol, `D` structural data (§4). Each `[:row B]` position admits
only the row kinds its table entry names; a `[:row B]` whose target is
of another kind refuses (§8). Keyed rows (`:bindings`, `:map`, `:store`,
`:parked-table`, `:cells`, the installs and module-stores slots) must
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
| `:reified` | `regs-cref` | `{:yin.k/tag :yin.k/frame :yin.k/registers R}`: a captured continuation, no wait |
| `:parked-ref` | `parked-id-P` | `{:yin.k/tag :yin.k/frame :yin.k/parked-id P}`: resolves in the task's parked table; never duplicates `R` |

`:reified` and `:parked-ref` keep the two outer discriminants amendment
§6 requires and lower differently: a reified value becomes a
receiver-owned native continuation from `R`; a parked reference
reconstructs the engine's parked value for `P`. A reified value's
registers are validated in captured-value context (amendment §7): its pc
must be a `:current-continuation` site's successor, not a wait or park
safepoint.

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
| `:put` | `stream-cref  value-V  op-id-D` |
| `:ffi` | `cell-C  call-id-D  op` (op may be nil) |
| `:ffi-request` | `call-id-D  envelope-V  request-stream-cref  response-stream-cref  response-cell-C  op-id-D` |
| `:link-request` | `link-id-D  name-S  request-stream-cref  response-stream-cref  cell-C  envelope-D  op-id-D` |
| `:link-response` | `link-id-D  name-S  request-stream-cref  response-stream-cref  cell-C` |
| `:install` | `name-S` |

The retained FFI request's `envelope-V` is the complete envelope map,
recursively encoded under §4's ordinary rules: `[:lit m]` when it
reaches no reference (the common case, arguments such as `[1 2]`), a
`:map` row otherwise. Lower decodes it and retries it verbatim; it
never recomputes the arguments. Correlation must agree, not merely be present:
a cell's `:cell-body` stream is the pending's response stream; an
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
| `:parked-table` | `P₁ regs-cref₁ P₂ regs-cref₂ …` sorted by P |
| `:install` | `name-S  phase  parent-D  response-D  child-cref` |
| `:cells` | the census, §3 |
| `:cell-body` | `stream-cref  position` (position in the `:dao.stream.remote/v1` portable cursor domain) |

Resource entries never appear in `:store`; streams and cells lower into
the receiver's private resource table beside the program store (linker
M4, `yin.vm.linker.md` 7.3). `:module-store` rows are the per-module
snapshots of §7.6.2's M4 paragraph. The parked table holds exactly the
reachable records. An `:install` row carries the complete entry of
§7.4.3 r5: phase `:running` or `:parked`, parent link id (nil where the
contract permits), the response verbatim as structural data, and the
child as a cref to a whole `:task` row in the child role. "Verified"
means verified by the **receiver**, never trusted because it decodes:
the response's manifest names the module the row is keyed by, its link
id equals `parent`, its image is well formed, hashes to the address it
is delivered under, is the image the manifest names, and its format,
contract and the child's profile agree (`yin.vm.linker.md` §5, §9;
amendment §2). A failure is `:yin.k/undecodable` naming the row.

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

`custody` is the custody arm carried as plain data, under the rules of
§7.2.1 and amendment §3, by role, kind and policy:

| role | kind | policy | `custody` |
|---|---|---|---|
| root | blocked / parked | exclusive | `{:yin.k/policy :yin.k/exclusive :yin.k/occurrence :yin.k/arbitration :yin.k/next-op-seq}` plus `:yin.k/origin` on every successor, absent on a first export |
| root | blocked / parked | fork | `{:yin.k/policy :yin.k/fork}` |
| root | halted | exclusive | `{:yin.k/origin …}` only: a result is not a lease subject |
| root | halted | fork | nil: a fork halt has no custody data and a first fork halt has no origin to carry |
| child | any | — | nil; validated with its root's occurrence, origin and counter passed down |

The explicit fork policy spelling projects onto the amendment's
empty-header fork representation: a version-3 fork root is admitted
exactly as a version-0 body is, proposes nothing, and is never upgraded
to custody; an exclusive-only receiver refuses it as
`:yin.k/profile-mismatch`. Admission semantics are the governing ones
unchanged; only the spelling is new. No body carries an epoch.

The root is a content row and **its id is the transported state's
address**, the `B` of UCF §7.3.4 for this carrier. Occurrence and origin
are inside it, as the governing rule keeps them (§10.1). The code map is
plain data in the root rather than a row kind: each instruction vector
keeps its own address `A` computed exactly as UCF §7.3.2 computes it, and
the carrier verifies, never recomputes, that address.

## 6. Canonical cell numbering

Cell ids must be a function of the state, or two lifts of one state give
two root ids. Local rules are not enough: a cell's number must depend on
everything that distinguishes it, including references to it from
elsewhere in the task (the review's counterexample: `'a` bound to a set
holding cells X and Y with equal bodies, `'b` bound to X; any rule that
looks only at the set and the bodies cannot tell X from Y, but `'b`
can). The numbering is therefore defined over the **whole alias
topology** of the task, and the definition is the reference against
which any algorithm is checked.

**What is defined, and what is not claimed.** The canonical numbering
is defined as **the output of the procedure below**, which is a function
of the state alone: every step depends only on structure, never on a
cell's number or on any hash that includes one, so renaming the cells of
the input does not change the output. It is *not* claimed to be the
minimum of the root bytes over all `n!` bijections; that is a different
definition, and a refinement-and-search procedure is not shown to reach
it (signature order does not order Merkle bytes). Nor is it claimed that
cells left together by refinement are interchangeable: they need not be
(a guest set encoding a disjoint triangle and square as ordered pairs of
equal-body cells leaves all seven cells in one class, and no
automorphism exchanges a triangle vertex with a square vertex). The
procedure therefore explores every unresolved alternative, with no
pruning, and the representative it returns is canonical because the
procedure is deterministic and renaming-invariant, not because of any
symmetry argument.

**The procedure.** Its input is the abstract task: the structure of
§5 with cells as identities, before any numbering. Every encoding below
is canonical CBOR (`dao.jing.cbor.md`) of a *synthetic vector
structure*; vectors are used throughout, never host maps or sets, so
that elements or keys that become equal after class substitution keep
their multiplicity. All byte comparisons are bytewise on those CBOR
bytes. Let the cells be `n` in number; if `n = 0` the procedure returns
the empty numbering and performs no search.

1. **Class labels** are CBOR byte strings. Wherever a label is embedded
   in a larger encoding it is embedded as a CBOR byte string (major
   type 2), never re-parsed. A cell's initial label is the CBOR of
   `[:body <stream-marker-bytes> <position-bytes>]`.
2. **Class encoding** `CE(x)` of any encoded value or row, structural
   and hash-free. **Every identity-bearing position encodes the cell's
   current label, never its incoming id**; the identity positions are
   exactly: `[:cell C]` values, the bare `cell-C` and `response-cell-C`
   slots of `:pending` rows, and the keys of the `:cells` census.
   - `[:lit d]` → `[:lit d]`.
   - `[:cell C]`, and a bare `C` slot → `[:cell <label of C>]` (full
     tag retained, §10.9).
   - `[:row B]` → `[:row <CE(row B)>]`, the row expanded in place; the
     expansion is finite because the structural edge family is acyclic
     (§8 step 3).
   - A row `[kind s₁ … sₖ]` → `[kind CE(s₁) … CE(sₖ)]` with each slot
     encoded as: a structural datum (`A`, `M`, `P`, `S`, `N`, `D`, a
     reason, a phase, a version, a role, a kind) as itself; a `C` as
     above; a `V` as above; a cref as the expanded row; a crefs vector
     as the vector of expansions.
   - For `:set` rows, and for the key-value pairs of `:map`, `:store`
     and `:bindings` rows, the entries of `:parked-table`, and the
     installs and module-stores slots of the root: encode each element
     (or each `[key value]` pair as a two-element vector), then **sort
     the resulting vector of encodings bytewise, retaining duplicates**.
     Ordered rows (`:vec`, `:list`, `:stack`, `:k`) and the ordered
     `frames` slot keep their order.
   - The `:cells` census is **not expanded**: in `CE(root)` the
     `cells-cref` slot is the constant `[:cells]`. The census is a
     derived index of the cells the task reaches, so it carries no
     information the reference positions do not, and (consistently with
     §8 step 3, which excludes census declaration edges from
     reachability) **census declaration occurrences contribute no
     positions** to any cell.
   - Context lookups (`P` in `:parked-ref` and `parked-id`, `M` in
     `store-of`) are not expanded; they appear as the plain datum.
   - An install child's `:task` row is **not expanded**: children are
     numbered first, as their own tasks with their own cells (task
     boundary; this runs bottom-up inside §8 step 6 and needs no
     restoration), and a child cref appears in the parent's encodings
     as `[:child <child root id>]`, the child's already-canonical root
     id as a byte string.
3. **Positions.** A position is the CBOR of the path from the task's
   root to one identity-bearing occurrence of a cell: a vector of
   steps, built by descending from the root and appending exactly one
   step per slot category crossed, with the occurrence itself
   contributing no step. The categories, and their one spelling each:
   - *Fixed slot* (any tabled slot that is not one of the categories
     below, including a `:pending` row's `cell-C` slots):
     `[kind slot-index]`, the slot's 0-based index in the row table.
   - *Ordered repeated entry* (`:vec`, `:list`, `:stack` elements; `:k`
     frames; the root's `frames`): `[kind i]` with `i` the 0-based
     position; for `frames` the step is `[:task :frame i]`.
   - *Unordered element* (`:set`): `[kind :elem CE(e)]`; never an index.
   - *Keyed pair* (`:map`, `:store`, `:bindings`, `:parked-table`, and
     the root's installs and module-stores slots, keyed by module name
     and `M`): `[kind :key CE(k)]` when descending into the key,
     `[kind :val CE(k)]` when descending into the value; never an
     index. For `:bindings`, `:parked-table`, installs and module
     stores the key is a structural datum and `CE(k)` is the datum.
   - *Task boundary*: a path never crosses into an install child; a
     child's cells have paths rooted at the child's own root.
   Equal occurrences yield equal positions, one per occurrence, and
   positions never contain a source enumeration index of an unordered
   container.
4. **Signature and refinement.** A cell's signature is the CBOR of
   `[:sig <old label> <sorted vector of its positions, duplicates
   retained>]`; its new label is that byte string. Repeat until no
   class splits. Keeping the old label makes refinement monotone, so
   the partition stabilizes in at most `n − k` splitting rounds from
   `k` initial classes plus one stability check; this bounds partition
   rounds only, not signature-processing or search work.
5. **Search.** Order classes bytewise by label. If every class is a
   singleton, assign `1 … n` in that order and stop. Otherwise take the
   first non-singleton class and, **for each of its members in turn**,
   give that member the label that is the CBOR byte string of
   `[:ind <depth> <old label>]`, where `depth` is 0 at the top-level
   search and increases by one per level of recursion (distinct from
   every refinement label by its tag; no ordering relative to other
   labels is needed), refine to stability, and
   recurse. Every branch is explored. Each complete leaf yields a
   numbering; build the §5 rows under it and take the **root body's
   canonical CBOR bytes** (the digest preimage, not the digest) as the
   leaf's key. The procedure's output is the row set of the leaf with
   the bytewise least key. Two leaves with equal keys have emitted
   identical rows: they differ only in which source cell received
   which number, which the transported representation cannot observe.

**Validation.** Canonicality is a property of the emitted rows, not of
a source-cell-to-number bijection, so the receiver does not compare
numberings. It decodes the rows to the abstract task, runs the
procedure, re-emits rows, and compares the resulting root id with the
carried root id: unequal is `:yin.k/undecodable` kind
`:noncanonical-cells`. Every labeling that yields the selected
commitment is thereby accepted, and no encounter-order tie-break ever
decides validity. This is a lower-side structural refusal, not a lift
kind. A receiver whose work bound (§10.10) is exceeded before the
procedure completes refuses with a structured resource refusal before
any attachment and never labels the state `:noncanonical-cells`, never
falls back to encounter order, and never accepts an unfinished check.

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
   child's version, then every profile map against the registry (only
   the semantic profile is supported, §2), then require all versions 3
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
   unreachable row is `:yin.k/undecodable`); every `[:cell C]` names a
   census entry and every census entry is reached **through the task's
   own roots, the census's declaration edges excluded**, per task and
   per child; every `:parked-ref` and `parked-id` resolves in the parked
   table; every `store-of` resolves to a carried module store; every
   `:install` pending has its entry and every entry's child is a
   `:child` task. After this step every traversal below is finite and
   safe.
4. **Hashes.** Every content row's id equals the SHA-256 address of its
   body; every code payload hashes to its key `A` and is well formed
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
6. **Canonical numbering.** Recompute §6 under the work bound and
   compare (`:yin.k/undecodable`, kind `:noncanonical-cells`; or the
   resource refusal of §10.10 if the bound is exceeded).
7. **Code and frames.** Each wait's resume pc is a static safepoint
   whose kinds admit the wait's reason (`:yin.k/undecodable`, kind
   `:reason-mismatch`); a parked record's pc is an explicit-park
   safepoint; a reified value's pc is a `:current-continuation`
   successor; return pcs follow a non-tail call; stack bases are bounded
   and nondecreasing; closure params agree with the addressed lambda;
   integer slots are in range.
8. **Pending and operations.** Reason-specific slot rules; correlation
   agreement of §5.3 (cell stream, envelope ids, module names); every
   op-id below `next-op-seq`, unique across the tree, absent without
   origin; every install response verified as §5.4 requires.
9. **Dependency closure.** Compute the census of amendment §6 from the
   rows (waits, pendings and envelopes, parked records, store, module
   stores, result, children) and require the carried `requires` and
   `code` map to agree with it exactly: a declared segment not reached
   or a reached segment not declared is `:yin.k/undecodable`. Then
   evaluate `requires` per UCF §7.6.5: missing segments, primitives,
   modules, streams, cursor profiles are `:yin.k/unsatisfied`; a body
   with cells claims `:dao.stream.remote/v1`; module-store coverage is
   complete for every `store-of` reached.
10. **Custody.** For an exclusive root, the occurrence, baseline and
    operation inspector and admission checks of §7.7.8; an authority
    never adds missing header keys.
11. **Composition.** Protection, resource, primitive and grant inputs of
    the receiving composition.
12. **Restore.** Reconstruct isolated code spaces, fresh values, the
    isolated store, module-store instances (first link wins), one
    private resource entry per cell, the parked table, children first
    (one native task instance **per install entry**, even where two
    entries share a child row), then waits in order; adopt `max(local,
    carried)` for the id counter; publish the machine only after every
    child succeeds; run, poll, mint, append, close or initialize nothing
    during lower.

Diagnostics use the established families and no new top-level outcome:
`:yin.k/profile-mismatch`, `:yin.k/undecodable` with `:yin.k/path` as
`[row-id slot-index …]` and a `:yin.k/kind` where one is named above
(`:reason-mismatch`, `:noncanonical-cells`), `:yin.k/hash-mismatch`,
`:yin.k/unsatisfied`, and on the lift side `:yin.k/non-portable` with
the two kinds this carrier adds to UCF §7.5.4's set: `:byte-string` and
`:metadata`.

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
  version-3 state `S` in the semantic profile **for which `lower(S)`
  succeeds** (adequate composition, resource and admission inputs; a
  refusal is not a counterexample), and with no execution between lower
  and lift, `lift(lower(S)) = S` by root id under the **fresh-receiver
  reconstruction context**: the receiver's id counter is 0 so
  `max(local, carried)` is the carried value; occurrence, origin and the
  custody arm are supplied to the lift from the restored task's own
  record, not minted; cell bodies and op-ids are carried verbatim; the
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
- **Legacy correspondence.** Versions 0, 1 and 2 are lowered directly by
  their own version-aware readers, keeping their addresses and custody
  bindings (§10.8). Normalization into version 3 is a separate tool and
  is **partial**: a legacy body normalizes with semantic preservation
  (same lowered machine under native equivalence) or refuses with one
  of this carrier's named refusals (`:byte-string`, `:metadata`, a
  profile version 3 does not yet support); it never substitutes a
  version-3 address for an admitted legacy checkpoint. Exact mutual
  inversion is required only between version 3 and its one canonical
  nested projection (§10.3).

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

Answers 7–9 are the Architect's (round 2) and are adopted:

7. **Other profile tables.** Deferral is acceptable for a semantic-only
   Draft; version 3 declares semantic support only and the other
   profiles fail closed at the gate (§2) until their complete tables and
   validation rules are published, with their profiles and code
   addresses unchanged.
8. **Legacy readers.** Direct version-aware lowering of versions 0–2,
   preserving address and custody binding; normalization is a separate,
   explicit, partial tool (§9) and a normalized checkpoint has a
   different address that never silently replaces an admitted legacy
   one.
9. **Skeleton placeholder.** Safe only if the full value-union tag is
   retained in the skeleton, which §6 now requires (`[:cell <class>]`,
   never a bare class); and a placeholder does not canonicalize alias
   topology, which is why §6 is now defined as the output of a
   renaming-invariant refinement-and-search procedure.

10. **Receiver work bound** (Architect, round 3; adopted as a
    requirement, with the limits left to implementation and
    composition). Before an implementation brief, the receiver must have
    a bound on decoded bytes, rows, cells, traversal depth, refinement
    work and search nodes for §6, with elapsed time as an optional
    operational limit on top. Exceeding a local budget produces a
    structured resource or capability refusal before any attachment, in
    the qualified-unsupported family `datom.world.md` already provides;
    it never labels a valid state `:noncanonical-cells`, never falls
    back to encounter order, and never accepts an unfinished check. The
    exact limits and their refusal mapping are implementation and
    composition decisions and must not alter canonical bytes.

**Proposals, not consequences.** The following are design choices this
document makes; they follow neither uniquely from the owner's three
rulings nor from the six invariants, and an implementation brief must
name them as choices being accepted: a new body version (3) rather than
another carrier identification; the canonical representative of §6 as
defined by its procedure; the wire unit and pack framing of §3; the
explicit fork-policy spelling of §5.5; semantic-only initial scope; the
byte-string and metadata restrictions of §4; SHA-256 as the row digest
(§3); and positional rows over EAV (§1). Preserving the complete FFI
envelope (§5.3) is *not* on this list: it is the inherited behaviour,
and narrowing it would have been the new protocol decision.

Still open after this revision: none beyond the other-profile tables
(§10.7) and the work-bound limits (§10.10).

## 11. What this does not change

Code identity, the contract stamp and profile registry, safepoints,
pending-wait semantics and the r5 required keys, install-child rules,
the dependency closure and satisfaction check, the isolated store, the
private resource table, module-store instancing, scheduler state, the
custody header and admission rules, fencing and operation ids, the
outcome families, and the existing refusal kinds (two lift kinds and two
`:yin.k/undecodable` kinds are added, §8). The carrier is a new body
version for the same facts. The
Architect's round-1 statement is the acceptance bar: tuple transport
violates none of the six invariants once polymorphic references and
context edges are specified and validated, and §4, §3 and §8 are where
they are.
