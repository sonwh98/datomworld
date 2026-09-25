## §7. The Universal Continuation Format (Proposed)

> [!WARNING]  
> **Status: Proposed / Deferred.** The Semantic VM's linear CESK state is
> theoretically sound as an architecture-agnostic continuation format, but
> true heterogeneous network migration requires addressing several critical
> defects identified in the 2026-09-14 architectural review. This revision
> (r4) incorporates r2's answers to that review's findings 1-19, the owner
> ruling on S7.3's canonical form (r3), and Rule R's "v3" contract (r4);
> S7.11 lists the acceptance blockers that keep this warning standing until
> an implementation phase closes each of them with tests.

The Semantic VM's linear CESK state (`{:segment id, :pc n, :env E, :stack S, :k K}`) is proposed as the canonical exchange format for network-transparent continuations. Because this lowered representation resolves execution-order ambiguity inherent in the Universal AST, it provides a simpler target for specialized execution engines (e.g., WebAssembly, LLVM, hardware FPGA) to participate in the `datom.world` ecosystem.

To safely "lift on park" and "lower on resume" across heterogeneous boundaries without violating host isolation or concurrency invariants, the following contracts must be fully specified before this feature is accepted:

1. **Safepoint Reconstruction Metadata:** `:yin.code/source` mappings are insufficient for state reconstruction. The safepoint set must be exactly the machine's parking transitions — including effect-producing `:call`s — and reconstruction metadata must separate what is derivable from the canonical code alone (stack *effects*, lexical *requirements*) from what is per-activation state only the frame can carry (absolute depths, captured environments). Foreign engines publish per-segment safepoint maps derived from the canonical stream so arbitrary hardware states can be predictably lifted into the canonical CESK format.
2. **Recursive Portable Encoding:** Section 1.1 permits host functions and local stream handles in the environment. A recursive encoding over a **disjoint tagged grammar** is required — one a program's own map literals cannot counterfeit — to encode these local references into portable descriptors, together with per-primitive **semantic profiles** (a name recovers a binding, not a meaning) and explicit, total failure modes for un-serializable resources. Cursor aliasing is observable and must survive the round trip.
3. **Dependency Closure & Context:** A parked fragment is not a complete configuration. The format must carry its complete scheduler context — required primitives *with profiles*, loaded modules, referenced parked records, the fresh-name counter, and the reachable store slice — discovered by a conservative fixed point that includes explicit store operands, and must restore into an **isolated execution store** so the receiver's own state cannot change resolution.
4. **Ownership Arbitration:** Emitting a continuation does not transfer ownership. Exclusion requires a **grounded authority** — a resource the grantor's boundary actually possesses, named in the value, independent of carriers — plus an **exporting state** that fences the source before publication, and **effect fencing** with authority epochs and stable operation ids, because absence of a lapse record is never evidence of tenure.
5. **Code Identity (Content Addressing):** Because tempids (`:segment 123`) are local to a single machine's database, continuations must refer to code via immutable, versioned semantic profiles — a **canonical instruction vector** (positional tuples, not EAV) whose address is computed over the loader's resolved interpretation, whose addressed payload is the vector itself, and whose interpretation is pinned by a contract stamp versioning the *complete* execution contract.

---

# Universal Continuation Format — protocol draft

Status: Phase 5 design draft, 2026-09-14; revised 2026-09-14 (r2) per the
architectural review `collab/1789387292000-architect-ucf-review.gpt-6-astra.findings.md`;
amended 2026-09-14 (r3) by owner ruling — §7.3's canonical form is the
positional instruction tuple vector, not EAV (§7.3.2).
Amended 2026-09-25 (r4) for Rule R, `yin/def` is syntax, never a name
(collab `1790345200000-architect-yin-def-rule-r-final` findings): the
contract stamp moves to "v3" because the resolution order refuses the
reserved name before env and a `:define` transition is added (S7.3.3);
`yin/def` leaves the primitive registry (S7.5.2) and the free-name set
(S7.6.1); S7.11 records the remaining definition-frame obligations.
Subordinate to [`datom.world.md`](./datom.world.md); builds on the Phase 0
contract in [`yin.vm.semantic.md`](./yin.vm.semantic.md) (§1–§4), the storage
contract in [`dao.jing.md`](./dao.jing.md), the stream contract in
[`dao.stream.md`](./dao.stream.md), and the lease vocabulary in
[`dao.lease.md`](./dao.lease.md). The five blockers above are answered in
§7.3–§7.7, one section each, in dependency order: code identity first, because
every other part names code.

Every sentence in §7.2–§7.9 is a rule of the proposed protocol. §7.11 records
what is deliberately left open, as acceptance blockers. Nothing below is
implemented; the warning at the top stands until an implementation phase
resolves each blocker with tests.

## §7.1 Stance: a continuation is a value, not a session

The semantic VM already parks as pure data (`yin.vm.semantic.md` §3.5): a
wait entry or a `:parked-continuation` record is `{segment pc env stack k}`
plus a reason and resource ids, with no handle and no closure attached. That
record is *locally* complete and *globally* meaningless: its segment is a
tempid, its stream ids are keys into this VM's store, its environment may hold
host functions, and nothing says which primitives it assumed.

The Universal Continuation Format (UCF) is the **lift** of that local record
into a self-describing value that any conforming engine can **lower** back
into its own registers, and the discipline that governs who may lower it.
Three roles participate, and they are stream roles, not parties to a call:

| Role | Does | Holds |
|---|---|---|
| **Emitter** | lifts a parked configuration into a UCF value and appends it to a medium | nothing, once appended |
| **Carrier** | any stream, file, DHT, or socket that moves the value; sees opaque data | nothing |
| **Resumer** | reads the value, satisfies its declared context, obtains custody, lowers, runs | custody, for the duration of a lease |

Three consequences are load-bearing. First, **a UCF value is content**: it has
a content address under `dao.jing`, equal values converge on one address, and
carrying it twice is harmless (`dao.jing.md`, *Materialization rule*). Second,
**a UCF value on a stream is not a claim**: a stream has no privileged reader
(`datom.world.md`, *Streams*), so emission transfers nothing. Custody is
arbitrated above the stream, by leases over datoms (§7.7), exactly where
`dao.stream.md` *Explicitly Absent* says a take belongs. Third, **content is
not occurrence**: the same parked computation can be encoded more than once
(sharing thresholds differ, §7.5.3), so the encodings address differently,
while remaining one *checkpoint occurrence*. Content identity is for
deduplication and materialization; occurrence identity is what custody names
(§7.3.4, §7.7). Conflating them was a defect of the first draft; this draft
never does.

The reference engine for every rule below is `yin.vm.semantic`. Its
configuration ⟨C, E, S, K⟩ with C = ⟨seg, pc, val, St⟩ (`yin.vm.semantic.md`
§4.1) is the **canonical CESK**. A foreign engine conforms by lifting into and
lowering from this configuration, never by extending it.

## §7.2 Vocabulary and envelope

UCF facts live under `:yin.k/*` (K for the continuation component of CESK).
A UCF value is one open map dispatching on `:yin.k/type`; a consumer ignores
qualified keys it does not understand, as for every DaoStream envelope. The
value must survive the host codec structurally unchanged: no function, host
object, or live handle anywhere inside it (`dao.stream.md`, *Envelopes*).
Every value it contains is encoded in the disjoint tagged grammar of §7.5 —
never a bare marker map.

```clojure
{:yin.k/type        :yin.k/continuation
 :yin.k/contract    {:yin.code/contract "v3" :yin.k/version 0}   ; S7.3.3
 :yin.k/id          :segment/sha256-…          ; §7.3.4  content address of this map minus :yin.k/id
 :yin.k/occurrence  :yin.k/o-…                  ; §7.7    the checkpoint occurrence — the lease subject
 :yin.k/origin      {:yin.k/occurrence :yin.k/o-…                        ; predecessor, or absent on a first park
                    :dao.lease/lease   :dao.lease/l-…                     ; the grant it ran under
                    :yin.k/emitter     <attribution>}
 :yin.k/arbitration {:dao.stream/identity … :dao.stream/descriptor …}     ; §7.7.3  the authority medium, named in the value
 :yin.k/frame       {…}                        ; §7.4  the canonical CESK at a safepoint
 :yin.k/values      {…}                        ; §7.5.3  shared subvalues, by content address
 :yin.k/cells       {…}                        ; §7.5.3  cursor cells: logical identity + position
 :yin.k/scheduler   {…}                        ; §7.6.3  fresh-name state and referenced parked records
 :yin.k/requires    {…}                        ; §7.6  dependency closure, with discovery status
 :yin.k/store       {…}                        ; §7.6.2  the store slice restored as an isolated store
 :yin.k/carried     [ … ]                      ; §7.3.4  canonical instruction vectors shipped inline
 :yin.k/policy      :yin.k/exclusive}          ; §7.7  or :yin.k/fork
```

The parts answer four questions a resumer must be able to ask of the data
alone: *what code* (`:yin.k/requires`, `:yin.k/carried`, and every segment
address inside the frame), *what state* (`:yin.k/frame`, `:yin.k/values`,
`:yin.k/cells`, `:yin.k/store`, `:yin.k/scheduler`), *what world*
(`:yin.k/requires`, `:yin.k/arbitration`), and *who may run it*
(`:yin.k/policy`, `:yin.k/occurrence`, with the custody facts of §7.7 on
their own medium).

A halted computation travels as the sibling value:

```clojure
{:yin.k/type      :yin.k/result
 :yin.k/contract  {…}
 :yin.k/occurrence :yin.k/o-…     ; the occurrence that halted
 :yin.k/value     <encoded>}      ; the final accumulator, in the §7.5 grammar
```

Both envelope kinds take their identity by the same rule: `:yin.k/id` is the
`dao.jing` segment address of the map with `:yin.k/id` removed (§7.3.4).

