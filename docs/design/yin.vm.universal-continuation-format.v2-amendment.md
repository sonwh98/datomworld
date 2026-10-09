# UCF version 2: four execution profiles

Status: normative architectural amendment, D10b-A; implementation is
D10b-B, not claimed here. Author: gpt-6-astra. Source baseline: 9cea60dd.
Date: 2026-10-07.

This is an append-only amendment to
[yin.vm.universal-continuation-format.md](yin.vm.universal-continuation-format.md)
(UCF), especially 7.2.1, 7.3, 7.4, 7.5, 7.6, 7.7.4 and 7.11.1.
It implements the D10b grammar ruling of 2026-10-06. It is authoritative
for body version 2 where it differs from the parent document. The parent
remains authoritative for versions 0 and 1. A statement about an existing
function below fixes its behavior at the named source baseline, not at an
arbitrary future revision of that function.

## 1. Scope and compatibility

Version 2 transports the native suspended state of semantic, de Bruijn
stack, de Bruijn register and AST-walker tasks. It does not translate a
continuation between kernels. Every profile works on JVM, JavaScript/Node
and Dart. Cross-host portability is required within a profile; equality
of bytes between different profiles is neither required nor expected.

Versions 0 and 1 retain their grammar, reader rules and bytes. Their
export selection must not silently switch to version 2. The already
approved D14 repair that includes omitted transitive module stores in
version-1 exports is part of the baseline: its corrected addresses stay
corrected. This amendment neither reverses that repair nor permits any
additional legacy-byte change. Existing checkpoint and ledger pins stay
unchanged.

There is no change to any instruction, AST-row, scalar-class, liveness,
module-manifest, DaoStream-outcome or lease vocabulary. In particular,
register code remains r2. No boundary opcode, operand, live-slot index,
register allocation or opcode arity is added. The new contract versions
continuation data and its interpretation, not executable code.

The four profiles are the following exact closed combinations:

| `:yin.k/engine` | `:yin.code/contract` | inner `:yin.k/version` | Kernel |
| --- | --- | --- | --- |
| `:semantic` | `"v3"` | `1` | `yin.vm.semantic` |
| `:stack` | `"b2"` | `1` | `yin.vm.debruijn.stack` |
| `:register` | `"r2"` | `1` | `yin.vm.debruijn.register` |
| `:walker` | `"v3"` | `1` | `yin.vm.ast-walker` |

The entire profile map, with exactly these three keys, travels in
`:yin.k/contract`. The body itself has `:yin.k/version 2`. The old
`ucf/contract-stamp` is not changed; a separate version-2 profile registry
owns these combinations. Engine identifiers above are literal unqualified
keywords. They are not the kernel's runtime `:format` keyword.

A receiver declares supported profiles through composition. An unsupported
combination, including a missing/malformed profile declaration, is
`:yin.k/profile-mismatch`. No inference from image shape, receiver type,
code contents or custody policy may replace the declaration. One task
and its install tree use one identical profile. A supported but different
profile on a child is a structural `:yin.k/undecodable` error after the
whole-tree profile gate. Mixed-kernel installs require a future contract;
all four homogeneous trees are required now.

## 2. Notation and closed structural grammar

`V` means the encoded guest-value grammar in section 6, not an arbitrary
host value. `N` means an exact CBOR integer in `[0, 2^52-1]`, never a
float64 carrier which happens to have an integral value. This bound
applies to counters, PCs, offsets, lengths, register indices and layout
length sums. Smaller kernel-specific limits also apply. Signed guest
integers and guest numeric values are not bounded by this notation.

`A` is a code address in the enclosing profile's native address domain
(section 4). `B` is a Jing segment address of canonical body bytes.
`M` is a module-store manifest address. `P` is a parked identifier in the
existing engine identifier domain. `C` is a task-local cell identifier.
`S` is a symbol. A vector is ordered. A map is unordered and has unique
keys by canonical-byte equality. `?` in a table means a key may be absent;
it does not mean a present nil is accepted. Empty maps/vectors are emitted
explicitly for required fields. All structural maps in this amendment
are closed: unlisted keys refuse. Optional absence has one spelling.
Guest literal maps remain open data because they are wrapped as values.

All named integer/version checks distinguish wire scalar kinds before
host numeric equality. Canonical bytes are Jing's canonical CBOR bytes;
retain float64 and other numeric carrier classes while validating hashes.
Only the established execution bridge converts scalars for execution.
Do not recompute a code hash after coercing `1.0` to `1` on JavaScript.

An environment `E` has this exact shape:

```clojure
{:yin.k/bindings {S V ...}
 :yin.k/store-of M}                 ; optional; omit outside a module
```

Lower reconstructs the native named environment, adding
`engine/store-of-key` only when the optional field exists. Positional
lexical frames `F` are a vector of vectors of `V`, outermost first, as
held by the stack/register kernels. No binding is filled from a receiver
store. A store association makes that module store a census root.

The body is:

