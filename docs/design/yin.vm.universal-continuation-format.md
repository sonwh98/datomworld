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
Amended 2026-10-04 (r5) for fenced custody (`yin.vm.linker.dht.md` 14.3,
M-next B): the handoff body is published as wire grammar, and
`:yin.k/version 1` adds the custody header, the operation-sequence state,
the fenced envelope, the grant epoch binding, and the admission outcomes
(7.2.1, 7.4.3, 7.7.8, 7.9, 7.11.1). The amendment is published design:
no version-1 code is landed and it closes no blocker.
Revised 2026-10-04 (r6) after the second architect's adversarial review
(collab `1791056670000-architect-ucf-v1-amendment-review`): inherited
operation ids are scoped to the granted checkpoint's carried pendings,
dedup is one namespace per admission resource, admission outcomes are
authenticated, uncertainty is separated from terminal refusal, epoch
exhaustion has a stated precedence, install children validate in their
root's context, and defective envelopes yield a structured diagnostic.
Revised 2026-10-04 (r7) on that architect's confirmation (collab
`1791057600000-architect-ucf-v1-amendment-r6-confirm`): checkpoint
unavailability suspends after the tenure check and mutates nothing,
snapshot variants preserve the operation baseline, the install phase
no longer constrains the child's kind, the defect set is defined in
full, and unknown transport acceptance is neither commitment nor its
absence.
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

Outcomes of every UCF lift or lower operation are data: one map
dispatching on `:yin.k/status`, in the DaoStream result convention. Where
an underlying stream outcome is met by a lift or a lower, it is preserved
unchanged under its own dispatch key `:dao.stream/outcome`: for those
direct failures UCF never renames it or nests it under a private key.
Admission outcomes are a second, disjoint family with their own dispatch
key. The full algebra is in 7.9.

### 7.2.1 The handoff body: the task wire grammar, versions 0 and 1

(Amendment r5, 2026-10-04; `yin.vm.linker.dht.md` 14.3 item 2.) The
envelope above is the design shape. The value a driver publishes is the
**handoff body**: one task, the whole blocked machine of 7.6.3, as one
map in canonical CBOR (`dao.jing.cbor.md`) under its content address.
Where the envelope sketch and this grammar differ, this grammar is the
wire contract. A task has an ordered wait set, so the body carries
ordered `:yin.k/frames`, never one `:yin.k/frame`. The scheduler slice of
7.6.3 is flattened into top-level keys. Code travels as an address-keyed
map. The body carries no `:yin.k/id`: its address is the hash of the body
(7.3.4). A halt is a body of kind `:halted`, the `:yin.k/result` sibling.

```clojure
{:yin.k/handoff       true      ; the tag
 :yin.k/version       0         ; the BODY's version: 0 or 1
 :yin.k/kind          :blocked  ; :blocked | :parked | :halted
 :yin.k/contract      {:yin.code/contract "v3" :yin.k/version 0}
 :yin.k/id-counter    n         ; 7.6.3 fresh-name state
 :yin.k/parked        {pid registers}      ; 7.6.3 parked records
 :yin.k/store         {encoded encoded}    ; 7.6.2 isolated slice
 :yin.k/module-stores {module {encoded encoded}}
 :yin.k/cells         {cell {:yin.k/stream marker
                             :yin.k/position p}}   ; 7.5.3
 :yin.k/requires      {:yin.k/cursor-profiles #{profile}
                       :yin.k/segments #{address}}
 :yin.k/code          {address vector}     ; 7.3.4, each verified
 ;; by kind
 :yin.k/frames        [{:yin.k/registers registers
                        :yin.k/pending pending}]   ; in wait order
 :yin.k/parked-id     pid       ; :parked only: the active record
 :yin.k/result        encoded   ; :halted only
 ;; when the task holds live install children (7.4.3)
 :yin.k/installs      {module install}}
```

`registers` is `{:yin.k/segment address :yin.k/pc n :yin.k/env {name
encoded} :yin.k/stack [encoded] :yin.k/k [frame]}`, every key required,
the segment a key of `:yin.k/code` and the pc inside that vector.
`pending` is one variant of 7.4.3. Every value is in the 7.5 grammar.

Shape rules, each a `:yin.k/undecodable` refusal naming its path:

- `:blocked` requires nonempty frames. `:halted` requires
  `:yin.k/result` and forbids frames. `:parked` requires
  `:yin.k/parked-id` naming a key of `:yin.k/parked`. A `:yin.k/result`
  on any other kind is refused.
- Frames restore in their carried order, for `:blocked` and for
  `:parked` alike; a resumer never drops a carried frame (7.4.3).
- A frame's resume pc is a static safepoint whose kinds admit the
  pending's reason; a parked record's resume pc is an `:explicit-park`
  safepoint (`yin.vm.ucf-revisions.md` section 8).
- The cells the body carries are exactly the cells its roots name:
  none missing, none extra. A body with cells claims
  `:dao.stream.remote/v1`, else `:yin.k/unsatisfied`.
- Each code vector hashes to its key (`:yin.k/hash-mismatch`) and is
  well formed under the stamp.

Lift adds these kinds to the `:yin.k/non-portable` set of 7.5.4:
`:incomplete-install`, `:install-response`, `:incomplete-write`,
`:reason-mismatch`, `:inconsistent-halt`, `:unaddressed-segment`,
`:address-mismatch`, and, under version 1, `:unprotected-pending` and
`:op-seq-exhausted` (7.4.3).

**Two version keys.** `:yin.k/version` at the top of the body is the
body's own version and is what this amendment raises. The key of the
same name inside `:yin.k/contract` belongs to the code stamp of 7.3.3.
That stamp is compared whole and stays `{:yin.code/contract "v3"
:yin.k/version 0}`: the amendment changes handoff data and composition
admission, not opcode semantics, so code stamps "v3", "b2" and "r2" and
module manifest schema 1 are unchanged, and cursor vectors keep their
bytes.

**The version gate.** A resumer decodes the bytes, checks the tag, and
then checks the body version before any other rule. A version it does
not speak, an absent version, or a version that is not an integer is
`:yin.k/profile-mismatch`, carrying `:yin.k/version` as found and
`:yin.k/supported`, the set the resumer speaks. The gate runs before
the remaining grammar, before any code hash is verified, before any
stream is attached, before any proposal is made, and before anything
is restored. An install child's body carries the version of the body
that holds it. Validate every nested body's version and structural
role before attachment, proposal, or restoration; a supported but
mixed-version tree is `:yin.k/undecodable`. The integer check is on
the canonical codec's integer kind, never on numeric equality: an
integral float such as `1.0` must not validate as a version on any
host, Node included, where both would compare equal to 1. A reader
that speaks only version 0 refuses a version-1 body by this gate and
never reads it as fork data.

**Version 0** is the grammar above and nothing more. It is a fork: it
names no occurrence, proposes nothing, and runs through no fenced
writer. A version-0 body stays valid under this contract and is never
upgraded to fenced custody, by a resumer or by a carrier. A version-0
reader ignores keys it does not know, so a custody key inside a
version-0 body establishes nothing. A reader that speaks version 1
keeps these fork semantics for a version-0 body exactly, and refuses
a version-0 body as `:yin.k/profile-mismatch` when exclusive custody
is required. Version 0 is fork only and version 1 is exclusive only.

**Version 1** is the fenced-custody grammar. A version-1 root body of
kind `:blocked` or `:parked` adds a custody header:

```clojure
{:yin.k/version     1
 :yin.k/policy      :yin.k/exclusive
 :yin.k/occurrence  O           ; the lease subject, 7.3.4 and 7.7.2
 :yin.k/arbitration {:dao.stream/identity i
                     :dao.stream/descriptor d}     ; 7.7.3
 :yin.k/origin      {:yin.k/occurrence P           ; the predecessor
                     :dao.lease/lease L            ; the grant it ran in
                     :yin.k/emitter a}             ; absent on first park
 :yin.k/next-op-seq n}          ; 7.7.8
```

The header belongs to the **root** body alone. Validation is
context-sensitive: the root is validated as a root, and each nested
install child is validated as a child, with the root's occurrence,
origin, and counter passed down to it. Header rules, each
`:yin.k/undecodable` naming its path:

- On a root of kind `:blocked` or `:parked`, `:yin.k/policy`,
  `:yin.k/occurrence`, `:yin.k/arbitration` and `:yin.k/next-op-seq`
  are required. The policy is exactly `:yin.k/exclusive`: version 1
  has no fork form, and fork uses version 0.
- `:yin.k/occurrence` is plain data in the canonical bytes domain,
  never nil, compared by its canonical bytes. It is stable across
  every publication retry and every restart of the emitter, distinct
  for distinct parks, and collision-resistant or made unique by the
  authority. The authority rejects a conflicting reuse: an offer of a
  known occurrence for a different park establishes nothing. The
  concrete form is fixed by M-next C, within these invariants.
  The concrete form (M-next C, slice C5): a UUID string in lowercase
  hex, one spelling, so canonical-byte equality is string equality.
  Any other form is `:malformed-occurrence`, in the root, its origin
  and every carried operation id. A reuse indistinguishable from a
  snapshot variant (equal occurrence, origin and baseline) is
  admitted as one.
- `:yin.k/origin` is absent on a first export and required on every
  successor root. Its occurrence differs from the body's own.
- `:yin.k/next-op-seq` is an exact integer of 7.7.8. It is 0 on a
  first export: that checkpoint is the recorded operation baseline.
- A root of kind `:halted` carries `:yin.k/origin` and none of the
  other header keys: a result is not a lease subject, and nothing
  remains to admit.
- An install child of any kind, halted included, carries none of the
  header keys, the origin among them. A child is part of its root's
  task, not a custody subject: its effects are admitted under the
  root's occurrence and drawn from the root's sequence, and its
  carried operation ids are checked against the root's origin and
  counter (7.4.3).

No body carries an epoch. A body is minted before any grant and its
bytes do not change on reclaim; the epoch is learned only from the
grant binding of 7.7.8.

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
- **Normalization gate.** The falsy `:gensym` prefix is not yet normalized
  alike: the loader defaults `false` to `"id"`, while canonicalization
  refuses it (`yin.vm.ucf-revisions.md` section 7.5). Before a UCF
  address is accepted, both paths must use one normalization rule, or
  admissible `v2` input must exclude that case by a published contract
  decision. The identity equivalence claim above is conditional on this
  gate; an untested edge case cannot acquire a portable code address.
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
 :yin.k/reason   :next                     ; :park | :next | :put | :ffi
                                          ; | :ffi-request | :call-effect
                                          ; | :link-request | :link-response
                                          ; | :install
 :yin.k/val      nil                       ; accumulator; nil except where §7.4.3 pre-fills it
 :yin.k/stack    [ … ]                     ; St per the table above, encoded
 :yin.k/env      { sym → encoded value }   ; E
 :yin.k/k        [ {:yin.k/frame-type :return                    ; or :eval-call / :request-sent,
                    :yin.k/segment :segment/sha256-…             ; which also carry :yin.k/call-id
                    :yin.k/pc 5 :yin.k/env {…} :yin.k/stack-base 0} … ]   ; K, innermost last
 :yin.k/pending  {…}}                      ; the wait it was in, §7.4.3 — one variant per reason