Outcomes of every UCF operation are data: one map dispatching on
`:yin.k/status`, in the DaoStream result convention. Where an underlying
stream outcome is involved, it is preserved unchanged under its own dispatch
key `:dao.stream/outcome` — UCF never renames or nests it under a private
key. The full algebra is in §7.9.

## §7.3 Code identity — content-addressed semantic profiles (blocker 5)

### 7.3.1 The problem restated as data

`:segment 123` is an entity id minted by one transactor; another machine's
`123` is different code or no code. A continuation naming code by tempid is
therefore not a value, it is a pointer. UCF names code by **what it is**.

### 7.3.2 The canonical instruction vector and its address

The segment attribute `:yin.code/hash`, reserved in `yin.vm.semantic.md`
§2.2, becomes required on any segment a continuation may reference. Its
value is the `dao.jing` segment address of the segment's **canonical
instruction vector**. The canonical form has had two predecessors, each
named so no later plan reinvents it: the first draft's sorted `[e a v]`
triples, which were neither total nor execution-faithful; and the r2
revision's attributed-map record (`{pc → {attr → value}}`), which was sound
but carried datom vocabulary into the identity layer — attribute names and
admissibility rules where position and the opcode table suffice. The r2
form is superseded by two owner rulings: tuples are the correct way to
represent a semantic AST, though the datom `[e a v t m]` need not be the
tuple shape when content-hashing code; and `dao.space.query` assumes no
EAV — its `relation` is an arbitrary mixed-dimensional tuple collection
(`src/cljc/dao/space/query.cljc:153-158`) — so Datalog-over-code does not
require `[e a v t m]` and UCF must not assume it anywhere for code. The
canonical form is therefore the **positional instruction tuple vector**:
one tuple per instruction, pc is the index, and the §2.4 opcode table fixes
each mnemonic's tuple arity and operand kinds. Nothing else names a slot.

**Input.** The vector is computed from a well-formed batch (§2.6) by first
applying the loader's *resolved interpretation* — the same
`index-batch` collapse the loader and the validator perform, where a repeated
single-valued attribute keeps its **last** value. This is the load-bearing
choice. Two batches whose constants arrive as `1, 2` versus `2, 1` resolve to
indexed values `2` and `1`; they *execute differently*, and over the resolved
interpretation they canonicalize differently and address differently. Over
raw triples they sorted identically — the collision the review demonstrated.
Canonicalization and execution now read the same value by construction; there
is nothing left to collide. The last-value-wins rule itself is part of the
contract stamp (§7.3.3) and is versioned with it.

**The vector.** With the resolution applied, the canonical instruction
vector of a segment is (the §2.7 worked segment, excerpted):

```clojure
[[:closure [x] 6]           ; mnemonic, params, resolved body pc
 [:push]
 [:const 10]                ; §2.5 literal: data only
 [:call 1 false]            ; argc, tail?
 …]
```

Rules, each replacing heavier machinery from the attributed-map record:

- **pc is the index.** No entity ids and no attribute names appear anywhere
  in the form: position fixes meaning, the §2.4 table fixes shape, and a
  query over code is a query over tuples of the vector, not over EAV.
- **Saturation.** Defaults the loader applies are materialized in the tuple —
  `:gensym` absent prefix becomes `"id"`, `:ffi-call` absent argc becomes
  `0`, `:stream-make` absent buffer becomes the default capacity — so a batch
  that omitted a defaulted operand and one that stated it canonicalize
  identically, because they execute identically.
- **Refs are resolved pcs**, exactly as the loader resolves them.
- **The header folds away.** `:yin.code/length` is `(count vector)`,
  `:yin.code/type :segment` is implied by the form. Provenance
  (`:yin.code/derived-from`, every `:yin.code/source`), entity ids, batch
  order, `t`, `m`, and `:yin.code/hash` itself are excluded — the address is
  computed *over* the vector, which therefore cannot contain it without
  hashing the hash being verified.
- **Admissible slots.** Each tuple is its mnemonic plus exactly the operands
  the §2.4 table gives it, in the table's order. A batch that resolves to
  anything else — a mnemonic outside the table, an operand the mnemonic's
  tuple has no slot for, a segment-entity attribute beyond the folded and
  excluded set — is **non-canonicalizable**, and canonicalization refuses
  naming the instruction (the lifted value is never minted; the refusal is
  `:yin.k/non-portable` with `:yin.k/kind :non-canonicalizable`).
- **Ordering: none is imposed.** The vector's order is its positions;
  canonicalization sorts nothing and compares no operand against another.
  Literal operands are values and hash under the same encoder as everything
  else, so equal literals address equally however their maps were built.
  The first draft's `[:segment …]`-versus-`[0 …]` triple sort — which throws
  `Keyword cannot be cast to Number` in the very host it must run on — has
  no successor here because there is no sort at all.

**The address** is `(dao.jing/segment-key vector)` — a
`:segment/sha256-<hex>` keyword. The vector *is* the resolved
interpretation, so r2's collision-freedom argument carries over unchanged
and needs only restating: canonicalization and execution read the same
value by construction; there is nothing left to collide. The address fixes
the resolved instruction stream — mnemonics, operands, control graph,
saturations — and nothing else.

**What equal addresses do and do not mean.** Two segments address equally
**iff** their loader-resolved interpretations are equal; this includes
segments that differ only in entity ids, batch order, provenance, or omitted
defaulted operands. No equivalence beyond that is claimed: different pc
layouts, different `:gensym` prefixes, different but *equivalent* instruction
sequences, and compiler-renamed artifacts address **differently**. The
address is a fingerprint of one instruction stream, not a quotient of a
semantics.

The canonical encoding beneath `segment-key` is transitional
(`dao.jing.md`, *Open items*). UCF inherits that limit unchanged: addresses
are portable exactly as far as `dao.jing` addresses are, and when the pinned
byte encoding lands, `:yin.code/hash` values change with every other minted
address. This is the correct dependency; UCF must not mint a second
addressing scheme.

### 7.3.3 The contract stamp

An address fixes *which* instructions; it does not fix what `:call` *means*.
Interpretation is supplied by the loader and the step loop (§1.1,
*Interpretation Creates Semantics*), and the first draft pinned only the
opcode table — leaving resolution precedence, effect outcomes, call
restoration, and scheduling outside the stamp. UCF therefore versions the
**complete execution contract**: the stamp names one published revision of

- the tuple grammar of §7.3.2 — the mnemonic set, each mnemonic's tuple
  arity and operand kinds, and the saturation/defaults table — plus the
  §2.6 well-formedness rules and the resolved-interpretation
  (last-value-wins) rule;
- the S2.4 opcode table and the S4.2 transitions, including the
  definition transition `[:define name]`, which writes its literal key
  and never resolves its operator;
- the resolution precedence of `resolve-var` (env, then store, then
  primitives, then modules), preceded by Rule R: a reserved name (the
  one-entry set `#{yin/def}`) is refused before env or store is
  consulted, so no binding can redirect a definition (S7.5.2);
- the effect→outcome mapping of §3.3 and `engine/handle-effect`;
- call restoration and the scheduler semantics of §3.5 — wait-entry shapes,
  `check-wait-set`'s round order, `semantic-restore`.

```clojure
:yin.k/contract {:yin.code/contract "v3"    ; the revision above, by name
                 :yin.k/version    0}       ; this envelope's own version
```

A resumer whose loader implements a different revision does not guess: it
returns `:yin.k/profile-mismatch` (§7.9) *before* lowering. Adding an opcode,
changing a transition, or changing the resolution order is a new revision; a
resumer on the old one cannot run code that observes the difference, and the
stamp is how it finds out before lowering rather than at an unknown integer
in `case`. Two revisions may be declared compatible only by a published
statement in `yin.vm.semantic.md` §2.4, never by an engine's own judgment.
Because interpretation is part of identity, an engine's code index is keyed
**per stamp revision**: lookup is `(get-in index [stamp address])`, and the
same address under different stamps is a different image the same way the
same datoms under different loaders are a different program.

The current revision is "v3" (`yin.vm/semantic-contract`, and
`yin.vm/ast-contract` for the Universal AST). It superseded "v2" because
Rule R changed the resolution order and added the `:define` opcode and
transition; see the revision log in S7.11. The de Bruijn images carry
their own names, "b2" (stack) and "r2" (register). Every persistent-code
loader requires a stamp and refuses `:contract-missing` or
`:contract-mismatch` before validation, so an old-stamped image fails by
stamp, not by grammar. The lowering adapters (`linearize/ast-loader`,
`linearize/rows-loader`) and `ucf/canonicalize` are not exceptions:
each requires its input's own stamp and verifies it before lowering or
canonicalizing, and stamps only output it produced from verified input.
Macro packets are code too: each macro-store value carries its own AST
stamp, which the transformer runner verifies before it executes the
packet. Fresh-code producers (yang, the expander, `vm/eval`, a linearizer over
code it just lowered) supply the current constant themselves, and an
observer medium whose only producer is trusted fresh code takes the one
explicitly named path for that, `vm/fresh-code-loader`; nothing assigns
a stamp to externally supplied datoms or rows.

### 7.3.4 References to code inside a continuation

Every place the local record says `:segment <id>` — the control, each
`:return` frame, each effect frame, each closure's `:segment` — the UCF frame
says `:yin.k/segment <address>`. Entry pcs and frame pcs are unchanged: a pc
is meaningful relative to the resolved stream and is stable under everything
§7.3.2 normalizes away.

**The addressed object is the vector.** `dao.jing` materializes the exact
payload it is handed and verifies address-equals-hash on every write and read
(`src/cljc/dao/jing.cljc`, `materialize!`); it supplies no aliases. So the payload
stored at a code address **is the canonical instruction vector** — nothing
else hashes to that address. A raw segment batch, with tempids and
provenance, materializes under its *own* different address and is not a
resolution mechanism for UCF; `:yin.k/carried` carries vectors, not batches.