```clojure
{:yin.k/handoff true
 :yin.k/version 2
 :yin.k/contract {:yin.k/engine engine
                  :yin.code/contract code-contract
                  :yin.k/version 1}
 :yin.k/kind kind                   ; :blocked | :parked | :halted
 :yin.k/id-counter N
 :yin.k/store {V V ...}
 :yin.k/module-stores {M {V V ...} ...}
 :yin.k/parked {P R ...}             ; R is profile-specific registers
 :yin.k/cells {C {:yin.k/stream stream-marker
                  :yin.k/position portable-position} ...}
 :yin.k/requires {:yin.k/cursor-profiles #{cursor-profile ...}
                   :yin.k/segments #{A ...}}
 :yin.k/code {A code-payload ...}
 :yin.k/installs {module-name I ...}
 ;; stack/register only, required even for a halt:
 :yin.k/layout [A ...]
 :yin.k/free-env E
 ;; kind-specific keys:
 :yin.k/frames [{:yin.k/registers R :yin.k/pending pending} ...]
 :yin.k/parked-id P
 :yin.k/result V
 ;; root custody arm, when present, as section 3 specifies
 }
```

`R` is never dispatched from its shape: it is interpreted under the
body's profile. Semantic/walker bodies forbid layout and free-env.
Stack/register free-env is the kernel's task-wide free environment,
separate from its positional lexical frames. It is included in value,
name-resolution and store-dependency discovery.

A blocked body has a nonempty frames vector and neither parked-id nor
result. A parked body has frames (possibly empty), a parked-id naming
its active parked record, and no result. A halted body has result and
no frames or parked-id. Parked and install maps contain exactly the
reachable records/children, not arbitrary inactive scheduler state.
Every kind carries all required common fields. A task with an empty
ready queue may have multiple ordered waits; never collapse them to one.

`I` is exactly the existing complete install entry with all four keys:
`:yin.k/phase`, `:yin.k/parent`, `:yin.k/response`, `:yin.k/child`.
The phase is `:running` or `:parked`; the response is the validated
portable module response and the child is a full version-2 body. Parent
is the existing parent identifier, including nil where the UCF install
contract permits it. Phase and child kind are independent: each of the
two phases admits each of blocked, parked and halted, provided the child
independently
passes the full quiescence and body checks. Preserve both fields; do not
introduce a stricter phase/kind pairing. This is the landed UCF 7.4.3
rule, including an explicitly parked child. Every install pending names
an entry here. Response manifest, image format, contract and child
profile must agree. No child initialization runs during lower.

Pending is the closed union already specified by UCF 7.4.3:
`:next`, `:put`, `:ffi`, `:ffi-request`, `:link-request`, `:link-response`,
`:install`. Their field shapes, stream/cell references, envelopes,
correlation IDs and operation-ID rules are unchanged. The complete
`:install` form, not the old name-only sketch, is required. Version 2
adds no observation, cursor-mint or close pending arm. Encoding and
validation must dispatch a pending before walking its V-valued fields;
a stream descriptor or a code row is not a guest literal map.

## 3. Custody arms, roles and admission

Fork/exclusive is derived from presence of the custody arm, not a new
mode flag and not body version alone. Let H be the set of keys
`{:yin.k/policy :yin.k/occurrence :yin.k/arbitration :yin.k/origin
:yin.k/next-op-seq}`. No top-level epoch or enrolled set is permitted.

| Structural role | Legal intersection with H | Meaning |
| --- | --- | --- |
| Root blocked/parked, fork | empty | fork; no custody admission |
| Root blocked/parked, exclusive first | policy, occurrence, arbitration, next-op-seq | new custody subject |
| Root blocked/parked, exclusive successor | previous four plus origin | successor custody subject |
| Root halted, fork | empty | ordinary result |
| Root halted, exclusive result | origin only | result of the origin lease; no new subject |
| Install child, any kind | empty | inherits its root's custody role |

Every other intersection is `:yin.k/undecodable`. Thus origin alone
identifies an exclusive halted result; it is not a partial runnable
header. Empty header maps do not travel. The policy, when required, is
exactly `:yin.k/exclusive`. Occurrences are the existing canonical UUID
strings. Arbitration is exactly the existing identity/descriptor pair.
Origin is exactly predecessor occurrence, lease and emitter; no new keys.
The successor differs from its predecessor. First next-op-seq is zero;
successor next-op-seq is the exact carried counter. Exhaustion, inherited
operation scope and baseline equality remain UCF 7.7.8 rules.

A fork tree forbids carried custody operation IDs. An exclusive tree
checks every retained operation, including descendants, against the
root's origin and baseline. Children have no independently granted epoch,
lease, counter or enrollment. Code profile and custody are independent:
all four profiles admit both root modes.

For an exclusive runnable root, lower requires the D10 inputs:
checkpoint address, protection declaration, authenticated binding and
replay-prefix evidence, lease/holder, arbitration transaction identity,
exact epoch, and current live tenure. Enforce all seven landed grant
checks and both directions of enrollment/protection agreement before
attachment. Missing grant yields awaiting-grant and no runnable VM;
contradictory binding/tenure yields not-holder; unavailable evidence or
protection yields unsatisfied, as in D10. The driver still proves the
address is an admitted variant and rechecks tenure before execution/IO.

After admission the root has the existing custody map and running gate;
children have the gate only. An exclusive halt restores ended, without
custody or a grant. Fork lower is an explicit composition choice: an
exclusive-only receiver refuses a fork with profile-mismatch, even if it
supports that execution profile. Administrative export-recovery lower is
separate and always remains exporting (section 10).

