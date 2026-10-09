# UCF transported state as tuples: the row schema

Status: **Draft, proposed, not landed.** Written 2026-10-10 from the owner
discussion recorded in
`public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog`
Part Three and the codex Architect review that accompanied it
(`collab/1791400000000-architect-ucf-blog-cross-vm-review*.findings.md`).
Subordinate to [`datom.world.md`](./datom.world.md) and to UCF §7 in
[`yin.vm.universal-continuation-format.md`](./yin.vm.universal-continuation-format.md);
follows the row conventions of
[`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md). Nothing here is
implemented. The nested `:yin.k/*` value grammar of UCF §7.5 is what ships
and is tested through Stage E; this document proposes its replacement as
the transport form and says what the replacement must preserve.

## 1. Stance

Three owner rulings fix the shape of this document.

1. *Cross-VM safepoints are semantic-VM safepoints.* The Universal AST is
   the canonical code; the instruction vector is its deterministic
   lowering; a safepoint is `(segment-hash, pc)` into that vector (UCF
   §7.3.2, §7.4.1). This schema changes nothing about code identity or
   safepoints. It only changes how the *state* at a safepoint is carried.
2. *The transported state is a tuple set.* The nested grammar's special
   cases for sharing, forgery, and the value table collapse into one
   mechanism, row ids in slots. What does not collapse is the per-entity
   decision between content and identity, which this schema makes a
   property of the row kind.
3. *Tuples are the transport form only.* They exist between a lift and a
   lower. At rest they are queryable rows like any other. Nothing executes
   them; the destination lowers them into its native representation.

The schema is positional rows, not entity-attribute-value, for the same
reason code is: a row kind fixes a slot list, one table serves both the
encoder and the decoder, and the row's merkle id commits to its children
inline. The Architect confirmed that positional code rows and explicit
state tuples do not conflict (round 2, "properties that passed review");
EAV was considered and rejected because it would introduce a second
tuple dialect beside the code rows with no gain in expressiveness.

## 2. Two id spaces, one rule

Every row is `[id kind & slots]`. The stored payload is the body
`[kind & slots]`; the id is an address envelope outside the hashed
payload, exactly as for code rows (code-as-tuples §2.1).

**Content rows** have `id = (dao.jing/segment-key body)`. Two content rows
with equal bodies are one row. A content row's id is merkle: it commits
to every row id in its slots.

**Identity rows** have an id minted by the lift, `:yin.k/c-<n>`, and are
never content-addressed. They carry state whose sameness is not its
contents: today, only cursor cells (UCF §7.5.3). Two identity rows with
equal bodies are two rows, and that is the point.

**The rule.** The row kind decides the id space, the schema fixes it per
kind, and no row may be in both. A content row may reference an identity
row by its cell id; that cell id then participates in the content row's
hash, so two otherwise-equal values wrapping two different cells hash
differently and never deduplicate into one. This is the alias
preservation the Architect's round-1 finding 2 required, and it is the
reason cell ids must be canonically numbered (§5) rather than arbitrary:
otherwise two lifts of the same state would produce different content
hashes for every row above a cell.

Cells cannot form cycles with content rows: a cell's slots are a stream
reference (content) and an opaque position (scalar), never a reference to
a content row that could reference the cell back. Content rows cannot
form cycles among themselves because a merkle id cannot contain itself.
The tuple set is therefore a DAG by construction, and UCF §7.5.3's cycle
refusal is preserved as a property of the schema rather than a check:
a value that would need a cycle cannot be lifted into rows, and the lift
reports it as today, `:yin.k/non-portable` with `:yin.k/kind :cyclic`
naming the path. Admitting cycles would be a separate ruling (Architect
round 1, finding 1) and is out of scope.

## 3. Slot kinds

A slot holds exactly one of:

| Slot kind | Holds | Notes |
|---|---|---|
| scalar | nil, boolean, number, string, keyword, symbol | Jing's portable scalar domain, including BigInt and float64 carriers (UCF §7.5.1) |
| plain | a vector, list, set, or map whose every element, key, and value is a scalar or plain | inline, encoded by Jing as data; carries no reference |
| cref | the id of a content row | |
| iref | the id of an identity row (a cell id) | |
| crefs | an ordered vector of content row ids | |

A collection that contains anything other than scalars and plains is not
a slot value; it becomes a row (§4, `:vec` `:list` `:set` `:map`) and the
slot holds its cref. This is the only place the encoder makes a
structural decision, and it is decided by content, not by heuristics:
*does this collection reach a reference?* The forgery problem of UCF
§7.5.1 does not arise because references are ids in typed slots, not
maps; a program's map literal is either plain data (inline) or a `:map`
row whose kind is fixed by the schema, and neither can be mistaken for a
marker because there are no markers.

## 4. The row table

Kinds are closed. A row of an unknown kind, a wrong arity, or a slot of
the wrong kind is a validation failure (§7). The table is the dictionary
for both encode and decode. "Innermost last" and "sorted" are part of the
schema, not conventions, because they determine the merkle id.

### 4.1 Values

| Kind | Slots | Id space | Decodes to |
|---|---|---|---|
| `:vec` | `[crefs-or-slots…]` one slot per element | content | vector |
| `:list` | same | content | list |
| `:set` | one slot per element, sorted by Jing canonical order of the element's encoded form | content | set |
| `:map` | `[k₁ v₁ k₂ v₂ …]` sorted by Jing canonical order of the encoded key | content | map |
| `:closure` | `[segment-address entry-pc params env-cref store-of]` | content | `{:type :closure …}` (semantic §2.4); `store-of` is a module manifest address or nil (linker M4) |
| `:env` | `[sym₁ slot₁ sym₂ slot₂ …]` sorted by symbol | content | E, or a captured environment |
| `:prim` | `[name]` | content | the named primitive after the profile check (UCF §7.5.2); the profile itself lives in requires, not in the row |
| `:stream` | `[identity descriptor]` | content | an attached handle under a fresh private key; identity and descriptor are plain |
| `:cell` | `[stream-cref position]` | **identity** | one fresh private cursor entry per cell id, seeded at `position` (opaque, exactly as the transport minted it) |
| `:reified` | `[regs-cref]` | content | `{:type :reified-continuation …}` |
| `:parked-ref` | `[parked-id]` | content | a `[:parked id]` value; `parked-id` must resolve in `:sched` (§4.3) |

A `:closure`'s `params` slot is plain (a vector of symbols). A `:prim`
row is content so that two references to `+` are one row; what it
*means* is settled at lower by the profile in requires, as today.

### 4.2 Machine state

| Kind | Slots | Id space |
|---|---|---|
| `:regs` | `[segment-address pc reason val-slot stack-cref env-cref k-cref pending-cref]` | content |
| `:stack` | one slot per St entry, bottom first | content |
| `:k` | `[kframe-cref …]` innermost last | content |
| `:kframe` | `[frame-type segment-address pc env-cref stack-base call-id stack-cref]` | content |
| `:pending` | `[reason & variant-slots]` per UCF §7.4.3, see below | content |

`:regs` is UCF §7.4.1's `:yin.k/frame` as a row: `reason` is one of the
nine safepoint reasons, `pc` is already the resume pc, and `val-slot`
is nil except where §7.4.3 pre-fills it.

`:kframe` carries what semantic §4.1 gives a return frame (`:segment`,
`:pc`, `:env`, `:stack-base`) plus `call-id` for `:eval-call` and
`:request-sent` frames and `stack-cref` for an effect frame that
snapshots its stack; the slots not used by a frame type are nil. The
Architect's round-1 finding 7 is the reason `stack-cref` exists: return
frames keep only a base into the shared operand vector, effect frames may
carry a stack of their own.

`:pending` keeps UCF §7.4.3's variants verbatim, one slot list per
reason:

| reason | slots |
|---|---|
| `:park` | `[]` |
| `:next` | `[cell-iref]` |
| `:put` | `[stream-cref value-slot]` |
| `:ffi` | `[call-id op request-cref response-cref cell-iref]` |
| `:ffi-request` | `[call-id request-op request-args-cref request-cref response-cref cell-iref]` |
| `:call-effect` | per the effect kind, as §7.4.3 specifies |
| `:link-request` `:link-response` `:install` | per `yin.vm.linker.md` 7.2, 7.3 |

Every rule of §7.4.3 about these (the response position is a kept
cursor, outstanding calls route to the emitter's pair) is unchanged; the
schema only changes how the variant is written down.

### 4.3 Task context

| Kind | Slots | Id space |
|---|---|---|
| `:store` | `[key₁ slot₁ key₂ slot₂ …]` sorted by key | content |
| `:module-store` | `[manifest-address store-cref]` | content |
| `:sched` | `[id-counter parked-crefs]` where `parked-crefs` is `[pid regs-cref pid regs-cref …]` sorted by pid | content |
| `:task` | see §4.4 | content |

`:store` is the reachable slice of UCF §7.6.2, restored into an isolated
execution store. Resource entries do not appear in it: stream handles and
cursor entries travel as `:stream` and `:cell` rows and lower into the
receiver's private resource table (linker M4, `yin.vm.linker.md` 7.3),
beside the program store, never inside it. `:module-store` rows are the
per-module snapshots of §7.6.2's M4 paragraph, one per manifest address.
`:sched` is §7.6.3 verbatim: the fresh-name counter and the referenced
parked records, each a `:regs` row.

### 4.4 The root

```clojure
[T :task
   contract        ; plain: {:yin.code/contract "v3" :yin.k/version 2}
   occurrence      ; plain: :yin.k/o-…  (the lease subject; NOT part of :yin.k/id, see §6)
   origin          ; plain: {:yin.k/occurrence … :dao.lease/lease … :yin.k/emitter …} or nil
   arbitration     ; plain: {:dao.stream/identity … :dao.stream/descriptor …}
   policy          ; plain: :yin.k/exclusive | :yin.k/fork
   regs-cref       ; the blocked machine's registers
   store-cref      ; the isolated slice
   module-stores   ; crefs, sorted by manifest address
   sched-cref
   requires        ; plain: UCF §7.6.1's map, unchanged
   carried]        ; crefs of canonical instruction vectors shipped inline (§7.3.4)
```

The root is a content row. Its id is the transported state's
`:yin.k/id`. Everything UCF §7.2 lists under "what code, what state, what
world, who may run it" is reachable from it, and nothing else is in the
set. A halted computation is the sibling root `[R :result contract
occurrence value-slot]`.

## 5. Canonical cell numbering

Cell ids are minted per lift. For the content hashes above them to be
stable across lifts of the same state, the numbering must be a function
of the state. The lift numbers cells in first-encounter order of one
fixed traversal of the root: `regs` (val, then stack bottom-up, then env
by sorted symbol, then k innermost-last with each frame's env and stack,
then pending), then `store` by sorted key, then `module-stores` by
manifest address, then `sched` by sorted pid. A cell encountered again
keeps its number. Two cells at the same stream and position encountered
at different points get different numbers, which is the aliasing the
schema exists to preserve.

Under this rule, two lifts of one state produce identical row sets and
an identical root id, so `:yin.k/id` means what UCF §7.3.4 says it means.
Two lifts of *different* states that happen to share a closure over the
same cell will not share that closure's row, because the cell number may
differ; that cross-lift dedup is deliberately given up. It was never
promised for identity-bearing values, and content identity for the rows
*below* a cell (the stream row, plain values) is unaffected.

On lower, every cell id is remapped to a fresh private resource entry
seeded at the carried position; every iref to it remaps to that entry.
Two irefs to one cell share one entry; one iref per cell keeps its own.
This is UCF §7.5.3's lowering rule unchanged.

## 6. Equality and the content address

There is no "ordering of the tuple set" to canonicalize, and so no
dependency on Jing sorting anything but map keys and set elements inside
a single row. The set is a DAG rooted at `:task`; the root's merkle id
commits to every reachable row; two sets are equal iff their root ids are
equal, which under §5 holds iff they were lifted from states equal up to
cell renaming. Jing supplies deterministic bytes and the hash of each
row body; the schema supplies the order within a body. This answers the
Architect's round-1 finding 6 without asking Jing for anything it does
not do.

`:yin.k/occurrence` is a slot of the root and therefore inside the hash.
That is a change from today, where `:yin.k/id` is computed with
occurrence present too (§7.2 hashes the map minus `:yin.k/id` only), so
it is not a regression; but it means snapshot variants of one occurrence
still have distinct ids, exactly as §7.3.4 intends, and the occurrence
remains the lease subject. **Open question (§9.1):** whether the root
should hash *without* occurrence and origin so that content identity is
occurrence-independent, with occurrence carried in the envelope beside
the root id. The current UCF rule says no; this draft follows it.

## 7. Validation on lower

Before the satisfaction check of UCF §7.6.5 and before any native
allocation, the receiver validates the set as data:

1. Every row's kind is in the table and its arity matches.
2. Every slot is of the kind the table says; a plain never reaches a
   reference (checked by walking it); a cref names a row present in the
   set whose kind is admissible in that slot; an iref names a `:cell`.
3. Every content row's id equals the Jing address of its body. A
   mismatch refuses the whole set.
4. Every row is reachable from the root; unreachable rows refuse the set
   (the emitter had no reason to send them, and a receiver must not
   accept state it cannot account for).
5. Every `:parked-ref` resolves in `:sched`; every `:kframe` with a
   `call-id` has a matching pending or parked record as §7.4.3 requires.
6. Cell ids are exactly `:yin.k/c-1 … :yin.k/c-n` for some n, each used
   at least once (a numbering gap means the lift and the set disagree).

Failure is total and names its place: `{:yin.k/status :yin.k/malformed
:yin.k/row id :yin.k/slot i :yin.k/kind …}`. Resource references are
authenticated before lift and re-sealed on lower exactly as the M4
amendment to §7.5.1 requires; the schema moves none of that.

## 8. Lift and lower, and what they preserve

For the reference machine:

- **lift** walks the wait entry, the K, the pending variant, the
  reachable slice, the module stores, and the scheduler, producing rows
  with §5's numbering, or refuses by path as UCF §7.5.4 specifies. The
  nested grammar's value table (`:yin.k/values`) and cell table
  (`:yin.k/cells`) have no counterpart: sharing is merkle, cells are
  rows.
- **lower** validates (§7), checks satisfaction (§7.6.5), allocates one
  private resource entry per cell, then decodes rows into the wait-entry
  shape, the isolated store, the module-store instances, and the
  scheduler (adopting `max(local, carried)` for the counter).

The laws, as the blog's Part Three states them after review:

- **Canonical round trip.** `lift(lower(S)) = S` for every valid set S,
  compared by root id, which under §5 absorbs cell renumbering.
- **Native observational equivalence.** `lower(lift(m))` need not equal
  m as native state (fresh ids, seals); it must preserve the next
  observable step, the pending wait, the result, and the effect trace.
- **Cross-engine.** Running the corpus to each safepoint on a foreign
  engine yields the same row set the reference machine yields.

The UCF §7.11 harness gains one assertion per law. The existing nested
grammar's tests become the oracle for the first law during transition:
`rows→nested` and `nested→rows` must be mutually inverse on the Stage D/E
corpus.

## 9. Open questions for the Architect

1. **Occurrence inside or beside the hash** (§6). The current UCF rule
   keeps it inside. If the tuple form is the moment to separate "what
   state" from "which attempt", the root should hash without occurrence
   and origin, and `:yin.k/occurrence` should become envelope metadata
   next to the root id. This touches §7.3.4 and §7.7.
2. **Packing.** Rows stored individually (shared rows shared across
   tasks in a Jing pool) or packed per task under one address. Same
   question code-as-tuples §2.1 leaves open; the two should get one
   answer.
3. **The nested grammar's fate.** Retired, or kept as a projection of
   the rows for channels that want one value per message. If kept, the
   two must satisfy the mutual-inverse law of §8 permanently, not only
   during transition.
4. **Contract stamp.** The schema is a change to the execution contract's
   transport half; `:yin.k/version` goes to 2. Whether
   `:yin.code/contract "v3"` also moves depends on whether the Architect
   reads the transport form as part of the stamped contract (§7.3.3 says
   the stamp versions the *complete* execution contract).
5. **Reified continuations.** `:reified` rows reference a `:regs` row,
   making a reified continuation share structure with a parked one. The
   semantic VM treats them as distinct value classes; the schema should
   confirm nothing in the loader depends on them being distinct rows.
6. **Foreign-engine value profiles.** This schema fixes the carrier. The
   per-engine profile (which slot kinds an engine lifts and lowers
   losslessly, and its refusal set) is a separate section, UCF §7.5.5 or
   a sibling document, and is not drafted here.

## 10. What this does not change

Code identity, the contract stamp, safepoints, pending-wait semantics,
the dependency closure and its satisfaction check, the isolated store,
the private resource table, module-store instancing, scheduler state,
custody (occurrence, lease, epoch, admission, the ledger), fencing,
outcomes, and every refusal kind. The schema is a new carrier for the
same facts. The Architect's round-2 statement stands as its acceptance
bar: tuple transport violates none of the six invariants provided
references and schema validation stay explicit, and §7 of this document
is where they stay explicit.