Loading a fetched vector has two paths, and both are specified. The
**projection path** converts the vector to loadable datoms deterministically
— segment entity eid `0`, instruction of pc `p` eid `(inc p)`, each tuple
emitted with `:yin.code/segment 0` and its saturated operands as attribute
datoms (so the round trip vector → datoms → loader is the identity), each
resolved-pc operand emitted as a ref to that eid — and hands the batch to
the ordinary §2.6 loader, so every well-formedness check runs on the way in.
The **direct path** decodes the canonical vector into an image without
datoms, as a foreign engine would. The two paths are a conformance
obligation: both must yield the same image, and a test in the harness (§7.11)
asserts it for every corpus segment. `yin.vm.code-as-tuples.md` §7.2
supersedes this section's original designation of the projection path as the
reference path: the direct path is primary and is the reference; the
projection path is derived from it. Both invoke the same validator, and
nothing may depend on which path a conforming engine takes.

On lowering, the resumer resolves each address in this order and stops at the
first hit:

1. its own code index for the value's stamp, keyed by address, each entry
   verified once by hashing and grammar-validating its vector;
2. `:yin.k/carried` in the value itself, verified the same way before load;
3. a `dao.jing` read by address, verified the same way.

Verification is two checks, always both: the vector must hash to the address
it claims (`:yin.k/hash-mismatch` otherwise), and it must satisfy the stamp's
tuple grammar — mnemonic set, arity, operand kinds, saturations in place
(`:yin.k/undecodable` naming the pc otherwise; a correct hash is not
structural validation, the same rule the value decoder applies, §7.5.1). A
miss is `:yin.k/unsatisfied` naming the address (§7.6.5); nothing from a
value that fails either check is loaded.

**The address index is additive; local-id immutability stands.** The §3.1
rule — an id already holding a different image is a load error, an identical
reload accepted — is unchanged by UCF, and the first draft's claim that a
hash check *replaces* it was wrong: two different, correctly hashed segments
can claim the same local id, and redirecting an existing id would silently
rewire every frame that names it. A lowering engine therefore keeps two
maps: `:code {local-id image}` under the §3.1 rule, and a separate
`{stamp {address image}}` index with an `address → local-id` alias column.
Segments loaded *by address* may mint fresh local ids; they may never reuse a
live one, and the alias column is checked so one address never lands under
two local ids in one VM.

**The continuation's own identity and the occurrence.** `:yin.k/id` is the
`dao.jing` segment address of the UCF map with `:yin.k/id` removed — and by
the same no-alias argument, the payload published under `:yin.k/id` is the
**id-less body**; a reader reconstructs the envelope with
`(assoc body :yin.k/id (dao.jing/segment-key body))`. Equal continuations
converge; an idempotent re-publication of one encoding is a no-op. But
encodings legitimately differ (§7.5.3), so `:yin.k/id` is *not* what custody,
chains, or grants name. They name `:yin.k/occurrence` — the checkpoint
occurrence id, minted exactly once, when a park is first exported, unique
under the emitter's attribution, and carried unchanged by every authorized
snapshot variant and every retry of the same park (§7.7.2). A re-emission
after a failed publication reuses the occurrence; it never mints a second
runnable checkpoint.

## §7.4 Safepoints — where a canonical configuration exists (blocker 1)

### 7.4.1 Only safepoints lift, and the table is the machine's

The linear machine's registers are canonical only *between* instructions.
Inside a `:call` that has popped its operands but not yet pushed a frame, or
inside a superinstruction the loader fused (§6.4), no ⟨C, E, S, K⟩ exists
that both describes the state and is expressible in the §2.4 vocabulary.
UCF resolves this by rule rather than by metadata: **a continuation may be
lifted only at a safepoint**, and a safepoint is a transition of the
reference machine at which the machine parks. The first draft's table
presented itself as complete while omitting the parks that arrive through
`:call` — a called host function that returns an effect blocks exactly as an
instruction effect does, through `apply-call` → `handle-effect` →
`call-park-entries`, and both ordinary and tail calls reach that path
(`yin.vm.semantic`, `apply-call`). The complete set:

| Safepoint kind | Raised by | Canonical resume pc | Stack in frame (St) | Accumulator on resume |
|---|---|---|---|---|
| explicit park | `:park` | `pc+1` | St at park | the resume value |
| blocked read | `:stream-next` → `blocked` | `pc+1` | St (cursor ref was in `val`) | the read value; `nil` on `end`, `:dao.stream/gap` on gap |
| blocked write | `:stream-put` → `full` | `pc+1` | St minus the popped target ref | the written value, on retry `ok` |
| FFI call, sent | `:ffi-call` → `:dao.stream/ok` | `pc+1` | St minus the popped args | the correlated response's `ok` value; its `error` raises |
| FFI call, retained | `:ffi-call` → `:dao.stream/full` | `pc+1` | St minus the popped args | as sent, once the request is appended and the response correlates |
| effectful call | `:call`/`:tailcall` whose operator yields a blocking effect (`require`, stream-module functions, effect handlers) | `pc+1` | St minus the popped operator and args | the effect's resume value, per its kind as above |
| halt | `:halt`, `:return` with empty K | — | — | not resumable; a `:yin.k/type :yin.k/result` travels instead |

Two corrections to the first draft's table stand beside the addition.
`:current-continuation` is **not** a safepoint: it does not park — it
reifies `val ← {seg, pc+1, E, St, K}` and continues, so a reified
continuation is a *value class* of the encoding (§7.5.1), never a lift
point. And `pc+1` as the resume pc is verified sound for every row above:
§2.6 requires the final pc to be a terminator, so every listed instruction
has a successor in its own segment.

The `:yin.k/frame` is exactly the wait-entry shape of `yin.vm.semantic.md`
§3.5, with segments replaced by addresses and values by their portable
encodings (§7.5):

```clojure
:yin.k/frame
{:yin.k/segment  :segment/sha256-3f1a…
 :yin.k/pc       5                         ; the resume pc, already pc+1
 :yin.k/reason   :next                     ; :park | :next | :put | :ffi | :ffi-request | :call-effect
 :yin.k/val      nil                       ; accumulator; nil except where §7.4.3 pre-fills it
 :yin.k/stack    [ … ]                     ; St per the table above, encoded
 :yin.k/env      { sym → encoded value }   ; E
 :yin.k/k        [ {:yin.k/frame-type :return                    ; or :eval-call / :request-sent,
                    :yin.k/segment :segment/sha256-…             ; which also carry :yin.k/call-id
                    :yin.k/pc 5 :yin.k/env {…} :yin.k/stack-base 0} … ]   ; K, innermost last
 :yin.k/pending  {…}}                      ; the wait it was in, §7.4.3 — one variant per reason
```

**Quiescence.** The migration unit is one task: the whole blocked machine
(§7.6.3). Lifting additionally requires the machine's ready-queue to be
empty — a machine with runnable work queued is mid-scheduling, its next
configuration is not the parked one, and lift answers `:yin.k/not-quiescent`.

### 7.4.2 Safepoint maps for foreign engines: static facts only

The reference machine needs no metadata because its registers *are* the
canonical configuration. A foreign engine — a register allocator over the
image, a WebAssembly lowering, a JIT — keeps its state in physical locations
of its own choosing, and the first draft promised it reconstruction data
that is not a function of the code: absolute stack depth and environment
key sets at a pc depend on the *activation* — a closure enters with the
caller's residual operand stack and an environment formed from its captured
environment plus arguments (`apply-call`), and §2.6 enforces no equal stack
heights at joins, so the same pc is legitimately reached at many depths.
None of that is derivable, and none of it needs to be: **the frame carries
it.** E, St, and every K frame's `:stack-base` and env travel in the value;
a foreign engine lowers the canonical frame directly.

What *is* a function of the canonical vector alone, and what such an engine
must publish — **per segment and per engine profile**, derived from the
canonical stream at its own load time — is the static half:

```clojure
[[sp :yin.safepoint/segment      :segment/sha256-3f1a…]  ; the code, by address
 [sp :yin.safepoint/engine       :wasm32/v1]             ; the engine profile that produced this layout
 [sp :yin.safepoint/pc           5]                      ; canonical resume pc
 [sp :yin.safepoint/stack-effect -1]                     ; Δ|St| across the parking instruction (static)
 [sp :yin.safepoint/lexically-required [x conn]]         ; names this pc can read from E (static)
 [sp :yin.safepoint/layout       {…}]]                   ; engine-owned: physical slot → canonical slot
```

Rules:

- `stack-effect` and `lexically-required` are **derived from the canonical
  vector alone**, by the same static walk the linearizer performs (§5.3).
  They are identical for every engine and checkable against the reference
  machine by a conformance test that runs the segment to each safepoint and
  compares the observed deltas and E-reads. Absolute depth, activation
  bases, captured environments, and physical layouts are **not** claimed,
  not derived, and not in this table; they are the frame's business.
- `:yin.safepoint/layout` is opaque to the protocol. It is a memo the engine
  keeps so that its `lift` is a table lookup. It never travels in a UCF
  value; a resumer that is a different engine has its own.
- `lift(engine-state, layout) → canonical frame` and
  `lower(canonical frame, layout) → engine-state` are total on safepoints and
  undefined elsewhere. The conformance obligation is `lift ∘ lower = id` on
  canonical frames, tested against the reference machine's own frames —
  which is possible precisely because the dynamic half rides in the frame.
- A request to lift at a pc that is not a safepoint of the segment returns
  `:yin.k/not-at-safepoint` naming the pc. An engine that wants finer
  interruption granularity must lower the program to a segment that
  *contains* a `:park` where it wants one: that is a different instruction
  record, a different address, and a different profile. An engine may not
  invent safepoints a record does not have, and two engines that disagree
  about the safepoint set of one address are not both conforming.

Safepoints are datoms, so a query can ask "where can this program be
migrated?" without running it, and a debugger's breakpoint set is a subset of
the safepoint set by construction.

### 7.4.3 The pending wait travels as data — complete, and resumable elsewhere