## 4. Code identity and image layout

> **Amendment 2026-10-10: A is the only code identity.** Owner ruling,
> verbatim: *"yes, A is the only code identity"*. The table below records
> the address functions version 2 bodies carry; version-2 bytes are
> unchanged and describe the **historical** contract, not a continuing
> authorization for A-less executable admission under the new contract
> (no byte rewriting and no compatibility shim). For every later body
> version, `:yin.k/code` and `:yin.k/requires :yin.k/segments` are keyed
> by the semantic A for all profiles; H and R are not keys. A stack or
> register body of a later version names its code by A plus its profile;
> the receiver derives the native image from A under the profile's
> lowering contract, or verifies held native bytes against that
> lowering, with H and R serving as integrity checks of native
> components. The "native address domains" of §2 collapse to A at that
> version, and §4.1's ordered layouts name A: offsets are derived from
> the native lowering, never carried as identities. The exact row
> grammar for the stack, register and walker profiles stays deferred
> (`yin.vm.ucf-transport-tuples.md`, body version 3, semantic only).

These are the address functions, with no UCF wrapper hash substituted:

| Profile | `:yin.k/code` value | Key computation |
| --- | --- | --- |
| semantic | canonical instruction vector | `ucf/code-address`, i.e. `jing/segment-key vector` |
| stack | unrelocated admitted instruction vector | `dcode/image-hash vector` |
| register | unrelocated `{:bodies [...] :instructions [...]}` | `rcode/register-hash image` |
| walker | `[id tag & slots]` | `id = jing/segment-key [tag & slots]`, map key equals id |

Stack H and register R are their existing hash strings, not new
`:segment/...` wrappers. H hashes the existing descriptor hash followed
by `encode-image`; R hashes its descriptor followed by
`encode-register-image`. Preserve the current string/hex framing exactly.
A profile declaration is not prefixed to these inputs: their existing
descriptors/code contracts already determine their domains. Body addresses
remain Jing hashes of whole canonical body bytes, distinct from A.

Code data slots use their existing raw code-scalar grammar, not V. A
map literal in an instruction or AST row is code data; never dispatch it
as a continuation/resource marker. Conversely, authentic runtime values
inside saved frames use V and cannot be hidden in a code constant to
escape ownership or dependency checks.

Validate each image with its own published validator. A semantic image
uses `code/well-formed-vector?`; stack uses `dcode/image-defect`; register
uses `rcode/register-image-defect`. The empty stack vector and empty
register image `{:bodies [] :instructions []}` are admitted only as the
empty base code space, as their loaders already allow. Do not validate a
concatenation as if it were a newly compiled single image.

Walker rows use the complete v3 table in
[code-as-tuples](yin.vm.code-as-tuples.md), section 2, implemented by
`vm/semantic-bytecode-grammar`. Check row arity, slot kinds, Rule R,
identity and all node/nodes references, not just the root hash. Row maps
are flattened addressed storage, never serialized map ASTs. Every child
reference must resolve locally; reference cycles are rejected. A module's
root is its image identity; it is not a hash of the rows map. There may
be multiple roots contributed by closures and continuation frames.

For semantic/walker, requires.segments equals the keys of code and code
is precisely the dependency closure. For stack/register it includes all
images of the current layout and any independently needed module image;
all such images must be addressed. Do not prune the middle of a layout
because its instructions appear dead: that changes absolute PCs.

### 4.1 Ordered layouts

A stack/register task carries its current `:yin.k/layout` as the exact
ordered vector of distinct image identities. It is never sorted. It is
nonempty; an empty base is represented by the appropriate empty-image
hash at index zero. Empty images are forbidden elsewhere. Duplicate
identities are forbidden (attach-image does not append them twice).

Derive row offsets by prefix sums of instruction counts, starting at zero.
Derive the native table `[identity offset length]` from that vector. Stack
relocates every operand whose opcode-table kind is `:pc`. Register does
the same and shifts each body's inclusive start/end by the offset. No
other operand is relocated. Concatenate in layout order. Sum overflow is
a deterministic refusal. Rebuild the runtime aggregate hash from this
concatenation; it is not an independent code-map entry or a second
persistent identity. Retain the register kernel's empty-base convention
of nil aggregate hash until a nonempty image is attached.

A captured register payload carries its own layout L (section 5). It must
be a nonempty prefix of the task's current layout, including an empty
base if present. This captures the native historical code space without
changing PCs. Equal layouts have equal reconstructions. A non-prefix
layout is not silently rebased or interpreted in receiver order. Every
image in L is carried once in the code map. Return frames use the enclosing
payload's coordinates and carry no separate code space.

Lift obtains component images by slicing the source offset table and
unrelocating exactly the :pc operands and register body ranges. Rehash
each reconstructed image and compare its identity with the table; then
reconstruct and compare the aggregate. Refuse a missing table, changed
prefix, inconsistent historical snapshot or unaddressed image rather
than guessing. A receiver attaches/rebuilds these images without running
any of them, into an isolated kernel code space. Receiver-local offsets,
code, stores and module bindings cannot affect the reconstruction.