```

**Linker safepoints (M4).** Every block marked (linker M4) is specified by
`yin.vm.linker.md`; the private resources table, sealed references and
install child were implemented in M4 (S3 ships r8-r11, the install child
and link-module; S4 ships manifests). The running VM implements the
safepoint and resource semantics; only the UCF lift and lower of parked
link and install entries stays future. The require flow adds three
safepoints to the set above, one per wait state of a `:module/require`
effect that misses the module registry (`yin.vm.linker.md` 7.2, 7.3); a
hit answers without parking. The frame is the effectful-call row's in all
three -- `require` is named there -- and the wait entry moves between the
three reasons without the machine advancing, so the canonical resume pc
and the stack are that row's in every state:

+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+
| Safepoint kind | Raised by                              | Resume pc | Stack in frame (St)    | Accumulator on resume                            |
+================+========================================+===========+========================+==================================================+
| :link-request  | the :module/require effect on a        | pc+1      | St minus the popped    | no resume from this state: on ok                 |
|                | registry miss, before the link         |           | operator and args      | the entry becomes :link-response                 |
|                | request envelope is appended ok;       |           |                        |                                                  |
|                | a full retry keeps this state          |           |                        |                                                  |
|                | (the :ffi-request discipline)          |           |                        |                                                  |
+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+
| :link-response | the request append's ok; the kept      | pc+1      | St minus the popped    | a matching refusal, or a failed                  |
|                | cursor polls the link response         |           | operator and args      | discharge, raises as the effect's                |
|                | stream for the correlated id           |           |                        | error                                            |
+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+
| :install       | a matching ok response, its            | pc+1      | St minus the popped    | the required module's symbol on                  |
|                | obligations discharged, while          |           | operator and args      | linked; the refusal as the                       |
|                | the install child runs its             |           |                        | effect's error on refused                        |
|                | phases                                 |           |                        |                                                  |
+----------------+----------------------------------------+-----------+------------------------+--------------------------------------------------+

The pending variants of the three reasons are in section 7.4.3.

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
  and Gaps*). At migration the composition serves every carried stream
  cell through `dao.stream.remote.md`: the exporter enters the cell's
  handle in its table and the cell's stream descriptor becomes a remote
  descriptor. This is an access path to the original logical stream, not
  a copied stream with new positions. `dao.stream` itself assumes no
  network. The mirror serves the source's declared reader and writer
  surface, preserving its outcome maps and cursor namespace rather than
  assigning new positions. The cell carries its kept cursor. The receiver
  attaches a reflection through that descriptor and resumes at the kept
  cursor. `:yin.k/cursor-profiles` in `:yin.k/requires` names
  `:dao.stream.remote/v1`, the one profile: a cursor is plain data in the
  channel's portable domain and the source honors it after
  serialization, including cursor identity, position, and `gap` outcomes.
  If the source cannot be entered in a table, preserve the cursor, or
  keep a channel reachable, lift refuses as `:yin.k/unsatisfied` before
  minting the value. A reflection marked gone on the receiver is also
  `:yin.k/unsatisfied`, naming the stream identity. The exporter keeps
  the entry served under a lease the resumer holds
  (`dao.stream.remote.md`, section 6). Re-homing a stream is out of
  scope: serving from the exporter pins the exporter.
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
  value), where through a reflection `ok` is acceptance on the outbound
  path and the wait resumes only when the source's `ok` is observed on the
  link's event writer, while an append whose effect is unknown leaves the
  wait undischarged for the program or its composition to decide
  (`dao.stream.remote.md`, sections 2.4 and 2.5); a `:next` wait polls
  its cell. No wait is silently dropped and no
  retained value is recomputed — what parks is what resumes.

**Linker pending variants (M4).** Specified by `yin.vm.linker.md` and
implemented in M4. The require flow adds three pending variants, one per
safepoint of the same name (`yin.vm.linker.md` 7.2, 7.3). A wait entry's
`:cursor` (the kept cursor) is UCF's `:yin.k/cell`: the cell id stands for
the cursor and its kept position rides in the cells table of section
7.5.3:

```clojure
;; module link, request in hand (:module/require miss)
{:yin.k/reason    :link-request
 :yin.k/link-id   [:t0 7]                 ; [origin counter], below
 :yin.k/envelope  {:yin.link/id [:t0 7] :yin.link/name 'foo
                   :yin.link/format :yin.debruijn.code
                   :yin.link/contract "b2"}   ; rebuilt verbatim
 :yin.k/request   {:dao.stream/identity ... :dao.stream/descriptor ...}
 :yin.k/response  {:dao.stream/identity ... :dao.stream/descriptor ...}
 :yin.k/cell      :yin.k/c-31}            ; response cell, minted newest
                                          ; before the request is appended

;; module link, awaiting the correlated response (request appended ok)
{:yin.k/reason    :link-response
 :yin.k/link-id   [:t0 7]
 :yin.k/response  {:dao.stream/identity ... :dao.stream/descriptor ...}
 :yin.k/cell      :yin.k/c-31}            ; the kept cell, at its kept
                                          ; position