A parked machine was waiting for something. That something is a resource of
the emitter's host, and the resumer cannot poll it. `:yin.k/pending` records
the wait in resource-independent terms so the resumer can re-establish an
*equivalent* wait, or decide it cannot. The first draft carried sketches;
the set here is exhaustive over §7.4.1's reasons, and each variant carries
everything a *foreign* resumer needs — including the state the first draft
left implicit on the emitter's heap:

```clojure
;; blocked read (:stream-next)
{:yin.k/reason   :next
 :yin.k/cell     :yin.k/c-17}          ; the cursor CELL id, §7.5.3 — not a bare position

;; blocked write (:stream-put, or an effectful call's :stream/put)
{:yin.k/reason   :put
 :yin.k/stream   {:dao.stream/identity … :dao.stream/descriptor …}   ; the target
 :yin.k/value    <encoded>}            ; the retained value — a retry appends THIS, and
                                       ; without it the wait is not re-establishable

;; FFI call, sent (:ffi-call → ok)
{:yin.k/reason     :ffi
 :yin.k/call-id    :call-7             ; the :dao.stream.apply/id correlation
 :yin.k/op         :op/add
 :yin.k/request    {:dao.stream/identity … :dao.stream/descriptor …}   ; where the request went
 :yin.k/response   {:dao.stream/identity … :dao.stream/descriptor …}   ; where the answer lands
 :yin.k/cell       :yin.k/c-23         ; the response cursor cell, §7.5.3, at its KEPT position}

;; FFI call, retained (:ffi-call → full): the request is still in hand
{:yin.k/reason     :ffi-request
 :yin.k/call-id    :call-7
 :yin.k/request-op :op/add
 :yin.k/request-args [<encoded> …]     ; enough to rebuild the envelope verbatim
 :yin.k/request    {identity/descriptor as above}
 :yin.k/response   {identity/descriptor as above}
 :yin.k/cell       :yin.k/c-23}        ; response cell at its kept position
```

Three rules make these resumable somewhere other than where they were minted:

- **The response position is a kept cursor, never an anchor.** Reattaching
  at `:oldest` replays responses that belong to other calls; attaching at
  `:newest` skips past this call's own. The response cell therefore travels
  at its *kept* position, and the whole task's waiters on that cell travel
  with it (§7.6.3), so the round-order semantics of `check-wait-set` —
  waiters sharing a cell consume successive values — are preserved on the
  resumer exactly. A first poll that observes `gap` is the honest outcome
  the contract already promises a kept cursor (`dao.stream.md`, *Retention
  and Gaps*). This requires the response transport to accept a kept cursor
  from another host, which `dao.stream.md` leaves transport-owned and TBD;
  UCF therefore declares, per stream, a **portable cursor profile** in
  `:yin.k/requires` (`:yin.k/cursor-profiles`), and a transport that does
  not declare one cannot be a pending-response endpoint — the lift refuses
  it as `:yin.k/unsatisfied` before the value is ever minted.
- **Outstanding calls route to the emitter's pair; the resumer's pair is its
  own.** The reference machine restores a `:request-sent` entry into a
  response wait through its fixed local store keys (`vm/call-out-stream-key`
  and friends). A resumer must *not* do that: the outstanding call's
  response will arrive on the **emitter's** response stream, because that is
  where the request was sent. The resumer re-establishes the wait against
  the carried response descriptor and the cell's fresh local key (the cell
  id remapped per §7.5.3), while calls the resumed program makes *after*
  lowering use the resumer's own pair under its own keys. Pending-call
  routing and future-call routing are separate stores of state; conflating
  them strands the migrated call exactly as the review described.
- **On resume, the resumer attaches and re-mints.** Attach to every
  descriptor in the frame, pending, and cells; an attachment resolving to
  `not-found` is `:yin.k/unsatisfied` naming the stream identity, never a
  silent `nil`; a `:put` wait retries its retained value and resumes with it
  on `ok` (the engine, a woken writer's retry appends and stamps the
  value); a `:next` wait polls its cell. No wait is silently dropped and no
  retained value is recomputed — what parks is what resumes.

## §7.5 Recursive portable encoding (blocker 2)

### 7.5.1 A disjoint tagged grammar

Encoding is one total function `encode : value × path → outcome`, applied
recursively to every value reachable from the frame: accumulator, operand
stack, environment values, every frame in K, parked records, and the store
slice of §7.6.2. The outcome is either the portable form or a refusal naming
the path.

The first draft encoded markers as ordinary maps — `{:yin.k/primitive '+}`,
`{:yin.k/ref addr}` — while passing program map literals through in their
natural shape. That is ambiguous to everyone: §2.5 admits map literals,
so a program can *contain* `{:yin.k/primitive '+}`, and a decoder cannot tell
an encoder's marker from a program's data. The grammar here is disjoint by
construction: **every map at an encoded-value position is a UCF marker**, and
a program's own maps are always wrapped.

| Encoded form | Tag | Contents | Decodes to |
|---|---|---|---|
| scalars: nil, boolean, number, string, keyword, symbol | — | themselves (never a map; cannot forge a marker) | themselves |
| vector, list, set | — | same shape, elements recursively encoded (never a map) | same shape, elements decoded |
| literal map | `:yin.k/literal` | `:yin.k/entries [k₁ v₁ k₂ v₂ …]`, keys and values recursively encoded | the map, keys and values decoded |
| closure | `:yin.k/closure` | `:yin.k/segment addr :yin.k/entry pc :yin.k/params […] :yin.k/env {encoded}` | `{:type :closure …}` (§2.4) |
| reified continuation | `:yin.k/frame` | a nested frame (§7.4.1), recursively | `{:type :reified-continuation …}` |
| parked continuation | `:yin.k/frame` | a nested frame plus `:yin.k/parked-id` | `{:type :parked-continuation …}` |
| primitive | `:yin.k/primitive` | `:yin.k/name sym` | the named function, after profile check (§7.5.2) |
| stream reference | `:yin.k/stream` | `:dao.stream/identity … :dao.stream/descriptor …` | an attached handle, under a fresh store key |
| cursor reference | `:yin.k/cursor-ref` | `:yin.k/cell cell-id` | a store cursor entry for that cell, §7.5.3 |
| shared subvalue | `:yin.k/ref` | `:yin.k/id addr` | the table entry at `addr`, §7.5.3 |
| anything else | — | — | **refused**: `:yin.k/non-portable` |

Why the wrapping is total rather than an "escaping rule" for suspicious
shapes: a literal map is wrapped *wherever it occurs and whatever it
contains* — including one that happens to read `{:yin.k/tag …}` — and the
entries inside a wrapper are themselves encoded, so a forged marker inside a
literal is re-wrapped one level down and peeled back exactly one level on
decode. No rule asks whether a map "looks like" data; no decoder decision
depends on program content. Scalar and collection values pass through only
because no marker is ever a non-map, which the closed tag table fixes.

Map keys are values and are encoded as such — inside `:yin.k/entries`
alternately with their values, and nowhere else; the frame's own `:yin.k/env`
keys are the program's symbols and are structural, not encoded values.

**Decoding validates, independent of hashing.** A hash check confirms what
was encoded; it does not confirm the encoding is well formed. The decoder
walks the grammar and rejects, as `:yin.k/undecodable` naming the path: a map
with no `:yin.k/tag` at an encoded-value position (impossible from a
conforming encoder); a tag outside the closed set; a marker missing its
required keys; a `:yin.k/ref` whose address is absent from the value table; a
ref graph that is cyclic *through ref data* (content-addressed table entries
cannot mint such a cycle — a cycle can only be written by hand, and it is
rejected); and a value table entry not referenced by anything reachable.
Malformed-but-correctly-hashed input fails here, before any lowering.

### 7.5.2 Primitives: a name is a binding, not a meaning

§1.1 admits host functions into the environment because `:var` resolves a
primitive symbol to the function value and a program may then bind it
(`(def f +)`). At lift time the emitter recognizes such a value by reverse
lookup in its own `:primitives` map — identity on the function object. The
first draft treated the recovered symbol as the whole story. It is not:

- one function object may sit under **several names**, making reverse lookup
  ambiguous;
- two composition-supplied primitive maps may bind the **same symbol to
  different functions** — name presence at the resumer proves nothing about
  behavior;
- identity stability says nothing about **purity or captured host state**.

UCF therefore binds every portable function to a **primitive profile**,
published data, and the binding is checked on both ends:

```clojure
;; in :yin.k/requires — computed, not hand-written (§7.6.1)
:yin.k/primitives {+
                   {:yin.k/profile :yin.k.pp/sha256-…   ; content address of the profile record
                    :yin.k/class    :pure               ; :pure | :effectful | :host
                    :yin.k/arities  [2]
                    :yin.k/effects  #{}                 ; for :effectful — declared effect kinds
                    :yin.k/host-state :none}}           ; :none, or refused
```

The profile record — `{name class arities effects host-state}` under a
content address — is what a name must be *checked against*, not merely
present as. Rules:

- **Ambiguity refuses the lift.** A function object that reverse-lookups to
  more than one name in the emitter's `:primitives` is refused
  (`:yin.k/non-portable`, kind `:ambiguous-primitive`) unless the
  composition declares a canonical-name table resolving it to exactly one.
- **A host function under no name is refused** (kind `:unnamed-function`) —
  a bridge handler's return value, a module-constructed function: it has no
  declared semantics to travel under.
- **`:host`-class functions are refused** (kind `:host-state-primitive`). A
  function whose behavior depends on undeclared host state is not a value
  any other host can honor; profiles that declare host state may not be
  lifted, and a profile claiming `:host-state :none` is a publication's
  warranty, versioned with the contract stamp's primitive-profile registry.
- **On lowering, the resumer's binding is checked by profile, not by
  presence.** For each required name, the resumer must hold a primitive
  whose profile address is equal; a same-named function of different profile
  is `:yin.k/unsatisfied` naming the symbol and the expected profile — it is
  a routing decision for whoever chose this resumer, not a silent
  substitution.
- The standard map `yin.vm/primitives` must be published with profiles
  (note `require` is `:effectful` with `:module/require`); that
  publication is an acceptance blocker (S7.11).