`row-at(L, pc)` uses half-open row intervals; if pc is the final end of
L, it names L's last row. At an internal boundary it names the following
row. This is the landed stack/register rule, including the one-past-end
case. A payload's image field must equal this result in its OWN L, not
the later task layout. This matters for captures before another attach.

## 5. Registers and return frames

The following maps enumerate every wire key. Native `:format`, aggregate
`:hash`, `:segment` image contents and offset tables are reconstructed
from the profile and layout, never trusted as opaque host payloads.
Optional store-of always means an M whose store is carried.

### 5.1 Semantic

```clojure
{:yin.k/segment A :yin.k/pc N :yin.k/env E
 :yin.k/stack [V ...] :yin.k/k [Ksem ...]}
```

Ksem is exactly:

```clojure
{:yin.k/type :return :yin.k/segment A :yin.k/pc N
 :yin.k/env E :yin.k/stack-base N}
```

Lower maps these to the native `:type :return`, segment alias, pc, env and
stack-base. K is bottom-to-top. The return PC is immediately after a
non-tail call in its segment; stack-base is at most the enclosing stack
length, and bases are nondecreasing from outermost to innermost frame.
Code and environment references contribute to the same census as the
active registers. Resume PC and return PC must be inside their segment.
The nested E spelling is version-2-only; legacy environment maps stay
unchanged.

### 5.2 Stack

```clojure
{:yin.k/layout [A ...] :yin.k/image A :yin.k/pc N
 :yin.k/frames [[V ...] ...] :yin.k/stack [V ...]
 :yin.k/continuation [Kstack ...]
 :yin.k/store-of M}                 ; optional
```

Kstack is exactly:

```clojure
{:yin.k/return-pc N :yin.k/frames [[V ...] ...]
 :yin.k/stack-base N :yin.k/store-of M} ; store-of optional
```

The continuation vector is bottom-to-top. The return PC is immediately
after a non-tail `:call` in the enclosing captured layout. Stack bases
are nondecreasing and at most the saved operand stack length. Validate
PC bounds and lexical accesses using the existing b2 instruction and
closure contracts. Lower restores through `stack-restore`, delivering
one result by appending it to the saved operand stack exactly once.
It reconstructs `:segment`, `:hash`, `:format :yin.debruijn.code`, and
`:image` on native entries. The restore itself must not replace the
current task code space with the captured historical prefix.

### 5.3 Register

```clojure
{:yin.k/layout [A ...] :yin.k/image A
 :yin.k/site-pc N :yin.k/pc N
 :yin.k/frames [[V ...] ...]
 :yin.k/regs [[N V] ...] :yin.k/live [N ...]
 :yin.k/continuation [Kreg ...]
 :yin.k/dest N-or-nil
 :yin.k/resume-mode mode            ; :write-result | :return-result
 :yin.k/store-of M}                 ; optional
```

Kreg is exactly:

```clojure
{:yin.k/site-pc N :yin.k/return-pc N
 :yin.k/frames [[V ...] ...]
 :yin.k/regs [[N V] ...] :yin.k/live [N ...]
 :yin.k/dest N :yin.k/store-of M}    ; store-of optional
```

The live vector is strictly ascending and distinct; its indices exactly
match the first component of regs in the same order. They equal the
site instruction's in-band live operand and fit its body's register
count. No dense live-register substitute is accepted. PC = site-pc + 1
and both are in the same inclusive body range. Destination equals the
instruction's result register, is in bounds and is absent from live.
A tail `:call` has dest nil and resume-mode return-result; every other
boundary has write-result and an integer dest. Kreg sites are non-tail
calls and return-pc = site-pc + 1. Preserve their captured store-of.

Reconstruct a native payload and apply all r2 `continuation-defect`
checks, including individual image validation and return-frame checks.
Do not use wait-entry-defect to reject valid post-delivery or linker
states merely because its original FFI-only wait union predates them;
the UCF pending union supplies those transport checks.

The existing live tuple indices are normative:

| Opcode | Live index (zero-based tuple index) |
| --- | --- |
| `:call` | 5 |
| `:stream-put` | 4 |
| `:stream-next` | 3 |
| `:ffi-call` | 4 |
| `:current-continuation` | 2 |
| `:park` | 2 |

Lower rebuilds the dense register vector with nil in nonlive slots,
then uses the existing write-result/return-result restore path. It must
not deliver the result twice or turn a tail call into a normal call.

### 5.4 Walker

```clojure
{:yin.k/env E :yin.k/k Kw}
```

Kw is nil or one of the closed maps below. Every non-nil map has required
`:yin.k/type` and `:yin.k/next Kw`. `:yin.k/env E` is optional on a frame:
when the source frame has it, encode it; when absent, preserve absence
and the native fallback to the active environment. Do not capture a
receiver environment. The following table lists every additional field;
there are no other allowed fields. `node` is an addressed AST row, not V.