;; module install (a matching ok discharged; the child runs)
{:yin.k/reason    :install
 :yin.k/name      'foo}
```

Four rules fix the exchange. The link id is the pair `[origin
counter]`, scoped to the link pair, not to a VM: several tasks share
one response stream, `origin` is a task origin tag the scheduler mints
from its own counter and never reuses, and `counter` is the task's
engine gensym, so no two requests on one pair share an id. The response
cursor is minted `:dao.stream/newest` before the request is appended --
the `dao.stream.md` *Cursors* rule for observing events caused by an
operation -- so a response that lands before the cursor exists is not
skipped. `:link-request` carries everything needed to rebuild the
envelope, `:link-response` carries the response descriptor and the kept
cell; a `full` append keeps the request state, retains the envelope
verbatim, and retries on the next poll, the `:ffi-request` discipline.
And the poll restores only on exact correlation: the entry advances
past every response whose `:yin.link/id` is not its own, keeping its
cursor at the first unconsumed position -- the per-cell round-order
discipline section 7.5.3 already gives shared cursors -- and skips
duplicate, late, and unknown responses, each skip a telemetry
diagnostic that consumes nothing but the cursor position. A matching
refusal, or a failed discharge, resumes the program with the refusal
raised as the effect's error, the way an `:ffi` `error` raises; a
composition that abandons a link raises the reason the same way and
retires the id. A matching `ok` makes the entry `:install`, and no task
is restored until the install reaches `linked` or `refused`: `linked`
restores every waiter with the required module's symbol as its value,
`refused` with the refusal as the effect's error. On `gap` the honest
outcome is a `gap`, and the composition's policy decides.

**Amendment r5: the shapes left open, and the keys on the wire.**
(2026-10-04; `yin.vm.linker.dht.md` 14.3 item 2. The first two rules
hold for both body versions of 7.2.1; operation ids are version 1.)

*Explicit park is the no-wait shape.* `:park` raises no wait entry, so
it has no frame and no pending. The parked record travels in
`:yin.k/parked` under its own id as a `registers` map, a body of kind
`:parked` names the active record by `:yin.k/parked-id`, and the
resumer re-creates the record. That explicitly parked activation
waits on nothing; the task may still hold other waits, and every
frame the body carries beside the record is restored as an ordered
wait (7.2.1). `:park` is therefore not a wire reason. Neither is
`:call-effect`: an effectful
call carries the variant of the effect it was observed to produce
(`:next`, `:put`, `:ffi`, `:ffi-request`, a link variant, or
`:install`), by the ruling in `yin.vm.ucf-revisions.md` section 8. A
pending whose reason is `:park` or `:call-effect`, or any reason
outside the variants here, is `:yin.k/undecodable`. This supersedes
those two entries of the reason comment in 7.4.1.

*The published `:install` variant is not complete alone.* The variant
above names a module and nothing else. The install it waits on is a
child task on the emitter's scheduler (`yin.vm.linker.md` 7.3): a
verified image, a phase, a store, and waits of its own. None of that
is in the name. A resumer handed only the name can do one of two
things, and both are wrong: wait forever on a child nobody runs, or
request the link again, which is a second effect the parked task
never made. That is the **name-only install sketch**, and it must
never be exported as task state. The complete shape is the pending
together with the body's install entry for the same name:

```clojure
;; the waiter's pending: unchanged, never sufficient alone
{:yin.k/reason :install
 :yin.k/name   'foo}

;; the body's entry for that name (7.2.1)
:yin.k/installs
{'foo {:yin.k/phase    :running   ; the child's phase (linker 7.3)
       :yin.k/parent   [:t0 7]    ; the link id that spawned it
       :yin.k/response {...}      ; the verified link response
       :yin.k/child    {...}}}    ; the child's whole handoff body
```

- The response is what a foreign resumer spawns its own fresh child
  from, and what the child's exports are validated against at halt.
  It is carried verbatim and must be in the canonical bytes domain,
  else lift refuses `:yin.k/non-portable`, kind `:install-response`.
- A response is verified by the resumer, not trusted because it
  decodes. Its manifest names the module the entry is keyed by, its
  link id equals `:yin.k/parent`, and its image and manifest pass the
  checks the linker applied at delivery (`yin.vm.linker.md` sections
  5 and 9): the image is well formed, hashes to the address it is
  delivered under, and is the image the manifest names. A failure is
  `:yin.k/undecodable` naming the entry's response.
- A live install entry carries phase `:running` or `:parked`. Its
  child independently satisfies the handoff grammar and the
  quiescence requirements, with kind `:blocked`, `:parked`, or
  `:halted`. The phase records scheduler progress; it does not
  substitute for validation of the child's actual machine state,
  and no phase requires or forbids a kind: a `:running` phase with
  a blocked child is valid. A runnable, non-quiescent child refuses
  export. Lower preserves both fields, and the scheduler continues
  from the restored child without rerunning initialization. Other
  install phases are not exportable live-child entries, and any
  other phase value is `:yin.k/undecodable`. No stricter relation
  between phase and kind is introduced until a scheduler
  normalization invariant is specified and proved; M-next D tests
  every combination of the two phases and three kinds, explicit
  park included.
- The resumer restores the child's carried state into the fresh
  child before the scheduler may step it. The fresh child is a
  template for the receiver's coordinates only: it does not run the
  module's initialization again, and nothing replays the link
  request.
- The child is a whole body of 7.2.1 in the version of its holder,
  with its own frames, parked records, stores, cells, code, and
  nested installs. It is validated by the same grammar in the child
  role, recursively and with its root's context (7.2.1), before
  anything of the parent is restored.
- Every live child travels, waited on or not, and no child steps
  while its task is exporting (7.7.4). A child that cannot be
  exported refuses the parent's export with the child's own outcome;
  a child that cannot be lowered refuses the parent's lower. No
  parent is published or restored over a child that did not cross.
- An `:install` pending whose name has no entry refuses the lift as
  `:yin.k/non-portable`, kind `:incomplete-install`, before
  publication; a body carrying such a pending is
  `:yin.k/undecodable`. An obligation a carried child will bind on
  completion is discharged by that child, not reported missing.
- The waiter list is not carried. The waiters of a child are exactly
  the frames whose `:install` pending names it, in frame order.
- `:yin.k/phase` and `:yin.k/parent` are required under version 1.
  A version-0 entry without a phase reads as `:running`.
- The entry-for-every-pending rule and the frames-restore rule hold
  for version 0 as well. The landed version-0 reader breaches both;
  the fixes are recorded as defects in `yin.vm.linker.dht.md` 14.3.

*Operation ids ride on retained writes (version 1).* Three variants
retain a write that may already have been attempted: `:put`,
`:ffi-request`, and `:link-request`. Each gains one optional key:

```clojure
:yin.k/op-id {:yin.k/occurrence P :yin.k/seq n}   ; 7.7.8
```

- The id is present exactly when the write was first attempted
  through a fenced writer. It was assigned once, before that first
  attempt, and is carried verbatim through park, lift, transfer,
  lower, and every retry. Lift never assigns or renumbers an id, and
  lower never mints one for a carried pending.
- `P` is the occurrence whose grant the assigning holder ran under.
  A body is minted at the park that ends that run, so `P` is never
  the body's own occurrence: it is the origin's or an earlier link
  of the same chain. A body with no `:yin.k/origin` carries no id.
- Every carried `:yin.k/seq` is below the root's
  `:yin.k/next-op-seq`, and no two pendings of one task, children
  included, share an id.
- An id on any other variant, a malformed id, or a breach of the two
  rules above is `:yin.k/undecodable`.
- The retry appends the retained value through the resumer's fenced
  writer under the carried id, with the resumer's own lease and
  epoch. It is never a bare append, and the wait advances only on
  the admission outcome correlated to that id (7.9).
- A retained write without an id is a write to a stream that was
  not enrolled when it was attempted. Enrollment is read from the
  arbitration medium (7.7.8), by both ends. If the resumer finds
  that stream enrolled, or cannot fence a stream whose pending
  carries an id, the lower refuses `:yin.k/unsatisfied` naming the
  stream. Protection is neither added to an attempted write nor
  dropped from one.
- A first exclusive export of a task whose retained write targets
  an enrolled stream refuses the lift as `:yin.k/non-portable`, kind
  `:unprotected-pending`: an operation attempted without an id
  cannot be made exactly-once afterwards.

*Required keys per variant, as the handoff body carries them.* Where
an example above differs, this list is the wire contract:

- `:next`: `:yin.k/cell`.
- `:ffi`: `:yin.k/cell`, `:yin.k/call-id`; `:yin.k/op` optional. The
  response stream is the cell's own stream marker.
- `:put`: `:yin.k/stream` (a stream marker), `:yin.k/value`.
- `:ffi-request`: `:yin.k/call-id`, `:yin.k/request-envelope` (the
  request, retried verbatim), `:yin.k/request`, `:yin.k/response`,
  `:yin.k/response-cell`; `:yin.k/request-op` and
  `:yin.k/request-args` are optional restatements of the envelope.
- `:link-request` and `:link-response`: `:yin.k/link-id`,
  `:yin.k/name`, `:yin.k/request`, `:yin.k/response`, `:yin.k/cell`;
  `:link-request` also `:yin.k/envelope`.
- `:install`: `:yin.k/name`, and the install entry above.

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

The scalar arm's "number" includes every exact-integer carrier Jing
supports, not only what a host's `number?` admits: a JS or Dart `BigInt`
is a scalar like a `long`, encoded as itself and carried by Jing's major
types 0/1 and tags 2/3 (C3 converged ruling 4). No marker is added, and a
big integer inside a heap cell stays under the cell-lift refusal. Likewise
Jing's float64 carrier, how an integral float keeps its kind on JavaScript,
is a scalar number (float-address mob ruling); `yin.vm/plain-data?` and
`machine-data?` admit it for rows and machine payloads too, and rows,
images and hashing keep its bytes. Admission and round-trip preservation
of a numeric scalar do not imply that every primitive accepts it: on
JavaScript the standard arithmetic and ordering primitives meet the
carrier's coercion refusal (`dao.jing.cbor.md`), while the JVM and Dart
compute on their native double. That error is Jing's `:carrier-coercion`,
not a lift refusal (section 7.9). Only a profile with an explicit seam
computes on floats portably.

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

**Closure markers carry a binding discipline and a store (linker M4).**
Specified by `yin.vm.linker.md` and implemented in M4. The closure row
above was written for the named semantic VM alone. The linker's four
backends split into named kernels (`:yin.ast/code`, `:yin.semantic/code`)
and positional ones (`:yin.debruijn.code`, `:yin.debruijn.register`), so
the marker gains a `:yin.k/binding` key with two variants, and a closure
defined in a linked module carries `:yin.k/store-of`, the manifest address
of the module store its body resolves against (`yin.vm.linker.md` 7.3):

```clojure
;; named (:yin.ast/code, :yin.semantic/code)
{:yin.k/tag :yin.k/closure :yin.k/binding :named
 :yin.k/segment addr :yin.k/entry pc
 :yin.k/params [sym ...] :yin.k/env {sym encoded}
 :yin.k/store-of m}                       ; when module-defined

;; positional (:yin.debruijn.code, :yin.debruijn.register)
{:yin.k/tag :yin.k/closure :yin.k/binding :positional
 :yin.k/segment identity :yin.k/entry pc
 :yin.k/arity n :yin.k/frames [[encoded ...] ...]
 :yin.k/store-of m}                       ; when module-defined
```

A `:named` marker lowers only into a named kernel and a `:positional`
marker only into a positional kernel of the same `:format`; the reverse
is `:binding-mismatch`, so a lifted slice carried elsewhere fails
closed rather than being reinterpreted. `:binding-mismatch` is a
lower-side refusal (`yin.vm.linker.md` 7.3), not a `:yin.k/non-portable`
kind, so it is not in the closed set of section 7.5.4. In a `:named` marker lifted
from the AST walker, the segment names the `:lambda` row whose body
slot is the closure's body and the entry is nil; in a `:positional`
marker, the segment is the origin image's identity and the entry is
relative to it. `yin.vm.linker.md` 7.3 states each kernel's lift and
lower into these shapes, the walker's included, with its
`:unrooted-body` refusal. The store named by `:yin.k/store-of` travels
once per slice, not per closure, under the module-store amendment of
section 7.6.2.

**Resource markers decode into a private table, and references re-seal
(linker M4).** Specified by `yin.vm.linker.md` and implemented in M4. The
`:yin.k/stream` and `:yin.k/cursor-ref` rows above decode to a store key
and a store cursor entry; the linker's resource split moves both targets
out of the store (`yin.vm.linker.md` 7.3). Lowering installs an attached
handle under a fresh resource id in the receiver's private `:resources`
table, and lowers a logical cell to a cursor entry in `:resources` seeded
with its carried position, remapping every `:yin.k/cursor-ref` to that
entry -- two refs to one cell still share one entry -- and no store key is
ever created: program values keep only the opaque `stream-ref` and
`cursor-ref` ids, exactly as a running program holds them. References are
sealed, not merely shaped. Each task's resources are bound to a
task-scoped capability secret, minted once by the composition at task
creation from its own random source and held in VM state -- never in a
program value, never on a stream, never counter-derived. Every reference
the engine issues carries a seal over its id under that secret, and every
effect dispatch that resolves a program-supplied reference verifies the
seal against the active task's secret before touching `:resources`; a
literal with a wrong or missing seal fails closed as
`:forged-resource-reference`, naming the effect and the id it named. Lift
and lower re-seal rather than carry, and lift authenticates before it
encodes: a `:yin.k/stream` or `:yin.k/cursor-ref` marker is emitted only
after the reference's seal and resource kind verify against the emitter
task's own secret, and an invalid, unsealed, or forged reference refuses
the lift immediately, as `:yin.k/non-portable` with `:yin.k/kind
:forged-resource-reference`. The marker carries no seal -- it is the
portable encoding -- and the lower, having installed the attachment or
cell in the receiver's `:resources`, issues a fresh reference sealed under
the receiver's secret. One task's literals cannot guess another task's
secret, so cross-task forgery and the export-laundering path fail with the
same refusal.

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

**Cells lower into the private resource table (linker M4).** Specified by
`yin.vm.linker.md` and implemented in M4. The lowering rule above -- each
cell becomes a fresh store cursor entry -- is amended by the resource
split of `yin.vm.linker.md` 7.3: each cell becomes a cursor entry in the
receiver's private `:resources` table, seeded with its carried position,
and every `:yin.k/cursor-ref` to it remaps to that entry; two refs to one
cell still share one entry and one ref per cell keeps its own, but no
store key is ever created, and program values keep only the opaque
reference ids, exactly as a running program holds them.

A stream reference is portable when the exporter can enter its handle in a
`dao.stream.remote.md` table and publish a remote descriptor for the
original logical stream. This applies to local implementations,
including an in-memory or string-backed stream; they need no native
network transport. The exporter checks the `:dao.stream.remote/v1` cursor
profile before encoding the cell, and the receiver holds a reflection
after `attach!`. The mirror preserves the source's cursor and outcome
semantics, including `gap`; it does not turn `:oldest` or `:newest` into
a kept position. A raw host handle
still never crosses the value boundary. A stream that cannot be served or
cannot honor the required profile refuses through the existing
non-portable or unsatisfied outcomes; a missing remote attachment is
`not-found` and becomes `:yin.k/unsatisfied`.

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
| :cyclic | :in-memory-handle | :non-canonicalizable | :foreign-parked-ref
| :forged-resource-reference | :missing-module-store | :unrooted-body`
(`:non-canonicalizable` and `:foreign-parked-ref` from §7.3.2 and §7.6.3;
the last three from linker M4, `yin.vm.linker.md` 7.3). There is no partial UCF value and no
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
 :yin.k/cursor-profiles #{:dao.stream/file-v1} ; required cursor profile
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

**Resource cells live beside the store, not in it (linker M4).** Specified
by `yin.vm.linker.md` and implemented in M4. The opening list above is
amended by the linker's resource split (`yin.vm.linker.md` 7.3): stream
handles, cursor cells, and the FFI pair live in a private `:resources`
table in VM state, beside the store, never inside it. The engine's own
machinery -- wait-set resolution, handle creation and polling, resume --
reads and writes only that table, and no user store instruction and no
`resolve-var` step consults it, at top level or inside a module closure.
The ids may stay predictable: they name nothing a store instruction can
reach. A migration slice still carries resource cells, remapped at resume,
but beside the program store; the decode targets of sections 7.5.1 and
7.5.3 move with them.

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

**There is no merge at the module grain either (linker M4).** Specified by
`yin.vm.linker.md` and implemented in M4. A module install's exports cross
the child-to-parent boundary as a lifted slice, and the stores cross with
them (`yin.vm.linker.md` 7.3). One snapshot encodes per module, under its
manifest address: the installing child's halted store, and every
dependency's store the exports reach transitively, as it stands in the
child's state at halt, including the mutations the child made through the
dependency's own closures. A closure's `:yin.k/store-of` with no snapshot
refuses the lift, as `:yin.k/non-portable` with `:yin.k/kind
:missing-module-store`; each snapshot encodes as a `:yin.k/store` slice,
and whatever that grammar refuses refuses the lift and the install. On
lowering, each receiving task instantiates every snapshot into
`:module-stores {m {sym value}}` in its own state, one instance per
module: a store the task already holds stands as instantiated -- first
link wins, and a later slice never overwrites a live instance -- and
nothing is written into any task's ambient store. A receiver binding of
the same name can neither supply nor shadow the module's value; while a
closure carrying `:yin.k/store-of m` executes, the module store of `m` is
its active store and the ambient store does not participate, the routing
`yin.vm.linker.md` 7.3 states.

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

The grantor's own facts on that ledger (M-next C, slice C5), each
written in the published attribute order of `yin.vm.ucf.ledger`:

| Fact | Dispatch | Author | Keys, in order |
|---|---|---|---|
| admitted offer | `:yin.k/custody :yin.k/offered` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:yin.k/id`, `:yin.k/policy`, `:yin.k/medium`, `:yin.k/baseline` (the operation baseline the inspector derived from the variant's bytes) |
| grant | `:dao.lease/status :dao.lease/accepted` | authority | `:dao.lease/status`, `:dao.lease/lease`, `:dao.lease/proposal`, `:dao.lease/subject`, `:dao.lease/holder`, `:dao.lease/duration`, `:dao.lease/max` (optional) |
| grant binding | `:yin.k/custody :yin.k/bound` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:dao.lease/lease`, `:dao.lease/holder`, `:yin.k/epoch` (7.7.8) |

The admitted offer is not the emitter's offer: it shares the
dispatch value but is authored by the authority and carries
`:yin.k/baseline`, and it records one admitted snapshot variant. The
emitter's offer stays evidence. A grant and its binding are one
transaction.

Slices C6, C7 and C10 add these grantor facts, in the same order rule:

| Fact | Dispatch | Author | Keys, in order |
|---|---|---|---|
| epoch change | `:yin.k/custody :yin.k/reclaimed` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:dao.lease/lease`, `:yin.k/epoch` (the occurrence's epoch after the reclaim, 7.7.8) |
| refusal | `:yin.k/custody :yin.k/refused` | authority | `:yin.k/custody`, `:yin.k/proposer`, `:dao.lease/proposal` (7.7.8) |
| fenced admission | `:yin.k/custody :yin.k/fenced` | authority | `:yin.k/custody`, `:yin.k/op-id`, `:yin.k/incarnation`, `:yin.k/epoch` (the lease and epoch that admitted the op id) |
| quarantine | `:yin.k/custody :yin.k/quarantined` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:yin.k/op-id` (the op id whose intent conflicted) |
| input | `:yin.k/custody :yin.k/input` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:dao.lease/lease`, `:yin.k/input-seq`, `:yin.k/source`, `:yin.k/observed` (`yin.vm.linker.dht.md` 14.2.2) |

A lapse and its epoch change are one transaction, and so are the two
halves of a refusal (7.7.8). A fenced admission commits in one
transaction with its op id's dedup record; an intent conflict commits
only the quarantine.

Slice C8's completion facts, as recorded by the authority:

| Fact | Dispatch | Author | Keys, in order |
|---|---|---|---|
| recorded report | `:yin.k/custody :yin.k/resumed` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:dao.lease/lease`, `:yin.k/result`, `:yin.k/successor` (for a continuation only: the occurrence the authority verified) |
| closure | `:yin.k/custody :yin.k/completed` | authority | `:yin.k/custody`, `:yin.k/occurrence`, `:dao.lease/lease` (the released lease) |
| edge | `:yin.k/custody :yin.k/succeeded` | authority | `:yin.k/custody`, `:yin.k/occurrence`, then exactly one of `:yin.k/successor` (the successor edge) and `:yin.k/result` (the terminal edge to a halted result's address, 7.7.6) |

The recorded report is the holder's report after the authority
verified it (7.7.8). It names no author: its author is the lease's
holder, which the ledger already holds. It is still evidence;
completion is the closure. A closure and its edge commit in the
transaction of the lease's `:release` lapse, between the lapse and
its epoch change.

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

(M-next D8/D9, as built.) The exporting machine carries one gate key,
`:yin.k/gate`, with the modes `:running`, `:exporting` and `:ended`:
under `:running` internal computation proceeds and stream observations
and effects are deferred to the driver; under `:exporting` and `:ended`
nothing is observed, no install child is advanced, a direct resume is
refused, and every public apply refuses a late result. The root's mode
is stamped on each install child at its creation and before each run;
a child carries the mode only. A machine entering exporting refuses
the custody holds -- a wait entry with reason `:observe`, an entry or
cell carrying `:yin.k/held`, a cursor cell carrying `:yin.k/unminted`,
a machine with a nonempty `:yin.k/closes` queue, and a
`:link-request` entry without `:cursor` -- as `:yin.k/non-portable`,
kind `:reason-mismatch`, with the holding task's path; the lift
refuses the same holds on its own behalf. A task is not at a liftable
safepoint while it holds any of these.

The lift's custody input is one **header** argument -- supplied to
`holder.export/prepare [machine record serve! header]`, stored in the
export record as `:header`, and passed to `export-task` through its
`:header` option -- `nil` or:

    {:yin.k/occurrence  O
     :yin.k/arbitration {:dao.stream/identity i
                         :dao.stream/descriptor d}
     :yin.k/origin      {:yin.k/occurrence P
                         :dao.lease/lease L
                         :yin.k/emitter a}  ; absent on a first export
     :yin.k/next-op-seq n
     :yin.k/enrolled    #{stream-identity ...}}

A `nil` header lifts today's version-0 fork; a header lifts version 1,
exclusive, in the canonical codec, with `:yin.k/policy
:yin.k/exclusive` added by the lift. The occurrence is minted once by
the driver and persisted before prepare is called; `:yin.k/enrolled`
is the composition's declaration from the ledger reader; it is never
encoded into the UCF body, but the header stays in the retained export
record. The live record can contain authentic closures and is not itself
a canonical data object. `holder.export/freeze [machine prepared-record]`
encodes a separate content-addressed export-recovery object, tagged
`:yin.k/export-recovery` with format version 1, using the handoff value/cell
codec. It returns recovery bytes/address beside the exact handoff body
bytes/address; both are stored durably before the journal's fenced event.
Recovery retains the local enrolled declaration, prior gate, ordered waits,
parked continuations, issue stamps, descriptors and dependency closure.
The enrolled declaration still never travels in a published handoff body.

`holder.export/rehydrate-fenced` validates the recovery object and its
embedded body before administrative attachment. It creates authentic
fresh-receiver-owned values and preserves resource aliases and distinct
cells without program reads, cursor minting, writes, closes, initialization
or execution. Every task stays exporting, with waits outside scheduler
queues. The prior gate is retained only as data; recovery is neither grant
admission nor permission to abort back into execution. Runtime composition
functions and handles are the environment, not the canonical journal.
Incomplete preparations cannot manufacture a missing snapshot on restart.
Recovery also retains a complete codec snapshot when legacy fork bytes
omit transitive dependencies; the published version-0 bytes stay unchanged.
For version 1, the module-store fixed point is a correctness repair:
canonical ordering uses a separate scratch census and did not populate
the emitted dependency table. Bodies that previously omitted transitive
module stores now contain them and have different content addresses; this
is not a new handoff version or closure format. The transitive-store test
pins the old one-pass body and corrected body bytes by their addresses.

The driver's exit persists recovery and body, journals the fence, attempts
the stable successor offer, reports that exact successor/body, carries
release of the matching origin lease, observes the authoritative closure
and successor edge, then retries the same offer until admitted. The
recovery object is stored first and a store that refuses stops the step
before the body is attempted; a retry re-brackets from the recovery
object, idempotent through the store. An
`:awaiting-completion` offer answer does not block reporting. A halt reports
its result body and observes the terminal edge, with no successor offer.
Every external attempt has a durable intent before delivery and an
acknowledgment afterward; an unmatched intent retains unknown delivery.
A non-admitted report answer either retries -- `:suspended` resends the
identical request -- or ends the run (r3 1.6): the terminal refusals
gate the machine `:ended`, append one diagnostic and carry the cleanup
release, which closes nothing by itself. A legal abort journals
one terminal `:aborted` record before local execution is handed back,
and the journal fold treats that record as closing the export. A holder
rechecks tenure across that append -- dao.lease's own `holding?`, with
the grant's max cap and its stopped latches -- and tenure that died
under it ends the run instead of restoring the machine; the cleanup
release opens with the next step, after the terminal record.
Reopened holders do not regain tenure from persisted clock readings:
they reconcile, release and re-enter candidacy, never resume execution.

Kind and
header agree at lift: blocked and parked roots carry occurrence,
arbitration, counter and policy, and origin when present; a halted
root carries origin only, and a header-bearing halt without an origin
is refused -- it is not automatically downgraded (a halt with no
origin at all is a version-0 result under a `nil` header); install
children carry no header. Before answering `:ok`, a version-1 root
lift runs `checkpoint/inspect` on its own bytes and address, then
`handoff/validate-body` on the decoded body; install children do not
independently undergo the root custody inspection. Any refusal
returns as the lift's refusal and nothing is published. A lift under a
header refuses `:yin.k/non-portable`, kind `:op-seq-exhausted`, at the
2^52-1 counter of a blocked or parked root (a halted root ignores that
header value), kind `:unprotected-pending` for a retained `:put`,
`:ffi-request` or `:link-request` entry, in the root or an install
child, with no operation id whose target identity is enrolled, and
`:yin.k/unsatisfied` naming the stream for an operation id on an
unenrolled target.

Abort is permitted only before any possibly accepted offer attempt, or
upon authoritative evidence that this occurrence has never been
admitted and cannot still become admitted from an outstanding attempt;
a refusal of one request is not such evidence. As implemented,
`holder.export/abort` reads the composition's persisted `attempts`
sequence -- each attempt's append outcome under `:append` -- and
accepts abort only with no attempts or when every attempt's outcome is
in the closed set `#{:dao.stream/full :dao.stream/invalid-value
:dao.stream/closed :dao.stream/refused}`; `transport-error` and a
missing answer refuse. A holder exporting a successor may abort only
under current valid tenure, implemented as `{:now n :bound b :live
true}` with `:live` supplied by ledger evidence that the lease is the
occurrence's active lease at its epoch; at or past the bound, and
without the evidence, abort is refused. After the lease has ended its
old local machine is never restored.

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
(7.7.3); only the possessing boundary writes it. Exactly-once admission
is available only at an enrolled transactional consumer. Its epoch check,
operation-intent check, durable dedup record, and side-effect commitment
share one atomic boundary against the admission resource, never a remote
check followed by a local act. A stale epoch is refused at commitment.
A consumer unable to join that boundary declares at-least-once delivery
or fail-stop behavior; UCF does not infer exactly-once from a lease stamp.

**Stable operation ids, intent, and durable dedup.** Every fenced effect
carries `:yin.k/op-id {:yin.k/occurrence O :yin.k/seq n}`, assigned in
emission order. Re-grant may reuse an id after a nondeterministic input
has changed, such as a kept cursor returning `gap` after eviction. The
same id therefore does not prove the same operation. An enrolled consumer
compares the canonical intent (effect kind, target identity, and payload)
with its durable `{op-id -> {intent result}}` record in the atomic
admission transition. An equal id and intent returns the recorded result
without a second commit; an equal id with different intent refuses and
commits nothing. A crash re-grant must replay durably recorded inputs or
fail-stop when a divergent intent cannot be reconciled. Without that
input discipline and transactional enrollment, the composition declares
at-least-once behavior rather than exactly-once effects.

**Where the stamp rides.** Program values are program data; UCF never
edits them. A resumed incarnation running under a lease emits through a
**fenced writer** the composition wires: a writer that wraps each appended
value in an envelope `{:yin.k/envelope ... :yin.k/incarnation <lease>
:yin.k/op-id ... :yin.k/value <the program value, verbatim>}`. An enrolled
consumer derives intent from the actual effect kind, destination identity,
and payload at admission. A bare stream is *declared* unprotected, and a
composition that needs exactly-once delivery through it has wired the
wrong thing. Fencing is opt-in per stream; the resource enforces it.
No callback tells a lapsed holder to stop; it stops at
its own lease bound (`dao.lease.md`, *The holder*), and what it did past
the bound is distinguishable after the fact by epoch and op-id.
Section 7.7.8 publishes the envelope's grammar, which adds the epoch to
the keys named here, and the sequence state behind the operation id.

**Durable completion of a checkpoint.** Publication of a successor is not
completion. The holder's exit sequence is: append the successor value to
the carrier, append `:yin.k/resumed` (evidence) to the arbitration medium,
append `:dao.lease/released`. Completion is the **grantor's ledger
transition** -- observing the release (or the `:resumed` evidence followed
by release) and closing the occurrence's tenure. A crash after successor
publication but before the ledger transition leaves an orphan successor,
not a grant. The lease lapses and the grantor may re-grant the last
recorded occurrence. For enrolled consumers, durable input replay and
atomic intent comparison prevent a divergent same-id effect from
committing. Without those gates, the re-grant is at-least-once or
fail-stop, not harmless exactly-once recovery. The orphan remains
queryable history (7.7.6); a holder report is evidence, while the grantor
ledger is authority.

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

(Amendment r6.) The rollback above is not an automatic transition. A
closed occurrence never regains tenure and receives no further grant
(7.7.8). Re-offering an earlier link, and compensating for effects
past it, is a governance act outside automatic successor admission.

(M-next C, slice C8.) A halted result completes its occurrence as a
continuation does, but the closure's edge is a terminal edge to the
result's address (7.7.2). A result is not an occurrence: it is never
offered, admitted or granted, no ancestry runs through it, and the
chain ends there.

### 7.7.7 Restart

A grantor that lost its ledger reclaims and re-grants (`dao.lease.md`,
*Restart*: reclaim first, then grant afresh — a new grant alone does not
end the prior tenure). Its inventory of possessed resources is the set of
occurrence ledgers it transacts; its recoverable holder for each is the
`:dao.lease/holder` of the last grant, both of which are facts on (or the
recoverable state of) the space it possesses. Both are queries, which is
why custody lives on a `dao.space` and not on the carrier stream.

(M-next C, slice C6.) An authority that reopens its durable ledger
runs these steps in order, and serves nothing before the last:

1. Open the ledger and fold it.
2. Reclaim every tenure it shows live, cause `:policy`, in grant
   order, each lapse with its epoch change as one transaction
   (7.7.8). Nothing is regranted.
3. Rebuild the judge from the ledger after those reclaims: its seen
   facts from every recorded grant and lapse (a lapse of cause
   `:release` is the release; no separate release is recorded), its
   answered proposals from the recorded grants and refusals.
4. Wire the proposal media from their oldest anchor, so a proposal
   drained before its occurrence could be granted is judged again.

A reclaim that does not commit refuses the whole open; reopen is
retried, and replays the ledger to the same state. The reclaim is
the adapter contract of `dao.lease.md`, *Composition duties*.
Because reopen reclaims, a holder's admission retry after a crash
answers `:stale` (7.7.8 step 3); its committed result is recovered
from the outcome projection (7.9). An authority opened without
reclaim replays the recorded result.

(M-next C, slice C12.) Enrollment carries no request identity. After
an uncertain answer the composition rereads the projection and
enrolls only when the derived target identity is absent; a blind
retry enrolls a second target.

### 7.7.8 Fenced custody, version 1: sequence, envelope, epoch binding

(Amendment r5, 2026-10-04; `yin.vm.linker.dht.md` 14.3 item 2.) This
section publishes the grammar that 7.7.5 argues for. The reasons stay
in 7.7.5; the runtime, ledger, and step contract stay in
`yin.vm.linker.dht.md` 14.2. Nothing here adds a key to the lease
vocabulary or to a DaoStream outcome map. Every rule applies to
version-1 bodies only (7.2.1).

**Exact integers.** `:yin.k/epoch`, `:yin.k/seq` and
`:yin.k/next-op-seq` are nonnegative exact integers no greater than
2^52-1 (4503599627370495), the bound within which every host compares
and increments exactly (`dao.lease.md`, *Keys*, uses the same one).
The kind is the canonical codec's integer kind: a float is not an
integer even when it is integral. A value outside the range or of
another kind makes its carrier defective: a body is
`:yin.k/undecodable`, an envelope or a binding establishes nothing.
No counter wraps, saturates silently, or restarts.

**Operation sequence state.** A task under a grant holds one counter,
`:yin.k/next-op-seq`, on its root. Install children draw from it.

1. *Assign.* Before the first attempt of a write to an enrolled
   stream, the fenced writer takes `n`, the counter's value, forms
   the id `{:yin.k/occurrence P :yin.k/seq n}` with `P` the
   occurrence its grant names, stores the id on the wait entry, and
   sets the counter to `n+1`. The three happen as one step over the
   machine value, before any append.
2. *Retain.* The id stays on the entry while the write is unfinished:
   a `full` path, an append of unknown effect, an acceptance with no
   admission outcome yet, or a `:suspended` outcome. A retry reuses
   the id and never takes another.
3. *Carry.* At a park the id travels in the pending (7.4.3) and the
   counter in the header (7.2.1). The successor's counter is the
   holder's counter at that park, so the sequence continues along
   the chain and never restarts at a new occurrence.
4. *Restore.* Lower sets the fresh task's counter to the carried
   value exactly. There is no local value to take a maximum with:
   the receiving task is new (contrast `:yin.k/id-counter`, 7.6.3).
5. *Regrant.* A holder granted the same checkpoint again starts from
   the same bytes, so from the same counter and the same carried
   ids. Re-executed writes take the same sequence numbers in the
   same order as long as its inputs replay; where they do not, the
   intent comparison below catches the divergence.
6. *Exhaust.* The largest assignable sequence is 2^52-2. A counter
   equal to 2^52-1 is exhausted: the writer assigns nothing and
   appends nothing, the write stays an undischarged wait, and a lift
   of that task refuses `:yin.k/non-portable`, kind
   `:op-seq-exhausted`. Recovery is outside this protocol.

**The fenced envelope.** A fenced writer wraps each value it appends
toward an enrolled consumer:

```clojure
{:yin.k/envelope    :yin.k/fenced-v1   ; dispatch key; closed
 :yin.k/incarnation L                  ; the writer's :dao.lease/lease
 :yin.k/epoch       e                  ; from L's grant binding
 :yin.k/op-id       {:yin.k/occurrence P :yin.k/seq n}
 :yin.k/value       v}                 ; the program value, verbatim
```

- All five keys are required. `:yin.k/op-id` has exactly its two
  keys. A consumer ignores other qualified keys, and they are never
  part of intent. An envelope that fails these rules, or dispatches
  on a value other than `:yin.k/fenced-v1`, is defective (below).
- **Inside program data: only `:yin.k/value`.** It is the value the
  program wrote, unchanged and uninterpreted. A program value that
  looks like an envelope is payload: it is wrapped like any other
  and never read as one.
- **Outside program data: everything else.** The envelope exists
  between the fenced writer and the enrolled boundary and nowhere
  else. It is never in a frame, an environment, a store, or a body;
  a pending carries the retained value and the id beside it, and
  the writer rebuilds the envelope at each retry from the id and
  its current binding. What the consumer's commit makes visible
  downstream is the consumer's contract; UCF requires only that the
  payload it commits is `:yin.k/value`.
- The envelope names no target, no effect kind, and no intent. The
  consumer takes the target identity and the effect kind from the
  boundary the envelope arrived at, and computes intent itself.
- The envelope carries no credential. The holder is authenticated
  by the attribution the composition supplies (`dao.lease.md`,
  *Composition duties*). An envelope whose attributed author is not
  the holder that the binding of `L` names is a fact about that
  author: it is defective.

**Defective envelopes.** A defective envelope commits no effect and
creates no dedup record. It yields no admission outcome: an outcome
would be attributed to the holder its fields claim, and those fields
are exactly what cannot be trusted. The consumer instead appends one
structured diagnostic to the diagnostic stream the composition
supplies:

```clojure
{:yin.k/diagnostic :yin.k/defective-envelope
 :yin.k/defect     :malformed       ; closed, below
 :yin.k/target     i                ; the boundary's stream identity
 :yin.k/author     a                ; resolved attribution, if any
 :yin.k/claimed    {...}}           ; readable envelope fields
```

- The closed defect set is `:malformed` for invalid envelope
  structure, unsupported envelope dispatch or version, invalid
  numeric fields, or intent that cannot be canonically encoded;
  `:unbound-lease` when no valid binding establishes the claimed
  incarnation; `:wrong-author` when attribution is absent, invalid,
  or does not identify the bound holder; and `:foreign-op-id` when
  authoritative scope validation fails.
- (M-next C, slice C7.) An op id whose `:yin.k/seq` is 2^52-1, a
  value no writer can assign, is `:malformed`.
- Enrollment belongs to the target boundary, while a writer's
  authorization comes from attribution and binding. There is no
  "unenrolled writer" defect: an unenrolled boundary cannot run
  this admission protocol or claim its guarantee, so it produces
  neither outcomes nor these diagnostics.
- Readers dispatch on the `:yin.k/diagnostic` key, never on the
  presence of an id. The diagnostic adds nothing to a lease fact or
  to a DaoStream outcome map.
- The composition supplies the diagnostic stream explicitly.
  Failure or backpressure while publishing a diagnostic never
  permits the rejected effect or creates an admission outcome;
  diagnostic publication failure remains an explicit driver
  outcome.
- `:yin.k/claimed` holds whichever of `:yin.k/incarnation`,
  `:yin.k/epoch` and `:yin.k/op-id` could be read, as claims. They
  are nested so that no reader correlating on a top-level op id can
  take the diagnostic for an answer. The payload is never echoed.
- The map carries neither `:yin.k/admission` nor `:yin.k/status`. A
  driver discharges nothing and ends nothing on a diagnostic.

**Intent.** Intent is the vector `[effect-kind target-identity
payload]`: the keyword naming the boundary's operation, the target's
`:dao.stream/identity`, and the envelope's `:yin.k/value`. This
amendment enrolls one effect kind, `:yin.k/append`, a stream append;
every write the reference machine retains is one. Two intents are
equal when their canonical bytes are equal. FFI and link correlation
ids are inside the payload and are compared with it; they are not
operation ids.

**The grant epoch binding.** The grantor publishes one more custody
fact (7.7.2), authored by itself:

```clojure
{:yin.k/custody    :yin.k/bound
 :yin.k/occurrence O
 :dao.lease/lease  L
 :dao.lease/holder H
 :yin.k/epoch      e}
```

- The lease fact is unchanged. The binding is a separate fact, so a
  lease judge, which switches on the lease keys, ignores it.
- Unlike the offer and the resumed report, the binding is authority,
  because the grantor authors it. It is valid only when its
  attributed author is the transactor of the space the body's
  `:yin.k/arbitration` names; when it was admitted in the same
  transaction as a `:dao.lease/accepted` fact with the same lease,
  the same holder, and the subject `{:yin.k/occurrence O}`; and when
  it is the only binding for `L`. Any other binding establishes
  nothing, and a grant on an occurrence with no valid binding
  confers no fenced tenure.
- (M-next C, slice C5.) *The only binding for `L`* means the only one
  in the named authority's transaction history. A reader holding a
  partial view can refute it, by finding a second binding, but never
  establish it. In this composition the reader's records are the
  authority's complete-retention journal read from its
  `:dao.stream/oldest` anchor, so the check is completeness-backed,
  and the authority's fold, which refuses a second binding, enforces
  it. The author of records read from a stream whose
  `:dao.stream/identity` equals the body's arbitration identity is
  that identity. Descriptors are transport-relative and are never
  compared.
- A reader needs authenticated evidence that the grant and the
  binding share one transaction of that authority: a transaction
  identity within the named authority's provenance domain,
  attributed to it. Equal `t` values read off arbitrary media prove
  nothing. M-next C supplies the evidence and shows that a remote
  reflection preserves it; a medium that cannot supply it cannot
  offer `:yin.k/exclusive`.
- **How a reader learns the epoch.** Only from a valid binding. A
  holder reads it beside its grant before it activates, and stamps
  it into its envelopes. A consumer reads the authority's current
  state inside its admission transition. An epoch a holder
  advertises, in an envelope or anywhere else, is a claim to be
  checked, never a source.
- **The counter.** Each occurrence has one epoch in its ledger
  (7.7.3). It is 0 when the occurrence is first admitted. Every
  reclaim of a lease on the occurrence, whatever its cause, raises
  it by exactly one in the transaction that records the
  `:dao.lease/lapsed` fact, before any new grant. A grant binds the
  epoch current at that grant, so the first grant binds 0 and the
  grant after `k` reclaims binds `k`. A successor is a new
  occurrence and starts at 0.
- (M-next C, slice C6.) **The epoch change** is the grantor's fact

  ```clojure
  {:yin.k/custody    :yin.k/reclaimed
   :yin.k/occurrence O
   :dao.lease/lease  L
   :yin.k/epoch      e}             ; the epoch after the reclaim
  ```

  committed in the transaction that records `L`'s
  `:dao.lease/lapsed` fact. Every lapse has exactly one, and the
  ledger refuses a lapse without it or it without its lapse. At the
  bound it is still written, carrying the unchanged epoch.
- (M-next C, slice C6.) **A refusal** is a pair committed in one
  transaction: the plain `:dao.lease/rejected` lease fact, verbatim
  `dao.lease` (it names no proposer), and the grantor's

  ```clojure
  {:yin.k/custody      :yin.k/refused
   :yin.k/proposer     P
   :dao.lease/proposal pid}
  ```

  A lease judge switches on the lease keys and ignores the refused
  fact; a reader learns which proposal a refusal answered, keyed
  `[proposer proposal-id]`, from the refused fact alone. The ledger
  refuses either half
  without the other. A proposal id is answered at most once, so a
  candidate refused, or answered `:yin.k/not-holder` (7.8), proposes
  again with a fresh proposal id.
- **Restart.** The epoch never decreases, is never reset, and is
  never reused. A grantor that restarts recovers the epoch from its
  durable ledger and reclaims, which raises it, before it grants
  (7.7.7). A grantor that cannot recover the epoch grants nothing
  for that occurrence: 7.7.7's reclaim-and-regrant presumes the
  epoch survived. A new authority never starts an old occurrence at
  0. Recovery from lost epoch or dedup state is governance, outside
  this protocol (7.7.5). (M-next C, slice C6.) Reopen runs 7.7.7's
  steps, rebuilding the judge after the reclaims: a reclaim that does
  not commit refuses the open, and reopen is retried.
- **Exhaustion.** Epoch 2^52-1 is usable until a reclaim would
  increment it: a grant bound at that value is valid, and its
  holder's effects are admitted like any other's. That reclaim ends
  the tenure, leaves the epoch unchanged, and permanently exhausts
  the occurrence. Thereafter admission returns `:suspended` before
  tenure checking, and no effect commits. Exhaustion is derived
  from the ledger: a lapse recorded for the lease bound at 2^52-1.
  An exhausted tenure cannot produce an eligible exclusive
  successor: the authority accepts no completion and grants nothing
  for the occurrence. Carrier bytes may remain as history, but a
  publication cannot reopen custody. (M-next C, slices C6 and C8.)
  In the ledger, that lapse's epoch change carries the lease's own
  binding epoch, which only a lease bound at 2^52-1 can do; that is
  the exhaustion. A report recorded at the bound is evidence: its
  release exhausts the occurrence without completing it.
- **Why a binding.** A remote holder cannot infer authoritative
  tenure from what it observes: `:dao.lease/lapsed` does not cross
  to it (`dao.lease.md`, *Carriage*), and its view of the ledger is
  partial. The binding publishes the grant's authoritative epoch.

**Admission.** An enrolled consumer admits each well-formed envelope
by one atomic transition against the authority's admission resource
(7.7.5). The checks run in this order and the first to fail decides
the outcome (7.9):

1. *Authority.* The authority the consumer is enrolled with must be
   readable. If it is unreachable, if its ledger for the occurrence
   that `L` is bound to is not recoverable, or if that occurrence
   is exhausted, the outcome is `:suspended`. An occurrence
   quarantined after an intent conflict (below) answers the same.
2. *Binding.* `L` has a valid binding, and the envelope's attributed
   author is the holder it names. Otherwise the envelope is
   defective: a diagnostic, and no outcome.
3. *Tenure.* The bound occurrence is open, `L` is its active lease,
   and `e` equals its current epoch, else `:stale`. Staleness is
   decided before the dedup record is consulted: a stale envelope is
   refused even when a record for its id exists.
4. *Scope.* An operation assigned during the current occurrence
   names that occurrence. An inherited operation must occur among
   the retained pending operations of the authoritative checkpoint
   granted to the holder, including its install children, and its
   occurrence must be an ancestor through authoritative completion
   records. Membership is derived from the accepted checkpoint, the
   body the authority admitted for the granted occurrence; no
   ancestry index and no pending-id index is stored. An id that
   fails is defective, `:foreign-op-id`. For an inherited id, if
   the authoritative checkpoint cannot be read and verified,
   admission answers `:suspended` without changing tenure,
   quarantine, or dedup state. Unavailable evidence is not evidence
   of an invalid id, and this suspension comes after the tenure
   check, so a stale holder is still answered `:stale`. The
   checkpoint is selected by the authority's accepted record, never
   by an envelope-supplied location. Current-occurrence ids require
   no inherited-membership lookup. Keeping the accepted checkpoint
   readable is a durability duty of the composition. (M-next C,
   slice C9.) Ancestry is checked first, from the completion records
   alone, before the checkpoint is read. A granted occurrence's
   ancestors never change, so a non-ancestor is `:foreign-op-id`
   permanently; only membership waits on a store that can be
   repaired. Any offered variant whose bytes verify to the recorded
   baseline is the checkpoint, because the variants share one
   baseline. A store that is absent, closed or throws is unreadable.
5. *Dedup.* With a record for the id: equal intent answers
   `:replayed` with the recorded result and commits nothing;
   different intent answers `:intent-conflict` and commits nothing.
   With no record: the effect, its result, and the record
   `{op-id -> {intent result}}` commit together, and the outcome is
   `:committed`.

**Results and uncertainty.** The recorded result is the effect's
definitive outcome, as plain data; for a stream append it is the
target's DaoStream outcome map, unchanged. A terminal refusal is a
result, recorded and replayed unchanged, but only when the atomic
admission boundary itself establishes it. An outcome that leaves the
effect unknown, such as a `:dao.stream/transport-error` from a
transport whose failures are not declared clean, is not a result.
Unknown transport acceptance establishes neither commitment nor
absence of commitment. The writer retains the id and retries through
the fenced admission boundary. The authority may already hold a
committed result, which the retry must replay. An external effect
whose uncertainty cannot be reconciled within that atomic boundary
is outside this guarantee. Backpressure, by contrast, is no
commitment: it is the writer path's own `:dao.stream/full`, and
nothing was appended.

**Snapshot variants.** Snapshot variants of one occurrence must
preserve its operation baseline: the root next-operation sequence
and the mapping of retained operation ids to canonical intents,
including child operations. Equal id sets alone are not enough: a
variant could keep an id and substitute its payload. The authority
refuses a conflicting variant; an unavailable comparison suspends
variant admission. (M-next C, slice C5.) The authority admits an
offer in this order: inspect the bytes; under the authority lock,
decide purely; store the body only when the decision will commit;
commit. A refused, replayed or uninspectable offer stores nothing. A
store that fails answers `:suspended` and commits nothing; a body
stored before a commit that then fails is a harmless orphan.
(M-next C, slice C9.) These mechanics realize the suspension above:
the comparison's basis, the recorded baseline, is always in the open
authority's projection, so the only unavailable comparison is that
failing store.

**One dedup namespace.** The dedup records form one logical namespace
per arbitration admission resource, across every enrolled target that
takes part in the guarantee, keyed by the operation id alone. Target
identity is part of the intent, never of the key. So a replay that
diverges and sends an id to another target meets the first target's
record and answers `:intent-conflict`. A consumer that cannot join
that atomic resource keeps no private table in its place: it cannot
claim the cross-target guarantee, and its stream is not enrolled.

**Enrollment.** That a target stream is enrolled is an attributed
fact of the authority on the arbitration medium, keyed by the target
stream's identity. No body carries it. M-next C defines its schema
and ensures that a change of enrollment cannot alter the protection
of an operation already retained under an id.

**Completion and the successor chain.** Completion (7.7.5) establishes
a single acyclic chain. The authority accepts at most one successor
per predecessor and one predecessor per successor. The successor's
occurrence is fresh, its `:yin.k/origin` names the predecessor and
the lease that ran it, and its `:yin.k/arbitration` identity is the
predecessor's, unchanged. Only that accepted completion is an edge:
a published successor or a resumed report the authority did not
accept is an orphan, and no ancestry runs through it. A closed
occurrence receives no further grant.

(M-next C, slice C8.) The first offer of a body with an origin is
refused `:awaiting-completion` while the origin's lease is its
occurrence's live lease. Otherwise it is refused `:orphan` unless it
is the successor the predecessor's closure recorded, under the
closing lease, at the reported address. An origin naming an
occurrence this ledger never saw is `:orphan`: a successor's
arbitration is its predecessor's, so no predecessor lives on another
authority. A body with no origin is a first export and is
unconstrained. A halted result completes through a terminal edge
(7.7.6).

**Quarantine.** An intent conflict that the authority itself
established, in an authenticated admission, quarantines the
occurrence: it stays open, is never regranted automatically, and its
admissions answer `:suspended`. Recovery or compensation is a
governance decision outside this protocol. An unauthenticated claim
of a conflict quarantines nothing. (M-next C, slice C8.) A
quarantined occurrence cannot complete: a report is refused, and a
release records only the lapse and its epoch change.

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

Every UCF lift or lower operation returns one map dispatching on
`:yin.k/status`; the set is closed and every non-`ok` outcome carries
the data needed to act on it. Admission outcomes (below) are the second
family, disjoint from this one.

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

**Amendment r5: version-1 refusals use the statuses above.** The
amendment adds no `:yin.k/status` value; the set stays closed.

- An unsupported, absent, or non-integer body version is
  `:yin.k/profile-mismatch`, carrying `:yin.k/version` as found and
  `:yin.k/supported`; it is raised before restoration and before
  every other check but the bytes and the tag (7.2.1).
- A malformed custody header, operation id, or install entry is
  `:yin.k/undecodable` with its path (7.2.1, 7.4.3).
- Lift refuses `:yin.k/non-portable` with kind `:incomplete-install`,
  `:unprotected-pending`, or `:op-seq-exhausted` (7.4.3, 7.7.8).
- A stream whose protection the resumer cannot match is
  `:yin.k/unsatisfied` naming the stream (7.4.3).
- A grant observed without a valid epoch binding is
  `:yin.k/not-holder`, carrying the lease state observed; the
  resumer releases the lease, as after any failure past custody.

**Amendment r5: admission outcomes.** The outcome of an enrolled
consumer's admission (7.7.8) is data that the consumer appends to the
composition stream the holder's driver reads. It is one map
dispatching on a second key, `:yin.k/admission`. It is not a
`:yin.k/status`, not a `:yin.k/kind`, and not a link refusal: it
reports what a consumer did with one effect, where a status reports
what a lift or a lower did with a value. The two families are
disjoint and their keys never appear in one map. A DaoStream map
inside `:yin.k/effect-result` is a recorded historical result, so
the rule against nesting a stream outcome, which governs direct lift
and lower failures, does not reach it. The set is closed, with
exactly these five values:

| `:yin.k/admission` | Meaning | Also carries |
|---|---|---|
| `:committed` | committed now, once | `:yin.k/effect-result` |
| `:replayed` | committed before; nothing new | `:yin.k/effect-result` |
| `:stale` | tenure is not current | `:yin.k/observed-epoch` |
| `:intent-conflict` | same id, other intent | both intents |
| `:suspended` | authority cannot decide | `:yin.k/arbitration` |

- Every outcome carries `:yin.k/op-id`, the id of the envelope it
  answers, and echoes that envelope's `:yin.k/incarnation`. A driver
  correlates on the pair, so an outcome answering another holder's
  retry of the same id is not mistaken for its own.
- Correlation alone is insufficient. An outcome counts only when it
  is authenticated: attributed, by the composition's resolver, to
  the enrolled consumer or admission authority for that target. An
  outcome from any other author is a fact about that author; the
  driver discharges nothing and ends nothing on it.
- `:yin.k/effect-result` is the recorded result of 7.7.8, the same
  data on the first answer and on every replay. A DaoStream outcome
  inside it is the map the target produced, with no key added,
  removed, or renamed.
- `:stale` carries `:yin.k/observed-epoch`, the occurrence's current
  epoch; `:yin.k/observed-lease`, its active lease, when one exists;
  and `:yin.k/closed true` when the occurrence is closed.
- `:intent-conflict` carries `:yin.k/recorded-intent` and
  `:yin.k/observed-intent`, each an intent vector of 7.7.8. On
  M-next C's ledger, `:yin.k/recorded-intent` and
  `:yin.k/observed-intent` are the canonical intent digests -- the
  BLAKE3 of the canonical bytes of `[:yin.k/append target value]`
  -- because the durable dedup record stores the digest and a
  closed-target record drops the payload. Digest equality is
  canonical-byte equality; the conflict decision is unchanged.
- `:suspended` carries `:yin.k/arbitration`, naming the authority
  that could not be read or could not decide, as a body names it
  (7.2.1). It also answers for an exhausted or quarantined
  occurrence, where retry cannot succeed without governance. On
  M-next C's authority, `:suspended`'s `:yin.k/arbitration` is
  `{:dao.stream/identity arb}`, the authority's journal identity.
- A defective envelope has no admission outcome; its structured
  diagnostic is in 7.7.8.
- Only `:committed` and `:replayed` discharge a wait, and the
  effect's resume value comes from their recorded result as 7.4.1
  states for the variant. `:suspended` leaves the wait undischarged
  and the id retained; the driver retries the same envelope.
  `:stale` and `:intent-conflict` leave the wait undischarged and
  end the run: the driver emits nothing further through its fenced
  writers and publishes no successor. After an intent conflict the
  occurrence is quarantined (7.7.8).
- A duplicate outcome for a wait already discharged is skipped, as
  a duplicate link response is (7.4.3). Outcomes are compared as
  data on every host, never as text.
- (M-next C, slice C7.) **The outcome projection.** The authority
  serves the `:committed` outcome of every fenced admission its
  ledger records on one reader-only stream whose identity is
  `"<arb>/outcomes"`, arb its journal identity: dense positions in
  ledger order, stable across reopen, blocked at the tail, never
  ended. Its records are the authority's because they are derived
  from its ledger. A driver holding a kept cursor recovers a
  committed result after a crash without a reply; the other
  outcomes are direct replies, recovered by retrying.
- (M-next C, slice C11.) A holder counts a record as
  authority-authored only when its composition attributes the
  stream it was read from to the arbitration identity: the reply
  stream the composition serves under that identity, and the
  outcome projection at the identity `"<arb>/outcomes"`.
  Attribution is identity equality; a descriptor is never compared.
  A front reply's `:yin.k/answer` is the landed answer unchanged
  (`yin.vm.linker.dht.md` 14.2.4).

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
  through the aliasing scenarios of `check-wait-set`. This depends on
  `dao.stream.remote.md` and its **portable cursor profile**
  `:dao.stream.remote/v1`, which preserves the source stream's kept cursor
  and `gap` outcomes. The M4 string-backed stream test below is the
  required cross-host proof.
- **Enforceable fencing.** Epoch-checked, atomic-with-commitment admission
  and durable op-id plus intent dedup at enrolled consumers are design
  items for `dao.space.transactor.md`. A consumer without a shared atomic
  boundary declares at-least-once or fail-stop behavior; it cannot claim
  exactly-once custody from the lease alone.
- **Canonical byte encoding.** `:yin.code/hash` and `:yin.k/id` are exactly
  as portable as `dao.jing/segment-key`. Canonical CBOR landed in
  3ddaa21b (recorded in `yin.vm.ucf-revisions.md` section 5); the
  cross-host address checks below still must pass.
- **Contract revision publication.** `:yin.code/contract "v3"` needs a
  published revision history naming the complete execution contract
  (§7.3.3): the tuple grammar — mnemonic set, per-mnemonic arity and operand
  kinds, saturation/defaults table — the opcode table and transitions, the
  resolution and last-value-wins rules, the effect outcome map, and the
  scheduler semantics. The index is in `yin.vm.semantic.md` 2.4 and the
  history landed in dbae125b (`yin.vm.ucf-revisions.md`); runtime profile
  checks below remain open.
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

### 7.11.1 Blocker-closure acceptance matrix

These are observable test contracts, not a declaration that the blockers
are closed. M3 and M4 tests run on JVM, CLJS (Node), and CLJD. A named
refusal must be a data outcome before a code image is loaded, a machine is
restored, or an effect is committed, as applicable. The M4 lift driver
must consume the UCF table amendments in linker section 11 item 12 before
its round-trip tests run. Full UCF acceptance waits for every row,
including the post-M5 gates; passing a linker milestone alone is not a
claim that all five blockers have closed.

**Code identity (7.3; blocker 5).**

- Invariant: A resumer executes the instruction stream named by the
  continuation's address and contract stamp, or refuses before loading it.
- Setup: On each host, make equal resolved vectors from batches with
  different entity ids, order, provenance, and omitted defaults; make
  unequal vectors by reversing repeated single-valued operands. Include
  a `:gensym` prefix of `false` beside absent and explicit `"id"` prefixes.
  Verify the published `v2` decision in both direct and projected loads.
  Include an index hit, carried code, a `dao.jing` miss/hit path, a valid
  vector under a wrong index entry, a correctly addressed malformed
  vector, a missing address, an old stamp, and two valid images claiming
  one live local id.
- Action and expected outcome: M3 `step`/`fetch` transfers and verifies
  each format over ring buffers; the two equal vectors address equally,
  the unequal vectors do not, and a `:sha256` address still verifies under
  its named algorithm. M3 also passes linker section 9's full refusal
  matrix, staged-response, invalid-request, and traffic-equivalence
  tests without a function or handle on the link stream. M4 lower selects
  by stamp then address, verifies hash and grammar, and gives B0-equal
  execution on all three hosts.
  Wrong index content is linker `:hash-mismatch` at M3 or
  `:yin.k/hash-mismatch` at UCF lower; malformed code is
  `:yin.k/undecodable` naming the pc; a miss is `:yin.k/unsatisfied`;
  an incompatible stamp is `:yin.k/profile-mismatch`. No check may
  redirect an existing local id or bypass the common validator. The
  falsy-prefix case either normalizes identically in both paths or is
  refused as inadmissible before an address is minted.
- Landing and order: M3 lands the stream and format-identity gate;
  M4 closes UCF identity after M3 and the published normalization
  decision, before any lifted image is run.
  The already landed `v2` history (dbae125b), canonical CBOR
  (3ddaa21b), and Phase 1 vector/stamp (f51077f2) supply the names and
  bytes, not the cross-host lowering proof. Landed linker design
  (1f7990d5, sections 5 and 9) specifies M2's four format records and
  refusal tests; the records must pass those tests before M3 starts.

**Safepoint reconstruction (7.4; blocker 1).**

- Invariant: At every declared parking transition, lifting and lowering
  preserve the next observable step and its wait, while other pcs refuse.
- Setup: Run the corpus to every 7.4.1 row: explicit park, blocked
  stream read, blocked write, sent FFI, retained FFI, ordinary and tail
  effectful calls, and halt; also exercise M4's `:link-request`,
  `:link-response`, and `:install` pending variants (linker section 11
  item 12). Exercise distinct activation depths and captured environments
  at one pc, a nonempty ready queue, a nonsafepoint pc, and a kept response
  cursor shared by two waiters. Omit the portable cursor profile for the
  refusal case. Separately prepare two independently implemented UCF
  engines with a nontrivial call stack, a captured closure, and a blocked
  effect at the handoff point.
- Action and expected outcome: On each host, lift a parked task and lower
  into a fresh VM, then compare its result and effect trace with the
  reference machine. Before the full corpus, lift that handoff from one
  engine and lower it in the other; the frame, pending wait, next result,
  and effect trace must agree in both directions. Static
  `:yin.safepoint/kinds`, stack effect, and lexical reads agree with the
  reference trace. The observed parked record, wait entry, and effect
  outcome determine `:yin.k/reason` and `:yin.k/pending`, never the
  static kind alone.
  Test both FFI states and each call effect. `lift(lower(frame))` must
  reproduce the canonical frame. An undeclared pc gives
  `:yin.k/not-at-safepoint`; queued work gives `:yin.k/not-quiescent`.
  Insufficient pending evidence refuses before publication, as does a
  missing portable cursor profile. Halt yields a result, not a frame.
- Reflection acceptance (`dao.stream.remote.implementation-plan.md`, slice 8): Park a
  continuation holding a string-backed local `dao.stream` with a kept
  cursor, then migrate to another network node. The exporter
  mechanically serves that stream by entering it in its table; the cell
  descriptor is a remote descriptor and the cell carries the kept
  cursor, and the receiver holds a reflection on the original logical
  stream. Reads resume at the kept position. Force retention loss and
  verify the reflection returns `:dao.stream/gap` with the source's
  successor cursor, without replay or silent skip. Repeat with a source
  that cannot honor the profile: lift refuses `:yin.k/unsatisfied`; a
  reflection marked gone on lower is `:yin.k/unsatisfied` naming the
  stream. The exporter's entry and any relay pair the route depends on
  are lease-governed, and a resumer that arrives after reclaim observes
  `not-found`.
- Landing and order: M4 covers the early two-engine gate, the table,
  reference-machine parity, and the cross-host reflection proof after code
  identity; the kept-cursor proof no longer waits for post-M5 hardening.
  M4 must land the mirror step and reflection of `dao.stream.remote.md`,
  not just reuse a native network stream.
  Before export, specify the no-wait explicit-park representation and any
  `:call-effect` pending shape; test them or remove that reason from the
  wire contract. The `:reasons` Option B ruling in
  `yin.vm.ucf-revisions.md` section 8 is landed design, not a runtime
  fix: M4 replaces it with static `:kinds` and tests dynamic reasons.
  A foreign engine claiming conformance runs the same frame parity harness.

**Recursive portable encoding (7.5; blocker 2).**

- Invariant: Every reachable portable value returns with its meaning and
  aliasing intact, and every unsupported or malformed value refuses as a
  whole with a precise path before execution.
- Setup: Park frames with nested literal maps that look like UCF tags,
  map keys containing values, closures, reified and parked continuations,
  profiled primitives, shared subvalues, stream refs, and two cursor refs
  that share a cell beside two independent cells at the same position.
  Add a host object, unnamed or ambiguous function, host-state primitive,
  cyclic value, forged resource ref, missing table ref, cyclic ref data,
  extra table entry, and a correctly hashed malformed marker.
- Action and expected outcome: Lift, transfer, and lower on each host;
  literals remain literals, repeated refs share one value, independent
  cursor cells advance independently, and shared cells advance in wait
  order. The lift refuses unsupported leaves as `:yin.k/non-portable`
  with path and kind and publishes no partial value; decode refuses
  malformed markers and refs as `:yin.k/undecodable` with path before
  restoring state. Forged resource refs refuse before lift, while valid
  refs re-seal on lower into the receiver's private resource table. A
  same-named primitive with a different profile is `:yin.k/unsatisfied`,
  never substituted by name alone.
- Landing and order: M4 supplies the recursive codec, published standard
  primitive profiles (including effectful `yin/def` and `require`), and
  linker section 11 item 12's `:yin.k/binding`, `:yin.k/store-of`,
  private-resource, and sealed-reference variants before lift uses them.
  M4 also proves the string-backed stream reflection and kept-cursor case in
  the safepoint row before cross-host values are accepted. Phase 1
  canonicalization and M2 format records do not encode frames or values.

**Ownership arbitration (7.7; blocker 4).**

- Invariant: One occurrence has at most one admitted holder, and each
  enrolled protected effect commits at most once across re-grants.
- Setup: Copy one checkpoint, including two valid encodings with one
  occurrence id, to two readers and leave the source's blocked writer
  wakeable. Use a transactable arbitration space, a grantor ledger,
  enrolled transactional consumer, and durable op-id intent and result
  records. Inject publication failure, retry, stale lease, consumer
  partition, and crashes after the successor append, resumed report, and
  release. After a crash, evict a kept-cursor value so the re-granted run
  observes `gap` and attempts a different effect at the same sequence.
- Action and expected outcome: M4's exporting transition detaches waits
  before publication; polling or direct resume of the exporting source
  cannot append, and retry preserves the occurrence. With arbitration,
  only a granted holder runs; competitors get `:yin.k/not-holder` or
  `:yin.k/awaiting-grant`. Reclaim advances the epoch; stale effects fail
  at commitment. At the enrolled consumer, epoch check, operation-intent
  check, dedup record, and side-effect commitment share one atomic
  boundary: equal id and intent return the stored result without a second
  commit; equal id with divergent intent refuses and commits nothing.
  A re-grant either replays durable inputs or fails closed on divergence.
  The grantor's ledger alone completes the occurrence. A partitioned
  protected consumer suspends admission. Without enrollment, the stream
  declares at-least-once or fail-stop behavior; without a durable
  transactable authority the composition offers `:yin.k/fork` only.
- Landing and order: M4 must land and test exporting before any lift
  publishes. Exclusive custody, epoch admission, crash recovery, and the
  `yin.repl.core` handoff composition close in post-M5 hardening, after
  code, state, and pending waits can round-trip. Until then an unfenced
  stream declares at-least-once delivery, not exclusive custody.

**Dependency closure (7.6; blocker 3).**

- Invariant: A resumer runs only when every reachable dependency is
  declared and satisfied, using an isolated store that cannot inherit a
  receiver task's bindings.
- Setup: Make code that reaches names through `:store-get` and
  `:store-put`, a dynamically called closure, two activations of one
  address with different captured environments, a transitive module
  closure, and a parked-id reference. Give the receiver a conflicting
  store binding and a different FFI pair. Omit in turn a segment, module
  footprint, primitive profile, stream attachment, and parked record.
- Action and expected outcome: Lift computes the fixed point over
  address-context pairs and the reachable store slice; lowering checks
  every requirement before restoring. The resumed task reads the carried
  binding, uses the receiver's pair only for future calls, routes pending
  calls to their carried endpoints, and shares no task-local module store.
  A missing segment reports `:blocked`; an undeclared footprint reports
  `:incomplete`; missing requirements yield `:yin.k/unsatisfied` with
  the computed discovery state and missing set. A missing active-value
  parked ref refuses lift as `:yin.k/non-portable` with
  `:yin.k/kind :foreign-parked-ref`. No partial lower occurs.
- Landing and order: M4 must test linker obligations, manifest store
  footprints, transitive child-install slices, and isolated lowering
  after encoding and code identity; linker section 11 criteria 15 and 23
  are the minimum module cases. The full UCF fixed point, scheduler
  parked records and fresh-name state, and all missing-dependency modes
  close in post-M5 hardening. M2 format records verify link payloads;
  they do not prove the task's transitive runtime closure.

**Fenced custody, version 1 (7.2.1, 7.4.3, 7.7.8, 7.9; amendment r5).**

This block adds obligations; it changes no row above. The safepoint
and portable-encoding rows keep their M4 kept-cursor and reflection
gate with its meaning and its evidence as written. The stage-1
handoff tests that landed with M-next A (80b59233,
`test/yin/vm/ucf/handoff_test.cljc`) are recorded by
`yin.vm.linker.dht.md` 14.1.3 and by `yin.vm.ucf-revisions.md` I-5;
they are not moved here and nothing below relies on them as custody
evidence. The ownership row keeps its own contract; the rows below
are the wire-level clauses that row's tests must also exercise.

- Invariant: A version-1 body, envelope, binding, and admission
  outcome mean the same on JVM, Node, and Dart, and every breach of
  their grammar is a data outcome before a machine is restored or an
  effect is committed.
- What M-next B claims: the grammar is published. It lands no code,
  passes no test, and closes no row. `handoff-version` is still 0.
- How each clause is tested on three hosts: by canonical byte
  fixtures decoded on each host, so a rule about integer kind or key
  presence is checked on the bytes and not on a host number type;
  and by the ordered host pairs of `yin.vm.linker.dht.md` 14.1.1
  where a value crosses. Outcomes are compared as data.

Clauses, with the stage that owes the evidence
(`yin.vm.linker.dht.md` 14.3):

1. Version gate (7.2.1). Fixtures: version 2, absent, a float `1.0`,
   a version-0 child in a version-1 root. Assert
   `:yin.k/profile-mismatch` with the found and supported versions,
   or `:yin.k/undecodable` for the mixed tree, with zero attach
   calls, zero proposals, and no machine: recursive validation has
   no side effect. The integral float is refused on Node as on the
   others. A version-0-only reader refuses a version-1 body. A
   version-0 body still lowers as a fork on a reader that speaks
   both; a composition requiring exclusive refuses it. Stage: D.
2. Custody header (7.2.1). Fixtures omit each required key, give a
   fork policy, a nil occurrence, an origin equal to the body's
   occurrence, and header keys other than the origin on a halted
   root. Root and embedded child are distinguished: any header key
   on a child, a halted child with an origin included, is refused,
   and a halted child without one validates. Assert
   `:yin.k/undecodable` with the path. Stage: D.
3. Sequence state (7.7.8). Assign-before-append, one increment,
   retention across `full`, unknown effect, and `:suspended`; the
   counter restored exactly; children drawing from the root.
   Stage: D for assignment and restoration; C for durable input
   replay; E for crash and regrant through the composition.
4. Carried ids (7.4.3, 7.7.8). An id crosses park, bytes, and lower
   unchanged on `:put`, `:ffi-request`, and `:link-request`, and the
   retry is fenced. Fixtures: an id on `:next`, a sequence at or
   above the counter, a duplicate id, an id in a body without an
   origin, an id naming the body's own occurrence. Assert
   `:yin.k/undecodable`. Protection mismatch in each direction is
   `:yin.k/unsatisfied` naming the stream; a first exclusive export
   over an attempted write is `:unprotected-pending`. Stage: D for
   these structural checks. Stage: C for authoritative membership
   and ancestry: an ancestor id the granted checkpoint never
   carried is refused, a carried one is admitted, a child's carried
   id counts, and an orphan successor gives no ancestry.
5. Explicit park and install completeness (7.4.3), both versions.
   A `:park` or `:call-effect` reason and an `:install` pending
   without its entry refuse at decode; a `:parked` body's frames
   restore in order; a response that fails verification and a phase
   outside `:running` and `:parked` refuse; each of the two phases
   crosses with each child kind `:blocked`, `:parked` and `:halted`,
   a `:running` phase over a blocked child and an explicitly parked
   child included, and lower preserves both fields; a runnable
   child refuses export; a restored child continues from its saved
   state without rerunning initialization or replaying the link
   request. Version 1 adds: phase and parent required, and a
   child's carried ids checked in the root's context. The two
   version-0 defects of `yin.vm.linker.dht.md` 14.3 are fixed with
   version-0 tests. Stage: D.
6. Envelope (7.7.8). Fixtures drop each key, change the dispatch
   value, give a wrong author, an unbound lease, and a foreign id,
   and nest an envelope-shaped program value. Assert each defective
   form commits nothing, records nothing, and yields the structured
   diagnostic with its defect and provenance and no admission
   outcome; absent attribution is `:wrong-author` and an intent
   that cannot be canonically encoded is `:malformed`; a failed or
   backpressured diagnostic append admits nothing and is itself a
   driver outcome; the nested value arrives as payload; no envelope key
   appears in any body. Stage: C for the consumer, D for the writer.
7. Epoch binding (7.7.8). First grant binds 0; each reclaim raises
   the epoch by one in the lapse's transaction; a successor starts
   at 0. A binding by another author, without authenticated
   common-transaction evidence, duplicated for one lease, or with a
   float epoch establishes nothing. Reopen the ledger and assert
   the epoch is recovered and raised, never reset. Stage: C for the
   authority facts and their atomic provenance, including through a
   remote reflection; D for the holder, which answers
   `:yin.k/not-holder` and releases on an invalid binding.
8. Admission order and outcomes (7.7.8, 7.9). One fixture per check
   in order: unreadable authority, wrong author, stale epoch, stale
   lease, closed occurrence, a foreign id, an inherited id from a
   closed ancestor, equal intent, different intent, fresh commit.
   Add a cross-target conflict: one id sent to a second enrolled
   target answers `:intent-conflict` from the shared namespace and
   quarantines the occurrence. Add forged outcomes: a `:committed`
   and an `:intent-conflict` from an unattributed author discharge
   nothing and quarantine nothing. Add an unknown-effect transport
   error, cut both before and after the remote commit: the id is
   retained, the retry goes through the fenced boundary, and it
   commits once or replays the result already held. Add an
   unreadable accepted checkpoint: an inherited id answers
   `:suspended` with tenure, quarantine, and dedup state unchanged,
   and a stale holder still answers `:stale`. Add a snapshot
   variant that keeps an id and changes its intent or the root
   counter: the authority refuses it. Assert the exact
   outcome map of 7.9 for each and that stale wins over an existing
   record. Stage: C for the consumer, D for the driver.
9. Bounds (7.7.8). A counter at 2^52-1 assigns nothing and refuses
   export. An epoch at 2^52-1 is tested twice: while valid, its
   grant activates and its effects commit; after the reclaim that
   would increment it, the lapse is recorded, nothing is granted,
   no successor is eligible, and admission answers `:suspended`
   before tenure. The values 2^52 and -1 are refused on the bytes.
   Stage: C for the epoch, D for the sequence.
10. Composition (linker 14.2.4). The crash, partition, reclaim, and
    successor-completion contracts run through the wired handoff
    composition on both host matrices, with crash cuts around
    completion and around result delivery. Stage: E.

Canonical fixtures prove grammar parity. They do not prove
atomicity, attribution, or recovery; those need the durable
transactional seam of `yin.vm.linker.dht.md` 14.2.4.

(M-next C, slice C12.) The stage-C gate proves the substrate: every
authority transition under every journal crash cut reopens to its
pre-state or its post-state with only whole transactions and a
converging retry, and one ledger's frames are byte-identical across
hosts. It does not close clause 10: crash, partition, and
successor-completion through the wired composition, with real kills,
remain stage E.

Not claimed by this amendment, and still owed by the ownership row:
exclusive custody itself, atomic admission, crash recovery, input
replay, and the `yin.repl.core` handoff composition. Governance
recovery, after quarantine, exhaustion, or lost authority state,
stays outside the automatic protocol.