- **`yin/def` is syntax, never a primitive (Rule R).** It is not in
  `yin.vm/primitives` and has no profile; a definition lowers to the
  `:define` transition (S7.3.3), which writes its literal key through
  `engine/store-put`. `empty-state` refuses a composition-supplied
  registry or profile table that binds `yin/def` (`:reserved-name`), and
  the VM constructors refuse a supplied env binding it, so a requirement
  naming it can never be satisfied or substituted. `require`
  is dynamic, not statically interpreted, and stays an ordinary
  primitive.

### 7.5.3 Sharing, cells, and cycles

**The value table.** A configuration is a DAG, not a tree: a closure's
captured environment is the frame's environment; a value on the stack is
also bound in `E`. Encoding naively duplicates, and a deep K makes
duplication quadratic. The encoder therefore builds a **value table**:

- On encountering a collection, closure, or continuation it has already
  encoded (host identity during one lift), it emits
  `{:yin.k/tag :yin.k/ref :yin.k/id addr}` where `addr` is the `dao.jing`
  address of the encoded subvalue, and places the subvalue in
  `:yin.k/values {addr → encoded}`.
- A subvalue is placed in the table when it is referenced more than once or
  its encoded size exceeds a composition-supplied threshold; otherwise it
  stays inline. Both choices yield equal semantics; only the table is needed
  for correctness of sharing.
- A cycle (a value reachable from itself through host identity) is refused
  as `:yin.k/non-portable` with `:yin.k/kind :cyclic` naming the path. The
  reference machine cannot produce one — `E` is extended by `merge`, never
  mutated — so the refusal guards against foreign engines and bridge-returned
  host structures. Cycles *through ref data* cannot be minted at all and are
  rejected at decode (§7.5.1); this single representation retires the first
  draft's two competing ones.

Because table entries are content-addressed, a carrier that already holds an
entry (a `dao.jing` intake pool, a DHT replica) need not carry it again, and
two continuations parked from the same closure share its encoding. This is
`streams-all-the-way-down.md` §6.3's checkpoint-as-published-fold applied to
a single frame. It is also why §7.3.4 separates `:yin.k/id` (which varies
with these choices, harmlessly) from `:yin.k/occurrence` (which does not).

**Cursor cells.** A cursor is *observable state with aliasing*: two distinct
cursor refs may sit at the same position and yet be independently
advanceable store entries, while repeated refs to one cursor ref must
advance together — `check-wait-set` advances a shared cursor ref before
polling the next waiter, so waiters on one ref read successive values, and
waiters on two refs at the same position read the same next value
(`yin.vm.engine`, `check-wait-set`). A position-only encoding erases
exactly this distinction: two cells at one position would collapse, and
their independent advancement would be lost. The encoding therefore carries
**logical cursor cells** separately from position content:

```clojure
:yin.k/cells {:yin.k/c-17 {:yin.k/stream   {:yin.k/tag :yin.k/stream …}
                           :yin.k/position <opaque, exactly as the transport minted it>}}
;; values reference cells, never positions:
{:yin.k/tag :yin.k/cursor-ref :yin.k/cell :yin.k/c-17}
```

Cell ids are minted by the emitter (stable within one lift; remapped on
lowering) and are *not* content-addressed — a cell is a local identity, like
a tempid, and deduplicating cells by their contents is precisely the aliasing
erasure this structure exists to prevent. On lowering, each cell becomes a
fresh store cursor entry seeded with its carried position; every
`:yin.k/cursor-ref` to it remaps to that entry; two refs to one cell share
one entry and one ref per cell keeps its own. The FFI response cell of
§7.4.3 is a cell like any other.

A stream reference is portable iff its transport produces a descriptor whose
`attach!` can succeed somewhere other than the emitter's host. The encoder
cannot know that; it encodes the descriptor and lets the resumer's `attach!`
report `not-found` as data. What the encoder *can* refuse is a handle whose
`descriptor` call fails, which cannot happen under the contract, and a raw
in-memory handle admitted by an in-process stream's admission declaration
(`dao.stream.md`, *Creation and Attachment*), which it treats as a host
object.

### 7.5.4 Failure is total and names its place

Encoding a continuation with one refused leaf refuses the whole
continuation:

```clojure
{:yin.k/status :yin.k/non-portable
 :yin.k/path   [:yin.k/env 'conn]         ; where in the frame
 :yin.k/kind   :host-object               ; the closed kind set below
 :yin.k/hint   "java.net.Socket"}         ; classified, never the object
```

The kind set is closed:
`:host-object | :unnamed-function | :ambiguous-primitive | :host-state-primitive
| :cyclic | :in-memory-handle | :non-canonicalizable | :foreign-parked-ref`
(the last two from §7.3.2 and §7.6.3). There is no partial UCF value and no
placeholder for a missing leaf. A program that wishes to be migratable keeps
host resources behind stream descriptors and named, profiled primitives,
which is the same discipline the host boundary rules already impose on
portable code (`datom.world.md`, *Host Boundaries*). The refusal is the
emitter's outcome, returned as data to whatever asked it to lift; the
machine itself is unaffected and remains parked locally.

## §7.6 Dependency closure and context (blocker 3)

### 7.6.1 Declared, not hand-written — and discovered conservatively

A resumer must be able to decide *before lowering* whether it can run the
value. `:yin.k/requires` carries that decision's inputs, and every set in it
is **computed by a conservative fixed point**, not hand-written and not
guessed from the current environment:

```clojure
:yin.k/requires
{:yin.k/segments        #{:segment/sha256-3f1a… :segment/sha256-9c02…}  ; every address in frame, k, closures, values, parked
 :yin.k/primitives      {sym → profile, §7.5.2}                        ; every {:yin.k/primitive …} encoded, plus every free :var
 :yin.k/modules         {my.mod :segment/sha256-…}                     ; module name → manifest address
 :yin.k/effects         #{:stream/next :stream/put :module/require}    ; effect kinds the segments can raise
 :yin.k/ffi-ops         #{:op/add}                                     ; :yin.code/ffi-op values present
 :yin.k/streams         #{<identity> …}                                ; logical-stream identities referenced anywhere
 :yin.k/cursor-profiles #{:dao.stream/file-v1}                         ; transports that must accept kept cursors, §7.4.3
 :yin.k/discovery       :complete}                                     ; :complete | :incomplete | :blocked — computed, §7.6.5
```

The fixed point starts from the frame, its K frames, the parked records of
§7.6.3, the store slice, and the pending wait. Its unit of analysis is a work
item `[code-address context]`, not an address: `context` is the finite
abstraction of the captured environment with which a closure enters that
code. Code is fetched, validated, and projected once per address, but every
newly discovered address-context pair is analyzed. Two closures over the
same address with different captured environments are therefore distinct
work items. A full pass repeats whenever it adds a work item or any dependency
fact, and convergence is reached only when a full pass adds neither:

- **Values:** every encoded closure contributes its segment address and its
  captured env's values and the work item
  `[segment-address captured-env-context]`; every stream and cursor marker
  contributes a stream identity; every `:yin.k/primitive` marker contributes
  a name. A captured environment contributes reachable values, never name
  discharge.
- **Code:** every reachable segment's instructions are walked — as datoms
  where the emitter holds the batch, as canonical tuples where only a vector
  was fetched (§7.3.4) — including **`:store-get` and `:store-put`
  operands**, which name store
  keys directly and which the first draft missed entirely; every `:var`
  name; every effect op (`:stream-*`, `:ffi-call`, and the effects of
  `:effectful`-class primitives the walk finds); every `:resume` operand
  (a parked id, §7.6.3). Discovering a new work item continues the fixed point
  with that address and context even when the address was already walked for
  another context.
- **Names:** only a free `:var` name is an obligation. An obligation is
  discharged by a matching key in the reachable store slice, or retained as
  a required primitive or module export and checked by profile (§7.5.2). An
  environment never discharges a name: captured environments contribute
  values to their work item's reachability analysis, while lexical binding
  determines whether a `:var` is free before it becomes an obligation. If no
  store key or profiled primitive/module requirement can discharge an
  obligation, discovery is `:incomplete`; after lowering, an unavailable
  retained requirement is `:yin.k/unsatisfied` (§7.6.5).
  Definitions are syntax (Rule R, S7.5.2): the definition operator
  `yin/def` is never a free name and never an obligation, because a
  well-formed segment has no `:var` naming it (`free-names` never returns
  it); a `[:define name]` operand is a store key the segment writes, like
  a `:store-put` operand, not a name it resolves.
- **Modules:** a module's manifest declares its exported primitive profiles
  and its **store footprint** (store keys and effect kinds its handlers may
  touch). Until manifests carry footprints, any required module whose
  footprint is undeclared sets `:yin.k/discovery :incomplete` — the honest
  statement that closure could not be computed, never a silent
  under-approximation. A module effect handler may read store keys the
  static walk cannot see; a composition may supply extra slice keys
  explicitly, and the value records that it did.

Because the computation is over data, the same query answers "what does this
program need?" for a segment that has never run. And because discovery can
*fail to terminate successfully* — a missing segment cannot be walked, an
undeclared footprint cannot be closed — the result distinguishes
`:complete` from `:incomplete` from `:blocked` (§7.6.5); a fixed point that
could not finish is reported as such, not passed off as a satisfied closure.

### 7.6.2 The store slice, restored as an isolated store

The store S holds `def` results, stream handles, cursor entries, and the FFI
pair (§4.1). None of it is global in the UCF sense; it is the emitter's. A
continuation carries the **reachable slice**: every store key the fixed
point of §7.6.1 can name — every free `:var` obligation for which the
emitter's store has a matching key, every `:store-get`/`:store-put` operand,
every stream/cursor cell — with values encoded by §7.5:

```clojure
:yin.k/store {counter 41
              'log {:yin.k/tag :yin.k/stream …}}
```