| Type | Additional fields | Constraints |
| --- | --- | --- |
| `:eval-operator` | `:yin.k/node A` | application, not a Rule-R definition |
| `:eval-operand` | `:yin.k/node A`, `:yin.k/function V`, `:yin.k/evaluated [V ...]` | application; evaluated count is less than operand count |
| `:eval-test` | `:yin.k/node A` | if row |
| `:dao.stream.apply/eval-operand` | `:yin.k/node A`, `:yin.k/evaluated [V ...]` | dao.stream.apply/call row; evaluated count less than operand count |
| `:dao.stream.apply/request-sent` | `:yin.k/parked-id P`, `:yin.k/op keyword` | retained FFI wrapper; section 7.3 |
| `:dao.stream.apply/eval-call` | optional `:yin.k/call-id P` | correlated response wrapper; section 7.3 |
| `:eval-stream-put-target` | `:yin.k/node A` | stream/put row |
| `:eval-stream-put-val` | `:yin.k/node A`, `:yin.k/stream-ref V` | stream/put row; V decodes to an authentic stream reference |
| `:eval-stream-cursor-source` | `:yin.k/node A` | stream/cursor row |
| `:eval-stream-next-cursor` | `:yin.k/node A` | stream/next row |
| `:eval-stream-close-source` | `:yin.k/node A` | stream/close row |
| `:eval-define` | `:yin.k/name` | the existing legal literal definition/store key |
| `:eval-resume-val` | `:yin.k/parked-id P` | a referenced reachable parked record |

For eval-operand, remove only runtime fields `:operator-evaluated?`,
`:fn` and `:evaluated` from the native `:frame` to recover the original
application AST; encode its row and put fn/evaluated into the listed
V fields. The source's operator-evaluated flag must be true. On lower,
rebuild the AST from the row, then add that flag, decoded fn and evaluated
vector. Absent native evaluated normalizes to the empty vector.

The native FFI operand frame contains op, operands and evaluated but no
AST type. Its static portion reconstructs the exact
`{:type :dao.stream.apply/call :op op :operands operands}` AST; project it
through the v3 row codec. Lower rebuilds the native three-field frame.
The source provenance need not be guessed from an equal call elsewhere:
row content, not AST object identity, determines its address.

Other node-bearing arms lower the addressed node as their native `:frame`.
Extra AST metadata never travels. Source-position metadata is not state.
Eval-define and eval-resume-val encode their native scalar fields directly;
these frames legitimately do not retain an original AST node. Lower may
not invent one. All dynamic fields, including saved environments and
partially evaluated operands, are census roots.

A Kw chain is finite; literal guest values in it may contain further
encoded continuations, but structural host cycles are non-portable.
Unknown frame types, missing operands or opaque map-AST fallback refuse.
Restore calls the native walker return transition with control nil,
decoded env/k and the delivered value, without evaluating any AST node
until the scheduler runs after admission. No artificial PC is introduced.

## 6. Values, closures and dependency census

Use the disjoint UCF 7.5 value grammar. In particular, every guest map
is a `:yin.k/literal` marker with encoded key/value entries. Structural
register maps, AST rows and pending maps are not guest literal maps.
Scalars/collections retain the canonical Jing carrier class. Resource
references are authenticated at lift and reminted under the receiver's
owner at lower. No source capability secret travels.

Closure markers use the existing kernel module seams, now validated
under the enclosing profile:

| Profile | Marker fields beyond `:yin.k/tag :yin.k/closure` |
| --- | --- |
| semantic | binding `:named`, format `:yin.semantic/code`, segment A, entry local PC, params, env bindings, optional store-of |
| stack | binding `:positional`, format `:yin.debruijn.code`, segment H, entry image-relative PC, arity, frames F, optional store-of |
| register | binding `:positional`, format `:yin.debruijn.register`, segment R, entry image-relative PC, arity, frames F, optional store-of |
| walker | binding `:named`, format `:yin.ast/code`, segment lambda-row A, entry nil, params, env bindings, optional store-of |

All field names above carry the existing `:yin.k/` namespace. Named
closure env is the existing `{S V}` bindings map, not E; its store-of is
the sibling marker key. Positional entry points are image-relative even
though live register PCs are absolute. Params/arity must agree with the
addressed lambda/closure instruction, not merely with the wire claim.
Use the kernel's lower-closure validation; never relabel another
profile's closure. Every frame value is recursively encoded.

Version 2 makes the parent document's reified/parked frame-value arms
explicit; it does not change legacy readers which currently refuse some
of these values. A reified value is exactly:

```clojure
{:yin.k/tag :yin.k/frame :yin.k/registers R}
```

A parked reference is exactly:

```clojure
{:yin.k/tag :yin.k/frame :yin.k/parked-id P}
```

The latter refers to the same task's parked table; do not duplicate R
inside it. Authenticate source-owned continuation values, validate R in
captured-value context (section 7), and create a receiver-owned native
continuation. For a parked reference, reconstruct the engine's parked
value associated with P. A parked body derives its active value from
parked-id, not an extra result. Self/mutually referenced parked records
resolve through the explicit P table; other cycles without a declared
identity fail as non-canonicalizable. No new general object-identity or
heap serialization scheme is authorized. Existing unsupported mutable
heap-cell payloads remain non-portable.

The fixed-point census starts from waits, pending values/envelopes,
reachable parked records, guest store slice, free-env when present,
module-store associations, halted result and each install child. Traverse
both keys and values, every frame arm, closure captures and nested frame
values. Iterate module stores/code until no dependency is added. Resolve
Rule-R names from the source's own profile interpretation, never from the
receiver's guest state. Missing source dependencies refuse; a known
portable primitive unavailable in the receiver is unsatisfied.