The FFI pair is never in the slice — a program that names the pair's store
keys directly holds handles, and the lift refuses it (§7.5.4). A resumer has
its own pair; outstanding calls route per §7.4.3.

**There is no merge.** The first draft said, in two places, both that the
slice wins on collision and that the slice merges *under* the local store —
a contradiction, and beneath it a real defect: resolution is env → store →
primitives (`resolve-var`), so a resumer whose local store happens to bind
`+` would change what the migrated program resolves, without any collision
at all. Merging in either order is the wrong shape. **The resumed task runs
in an isolated execution store**: a fresh store whose contents are exactly
the carried slice, the remapped cells of §7.5.3, and the resumer's FFI pair
— nothing else of the resumer's, nothing of any other task's. A name absent
from the slice resolves through primitives and modules at the resumer
exactly as it did at the emitter, because there is no third store to
intervene; a slice key cannot collide with a receiver task's key because no
receiver key participates; resource entries are remapped to fresh local
keys (cursor cells per §7.5.3, stream handles per §7.4.3), and non-resource
entries keep their keys verbatim. "Unreachable store entries do not travel"
survives unchanged — anything the program did not observe is not the
program's state — and nothing else shares state with the resumed task by
accident.

### 7.6.3 The migration unit is one task

The reference machine's scheduler state does not live in the store, and the
first draft transported none of it. Both omissions are defects: the
machine's fresh identifiers come from `:id-counter`, and `:resume` resolves
its operand against the `:parked` map — a fresh receiver could reuse an
existing generated identifier, or fail to resolve a parked continuation a
migrated segment is about to name (`engine/park-continuation`,
`engine/resume-continuation`, §3.5). UCF therefore states the unit and
carries the state:

- **The unit is the whole blocked machine**: registers (the frame), the
  wait-set (as pending variants, §7.4.3), the referenced `:parked` records,
  and the fresh-name counter. The ready-queue must be empty at lift
  (`:yin.k/not-quiescent` otherwise, §7.4.1) — a task with queued runnable
  work has a next configuration that is not the parked one.
- **Fresh-name state travels.** `:yin.k/scheduler {:yin.k/id-counter n
  …}` carries the counter verbatim; the lowered machine adopts
  `max(local, carried)` so generated ids never repeat, and ids already
  minted (gensym results in env, stack, or store) travel as ordinary values,
  unaffected by the counter.
- **Referenced parked records travel.** The fixed point
  of §7.6.1 collects every `:resume` operand in reachable code and every
  `[:parked id]` value from reachable environments. Each must resolve
  within this task to a parked record carried under
  `:yin.k/scheduler :yin.k/parked {pid → frame}`. A missing id referenced
  as a code operand is reported as an unsupplied requirement
  (`:yin.k/unsatisfied`). A missing id referenced by an active value
  refuses the lift (`:yin.k/non-portable`, kind `:foreign-parked-ref`),
  as it represents corrupted state. Parked records not named by the
  reachable graph do not travel.

### 7.6.4 Modules

`:yin.k/modules` names each module by the content address of its published
manifest, on the model of `dao.space.index/publish-index!` (`dao.jing.md`,
*Publication from an agent*). A module is satisfied when the resumer holds a
module registry entry (`yin.vm.module` — a value, supplied by the
composition) whose manifest address is equal; it is not satisfied by a
same-named module of different content. Module *code* is segments and is
covered by `:yin.k/segments` and the manifest's own addressing; the manifest
pins the effect handlers, the exported primitive profiles (§7.5.2), and —
per §7.6.1 — the store footprint the fixed point needs to claim
`:complete`.

### 7.6.5 The satisfaction check

Before lowering, the resumer evaluates `:yin.k/requires` against what it
holds and answers with one outcome:

```clojure
{:yin.k/status    :yin.k/unsatisfied
 :yin.k/discovery :incomplete                    ; or :blocked, §7.6.1 — the computed status of the closure itself
 :yin.k/missing   {:yin.k/segments       #{:segment/sha256-9c02…}
                   :yin.k/primitives     {println <expected profile>}
                   :yin.k/modules        {my.mod …}
                   :yin.k/streams        #{<identity>}
                   :yin.k/cursor-profiles #{:dao.stream/file-v1}}}  ; keys present only where something is missing
```

The check reports everything missing in one pass *that the fixed point could
see*, and says which kind of pass it was: `:complete` (the closure is fully
computed; the missing list is exhaustive), `:incomplete` (a module footprint
was undeclared; the missing list is a lower bound), or `:blocked` (a missing
segment stopped discovery; satisfying `:yin.k/segments` and re-running the
check is the only way forward). A missing segment may be resolved by
`dao.jing` fetch (§7.3.4); a missing primitive, module, or cursor profile
cannot be resolved by the resumer and is a routing decision for whoever
chose this resumer. A resumer never lowers a partially satisfied value; a
host with no implementation for something answers with the qualified
unsupported outcome, which `datom.world.md` calls a correct outcome, not a
gap to be filled.

## §7.7 Ownership arbitration — custody as a lease over datoms (blocker 4)

### 7.7.1 The race, named

The emitter parks, lifts, appends the UCF value to a medium, and keeps its
local `:parked` record. Three readers see the value. The emitter's own wait
set wakes because the stream it was waiting on delivered. Now four machines
believe they may continue one computation. Nothing in DaoStream can stop
them: a stream has no privileged reader, there is no destructive read, and
"the hard part of a take was never the removal but the *exclusion* of
competing takers, which is a lease over datoms" (`dao.stream.md`,
*Explicitly Absent*).

UCF adopts that sentence as its design. **Resumption is a take. A take is a
write. Exclusion is a lease.** Nothing new is added to the stream contract,
and no key is added to any result map.

### 7.7.2 Custody facts, and their dispatch keys

Custody is negotiated on an **arbitration medium** — a `dao.space` whose
facts are ordinary datoms — using `dao.lease.md`'s vocabulary unchanged,
with the checkpoint **occurrence** as the leased subject. The UCF-authored
facts are dispatch-keyed exactly as lease facts are (`dao.lease.md`,
*Vocabulary*: a reader switches on `:dao.lease/status`; a fact carrying it
is a lease fact, one carrying neither is ignored):

| Fact | Dispatch | Author | Required keys |
|---|---|---|---|
| offer | `:yin.k/custody :yin.k/offered` | emitter | `:yin.k/occurrence`, `:yin.k/id` (this snapshot variant), `:yin.k/policy`, `:yin.k/medium` (the carrier identity) |
| checkpoint done | `:yin.k/custody :yin.k/resumed` | holder | `:yin.k/occurrence`, `:dao.lease/lease`, `:yin.k/result` — the address of the successor's occurrence value or of a `:yin.k/result` |

Lease facts themselves keep their own table verbatim — proposal
(`:dao.lease/proposed`, with `:dao.lease/subject = {:yin.k/occurrence O}`),
grant (`:dao.lease/accepted`, with `:dao.lease/lease :dao.lease/holder
:dao.lease/subject :dao.lease/duration`, and `:dao.lease/max` optional),
refusal, release, renewal, lapse — each dispatched on `:dao.lease/status`
or `:dao.lease/event`, each with `dao.lease.md`'s required keys, unchanged.
A lease judge reading the same medium switches on the lease keys and
ignores the `:yin.k/custody` facts; a UCF reader switches on
`:yin.k/custody` and reads lease facts for authority. Both `:yin.k/custody`
facts are **evidence, not authority**: an offer creates no claim, and a
`:resumed` record is a holder's report — completion is the grantor's ledger
transition, not the report (§7.7.6). Authority is exactly `dao.lease.md`'s:
**only grantor-authored facts establish terms.**

The subject of every grant is the **occurrence**, never `:yin.k/id`:
snapshot variants and retries of one park share one occurrence, so no pair
of encodings can hold independent grants, and an equal-content copy minted
by coincidence is a different occurrence and a different computation
(§7.3.4).

### 7.7.3 The authority: what the grantor possesses

`dao.lease.md` is explicit that a judge must be composed **inside the
boundary that possesses the resource** and must reclaim *by acting on what
it itself holds*; a judge that possesses nothing is outside the contract.
The first draft designated "the composition-designated arbiter for the
carrier medium," defaulting to the emitter — an arbiter relative to a
carrier, possessing an offer, which is a copy of content: the same
occurrence carried on a second medium would acquire a second arbiter, and
neither arbiter controls execution of anything. This section replaces that
with a grounded design.

**The possessed resource is the occurrence's execution ledger**: the set of
datoms about one occurrence id on the arbitration `dao.space` — its offer,
grants, renewals, lapses, and resumed reports, plus the occurrence's
**admission epoch** (§7.7.5). The **grantor is the boundary that transacts
that space** — its transactor. Possession is exact and of the right kind:

- the ledger's datom base lives inside the transactor's boundary, and
  admission to it is the transactor's alone — no other boundary can write
  those datoms, which is why grantor-authored facts are worth more than
  holder reports;
- the **reclaim** is the grantor acting on what it holds: recording the
  lapse on its own base and advancing the occurrence's epoch datom. It
  needs no cooperation from any holder and no reach into any carrier;
- the arbiter is **not medium-relative**: the binding
  occurrence → arbitration space is minted at first export and travels *in
  the value* (`:yin.k/arbitration`, §7.2), so every carrier, every snapshot
  variant, and every copy names the same one authority. The same content on
  two media does not create a second arbiter, because the authority is named
  by the occurrence, not discovered from wherever a value happened to land;
- **one holder per occurrence is the grantor's policy, enforced where
  policy lives**: `dao.lease` does not impose it, and UCF does not add to
  the vocabulary — the grantor's ledger admits at most one live lease per
  occurrence (a second grant requires the prior lease reclaimed first,
  which `dao.lease.md`'s own validity rules make the grantor the sole
  author of). Exclusive custody is thus a property of the ledger the
  grantor possesses, not an aspiration over streams it does not.