Cells are task-local: encode aliasing as one C; distinct source cells
remain distinct even if positions equal. Assign IDs deterministically
before order-sensitive encoding, using the established canonical
ordering pass, not comparator side effects. Canonical encoded key order
orders maps and sets; it must not reorder waits, layouts, positional
frames, register pairs or continuation chains. Every declared cell must
be reached and every reached cell declared. Apply the same rule per
child. Pin roots needed by outstanding FFI/stream obligations according
to the existing heap/resource rules; serialization is not reclamation.

## 7. Eligibility and safepoint validation

### 7.1 Whole-task requirements

Only blocked, explicitly parked or halted tasks at the existing engine
export boundary are eligible. Running CESK/PC snapshots are not a fourth
kind. All ready queues are empty, all installs complete in the UCF sense,
and root/child state is stable. Refuse any root or child with an observe
wait, unapplied held observation, reachable unminted cursor, pending close,
or link request without its installed response cursor. These are D4-D9
holds, not occasions to invent a new pending variant or opcode.

A successful lift is checked by the version-aware checkpoint inspector
and complete restoration-grammar validator before it answers OK. A valid
code image alone proves neither a valid saved continuation nor a valid
task safepoint. Safepoint tables remain derived data, not stored flags.

### 7.2 PC profiles

For semantic, derive safepoints with the existing ucf/safepoints semantics.
For stack, the site is resume PC minus one, in the captured layout; the
same opcode-to-reason table below applies. For register, the carried
site-pc and PC satisfy section 5.3 and the existing r2 validator.

| Site opcode | Allowed pending reasons |
| --- | --- |
| `:stream-next` | next |
| `:stream-put` | put |
| `:ffi-call` | ffi, ffi-request |
| `:call` | next, put, ffi, ffi-request, link-request, link-response, install |
| `:park` | none; explicit parked record only |
| `:current-continuation` | none; captured-value record only |

Reason names in this table are the corresponding unqualified keywords.
Call permits dynamic effects; the pending must independently prove the
actual effect and all correlation/resource constraints. Do not infer an
FFI request merely from a call opcode. Parked-table entries in PC profiles
must name explicit park sites. FFI bookkeeping parked records are handled
by 7.3, not mislabeled as explicit parks. A captured-value R must name a
current-continuation site. Return-frame sites are non-tail calls.

For stack, the saved resume PC and every return PC are strictly inside
the captured code-space length. An admitted b2 image ends at a jump,
return or halt, not a suspending instruction; no valid park needs an
out-of-range resume fetch. The general row-at one-past rule does not
waive this eligibility check. Register's inclusive body ranges and
validator govern its PC endpoints. Semantic retains its existing strict
PC bound. All lexical/stack and
register invariants of section 5 remain mandatory in addition to the
table. No blanket assumption that every positive PC is a boundary.

### 7.3 Walker and FFI normalization

Walker eligibility is structural, not a claim of cryptographic execution
provenance. A live exporter obtains R only from an engine-created retained
wait or park and authenticates its values. A reader proves the closed
Kw grammar, all addressed dependencies and the pending's valid engine
state. It does not invent a site that the kernel never retained. The
absence of a PC is not grounds to refuse the walker.

Ordinary next/put/link/install waits carry the post-effect k/env that
the walker supplied to the engine. Stream evaluation frames may occur
deeper in Kw because an operand itself suspended. These nested frames
must not be mistaken for another outstanding pending.

For walker ffi-request, the top Kw arm is request-sent; its parked-id
matches pending.call-id, op matches the request envelope, and next/env
are the post-call continuation. The existing internal FFI parked record
with that same call ID has eval-call k, the same next/env and no required
call-id field. It is correlation bookkeeping, not an explicit vm/park.
For walker ffi, the top Kw is eval-call with call-id equal to the
pending call ID. The matching bookkeeping record, if the kernel still
holds it, is encoded in the parked table and checked for this exact
relationship. A parked record whose k is this wrapper is allowed only
when justified by one such live FFI pending. An active parked-id may
never select an FFI bookkeeping record. All other walker parked records
are explicit parks and use the ordinary closed R grammar.

Lower preserves these wrappers and parked IDs and uses the native
walker FFI transitions: request delivery becomes a response wait, and
response delivery validates/unpacks exactly once and removes bookkeeping.
Do not also install semantic/register request-sent behavior on top of
the walker wrappers. PC profiles retain their existing request-sent/
call-id transport reconstruction and restore seams. Portable correlation
IDs and request bytes are unchanged; no retry mints a new ID.

Gated cursor creation is still immediate with an unminted cell; D12
records its mint and applies it before export. Poll and close holds
stay outside this grammar. This is why r2 boundary-opcodes need not change.

## 8. Validation order and diagnostics

Validation is pure until step 6 below. Check the entire tree before
performing even one resource attachment.

1. Decode the canonical codec while retaining numeric classes. Check
   handoff tag and outer body version. An unreadable object is
   undecodable; an absent, non-integer or unsupported body version is
   profile-mismatch. A v0-only/v1-only reader refuses 2 here.
2. Traverse structurally identifiable install children in canonical
   module-name order. Check all body versions, then all profile maps
   against the supported registry. Only after these gates check that
   all versions are 2 and all profiles equal the root's. A malformed
   install container that cannot be traversed is undecodable; do not
   claim to have profile-checked a non-body. Every known child is gated
   before hashing code, attachment, proposal or restoration.
3. Validate closed body/custody/register/value/pending/layout shapes,
   canonical bytes and the requested body address, native code hashes,
   code validators, frame eligibility, marker/code agreement, complete
   dependency closure and nested install roles. Unsupported cursor or
   primitive requirements yield unsatisfied. No host execution probes
   are used to validate code or values.
4. For an exclusive root, run the existing occurrence/baseline/operation
   inspector and admission checks. An authority rejects fork bodies
   as custody checkpoints; it never adds missing policy/header keys.
   Completion accepts exclusive halted results only under their origin.
5. Check receiving composition protection/resource/primitive requirements
   and grant inputs. Unavailable evidence is not an empty enrollment or
   empty replay prefix. No runnable VM is returned on refusal.
6. Reconstruct isolated code spaces, fresh values, stores, resources and
   children. Apply the permitted gate/custody state and publish the
   machine only after every child succeeds. Do not run, poll, mint,
   append, close, initialize modules or consume a delivery during lower.

Profile mismatch includes `:yin.k/path`, the found version/contract and
the supported set. Structural errors use `:yin.k/undecodable` with the
exact path; bad content identities use `:yin.k/hash-mismatch` and identify
body or code key. Unsupported execution profile is never reported as a
hash error first. Lift refusals use existing non-portable kinds for
unaddressed code, noncanonical values and inconsistent state, with path
and explanatory data; introduce no new top-level outcome algebra.

Counter exhaustion retains the existing distinctions: incoming exhausted
baseline is inspectable but cannot allocate another operation; export
refuses an exhausted next-op-seq according to the existing lift rule.
Body versions and code PCs are not epochs. No new authority dedup keys,
lease facts or DaoStream outcome keys are added.

## 9. Lowering and isolation contract

Choose the receiver kernel from the validated profile and composition,
not from a guest module registry. A supplied receiver of another profile
is profile-mismatch, never a request to compile or translate. Clear guest
store, module stores, parked records, installs, wait/ready queues, closes,
issued state and kernel code layout before rebuilding. Retain only
composition-owned host module entries, named primitive/profile registry
and administrative resource/secret factories. No initialization reruns.

Restore waits in wire order; rebuild per-task issue stamps/counters as
in D10 rather than importing unrelated receiver history. Preserve carried
operation IDs and the root's exact next-op-seq; D11 assigns only genuinely
new operations. Replay starts from the existing zero-to-frontier prefix
contract; the snapshot does not invent an input record. FFI and link
apply paths must preserve request-to-response phase transitions.

For stack/register, the current layout owns code; captured prefix
layouts reconstruct continuation payloads, not new running code spaces.
Closure image-relative locations resolve against current offsets. For
walker, build the row-node index before materializing closures or Kw
frames. Use the receiver's authentic constructors; source owner objects
and receiver-local guest bindings cannot supply dependencies.

Restoration itself delivers no wait result. A test's controlled later
engine apply followed by normal scheduling is what makes execution
continue. An exclusive machine is runnable only after grant admission;
a fork is explicitly permitted by composition; a result has no work to
schedule. Installs carry their existing parent/phase and scheduler role.

## 10. Export recovery, integration and implementation boundary

D14's `:yin.k/export-recovery` version 1 is a separate format. It may
embed a version-2 body/snapshot once its reader dispatches to this grammar;
its outer tag/version does not become 2 merely because the embedded body
does. Extend its shared census and profile-aware administrative lower,
not an opaque Closure codec. All recovery/body binding, metadata,
descriptor, alias and byte-reproduction checks remain mandatory.

Rehydration leaves root and children exporting with waits outside runnable
queues; it installs no grant/custody and permits no abort back into
execution. A recovery object's enrolled declaration is preparation data,
not fresh admission evidence. Unsupported embedded profiles refuse before
attachment. Existing recovery fixtures with legacy embedded bodies stay
unchanged. Runtime layouts/hashes are reconstructed, not stashed as host
objects in the journal.

D10b-B must audit version tests in the exporter, handoff reader,
checkpoint inspector, grant/offer/completion and holder/recovery paths.
Replace semantic-only assumptions with pure profile dispatch only where
this amendment requires it. Do not replace `version == 1` with `>= 1`:
version 2 has forks and origin-only results, so dispatch by validated
version plus structural role. The inspector must understand the new R
arms without running a kernel. Code and manifest profile agreement still
holds for link/install responses.

Permitted implementation files include ucf, handoff, checkpoint,
holder/export, the four kernels and their code/continuation validators,
the required authority/holder version-dispatch integration and tests.
No instruction contract change is authorized. Engine seams may be exposed
for pure reconstruction/validation where required; they may not add a
new execution transition or weaken any gate. `code.cljc` remains the
semantic validator, not a union validator for all images.

## 11. Acceptance laws and required rows