What is possessed, concretely, is the *right to admit effects as this
occurrence* — the ledger is where epochs and dedup-stamped admissions are
recorded, which is what §7.7.5's fencing consults. A composition that
cannot wire a `dao.space` transactor as authority cannot offer
`:yin.k/exclusive` custody at all; it composes `:yin.k/fork` only. That is
an honest capability limit, not a gap to paper over.

### 7.7.4 The exporting state: fencing the source before publication

Emitter-as-candidate was the first draft's whole answer to the
source-wakeup race, and it does not close the publication window. The
lifecycle published first and made the emitter a candidate afterward, but
nothing ever removed the emitter's *local* eligibility: its driver may
still poll the wait set — and polling a blocked writer **performs the
append** (the engine, a `:put` entry retries `append!` on every
poll) — may restore a woken entry from the ready queue, may satisfy a
direct `:resume`, and may retry a blocked write, all after the value is in
other machines' hands. Delaying register restoration is too late; the
append is the effect.

UCF therefore defines an explicit **exporting** transition that precedes
any publication, and the lift driver performs it as one step over the
machine value:

1. **Quiesce**: the ready-queue must be empty (§7.6.3).
2. **Detach the wait set**: every wait entry moves from the machine's
   wait-set into the export record. The machine's wait-set is now empty,
   so *there is nothing to poll* — no poll, no append, no effect. The
   entries are not lost: the export record retains them verbatim; they are
   the source of the `:yin.k/pending` variants of §7.4.3.
3. **Neutralize control**: the record's control and K are already absent
   (the machine is parked or blocked); direct `:resume` resolves against
   `:parked`, and the parked records referenced by reachable code travel
   inside the export record — a local `:resume` against them is refused
   while exporting (the restore function observes the exporting machine
   and declines), because the parked id now names a checkpoint that may be
   granted elsewhere.
4. **Publish, then offer**: append segments and the value body; record
   `:yin.k/offered` on the arbitration medium. Only now do readers exist,
   and the source was fenced before any of them did.

The exporting machine is pure data with an empty wait-set and a retained
export record — no engine change, no callback, no timer; the drivers of the
world observe the state as they observe `ready-for-ingress?` (§3.1), and a
driver that polls an exporting machine's wait set anyway is a host assembly
defect of the same kind as wiring a refusing deposit destination.

**Failure recovery and idempotent retry.** If publication fails — a full
carrier, an unreachable arbitration medium — the machine stays exporting
and the composition retries; a retry re-encodes or reuses the encoded value
and **reuses the occurrence** (§7.3.4), so no retry mints a second runnable
checkpoint. Abandoning the export reinstates the export record into the
machine (wait entries return to the wait-set, parked records to `:parked`)
and the task resumes as a purely local one — the sole paths back to local
execution are *abort export* (before the offer is recorded) and *a grant to
the emitter itself* (after); there is no third.

### 7.7.5 Fencing effects: epochs, operation ids, and what exactly-once costs

`dao.lease.md` states it plainly: **the absence of a `:lapsed` fact is
never evidence of tenure.** The first draft's fencing — stamp effects with
the lease id, let consumers consult the medium and drop effects whose
incarnation has a `:lapsed` record — fails in every direction the review
named: a partitioned consumer accepts stale effects (it cannot see the
lapse); a delayed legitimate effect is discarded after release; repeated
effects within one lease share one incarnation and are indistinguishable; a
replacement holder repeats an effect its predecessor committed; and apply
ids correlate requests with responses, they do not suppress duplicates.
Lapse-record filtering is correlation, not fencing. What UCF requires
instead:

**Authority epochs, checked atomically with commitment.** The occurrence's
ledger carries an epoch datom, advanced by the grantor on every reclaim
(§7.7.3) — a monotone number the possessing boundary alone can write. A
consumer that must be exactly-once (a `dao.space` transactor, an FFI callee
with side effects) admits a fenced effect only in the same act that reads
the epoch: the check and the commit are one transition against the
admission resource, never check-then-act. An effect whose incarnation's
epoch is not the current one is refused at commitment. This is
`dao.lease.md`'s "fencing from the resource" for durable resources, and it
is a composition duty that composition owes exactly once.

**Stable operation ids, and durable dedup.** Every fenced effect carries
`:yin.k/op-id {:yin.k/occurrence O :yin.k/seq n}` — assigned by the fenced
writer in emission order. Op-ids are deterministic functions of the
checkpoint and the operation sequence: a replacement holder resuming the
*same* occurrence replays the same op-ids, which is what makes
across-incarnation dedup possible at all. Exactly-once consumers keep a
durable `{op-id → committed result}` record, written in the same atomic
transition as the epoch check; a replayed op-id returns the recorded result
and commits nothing. Within one incarnation and across incarnations, the
same op-id is the same logical operation — the room for exactly-once
semantics that lease identity alone cannot provide.

**Where the stamp rides.** Program values are program data; UCF never
edits them. A resumed incarnation running under a lease emits through a
**fenced writer** the composition wires: a writer that wraps each appended
value in an envelope `{:yin.k/envelope … :yin.k/incarnation <lease>
:yin.k/op-id … :yin.k/value <the program value, verbatim>}`. Exactly-once
consumers read stamped envelopes; a stream wired bare — no fence — is
*declared* unprotected, and a composition that needs exactly-once delivery
through it has wired the wrong thing. Fencing is opt-in per stream, exactly
as `dao.lease.md` places the burden: "a holder needing exclusion obtains it
from the resource." No callback tells a lapsed holder to stop; it stops at
its own lease bound (`dao.lease.md`, *The holder*), and what it did past
the bound is distinguishable after the fact by epoch and op-id.

**Durable completion of a checkpoint.** Publication of a successor is not
completion. The holder's exit sequence is: append the successor value to
the carrier, append `:yin.k/resumed` (evidence) to the arbitration medium,
append `:dao.lease/released`. Completion is the **grantor's ledger
transition** — observing the release (or the `:resumed` evidence followed
by release) and closing the occurrence's tenure. The crash windows are then
bounded and harmless: a crash after the successor append but before the
records leaves an orphan successor — content-addressed, idempotent,
re-readable as evidence but granting nothing; the lease lapses by silence;
the grantor re-grants the *last recorded* occurrence, and the orphan chain
is queryable history (§7.7.6). A crash mid-sequence never requires the
grantor to trust a holder's report as proof of durable completion, because
the report is evidence and the ledger is authority.

**Partition policy, stated honestly.** A fenced consumer that cannot reach
the authority (epoch unreadable) must **suspend protected effect
commitment** — fail closed — or the composition has chosen at-least-once
delivery with after-the-fact dedup reconciliation; which one is a declared
property of the consumer, and a consumer that neither checks nor declares
is not exactly-once and may not claim to be. A holder partitioned from the
arbitration medium runs to its own lease bound and its effects are
after-the-fact distinguishable, which is the guarantee `dao.lease.md`
actually makes; UCF does not promise more. A grantor that lost its ledger
reclaims and re-grants (`dao.lease.md`, *Restart*); **permanent** authority
loss breaks exclusive custody irreparably — the ledger was the resource —
and repair is a governance act outside this protocol. The arbitration
medium's durability is therefore a composition duty owed before
`:yin.k/exclusive` is offered at all (`dao.lease.md`, *Composition
duties*: a judge whose durability matches the resource).

### 7.7.6 Successors and chains

A resumed continuation runs to its next safepoint and either halts or parks
again. Either way it produces a successor value — a `:yin.k/continuation`
or a `:yin.k/result` — whose `:yin.k/origin` carries the predecessor's
`:yin.k/occurrence` and the lease it ran under. Each park is a **new
occurrence**: minted fresh by the running holder, chained to its
predecessor, and itself the subject of the next custody cycle. The holder
appends the successor, records `:yin.k/resumed` with the successor's
address, and releases. The chain of occurrences linked by `:yin.k/origin`
is the migratory computation's history, queryable on the arbitration
medium: where it ran, under which grant, what it became. A rollback is a
re-offer of an earlier link; effects past that link need compensation, not
recomputation (`streams-all-the-way-down.md` §6.4) — and §7.7.5's op-ids
are what compensation reads to find what was done.

### 7.7.7 Restart

A grantor that lost its ledger reclaims and re-grants (`dao.lease.md`,
*Restart*: reclaim first, then grant afresh — a new grant alone does not
end the prior tenure). Its inventory of possessed resources is the set of
occurrence ledgers it transacts; its recoverable holder for each is the
`:dao.lease/holder` of the last grant, both of which are facts on (or the
recoverable state of) the space it possesses. Both are queries, which is
why custody lives on a `dao.space` and not on the carrier stream.

## §7.8 Lifecycle: lift on park, lower on resume

**Lift** (emitter side; composition-driven, never automatic on every park):

1. The machine is parked or blocked with a local record `r`, and its
   ready-queue is empty, else `:yin.k/not-quiescent`.
2. **Enter exporting** (§7.7.4): detach the wait set into the export
   record, neutralize direct `:resume`. From here the source is fenced; no
   poll, retry, or local resume can perform an effect.
3. `lift(export-record)`: confirm the record is at a safepoint (§7.4.1),
   else `:yin.k/not-at-safepoint`; canonicalize every referenced segment and
   compute its address (§7.3.2), else the refusal; encode (§7.5), else the
   refusal; compute `:yin.k/requires` and `:yin.k/store` by the fixed point
   (§7.6), recording the discovery status; stamp the contract (§7.3.3); on
   first export, mint the occurrence; mint `:yin.k/id`.
4. Append the canonical instruction vectors of every required segment to a
   `dao.jing` intake pool (the payloads *are* the vectors, §7.3.4), then
   the id-less value body to the carrier medium; observe `:dao.stream/ok`
   on each. `:yin.k/carried` may then be empty. Retry of any failed append
   reuses the occurrence (§7.7.4).
5. Under `:exclusive`, append `:yin.k/offered` to the arbitration medium.
   The export record stays for recovery; the emitter is now a candidate
   like any other, and wakes only on custody (a grant to itself), never on
   data.

**Lower** (resumer side):