Run the following on JVM, Node and Dart for every profile and both
version-2 modes unless the row explicitly concerns one profile. Each row
uses real source machines and a fresh receiver; refusal alone is not a
round trip. Use setup/action/assertion style. D16's gate includes these
rows and the whole existing D4-D6 zero-program-IO matrix.

1. **Profile/version gates.** Setup a valid body, then mutate its outer
   version, profile combination, child version or child profile; action
   lower with counting attachments; assert the specified refusal and
   zero attachments/execution. Bad profile plus bad code hash reports
   profile-mismatch. A fork to an exclusive-only receiver refuses.
2. **Blocked continuation.** Setup each applicable pending reason,
   including multiple ordered waits and an install child; lift/lower;
   deliver the controlled outcome and run. Assert result, effect trace
   and wait selection equal uninterrupted execution. Pin retained FFI
   writer then response reader and both linker phases separately.
3. **Explicit park and halt.** Setup park with other carried waits, and
   halt with a closure/cursor/module dependency only in the result;
   round trip; assert active park identity, complete census and correct
   resume/result. No module initialization runs on lower.
4. **Closure and frame values.** Capture closures in blocked environments,
   operand stacks, sparse registers, return frames and walker partially
   evaluated operands. Include owned reified and parked references.
   Round trip; assert fresh receiver ownership, behavior, aliasing and
   distinct cells. Literal maps shaped like markers remain data.
5. **Image growth.** Stack/register: attach A then B, capture, attach C,
   retain a prefix-layout continuation with cross-image return frames;
   restore into a receiver whose old layout is C,A. Assert source layout
   and historical prefixes are reconstructed, return trace agrees and
   no source aggregate hash is used as a component image identity.
   Include empty base, row-boundary validation and code hash corruption.
6. **Register validity.** Mutate live order, sparse pair index, destination,
   tail mode, site/return PC or body range. Assert pre-attachment refusal.
   Unchanged r2 images validate and keep the exact old register hash.
7. **Walker validity.** Exercise every Kw arm through source execution or
   a native continuation builder checked against its transition. Include
   suspension during operator, successive operands, if test, definition,
   stream operands, resume value and FFI operand evaluation. Mutate row
   identity, missing child, frame tag, evaluated length or FFI correlation;
   assert pre-attachment refusal. FFI bookkeeping is not explicit park.
8. **Custody.** Repeat D10's seven grant-consistency checks, protection in
   both directions, ID-on-unenrolled/enrolled-without-ID refusals, root
   counter restoration and child gates. Exclusive halt has no grant or
   runnable custody. No default receiver state supplies missing evidence.
9. **Export holds.** Setup each D4-D9 hold in root and child; attempt lift;
   assert refusal and unchanged fenced state. Never accept a cursor or
   poll pseudo-boundary to avoid the r2 live-operand constraint.
10. **Canonical bytes.** Use identical prepared data in each profile on
    all hosts, varying only map insertion order; assert exact body bytes,
    body address, code keys and cell IDs agree. Layout/wait order changes
    are not erased. Pin float64 integral content and negative zero in
    code/value positions before host execution conversion.
11. **Legacy and recovery.** Assert legacy version-0/1 and ledger pins
    unchanged from the post-D14 baseline. Freeze and fresh-process
    rehydrate a version-2 recovery snapshot; assert exact published bytes,
    complete registers/cells, exporting gates, zero program IO and refused
    restarted abort. Reject recovery bytes as a handoff body.
12. **Isolation.** Poison receiver guest/module stores, parked/install
    maps, closes/issued state and code layout. Restore with valid host
    modules/resources only; assert identical behavior and declarations.
    Unknown required primitive/resource is unsatisfied before execution.

D10b-A supplies the contract, not proof of D10b-B completion. D14/D15 may
continue on the semantic legacy path. D16 and stage-D completion remain
blocked until these successful four-profile lift/lower rows pass. The
existing non-semantic unaddressed-segment refusals remain honest temporary
pins until replaced by successful round trips. Stage E does not waive
these prerequisites.

## 12. Review record and explicit resolutions

- The exact profile strings come from `vm/*-contract`, not informal
  version numbers in document titles. Walker and semantic share the
  string v3 but differ by engine; neither is inferred from that string.
- Runtime register payloads contain sparse regs, not dense registers.
  Their return frames have no private code space; this grammar preserves
  both facts and the source layout's historical prefix.
- Walker AST frames contain dynamic values. Replacing them by a row
  address alone loses evaluated operands and the resolved operator.
- Walker FFI bookkeeping parks are distinct from explicit parks. Their
  closed correlation rule is necessary to cover the actual kernel.
- The UCF frame-value arms were previously underspecified and partly
  refused by the implementation. Section 6 fixes their version-2 shape;
  this is no promise that legacy lower accepts them.
- Fork/exclusive dispatch is structural. A version-2 halt may be a fork
  result or an origin-bearing exclusive result; children are never
  independently admitted subjects.
- D14's already-approved v1 dependency repair is preserved. No new
  legacy stamp, format or address migration is part of this amendment.
- All above are architect rulings for D10b-B. No owner decision remains
  open in this specification. Implementation defects against these rules
  are implementation work, not permission to omit a profile or frame arm.