1. Read the value; check `:yin.k/contract`, else `:yin.k/profile-mismatch`.
2. Verify `:yin.k/id` by re-hashing the body, else `:yin.k/hash-mismatch`.
3. Decode against the §7.5.1 grammar with full structural validation, else
   `:yin.k/undecodable` — a correct hash is not structural validation, and
   both checks always run.
4. Evaluate `:yin.k/requires` (§7.6.5); fetch what may be fetched (segments
   by address, verified by re-canonicalization); else
   `:yin.k/unsatisfied` with the discovery status.
5. Under `:exclusive`, attach to the arbitration medium (an attachment
   failure here is `:yin.k/unsatisfied` naming it — the value declares its
   authority, §7.7.3), propose, then observe the grant, else
   `:yin.k/not-holder` with the lease state observed; while a proposal is
   unanswered the driver's step answers `:yin.k/awaiting-grant` — a
   grantor owes no answer and no bound applies (`dao.lease.md`,
   *Authority*).
6. Load every required segment by address into the per-stamp index,
   verifying each (§7.3.4).
7. **Restore into an isolated store** (§7.6.2): the slice, the remapped
   cells, the resumer's FFI pair. Attach to every stream descriptor in the
   frame, pending waits, and cells (§7.4.3); an attachment `not-found` is
   `:yin.k/unsatisfied` naming the stream. **Any failure from this step
   onward — after custody was acquired — releases first**: the resumer
   appends `:dao.lease/released`, the occurrence returns to offered, and
   the failure outcome is returned. A resumer that dies without releasing
   is recovered by the lease's own lapse; nothing else is owed.
8. `lower(frame, layout)` into engine registers; re-mint every pending wait
   in the local wait set; seed `:id-counter` per §7.6.3; run — through a
   fenced writer under `:exclusive` (§7.7.5).
9. At the next safepoint or halt, produce the successor occurrence
   (§7.7.6), record `:yin.k/resumed`, release.

Neither sequence contains a callback. Each is a step function a
composition-supplied driver repeats, as for every other interpreter in the
system.

## §7.9 Outcome algebra

Every UCF operation returns one map dispatching on `:yin.k/status`; the set
is closed and every non-`ok` outcome carries the data needed to act on it.

| `:yin.k/status` | Raised by | Carries |
|---|---|---|
| `:yin.k/ok` | any | the value, or the lowered VM |
| `:yin.k/not-at-safepoint` | lift | `:yin.k/segment`, `:yin.k/pc` |
| `:yin.k/not-quiescent` | lift | the ready-queue depth |
| `:yin.k/non-portable` | lift | `:yin.k/path`, `:yin.k/kind`, `:yin.k/hint` — kinds at §7.5.4, cycles included as `:kind :cyclic` |
| `:yin.k/profile-mismatch` | lower | `:yin.k/contract` expected and found |
| `:yin.k/hash-mismatch` | lower | the address claimed and the address computed |
| `:yin.k/undecodable` | lower | `:yin.k/path`, the structural defect — malformed markers, missing or cyclic refs, malformed-but-correctly-hashed bodies |
| `:yin.k/unsatisfied` | lower | `:yin.k/missing`, `:yin.k/discovery` — includes attachment failures, cursor profiles, and an unreachable arbitration medium |
| `:yin.k/awaiting-grant` | lower | `:yin.k/occurrence`, the proposal — a step status while a proposal is unanswered, bounded by nothing (`dao.lease.md`) |
| `:yin.k/not-holder` | lower | `:yin.k/occurrence`, the lease state observed |

Terminal DaoStream outcomes met during lift or lower (`transport-error`,
`closed`, `invalid-descriptor`, …) are surfaced unchanged inside the UCF
outcome under their own key `:dao.stream/outcome`, exactly as the transport
produced them; UCF does not rename them, nest them under private keys, or
translate between them. FFI requests and responses inside a value use the
reference machine's versioned envelope, `dao.stream.apply` —
`:dao.stream.apply/id`, `/op`, `/args`, `/ok`, `/error` — never the
deleted v1 `dao.stream.apply` vocabulary. Every lower failure names its
cleanup obligation: before custody, none; after custody, release
(§7.8 step 7).

## §7.10 Invariant compliance

| Invariant | How UCF honors it |
|---|---|
| No hidden global state | Every dependency is declared in `:yin.k/requires` — *as computed by the declared fixed point, with its completeness stated* (`:yin.k/discovery`); the store travels as an explicit slice restored into an isolated store; fresh-name state and parked records travel explicitly; custody is facts on a named, possessed medium. |
| No implicit control flow | Resume happens only at a safepoint the machine's parking transitions define; the successor chain is explicit `:yin.k/origin` links; the exporting state is an observed data state, never a notification. |
| No callbacks | Lift and lower are step functions; custody is observed, never notified; a lapsed holder stops at its own bound; an unanswered proposal is a step status, not a wait. |
| No shared mutable state | A UCF value is content-addressed and immutable; occurrence identity separates content variants from the one leased computation; two resumers of one value under `:fork` diverge into distinct incarnations; the isolated execution store shares nothing with the resumer's tasks. |
| No layer collapsing | Canonicalize, encode, discover, arbitrate, load, restore, execute are separate functions with data between them. The engine's hot loop is untouched: lifting is a pure function over the blocked machine's value, and the exporting state is that value with an emptied wait-set and a retained record — driver discipline, not engine machinery. |
| No assumed graphs | Sharing is an explicit value table keyed by address; cursor aliasing is an explicit cell graph, not inferred from positions; code refs are pcs relative to an addressed vector; nothing is resolved by pointer; foreign engines reconstruct dynamic state from the frame, never from layouts they assume. |

And the axioms — each claim conditioned on the obligation it depends on: the
continuation is a stream value (1); its meaning is supplied by the
resumer's loader and contract stamp, not carried in it (2); its code and its
state are datoms under `:yin.code/*` and `:yin.k/*` (3); and it pauses,
travels, and resumes anywhere that **holds what it declares, satisfies the
declared cursor profiles, and obtains custody where the policy demands it**
(4) — the unconditional form of that sentence was a claim the unresolved
state closure, custody enforcement, and effect admission had not earned.

## §7.11 Acceptance blockers for the implementation phase

The warning at the top of this file is retained until each of these is
closed with a test in the parity or conformance suite. The first four are
the review's named architectural obligations; they are blockers to
*accepting the design*, not merely work items.

- **Authority grounding and wiring.** The arbitration `dao.space`, its
  transactor as grantor, the occurrence ledger with epochs, and the
  attribution resolver `dao.lease.md` requires, are composition duties; the
  first composition to ship UCF is `yin.repl.core`'s handoff demo, and
  its wiring becomes the worked example. A composition without a
  transactable space offers `:yin.k/fork` only.
- **Execution identity.** Occurrence minting, the `:yin.k/origin` chain,
  retry idempotence (one occurrence per park), and successor completion as
  a grantor ledger transition need conformance tests, including the crash
  windows of §7.7.5.
- **Pending-state completeness.** Every pending variant of §7.4.3 must
  round-trip and resume *elsewhere*: retained writes, sent and retained FFI
  calls with both endpoints and the kept response cursor, and cursor cells
  through the aliasing scenarios of `check-wait-set`. This depends on a
  **portable cursor profile** in at least one transport, which
  `dao.stream.md` leaves TBD — an external dependency UCF inherits and
  cannot close itself.
- **Enforceable fencing.** Epoch-checked, atomic-with-commitment admission
  and durable op-id dedup at consumers: which consumers check, and how a
  `dao.space` transactor consults the epoch without a callback, is a design
  item for `dao.space.transactor.md`. Until it lands, `:yin.k/exclusive`
  custody is at-least-once and must say so.
- **Canonical byte encoding.** `:yin.code/hash` and `:yin.k/id` are exactly
  as portable as `dao.jing/segment-key`, which is transitional. Closing this
  is `dao.jing`'s open item, not UCF's, but UCF cannot be declared
  cross-host stable before it lands.
- **Contract revision publication.** `:yin.code/contract "v3"` needs a
  published revision history naming the complete execution contract
  (§7.3.3): the tuple grammar — mnemonic set, per-mnemonic arity and operand
  kinds, saturation/defaults table — the opcode table and transitions, the
  resolution and last-value-wins rules, the effect outcome map, and the
  scheduler semantics, written into `yin.vm.semantic.md` §2.4 beside the
  opcode table.
- **Primitive profile publication.** The standard `yin.vm/primitives`
  map published with profiles (S7.5.2), including the `:effectful`
  declaration for `require` (`yin/def` is syntax, not a primitive);
  reverse-lookup uniqueness asserted at `create-vm`.
- **Definition frames under Rule R.** The UCF frame encoding of a
  pending definition (a define continuation awaiting its value), stamp
  comparison at continuation lowering, module-store snapshot lowering
  through `engine/store-put`, and the lift and lower round trip of a
  parked definition are M4 obligations and do not pass today.
- **Discovery completeness.** The fixed point of §7.6.1 implemented and
  tested against the `:store-get`/`:store-put`, dynamic-callee, and
  cross-activation-`E` cases; module manifests publishing store footprints
  so `:yin.k/discovery :incomplete` disappears.
- **Isolated-store lowering.** Conformance tests that the resumed task's
  store contains exactly the slice and the resumer's pair, and that a
  receiver-side binding of a name in the slice's name set cannot change
  resolution.
- **Safepoint conformance harness.** A test that runs every corpus segment
  on the reference machine to each safepoint of the complete §7.4.1 table —
  effect-producing calls included — lifts, lowers into a fresh VM, and
  checks the parity suite's result; the same harness is what a foreign
  engine runs to claim conformance, including `lift ∘ lower = id`, the
  static `stack-effect`/`lexically-required` checks, and the §7.3.4
  projection/direct-path image-equality assertion.
- **Exporting-state integration.** The lift driver's exporting transition
  and its failure/retry paths, tested specifically against the
  poll-a-blocked-writer-appends hazard of §7.7.4.
